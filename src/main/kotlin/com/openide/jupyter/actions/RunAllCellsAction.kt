package com.openide.jupyter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.openide.jupyter.kernel.KernelStatus
import com.openide.jupyter.model.CellType

class RunAllCellsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e) ?: return
        val km = editor.kernelManager ?: return
        val notebook = editor.notebook ?: return
        val panel = editor.getNotebookPanel()

        val codeCells = notebook.cells.filter { it.cellType == CellType.CODE }
        if (codeCells.isEmpty()) return

        Thread {
            for (cell in codeCells) {
                javax.swing.SwingUtilities.invokeLater {
                    panel.clearCellOutputs(cell.id)
                    panel.setCellExecuting(cell.id, true)
                    cell.outputs.clear()
                }

                val msgId = km.sendExecuteRequest(cell.source)
                val done = java.util.concurrent.CountDownLatch(1)

                km.registerCallback(msgId) { msg ->
                    val msgType = msg.get("msg_type")?.asString ?: return@registerCallback
                    val content = msg.getAsJsonObject("content") ?: return@registerCallback

                    javax.swing.SwingUtilities.invokeLater {
                        when (msgType) {
                            "stream" -> {
                                val text = content.get("text")?.asString ?: ""
                                val output = com.openide.jupyter.model.CellOutput(
                                    com.openide.jupyter.model.OutputType.STREAM, text = text
                                )
                                cell.outputs.add(output)
                                panel.appendCellOutput(cell.id, output)
                            }
                            "execute_result", "display_data" -> {
                                val data = content.getAsJsonObject("data")?.entrySet()?.associate {
                                    it.key to (if (it.value.isJsonArray) it.value.asJsonArray.joinToString("") { e -> e.asString }
                                    else it.value.asString) as Any
                                }
                                val outputType = if (msgType == "execute_result")
                                    com.openide.jupyter.model.OutputType.EXECUTE_RESULT
                                else com.openide.jupyter.model.OutputType.DISPLAY_DATA
                                val output = com.openide.jupyter.model.CellOutput(outputType, data = data)
                                cell.outputs.add(output)
                                panel.appendCellOutput(cell.id, output)
                            }
                            "error" -> {
                                val output = com.openide.jupyter.model.CellOutput(
                                    com.openide.jupyter.model.OutputType.ERROR,
                                    ename = content.get("ename")?.asString,
                                    evalue = content.get("evalue")?.asString,
                                    traceback = content.getAsJsonArray("traceback")?.map { it.asString }
                                )
                                cell.outputs.add(output)
                                panel.appendCellOutput(cell.id, output)
                            }
                            "execute_reply" -> {
                                cell.executionCount = content.get("execution_count")?.asInt
                                panel.setCellExecuting(cell.id, false)
                                panel.setExecutionCount(cell.id, cell.executionCount)
                                km.removeCallback(msgId)
                                done.countDown()
                            }
                        }
                    }
                }
                done.await()
            }
            editor.notebook?.isDirty = true
        }.start()
    }

    override fun update(e: AnActionEvent) {
        val editor = getCurrentNotebookEditor(e)
        e.presentation.isEnabled = editor?.kernelManager?.status?.let {
            it == KernelStatus.IDLE
        } ?: false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
