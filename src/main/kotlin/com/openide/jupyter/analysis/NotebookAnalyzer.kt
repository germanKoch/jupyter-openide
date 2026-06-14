package com.openide.jupyter.analysis

import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A single diagnostic mapped back to a notebook cell (0-based line/col). */
data class Diagnostic(
    val cellId: String,
    val line: Int,
    val col: Int,
    val endCol: Int,
    val severity: String,
    val message: String,
    val code: String
)

private data class AnalyzerResult(val diagnostics: List<Diagnostic> = emptyList())

/**
 * Runs the bundled `analyzer.py` against the current notebook cells in a
 * short-lived subprocess on the project's Python interpreter. All work happens
 * on a dedicated daemon thread; results are delivered via the callback.
 */
class NotebookAnalyzer(
    private val pythonPathProvider: () -> String?,
    private val workingDirectory: File?
) {
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jupyter-analyzer").apply { isDaemon = true }
    }

    @Volatile private var scriptFile: File? = null

    private fun ensureScript(): File? {
        scriptFile?.let { if (it.exists()) return it }
        return try {
            val text = javaClass.classLoader
                .getResourceAsStream("notebook/analyzer.py")
                ?.bufferedReader()?.readText() ?: return null
            val tmp = File.createTempFile("jupyter_analyzer_", ".py")
            tmp.deleteOnExit()
            tmp.writeText(text)
            scriptFile = tmp
            tmp
        } catch (_: Exception) {
            null
        }
    }

    /** Analyze [cells] (id -> source). [onResult] runs on the analyzer thread. */
    fun analyze(cells: List<Pair<String, String>>, onResult: (List<Diagnostic>) -> Unit) {
        val python = pythonPathProvider() ?: return
        val script = ensureScript() ?: return
        executor.submit {
            try {
                val input = gson.toJson(
                    mapOf("cells" to cells.map { mapOf("id" to it.first, "source" to it.second) })
                )
                val pb = ProcessBuilder(python, script.absolutePath)
                workingDirectory?.let { pb.directory(it) }
                val proc = pb.start()
                // Drain stderr on a separate thread so a chatty interpreter can't
                // fill the stderr pipe and deadlock the stdout read.
                val errThread = Thread {
                    try { proc.errorStream.use { it.readBytes() } } catch (_: Exception) {}
                }
                errThread.isDaemon = true
                errThread.start()
                proc.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
                val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return@submit
                }
                errThread.join(500)
                // Be tolerant of any leading stdout noise before the JSON object.
                val jsonStart = out.indexOf('{')
                if (jsonStart < 0) return@submit
                val result = gson.fromJson(out.substring(jsonStart), AnalyzerResult::class.java)
                    ?: return@submit
                onResult(result.diagnostics)
            } catch (_: Exception) {
                // Analysis is best-effort; never disturb the editor.
            }
        }
    }

    fun dispose() {
        executor.shutdownNow()
        scriptFile?.delete()
    }
}
