package io.zenwave360.zdl.lsp.intellij

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * PSI file for ZDL
 */
class ZdlFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZdlLanguage.INSTANCE) {
    /**
     * Get the file type
     */
    override fun getFileType(): FileType {
        return ZdlFileType.INSTANCE
    }
    
    /**
     * Get the file name
     */
    override fun toString(): String {
        return "ZDL File"
    }
}
