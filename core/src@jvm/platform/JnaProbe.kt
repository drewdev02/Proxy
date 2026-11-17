package org.uci.proxy.core.platform

import com.sun.jna.Native

/** Carga la parte nativa de JNA. Sirve para comprobar que el programa empaquetado puede usarla. */
object JnaProbe {
    /** Tamaño de un puntero en bytes; si JNA no se puede cargar, lanza el error de carga. */
    fun pointerSize(): Int = Native.POINTER_SIZE
}
