package org.uci.proxy.common

import org.uci.proxy.core.port.DataProtectionException

/** Por qué no se pudo guardar o borrar algo en el almacén de la app. */
enum class StorageFailure {
    /** El almacén seguro del sistema (Keystore, DPAPI, Llavero) no cifró o no descifró la contraseña. */
    SECURE_STORAGE,

    /** Cualquier otro fallo al escribir los datos. */
    OTHER,
}

/** Clasifica una excepción del almacén. */
fun classifyStorageError(error: Throwable): StorageFailure =
    if (generateSequence(error) { it.cause }.any { it is DataProtectionException }) StorageFailure.SECURE_STORAGE else StorageFailure.OTHER
