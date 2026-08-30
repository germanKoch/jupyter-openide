package com.openide.jupyter.editor

import com.google.gson.JsonObject
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.openide.jupyter.analysis.NotebookAnalyzer
import com.openide.jupyter.kernel.KernelManager
import com.openide.jupyter.kernel.KernelOwnership
import com.openide.jupyter.kernel.KernelRegistry
import com.openide.jupyter.kernel.KernelRequestHandle
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.kernel.KernelTarget
import com.openide.jupyter.model.*
import com.openide.jupyter.navigation.FileLocation
import com.openide.jupyter.navigation.NavigationCell
import com.openide.jupyter.navigation.NavigationFailure
import com.openide.jupyter.navigation.NavigationRequest
import com.openide.jupyter.navigation.NavigationPositions
import com.openide.jupyter.navigation.NavigationUnresolved
import com.openide.jupyter.navigation.NotebookLocation
import com.openide.jupyter.navigation.PythonNavigationResolver
import com.openide.jupyter.python.PythonSdkDetector
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

internal data class NavigationInterpreterSelection(
    val interpreter: String?,
    val unresolvedMessage: String? = null
)

internal fun shouldApplyKernelStatusAnnouncement(
    editorDisposed: Boolean,
    managerIsCurrent: Boolean,
    actualStatus: KernelStatus,
    announcedStatus: KernelStatus
): Boolean = !editorDisposed && managerIsCurrent && actualStatus == announcedStatus

/**
 * Selects the resolver interpreter without crossing an attached-kernel boundary.
 * A configured attachment is authoritative, including an explicit null choice.
 */
