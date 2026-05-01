package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.model.Cell
import com.openide.jupyter.model.CellType

class AddCellAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        addCell(e, CellType.CODE)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = getCurrentNotebookEditor(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class AddMarkdownCellAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        addCell(e, CellType.MARKDOWN)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = getCurrentNotebookEditor(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

private fun addCell(e: AnActionEvent, type: CellType) {
    val editor = getCurrentNotebookEditor(e) ?: return
    val notebook = editor.notebook ?: return
    val panel = editor.getNotebookPanel()

    val newCell = Cell(cellType = type)
    val selectedId = panel.selectedCellId
    val insertIndex = if (selectedId != null) {
        val idx = notebook.cells.indexOfFirst { it.id == selectedId }
        if (idx >= 0) idx + 1 else notebook.cells.size
    } else {
        notebook.cells.size
    }

    notebook.cells.add(insertIndex, newCell)
    notebook.isDirty = true

    if (selectedId != null) {
        panel.insertCellAfter(selectedId, newCell)
    } else {
        panel.addCellToView(newCell)
    }
}
