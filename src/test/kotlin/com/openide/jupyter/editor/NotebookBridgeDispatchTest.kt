package com.openide.jupyter.editor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotebookBridgeDispatchTest {

    @Test
    fun `browser mutations run on EDT in source before save order`() {
        val completed = CountDownLatch(1)
        val events = mutableListOf<String>()

        dispatchNotebookBridgeEvent {
            assertTrue(SwingUtilities.isEventDispatchThread())
            events += "source"
        }
        dispatchNotebookBridgeEvent {
            assertTrue(SwingUtilities.isEventDispatchThread())
            events += "save"
            completed.countDown()
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("source", "save"), events)
    }
}
