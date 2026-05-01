package com.openide.jupyter.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.openide.jupyter.editor.JupyterNotebookEditor
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.python.PythonSdkDetector

class StartKernelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = getCurrentNotebookEditor(e) ?: return

        val pythonPath = PythonSdkDetector.detectPythonInterpreter(project, editor.file.path)
        if (pythonPath == null) {
            showNotification(project, "No Python interpreter configured. Please configure a Python SDK in Project Settings.", NotificationType.WARNING)
            return
        }

        if (!PythonSdkDetector.checkJupyterInstalled(pythonPath)) {
            showInstallNotification(project, pythonPath, editor)
            return
        }

        try {
            editor.startKernel(pythonPath)
        } catch (ex: Exception) {
            showNotification(project, "Failed to start kernel: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        e.presentation.isEnabled = editor != null &&
            (editor.kernelManager == null || editor.kernelManager?.status == KernelStatus.DISCONNECTED)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun showInstallNotification(project: com.intellij.openapi.project.Project, pythonPath: String, editor: JupyterNotebookEditor) {
        val notification = com.intellij.notification.Notification(
            "Jupyter",
            "Jupyter not found",
            "Jupyter is not installed in the configured Python environment.",
            NotificationType.WARNING
        )
        notification.addAction(object : com.intellij.notification.NotificationAction("Install jupyter & ipykernel") {
            override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                notification.expire()
                Thread {
                    val success = PythonSdkDetector.installJupyter(pythonPath)
                    javax.swing.SwingUtilities.invokeLater {
                        if (success) {
                            showNotification(project, "Jupyter installed successfully. You can now start the kernel.", NotificationType.INFORMATION)
                        } else {
                            showNotification(project, "Failed to install Jupyter. Please install manually: pip install jupyter ipykernel", NotificationType.ERROR)
                        }
                    }
                }.start()
            }
        })
        notification.notify(project)
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, content: String, type: NotificationType) {
        com.intellij.notification.Notification("Jupyter", "Jupyter Notebook", content, type).notify(project)
    }
}

fun getCurrentNotebookEditor(e: AnActionEvent): JupyterNotebookEditor? {
    val project = e.project ?: return null
    val editor = FileEditorManager.getInstance(project).selectedEditor
    return editor as? JupyterNotebookEditor
}
