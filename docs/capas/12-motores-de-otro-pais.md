# Capa 12: motores de Otro País (OpenVPN y Tailscale)

[← Roadmap](../../ROADMAP.md)

**Resultado:** al activar «Salir por otro país» en un perfil se elige el motor: **OpenVPN** (lo de la capa 9) o
**Tailscale**: un nodo de salida de una red de Tailscale (o de un servidor Headscale), con una clave de autenticación. El
túnel, las reglas de salida y la vuelta al proxy si Otro País se cae funcionan igual con los dos.

**Diseño:** el túnel ya no sabe de OpenVPN. Habla con un `CountryEngine` que, a través del proxy del perfil, abre una
`VpnSession` de paquetes IP con su `VpnNetwork` (dirección, prefijo, DNS, MTU). `CountryEngines` reúne los de cada
plataforma; `ProxyDialer` es la forma común de llegar a un servidor por el proxy (nombres sslip.io, preferir rechazos
informativos, credenciales). El perfil guarda un `CountryExit` (`OpenVpn` o `Tailscale`) y sus secretos van al almacén
seguro (`CountryExitSecrets`). En el JSON sigue la clave `vpn`: los perfiles anteriores se leen como OpenVPN.

**Tailscale por dentro:** `tools/tsbridge` es un módulo de Go sobre `tsnet` con una interfaz virtual propia: la app le
entrega paquetes y recibe paquetes, sin interfaz del sistema. Se compila como librería compartida con solo JNI (la misma
para Android y la JVM de escritorio). Todo lo que Tailscale abre (control, relés DERP) va a un proxy CONNECT local de la
app (`LocalConnectProxy`, en 127.0.0.1 con un token de un solo uso) que sale por el proxy del perfil con NTLM y todo lo
demás. El UDP directo se desactiva (`TS_DEBUG_ALWAYS_USE_DERP` y el modo «solo TCP 443» de Tailscale, sin STUN): detrás
del proxy no existe, así nada sale por fuera, y la comprobación de red no provoca «rebinds» que corten el relé. No se
suben registros a Tailscale. La identidad del nodo se guarda en un directorio privado por perfil; la búsqueda de nodos
del editor usa un nodo efímero que la red olvida. En Android la librería va en `jniLibs`; en escritorio, en los recursos,
y se extrae como `wintun.dll` (carpeta nueva, huella comprobada, en el motor de Windows bajo `ProgramData`).

| Parte | Trabajo | Hecho |
|---|---|---|
| Interfaz | Selector de motor, campos de Tailscale (servidor de control, clave, nodo de salida, nombre) y «Buscar nodos de salida» | ✔ |
| Núcleo | `CountryEngine`, `CountryEngines`, `VpnNetwork`, `ProxyDialer`, `OpenVpnEngine`, `TailscaleEngine`, `LocalConnectProxy`, `CountryEngineCatalog` | ✔ |
| Adaptadores | `NativeTailscaleBridge` (JNI) y `tools/tsbridge` (Go): librería para macOS, Windows, Linux y Android arm64/x86_64 | ✔ |
| Protocolo | `EngineStart.countryExit` lleva motor, secretos y directorio de estado al motor de escritorio | ✔ |

**Pruebas:** en Go, una sesión abierta solo a través de un proxy con autenticación saca una conexión TCP por el nodo de
salida de una red de pruebas propia (`testcontrol` de Tailscale, un DERP con TLS y un nodo de salida), y una clave mala se
reconoce. En Kotlin, `TailscaleEngineLiveTest` hace lo mismo con la librería real y el proxy NTLM de pruebas: busca nodos,
conecta y completa una petición HTTP por el nodo de salida, descarga 3 MB íntegros (alrededor de 1 s) y, con la contraseña
del proxy mala, corta enseguida con `PROXY_AUTH_FAILED`. En CI corre obligatoria en Linux, macOS y Windows; `WindowsTailscaleE2ETest` descarga 5 MB por un
adaptador Wintun real saliendo por Tailscale; y el APK debe llevar las dos librerías de Android. Además, las pruebas del
editor, de la migración del JSON y de los secretos por motor. A mano, en el emulador de Android (16, arm64) con la red de
pruebas y el proxy NTLM del equipo: el perfil antiguo se leyó como OpenVPN, la búsqueda encontró el nodo, el perfil
conectó por Tailscale y Chrome recibió la página a través del nodo de salida, con reglas de salida activas. Esa prueba
destapó tres cosas propias de Android que ya están resueltas: sin netlink para leer las interfaces (se le da a Tailscale
una interfaz sintética), un temporal que no se puede escribir (la app pasa el suyo y `TMPDIR`) y un `panic` de Go que no
dejaba rastro (ahora queda en `tsbridge-fallo.txt`, en la caché de la app).

**Limitaciones:** detrás del proxy todo va por los relés DERP de Tailscale (TCP 443): funciona, con más latencia que una
conexión directa. Hace falta un nodo de salida (una máquina propia en el país, o el complemento de Mullvad de Tailscale).
Una sola sesión de Tailscale por proceso: en Android, buscar nodos con un túnel Tailscale activo no se puede. Tailscale
1.104 a veces no termina de apagarse con solo DERP (no cierra su conexión UDP ficticia al «rebind»): se da por cerrada a
los 5 s. La librería pesa unos 20 MB por plataforma.

> Pendiente: probarlo en la red de la UCI con un nodo de salida real, en Android y en escritorio; inicio de sesión
> interactivo (sin clave) desde el editor.

---
