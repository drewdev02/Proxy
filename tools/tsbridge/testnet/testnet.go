// Package testnet levanta una red de Tailscale completa en este equipo, para las pruebas: servidor de control
// (testcontrol, el de las pruebas de Tailscale), un relé DERP y un nodo de salida que reenvía las conexiones TCP a su
// destino real y hace de DNS de la red, como un nodo de salida de verdad. No hace falta cuenta ni conexión a Internet
// (sin Internet, el DNS contesta que el nombre no existe).
package testnet

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"sync/atomic"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/derp/derpserver"
	"tailscale.com/ipn"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/logtail"
	"tailscale.com/net/tsaddr"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/dnstype"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

// Net es la red levantada.
type Net struct {
	ControlURL string `json:"controlUrl"`
	AuthKey    string `json:"authKey"`
	ExitName   string `json:"exitNode"`
	ExitIP     string `json:"exitNodeIp"`
	// Forwarded cuenta las conexiones que el nodo de salida llevó a su destino.
	Forwarded atomic.Int64 `json:"-"`
	// Resolved cuenta las consultas DNS que contestó el nodo de salida.
	Resolved atomic.Int64 `json:"-"`

	control *http.Server
	derp    *httptest.Server
	exit    *tsnet.Server
	dir     string
}

// TestRange es el rango de documentación que hace de «Internet» en las pruebas: los nodos de salida rechazan los
// destinos privados, así que las pruebas apuntan aquí y el nodo de salida lo lleva a redirect.
var TestRange = netip.MustParsePrefix("203.0.113.0/24")

// Start levanta la red escuchando en ip. Las conexiones que lleguen al nodo de salida para [TestRange] se llevan a
// redirect (host:puerto de este equipo), si no está vacío; las demás, a su destino real.
func Start(ctx context.Context, ip, redirect string, logf logger.Logf) (*Net, error) {
	logtail.Disable()
	n := &Net{}
	ok := false
	defer func() {
		if !ok {
			n.Close()
		}
	}()

	// El relé DERP, con TLS (los clientes no aceptan otra cosa) y un certificado que aceptan por InsecureForTests.
	d := derpserver.New(key.NewNode(), logf)
	ln, err := net.Listen("tcp", net.JoinHostPort(ip, "0"))
	if err != nil {
		return nil, err
	}
	n.derp = httptest.NewUnstartedServer(derpserver.Handler(d))
	n.derp.Listener.Close()
	n.derp.Listener = ln
	n.derp.Config.ErrorLog = logger.StdLogger(logf)
	n.derp.Config.TLSNextProto = make(map[string]func(*http.Server, *tls.Conn, http.Handler))
	n.derp.StartTLS()
	derpMap := &tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
		1: {RegionID: 1, RegionCode: "test", RegionName: "Pruebas", Nodes: []*tailcfg.DERPNode{{
			Name: "t1", RegionID: 1, HostName: ip, IPv4: ip, IPv6: "none",
			DERPPort: ln.Addr().(*net.TCPAddr).Port, STUNPort: -1, InsecureForTests: true,
		}}},
	}}

	// El servidor de control.
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	n.AuthKey = "tskey-auth-test-" + hex.EncodeToString(buf)
	cln, err := net.Listen("tcp", net.JoinHostPort(ip, "0"))
	if err != nil {
		return nil, err
	}
	n.ControlURL = "http://" + cln.Addr().String()
	control := &testcontrol.Server{
		DERPMap:          derpMap,
		RequireAuthKey:   n.AuthKey,
		AllNodesSameUser: true,
		AllOnline:        true,
		ExplicitBaseURL:  n.ControlURL,
		Logf:             logf,
	}
	n.control = &http.Server{Handler: control}
	go n.control.Serve(cln)

	// El nodo de salida: lo que le llega para otras direcciones lo conecta a su destino desde este equipo.
	n.dir, err = os.MkdirTemp("", "tsnet-exit")
	if err != nil {
		return nil, err
	}
	n.ExitName = "salida-pruebas"
	n.exit = &tsnet.Server{
		Dir: n.dir, Hostname: n.ExitName, AuthKey: n.AuthKey, ControlURL: n.ControlURL,
		Ephemeral: true, Store: new(mem.Store), Logf: logf, UserLogf: logf,
	}
	n.exit.RegisterFallbackTCPHandler(func(src, dst netip.AddrPort) (func(net.Conn), bool) {
		return func(c net.Conn) {
			defer c.Close()
			target := dst.String()
			if redirect != "" && TestRange.Contains(dst.Addr()) {
				target = redirect
			}
			up, err := net.DialTimeout("tcp", target, 10*time.Second)
			if err != nil {
				logf("testnet: no se pudo conectar con %v: %v", dst, err)
				return
			}
			defer up.Close()
			n.Forwarded.Add(1)
			clientDone := make(chan struct{})
			go func() {
				io.Copy(up, c)
				close(clientDone)
			}()
			io.Copy(c, up)
			// Cerrar de golpe tiraría lo que aún está en el búfer de envío de la pila TCP del nodo (se vio: una descarga
			// se cortaba a mitad). Como un proxy de verdad: FIN, y esperar a que el cliente termine.
			if cw, ok := c.(interface{ CloseWrite() error }); ok {
				cw.CloseWrite()
			}
			select {
			case <-clientDone:
			case <-time.After(60 * time.Second):
			}
		}, true
	})
	upCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	st, err := n.exit.Up(upCtx)
	if err != nil {
		return nil, fmt.Errorf("el nodo de salida no arrancó: %w", err)
	}
	for _, a := range st.TailscaleIPs {
		if a.Is4() {
			n.ExitIP = a.String()
		}
	}
	lc, err := n.exit.LocalClient()
	if err != nil {
		return nil, err
	}
	if _, err := lc.EditPrefs(upCtx, &ipn.MaskedPrefs{
		Prefs:              ipn.Prefs{AdvertiseRoutes: tsaddr.ExitRoutes()},
		AdvertiseRoutesSet: true,
	}); err != nil {
		return nil, err
	}
	control.SetSubnetRoutes(st.Self.PublicKey, tsaddr.ExitRoutes())

	// El DNS de la red: el nodo de salida, como hace Tailscale con un nodo de salida real. Sin esto, MagicDNS
	// (100.100.100.100) no sabe resolver nombres públicos y las apps que salen por la red no consiguen la IP real.
	dns, err := n.exit.ListenPacket("udp", net.JoinHostPort(n.ExitIP, "53"))
	if err != nil {
		return nil, fmt.Errorf("el DNS del nodo de salida no arrancó: %w", err)
	}
	go n.serveDNS(dns, logf)
	control.DNSConfig = &tailcfg.DNSConfig{Resolvers: []*dnstype.Resolver{{Addr: n.ExitIP}}}

	ok = true
	return n, nil
}

