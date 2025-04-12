package io.zenwave360.zdl.lsp.intellij

import com.intellij.psi.tree.IElementType
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.extapi.psi.ASTWrapperPsiElement

/**
 * PSI element types for ZDL
 */
object ZdlTypes {
    // Element types
    val ENTITY = ZdlElementType("ENTITY")
    val FIELD = ZdlElementType("FIELD")
    val ENUM = ZdlElementType("ENUM")
    val ENUM_VALUE = ZdlElementType("ENUM_VALUE")
    val SERVICE = ZdlElementType("SERVICE")
    val METHOD = ZdlElementType("METHOD")
    val AGGREGATE = ZdlElementType("AGGREGATE")
    val COMMAND = ZdlElementType("COMMAND")
    val EVENT = ZdlElementType("EVENT")
    val RELATIONSHIP = ZdlElementType("RELATIONSHIP")
    
    /**
     * Element type for ZDL
     */
    class ZdlElementType(debugName: String) : IElementType(debugName, ZdlLanguage.INSTANCE)
    
    /**
     * Factory for creating PSI elements
     */
    object Factory {
        /**
         * Create a PSI element for a node
         */
        fun createElement(node: ASTNode): PsiElement {
            return when (node.elementType) {
                ENTITY -> ZdlEntityElement(node)
                FIELD -> ZdlFieldElement(node)
                ENUM -> ZdlEnumElement(node)
                ENUM_VALUE -> ZdlEnumValueElement(node)
                SERVICE -> ZdlServiceElement(node)
                METHOD -> ZdlMethodElement(node)
                AGGREGATE -> ZdlAggregateElement(node)
                COMMAND -> ZdlCommandElement(node)
                EVENT -> ZdlEventElement(node)
                RELATIONSHIP -> ZdlRelationshipElement(node)
                else -> ASTWrapperPsiElement(node)
            }
        }
    }
    
    /**
     * PSI element for an entity
     */
    class ZdlEntityElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for a field
     */
    class ZdlFieldElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for an enum
     */
    class ZdlEnumElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for an enum value
     */
    class ZdlEnumValueElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for a service
     */
    class ZdlServiceElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for a method
     */
    class ZdlMethodElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for an aggregate
     */
    class ZdlAggregateElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for a command
     */
    class ZdlCommandElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for an event
     */
    class ZdlEventElement(node: ASTNode) : ASTWrapperPsiElement(node)
    
    /**
     * PSI element for a relationship
     */
    class ZdlRelationshipElement(node: ASTNode) : ASTWrapperPsiElement(node)
}
