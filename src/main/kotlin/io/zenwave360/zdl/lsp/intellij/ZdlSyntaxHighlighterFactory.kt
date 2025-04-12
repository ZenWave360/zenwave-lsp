package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Factory for creating ZDL syntax highlighters
 */
class ZdlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    /**
     * Create a syntax highlighter for a file
     */
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return ZdlSyntaxHighlighter()
    }
}
