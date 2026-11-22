# 0001. Puertos e inyección por constructor con Koin

Estado: aceptada
Fecha: 2026-11-16

## Contexto

Un solo núcleo en Kotlin común para Android, iOS, macOS y Windows, con lo propio de cada sistema (abrir la interfaz TUN,
guardar secretos, escribir el registro) fuera de él.

## Decisión

- Lo que cambia por plataforma son **interfaces comunes (puertos)** en `core/src/port`, con una implementación por
  plataforma. El único `expect` del proyecto es el módulo de Koin (`platformModule`) que las enlaza.
- Las dependencias se reciben **siempre por constructor**. Koin aparece solo en la raíz de composición: los paquetes `di/`
  y los puntos de entrada que el sistema instancia y que no admiten constructor propio.
- Lo que necesita datos de ejecución (una sesión de túnel, una conexión) lo crea una fábrica inyectada, no Koin.
- Reloj, aleatoriedad y dispatchers se inyectan.

## Descartado

- **`expect class` por adaptador:** no se puede sustituir por un doble en las pruebas y ata el núcleo a una clase concreta.
- **Localizador de servicios (`get()` donde haga falta):** esconde las dependencias de cada clase y obliga a arrancar Koin
  en las pruebas.

## Consecuencias

- Las pruebas pasan los dobles por constructor, sin arrancar Koin.
- El grafo se resuelve en ejecución, no al compilar: `KoinVerifyTest` detecta lo que falta enlazar. No ve los registros con
  nombre (como `ProxyConnectors`), que hay que declarar a mano en esa prueba.
- `PlatformInfo` sirve para diagnósticos y para la raíz de composición de `jvm`, nunca para ramificar comportamiento en el
  núcleo: para eso se pregunta por la capacidad (`TunnelControl.isAvailable`).
