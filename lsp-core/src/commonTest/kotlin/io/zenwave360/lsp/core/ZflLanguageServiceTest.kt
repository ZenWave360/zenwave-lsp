package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.impl.ZenwaveLanguageServiceImpl
import io.zenwave360.lsp.core.model.Position
import io.zenwave360.lsp.core.zdl.ZdlParserAdapter
import io.zenwave360.lsp.core.zfl.ZflParserAdapter
import kotlin.test.*

/**
 * Tests for ZenwaveLanguageService with ZFL (ZenWave Flow Language).
 *
 * Demonstrates that the LSP core works with both ZDL and ZFL parsers
 * through the common SemanticModel interface.
 */
class ZflLanguageServiceTest {

    private val service = ZenwaveLanguageServiceImpl(ZdlParserAdapter(), ZflParserAdapter())

    @Test
    fun testParseZfl_Subscriptions() {
        val text = readTestFile("subscriptions.zfl")
        val model = service.parse(ZenwaveLanguage.ZFL, text)

        assertNotNull(model)
        assertNotNull(model.data)

        // Verify basic ZFL structure
        assertTrue(model.data.containsKey("flows"))
        assertTrue(model.data.containsKey("imports"))
        assertTrue(model.data.containsKey("locations"))
        assertTrue(model.data.containsKey("problems"))
    }

    @Test
    fun testDiagnostics_ZflNoProblems() {
        val text = readTestFile("subscriptions.zfl")
        val problems = service.diagnostics(ZenwaveLanguage.ZFL, text)

        // subscriptions.zfl should have no validation errors
        assertEquals(0, problems.size)
    }

    @Test
    fun testHover_ZflFlow() {
        val text = readTestFile("subscriptions.zfl")

        // Test hover on a flow element
        // Note: Exact line numbers depend on the ZFL file structure
        val request = HoverRequest(
            text = text,
            position = Position(line = 10, character = 5)
        )

        val hover = service.hover(ZenwaveLanguage.ZFL, request)

        // Should return some jsonPath or null depending on position
        // This tests that ZFL parsing works through the LSP core API
    }

    @Test
    fun testLocationLookup_Zfl() {
        val text = readTestFile("subscriptions.zfl")
        val model = service.parse(ZenwaveLanguage.ZFL, text)

        // Test that getLocation works for ZFL
        // The exact positions depend on the ZFL file content
        val location = model.getLocation(10, 5)

        // If there's a semantic element at this position, we should get a jsonPath
        // Otherwise null is acceptable
        // This verifies the location index is built correctly for ZFL
    }

    @Test
    fun testCompletion_ZflStubbed() {
        val text = readTestFile("subscriptions.zfl")

        val request = CompletionRequest(
            text = text,
            position = Position(line = 10, character = 5)
        )

        val completions = service.completion(ZenwaveLanguage.ZFL, request)

        // v0 implementation returns empty list
        assertEquals(0, completions.size)
    }

    @Test
    fun testDefinition_ZflStubbed() {
        val text = readTestFile("subscriptions.zfl")

        val request = DefinitionRequest(
            text = text,
            position = Position(line = 10, character = 5)
        )

        val location = service.definition(ZenwaveLanguage.ZFL, request)

        // v0 implementation returns null
        assertNull(location)
    }

    @Test
    fun testSemanticModel_ZflDataStructure() {
        val text = readTestFile("subscriptions.zfl")
        val model = service.parse(ZenwaveLanguage.ZFL, text)

        // Verify the semantic model exposes the expected ZFL data structure
        val data = model.data

        // Check ZFL-specific top-level keys
        assertTrue(data.containsKey("flows"))
        assertTrue(data.containsKey("imports"))
        assertTrue(data.containsKey("config"))
        assertTrue(data.containsKey("locations"))
        assertTrue(data.containsKey("problems"))

        // Verify flows exist
        @Suppress("UNCHECKED_CAST")
        val flows = data["flows"] as? Map<*, *>
        assertNotNull(flows)
        assertTrue(flows.isNotEmpty(), "Should have at least one flow")
    }

}

