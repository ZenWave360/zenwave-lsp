package io.zenwave360.lsp.core.model

interface SemanticModel {

    /** Raw semantic data (Map-of-Maps) */
    val data: Map<String, Any?>

    /** Validation / parse problems */
    val problems: List<Problem>

    /**
     * Given a cursor position, returns the jsonPath
     * of the semantic element under the cursor.
     *
     * Example:
     *   entities.Customer.fields.customerId.name
     */
    fun getLocation(line: Int, character: Int): String?
}

