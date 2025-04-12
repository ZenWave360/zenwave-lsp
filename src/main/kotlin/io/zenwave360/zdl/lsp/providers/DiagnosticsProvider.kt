package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL diagnostics
 */
class DiagnosticsProvider {
    private val logger = LoggerFactory.getLogger(DiagnosticsProvider::class.java)
    
    /**
     * Get diagnostics for a document
     */
    fun getDiagnostics(document: ZdlDocument): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        // Add parse errors
        for (error in document.getParseErrors()) {
            diagnostics.add(
                Diagnostic(
                    error.range,
                    error.message,
                    DiagnosticSeverity.Error,
                    "zdl-parser"
                )
            )
        }
        
        // Add semantic errors
        val model = document.getModel()
        if (model != null) {
            // Check for duplicate entity names
            val entityNames = mutableSetOf<String>()
            model.entities?.forEach { entity ->
                val name = entity.name
                if (name in entityNames) {
                    // Find the position of the duplicate entity
                    val position = findEntityPosition(document, name)
                    if (position != null) {
                        diagnostics.add(
                            Diagnostic(
                                position,
                                "Duplicate entity name: $name",
                                DiagnosticSeverity.Error,
                                "zdl-validator"
                            )
                        )
                    }
                } else {
                    entityNames.add(name)
                }
                
                // Check for duplicate field names within the entity
                val fieldNames = mutableSetOf<String>()
                entity.fields?.forEach { field ->
                    val fieldName = field.name
                    if (fieldName in fieldNames) {
                        // Find the position of the duplicate field
                        val position = findFieldPosition(document, name, fieldName)
                        if (position != null) {
                            diagnostics.add(
                                Diagnostic(
                                    position,
                                    "Duplicate field name: $fieldName in entity $name",
                                    DiagnosticSeverity.Error,
                                    "zdl-validator"
                                )
                            )
                        }
                    } else {
                        fieldNames.add(fieldName)
                    }
                }
            }
            
            // Check for duplicate enum names
            val enumNames = mutableSetOf<String>()
            model.enums?.forEach { enum ->
                val name = enum.name
                if (name in enumNames) {
                    // Find the position of the duplicate enum
                    val position = findEnumPosition(document, name)
                    if (position != null) {
                        diagnostics.add(
                            Diagnostic(
                                position,
                                "Duplicate enum name: $name",
                                DiagnosticSeverity.Error,
                                "zdl-validator"
                            )
                        )
                    }
                } else {
                    enumNames.add(name)
                }
                
                // Check for duplicate enum value names
                val valueNames = mutableSetOf<String>()
                enum.values?.forEach { value ->
                    val valueName = value.name
                    if (valueName in valueNames) {
                        // Find the position of the duplicate enum value
                        val position = findEnumValuePosition(document, name, valueName)
                        if (position != null) {
                            diagnostics.add(
                                Diagnostic(
                                    position,
                                    "Duplicate enum value name: $valueName in enum $name",
                                    DiagnosticSeverity.Error,
                                    "zdl-validator"
                                )
                            )
                        }
                    } else {
                        valueNames.add(valueName)
                    }
                }
            }
        }
        
        return diagnostics
    }
    
    /**
     * Find the position of an entity in the document
     */
    private fun findEntityPosition(document: ZdlDocument, entityName: String): Range? {
        val text = document.getText()
        val pattern = "entity\\s+$entityName"
        val regex = Regex(pattern)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the position of a field in the document
     */
    private fun findFieldPosition(document: ZdlDocument, entityName: String, fieldName: String): Range? {
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
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the position of an enum in the document
     */
    private fun findEnumPosition(document: ZdlDocument, enumName: String): Range? {
        val text = document.getText()
        val pattern = "enum\\s+$enumName"
        val regex = Regex(pattern)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the position of an enum value in the document
     */
    private fun findEnumValuePosition(document: ZdlDocument, enumName: String, valueName: String): Range? {
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
        
        return Range(startPosition, endPosition)
    }
}
