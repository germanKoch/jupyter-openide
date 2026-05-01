package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.kernel.KernelStatus

class StopKernelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e) ?: return
        editor.stopKernel()
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        e.presentation.isEnabled = editor?.kernelManager?.status?.let {
            it != KernelStatus.DISCONNECTED
        } ?: false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
