package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.CompletionItem
import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.rules.FlowRules
import io.zenwave360.lsp.core.rules.LineIndex
import io.zenwave360.lsp.core.rules.RuleDeclaration
import io.zenwave360.lsp.core.rules.containsInclusive
import io.zenwave360.lsp.core.rules.ruleCompletionContext
import io.zenwave360.lsp.core.rules.ruleCompletionItems
import io.zenwave360.lsp.core.rules.ruleHoverMarkdown
import io.zenwave360.lsp.core.rules.ruleReferences
import io.zenwave360.lsp.core.rules.tokenize
import io.zenwave360.lsp.core.rules.zflFlowRules

/**
 * Business rules of a ZFL document (ZFL-R11, ZFL-R12): the `rules` blocks of each flow and the names in `@rules(...)`,
 * which resolve against the rules of the flow they are written in. Read from the text, so a flow that does not parse yet,
 * as while a name is being typed, still has its rules.
 */
internal class ZflRules(private val uri: String, private val text: String) {
    private val tokens = tokenize(text)
    private val index = LineIndex(text)
    private val references = ruleReferences(tokens, index, setOf("rules"))
    private val flows: List<FlowRules> = zflFlowRules(text, tokens, index, text.length)

    private fun rulesOf(flow: FlowRules): List<RuleDeclaration> = flow.blocks.flatMap { it.rules }

    private fun flowAt(position: Position): FlowRules? = flows.firstOrNull { it.range.containsInclusive(position) }

    private class Site(val range: Range, val name: String, val flow: FlowRules?, val matches: List<RuleDeclaration>)

    private fun siteAt(position: Position): Site? {
        flows.forEach { flow ->
            rulesOf(flow).firstOrNull { it.nameRange.containsInclusive(position) }?.let { return Site(it.nameRange, it.name, flow, listOf(it)) }
        }
        val reference = references.firstOrNull { it.range.containsInclusive(position) } ?: return null
        val flow = flowAt(reference.range.start)
        return Site(reference.range, reference.name, flow, flow?.let(::rulesOf).orEmpty().filter { it.name == reference.name })
    }

    /** Whether the cursor is on a rule's name, declared or written in `@rules(...)`: such a place is answered here, even by nothing. */
    fun isRuleSite(position: Position): Boolean = siteAt(position) != null

    fun hover(position: Position): HoverResult? {
        val site = siteAt(position) ?: return null
        val first = site.matches.firstOrNull() ?: return null
        return HoverResult(
            DocumentSymbolRef(uri, "flows.${site.flow?.name}.rules.${first.name}", site.range),
            ruleHoverMarkdown(site.matches),
            site.range,
        )
    }

    /** Where the rule is declared, in the same flow. */
    fun definition(position: Position): List<NavigationTarget> =
        siteAt(position)?.let { site ->
            site.matches.map {
                NavigationTarget(targetKind = "definition", label = site.name, uri = uri, range = it.nameRange, category = "zfl", relationType = "declares")
            }
        }.orEmpty()

    fun completion(position: Position): List<CompletionItem> {
        val context = ruleCompletionContext(text, index.offset(position), setOf("rules")) ?: return emptyList()
        val flow = flowAt(position) ?: return emptyList()
        return ruleCompletionItems(rulesOf(flow), context, index)
    }

    /** The outline entries of a flow's rules. */
    fun hierarchy(flowName: String): List<HierarchyNode> =
        flows.firstOrNull { it.name == flowName }?.let(::rulesOf).orEmpty().map { rule ->
            HierarchyNode(
                id = zflSemanticId(uri, "flows.$flowName.rules.${rule.name}"),
                label = rule.name,
                kind = "rule",
                language = "zfl",
                source = SourceLocation(uri, rule.range),
                children = emptyList(),
                uiHints = buildMap { rule.aggregate?.let { put("aggregate", it) } },
            )
        }
}
