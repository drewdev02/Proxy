// Package bridge conecta ProxyUCI con una red de Tailscale a nivel de paquetes IP.
//
// La app no le da a Tailscale una interfaz del sistema: le da un dispositivo virtual ([chanTun]) por el que entran los
// paquetes que el túnel de la app quiere sacar por el nodo de salida, y por el que salen las respuestas. Todo lo que
// Tailscale abre hacia fuera (servidor de control, relés DERP) va por un proxy HTTP que pone la app: el puente CONNECT
// local que a su vez sale por el proxy del perfil (con NTLM, nombres sslip.io, etc.). El UDP directo se desactiva:
// detrás de un proxy institucional no existe, y así ningún paquete sale por fuera del proxy.
package bridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/wireguard-go/tun"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/logtail"
	"tailscale.com/net/tshttpproxy"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

// MTU de la interfaz virtual: la de Tailscale por defecto, que cabe en cualquier camino.
const MTU = 1280

// QuadDNS es el resolvedor de Tailscale. Con un nodo de salida, reenvía las consultas a ese nodo: los nombres se
// resuelven en el país de salida.
const QuadDNS = "100.100.100.100"

// Config es lo que la app manda para abrir una sesión.
type Config struct {
	ControlURL string `json:"controlUrl"`
	AuthKey    string `json:"authKey"`
	Hostname   string `json:"hostname"`
	// StateDir guarda la identidad del nodo, para no registrarlo de nuevo en cada conexión.
	StateDir string `json:"stateDir"`
	// ExitNode es el nombre, el nombre DNS, la IP 100.x o el ID del nodo de salida. Vacío: el único que haya.
	ExitNode string `json:"exitNode"`
	// ProxyURL es el proxy HTTP por el que sale todo, por ejemplo http://token:x@127.0.0.1:41234.
	ProxyURL string `json:"proxyUrl"`
	// ListOnly abre la sesión solo para listar los nodos de salida, sin elegir ninguno.
	ListOnly bool `json:"listOnly"`
	// Ephemeral registra un nodo efímero, sin guardar nada en disco: la red lo olvida al desconectarse. Para listar
	// nodos sin dejar uno permanente por cada búsqueda.
	Ephemeral bool `json:"ephemeral"`
	// TempDir es dónde crear directorios temporales. En Android el de Go por defecto (/data/local/tmp) no se puede
	// escribir: la app da el suyo.
	TempDir   string `json:"tempDir"`
	TimeoutMs int    `json:"timeoutMs"`
	Verbose   bool   `json:"verbose"`
}

// Network es la red que la sesión le da al túnel.
type Network struct {
	Address string   `json:"address"`
	Prefix  int      `json:"prefix"`
	DNS     []string `json:"dns"`
	MTU     int      `json:"mtu"`
	// ExitNode es el nodo elegido, para mostrarlo.
	ExitNode *ExitNode `json:"exitNode,omitempty"`
}

// ExitNode es un nodo de la red que ofrece salida.
type ExitNode struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	DNSName     string `json:"dnsName"`
	IP          string `json:"ip"`
	Online      bool   `json:"online"`
	Country     string `json:"country,omitempty"`
	CountryCode string `json:"countryCode,omitempty"`
	City        string `json:"city,omitempty"`
}

// Códigos de fallo que la app traduce a sus motivos.
const (
	CodeAuth        = "AUTH_FAILED"
	CodeUnreachable = "CONTROL_UNREACHABLE"
	CodeNoExitNode  = "EXIT_NODE_NOT_FOUND"
	CodeConfig      = "CONFIG_INVALID"
	CodeInternal    = "INTERNAL"
)

// Error es un fallo con su código.
type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

func fail(code, format string, a ...any) error {
	return &Error{Code: code, Message: fmt.Sprintf(format, a...)}
}

// El proxy de cada proceso: Tailscale fija su función de proxy una vez, así que esta lee el valor actual.
var (
	proxyURL     atomic.Pointer[url.URL]
	setupOnce    sync.Once
	setupErr     error
	sessionMu    sync.Mutex
	sessionAlive bool
	// cancelStart corta el Start en curso (la app lo pide si el proxy rechaza sus credenciales o al desconectar).
	cancelStart context.CancelFunc
)

