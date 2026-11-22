# Capa 4: navegación en Android

[← Roadmap](../../ROADMAP.md)

> **Estado: terminada el 2026-11-16 en el emulador de Android 17, contra el proxy de pruebas con NTLM y
> salida real a Internet.** Verificado:
> - `./kotlin test` (660 pruebas) y `./kotlin build` pasan en los tres targets. La pila TCP se prueba con
>   un cliente simulado y tiempo virtual: saludo, datos en ambos sentidos, segmentos duplicados, solapados y
>   desordenados, ventana cerrada, retransmisión, cierres, RST y desbordamiento de números de secuencia.
> - Chrome carga `https://example.com` por el túnel y el proxy registra `CONNECT example.com:443`
>   (`maestro/navegar-android.yaml`).
> - Desde la shell del dispositivo, una petición HTTP a `example.com` devuelve `200` pasando por el proxy.
> - Una descarga de 5 MB por el túnel llega con el mismo SHA-256 que el original; cinco a la vez, también.
> - El tráfico propio de Android (comprobación de conectividad, servicios de Google) sale por el proxy.
> - Al desconectar no quedan interfaz ni servicio, y no hay errores en el registro.
> - iOS (simulador) y macOS siguen funcionando igual; allí esta capa no añade nada visible.
>
> Rendimiento medido: 1,7 MB/s en la descarga de 5 MB dentro del emulador. Es modesto y esperable: la pila
> no usa escalado de ventana (tope de 64 KB por conexión) y protege todo su estado con un solo candado.
>
> Sin probar:
> - **El proxy real de la UCI** y un dispositivo físico.
> - **SOCKS5** como transporte del túnel (solo el conector, contra un servidor simulado).
> - Sesiones largas, cambios de red y uso sostenido de memoria.
>
> Diferencias con `SuperProxyWin` que quedan pendientes, porque afectan sobre todo a la UCI:
> - **HTTP en claro por el puerto 80.** Aquí todo va por `CONNECT`. `SuperProxyWin` reenviaba el puerto 80
>   como petición de proxy normal porque el Squid de la UCI rechaza `CONNECT` a ese puerto. Hasta
>   portarlo, las páginas `http://` fallarían allí. El proxy de pruebas sí acepta `CONNECT` al 80.
> - **Atajos para Telegram** (no inspeccionar sus IP, cortar rápido el puerto 80): no portados.
>
> Sí portado: el recurso a `sslip.io` para IP públicas. Al principio se usaba solo si el proxy rechazaba la IP
> escrita tal cual; desde la rc5 se pide siempre primero por nombre, como SuperProxy, porque el Squid de la UCI
> rechaza las IP literales (ver capa 9, «Servicios de nombres configurables»).
>
> Añadido después (rc3): el túnel contesta en local las comprobaciones de conectividad de Android, Windows y macOS
> (`ConnectivityChecks`): el proxy institucional suele bloquearlas (403, 503) y el sistema mostraba «sin Internet»
> aunque el tráfico pasara. Solo contesta si el proxy ha abierto conexiones hace poco, para no ocultar un proxy caído
> o una contraseña mala. Se desactiva en Ajustes.
>
> Cierre a medias: si una app cierra su envío antes de recibir la respuesta, el relé se lo transmite al
> proxy, como hacía `SuperProxyWin`. Funciona con el proxy de pruebas; falta ver cómo lo trata Squid.

**Resultado:** con el túnel activo, apps que no soportan proxy (mensajería, tiendas, navegador) funcionan a
través del proxy institucional.

| Parte | Trabajo |
|---|---|
| Interfaz | Mensajes de error del túnel (proxy rechaza, sin red) |
| Núcleo | `TunTcpStack` y `TunTcpConnection` (port de `TunTcpStack.cs`), `Relay`, sniffing de `Host`/SNI, alternativa `.sslip.io`, rechazo de UDP con ICMP |
| Adaptadores | Ninguno nuevo |
| Inyección | `TunTcpStackFactory` y el `PacketHandler` de TCP |

**Pruebas:** en JVM, un SYN inyectado en la TUN falsa llega a un proxy falso con NTLM y los datos vuelven
íntegros; cierre, retransmisión y reset.

**Demo:** navegar y descargar un archivo grande en el teléfono con el proxy real. Es el primer extremo a
extremo completo; a partir de aquí el núcleo ya no cambia para las demás plataformas.
