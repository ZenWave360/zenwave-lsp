package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions

internal expect object SpecParserBridge {
    /** [loaders] replaces the parser's default loaders for external `$ref`s; `null` keeps the defaults. */
    fun parseText(
        text: String,
        baseUri: String,
        options: RefParserOptions = RefParserOptions(),
        loaders: List<DocumentLoader>? = null,
    ): ParsedDocument

    fun parseUri(uri: String, options: RefParserOptions = RefParserOptions()): ParsedDocument
}
