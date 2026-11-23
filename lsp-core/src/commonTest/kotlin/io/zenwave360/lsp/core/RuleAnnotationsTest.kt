package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A javadoc and annotations before a rule (ZFL-R11, ZFL-R12): never a rule themselves, shown on hover. */
class RuleAnnotationsTest {

    private val zdlUri = "file:///workspace/orders.zdl"
    private val zflUri = "file:///workspace/orders.zfl"
    private val triple = "\"\"\""

    private fun server(uri: String, text: String): ZenwaveLanguageServer =
        ZenwaveLanguageServer(
            modules = listOf(ZdlLanguageModule(), ZflLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
        ).also { it.open(DocumentSnapshot(DocumentRef(uri, if (uri.endsWith(".zdl")) "zdl" else "zfl", 1), text)) }

    private fun String.at(needle: String, occurrence: Int = 0, offsetInto: Int = 1): Position {
        var from = -1
        repeat(occurrence + 1) { from = indexOf(needle, from + 1) }
        check(from >= 0) { "$needle not found" }
        val offset = from + offsetInto
        val before = substring(0, offset)
        return Position(before.count { it == '\n' }, offset - (before.lastIndexOf('\n') + 1))
    }

    private fun flatten(nodes: List<HierarchyNode>): List<HierarchyNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    private fun zdl(eol: String = "\n", plainValue: String = "\"x\"") = """
        rules (SlotOccupancy) {
          /** the booking javadoc
           * second line */
          @invariant  noBookingWithoutSeats "no booking without seats"
          @rule(severity: high, tags: ["a", "b)"], nested: f(g(1))) plain $plainValue
          noAnnotation "y"
        }
        entity SlotOccupancy { name String }
        service S for (SlotOccupancy) {
          @rules(noBookingWithoutSeats, plain)
          op(SlotOccupancy) SlotOccupancy
        }
    """.trimIndent().replace("\n", eol)

    @Test
    fun annotationsAreNotRulesAndOutlineHasTheNames() {
        for (eol in listOf("\n", "\r\n")) {
            val text = zdl(eol)
            val rules = server(zdlUri, text).hierarchy(zdlUri).single { it.label == "rules" }
            assertEquals(listOf("noBookingWithoutSeats", "plain", "noAnnotation"), rules.children.map { it.label })
        }
    }

    @Test
    fun zdlHoverShowsAnnotationsAndJavadocBeforeTheText() {
        for (eol in listOf("\n", "\r\n")) {
            val text = zdl(eol)
            val server = server(zdlUri, text)
            val onDeclaration = assertNotNull(server.hover(zdlUri, text.at("noBookingWithoutSeats")))
            val markdown = onDeclaration.markdown
            assertTrue("`@invariant`" in markdown, markdown)
            assertTrue("the booking javadoc\nsecond line" in markdown, markdown)
            assertTrue(markdown.indexOf("@invariant") < markdown.indexOf("the booking javadoc"), markdown)
            assertTrue(markdown.indexOf("the booking javadoc") < markdown.indexOf("no booking without seats"), markdown)
            assertEquals(
                "noBookingWithoutSeats",
                text.lines()[onDeclaration.range!!.start.line].substring(onDeclaration.range!!.start.character, onDeclaration.range!!.end.character),
            )

            val onReference = assertNotNull(server.hover(zdlUri, text.at("plain", occurrence = 1)))
            assertTrue("`@rule(severity: high, tags: [\"a\", \"b)\"], nested: f(g(1)))`" in onReference.markdown, onReference.markdown)

            val plain = assertNotNull(server.hover(zdlUri, text.at("noAnnotation")))
            assertTrue("`@" !in plain.markdown && "javadoc" !in plain.markdown, plain.markdown)
        }
    }

    @Test
    fun tripleQuotedValueAfterAnAnnotation() {
        val text = zdl(plainValue = "$triple\n  multi \"line\"\n  $triple")
        val server = server(zdlUri, text)
        assertEquals(
            listOf("noBookingWithoutSeats", "plain", "noAnnotation"),
            server.hierarchy(zdlUri).single { it.label == "rules" }.children.map { it.label },
        )
        assertTrue("multi \"line\"" in assertNotNull(server.hover(zdlUri, text.at("plain"))).markdown)
    }

    @Test
    fun zdlCompletionShowsAnnotationsInDetail() {
        val text = zdl().replace("@rules(noBookingWithoutSeats, plain)", "@rules(pl)")
        val item = server(zdlUri, text).completion(zdlUri, text.at("@rules(pl", offsetInto = 9)).single()
        assertEquals("plain", item.label)
        assertTrue("@rule(severity: high" in item.detail.orEmpty(), item.detail)
    }

    private fun zfl(eol: String = "\n") = """
        flow Book {
            rules (SlotOccupancy) {
                /** zfl javadoc */
                @invariant noBookingWithoutSeats 'single quoted'
                @rule(severity: high) plain "x"
                noAnnotation "y"
            }
            start Booked {}
            @rules(noBookingWithoutSeats, plain)
            when Booked do book
        }
    """.trimIndent().replace("\n", eol)

    @Test
    fun zflHoverAndOutline() {
        for (eol in listOf("\n", "\r\n")) {
            val text = zfl(eol)
            val server = server(zflUri, text)
            val onDeclaration = assertNotNull(server.hover(zflUri, text.at("noBookingWithoutSeats")))
            assertTrue("`@invariant`" in onDeclaration.markdown, onDeclaration.markdown)
            assertTrue("zfl javadoc" in onDeclaration.markdown, onDeclaration.markdown)
            assertTrue("single quoted" in onDeclaration.markdown, onDeclaration.markdown)

            val onReference = assertNotNull(server.hover(zflUri, text.at("plain", occurrence = 1)))
            assertTrue("`@rule(severity: high)`" in onReference.markdown, onReference.markdown)

            val labels = flatten(server.hierarchy(zflUri)).filter { it.kind == "rule" }.map { it.label }
            assertEquals(listOf("noBookingWithoutSeats", "plain", "noAnnotation"), labels)
        }
    }

    @Test
    fun aCommentInsideAnAnnotationIsNotTheJavadocAndStringsKeepTheirSpaces() {
        val text = """
            rules (O) {
              @rule(note: /** internal */ "a   b",  x: 1) actual "the rule"
            }
            entity O { name String }
        """.trimIndent()
        val markdown = assertNotNull(server(zdlUri, text).hover(zdlUri, text.at("actual"))).markdown
        assertTrue("internal" !in markdown, markdown)
        assertTrue("`@rule(note: \"a   b\", x: 1)`" in markdown, markdown)
    }

    @Test
    fun outlineRangeStartsAtTheJavadocWhileDefinitionStaysOnTheName() {
        val text = zdl()
        val server = server(zdlUri, text)
        val rule = server.hierarchy(zdlUri).single { it.label == "rules" }.children.first()
        assertEquals(text.at("/** the booking", offsetInto = 0), rule.source.range.start)
        val plain = server.hierarchy(zdlUri).single { it.label == "rules" }.children[1]
        assertEquals(text.at("@rule(severity", offsetInto = 0), plain.source.range.start)
        val definition = server.definition(zdlUri, text.at("noBookingWithoutSeats", occurrence = 1))
        assertEquals(text.at("noBookingWithoutSeats", offsetInto = 0), definition.first().range!!.start)
    }
}
