package com.openide.jupyter.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.openide.jupyter.kernel.KernelConnectionInfoCodec
import com.openide.jupyter.kernel.KernelTarget
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/** Lets a notebook either own a newly launched kernel or attach to an existing one. */
class KernelConnectionDialog(
    project: Project,
    private val notebookDirectory: File?,
    private val pythonPathProvider: () -> String?
) : DialogWrapper(project) {

    private enum class Mode(private val label: String) {
        LAUNCH("Start a new kernel"),
        CONNECTION_FILE("Attach using a connection file"),
        MANUAL("Attach using connection parameters");

        override fun toString(): String = label
    }

    private val mode = JComboBox(Mode.entries.toTypedArray())
    private val cards = JPanel(CardLayout())
    private val pythonPath = JTextField()
    private val sourceInterpreter = JTextField()
    private val connectionFile = JTextField()
    private val manualJson = JTextArea(defaultConnectionJson(), 13, 54).apply {
        lineWrap = false
        tabSize = 2
    }
    private val sourceInterpreterPanel = JPanel(BorderLayout(0, 5)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Attached-kernel source navigation"),
            BorderFactory.createEmptyBorder(2, 4, 4, 4)
        )
        add(
            pathPanel(
                sourceInterpreter,
                "Local Python (optional):",
                true
            ),
            BorderLayout.NORTH
        )
        add(
            JLabel(
                "<html>Used only to locate and parse source; it should match the attached kernel. " +
                    "For a remote kernel, choose a local mirror of its environment. Remote-only " +
                    "files cannot be opened because source-root mapping is not configured.</html>"
            ),
            BorderLayout.CENTER
        )
    }

    init {
        title = "Jupyter Kernel"
        cards.add(pathPanel(pythonPath, "Python interpreter:", true), Mode.LAUNCH.name)
        cards.add(pathPanel(connectionFile, "Connection file:", false), Mode.CONNECTION_FILE.name)
        cards.add(
            JPanel(BorderLayout(0, 6)).apply {
                add(
                    JLabel("Paste classic Jupyter connection JSON (all five ports, IP, key, and signature scheme):"),
                    BorderLayout.NORTH
                )
                add(JScrollPane(manualJson), BorderLayout.CENTER)
            },
            Mode.MANUAL.name
        )
        mode.addActionListener { showSelectedCard() }
        init()
        showSelectedCard()
        detectPythonAsync()
    }

    fun selectedTarget(): KernelTarget = buildTarget()

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 10)).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        add(
            JPanel(BorderLayout(8, 0)).apply {
                add(JLabel("Connection:"), BorderLayout.WEST)
                add(mode, BorderLayout.CENTER)
            },
            BorderLayout.NORTH
        )
        add(
            JPanel(BorderLayout(0, 8)).apply {
                add(cards, BorderLayout.CENTER)
                add(sourceInterpreterPanel, BorderLayout.SOUTH)
            },
            BorderLayout.CENTER
        )
        preferredSize = Dimension(720, 390)
    }

    override fun doValidate(): ValidationInfo? = try {
        buildTarget()
        null
    } catch (exception: Exception) {
        ValidationInfo(exception.message ?: "Invalid kernel connection parameters", cards)
    }

    private fun buildTarget(): KernelTarget = when (mode.selectedItem as Mode) {
        Mode.LAUNCH -> {
            val executable = pythonPath.text.trim()
            require(executable.isNotEmpty()) { "Choose or enter a Python interpreter" }
            KernelTarget.Launch(executable, notebookDirectory)
        }

        Mode.CONNECTION_FILE -> {
            val path = connectionFile.text.trim()
            require(path.isNotEmpty()) { "Choose a Jupyter connection file" }
            val file = File(path)
            // Parse now so an incomplete/stale file produces a useful validation error
            // before the dialog closes.
            KernelConnectionInfoCodec.read(file)
            KernelTarget.ConnectionFile(file, selectedSourceInterpreter())
        }

        Mode.MANUAL -> KernelTarget.Manual(
            KernelConnectionInfoCodec.parse(manualJson.text),
            selectedSourceInterpreter()
        )
    }

    private fun selectedSourceInterpreter(): String? =
        sourceInterpreter.text.trim().takeIf { it.isNotEmpty() }

    private fun pathPanel(field: JTextField, label: String, python: Boolean): JPanel =
        JPanel(BorderLayout(8, 0)).apply {
            add(JLabel(label), BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply {
                    if (python) {
                        add(JButton("Detect").apply { addActionListener { detectPythonAsync() } })
                    }
                    add(JButton("Browse…").apply {
                        addActionListener { chooseFile(field, python) }
                    })
                },
                BorderLayout.EAST
            )
        }

    private fun chooseFile(field: JTextField, python: Boolean) {
        val initial = field.text.trim().takeIf { it.isNotEmpty() }?.let(::File)
            ?: notebookDirectory
        val chooser = JFileChooser(initial).apply {
            dialogTitle = if (python) "Choose Python Interpreter" else "Choose Jupyter Connection File"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(cards) == JFileChooser.APPROVE_OPTION) {
            field.text = chooser.selectedFile.absolutePath
        }
    }

    private fun detectPythonAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val detected = pythonPathProvider()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!isDisposed && detected != null) {
                        if (pythonPath.text.isBlank()) pythonPath.text = detected
                        if (sourceInterpreter.text.isBlank()) sourceInterpreter.text = detected
                    }
                },
                // A pooled thread defaults to NON_MODAL, which cannot update this modal dialog.
                ModalityState.any()
            )
        }
    }

    private fun showSelectedCard() {
        val selectedMode = mode.selectedItem as Mode
        (cards.layout as CardLayout).show(cards, selectedMode.name)
        sourceInterpreterPanel.isVisible = selectedMode != Mode.LAUNCH
    }

    private companion object {
        fun defaultConnectionJson(): String = """
            {
              "ip": "127.0.0.1",
              "transport": "tcp",
              "shell_port": 0,
              "iopub_port": 0,
              "stdin_port": 0,
              "control_port": 0,
              "hb_port": 0,
              "key": "",
              "signature_scheme": "hmac-sha256"
            }
        """.trimIndent()
    }
}
