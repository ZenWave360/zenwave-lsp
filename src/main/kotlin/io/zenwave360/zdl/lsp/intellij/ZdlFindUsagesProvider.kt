package io.zenwave360.zdl.lsp.intellij

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet

/**
 * Find usages provider for ZDL
 */
class ZdlFindUsagesProvider : FindUsagesProvider {
    /**
     * Get a words scanner for the language
     */
    override fun getWordsScanner(): WordsScanner {
        return DefaultWordsScanner(
            ZdlLexer(),
            TokenSet.create(ZdlTokenTypes.IDENTIFIER),
            ZdlTokenTypes.COMMENTS,
            ZdlTokenTypes.STRINGS
        )
    }
    
    /**
     * Check if an element can have usages
     */
    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        return psiElement is ZdlTypes.ZdlEntityElement ||
                psiElement is ZdlTypes.ZdlEnumElement ||
                psiElement is ZdlTypes.ZdlServiceElement ||
                psiElement is ZdlTypes.ZdlAggregateElement ||
                psiElement is ZdlTypes.ZdlEventElement
    }
    
    /**
     * Get the help ID for the find usages dialog
     */
    override fun getHelpId(psiElement: PsiElement): String? {
        return null
    }
    
    /**
     * Get the type of an element
     */
    override fun getType(element: PsiElement): String {
        return when (element) {
            is ZdlTypes.ZdlEntityElement -> "entity"
            is ZdlTypes.ZdlEnumElement -> "enum"
            is ZdlTypes.ZdlServiceElement -> "service"
            is ZdlTypes.ZdlAggregateElement -> "aggregate"
            is ZdlTypes.ZdlEventElement -> "event"
            is ZdlTypes.ZdlFieldElement -> "field"
            is ZdlTypes.ZdlEnumValueElement -> "enum value"
            is ZdlTypes.ZdlMethodElement -> "method"
            is ZdlTypes.ZdlCommandElement -> "command"
            is ZdlTypes.ZdlRelationshipElement -> "relationship"
            else -> "element"
        }
    }
    
    /**
     * Get the descriptive name of an element
     */
    override fun getDescriptiveName(element: PsiElement): String {
        return element.text
    }
    
    /**
     * Get the node text for an element
     */
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return element.text
    }
}
