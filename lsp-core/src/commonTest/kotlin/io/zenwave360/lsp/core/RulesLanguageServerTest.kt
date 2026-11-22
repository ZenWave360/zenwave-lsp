package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.CodeAction
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ZFL-R11 and ZFL-R12 in the editor: hover, outline, definition, completion and quick fixes, on the real parsers. */
class RulesLanguageServerTest {

    private val zdlUri = "file:///workspace/orders.zdl"
    private val zflUri = "file:///workspace/orders.zfl"

    private fun server(): ZenwaveLanguageServer =
        ZenwaveLanguageServer(
            modules = listOf(ZdlLanguageModule(), ZflLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
        )

    private fun ZenwaveLanguageServer.opened(uri: String, text: String): ZenwaveLanguageServer {
        open(DocumentSnapshot(DocumentRef(uri, if (uri.endsWith(".zdl")) "zdl" else "zfl", 1), text))
        return this
    }

    /** The position of the [offsetInto]th character of the [occurrence]th [needle] in this text. */
    private fun String.at(needle: String, occurrence: Int = 0, offsetInto: Int = 1): Position {
        var from = -1
        repeat(occurrence + 1) { from = indexOf(needle, from + 1) }
        check(from >= 0) { "$needle not found" }
        val offset = from + offsetInto
        val before = substring(0, offset)
        return Position(before.count { it == '\n' }, offset - (before.lastIndexOf('\n') + 1))
    }

    private fun String.slice(range: Range): String {
        val lines = lines()
        require(range.start.line == range.end.line)
        return lines[range.start.line].substring(range.start.character, range.end.character)
    }

    private fun String.apply(action: CodeAction): String {
        val lines = lines().toMutableList()
        action.edits.sortedByDescending { it.range.start.character }.forEach { edit ->
            val line = lines[edit.range.start.line]
            lines[edit.range.start.line] = line.substring(0, edit.range.start.character) + edit.newText + line.substring(edit.range.end.character)
        }
        return lines.joinToString("\n")
    }

    private fun flatten(nodes: List<HierarchyNode>): List<HierarchyNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    // ---------- ZDL ----------

    private val zdl = """
        rules (Order) {
            minimumAmount "At least 10 EUR"
            stock-first "Stock is reserved before payment"
        }
        rules { audit "Every change is audited" }
        entity Order { name String }
        aggregate OrderAggregate(Order) {
        }
        service OrderService for (OrderAggregate) {
            @rules(minimumAmount, "stock-first", nope)
            placeOrder(Order) Order
        }
    """.trimIndent()

    @Test
    fun zdlHoverOnADeclarationAndOnANameInRules() {
        val server = server().opened(zdlUri, zdl)

        val onDeclaration = assertNotNull(server.hover(zdlUri, zdl.at("stock-first")))
        assertTrue("### stock-first" in onDeclaration.markdown, onDeclaration.markdown)
        assertTrue("aggregate: `Order`" in onDeclaration.markdown)
        assertTrue("Stock is reserved before payment" in onDeclaration.markdown)
        assertEquals("stock-first", zdl.slice(assertNotNull(onDeclaration.range)))

        val onName = assertNotNull(server.hover(zdlUri, zdl.at("\"stock-first\"", offsetInto = 3)))
        assertTrue("Stock is reserved before payment" in onName.markdown, onName.markdown)
        assertEquals("\"stock-first\"", zdl.slice(assertNotNull(onName.range)))
        assertEquals("rules.Order.stock-first", onName.documentSymbol.semanticPath)

        val noAggregate = assertNotNull(server.hover(zdlUri, zdl.at("audit", occurrence = 0)))
        assertTrue("aggregate" !in noAggregate.markdown, noAggregate.markdown)
    }

    @Test
    fun zdlHoverOnAnUndeclaredNameIsNothing() {
        val server = server().opened(zdlUri, zdl)
        assertNull(server.hover(zdlUri, zdl.at("nope")))
    }

    @Test
    fun zdlDefinitionOfANameInRulesIsItsDeclaration() {
        val server = server().opened(zdlUri, zdl)

        val targets = server.definition(zdlUri, zdl.at("minimumAmount", occurrence = 1))
        val target = targets.single()
        assertEquals(zdlUri, target.uri)
        assertEquals(Range(Position(1, 4), Position(1, 17)), target.range)

        val quoted = server.definition(zdlUri, zdl.at("\"stock-first\"", offsetInto = 4)).single()
        assertEquals("stock-first", zdl.slice(assertNotNull(quoted.range)))
        assertEquals(2, quoted.range!!.start.line)

        assertTrue(server.definition(zdlUri, zdl.at("nope")).isEmpty())
    }

    @Test
    fun zdlOutlineListsRulesAndTheirEntries() {
        val server = server().opened(zdlUri, zdl)
        val rules = server.hierarchy(zdlUri).single { it.label == "rules" }
        assertEquals(listOf("minimumAmount", "stock-first", "audit"), rules.children.map { it.label })
        assertTrue(rules.children.all { it.kind == "rule" })
        assertEquals("Order", rules.children[0].uiHints["aggregate"])
        assertEquals(Range(Position(1, 4), Position(1, 35)), rules.children[0].source.range)
        assertEquals("$zdlUri#rules.Order.minimumAmount", rules.children[0].id)
    }

    @Test
    fun zdlOutlineIncludesRulesDeclaredWithPolicies() {
        val text = zdl.replace("rules (Order)", "policies (Order)")
        val rules = server().opened(zdlUri, text).hierarchy(zdlUri).single { it.label == "rules" }
        assertEquals(listOf("minimumAmount", "stock-first", "audit"), rules.children.map { it.label })
    }

    @Test
    fun zdlOutlineIsUnchangedWithoutRules() {
        val text = "entity Order { name String }"
        assertTrue(server().opened(zdlUri, text).hierarchy(zdlUri).none { it.label == "rules" })
    }

    @Test
    fun zdlCompletionInsideRulesOffersDeclaredNames() {
        val text = zdl.replace("@rules(minimumAmount, \"stock-first\", nope)", "@rules(%s)")

        val all = server().opened(zdlUri, text.replace("%s", "")).completion(zdlUri, (text.replace("%s", "")).at("@rules(", offsetInto = 7))
        assertEquals(listOf("audit", "minimumAmount", "stock-first"), all.map { it.label })
        assertEquals("\"stock-first\"", all.single { it.label == "stock-first" }.insertText)
        assertEquals("minimumAmount", all.single { it.label == "minimumAmount" }.insertText)
        assertTrue(all.single { it.label == "audit" }.documentation!!.contains("audited"))

        val typed = text.replace("%s", "minimumAmount, au")
        val afterComma = server().opened(zdlUri, typed).completion(zdlUri, typed.at("au)", offsetInto = 2))
        assertEquals(listOf("audit"), afterComma.map { it.label })

        val inQuotes = text.replace("%s", "\"stock")
        val quoted = server().opened(zdlUri, inQuotes).completion(zdlUri, inQuotes.at("\"stock", offsetInto = 6))
        assertEquals(listOf("stock-first"), quoted.map { it.label })
        assertEquals("stock-first", quoted.single().insertText)
    }

    @Test
    fun zdlCompletionOutsideRulesIsEmpty() {
        val server = server().opened(zdlUri, zdl)
        assertTrue(server.completion(zdlUri, zdl.at("placeOrder")).isEmpty())
        // after the closing parenthesis
        assertTrue(server.completion(zdlUri, zdl.at("nope)", offsetInto = 5)).isEmpty())
    }

    @Test
    fun zdlUnknownRuleIsAWarningOnTheName() {
        val diagnostics = server().opened(zdlUri, zdl).diagnostics(zdlUri)
        val warning = diagnostics.single()
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertEquals("No rule 'nope' is declared.", warning.message)
        assertEquals("nope", zdl.slice(warning.range))
    }

    @Test
    fun zdlPoliciesKeywordHasAQuickFixToRules() {
        val text = zdl.replace("rules (Order)", "policies (Order)")
        val server = server().opened(zdlUri, text)
        val deprecation = server.diagnostics(zdlUri).single { it.message.contains("'policies' is deprecated") }
        assertEquals(DiagnosticSeverity.WARNING, deprecation.severity)

        val actions = server.codeActions(zdlUri, deprecation.range)
        val fix = actions.single()
        assertEquals("quickfix", fix.kind)
        assertEquals("Replace 'policies' with 'rules'", fix.title)
        val fixed = text.apply(fix)
        assertTrue(fixed.startsWith("rules (Order) {"), fixed)
        assertTrue(server().opened(zdlUri, fixed).diagnostics(zdlUri).none { it.message.contains("deprecated") })
    }

    @Test
    fun zdlPolicyAnnotationHasAQuickFixToRules() {
        val text = zdl.replace("@rules(minimumAmount, \"stock-first\", nope)", "@policy(minimumAmount, \"stock-first\")")
        val server = server().opened(zdlUri, text)
        val deprecation = server.diagnostics(zdlUri).single { it.message.contains("'@policy' is deprecated") }
        assertEquals("@policy(minimumAmount, \"stock-first\")", text.slice(deprecation.range), "the range covers the whole annotation")

        // asked from inside the annotation, as the editor does for the cursor
        val fix = server.codeActions(zdlUri, Range(text.at("minimumAmount", occurrence = 1), text.at("minimumAmount", occurrence = 1))).single()
        assertEquals("Replace '@policy' with '@rules'", fix.title)
        val fixed = text.apply(fix)
        assertTrue("@rules(minimumAmount, \"stock-first\")" in fixed, fixed)
        assertTrue(server().opened(zdlUri, fixed).diagnostics(zdlUri).isEmpty())
    }

    @Test
    fun noQuickFixWhereThereIsNoDeprecationWarning() {
        val server = server().opened(zdlUri, zdl)
        assertTrue(server.codeActions(zdlUri, Range(zdl.at("nope"), zdl.at("nope"))).isEmpty())
    }

    // ---------- ZFL ----------

    private val zfl = """
        flow PlaceOrder {
            rules (Order) {
                minimumAmount "At least 10 EUR"
                stock-first "Stock is reserved before payment"
            }
            rules {
                audit "Every change is audited"
            }
            start PlaceOrderRequested {}
            @rules(minimumAmount, "stock-first")
            when PlaceOrderRequested do placeOrder
        }

        flow Refund {
            rules {
                refundLimit "Refunds are capped"
            }
            start RefundRequested {}
            @rules(refundLimit, minimumAmount)
            when RefundRequested do refund
        }
    """.trimIndent()

    @Test
    fun zflHoverOnADeclarationAndOnANameInRules() {
        val server = server().opened(zflUri, zfl)

        val onDeclaration = assertNotNull(server.hover(zflUri, zfl.at("stock-first")))
        assertTrue("### stock-first" in onDeclaration.markdown)
        assertTrue("aggregate: `Order`" in onDeclaration.markdown)
        assertEquals("stock-first", zfl.slice(assertNotNull(onDeclaration.range)))

        val onName = assertNotNull(server.hover(zflUri, zfl.at("\"stock-first\"", offsetInto = 3)))
        assertTrue("Stock is reserved before payment" in onName.markdown, onName.markdown)
        assertEquals("\"stock-first\"", zfl.slice(assertNotNull(onName.range)))
    }

    @Test
    fun zflRulesResolveInTheirOwnFlowOnly() {
        val server = server().opened(zflUri, zfl)

        val own = server.definition(zflUri, zfl.at("refundLimit", occurrence = 1)).single()
        assertEquals(15, own.range!!.start.line)

        // minimumAmount is declared in PlaceOrder, not in Refund
        assertTrue(server.definition(zflUri, zfl.at("minimumAmount", occurrence = 2)).isEmpty())
        assertNull(server.hover(zflUri, zfl.at("minimumAmount", occurrence = 2)))

        val definition = server.definition(zflUri, zfl.at("minimumAmount", occurrence = 1)).single()
        assertEquals(Range(Position(2, 8), Position(2, 21)), definition.range)
    }

    @Test
    fun zflOutlineListsTheRulesOfEachFlow() {
        val flows = server().opened(zflUri, zfl).hierarchy(zflUri).single { it.label == "flows" }
        val placeOrder = flows.children.single { it.label == "PlaceOrder" }
        assertEquals(listOf("minimumAmount", "stock-first", "audit"), placeOrder.children.filter { it.kind == "rule" }.map { it.label })
        val refund = flows.children.single { it.label == "Refund" }
        assertEquals(listOf("refundLimit"), refund.children.filter { it.kind == "rule" }.map { it.label })
        val rule = placeOrder.children.first { it.kind == "rule" }
        assertEquals("Order", rule.uiHints["aggregate"])
        assertEquals(Range(Position(2, 8), Position(2, 39)).start, rule.source.range.start)
        assertTrue(flatten(placeOrder.children).any { it.kind == "policy" }, "the when block is still listed")
    }

    @Test
    fun zflCompletionOffersTheRulesOfTheFlow() {
        val text = zfl.replace("@rules(refundLimit, minimumAmount)", "@rules(refundLimit, )")
        val items = server().opened(zflUri, text).completion(zflUri, text.at("refundLimit, )", offsetInto = 13))
        assertEquals(listOf("refundLimit"), items.map { it.label })

        val placeOrder = zfl.replace("@rules(minimumAmount, \"stock-first\")", "@rules(m)")
        val typed = server().opened(zflUri, placeOrder).completion(zflUri, placeOrder.at("(m)", offsetInto = 2))
        assertEquals(listOf("minimumAmount"), typed.map { it.label })

        val empty = zfl.replace("@rules(minimumAmount, \"stock-first\")", "@rules()")
        val all = server().opened(zflUri, empty).completion(zflUri, empty.at("@rules()", offsetInto = 7))
        assertEquals(listOf("audit", "minimumAmount", "stock-first"), all.map { it.label })
        assertEquals("\"stock-first\"", all.single { it.label == "stock-first" }.insertText)
    }

    @Test
    fun zflUnknownRuleIsAWarningOnTheName() {
        val warning = server().opened(zflUri, zfl).diagnostics(zflUri).single { it.message.contains("no rule") }
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertEquals("Flow 'Refund' has no rule 'minimumAmount'.", warning.message)
        assertEquals("minimumAmount", zfl.slice(warning.range))
        assertEquals(zfl.at("minimumAmount", occurrence = 2, offsetInto = 0), warning.range.start)
    }

    @Test
    fun zflQuotedUnknownRuleWarnsOnTheWholeString() {
        val text = zfl.replace("@rules(minimumAmount, \"stock-first\")", "@rules(\"no-such\")")
        val warning = server().opened(zflUri, text).diagnostics(zflUri).single { it.message.contains("'no-such'") }
        assertEquals("\"no-such\"", text.slice(warning.range))
    }

    @Test
    fun zflWithKnownRulesHasNoRuleWarnings() {
        val text = zfl.replace("@rules(refundLimit, minimumAmount)", "@rules(refundLimit)")
        val diagnostics: List<Diagnostic> = server().opened(zflUri, text).diagnostics(zflUri)
        assertTrue(diagnostics.none { it.message.contains("rule") }, diagnostics.toString())
    }

    private val qualifiedZdl = """
        rules (Order) {
            limit "At most 100 EUR"
            stock-first "Stock first"
        }
        entity Order { name String }
        aggregate OrderAggregate(Order) {
        }
        service OrderService for (OrderAggregate) {
            @rules(Order.limit, "stock-first")
            placeOrder(Order) Order
        }
    """.trimIndent()

    @Test
    fun zdlUnquotedQualifiedNameHoversResolvesAndHasNoWarning() {
        val server = server().opened(zdlUri, qualifiedZdl)
        assertTrue(server.diagnostics(zdlUri).none { it.message.contains("rule") }, server.diagnostics(zdlUri).toString())

        val onReference = qualifiedZdl.at("@rules(Order.limit", offsetInto = 13)
        val hover = assertNotNull(server.hover(zdlUri, onReference))
        assertTrue("At most 100 EUR" in hover.markdown, hover.markdown)
        assertEquals("Order.limit", qualifiedZdl.slice(hover.range!!))

        val definition = server.definition(zdlUri, onReference).single()
        assertEquals(qualifiedZdl.at("limit \"At most", offsetInto = 0), definition.range!!.start)
    }

    @Test
    fun zdlUnquotedQualifiedUnknownNameWarnsOnTheWholeName() {
        val text = qualifiedZdl.replace("@rules(Order.limit,", "@rules(Order.nope,")
        val warning = server().opened(zdlUri, text).diagnostics(zdlUri).single { it.message.contains("nope") }
        assertEquals("Order.nope", text.slice(warning.range))
    }

    @Test
    fun zdlQualifiedNameCompletionOffersDeclaredNamesWhileTyped() {
        val typed = qualifiedZdl.replace("@rules(Order.limit, \"stock-first\")", "@rules(Order.li)")
        val items = server().opened(zdlUri, typed).completion(zdlUri, typed.at("Order.li)", offsetInto = 8))
        assertEquals(listOf("Order.limit"), items.map { it.label })
        assertEquals("Order.limit", items.single().insertText)

        val dotted = qualifiedZdl.replace("@rules(Order.limit, \"stock-first\")", "@rules(Order.)")
        val afterDot = server().opened(zdlUri, dotted).completion(zdlUri, dotted.at("Order.)", offsetInto = 6))
        assertEquals(listOf("Order.limit", "Order.stock-first"), afterDot.map { it.label })
        val edit = afterDot.first().textEdit!!
        assertEquals("Order.", dotted.slice(edit.range))
    }

    @Test
    fun zflUnquotedQualifiedNameWarnsOnTheWholeNameAndStaysARuleSite() {
        val text = """
            flow Refund {
                rules {
                    audit "Audited"
                }
                start RefundRequested {}
                @rules(Order.limit, audit)
                when RefundRequested do refund
            }
        """.trimIndent()
        val server = server().opened(zflUri, text)
        val warning = server.diagnostics(zflUri).let { all -> all.singleOrNull { it.message.contains("'Order.limit'") } ?: error(all.toString()) }
        assertEquals("Order.limit", text.slice(warning.range))

        val position = text.at("@rules(Order.limit", offsetInto = 13)
        assertTrue(server.definition(zflUri, position).none { it.range == null }, "answers at the name, not the command")
        assertEquals(null, server.hover(zflUri, position), "no such rule to show")
        val completion = server.completion(zflUri, text.at("@rules(Order.limit, audit", offsetInto = 20))
        assertEquals(listOf("audit"), completion.map { it.label })
    }

    private val aggregateFlow = """
        flow Refund {
            rules (Order) {
                limit "At most 100 EUR"
            }
            rules (Payment) {
                limit "Payment limit"
            }
            start RefundRequested {}
            @rules(Order.limit, "Payment.limit")
            when RefundRequested do refund
        }
    """.trimIndent()

    @Test
    fun zflQualifiedNameResolvesToTheAggregateRule() {
        val server = server().opened(zflUri, aggregateFlow)
        assertTrue(server.diagnostics(zflUri).none { it.message.contains("rule") }, server.diagnostics(zflUri).toString())

        val onOrder = aggregateFlow.at("@rules(Order.limit", offsetInto = 13)
        val hover = assertNotNull(server.hover(zflUri, onOrder))
        assertTrue("At most 100 EUR" in hover.markdown && "Payment limit" !in hover.markdown, hover.markdown)
        val definition = server.definition(zflUri, onOrder).single()
        assertEquals(aggregateFlow.at("limit \"At most", offsetInto = 0), definition.range!!.start)

        val onPayment = aggregateFlow.at("Payment.limit\")", offsetInto = 3)
        assertEquals(aggregateFlow.at("limit \"Payment", offsetInto = 0), server.definition(zflUri, onPayment).single().range!!.start)
    }

    @Test
    fun zflBareNameStillMatchesRulesOfSeveralAggregates() {
        val text = aggregateFlow.replace("@rules(Order.limit, \"Payment.limit\")", "@rules(limit)")
        val definitions = server().opened(zflUri, text).definition(zflUri, text.at("@rules(limit", offsetInto = 9))
        assertEquals(2, definitions.size, definitions.toString())
    }

    @Test
    fun zflCompletionOffersTheQualifiedNameOnceADotIsTyped() {
        val text = aggregateFlow.replace("@rules(Order.limit, \"Payment.limit\")", "@rules(Pay)")
        val bare = server().opened(zflUri, text).completion(zflUri, text.at("Pay)", offsetInto = 3))
        assertTrue(bare.none { '.' in it.label }, bare.toString())

        val dotted = aggregateFlow.replace("@rules(Order.limit, \"Payment.limit\")", "@rules(Pay.)").replace("Pay.", "Payment.")
        val items = server().opened(zflUri, dotted).completion(zflUri, dotted.at("Payment.)", offsetInto = 8))
        assertEquals(listOf("Payment.limit"), items.map { it.label })
    }
}
