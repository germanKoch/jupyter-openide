package com.openide.jupyter.navigation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PythonNavigationResolverTest {
    private val python: String = findPython()

    @Test
    fun `uses the nearest preceding reassignment`() {
        val source = """
            def target():
                return 1
            target = 42
            target
        """.trimIndent()

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.lastIndexOf("target") + 1)

        val location = assertIs<NotebookLocation>(result)
        assertEquals("cell", location.cellId)
        assertEquals(2, location.line)
        assertEquals(0, location.column)
    }

    @Test
    fun `does not navigate to a forward definition`() {
        val source = """
            target()
            def target():
                return 1
        """.trimIndent()

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.indexOf("target") + 1)

        assertEquals(NavigationFailure.NOT_FOUND, assertIs<NavigationUnresolved>(result).reason)
    }

    @Test
    fun `selects the newest binding from preceding cells`() {
        val cells =
            listOf(
                NavigationCell("one", "def target():\n    return 1\n"),
                NavigationCell("two", "target = 2\n"),
                NavigationCell("three", "target\n"),
            )

        val result = resolve(cells, "three", 1)

        val location = assertIs<NotebookLocation>(result)
        assertEquals("two", location.cellId)
        assertEquals(0, location.line)
    }

    @Test
    fun `nested function bindings do not leak into notebook globals`() {
        val cells =
            listOf(
                NavigationCell("one", "def wrapper():\n    def hidden():\n        pass\n"),
                NavigationCell("two", "hidden()\n"),
            )

        val result = resolve(cells, "two", 1)

        assertEquals(NavigationFailure.NOT_FOUND, assertIs<NavigationUnresolved>(result).reason)
    }

    @Test
    fun `later local assignment blocks a preceding notebook global`() {
        val cells =
            listOf(
                NavigationCell("one", "name = 10\n"),
                NavigationCell(
                    "two",
                    "def read():\n    print(name)\n    name = 20\n    return name\n",
                ),
            )
        val source = cells[1].source

        val result = resolve(cells, "two", source.indexOf("name") + 1)

        assertEquals(NavigationFailure.NOT_FOUND, assertIs<NavigationUnresolved>(result).reason)
    }

    @Test
    fun `reports Unicode code point columns from a UTF16 cursor`() {
        val source = "\"😀\"; café = 1\nprint(café)\n"
        val cursor = source.lastIndexOf("café") + 2

        val result = resolve(listOf(NavigationCell("unicode", source)), "unicode", cursor)

        val location = assertIs<NotebookLocation>(result)
        assertEquals(0, location.line)
        assertEquals(5, location.column)
    }

    @Test
    fun `resolves stdlib class methods through a from import`() {
        val source = "from pathlib import Path\nPath.cwd()\n"

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.indexOf("cwd") + 1)

        val location = assertIs<FileLocation>(result)
        assertTrue(location.path.toString().contains("pathlib"))
        assertEquals("cwd", location.symbol)
        assertTrue(location.line >= 0)
    }

    @Test
    fun `resolves import aliases and dotted class members`() {
        val source = "import pathlib as pl\npl.Path.cwd()\n"

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.indexOf("cwd") + 1)

        val location = assertIs<FileLocation>(result)
        assertEquals("cwd", location.symbol)
        assertTrue(location.path.toString().contains("pathlib"))
    }

    @Test
    fun `infers an instance returned by a classmethod`() {
        val source = "from pathlib import Path\npath = Path.cwd()\npath.exists()\n"

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.indexOf("exists") + 1)

        val location = assertIs<FileLocation>(result)
        assertEquals("exists", location.symbol)
        assertTrue(location.path.toString().contains("pathlib"))
    }

    @Test
    fun `masks common IPython commands without moving Python source offsets`() {
        val source = "notes = \"\"\"\n%%bash\n!this is documentation\n\"\"\"\n" + """
            %matplotlib inline
            !echo ready
            Path?
            captured = !printf value
            from pathlib import Path
            Path.cwd()
        """.trimIndent()

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.indexOf("cwd") + 1)

        val location = assertIs<FileLocation>(result)
        assertEquals("cwd", location.symbol)
        assertTrue(location.path.toString().contains("pathlib"))
    }

    @Test
    fun `arbitrary shell punctuation cannot poison following Python navigation`() {
        val source = "!echo (\nimport json\nresult = json.loads(\"{}\")\nresult\n"
        val cells = listOf(NavigationCell("cell", source))

        val localResult = resolve(
            cells,
            "cell",
            source.lastIndexOf("result") + 1,
        )
        val local = assertIs<NotebookLocation>(localResult)
        assertEquals("cell", local.cellId)
        assertEquals(2, local.line)
        assertEquals(0, local.column)

        val memberLocation = resolve(
            cells,
            "cell",
            source.indexOf("loads") + 1,
        )
        val library = assertIs<FileLocation>(memberLocation)
        assertEquals("loads", library.symbol)
        assertTrue(library.path.fileName.toString().startsWith("__init__"))
        assertTrue(library.line >= 0)
    }

    @Test
    fun `does not choose a platform branch that may be inactive`() {
        val source = "import os\nos.path.join('a', 'b')\n"

        val result = resolve(listOf(NavigationCell("cell", source)), "cell", source.indexOf("join") + 1)

        when (result) {
            is FileLocation -> {
                val expectedModule = if (java.io.File.separatorChar == '\\') "ntpath" else "posixpath"
                assertTrue(
                    result.path.fileName.toString().contains(expectedModule),
                    "Navigation must not open the inactive platform implementation: ${result.path}",
                )
            }
            is NavigationUnresolved -> assertEquals(NavigationFailure.SOURCE_UNAVAILABLE, result.reason)
            else -> error("Unexpected navigation result: $result")
        }
    }

    @Test
    fun `follows a bounded package reexport without executing module code`() {
        val project = Files.createTempDirectory("jupyter-navigation-project-")
        val packageDirectory = project.resolve("sample_package").createDirectories()
        val marker = project.resolve("executed.marker")
        packageDirectory.resolve("__init__.py").writeText("from .api import Public\n")
        val api = packageDirectory.resolve("api.py")
        api.writeText(
            """
            from pathlib import Path
            Path(${pythonString(marker.toString())}).write_text("executed")
            class Public:
                def method(self):
                    return 1
            """.trimIndent() + "\n",
        )
        val source = "from sample_package import Public\nPublic.method()\n"

        val result =
            resolve(
                listOf(NavigationCell("cell", source)),
                "cell",
                source.indexOf("method") + 1,
                project,
            )

        val location = assertIs<FileLocation>(result)
        assertEquals(api.toRealPath(), location.path.toRealPath())
        assertEquals(3, location.line)
        assertFalse(marker.exists(), "Static navigation must not execute imported module source")
    }

    @Test
    fun `terminates a helper that exceeds its deadline`() {
        val resolver =
            PythonNavigationResolver(
                limits = NavigationResolverLimits(timeoutMillis = 100),
                helperSource = NavigationHelperSource { "import time\ntime.sleep(60)\n" },
            )
        val request = request("value\n", 1)

        val result = resolver.use { it.resolve(request) }

        assertEquals(NavigationFailure.TIMEOUT, assertIs<NavigationUnresolved>(result).reason)
    }

    @Test
    fun `rejects helper output beyond the configured cap`() {
        val resolver =
            PythonNavigationResolver(
                limits = NavigationResolverLimits(maxOutputBytes = 128),
                helperSource =
                    NavigationHelperSource {
                        "import sys\nsys.stdin.read()\nsys.stdout.write('x' * 4096)\n"
                    },
            )

        val result = resolver.use { it.resolve(request("value\n", 1)) }

        assertEquals(NavigationFailure.OUTPUT_TOO_LARGE, assertIs<NavigationUnresolved>(result).reason)
    }

    private fun resolve(
        cells: List<NavigationCell>,
        currentCellId: String,
        cursor: Int,
        workingDirectory: Path? = null,
    ): NavigationResult =
        PythonNavigationResolver().use { resolver ->
            resolver.resolve(
                NavigationRequest(
                    cells = cells,
                    currentCellId = currentCellId,
                    cursorOffsetUtf16 = cursor,
                    pythonInterpreter = python,
                    workingDirectory = workingDirectory,
                ),
            )
        }

    private fun request(source: String, cursor: Int) =
        NavigationRequest(
            cells = listOf(NavigationCell("cell", source)),
            currentCellId = "cell",
            cursorOffsetUtf16 = cursor,
            pythonInterpreter = python,
        )

    private fun pythonString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun findPython(): String {
        val candidates = listOfNotNull(System.getenv("PYTHON"), "python3", "python")
        return candidates.firstOrNull { candidate ->
            try {
                ProcessBuilder(candidate, "--version").start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            }
        } ?: error("Python 3 is required for navigation integration tests")
    }
}