// serveDNS contesta las consultas A resolviendo el nombre desde este equipo; lo demás (u otro tipo, o un nombre que
// no existe) se contesta sin respuestas, para que el cliente no espere.
func (n *Net) serveDNS(pc net.PacketConn, logf logger.Logf) {
	defer pc.Close()
	buf := make([]byte, 1500)
	for {
		size, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		query := append([]byte(nil), buf[:size]...)
		go func() {
			reply, err := n.answer(query)
			if err != nil {
				logf("testnet: consulta DNS ilegible: %v", err)
				return
			}
			n.Resolved.Add(1)
			pc.WriteTo(reply, from)
		}()
	}
}

func (n *Net) answer(query []byte) ([]byte, error) {
	var p dnsmessage.Parser
	header, err := p.Start(query)
	if err != nil {
		return nil, err
	}
	question, err := p.Question()
	if err != nil {
		return nil, err
	}
	var addresses []net.IP
	rcode := dnsmessage.RCodeSuccess
	if question.Type == dnsmessage.TypeA && question.Class == dnsmessage.ClassINET {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", question.Name.String())
		cancel()
		if err != nil {
			rcode = dnsmessage.RCodeNameError
		}
		addresses = ips
	}
	reply := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: header.ID, Response: true, RecursionDesired: header.RecursionDesired, RecursionAvailable: true, RCode: rcode})
	reply.EnableCompression()
	if err := reply.StartQuestions(); err != nil {
		return nil, err
	}
	if err := reply.Question(question); err != nil {
		return nil, err
	}
	if err := reply.StartAnswers(); err != nil {
		return nil, err
	}
	for _, ip := range addresses {
		if v4 := ip.To4(); v4 != nil {
			rr := dnsmessage.AResource{}
			copy(rr.A[:], v4)
			if err := reply.AResource(dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 60}, rr); err != nil {
				return nil, err
			}
		}
	}
	return reply.Finish()
}

// Close lo apaga todo.
func (n *Net) Close() {
	if n.exit != nil {
		// Con TS_DEBUG_ALWAYS_USE_DERP el apagado de Tailscale puede quedarse esperando (ver bridge.Session.Close).
		done := make(chan struct{})
		go func() {
			n.exit.Close()
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
	}
	if n.control != nil {
		n.control.Close()
	}
	if n.derp != nil {
		n.derp.Close()
	}
	if n.dir != "" {
		os.RemoveAll(n.dir)
	}
}

// LocalIP devuelve una dirección IPv4 de este equipo que no es de loopback, o 127.0.0.1 si no hay.
func LocalIP() string {
	ifaces, _ := net.Interfaces()
	for _, i := range ifaces {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				if v4 := ipn.IP.To4(); v4 != nil && !v4.IsLinkLocalUnicast() {
					return v4.String()
				}
			}
		}
	}
	return "127.0.0.1"
}
