package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class DeleteCellAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e) ?: return
        val notebook = editor.notebook ?: return
        val panel = editor.getNotebookPanel()
        val selectedId = panel.selectedCellId ?: return

        val idx = notebook.cells.indexOfFirst { it.id == selectedId }
        if (idx >= 0) {
            notebook.cells.removeAt(idx)
            notebook.isDirty = true
            panel.removeCellFromView(selectedId)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        e.presentation.isEnabled = editor?.getNotebookPanel()?.selectedCellId != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
