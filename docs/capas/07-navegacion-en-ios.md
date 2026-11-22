# Capa 7: navegación en iOS

[← Roadmap](../../ROADMAP.md)

**Resultado:** lo mismo en un iPhone: iOS pide permiso para añadir la configuración VPN, conecta y las apps
navegan por el proxy.

| Parte | Trabajo |
|---|---|
| Interfaz | La misma |
| Núcleo | Sin cambios; revisión de límites de búfer y de conexiones para la extensión |
| Adaptadores | Target `PacketTunnel` con subclase Swift mínima, `IosPlatformTunnel` sobre `packetFlow`, `IosTunnelController` con `NETunnelProviderManager`, Keychain y App Group compartidos |
| Inyección | Koin propio en la extensión con `coreModule` y `platformModule`, sin Compose |

**Validaciones que resuelve:** soporte de Amper para el target de extensión, perfil de aprovisionamiento con
Network Extensions, y memoria de la extensión bajo carga.

**Demo:** navegación sostenida en un iPhone real sin que el sistema mate la extensión. No se puede demostrar en
el simulador.
