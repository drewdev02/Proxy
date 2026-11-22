# Capa 11: reglas de salida (split tunneling)

[← Roadmap](../../ROADMAP.md)

**Resultado:** cada perfil puede tener reglas que deciden por dónde sale cada conexión: por el proxy, por Otro País (la VPN
del perfil), directa por la red real, o bloqueada. Una regla es un dominio (con sus subdominios, o con `*`), una IP o rango
CIDR, o una app de Android. Gana la primera que coincide; lo demás toma la salida por defecto (proxy u Otro País). Sin
reglas, el perfil se comporta como antes: todo por el proxy, o todo por la VPN si tiene una.

| Salida | Dominio | IP o rango | App (Android) |
|---|---|---|---|
| Proxy | ✔ | ✔ | ✔ |
| Otro País | ✔ | ✔ | ✔ |
| Directo | ✔ si su dirección es interna (si no, por el proxy) | ✔ el rango no entra en la interfaz | ✔ la app queda fuera de la VPN |
| Bloquear | ✔ | ✔ | ✔ |

**Cómo funciona:** con reglas, la interfaz es la del modo directo (DNS falso y pila TCP propia) y, si alguna regla usa
Otro País, la VPN se abre a la vez. Las consultas DNS de nombres que van por Otro País se preguntan al DNS de la VPN, así
que la app recibe la IP real; las conexiones a esas IP salen por la VPN cambiando la dirección de origen (la de la interfaz
por la que asignó la VPN) y se deshace a la vuelta. Por la VPN va también UDP. Una app con regla de Otro País que recibió
una dirección inventada (el DNS no sabe qué app pregunta) se resuelve en ese momento por el DNS de la VPN y su conexión se
traduce en ambos sentidos; su primera conexión a cada sitio tarda un segundo más. Qué app abrió cada conexión lo dice
Android (`getConnectionOwnerUid`, Android 10 o más) al abrirse.

**La red local, sin proxy:** el proxy institucional no abre los sitios de su propia intranet (el de la UCI contesta 503
a `drst.uci.cu`), que sin la app se abren directamente porque su dirección es interna. Al conectar, antes de capturar el
DNS, se leen los servidores DNS y los dominios de búsqueda de la red real (`scutil` en macOS, PowerShell en Windows,
`LinkProperties` en Android). Los nombres de esos dominios (`*.uci.cu`), y los de reglas de dominio «Directo», se
preguntan a ese DNS; si la dirección es interna (10/8, 172.16/12, 192.168/16, 100.64/10), la app la recibe tal cual y su
conexión va directa, porque esas direcciones nunca entran en la interfaz. Si es pública, o cae en la subred del túnel,
sigue el camino de siempre. Solo se usan servidores DNS internos, así que la consulta tampoco entra en el túnel. Una regla
de dominio «Otro País» o «Bloquear» manda sobre el ajuste; una «Proxy» no (`*.uci.cu → Proxy` sigue abriendo directo lo
interno, porque el proxy no lo abre, y por el proxy lo público). Vale con reglas y sin
ellas, también con todo por Otro País. Lo controla el ajuste «Abrir la red local sin proxy», activo por defecto. Además,
mientras el túnel está activo WPAD (`wpad`, `wpad.*`) contesta «no existe»: así el navegador no encuentra el proxy de la
red ni lo usa por su cuenta, y deja de reintentarlo sin parar.

**Si Otro País se cae:** lo suyo sale por el proxy, se avisa (notificación en Android, aviso en la tarjeta del perfil en
todas; en escritorio el motor lo cuenta con `STATE connected vpn-down`) y se reintenta de 5 s a 60 s. No se reintenta con
credenciales o archivo malos.

| Parte | Trabajo | Hecho |
|---|---|---|
| Interfaz | Sección «Reglas de salida» en el editor del perfil; «Elegir app» con iconos y buscador en Android; aviso de Otro País caído | ✔ |
| Núcleo | `RoutingRules`, `RuleSet`, `SplitRouting` (`VpnLink`, `SplitDnsHandler`, `ExitRouterHandler`), VPN supervisada en `TunnelRunner` | ✔ |
| Red local | `LocalDnsSource` (`MacLocalDnsSource`, `WinLocalDnsSource`, `AndroidLocalDnsSource`), `KtorDnsTransport`, `LocalDnsPolicy`, `LocalDnsResolver`, `LocalDnsHandler`; ajuste `intranetDirect` | ✔ |
| Adaptadores | `AndroidConnectionOwner`, apps directas con `addDisallowedApplication`, `PackageManagerAppCatalog`, rangos directos fuera de las rutas | ✔ |
| Protocolo | `STATE connected vpn-down` entre el motor de escritorio y la ventana | ✔ |

**Pruebas:** unitarias de las reglas, del reparto (DNS por la VPN, traducción de ida y vuelta, bloqueo, rangos directos,
caída y vuelta de la VPN, reglas por app) y del editor. `SplitRoutingLiveTest` contra ProtonVPN real a través del proxy
(se activa con variables de entorno). A mano: Android en emulador (Chrome por Otro País vio la IP de Canadá mientras el
resto salía por el proxy) y macOS con la app de escritorio (`ifconfig.me` por Canadá, lo demás por el proxy, limpieza
correcta al desconectar). En CI, `WindowsSplitTunnelE2ETest` con Wintun real y una VPN de bucle local en el proceso.
Red local: `LocalDnsTunnelTest` (sin reglas, con reglas, todo por Otro País, ajuste apagado, regla que manda, DNS lento,
WPAD), `LocalDnsTest`, `KtorDnsTransportTest` (UDP real en bucle local), la lectura de `scutil` y de PowerShell con salidas
enlatadas, y `LocalDnsSourceLiveTest`, que lee el DNS real en los equipos de macOS y Windows de CI.

**Limitaciones:** con DNS seguro en el navegador (DoH) solo valen las reglas de app o IP; una app que guardó en caché una
dirección inventada sale por el proxy hasta volver a resolver (60 s como mucho).

> Pendiente: reglas por app en Windows y macOS, crear reglas desde el registro, plantillas («Streaming → Otro País»),
> salida directa para dominios con dirección pública (haría falta una ruta por destino), y que la subred del túnel evite
> los rangos que use la intranet.

---
