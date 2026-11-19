package bridge

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/drewdev02/proxyuci/tsbridge/testnet"
)

func TestMain(m *testing.M) {
	// Antes que nada de Tailscale: el nodo de salida de la red de prueba vive en este mismo proceso.
	if err := Setup(); err != nil {
		panic(err)
	}
	os.Exit(m.Run())
}

// connectProxy es un proxy HTTP mínimo que solo acepta CONNECT con usuario y contraseña, y cuenta a dónde conecta.
type connectProxy struct {
	ln      net.Listener
	auth    string
	mu      sync.Mutex
	targets []string
	refused atomic.Int64
}

func startProxy(t *testing.T, user, pass string) *connectProxy {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &connectProxy{ln: ln, auth: "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))}
	// La red de prueba vive en este proceso y usa la misma función de proxy: que salga también por este.
	UseProxy(&url.URL{Scheme: "http", User: url.UserPassword(user, pass), Host: ln.Addr().String()})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.serve(c)
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return p
}

func (p *connectProxy) serve(c net.Conn) {
	defer c.Close()
	br := bufio.NewReader(c)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if req.Header.Get("Proxy-Authorization") != p.auth {
		p.refused.Add(1)
		io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"x\"\r\nContent-Length: 0\r\n\r\n")
		return
	}
	if req.Method != http.MethodConnect {
		// Una petición HTTP normal (servidor de control http://): se lleva tal cual a su destino.
		host := req.URL.Host
		if req.URL.Port() == "" {
			host += ":80"
		}
		p.mu.Lock()
		p.targets = append(p.targets, host)
		p.mu.Unlock()
		up, err := net.DialTimeout("tcp", host, 10*time.Second)
		if err != nil {
			io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			return
		}
		defer up.Close()
		req.Header.Del("Proxy-Authorization")
		if err := req.WriteProxy(up); err != nil {
			return
		}
		go func() {
			if n := br.Buffered(); n > 0 {
				b, _ := br.Peek(n)
				up.Write(b)
			}
			io.Copy(up, c)
		}()
		io.Copy(c, up)
		return
	}
	p.mu.Lock()
	p.targets = append(p.targets, req.Host)
	p.mu.Unlock()
	up, err := net.DialTimeout("tcp", req.Host, 10*time.Second)
	if err != nil {
		io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
		return
	}
	defer up.Close()
	io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
	go func() {
		if n := br.Buffered(); n > 0 {
			b, _ := br.Peek(n)
			up.Write(b)
		}
		io.Copy(up, c)
	}()
	io.Copy(c, up)
}

func (p *connectProxy) seen() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.targets...)
}

func checksum(b []byte, initial uint32) uint16 {
	sum := initial
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + sum>>16
	}
	return ^uint16(sum)
}

// synPacket arma un SYN de TCP sobre IPv4.
func synPacket(src, dst netip.AddrPort) []byte { return tcpPacket(src, dst, 0x02, 1000, 0) }

// tcpPacket arma un segmento TCP sin datos sobre IPv4.
func tcpPacket(src, dst netip.AddrPort, flags byte, seq, ack uint32) []byte {
	p := make([]byte, 40)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:], 40)
	binary.BigEndian.PutUint16(p[4:], 1)
	p[8] = 64
	p[9] = 6
	s4, d4 := src.Addr().As4(), dst.Addr().As4()
	copy(p[12:], s4[:])
	copy(p[16:], d4[:])
	binary.BigEndian.PutUint16(p[10:], checksum(p[:20], 0))
	tcp := p[20:]
	binary.BigEndian.PutUint16(tcp[0:], src.Port())
	binary.BigEndian.PutUint16(tcp[2:], dst.Port())
	binary.BigEndian.PutUint32(tcp[4:], seq)
	binary.BigEndian.PutUint32(tcp[8:], ack)
	tcp[12] = 5 << 4
	tcp[13] = flags
	binary.BigEndian.PutUint16(tcp[14:], 65535)
	var pseudo uint32
	pseudo += uint32(binary.BigEndian.Uint16(s4[0:])) + uint32(binary.BigEndian.Uint16(s4[2:]))
	pseudo += uint32(binary.BigEndian.Uint16(d4[0:])) + uint32(binary.BigEndian.Uint16(d4[2:]))
	pseudo += 6 + 20
	binary.BigEndian.PutUint16(tcp[16:], checksum(tcp, pseudo))
	return p
}

