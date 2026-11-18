# ProxyUCI

Se autentica contra un proxy institucional (HTTP con NTLMv2 o Basic, o SOCKS5) y manda todo el tráfico del dispositivo
por él, con una VPN local. Está hecho en Kotlin Multiplatform para Android, macOS, Windows e iOS.

Funciona en Android, macOS y Windows. En iOS solo están los perfiles y «Probar proxy»; el túnel falta. Todo está probado
contra un proxy de pruebas propio (`tools/test_proxy.py`), no contra el de la UCI.

## Descargas

En [Releases](https://github.com/drewdev02/Proxy/releases) hay un APK, un `.exe` para Windows de 64 bits y un `.dmg`
para Apple Silicon. El `.exe` y el `.dmg` no están firmados, así que Windows y macOS avisarán al abrirlos. En el
escritorio, conectar pide permisos de administrador.

## Compilar

```sh
./kotlin build
./kotlin test
./kotlin run -m desktopApp --no-compose-hot-reload
```

La primera vez descarga el toolchain y el JDK que necesita. Para Android e iOS: `./kotlin run -m androidApp` y
`./kotlin run -m iosApp -p iosSimulatorArm64 -d <simulador>`.

## Código

`core` es el motor, sin Compose; `shared` es la interfaz; `androidApp`, `desktopApp` e `iosApp` son los puntos de entrada.
El diseño está en [PLAN.md](PLAN.md), el estado por capas en [ROADMAP.md](ROADMAP.md) y cómo se publica una versión en
[PUBLICAR.md](PUBLICAR.md).
