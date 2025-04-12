package io.zenwave360.zdl.lsp.intellij

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Completion contributor for ZDL
 */
class ZdlCompletionContributor : CompletionContributor() {
    init {
        // Add completion for keywords
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(ZdlLanguage.INSTANCE),
            KeywordCompletionProvider()
        )
    }
    
    /**
     * Completion provider for keywords
     */
    private class KeywordCompletionProvider : CompletionProvider<CompletionParameters>() {
        /**
         * Add completion variants
         */
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            // Add keywords
            KEYWORDS.forEach { keyword ->
                result.addElement(
                    LookupElementBuilder.create(keyword)
                        .bold()
                        .withTypeText("keyword")
                )
            }
            
            // Add field types
            FIELD_TYPES.forEach { type ->
                result.addElement(
                    LookupElementBuilder.create(type)
                        .withTypeText("type")
                )
            }
            
            // Add validations
            VALIDATIONS.forEach { validation ->
                result.addElement(
                    LookupElementBuilder.create(validation)
                        .withTypeText("validation")
                )
            }
        }
        
        companion object {
            // ZDL keywords
            private val KEYWORDS = listOf(
                "import", "config", "apis", "plugins", "policies", "disabled",
                "asyncapi", "openapi", "entity", "enum", "input", "output", "event",
                "relationship", "ManyToMany", "ManyToOne", "OneToMany", "OneToOne",
                "service", "aggregate", "id", "for", "to", "withEvents", "with"
            )
            
            // ZDL field types
            private val FIELD_TYPES = listOf(
                "String", "Integer", "Long", "int", "long", "BigDecimal", "Float", "float",
                "Double", "double", "Enum", "Boolean", "boolean", "Map", "LocalDate",
                "LocalDateTime", "ZonedDate", "ZonedDateTime", "Instant", "Duration",
                "UUID", "byte", "byte[]", "Blob", "AnyBlob", "ImageBlob", "TextBlob"
            )
            
            // ZDL field validations
            private val VALIDATIONS = listOf(
                "required", "unique", "min", "max", "minlength", "maxlength", "pattern", "email"
            )
        }
    }
}
