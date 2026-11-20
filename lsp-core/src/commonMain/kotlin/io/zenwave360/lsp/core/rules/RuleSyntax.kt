package io.zenwave360.lsp.core.rules

import io.zenwave360.lsp.core.contracts.CompletionItem
import io.zenwave360.lsp.core.contracts.CompletionTextEdit
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range

/*
 * What ZDL and ZFL share of business rules (ZFL-R11, ZFL-R12): the text of a `rules` block and of `@rules(...)`.
 * dsl-kotlin records where a ZFL rule is declared and where a ZFL `@rules` name is written, but not where a ZDL
 * rule or a ZDL `@rules` name is, and an unfinished `@rules(` has no model at all, so the server reads the text.
 */

internal enum class TokenKind { IDENT, STRING, NUMBER, PUNCT }

/** [end] is exclusive. */
internal data class RuleToken(val text: String, val start: Int, val end: Int, val kind: TokenKind)

/** Lexes ZDL or ZFL text well enough to find rule syntax: comments are skipped, a string is one token, `-` belongs to a name. */
internal fun tokenize(text: String): List<RuleToken> = scan(text).first

/** Whether [text] ends inside a comment: after a `//` with no line end yet, or inside an unclosed block comment. */
internal fun endsInsideComment(text: String): Boolean = scan(text).second

/** The tokens of [text], and whether the text ends inside a comment. Strings are `"..."`, `'...'` and ZDL `"""..."""`, as in Zdl.g4 and Zfl.g4. */
private fun scan(text: String): Pair<List<RuleToken>, Boolean> {
    val tokens = mutableListOf<RuleToken>()
    var inComment = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            c == '/' && text.startsWith("//", i) -> {
                while (i < text.length && text[i] != '\n') i++
                if (i >= text.length) inComment = true
            }
            c == '/' && text.startsWith("/*", i) -> {
                val close = text.indexOf("*/", i + 2)
                if (close < 0) inComment = true
                i = if (close < 0) text.length else close + 2
            }
            c == '"' && text.startsWith("\"\"\"", i) -> {
                val close = text.indexOf("\"\"\"", i + 3)
                val j = if (close < 0) text.length else close + 3
                tokens += RuleToken(text.substring(i, j), i, j, TokenKind.STRING)
                i = j
            }
            c == '"' || c == '\'' -> {
                var j = i + 1
                while (j < text.length && text[j] != c) j += if (text[j] == '\\') 2 else 1
                j = if (j < text.length) j + 1 else text.length
                tokens += RuleToken(text.substring(i, j), i, j, TokenKind.STRING)
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '-')) j++
                tokens += RuleToken(text.substring(i, j), i, j, TokenKind.IDENT)
                i = j
            }
            c.isDigit() -> {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '.')) j++
                tokens += RuleToken(text.substring(i, j), i, j, TokenKind.NUMBER)
                i = j
            }
            else -> {
                tokens += RuleToken(c.toString(), i, i + 1, TokenKind.PUNCT)
                i++
            }
        }
    }
    return tokens to inComment
}

/** The UTF-16 offset in [text] of the [codePoints]th code point; ANTLR counts code points, Kotlin strings and LSP count UTF-16 units. */
internal fun codePointsToUtf16(text: String, codePoints: Int): Int {
    var i = 0
    var n = 0
    while (n < codePoints && i < text.length) {
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return i
}

/** Offsets to line and character positions. */
internal class LineIndex(private val text: String) {
    private val starts: List<Int> = buildList {
        add(0)
        text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }

    fun position(offset: Int): Position {
        val clamped = offset.coerceIn(0, text.length)
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= clamped) low = mid else high = mid - 1
        }
        return Position(low, clamped - starts[low])
    }

    fun offset(position: Position): Int =
        ((starts.getOrNull(position.line) ?: text.length) + position.character).coerceAtMost(text.length)

    fun range(start: Int, end: Int): Range = Range(position(start), position(end))
}

internal fun Range.containsInclusive(position: Position): Boolean {
    fun Position.before(other: Position) = line < other.line || (line == other.line && character < other.character)
    return !position.before(start) && !end.before(position)
}

internal fun RuleToken.unquoted(): String = when {
    kind != TokenKind.STRING -> text
    text.length >= 6 && text.startsWith("\"\"\"") && text.endsWith("\"\"\"") -> text.substring(3, text.length - 3)
    text.length >= 2 && text.endsWith(text[0]) -> text.substring(1, text.length - 1)
    else -> text.drop(1)
}

/** A name written in `@rules(...)` (or `@policy(...)` in ZDL). [range] covers the token, quotes included. */
internal data class RuleReference(val name: String, val range: Range, val annotation: String)

