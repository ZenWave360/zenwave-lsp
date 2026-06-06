package io.zenwave360.lsp.core.platform

import io.zenwave360.lsp.core.contracts.DocumentSnapshot

class InMemoryDocumentSessionStore : DocumentSessionStore {
    private val lock = PlatformReadWriteLock()
    private val snapshots = linkedMapOf<String, DocumentSnapshot>()

    override fun open(snapshot: DocumentSnapshot) {
        lock.write {
            snapshots[snapshot.ref.uri] = snapshot
        }
    }

    override fun change(uri: String, text: String, version: Int) {
        lock.write {
            val current = snapshots[uri]
            if (current != null && version >= current.ref.version) {
                snapshots[uri] = current.copy(
                    ref = current.ref.copy(version = version),
                    text = text
                )
            }
        }
    }

    override fun close(uri: String) {
        lock.write {
            snapshots.remove(uri)
        }
    }

    override fun get(uri: String): DocumentSnapshot? =
        lock.read {
            snapshots[uri]
        }

    override fun getAll(): List<DocumentSnapshot> =
        lock.read {
            snapshots.values.toList()
        }
}
