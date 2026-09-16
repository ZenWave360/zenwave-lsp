package io.zenwave360.lsp.core.contracts

/**
 * Documents other than the one being answered for, as the server could obtain them: the editor's content when
 * they are open, otherwise what the server read. A document the server could not reach is simply absent.
 */
class RelatedDocuments(private val documents: Map<String, DocumentSnapshot> = emptyMap()) {
    fun get(uri: String): DocumentSnapshot? = documents[uri]

    val uris: Set<String> get() = documents.keys

    companion object {
        val NONE = RelatedDocuments()
    }
}
