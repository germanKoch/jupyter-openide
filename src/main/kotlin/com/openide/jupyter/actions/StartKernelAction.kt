package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.openide.jupyter.editor.JupyterNotebookEditor
import com.openide.jupyter.python.PythonSdkDetector
import java.io.File

class StartKernelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = getCurrentNotebookEditor(e) ?: return
        val dialog = KernelConnectionDialog(
            project = project,
            notebookDirectory = editor.file.parent?.let { File(it.path) },
            pythonPathProvider = {
                PythonSdkDetector.detectPythonInterpreter(project, editor.file.path)
            }
        )
        if (dialog.showAndGet()) {
            editor.startKernel(dialog.selectedTarget())
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        e.presentation.isEnabled = editor?.canStartKernel() == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

}

fun getCurrentNotebookEditor(e: AnActionEvent): JupyterNotebookEditor? {
    val project = e.project ?: return null
    val editor = FileEditorManager.getInstance(project).selectedEditor
    return editor as? JupyterNotebookEditor
}
