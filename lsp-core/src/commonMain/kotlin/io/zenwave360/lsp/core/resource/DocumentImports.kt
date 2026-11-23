package io.zenwave360.lsp.core.resource

/** One reference a ZDL, ZFL or ZW document writes to another document, and where it writes it. */
data class DocumentImport(val reference: String, val line: Int, val start: Int, val end: Int)

/**
 * The documents a model document imports (AR-R9): the `@import("...")` of ZDL, ZFL and ZW, and the `@zdl("...")` of a
 * ZFL system, which names the ZDL it annotates. Found in the text, like the other references of
 * [ZenwaveResourceService]; nothing is resolved here, [ZenwaveResourceService.readImport] does that.
 */
object DocumentImports {
    private val IMPORT = Regex("""@(?:import|zdl)\s*\(\s*(?:[A-Za-z_]\w*\s*:\s*)?(?:"([^"\\]*)"|'([^'\\]*)')""")

    /** The extensions of the documents the language features take (AR-R8); anything else is never read as a model. */
    val MODEL_EXTENSIONS = listOf(".zdl", ".zfl", ".zw")

    fun isModelDocument(path: String): Boolean {
        val name = path.substringBefore('#').substringBefore('?')
        return MODEL_EXTENSIONS.any { name.endsWith(it) }
    }

    fun inText(text: String): List<DocumentImport> =
        text.split('\n').flatMapIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            IMPORT.findAll(line).mapNotNull { match ->
                val before = line.substring(0, match.range.first)
                val trimmed = line.trimStart()
                // A commented line is not an import. A `//` after a `:` is a URL, not a comment.
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@mapNotNull null
                if (Regex("""(?<!:)//""").containsMatchIn(before)) return@mapNotNull null
                val reference = match.groups[1]?.value ?: match.groups[2]?.value ?: return@mapNotNull null
                if (reference.isEmpty()) return@mapNotNull null
                // The match ends with the closing quote, so the value ends just before its last character.
                DocumentImport(reference, index, match.range.last - reference.length, match.range.last)
            }.toList()
        }
}
