package org.uci.proxy.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
