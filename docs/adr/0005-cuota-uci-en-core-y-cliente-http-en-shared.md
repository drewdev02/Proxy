# 0005. La cuota de la UCI: lógica en `core`, cliente HTTP en `shared`

Estado: aceptada
Fecha: 2026-11-22

## Contexto

La cuota de navegación de la UCI se consulta a un servicio SOAP de la intranet. Todo el código nació en `shared`, el módulo
de la interfaz: el cliente con Ktor, el almacén, el monitor que controla el túnel y la tarjeta. Pero casi nada de eso es
interfaz.

## Decisión

Pasan a `core`: `QuotaMonitor`, el formato SOAP (`QuotaSoap`), `UciQuota` y `QuotaAlert` (modelo), y los puertos
`UciQuotaService`, `QuotaStore` y `QuotaNotifier`, con `StoredQuotaStore` en `data`. En `shared` quedan:

- **`HttpUciQuotaService`**, el cliente HTTP con Ktor y su configuración de TLS por plataforma.
- **La tarjeta** (`QuotaCard`) y los textos de las notificaciones (`QuotaAlert.title()` y `message()`), que usan los
  recursos de idioma de Compose.

## Descartado

- **Llevar también el cliente HTTP a `core`:** `core` lo enlaza la extensión de iOS, que tiene un límite de memoria
  estricto y hoy no lleva `ktor-client`. Añadirlo solo por la cuota lo cargaría en la extensión, que no consulta cuotas.

## Consecuencias

- `QuotaMonitor` se prueba en `core` con dobles, sin Compose.
- La interfaz del servicio es un puerto, así que otro cliente (por ejemplo, uno que use el proxy) se enchufa sin tocar el
  monitor.
- Las funciones de formato de cifras (`mbText`, `wholePercent`) son públicas en `core` porque las usa la interfaz.
