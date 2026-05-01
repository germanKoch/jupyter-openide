package com.openide.jupyter.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.openide.jupyter.kernel.KernelManager
import com.openide.jupyter.kernel.KernelRegistry
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.model.*
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.*

class JupyterNotebookEditor(
    val project: Project,
    private val file: VirtualFile
) : FileEditor, UserDataHolderBase() {

    private val propertyChangeSupport = PropertyChangeSupport(this)
    private val mainPanel = JPanel(java.awt.BorderLayout())
    private val notebookPanel = NotebookPanel(this)
    private val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2))
    private val statusLabel = JLabel("Kernel: disconnected")

    var notebook: Notebook? = null
        private set

    var kernelManager: KernelManager? = null
        private set

    init {
        toolbar.add(statusLabel)
        mainPanel.add(toolbar, java.awt.BorderLayout.NORTH)
        mainPanel.add(notebookPanel.component, java.awt.BorderLayout.CENTER)

        loadNotebook()

        notebookPanel.onCellSelected = { cellId ->
            // cell selection tracked in NotebookPanel
        }

        notebookPanel.onCellSourceChanged = { cellId, newSource ->
            notebook?.cells?.find { it.id == cellId }?.let { cell ->
                cell.source = newSource
                notebook?.isDirty = true
                propertyChangeSupport.firePropertyChange("modified", false, true)
            }
        }
    }

    private fun loadNotebook() {
        val content = String(file.contentsToByteArray(), Charsets.UTF_8)
        val result = NotebookSerializer.deserialize(content, file.path)
        result.onSuccess { nb ->
            notebook = nb
            notebookPanel.renderNotebook(nb)
        }
        result.onFailure {
            val errorPanel = JLabel("<html>Failed to open notebook: ${it.message}<br>The file may be malformed.</html>")
            mainPanel.removeAll()
            mainPanel.add(errorPanel, java.awt.BorderLayout.CENTER)
        }
    }

    fun startKernel(pythonPath: String) {
        val km = KernelManager(pythonPath, this)
        kernelManager = km
        KernelRegistry.getInstance(project).register(file.path, km)

        km.onStatusChanged = { status ->
            SwingUtilities.invokeLater {
                statusLabel.text = "Kernel: ${status.name.lowercase()}"
            }
        }

        km.start()
    }

    fun stopKernel() {
        kernelManager?.stop()
        kernelManager?.let { KernelRegistry.getInstance(project).unregister(file.path) }
        kernelManager = null
        statusLabel.text = "Kernel: disconnected"
    }

    fun restartKernel() {
        val pythonPath = kernelManager?.pythonPath ?: return
        stopKernel()
        startKernel(pythonPath)
    }

    fun getSelectedCell(): Cell? {
        val cellId = notebookPanel.selectedCellId ?: return null
        return notebook?.cells?.find { it.id == cellId }
    }

    fun getNotebookPanel(): NotebookPanel = notebookPanel

    fun saveNotebook() {
        val nb = notebook ?: return
        val json = NotebookSerializer.serialize(nb)
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            file.setBinaryContent(json.toByteArray(Charsets.UTF_8))
        }
        nb.isDirty = false
        propertyChangeSupport.firePropertyChange("modified", true, false)
    }

    override fun getComponent(): JComponent = mainPanel

    override fun getPreferredFocusedComponent(): JComponent = notebookPanel.component

    override fun getName(): String = "Jupyter Notebook"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = notebook?.isDirty ?: false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        stopKernel()
    }
}
