package com.openide.jupyter.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

class JupyterNotebookFileType : FileType {

    override fun getName(): String = "Jupyter Notebook"

    override fun getDescription(): String = "Jupyter Notebook file"

    override fun getDefaultExtension(): String = "ipynb"

    override fun getIcon(): Icon = ICON

    override fun isBinary(): Boolean = false

    companion object {
        @JvmField
        val INSTANCE = JupyterNotebookFileType()

        private val ICON: Icon =
            IconLoader.getIcon("/icons/jupyter-notebook.svg", JupyterNotebookFileType::class.java)
    }
}
