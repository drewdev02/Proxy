package org.uci.proxy.common

import org.uci.proxy.core.port.DataProtectionException
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageFailureTest {
    @Test
    fun `a failure of the secure storage is told apart`() {
        assertEquals(StorageFailure.SECURE_STORAGE, classifyStorageError(DataProtectionException("Keystore")))
        assertEquals(StorageFailure.SECURE_STORAGE, classifyStorageError(IllegalStateException("envoltorio", DataProtectionException("DPAPI"))))
    }

    @Test
    fun `any other failure is a generic one`() {
        assertEquals(StorageFailure.OTHER, classifyStorageError(IllegalStateException("disco lleno")))
    }
}
