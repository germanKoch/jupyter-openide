package com.openide.jupyter.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NotebookSerializerTest {

    @Test
    fun `malformed or structurally invalid notebook is rejected instead of replaced`() {
        assertTrue(NotebookSerializer.deserialize("{broken", "/tmp/broken.ipynb").isFailure)
        assertTrue(NotebookSerializer.deserialize("[]", "/tmp/array.ipynb").isFailure)
        assertTrue(
            NotebookSerializer.deserialize(
                """{"nbformat":4,"metadata":{},"cells":"not an array"}""",
                "/tmp/cells.ipynb"
            ).isFailure
        )
    }

    @Test
    fun `round trip preserves raw cells attachments metadata rich mime and unknown fields`() {
        val input = """
            {
              "nbformat": 4,
              "nbformat_minor": 5,
              "custom_root": {"future": true},
              "metadata": {
                "kernelspec": {
                  "name": "python3",
                  "display_name": "Python 3",
                  "language": "python",
                  "resource_dir": "/custom/kernel"
                },
                "language_info": {
                  "name": "python",
                  "version": "3.14",
                  "mimetype": "text/x-python",
                  "file_extension": ".py",
                  "codemirror_mode": {"name": "ipython", "version": 3}
                },
                "widgets": {"application/vnd.jupyter.widget-state+json": {"state": {}}}
              },
              "cells": [
                {
                  "id": "raw-cell",
                  "cell_type": "raw",
                  "metadata": {"raw_mimetype": "text/latex"},
                  "source": ["\\alpha\n"],
                  "custom_cell_field": 17
                },
                {
                  "id": "markdown-cell",
                  "cell_type": "markdown",
                  "metadata": {"tags": ["docs"]},
                  "attachments": {
                    "pixel.png": {"image/png": "AAAA"}
                  },
                  "source": ["![pixel](attachment:pixel.png)\n"]
                },
                {
                  "id": "code-cell",
                  "cell_type": "code",
                  "metadata": {"collapsed": false},
                  "execution_count": 9,
                  "source": ["print('x')\n"],
                  "outputs": [
                    {
                      "output_type": "stream",
                      "name": "stderr",
                      "text": ["warning\n"],
                      "custom_stream_field": "kept"
                    },
                    {
                      "output_type": "execute_result",
                      "execution_count": 7,
                      "data": {
                        "text/plain": ["{'answer': 42}"],
                        "application/json": {"answer": 42, "items": [1, 2, 3]}
                      },
                      "metadata": {"expanded": true},
                      "custom_result_field": [1, 2]
                    },
                    {
                      "output_type": "display_data",
                      "data": {"text/html": ["<b>safe after rendering</b>"]},
                      "metadata": {"isolated": true},
                      "transient": {"display_id": "display-1"}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val notebook = NotebookSerializer.deserialize(input, "/tmp/lossless.ipynb").getOrThrow()
        assertEquals(CellType.RAW, notebook.cells[0].cellType)
        assertEquals("\\alpha\n", notebook.cells[0].source)
        assertEquals("stderr", notebook.cells[2].outputs[0].name)
        assertEquals(7, notebook.cells[2].outputs[1].executionCount)
        assertTrue(notebook.cells[2].outputs[1].data?.get("application/json") is com.google.gson.JsonObject)

        val saved = JsonParser.parseString(NotebookSerializer.serialize(notebook)).asJsonObject
        assertTrue(saved.getAsJsonObject("custom_root").get("future").asBoolean)
        assertEquals(
            "/custom/kernel",
            saved.getAsJsonObject("metadata").getAsJsonObject("kernelspec").get("resource_dir").asString
        )
        assertTrue(saved.getAsJsonObject("metadata").has("widgets"))

        val cells = saved.getAsJsonArray("cells")
        assertEquals("raw", cells[0].asJsonObject.get("cell_type").asString)
        assertEquals(17, cells[0].asJsonObject.get("custom_cell_field").asInt)
        assertTrue(cells[1].asJsonObject.has("attachments"))

        val outputs = cells[2].asJsonObject.getAsJsonArray("outputs")
        assertEquals("stderr", outputs[0].asJsonObject.get("name").asString)
        assertEquals("kept", outputs[0].asJsonObject.get("custom_stream_field").asString)
        assertEquals(7, outputs[1].asJsonObject.get("execution_count").asInt)
        assertTrue(outputs[1].asJsonObject.getAsJsonObject("data").get("application/json").isJsonObject)
        assertTrue(outputs[1].asJsonObject.getAsJsonObject("metadata").get("expanded").asBoolean)
        assertEquals(
            "display-1",
            notebook.cells[2].outputs[2].transientData?.get("display_id")?.asString
        )
        assertFalse(outputs[2].asJsonObject.has("transient"))
    }

    @Test
    fun `attachments retain unknown mime values arrays and explicit nulls exactly`() {
        val input = """
            {
              "nbformat": 4,
              "nbformat_minor": 5,
              "metadata": {},
              "cells": [
                {
                  "id": "attached",
                  "cell_type": "markdown",
                  "metadata": {},
                  "source": ["![plot](attachment:plot%20one.png)"],
                  "attachments": {
                    "plot one.png": {
                      "image/png": ["iVBO", "Rw0KGgo="],
                      "application/x.future+json": {"nested": [1, null]},
                      "image/svg+xml": null
                    }
                  }
                },
                {
                  "id": "explicit-null",
                  "cell_type": "raw",
                  "metadata": {},
                  "source": [],
                  "attachments": null
                }
              ]
            }
        """.trimIndent()
        val expected = JsonParser.parseString(input).asJsonObject.getAsJsonArray("cells")

        val notebook = NotebookSerializer.deserialize(input, "/tmp/attachments.ipynb").getOrThrow()
        assertEquals(expected[0].asJsonObject.get("attachments"), notebook.cells[0].attachments)
        assertTrue(notebook.cells[1].attachments?.isJsonNull == true)

        notebook.cells[0].source = "updated"
        val savedCells = JsonParser.parseString(NotebookSerializer.serialize(notebook))
            .asJsonObject.getAsJsonArray("cells")
        assertEquals(expected[0].asJsonObject.get("attachments"), savedCells[0].asJsonObject.get("attachments"))
        assertTrue(savedCells[1].asJsonObject.has("attachments"))
        assertTrue(savedCells[1].asJsonObject.get("attachments").isJsonNull)
    }

    @Test
    fun `missing invalid and duplicate cell ids are replaced with unique valid ids`() {
        val input = """
            {
              "nbformat": 4,
              "nbformat_minor": 5,
              "metadata": {},
              "cells": [
                {"id":"duplicate","cell_type":"markdown","metadata":{},"source":[]},
                {"id":"duplicate","cell_type":"markdown","metadata":{},"source":[]},
                {"id":"not valid!","cell_type":"raw","metadata":{},"source":[]},
                {"cell_type":"code","metadata":{},"source":[],"execution_count":null,"outputs":[]}
              ]
            }
        """.trimIndent()

        val ids = NotebookSerializer.deserialize(input, "/tmp/ids.ipynb").getOrThrow().cells.map { it.id }
        assertEquals(4, ids.toSet().size)
        assertEquals("duplicate", ids.first())
        assertNotEquals("duplicate", ids[1])
        ids.forEach { assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(it)) }
    }

    @Test
    fun `saving a pre cell-id notebook upgrades it to nbformat 4 point 5`() {
        val input = """
            {
              "nbformat": 4,
              "nbformat_minor": 4,
              "metadata": {},
              "cells": [{"cell_type":"markdown","metadata":{},"source":["old"]}]
            }
        """.trimIndent()

        val notebook = NotebookSerializer.deserialize(input, "/tmp/old.ipynb").getOrThrow()
        val saved = JsonParser.parseString(NotebookSerializer.serialize(notebook)).asJsonObject

        assertEquals(5, saved.get("nbformat_minor").asInt)
        assertTrue(saved.getAsJsonArray("cells")[0].asJsonObject.has("id"))
    }

    @Test
    fun `fresh kernel outputs retain channel count metadata and structured mime but not transient`() {
        val structured = JsonParser.parseString("""{"answer":42,"items":[1,2]}""")
        val outputMetadata = JsonParser.parseString("""{"expanded":true}""").asJsonObject
        val transient = JsonParser.parseString("""{"display_id":"live-1"}""").asJsonObject
        val notebook = Notebook(
            filePath = "/tmp/kernel-output.ipynb",
            cells = mutableListOf(
                Cell(
                    cellType = CellType.CODE,
                    outputs = mutableListOf(
                        CellOutput(OutputType.STREAM, text = "warning\n", name = "stderr"),
                        CellOutput(
                            outputType = OutputType.EXECUTE_RESULT,
                            data = linkedMapOf("application/json" to structured, "text/plain" to "{'answer': 42}"),
                            executionCount = 12,
                            metadata = outputMetadata,
                            transientData = transient
                        )
                    )
                )
            )
        )

        val outputs = JsonParser.parseString(NotebookSerializer.serialize(notebook))
            .asJsonObject.getAsJsonArray("cells")[0].asJsonObject.getAsJsonArray("outputs")
        assertEquals("stderr", outputs[0].asJsonObject.get("name").asString)
        assertEquals(12, outputs[1].asJsonObject.get("execution_count").asInt)
        assertTrue(outputs[1].asJsonObject.getAsJsonObject("data").get("application/json").isJsonObject)
        assertTrue(outputs[1].asJsonObject.getAsJsonObject("metadata").get("expanded").asBoolean)
        assertFalse(outputs[1].asJsonObject.has("transient"))
        assertEquals(
            "live-1",
            notebook.cells.single().outputs[1].transientData?.get("display_id")?.asString
        )
    }

    @Test
    fun `required execution count nulls and arbitrary JSON nulls survive round trip`() {
        val input = """
            {
              "nbformat": 4,
              "nbformat_minor": 5,
              "metadata": {"custom": {"nested": null}},
              "cells": [{
                "id": "nullable-output",
                "cell_type": "code",
                "metadata": {"custom": {"nested": null}},
                "source": [],
                "execution_count": null,
                "outputs": [{
                  "output_type": "execute_result",
                  "execution_count": null,
                  "data": {
                    "application/json": null,
                    "text/plain": "None"
                  },
                  "metadata": {"custom": null}
                }]
              }]
            }
        """.trimIndent()

        val notebook = NotebookSerializer.deserialize(input, "/tmp/nulls.ipynb").getOrThrow()
        val saved = JsonParser.parseString(NotebookSerializer.serialize(notebook)).asJsonObject
        val savedCell = saved.getAsJsonArray("cells").single().asJsonObject
        val savedOutput = savedCell.getAsJsonArray("outputs").single().asJsonObject

        assertTrue(savedCell.has("execution_count"))
        assertTrue(savedCell.get("execution_count").isJsonNull)
        assertTrue(savedOutput.has("execution_count"))
        assertTrue(savedOutput.get("execution_count").isJsonNull)
        assertTrue(saved.getAsJsonObject("metadata").getAsJsonObject("custom").get("nested").isJsonNull)
        assertTrue(savedCell.getAsJsonObject("metadata").getAsJsonObject("custom").get("nested").isJsonNull)
        assertTrue(savedOutput.getAsJsonObject("metadata").get("custom").isJsonNull)
        assertTrue(savedOutput.getAsJsonObject("data").has("application/json"))
        assertTrue(savedOutput.getAsJsonObject("data").get("application/json").isJsonNull)
        assertTrue(NotebookSerializer.deserialize(saved.toString(), "/tmp/nulls-saved.ipynb").isSuccess)
    }

    @Test
    fun `minimal Julia metadata does not acquire Python defaults`() {
        val input = """
            {
              "nbformat": 4,
              "nbformat_minor": 5,
              "metadata": {
                "kernelspec": {
                  "name": "julia-1.11",
                  "display_name": "Julia 1.11"
                },
                "language_info": {
                  "name": "julia",
                  "version": null
                }
              },
              "cells": [{
                "id": "julia-markdown",
                "cell_type": "markdown",
                "metadata": {},
                "source": []
              }]
            }
        """.trimIndent()

        val notebook = NotebookSerializer.deserialize(input, "/tmp/julia.ipynb").getOrThrow()
        val metadata = JsonParser.parseString(NotebookSerializer.serialize(notebook))
            .asJsonObject.getAsJsonObject("metadata")
        val kernelSpec = metadata.getAsJsonObject("kernelspec")
        val languageInfo = metadata.getAsJsonObject("language_info")

        assertFalse(kernelSpec.has("language"))
        assertEquals("julia", languageInfo.get("name").asString)
        assertTrue(languageInfo.has("version"))
        assertTrue(languageInfo.get("version").isJsonNull)
        assertFalse(languageInfo.has("mimetype"))
        assertFalse(languageInfo.has("file_extension"))
    }

    @Test
    fun `unknown cell and output types fail visibly`() {
        val unknownCell = """
            {"nbformat":4,"metadata":{},"cells":[
              {"cell_type":"future-cell","metadata":{},"source":[]}
            ]}
        """.trimIndent()
        val unknownOutput = """
            {"nbformat":4,"metadata":{},"cells":[
              {"cell_type":"code","metadata":{},"source":[],"outputs":[
                {"output_type":"future-output"}
              ]}
            ]}
        """.trimIndent()

        assertTrue(NotebookSerializer.deserialize(unknownCell, "/tmp/cell.ipynb").isFailure)
        assertTrue(NotebookSerializer.deserialize(unknownOutput, "/tmp/output.ipynb").isFailure)
    }

    @Test
    fun `blank file still creates a usable notebook`() {
        val notebook = NotebookSerializer.deserialize("  ", "/tmp/new.ipynb").getOrThrow()
        assertEquals(1, notebook.cells.size)
        assertIs<Cell>(notebook.cells.single())
        assertEquals(CellType.CODE, notebook.cells.single().cellType)
    }
}
