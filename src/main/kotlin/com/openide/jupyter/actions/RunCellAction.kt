package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.model.CellExecutionState
import com.openide.jupyter.model.CellType

class RunCellAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e) ?: return
        val cell = editor.getSelectedCell() ?: return
        if (cell.cellType != CellType.CODE) return
        editor.executeCell(cell.id)
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        val selectedCell = editor?.getSelectedCell()
        val hasCell = selectedCell?.cellType == CellType.CODE &&
            selectedCell.executionState != CellExecutionState.EXECUTING
        val canRun = editor?.kernelManager?.status != KernelStatus.STARTING
        // executeCell already performs the supported on-demand kernel startup.
        e.presentation.isEnabled = hasCell && canRun
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
