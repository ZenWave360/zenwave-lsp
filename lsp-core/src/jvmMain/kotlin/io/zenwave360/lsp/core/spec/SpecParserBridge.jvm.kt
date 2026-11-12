package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.dereferenceBlocking
import io.zenwave360.jsonrefparser.parseBlocking
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions

internal actual object SpecParserBridge {
    actual fun parseText(
        text: String,
        baseUri: String,
        options: RefParserOptions,
        loaders: List<DocumentLoader>?,
    ): ParsedDocument =
        RefParser.fromText(text = text, baseUri = baseUri, options = options,
            loaders = referenceLoadersFor(text, baseUri, options, loaders))
            .dereferenceBlocking()
            .getParsedDocument()

    actual fun parseUri(uri: String, options: RefParserOptions, loaders: List<DocumentLoader>?): ParsedDocument =
        RefParser(uri, options = options, loaders = listOf(ReferenceDocumentLoader(uri, loaders)))
            .dereferenceBlocking()
            .getParsedDocument()

    actual fun parseRaw(text: String, baseUri: String): ParsedDocument =
        RefParser.fromText(text, baseUri, loaders = emptyList()).parseBlocking().getParsedDocument()
}
