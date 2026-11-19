package org.uci.proxy.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.uci.proxy.ui.catalog.CatalogSection
import org.uci.proxy.ui.catalog.CatalogSectionView
import kotlin.test.Test

/** Cada sección del catálogo de componentes contra su captura de referencia (ver [Snapshots]). */
class CatalogSnapshotTest {
    @Test
    fun catalogSectionsMatchReferences() {
        val snapshots = Snapshots("catalog")
        CatalogSection.entries.forEach { section ->
            snapshots.check(section.name.lowercase(), widthDp = 412) { CatalogSectionView(section, Modifier.padding(20.dp)) }
        }
        snapshots.assertAllMatch()
    }
}
