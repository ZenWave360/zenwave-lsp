package io.zenwave360.zdl.lsp.providers

import io.zenwave360.zdl.lsp.model.ZdlDocument
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.slf4j.LoggerFactory

/**
 * Provider for ZDL code formatting
 */
class FormattingProvider {
    private val logger = LoggerFactory.getLogger(FormattingProvider::class.java)
    
    /**
     * Get formatting edits for a document
     */
    fun getFormattingEdits(document: ZdlDocument, options: FormattingOptions): List<TextEdit> {
        val edits = mutableListOf<TextEdit>()
        
        // Get the document text
        val text = document.getText()
        val lines = text.split("\n")
        
        // Format the document
        val formattedLines = formatLines(lines, options)
        
        // Create a single edit for the entire document
        val range = Range(Position(0, 0), Position(lines.size, 0))
        val newText = formattedLines.joinToString("\n")
        edits.add(TextEdit(range, newText))
        
        return edits
    }
    
    /**
     * Format lines of code
     */
    private fun formatLines(lines: List<String>, options: FormattingOptions): List<String> {
        val formattedLines = mutableListOf<String>()
        var indentLevel = 0
        var inComment = false
        
        for (line in lines) {
            var trimmedLine = line.trim()
            
            // Skip empty lines
            if (trimmedLine.isEmpty()) {
                formattedLines.add("")
                continue
            }
            
            // Handle comments
            if (trimmedLine.startsWith("/*")) {
                inComment = true
                formattedLines.add(getIndent(indentLevel, options) + trimmedLine)
                continue
            }
            
            if (inComment) {
                formattedLines.add(getIndent(indentLevel, options) + trimmedLine)
                if (trimmedLine.endsWith("*/")) {
                    inComment = false
                }
                continue
            }
            
            // Handle single-line comments
            if (trimmedLine.startsWith("//")) {
                formattedLines.add(getIndent(indentLevel, options) + trimmedLine)
                continue
            }
            
            // Adjust indent level for closing braces
            if (trimmedLine.startsWith("}")) {
                indentLevel--
                if (indentLevel < 0) indentLevel = 0
            }
            
            // Format the line
            formattedLines.add(getIndent(indentLevel, options) + formatLine(trimmedLine, options))
            
            // Adjust indent level for opening braces
            if (trimmedLine.endsWith("{")) {
                indentLevel++
            }
        }
        
        return formattedLines
    }
    
    /**
     * Format a single line of code
     */
    private fun formatLine(line: String, options: FormattingOptions): String {
        var formattedLine = line
        
        // Add space after commas
        formattedLine = formattedLine.replace(",", ", ")
        
        // Add space after colons
        formattedLine = formattedLine.replace(":", ": ")
        
        // Add space around equals
        formattedLine = formattedLine.replace("=", " = ")
        
        // Remove extra spaces
        formattedLine = formattedLine.replace(Regex("\\s+"), " ")
        
        // Fix spaces around braces
        formattedLine = formattedLine.replace("{ ", "{")
        formattedLine = formattedLine.replace(" }", "}")
        
        // Fix spaces around parentheses
        formattedLine = formattedLine.replace("( ", "(")
        formattedLine = formattedLine.replace(" )", ")")
        
        // Add space before opening brace
        formattedLine = formattedLine.replace("{", " {")
        
        // Remove extra spaces again
        formattedLine = formattedLine.replace(Regex("\\s+"), " ").trim()
        
        return formattedLine
    }
    
    /**
     * Get the indent string for a given level
     */
    private fun getIndent(level: Int, options: FormattingOptions): String {
        val indent = if (options.insertSpaces) " ".repeat(options.tabSize) else "\t"
        return indent.repeat(level)
    }
}
