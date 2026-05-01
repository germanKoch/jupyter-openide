package com.openide.jupyter.filetype

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

class JupyterNotebookFileType : FileType {

    override fun getName(): String = "Jupyter Notebook"

    override fun getDescription(): String = "Jupyter Notebook file"

    override fun getDefaultExtension(): String = "ipynb"

    override fun getIcon(): Icon? = null

    override fun isBinary(): Boolean = false

    companion object {
        @JvmField
        val INSTANCE = JupyterNotebookFileType()
    }
}
