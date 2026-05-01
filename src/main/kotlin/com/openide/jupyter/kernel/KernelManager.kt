package com.openide.jupyter.kernel

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KernelManager(
    val pythonPath: String,
    private val parentDisposable: Disposable
) : Disposable {

    var status: KernelStatus = KernelStatus.DISCONNECTED
        private set(value) {
            field = value
            onStatusChanged?.invoke(value)
        }

    var onStatusChanged: ((KernelStatus) -> Unit)? = null
    var onMessage: ((String, JsonObject) -> Unit)? = null

    private var processHandler: OSProcessHandler? = null
    private var connectionFile: File? = null
    private var connectionInfo: KernelConnectionInfo? = null
    private var zmqContext: ZContext? = null
    private var shellSocket: ZMQ.Socket? = null
    private var iopubSocket: ZMQ.Socket? = null
    private var controlSocket: ZMQ.Socket? = null
    private var heartbeatSocket: ZMQ.Socket? = null
    private var iopubThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val session = UUID.randomUUID().toString()
    private val messageCallbacks = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val gson = Gson()
    @Volatile private var running = false

    init {
        Disposer.register(parentDisposable, this)
    }

    fun start() {
        if (status != KernelStatus.DISCONNECTED) return
        status = KernelStatus.STARTING

        try {
            val connFile = createConnectionFile()
            connectionFile = connFile
            connectionInfo = parseConnectionFile(connFile)

            val cmd = GeneralCommandLine(
                pythonPath, "-m", "ipykernel_launcher", "-f", connFile.absolutePath
            )
            cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

            val handler = OSProcessHandler(cmd)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    if (running) {
                        running = false
                        status = KernelStatus.DISCONNECTED
                    }
                }
            })
            handler.startNotify()
            processHandler = handler

            connectZmq()
            running = true
            startIopubListener()
            startHeartbeat()
            waitForReady()

        } catch (e: Exception) {
            status = KernelStatus.DISCONNECTED
            cleanup()
            throw e
        }
    }

    fun stop() {
        if (status == KernelStatus.DISCONNECTED) return
        running = false

        try {
            sendControlMessage("shutdown_request", """{"restart": false}""")
            Thread.sleep(500)
        } catch (_: Exception) {}

        cleanup()
        status = KernelStatus.DISCONNECTED
    }

    fun sendExecuteRequest(code: String): String {
        val msgId = UUID.randomUUID().toString()
        val content = JsonObject().apply {
            addProperty("code", code)
            addProperty("silent", false)
            addProperty("store_history", true)
            addProperty("allow_stdin", false)
            addProperty("stop_on_error", true)
        }
        sendShellMessage("execute_request", content, msgId)
        return msgId
    }

    fun interrupt() {
        sendControlMessage("interrupt_request", "{}")
    }

    fun registerCallback(msgId: String, callback: (JsonObject) -> Unit) {
        messageCallbacks[msgId] = callback
    }

    fun removeCallback(msgId: String) {
        messageCallbacks.remove(msgId)
    }

    private fun createConnectionFile(): File {
        val random = SecureRandom()
        val key = ByteArray(32).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        val ports = (0..4).map { findFreePort() }
        val connInfo = JsonObject().apply {
            addProperty("ip", "127.0.0.1")
            addProperty("transport", "tcp")
            addProperty("shell_port", ports[0])
            addProperty("iopub_port", ports[1])
            addProperty("stdin_port", ports[2])
            addProperty("control_port", ports[3])
            addProperty("hb_port", ports[4])
            addProperty("key", key)
            addProperty("signature_scheme", "hmac-sha256")
            addProperty("kernel_name", "python3")
        }
        val file = File.createTempFile("jupyter_kernel_", ".json")
        file.deleteOnExit()
        file.writeText(gson.toJson(connInfo))
        return file
    }

    private fun findFreePort(): Int {
        java.net.ServerSocket(0).use { return it.localPort }
    }

    private fun parseConnectionFile(file: File): KernelConnectionInfo {
        val json = JsonParser.parseString(file.readText()).asJsonObject
        return KernelConnectionInfo(
            ip = json.get("ip").asString,
            transport = json.get("transport").asString,
            shellPort = json.get("shell_port").asInt,
            iopubPort = json.get("iopub_port").asInt,
            stdinPort = json.get("stdin_port").asInt,
            controlPort = json.get("control_port").asInt,
            hbPort = json.get("hb_port").asInt,
            key = json.get("key").asString,
            signatureScheme = json.get("signature_scheme").asString,
            kernelName = json.get("kernel_name").asString
        )
    }

    private fun connectZmq() {
        val info = connectionInfo ?: throw IllegalStateException("No connection info")
        val ctx = ZContext()
        zmqContext = ctx

        shellSocket = ctx.createSocket(ZMQ.DEALER).apply {
            identity = session.toByteArray()
            connect("${info.transport}://${info.ip}:${info.shellPort}")
        }
        iopubSocket = ctx.createSocket(ZMQ.SUB).apply {
            subscribe(ByteArray(0))
            connect("${info.transport}://${info.ip}:${info.iopubPort}")
        }
        controlSocket = ctx.createSocket(ZMQ.DEALER).apply {
            identity = session.toByteArray()
            connect("${info.transport}://${info.ip}:${info.controlPort}")
        }
        heartbeatSocket = ctx.createSocket(ZMQ.REQ).apply {
            connect("${info.transport}://${info.ip}:${info.hbPort}")
        }
    }

    private fun sendShellMessage(msgType: String, content: JsonObject, msgId: String = UUID.randomUUID().toString()) {
        val socket = shellSocket ?: return
        val info = connectionInfo ?: return
        sendMessage(socket, info.key, msgType, content, msgId)
    }

    private fun sendControlMessage(msgType: String, contentJson: String) {
        val socket = controlSocket ?: return
        val info = connectionInfo ?: return
        val content = JsonParser.parseString(contentJson).asJsonObject
        sendMessage(socket, info.key, msgType, content)
    }

    private fun sendMessage(socket: ZMQ.Socket, key: String, msgType: String, content: JsonObject, msgId: String = UUID.randomUUID().toString()) {
        val header = JsonObject().apply {
            addProperty("msg_id", msgId)
            addProperty("session", session)
            addProperty("username", "jupyter-openide")
            addProperty("date", java.time.Instant.now().toString())
            addProperty("msg_type", msgType)
            addProperty("version", "5.4")
        }
        val parentHeader = JsonObject()
        val metadata = JsonObject()

        val headerStr = gson.toJson(header)
        val parentStr = gson.toJson(parentHeader)
        val metadataStr = gson.toJson(metadata)
        val contentStr = gson.toJson(content)

        val hmac = MessageSigner.sign(key, headerStr, parentStr, metadataStr, contentStr)

        synchronized(socket) {
            socket.sendMore("<IDS|MSG>".toByteArray())
            socket.sendMore(hmac.toByteArray())
            socket.sendMore(headerStr.toByteArray())
            socket.sendMore(parentStr.toByteArray())
            socket.sendMore(metadataStr.toByteArray())
            socket.send(contentStr.toByteArray())
        }
    }

    private fun startIopubListener() {
        iopubThread = Thread({
            val socket = iopubSocket ?: return@Thread
            while (running) {
                try {
                    val frames = mutableListOf<ByteArray>()
                    socket.receiveTimeOut = 1000
                    val first = socket.recv(0) ?: continue
                    frames.add(first)
                    while (socket.hasReceiveMore()) {
                        frames.add(socket.recv(0))
                    }
                    processIopubMessage(frames)
                } catch (_: org.zeromq.ZMQException) {
                    if (!running) break
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "jupyter-iopub-listener").apply {
            isDaemon = true
            start()
        }
    }

    private fun processIopubMessage(frames: List<ByteArray>) {
        val delimiterIdx = frames.indexOfFirst { String(it) == "<IDS|MSG>" }
        if (delimiterIdx < 0 || frames.size < delimiterIdx + 6) return

        val headerJson = String(frames[delimiterIdx + 2], StandardCharsets.UTF_8)
        val parentHeaderJson = String(frames[delimiterIdx + 3], StandardCharsets.UTF_8)
        val contentJson = String(frames[delimiterIdx + 5], StandardCharsets.UTF_8)

        val header = JsonParser.parseString(headerJson).asJsonObject
        val parentHeader = JsonParser.parseString(parentHeaderJson).asJsonObject
        val content = JsonParser.parseString(contentJson).asJsonObject
        val msgType = header.get("msg_type")?.asString ?: return

        when (msgType) {
            "status" -> {
                val state = content.get("execution_state")?.asString
                when (state) {
                    "idle" -> status = KernelStatus.IDLE
                    "busy" -> status = KernelStatus.BUSY
                }
            }
        }

        val parentMsgId = parentHeader.get("msg_id")?.asString
        if (parentMsgId != null) {
            messageCallbacks[parentMsgId]?.invoke(
                JsonObject().apply {
                    addProperty("msg_type", msgType)
                    add("content", content)
                    add("header", header)
                    add("parent_header", parentHeader)
                }
            )
        }

        onMessage?.invoke(msgType, JsonObject().apply {
            add("content", content)
            add("header", header)
            add("parent_header", parentHeader)
        })
    }

    private fun startHeartbeat() {
        heartbeatThread = Thread({
            val socket = heartbeatSocket ?: return@Thread
            while (running) {
                try {
                    socket.send("ping".toByteArray())
                    socket.receiveTimeOut = 5000
                    socket.recv(0)
                    Thread.sleep(5000)
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "jupyter-heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    private fun waitForReady() {
        val startTime = System.currentTimeMillis()
        while (status == KernelStatus.STARTING && System.currentTimeMillis() - startTime < 10000) {
            Thread.sleep(200)
        }
        if (status == KernelStatus.STARTING) {
            status = KernelStatus.IDLE
        }
    }

    private fun cleanup() {
        running = false
        iopubThread?.interrupt()
        heartbeatThread?.interrupt()
        try { shellSocket?.close() } catch (_: Exception) {}
        try { iopubSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { heartbeatSocket?.close() } catch (_: Exception) {}
        try { zmqContext?.close() } catch (_: Exception) {}
        shellSocket = null
        iopubSocket = null
        controlSocket = null
        heartbeatSocket = null
        zmqContext = null
        processHandler?.destroyProcess()
        processHandler = null
        connectionFile?.delete()
        connectionFile = null
        connectionInfo = null
        messageCallbacks.clear()
    }

    override fun dispose() {
        stop()
    }
}

data class KernelConnectionInfo(
    val ip: String,
    val transport: String,
    val shellPort: Int,
    val iopubPort: Int,
    val stdinPort: Int,
    val controlPort: Int,
    val hbPort: Int,
    val key: String,
    val signatureScheme: String,
    val kernelName: String
)
