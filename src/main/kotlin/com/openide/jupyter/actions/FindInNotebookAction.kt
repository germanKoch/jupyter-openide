package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Opens the in-notebook find bar (Cmd/Ctrl+F). Enabled only while a Jupyter
 * notebook is the active editor, so it shadows the platform Find action there
 * but leaves it untouched everywhere else. This is the fallback path for when
 * the IDE consumes the keystroke before the embedded JCEF page receives it.
 */
class FindInNotebookAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        getCurrentNotebookEditor(e)?.getNotebookPanel()?.openFind(false)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getCurrentNotebookEditor(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
