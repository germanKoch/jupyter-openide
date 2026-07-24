package com.openide.jupyter.editor

import com.google.gson.Gson
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.openide.jupyter.analysis.Diagnostic
import com.openide.jupyter.model.*
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.net.URLEncoder
import javax.swing.JComponent

class NotebookPanel(private val parentDisposable: Disposable) : Disposable {

    private val browser: JBCefBrowser = JBCefBrowser()
    private val cellSelectedQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val cellSourceChangedQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val runCellQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val addCellQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val deleteCellQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val saveNotebookQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val ideActionQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)

    private val gson = Gson()

    companion object {
        private val LOG = Logger.getInstance(NotebookPanel::class.java)

        // Bundled UI resources, cached after the first successful read so subsequent
        // editor instances in this session don't re-read (and can't re-fail) on the jar.
        @Volatile private var cachedCss: String? = null
        @Volatile private var cachedJs: String? = null
    }

    var selectedCellId: String? = null
        private set

    var onCellSelected: ((String) -> Unit)? = null
    var onCellSourceChanged: ((String, String) -> Unit)? = null
    var onRunCell: ((String) -> Unit)? = null
    var onAddCell: ((String, String) -> Unit)? = null
    var onDeleteCell: ((String) -> Unit)? = null
    var onSaveNotebook: (() -> Unit)? = null
    var onIdeAction: ((String) -> Unit)? = null

    private var pendingNotebook: Notebook? = null
    private var loaded = false

    // False when the bundled UI resources (css/js) could not be read from the plugin
    // jar, so the page is showing the recoverable error screen instead of the editor.
    private var resourcesOk = true

    init {
        Disposer.register(parentDisposable, this)

        cellSelectedQuery.addHandler { cellId ->
            selectedCellId = cellId
            onCellSelected?.invoke(cellId)
            null
        }

        cellSourceChangedQuery.addHandler { data ->
            val sepIdx = data.indexOf('')
            if (sepIdx >= 0) {
                val cellId = data.substring(0, sepIdx)
                val source = data.substring(sepIdx + 1)
                onCellSourceChanged?.invoke(cellId, source)
            }
            null
        }

        runCellQuery.addHandler { cellId ->
            javax.swing.SwingUtilities.invokeLater {
                onRunCell?.invoke(cellId)
            }
            null
        }

        addCellQuery.addHandler { data ->
            val parts = data.split(" ", limit = 2)
            if (parts.size == 2) {
                javax.swing.SwingUtilities.invokeLater {
                    onAddCell?.invoke(parts[0], parts[1])
                }
            }
            null
        }

        deleteCellQuery.addHandler { cellId ->
            javax.swing.SwingUtilities.invokeLater {
                onDeleteCell?.invoke(cellId)
            }
            null
        }

        saveNotebookQuery.addHandler {
            javax.swing.SwingUtilities.invokeLater {
                onSaveNotebook?.invoke()
            }
            null
        }

        ideActionQuery.addHandler { actionId ->
            javax.swing.SwingUtilities.invokeLater {
                onIdeAction?.invoke(actionId)
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectBridge()
                    loaded = true
                    pendingNotebook?.let { renderNotebook(it) }
                    pendingNotebook = null
                }
            }
        }, browser.cefBrowser)

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener { executeJs("setTheme(${isDarkTheme()})") }
        )

        val html = buildInlineHtml(isDarkTheme())
        browser.loadHTML(html)
    }

    // Falls back to dark (the plugin's original, only theme) if the LaF API can't
    // report a value, so behavior is unchanged for anyone on an odd/custom theme.
    private fun isDarkTheme(): Boolean {
        return LafManager.getInstance().currentUIThemeLookAndFeel?.isDark ?: true
    }

    val component: JComponent get() = browser.component

    fun renderNotebook(notebook: Notebook) {
        if (!resourcesOk) return
        if (!loaded) {
            pendingNotebook = notebook
            return
        }
        executeJs("clearNotebook()")
        for (cell in notebook.cells) {
            addCellToView(cell)
        }
        executeJs("renderNotebookComplete()")
    }

    fun addCellToView(cell: Cell) {
        val type = if (cell.cellType == CellType.CODE) "code" else "markdown"
        val source = if (cell.cellType == CellType.MARKDOWN) {
            escapeJs(renderMarkdown(cell.source))
        } else {
            escapeJs(cell.source)
        }
        val outputsHtml = if (cell.cellType == CellType.CODE) {
            escapeJs(renderOutputs(cell.outputs))
        } else {
            ""
        }
        val execCount = cell.executionCount?.toString() ?: "null"
        executeJs("addCell('${escapeJs(cell.id)}', '$type', '$source', '$outputsHtml', $execCount)")
        if (cell.cellType == CellType.MARKDOWN) {
            executeJs("setMarkdownSource('${escapeJs(cell.id)}', '${escapeJs(cell.source)}')")
        }
    }

    fun insertCellAfter(afterCellId: String, cell: Cell) {
        val type = if (cell.cellType == CellType.CODE) "code" else "markdown"
        val source = if (cell.cellType == CellType.MARKDOWN) {
            escapeJs(renderMarkdown(cell.source))
        } else {
            escapeJs(cell.source)
        }
        executeJs("insertCellAfter('${escapeJs(afterCellId)}', '${escapeJs(cell.id)}', '$type', '$source', '', null)")
    }

    fun removeCellFromView(cellId: String) {
        executeJs("removeCell('${escapeJs(cellId)}')")
    }

    fun clearCellOutputs(cellId: String) {
        executeJs("clearOutputs('${escapeJs(cellId)}')")
    }

    fun appendCellOutput(cellId: String, output: CellOutput) {
        val html = escapeJs(renderSingleOutput(output))
        executeJs("appendOutput('${escapeJs(cellId)}', '$html')")
    }

    fun setExecutionCount(cellId: String, count: Int?) {
        val countVal = count?.toString() ?: "null"
        executeJs("setExecutionCount('${escapeJs(cellId)}', $countVal)")
    }

    fun setCellExecuting(cellId: String, executing: Boolean) {
        executeJs("setCellExecuting('${escapeJs(cellId)}', $executing)")
    }

    fun setDiagnostics(diagnostics: List<Diagnostic>) {
        val json = gson.toJson(diagnostics)
        executeJs("setDiagnostics('${escapeJs(json)}')")
    }

    fun notifyCellExecuted(cellId: String, success: Boolean) {
        executeJs("onCellExecuted('${escapeJs(cellId)}', $success)")
    }

    /**
     * Open the in-notebook find (or find+replace) bar. Used as a fallback for the
     * JS keydown handler: if the IDE consumes Cmd+F/Cmd+R before the JCEF page sees
     * it, the bound action calls this instead. Focusing the browser first ensures
     * keystrokes land in the find field.
     */
    fun openFind(replace: Boolean) {
        browser.component.requestFocusInWindow()
        executeJs("openFind($replace)")
    }

    fun makeCellEditable(cellId: String) {
        executeJs("makeEditable('${escapeJs(cellId)}')")
    }

    fun makeCellReadOnly(cellId: String) {
        executeJs("makeReadOnly('${escapeJs(cellId)}')")
    }

    fun startEditMarkdown(cellId: String) {
        executeJs("startEditMarkdown('${escapeJs(cellId)}')")
    }

    fun stopEditMarkdown(cellId: String, renderedHtml: String) {
        executeJs("stopEditMarkdown('${escapeJs(cellId)}', '${escapeJs(renderedHtml)}')")
    }

    private fun renderOutputs(outputs: List<CellOutput>): String {
        return outputs.joinToString("") { renderSingleOutput(it) }
    }

    private fun renderSingleOutput(output: CellOutput): String {
        return when (output.outputType) {
            OutputType.STREAM -> {
                "<div class=\"output-stream\">${escapeHtml(output.text ?: "")}</div>"
            }
            OutputType.EXECUTE_RESULT, OutputType.DISPLAY_DATA -> {
                renderMimeBundle(output.data)
            }
            OutputType.ERROR -> {
                val traceback = output.traceback?.joinToString("\n") { stripAnsi(it) } ?: ""
                "<div class=\"output-error\">${escapeHtml(traceback)}</div>"
            }
        }
    }

    private fun renderMimeBundle(data: Map<String, Any>?): String {
        if (data == null) return ""
        data["text/html"]?.let {
            return "<div class=\"output-html\">${it}</div>"
        }
        data["image/png"]?.let {
            return "<div class=\"output-image\"><img src=\"data:image/png;base64,${it}\"></div>"
        }
        data["image/svg+xml"]?.let {
            return "<div class=\"output-image\">${it}</div>"
        }
        data["text/plain"]?.let {
            return "<div class=\"output-stream\">${escapeHtml(it.toString())}</div>"
        }
        return ""
    }

    fun renderMarkdown(source: String): String {
        var html = escapeHtml(source)
        html = html.replace(Regex("^#{6}\\s+(.+)$", RegexOption.MULTILINE), "<h6>$1</h6>")
        html = html.replace(Regex("^#{5}\\s+(.+)$", RegexOption.MULTILINE), "<h5>$1</h5>")
        html = html.replace(Regex("^#{4}\\s+(.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
        html = html.replace(Regex("^#{3}\\s+(.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        html = html.replace(Regex("^#{2}\\s+(.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        html = html.replace(Regex("^#\\s+(.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        html = html.replace(Regex("```([\\s\\S]*?)```"), "<pre><code>$1</code></pre>")
        html = html.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        html = html.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        html = html.replace(Regex("^[-*]\\s+(.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        html = html.replace(Regex("(<li>.*</li>\\n?)+"), "<ul>$0</ul>")
        html = html.replace(Regex("^>\\s+(.+)$", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
        html = html.replace(Regex("\n\n"), "</p><p>")
        html = "<p>$html</p>"
        return html
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\[[0-9;]*m"), "")
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun escapeJs(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\u2028", "\\u2028") // line separator: valid in JSON, breaks JS literals
            .replace("\u2029", "\\u2029") // paragraph separator: same
    }

    private fun injectBridge() {
        val selectHandler = cellSelectedQuery.inject("id")
        val sourceHandler = cellSourceChangedQuery.inject("data")
        val runCellHandler = runCellQuery.inject("id")
        val addCellHandler = addCellQuery.inject("data")
        val deleteCellHandler = deleteCellQuery.inject("id")
        val saveHandler = saveNotebookQuery.inject("'save'")
        val ideActionHandler = ideActionQuery.inject("actionId")
        executeJs("""
            initBridge({
                cellSelected: function(id) { $selectHandler },
                cellSourceChanged: function(id, src) { var data = id + '' + src; $sourceHandler },
                runCell: function(id) { $runCellHandler },
                addCell: function(afterId, type) { var data = afterId + ' ' + type; $addCellHandler },
                deleteCell: function(id) { $deleteCellHandler },
                saveNotebook: function() { $saveHandler },
                runIdeAction: function(actionId) { $ideActionHandler }
            });
        """.trimIndent())
    }

    private fun buildInlineHtml(isDark: Boolean): String {
        // Cache the bundled resources once read successfully: later notebooks opened in
        // the same session reuse them and never touch the jar again, so a transient
        // jar-read glitch can affect at most the first open in a session.
        val css = cachedCss ?: loadResource("notebook/notebook.css").also { if (it.isNotBlank()) cachedCss = it }
        val js = cachedJs ?: loadResource("notebook/notebook.js").also { if (it.isNotBlank()) cachedJs = it }
        if (js.isBlank()) {
            // Resources couldn't be read from the plugin jar. Show a recoverable message
            // instead of letting the editor construction fail with a blank/dead tab.
            resourcesOk = false
            return buildResourceErrorHtml()
        }
        val theme = if (isDark) "dark" else "light"
        return """
            <!DOCTYPE html>
            <html lang="en" data-theme="$theme">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>$css</style>
            </head>
            <body>
                <div id="notebook-container"></div>
                <script>$js</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildResourceErrorHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8">
            <style>
              body{font-family:-apple-system,'Segoe UI',sans-serif;background:#2b2b2b;color:#bbbbbb;
                   display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}
              .box{max-width:540px;padding:24px 28px;text-align:center;line-height:1.55;}
              h2{color:#e8e8e8;font-size:16px;margin:0 0 12px;}
              code{background:#3c3f41;padding:1px 6px;border-radius:4px;color:#d7ba7d;}
            </style></head>
            <body><div class="box">
              <h2>Couldn't load the notebook editor</h2>
              <p>The plugin's interface resources could not be read from its archive. This is
              almost always a temporary IDE issue &mdash; not a problem with your notebook file.</p>
              <p>Please restart the IDE (<code>File &rarr; Restart IDE</code>) and reopen the notebook.</p>
            </div></body></html>
        """.trimIndent()
    }

    private fun loadResource(path: String): String {
        var lastError: Throwable? = null
        repeat(3) {
            try {
                javaClass.classLoader.getResourceAsStream(path)?.use { stream ->
                    return stream.bufferedReader().readText()
                }
                // Stream was null: the resource is genuinely absent, so a retry can't help.
                return ""
            } catch (t: Throwable) {
                // Transient IDE-level jar decompression failures (a memory-mapped
                // ZipException such as "invalid code lengths set") can make one read fail
                // while a retry succeeds. Never let it escape and kill the editor.
                lastError = t
            }
        }
        LOG.warn("Failed to read bundled resource '$path' from the plugin jar after retries; " +
            "the IDE may need a restart.", lastError)
        return ""
    }

    private fun executeJs(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url ?: "about:blank", 0)
    }

    override fun dispose() {
    }
}