// CancelStart corta el arranque en curso, si lo hay: Start devuelve enseguida con CONTROL_UNREACHABLE.
func CancelStart() {
	sessionMu.Lock()
	defer sessionMu.Unlock()
	if cancelStart != nil {
		cancelStart()
	}
}

// Setup prepara Tailscale para este proceso. Tiene que correr antes de que nada de Tailscale lea la configuración de
// proxy (por eso también se llama al cargar la librería); después, la función de proxy ya no se puede cambiar.
func Setup() error {
	setupOnce.Do(func() {
		// Sin subir registros a log.tailscale.com: la app no los manda a ningún sitio.
		envknob.SetNoLogsNoSupport()
		logtail.Disable()
		// El log estándar de Go escribe en stderr (en Android, en logcat) cosas como la URL del proxy local con su token.
		log.SetOutput(io.Discard)
		// Solo DERP: el UDP directo no atraviesa el proxy, y además así ningún paquete sale por fuera de él.
		envknob.Setenv("TS_DEBUG_ALWAYS_USE_DERP", "true")
		// Sin abrir puertos en el router (UPnP, NAT-PMP, PCP): no tiene sentido detrás de un proxy.
		envknob.Setenv("TS_DISABLE_PORTMAPPER", "true")
		setupErr = tshttpproxy.SetProxyFunc(func(*url.URL) (*url.URL, error) {
			// Todo, incluso lo que va a direcciones locales, por el proxy de la app: un nil saldría directo.
			return proxyURL.Load(), nil
		})
	})
	return setupErr
}

var crashOnce sync.Once

