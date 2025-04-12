package io.zenwave360.zdl.lsp.model

import io.github.zenwave360.zdl.ZdlParser
import io.github.zenwave360.zdl.antlr.ZdlModel
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Represents a ZDL document in the language server
 */
class ZdlDocument(
    val uri: String,
    val languageId: String,
    var version: Int,
    private var text: String
) {
    private val logger = LoggerFactory.getLogger(ZdlDocument::class.java)
    
    // Parsed model
    private var model: ZdlModel? = null
    
    // Parse errors
    private var parseErrors = mutableListOf<ParseError>()
    
    init {
        // Parse the document
        parse()
    }
    
    /**
     * Get the document text
     */
    fun getText(): String {
        return text
    }
    
    /**
     * Set the document text
     */
    fun setText(text: String) {
        this.text = text
        parse()
    }
    
    /**
     * Apply a change to the document
     */
    fun applyChange(range: Range, newText: String) {
        // Convert range to offsets
        val startOffset = positionToOffset(range.start)
        val endOffset = positionToOffset(range.end)
        
        // Apply the change
        if (startOffset != -1 && endOffset != -1) {
            val before = text.substring(0, startOffset)
            val after = text.substring(endOffset)
            text = before + newText + after
            
            // Re-parse the document
            parse()
        }
    }
    
    /**
     * Get the parsed model
     */
    fun getModel(): ZdlModel? {
        return model
    }
    
    /**
     * Get the parse errors
     */
    fun getParseErrors(): List<ParseError> {
        return parseErrors
    }
    
    /**
     * Convert a position to an offset in the document
     */
    fun positionToOffset(position: Position): Int {
        val lines = text.split("\n")
        
        // Check if the position is valid
        if (position.line < 0 || position.line >= lines.size) {
            return -1
        }
        
        // Calculate the offset
        var offset = 0
        for (i in 0 until position.line) {
            offset += lines[i].length + 1 // +1 for the newline character
        }
        
        // Add the character offset
        val line = lines[position.line]
        if (position.character < 0 || position.character > line.length) {
            return -1
        }
        
        offset += position.character
        
        return offset
    }
    
    /**
     * Convert an offset to a position in the document
     */
    fun offsetToPosition(offset: Int): Position {
        if (offset < 0 || offset > text.length) {
            return Position(0, 0)
        }
        
        var line = 0
        var character = 0
        var currentOffset = 0
        
        val lines = text.split("\n")
        for (currentLine in lines) {
            if (currentOffset + currentLine.length + 1 > offset) {
                character = offset - currentOffset
                break
            }
            
            currentOffset += currentLine.length + 1 // +1 for the newline character
            line++
        }
        
        return Position(line, character)
    }
    
    /**
     * Get the line at the given line number
     */
    fun getLine(line: Int): String? {
        val lines = text.split("\n")
        if (line < 0 || line >= lines.size) {
            return null
        }
        
        return lines[line]
    }
    
    /**
     * Get the word at the given position
     */
    fun getWordAtPosition(position: Position): String {
        val line = getLine(position.line) ?: return ""
        
        // Find the start of the word
        var start = position.character
        while (start > 0 && isWordChar(line[start - 1])) {
            start--
        }
        
        // Find the end of the word
        var end = position.character
        while (end < line.length && isWordChar(line[end])) {
            end++
        }
        
        // Extract the word
        return if (start < end) line.substring(start, end) else ""
    }
    
    /**
     * Check if a character is part of a word
     */
    private fun isWordChar(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '_'
    }
    
    /**
     * Parse the document
     */
    private fun parse() {
        // Clear previous errors
        parseErrors.clear()
        
        try {
            // Parse the document
            val parser = ZdlParser()
            model = parser.parseModel(text)
        } catch (e: IOException) {
            logger.error("Error parsing document: ${e.message}")
            parseErrors.add(ParseError("Error parsing document: ${e.message}", Range(Position(0, 0), Position(0, 0))))
        } catch (e: Exception) {
            logger.error("Error parsing document: ${e.message}")
            parseErrors.add(ParseError("Error parsing document: ${e.message}", Range(Position(0, 0), Position(0, 0))))
        }
    }
    
    /**
     * Represents a parse error in the document
     */
    data class ParseError(
        val message: String,
        val range: Range
    )
}
