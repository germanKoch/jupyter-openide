package com.openide.jupyter.navigation

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun interface NavigationHelperSource {
    fun readText(): String
}

fun interface NavigationProcessStarter {
    fun start(command: List<String>, workingDirectory: Path?): Process
}

/**
 * Static Python source resolver backed by the selected interpreter's parser and import layout.
 *
 * The helper parses notebook/module source with `ast`; it never imports a target module and never
 * evaluates notebook code. Process input, output, module traversal and wall-clock time are bounded.
 */
class PythonNavigationResolver(
    private val limits: NavigationResolverLimits = NavigationResolverLimits(),
    private val helperSource: NavigationHelperSource = ClasspathNavigationHelperSource,
    private val processStarter: NavigationProcessStarter = DefaultNavigationProcessStarter,
) : NavigationResolver, Closeable {
    private val gson = Gson()
    private val helperLock = Any()

    @Volatile
    private var helperPath: Path? = null

    @Volatile
    private var closed = false

    override fun resolve(request: NavigationRequest): NavigationResult {
        validate(request)?.let { return it }

        val currentCell = request.cells.first { it.id == request.currentCellId }
        val codePointOffset =
            NavigationPositions.utf16OffsetToCodePointOffset(currentCell.source, request.cursorOffsetUtf16)
                ?: return NavigationUnresolved(
                    NavigationFailure.INVALID_REQUEST,
                    "The cursor offset is outside the current cell or splits a Unicode surrogate pair.",
                )

        val payload =
            HelperRequest(
                cells = request.cells.map { HelperCell(it.id, it.source) },
                currentCellId = request.currentCellId,
                cursorCodePointOffset = codePointOffset,
                maxSourceBytes = limits.maxSourceBytes,
                maxModules = limits.maxModules,
                maxReexportDepth = limits.maxReexportDepth,
            )
        val input = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        if (input.size > limits.maxInputBytes) {
            return NavigationUnresolved(
                NavigationFailure.INPUT_TOO_LARGE,
                "Navigation input exceeds ${limits.maxInputBytes} bytes.",
            )
        }

        val script =
            try {
                ensureHelperPath()
            } catch (exception: Exception) {
                return NavigationUnresolved(
                    NavigationFailure.HELPER_ERROR,
                    "Cannot prepare the navigation helper: ${safeMessage(exception)}",
                )
            }
        val process =
            try {
                processStarter.start(
                    listOf(request.pythonInterpreter, "-I", "-B", script.toString()),
                    request.workingDirectory,
                )
            } catch (exception: Exception) {
                return NavigationUnresolved(
                    NavigationFailure.PROCESS_ERROR,
                    "Cannot start the selected Python interpreter: ${safeMessage(exception)}",
                )
            }

        return communicate(process, input)
    }

    override fun close() {
        val path = synchronized(helperLock) {
            if (closed) return
            closed = true
            helperPath.also { helperPath = null }
        }
        path?.let {
            try {
                Files.deleteIfExists(it)
            } catch (_: Exception) {
                // The OS can reclaim a stale temporary helper; resolution has already stopped.
            }
        }
    }

    private fun validate(request: NavigationRequest): NavigationUnresolved? {
        if (closed) {
            return NavigationUnresolved(NavigationFailure.INVALID_REQUEST, "The resolver is closed.")
        }
        if (request.pythonInterpreter.isBlank()) {
            return NavigationUnresolved(
                NavigationFailure.INVALID_REQUEST,
                "A Python interpreter is required.",
            )
        }
        if (request.cells.isEmpty()) {
            return NavigationUnresolved(NavigationFailure.INVALID_REQUEST, "The notebook has no cells.")
        }
        if (request.cells.map { it.id }.toSet().size != request.cells.size) {
            return NavigationUnresolved(
                NavigationFailure.INVALID_REQUEST,
                "Notebook cell ids must be unique.",
            )
        }
        if (request.cells.none { it.id == request.currentCellId }) {
            return NavigationUnresolved(
                NavigationFailure.INVALID_REQUEST,
                "The current cell is not present in the notebook snapshot.",
            )
        }
        if (request.workingDirectory != null && !Files.isDirectory(request.workingDirectory)) {
            return NavigationUnresolved(
                NavigationFailure.INVALID_REQUEST,
                "The navigation working directory does not exist or is not a directory.",
            )
        }
        return null
    }

    private fun communicate(process: Process, input: ByteArray): NavigationResult {
        val executor = newIoExecutor()
        val stdout = executor.submit(Callable { drain(process.inputStream, limits.maxOutputBytes) })
        val stderr = executor.submit(Callable { drain(process.errorStream, limits.maxOutputBytes) })
        val stdin =
            executor.submit(
                Callable {
                    process.outputStream.use { stream ->
                        stream.write(input)
                        stream.flush()
                    }
                },
            )

        try {
            if (!process.waitFor(limits.timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminate(process)
                return NavigationUnresolved(
                    NavigationFailure.TIMEOUT,
                    "Static navigation exceeded ${limits.timeoutMillis} ms.",
                )
            }

            val stdoutCapture = awaitCapture(stdout)
            val stderrCapture = awaitCapture(stderr)
            awaitInput(stdin)
            if (stdoutCapture == null || stderrCapture == null) {
                return NavigationUnresolved(
                    NavigationFailure.PROCESS_ERROR,
                    "The navigation helper streams did not close in time.",
                )
            }
            if (stdoutCapture.truncated || stderrCapture.truncated) {
                return NavigationUnresolved(
                    NavigationFailure.OUTPUT_TOO_LARGE,
                    "Navigation helper output exceeds ${limits.maxOutputBytes} bytes.",
                )
            }
            if (process.exitValue() != 0) {
                val detail = stderrCapture.text().trim().take(MAX_ERROR_TEXT)
                return NavigationUnresolved(
                    NavigationFailure.PROCESS_ERROR,
                    if (detail.isEmpty()) {
                        "The navigation helper exited with code ${process.exitValue()}."
                    } else {
                        "The navigation helper exited with code ${process.exitValue()}: $detail"
                    },
                )
            }
            return parseResponse(stdoutCapture.text())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            terminate(process)
            return NavigationUnresolved(
                NavigationFailure.PROCESS_ERROR,
                "Navigation was interrupted.",
            )
        } finally {
            if (process.isAlive) terminate(process)
            stdin.cancel(true)
            stdout.cancel(true)
            stderr.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun parseResponse(json: String): NavigationResult {
        val response =
            try {
                gson.fromJson(json, HelperResponse::class.java)
            } catch (_: JsonParseException) {
                null
            }
                ?: return NavigationUnresolved(
                    NavigationFailure.INVALID_RESPONSE,
                    "The navigation helper returned invalid JSON.",
                )

        if (response.line != null && response.line < 0 || response.column != null && response.column < 0) {
            return NavigationUnresolved(
                NavigationFailure.INVALID_RESPONSE,
                "The navigation helper returned a negative source position.",
            )
        }
        return when (response.status) {
            "notebook" -> {
                val cellId = response.cellId
                val line = response.line
                val column = response.column
                val symbol = response.symbol
                if (cellId == null || line == null || column == null || symbol == null) {
                    invalidResponse("Incomplete notebook location.")
                } else {
                    NotebookLocation(cellId, line, column, symbol)
                }
            }

            "file" -> {
                val path = response.path
                val line = response.line
                val column = response.column
                val symbol = response.symbol
                if (path == null || line == null || column == null || symbol == null) {
                    invalidResponse("Incomplete file location.")
                } else {
                    try {
                        FileLocation(Path.of(path).toAbsolutePath().normalize(), line, column, symbol)
                    } catch (_: Exception) {
                        invalidResponse("Invalid file path.")
                    }
                }
            }

            "unresolved" -> {
                NavigationUnresolved(mapReason(response.reason), response.message?.take(MAX_ERROR_TEXT))
            }

            else -> invalidResponse("Unknown navigation response status.")
        }
    }

    private fun ensureHelperPath(): Path =
        synchronized(helperLock) {
            check(!closed) { "The resolver is closed." }
            helperPath?.let { return@synchronized it }
            val text = helperSource.readText()
            val path = Files.createTempFile("jupyter-openide-navigation-", ".py")
            try {
                Files.writeString(
                    path,
                    text,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            } catch (exception: Exception) {
                Files.deleteIfExists(path)
                throw exception
            }
            path.toFile().deleteOnExit()
            helperPath = path
            path
        }

    private fun awaitCapture(future: Future<StreamCapture>): StreamCapture? =
        try {
            future.get(limits.streamJoinMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        }

    private fun awaitInput(future: Future<Unit>) {
        try {
            future.get(limits.streamJoinMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // A helper that exits early commonly closes stdin. Its exit/output carries the result.
        }
    }

    private fun terminate(process: Process) {
        val descendants = try { process.descendants().toList() } catch (_: Exception) { emptyList() }
        descendants.asReversed().forEach { child ->
            try {
                child.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        try {
            process.toHandle().destroyForcibly()
        } catch (_: Exception) {
        }
        if (limits.terminationGraceMillis > 0) {
            try {
                process.waitFor(limits.terminationGraceMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (process.isAlive) process.toHandle().destroyForcibly()
        descendants.forEach { child ->
            try {
                if (child.isAlive) child.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        try {
            process.outputStream.close()
        } catch (_: Exception) {
        }
    }

    private fun mapReason(reason: String?): NavigationFailure =
        when (reason) {
            "no_symbol" -> NavigationFailure.NO_SYMBOL
            "source_unavailable" -> NavigationFailure.SOURCE_UNAVAILABLE
            "invalid_request", "invalid_source" -> NavigationFailure.INVALID_REQUEST
            "helper_error" -> NavigationFailure.HELPER_ERROR
            else -> NavigationFailure.NOT_FOUND
        }

    private fun invalidResponse(message: String) =
        NavigationUnresolved(NavigationFailure.INVALID_RESPONSE, message)

    private fun safeMessage(exception: Exception): String =
        (exception.message ?: exception::class.java.simpleName).take(MAX_ERROR_TEXT)

    private data class HelperCell(val id: String, val source: String)

    private data class HelperRequest(
        val cells: List<HelperCell>,
        val currentCellId: String,
        val cursorCodePointOffset: Int,
        val maxSourceBytes: Int,
        val maxModules: Int,
        val maxReexportDepth: Int,
    )

    private data class HelperResponse(
        val status: String? = null,
        val cellId: String? = null,
        val path: String? = null,
        val line: Int? = null,
        val column: Int? = null,
        val symbol: String? = null,
        val reason: String? = null,
        val message: String? = null,
    )

    private data class StreamCapture(val bytes: ByteArray, val truncated: Boolean) {
        fun text(): String = String(bytes, StandardCharsets.UTF_8)
    }

    private companion object {
        const val MAX_ERROR_TEXT = 4_096

        fun newIoExecutor(): ExecutorService {
            var index = 0
            return Executors.newFixedThreadPool(3) { runnable ->
                Thread(runnable, "jupyter-navigation-io-${index++}").apply { isDaemon = true }
            }
        }

        fun drain(stream: java.io.InputStream, limit: Int): StreamCapture =
            stream.use {
                val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
                val buffer = ByteArray(8 * 1024)
                var truncated = false
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    val remaining = limit - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(remaining, read))
                    if (read > remaining) truncated = true
                }
                StreamCapture(output.toByteArray(), truncated)
            }
    }
}

private object ClasspathNavigationHelperSource : NavigationHelperSource {
    override fun readText(): String =
        PythonNavigationResolver::class.java
            .getResourceAsStream("/notebook/navigation.py")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Bundled navigation helper /notebook/navigation.py is missing.")
}

private object DefaultNavigationProcessStarter : NavigationProcessStarter {
    override fun start(command: List<String>, workingDirectory: Path?): Process =
        ProcessBuilder(command)
            .apply { if (workingDirectory != null) directory(workingDirectory.toFile()) }
            .start()
}
