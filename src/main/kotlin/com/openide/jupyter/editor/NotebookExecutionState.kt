package com.openide.jupyter.editor

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.openide.jupyter.model.Cell
import com.openide.jupyter.model.CellExecutionState
import com.openide.jupyter.model.CellOutput
import com.openide.jupyter.model.Notebook

/** A deterministic, JCEF-independent queue used across asynchronous kernel startup. */
internal class PendingCellExecutions {
    private val cellIds = linkedSetOf<String>()

    @Synchronized
    fun enqueue(cellId: String) {
        cellIds += cellId
    }

    @Synchronized
    fun drain(): List<String> = cellIds.toList().also { cellIds.clear() }

    @Synchronized
    fun clear() {
        cellIds.clear()
    }
}

internal data class OutputMutation(
    val persisted: Boolean,
    val rerenderCellIds: Set<String> = emptySet(),
    val appendedOutput: CellOutput? = null
)

internal data class ExecutionStartRollback(
    val outputMutation: OutputMutation,
    val modifiedStateChanged: Boolean
)

internal enum class ExecutionCompletionOutcome {
    SUCCESS,
    ERROR,
    ABORTED
}

/**
 * Joins execution terminal signals from the shell and IOPub channels. Their
 * relative arrival order is unspecified, so IOPub idle alone must not report a
 * request as successful before an `execute_reply: aborted` can be observed.
 */
internal class ExecutionCompletionGate {
    private var executeReplyReceived = false
    private var executeReplyStatus: String? = null
    private var iopubIdleReceived = false
    private var iopubErrorReceived = false
    private var terminal = false

    fun onExecuteReply(status: String?): ExecutionCompletionOutcome? {
        executeReplyReceived = true
        executeReplyStatus = status
        return outcomeIfTerminal()
    }

    fun onIopubError() {
        iopubErrorReceived = true
    }

    fun onIopubIdle(): ExecutionCompletionOutcome? {
        iopubIdleReceived = true
        return outcomeIfTerminal()
    }

    private fun outcomeIfTerminal(): ExecutionCompletionOutcome? {
        if (terminal || !executeReplyReceived || !iopubIdleReceived) return null
        terminal = true
        return when {
            executeReplyStatus == "aborted" -> ExecutionCompletionOutcome.ABORTED
            executeReplyStatus == "error" || iopubErrorReceived -> ExecutionCompletionOutcome.ERROR
            else -> ExecutionCompletionOutcome.SUCCESS
        }
    }
}

/**
 * Applies Jupyter output semantics to the runtime notebook model. UI rendering
 * and FileEditor dirty-state notification stay with JupyterNotebookEditor.
 */
