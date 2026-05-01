package com.openide.jupyter.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object NotebookSerializer {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun deserialize(json: String, filePath: String): Result<Notebook> {
        if (json.isBlank()) {
            return Result.success(createDefaultNotebook(filePath))
        }
        return try {
            val parsed = try {
                JsonParser.parseString(json)
            } catch (e: Exception) {
                return Result.success(createDefaultNotebook(filePath))
            }
            if (!parsed.isJsonObject) {
                return Result.success(createDefaultNotebook(filePath))
            }
            val root = parsed.asJsonObject
            val nbformat = root.get("nbformat")?.asInt ?: return Result.failure(
                IllegalArgumentException("Missing nbformat field")
            )
            if (nbformat != 4) {
                return Result.failure(
                    IllegalArgumentException("Unsupported nbformat version: $nbformat (only v4 supported)")
                )
            }
            val nbformatMinor = root.get("nbformat_minor")?.asInt ?: 0
            val metadata = parseMetadata(root.getAsJsonObject("metadata"))
            val cells = parseCells(root.getAsJsonArray("cells"))
            Result.success(
                Notebook(
                    filePath = filePath,
                    nbformatVersion = nbformat,
                    nbformatMinor = nbformatMinor,
                    metadata = metadata,
                    cells = cells.toMutableList()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createDefaultNotebook(filePath: String): Notebook {
        return Notebook(
            filePath = filePath,
            nbformatVersion = 4,
            nbformatMinor = 5,
            metadata = NotebookMetadata(
                kernelSpec = KernelSpec(name = "python3", displayName = "Python 3", language = "python"),
                languageInfo = LanguageInfo(name = "python", version = "", mimetype = "text/x-python", fileExtension = ".py")
            ),
            cells = mutableListOf(Cell(cellType = CellType.CODE))
        )
    }

    fun serialize(notebook: Notebook): String {
        val root = JsonObject()
        root.add("metadata", serializeMetadata(notebook.metadata))
        root.addProperty("nbformat", notebook.nbformatVersion)
        root.addProperty("nbformat_minor", notebook.nbformatMinor)
        root.add("cells", serializeCells(notebook.cells))
        return gson.toJson(root)
    }

    private fun parseMetadata(obj: JsonObject?): NotebookMetadata {
        if (obj == null) return NotebookMetadata()
        val kernelSpec = obj.getAsJsonObject("kernelspec")?.let { ks ->
            KernelSpec(
                name = ks.get("name")?.asString ?: "python3",
                displayName = ks.get("display_name")?.asString ?: "Python 3",
                language = ks.get("language")?.asString ?: "python"
            )
        }
        val languageInfo = obj.getAsJsonObject("language_info")?.let { li ->
            LanguageInfo(
                name = li.get("name")?.asString ?: "python",
                version = li.get("version")?.asString ?: "",
                mimetype = li.get("mimetype")?.asString ?: "text/x-python",
                fileExtension = li.get("file_extension")?.asString ?: ".py"
            )
        }
        return NotebookMetadata(kernelSpec, languageInfo)
    }

    private fun serializeMetadata(metadata: NotebookMetadata): JsonElement {
        val obj = JsonObject()
        metadata.kernelSpec?.let { ks ->
            val ksObj = JsonObject()
            ksObj.addProperty("name", ks.name)
            ksObj.addProperty("display_name", ks.displayName)
            ksObj.addProperty("language", ks.language)
            obj.add("kernelspec", ksObj)
        }
        metadata.languageInfo?.let { li ->
            val liObj = JsonObject()
            liObj.addProperty("name", li.name)
            liObj.addProperty("version", li.version)
            liObj.addProperty("mimetype", li.mimetype)
            liObj.addProperty("file_extension", li.fileExtension)
            obj.add("language_info", liObj)
        }
        return obj
    }

    private fun parseCells(cells: com.google.gson.JsonArray?): List<Cell> {
        if (cells == null) return emptyList()
        return cells.mapNotNull { element ->
            val obj = element.asJsonObject
            val cellTypeStr = obj.get("cell_type")?.asString ?: return@mapNotNull null
            val cellType = when (cellTypeStr) {
                "code" -> CellType.CODE
                "markdown" -> CellType.MARKDOWN
                "raw" -> CellType.MARKDOWN
                else -> return@mapNotNull null
            }
            val source = extractSource(obj.get("source"))
            val id = obj.get("id")?.asString
            val executionCount = obj.get("execution_count")?.let {
                if (it.isJsonNull) null else it.asInt
            }
            val outputs = if (cellType == CellType.CODE) {
                parseOutputs(obj.getAsJsonArray("outputs"))
            } else {
                mutableListOf()
            }
            Cell(
                id = id ?: java.util.UUID.randomUUID().toString(),
                cellType = cellType,
                source = source,
                outputs = outputs,
                executionCount = executionCount
            )
        }
    }

    private fun extractSource(source: JsonElement?): String {
        if (source == null) return ""
        if (source.isJsonArray) {
            return source.asJsonArray.joinToString("") { it.asString }
        }
        return source.asString
    }

    private fun parseOutputs(outputs: com.google.gson.JsonArray?): MutableList<CellOutput> {
        if (outputs == null) return mutableListOf()
        return outputs.mapNotNull { element ->
            val obj = element.asJsonObject
            val outputType = when (obj.get("output_type")?.asString) {
                "stream" -> OutputType.STREAM
                "execute_result" -> OutputType.EXECUTE_RESULT
                "display_data" -> OutputType.DISPLAY_DATA
                "error" -> OutputType.ERROR
                else -> return@mapNotNull null
            }
            when (outputType) {
                OutputType.STREAM -> CellOutput(
                    outputType = outputType,
                    text = extractSource(obj.get("text"))
                )
                OutputType.EXECUTE_RESULT, OutputType.DISPLAY_DATA -> CellOutput(
                    outputType = outputType,
                    data = parseDataBundle(obj.getAsJsonObject("data"))
                )
                OutputType.ERROR -> CellOutput(
                    outputType = outputType,
                    ename = obj.get("ename")?.asString,
                    evalue = obj.get("evalue")?.asString,
                    traceback = obj.getAsJsonArray("traceback")?.map { it.asString }
                )
            }
        }.toMutableList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDataBundle(data: JsonObject?): Map<String, Any>? {
        if (data == null) return null
        val result = mutableMapOf<String, Any>()
        for ((key, value) in data.entrySet()) {
            result[key] = when {
                value.isJsonArray -> value.asJsonArray.joinToString("") { it.asString }
                value.isJsonPrimitive -> value.asString
                else -> value.toString()
            }
        }
        return result
    }

    private fun serializeCells(cells: List<Cell>): com.google.gson.JsonArray {
        val array = com.google.gson.JsonArray()
        for (cell in cells) {
            val obj = JsonObject()
            obj.addProperty("id", cell.id)
            obj.addProperty("cell_type", when (cell.cellType) {
                CellType.CODE -> "code"
                CellType.MARKDOWN -> "markdown"
            })
            obj.add("source", serializeSource(cell.source))
            obj.add("metadata", gson.toJsonTree(cell.metadata))
            if (cell.cellType == CellType.CODE) {
                if (cell.executionCount != null) {
                    obj.addProperty("execution_count", cell.executionCount)
                } else {
                    obj.add("execution_count", com.google.gson.JsonNull.INSTANCE)
                }
                obj.add("outputs", serializeOutputs(cell.outputs))
            }
            array.add(obj)
        }
        return array
    }

    private fun serializeSource(source: String): com.google.gson.JsonArray {
        val array = com.google.gson.JsonArray()
        val lines = source.split("\n")
        for ((i, line) in lines.withIndex()) {
            if (i < lines.size - 1) {
                array.add(line + "\n")
            } else if (line.isNotEmpty()) {
                array.add(line)
            }
        }
        return array
    }

    private fun serializeOutputs(outputs: List<CellOutput>): com.google.gson.JsonArray {
        val array = com.google.gson.JsonArray()
        for (output in outputs) {
            val obj = JsonObject()
            when (output.outputType) {
                OutputType.STREAM -> {
                    obj.addProperty("output_type", "stream")
                    obj.addProperty("name", "stdout")
                    obj.addProperty("text", output.text ?: "")
                }
                OutputType.EXECUTE_RESULT -> {
                    obj.addProperty("output_type", "execute_result")
                    obj.add("data", serializeDataBundle(output.data))
                    obj.add("metadata", JsonObject())
                    obj.addProperty("execution_count", 0)
                }
                OutputType.DISPLAY_DATA -> {
                    obj.addProperty("output_type", "display_data")
                    obj.add("data", serializeDataBundle(output.data))
                    obj.add("metadata", JsonObject())
                }
                OutputType.ERROR -> {
                    obj.addProperty("output_type", "error")
                    obj.addProperty("ename", output.ename ?: "")
                    obj.addProperty("evalue", output.evalue ?: "")
                    val tb = com.google.gson.JsonArray()
                    output.traceback?.forEach { tb.add(it) }
                    obj.add("traceback", tb)
                }
            }
            array.add(obj)
        }
        return array
    }

    private fun serializeDataBundle(data: Map<String, Any>?): JsonObject {
        val obj = JsonObject()
        data?.forEach { (key, value) ->
            obj.addProperty(key, value.toString())
        }
        return obj
    }
}
