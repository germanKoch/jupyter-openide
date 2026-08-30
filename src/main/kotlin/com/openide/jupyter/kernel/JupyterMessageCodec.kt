package com.openide.jupyter.kernel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

enum class JupyterChannel {
    SHELL,
    IOPUB,
    CONTROL
}

data class JupyterMessage(
    val channel: JupyterChannel,
    val msgType: String,
    val header: JsonObject,
    val parentHeader: JsonObject,
    val metadata: JsonObject,
    val content: JsonObject,
    val buffers: List<ByteArray> = emptyList()
) {
    val msgId: String? get() = header.get("msg_id")?.asString
    val parentMsgId: String? get() = parentHeader.get("msg_id")?.asString

    fun toLegacyJson(): JsonObject = JsonObject().apply {
        addProperty("msg_type", msgType)
        add("content", content)
        add("header", header)
        add("parent_header", parentHeader)
        add("metadata", metadata)
    }
}

/** Codec for the classic multipart Jupyter wire envelope. */
object JupyterMessageCodec {
    private const val DELIMITER = "<IDS|MSG>"
    private val delimiterBytes = DELIMITER.toByteArray(StandardCharsets.US_ASCII)
    private val gson = Gson()

    fun encode(
        connectionInfo: KernelConnectionInfo,
        session: String,
        msgType: String,
        content: JsonObject,
        msgId: String,
        protocolVersion: String = "5.4",
        parentHeader: JsonObject = JsonObject(),
        metadata: JsonObject = JsonObject(),
        buffers: List<ByteArray> = emptyList()
    ): List<ByteArray> {
        val header = JsonObject().apply {
            addProperty("msg_id", msgId)
            addProperty("session", session)
            addProperty("username", "jupyter-openide")
            addProperty("date", java.time.Instant.now().toString())
            addProperty("msg_type", msgType)
            addProperty("version", protocolVersion)
        }
        val messageParts = listOf(
            gson.toJson(header).toByteArray(StandardCharsets.UTF_8),
            gson.toJson(parentHeader).toByteArray(StandardCharsets.UTF_8),
            gson.toJson(metadata).toByteArray(StandardCharsets.UTF_8),
            gson.toJson(content).toByteArray(StandardCharsets.UTF_8)
        )
        val signature = MessageSigner.sign(
            connectionInfo.key,
            connectionInfo.signatureScheme,
            *messageParts.toTypedArray()
        ).toByteArray(StandardCharsets.US_ASCII)
        return listOf(delimiterBytes, signature) + messageParts + buffers
    }

    fun decode(
        connectionInfo: KernelConnectionInfo,
        channel: JupyterChannel,
        frames: List<ByteArray>
    ): JupyterMessage {
        val delimiterIndex = frames.indexOfFirst { it.contentEquals(delimiterBytes) }
        require(delimiterIndex >= 0) { "Malformed Jupyter message: missing $DELIMITER delimiter" }
        require(frames.size >= delimiterIndex + 6) {
            "Malformed Jupyter message: expected signature and four JSON frames"
        }

        val signature = frames[delimiterIndex + 1]
        val signedParts = arrayOf(
            frames[delimiterIndex + 2],
            frames[delimiterIndex + 3],
            frames[delimiterIndex + 4],
            frames[delimiterIndex + 5]
        )
        require(
            MessageSigner.verify(
                connectionInfo.key,
                connectionInfo.signatureScheme,
                signature,
                *signedParts
            )
        ) { "Invalid Jupyter message signature" }

        val header = parseObject("header", signedParts[0])
        val parentHeader = parseObject("parent_header", signedParts[1])
        val metadata = parseObject("metadata", signedParts[2])
        val content = parseObject("content", signedParts[3])
        val msgTypeElement = header.get("msg_type")
            ?: throw IllegalArgumentException("Malformed Jupyter message: header has no msg_type")
        require(msgTypeElement.isJsonPrimitive && msgTypeElement.asJsonPrimitive.isString) {
            "Malformed Jupyter message: header msg_type must be a string"
        }
        val msgType = msgTypeElement.asString

        return JupyterMessage(
            channel = channel,
            msgType = msgType,
            header = header,
            parentHeader = parentHeader,
            metadata = metadata,
            content = content,
            buffers = frames.drop(delimiterIndex + 6)
        )
    }

    private fun parseObject(name: String, bytes: ByteArray): JsonObject {
        return try {
            JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed Jupyter $name JSON", e)
        }
    }
}
