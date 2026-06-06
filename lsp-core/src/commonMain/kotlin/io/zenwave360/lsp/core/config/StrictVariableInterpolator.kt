package io.zenwave360.lsp.core.config

data class InterpolationResult(
    val value: String,
    val unresolvedVariables: List<String> = emptyList(),
)

object StrictVariableInterpolator {
    private val placeholderPattern = Regex("""\{\{\s*([A-Za-z0-9_.-]+)\s*}}""")

    fun interpolate(value: String, properties: Map<String, String>): InterpolationResult {
        val unresolved = linkedSetOf<String>()
        val resolved = placeholderPattern.replace(value) { match ->
            val name = match.groupValues[1]
            properties[name] ?: run {
                unresolved += name
                match.value
            }
        }
        return InterpolationResult(
            value = resolved,
            unresolvedVariables = unresolved.toList()
        )
    }
}
