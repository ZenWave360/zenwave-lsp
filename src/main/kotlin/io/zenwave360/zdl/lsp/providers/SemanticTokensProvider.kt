package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokens
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL semantic tokens
 */
class SemanticTokensProvider {
    private val logger = LoggerFactory.getLogger(SemanticTokensProvider::class.java)
    
    // Token types
    private val TOKEN_TYPE_NAMESPACE = 0
    private val TOKEN_TYPE_TYPE = 1
    private val TOKEN_TYPE_CLASS = 2
    private val TOKEN_TYPE_ENUM = 3
    private val TOKEN_TYPE_INTERFACE = 4
    private val TOKEN_TYPE_STRUCT = 5
    private val TOKEN_TYPE_TYPE_PARAMETER = 6
    private val TOKEN_TYPE_PARAMETER = 7
    private val TOKEN_TYPE_VARIABLE = 8
    private val TOKEN_TYPE_PROPERTY = 9
    private val TOKEN_TYPE_ENUM_MEMBER = 10
    private val TOKEN_TYPE_EVENT = 11
    private val TOKEN_TYPE_FUNCTION = 12
    private val TOKEN_TYPE_METHOD = 13
    private val TOKEN_TYPE_MACRO = 14
    private val TOKEN_TYPE_KEYWORD = 15
    private val TOKEN_TYPE_MODIFIER = 16
    private val TOKEN_TYPE_COMMENT = 17
    private val TOKEN_TYPE_STRING = 18
    private val TOKEN_TYPE_NUMBER = 19
    private val TOKEN_TYPE_REGEXP = 20
    private val TOKEN_TYPE_OPERATOR = 21
    
    // Token modifiers
    private val TOKEN_MODIFIER_DECLARATION = 0
    private val TOKEN_MODIFIER_DEFINITION = 1
    private val TOKEN_MODIFIER_READONLY = 2
    private val TOKEN_MODIFIER_STATIC = 3
    private val TOKEN_MODIFIER_DEPRECATED = 4
    private val TOKEN_MODIFIER_ABSTRACT = 5
    private val TOKEN_MODIFIER_ASYNC = 6
    private val TOKEN_MODIFIER_MODIFICATION = 7
    private val TOKEN_MODIFIER_DOCUMENTATION = 8
    private val TOKEN_MODIFIER_DEFAULT_LIBRARY = 9
    
    // ZDL keywords
    private val keywords = setOf(
        "import", "config", "apis", "plugins", "policies", "disabled",
        "asyncapi", "openapi", "entity", "enum", "input", "output", "event",
        "relationship", "ManyToMany", "ManyToOne", "OneToMany", "OneToOne",
        "service", "aggregate", "id", "for", "to", "withEvents", "with",
        "required", "unique", "min", "max", "minlength", "maxlength", "pattern", "email"
    )
    
