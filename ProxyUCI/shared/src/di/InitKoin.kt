package org.uci.proxy.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.uci.proxy.core.di.coreModule
import org.uci.proxy.core.di.platformModule

/** Módulos del proceso que muestra la interfaz. */
val appModules: List<Module> = listOf(platformModule, coreModule, uiModule)

/**
 * Arranca Koin para el proceso de la interfaz. Cada app la llama una vez desde su punto de entrada;
 * [config] permite añadir lo propio de la plataforma, como `androidContext`.
 */
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    modules(appModules)
    // Después de los módulos comunes: lo que la app enlace en [config] sustituye a lo común,
    // por ejemplo el túnel "no disponible" por el de verdad.
    config?.invoke(this)
}
