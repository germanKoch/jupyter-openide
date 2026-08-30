package com.openide.jupyter.kernel

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.lang.ArithmeticException

/**
 * The classic Jupyter connection-file fields used by this client.
 *
 * [kernelName] is deliberately optional: it is useful display metadata, but it
 * is not part of the required classic connection-file contract.
 */
data class KernelConnectionInfo(
    val ip: String,
    val transport: String,
    val shellPort: Int,
    val iopubPort: Int,
    val stdinPort: Int,
    val controlPort: Int,
    val hbPort: Int,
    val key: String,
    val signatureScheme: String = MessageSigner.DEFAULT_SIGNATURE_SCHEME,
    val kernelName: String? = null
) {
    fun validated(): KernelConnectionInfo {
        require(ip.isNotBlank()) { "Kernel connection IP must not be blank" }
        require(transport == "tcp") {
            "Unsupported Jupyter transport '$transport'; only classic TCP connections are supported"
        }

        val ports = linkedMapOf(
            "shell_port" to shellPort,
            "iopub_port" to iopubPort,
            "stdin_port" to stdinPort,
            "control_port" to controlPort,
            "hb_port" to hbPort
        )
        ports.forEach { (name, port) ->
            require(port in 1..65535) { "$name must be between 1 and 65535 (was $port)" }
        }
        require(ports.values.toSet().size == ports.size) {
            "Kernel connection ports must be distinct"
        }
        MessageSigner.validateSignatureScheme(signatureScheme)
        return this
    }

    fun endpoint(port: Int): String {
        require(port in 1..65535) { "Jupyter endpoint port must be between 1 and 65535" }
        val host = if (':' in ip && !ip.startsWith('[')) "[$ip]" else ip
        return "tcp://$host:$port"
    }
}

/** A small, IntelliJ-independent codec suitable for unit tests and manual input. */
object KernelConnectionInfoCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun parse(json: String): KernelConnectionInfo {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid Jupyter connection JSON", e)
        }

        require(
            !root.has("curve_publickey") &&
                !root.has("curve_secretkey") &&
                !root.has("curve_serverkey")
        ) {
            "CurveZMQ-encrypted Jupyter connections are not supported"
        }

        return KernelConnectionInfo(
            ip = root.requiredString("ip"),
            transport = root.requiredString("transport").lowercase(),
            shellPort = root.requiredInt("shell_port"),
            iopubPort = root.requiredInt("iopub_port"),
            stdinPort = root.requiredInt("stdin_port"),
            controlPort = root.requiredInt("control_port"),
            hbPort = root.requiredInt("hb_port"),
            key = root.optionalString("key") ?: "",
            signatureScheme = root.optionalString("signature_scheme")
                ?: MessageSigner.DEFAULT_SIGNATURE_SCHEME,
            kernelName = root.optionalString("kernel_name")?.takeIf { it.isNotBlank() }
        ).validated()
    }

    fun read(file: File): KernelConnectionInfo {
        require(file.isFile) { "Jupyter connection file does not exist: ${file.absolutePath}" }
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Invalid Jupyter connection file '${file.absolutePath}': ${e.message}",
                e
            )
        }
    }

    fun toJson(connectionInfo: KernelConnectionInfo): String {
        val info = connectionInfo.validated()
        val root = JsonObject().apply {
            addProperty("ip", info.ip)
            addProperty("transport", info.transport)
            addProperty("shell_port", info.shellPort)
            addProperty("iopub_port", info.iopubPort)
            addProperty("stdin_port", info.stdinPort)
            addProperty("control_port", info.controlPort)
            addProperty("hb_port", info.hbPort)
            addProperty("key", info.key)
            addProperty("signature_scheme", info.signatureScheme)
            info.kernelName?.let { addProperty("kernel_name", it) }
        }
        return gson.toJson(root)
    }

    fun write(file: File, connectionInfo: KernelConnectionInfo) {
        file.writeText(toJson(connectionInfo), Charsets.UTF_8)
    }

    private fun JsonObject.requiredString(name: String): String {
        return optionalString(name)
            ?: throw IllegalArgumentException("Missing required connection field '$name'")
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = get(name)
            ?: throw IllegalArgumentException("Missing required connection field '$name'")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("Connection field '$name' must be an integer")
        }
        return try {
            value.asBigDecimal.intValueExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Connection field '$name' must be an integer", e)
        }
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw IllegalArgumentException("Connection field '$name' must be a string")
        }
        return value.asString
    }
}
