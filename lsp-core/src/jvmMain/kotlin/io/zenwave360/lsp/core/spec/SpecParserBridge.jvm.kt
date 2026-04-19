package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.dereferenceBlocking
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions

internal actual object SpecParserBridge {
    actual fun parseText(text: String, baseUri: String, options: RefParserOptions): ParsedDocument =
        RefParser.fromText(text = text, baseUri = baseUri, options = options)
            .dereferenceBlocking()
            .getParsedDocument()

    actual fun parseUri(uri: String, options: RefParserOptions): ParsedDocument =
        RefParser(uri, options = options)
            .dereferenceBlocking()
            .getParsedDocument()
}
