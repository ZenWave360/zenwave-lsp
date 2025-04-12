package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL hover information
 */
class HoverProvider {
    private val logger = LoggerFactory.getLogger(HoverProvider::class.java)
    
    // Documentation for ZDL keywords
    private val keywordDocs = mapOf(
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
    private val typeDocs = mapOf(
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
    
    /**
     * Get hover information for a document at a position
     */
    fun getHover(document: ZdlDocument, position: Position): Hover? {
        // Get the word at the position
        val word = document.getWordAtPosition(position)
        if (word.isEmpty()) {
            return null
        }
        
        // Check if it's a keyword
        val keywordDoc = keywordDocs[word]
        if (keywordDoc != null) {
            return createHover(word, keywordDoc, position, document)
        }
        
        // Check if it's a type
        val typeDoc = typeDocs[word]
        if (typeDoc != null) {
            return createHover(word, typeDoc, position, document)
        }
        
        // Check if it's an entity, enum, or field
        val model = document.getModel()
        if (model != null) {
            // Check entities
            model.entities?.forEach { entity ->
                if (entity.name == word) {
                    val doc = "Entity: ${entity.name}\n\n" +
                            (entity.javadoc ?: "") +
                            "\n\nFields: " + (entity.fields?.joinToString(", ") { it.name } ?: "")
                    return createHover(word, doc, position, document)
                }
                
                // Check fields
                entity.fields?.forEach { field ->
                    if (field.name == word) {
                        val doc = "Field: ${field.name}\n\n" +
                                "Type: ${field.type}" +
                                (if (field.javadoc != null) "\n\n${field.javadoc}" else "")
                        return createHover(word, doc, position, document)
                    }
                }
            }
            
            // Check enums
            model.enums?.forEach { enum ->
                if (enum.name == word) {
                    val doc = "Enum: ${enum.name}\n\n" +
                            (enum.javadoc ?: "") +
                            "\n\nValues: " + (enum.values?.joinToString(", ") { it.name } ?: "")
                    return createHover(word, doc, position, document)
                }
                
                // Check enum values
                enum.values?.forEach { value ->
                    if (value.name == word) {
                        val doc = "Enum Value: ${value.name}\n\n" +
                                "Enum: ${enum.name}" +
                                (if (value.javadoc != null) "\n\n${value.javadoc}" else "")
                        return createHover(word, doc, position, document)
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Create a hover object
     */
    private fun createHover(word: String, content: String, position: Position, document: ZdlDocument): Hover {
        // Get the range of the word
        val line = document.getLine(position.line) ?: ""
        val startChar = line.indexOf(word, position.character - word.length)
        val endChar = startChar + word.length
        
        val range = Range(
            Position(position.line, startChar),
            Position(position.line, endChar)
        )
        
        // Create the hover
        val markupContent = MarkupContent(MarkupKind.MARKDOWN, content)
        return Hover(markupContent, range)
    }
}
