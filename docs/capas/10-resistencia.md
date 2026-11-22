# Capa 10: resistencia

[← Roadmap](../../ROADMAP.md)

**Resultado:** la conexión sobrevive a un cambio de Wi-Fi, y un cierre anómalo no deja el equipo sin red.

| Parte | Trabajo |
|---|---|
| Interfaz | Estados "Reconectando" y errores recuperables |
| Núcleo | Política de reconexión, límites de conexiones, apagado ordenado |
| Adaptadores | Aviso de cambio de red por plataforma; restauración de rutas y DNS al arrancar en escritorio |
| Inyección | Puerto `NetworkMonitor` y sus implementaciones |

**Demo:** cambiar de red con el túnel activo; matar el proceso del motor en escritorio y comprobar que la red
se recupera al reabrir la app.
