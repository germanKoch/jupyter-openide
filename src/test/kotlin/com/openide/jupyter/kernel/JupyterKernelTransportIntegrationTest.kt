package com.openide.jupyter.kernel

import com.google.gson.JsonObject
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JupyterKernelTransportIntegrationTest {

    @Test
    fun `readiness requires matching kernel info reply and heartbeat`() {
        val info = connectionInfo()
        FakeKernel(
            info = info,
            correctKernelInfoDelayMillis = 200,
            heartbeatReplies = Int.MAX_VALUE
        ).use { kernel ->
            kernel.start()
            val disconnected = AtomicReference<Throwable?>()
            val transport = JupyterKernelTransport(
                info,
                testConfig(),
                listener(disconnected)
            )

            try {
                transport.start()

                assertFailsWith<TimeoutException> {
                    transport.ready.get(75, TimeUnit.MILLISECONDS)
                }
                assertFalse(transport.ready.isDone)

                transport.ready.get(2, TimeUnit.SECONDS)
                assertTrue(transport.ready.isDone)
                assertFalse(transport.ready.isCompletedExceptionally)
                assertTrue(disconnected.get() == null)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `missing heartbeat disconnects without sending another req`() {
        val info = connectionInfo()
        FakeKernel(
            info = info,
            correctKernelInfoDelayMillis = 0,
            heartbeatReplies = 1
        ).use { kernel ->
            kernel.start()
            val disconnected = AtomicReference<Throwable?>()
            val disconnectSignal = CountDownLatch(1)
            val transport = JupyterKernelTransport(
                info,
                testConfig(),
                object : KernelTransportListener {
                    override fun onMessage(message: JupyterMessage) = Unit

                    override fun onDisconnected(cause: Throwable) {
                        disconnected.set(cause)
                        disconnectSignal.countDown()
                    }
                }
            )

            try {
                transport.start()
                transport.ready.get(2, TimeUnit.SECONDS)

                assertTrue(disconnectSignal.await(2, TimeUnit.SECONDS))
                val cause = assertNotNull(disconnected.get())
                assertTrue(cause.message.orEmpty().contains("heartbeat", ignoreCase = true))
                assertTrue(kernel.heartbeatRequestCount.get() == 2)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `readiness uses control while shell is blocked by an execution`() {
        val info = connectionInfo()
        FakeKernel(
            info = info,
            correctKernelInfoDelayMillis = 0,
            heartbeatReplies = Int.MAX_VALUE,
            serviceShell = false
        ).use { kernel ->
            kernel.start()
            val disconnected = AtomicReference<Throwable?>()
            val transport = JupyterKernelTransport(
                info,
                testConfig(),
                listener(disconnected)
            )

            try {
                transport.start()
                transport.ready.get(2, TimeUnit.SECONDS)

                assertFalse(transport.ready.isCompletedExceptionally)
                assertEquals(0, kernel.shellKernelInfoRequestCount.get())
                assertEquals(1, kernel.controlKernelInfoRequestCount.get())
                assertTrue(disconnected.get() == null)
            } finally {
                transport.close()
            }
        }
    }

    private fun listener(disconnected: AtomicReference<Throwable?>) =
        object : KernelTransportListener {
            override fun onMessage(message: JupyterMessage) = Unit

            override fun onDisconnected(cause: Throwable) {
                disconnected.set(cause)
            }
        }

    private fun testConfig() = KernelManagerConfig(
        startupTimeoutMillis = 2_000,
        heartbeatIntervalMillis = 30,
        heartbeatTimeoutMillis = 100,
        shutdownTimeoutMillis = 50,
        transportCloseTimeoutMillis = 500
    )

    private fun connectionInfo(): KernelConnectionInfo {
        val reservations = List(5) {
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            }
        }
        val ports = try {
            reservations.map { it.localPort }
        } finally {
            reservations.forEach { it.close() }
        }
        return KernelConnectionInfo(
            ip = "127.0.0.1",
            transport = "tcp",
            shellPort = ports[0],
            iopubPort = ports[1],
            stdinPort = ports[2],
            controlPort = ports[3],
            hbPort = ports[4],
            key = "integration-secret"
        ).validated()
    }

    private class FakeKernel(
        private val info: KernelConnectionInfo,
        private val correctKernelInfoDelayMillis: Long,
        private val heartbeatReplies: Int,
        private val serviceShell: Boolean = true
    ) : AutoCloseable {
        val heartbeatRequestCount = AtomicInteger()
        val shellKernelInfoRequestCount = AtomicInteger()
        val controlKernelInfoRequestCount = AtomicInteger()

        private val running = AtomicBoolean(true)
        private val started = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread(::run, "fake-jupyter-kernel").apply { isDaemon = true }

        fun start() {
            thread.start()
            check(started.await(2, TimeUnit.SECONDS)) { "Fake kernel did not bind its sockets" }
            failure.get()?.let { throw AssertionError("Fake kernel failed to start", it) }
        }

        private fun run() {
            var context: ZContext? = null
            var poller: ZMQ.Poller? = null
            try {
                context = ZContext()
                val shell = context.createSocket(SocketType.ROUTER).apply {
                    check(bind(info.endpoint(info.shellPort)))
                }
                context.createSocket(SocketType.PUB).apply {
                    check(bind(info.endpoint(info.iopubPort)))
                }
                val control = context.createSocket(SocketType.ROUTER).apply {
                    check(bind(info.endpoint(info.controlPort)))
                }
                val heartbeat = context.createSocket(SocketType.REP).apply {
                    check(bind(info.endpoint(info.hbPort)))
                }
                poller = context.createPoller(3)
                val shellIndex = poller.register(shell, ZMQ.Poller.POLLIN)
                val controlIndex = poller.register(control, ZMQ.Poller.POLLIN)
                val heartbeatIndex = poller.register(heartbeat, ZMQ.Poller.POLLIN)
                started.countDown()

                var requestIdentity: ByteArray? = null
                var requestHeader: JsonObject? = null
                var requestSocket: ZMQ.Socket? = null
                var correctReplyAtNanos = Long.MAX_VALUE

                while (running.get()) {
                    poller.poll(10)
                    if (poller.pollin(shellIndex)) {
                        val frames = receiveMultipart(shell)
                        shellKernelInfoRequestCount.incrementAndGet()
                        if (serviceShell) {
                            val request = handleKernelInfoRequest(
                                shell,
                                JupyterChannel.SHELL,
                                frames
                            )
                            requestIdentity = request.first
                            requestHeader = request.second
                            requestSocket = shell
                            correctReplyAtNanos = scheduleReply(
                                shell,
                                requestIdentity,
                                requestHeader
                            )
                        }
                    }

                    if (poller.pollin(controlIndex)) {
                        val frames = receiveMultipart(control)
                        controlKernelInfoRequestCount.incrementAndGet()
                        val request = handleKernelInfoRequest(
                            control,
                            JupyterChannel.CONTROL,
                            frames
                        )
                        requestIdentity = request.first
                        requestHeader = request.second
                        requestSocket = control
                        correctReplyAtNanos = scheduleReply(
                            control,
                            requestIdentity,
                            requestHeader
                        )
                    }

                    if (
                        requestSocket != null &&
                        requestIdentity != null &&
                        requestHeader != null &&
                        System.nanoTime() >= correctReplyAtNanos
                    ) {
                        sendKernelInfoReply(requestSocket, requestIdentity, requestHeader)
                        correctReplyAtNanos = Long.MAX_VALUE
                    }

                    if (poller.pollin(heartbeatIndex)) {
                        val payload = heartbeat.recv(0)
                            ?: error("Fake kernel lost a heartbeat frame")
                        val requestNumber = heartbeatRequestCount.incrementAndGet()
                        if (requestNumber <= heartbeatReplies) {
                            check(heartbeat.send(payload, 0))
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) failure.set(t)
                started.countDown()
            } finally {
                try {
                    poller?.close()
                } catch (_: Exception) {
                }
                try {
                    context?.close()
                } catch (_: Exception) {
                }
            }
        }

        private fun handleKernelInfoRequest(
            socket: ZMQ.Socket,
            channel: JupyterChannel,
            frames: List<ByteArray>
        ): Pair<ByteArray, JsonObject> {
            val message = JupyterMessageCodec.decode(info, channel, frames)
            check(message.msgType == "kernel_info_request") {
                "Unexpected ${message.msgType} on the readiness channel"
            }
            return frames.first().copyOf() to message.header.deepCopy()
        }

        private fun scheduleReply(
            socket: ZMQ.Socket,
            identity: ByteArray,
            requestHeader: JsonObject
        ): Long {
            if (correctKernelInfoDelayMillis <= 0) {
                sendKernelInfoReply(socket, identity, requestHeader)
                return Long.MAX_VALUE
            }
            sendKernelInfoReply(
                socket,
                identity,
                JsonObject().apply { addProperty("msg_id", "not-the-request") }
            )
            return System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(correctKernelInfoDelayMillis)
        }

        private fun sendKernelInfoReply(
            socket: ZMQ.Socket,
            identity: ByteArray,
            parentHeader: JsonObject
        ) {
            check(socket.send(identity, ZMQ.SNDMORE))
            val frames = JupyterMessageCodec.encode(
                connectionInfo = info,
                session = "fake-kernel-session",
                msgType = "kernel_info_reply",
                content = JsonObject(),
                msgId = UUID.randomUUID().toString(),
                parentHeader = parentHeader
            )
            frames.forEachIndexed { index, frame ->
                check(socket.send(frame, if (index < frames.lastIndex) ZMQ.SNDMORE else 0))
            }
        }

        private fun receiveMultipart(socket: ZMQ.Socket): List<ByteArray> {
            val frames = mutableListOf(
                socket.recv(0) ?: error("Fake kernel lost the first message frame")
            )
            while (socket.hasReceiveMore()) {
                frames += socket.recv(0) ?: error("Fake kernel lost a multipart frame")
            }
            return frames
        }

        override fun close() {
            running.set(false)
            thread.join(2_000)
            check(!thread.isAlive) { "Fake kernel thread did not stop" }
            failure.get()?.let { throw AssertionError("Fake kernel failed", it) }
        }
    }
}