    /**
     * Get semantic tokens for a document
     */
    fun getSemanticTokens(document: ZdlDocument): SemanticTokens {
        val data = mutableListOf<Int>()
        
        // Get the document text
        val text = document.getText()
        val lines = text.split("\n")
        
        // Process each line
        var prevLine = 0
        var prevChar = 0
        
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            var charIndex = 0
            
            while (charIndex < line.length) {
                // Skip whitespace
                if (line[charIndex].isWhitespace()) {
                    charIndex++
                    continue
                }
                
                // Check for comments
                if (charIndex < line.length - 1 && line[charIndex] == '/' && line[charIndex + 1] == '/') {
                    // Single-line comment
                    val tokenLength = line.length - charIndex
                    addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_COMMENT, 0, prevLine, prevChar)
                    prevLine = lineIndex
                    prevChar = charIndex
                    break
                }
                
                if (charIndex < line.length - 1 && line[charIndex] == '/' && line[charIndex + 1] == '*') {
                    // Multi-line comment
                    val commentEnd = line.indexOf("*/", charIndex)
                    if (commentEnd != -1) {
                        val tokenLength = commentEnd + 2 - charIndex
                        addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_COMMENT, 0, prevLine, prevChar)
                        prevLine = lineIndex
                        prevChar = charIndex
                        charIndex = commentEnd + 2
                        continue
                    } else {
                        val tokenLength = line.length - charIndex
                        addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_COMMENT, 0, prevLine, prevChar)
                        prevLine = lineIndex
                        prevChar = charIndex
                        break
                    }
                }
                
                // Check for strings
                if (line[charIndex] == '"' || line[charIndex] == '\'') {
                    val quote = line[charIndex]
                    val stringEnd = line.indexOf(quote, charIndex + 1)
                    if (stringEnd != -1) {
                        val tokenLength = stringEnd + 1 - charIndex
                        addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_STRING, 0, prevLine, prevChar)
                        prevLine = lineIndex
                        prevChar = charIndex
                        charIndex = stringEnd + 1
                        continue
                    } else {
                        val tokenLength = line.length - charIndex
                        addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_STRING, 0, prevLine, prevChar)
                        prevLine = lineIndex
                        prevChar = charIndex
                        break
                    }
                }
                
                // Check for numbers
                if (line[charIndex].isDigit()) {
                    var tokenLength = 0
                    while (charIndex + tokenLength < line.length && (line[charIndex + tokenLength].isDigit() || line[charIndex + tokenLength] == '.')) {
                        tokenLength++
                    }
                    addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_NUMBER, 0, prevLine, prevChar)
                    prevLine = lineIndex
                    prevChar = charIndex
                    charIndex += tokenLength
                    continue
                }
                
                // Check for identifiers and keywords
                if (line[charIndex].isLetterOrDigit() || line[charIndex] == '_') {
                    var tokenLength = 0
                    while (charIndex + tokenLength < line.length && (line[charIndex + tokenLength].isLetterOrDigit() || line[charIndex + tokenLength] == '_')) {
                        tokenLength++
                    }
                    val token = line.substring(charIndex, charIndex + tokenLength)
                    
                    if (token in keywords) {
                        // Keyword
                        addToken(data, lineIndex, charIndex, tokenLength, TOKEN_TYPE_KEYWORD, 0, prevLine, prevChar)
                    } else {
                        // Identifier
                        val tokenType = getIdentifierTokenType(token, document)
                        addToken(data, lineIndex, charIndex, tokenLength, tokenType, 0, prevLine, prevChar)
                    }
                    
                    prevLine = lineIndex
                    prevChar = charIndex
                    charIndex += tokenLength
                    continue
                }
                
                // Check for operators
                if (isOperator(line[charIndex])) {
                    addToken(data, lineIndex, charIndex, 1, TOKEN_TYPE_OPERATOR, 0, prevLine, prevChar)
                    prevLine = lineIndex
                    prevChar = charIndex
                    charIndex++
                    continue
                }
                
                // Skip other characters
                charIndex++
            }
        }
        
        return SemanticTokens(data)
    }
    
    /**
     * Add a token to the data list
     */
    private fun addToken(data: MutableList<Int>, line: Int, startChar: Int, length: Int, tokenType: Int, tokenModifiers: Int, prevLine: Int, prevChar: Int) {
        // Calculate delta line and delta start
        val deltaLine = line - prevLine
        val deltaStart = if (deltaLine == 0) startChar - prevChar else startChar
        
        // Add the token data
        data.add(deltaLine)
        data.add(deltaStart)
        data.add(length)
        data.add(tokenType)
        data.add(tokenModifiers)
    }
    
    /**
     * Get the token type for an identifier
     */
    private fun getIdentifierTokenType(identifier: String, document: ZdlDocument): Int {
        // Check if it's an entity
        val model = document.getModel()
        if (model != null) {
            model.entities?.forEach { entity ->
                if (entity.name == identifier) {
                    return TOKEN_TYPE_CLASS
                }
            }
            
            // Check if it's an enum
            model.enums?.forEach { enum ->
                if (enum.name == identifier) {
                    return TOKEN_TYPE_ENUM
                }
                
                // Check if it's an enum value
                enum.values?.forEach { value ->
                    if (value.name == identifier) {
                        return TOKEN_TYPE_ENUM_MEMBER
                    }
                }
            }
            
            // Check if it's a service
            model.services?.forEach { service ->
                if (service.name == identifier) {
                    return TOKEN_TYPE_INTERFACE
                }
                
                // Check if it's a method
                service.methods?.forEach { method ->
                    if (method.name == identifier) {
                        return TOKEN_TYPE_METHOD
                    }
                }
            }
            
            // Check if it's an aggregate
            model.aggregates?.forEach { aggregate ->
                if (aggregate.name == identifier) {
                    return TOKEN_TYPE_STRUCT
                }
                
                // Check if it's a command
                aggregate.commands?.forEach { command ->
                    if (command.name == identifier) {
                        return TOKEN_TYPE_FUNCTION
                    }
                }
            }
            
            // Check if it's an event
            model.events?.forEach { event ->
                if (event.name == identifier) {
                    return TOKEN_TYPE_EVENT
                }
            }
        }
        
        // Default to variable
        return TOKEN_TYPE_VARIABLE
    }
    
    /**
     * Check if a character is an operator
     */
    private fun isOperator(c: Char): Boolean {
        return c in setOf('{', '}', '[', ']', '(', ')', ',', ':', '=', '|')
    }
}
