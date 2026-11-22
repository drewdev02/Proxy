# 0002. El registro y el consumo guardados con Room, en un módulo aparte

Estado: aceptada
Fecha: decidida durante la capa 8 (actividad); ver el [ROADMAP](../../ROADMAP.md)

## Contexto

La pantalla «Registro» y la de «Consumo» necesitan datos que sobreviven al cierre de la app y que crecen. La extensión de
iOS que ejecutará el túnel tiene un límite de memoria estricto y no documentado: se reportan unos 50 MB desde iOS 15, con
informes contradictorios de 15 MB. Enlaza `core`.

## Decisión

Room con SQLite incluido (`sqlite-bundled`, igual en todas las plataformas), en un módulo `database` que **`core` no
conoce**. En `core/src/port` viven solo las interfaces (`LogHistory`, `TrafficHistory`); `database` las implementa
(`RoomLogHistory`, `RoomTrafficHistory`). Los secretos siguen en `SecretStore`.

## Descartado

- **Room dentro de `core`:** la extensión de iOS cargaría SQLite aunque no lo use.
- **Un archivo de texto propio:** habría que escribir consultas, recorte por antigüedad y sumas por día a mano.

## Consecuencias

- La extensión de iOS enlaza `core` sin Room ni Compose.
- Quien guarda lo que el motor produce (`LogRecorder`, `TrafficRecorder`) corre en el proceso de la interfaz; en escritorio
  el motor es otro proceso y le manda esos datos por el canal del motor.
- Pendiente: el CI no ejecuta las pruebas de `database`.
