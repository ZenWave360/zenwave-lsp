package io.zenwave360.zdl.lsp.intellij

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * Token types for ZDL
 */
object ZdlTokenTypes {
    // Basic token types
    val IDENTIFIER = ZdlElementType("IDENTIFIER")
    val KEYWORD = ZdlElementType("KEYWORD")
    val STRING = ZdlElementType("STRING")
    val NUMBER = ZdlElementType("NUMBER")
    val COMMENT = ZdlElementType("COMMENT")
    val LINE_COMMENT = ZdlElementType("LINE_COMMENT")
    val JAVADOC = ZdlElementType("JAVADOC")
    val WHITE_SPACE = ZdlElementType("WHITE_SPACE")
    
    // Punctuation
    val LPAREN = ZdlElementType("LPAREN") // (
    val RPAREN = ZdlElementType("RPAREN") // )
    val LBRACE = ZdlElementType("LBRACE") // {
    val RBRACE = ZdlElementType("RBRACE") // }
    val LBRACK = ZdlElementType("LBRACK") // [
    val RBRACK = ZdlElementType("RBRACK") // ]
    val COMMA = ZdlElementType("COMMA") // ,
    val COLON = ZdlElementType("COLON") // :
    val EQUALS = ZdlElementType("EQUALS") // =
    val OR = ZdlElementType("OR") // |
    
    // Keywords
    val ENTITY_KEYWORD = ZdlElementType("ENTITY_KEYWORD")
    val ENUM_KEYWORD = ZdlElementType("ENUM_KEYWORD")
    val SERVICE_KEYWORD = ZdlElementType("SERVICE_KEYWORD")
    val AGGREGATE_KEYWORD = ZdlElementType("AGGREGATE_KEYWORD")
    val EVENT_KEYWORD = ZdlElementType("EVENT_KEYWORD")
    val RELATIONSHIP_KEYWORD = ZdlElementType("RELATIONSHIP_KEYWORD")
    val INPUT_KEYWORD = ZdlElementType("INPUT_KEYWORD")
    val OUTPUT_KEYWORD = ZdlElementType("OUTPUT_KEYWORD")
    val REQUIRED_KEYWORD = ZdlElementType("REQUIRED_KEYWORD")
    val UNIQUE_KEYWORD = ZdlElementType("UNIQUE_KEYWORD")
    
    // Token sets
    val COMMENTS = TokenSet.create(COMMENT, LINE_COMMENT, JAVADOC)
    val STRINGS = TokenSet.create(STRING)
    val KEYWORDS = TokenSet.create(
        ENTITY_KEYWORD, ENUM_KEYWORD, SERVICE_KEYWORD, AGGREGATE_KEYWORD,
        EVENT_KEYWORD, RELATIONSHIP_KEYWORD, INPUT_KEYWORD, OUTPUT_KEYWORD,
        REQUIRED_KEYWORD, UNIQUE_KEYWORD
    )
    val BRACES = TokenSet.create(LBRACE, RBRACE)
    val PARENTHESES = TokenSet.create(LPAREN, RPAREN)
    val BRACKETS = TokenSet.create(LBRACK, RBRACK)
    
    /**
     * Element type for ZDL
     */
    class ZdlElementType(debugName: String) : IElementType(debugName, ZdlLanguage.INSTANCE)
}
