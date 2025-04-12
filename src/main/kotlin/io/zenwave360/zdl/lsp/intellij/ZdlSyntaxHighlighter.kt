package io.zenwave360.zdl.lsp.intellij

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * Syntax highlighter for ZDL
 */
class ZdlSyntaxHighlighter : SyntaxHighlighterBase() {
    /**
     * Create a lexer for the highlighter
     */
    override fun getHighlightingLexer(): Lexer {
        return ZdlLexer()
    }
    
    /**
     * Get the text attributes for a token type
     */
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        return when (tokenType) {
            // Keywords
            in ZdlTokenTypes.KEYWORDS -> KEYWORD_KEYS
            
            // Identifiers
            ZdlTokenTypes.IDENTIFIER -> IDENTIFIER_KEYS
            
            // Strings
            ZdlTokenTypes.STRING -> STRING_KEYS
            
            // Numbers
            ZdlTokenTypes.NUMBER -> NUMBER_KEYS
            
            // Comments
            ZdlTokenTypes.COMMENT, ZdlTokenTypes.LINE_COMMENT -> COMMENT_KEYS
            ZdlTokenTypes.JAVADOC -> DOC_COMMENT_KEYS
            
            // Braces
            ZdlTokenTypes.LBRACE, ZdlTokenTypes.RBRACE -> BRACES_KEYS
            
            // Parentheses
            ZdlTokenTypes.LPAREN, ZdlTokenTypes.RPAREN -> PARENTHESES_KEYS
            
            // Brackets
            ZdlTokenTypes.LBRACK, ZdlTokenTypes.RBRACK -> BRACKETS_KEYS
            
            // Operators
            ZdlTokenTypes.EQUALS, ZdlTokenTypes.COLON, ZdlTokenTypes.COMMA, ZdlTokenTypes.OR -> OPERATOR_KEYS
            
            // Default
            else -> EMPTY_KEYS
        }
    }
    
    companion object {
        // Text attributes
        val KEYWORD = TextAttributesKey.createTextAttributesKey("ZDL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val IDENTIFIER = TextAttributesKey.createTextAttributesKey("ZDL_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
        val STRING = TextAttributesKey.createTextAttributesKey("ZDL_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = TextAttributesKey.createTextAttributesKey("ZDL_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT = TextAttributesKey.createTextAttributesKey("ZDL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val DOC_COMMENT = TextAttributesKey.createTextAttributesKey("ZDL_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
        val BRACES = TextAttributesKey.createTextAttributesKey("ZDL_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val PARENTHESES = TextAttributesKey.createTextAttributesKey("ZDL_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
        val BRACKETS = TextAttributesKey.createTextAttributesKey("ZDL_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val OPERATOR = TextAttributesKey.createTextAttributesKey("ZDL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("ZDL_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
        
        // Keys arrays
        private val EMPTY_KEYS = arrayOf<TextAttributesKey>()
        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val DOC_COMMENT_KEYS = arrayOf(DOC_COMMENT)
        private val BRACES_KEYS = arrayOf(BRACES)
        private val PARENTHESES_KEYS = arrayOf(PARENTHESES)
        private val BRACKETS_KEYS = arrayOf(BRACKETS)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val BAD_CHARACTER_KEYS = arrayOf(BAD_CHARACTER)
    }
}
