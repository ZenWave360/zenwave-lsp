package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal actual object SpecParserBridge {
    actual fun parseText(
        text: String,
        baseUri: String,
        options: RefParserOptions,
        loaders: List<DocumentLoader>?,
    ): ParsedDocument =
        runSynchronously {
            RefParser.fromText(text = text, baseUri = baseUri, options = options,
                loaders = referenceLoadersFor(text, baseUri, options, loaders))
                .dereference()
                .getParsedDocument()
        }

    actual fun parseUri(uri: String, options: RefParserOptions, loaders: List<DocumentLoader>?): ParsedDocument =
        runSynchronously {
            RefParser(uri, options = options, loaders = listOf(ReferenceDocumentLoader(uri, loaders)))
                .dereference()
                .getParsedDocument()
        }

    actual fun parseRaw(text: String, baseUri: String): ParsedDocument =
        runSynchronously { RefParser.fromText(text, baseUri, loaders = emptyList()).parse().getParsedDocument() }

    private fun <T> runSynchronously(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(resumeResult: Result<T>) {
                result = resumeResult
            }
        })
        return result!!.getOrThrow()
    }
}
