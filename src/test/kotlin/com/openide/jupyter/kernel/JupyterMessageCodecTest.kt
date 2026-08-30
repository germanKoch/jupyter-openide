package com.openide.jupyter.kernel

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JupyterMessageCodecTest {

    @Test
    fun `signed envelope round trips`() {
        val content = JsonObject().apply { addProperty("code", "1 + 1") }
        val frames = JupyterMessageCodec.encode(
            connectionInfo(),
            session = "session",
            msgType = "execute_request",
            content = content,
            msgId = "message-id",
            buffers = listOf(byteArrayOf(1, 2, 3))
        )

        val decoded = JupyterMessageCodec.decode(
            connectionInfo(),
            JupyterChannel.SHELL,
            frames
        )

        assertEquals("execute_request", decoded.msgType)
        assertEquals("message-id", decoded.msgId)
        assertEquals("1 + 1", decoded.content.get("code").asString)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.buffers.single())
    }

    @Test
    fun `tampered content and wrong key are rejected`() {
        val frames = JupyterMessageCodec.encode(
            connectionInfo(),
            session = "session",
            msgType = "kernel_info_request",
            content = JsonObject(),
            msgId = "message-id"
        )
        val tampered = frames.toMutableList().apply {
            this[5] = "{\"tampered\":true}".toByteArray()
        }

        assertFailsWith<IllegalArgumentException> {
            JupyterMessageCodec.decode(connectionInfo(), JupyterChannel.SHELL, tampered)
        }
        assertFailsWith<IllegalArgumentException> {
            JupyterMessageCodec.decode(
                connectionInfo().copy(key = "different"),
                JupyterChannel.SHELL,
                frames
            )
        }
    }

    @Test
    fun `signer uses constant envelope bytes and validates schemes`() {
        val parts = arrayOf("a".toByteArray(), "b".toByteArray())
        val signature = MessageSigner.sign("key", "hmac-sha256", *parts)

        assertTrue(
            MessageSigner.verify(
                "key",
                "hmac-sha256",
                signature.toByteArray(),
                *parts
            )
        )
        assertFailsWith<IllegalArgumentException> {
            MessageSigner.validateSignatureScheme("hmac-md5")
        }
        assertFailsWith<IllegalArgumentException> {
            MessageSigner.sign("", "hmac-md5", *parts)
        }
        assertFailsWith<IllegalArgumentException> {
            MessageSigner.verify("", "hmac-md5", byteArrayOf(), *parts)
        }
    }

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
}
