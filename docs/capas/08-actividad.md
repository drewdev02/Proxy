# Capa 8: actividad

[← Roadmap](../../ROADMAP.md)

> **Actualización (2026-11-22):** los contadores de tráfico ya están: tráfico en vivo en Conexión y la pantalla «Consumo»,
> con lo consumido guardado en Room (ver «Hecho desde la capa 12» en el [Roadmap](../../ROADMAP.md)). De esta capa solo queda
> pendiente el transporte de las estadísticas en iOS. El resto del documento está como estaba al terminar el registro.
>
> **Estado: el registro está hecho en las cuatro plataformas; faltan los contadores de tráfico.**
>
> Hecho:
> - **Pantalla «Registro» en vivo:** filtros por nivel, pausar y reanudar, limpiar y copiar. Cada plataforma envuelve
>   su registrador con `BufferedLogger`, que llena el búfer común `LogBuffer` (mensajes de hasta 4000 caracteres).
> - **Escritorio:** el motor elevado reenvía sus mensajes a la ventana con `LOG <json>` por el socket local.
> - **Registro guardado (Room):** el módulo `database` guarda los mensajes en SQLite (`LogRecorder` →
>   `RoomLogHistory`); al abrir la app se recupera lo anterior. Se guardan 7 días y como mucho 20 000 líneas, y
>   «Limpiar» borra también lo guardado. Va aparte de `core` para que la extensión de iOS no cargue SQLite. Probado
>   en JVM e iOS con la misma batería de contrato del DAO.
>
> Pendiente: contadores de bytes subidos y bajados (`TunnelMonitor` solo expone el estado) y el transporte de
> estadísticas en iOS (`sendProviderMessage`, con la capa 7).

**Resultado:** una pantalla muestra bytes subidos y bajados en vivo y un registro de eventos (conexiones, DNS,
errores), en las cuatro plataformas.

| Parte | Trabajo | Hecho |
|---|---|---|
| Interfaz | Pantalla «Registro» con filtros, pausa, limpiar y copiar | ✔ |
| Interfaz | Contadores de tráfico | |
| Núcleo | `LogBuffer`, `BufferedLogger`, `LogRecorder`, puerto `LogHistory` | ✔ |
| Núcleo | Estadísticas de tráfico desde motor y relé | |
| Almacenamiento | Módulo `database`: Room KMP con SQLite incluido (`RoomLogHistory`, `databaseModule`) | ✔ |
| Adaptadores | Mensajes `LOG` del motor a la ventana en escritorio | ✔ |
| Adaptadores | Estadísticas entre procesos: IPC en escritorio, `sendProviderMessage` en iOS | |

**Demo:** descargar un archivo y ver los contadores moverse en cada plataforma.
