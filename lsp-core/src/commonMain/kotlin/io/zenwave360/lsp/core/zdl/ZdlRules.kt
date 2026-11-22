package io.zenwave360.lsp.core.zdl

import io.zenwave360.lsp.core.contracts.CodeAction
import io.zenwave360.lsp.core.contracts.CompletionItem
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentEdit
import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.rules.LineIndex
import io.zenwave360.lsp.core.rules.RuleDeclaration
import io.zenwave360.lsp.core.rules.containsInclusive
import io.zenwave360.lsp.core.rules.ruleCompletionContext
import io.zenwave360.lsp.core.rules.ruleCompletionItems
import io.zenwave360.lsp.core.rules.ruleHoverMarkdown
import io.zenwave360.lsp.core.rules.ruleReferences
import io.zenwave360.lsp.core.rules.tokenize
import io.zenwave360.lsp.core.rules.zdlRuleBlocks

/**
 * Business rules of a ZDL document (ZFL-R11, ZFL-R12): the `rules` blocks (and the deprecated `policies`) and the
 * names in `@rules(...)` and the deprecated `@policy(...)`, which resolve against the document's own rules.
 */
internal class ZdlRules(private val uri: String, private val text: String) {
    private val tokens = tokenize(text)
    private val index = LineIndex(text)
    private val blocks = zdlRuleBlocks(text, tokens, index)
    private val declarations: List<RuleDeclaration> = blocks.flatMap { it.rules }
    private val references = ruleReferences(tokens, index, ANNOTATIONS)

    /** The rule under the cursor: a declared name or a name in `@rules(...)`. Null when the cursor is on neither. */
    private fun siteAt(position: Position): Pair<Range, RuleDeclaration?>? {
        declarations.firstOrNull { it.nameRange.containsInclusive(position) }?.let { return it.nameRange to it }
        references.firstOrNull { it.range.containsInclusive(position) }?.let { return it.range to null }
        return null
    }

    private fun declared(name: String): List<RuleDeclaration> = declarations.filter { it.name == name }

    private fun targets(position: Position): Triple<Range, String, List<RuleDeclaration>>? {
        val (range, declaration) = siteAt(position) ?: return null
        val name = declaration?.name ?: references.first { it.range == range }.name
        return Triple(range, name, if (declaration != null) listOf(declaration) else declared(name))
    }

    /** Whether the cursor is on a rule's name, declared or written in `@rules(...)`: such a place is answered here, even by nothing. */
    fun isRuleSite(position: Position): Boolean = siteAt(position) != null

    /** Hover on a declared name or a name in `@rules(...)`; null when the cursor is elsewhere or the name is not declared. */
    fun hover(position: Position): HoverResult? {
        val (range, _, matches) = targets(position) ?: return null
        if (matches.isEmpty()) return null
        return HoverResult(DocumentSymbolRef(uri, "rules.${matches.first().key}", range), ruleHoverMarkdown(matches), range)
    }

    /**
     * Where the rule under the cursor is declared: every rule of that name in the document, or the rule itself when the cursor
     * is on its declaration. Null when the cursor is not on a rule, so other resolution applies.
     */
    fun definition(position: Position): List<NavigationTarget>? {
        val (_, name, matches) = targets(position) ?: return null
        return matches.map {
            NavigationTarget(targetKind = "definition", label = name, uri = uri, range = it.nameRange, category = "zdl", relationType = "declares")
        }
    }

    fun completion(position: Position): List<CompletionItem> {
        val context = ruleCompletionContext(text, index.offset(position), ANNOTATIONS) ?: return emptyList()
        return ruleCompletionItems(declarations, context, index)
    }

    /** One `rules` section with an entry per declared rule, whichever keyword declared it. */
    fun hierarchy(): HierarchyNode? {
        if (declarations.isEmpty()) return null
        return HierarchyNode(
            id = zdlSemanticId(uri, "rules"),
            label = "rules",
            kind = "section",
            language = "zdl",
            source = SourceLocation(uri, Range(blocks.first().range.start, blocks.last().range.end)),
            children = declarations.map { rule ->
                HierarchyNode(
                    id = zdlSemanticId(uri, "rules.${rule.key}"),
                    label = rule.name,
                    kind = "rule",
                    language = "zdl",
                    source = SourceLocation(uri, rule.range),
                    children = emptyList(),
                    uiHints = buildMap { rule.aggregate?.let { put("aggregate", it) } },
                )
            },
        )
    }

    /** Quick fixes for the deprecation warnings of `policies` and `@policy`. */
    fun codeActions(diagnostics: List<Diagnostic>): List<CodeAction> =
        diagnostics.mapNotNull { diagnostic ->
            when {
                diagnostic.code == "policies" && diagnostic.message.startsWith("'policies' is deprecated") ->
                    replacement(diagnostic, "policies", "rules", "Replace 'policies' with 'rules'")
                diagnostic.code == "policy" && diagnostic.message.startsWith("'@policy' is deprecated") ->
                    replacement(diagnostic, "@policy", "@rules", "Replace '@policy' with '@rules'")
                else -> null
            }
        }

    /** Replaces [old] at the start of the diagnostic's range, only when the text there is [old]. */
    private fun replacement(diagnostic: Diagnostic, old: String, new: String, title: String): CodeAction? {
        val start = diagnostic.range.start
        val line = text.lineSequence().elementAtOrNull(start.line) ?: return null
        if (!line.startsWith(old, start.character)) return null
        val range = Range(start, Position(start.line, start.character + old.length))
        return CodeAction(title, "quickfix", listOf(DocumentEdit(uri, range, new)), listOf(diagnostic))
    }

    private companion object {
        val ANNOTATIONS = setOf("rules", "policy")
    }
}
