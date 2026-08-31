package com.openide.jupyter.actions

import org.w3c.dom.Element
import javax.swing.Icon
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolbarPresentationTest {

    @Test
    fun `toolbar actions have distinct resolvable icons`() {
        val descriptor = assertNotNull(
            javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml"),
            "The packaged plugin descriptor must be available to the test"
        )
        val document = descriptor.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val groups = document.getElementsByTagName("group")
        val toolbarGroup = (0 until groups.length)
            .map { groups.item(it) as Element }
            .single { it.getAttribute("id") == "jupyter.toolbar" }
        val actionNodes = toolbarGroup.getElementsByTagName("action")
        val iconsByAction = (0 until actionNodes.length)
            .map { actionNodes.item(it) as Element }
            .filter { it.parentNode === toolbarGroup }
            .associate { it.getAttribute("id") to it.getAttribute("icon") }

        val expected = mapOf(
            "jupyter.startKernel" to "AllIcons.Actions.Attach",
            "jupyter.stopKernel" to "AllIcons.Actions.Suspend",
            "jupyter.restartKernel" to "AllIcons.Actions.Restart",
            "jupyter.interruptKernel" to "AllIcons.Actions.Pause",
            "jupyter.runCell" to "AllIcons.Actions.Execute",
            "jupyter.runAllCells" to "AllIcons.Actions.RunAll",
            "jupyter.addCodeCell" to "AllIcons.General.Add",
            "jupyter.addMarkdownCell" to "AllIcons.Actions.AddFile",
            "jupyter.deleteCell" to "AllIcons.General.Delete"
        )

        assertEquals(expected, iconsByAction)
        assertEquals(expected.size, expected.values.toSet().size, "Toolbar icons must be distinct")
        expected.values.forEach { iconReference ->
            assertTrue(resolveAllIcon(iconReference) is Icon, "$iconReference must exist on the platform")
        }
    }

    private fun resolveAllIcon(reference: String): Any? {
        val path = reference.removePrefix("AllIcons.").split('.')
        assertEquals(2, path.size, "Unexpected AllIcons reference: $reference")
        val iconClass = Class.forName("com.intellij.icons.AllIcons\$${path[0]}")
        return iconClass.getField(path[1]).get(null)
    }
}
