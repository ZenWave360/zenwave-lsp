package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions

internal expect object SpecParserBridge {
    /** [loaders] replaces platform delegates; implicit reads retain per-reference guards on every document. */
    fun parseText(
        text: String,
        baseUri: String,
        options: RefParserOptions = RefParserOptions(),
        loaders: List<DocumentLoader>? = null,
    ): ParsedDocument

    fun parseUri(
        uri: String,
        options: RefParserOptions = RefParserOptions(),
        loaders: List<DocumentLoader>? = null,
    ): ParsedDocument

    /** Parse only, for checking raw reference strings before resolution destroys dot segments. */
    fun parseRaw(text: String, baseUri: String): ParsedDocument
}
