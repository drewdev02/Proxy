package org.uci.proxy

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.uci.proxy.di.initKoin

/** Raíz de composición de Android: el sistema crea esta clase, así que aquí arranca Koin. */
class ProxyUciApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@ProxyUciApplication)
        }
    }
}
