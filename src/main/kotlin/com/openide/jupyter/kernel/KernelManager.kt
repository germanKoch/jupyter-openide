package com.openide.jupyter.kernel

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class KernelManagerConfig(
    val startupTimeoutMillis: Long = 10_000,
    val heartbeatIntervalMillis: Long = 5_000,
    val heartbeatTimeoutMillis: Long = 5_000,
    val shutdownTimeoutMillis: Long = 500,
    val transportCloseTimeoutMillis: Long = 2_000
) {
    init {
        require(startupTimeoutMillis > 0) { "startupTimeoutMillis must be positive" }
        require(heartbeatIntervalMillis > 0) { "heartbeatIntervalMillis must be positive" }
        require(heartbeatTimeoutMillis > 0) { "heartbeatTimeoutMillis must be positive" }
        require(shutdownTimeoutMillis >= 0) { "shutdownTimeoutMillis must not be negative" }
        require(transportCloseTimeoutMillis > 0) { "transportCloseTimeoutMillis must be positive" }
    }
}

class KernelRequestHandle internal constructor(
    val msgId: String,
    val completion: CompletableFuture<Unit>
)

internal interface ManagedKernelProcess {
    val isAlive: Boolean
    fun destroy()
}

internal fun interface KernelProcessLauncher {
    fun launch(
        target: KernelTarget.Launch,
        connectionFile: File,
        onTerminated: (Int) -> Unit
    ): ManagedKernelProcess
}

internal object DefaultKernelProcessLauncher : KernelProcessLauncher {
    override fun launch(
        target: KernelTarget.Launch,
        connectionFile: File,
        onTerminated: (Int) -> Unit
    ): ManagedKernelProcess {
        val commandLine = GeneralCommandLine(
            target.pythonPath,
            "-m",
            "ipykernel_launcher",
            "-f",
            connectionFile.absolutePath
        ).apply {
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            target.workingDirectory?.let { workDirectory = it }
        }
        val handler = OSProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                onTerminated(event.exitCode)
            }
        })
        handler.startNotify()

        return object : ManagedKernelProcess {
            private val destroyed = AtomicBoolean(false)

            override val isAlive: Boolean
                get() = !handler.isProcessTerminated

            override fun destroy() {
                if (destroyed.compareAndSet(false, true) && !handler.isProcessTerminated) {
                    handler.destroyProcess()
                }
            }
        }
    }
}

