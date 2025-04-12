package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * File type for ZDL files
 */
class ZdlFileType : LanguageFileType(ZdlLanguage.INSTANCE) {
    /**
     * Get the name of the file type
     */
    override fun getName(): String {
        return "ZDL"
    }
    
    /**
     * Get the description of the file type
     */
    override fun getDescription(): String {
        return "ZenWave Domain Language file"
    }
    
    /**
     * Get the default extension for the file type
     */
    override fun getDefaultExtension(): String {
        return "zdl"
    }
    
    /**
     * Get the icon for the file type
     */
    override fun getIcon(): Icon? {
        return ZdlIcons.FILE
    }
    
    companion object {
        /**
         * The instance of the file type
         */
        val INSTANCE = ZdlFileType()
    }
}
