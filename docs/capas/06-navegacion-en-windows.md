# Capa 6: navegación en Windows

[← Roadmap](../../ROADMAP.md)

**Resultado:** lo mismo en Windows, igualando al `SuperProxyWin.exe` actual en modo Directo, y entregado como
**un solo `.exe`** que se autoextrae (JRE recortado, JAR y `wintun.dll` comprimidos dentro; la primera vez
descomprime en una carpeta de caché y las siguientes arranca directo).

> **Estado al 2026-11-17: el túnel funciona en un Windows real (ejecutor de GitHub Actions) con el adaptador
> Wintun de verdad, y hay un `ProxyUCI.exe` de un solo archivo (74,9 MB) que CI construye, abre y usa como
> motor del túnel. Falta probar la app completa con el aviso de UAC en una máquina con pantalla.**
>
> **El `.exe`** (`tools/windows-exe/`, trabajo «ProxyUCI.exe (Windows)» de CI, artefacto `ProxyUCI-windows-x64`):
> un arranque en C# (compilado con el `csc.exe` de Windows) lleva dentro, comprimidos, una Java 25 recortada con
> `jlink` (módulos: `java.base`, `java.desktop`, `java.instrument`, `java.logging`, `java.management`,
> `java.naming`, `jdk.unsupported`, `jdk.crypto.ec`) y las 79 librerías de la app. La primera ejecución extrae
> (unos 3 s) y las siguientes arrancan en menos de 1 s. Un proceso elevado extrae y ejecuta desde
> `%ProgramFiles%\ProxyUCI` y uno normal desde `%LOCALAPPDATA%\ProxyUCI`, sin que variables de entorno o argumentos
> lo cambien, para que el motor con privilegios no ejecute nada que un usuario normal pueda sustituir. CI comprueba:
> `--self-test` (grafo del motor, JNA y `wintun.dll` verificada, dos veces), `--ready-file` (la ventana de Compose
> se compone, con dibujado por software) y el túnel real con el `.exe` como motor lanzado por el controlador de la
> app (descarga de 5 MB íntegra por el proxy NTLM y adaptador retirado al desconectar).
>
> **Fallo de Amper que esto destapó:** el jar ejecutable (`-f executable-jar`) guarda las librerías por nombre de
> archivo en una carpeta plana, y ocho se llaman igual en grupos distintos (`runtime-desktop` de `androidx`, de
> 1,9 MB, queda pisado por el stub de 4,8 KB de `org.jetbrains.compose`); sin `Composer` la ventana no abre. Por
> eso el script no lo usa: toma de `./kotlin run` el classpath exacto (las rutas completas son únicas), copia cada
> librería con prefijo numérico y las enlaza en orden desde un `app.jar` de manifiesto.
>
> Verificado en CI (`WindowsTunnelE2ETest`, como Administrador, rutas acotadas a `192.0.2.0/24`): se extrae y
> carga `wintun.dll`; `netsh` da al adaptador `ProxyUCI` su IP /16, su DNS y la métrica 1; las rutas «en
> vínculo» (`nexthop=0.0.0.0`) valen; Windows resuelve un nombre inventado a una dirección falsa del túnel; una
> descarga de 5 MB por el proxy NTLM llega íntegra; al parar, el adaptador desaparece. La prueba encontró un
> fallo real que las pruebas simuladas no veían: el archivo del token usaba permisos POSIX (ya corregido con una
> lista de acceso).
>
> A vigilar: tras conectar, Windows tarda unos segundos (4 o 5 intentos de un segundo en CI) en empezar a usar el
> DNS del adaptador; una app que consulte justo al conectar puede fallar un instante. Los procesos que un paso
> de CI deja en segundo plano mueren al acabar el paso, por eso la prueba arranca sus servidores en su mismo paso.
>
> Hecho (259 pruebas del núcleo en JVM pasan en macOS; `desktopApp` compila):
> - `WinNetwork` (`netsh` y `route`), `WinPlatformTunnel`, `JnaWintunOpener` y el extractor de la DLL con
>   comprobación de SHA-256, más el lanzador `WindowsElevatedEngineLauncher` (PowerShell `RunAs`, que pide
>   el aviso de UAC y espera al motor). `EngineMain` y `DesktopAppModule` eligen según el sistema.
> - Se sacaron a código común `CommandRunner` y `PollingTunDevice`, que ahora usan macOS y Windows.
> - `wintun.dll` 0.14.1 (amd64 y arm64) del ZIP oficial de wintun.net, con su licencia, como recurso del núcleo.
>   La licencia solo permite redistribuirla sin modificar y usándola por su API (`wintun.h`).
> - `.github/workflows/ci.yml`: pruebas del núcleo en Linux, macOS y Windows, y compilación de escritorio en Windows.
>
> Sin verificar (CI no puede): el aviso de UAC con `WindowsElevatedEngineLauncher` (PowerShell `RunAs` sobre el
> `.exe`; en CI el ejecutor ya es Administrador y el motor se lanza directo), la carpeta de un usuario normal
> (`%LOCALAPPDATA%`; el ejecutor siempre corre elevado, así que solo se usa `%ProgramFiles%`), un clic en la ventana,
> el dibujado con tarjeta gráfica (CI usa el de software), Windows en ARM64 (el `.exe` es solo x64), el
> comportamiento con otras VPN o con el DNS de una red real, y el enrutado de todo el IPv4 público (CI lo acota para
> no cortar al ejecutor). El `.exe` no está firmado: SmartScreen y los antivirus pueden avisar.
>
> Pendiente: probar la app completa en la VM de Parallels (ventana + UAC); firmar el `.exe`; endurecer
> la carpeta donde el motor elevado extrae la DLL (hoy: carpeta nueva de nombre aleatorio en `ProgramData` y
> huella verificada antes de cargar); avisar a Amper del choque de nombres del jar ejecutable.

| Parte | Trabajo |
|---|---|
| Interfaz | La misma |
| Núcleo | Sin cambios |
| Adaptadores | `WintunTunnel` por JNA, rutas con `netsh`/`route`, elevación por UAC, `wintun.dll` empaquetado |
| Inyección | Rama de Windows en el `platformModule` de `jvm` |

**Pruebas:** la batería de contrato de `TunDevice` contra `WintunTunnel`.

**Demo:** conectar y navegar en un PC con el proxy real.
