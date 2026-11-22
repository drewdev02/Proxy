# Capa 3: túnel con DNS en Android

[← Roadmap](../../ROADMAP.md)

> **Estado: terminada el 2026-11-16 en el emulador de Android 17.** Verificado:
> - `./kotlin test` (462 pruebas) y `./kotlin build` pasan en los tres targets.
> - Al pulsar «Conectar» Android pide autorizar la VPN, la app pasa a «Conectado», aparece la interfaz
>   `tun0` con dirección `10.A.0.1/32` y ruta `10.A.0.0/16`, y la notificación del servicio.
> - Desde fuera de la app (`adb shell ping -c 1 <nombre>`) cualquier nombre resuelve a una dirección
>   `10.A.1.x`, y el mismo nombre siempre a la misma. El registro muestra consultas de otras apps del sistema.
> - Al desconectar desaparecen la interfaz, la notificación y el servicio, y la resolución vuelve a ser la
>   normal. Conectar de nuevo funciona.
>
> Flujos: `maestro/tunel-android.yaml` y `maestro/tunel-android-desconectar.yaml`. El emulador tiene que
> tener la pantalla encendida (`adb shell input keyevent KEYCODE_WAKEUP`).
>
> Límites de esta capa, a propósito:
> - **Solo responde DNS.** El tráfico TCP todavía no entra en el túnel: solo se enruta la subred virtual.
>   Con el túnel activo las apps resuelven a direcciones inventadas y no pueden conectar; es lo esperado
>   hasta la capa 4.
> - En iOS y escritorio no hay túnel: `TunnelControl.isAvailable` es falso y la lista no muestra «Conectar».
>   Comprobado a la vista en macOS (por accesibilidad) y en el simulador de iOS: solo aparecen «Editar» y
>   «Eliminar». El botón llegará a macOS en la capa 5 y a iOS en la 7.
> - Sin probar: revocar la VPN desde los ajustes del sistema, y un dispositivo real.
>
> Dos fallos de capas anteriores que salieron al probar esta en macOS e iOS, ya corregidos:
> - **El editor se cerraba solo tras guardar** (capa 1). El view model del formulario se reutiliza y
>   conservaba la marca de «guardado», así que la siguiente apertura se cerraba al instante. Los flujos de
>   Maestro no lo veían porque reiniciaban la app entre guardar y editar; ahora editan justo después.
> - **«Guardar» quedaba bajo el teclado en iOS** (capa 2, al añadir «Probar proxy»). El formulario respeta
>   ahora el espacio del teclado y se puede desplazar hasta el botón.
>
> Decisiones tomadas al implementar:
> - El servicio en primer plano usa el tipo `systemExempted`, el que Android reserva para apps de VPN.
> - La propia app queda fuera del túnel (`addDisallowedApplication`), para que sus conexiones al proxy
>   salgan por la red real sin tener que proteger cada socket.
> - El servicio de VPN vive en `androidApp` y solo traduce órdenes; la lógica está en `TunnelRunner`, común.
> - Lo que solo necesita un proceso que ejecuta túneles está en un módulo de Koin aparte, `tunnelModule`.

**Resultado:** al pulsar "Conectar" Android pide permiso de VPN, aparece la llave en la barra de estado, la app
muestra "Conectado" y un `nslookup` desde otra app devuelve una IP de la subred virtual.

| Parte | Trabajo |
|---|---|
| Interfaz | Botón Conectar/Desconectar y estado, con `TunnelControl` y `TunnelMonitor` |
| Núcleo | `packet` (IPv4, UDP, checksums), `FakeDnsTable`, `DnsResponder`, `TunnelEngine` con `PacketHandler` de DNS, `TunnelSessionFactory` |
| Adaptadores | `AndroidPlatformTunnel` (`VpnService.Builder`), `ProxyVpnService` en primer plano, `AndroidTunnelController` |
| Inyección | Enlaces de túnel y control en el `platformModule` de Android |

**Pruebas:** batería de contrato de `TunDevice` con el doble en memoria; DNS falso de ida y vuelta.

**Demo:** conectar, consultar un nombre, desconectar; la red vuelve a su estado normal.
