package io.zenwave360.zdl.lsp.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Parser definition for ZDL
 */
class ZdlParserDefinition : ParserDefinition {
    /**
     * Create a lexer for the language
     */
    override fun createLexer(project: Project): Lexer {
        return ZdlLexer()
    }
    
    /**
     * Get the file node type
     */
    override fun getFileNodeType(): IFileElementType {
        return FILE
    }
    
    /**
     * Get the comment token set
     */
    override fun getCommentTokens(): TokenSet {
        return ZdlTokenTypes.COMMENTS
    }
    
    /**
     * Get the string literal token set
     */
    override fun getStringLiteralElements(): TokenSet {
        return ZdlTokenTypes.STRINGS
    }
    
    /**
     * Create a parser for the language
     */
    override fun createParser(project: Project): PsiParser {
        return ZdlParser()
    }
    
    /**
     * Create a PSI element for a node
     */
    override fun createElement(node: ASTNode): PsiElement {
        return ZdlTypes.Factory.createElement(node)
    }
    
    /**
     * Create a PSI file for a file view provider
     */
    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return ZdlFile(viewProvider)
    }
    
    companion object {
        /**
         * The file element type
         */
        val FILE = IFileElementType(ZdlLanguage.INSTANCE)
    }
}
