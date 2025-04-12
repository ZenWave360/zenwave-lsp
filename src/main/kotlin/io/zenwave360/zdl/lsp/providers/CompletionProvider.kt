package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL code completion
 */
class CompletionProvider {
    private val logger = LoggerFactory.getLogger(CompletionProvider::class.java)
    
    // ZDL keywords
    private val keywords = listOf(
        "import", "config", "apis", "plugins", "policies", "disabled",
        "asyncapi", "openapi", "entity", "enum", "input", "output", "event",
        "relationship", "ManyToMany", "ManyToOne", "OneToMany", "OneToOne",
        "service", "aggregate", "id", "for", "to", "withEvents", "with"
    )
    
    // ZDL field types
    private val fieldTypes = listOf(
        "String", "Integer", "Long", "int", "long", "BigDecimal", "Float", "float",
        "Double", "double", "Enum", "Boolean", "boolean", "Map", "LocalDate",
        "LocalDateTime", "ZonedDate", "ZonedDateTime", "Instant", "Duration",
        "UUID", "byte", "byte[]", "Blob", "AnyBlob", "ImageBlob", "TextBlob"
    )
    
    // ZDL field validations
    private val validations = listOf(
        "required", "unique", "min", "max", "minlength", "maxlength", "pattern", "email"
    )
    
    /**
     * Get completion items for a document at a position
     */
    fun getCompletionItems(document: ZdlDocument, position: Position): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        
        // Get the current line
        val line = document.getLine(position.line) ?: return items
        
        // Get the prefix
        val prefix = if (position.character > 0) line.substring(0, position.character) else ""
        
        // Check if we're in an entity definition
        if (isInEntityDefinition(document, position)) {
            // Add field types
            items.addAll(fieldTypes.map { createCompletionItem(it, CompletionItemKind.Class) })
            
            // Add validations
            items.addAll(validations.map { createCompletionItem(it, CompletionItemKind.Keyword) })
            
            // Add entity names for relationships
            val model = document.getModel()
            model?.entities?.forEach { entity ->
                items.add(createCompletionItem(entity.name, CompletionItemKind.Class))
            }
        } else {
            // Add keywords
            items.addAll(keywords.map { createCompletionItem(it, CompletionItemKind.Keyword) })
            
            // Add entity names
            val model = document.getModel()
            model?.entities?.forEach { entity ->
                items.add(createCompletionItem(entity.name, CompletionItemKind.Class))
            }
            
            // Add enum names
            model?.enums?.forEach { enum ->
                items.add(createCompletionItem(enum.name, CompletionItemKind.Enum))
            }
        }
        
        return items
    }
    
    /**
     * Create a completion item
     */
    private fun createCompletionItem(label: String, kind: CompletionItemKind): CompletionItem {
        val item = CompletionItem(label)
        item.kind = kind
        return item
    }
    
    /**
     * Check if the position is inside an entity definition
     */
    private fun isInEntityDefinition(document: ZdlDocument, position: Position): Boolean {
        val text = document.getText()
        val lines = text.split("\n")
        
        // Check if we're after an entity declaration
        var inEntity = false
        var braceCount = 0
        
        for (i in 0 until position.line) {
            val currentLine = lines[i]
            
            if (currentLine.contains("entity") && !currentLine.startsWith("//")) {
                inEntity = true
            }
            
            if (inEntity) {
                braceCount += currentLine.count { it == '{' }
                braceCount -= currentLine.count { it == '}' }
                
                if (braceCount == 0) {
                    inEntity = false
                }
            }
        }
        
        // Check the current line
        val currentLine = lines[position.line].substring(0, position.character)
        if (currentLine.contains("entity") && !currentLine.startsWith("//")) {
            inEntity = true
        }
        
        if (inEntity) {
            braceCount += currentLine.count { it == '{' }
            braceCount -= currentLine.count { it == '}' }
            
            if (braceCount == 0) {
                inEntity = false
            }
        }
        
        return inEntity && braceCount > 0
    }
}
