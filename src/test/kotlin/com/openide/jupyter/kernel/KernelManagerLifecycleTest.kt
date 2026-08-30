package com.openide.jupyter.kernel

import com.google.gson.JsonObject
import com.intellij.openapi.util.Disposer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KernelManagerLifecycleTest {

    @Test
    fun `attached target disconnects without shutdown process launch or file deletion`() {
        val externalFile = Files.createTempFile("external-kernel", ".json").toFile()
        KernelConnectionInfoCodec.write(externalFile, connectionInfo())
        val transportFactory = FakeTransportFactory()
        val launchCount = AtomicInteger()
        val parent = Disposer.newDisposable()
        val manager = KernelManager(
            KernelTarget.ConnectionFile(externalFile),
            parent,
            testConfig(),
            transportFactory,
            KernelProcessLauncher { _, _, _ ->
                launchCount.incrementAndGet()
                error("Attached target must not launch a process")
            }
        )

        try {
            manager.start()
            manager.stop()
            manager.stop()

            assertEquals(KernelOwnership.ATTACHED, manager.ownership)
            assertEquals(0, launchCount.get())
            assertEquals(0, transportFactory.transport.shutdownCount.get())
            assertEquals(1, transportFactory.transport.closeCount.get())
            assertTrue(externalFile.exists())
            assertEquals(KernelStatus.DISCONNECTED, manager.status)
        } finally {
            Disposer.dispose(parent)
            externalFile.delete()
        }
    }

    @Test
    fun `owned target requests shutdown destroys process and deletes owned file`() {
        val transportFactory = FakeTransportFactory()
        val process = FakeManagedProcess()
        var launchedWith: File? = null
        val parent = Disposer.newDisposable()
        val manager = KernelManager(
            KernelTarget.Launch("python", null),
            parent,
            testConfig(),
            transportFactory,
            KernelProcessLauncher { _, connectionFile, _ ->
                launchedWith = connectionFile
                process
            }
        )

        try {
            manager.start()
            val ownedFile = assertNotNull(launchedWith)
            assertTrue(ownedFile.exists())

            manager.stop()
            manager.stop()

            assertEquals(1, transportFactory.transport.shutdownCount.get())
            assertEquals(1, transportFactory.transport.closeCount.get())
            assertEquals(1, process.destroyCount.get())
            assertFalse(ownedFile.exists())
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `readiness timeout fails instead of synthesizing idle`() {
        val externalFile = Files.createTempFile("external-kernel", ".json").toFile()
        KernelConnectionInfoCodec.write(externalFile, connectionInfo())
        val transportFactory = FakeTransportFactory(autoReady = false)
        val parent = Disposer.newDisposable()
        val manager = KernelManager(
            KernelTarget.ConnectionFile(externalFile),
            parent,
            testConfig(startupTimeoutMillis = 20),
            transportFactory,
            neverLaunchProcess()
        )

        try {
            assertFailsWith<KernelDisconnectedException> { manager.start() }
            assertEquals(KernelStatus.DISCONNECTED, manager.status)
            assertEquals(1, transportFactory.transport.closeCount.get())
            assertTrue(externalFile.exists())
        } finally {
            Disposer.dispose(parent)
            externalFile.delete()
        }
    }

    @Test
    fun `owned process exit during startup fails and cleans owned resources`() {
        val transportFactory = FakeTransportFactory(autoReady = false)
        val process = FakeManagedProcess()
        var launchedWith: File? = null
        val parent = Disposer.newDisposable()
        val manager = KernelManager(
            KernelTarget.Launch("python"),
            parent,
            testConfig(),
            transportFactory,
            KernelProcessLauncher { _, connectionFile, onTerminated ->
                launchedWith = connectionFile
                onTerminated(17)
                process
            }
        )

        try {
            val failure = assertFailsWith<KernelDisconnectedException> { manager.start() }

            assertTrue(failure.message.orEmpty().contains("code 17"))
            assertEquals(KernelStatus.DISCONNECTED, manager.status)
            assertEquals(1, process.destroyCount.get())
            assertFalse(assertNotNull(launchedWith).exists())
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `process exit during final alive check cannot be overwritten with idle`() {
        val transportFactory = FakeTransportFactory()
        val aliveChecks = AtomicInteger()
        val destroyCount = AtomicInteger()
        lateinit var notifyTerminated: (Int) -> Unit
        val process = object : ManagedKernelProcess {
            override val isAlive: Boolean
                get() {
                    if (aliveChecks.incrementAndGet() == 2) notifyTerminated(23)
                    return true
                }

            override fun destroy() {
                destroyCount.incrementAndGet()
            }
        }
        val parent = Disposer.newDisposable()
        val manager = KernelManager(
            KernelTarget.Launch("python"),
            parent,
            testConfig(),
            transportFactory,
            KernelProcessLauncher { _, _, onTerminated ->
                notifyTerminated = onTerminated
                process
            }
        )

        try {
            val failure = assertFailsWith<KernelDisconnectedException> { manager.start() }

            assertTrue(failure.message.orEmpty().contains("code 23"))
            assertEquals(2, aliveChecks.get())
            assertEquals(KernelStatus.DISCONNECTED, manager.status)
            assertEquals(1, transportFactory.transport.closeCount.get())
            assertTrue(destroyCount.get() >= 1)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `stale iopub status cannot overwrite disconnected after stop`() {
        val blockingReady = BlockingStatusReadyFuture()
        val transportFactory = FakeTransportFactory(readyFuture = blockingReady)
        val parent = Disposer.newDisposable()
        val manager = manualManager(parent, transportFactory)
        val deliveredMessages = AtomicInteger()

        try {
            manager.onMessage = { _, _ -> deliveredMessages.incrementAndGet() }
            manager.start()
            blockingReady.blockNextStatusCheck()
            val emitted = CountDownLatch(1)
            val emitter = thread(name = "stale-iopub-status") {
                try {
                    transportFactory.transport.emit(statusMessage("stale", "idle"))
                } finally {
                    emitted.countDown()
                }
            }

            assertTrue(blockingReady.awaitStatusCheck())
            manager.stop()
            blockingReady.releaseStatusCheck()

            assertTrue(emitted.await(1, TimeUnit.SECONDS))
            emitter.join(1_000)
            assertEquals(KernelStatus.DISCONNECTED, manager.status)
            assertEquals(0, deliveredMessages.get())
        } finally {
            blockingReady.releaseStatusCheck()
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `cancelled startup failure cannot disconnect a newer generation`() {
        val firstReady = GatedFailedReadyFuture()
        val transportFactory = SequencedTransportFactory(firstReady)
        val parent = Disposer.newDisposable()
        val manager = KernelManager(
            KernelTarget.Manual(connectionInfo()),
            parent,
            testConfig(startupTimeoutMillis = 2_000),
            transportFactory,
            neverLaunchProcess()
        )
        val firstFailure = CompletableFuture<Throwable>()

        try {
            val firstStart = thread(name = "cancelled-kernel-start") {
                try {
                    manager.start()
                    firstFailure.complete(AssertionError("Cancelled startup unexpectedly succeeded"))
                } catch (failure: Throwable) {
                    firstFailure.complete(failure)
                }
            }

            assertTrue(firstReady.awaitGet())
            manager.stop()
            manager.start()
            assertEquals(KernelStatus.IDLE, manager.status)

            firstReady.releaseGet()
            firstStart.join(1_000)
            assertFalse(firstStart.isAlive)
            assertTrue(firstFailure.get(1, TimeUnit.SECONDS) is KernelDisconnectedException)
            assertEquals(KernelStatus.IDLE, manager.status)

            val handle = manager.execute("1 + 1") { }
            assertFalse(handle.completion.isCompletedExceptionally)
        } finally {
            firstReady.releaseGet()
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `atomic execute registers callback before transport send`() {
        val transportFactory = FakeTransportFactory()
        val parent = Disposer.newDisposable()
        val manager = manualManager(parent, transportFactory)
        val messages = mutableListOf<String>()

        try {
            manager.start()
            transportFactory.transport.onShellSend = { msgType, _, msgId ->
                assertEquals("execute_request", msgType)
                transportFactory.transport.emit(executeReply(msgId, "ok"))
                transportFactory.transport.emit(statusMessage(msgId, "idle"))
            }

            val handle = manager.execute("1 + 1") { message ->
                messages += message.get("msg_type").asString
            }

            assertEquals(listOf("execute_reply", "status"), messages)
            assertTrue(handle.completion.isDone)
            assertFalse(handle.completion.isCompletedExceptionally)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `execute waits for shell reply when iopub idle arrives first`() {
        val transportFactory = FakeTransportFactory()
        val parent = Disposer.newDisposable()
        val manager = manualManager(parent, transportFactory)
        lateinit var msgId: String

        try {
            manager.start()
            transportFactory.transport.onShellSend = { _, _, sentId ->
                msgId = sentId
                transportFactory.transport.emit(statusMessage(sentId, "idle"))
            }

            val handle = manager.execute("1 + 1") { }
            assertFalse(handle.completion.isDone)

            transportFactory.transport.emit(executeReply(msgId, "ok"))
            assertTrue(handle.completion.isDone)
            assertFalse(handle.completion.isCompletedExceptionally)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `aborted execute reply fails and removes request instead of reporting idle success`() {
        val transportFactory = FakeTransportFactory()
        val parent = Disposer.newDisposable()
        val manager = manualManager(parent, transportFactory)
        val messages = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, Throwable>>()

        try {
            manager.onRequestFailed = { msgId, cause -> failures += msgId to cause }
            manager.start()
            transportFactory.transport.onShellSend = { _, _, msgId ->
                transportFactory.transport.emit(executeReply(msgId, "aborted"))
                // Real ipykernel publishes busy/idle even though queued code did
                // not run. This idle must not turn the cell/request successful.
                transportFactory.transport.emit(statusMessage(msgId, "idle"))
            }

            val handle = manager.execute("print('must not run')") { message ->
                messages += message.get("msg_type").asString
            }

            assertTrue(handle.completion.isCompletedExceptionally)
            val failure = assertFailsWith<ExecutionException> { handle.completion.get() }
            assertTrue(failure.cause is KernelRequestAbortedException)
            assertEquals(listOf("execute_reply"), messages)
            assertEquals(listOf(handle.msgId), failures.map { it.first })
            assertTrue(failures.single().second is KernelRequestAbortedException)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `legacy execute buffers messages until callback registration`() {
        val transportFactory = FakeTransportFactory()
        val parent = Disposer.newDisposable()
        val manager = manualManager(parent, transportFactory)
        val messages = mutableListOf<String>()

        try {
            manager.start()
            transportFactory.transport.onShellSend = { _, _, msgId ->
                transportFactory.transport.emit(streamMessage(msgId, "early"))
                transportFactory.transport.emit(statusMessage(msgId, "idle"))
            }

            val msgId = manager.sendExecuteRequest("pass")
            manager.registerCallback(msgId) { message ->
                messages += message.get("msg_type").asString
                if (message.get("msg_type").asString == "status") {
                    manager.removeCallback(msgId)
                }
            }

            assertEquals(listOf("stream", "status"), messages)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `disconnect fails pending request and publishes failure`() {
        val transportFactory = FakeTransportFactory()
        val parent = Disposer.newDisposable()
        val manager = manualManager(parent, transportFactory)
        val failures = mutableListOf<String>()

        try {
            manager.onRequestFailed = { msgId, _ -> failures += msgId }
            manager.start()
            val handle = manager.execute("while True: pass") { }

            transportFactory.transport.fail(
                KernelDisconnectedException("heartbeat lost")
            )

            assertTrue(handle.completion.isCompletedExceptionally)
            assertFailsWith<ExecutionException> { handle.completion.get() }
            assertEquals(listOf(handle.msgId), failures)
            assertEquals(KernelStatus.DISCONNECTED, manager.status)
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun manualManager(
        parent: com.intellij.openapi.Disposable,
        transportFactory: FakeTransportFactory
    ): KernelManager {
        return KernelManager(
            KernelTarget.Manual(connectionInfo()),
            parent,
            testConfig(),
            transportFactory,
            neverLaunchProcess()
        )
    }

    private fun neverLaunchProcess() = KernelProcessLauncher { _, _, _ ->
        error("Attached target must not launch a process")
    }

    private fun testConfig(startupTimeoutMillis: Long = 250) = KernelManagerConfig(
        startupTimeoutMillis = startupTimeoutMillis,
        heartbeatIntervalMillis = 50,
        heartbeatTimeoutMillis = 50,
        shutdownTimeoutMillis = 20,
        transportCloseTimeoutMillis = 100
    )

    private fun connectionInfo() = KernelConnectionInfo(
        ip = "127.0.0.1",
        transport = "tcp",
        shellPort = 57503,
        iopubPort = 40885,
        stdinPort = 52597,
        controlPort = 50160,
        hbPort = 42540,
        key = "secret"
    ).validated()

    private fun statusMessage(parentMsgId: String, state: String): JupyterMessage {
        return message(parentMsgId, "status", JsonObject().apply {
            addProperty("execution_state", state)
        })
    }

    private fun streamMessage(parentMsgId: String, text: String): JupyterMessage {
        return message(parentMsgId, "stream", JsonObject().apply {
            addProperty("name", "stdout")
            addProperty("text", text)
        })
    }

    private fun executeReply(parentMsgId: String, status: String): JupyterMessage {
        return message(
            parentMsgId,
            "execute_reply",
            JsonObject().apply { addProperty("status", status) },
            JupyterChannel.SHELL
        )
    }

    private fun message(
        parentMsgId: String,
        msgType: String,
        content: JsonObject,
        channel: JupyterChannel = JupyterChannel.IOPUB
    ): JupyterMessage {
        return JupyterMessage(
            channel = channel,
            msgType = msgType,
            header = JsonObject().apply {
                addProperty("msg_id", "reply-$parentMsgId")
                addProperty("msg_type", msgType)
            },
            parentHeader = JsonObject().apply { addProperty("msg_id", parentMsgId) },
            metadata = JsonObject(),
            content = content
        )
    }

    private class FakeTransportFactory(
        private val autoReady: Boolean = true,
        private val readyFuture: CompletableFuture<Unit> = CompletableFuture()
    ) : KernelTransportFactory {
        lateinit var transport: FakeTransport

        override fun create(
            connectionInfo: KernelConnectionInfo,
            config: KernelManagerConfig,
            listener: KernelTransportListener
        ): KernelTransport {
            transport = FakeTransport(listener, autoReady, readyFuture)
            return transport
        }
    }

    private class SequencedTransportFactory(
        private val firstReady: CompletableFuture<Unit>
    ) : KernelTransportFactory {
        private val createCount = AtomicInteger()

        override fun create(
            connectionInfo: KernelConnectionInfo,
            config: KernelManagerConfig,
            listener: KernelTransportListener
        ): KernelTransport {
            val ready = if (createCount.getAndIncrement() == 0) {
                firstReady
            } else {
                CompletableFuture<Unit>().apply { complete(Unit) }
            }
            return FakeTransport(listener, autoReady = false, ready = ready)
        }
    }

    private class FakeTransport(
        private val listener: KernelTransportListener,
        private val autoReady: Boolean,
        override val ready: CompletableFuture<Unit>
    ) : KernelTransport {
        val shutdownCount = AtomicInteger()
        val closeCount = AtomicInteger()
        var onShellSend: ((String, JsonObject, String) -> Unit)? = null

        override fun start() {
            if (autoReady) ready.complete(Unit)
        }

        override fun sendShell(msgType: String, content: JsonObject, msgId: String) {
            onShellSend?.invoke(msgType, content, msgId)
        }

        override fun sendControl(msgType: String, content: JsonObject, msgId: String) {
        }

        override fun requestShutdown(): CompletableFuture<Unit> {
            shutdownCount.incrementAndGet()
            return CompletableFuture.completedFuture(Unit)
        }

        override fun fail(cause: Throwable) {
            listener.onDisconnected(cause)
        }

        override fun close() {
            if (closeCount.incrementAndGet() == 1 && !ready.isDone) {
                ready.completeExceptionally(KernelDisconnectedException("closed"))
            }
        }

        fun emit(message: JupyterMessage) {
            listener.onMessage(message)
        }
    }

    private class BlockingStatusReadyFuture : CompletableFuture<Unit>() {
        private val blockNextCheck = AtomicBoolean(false)
        private val checkEntered = CountDownLatch(1)
        private val releaseCheck = CountDownLatch(1)

        fun blockNextStatusCheck() {
            blockNextCheck.set(true)
        }

        fun awaitStatusCheck(): Boolean = checkEntered.await(1, TimeUnit.SECONDS)

        fun releaseStatusCheck() {
            releaseCheck.countDown()
        }

        override fun isDone(): Boolean {
            if (blockNextCheck.compareAndSet(true, false)) {
                checkEntered.countDown()
                releaseCheck.await(1, TimeUnit.SECONDS)
            }
            return super.isDone()
        }
    }

    private class GatedFailedReadyFuture : CompletableFuture<Unit>() {
        private val getEntered = CountDownLatch(1)
        private val releaseGet = CountDownLatch(1)

        fun awaitGet(): Boolean = getEntered.await(1, TimeUnit.SECONDS)

        fun releaseGet() {
            releaseGet.countDown()
        }

        override fun get(timeout: Long, unit: TimeUnit): Unit {
            getEntered.countDown()
            releaseGet.await(1, TimeUnit.SECONDS)
            return super.get(timeout, unit)
        }
    }

    private class FakeManagedProcess : ManagedKernelProcess {
        private val destroyed = AtomicBoolean(false)
        val destroyCount = AtomicInteger()

        override val isAlive: Boolean get() = !destroyed.get()

        override fun destroy() {
            if (destroyed.compareAndSet(false, true)) destroyCount.incrementAndGet()
        }
    }
}
