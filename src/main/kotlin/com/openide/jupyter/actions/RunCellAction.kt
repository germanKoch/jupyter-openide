package com.openide.jupyter.actions

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.model.*

class RunCellAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e) ?: return
        val km = editor.kernelManager ?: return
        val cell = editor.getSelectedCell() ?: return
        if (cell.cellType != CellType.CODE) return

        val panel = editor.getNotebookPanel()
        panel.clearCellOutputs(cell.id)
        panel.setCellExecuting(cell.id, true)
        cell.executionState = CellExecutionState.EXECUTING
        cell.outputs.clear()

        val msgId = km.sendExecuteRequest(cell.source)
        km.registerCallback(msgId) { msg ->
            val msgType = msg.get("msg_type")?.asString ?: return@registerCallback
            val content = msg.getAsJsonObject("content") ?: return@registerCallback

            javax.swing.SwingUtilities.invokeLater {
                when (msgType) {
                    "stream" -> {
                        val text = content.get("text")?.asString ?: ""
                        val output = CellOutput(OutputType.STREAM, text = text)
                        cell.outputs.add(output)
                        panel.appendCellOutput(cell.id, output)
                    }
                    "execute_result" -> {
                        val data = parseDataBundle(content.getAsJsonObject("data"))
                        val output = CellOutput(OutputType.EXECUTE_RESULT, data = data)
                        cell.outputs.add(output)
                        panel.appendCellOutput(cell.id, output)
                    }
                    "display_data" -> {
                        val data = parseDataBundle(content.getAsJsonObject("data"))
                        val output = CellOutput(OutputType.DISPLAY_DATA, data = data)
                        cell.outputs.add(output)
                        panel.appendCellOutput(cell.id, output)
                    }
                    "error" -> {
                        val output = CellOutput(
                            OutputType.ERROR,
                            ename = content.get("ename")?.asString,
                            evalue = content.get("evalue")?.asString,
                            traceback = content.getAsJsonArray("traceback")?.map { it.asString }
                        )
                        cell.outputs.add(output)
                        panel.appendCellOutput(cell.id, output)
                    }
                    "execute_reply" -> {
                        val execCount = content.get("execution_count")?.asInt
                        cell.executionCount = execCount
                        cell.executionState = CellExecutionState.IDLE
                        panel.setCellExecuting(cell.id, false)
                        panel.setExecutionCount(cell.id, execCount)
                        km.removeCallback(msgId)
                        editor.notebook?.isDirty = true
                    }
                }
            }
        }
    }

    private fun parseDataBundle(data: JsonObject?): Map<String, Any>? {
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

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        val hasKernel = editor?.kernelManager?.status?.let {
            it == KernelStatus.IDLE || it == KernelStatus.BUSY
        } ?: false
        val hasCell = editor?.getSelectedCell()?.cellType == CellType.CODE
        e.presentation.isEnabled = hasKernel && hasCell
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
