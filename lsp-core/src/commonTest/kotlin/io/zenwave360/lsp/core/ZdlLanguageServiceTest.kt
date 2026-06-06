package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.impl.ZenwaveLanguageServiceImpl
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.model.Severity
import io.zenwave360.lsp.core.zdl.ZdlParserAdapter
import io.zenwave360.lsp.core.zfl.ZflParserAdapter
import kotlin.test.*

/**
 * Tests for ZenwaveLanguageService implementation.
 *
 * Tests both ZDL and ZFL parsing and LSP features:
 * - Parsing and semantic model creation
 * - Diagnostics (problems)
 * - Hover (jsonPath resolution)
 * - Location lookup (cursor position → jsonPath)
 */
class ZdlLanguageServiceTest {

    private val service = ZenwaveLanguageServiceImpl(ZdlParserAdapter(), ZflParserAdapter())

    // ========== ZDL Tests ==========

    @Test
    fun testParseZdl_Complete() {
        val text = readTestFile("complete.zdl")
        val model = service.parse(ZenwaveLanguage.ZDL, text)

        assertNotNull(model)
        assertNotNull(model.data)

        // Verify basic structure
        assertTrue(model.data.containsKey("entities"))
        assertTrue(model.data.containsKey("services"))
        assertTrue(model.data.containsKey("config"))
    }

    @Test
    fun testDiagnostics_ZdlNoProblems() {
        val text = readTestFile("complete.zdl")
        val problems = service.diagnostics(ZenwaveLanguage.ZDL, text)

        // complete.zdl should have no validation errors
        assertEquals(0, problems.size)
    }

    @Test
    fun testDiagnostics_ZdlWithProblems() {
        val text = readTestFile("problems.zdl")
        val problems = service.diagnostics(ZenwaveLanguage.ZDL, text)

        // problems.zdl should have validation errors
        assertTrue(problems.size > 0, "Expected validation problems in problems.zdl")

        // Verify problem structure
        problems.forEach { problem ->
            assertNotNull(problem.jsonPath, "Problem should have a jsonPath")
            assertNotNull(problem.message, "Problem should have a message")
            assertNotNull(problem.range, "Problem should have a range")
            assertEquals(Severity.ERROR, problem.severity)
        }
    }

    @Test
    fun testHover_ZdlEntityField() {
        val text = readTestFile("complete.zdl")

        // Line 86, character 12 should be on "customerId" field name
        val request = HoverRequest(
            text = text,
            position = Position(line = 86, character = 12)
        )

        val hover = service.hover(ZenwaveLanguage.ZDL, request)

        assertNotNull(hover, "Hover should return information")
        assertEquals("entities.Customer.fields.customerId.name", hover.contents)
    }

    @Test
    fun testHover_ZdlEntityFieldType() {
        val text = readTestFile("complete.zdl")

        // Line 86, character 20 should be on the field type
        val request = HoverRequest(
            text = text,
            position = Position(line = 86, character = 20)
        )

        val hover = service.hover(ZenwaveLanguage.ZDL, request)

        assertNotNull(hover, "Hover should return information")
        assertEquals("entities.Customer.fields.customerId.type", hover.contents)
    }

    @Test
    fun testHover_ZdlEntityFieldValidation() {
        val text = readTestFile("complete.zdl")

        // Line 86, character 30 should be on the validation
        val request = HoverRequest(
            text = text,
            position = Position(line = 86, character = 30)
        )

        val hover = service.hover(ZenwaveLanguage.ZDL, request)

        assertNotNull(hover, "Hover should return information")
        assertEquals("entities.Customer.fields.customerId.validations.required", hover.contents)
    }

    @Test
    fun testHover_ZdlNoMatch() {
        val text = readTestFile("complete.zdl")

        // Line 0, character 0 should not match any semantic element
        val request = HoverRequest(
            text = text,
            position = Position(line = 0, character = 0)
        )

        val hover = service.hover(ZenwaveLanguage.ZDL, request)

        // Should return null or empty when no match
        // (depending on whether line 0 has content)
        // This tests the null-safety of the implementation
    }

    @Test
    fun testCompletion_ZdlStubbed() {
        val text = readTestFile("complete.zdl")

        val request = CompletionRequest(
            text = text,
            position = Position(line = 86, character = 12)
        )

        val completions = service.completion(ZenwaveLanguage.ZDL, request)

        // v0 implementation returns empty list
        assertEquals(0, completions.size)
    }

    @Test
    fun testDefinition_ZdlStubbed() {
        val text = readTestFile("complete.zdl")

        val request = DefinitionRequest(
            text = text,
            position = Position(line = 86, character = 12)
        )

        val location = service.definition(ZenwaveLanguage.ZDL, request)

        // v0 implementation returns null
        assertNull(location)
    }

    @Test
    fun testLocationLookup_ZdlMultiplePositions() {
        val text = readTestFile("complete.zdl")
        val model = service.parse(ZenwaveLanguage.ZDL, text)

        // Test multiple positions from the original test
        assertEquals(
            "entities.Customer.fields.customerId.name",
            model.getLocation(86, 12)
        )

        assertEquals(
            "entities.Customer.fields.customerId.type",
            model.getLocation(86, 20)
        )

        assertEquals(
            "entities.Customer.fields.customerId.validations.required",
            model.getLocation(86, 30)
        )

        assertEquals(
            "entities.Customer.fields.customerId.validations.required",
            model.getLocation(86, 25)
        )

        assertEquals(
            "entities.Customer.fields.customerId.validations.required",
            model.getLocation(86, 33)
        )

        assertEquals(
            "entities.Customer.body",
            model.getLocation(86, 34)
        )
    }

    @Test
    fun testSemanticModel_DataStructure() {
        val text = readTestFile("complete.zdl")
        val model = service.parse(ZenwaveLanguage.ZDL, text)

        // Verify the semantic model exposes the expected data structure
        val data = model.data

        // Check top-level keys
        assertTrue(data.containsKey("entities"))
        assertTrue(data.containsKey("services"))
        assertTrue(data.containsKey("config"))
        assertTrue(data.containsKey("enums"))
        assertTrue(data.containsKey("relationships"))
        assertTrue(data.containsKey("locations"))
        assertTrue(data.containsKey("problems"))
    }
}