// TestExitNodeThroughProxy: una sesión abierta solo a través de un proxy HTTP con autenticación saca un SYN por el
// nodo de salida y recibe el SYN-ACK del destino real.
func TestExitNodeThroughProxy(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	logf := t.Logf

	// El destino: un servidor TCP local. El nodo de salida lleva allí lo que va a 203.0.113.42.
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		for {
			c, err := target.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	proxy := startProxy(t, "token", "x")
	tn, err := testnet.Start(ctx, "127.0.0.1", target.Addr().String(), func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	defer tn.Close()

	s, err := Start(ctx, Config{
		ControlURL: tn.ControlURL,
		AuthKey:    tn.AuthKey,
		Hostname:   "cliente",
		StateDir:   t.TempDir(),
		ExitNode:   tn.ExitName,
		ProxyURL:   "http://token:x@" + proxy.ln.Addr().String(),
		TimeoutMs:  60000,
		Verbose:    os.Getenv("TS_VERBOSE") != "",
	}, logf)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	nw := s.Network()
	if nw.ExitNode == nil || nw.ExitNode.Name != tn.ExitName {
		t.Fatalf("nodo de salida: %+v", nw.ExitNode)
	}
	if !strings.HasPrefix(nw.Address, "100.") {
		t.Fatalf("dirección: %s", nw.Address)
	}

	src := netip.AddrPortFrom(netip.MustParseAddr(nw.Address), 40000)
	dst := netip.MustParseAddrPort("203.0.113.42:8080")
	deadline := time.Now().Add(30 * time.Second)
	got := false
	var theirSeq uint32
	for !got && time.Now().Before(deadline) {
		if err := s.WritePacket(synPacket(src, dst)); err != nil {
			t.Fatal(err)
		}
		for wait := time.Now().Add(2 * time.Second); time.Now().Before(wait); {
			p, err := s.ReadPacket(500 * time.Millisecond)
			if err != nil {
				t.Fatal(err)
			}
			if len(p) >= 40 && p[9] == 6 && p[20+13]&0x12 == 0x12 && binary.BigEndian.Uint16(p[20:]) == dst.Port() {
				got = true
				theirSeq = binary.BigEndian.Uint32(p[24:])
				break
			}
		}
	}
	if !got {
		t.Fatal("no llegó el SYN-ACK del destino a través del nodo de salida")
	}
	// El ACK completa la conexión: solo entonces el nodo de salida conecta con el destino real.
	if err := s.WritePacket(tcpPacket(src, dst, 0x10, 1001, theirSeq+1)); err != nil {
		t.Fatal(err)
	}
	for end := time.Now().Add(10 * time.Second); tn.Forwarded.Load() == 0 && time.Now().Before(end); {
		time.Sleep(100 * time.Millisecond)
	}
	if tn.Forwarded.Load() == 0 {
		t.Fatal("el nodo de salida no reenvió la conexión")
	}

	// Todo lo que salió de la sesión pasó por el proxy: el control y el DERP, y nada a log.tailscale.com.
	seen := proxy.seen()
	ctrl := strings.TrimPrefix(tn.ControlURL, "http://")
	var sawControl, sawLog bool
	for _, h := range seen {
		if h == ctrl {
			sawControl = true
		}
		if strings.Contains(h, "log.tailscale") {
			sawLog = true
		}
	}
	if !sawControl {
		t.Fatalf("el control no pasó por el proxy; el proxy vio %v", seen)
	}
	if sawLog {
		t.Fatalf("se intentó subir registros a Tailscale: %v", seen)
	}
	t.Logf("el proxy vio: %v", seen)

	nodes, err := s.ExitNodes(ctx)
	if err != nil || len(nodes) != 1 || nodes[0].Name != tn.ExitName {
		t.Fatalf("nodos de salida: %+v %v", nodes, err)
	}
}

func TestWrongAuthKeyIsReported(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	proxy := startProxy(t, "token", "x")
	tn, err := testnet.Start(ctx, "127.0.0.1", "", func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	defer tn.Close()
	_, err = Start(ctx, Config{
		ControlURL: tn.ControlURL, AuthKey: "tskey-auth-equivocada", StateDir: t.TempDir(),
		ProxyURL: "http://token:x@" + proxy.ln.Addr().String(), TimeoutMs: 30000,
	}, t.Logf)
	var e *Error
	if err == nil || !asError(err, &e) || e.Code != CodeAuth {
		t.Fatalf("se esperaba %s, llegó %v", CodeAuth, err)
	}
}

func asError(err error, target **Error) bool {
	e, ok := err.(*Error)
	if ok {
		*target = e
	}
	return ok
}
