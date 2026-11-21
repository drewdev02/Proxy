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

| Otro País caído | Sitios que salen por el proxy | Falló | Sin perfiles | Sin túnel (iOS) |
|---|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/connection/phone-vpn-down.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-via-proxy.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-failed.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-no-profiles.png) | ![](../../shared/test@jvm/snapshots/macos/connection/phone-no-tunnel.png) |

Escritorio y tableta (dos columnas, barra lateral):

![](../../shared/test@jvm/snapshots/macos/connection/desktop-connected.png)

En la app de macOS, la barra de título es transparente y la app ocupa toda la ventana: los botones de cerrar,
minimizar y ampliar quedan sobre el fondo de la app (captura real de la ventana, no de las pruebas). En Windows 11 la
barra se pinta del color del fondo.

![](escritorio/macos-conexion.png)

## Perfiles

| Con un perfil conectado | Sin perfiles | Con fallos (túnel y borrado) |
|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/profile-list/phone-connected.png) | ![](../../shared/test@jvm/snapshots/macos/profile-list/phone-empty.png) | ![](../../shared/test@jvm/snapshots/macos/profile-list/phone-errors.png) |

## Editar perfil

| Perfil nuevo con errores | OpenVPN y reglas de salida | Reglas sin Otro País | Tailscale con nodos encontrados |
|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-new-with-errors.png) | ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-openvpn-rules.png) | ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-rules-without-country.png) | ![](../../shared/test@jvm/snapshots/macos/profile-editor/phone-tailscale-nodes.png) |

## Registro

| En vivo | En pausa, solo avisos y errores | Vacío |
|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/logs/phone-live.png) | ![](../../shared/test@jvm/snapshots/macos/logs/phone-paused-filtered.png) | ![](../../shared/test@jvm/snapshots/macos/logs/phone-empty.png) |

Escritorio:

![](../../shared/test@jvm/snapshots/macos/logs/desktop-live.png)

## Ajustes

| Servicios de nombres con pruebas | Servicios de nombres apagados |
|---|---|
| ![](../../shared/test@jvm/snapshots/macos/settings/phone-services.png) | ![](../../shared/test@jvm/snapshots/macos/settings/phone-names-off.png) |

Escritorio:

![](../../shared/test@jvm/snapshots/macos/settings/desktop-services.png)

## Catálogo de componentes

Todos los componentes `Uci` en todos sus estados (en la app: modo desarrollo, pestaña Perfiles → Catálogo).

| Colores y tipografía | Contenedores | Botones | Botón de conexión |
|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/catalog/foundations.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/containers.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/buttons.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/power.png) |

| Estado | Formularios | Listas | Navegación | Datos |
|---|---|---|---|---|
| ![](../../shared/test@jvm/snapshots/macos/catalog/status.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/forms.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/lists.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/navigation.png) | ![](../../shared/test@jvm/snapshots/macos/catalog/data.png) |
