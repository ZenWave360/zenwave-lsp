package io.zenwave360.lsp.core.contracts

/**
 * Which documents are known to exist without reading them: the open ones, and the files the background index has
 * seen on disk. Cross-reference contributions are synchronous and cannot read, so a ZFL `@zdl` that lists several
 * locations (NAV-R9) picks the first known one for the index. A location that is not known is not a winner.
 */
fun interface KnownDocuments {
    fun isKnown(uri: String): Boolean

    companion object {
        /** Nothing is known: the first location written stands. */
        val NONE = KnownDocuments { false }
    }
}