// keepCrashReport hace que un fallo fatal de Go (un panic) quede escrito en dir/tsbridge-fallo.txt: en Android la
// salida de error del proceso no va a ninguna parte, y sin esto no se sabría por qué se cerró la app.
func keepCrashReport(dir string) {
	if dir == "" {
		return
	}
	crashOnce.Do(func() {
		f, err := os.OpenFile(filepath.Join(dir, "tsbridge-fallo.txt"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		if err != nil {
			return
		}
		_ = debug.SetCrashOutput(f, debug.CrashOptions{})
		f.Close()
	})
}

// UseProxy fija el proxy por el que sale todo lo de Tailscale en este proceso.
func UseProxy(u *url.URL) { proxyURL.Store(u) }

// nowhere es un proxy que no existe: cerrada la sesión, lo que quede de Tailscale no puede salir directo.
var nowhere = &url.URL{Scheme: "http", Host: "127.0.0.1:1"}

// Session es una sesión abierta.
type Session struct {
	srv      *tsnet.Server
	tun      *chanTun
	network  Network
	logf     func(string, ...any)
	tempDir  string
	stopOnly chan struct{}
}

// Start abre la sesión y espera a que el nodo de salida esté listo (salvo con ListOnly).
func Start(parent context.Context, cfg Config, logf func(string, ...any)) (*Session, error) {
	if cfg.ControlURL == "" || cfg.ProxyURL == "" || cfg.StateDir == "" && !cfg.Ephemeral {
		return nil, fail(CodeConfig, "faltan controlUrl, stateDir o proxyUrl")
	}
	pu, err := url.Parse(cfg.ProxyURL)
	if err != nil || pu.Host == "" {
		return nil, fail(CodeConfig, "proxyUrl no válida")
	}
	// Una sesión por proceso: la función de proxy y los ajustes de Tailscale son globales.
	sessionMu.Lock()
	if sessionAlive {
		sessionMu.Unlock()
		return nil, fail(CodeInternal, "ya hay una sesión de Tailscale abierta")
	}
	sessionAlive = true
	sessionMu.Unlock()
	ok := false
	defer func() {
		if !ok {
			sessionMu.Lock()
			sessionAlive = false
			sessionMu.Unlock()
		}
	}()

	keepCrashReport(cfg.TempDir)
	if os.Getenv("TSBRIDGE_VERBOSE") != "" {
		cfg.Verbose = true
	}
	if cfg.TempDir != "" && os.Getenv("TMPDIR") == "" {
		// Partes de Tailscale buscan dónde guardar cosas con os.TempDir(), que en Android es /data/local/tmp y no se
		// puede escribir (sin esto, entra en pánico con «no safe place found to store log state»).
		os.Setenv("TMPDIR", cfg.TempDir)
	}
	if err := Setup(); err != nil {
		// Sin la función de proxy, Tailscale saldría directo, saltándose el proxy del perfil: no se conecta.
		return nil, fail(CodeInternal, "no se pudo fijar el proxy de Tailscale: %v", err)
	}
	UseProxy(pu)

	timeout := time.Duration(cfg.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	sessionMu.Lock()
	cancelStart = cancel
	sessionMu.Unlock()
	defer func() {
		sessionMu.Lock()
		cancelStart = nil
		sessionMu.Unlock()
	}()

	tempDir := ""
	if cfg.Ephemeral {
		// tsnet necesita un directorio aunque no guarde el estado en él.
		dir, err := os.MkdirTemp(cfg.TempDir, "tsbridge-efimero")
		if err != nil {
			return nil, fail(CodeInternal, "no se pudo crear un directorio temporal: %v", err)
		}
		cfg.StateDir, tempDir = dir, dir
		defer func() {
			if !ok {
				os.RemoveAll(tempDir)
			}
		}()
	}
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		return nil, fail(CodeConfig, "no se pudo crear el directorio de estado: %v", err)
	}
	t := newChanTun()
	tsLogf := func(format string, a ...any) {
		if cfg.Verbose {
			logf("tailscale: "+format, a...)
		}
	}
	hostname := cfg.Hostname
	if hostname == "" {
		hostname = "proxyuci"
	}
	srv := &tsnet.Server{
		Dir:        cfg.StateDir,
		Hostname:   hostname,
		AuthKey:    cfg.AuthKey,
		ControlURL: cfg.ControlURL,
		Tun:        t,
		Logf:       tsLogf,
		UserLogf:   tsLogf,
	}
	if cfg.Ephemeral {
		srv.Ephemeral = true
		srv.Store = new(mem.Store)
	}
	s := &Session{srv: srv, tun: t, logf: logf, tempDir: tempDir}

	logf("Conectando con el servidor de control %s", cfg.ControlURL)
	status, err := srv.Up(ctx)
	if err != nil {
		srv.Close()
		return nil, classifyUp(ctx, err)
	}
	v4 := firstV4(status.TailscaleIPs)
	if !v4.IsValid() {
		srv.Close()
		return nil, fail(CodeInternal, "el nodo no recibió una dirección IPv4")
	}
	s.network = Network{Address: v4.String(), Prefix: 32, DNS: []string{QuadDNS}, MTU: MTU}
	s.keepOnlyTCP443()
	logf("Nodo en la red con la dirección %s", v4)

	if !cfg.ListOnly {
		node, err := s.useExitNode(ctx, cfg.ExitNode)
		if err != nil {
			srv.Close()
			return nil, err
		}
		s.network.ExitNode = node
	}
	ok = true
	return s, nil
}

func classifyUp(ctx context.Context, err error) error {
	msg := err.Error()
	lower := strings.ToLower(msg)
	switch {
	case strings.Contains(lower, "auth") && (strings.Contains(lower, "key") || strings.Contains(lower, "invalid") || strings.Contains(lower, "expired")):
		return fail(CodeAuth, "%s", msg)
	case strings.Contains(lower, "not authorized"), strings.Contains(lower, "unauthorized"):
		return fail(CodeAuth, "%s", msg)
	case errors.Is(err, context.DeadlineExceeded) || ctx.Err() != nil:
		return fail(CodeUnreachable, "el servidor de control no respondió a tiempo (%s)", msg)
	default:
		return fail(CodeUnreachable, "%s", msg)
	}
}

func firstV4(ips []netip.Addr) netip.Addr {
	for _, ip := range ips {
		if ip.Is4() {
			return ip
		}
	}
	return netip.Addr{}
}

// ExitNodes lista los nodos que ofrecen salida.
func (s *Session) ExitNodes(ctx context.Context) ([]ExitNode, error) {
	lc, err := s.srv.LocalClient()
	if err != nil {
		return nil, fail(CodeInternal, "%v", err)
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return nil, fail(CodeInternal, "%v", err)
	}
	return exitNodesOf(st), nil
}

func exitNodesOf(st *ipnstate.Status) []ExitNode {
	var nodes []ExitNode
	for _, p := range st.Peer {
		if !p.ExitNodeOption {
			continue
		}
		n := ExitNode{
			ID:      string(p.ID),
			Name:    p.HostName,
			DNSName: strings.TrimSuffix(p.DNSName, "."),
			Online:  p.Online,
		}
		if ip := firstV4(p.TailscaleIPs); ip.IsValid() {
			n.IP = ip.String()
		}
		if p.Location != nil {
			n.Country, n.CountryCode, n.City = p.Location.Country, p.Location.CountryCode, p.Location.City
		}
		nodes = append(nodes, n)
	}
	return nodes
}

func matches(n ExitNode, want string) bool {
	w := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(want), "."))
	return w == strings.ToLower(n.ID) || w == strings.ToLower(n.Name) || w == strings.ToLower(n.DNSName) || w == n.IP ||
		strings.HasPrefix(strings.ToLower(n.DNSName), w+".")
}