class KernelManager internal constructor(
    val target: KernelTarget,
    private val parentDisposable: Disposable,
    val config: KernelManagerConfig,
    private val transportFactory: KernelTransportFactory,
    private val processLauncher: KernelProcessLauncher
) : Disposable {

    constructor(
        target: KernelTarget,
        parentDisposable: Disposable,
        config: KernelManagerConfig = KernelManagerConfig()
    ) : this(
        target,
        parentDisposable,
        config,
        DefaultKernelTransportFactory,
        DefaultKernelProcessLauncher
    )

    /** Backward-compatible launch constructor used by the current editor. */
    constructor(
        pythonPath: String,
        parentDisposable: Disposable,
        workingDirectory: File? = null
    ) : this(
        KernelTarget.Launch(pythonPath, workingDirectory),
        parentDisposable,
        KernelManagerConfig(),
        DefaultKernelTransportFactory,
        DefaultKernelProcessLauncher
    )

    val ownership: KernelOwnership get() = target.ownership
    val pythonPath: String? get() = (target as? KernelTarget.Launch)?.pythonPath
    /** Interpreter explicitly selected for local source lookup, including attached kernels. */
    val sourceInterpreter: String? get() = target.sourceInterpreter

    @Volatile
    var status: KernelStatus = KernelStatus.DISCONNECTED
        private set

    var onStatusChanged: ((KernelStatus) -> Unit)? = null
    var onMessage: ((String, JsonObject) -> Unit)? = null
    var onRequestFailed: ((String, Throwable) -> Unit)? = null
    var sourceLocationResolver: KernelSourceLocationResolver? = null

    private val lifecycleLock = Any()
    private val generation = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val processExitFailures = ConcurrentHashMap<Long, Throwable>()

    @Volatile
    private var activeTransport: KernelTransport? = null

    @Volatile
    private var activeProcess: ManagedKernelProcess? = null

    @Volatile
    private var ownedConnectionFile: File? = null

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Starts an owned kernel or attaches to an existing connection and blocks
     * until a matching kernel_info_reply and heartbeat echo are observed.
     */
    fun start() {
        var startingStatusCallback: ((KernelStatus) -> Unit)? = null
        val myGeneration = synchronized(lifecycleLock) {
            check(!disposed) { "KernelManager is disposed" }
            if (status != KernelStatus.DISCONNECTED) return
            generation.incrementAndGet().also {
                status = KernelStatus.STARTING
                startingStatusCallback = onStatusChanged
            }
        }
        notifyStatusChanged(startingStatusCallback, KernelStatus.STARTING)

        var localTransport: KernelTransport? = null
        var localProcess: ManagedKernelProcess? = null
        var localOwnedFile: File? = null

        try {
            val connectionInfo = when (val currentTarget = target) {
                is KernelTarget.Launch -> {
                    val (file, info) = createOwnedConnectionFile()
                    localOwnedFile = file
                    ensureGenerationActive(myGeneration)
                    localProcess = processLauncher.launch(currentTarget, file) { exitCode ->
                        handleProcessTerminated(myGeneration, exitCode)
                    }
                    processExitFailures.remove(myGeneration)?.let { throw it }
                    check(localProcess.isAlive) { "Jupyter kernel process exited during startup" }
                    info
                }
                is KernelTarget.ConnectionFile -> KernelConnectionInfoCodec.read(currentTarget.file)
                is KernelTarget.Manual -> currentTarget.connectionInfo.validated()
            }

            ensureGenerationActive(myGeneration)
            val transportReference = arrayOfNulls<KernelTransport>(1)
            val listener = object : KernelTransportListener {
                override fun onMessage(message: JupyterMessage) {
                    handleTransportMessage(myGeneration, transportReference[0], message)
                }

                override fun onDisconnected(cause: Throwable) {
                    handleTransportDisconnected(
                        myGeneration,
                        transportReference[0],
                        cause
                    )
                }
            }
            localTransport = transportFactory.create(connectionInfo, config, listener)
            transportReference[0] = localTransport

            synchronized(lifecycleLock) {
                ensureGenerationActive(myGeneration)
                activeTransport = localTransport
                activeProcess = localProcess
                ownedConnectionFile = localOwnedFile
            }

            localTransport.start()
            processExitFailures.remove(myGeneration)?.let { throw it }
            try {
                localTransport.ready.get(config.startupTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw KernelDisconnectedException(
                    "Jupyter kernel did not answer kernel_info and heartbeat within " +
                        "${config.startupTimeoutMillis} ms",
                    e
                )
            }
            processExitFailures.remove(myGeneration)?.let { throw it }
            val idleStatusCallback = synchronized(lifecycleLock) {
                ensureGenerationActive(myGeneration)
                check(activeTransport === localTransport) {
                    "Jupyter kernel transport disconnected during startup"
                }
                if (localProcess != null) {
                    check(activeProcess === localProcess && localProcess.isAlive) {
                        "Jupyter kernel process exited during startup"
                    }
                    // isAlive may race with the process callback. Re-read both
                    // the recorded exit and lifecycle generation before making
                    // the successful startup state externally visible.
                    processExitFailures.remove(myGeneration)?.let { throw it }
                    ensureGenerationActive(myGeneration)
                    check(activeProcess === localProcess) {
                        "Jupyter kernel process disconnected during startup"
                    }
                }
                check(activeTransport === localTransport) {
                    "Jupyter kernel transport disconnected during startup"
                }
                status = KernelStatus.IDLE
                onStatusChanged
            }
            notifyStatusChanged(idleStatusCallback, KernelStatus.IDLE)
        } catch (t: Throwable) {
            val cause = unwrapFailure(t)
            closeSafely(localTransport)
            destroySafely(localProcess)
            deleteSafely(localOwnedFile)
            clearActiveIfMatches(localTransport, localProcess, localOwnedFile)
            val failureTransition = synchronized(lifecycleLock) {
                if (generation.get() != myGeneration) {
                    null
                } else {
                    generation.incrementAndGet()
                    status = KernelStatus.DISCONNECTED
                    StartupFailureTransition(
                        statusCallback = onStatusChanged,
                        pendingRequests = detachAllPending()
                    )
                }
            }
            failureTransition?.let { transition ->
                failPending(transition.pendingRequests, cause)
                notifyStatusChanged(transition.statusCallback, KernelStatus.DISCONNECTED)
            }
            throw asRuntimeFailure(cause)
        } finally {
            processExitFailures.remove(myGeneration)
        }
    }

    /**
     * Stops an owned kernel, but only disconnects an attached target. Cleanup is
     * always attempted, even when the externally visible status is disconnected.
     */
    fun stop() {
        val snapshot = synchronized(lifecycleLock) {
            generation.incrementAndGet()
            val value = ResourceSnapshot(
                transport = activeTransport,
                process = activeProcess,
                ownedFile = ownedConnectionFile
            )
            activeTransport = null
            activeProcess = null
            ownedConnectionFile = null
            value
        }

        val stopped = KernelDisconnectedException("Kernel session stopped")
        failAllPending(stopped)

        if (ownership == KernelOwnership.OWNED) {
            snapshot.transport?.let { transport ->
                try {
                    transport.requestShutdown().get(
                        config.shutdownTimeoutMillis,
                        TimeUnit.MILLISECONDS
                    )
                } catch (_: Exception) {
                    // The owned process is destroyed below if graceful shutdown fails.
                }
            }
        }

        closeSafely(snapshot.transport)
        if (ownership == KernelOwnership.OWNED) {
            destroySafely(snapshot.process)
            deleteSafely(snapshot.ownedFile)
        }
        publishStatus(KernelStatus.DISCONNECTED)
    }

    fun reconnect() {
        stop()
        start()
    }

    /**
     * Legacy API. A placeholder is installed before send, so messages arriving
     * before registerCallback() are buffered rather than lost.
     */
    fun sendExecuteRequest(code: String): String {
        return sendLegacyShellRequest("execute_request", executeContent(code))
    }

    /** Atomic execute API recommended for new integrations. */
    fun execute(code: String, callback: (JsonObject) -> Unit): KernelRequestHandle {
        return sendAtomicShellRequest("execute_request", executeContent(code), callback)
    }

    /** Standard Jupyter inspect request; source file/line is not guaranteed by the protocol. */
    fun inspect(
        code: String,
        cursorPosition: Int = code.codePointCount(0, code.length),
        detailLevel: Int = 0,
        callback: (JsonObject) -> Unit
    ): KernelRequestHandle {
        require(cursorPosition >= 0) { "cursorPosition must not be negative" }
        require(detailLevel >= 0) { "detailLevel must not be negative" }
        val content = JsonObject().apply {
            addProperty("code", code)
            addProperty("cursor_pos", cursorPosition)
            addProperty("detail_level", detailLevel)
        }
        return sendAtomicShellRequest("inspect_request", content, callback)
    }

    /** Returns false and a failed Result when no language-aware resolver is installed. */
    fun requestSourceLocation(
        expression: String,
        callback: (Result<KernelSourceLocation?>) -> Unit
    ): Boolean {
        val resolver = sourceLocationResolver
        if (resolver == null) {
            callback(
                Result.failure(
                    UnsupportedOperationException(
                        "No kernel source-location resolver is configured"
                    )
                )
            )
            return false
        }
        resolver.request(expression, callback)
        return true
    }

    fun interrupt() {
        val transport = activeTransport ?: return
        transport.sendControl("interrupt_request", JsonObject())
    }

    fun registerCallback(msgId: String, callback: (JsonObject) -> Unit) {
        pendingRequests.computeIfAbsent(msgId) { PendingRequest(autoRemove = false) }
            .register(callback)
    }

    fun removeCallback(msgId: String) {
        pendingRequests.remove(msgId)?.complete()
    }

    private fun sendLegacyShellRequest(msgType: String, content: JsonObject): String {
        val msgId = UUID.randomUUID().toString()
        val pending = PendingRequest(autoRemove = false)
        check(pendingRequests.putIfAbsent(msgId, pending) == null)
        try {
            requireReadyTransport().sendShell(msgType, content, msgId)
        } catch (t: Throwable) {
            pendingRequests.remove(msgId, pending)
            if (pending.fail(t)) notifyRequestFailed(msgId, t)
            throw t
        }
        return msgId
    }

    private fun sendAtomicShellRequest(
        msgType: String,
        content: JsonObject,
        callback: (JsonObject) -> Unit
    ): KernelRequestHandle {
        val msgId = UUID.randomUUID().toString()
        val expectedReplyType = msgType.removeSuffix("_request") + "_reply"
        val pending = PendingRequest(
            autoRemove = true,
            expectedReplyType = expectedReplyType
        ).apply { register(callback) }
        check(pendingRequests.putIfAbsent(msgId, pending) == null)
        try {
            requireReadyTransport().sendShell(msgType, content, msgId)
        } catch (t: Throwable) {
            pendingRequests.remove(msgId, pending)
            if (pending.fail(t)) notifyRequestFailed(msgId, t)
            throw t
        }
        return KernelRequestHandle(msgId, pending.completion)
    }

    private fun requireReadyTransport(): KernelTransport {
        val transport = activeTransport
            ?: throw KernelDisconnectedException("No Jupyter kernel is connected")
        if (status != KernelStatus.IDLE && status != KernelStatus.BUSY) {
            throw KernelDisconnectedException("Jupyter kernel is not ready (status: $status)")
        }
        return transport
    }

    private fun handleTransportMessage(
        messageGeneration: Long,
        transport: KernelTransport?,
        message: JupyterMessage
    ) {
        if (!isCurrent(messageGeneration, transport)) return

        val nextStatus = if (
            message.channel == JupyterChannel.IOPUB &&
            message.msgType == "status" &&
            transport != null &&
            transport.ready.isDone &&
            !transport.ready.isCompletedExceptionally
        ) {
            when (message.content.get("execution_state")?.asString) {
                "idle" -> KernelStatus.IDLE
                "busy" -> KernelStatus.BUSY
                "starting" -> KernelStatus.STARTING
                else -> null
            }
        } else {
            null
        }
        if (nextStatus != null) {
            var stillCurrent = false
            val statusCallback = synchronized(lifecycleLock) {
                if (!isCurrent(messageGeneration, transport)) {
                    null
                } else {
                    status = nextStatus
                    stillCurrent = true
                    onStatusChanged
                }
            }
            // Stop/disconnect won the race after the optimistic entry check.
            // Treat the whole message as stale; callbacks below remain outside
            // the lifecycle lock and cannot resurrect request/editor state.
            if (!stillCurrent) return
            notifyStatusChanged(statusCallback, nextStatus)
        }

        val parentMsgId = message.parentMsgId
        if (parentMsgId != null) {
            val pending = pendingRequests[parentMsgId]
            if (pending != null) {
                pending.deliver(message.toLegacyJson())
                when (val outcome = pending.observe(message, parentMsgId)) {
                    PendingOutcome.NONE -> Unit
                    PendingOutcome.COMPLETED -> {
                        if (pending.autoRemove) pendingRequests.remove(parentMsgId, pending)
                    }
                    is PendingOutcome.FAILED -> {
                        pendingRequests.remove(parentMsgId, pending)
                        notifyRequestFailed(parentMsgId, outcome.cause)
                    }
                }
            }
        }

        try {
            onMessage?.invoke(message.msgType, message.toLegacyJson())
        } catch (_: Exception) {
        }
    }

    private fun handleTransportDisconnected(
        disconnectedGeneration: Long,
        transport: KernelTransport?,
        cause: Throwable
    ) {
        val snapshot = synchronized(lifecycleLock) {
            if (!isCurrent(disconnectedGeneration, transport)) return
            generation.incrementAndGet()
            val value = ResourceSnapshot(activeTransport, activeProcess, ownedConnectionFile)
            activeTransport = null
            activeProcess = null
            ownedConnectionFile = null
            value
        }

        failAllPending(cause)
        if (ownership == KernelOwnership.OWNED) {
            destroySafely(snapshot.process)
            deleteSafely(snapshot.ownedFile)
        }
        publishStatus(KernelStatus.DISCONNECTED)
    }

    private fun publishStatus(value: KernelStatus) {
        status = value
        notifyStatusChanged(onStatusChanged, value)
    }

    private fun notifyStatusChanged(
        callback: ((KernelStatus) -> Unit)?,
        value: KernelStatus
    ) {
        try {
            callback?.invoke(value)
        } catch (_: Exception) {
        }
    }

    private fun handleProcessTerminated(processGeneration: Long, exitCode: Int) {
        if (generation.get() != processGeneration) return
        val failure = KernelDisconnectedException(
            "Jupyter kernel process exited with code $exitCode"
        )
        processExitFailures[processGeneration] = failure
        activeTransport?.fail(failure)
    }

    private fun failAllPending(cause: Throwable) {
        failPending(detachAllPending(), cause)
    }

    private fun detachAllPending(): List<Pair<String, PendingRequest>> {
        val detached = mutableListOf<Pair<String, PendingRequest>>()
        pendingRequests.entries.toList().forEach { (msgId, pending) ->
            if (pendingRequests.remove(msgId, pending)) {
                detached += msgId to pending
            }
        }
        return detached
    }

    private fun failPending(
        requests: List<Pair<String, PendingRequest>>,
        cause: Throwable
    ) {
        requests.forEach { (msgId, pending) ->
            if (pending.fail(cause)) notifyRequestFailed(msgId, cause)
        }
    }

    private fun notifyRequestFailed(msgId: String, cause: Throwable) {
        try {
            onRequestFailed?.invoke(msgId, cause)
        } catch (_: Exception) {
        }
    }

    private fun executeContent(code: String): JsonObject = JsonObject().apply {
        addProperty("code", code)
        addProperty("silent", false)
        addProperty("store_history", true)
        addProperty("allow_stdin", false)
        addProperty("stop_on_error", true)
    }

    private fun ensureGenerationActive(expected: Long) {
        check(generation.get() == expected && !disposed) { "Kernel startup was cancelled" }
    }

    private fun isCurrent(expectedGeneration: Long, transport: KernelTransport?): Boolean {
        return generation.get() == expectedGeneration && activeTransport === transport
    }

    private fun clearActiveIfMatches(
        transport: KernelTransport?,
        process: ManagedKernelProcess?,
        file: File?
    ) {
        synchronized(lifecycleLock) {
            if (activeTransport === transport) activeTransport = null
            if (activeProcess === process) activeProcess = null
            if (ownedConnectionFile === file) ownedConnectionFile = null
        }
    }

    private fun createOwnedConnectionFile(): Pair<File, KernelConnectionInfo> {
        val reservations = mutableListOf<ServerSocket>()
        try {
            repeat(5) {
                reservations += ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                }
            }
            val ports = reservations.map { it.localPort }
            val random = SecureRandom()
            val key = ByteArray(32).also(random::nextBytes)
                .joinToString("") { "%02x".format(it) }
            val info = KernelConnectionInfo(
                ip = "127.0.0.1",
                transport = "tcp",
                shellPort = ports[0],
                iopubPort = ports[1],
                stdinPort = ports[2],
                controlPort = ports[3],
                hbPort = ports[4],
                key = key,
                signatureScheme = MessageSigner.DEFAULT_SIGNATURE_SCHEME,
                kernelName = "python3"
            ).validated()
            val file = createSecureTempFile()
            try {
                KernelConnectionInfoCodec.write(file, info)
                file.deleteOnExit()
                return file to info
            } catch (t: Throwable) {
                deleteSafely(file)
                throw t
            }
        } finally {
            reservations.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createSecureTempFile(): File {
        val permissions = PosixFilePermissions.fromString("rw-------")
        val path = try {
            Files.createTempFile(
                "jupyter_kernel_",
                ".json",
                PosixFilePermissions.asFileAttribute(permissions)
            )
        } catch (_: UnsupportedOperationException) {
            Files.createTempFile("jupyter_kernel_", ".json")
        }
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
        }
        return path.toFile()
    }

    private fun unwrapFailure(t: Throwable): Throwable {
        return if (t is ExecutionException && t.cause != null) t.cause!! else t
    }

    private fun asRuntimeFailure(t: Throwable): RuntimeException {
        return when (t) {
            is RuntimeException -> t
            else -> KernelDisconnectedException("Jupyter kernel failed: ${t.message}", t)
        }
    }

    private fun closeSafely(transport: KernelTransport?) {
        try {
            transport?.close()
        } catch (_: Exception) {
        }
    }

    private fun destroySafely(process: ManagedKernelProcess?) {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
    }

    private fun deleteSafely(file: File?) {
        try {
            file?.delete()
        } catch (_: Exception) {
        }
    }

    override fun dispose() {
        disposed = true
        stop()
    }

    private data class ResourceSnapshot(
        val transport: KernelTransport?,
        val process: ManagedKernelProcess?,
        val ownedFile: File?
    )

    private data class StartupFailureTransition(
        val statusCallback: ((KernelStatus) -> Unit)?,
        val pendingRequests: List<Pair<String, PendingRequest>>
    )

    private sealed interface PendingOutcome {
        data object NONE : PendingOutcome
        data object COMPLETED : PendingOutcome
        data class FAILED(val cause: Throwable) : PendingOutcome
    }

    private class PendingRequest(
        val autoRemove: Boolean,
        private val expectedReplyType: String? = null
    ) {
        val completion = CompletableFuture<Unit>()

        private val lock = Any()
        private val backlog = mutableListOf<JsonObject>()
        private var callback: ((JsonObject) -> Unit)? = null
        private var replyReceived = expectedReplyType == null
        private var idleReceived = false
        private var terminal = false

        fun register(newCallback: (JsonObject) -> Unit) {
            val queued = synchronized(lock) {
                callback = newCallback
                backlog.toList().also { backlog.clear() }
            }
            queued.forEach(::invokeSafely)
        }

        fun deliver(message: JsonObject) {
            val current = synchronized(lock) {
                if (terminal) return
                callback.also {
                    if (it == null) backlog += message
                }
            }
            if (current != null) invokeSafely(message)
        }

        /**
         * Atomic requests complete only after both their shell reply and the
         * matching IOPub idle, regardless of cross-channel arrival order. An
         * execute request aborted by stop_on_error is terminal immediately and
         * must never be presented as a successful idle execution.
         */
        fun observe(message: JupyterMessage, msgId: String): PendingOutcome {
            val outcome = synchronized(lock) {
                if (terminal) return PendingOutcome.NONE

                if (
                    expectedReplyType != null &&
                    message.channel == JupyterChannel.SHELL &&
                    message.msgType == expectedReplyType
                ) {
                    replyReceived = true
                    if (
                        expectedReplyType == "execute_reply" &&
                        message.content.get("status")?.asString == "aborted"
                    ) {
                        terminal = true
                        return@synchronized PendingOutcome.FAILED(
                            KernelRequestAbortedException(
                                "Jupyter kernel aborted execute request $msgId after a previous failure"
                            )
                        )
                    }
                }
                if (
                    message.channel == JupyterChannel.IOPUB &&
                    message.msgType == "status" &&
                    message.content.get("execution_state")?.asString == "idle"
                ) {
                    idleReceived = true
                }
                if (replyReceived && idleReceived) {
                    terminal = true
                    PendingOutcome.COMPLETED
                } else {
                    PendingOutcome.NONE
                }
            }
            when (outcome) {
                PendingOutcome.COMPLETED -> completion.complete(Unit)
                is PendingOutcome.FAILED -> completion.completeExceptionally(outcome.cause)
                PendingOutcome.NONE -> Unit
            }
            return outcome
        }

        fun complete(): Boolean {
            val changed = synchronized(lock) {
                if (terminal) false else {
                    terminal = true
                    true
                }
            }
            if (changed) completion.complete(Unit)
            return changed
        }

        fun fail(cause: Throwable): Boolean {
            val changed = synchronized(lock) {
                if (terminal) false else {
                    terminal = true
                    backlog.clear()
                    true
                }
            }
            if (changed) completion.completeExceptionally(cause)
            return changed
        }

        private fun invokeSafely(message: JsonObject) {
            try {
                callback?.invoke(message)
            } catch (_: Exception) {
            }
        }
    }
}
