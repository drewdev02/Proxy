# Pantallas de la nueva interfaz

Capturas de las pantallas rediseñadas, con datos de ejemplo. Son las mismas imágenes que usan las pruebas de capturas
(`shared/test@jvm`), así que siempre muestran el aspecto actual: si una pantalla cambia sin querer, la prueba falla; si
cambia a propósito, se regeneran con:

```
UPDATE_SNAPSHOTS=1 ./kotlin test -m shared -p jvm
```

Están dibujadas en macOS a 412 dp de ancho (móvil) y, cuando aplica, a 1100 dp (escritorio y tableta).

## Conexión (inicio)

| Desconectado | Conectando | Conectado |
|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/connection/phone-disconnected.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-connecting.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-connected.png) |

| Otro País caído | Falló | Sin perfiles | Sin túnel (iOS) |
|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/connection/phone-vpn-down.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-failed.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-no-profiles.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-no-tunnel.png) |

Escritorio y tableta (dos columnas, barra lateral):

![](../../shared/test@jvm/snapshots/macos/connection/desktop-connected.png)

## Editar perfil

| Perfil nuevo con errores | OpenVPN y reglas de salida | Tailscale con nodos encontrados |
|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-new-with-errors.png) | ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-openvpn-rules.png) | ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-tailscale-nodes.png) |

## Catálogo de componentes

Todos los componentes `Uci` en todos sus estados (en la app: modo desarrollo, pestaña Perfiles → Catálogo).

| Colores y tipografía | Contenedores | Botones | Botón de conexión |
|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/catalog/foundations.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/containers.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/buttons.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/power.png) |

| Estado | Formularios | Listas | Navegación | Datos |
|---|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/catalog/status.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/forms.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/lists.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/navigation.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/data.png) |

## Pendientes de rediseño

Perfiles, Registros y Ajustes todavía tienen el aspecto anterior (con el tema oscuro ya aplicado).
