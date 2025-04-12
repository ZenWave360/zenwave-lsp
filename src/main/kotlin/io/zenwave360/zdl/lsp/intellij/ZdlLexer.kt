package io.zenwave360.zdl.lsp.intellij

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import io.github.zenwave360.zdl.antlr.ZdlLexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Token

/**
 * Lexer for ZDL
 */
class ZdlLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var lexerTokens: List<Token> = emptyList()
    private var currentTokenIndex = 0
    
    /**
     * Start lexing at the specified position
     */
    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        
        // Create ANTLR lexer
        val lexer = ZdlLexer(CharStreams.fromString(buffer.subSequence(startOffset, endOffset).toString()))
        
        // Collect all tokens
        lexerTokens = lexer.allTokens
        currentTokenIndex = 0
    }
    
    /**
     * Get the current token type
     */
    override fun getTokenType(): IElementType? {
        if (currentTokenIndex >= lexerTokens.size) {
            return null
        }
        
        val token = lexerTokens[currentTokenIndex]
        return mapTokenType(token.type)
    }
    
    /**
     * Get the current token start offset
     */
    override fun getTokenStart(): Int {
        if (currentTokenIndex >= lexerTokens.size) {
            return endOffset
        }
        
        return startOffset + lexerTokens[currentTokenIndex].startIndex
    }
    
    /**
     * Get the current token end offset
     */
    override fun getTokenEnd(): Int {
        if (currentTokenIndex >= lexerTokens.size) {
            return endOffset
        }
        
        return startOffset + lexerTokens[currentTokenIndex].stopIndex + 1
    }
    
    /**
     * Advance to the next token
     */
    override fun advance() {
        currentTokenIndex++
    }
    
    /**
     * Get the current buffer
     */
    override fun getBufferSequence(): CharSequence {
        return buffer
    }
    
    /**
     * Get the end offset of the buffer
     */
    override fun getBufferEnd(): Int {
        return endOffset
    }
    
    /**
     * Map an ANTLR token type to an IntelliJ token type
     */
    private fun mapTokenType(tokenType: Int): IElementType {
        return when (tokenType) {
            ZdlLexer.ENTITY -> ZdlTokenTypes.ENTITY_KEYWORD
            ZdlLexer.ENUM -> ZdlTokenTypes.ENUM_KEYWORD
            ZdlLexer.SERVICE -> ZdlTokenTypes.SERVICE_KEYWORD
            ZdlLexer.AGGREGATE -> ZdlTokenTypes.AGGREGATE_KEYWORD
            ZdlLexer.EVENT -> ZdlTokenTypes.EVENT_KEYWORD
            ZdlLexer.RELATIONSHIP -> ZdlTokenTypes.RELATIONSHIP_KEYWORD
            ZdlLexer.INPUT -> ZdlTokenTypes.INPUT_KEYWORD
            ZdlLexer.OUTPUT -> ZdlTokenTypes.OUTPUT_KEYWORD
            ZdlLexer.REQUIRED -> ZdlTokenTypes.REQUIRED_KEYWORD
            ZdlLexer.UNIQUE -> ZdlTokenTypes.UNIQUE_KEYWORD
            ZdlLexer.ID -> ZdlTokenTypes.IDENTIFIER
            ZdlLexer.INT, ZdlLexer.NUMBER -> ZdlTokenTypes.NUMBER
            ZdlLexer.DOUBLE_QUOTED_STRING, ZdlLexer.SINGLE_QUOTED_STRING -> ZdlTokenTypes.STRING
            ZdlLexer.JAVADOC -> ZdlTokenTypes.JAVADOC
            ZdlLexer.LINE_COMMENT -> ZdlTokenTypes.LINE_COMMENT
            ZdlLexer.COMMENT -> ZdlTokenTypes.COMMENT
            ZdlLexer.LPAREN -> ZdlTokenTypes.LPAREN
            ZdlLexer.RPAREN -> ZdlTokenTypes.RPAREN
            ZdlLexer.LBRACE -> ZdlTokenTypes.LBRACE
            ZdlLexer.RBRACE -> ZdlTokenTypes.RBRACE
            ZdlLexer.LBRACK -> ZdlTokenTypes.LBRACK
            ZdlLexer.RBRACK -> ZdlTokenTypes.RBRACK
            ZdlLexer.COMMA -> ZdlTokenTypes.COMMA
            ZdlLexer.COLON -> ZdlTokenTypes.COLON
            ZdlLexer.EQUALS -> ZdlTokenTypes.EQUALS
            ZdlLexer.OR -> ZdlTokenTypes.OR
            ZdlLexer.WS -> ZdlTokenTypes.WHITE_SPACE
            else -> ZdlTokenTypes.IDENTIFIER
        }
    }
}
