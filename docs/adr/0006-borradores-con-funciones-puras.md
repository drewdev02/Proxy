# 0006. El editor de perfil con borradores de funciones puras

Estado: aceptada
Fecha: 2026-11-22

## Contexto

`ProfileEditorViewModel` tenía 544 líneas: los datos del proxy, la prueba, la cuota, el guardado, las reglas de salida y
dos motores de «Otro País» (OpenVPN y Tailscale). La pantalla tenía 528 líneas con todas las secciones juntas.

## Decisión

Cada sección tiene un **borrador**, una clase de datos cuyos cambios son funciones puras que devuelven otro borrador:
`RoutingDraft` (`withRule`, `withRuleMoved`…), `VpnDraft` (`errors()`, `toExitAndSecrets()`, `withOvpnFile`,
`withSecrets`) y `TailscaleDraft` (`errors()`, `withSearchOutcome`). El `ViewModel` conserva **un solo** `StateFlow` y solo
decide cuándo llamarlas y qué más reinicia. La pantalla se parte por secciones (`ProxySections`, `CountryExitSection`,
`RoutingSection`).

## Descartado

- **Un `ViewModel` o un editor con estado propio por sección:** las secciones están acopladas. Encender «Otro País» cambia
  la salida de la regla nueva, y cualquier edición borra el aviso de guardado fallido y los errores de campo. Con estados
  separados habría que sincronizarlos a mano.

## Consecuencias

- Las reglas de cada sección se prueban sin `ViewModel` ni corrutinas (`RoutingDraftTest`, `CountryExitDraftTest`).
- La API pública del `ViewModel` no cambió, así que las pruebas anteriores del editor siguen valiendo tal cual.
- Hay matices de comportamiento que el `ViewModel` debe conservar: escribir en el campo de la regla nueva y añadir una regla
  inválida **no** borran el aviso de guardado fallido; el resto de cambios sí.
- El `ViewModel` bajó a unas 350 líneas, no a menos, porque sigue orquestando los efectos (la prueba del proxy, la
  búsqueda de nodos, el guardado).
