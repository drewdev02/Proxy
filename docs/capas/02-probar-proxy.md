# Capa 2: probar proxy

[← Roadmap](../../ROADMAP.md)

> **Estado: terminada el 2026-11-16 en Android, iOS y macOS contra un proxy de pruebas; falta el proxy real de
> la UCI y probar en Windows.**
>
> Verificado:
> - `./kotlin test` pasa en los tres targets. MD4, MD5, HMAC-MD5 y NTLMv2 cumplen los vectores de las RFC
>   1320, 1321 y 2202 y de MS-NLMP 4.2.4, comprobados además con OpenSSL.
> - Con sockets reales, en emulador Android y simulador iOS (`maestro/probar-proxy-*.yaml`): NTLMv2 y Basic
>   con contraseña correcta y equivocada, y puerto sin servicio.
> - En JVM, la misma pila real contra el proxy de pruebas da los mismos cinco resultados.
> - En la app de escritorio en macOS, manejando la ventana por accesibilidad (`orca computer`, en segundo
>   plano): probar con NTLMv2 con contraseña correcta y equivocada.
>
> El proxy de pruebas es `tools/test_proxy.py`. Su verificación de NTLMv2 es independiente de la de la app y
> está contrastada con `curl --proxy-ntlm`.
>
> Pendiente:
> - **Proxy real de la UCI.** Sin acceso a esa red no se puede probar. El único punto dudoso es el dominio: si
>   el usuario no lo escribe, se usa el que anuncia el proxy en su desafío, mientras que `SuperProxyWin` usaba
>   "UCI" fijo. Si fallara, basta escribir el usuario como `UCI\usuario`.
> - Windows: no se ha ejecutado nada de esta capa allí.
> - SOCKS5 solo está probado contra un servidor simulado, no contra uno real.
>
> Hallazgos que cambiaron el diseño:
> - **Android 17 bloquea la red local.** Con `targetSdk` 37 la app necesita el permiso en ejecución
>   `ACCESS_LOCAL_NETWORK`; sin él la conexión se queda colgada hasta agotar el tiempo. Hay un puerto nuevo,
>   `LocalNetworkPermission`, que también hará falta antes de levantar el túnel (capa 3).
> - **Conectar bloquea el hilo.** En Android lanzaba `NetworkOnMainThreadException`; la conexión se hace en
>   el dispatcher de entrada/salida, que ahora aporta cada `platformModule` porque `Dispatchers.IO` no existe
>   en el código común.
> - **`ktor-network` funciona en iOS** dentro de la app. Dentro de la extensión de red sigue sin probar.

**Resultado:** el botón "Probar proxy" conecta con el proxy real y responde: correcto, credenciales rechazadas
(407), o proxy inalcanzable.

| Parte | Trabajo |
|---|---|
| Interfaz | Botón y resultado en el formulario |
| Núcleo | `ProxyConnector` (`HttpConnectConnector`, `Socks5Connector`), `ProxyAuthenticator` (Basic, NTLMv2), `Md4`, `Md5`, `HmacMd5`, `ProxyTester` |
| Adaptadores | Ninguno nuevo si `ktor-network` funciona en iOS; si no, puerto `SocketFactory` |
| Inyección | Registro de conectores y autenticadores en `coreModule` |

**Validaciones que resuelve:** sockets comunes con `ktor-network` en un iPhone real, y NTLMv2 contra el proxy
institucional.

**Pruebas:** vectores de NTLMv2 de MS-NLMP; un proxy falso en memoria que exige Basic y NTLM.

**Demo:** con credenciales buenas y malas, en las cuatro plataformas.
