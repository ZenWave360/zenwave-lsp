package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.CodeAction
import io.zenwave360.lsp.core.contracts.DocumentEdit
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Strings, comments and supplementary characters around rules (ZFL-R11, ZFL-R12). */
class RulesSyntaxRobustnessTest {

    private val zdlUri = "file:///workspace/orders.zdl"
    private val zflUri = "file:///workspace/orders.zfl"
    private val emoji = "😀"
    private val triple = "\"\"\""

    private fun server(uri: String, text: String): ZenwaveLanguageServer =
        ZenwaveLanguageServer(
            modules = listOf(ZdlLanguageModule(), ZflLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
        ).also { it.open(DocumentSnapshot(DocumentRef(uri, if (uri.endsWith(".zdl")) "zdl" else "zfl", 1), text)) }

    /** The UTF-16 position of the [offsetInto]th character of the [occurrence]th [needle]. */
    private fun String.at(needle: String, occurrence: Int = 0, offsetInto: Int = 1): Position {
        var from = -1
        repeat(occurrence + 1) { from = indexOf(needle, from + 1) }
        check(from >= 0) { "$needle not found" }
        val offset = from + offsetInto
        val before = substring(0, offset)
        return Position(before.count { it == '\n' }, offset - (before.lastIndexOf('\n') + 1))
    }

    private fun String.slice(range: Range): String {
        require(range.start.line == range.end.line)
        return lines()[range.start.line].substring(range.start.character, range.end.character)
    }

    private fun String.apply(edits: List<DocumentEdit>): String {
        val lines = lines().toMutableList()
        edits.sortedByDescending { it.range.start.character }.forEach { edit ->
            val line = lines[edit.range.start.line]
            lines[edit.range.start.line] = line.substring(0, edit.range.start.character) + edit.newText + line.substring(edit.range.end.character)
        }
        return lines.joinToString("\n")
    }

    private fun flatten(nodes: List<HierarchyNode>): List<HierarchyNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    // ---------- strings ----------

    private val zdlStrings = """
        rules {
            audit 'Every change audited'
            tricky ${triple}Closes } and opens { // not a comment /* nor this$triple
            braces "a } b { // c /* d"
            single 'e } f { // g /* h'
        }
        entity Order { name String }
        aggregate OrderAggregate(Order) {
        }
        service OrderService for (OrderAggregate) {
            @rules('audit', tricky, braces, single)
            placeOrder(Order) Order
        }
    """.trimIndent()

    @Test
    fun zdlSingleTripleAndBracedStringsKeepTheRulesBlockTogether() {
        val server = server(zdlUri, zdlStrings)
        val rules = flatten(server.hierarchy(zdlUri)).first { it.label == "rules" }
        val names = flatten(rules.children).map { it.label }
        assertEquals(listOf("audit", "tricky", "braces", "single"), names.filter { it in listOf("audit", "tricky", "braces", "single") }.distinct())

        val audit = assertNotNull(server.hover(zdlUri, zdlStrings.at("audit", offsetInto = 2)))
        assertTrue("Every change audited" in audit.markdown, audit.markdown)
        val tricky = assertNotNull(server.hover(zdlUri, zdlStrings.at("tricky", occurrence = 0, offsetInto = 2)))
        assertTrue("Closes } and opens {" in tricky.markdown, tricky.markdown)

        // the single-quoted reference resolves
        val quoted = server.definition(zdlUri, zdlStrings.at("'audit'", offsetInto = 3)).single()
        assertEquals("audit", zdlStrings.slice(quoted.range!!))
    }

    @Test
    fun zdlCommentMarkersInsideStringsDoNotHideRules() {
        val server = server(zdlUri, zdlStrings)
        // `single` is declared after strings holding `}`, `{`, `//` and `/*`
        val target = server.definition(zdlUri, zdlStrings.at("single", occurrence = 1, offsetInto = 2)).single()
        assertEquals("single", zdlStrings.slice(target.range!!))
        assertEquals(zdlStrings.at("single", occurrence = 0, offsetInto = 0), target.range!!.start)
    }

    @Test
    fun zdlCompletionInsideSingleQuotes() {
        val text = zdlStrings.replace("@rules('audit', tricky, braces, single)", "@rules('au')")
        val items = server(zdlUri, text).completion(zdlUri, text.at("'au'", offsetInto = 3))
        assertEquals(listOf("audit"), items.map { it.label })
        val edit = assertNotNull(items.single().textEdit)
        assertEquals("'audit'", edit.newText)
        assertEquals("'au'", text.slice(edit.range))
    }

    @Test
    fun zflSingleQuotedRulesAndReferences() {
        val zfl = """
            flow PlaceOrder {
                rules {
                    audit 'Every } change { audited'
                }
                start PlaceOrderRequested {}
                @rules('audit', 'nope')
                when PlaceOrderRequested do placeOrder
            }
        """.trimIndent()
        val server = server(zflUri, zfl)
        val flow = flatten(server.hierarchy(zflUri)).first { it.label == "PlaceOrder" }
        assertEquals(listOf("audit"), flow.children.filter { it.kind == "rule" }.map { it.label })
        val hover = assertNotNull(server.hover(zflUri, zfl.at("'audit'", offsetInto = 3)))
        assertTrue("Every } change { audited" in hover.markdown, hover.markdown)
        val warning = server.diagnostics(zflUri).single { it.message.contains("nope") }
        assertEquals("'nope'", zfl.slice(warning.range))
    }

    // ---------- code points ----------

    @Test
    fun zdlWarningAfterASupplementaryCharacterAppliesItsQuickFix() {
        val text = "// $emoji\npolicies { audit \"x\" }"
        val server = server(zdlUri, text)
        val warning = server.diagnostics(zdlUri).single { it.message.contains("deprecated") }
        assertEquals("policies", text.slice(warning.range))
        val fix = server.codeActions(zdlUri, warning.range).single()
        assertEquals("// $emoji\nrules { audit \"x\" }", text.apply(fix.edits))
    }

    @Test
    fun zflWarningAfterASupplementaryCharacterOnTheSameLine() {
        val zfl = """
            flow PlaceOrder {
                start PlaceOrderRequested {}
                @rules("$emoji$emoji", nope)
                when PlaceOrderRequested do placeOrder
            }
        """.trimIndent()
        val warnings = server(zflUri, zfl).diagnostics(zflUri).filter { it.message.contains("no rule") }
        assertEquals(listOf("\"$emoji$emoji\"", "nope"), warnings.map { zfl.slice(it.range) })
    }

    // ---------- comments ----------

    @Test
    fun noRuleCompletionInsideComments() {
        val base = zdlStrings.replace("@rules('audit', tricky, braces, single)", "@rules(%s)")
        listOf("// comment ", "/* block ").forEach { typed ->
            val text = base.replace("%s", typed)
            assertTrue(server(zdlUri, text).completion(zdlUri, text.at(typed, offsetInto = typed.length)).isEmpty(), typed)
        }
        // a closed block comment and a new line end the comment
        val closed = base.replace("%s", "/* done */ ")
        assertEquals(4, server(zdlUri, closed).completion(zdlUri, closed.at("/* done */ ", offsetInto = 11)).size)
        val nextLine = base.replace("%s", "// note\n        ")
        assertEquals(4, server(zdlUri, nextLine).completion(zdlUri, Position(nextLine.at("// note").line + 1, 8)).size)
    }

    // ---------- replacement ranges ----------

    @Test
    fun hyphenatedCompletionReplacesWhatWasTyped() {
        val zdl = """
            rules { stock-first "Stock first" }
            entity Order { name String }
            aggregate OrderAggregate(Order) {
            }
            service OrderService for (OrderAggregate) {
                @rules(%s)
                placeOrder(Order) Order
            }
        """.trimIndent()

        fun accept(typed: String, cursorAfter: String): String {
            val text = zdl.replace("%s", typed)
            val position = text.at("@rules(", offsetInto = 7 + typed.indexOf(cursorAfter) + cursorAfter.length)
            val item = server(zdlUri, text).completion(zdlUri, position).single()
            val edit = assertNotNull(item.textEdit)
            return text.apply(listOf(DocumentEdit(zdlUri, edit.range, edit.newText)))
        }
        assertTrue("@rules(\"stock-first\")" in accept("\"stock-fi\"", "stock-fi"))
        assertTrue("@rules(\"stock-first\")" in accept("\"stock-fi", "stock-fi"))
        assertTrue("@rules(\"stock-first\")" in accept("stock-fi", "stock-fi"))
        assertTrue("@rules(\"stock-first\")" in accept("", ""))
    }
}