/** The names listed by each annotation of [annotations] (without `@`), such as `rules`, `policy`. */
internal fun ruleReferences(tokens: List<RuleToken>, index: LineIndex, annotations: Set<String>): List<RuleReference> {
    val result = mutableListOf<RuleReference>()
    var i = 0
    while (i < tokens.size) {
        val open = annotationOpen(tokens, i, annotations)
        if (open == null) {
            i++
            continue
        }
        var j = open + 1
        while (j < tokens.size && tokens[j].text != ")") {
            val token = tokens[j]
            val previous = tokens[j - 1].text
            if ((token.kind == TokenKind.IDENT || token.kind == TokenKind.STRING) && previous in listOf("(", ",", "[")) {
                result += RuleReference(token.unquoted(), index.range(token.start, token.end), tokens[i + 1].text)
            }
            j++
        }
        i = j
    }
    return result
}

/** When tokens at [i] start `@name(` for one of [annotations], the index of the `(`. */
private fun annotationOpen(tokens: List<RuleToken>, i: Int, annotations: Set<String>): Int? {
    val at = tokens[i]
    val name = tokens.getOrNull(i + 1) ?: return null
    val paren = tokens.getOrNull(i + 2) ?: return null
    if (at.text != "@" || at.kind != TokenKind.PUNCT || name.kind != TokenKind.IDENT || name.start != at.end) return null
    if (name.text !in annotations || paren.text != "(") return null
    return i + 2
}

/**
 * A cursor inside `@rules(` ... `)`: what has been typed of the name. [replaceStart] and [replaceEnd] are the offsets the chosen
 * name replaces: the typed prefix with its opening quote, and the closing quote when one follows the cursor.
 */
internal data class RuleCompletionContext(val prefix: String, val quoted: Boolean, val replaceStart: Int, val replaceEnd: Int, val quote: Char = '"')

/** The completion context of [offset], or null when it is not inside such an annotation's argument list, or inside a comment. */
internal fun ruleCompletionContext(text: String, offset: Int, annotations: Set<String>): RuleCompletionContext? {
    val before = text.substring(0, offset.coerceIn(0, text.length))
    if (endsInsideComment(before)) return null
    val tokens = tokenize(before)
    var open = -1
    for (i in tokens.indices) {
        annotationOpen(tokens, i, annotations)?.let { open = it }
    }
    if (open < 0 || tokens.drop(open + 1).any { it.text == ")" }) return null
    val last = tokens.last()
    val separators = listOf("(", ",", "[")
    val here = before.length
    if (last.end == here && (last.kind == TokenKind.IDENT || (last.kind == TokenKind.STRING && !last.text.closed()))) {
        val previous = tokens.getOrNull(tokens.size - 2)?.text
        if (previous !in separators) return null
        if (last.kind == TokenKind.IDENT) return RuleCompletionContext(last.text, false, last.start, here)
        if (last.text.startsWith("\"\"\"")) return null
        val quote = last.text[0]
        val end = if (text.getOrNull(here) == quote) here + 1 else here
        return RuleCompletionContext(last.text.drop(1), true, last.start, end, quote)
    }
    // After `(`, `,` or `[`: a new name starts.
    return if (last.text in separators && last.end <= here) RuleCompletionContext("", false, here, here) else null
}

private fun String.closed(): Boolean = length >= 2 && endsWith(this[0]) && !startsWith("\"\"\"")

/** A rule as declared: its [key] is `<aggregate>.<name>` or `<name>`, as dsl-kotlin keys `rules`. */
internal data class RuleDeclaration(
    val name: String,
    val aggregate: String?,
    val value: String?,
    val nameRange: Range,
    /** The whole entry, from the name to the end of its value. */
    val range: Range,
) {
    val key: String get() = if (aggregate != null) "$aggregate.$name" else name
}

/** A `rules (Aggregate) { ... }` or deprecated `policies (Aggregate) { ... }` block. */
internal data class RuleBlock(val keyword: String, val keywordRange: Range, val aggregate: String?, val range: Range, val rules: List<RuleDeclaration>)

/** The top-level `rules` and `policies` blocks of a ZDL document. */
internal fun zdlRuleBlocks(tokens: List<RuleToken>, index: LineIndex): List<RuleBlock> {
    val blocks = mutableListOf<RuleBlock>()
    var depth = 0
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        when {
            token.text == "{" && token.kind == TokenKind.PUNCT -> { depth++; i++ }
            token.text == "}" && token.kind == TokenKind.PUNCT -> { depth--; i++ }
            depth == 0 && token.kind == TokenKind.IDENT && (token.text == "rules" || token.text == "policies") &&
                startsStatement(tokens, i) -> {
                val block = readBlock(tokens, i, index)
                if (block == null) {
                    i++
                } else {
                    blocks += block.first
                    i = block.second
                }
            }
            else -> i++
        }
    }
    return blocks
}

/** A ZFL flow with the `rules` blocks written directly inside it. [range] runs to the closing `}`, or to the end of an unfinished text. */
internal data class FlowRules(val name: String, val range: Range, val blocks: List<RuleBlock>)

