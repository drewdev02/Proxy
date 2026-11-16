package org.uci.proxy.core

import org.uci.proxy.core.platform.FileKeyValueStore
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyValueStoreTest {
    private val directory = Files.createTempDirectory("proxyuci-test")
    private val file = directory.resolve("nested").resolve("settings.properties")

    @AfterTest
    fun cleanUp() {
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        directory.deleteRecursively()
    }

    @Test
    fun `missing file reads as empty`() {
        assertNull(FileKeyValueStore(file).getString("clave"))
    }

    @Test
    fun `values survive reopening the file`() {
        FileKeyValueStore(file).putString("clave", "valor con ñ, = y\nsalto de línea")

        assertEquals("valor con ñ, = y\nsalto de línea", FileKeyValueStore(file).getString("clave"))
    }

    @Test
    fun `creates the parent directory on first write`() {
        FileKeyValueStore(file).putString("clave", "valor")

        assertTrue(file.exists())
    }

    @Test
    fun `removed values stay removed after reopening`() {
        val store = FileKeyValueStore(file)
        store.putString("a", "1")
        store.putString("b", "2")

        store.remove("a")

        val reopened = FileKeyValueStore(file)
        assertNull(reopened.getString("a"))
        assertEquals("2", reopened.getString("b"))
    }

    @Test
    fun `no temporary file is left behind`() {
        FileKeyValueStore(file).putString("clave", "valor")

        assertEquals(listOf(file), file.parent.listDirectoryEntries())
    }
}
