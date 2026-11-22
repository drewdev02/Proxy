# 0007. Los módulos `coreTesting` y `desktopEngine`

Estado: aceptada
Fecha: 2026-11-22

## Contexto

El paquete `desktop` que `core` tenía para la JVM (unas 800 líneas) lo usa solo `desktopApp`: cómo la ventana lanza el motor con permisos de
administrador y habla con él por un socket local. Estando en `core`, el núcleo cargaba código que no es del núcleo. Sacarlo
obligaba a mover sus pruebas, y estas usan los dobles de `core/test/fakes`.

## Decisión

- **`desktopEngine`** (`jvm/lib`, paquete `org.uci.proxy.desktop`) contiene los lanzadores de macOS y Windows, el protocolo,
  la sesión del motor, el controlador de la ventana y `PrivateFile`. Lo usa `desktopApp`.
- **`coreTesting`** (`kmp/lib`) contiene los dobles de prueba de `core` y el soporte de las pruebas de Windows
  (`WindowsE2E`), como código normal del módulo. `core` y `desktopEngine` lo usan en sus pruebas.
- `WindowsAccounts` se queda en `core/src@jvm/platform/windows`: lo usa `WindowsProtectedDirectory`, y `core` no puede
  depender de un módulo que depende de él.

## Descartado

- **Duplicar los dobles en `desktopEngine/test`:** dos copias que se desincronizan.
- **Dejar `desktop/` en `core`:** funciona, pero mezcla el núcleo con el código de una sola app.
- **Usar test fixtures:** Amper no los tiene. Ni la documentación de Amper (consultada con Context7) ni la búsqueda en la web
  describen un mecanismo para compartir código de prueba entre módulos.

## Consecuencias

- Amper admite el ciclo `core` (en pruebas) → `coreTesting` → `core` sin quejarse.
- Los dobles de `coreTesting` no ven lo `internal` de `core`. `FakeHttpProxy`, que verifica NTLM con funciones internas, se
  quedó en `core/test/fakes`, y lo mismo valdrá para cualquier doble que las necesite.
- Un paquete puede repartirse entre módulos: `org.uci.proxy.core.fakes` está a la vez en `core/test` y en `coreTesting`.
- El CI prueba `desktopEngine` aparte, y el empaquetado del `.dmg` y del `.exe` lo incluye solo, porque toma el classpath
  de `desktopApp`.
