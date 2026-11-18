# ProxyUCI

[![CI](https://github.com/drewdev02/Proxy/actions/workflows/ci.yml/badge.svg)](https://github.com/drewdev02/Proxy/actions/workflows/ci.yml)

Una app que se autentica contra un proxy institucional (HTTP con **NTLMv2** o Basic, o **SOCKS5**) y enruta **todo el
tráfico del dispositivo** por él, mediante una VPN local (una interfaz TUN). Está escrita en Kotlin Multiplatform, con
un único núcleo común y adaptadores mínimos por plataforma, para **Android, macOS, Windows e iOS**. Es el equivalente
en Kotlin de Super Proxy (Android) y de su port propio para Windows en C#.

## Estado

| Plataforma | Qué funciona | Cómo se ha probado |
|---|---|---|
| **Android** | Perfiles con contraseña cifrada, «Probar proxy», túnel con DNS falso y navegación TCP | Emulador de Android |
| **macOS** (Apple Silicon) | Lo mismo, con una interfaz `utun` y un motor que corre como administrador | En un Mac, con el DNS de todos los dominios (ver abajo) |
| **Windows** (x64) | Lo mismo, con un adaptador Wintun y un motor elevado por UAC | En CI, con un adaptador Wintun real y una descarga de 5 MB por el proxy |
| **iOS** | Perfiles y «Probar proxy» | En el simulador. **El túnel aún no existe**: necesita una extensión de red, firma con cuenta de pago y un dispositivo real |

Todo se ha probado contra un proxy de pruebas propio ([`tools/test_proxy.py`](./tools/test_proxy.py), con NTLMv2, Basic y
SOCKS5). **Falta probarlo contra el proxy real de la UCI.**

Limitaciones conocidas:

- En macOS, con otra VPN de túnel completo activa (por ejemplo Tailscale con nodo de salida), el DNS de esa VPN gana al
  del túnel: se navega igual, pero los nombres los resuelve la otra VPN.
- En Windows falta comprobar el aviso de UAC con la ventana en una máquina con pantalla (CI solo ve el motor y la
  ventana por separado).
- Pendiente: actividad y registro de eventos, «Otro País» con OpenVPN, resistencia a cambios de red y el túnel de iOS.
  El detalle de cada capa está en [`ROADMAP.md`](./ROADMAP.md).

## Descargas

Cada versión se publica en [Releases](https://github.com/drewdev02/Proxy/releases) con tres archivos. La última es una
versión candidata (`v0.1.0-rc1`).

| Archivo | Plataforma | Al abrirlo |
|---|---|---|
| `ProxyUCI-X.Y.Z-android.apk` | Android 8.0 o superior | Permite instalar desde esa fuente; la app pide el permiso de VPN |
| `ProxyUCI-X.Y.Z-windows-x64.exe` | Windows 10/11 de 64 bits | Un solo archivo, sin instalar. Está **sin firmar**: SmartScreen puede avisar. Al conectar pide permisos de administrador |
| `ProxyUCI-X.Y.Z-macos-arm64.dmg` | macOS con Apple Silicon | Arrastra la app a Aplicaciones. Está **sin firmar ni notarizar**: la primera vez, clic derecho › Abrir. Al conectar pide la contraseña de administrador |

Para comprobar lo descargado (`SHA256SUMS.txt` va en la misma Release):

```sh
shasum -a 256 -c SHA256SUMS.txt
gh attestation verify ProxyUCI-0.1.0-rc1-android.apk -R drewdev02/Proxy   # salió de este repositorio
```

Cómo se publican las versiones, y cómo se configura la firma, está en [`PUBLICAR.md`](./PUBLICAR.md).

## Cómo está hecho

```
core/         Núcleo sin Compose: modelo, motor del túnel (pila TCP en espacio de usuario, DNS falso, relé),
              conectores NTLMv2 / Basic / SOCKS5, puertos y los adaptadores de cada plataforma
shared/       Interfaz en Compose Multiplatform y sus view models
androidApp/   App de Android (servicio de VPN)
desktopApp/   App de escritorio para macOS y Windows (JVM); el motor privilegiado es el mismo programa con --engine
iosApp/       App de iOS
tools/        Proxy de pruebas, y los scripts que empaquetan el .exe (windows-exe) y el .dmg (macos-dmg)
maestro/      Flujos de prueba de interfaz para Android e iOS
```

La lógica vive en común; cada plataforma solo abre la interfaz TUN, lee y escribe paquetes y arranca el túnel con el
mecanismo del sistema. Los puertos son interfaces del núcleo, y lo único `expect`/`actual` es el módulo de Koin de cada
plataforma. Todo va con inyección por constructor (Koin 4) y SOLID. El diseño completo está en
[`PLAN.md`](./PLAN.md) y el orden de entrega, por capas verticales, en [`ROADMAP.md`](./ROADMAP.md).

## Desarrollo

El proyecto usa el [Kotlin Toolchain](https://kotlin-toolchain.org/) (Amper). Los scripts `kotlin` (macOS/Linux) y
`kotlin.bat` (Windows) descargan la versión fijada la primera vez y aprovisionan su propio JDK: no hay que instalar nada.

```sh
./kotlin build                                  # compila todo
./kotlin test                                   # todas las pruebas
./kotlin test -m core --platform jvm            # solo las del núcleo, en la JVM
./kotlin run -m androidApp                      # emulador o dispositivo Android
./kotlin run -m desktopApp --no-compose-hot-reload
./kotlin run -m iosApp -p iosSimulatorArm64 -d <id del simulador>
```

Para probar el túnel sin el proxy de la UCI:

```sh
python3 tools/test_proxy.py --auth ntlm --port 3128 --upstream   # usuario andrey, contraseña secreta7
```

En el escritorio, conectar necesita privilegios (la interfaz de red solo la crea un administrador): la ventana lanza el
motor con el diálogo de contraseña de macOS o el aviso de UAC de Windows. Las pruebas de CI de cada commit están en
[`.github/workflows/ci.yml`](./.github/workflows/ci.yml).
