package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions

internal expect object SpecParserBridge {
    fun parseText(text: String, baseUri: String, options: RefParserOptions = RefParserOptions()): ParsedDocument

    fun parseUri(uri: String, options: RefParserOptions = RefParserOptions()): ParsedDocument
}