internal class NotebookExecutionState(
    private val notebook: Notebook,
    private val cell: Cell,
    private val onPersistedMutation: () -> Unit = {}
) {
    private var clearBeforeNextOutput = false
    private var startSnapshot: ExecutionStartSnapshot? = null

    /**
     * Atomically claims this cell for one in-flight request on the EDT.
     *
     * KernelManager accepts requests while the kernel is busy, so without this
     * guard two requests for the same cell would share and interleave one output
     * list. A rejected start must not clear the output of the active request.
     */
    fun tryBeginExecution(): OutputMutation? {
        if (cell.executionState == CellExecutionState.EXECUTING) return null
        startSnapshot = ExecutionStartSnapshot(
            outputs = cell.outputs.toList(),
            executionCount = cell.executionCount,
            executionState = cell.executionState,
            notebookWasDirty = notebook.isDirty
        )
        cell.executionState = CellExecutionState.EXECUTING
        clearBeforeNextOutput = false
        cell.outputs.clear()
        return mutation(
            persisted = true,
            rerenderCellIds = setOf(cell.id)
        )
    }

    /** The request was accepted by the transport, so old output is no longer restorable. */
    fun commitExecutionStart() {
        startSnapshot = null
    }

    /**
     * Restores the exact pre-run model only when sending failed synchronously.
     * Once [commitExecutionStart] has been called, a later disconnect may have
     * happened after the kernel accepted the request and must not roll back.
     */
    fun rollbackExecutionStart(): ExecutionStartRollback? {
        val snapshot = startSnapshot ?: return null
        startSnapshot = null
        clearBeforeNextOutput = false
        cell.outputs.clear()
        cell.outputs.addAll(snapshot.outputs)
        cell.executionCount = snapshot.executionCount
        cell.executionState = snapshot.executionState
        val dirtyBeforeRollback = notebook.isDirty
        notebook.isDirty = snapshot.notebookWasDirty
        return ExecutionStartRollback(
            outputMutation = OutputMutation(
                persisted = false,
                rerenderCellIds = setOf(cell.id)
            ),
            modifiedStateChanged = dirtyBeforeRollback != notebook.isDirty
        )
    }

    fun append(output: CellOutput): OutputMutation {
        val deferredClearApplied = consumeDeferredClear()
        cell.outputs += output
        return if (deferredClearApplied) {
            mutation(
                persisted = true,
                rerenderCellIds = setOf(cell.id)
            )
        } else {
            mutation(
                persisted = true,
                appendedOutput = output
            )
        }
    }

    fun clear(wait: Boolean): OutputMutation {
        if (wait) {
            clearBeforeNextOutput = true
            return mutation(persisted = false)
        }
        clearBeforeNextOutput = false
        cell.outputs.clear()
        return mutation(
            persisted = true,
            rerenderCellIds = setOf(cell.id)
        )
    }

    fun updateDisplay(
        data: Map<String, Any>?,
        metadata: JsonObject,
        transientData: JsonObject?
    ): OutputMutation {
        val rerender = linkedSetOf<String>()
        var persisted = false
        if (consumeDeferredClear()) {
            persisted = true
            rerender += cell.id
        }

        val displayId = displayId(transientData)
            ?: return mutation(persisted, rerender)

        notebook.cells.forEach { candidateCell ->
            var changed = false
            candidateCell.outputs.indices.forEach { index ->
                val existing = candidateCell.outputs[index]
                if (displayId(existing.transientData) == displayId) {
                    candidateCell.outputs[index] = existing.copy(
                        data = copyDataBundle(data),
                        metadata = metadata.deepCopy(),
                        transientData = transientData?.deepCopy()
                    )
                    changed = true
                }
            }
            if (changed) {
                persisted = true
                rerender += candidateCell.id
            }
        }
        return mutation(persisted, rerender)
    }

    fun setExecutionCount(executionCount: Int?): OutputMutation {
        cell.executionCount = executionCount
        return mutation(persisted = true)
    }

    private fun mutation(
        persisted: Boolean,
        rerenderCellIds: Set<String> = emptySet(),
        appendedOutput: CellOutput? = null
    ): OutputMutation {
        if (persisted) onPersistedMutation()
        return OutputMutation(persisted, rerenderCellIds, appendedOutput)
    }

    private fun consumeDeferredClear(): Boolean {
        if (!clearBeforeNextOutput) return false
        clearBeforeNextOutput = false
        cell.outputs.clear()
        return true
    }

    private fun displayId(transientData: JsonObject?): String? {
        val value = transientData?.get("display_id") ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            value.asString
        } else {
            null
        }
    }

    private fun copyDataBundle(data: Map<String, Any>?): Map<String, Any>? {
        if (data == null) return null
        return linkedMapOf<String, Any>().also { copy ->
            data.forEach { (mime, value) ->
                copy[mime] = if (value is JsonElement) value.deepCopy() else value
            }
        }
    }

    private data class ExecutionStartSnapshot(
        val outputs: List<CellOutput>,
        val executionCount: Int?,
        val executionState: CellExecutionState,
        val notebookWasDirty: Boolean
    )
}
