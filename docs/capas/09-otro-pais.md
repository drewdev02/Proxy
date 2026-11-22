# Capa 9: Otro País (macOS, Windows y Android)

[← Roadmap](../../ROADMAP.md)

**Resultado:** un perfil puede, opcionalmente, cargar un archivo `.ovpn` con las credenciales de su VPN; al
conectar, el tráfico sale por ese servidor y la IP pública pasa a ser la del país elegido, todo a través del proxy
institucional. Sin archivo, el perfil conecta directo como siempre.

**Decisión de diseño:** el cliente OpenVPN va **dentro de la app**, escrito en Kotlin, y no se lanza un `openvpn`
instalado aparte. Así no hay nada que instalar, ni procesos hijos que limpiar, ni DNS que restaurar, y el mismo código
sirve en Android, macOS y Windows (los tres son JVM). No hace falta un proxy local: el servidor VPN se alcanza con un
`CONNECT` al proxy, con los mismos conectores que el resto de la app.

| Parte | Trabajo | Hecho |
|---|---|---|
| Interfaz | Sección «Salir por otro país (VPN)» en el editor del perfil, con selector de archivo del sistema (oculta en iOS) | ✔ |
| Núcleo | `OvpnConfig` (lector del `.ovpn`), `OpenVpnClient` (`tls-crypt`, canal de control, AES-GCM), `OpenVpnExit` (hoy `OpenVpnEngine`, capa 12), camino VPN de `TunnelRunner` | ✔ |
| Adaptadores | `JcaOpenVpnCrypto` y `SslEngineTlsSession` (compartidos por escritorio y Android con el alias `jvmAndAndroid`), `AwtFilePicker`, selector de Android | ✔ |
| Almacenamiento | El `.ovpn` y la contraseña de la VPN, por perfil, en el almacén seguro de cada sistema | ✔ |

**Alcance del cliente:** OpenVPN sobre TCP, `tls-crypt`, usuario y contraseña, AES-GCM y servidores que empujan
`peer-id`. Se rechazan al cargar, con un mensaje claro, los perfiles solo UDP, con `tls-auth`, `tls-crypt-v2`,
certificado de cliente o compresión. No renegocia claves: si el servidor lo pide, la sesión termina y hay que reconectar.

**Pruebas:** contra el `openvpn` oficial (`tools/ovpn_test_server.sh`: certificados propios, TCP, `tls-crypt`), que corre
en CI en Linux y macOS; pruebas unitarias del lector, del PRF y de los canales; y el camino del túnel con una sesión
simulada. Verificado a mano en el emulador de Android con un proxy NTLM y un servidor de ProtonVPN real: la IP pública
vista desde el emulador fue la del servidor VPN (Canadá), distinta de la del equipo.

**Lo que enseñó la red de la UCI (rc4):** el Squid de la UCI respondió `403` a los `CONNECT` al servidor de ProtonVPN porque
el `.ovpn` lo da por IP literal. SuperProxy lo evita pidiendo siempre el destino por su nombre `sslip.io`; ahora ProxyUCI
también (en el modo directo y en el de VPN), prueba antes el puerto 443 y distingue «bloqueado por política» de
«inalcanzable». Mi proxy de pruebas no rechazaba IP literales, por eso no se vio antes.

**Servicios de nombres configurables (rc6 y rc7):** en Ajustes hay una lista de servicios que dan nombre a una IP pública
(sslip.io y nip.io de fábrica). Cada uno tiene su interruptor y su orden, y se pueden añadir servicios propios (con
validación de la plantilla y un aviso de confianza) y eliminarlos. Se prueban por orden, empezando por el que funcionó la
última vez; la IP tal cual queda como último recurso. Un servicio que el proxy rechaza tres veces seguidas se aparta cinco
minutos. Cada servicio muestra su último resultado («funcionó hace 2 min», «el proxy lo rechazó») y tiene un botón
«Probar» que le pide al proxy del perfil (el del túnel activo o el primero) una IP pública conocida por ese nombre. En
escritorio, el motor cuenta el resultado a la ventana con el mensaje `NAMESTAT`.

> Pendiente: comprobarlo en macOS y Windows reales con la red de la UCI.
