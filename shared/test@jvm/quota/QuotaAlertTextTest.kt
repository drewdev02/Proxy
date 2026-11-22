package org.uci.proxy.quota

import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Los textos de las notificaciones de la cuota, en el idioma del sistema. */
class QuotaAlertTextTest {
    private val original = Locale.getDefault()

    @AfterTest
    fun restore() = Locale.setDefault(original)

    private fun texts(language: String, alert: QuotaAlert): Pair<String, String> = runBlocking {
        Locale.setDefault(Locale.forLanguageTag(language))
        alert.title() to alert.message()
    }

    @Test
    fun `in spanish they read as before`() {
        assertEquals(
            "Cuota de la UCI al 75 %" to "Llevas el 80 % de tu cuota: 800 MB de 1000 MB.",
            texts("es", QuotaAlert.Threshold(75, UciQuota(1000, 800.0, "", 0))),
        )
        assertEquals(
            "Cuota de la UCI al 90 %" to "Llevas el 96 % de tu cuota: 960,5 MB de 1000 MB. Te quedan 39,5 MB.",
            texts("es", QuotaAlert.Threshold(90, UciQuota(1000, 960.5, "", 0))),
        )
        assertEquals(
            "Cuota de la UCI al 90 %" to "Llevas el 115 % de tu cuota: 2300 MB de 2000 MB. Te pasaste 300 MB.",
            texts("es", QuotaAlert.Threshold(90, UciQuota(2000, 2300.0, "", 0))),
        )
        assertEquals(
            "Túnel en pausa por la cuota" to
                "Llegaste al 95 % de tu cuota: se desconectó el túnel para no agotarla. Puedes conectar igual desde Conexión.",
            texts("es", QuotaAlert.Paused(UciQuota(1000, 950.0, "", 0))),
        )
    }

    @Test
    fun `in english numbers use a decimal point and no space before the percent sign`() {
        assertEquals(
            "UCI quota at 90%" to "You have used 96% of your quota: 960.5 MB of 1000 MB. You have 39.5 MB left.",
            texts("en", QuotaAlert.Threshold(90, UciQuota(1000, 960.5, "", 0))),
        )
    }
}
