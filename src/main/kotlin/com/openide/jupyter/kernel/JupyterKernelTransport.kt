package com.openide.jupyter.kernel

import com.google.gson.JsonObject
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class KernelDisconnectedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class KernelRequestAbortedException(message: String) : IllegalStateException(message)

internal interface KernelTransportListener {
    fun onMessage(message: JupyterMessage)
    fun onDisconnected(cause: Throwable)
}

internal interface KernelTransport {
    val ready: CompletableFuture<Unit>

    fun start()
    fun sendShell(msgType: String, content: JsonObject, msgId: String)
    fun sendControl(msgType: String, content: JsonObject, msgId: String = UUID.randomUUID().toString())
    fun requestShutdown(): CompletableFuture<Unit>
    fun fail(cause: Throwable)
    fun close()
}

internal fun interface KernelTransportFactory {
    fun create(
        connectionInfo: KernelConnectionInfo,
        config: KernelManagerConfig,
        listener: KernelTransportListener
    ): KernelTransport
}

internal object DefaultKernelTransportFactory : KernelTransportFactory {
    override fun create(
        connectionInfo: KernelConnectionInfo,
        config: KernelManagerConfig,
        listener: KernelTransportListener
    ): KernelTransport = JupyterKernelTransport(connectionInfo, config, listener)
}

/**
 * Owns every JeroMQ socket on one actor thread. Public callers only enqueue
 * immutable commands; socket creation, send, receive and close never migrate
 * between threads.
 */
