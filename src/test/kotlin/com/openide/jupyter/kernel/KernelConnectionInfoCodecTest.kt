package com.openide.jupyter.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernelConnectionInfoCodecTest {

    @Test
    fun `classic connection file does not require kernel name`() {
        val info = KernelConnectionInfoCodec.parse(
            """
            {
              "control_port": 50160,
              "shell_port": 57503,
              "transport": "tcp",
              "signature_scheme": "hmac-sha256",
              "stdin_port": 52597,
              "hb_port": 42540,
              "ip": "127.0.0.1",
              "iopub_port": 40885,
              "key": "secret"
            }
            """.trimIndent()
        )

        assertEquals(57503, info.shellPort)
        assertEquals("secret", info.key)
        assertNull(info.kernelName)
    }

    @Test
    fun `codec round trips optional kernel name`() {
        val original = connectionInfo(kernelName = "python3")

        val decoded = KernelConnectionInfoCodec.parse(
            KernelConnectionInfoCodec.toJson(original)
        )

        assertEquals(original, decoded)
        assertTrue(KernelConnectionInfoCodec.toJson(original).contains("kernel_name"))
        assertFalse(
            KernelConnectionInfoCodec.toJson(original.copy(kernelName = null))
                .contains("kernel_name")
        )
    }

    @Test
    fun `validation rejects duplicate and invalid ports`() {
        assertFailsWith<IllegalArgumentException> {
            connectionInfo().copy(iopubPort = 57503).validated()
        }
        assertFailsWith<IllegalArgumentException> {
            connectionInfo().copy(hbPort = 0).validated()
        }
    }

    @Test
    fun `validation rejects unsupported transport signature and curve fields`() {
        assertFailsWith<IllegalArgumentException> {
            connectionInfo().copy(transport = "ipc").validated()
        }
        assertFailsWith<IllegalArgumentException> {
            connectionInfo().copy(signatureScheme = "hmac-md5").validated()
        }
        assertFailsWith<IllegalArgumentException> {
            KernelConnectionInfoCodec.parse(
                """
                {
                  "ip": "127.0.0.1",
                  "transport": "tcp",
                  "shell_port": 57503,
                  "iopub_port": 40885,
                  "stdin_port": 52597,
                  "control_port": 50160,
                  "hb_port": 42540,
                  "key": "secret",
                  "signature_scheme": "hmac-sha256",
                  "curve_serverkey": "abc"
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `codec rejects coerced string and fractional port values`() {
        val validJson = KernelConnectionInfoCodec.toJson(connectionInfo())

        assertFailsWith<IllegalArgumentException> {
            KernelConnectionInfoCodec.parse(
                validJson.replace("\"shell_port\": 57503", "\"shell_port\": \"57503\"")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KernelConnectionInfoCodec.parse(
                validJson.replace("\"shell_port\": 57503", "\"shell_port\": 57503.5")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KernelConnectionInfoCodec.parse(
                validJson.replace("\"transport\": \"tcp\"", "\"transport\": true")
            )
        }
    }

    @Test
    fun `endpoint formats ipv6 addresses`() {
        assertEquals("tcp://[::1]:57503", connectionInfo().copy(ip = "::1").endpoint(57503))
    }

    private fun connectionInfo(kernelName: String? = null) = KernelConnectionInfo(
        ip = "127.0.0.1",
        transport = "tcp",
        shellPort = 57503,
        iopubPort = 40885,
        stdinPort = 52597,
        controlPort = 50160,
        hbPort = 42540,
        key = "secret",
        kernelName = kernelName
    )
}
