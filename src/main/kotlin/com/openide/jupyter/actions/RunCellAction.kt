package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.kernel.KernelStatus
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
        val hasKernel = editor?.kernelManager?.status?.let {
            it == KernelStatus.IDLE || it == KernelStatus.BUSY
        } ?: false
        val hasCell = editor?.getSelectedCell()?.cellType == CellType.CODE
        e.presentation.isEnabled = hasKernel && hasCell
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
