package org.uci.proxy.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.uci.proxy.core.di.platformModule

/** Módulos del proceso que muestra la interfaz. */
val appModules: List<Module> = listOf(platformModule, uiModule)

/**
 * Arranca Koin para el proceso de la interfaz. Cada app la llama una vez desde su punto de entrada;
 * [config] permite añadir lo propio de la plataforma, como `androidContext`.
 */
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    config?.invoke(this)
    modules(appModules)
}
