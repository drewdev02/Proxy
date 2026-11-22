# Capa 5: navegación en macOS

[← Roadmap](../../ROADMAP.md)

> **Estado al 2026-11-16: el túnel funciona en macOS para el tráfico TCP y la captura de todo el DNS
> funciona cuando no hay otra VPN de túnel completo. Con otra VPN así conectada (Tailscale con nodo de
> salida), su DNS le gana al nuestro. Verificado en este Mac; falta el proxy real de la UCI.**
>
> Hecho y verificado en este Mac, como root mediante el diálogo de administrador:
> - La ventana lanza el motor con privilegios, este crea la interfaz `utun` y ambos se hablan por un socket
>   local con token. Al desconectar no quedan interfaz, rutas, proceso root ni archivo de token.
> - Prueba acotada (solo la dirección de `example.com`): HTTP y HTTPS devuelven `200` por el túnel y el
>   proxy registra los `CONNECT`; el resto de la red no se ve afectado.
> - Prueba completa (todo el IPv4 público, tope de 60 s): `example.com` por HTTP y HTTPS devuelve `200`, y
>   el proxy registra conexiones del sistema y de otras apps (`api.anthropic.com`, Google, Apple).
> - `./kotlin test` y `./kotlin build` pasan; hay una prueba de integración ventana-motor por socket real.
>
> **Captura de DNS.** Desviar al túnel las rutas hacia los servidores DNS no basta: en este Mac el
> resolvedor está ligado a una interfaz (`if_index`, la de Tailscale) y sus consultas ignoran la tabla de
> rutas. Por eso el motor publica con `scutil` un resolvedor propio en
> `State:/Network/Service/ProxyUCI/DNS` y lo retira al cerrar (y al arrancar, por si quedó de antes).
> - **Por dominio, verificado** (`--dns-domains sslip.io`): los nombres de ese dominio resuelven a
>   direcciones `10.A.1.x` del túnel, una descarga de 5 MB por nombre llega íntegra y el resto de nombres
>   sigue con el DNS real. Al desconectar la clave desaparece.
> - **Todos los dominios, verificado sin otra VPN** (Tailscale apagado): un nombre inventado y
>   `example.org` resuelven a direcciones `10.A.1.x`, `curl` conecta a esas direcciones, HTTPS a
>   `example.com` y `www.apple.com` devuelve `200` por el proxy y la descarga de 5 MB por nombre llega
>   íntegra. Al desconectar, la clave desaparece y el DNS real vuelve a responder.
> - **Con otra VPN de túnel completo, no captura.** Con Tailscale y nodo de salida, nuestro resolvedor
>   aparece el primero en `scutil --dns` y responde a `dig @10.A.0.2`, pero el sistema sigue preguntando a
>   Tailscale: los dos atienden «todos los dominios» y gana el de orden más bajo (Tailscale `100600`, el
>   nuestro `102200`). Ese orden lo calcula macOS según el rango del servicio de red; añadir `SearchOrder`
>   a la clave no lo cambia (probado). En ese caso el tráfico entra igual en el túnel por su IP y el nombre
>   se saca del SNI o de `Host`.
> - Pasada final de la prueba completa, sin Tailscale: DNS capturado, HTTPS a `example.com` y
>   `www.apple.com` y HTTP a `example.org` con `200`, descarga de 5 MB íntegra, 32 conexiones por el proxy
>   y ninguna rechazada; al desconectar no queda nada.
> - Los servidores DNS de la red **no** se desvían al túnel con rutas (antes sí): el resolvedor propio basta,
>   y en muchas redes ese servidor es la puerta de enlace.
> - Las pasadas anteriores daban «tiempo agotado» en algunas conexiones. Era el proxy de pruebas: hace su
>   propia consulta DNS por UDP y no reintentaba, y la red (un punto de acceso móvil) pierde paquetes.
>   `tools/test_proxy.py` ahora reintenta; con la red de pruebas conviene `--dns 1.1.1.1`.
>
> Otras cosas pendientes o dudosas:
> - Las peticiones a `www.apple.com` fallan a ratos en este Mac también sin el túnel (salida por Tailscale);
>   el fallo suelto visto en la prueba completa no es del túnel.
> - Al conectar y desconectar se cortan las conexiones TCP abiertas del equipo (la pila responde con RST a
>   las que no conoce); se reconectan solas.
> - Cada conexión pide la contraseña de administrador. (Al principio el motor root se ejecutaba desde las
>   carpetas de compilación; ahora se distribuye dentro de `ProxyUCI.app` en el `.dmg`, ver `PUBLICAR.md`.)
>
> Opciones de prueba del motor, por la variable `PROXYUCI_ENGINE_FLAGS`: `--max-seconds N`,
> `--no-dns-capture`, `--dns-domains a,b`, `--routes cidr,cidr`, `--log-file ruta`. El proxy de pruebas, en la misma máquina,
> necesita `--bind-interface <interfaz de salida>`.

**Resultado:** lo mismo que la capa 4 en el Mac: la app pide la contraseña de administrador, conecta y todo el
tráfico sale por el proxy.

| Parte | Trabajo |
|---|---|
| Interfaz | La misma; aviso de elevación de privilegios |
| Núcleo | Protocolo IPC entre ventana y motor (mensajes serializados) |
| Adaptadores | `UtunTunnel` por JNA, rutas y DNS del sistema con restauración, proceso `--engine` elevado, cliente IPC como `TunnelControl`/`TunnelMonitor` |
| Inyección | `platformModule` de `jvm` con selección por `PlatformInfo` |

**Validaciones que resuelve:** JNA dentro de `jvm/app` y apertura de `utun` como root.

**Pruebas:** la batería de contrato de `TunDevice` contra `UtunTunnel`.

**Demo:** `./kotlin run -m desktopApp`, conectar, navegar, desconectar; rutas y DNS quedan como estaban.
