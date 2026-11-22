# Capa 1: perfil de proxy

[← Roadmap](../../ROADMAP.md)

> **Estado: terminada el 2026-11-16 en Android, iOS y macOS; falta Windows.** Verificado con flujos de
> Maestro en emulador Android y simulador iOS: crear un perfil, cerrar la app, reabrirla, editarlo (la
> contraseña vuelve del almacén seguro) y eliminarlo. En Android la contraseña queda cifrada en disco.
> `./kotlin test` pasa en los tres targets.
>
> En macOS se recorrió lo mismo en la app de escritorio, manejando la ventana por accesibilidad con
> `orca computer`: el perfil queda en `~/Library/Application Support/ProxyUCI/settings.properties` sin la
> contraseña, esta va al llavero, ambos sobreviven a reiniciar la app y se borran al eliminar el perfil.
>
> Pendiente: Windows. El cifrado DPAPI no se ha ejecutado nunca.
>
> El llavero de iOS no se puede probar desde `./kotlin test`: el ejecutable de pruebas no es una app y recibe
> `errSecMissingEntitlement`; y si se firma a mano con permisos de llavero, el simulador se niega a lanzarlo
> ("Security policy issue"). Se comprueba con la app instalada, mediante los flujos de `maestro/`:
>
> ```bash
> maestro --device <id> test maestro/perfil-android.yaml
> maestro --device <id> test maestro/perfil-ios.yaml
> ```
>
> Ambos borran los datos de la app al empezar.

**Resultado:** se puede crear un perfil (servidor, puerto, protocolo, usuario, contraseña), cerrarlo y
encontrarlo al volver a abrir la app.

| Parte | Trabajo |
|---|---|
| Interfaz | Formulario de perfil con validación y lista de perfiles |
| Núcleo | `ProxyProfile`, `ProfileRepository` (serializa a JSON); puertos `KeyValueStore` y `SecretStore` |
| Adaptadores | Keystore, Keychain, DPAPI y almacenamiento de preferencias de cada plataforma |
| Inyección | Enlaces de los dos almacenes en cada `platformModule` |

**Demo:** guardar un perfil, matar la app, reabrir y verlo. La contraseña no aparece en claro en disco.
