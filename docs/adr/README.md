# Decisiones de arquitectura

Una página por decisión que cuesta cambiar: qué había, qué se decidió, qué se descartó y qué consecuencias trae. Sirven
para no volver a discutir lo ya decidido y para saber por qué el código es como es. Para cambiar una, se escribe otra que
la sustituya y se marca la vieja como «sustituida».

| # | Decisión | Estado |
|---|---|---|
| [0001](0001-puertos-e-inyeccion-por-constructor.md) | Puertos e inyección por constructor con Koin | Aceptada |
| [0002](0002-registro-con-room-en-modulo-aparte.md) | El registro y el consumo guardados con Room, en un módulo aparte | Aceptada |
| [0003](0003-otro-pais-con-cliente-openvpn-propio.md) | «Otro País» con un cliente OpenVPN propio | Aceptada |
| [0004](0004-capas-de-core.md) | Capas en `core`, comprobadas con una prueba | Aceptada |
| [0005](0005-cuota-uci-en-core-y-cliente-http-en-shared.md) | La cuota de la UCI: lógica en `core`, cliente HTTP en `shared` | Aceptada |
| [0006](0006-borradores-con-funciones-puras.md) | El editor de perfil con borradores de funciones puras | Aceptada |
| [0007](0007-modulos-coretesting-y-desktopengine.md) | Los módulos `coreTesting` y `desktopEngine` | Aceptada |

## Formato

```
# NNNN. Título
Estado: aceptada | sustituida por NNNN
Fecha: AAAA-MM-DD

## Contexto       qué problema había y qué restricciones
## Decisión       qué se hace
## Descartado     las alternativas y por qué no
## Consecuencias  lo que se gana y lo que cuesta
```
