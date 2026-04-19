package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ZflDiagnosticsTest {

    private val module = ZflLanguageModule()

    @Test
    fun diagnosticsReportMissingFlowForEmptyDocuments() {
        val diagnostics = module.diagnostics(
            DocumentSnapshot(
                ref = DocumentRef("file:///workspace/flows/empty.zfl", "zfl", 1),
                text = "systems { }"
            )
        )
        val diagnostic = diagnostics.firstOrNull()

        assertNotNull(diagnostic)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("$", diagnostic.code)
        assertEquals("zfl", diagnostic.data["language"])
    }
}
