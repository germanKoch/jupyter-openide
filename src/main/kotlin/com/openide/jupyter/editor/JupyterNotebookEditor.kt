package com.openide.jupyter.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.openide.jupyter.kernel.KernelManager
import com.openide.jupyter.kernel.KernelRegistry
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.model.*
import com.openide.jupyter.python.PythonSdkDetector
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

        notebookPanel.onAddCell = { afterCellId, cellType ->
            notebook?.let { nb ->
                val type = if (cellType == "markdown") CellType.MARKDOWN else CellType.CODE
                val newCell = Cell(cellType = type)
                if (afterCellId.isEmpty()) {
                    nb.cells.add(0, newCell)
                } else {
                    val idx = nb.cells.indexOfFirst { it.id == afterCellId }
                    if (idx >= 0) {
                        nb.cells.add(idx + 1, newCell)
                    } else {
                        nb.cells.add(newCell)
                    }
                }
                notebookPanel.insertCellAfter(afterCellId, newCell)
                nb.isDirty = true
                propertyChangeSupport.firePropertyChange("modified", false, true)
            }
        }

        notebookPanel.onDeleteCell = { cellId ->
            notebook?.let { nb ->
                nb.cells.removeAll { it.id == cellId }
                notebookPanel.removeCellFromView(cellId)
                nb.isDirty = true
                propertyChangeSupport.firePropertyChange("modified", false, true)
            }
        }

        notebookPanel.onSaveNotebook = {
            saveNotebook()
        }

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeAllDocumentsSaving() {
                if (notebook?.isDirty == true) {
                    saveNotebook()
                }
            }
        })
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
        startKernelAsync(pythonPath, null)
    }

    private fun startKernelAsync(pythonPath: String, onReady: (() -> Unit)?) {
        if (kernelManager != null && kernelManager?.status != KernelStatus.DISCONNECTED) {
            onReady?.invoke()
            return
        }

        SwingUtilities.invokeLater { statusLabel.text = "Kernel: starting..." }

        Thread {
            try {
                val notebookDir = file.parent?.let { java.io.File(it.path) }
                val km = KernelManager(pythonPath, this@JupyterNotebookEditor, notebookDir)
                km.onStatusChanged = { status ->
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Kernel: ${status.name.lowercase()}"
                    }
                }
                km.start()

                SwingUtilities.invokeLater {
                    kernelManager = km
                    KernelRegistry.getInstance(project).register(file.path, km)
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Kernel: disconnected"
                    showNotification("Failed to start kernel: ${e.message}", NotificationType.ERROR)
                }
            }
        }.start()
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
        val km = kernelManager
        if (km == null || km.status == KernelStatus.DISCONNECTED) {
            autoStartKernelAndExecute(cellId)
            return
        }
        doExecuteCell(cellId, km)
    }

    private fun autoStartKernelAndExecute(cellId: String) {
        statusLabel.text = "Kernel: detecting Python..."

        Thread {
            val pythonPath = PythonSdkDetector.detectPythonInterpreter(project, file.path)
            if (pythonPath == null) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Kernel: no Python"
                    showNotification("No Python interpreter found. Configure a Python SDK in Project Settings.", NotificationType.WARNING)
                }
                return@Thread
            }

            if (!PythonSdkDetector.checkJupyterInstalled(pythonPath)) {
                SwingUtilities.invokeLater { statusLabel.text = "Kernel: installing ipykernel..." }
                val installed = PythonSdkDetector.installJupyter(pythonPath)
                if (!installed) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Kernel: install failed"
                        showNotification("Failed to install ipykernel. Run manually: $pythonPath -m pip install ipykernel", NotificationType.ERROR)
                    }
                    return@Thread
                }
            }

            startKernelAsync(pythonPath) {
                doExecuteCell(cellId, kernelManager!!)
            }
        }.start()
    }

    private fun doExecuteCell(cellId: String, km: KernelManager) {
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
                        val textPlain = data?.get("text/plain")?.toString() ?: ""
                        val alreadyShown = cell.outputs.any {
                            it.outputType == OutputType.STREAM && it.text?.trim() == textPlain.trim()
                        }
                        if (!alreadyShown) {
                            val output = CellOutput(OutputType.EXECUTE_RESULT, data = data)
                            cell.outputs.add(output)
                            notebookPanel.appendCellOutput(cell.id, output)
                        }
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
                    "execute_input" -> {
                        val execCount = content.get("execution_count")?.asInt
                        cell.executionCount = execCount
                        notebookPanel.setExecutionCount(cell.id, execCount)
                    }
                    "status" -> {
                        val state = content.get("execution_state")?.asString
                        if (state == "idle") {
                            cell.executionState = CellExecutionState.IDLE
                            notebookPanel.setCellExecuting(cell.id, false)
                            km.removeCallback(msgId)
                            notebook?.isDirty = true
                        }
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

    private fun showNotification(content: String, type: NotificationType) {
        Notification("Jupyter", "Jupyter Notebook", content, type).notify(project)
    }

    override fun dispose() {
        stopKernel()
    }
}
