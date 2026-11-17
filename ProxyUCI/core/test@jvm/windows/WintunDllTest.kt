package org.uci.proxy.core.windows

import org.uci.proxy.core.desktop.WindowsElevatedEngineLauncher
import org.uci.proxy.core.platform.windows.ResourceWintunDllLocator
import org.uci.proxy.core.platform.windows.WindowsNetworkException
import org.uci.proxy.core.platform.windows.WintunDll
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FAKE_DLL = "MZ-no-es-una-dll-de-verdad".encodeToByteArray()
private val FAKE_HASH = MessageDigest.getInstance("SHA-256").digest(FAKE_DLL).joinToString("") { "%02x".format(it) }

class WintunDllTest {
    private val root: Path = createTempDirectory("wintun-test")
    private val openedResources = mutableListOf<String>()

    private fun locator(
        osArch: String = "amd64",
        hashes: Map<String, String> = mapOf("amd64" to FAKE_HASH, "arm64" to FAKE_HASH),
        content: ByteArray? = FAKE_DLL,
    ) = ResourceWintunDllLocator(
        root = root,
        osArch = osArch,
        expectedHashes = hashes,
        openResource = { name ->
            openedResources += name
            content?.let(::ByteArrayInputStream)
        },
    )

    @Test
    fun `os arch names map to the architectures that are packaged`() {
        assertEquals("amd64", WintunDll.architectureFor("amd64"))
        assertEquals("amd64", WintunDll.architectureFor("x86_64"))
        assertEquals("arm64", WintunDll.architectureFor("aarch64"))
        assertNull(WintunDll.architectureFor("x86"))
    }

    @Test
    fun `the DLL of the machine's architecture is extracted and verified`() {
        val dll = locator(osArch = "aarch64").locate()

        assertEquals(listOf("/native/windows-arm64/wintun.dll"), openedResources)
        assertContentEquals(FAKE_DLL, dll.readBytes())
        assertEquals("wintun.dll", dll.fileName.toString())
    }

    @Test
    fun `a DLL that is not the expected one is refused and removed`() {
        val error = assertFailsWith<WindowsNetworkException> { locator(content = "otra cosa".encodeToByteArray()).locate() }

        assertTrue("no es la esperada" in error.message.orEmpty())
        Files.walk(root).use { files -> assertTrue(files.noneMatch { it.fileName.toString() == "wintun.dll" }) }
    }

    @Test
    fun `an existing file is never reused`() {
        val first = locator().locate()
        val second = locator().locate()

        assertNotEquals(first.parent, second.parent)
    }

    @Test
    fun `an unsupported architecture or a missing resource is reported`() {
        assertFailsWith<WindowsNetworkException> { locator(osArch = "x86").locate() }
        assertFailsWith<WindowsNetworkException> { locator(content = null).locate() }
        assertFailsWith<WindowsNetworkException> { locator(hashes = emptyMap()).locate() }
    }

    @Test
    fun `the packaged hashes match the DLLs shipped in the resources`() {
        // Con las DLL reales, para que cambiar una sin actualizar su huella falle aquí y no en Windows.
        for ((architecture, expected) in WintunDll.SHA256) {
            val bytes = requireNotNull(WintunDll::class.java.getResourceAsStream("/native/windows-$architecture/wintun.dll")) {
                "Falta la DLL de $architecture"
            }.use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, "huella de wintun.dll para $architecture")
        }
    }
}

class WindowsEngineLauncherTest {
    @Test
    fun `the engine is started elevated and hidden, and PowerShell waits for it`() {
        val command = WindowsElevatedEngineLauncher.powershellCommand(
            listOf("C:\\Program Files\\Java\\bin\\java.exe", "-cp", "C:\\mi carpeta\\app.jar", "org.uci.proxy.MainKt", "--engine"),
        )

        assertEquals("powershell.exe", command.first())
        val script = command.last()
        assertTrue("Start-Process -FilePath 'C:\\Program Files\\Java\\bin\\java.exe'" in script)
        assertTrue("-Verb RunAs -WindowStyle Hidden -PassThru" in script)
        assertTrue("'\"-cp\"','\"C:\\mi carpeta\\app.jar\"','\"org.uci.proxy.MainKt\"','\"--engine\"'" in script)
        assertTrue(script.endsWith("\$p.WaitForExit(); exit \$p.ExitCode"))
    }

    @Test
    fun `a single quote in a path cannot break out of the PowerShell string`() {
        assertEquals("'C:\\O''Brien\\app.exe'", WindowsElevatedEngineLauncher.psString("C:\\O'Brien\\app.exe"))
    }
}
