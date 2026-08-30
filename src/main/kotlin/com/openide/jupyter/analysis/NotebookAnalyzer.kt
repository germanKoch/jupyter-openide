package com.openide.jupyter.analysis

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private val workingDirectory: File?,
    private val processTimeoutMillis: Long = 10_000
) {
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jupyter-analyzer").apply { isDaemon = true }
    }

    @Volatile private var scriptFile: File? = null
    private val activeProcess = AtomicReference<Process?>()

    private fun ensureScript(): File? {
        scriptFile?.let { if (it.exists()) return it }
        // Retry the read: a transient IDE-level jar decompression failure (a memory-mapped
        // ZipException) can make one read fail while a retry succeeds. Failure here only
        // disables analysis, never the editor, so we degrade silently.
        repeat(3) {
            try {
                val text = javaClass.classLoader
                    .getResourceAsStream("notebook/analyzer.py")
                    ?.bufferedReader()?.use { it.readText() } ?: return null
                val tmp = File.createTempFile("jupyter_analyzer_", ".py")
                tmp.deleteOnExit()
                tmp.writeText(text)
                scriptFile = tmp
                return tmp
            } catch (_: Throwable) {
                // try again
            }
        }
        return null
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
                activeProcess.set(proc)
                // Both pipes must be drained concurrently. Reading stdout to EOF before
                // waitFor made the old ten-second timeout ineffective: a hung analyzer
                // simply kept readText() blocked forever.
                val stdout = ByteArrayOutputStream()
                val outThread = Thread {
                    try { proc.inputStream.use { it.copyTo(stdout) } } catch (_: Exception) {}
                }
                outThread.isDaemon = true
                outThread.start()
                val errThread = Thread {
                    try { proc.errorStream.use { it.readBytes() } } catch (_: Exception) {}
                }
                errThread.isDaemon = true
                errThread.start()
                // stdin can block as well (the OS pipe fills if a broken helper never
                // reads it), so it participates in the same wall-clock timeout.
                val inputThread = Thread {
                    try {
                        proc.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
                    } catch (_: Exception) {
                    }
                }
                inputThread.isDaemon = true
                inputThread.start()

                if (!proc.waitFor(processTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    terminateProcess(proc)
                    return@submit
                }
                inputThread.join(500)
                outThread.join(1_000)
                errThread.join(500)
                if (proc.exitValue() != 0 || inputThread.isAlive || outThread.isAlive) return@submit
                val out = stdout.toString(Charsets.UTF_8)
                // Be tolerant of any leading stdout noise before the JSON object.
                val jsonStart = out.indexOf('{')
                if (jsonStart < 0) return@submit
                val result = gson.fromJson(out.substring(jsonStart), AnalyzerResult::class.java)
                    ?: return@submit
                onResult(result.diagnostics)
            } catch (_: Exception) {
                // Analysis is best-effort; never disturb the editor.
            } finally {
                activeProcess.getAndSet(null)?.let { process ->
                    if (process.isAlive) terminateProcess(process)
                }
            }
        }
    }

    fun dispose() {
        activeProcess.getAndSet(null)?.let(::terminateProcess)
        executor.shutdownNow()
        scriptFile?.delete()
    }

    private fun terminateProcess(process: Process) {
        val descendants = try { process.descendants().toList() } catch (_: Exception) { emptyList() }
        descendants.asReversed().forEach { child ->
            try { child.destroyForcibly() } catch (_: Exception) {}
        }
        // Process.destroy()/destroyForcibly() may synchronously close a Java pipe
        // and wait behind the blocked stdin writer. ProcessHandle performs the OS
        // kill first, so the writer is released and the wall-clock bound is real.
        try { process.toHandle().destroyForcibly() } catch (_: Exception) {}
        try { process.waitFor(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        descendants.forEach { child ->
            try { if (child.isAlive) child.destroyForcibly() } catch (_: Exception) {}
        }
        try { if (process.isAlive) process.toHandle().destroyForcibly() } catch (_: Exception) {}
        // Closing the Java-side pipe handles unblocks daemon drain threads even
        // if a platform takes a moment to reap an already-killed descendant.
        try { process.outputStream.close() } catch (_: Exception) {}
        try { process.inputStream.close() } catch (_: Exception) {}
        try { process.errorStream.close() } catch (_: Exception) {}
    }
}
