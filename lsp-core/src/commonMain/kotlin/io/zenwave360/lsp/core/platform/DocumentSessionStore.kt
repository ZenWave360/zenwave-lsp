package io.zenwave360.lsp.core.platform

import io.zenwave360.lsp.core.contracts.DocumentSnapshot

interface DocumentSessionStore {
    fun open(snapshot: DocumentSnapshot)

    fun change(uri: String, text: String, version: Int)

    fun close(uri: String)

    fun get(uri: String): DocumentSnapshot?

    fun getAll(): List<DocumentSnapshot>
}
