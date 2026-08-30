package com.openide.jupyter.editor

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.openide.jupyter.model.Cell
import com.openide.jupyter.model.CellExecutionState
import com.openide.jupyter.model.CellOutput
import com.openide.jupyter.model.CellType
import com.openide.jupyter.model.Notebook
import com.openide.jupyter.model.OutputType
import com.openide.jupyter.kernel.KernelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookExecutionStateTest {

    @Test
    fun `pending executions are unique and drain exactly once`() {
        val pending = PendingCellExecutions()
        pending.enqueue("first")
        pending.enqueue("second")
        pending.enqueue("first")

        assertEquals(listOf("first", "second"), pending.drain())
        assertTrue(pending.drain().isEmpty())
    }

    @Test
    fun `begin append clear and count report persisted mutations immediately`() {
        val cell = codeCell(
            "current",
            mutableListOf(CellOutput(OutputType.STREAM, text = "old"))
        )
        var modifiedSignals = 0
        val state = NotebookExecutionState(notebook(cell), cell) { modifiedSignals++ }

        val begin = assertNotNull(state.tryBeginExecution())
        assertTrue(begin.persisted)
        assertEquals(setOf("current"), begin.rerenderCellIds)
        assertTrue(cell.outputs.isEmpty())
        assertEquals(CellExecutionState.EXECUTING, cell.executionState)
        assertEquals(1, modifiedSignals)

        val append = state.append(CellOutput(OutputType.STREAM, text = "new"))
        assertTrue(append.persisted)
        assertEquals("new", append.appendedOutput?.text)
        assertEquals(2, modifiedSignals)

        val count = state.setExecutionCount(12)
        assertTrue(count.persisted)
        assertEquals(12, cell.executionCount)
        assertEquals(3, modifiedSignals)

        val clear = state.clear(wait = false)
        assertTrue(clear.persisted)
        assertTrue(cell.outputs.isEmpty())
        assertEquals(4, modifiedSignals)
    }

    @Test
    fun `execution start marks cleared output modified before any reply or idle`() {
        val cell = codeCell(
            "current",
            mutableListOf(CellOutput(OutputType.STREAM, text = "persisted-before-run"))
        )
        var editorModified = false
        val state = NotebookExecutionState(notebook(cell), cell) {
            editorModified = true
        }

        assertNotNull(state.tryBeginExecution())

        assertTrue(editorModified)
        assertTrue(cell.outputs.isEmpty())
    }

    @Test
    fun `second request for executing cell is rejected without clearing active output`() {
        val cell = codeCell(
            "current",
            mutableListOf(CellOutput(OutputType.STREAM, text = "persisted-before-run"))
        )
        val currentNotebook = notebook(cell)
        val first = NotebookExecutionState(currentNotebook, cell)
        val second = NotebookExecutionState(currentNotebook, cell)

        assertNotNull(first.tryBeginExecution())
        first.append(CellOutput(OutputType.STREAM, text = "first-request"))

        assertNull(second.tryBeginExecution())
        assertEquals(CellExecutionState.EXECUTING, cell.executionState)
        assertEquals(listOf("first-request"), cell.outputs.map { it.text })
    }

    @Test
    fun `synchronous send rejection restores exact pre execution model and dirty state`() {
        val oldOutput = CellOutput(OutputType.STREAM, text = "persisted-before-run")
        val cell = codeCell("current", mutableListOf(oldOutput)).apply {
            executionCount = 41
            executionState = CellExecutionState.ERROR
        }
        val currentNotebook = notebook(cell).apply { isDirty = false }
        var modifiedSignals = 0
        val state = NotebookExecutionState(currentNotebook, cell) {
            currentNotebook.isDirty = true
            modifiedSignals++
        }

        assertNotNull(state.tryBeginExecution())
        val rollback = assertNotNull(state.rollbackExecutionStart())

        assertEquals(listOf(oldOutput), cell.outputs)
        assertEquals(41, cell.executionCount)
        assertEquals(CellExecutionState.ERROR, cell.executionState)
        assertFalse(currentNotebook.isDirty)
        assertTrue(rollback.modifiedStateChanged)
        assertFalse(rollback.outputMutation.persisted)
        assertEquals(setOf("current"), rollback.outputMutation.rerenderCellIds)
        assertEquals(1, modifiedSignals)
    }

    @Test
    fun `accepted request cannot be rolled back after later disconnect`() {
        val cell = codeCell(
            "current",
            mutableListOf(CellOutput(OutputType.STREAM, text = "old"))
        )
        val state = NotebookExecutionState(notebook(cell), cell)

        assertNotNull(state.tryBeginExecution())
        state.commitExecutionStart()

        assertNull(state.rollbackExecutionStart())
        assertTrue(cell.outputs.isEmpty())
        assertEquals(CellExecutionState.EXECUTING, cell.executionState)
    }

    @Test
    fun `execution completion waits for shell reply and never reports aborted as success`() {
        val idleFirst = ExecutionCompletionGate()
        assertNull(idleFirst.onIopubIdle())
        assertEquals(ExecutionCompletionOutcome.SUCCESS, idleFirst.onExecuteReply("ok"))

        val aborted = ExecutionCompletionGate()
        assertNull(aborted.onExecuteReply("aborted"))
        assertEquals(ExecutionCompletionOutcome.ABORTED, aborted.onIopubIdle())

        val failed = ExecutionCompletionGate()
        failed.onIopubError()
        assertNull(failed.onExecuteReply("error"))
        assertEquals(ExecutionCompletionOutcome.ERROR, failed.onIopubIdle())
    }

    @Test
    fun `stale kernel status announcement is rejected at swing delivery time`() {
        assertTrue(
            shouldApplyKernelStatusAnnouncement(
                editorDisposed = false,
                managerIsCurrent = true,
                actualStatus = KernelStatus.IDLE,
                announcedStatus = KernelStatus.IDLE
            )
        )
        assertFalse(
            shouldApplyKernelStatusAnnouncement(
                editorDisposed = false,
                managerIsCurrent = true,
                actualStatus = KernelStatus.DISCONNECTED,
                announcedStatus = KernelStatus.IDLE
            )
        )
    }

    @Test
    fun `clear wait defers removal until immediately before next output`() {
        val cell = codeCell(
            "current",
            mutableListOf(CellOutput(OutputType.STREAM, text = "old"))
        )
        val state = NotebookExecutionState(notebook(cell), cell)

        val deferred = state.clear(wait = true)
        assertFalse(deferred.persisted)
        assertEquals(listOf("old"), cell.outputs.map { it.text })

        val first = state.append(CellOutput(OutputType.STREAM, text = "replacement"))
        assertTrue(first.persisted)
        assertEquals(setOf("current"), first.rerenderCellIds)
        assertNull(first.appendedOutput)
        assertEquals(listOf("replacement"), cell.outputs.map { it.text })

        val second = state.append(CellOutput(OutputType.STREAM, text = "after"))
        assertEquals("after", second.appendedOutput?.text)
        assertEquals(listOf("replacement", "after"), cell.outputs.map { it.text })
    }

    @Test
    fun `update display replaces every matching runtime display id without appending`() {
        val first = codeCell(
            "first",
            mutableListOf(
                displayOutput("shared", "old-first"),
                displayOutput("shared", "old-first-again"),
                displayOutput("other", "untouched")
            )
        )
        val second = codeCell(
            "second",
            mutableListOf(
                CellOutput(
                    outputType = OutputType.EXECUTE_RESULT,
                    data = mapOf("text/plain" to JsonPrimitive("old-second")),
                    executionCount = 9,
                    transientData = transient("shared")
                )
            )
        )
        val notebook = notebook(first, second)
        val state = NotebookExecutionState(notebook, first)

        val mutation = state.updateDisplay(
            data = mapOf("text/plain" to JsonPrimitive("updated")),
            metadata = JsonObject().apply { addProperty("fresh", true) },
            transientData = transient("shared")
        )

        assertTrue(mutation.persisted)
        assertEquals(setOf("first", "second"), mutation.rerenderCellIds)
        assertEquals(3, first.outputs.size)
        assertEquals("updated", first.outputs[0].data?.get("text/plain").toStringValue())
        assertEquals("updated", first.outputs[1].data?.get("text/plain").toStringValue())
        assertEquals("untouched", first.outputs[2].data?.get("text/plain").toStringValue())
        assertEquals(OutputType.EXECUTE_RESULT, second.outputs.single().outputType)
        assertEquals(9, second.outputs.single().executionCount)
        assertEquals("updated", second.outputs.single().data?.get("text/plain").toStringValue())
        assertEquals("shared", second.outputs.single().transientData?.get("display_id")?.asString)
    }

    @Test
    fun `unmatched update display does not append an output`() {
        val cell = codeCell("current", mutableListOf(displayOutput("known", "old")))
        val state = NotebookExecutionState(notebook(cell), cell)

        val mutation = state.updateDisplay(
            data = mapOf("text/plain" to JsonPrimitive("new")),
            metadata = JsonObject(),
            transientData = transient("missing")
        )

        assertFalse(mutation.persisted)
        assertTrue(mutation.rerenderCellIds.isEmpty())
        assertEquals(1, cell.outputs.size)
        assertEquals("old", cell.outputs.single().data?.get("text/plain").toStringValue())
    }

    private fun displayOutput(displayId: String, text: String) = CellOutput(
        outputType = OutputType.DISPLAY_DATA,
        data = mapOf("text/plain" to JsonPrimitive(text)),
        transientData = transient(displayId)
    )

    private fun transient(displayId: String) = JsonObject().apply {
        addProperty("display_id", displayId)
    }

    private fun codeCell(id: String, outputs: MutableList<CellOutput>) = Cell(
        id = id,
        cellType = CellType.CODE,
        outputs = outputs
    )

    private fun notebook(vararg cells: Cell) = Notebook(
        filePath = "/tmp/runtime.ipynb",
        cells = cells.toMutableList()
    )

    private fun Any?.toStringValue(): String? = when (this) {
        is JsonPrimitive -> asString
        else -> this?.toString()
    }
}
