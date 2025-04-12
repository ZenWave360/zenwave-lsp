package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL reference locations
 */
class ReferencesProvider {
    private val logger = LoggerFactory.getLogger(ReferencesProvider::class.java)
    
    /**
     * Get reference locations for a document at a position
     */
    fun getReferenceLocations(document: ZdlDocument, position: Position, includeDeclaration: Boolean): List<Location> {
        // Get the word at the position
        val word = document.getWordAtPosition(position)
        if (word.isEmpty()) {
            return emptyList()
        }
        
        val references = mutableListOf<Location>()
        
        // Check if it's a reference to an entity, enum, or field
        val model = document.getModel()
        if (model != null) {
            // Check if it's an entity reference
            model.entities?.forEach { entity ->
                if (entity.name == word) {
                    // Find all references to the entity
                    if (includeDeclaration) {
                        // Add the declaration
                        val declaration = findEntityDefinition(document, word)
                        if (declaration != null) {
                            references.add(declaration)
                        }
                    }
                    
                    // Find references in relationships
                    findEntityReferences(document, word).forEach { reference ->
                        references.add(reference)
                    }
                }
            }
            
            // Check if it's an enum reference
            model.enums?.forEach { enum ->
                if (enum.name == word) {
                    // Find all references to the enum
                    if (includeDeclaration) {
                        // Add the declaration
                        val declaration = findEnumDefinition(document, word)
                        if (declaration != null) {
                            references.add(declaration)
                        }
                    }
                    
                    // Find references in field types
                    findEnumReferences(document, word).forEach { reference ->
                        references.add(reference)
                    }
                }
            }
            
            // Check if it's a field reference
            model.entities?.forEach { entity ->
                entity.fields?.forEach { field ->
                    if (field.name == word) {
                        // Find all references to the field
                        if (includeDeclaration) {
                            // Add the declaration
                            val declaration = findFieldDefinition(document, entity.name, word)
                            if (declaration != null) {
                                references.add(declaration)
                            }
                        }
                        
                        // Find references in relationships
                        findFieldReferences(document, entity.name, word).forEach { reference ->
                            references.add(reference)
                        }
                    }
                }
            }
            
            // Check if it's an enum value reference
            model.enums?.forEach { enum ->
                enum.values?.forEach { value ->
                    if (value.name == word) {
                        // Find all references to the enum value
                        if (includeDeclaration) {
                            // Add the declaration
                            val declaration = findEnumValueDefinition(document, enum.name, word)
                            if (declaration != null) {
                                references.add(declaration)
                            }
                        }
                        
                        // Find references in field initializations
                        findEnumValueReferences(document, enum.name, word).forEach { reference ->
                            references.add(reference)
                        }
                    }
                }
            }
        }
        
        return references
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
     * Find references to an entity in the document
     */
    private fun findEntityReferences(document: ZdlDocument, entityName: String): List<Location> {
        val references = mutableListOf<Location>()
        val text = document.getText()
        
        // Find references in relationships
        val relationshipPattern = "relationship\\s+\\w+\\s*\\{[^}]*\\b$entityName\\b[^}]*\\}"
        val relationshipRegex = Regex(relationshipPattern, RegexOption.DOT_MATCHES_ALL)
        val relationshipMatches = relationshipRegex.findAll(text)
        
        for (match in relationshipMatches) {
            val matchText = match.value
            val entityPattern = "\\b$entityName\\b"
            val entityRegex = Regex(entityPattern)
            val entityMatches = entityRegex.findAll(matchText)
            
            for (entityMatch in entityMatches) {
                val startOffset = match.range.first + entityMatch.range.first
                val endOffset = startOffset + entityName.length
                
                val startPosition = document.offsetToPosition(startOffset)
                val endPosition = document.offsetToPosition(endOffset)
                
                references.add(Location(document.uri, Range(startPosition, endPosition)))
            }
        }
        
        // Find references in field types
        val fieldTypePattern = "\\w+\\s+$entityName\\b"
        val fieldTypeRegex = Regex(fieldTypePattern)
        val fieldTypeMatches = fieldTypeRegex.findAll(text)
        
        for (match in fieldTypeMatches) {
            val startOffset = match.range.first + match.value.indexOf(entityName)
            val endOffset = startOffset + entityName.length
            
            val startPosition = document.offsetToPosition(startOffset)
            val endPosition = document.offsetToPosition(endOffset)
            
            references.add(Location(document.uri, Range(startPosition, endPosition)))
        }
        
        return references
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
     * Find references to an enum in the document
     */
    private fun findEnumReferences(document: ZdlDocument, enumName: String): List<Location> {
        val references = mutableListOf<Location>()
        val text = document.getText()
        
        // Find references in field types
        val fieldTypePattern = "\\w+\\s+$enumName\\b"
        val fieldTypeRegex = Regex(fieldTypePattern)
        val fieldTypeMatches = fieldTypeRegex.findAll(text)
        
        for (match in fieldTypeMatches) {
            val startOffset = match.range.first + match.value.indexOf(enumName)
            val endOffset = startOffset + enumName.length
            
            val startPosition = document.offsetToPosition(startOffset)
            val endPosition = document.offsetToPosition(endOffset)
            
            references.add(Location(document.uri, Range(startPosition, endPosition)))
        }
        
        return references
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
     * Find references to a field in the document
     */
    private fun findFieldReferences(document: ZdlDocument, entityName: String, fieldName: String): List<Location> {
        val references = mutableListOf<Location>()
        val text = document.getText()
        
        // Find references in relationships
        val relationshipPattern = "relationship\\s+\\w+\\s*\\{[^}]*$entityName\\s*\\{[^}]*$fieldName[^}]*\\}[^}]*\\}"
        val relationshipRegex = Regex(relationshipPattern, RegexOption.DOT_MATCHES_ALL)
        val relationshipMatches = relationshipRegex.findAll(text)
        
        for (match in relationshipMatches) {
            val matchText = match.value
            val fieldPattern = "\\b$fieldName\\b"
            val fieldRegex = Regex(fieldPattern)
            val fieldMatches = fieldRegex.findAll(matchText)
            
            for (fieldMatch in fieldMatches) {
                val startOffset = match.range.first + fieldMatch.range.first
                val endOffset = startOffset + fieldName.length
                
                val startPosition = document.offsetToPosition(startOffset)
                val endPosition = document.offsetToPosition(endOffset)
                
                references.add(Location(document.uri, Range(startPosition, endPosition)))
            }
        }
        
        return references
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
    
    /**
     * Find references to an enum value in the document
     */
    private fun findEnumValueReferences(document: ZdlDocument, enumName: String, valueName: String): List<Location> {
        val references = mutableListOf<Location>()
        val text = document.getText()
        
        // Find references in field initializations
        val initPattern = "=\\s*$valueName\\b"
        val initRegex = Regex(initPattern)
        val initMatches = initRegex.findAll(text)
        
        for (match in initMatches) {
            val startOffset = match.range.first + match.value.indexOf(valueName)
            val endOffset = startOffset + valueName.length
            
            val startPosition = document.offsetToPosition(startOffset)
            val endPosition = document.offsetToPosition(endOffset)
            
            references.add(Location(document.uri, Range(startPosition, endPosition)))
        }
        
        return references
    }
}
