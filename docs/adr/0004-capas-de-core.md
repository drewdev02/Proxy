# 0004. Capas en `core`, comprobadas con una prueba

Estado: aceptada
Fecha: 2026-11-22

## Contexto

`core/src` creció a unos 80 archivos en 17 paquetes sin una regla de qué puede depender de qué. Se vio en síntomas
concretos: `port` y `log` se importaban entre sí; `AppDispatchers` vivía en `profile/` y lo usaban seis paquetes;
`settings/AppSettings.kt` juntaba el modelo, la interfaz del repositorio y su implementación; `ProfileRepository` era un
puerto fuera de `port/`; `proxy/IpNamer` conocía `settings`; y la lógica de la cuota estaba en el módulo de la interfaz.

## Decisión

Los paquetes de `core/src` se ordenan en capas, y cada una solo importa de las de abajo (ver
[`architecture.md`](../architecture.md#capas-de-core)):

- **base:** `model` (datos), `common`, `packet`, `auth`: no dependen de nada del proyecto.
- **`port`:** las interfaces hacia fuera; solo conocen la base.
- **`data`:** las implementaciones `Stored*` sobre los almacenes de `port`.
- **protocolos:** `routing`, `dns`, `tcp`, `net`, `proxy`, `vpn`.
- **funciones:** `log`, `profile`, `settings`, `traffic`, `quota`: la lógica; usan los puertos, nunca `data`.
- **`engine`** monta el túnel, y **`di`** lo conecta todo.

`LayersTest` recorre los `import` y falla si una capa importa de otra de arriba o si aparece un paquete sin capa. Un
paquete nuevo hay que registrarlo en esa prueba, y así se decide a qué capa pertenece.

## Descartado

- **Un módulo de Amper por capa (`model`, `ports`, `engine`…):** cada módulo repite plataformas y `namespace` de Android;
  la extensión de iOS enlaza `core` como un todo, así que no se ahorra nada en tamaño; y la compilación incremental ya
  funciona por archivo. La prueba da el mismo control con un coste menor.
- **Un paquete `domain/` para la lógica:** solo habría añadido un prefijo a cientos de `import`.

## Consecuencias

- Un solo `AppSettings.kt` con cinco tipos pasó a `model`, `port` y `data`; `storage/` desapareció.
- Los motivos de fallo que viajan en `TunnelFailure` (`ProtocolIssue`, `VpnHandshakeIssue`, `OvpnConfigError`) viven en
  `model`, para que `port` no dependa de `proxy` ni de `vpn`. Sus nombres serializados no cambian.
- Mover un tipo de paquete es seguro para los datos guardados mientras sus subtipos tengan `@SerialName`; hay que
  comprobarlo antes de mover algo serializable.
- Cuesta el cambio de `import` en muchos archivos y los conflictos con ramas abiertas que tocaran los mismos archivos.