internal class JupyterKernelTransport(
    private val connectionInfo: KernelConnectionInfo,
    private val config: KernelManagerConfig,
    private val listener: KernelTransportListener
) : KernelTransport {

    override val ready = CompletableFuture<Unit>()

    private val commands = LinkedBlockingQueue<Command>()
    private val started = AtomicBoolean(false)
    private val stopped = CompletableFuture<Unit>()
    private val externalFailure = AtomicReference<Throwable?>()
    private val session = UUID.randomUUID().toString()
    private val actorThread = Thread(::runActor, "jupyter-zmq-session").apply {
        isDaemon = true
    }

    override fun start() {
        check(started.compareAndSet(false, true)) { "Kernel transport has already been started" }
        actorThread.start()
    }

    override fun sendShell(msgType: String, content: JsonObject, msgId: String) {
        enqueue(Command.Send(JupyterChannel.SHELL, msgType, content.deepCopy(), msgId))
    }

    override fun sendControl(msgType: String, content: JsonObject, msgId: String) {
        enqueue(Command.Send(JupyterChannel.CONTROL, msgType, content.deepCopy(), msgId))
    }

    override fun requestShutdown(): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        enqueue(
            Command.Send(
                channel = JupyterChannel.CONTROL,
                msgType = "shutdown_request",
                content = JsonObject().apply { addProperty("restart", false) },
                msgId = UUID.randomUUID().toString(),
                replyCompletion = completion
            )
        )
        return completion
    }

    override fun fail(cause: Throwable) {
        externalFailure.compareAndSet(null, cause)
    }

    override fun close() {
        if (!started.get()) {
            ready.completeExceptionally(KernelDisconnectedException("Kernel transport closed before start"))
            stopped.complete(Unit)
            return
        }
        commands.offer(Command.Stop)
        if (Thread.currentThread() !== actorThread) {
            try {
                stopped.get(config.transportCloseTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // The actor owns its sockets and will still close them in its finally block.
            }
        }
    }

    private fun enqueue(command: Command) {
        if (!started.get() || stopped.isDone) {
            throw KernelDisconnectedException("Kernel transport is not running")
        }
        commands.offer(command)
    }

    private fun runActor() {
        var context: ZContext? = null
        var poller: ZMQ.Poller? = null
        val replyCompletions = mutableMapOf<String, CompletableFuture<Unit>>()
        var failure: Throwable? = null

        try {
            context = ZContext()
            val shell = context.createSocket(SocketType.DEALER).apply {
                identity = session.toByteArray(StandardCharsets.UTF_8)
                check(connect(connectionInfo.endpoint(connectionInfo.shellPort))) {
                    "Failed to connect Jupyter shell channel"
                }
            }
            val iopub = context.createSocket(SocketType.SUB).apply {
                subscribe(ByteArray(0))
                check(connect(connectionInfo.endpoint(connectionInfo.iopubPort))) {
                    "Failed to connect Jupyter IOPub channel"
                }
            }
            val control = context.createSocket(SocketType.DEALER).apply {
                identity = session.toByteArray(StandardCharsets.UTF_8)
                check(connect(connectionInfo.endpoint(connectionInfo.controlPort))) {
                    "Failed to connect Jupyter control channel"
                }
            }
            val heartbeat = context.createSocket(SocketType.REQ).apply {
                check(connect(connectionInfo.endpoint(connectionInfo.hbPort))) {
                    "Failed to connect Jupyter heartbeat channel"
                }
            }

            poller = context.createPoller(4)
            val shellIndex = poller.register(shell, ZMQ.Poller.POLLIN)
            val iopubIndex = poller.register(iopub, ZMQ.Poller.POLLIN)
            val controlIndex = poller.register(control, ZMQ.Poller.POLLIN)
            val heartbeatIndex = poller.register(heartbeat, ZMQ.Poller.POLLIN)

            val readinessMsgId = UUID.randomUUID().toString()
            // A running kernel processes shell requests serially, so a long user
            // execution can otherwise keep an attaching client in STARTING until
            // its startup timeout expires. The control channel has priority and
            // supports the same kernel_info request/reply exchange.
            sendJupyterMessage(control, "kernel_info_request", JsonObject(), readinessMsgId)

            var kernelInfoReceived = false
            var heartbeatConfirmed = false
            var heartbeatPayload: ByteArray? = null
            var heartbeatDeadlineNanos = 0L
            var nextHeartbeatNanos = System.nanoTime()
            var keepRunning = true

            while (keepRunning) {
                externalFailure.get()?.let { throw it }

                while (true) {
                    when (val command = commands.poll() ?: break) {
                        is Command.Send -> {
                            val socket = when (command.channel) {
                                JupyterChannel.SHELL -> shell
                                JupyterChannel.CONTROL -> control
                                JupyterChannel.IOPUB -> error("Cannot send on the IOPub SUB socket")
                            }
                            sendJupyterMessage(socket, command.msgType, command.content, command.msgId)
                            command.replyCompletion?.let { replyCompletions[command.msgId] = it }
                        }
                        Command.Stop -> keepRunning = false
                    }
                }
                if (!keepRunning) break

                val now = System.nanoTime()
                if (heartbeatPayload == null && now >= nextHeartbeatNanos) {
                    val payload = "jupyter-openide:${UUID.randomUUID()}"
                        .toByteArray(StandardCharsets.UTF_8)
                    check(heartbeat.send(payload, 0)) { "Failed to send Jupyter heartbeat" }
                    heartbeatPayload = payload
                    heartbeatDeadlineNanos = now + TimeUnit.MILLISECONDS.toNanos(
                        config.heartbeatTimeoutMillis
                    )
                } else if (heartbeatPayload != null && now >= heartbeatDeadlineNanos) {
                    throw KernelDisconnectedException(
                        "Jupyter kernel missed the heartbeat deadline"
                    )
                }

                poller.poll(ACTOR_POLL_MILLIS)

                if (poller.pollin(shellIndex)) {
                    receiveAvailable(shell).forEach { frames ->
                        val message = JupyterMessageCodec.decode(
                            connectionInfo,
                            JupyterChannel.SHELL,
                            frames
                        )
                        if (
                            message.msgType == "kernel_info_reply" &&
                            message.parentMsgId == readinessMsgId
                        ) {
                            kernelInfoReceived = true
                        }
                        dispatch(message, replyCompletions)
                    }
                }

                if (poller.pollin(iopubIndex)) {
                    receiveAvailable(iopub).forEach { frames ->
                        dispatch(
                            JupyterMessageCodec.decode(
                                connectionInfo,
                                JupyterChannel.IOPUB,
                                frames
                            ),
                            replyCompletions
                        )
                    }
                }

                if (poller.pollin(controlIndex)) {
                    receiveAvailable(control).forEach { frames ->
                        val message = JupyterMessageCodec.decode(
                            connectionInfo,
                            JupyterChannel.CONTROL,
                            frames
                        )
                        if (
                            message.msgType == "kernel_info_reply" &&
                            message.parentMsgId == readinessMsgId
                        ) {
                            kernelInfoReceived = true
                        }
                        dispatch(message, replyCompletions)
                    }
                }

                if (poller.pollin(heartbeatIndex)) {
                    val reply = heartbeat.recv(ZMQ.DONTWAIT)
                        ?: throw KernelDisconnectedException("Jupyter heartbeat reply disappeared")
                    val expected = heartbeatPayload
                        ?: throw KernelDisconnectedException("Unexpected Jupyter heartbeat reply")
                    if (!reply.contentEquals(expected)) {
                        throw KernelDisconnectedException("Jupyter kernel returned a mismatched heartbeat")
                    }
                    heartbeatPayload = null
                    heartbeatConfirmed = true
                    nextHeartbeatNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                        config.heartbeatIntervalMillis
                    )
                }

                if (kernelInfoReceived && heartbeatConfirmed) {
                    ready.complete(Unit)
                }
            }
        } catch (t: Throwable) {
            failure = if (t is KernelDisconnectedException) {
                t
            } else {
                KernelDisconnectedException("Jupyter transport failed: ${t.message}", t)
            }
            ready.completeExceptionally(failure)
        } finally {
            val terminal = failure ?: KernelDisconnectedException("Kernel transport stopped")
            if (!ready.isDone) ready.completeExceptionally(terminal)
            replyCompletions.values.forEach { it.completeExceptionally(terminal) }

            try {
                poller?.close()
            } catch (_: Exception) {
            }
            try {
                context?.close()
            } catch (_: Exception) {
            }
            stopped.complete(Unit)

            failure?.let {
                try {
                    listener.onDisconnected(it)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendJupyterMessage(
        socket: ZMQ.Socket,
        msgType: String,
        content: JsonObject,
        msgId: String
    ) {
        val frames = JupyterMessageCodec.encode(
            connectionInfo = connectionInfo,
            session = session,
            msgType = msgType,
            content = content,
            msgId = msgId
        )
        frames.forEachIndexed { index, frame ->
            val flags = if (index < frames.lastIndex) ZMQ.SNDMORE else 0
            check(socket.send(frame, flags)) { "Failed to send Jupyter $msgType frame" }
        }
    }

    private fun receiveAvailable(socket: ZMQ.Socket): List<List<ByteArray>> {
        val messages = mutableListOf<List<ByteArray>>()
        while (messages.size < MAX_MESSAGES_PER_CHANNEL) {
            val first = socket.recv(ZMQ.DONTWAIT) ?: break
            val frames = mutableListOf(first)
            while (socket.hasReceiveMore()) {
                frames += socket.recv(0)
                    ?: throw KernelDisconnectedException("Incomplete multipart Jupyter message")
            }
            messages += frames
        }
        return messages
    }

    private fun dispatch(
        message: JupyterMessage,
        replyCompletions: MutableMap<String, CompletableFuture<Unit>>
    ) {
        val parentId = message.parentMsgId
        if (parentId != null && message.msgType.endsWith("_reply")) {
            replyCompletions.remove(parentId)?.complete(Unit)
        }
        try {
            listener.onMessage(message)
        } catch (_: Exception) {
            // A UI callback must not terminate the socket owner.
        }
    }

    private sealed interface Command {
        data class Send(
            val channel: JupyterChannel,
            val msgType: String,
            val content: JsonObject,
            val msgId: String,
            val replyCompletion: CompletableFuture<Unit>? = null
        ) : Command

        data object Stop : Command
    }

    private companion object {
        const val ACTOR_POLL_MILLIS = 25L
        const val MAX_MESSAGES_PER_CHANNEL = 128
    }
}
