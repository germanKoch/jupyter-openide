package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.kernel.KernelStatus

class InterruptKernelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e) ?: return
        editor.kernelManager?.interrupt()
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        e.presentation.isEnabled = editor?.kernelManager?.status == KernelStatus.BUSY
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
