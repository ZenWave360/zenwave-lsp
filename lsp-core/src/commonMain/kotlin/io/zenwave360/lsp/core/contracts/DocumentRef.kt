package io.zenwave360.lsp.core.contracts

data class DocumentRef(
    val uri: String,
    val languageId: String,
    val version: Int
)

data class DocumentSnapshot(
    val ref: DocumentRef,
    val text: String
)
