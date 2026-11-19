//go:build android

package bridge

import (
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"
)

// Android no deja a las apps leer las interfaces de red por netlink (desde Android 11), y sin ellas Tailscale no
// arranca («route ip+net: netlinkrib: permission denied»). La app oficial se las pasa desde Java; aquí basta una interfaz
// sintética con la dirección de salida del equipo, como hace el paquete androidbin de Tailscale: todo va por el proxy y
// por DERP, así que Tailscale no necesita conocer la red local.
func init() {
	netmon.RegisterInterfaceGetter(syntheticInterfaces)
}

func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	// Marcar un destino UDP no envía nada: solo hace que el sistema elija la ruta y la dirección de origen.
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if len(addrs) == 0 {
		// Sin red, o sin ruta: una dirección privada cualquiera, para que Tailscale arranque y reintente.
		addrs = append(addrs, &net.IPNet{IP: net.IPv4(10, 0, 0, 2), Mask: net.CIDRMask(32, 32)})
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: "proxyuci0", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, target string) (netip.Addr, bool) {
	c, err := net.DialTimeout(network, target, time.Second)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ap, err := netip.ParseAddrPort(c.LocalAddr().String())
	if err != nil {
		return netip.Addr{}, false
	}
	return ap.Addr().Unmap(), true
}
