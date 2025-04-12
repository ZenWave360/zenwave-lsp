package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL definition locations
 */
class DefinitionProvider {
    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)
    
    /**
     * Get definition locations for a document at a position
     */
    fun getDefinitionLocations(document: ZdlDocument, position: Position): List<Location> {
        // Get the word at the position
        val word = document.getWordAtPosition(position)
        if (word.isEmpty()) {
            return emptyList()
        }
        
        // Check if it's a reference to an entity, enum, or field
        val model = document.getModel()
        if (model != null) {
            // Check if it's an entity reference
            model.entities?.forEach { entity ->
                if (entity.name == word) {
                    // Find the entity definition
                    val location = findEntityDefinition(document, word)
                    if (location != null) {
                        return listOf(location)
                    }
                }
            }
            
            // Check if it's an enum reference
            model.enums?.forEach { enum ->
                if (enum.name == word) {
                    // Find the enum definition
                    val location = findEnumDefinition(document, word)
                    if (location != null) {
                        return listOf(location)
                    }
                }
            }
            
            // Check if it's a field reference
            model.entities?.forEach { entity ->
                entity.fields?.forEach { field ->
                    if (field.name == word) {
                        // Find the field definition
                        val location = findFieldDefinition(document, entity.name, word)
                        if (location != null) {
                            return listOf(location)
                        }
                    }
                }
            }
            
            // Check if it's an enum value reference
            model.enums?.forEach { enum ->
                enum.values?.forEach { value ->
                    if (value.name == word) {
                        // Find the enum value definition
                        val location = findEnumValueDefinition(document, enum.name, word)
                        if (location != null) {
                            return listOf(location)
                        }
                    }
                }
            }
        }
        
        return emptyList()
    }
    
    /**
     * Find the definition of an entity in the document
     */
    private fun findEntityDefinition(document: ZdlDocument, entityName: String): Location? {
        val text = document.getText()
        val pattern = "entity\\s+$entityName"
        val regex = Regex(pattern)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Location(document.uri, Range(startPosition, endPosition))
    }
    
    /**
     * Find the definition of an enum in the document
     */
    private fun findEnumDefinition(document: ZdlDocument, enumName: String): Location? {
        val text = document.getText()
        val pattern = "enum\\s+$enumName"
        val regex = Regex(pattern)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Location(document.uri, Range(startPosition, endPosition))
    }
    
    /**
     * Find the definition of a field in the document
     */
    private fun findFieldDefinition(document: ZdlDocument, entityName: String, fieldName: String): Location? {
        val text = document.getText()
        val entityPattern = "entity\\s+$entityName\\s*\\{([^}]*)\\}"
        val entityRegex = Regex(entityPattern, RegexOption.DOT_MATCHES_ALL)
        val entityMatch = entityRegex.find(text) ?: return null
        
        val entityBody = entityMatch.groupValues[1]
        val fieldPattern = "\\b$fieldName\\b"
        val fieldRegex = Regex(fieldPattern)
        val fieldMatch = fieldRegex.find(entityBody) ?: return null
        
        val entityStartOffset = entityMatch.range.first
        val fieldRelativeOffset = fieldMatch.range.first
        val startOffset = entityStartOffset + entityMatch.groupValues[0].indexOf(entityBody) + fieldRelativeOffset
        val endOffset = startOffset + fieldName.length
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Location(document.uri, Range(startPosition, endPosition))
    }
    
    /**
     * Find the definition of an enum value in the document
     */
    private fun findEnumValueDefinition(document: ZdlDocument, enumName: String, valueName: String): Location? {
        val text = document.getText()
        val enumPattern = "enum\\s+$enumName\\s*\\{([^}]*)\\}"
        val enumRegex = Regex(enumPattern, RegexOption.DOT_MATCHES_ALL)
        val enumMatch = enumRegex.find(text) ?: return null
        
        val enumBody = enumMatch.groupValues[1]
        val valuePattern = "\\b$valueName\\b"
        val valueRegex = Regex(valuePattern)
        val valueMatch = valueRegex.find(enumBody) ?: return null
        
        val enumStartOffset = enumMatch.range.first
        val valueRelativeOffset = valueMatch.range.first
        val startOffset = enumStartOffset + enumMatch.groupValues[0].indexOf(enumBody) + valueRelativeOffset
        val endOffset = startOffset + valueName.length
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Location(document.uri, Range(startPosition, endPosition))
    }
}
