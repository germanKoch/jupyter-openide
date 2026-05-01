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

        notebookPanel.onRunCell = { cellId ->
            executeCell(cellId)
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

    fun executeCell(cellId: String) {
        val km = kernelManager ?: return
        val cell = notebook?.cells?.find { it.id == cellId } ?: return
        if (cell.cellType != CellType.CODE) return

        notebookPanel.clearCellOutputs(cell.id)
        notebookPanel.setCellExecuting(cell.id, true)
        cell.executionState = CellExecutionState.EXECUTING
        cell.outputs.clear()

        val msgId = km.sendExecuteRequest(cell.source)
        km.registerCallback(msgId) { msg ->
            val msgType = msg.get("msg_type")?.asString ?: return@registerCallback
            val content = msg.getAsJsonObject("content") ?: return@registerCallback

            SwingUtilities.invokeLater {
                when (msgType) {
                    "stream" -> {
                        val text = content.get("text")?.asString ?: ""
                        val output = CellOutput(OutputType.STREAM, text = text)
                        cell.outputs.add(output)
                        notebookPanel.appendCellOutput(cell.id, output)
                    }
                    "execute_result" -> {
                        val data = parseDataBundle(content.getAsJsonObject("data"))
                        val output = CellOutput(OutputType.EXECUTE_RESULT, data = data)
                        cell.outputs.add(output)
                        notebookPanel.appendCellOutput(cell.id, output)
                    }
                    "display_data" -> {
                        val data = parseDataBundle(content.getAsJsonObject("data"))
                        val output = CellOutput(OutputType.DISPLAY_DATA, data = data)
                        cell.outputs.add(output)
                        notebookPanel.appendCellOutput(cell.id, output)
                    }
                    "error" -> {
                        val output = CellOutput(
                            OutputType.ERROR,
                            ename = content.get("ename")?.asString,
                            evalue = content.get("evalue")?.asString,
                            traceback = content.getAsJsonArray("traceback")?.map { it.asString }
                        )
                        cell.outputs.add(output)
                        notebookPanel.appendCellOutput(cell.id, output)
                    }
                    "execute_reply" -> {
                        val execCount = content.get("execution_count")?.asInt
                        cell.executionCount = execCount
                        cell.executionState = CellExecutionState.IDLE
                        notebookPanel.setCellExecuting(cell.id, false)
                        notebookPanel.setExecutionCount(cell.id, execCount)
                        km.removeCallback(msgId)
                        notebook?.isDirty = true
                    }
                }
            }
        }
    }

    private fun parseDataBundle(data: com.google.gson.JsonObject?): Map<String, Any>? {
        if (data == null) return null
        val result = mutableMapOf<String, Any>()
        for ((key, value) in data.entrySet()) {
            result[key] = when {
                value.isJsonArray -> value.asJsonArray.joinToString("") { it.asString }
                value.isJsonPrimitive -> value.asString
                else -> value.toString()
            }
        }
        return result
    }

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
