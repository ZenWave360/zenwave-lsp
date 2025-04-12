package io.zenwave360.zdl.lsp.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import io.github.zenwave360.zdl.ZdlParser
import io.github.zenwave360.zdl.antlr.ZdlModel
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

/**
 * Parser for ZDL
 */
class ZdlParser : PsiParser {
    /**
     * Parse a file
     */
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        
        // Parse the file using the ZDL parser
        try {
            val text = builder.originalText.toString()
            val parser = ZdlParser()
            val model = parser.parseModel(text)
            
            // Build the AST
            buildAst(builder, model)
        } catch (e: Exception) {
            // Handle parsing errors
            builder.error("Error parsing ZDL file: ${e.message}")
        }
        
        rootMarker.done(root)
        return builder.treeBuilt
    }
    
    /**
     * Build the AST from the ZDL model
     */
    private fun buildAst(builder: PsiBuilder, model: ZdlModel) {
        // Process entities
        model.entities?.forEach { entity ->
            val entityMarker = builder.mark()
            
            // Add entity name
            val nameMarker = builder.mark()
            nameMarker.done(ZdlTokenTypes.IDENTIFIER)
            
            // Process fields
            entity.fields?.forEach { field ->
                val fieldMarker = builder.mark()
                
                // Add field name
                val fieldNameMarker = builder.mark()
                fieldNameMarker.done(ZdlTokenTypes.IDENTIFIER)
                
                // Add field type
                val fieldTypeMarker = builder.mark()
                fieldTypeMarker.done(ZdlTokenTypes.IDENTIFIER)
                
                fieldMarker.done(ZdlTypes.FIELD)
            }
            
            entityMarker.done(ZdlTypes.ENTITY)
        }
        
        // Process enums
        model.enums?.forEach { enum ->
            val enumMarker = builder.mark()
            
            // Add enum name
            val nameMarker = builder.mark()
            nameMarker.done(ZdlTokenTypes.IDENTIFIER)
            
            // Process enum values
            enum.values?.forEach { value ->
                val valueMarker = builder.mark()
                
                // Add value name
                val valueNameMarker = builder.mark()
                valueNameMarker.done(ZdlTokenTypes.IDENTIFIER)
                
                valueMarker.done(ZdlTypes.ENUM_VALUE)
            }
            
            enumMarker.done(ZdlTypes.ENUM)
        }
        
        // Process services
        model.services?.forEach { service ->
            val serviceMarker = builder.mark()
            
            // Add service name
            val nameMarker = builder.mark()
            nameMarker.done(ZdlTokenTypes.IDENTIFIER)
            
            // Process methods
            service.methods?.forEach { method ->
                val methodMarker = builder.mark()
                
                // Add method name
                val methodNameMarker = builder.mark()
                methodNameMarker.done(ZdlTokenTypes.IDENTIFIER)
                
                methodMarker.done(ZdlTypes.METHOD)
            }
            
            serviceMarker.done(ZdlTypes.SERVICE)
        }
        
        // Process aggregates
        model.aggregates?.forEach { aggregate ->
            val aggregateMarker = builder.mark()
            
            // Add aggregate name
            val nameMarker = builder.mark()
            nameMarker.done(ZdlTokenTypes.IDENTIFIER)
            
            // Process commands
            aggregate.commands?.forEach { command ->
                val commandMarker = builder.mark()
                
                // Add command name
                val commandNameMarker = builder.mark()
                commandNameMarker.done(ZdlTokenTypes.IDENTIFIER)
                
                commandMarker.done(ZdlTypes.COMMAND)
            }
            
            aggregateMarker.done(ZdlTypes.AGGREGATE)
        }
    }
}
