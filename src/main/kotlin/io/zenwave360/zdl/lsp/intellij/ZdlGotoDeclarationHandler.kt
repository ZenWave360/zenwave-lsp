package io.zenwave360.zdl.lsp.intellij

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Go to declaration handler for ZDL
 */
class ZdlGotoDeclarationHandler : GotoDeclarationHandler {
    /**
     * Get the declaration targets for an element
     */
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        if (sourceElement == null) {
            return null
        }
        
        // Check if the file is a ZDL file
        val file = sourceElement.containingFile
        if (file !is ZdlFile) {
            return null
        }
        
        // Get the text of the element
        val text = sourceElement.text
        
        // Find the declaration
        val declaration = findDeclaration(file, text)
        
        return if (declaration != null) {
            arrayOf(declaration)
        } else {
            null
        }
    }
    
    /**
     * Find the declaration of an element in a file
     */
    private fun findDeclaration(file: PsiFile, name: String): PsiElement? {
        // Find all elements in the file
        val elements = PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java)
        
        // Look for a declaration with the given name
        for (element in elements) {
            if (element.text == name) {
                // Check if it's a declaration
                if (isDeclaration(element)) {
                    return element
                }
            }
        }
        
        return null
    }
    
    /**
     * Check if an element is a declaration
     */
    private fun isDeclaration(element: PsiElement): Boolean {
        // Check if the element is a declaration
        val parent = element.parent
        if (parent != null) {
            val parentText = parent.text
            return parentText.contains("entity") || parentText.contains("enum") ||
                    parentText.contains("service") || parentText.contains("aggregate") ||
                    parentText.contains("event")
        }
        
        return false
    }
}
