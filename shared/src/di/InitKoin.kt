package org.uci.proxy.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.uci.proxy.core.di.coreModule
import org.uci.proxy.core.di.platformModule
import org.uci.proxy.core.log.LogRecorder
import org.uci.proxy.database.di.databaseModule

/** Módulos del proceso que muestra la interfaz. */
val appModules: List<Module> = listOf(platformModule, coreModule, databaseModule, uiModule)

/**
 * Arranca Koin para el proceso de la interfaz. Cada app la llama una vez desde su punto de entrada;
 * [config] permite añadir lo propio de la plataforma, como `androidContext`.
 */
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    modules(appModules)
    // Después de los módulos comunes: lo que la app enlace en [config] sustituye a lo común,
    // por ejemplo el túnel "no disponible" por el de verdad.
    config?.invoke(this)
}.also { app ->
    // El registro se guarda en disco desde el arranque, haya o no alguien mirando la pantalla «Registro».
    app.koin.get<LogRecorder>().start()
}
