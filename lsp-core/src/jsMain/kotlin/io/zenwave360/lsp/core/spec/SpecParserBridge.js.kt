package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal actual object SpecParserBridge {
    actual fun parseText(text: String, baseUri: String, options: RefParserOptions): ParsedDocument =
        runSynchronously {
            RefParser.fromText(text = text, baseUri = baseUri, options = options)
                .dereference()
                .getParsedDocument()
        }

    actual fun parseUri(uri: String, options: RefParserOptions): ParsedDocument =
        runSynchronously {
            RefParser(uri, options = options)
                .dereference()
                .getParsedDocument()
        }

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
