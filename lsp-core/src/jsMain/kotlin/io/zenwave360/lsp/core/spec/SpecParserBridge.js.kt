package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.Job

internal actual object SpecParserBridge {
    actual fun parseText(
        text: String,
        baseUri: String,
        options: RefParserOptions,
        loaders: List<DocumentLoader>?,
    ): ParsedDocument {
        val guard = ReferenceDocumentLoader(baseUri, loaders)
        return guard.restoreDocument(runSynchronously {
            RefParser.fromText(text = guard.prepareText(text, baseUri), baseUri = baseUri, options = options,
                loaders = listOf(synchronousLoader(guard)))
                .dereference()
                .getParsedDocument()
        })
    }

    actual fun parseUri(uri: String, options: RefParserOptions, loaders: List<DocumentLoader>?): ParsedDocument {
        val guard = loaders?.singleOrNull() as? ReferenceDocumentLoader ?: ReferenceDocumentLoader(uri, loaders)
        return guard.restoreDocument(runSynchronously {
            RefParser(uri, options = options, loaders = listOf(synchronousLoader(guard)))
                .dereference()
                .getParsedDocument()
        })
    }

    actual fun parseRaw(text: String, baseUri: String): ParsedDocument =
        runSynchronously { RefParser.fromText(text, baseUri, loaders = emptyList()).parse().getParsedDocument() }

    /** Fail inside the loader, where OnMissing.SKIP can retain just the unresolved ref. */
    private fun synchronousLoader(delegate: DocumentLoader): DocumentLoader = object : DocumentLoader {
        override fun canLoad(uri: String): Boolean = delegate.canLoad(uri)
        override suspend fun load(uri: String): String = runSynchronously { delegate.load(uri) }
    }

    private fun <T> runSynchronously(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        val job = Job()
        try {
            block.startCoroutine(object : Continuation<T> {
                override val context = job

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            })
            return checkNotNull(outcome) { "The reference loader did not complete synchronously" }.getOrThrow()
        } finally {
            // Abort pending HTTP reads; a late completion never resumes the parser's loader call.
            job.cancel()
        }
    }
}
