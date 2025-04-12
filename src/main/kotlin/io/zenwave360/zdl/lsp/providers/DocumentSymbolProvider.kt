package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL document symbols
 */
class DocumentSymbolProvider {
    private val logger = LoggerFactory.getLogger(DocumentSymbolProvider::class.java)
    
    /**
     * Get document symbols for a document
     */
    fun getDocumentSymbols(document: ZdlDocument): List<Either<SymbolInformation, DocumentSymbol>> {
        val symbols = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
        
        // Get the model
        val model = document.getModel()
        if (model != null) {
            // Add entities
            model.entities?.forEach { entity ->
                // Find the entity position
                val entityRange = findEntityRange(document, entity.name)
                if (entityRange != null) {
                    // Create the entity symbol
                    val entitySymbol = DocumentSymbol(
                        entity.name,
                        SymbolKind.Class,
                        entityRange,
                        entityRange,
                        entity.javadoc,
                        mutableListOf()
                    )
                    
                    // Add fields
                    entity.fields?.forEach { field ->
                        // Find the field position
                        val fieldRange = findFieldRange(document, entity.name, field.name)
                        if (fieldRange != null) {
                            // Create the field symbol
                            val fieldSymbol = DocumentSymbol(
                                field.name,
                                SymbolKind.Field,
                                fieldRange,
                                fieldRange,
                                field.javadoc,
                                null
                            )
                            
                            // Add the field to the entity
                            entitySymbol.children.add(fieldSymbol)
                        }
                    }
                    
                    // Add the entity to the symbols
                    symbols.add(Either.forRight(entitySymbol))
                }
            }
            
            // Add enums
            model.enums?.forEach { enum ->
                // Find the enum position
                val enumRange = findEnumRange(document, enum.name)
                if (enumRange != null) {
                    // Create the enum symbol
                    val enumSymbol = DocumentSymbol(
                        enum.name,
                        SymbolKind.Enum,
                        enumRange,
                        enumRange,
                        enum.javadoc,
                        mutableListOf()
                    )
                    
                    // Add enum values
                    enum.values?.forEach { value ->
                        // Find the enum value position
                        val valueRange = findEnumValueRange(document, enum.name, value.name)
                        if (valueRange != null) {
                            // Create the enum value symbol
                            val valueSymbol = DocumentSymbol(
                                value.name,
                                SymbolKind.EnumMember,
                                valueRange,
                                valueRange,
                                value.javadoc,
                                null
                            )
                            
                            // Add the enum value to the enum
                            enumSymbol.children.add(valueSymbol)
                        }
                    }
                    
                    // Add the enum to the symbols
                    symbols.add(Either.forRight(enumSymbol))
                }
            }
            
            // Add services
            model.services?.forEach { service ->
                // Find the service position
                val serviceRange = findServiceRange(document, service.name)
                if (serviceRange != null) {
                    // Create the service symbol
                    val serviceSymbol = DocumentSymbol(
                        service.name,
                        SymbolKind.Interface,
                        serviceRange,
                        serviceRange,
                        service.javadoc,
                        mutableListOf()
                    )
                    
                    // Add methods
                    service.methods?.forEach { method ->
                        // Find the method position
                        val methodRange = findMethodRange(document, service.name, method.name)
                        if (methodRange != null) {
                            // Create the method symbol
                            val methodSymbol = DocumentSymbol(
                                method.name,
                                SymbolKind.Method,
                                methodRange,
                                methodRange,
                                method.javadoc,
                                null
                            )
                            
                            // Add the method to the service
                            serviceSymbol.children.add(methodSymbol)
                        }
                    }
                    
                    // Add the service to the symbols
                    symbols.add(Either.forRight(serviceSymbol))
                }
            }
            
            // Add aggregates
            model.aggregates?.forEach { aggregate ->
                // Find the aggregate position
                val aggregateRange = findAggregateRange(document, aggregate.name)
                if (aggregateRange != null) {
                    // Create the aggregate symbol
                    val aggregateSymbol = DocumentSymbol(
                        aggregate.name,
                        SymbolKind.Class,
                        aggregateRange,
                        aggregateRange,
                        aggregate.javadoc,
                        mutableListOf()
                    )
                    
                    // Add commands
                    aggregate.commands?.forEach { command ->
                        // Find the command position
                        val commandRange = findCommandRange(document, aggregate.name, command.name)
                        if (commandRange != null) {
                            // Create the command symbol
                            val commandSymbol = DocumentSymbol(
                                command.name,
                                SymbolKind.Method,
                                commandRange,
                                commandRange,
                                command.javadoc,
                                null
                            )
                            
                            // Add the command to the aggregate
                            aggregateSymbol.children.add(commandSymbol)
                        }
                    }
                    
                    // Add the aggregate to the symbols
                    symbols.add(Either.forRight(aggregateSymbol))
                }
            }
        }
        
        return symbols
    }
    
