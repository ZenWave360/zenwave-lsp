package io.zenwave360.lsp.core.platform

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot

class InMemoryDocumentSessionStore : DocumentSessionStore {
    private val snapshots = linkedMapOf<String, DocumentSnapshot>()

    override fun open(snapshot: DocumentSnapshot) {
        snapshots[snapshot.ref.uri] = snapshot
    }

    override fun change(uri: String, text: String, version: Int) {
        val current = snapshots[uri] ?: return
        if (version < current.ref.version) {
            return
        }
        snapshots[uri] = current.copy(
            ref = current.ref.copy(version = version),
            text = text
        )
    }

    override fun close(uri: String) {
        snapshots.remove(uri)
    }

    override fun get(uri: String): DocumentSnapshot? =
        snapshots[uri]

    override fun getAll(): List<DocumentSnapshot> =
        snapshots.values.toList()
}