func (s *Session) useExitNode(ctx context.Context, want string) (*ExitNode, error) {
	lc, err := s.srv.LocalClient()
	if err != nil {
		return nil, fail(CodeInternal, "%v", err)
	}
	// La lista de nodos llega con el primer mapa de la red: se espera un poco si aún está vacía.
	var nodes []ExitNode
	for {
		st, err := lc.Status(ctx)
		if err != nil {
			return nil, fail(CodeInternal, "%v", err)
		}
		nodes = exitNodesOf(st)
		if len(nodes) > 0 || ctx.Err() != nil {
			break
		}
		select {
		case <-ctx.Done():
		case <-time.After(300 * time.Millisecond):
		}
	}
	var chosen *ExitNode
	for i := range nodes {
		if want == "" && len(nodes) == 1 || want != "" && matches(nodes[i], want) {
			chosen = &nodes[i]
			break
		}
	}
	if chosen == nil {
		names := make([]string, 0, len(nodes))
		for _, n := range nodes {
			names = append(names, n.Name)
		}
		if want == "" {
			return nil, fail(CodeNoExitNode, "elige un nodo de salida; hay %d: %s", len(nodes), strings.Join(names, ", "))
		}
		return nil, fail(CodeNoExitNode, "no hay un nodo de salida «%s»; hay: %s", want, strings.Join(names, ", "))
	}
	if _, err := lc.EditPrefs(ctx, &ipn.MaskedPrefs{
		Prefs:         ipn.Prefs{ExitNodeID: tailcfg.StableNodeID(chosen.ID)},
		ExitNodeIDSet: true,
	}); err != nil {
		return nil, fail(CodeInternal, "no se pudo elegir el nodo de salida: %v", err)
	}
	// Un ping por WireGuard confirma que el camino hasta el nodo funciona antes de mandar tráfico.
	if ip, err := netip.ParseAddr(chosen.IP); err == nil {
		pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		if _, err := lc.Ping(pctx, ip, tailcfg.PingTSMP); err != nil {
			s.logf("El nodo de salida %s no contestó al ping (%v); se sigue igualmente", chosen.Name, err)
		}
	}
	s.logf("Saliendo por el nodo %s (%s)", chosen.Name, chosen.IP)
	return chosen, nil
}

// keepOnlyTCP443 mantiene a Tailscale en su modo «solo TCP 443»: sin UDP ni STUN. Detrás del proxy es lo que hay, y sin
// él la comprobación de red, que no puede enviar nada, lo toma por un fallo y hace un «rebind» que corta el relé DERP en
// mitad de las transferencias (se vio: una descarga se quedaba parada). Normalmente lo concede el servidor de control con
// una capacidad del nodo, y Tailscale lo vuelve a fijar cada vez que llega un mapa de DERP: por eso se reafirma a menudo.
func (s *Session) keepOnlyTCP443() {
	ms, ok := s.srv.Sys().MagicSock.GetOK()
	if !ok {
		s.logf("No se pudo poner Tailscale en modo solo TCP 443")
		return
	}
	ms.SetOnlyTCP443(true)
	s.logf("Tailscale en modo solo TCP 443 (sin UDP)")
	s.stopOnly = make(chan struct{})
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-s.stopOnly:
				return
			case <-t.C:
				ms.SetOnlyTCP443(true)
			}
		}
	}()
}

// Network es la red de la sesión.
func (s *Session) Network() Network { return s.network }

