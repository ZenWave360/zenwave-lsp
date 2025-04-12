package io.zenwave360.zdl.lsp.intellij

import com.intellij.lang.Language

/**
 * Language definition for ZDL
 */
class ZdlLanguage private constructor() : Language("ZDL") {
    companion object {
        /**
         * The instance of the language
         */
        val INSTANCE = ZdlLanguage()
    }
}
