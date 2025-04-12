package io.zenwave360.zdl.lsp.intellij

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Documentation provider for ZDL
 */
class ZdlDocumentationProvider : AbstractDocumentationProvider() {
    /**
     * Generate documentation for an element
     */
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        // Get the text of the element
        val text = element.text
        
        // Check if it's a keyword
        val keywordDoc = KEYWORD_DOCS[text]
        if (keywordDoc != null) {
            return "<b>$text</b><br><br>$keywordDoc"
        }
        
        // Check if it's a type
        val typeDoc = TYPE_DOCS[text]
        if (typeDoc != null) {
            return "<b>$text</b><br><br>$typeDoc"
        }
        
        return null
    }
    
    companion object {
        // Documentation for ZDL keywords
        private val KEYWORD_DOCS = mapOf(
            "entity" to "Defines a domain entity with fields and relationships.",
            "enum" to "Defines an enumeration type with a set of possible values.",
            "input" to "Defines an input data structure for service operations.",
            "output" to "Defines an output data structure for service operations.",
            "event" to "Defines an event that can be published or consumed.",
            "relationship" to "Defines relationships between entities.",
            "ManyToMany" to "Defines a many-to-many relationship between entities.",
            "ManyToOne" to "Defines a many-to-one relationship between entities.",
            "OneToMany" to "Defines a one-to-many relationship between entities.",
            "OneToOne" to "Defines a one-to-one relationship between entities.",
            "service" to "Defines a service with operations.",
            "aggregate" to "Defines an aggregate with commands.",
            "required" to "Marks a field as required (not null).",
            "unique" to "Marks a field as unique.",
            "min" to "Specifies the minimum value for a numeric field.",
            "max" to "Specifies the maximum value for a numeric field.",
            "minlength" to "Specifies the minimum length for a string field.",
            "maxlength" to "Specifies the maximum length for a string field.",
            "pattern" to "Specifies a regular expression pattern for a string field.",
            "email" to "Marks a string field as an email address."
        )
        
        // Documentation for ZDL field types
        private val TYPE_DOCS = mapOf(
            "String" to "A string value.",
            "Integer" to "A 32-bit integer value.",
            "Long" to "A 64-bit integer value.",
            "int" to "A 32-bit integer value (primitive type).",
            "long" to "A 64-bit integer value (primitive type).",
            "BigDecimal" to "A decimal value with arbitrary precision.",
            "Float" to "A 32-bit floating-point value.",
            "float" to "A 32-bit floating-point value (primitive type).",
            "Double" to "A 64-bit floating-point value.",
            "double" to "A 64-bit floating-point value (primitive type).",
            "Boolean" to "A boolean value (true/false).",
            "boolean" to "A boolean value (primitive type).",
            "Map" to "A key-value map.",
            "LocalDate" to "A date without time information.",
            "LocalDateTime" to "A date and time without timezone information.",
            "ZonedDate" to "A date with timezone information.",
            "ZonedDateTime" to "A date and time with timezone information.",
            "Instant" to "A point in time.",
            "Duration" to "A time duration.",
            "UUID" to "A universally unique identifier.",
            "byte" to "An 8-bit integer value (primitive type).",
            "byte[]" to "An array of bytes.",
            "Blob" to "A binary large object.",
            "AnyBlob" to "A binary large object of any type.",
            "ImageBlob" to "A binary large object containing image data.",
            "TextBlob" to "A binary large object containing text data."
        )
    }
}