internal fun selectNavigationSourceInterpreter(
    kernelOwnership: KernelOwnership?,
    configuredSourceInterpreter: String?,
    cachedPython: String?,
    detectPython: () -> String?
): NavigationInterpreterSelection {
    if (kernelOwnership != null) {
        configuredSourceInterpreter?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return NavigationInterpreterSelection(it)
        }
        val message = if (kernelOwnership == KernelOwnership.ATTACHED) {
            "Source navigation is disabled for this attached kernel because no local source " +
                "interpreter was selected. Reconnect and choose a matching local Python. " +
                "Remote-only source is unavailable because source-root mapping is not configured."
        } else {
            "The running kernel has no Python interpreter configured for source navigation."
        }
        return NavigationInterpreterSelection(null, message)
    }

    cachedPython?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return NavigationInterpreterSelection(it)
    }
    return detectPython()?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { NavigationInterpreterSelection(it) }
        ?: NavigationInterpreterSelection(
            null,
            "No Python interpreter is available for source navigation."
        )
}

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

    @Volatile private var cachedPython: String? = null
    @Volatile private var startingKernelManager: KernelManager? = null
    @Volatile private var kernelStarting = false
    @Volatile private var pythonDetectionRunning = false
    @Volatile private var editorDisposed = false
    private val kernelLifecycleLock = Any()
    private val kernelGeneration = AtomicInteger(0)
    private val pendingExecutionCells = PendingCellExecutions()
    private val kernelExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "jupyter-kernel-lifecycle").apply { isDaemon = true }
    }

    private val navigationResolver = PythonNavigationResolver()
    private val navigationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jupyter-source-navigation").apply { isDaemon = true }
    }
    private val navigationGeneration = AtomicLong(0)

    private val analyzer: NotebookAnalyzer by lazy {
        NotebookAnalyzer(
            pythonPathProvider = {
                kernelManager?.pythonPath ?: cachedPython ?: run {
                    val p = PythonSdkDetector.detectPythonInterpreter(project, file.path)
                    cachedPython = p
                    p
                }
            },
            workingDirectory = file.parent?.let { java.io.File(it.path) }
        )
    }

    private val analysisScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jupyter-analysis-debounce").apply { isDaemon = true }
    }
    @Volatile private var analysisFuture: ScheduledFuture<*>? = null
    @Volatile private var pendingSnapshot: List<Pair<String, String>> = emptyList()
    private val analysisGeneration = AtomicInteger(0)

    init {
        val toolbarGroup = ActionManager.getInstance().getAction("jupyter.toolbar") as? ActionGroup
        if (toolbarGroup != null) {
            val actionToolbar = ActionManager.getInstance().createActionToolbar(
                ActionPlaces.EDITOR_TOOLBAR,
                toolbarGroup,
                true
            )
            actionToolbar.targetComponent = mainPanel
            toolbar.add(actionToolbar.component)
        }
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
            scheduleAnalysis()
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
                scheduleAnalysis()
            }
        }

        notebookPanel.onDeleteCell = { cellId ->
            notebook?.let { nb ->
                nb.cells.removeAll { it.id == cellId }
                notebookPanel.removeCellFromView(cellId)
                nb.isDirty = true
                propertyChangeSupport.firePropertyChange("modified", false, true)
                scheduleAnalysis()
            }
        }

        notebookPanel.onSaveNotebook = {
            saveNotebook()
        }

        notebookPanel.onIdeAction = { actionId ->
            runIdeAction(actionId)
        }

        notebookPanel.onDefinitionRequested = { request ->
            resolveDefinition(request)
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
        val result = try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            NotebookSerializer.deserialize(content, file.path)
        } catch (exception: Exception) {
            Result.failure(exception)
        }
        result.onSuccess { nb ->
            notebook = nb
            notebookPanel.renderNotebook(nb)
            scheduleAnalysis()
        }
        result.onFailure {
            val message = (it.message ?: it::class.java.simpleName)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val errorPanel = JLabel("<html>Failed to open notebook: $message<br>The file may be malformed.</html>")
            mainPanel.removeAll()
            mainPanel.add(errorPanel, java.awt.BorderLayout.CENTER)
        }
    }

    private fun scheduleAnalysis() {
        val nb = notebook ?: return
        // Snapshot on the (mutating) calling thread so the analyzer thread never
        // iterates notebook.cells concurrently with a structural change.
        pendingSnapshot = try {
            nb.cells.filter { it.cellType == CellType.CODE }.map { it.id to it.source }
        } catch (_: Exception) {
            return
        }
        val gen = analysisGeneration.incrementAndGet()
        analysisFuture?.cancel(false)
        analysisFuture = try {
            analysisScheduler.schedule({ runAnalysis(gen) }, 600, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private fun runAnalysis(gen: Int) {
        if (gen != analysisGeneration.get()) return
        analyzer.analyze(pendingSnapshot) { diags ->
            if (gen != analysisGeneration.get()) return@analyze
            SwingUtilities.invokeLater {
                // Re-check on the EDT: a keystroke may have arrived since the
                // analyzer thread passed its guard, making this result stale.
                if (gen == analysisGeneration.get()) notebookPanel.setDiagnostics(diags)
            }
        }
    }

    private fun resolveDefinition(request: DefinitionRequest) {
        val snapshot = notebook?.cells
            ?.filter { it.cellType == CellType.CODE }
            ?.map { NavigationCell(it.id, it.source) }
            .orEmpty()
        if (snapshot.none { it.id == request.cellId }) return
        val generation = navigationGeneration.incrementAndGet()
        val navigationSession = synchronized(kernelLifecycleLock) {
            (kernelManager ?: startingKernelManager)?.let {
                it.ownership to it.sourceInterpreter
            }
        }
        val sessionOwnership = navigationSession?.first
        val configuredSourceInterpreter = navigationSession?.second
        val knownCachedPython = cachedPython
        val workingDirectory = file.parent?.path?.let {
            try { Path.of(it) } catch (_: Exception) { null }
        }

        navigationExecutor.submit {
            val selection = selectNavigationSourceInterpreter(
                kernelOwnership = sessionOwnership,
                configuredSourceInterpreter = configuredSourceInterpreter,
                cachedPython = knownCachedPython,
                detectPython = {
                    PythonSdkDetector.detectPythonInterpreter(project, file.path)?.also {
                        cachedPython = it
                    }
                }
            )
            val result = if (selection.interpreter == null) {
                NavigationUnresolved(
                    NavigationFailure.INVALID_REQUEST,
                    selection.unresolvedMessage
                )
            } else {
                navigationResolver.resolve(
                    NavigationRequest(
                        cells = snapshot,
                        currentCellId = request.cellId,
                        cursorOffsetUtf16 = request.cursorOffsetUtf16,
                        pythonInterpreter = selection.interpreter,
                        workingDirectory = workingDirectory
                    )
                )
            }

            SwingUtilities.invokeLater {
                if (editorDisposed || generation != navigationGeneration.get()) return@invokeLater
                // Do not navigate using a result calculated from source that changed
                // while the helper process was running.
                val current = notebook?.cells
                    ?.filter { it.cellType == CellType.CODE }
                    ?.map { NavigationCell(it.id, it.source) }
                    .orEmpty()
                if (current != snapshot) return@invokeLater
                handleNavigationResult(result, sessionOwnership)
            }
        }
    }

    private fun handleNavigationResult(
        result: com.openide.jupyter.navigation.NavigationResult,
        kernelOwnership: KernelOwnership?
    ) {
        when (result) {
            is NotebookLocation -> notebookPanel.navigateToCellLocation(
                result.cellId,
                result.line,
                result.column,
                result.symbol
            )

            is FileLocation -> {
                val virtualFile = VirtualFileManager.getInstance()
                    .refreshAndFindFileByNioPath(result.path)
                if (virtualFile == null) {
                    showNotification("Source file no longer exists: ${result.path}", NotificationType.WARNING)
                    return
                }
                val utf16Column = try {
                    NavigationPositions.codePointColumnToUtf16(
                        Files.readString(result.path),
                        result.line,
                        result.column
                    ) ?: 0
                } catch (_: Exception) {
                    0
                }
                OpenFileDescriptor(project, virtualFile, result.line, utf16Column).navigate(true)
            }

            is NavigationUnresolved -> {
                val baseMessage = result.message ?: when (result.reason) {
                    NavigationFailure.SOURCE_UNAVAILABLE ->
                        "Source is unavailable for this built-in or compiled symbol."
                    else -> "No declaration found at the caret."
                }
                val message = if (
                    kernelOwnership == KernelOwnership.ATTACHED &&
                    result.reason == NavigationFailure.SOURCE_UNAVAILABLE
                ) {
                    "$baseMessage The attached kernel may be remote; only files visible through " +
                        "the selected local source interpreter can be opened, and source-root " +
                        "mapping is not configured."
                } else {
                    baseMessage
                }
                showNotification(message, NotificationType.INFORMATION)
            }
        }
    }

    private fun runIdeAction(actionId: String) {
        try {
            val action = ActionManager.getInstance().getAction(actionId) ?: return
            val dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, notebookPanel.component)
                .build()
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)
            action.actionPerformed(event)
        } catch (_: Exception) {
            // Action unavailable in this context; ignore.
        }
    }

    fun startKernel(pythonPath: String) {
        startKernel(
            KernelTarget.Launch(
                pythonPath,
                file.parent?.let { java.io.File(it.path) }
            )
        )
    }

    fun startKernel(target: KernelTarget) {
        startKernelAsync(target, null)
    }

    private fun startKernelAsync(target: KernelTarget, onReady: (() -> Unit)?) {
        var staleManager: KernelManager? = null
        val startGeneration = synchronized(kernelLifecycleLock) {
            if (editorDisposed) return
            val current = kernelManager
            if (current != null && current.status != KernelStatus.DISCONNECTED) {
                val pendingCellIds = pendingExecutionCells.drain()
                SwingUtilities.invokeLater {
                    pendingCellIds.forEach { doExecuteCell(it, current) }
                    onReady?.invoke()
                }
                return
            }
            if (kernelStarting) return
            staleManager = current
            kernelManager = null
            kernelStarting = true
            pythonDetectionRunning = false
            kernelGeneration.incrementAndGet()
        }
        staleManager?.let { KernelRegistry.getInstance(project).unregister(file.path) }

        SwingUtilities.invokeLater { statusLabel.text = "Kernel: starting..." }

        kernelExecutor.submit {
            try {
                staleManager?.stop()
            } catch (_: Exception) {
            }
            val km = KernelManager(target, this@JupyterNotebookEditor)
            val acceptedForStartup = synchronized(kernelLifecycleLock) {
                if (editorDisposed || kernelGeneration.get() != startGeneration || !kernelStarting) {
                    false
                } else {
                    startingKernelManager = km
                    true
                }
            }
            if (!acceptedForStartup) {
                km.stop()
                return@submit
            }

            km.onStatusChanged = { status ->
                val current = synchronized(kernelLifecycleLock) {
                    kernelGeneration.get() == startGeneration &&
                        (startingKernelManager === km || kernelManager === km)
                }
                if (current) {
                    SwingUtilities.invokeLater {
                        val stillCurrent = synchronized(kernelLifecycleLock) {
                            shouldApplyKernelStatusAnnouncement(
                                editorDisposed = editorDisposed,
                                managerIsCurrent = kernelGeneration.get() == startGeneration &&
                                    (startingKernelManager === km || kernelManager === km),
                                actualStatus = km.status,
                                announcedStatus = status
                            )
                        }
                        if (stillCurrent) {
                            statusLabel.text = "Kernel: ${status.name.lowercase()}"
                        }
                    }
                }
            }

            try {
                km.start()

                SwingUtilities.invokeLater {
                    val pendingCellIds = synchronized(kernelLifecycleLock) {
                        if (
                            !editorDisposed &&
                            kernelGeneration.get() == startGeneration &&
                            startingKernelManager === km
                        ) {
                            startingKernelManager = null
                            kernelManager = km
                            kernelStarting = false
                            pendingExecutionCells.drain()
                        } else {
                            null
                        }
                    }
                    if (pendingCellIds != null) {
                        KernelRegistry.getInstance(project).register(file.path, km)
                        pendingCellIds.forEach { doExecuteCell(it, km) }
                        onReady?.invoke()
                    } else {
                        kernelExecutor.submit { km.stop() }
                    }
                }
            } catch (e: Exception) {
                val current = synchronized(kernelLifecycleLock) {
                    if (kernelGeneration.get() == startGeneration && startingKernelManager === km) {
                        startingKernelManager = null
                        kernelStarting = false
                        pythonDetectionRunning = false
                        pendingExecutionCells.clear()
                        true
                    } else {
                        false
                    }
                }
                if (current) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Kernel: disconnected"
                        showNotification(
                            "Failed to connect to kernel: ${e.message ?: e::class.java.simpleName}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        }
    }

    fun stopKernel() {
        stopKernel(null)
    }

    fun restartKernel() {
        val target = synchronized(kernelLifecycleLock) {
            kernelManager?.target ?: startingKernelManager?.target
        } ?: return
        stopKernel { startKernel(target) }
    }

    private fun stopKernel(onStopped: (() -> Unit)?) {
        val managers = synchronized(kernelLifecycleLock) {
            kernelGeneration.incrementAndGet()
            kernelStarting = false
            pythonDetectionRunning = false
            pendingExecutionCells.clear()
            listOfNotNull(kernelManager, startingKernelManager).distinct().also {
                kernelManager = null
                startingKernelManager = null
            }
        }
        KernelRegistry.getInstance(project).unregister(file.path)
        statusLabel.text = "Kernel: disconnected"
        kernelExecutor.submit {
            managers.forEach { manager ->
                try {
                    manager.stop()
                } catch (_: Exception) {
                }
            }
            onStopped?.let { callback -> SwingUtilities.invokeLater { callback() } }
        }
    }

    fun getSelectedCell(): Cell? {
        val cellId = notebookPanel.selectedCellId ?: return null
        return notebook?.cells?.find { it.id == cellId }
    }

    fun getNotebookPanel(): NotebookPanel = notebookPanel

    fun canStartKernel(): Boolean = synchronized(kernelLifecycleLock) {
        !kernelStarting && !pythonDetectionRunning &&
            (kernelManager == null || kernelManager?.status == KernelStatus.DISCONNECTED)
    }

    /** Keeps toolbar actions and JCEF-originated structural edits on the same save/analysis path. */
    fun notifyNotebookStructureChanged() {
        notebook?.isDirty = true
        propertyChangeSupport.firePropertyChange("modified", false, true)
        scheduleAnalysis()
    }

    fun executeCell(cellId: String) {
        val km = kernelManager
        if (km == null || km.status == KernelStatus.DISCONNECTED) {
            autoStartKernelAndExecute(cellId)
            return
        }
        doExecuteCell(cellId, km)
    }

    private fun autoStartKernelAndExecute(cellId: String) {
        val detectionGeneration = synchronized(kernelLifecycleLock) {
            pendingExecutionCells.enqueue(cellId)
            if (pythonDetectionRunning || kernelStarting) {
                null
            } else {
                pythonDetectionRunning = true
                kernelGeneration.get()
            }
        }
        if (detectionGeneration == null) return
        statusLabel.text = "Kernel: detecting Python..."

        kernelExecutor.submit {
            val pythonPath = PythonSdkDetector.detectPythonInterpreter(project, file.path)
            if (pythonPath == null) {
                if (finishPythonDetectionFailure(detectionGeneration)) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Kernel: no Python"
                        showNotification("No Python interpreter found. Configure a Python SDK in Project Settings.", NotificationType.WARNING)
                    }
                }
                return@submit
            }

            if (!PythonSdkDetector.checkJupyterInstalled(pythonPath)) {
                if (finishPythonDetectionFailure(detectionGeneration)) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Kernel: ipykernel missing"
                        showNotification(
                            "ipykernel is not installed. Run: $pythonPath -m pip install ipykernel",
                            NotificationType.WARNING
                        )
                    }
                }
                return@submit
            }

            val stillCurrent = synchronized(kernelLifecycleLock) {
                if (
                    kernelGeneration.get() == detectionGeneration &&
                    pythonDetectionRunning &&
                    !editorDisposed
                ) {
                    pythonDetectionRunning = false
                    true
                } else {
                    false
                }
            }
            if (!stillCurrent) return@submit

            cachedPython = pythonPath
            startKernelAsync(
                KernelTarget.Launch(
                    pythonPath,
                    file.parent?.let { java.io.File(it.path) }
                ),
                null
            )
        }
    }

    private fun finishPythonDetectionFailure(expectedGeneration: Int): Boolean {
        return synchronized(kernelLifecycleLock) {
            if (
                kernelGeneration.get() != expectedGeneration ||
                !pythonDetectionRunning ||
                editorDisposed
            ) {
                false
            } else {
                pythonDetectionRunning = false
                pendingExecutionCells.clear()
                true
            }
        }
    }

    /** Starts one request on the already connected kernel. Must be called on the EDT. */
    fun executeCellRequest(cellId: String): CompletableFuture<Unit>? {
        val km = kernelManager ?: return null
        if (km.status != KernelStatus.IDLE && km.status != KernelStatus.BUSY) return null
        return doExecuteCell(cellId, km)?.completion
    }

    fun executeAllCells() {
        val cellIds = notebook?.cells
            ?.filter { it.cellType == CellType.CODE }
            ?.map { it.id }
            .orEmpty()
        if (cellIds.isEmpty()) return

        kernelExecutor.submit {
            for (cellId in cellIds) {
                try {
                    val started = CompletableFuture<CompletableFuture<Unit>?>()
                    SwingUtilities.invokeLater {
                        started.complete(executeCellRequest(cellId))
                    }
                    val completion = started.get(5, TimeUnit.SECONDS) ?: break
                    completion.get(5, TimeUnit.MINUTES)
                } catch (exception: Exception) {
                    SwingUtilities.invokeLater {
                        showNotification(
                            "Run All stopped: ${exception.cause?.message ?: exception.message}",
                            NotificationType.ERROR
                        )
                    }
                    break
                }
            }
        }
    }

    private fun doExecuteCell(cellId: String, km: KernelManager): KernelRequestHandle? {
        val currentNotebook = notebook ?: return null
        val cell = currentNotebook.cells.find { it.id == cellId } ?: return null
        if (cell.cellType != CellType.CODE) return null

        val outputState = NotebookExecutionState(currentNotebook, cell, ::markNotebookModified)
        val beginMutation = outputState.tryBeginExecution() ?: return null
        applyOutputMutation(currentNotebook, cell, beginMutation)
        notebookPanel.setCellExecuting(cell.id, true)

        val completionGate = ExecutionCompletionGate()
        fun applyCompletion(outcome: ExecutionCompletionOutcome?) {
            if (outcome == null) return
            val success = outcome == ExecutionCompletionOutcome.SUCCESS
            cell.executionState = if (success) CellExecutionState.IDLE else CellExecutionState.ERROR
            notebookPanel.setCellExecuting(cell.id, false)
            notebookPanel.notifyCellExecuted(cell.id, success)
        }
        val handle = try {
            km.execute(cell.source) { msg ->
                val msgType = msg.get("msg_type")?.asString ?: return@execute
                val content = msg.getAsJsonObject("content") ?: return@execute

                SwingUtilities.invokeLater {
                    when (msgType) {
                        "stream" -> {
                            val output = CellOutput(
                                outputType = OutputType.STREAM,
                                text = content.get("text")?.asString.orEmpty(),
                                name = content.get("name")?.asString ?: "stdout"
                            )
                            applyOutputMutation(currentNotebook, cell, outputState.append(output))
                        }

                        "execute_result" -> {
                            val output = CellOutput(
                                outputType = OutputType.EXECUTE_RESULT,
                                data = parseDataBundle(content.getAsJsonObject("data")),
                                executionCount = content.get("execution_count")?.let {
                                    if (it.isJsonNull) null else it.asInt
                                },
                                metadata = content.getAsJsonObject("metadata")?.deepCopy() ?: JsonObject(),
                                transientData = content.getAsJsonObject("transient")?.deepCopy()
                            )
                            applyOutputMutation(currentNotebook, cell, outputState.append(output))
                        }

                        "display_data" -> {
                            val output = CellOutput(
                                outputType = OutputType.DISPLAY_DATA,
                                data = parseDataBundle(content.getAsJsonObject("data")),
                                metadata = content.getAsJsonObject("metadata")?.deepCopy() ?: JsonObject(),
                                transientData = content.getAsJsonObject("transient")?.deepCopy()
                            )
                            applyOutputMutation(currentNotebook, cell, outputState.append(output))
                        }

                        "update_display_data" -> {
                            applyOutputMutation(
                                currentNotebook,
                                cell,
                                outputState.updateDisplay(
                                    data = parseDataBundle(content.getAsJsonObject("data")),
                                    metadata = content.getAsJsonObject("metadata")?.deepCopy()
                                        ?: JsonObject(),
                                    transientData = content.getAsJsonObject("transient")?.deepCopy()
                                )
                            )
                        }

                        "clear_output" -> {
                            applyOutputMutation(
                                currentNotebook,
                                cell,
                                outputState.clear(content.get("wait")?.asBoolean == true)
                            )
                        }

                        "error" -> {
                            completionGate.onIopubError()
                            val output = CellOutput(
                                outputType = OutputType.ERROR,
                                ename = content.get("ename")?.asString,
                                evalue = content.get("evalue")?.asString,
                                traceback = content.getAsJsonArray("traceback")?.map { it.asString }
                            )
                            applyOutputMutation(currentNotebook, cell, outputState.append(output))
                        }

                        "execute_input" -> {
                            val executionCount = content.get("execution_count")?.asInt
                            applyOutputMutation(
                                currentNotebook,
                                cell,
                                outputState.setExecutionCount(executionCount)
                            )
                            notebookPanel.setExecutionCount(cell.id, executionCount)
                        }

                        "execute_reply" -> {
                            applyCompletion(
                                completionGate.onExecuteReply(
                                    content.get("status")?.let {
                                        if (it.isJsonNull) null else it.asString
                                    }
                                )
                            )
                        }

                        "status" -> {
                            if (content.get("execution_state")?.asString == "idle") {
                                applyCompletion(completionGate.onIopubIdle())
                            }
                        }
                    }
                }
            }.also { outputState.commitExecutionStart() }
        } catch (exception: Exception) {
            outputState.rollbackExecutionStart()?.let { rollback ->
                applyOutputMutation(currentNotebook, cell, rollback.outputMutation)
                notebookPanel.setExecutionCount(cell.id, cell.executionCount)
                if (rollback.modifiedStateChanged) {
                    propertyChangeSupport.firePropertyChange(
                        "modified",
                        !currentNotebook.isDirty,
                        currentNotebook.isDirty
                    )
                }
            }
            notebookPanel.setCellExecuting(cell.id, false)
            notebookPanel.notifyCellExecuted(cell.id, false)
            showNotification(
                "Failed to execute cell: ${exception.message ?: exception::class.java.simpleName}",
                NotificationType.ERROR
            )
            return null
        }

        handle.completion.whenComplete { _, failure ->
            if (failure != null) {
                SwingUtilities.invokeLater {
                    if (cell.executionState == CellExecutionState.EXECUTING) {
                        cell.executionState = CellExecutionState.ERROR
                        notebookPanel.setCellExecuting(cell.id, false)
                        notebookPanel.notifyCellExecuted(cell.id, false)
                    }
                }
            }
        }
        return handle
    }

    private fun applyOutputMutation(
        currentNotebook: Notebook,
        executingCell: Cell,
        mutation: OutputMutation
    ) {
        mutation.rerenderCellIds.forEach { cellId ->
            val changedCell = currentNotebook.cells.find { it.id == cellId } ?: return@forEach
            notebookPanel.clearCellOutputs(cellId)
            changedCell.outputs.forEach { notebookPanel.appendCellOutput(cellId, it) }
        }
        mutation.appendedOutput?.let { notebookPanel.appendCellOutput(executingCell.id, it) }
    }

    private fun markNotebookModified() {
        val currentNotebook = notebook ?: return
        val wasModified = currentNotebook.isDirty
        currentNotebook.isDirty = true
        if (!wasModified) {
            propertyChangeSupport.firePropertyChange("modified", false, true)
        }
    }

    private fun parseDataBundle(data: JsonObject?): Map<String, Any>? {
        if (data == null) return null
        val result = linkedMapOf<String, Any>()
        for ((key, value) in data.entrySet()) {
            result[key] = value.deepCopy()
        }
        return result
    }

    fun saveNotebook() {
        val nb = notebook ?: return
        try {
            val json = NotebookSerializer.serialize(nb)
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                file.setBinaryContent(json.toByteArray(Charsets.UTF_8))
            }
            nb.isDirty = false
            propertyChangeSupport.firePropertyChange("modified", true, false)
        } catch (exception: Exception) {
            showNotification(
                "Failed to save notebook: ${exception.message ?: exception::class.java.simpleName}",
                NotificationType.ERROR
            )
        }
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
        val managers = synchronized(kernelLifecycleLock) {
            editorDisposed = true
            kernelGeneration.incrementAndGet()
            navigationGeneration.incrementAndGet()
            kernelStarting = false
            pythonDetectionRunning = false
            pendingExecutionCells.clear()
            listOfNotNull(kernelManager, startingKernelManager).distinct().also {
                kernelManager = null
                startingKernelManager = null
            }
        }
        KernelRegistry.getInstance(project).unregister(file.path)
        managers.forEach { manager ->
            try { manager.stop() } catch (_: Exception) {}
        }
        analysisFuture?.cancel(false)
        analysisScheduler.shutdownNow()
        analyzer.dispose()
        navigationResolver.close()
        navigationExecutor.shutdownNow()
        kernelExecutor.shutdownNow()
    }
}
