package com.openide.jupyter.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class JupyterNotebookEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension?.equals("ipynb", ignoreCase = true) == true
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return JupyterNotebookEditor(project, file)
    }

    override fun getEditorTypeId(): String = "jupyter-notebook-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
