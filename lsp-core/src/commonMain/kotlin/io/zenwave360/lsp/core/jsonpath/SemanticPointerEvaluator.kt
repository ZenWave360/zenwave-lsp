package io.zenwave360.lsp.core.jsonpath

import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.SourceLocation

object SemanticPointerEvaluator {

    fun evaluate(model: Any?, path: String): Any? {
        var current = model
        for (segment in parse(path)) {
            current = when {
                current is Map<*, *> && segment is Segment.Property ->
                    current[segment.name]

                current is List<*> && segment is Segment.Index ->
                    current.getOrNull(segment.index)

                current is List<*> && segment is Segment.Property ->
                    current.firstOrNull { candidate ->
                        val stableName = stableName(candidate)
                        stableName != null && stableName == segment.name
                    }

                else -> null
            }

            if (current == null) return null
        }
        return current
    }

    fun locationOf(locationTable: Map<String, SourceLocation>, path: String): SourceLocation? =
        locationTable[normalize(path)]

    fun pathAtPosition(locationTable: Map<String, SourceLocation>, position: Position): String? =
        locationTable
            .asSequence()
            .filter { (_, location) -> contains(location, position) }
            .sortedWith(
                compareBy<Map.Entry<String, SourceLocation>>(
                    { spanScore(it.value) },
                    { depthScore(it.key) },
                    { it.key.length }
                )
            )
            .map { it.key }
            .firstOrNull()

    fun normalize(path: String): String =
        serialize(parse(path))

    fun appendProperty(path: String, property: String): String =
        serialize(parse(path) + Segment.Property(property))

    fun appendIndex(path: String, index: Int): String =
        serialize(parse(path) + Segment.Index(index))

    internal fun stableName(node: Any?): String? =
        (node as? Map<*, *>)?.get("name") as? String

    internal sealed interface Segment {
        data class Property(val name: String) : Segment
        data class Index(val index: Int) : Segment
    }

    internal fun parse(path: String): List<Segment> {
        require(path.isNotBlank()) { "Semantic path cannot be blank" }
        require(path[0] == '$') { "Semantic path must start with '$'" }

        val segments = mutableListOf<Segment>()
        var index = 1
        while (index < path.length) {
            when (val current = path[index]) {
                '.' -> {
                    index++
                    val start = index
                    while (index < path.length && isSimpleIdentifierChar(path[index])) {
                        index++
                    }
                    require(index > start) { "Expected property name after '.' in $path" }
                    segments += Segment.Property(path.substring(start, index))
                }

                '[' -> {
                    index++
                    require(index < path.length) { "Unterminated bracket segment in $path" }
                    val next = path[index]
                    when {
                        next == '\'' || next == '"' -> {
                            val quote = next
                            index++
                            val builder = StringBuilder()
                            while (index < path.length && path[index] != quote) {
                                val ch = path[index]
                                if (ch == '\\') {
                                    require(index + 1 < path.length) { "Invalid escape sequence in $path" }
                                    index++
                                    builder.append(path[index])
                                } else {
                                    builder.append(ch)
                                }
                                index++
                            }
                            require(index < path.length && path[index] == quote) { "Unterminated string segment in $path" }
                            index++
                            require(index < path.length && path[index] == ']') { "Expected closing bracket in $path" }
                            index++
                            segments += Segment.Property(builder.toString())
                        }

                        next.isDigit() -> {
                            val start = index
                            while (index < path.length && path[index].isDigit()) {
                                index++
                            }
                            require(index < path.length && path[index] == ']') { "Expected closing bracket in $path" }
                            val numeric = path.substring(start, index)
                            index++
                            segments += Segment.Index(numeric.toInt())
                        }

                        else -> error("Unsupported bracket syntax in $path at position $index")
                    }
                }

                else -> error("Unsupported semantic path syntax in $path at position $index ('$current')")
            }
        }
        return segments
    }

    private fun serialize(segments: List<Segment>): String =
        buildString {
            append('$')
            segments.forEach { segment ->
                when (segment) {
                    is Segment.Index -> append('[').append(segment.index).append(']')
                    is Segment.Property -> {
                        if (isSimpleIdentifier(segment.name)) {
                            append('.').append(segment.name)
                        } else {
                            append("['")
                            append(segment.name.replace("\\", "\\\\").replace("'", "\\'"))
                            append("']")
                        }
                    }
                }
            }
        }

    private fun contains(location: SourceLocation, position: Position): Boolean {
        if (compare(position, location.range.start) < 0) return false
        return compare(position, location.range.end) <= 0
    }

    private fun compare(left: Position, right: Position): Int =
        when {
            left.line != right.line -> left.line.compareTo(right.line)
            else -> left.character.compareTo(right.character)
        }

    private fun spanScore(location: SourceLocation): Int {
        val lineDelta = (location.range.end.line - location.range.start.line) * 10_000
        val characterDelta = location.range.end.character - location.range.start.character
        return lineDelta + characterDelta
    }

    private fun depthScore(path: String): Int =
        -parse(path).size

    private fun isSimpleIdentifier(value: String): Boolean =
        value.isNotEmpty() &&
            isSimpleIdentifierStart(value[0]) &&
            value.drop(1).all(::isSimpleIdentifierChar)

    private fun isSimpleIdentifierStart(ch: Char): Boolean =
        ch == '_' || ch.isLetter()

    private fun isSimpleIdentifierChar(ch: Char): Boolean =
        isSimpleIdentifierStart(ch) || ch.isDigit()
}
