# Capa 0: esqueleto andante

[← Roadmap](../../ROADMAP.md)

> **Estado: terminada el 2026-11-16**, salvo la comprobación en Windows. Verificado: `./kotlin build` y
> `./kotlin test` pasan en JVM, Android e iOS (simulador); la app muestra su plataforma en este Mac, en un
> emulador Android y en un simulador de iPhone. Pendiente: abrirla en un PC con Windows.
>
> Para ejecutar: `./kotlin run -m desktopApp --no-compose-hot-reload` (sin esa opción descarga un runtime de
> 240 MB para la recarga en caliente), `./kotlin run -m androidApp -d <id>` y
> `./kotlin run -m iosApp -p iosSimulatorArm64 -d <id>`.

**Resultado:** la app arranca en las cuatro plataformas y muestra "ProxyUCI en \<plataforma\> \<versión\>",
dato que llega por inyección desde el adaptador de cada una.

| Parte | Trabajo |
|---|---|
| Interfaz | Pantalla inicial con un view model que recibe `PlatformInfo` |
| Núcleo | Módulo `core` nuevo; puertos `PlatformInfo` y `Logger` |
| Adaptadores | `AndroidPlatformInfo`, `IosPlatformInfo`, `JvmPlatformInfo` y un `Logger` por plataforma |
| Inyección | `coreModule`, `expect val platformModule`, `uiModule`; arranque de Koin en cada app |

**Validaciones que resuelve:** Koin y el catálogo de dependencias compilan con Amper en `jvm`, `android`,
`iosArm64` e `iosSimulatorArm64`.

**Demo:** captura de la misma pantalla en teléfono Android, iPhone, Mac y PC.