// WritePacket entrega a Tailscale un paquete IP que la app quiere sacar.
func (s *Session) WritePacket(p []byte) error { return s.tun.inject(p) }

// ReadPacket espera un paquete IP de vuelta. Devuelve nil si pasa el tiempo, e io.EOF si la sesión terminó.
func (s *Session) ReadPacket(timeout time.Duration) ([]byte, error) { return s.tun.next(timeout) }

// Close cierra la sesión.
func (s *Session) Close() {
	if s.stopOnly != nil {
		close(s.stopOnly)
	}
	if in, out := s.tun.droppedIn.Load(), s.tun.droppedOut.Load(); in+out > 0 {
		s.logf("Paquetes descartados por cola llena: %d hacia Tailscale, %d hacia la app", in, out)
	}
	// Tailscale cierra la interfaz virtual al apagarse; cerrarla antes lo deja esperando un cerrojo para siempre.
	//
	// Con TS_DEBUG_ALWAYS_USE_DERP, Tailscale (v1.104) reemplaza en cada «rebind» su conexión UDP ficticia sin cerrar
	// la anterior, y el apagado espera para siempre a quien leía de ella. Por eso se espera un tiempo y, si no termina,
	// se abandona: el control y el DERP ya están cerrados a esas alturas, solo quedan unas gorutinas paradas.
	done := make(chan struct{})
	go func() {
		s.srv.Close()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		s.logf("Tailscale no terminó de cerrarse a tiempo; se da por cerrada la sesión")
	}
	s.tun.Close()
	if s.tempDir != "" {
		os.RemoveAll(s.tempDir)
	}
	UseProxy(nowhere)
	sessionMu.Lock()
	sessionAlive = false
	sessionMu.Unlock()
}

// chanTun es la interfaz virtual: dos colas de paquetes entre la app y Tailscale.
type chanTun struct {
	toTailscale chan []byte
	fromNet     chan []byte
	events      chan tun.Event
	closed      chan struct{}
	once        sync.Once
	// Paquetes descartados por cola llena, en cada sentido: si crecen, la app o Tailscale no dan abasto.
	droppedIn, droppedOut atomic.Int64
}

func newChanTun() *chanTun {
	t := &chanTun{
		toTailscale: make(chan []byte, 512),
		fromNet:     make(chan []byte, 512),
		events:      make(chan tun.Event, 1),
		closed:      make(chan struct{}),
	}
	t.events <- tun.EventUp
	return t
}

func (t *chanTun) inject(p []byte) error {
	pkt := append([]byte(nil), p...)
	select {
	case <-t.closed:
		return io.EOF
	case t.toTailscale <- pkt:
		return nil
	default:
		t.droppedIn.Add(1) // cola llena: se descarta, como haría una interfaz saturada
		return nil
	}
}

func (t *chanTun) next(timeout time.Duration) ([]byte, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case p := <-t.fromNet:
		return p, nil
	case <-t.closed:
		return nil, io.EOF
	case <-timer.C:
		return nil, nil
	}
}

func (t *chanTun) File() *os.File { return nil }

func (t *chanTun) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
	select {
	case <-t.closed:
		return 0, io.EOF
	case p := <-t.toTailscale:
		if len(slab) < len(p)+2*tun.ReadPacketSpacing {
			return 0, tun.ErrTooManySegments
		}
		n := copy(slab[tun.ReadPacketSpacing:], p)
		packets[0] = tun.ReadPacket{Offset: tun.ReadPacketSpacing, Size: n}
		return 1, nil
	}
}

func (t *chanTun) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		if len(b) <= offset {
			continue
		}
		pkt := append([]byte(nil), b[offset:]...)
		select {
		case <-t.closed:
			return 0, io.EOF
		case t.fromNet <- pkt:
		default: // la app no lee a tiempo: se descarta
			t.droppedOut.Add(1)
		}
	}
	return len(bufs), nil
}

func (t *chanTun) MTU() (int, error)        { return MTU, nil }
func (t *chanTun) Name() (string, error)    { return "proxyuci", nil }
func (t *chanTun) Events() <-chan tun.Event { return t.events }
func (t *chanTun) BatchSize() int           { return 1 }

func (t *chanTun) Close() error {
	t.once.Do(func() {
		close(t.closed)
		close(t.events)
	})
	return nil
}
