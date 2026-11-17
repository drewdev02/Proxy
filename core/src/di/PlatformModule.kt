package org.uci.proxy.core.di

import org.koin.core.module.Module

/**
 * Enlaza cada puerto de `org.uci.proxy.core.port` con su implementación de plataforma.
 * Es el único `expect` del proyecto: el resto del código depende solo de las interfaces.
 */
expect val platformModule: Module
