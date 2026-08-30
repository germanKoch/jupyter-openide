package com.openide.jupyter.kernel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KernelTargetSourceInterpreterTest {
    @Test
    fun `launched kernel uses its own interpreter for source navigation`() {
        val target = KernelTarget.Launch("/env/bin/python")

        assertEquals("/env/bin/python", target.sourceInterpreter)
    }

    @Test
    fun `attached targets preserve an explicit local source interpreter`() {
        val connectionFile =
            KernelTarget.ConnectionFile(File("connection.json"), "/mirror/bin/python")
        val manual = KernelTarget.Manual(connectionInfo(), "/other-mirror/bin/python")

        assertEquals("/mirror/bin/python", connectionFile.sourceInterpreter)
        assertEquals("/other-mirror/bin/python", manual.sourceInterpreter)
    }

    @Test
    fun `attached source interpreter can be explicitly omitted but not blank`() {
        assertNull(KernelTarget.ConnectionFile(File("connection.json")).sourceInterpreter)
        assertNull(KernelTarget.Manual(connectionInfo()).sourceInterpreter)
        assertFailsWith<IllegalArgumentException> {
            KernelTarget.ConnectionFile(File("connection.json"), "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            KernelTarget.Manual(connectionInfo(), "")
        }
    }

    private fun connectionInfo() = KernelConnectionInfo(
        ip = "127.0.0.1",
        transport = "tcp",
        shellPort = 1,
        iopubPort = 2,
        stdinPort = 3,
        controlPort = 4,
        hbPort = 5,
        key = ""
    )
}