/** The flows of a ZFL document and their `rules` blocks, read from the text so an unfinished document still has them. */
internal fun zflFlowRules(tokens: List<RuleToken>, index: LineIndex, textLength: Int): List<FlowRules> {
    val flows = mutableListOf<FlowRules>()
    var depth = 0
    var name: String? = null
    var flowStart = 0
    var blocks = mutableListOf<RuleBlock>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        when {
            token.text == "{" && token.kind == TokenKind.PUNCT -> { depth++; i++ }
            token.text == "}" && token.kind == TokenKind.PUNCT -> {
                depth--
                if (depth == 0 && name != null) {
                    flows += FlowRules(name, index.range(flowStart, token.end), blocks)
                    name = null
                }
                i++
            }
            depth == 0 && token.text == "flow" && token.kind == TokenKind.IDENT &&
                tokens.getOrNull(i + 1)?.kind == TokenKind.IDENT && tokens.getOrNull(i + 2)?.text == "{" -> {
                name = tokens[i + 1].text
                flowStart = token.start
                blocks = mutableListOf()
                depth = 1
                i += 3
            }
            depth == 1 && name != null && token.kind == TokenKind.IDENT && token.text == "rules" -> {
                val block = readBlock(tokens, i, index)
                if (block == null) {
                    i++
                } else {
                    blocks += block.first
                    i = block.second
                }
            }
            else -> i++
        }
    }
    name?.let { flows += FlowRules(it, index.range(flowStart, textLength), blocks) }
    return flows
}

/** `rules` begins a block after nothing, after the previous block's `}` or after an import's string. */
private fun startsStatement(tokens: List<RuleToken>, i: Int): Boolean {
    val previous = tokens.getOrNull(i - 1) ?: return true
    return previous.text == "}" || previous.kind == TokenKind.STRING
}

private fun readBlock(tokens: List<RuleToken>, i: Int, index: LineIndex): Pair<RuleBlock, Int>? {
    val keyword = tokens[i]
    var j = i + 1
    var aggregate: String? = null
    if (tokens.getOrNull(j)?.text == "(") {
        val name = tokens.getOrNull(j + 1)
        if (name?.kind != TokenKind.IDENT || tokens.getOrNull(j + 2)?.text != ")") return null
        aggregate = name.text
        j += 3
    }
    if (tokens.getOrNull(j)?.text != "{") return null
    val open = tokens[j]
    j++
    val rules = mutableListOf<RuleDeclaration>()
    while (j < tokens.size && tokens[j].text != "}") {
        val name = tokens[j]
        if (name.kind != TokenKind.IDENT) {
            j++
            continue
        }
        val value = tokens.getOrNull(j + 1)?.takeIf { it.kind == TokenKind.STRING || it.kind == TokenKind.NUMBER || it.kind == TokenKind.IDENT }
        val last = value ?: name
        rules += RuleDeclaration(
            name = name.text,
            aggregate = aggregate,
            value = value?.let { if (it.kind == TokenKind.STRING) it.unquoted().trimIndent().trim() else it.text },
            nameRange = index.range(name.start, name.end),
            range = index.range(name.start, last.end),
        )
        j += if (value != null) 2 else 1
    }
    val end = tokens.getOrNull(j)?.end ?: open.end
    return RuleBlock(keyword.text, index.range(keyword.start, keyword.end), aggregate, index.range(keyword.start, end), rules) to (j + 1)
}

/** The text of a rule hover. */
internal fun ruleHoverMarkdown(declarations: List<RuleDeclaration>): String =
    declarations.joinToString("\n\n") { rule ->
        buildList {
            add("### ${rule.name}")
            add("")
            add("- kind: `rule`")
            rule.aggregate?.let { add("- aggregate: `$it`") }
            rule.value?.takeIf { it.isNotBlank() }?.let {
                add("")
                add(it)
            }
        }.joinToString("\n")
    }

/** One completion item per distinct declared name that starts with what was typed. A name with `-` is written quoted. */
internal fun ruleCompletionItems(declarations: List<RuleDeclaration>, context: RuleCompletionContext, index: LineIndex): List<CompletionItem> =
    declarations
        .filter { it.name.startsWith(context.prefix, ignoreCase = true) }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase() }
        .map { rule ->
            val quote = context.quote
            val text = when {
                context.quoted -> "$quote${rule.name}$quote"
                rule.name.contains('-') -> "\"${rule.name}\""
                else -> rule.name
            }
            CompletionItem(
                label = rule.name,
                kind = "rule",
                detail = listOfNotNull(rule.aggregate?.let { "($it)" }, rule.value?.lineSequence()?.firstOrNull()).joinToString(" ").ifBlank { null },
                insertText = if (context.quoted) rule.name else text,
                documentation = rule.value,
                textEdit = CompletionTextEdit(index.range(context.replaceStart, context.replaceEnd), text),
            )
        }
