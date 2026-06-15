package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Opens the in-notebook find bar with the replace row (Cmd/Ctrl+R). Enabled only
 * while a Jupyter notebook is the active editor. See [FindInNotebookAction].
 */
class ReplaceInNotebookAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        getCurrentNotebookEditor(e)?.getNotebookPanel()?.openFind(true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getCurrentNotebookEditor(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