    /**
     * Find the range of an entity in the document
     */
    private fun findEntityRange(document: ZdlDocument, entityName: String): Range? {
        val text = document.getText()
        val pattern = "entity\\s+$entityName[^{]*\\{[^}]*\\}"
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of a field in the document
     */
    private fun findFieldRange(document: ZdlDocument, entityName: String, fieldName: String): Range? {
        val text = document.getText()
        val entityPattern = "entity\\s+$entityName\\s*\\{([^}]*)\\}"
        val entityRegex = Regex(entityPattern, RegexOption.DOT_MATCHES_ALL)
        val entityMatch = entityRegex.find(text) ?: return null
        
        val entityBody = entityMatch.groupValues[1]
        val fieldPattern = "\\b$fieldName\\b[^,;{]*[,;{]"
        val fieldRegex = Regex(fieldPattern, RegexOption.DOT_MATCHES_ALL)
        val fieldMatch = fieldRegex.find(entityBody) ?: return null
        
        val entityStartOffset = entityMatch.range.first
        val fieldRelativeOffset = fieldMatch.range.first
        val startOffset = entityStartOffset + entityMatch.groupValues[0].indexOf(entityBody) + fieldRelativeOffset
        val endOffset = startOffset + fieldMatch.value.length
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of an enum in the document
     */
    private fun findEnumRange(document: ZdlDocument, enumName: String): Range? {
        val text = document.getText()
        val pattern = "enum\\s+$enumName[^{]*\\{[^}]*\\}"
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of an enum value in the document
     */
    private fun findEnumValueRange(document: ZdlDocument, enumName: String, valueName: String): Range? {
        val text = document.getText()
        val enumPattern = "enum\\s+$enumName\\s*\\{([^}]*)\\}"
        val enumRegex = Regex(enumPattern, RegexOption.DOT_MATCHES_ALL)
        val enumMatch = enumRegex.find(text) ?: return null
        
        val enumBody = enumMatch.groupValues[1]
        val valuePattern = "\\b$valueName\\b[^,}]*[,}]"
        val valueRegex = Regex(valuePattern, RegexOption.DOT_MATCHES_ALL)
        val valueMatch = valueRegex.find(enumBody) ?: return null
        
        val enumStartOffset = enumMatch.range.first
        val valueRelativeOffset = valueMatch.range.first
        val startOffset = enumStartOffset + enumMatch.groupValues[0].indexOf(enumBody) + valueRelativeOffset
        val endOffset = startOffset + valueMatch.value.length
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of a service in the document
     */
    private fun findServiceRange(document: ZdlDocument, serviceName: String): Range? {
        val text = document.getText()
        val pattern = "service\\s+$serviceName[^{]*\\{[^}]*\\}"
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of a method in the document
     */
    private fun findMethodRange(document: ZdlDocument, serviceName: String, methodName: String): Range? {
        val text = document.getText()
        val servicePattern = "service\\s+$serviceName[^{]*\\{([^}]*)\\}"
        val serviceRegex = Regex(servicePattern, RegexOption.DOT_MATCHES_ALL)
        val serviceMatch = serviceRegex.find(text) ?: return null
        
        val serviceBody = serviceMatch.groupValues[1]
        val methodPattern = "\\b$methodName\\b[^;]*;"
        val methodRegex = Regex(methodPattern, RegexOption.DOT_MATCHES_ALL)
        val methodMatch = methodRegex.find(serviceBody) ?: return null
        
        val serviceStartOffset = serviceMatch.range.first
        val methodRelativeOffset = methodMatch.range.first
        val startOffset = serviceStartOffset + serviceMatch.groupValues[0].indexOf(serviceBody) + methodRelativeOffset
        val endOffset = startOffset + methodMatch.value.length
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of an aggregate in the document
     */
    private fun findAggregateRange(document: ZdlDocument, aggregateName: String): Range? {
        val text = document.getText()
        val pattern = "aggregate\\s+$aggregateName[^{]*\\{[^}]*\\}"
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null
        
        val startOffset = match.range.first
        val endOffset = match.range.last + 1
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
    
    /**
     * Find the range of a command in the document
     */
    private fun findCommandRange(document: ZdlDocument, aggregateName: String, commandName: String): Range? {
        val text = document.getText()
        val aggregatePattern = "aggregate\\s+$aggregateName[^{]*\\{([^}]*)\\}"
        val aggregateRegex = Regex(aggregatePattern, RegexOption.DOT_MATCHES_ALL)
        val aggregateMatch = aggregateRegex.find(text) ?: return null
        
        val aggregateBody = aggregateMatch.groupValues[1]
        val commandPattern = "\\b$commandName\\b[^;]*;"
        val commandRegex = Regex(commandPattern, RegexOption.DOT_MATCHES_ALL)
        val commandMatch = commandRegex.find(aggregateBody) ?: return null
        
        val aggregateStartOffset = aggregateMatch.range.first
        val commandRelativeOffset = commandMatch.range.first
        val startOffset = aggregateStartOffset + aggregateMatch.groupValues[0].indexOf(aggregateBody) + commandRelativeOffset
        val endOffset = startOffset + commandMatch.value.length
        
        val startPosition = document.offsetToPosition(startOffset)
        val endPosition = document.offsetToPosition(endOffset)
        
        return Range(startPosition, endPosition)
    }
}
