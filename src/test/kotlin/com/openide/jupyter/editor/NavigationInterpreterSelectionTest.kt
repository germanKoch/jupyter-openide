package com.openide.jupyter.editor

import com.openide.jupyter.kernel.KernelOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationInterpreterSelectionTest {
    @Test
    fun `attached kernel without source interpreter never falls back to cached or detected Python`() {
        var detectionCalls = 0

        val selection = selectNavigationSourceInterpreter(
            kernelOwnership = KernelOwnership.ATTACHED,
            configuredSourceInterpreter = null,
            cachedPython = "/project/bin/python",
            detectPython = {
                detectionCalls++
                "/detected/bin/python"
            }
        )

        assertNull(selection.interpreter)
        assertEquals(0, detectionCalls)
        assertTrue(selection.unresolvedMessage.orEmpty().contains("attached kernel"))
        assertTrue(selection.unresolvedMessage.orEmpty().contains("source-root mapping"))
    }

    @Test
    fun `attached kernel source interpreter is authoritative`() {
        var detectionCalls = 0

        val selection = selectNavigationSourceInterpreter(
            kernelOwnership = KernelOwnership.ATTACHED,
            configuredSourceInterpreter = " /attached-mirror/bin/python ",
            cachedPython = "/project/bin/python",
            detectPython = {
                detectionCalls++
                "/detected/bin/python"
            }
        )

        assertEquals("/attached-mirror/bin/python", selection.interpreter)
        assertEquals(0, detectionCalls)
    }

    @Test
    fun `running owned kernel also uses its configured interpreter`() {
        val selection = selectNavigationSourceInterpreter(
            kernelOwnership = KernelOwnership.OWNED,
            configuredSourceInterpreter = "/kernel/bin/python",
            cachedPython = "/project/bin/python",
            detectPython = { error("Detector must not run for an active kernel") }
        )

        assertEquals("/kernel/bin/python", selection.interpreter)
    }

    @Test
    fun `disconnected editor may use cached then detected project Python`() {
        var detectionCalls = 0
        val cached = selectNavigationSourceInterpreter(
            kernelOwnership = null,
            configuredSourceInterpreter = null,
            cachedPython = "/cached/bin/python",
            detectPython = {
                detectionCalls++
                "/detected/bin/python"
            }
        )
        val detected = selectNavigationSourceInterpreter(
            kernelOwnership = null,
            configuredSourceInterpreter = null,
            cachedPython = null,
            detectPython = {
                detectionCalls++
                "/detected/bin/python"
            }
        )

        assertEquals("/cached/bin/python", cached.interpreter)
        assertEquals("/detected/bin/python", detected.interpreter)
        assertEquals(1, detectionCalls)
    }
}
