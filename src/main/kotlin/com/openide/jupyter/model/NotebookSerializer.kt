package com.openide.jupyter.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * Lossless-enough notebook codec.
 *
 * The editor owns a small, typed subset of nbformat.  Every parsed object also
 * retains its original JSON so fields that the editor does not understand
 * (attachments, widgets metadata, custom MIME data, and future nbformat
 * extensions) survive an edit/save cycle.
 */
object NotebookSerializer {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        // nbformat requires several nullable properties (notably execution_count)
        // to remain present, and arbitrary metadata/MIME JSON may contain nulls.
        .serializeNulls()
        .create()
    private val validCellId = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun deserialize(json: String, filePath: String): Result<Notebook> {
        if (json.isBlank()) return Result.success(createDefaultNotebook(filePath))

        return runCatching {
            val parsed = JsonParser.parseString(json)
            require(parsed.isJsonObject) { "Notebook root must be a JSON object" }
            val root = parsed.asJsonObject

            val nbformat = requiredInt(root, "nbformat")
            require(nbformat == 4) {
                "Unsupported nbformat version: $nbformat (only v4 supported)"
            }
            val nbformatMinor = optionalInt(root, "nbformat_minor") ?: 0
            val metadata = parseMetadata(optionalObject(root, "metadata"))
            val cellsElement = root.get("cells")
                ?: throw IllegalArgumentException("Missing cells field")
            require(cellsElement.isJsonArray) { "Notebook cells must be an array" }
            val cells = parseCells(cellsElement.asJsonArray)

            Notebook(
                filePath = filePath,
                nbformatVersion = nbformat,
                nbformatMinor = nbformatMinor,
                metadata = metadata,
                cells = cells,
                originalJson = root.deepCopy()
            )
        }
    }

    fun serialize(notebook: Notebook): String {
        val root = notebook.originalJson?.deepCopy() ?: JsonObject()
        root.add("metadata", serializeMetadata(notebook.metadata))
        root.addProperty("nbformat", notebook.nbformatVersion)
        // Cell ids are part of nbformat starting with 4.5. The editor always
        // normalizes ids, so an older 4.x notebook must be upgraded on save
        // rather than emitting ids under an incompatible 4.4 schema.
        root.addProperty("nbformat_minor", maxOf(notebook.nbformatMinor, 5))
        root.add("cells", serializeCells(notebook.cells))
        return gson.toJson(root)
    }

    private fun createDefaultNotebook(filePath: String): Notebook = Notebook(
        filePath = filePath,
        nbformatVersion = 4,
        nbformatMinor = 5,
        metadata = NotebookMetadata(
            kernelSpec = KernelSpec(name = "python3", displayName = "Python 3", language = "python"),
            languageInfo = LanguageInfo(
                name = "python",
                version = "",
                mimetype = "text/x-python",
                fileExtension = ".py"
            )
        ),
        cells = mutableListOf(Cell(cellType = CellType.CODE))
    )

    private fun parseMetadata(obj: JsonObject?): NotebookMetadata {
        if (obj == null) return NotebookMetadata()

        val kernelSpec = optionalObject(obj, "kernelspec")?.let { ks ->
            KernelSpec(
                name = optionalString(ks, "name") ?: "python3",
                displayName = optionalString(ks, "display_name") ?: "Python 3",
                language = optionalString(ks, "language")
            )
        }
        val languageInfo = optionalObject(obj, "language_info")?.let { info ->
            LanguageInfo(
                name = optionalString(info, "name") ?: "python",
                version = optionalString(info, "version"),
                mimetype = optionalString(info, "mimetype"),
                fileExtension = optionalString(info, "file_extension")
            )
        }
        return NotebookMetadata(kernelSpec, languageInfo, obj.deepCopy())
    }

    private fun serializeMetadata(metadata: NotebookMetadata): JsonObject {
        val obj = metadata.originalJson?.deepCopy() ?: JsonObject()

        metadata.kernelSpec?.let { kernelSpec ->
            val kernel = optionalObject(obj, "kernelspec")?.deepCopy() ?: JsonObject()
            kernel.addProperty("name", kernelSpec.name)
            kernel.addProperty("display_name", kernelSpec.displayName)
            // `language` is optional. A parsed null leaves the retained original
            // JSON untouched, preserving the distinction between absent and null.
            kernelSpec.language?.let { kernel.addProperty("language", it) }
            obj.add("kernelspec", kernel)
        } ?: obj.remove("kernelspec")

        metadata.languageInfo?.let { languageInfo ->
            val language = optionalObject(obj, "language_info")?.deepCopy() ?: JsonObject()
            language.addProperty("name", languageInfo.name)
            // These language_info properties are optional in nbformat. Do not
            // synthesize Python values when a parsed Julia/R/etc. notebook omitted
            // them; null keeps an explicit retained null and absence stays absent.
            languageInfo.version?.let { language.addProperty("version", it) }
            languageInfo.mimetype?.let { language.addProperty("mimetype", it) }
            languageInfo.fileExtension?.let { language.addProperty("file_extension", it) }
            obj.add("language_info", language)
        } ?: obj.remove("language_info")

        return obj
    }

    private fun parseCells(cells: JsonArray): MutableList<Cell> {
        val seenIds = mutableSetOf<String>()
        return cells.mapIndexed { index, element ->
            require(element.isJsonObject) { "Cell at index $index must be an object" }
            val obj = element.asJsonObject
            val cellType = when (requiredString(obj, "cell_type")) {
                "code" -> CellType.CODE
                "markdown" -> CellType.MARKDOWN
                "raw" -> CellType.RAW
                else -> throw IllegalArgumentException("Unsupported cell type at index $index")
            }
            val requestedId = optionalString(obj, "id")
            val id = if (requestedId != null && validCellId.matches(requestedId) && seenIds.add(requestedId)) {
                requestedId
            } else {
                generateUniqueCellId(seenIds)
            }
            val metadata = optionalObject(obj, "metadata")?.deepCopy() ?: JsonObject()
            val outputs = if (cellType == CellType.CODE) {
                parseOutputs(optionalArray(obj, "outputs"), index)
            } else {
                mutableListOf()
            }

            Cell(
                id = id,
                cellType = cellType,
                source = extractMultilineText(obj.get("source"), "cell[$index].source"),
                outputs = outputs,
                executionCount = if (cellType == CellType.CODE) optionalInt(obj, "execution_count") else null,
                metadata = metadata,
                // Keep this as raw JSON: attachment bundles may contain future
                // MIME types, arrays, nested values, or an explicit JSON null.
                attachments = obj.get("attachments")?.deepCopy(),
                originalJson = obj.deepCopy()
            )
        }.toMutableList()
    }

    private fun generateUniqueCellId(seenIds: MutableSet<String>): String {
        while (true) {
            val id = UUID.randomUUID().toString()
            if (seenIds.add(id)) return id
        }
    }

    private fun serializeCells(cells: List<Cell>): JsonArray = JsonArray().also { array ->
        cells.forEach { cell ->
            val obj = cell.originalJson?.deepCopy() ?: JsonObject()
            obj.addProperty("id", cell.id)
            obj.addProperty(
                "cell_type",
                when (cell.cellType) {
                    CellType.CODE -> "code"
                    CellType.MARKDOWN -> "markdown"
                    CellType.RAW -> "raw"
                }
            )
            obj.add("source", serializeMultilineText(cell.source))
            obj.add("metadata", cell.metadata.deepCopy())
            if (cell.attachments == null) {
                obj.remove("attachments")
            } else {
                obj.add("attachments", cell.attachments.deepCopy())
            }

            if (cell.cellType == CellType.CODE) {
                addNullableInt(obj, "execution_count", cell.executionCount)
                obj.add("outputs", serializeOutputs(cell.outputs))
            }
            array.add(obj)
        }
    }

    private fun parseOutputs(outputs: JsonArray?, cellIndex: Int): MutableList<CellOutput> {
        if (outputs == null) return mutableListOf()
        return outputs.mapIndexed { outputIndex, element ->
            require(element.isJsonObject) {
                "Output $outputIndex in cell $cellIndex must be an object"
            }
            val obj = element.asJsonObject
            when (val type = requiredString(obj, "output_type")) {
                "stream" -> CellOutput(
                    outputType = OutputType.STREAM,
                    text = extractMultilineText(obj.get("text"), "stream.text"),
                    name = optionalString(obj, "name") ?: "stdout",
                    originalJson = obj.deepCopy()
                )

                "execute_result" -> CellOutput(
                    outputType = OutputType.EXECUTE_RESULT,
                    data = parseDataBundle(optionalObject(obj, "data")),
                    executionCount = optionalInt(obj, "execution_count"),
                    metadata = optionalObject(obj, "metadata")?.deepCopy() ?: JsonObject(),
                    transientData = optionalObject(obj, "transient")?.deepCopy(),
                    originalJson = obj.deepCopy()
                )

                "display_data" -> CellOutput(
                    outputType = OutputType.DISPLAY_DATA,
                    data = parseDataBundle(optionalObject(obj, "data")),
                    metadata = optionalObject(obj, "metadata")?.deepCopy() ?: JsonObject(),
                    transientData = optionalObject(obj, "transient")?.deepCopy(),
                    originalJson = obj.deepCopy()
                )

                "error" -> CellOutput(
                    outputType = OutputType.ERROR,
                    ename = optionalString(obj, "ename"),
                    evalue = optionalString(obj, "evalue"),
                    traceback = optionalArray(obj, "traceback")?.mapIndexed { traceIndex, value ->
                        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                            "traceback[$traceIndex] must be a string"
                        }
                        value.asString
                    },
                    originalJson = obj.deepCopy()
                )

                else -> throw IllegalArgumentException(
                    "Unsupported output type '$type' in cell $cellIndex"
                )
            }
        }.toMutableList()
    }

    private fun serializeOutputs(outputs: List<CellOutput>): JsonArray = JsonArray().also { array ->
        outputs.forEach { output ->
            val obj = output.originalJson?.deepCopy() ?: JsonObject()
            // `transient` is explicitly runtime-only in nbformat. Remove it even
            // when it came from the lossless original JSON retained at parse time.
            obj.remove("transient")
            when (output.outputType) {
                OutputType.STREAM -> {
                    obj.addProperty("output_type", "stream")
                    obj.addProperty("name", output.name ?: "stdout")
                    obj.add("text", serializeMultilineText(output.text.orEmpty()))
                }

                OutputType.EXECUTE_RESULT -> {
                    obj.addProperty("output_type", "execute_result")
                    obj.add("data", serializeDataBundle(output.data))
                    obj.add("metadata", output.metadata.deepCopy())
                    addNullableInt(obj, "execution_count", output.executionCount)
                }

                OutputType.DISPLAY_DATA -> {
                    obj.addProperty("output_type", "display_data")
                    obj.add("data", serializeDataBundle(output.data))
                    obj.add("metadata", output.metadata.deepCopy())
                }

                OutputType.ERROR -> {
                    obj.addProperty("output_type", "error")
                    obj.addProperty("ename", output.ename.orEmpty())
                    obj.addProperty("evalue", output.evalue.orEmpty())
                    obj.add("traceback", JsonArray().also { traceback ->
                        output.traceback.orEmpty().forEach(traceback::add)
                    })
                }
            }
            array.add(obj)
        }
    }

    private fun parseDataBundle(data: JsonObject?): Map<String, Any>? {
        if (data == null) return null
        return linkedMapOf<String, Any>().also { result ->
            data.entrySet().forEach { (mime, value) -> result[mime] = value.deepCopy() }
        }
    }

    private fun serializeDataBundle(data: Map<String, Any>?): JsonObject = JsonObject().also { obj ->
        data.orEmpty().forEach { (mime, value) ->
            obj.add(mime, if (value is JsonElement) value.deepCopy() else gson.toJsonTree(value))
        }
    }

    private fun extractMultilineText(source: JsonElement?, path: String): String {
        if (source == null || source.isJsonNull) return ""
        if (source.isJsonPrimitive && source.asJsonPrimitive.isString) return source.asString
        require(source.isJsonArray) { "$path must be a string or an array of strings" }
        return buildString {
            source.asJsonArray.forEachIndexed { index, element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    "$path[$index] must be a string"
                }
                append(element.asString)
            }
        }
    }

    private fun serializeMultilineText(text: String): JsonArray = JsonArray().also { lines ->
        if (text.isEmpty()) return@also
        var start = 0
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                lines.add(text.substring(start, index + 1))
                start = index + 1
            }
        }
        if (start < text.length) lines.add(text.substring(start))
    }

    private fun optionalObject(parent: JsonObject, name: String): JsonObject? {
        val value = parent.get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonObject) { "$name must be an object" }
        return value.asJsonObject
    }

    private fun optionalArray(parent: JsonObject, name: String): JsonArray? {
        val value = parent.get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonArray) { "$name must be an array" }
        return value.asJsonArray
    }

    private fun requiredString(parent: JsonObject, name: String): String =
        optionalString(parent, name) ?: throw IllegalArgumentException("Missing $name field")

    private fun optionalString(parent: JsonObject, name: String): String? {
        val value = parent.get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
        return value.asString
    }

    private fun requiredInt(parent: JsonObject, name: String): Int =
        optionalInt(parent, name) ?: throw IllegalArgumentException("Missing $name field")

    private fun optionalInt(parent: JsonObject, name: String): Int? {
        val value = parent.get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
        return try {
            value.asJsonPrimitive.asBigDecimal.toBigIntegerExact().intValueExact()
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("$name must be an integer", exception)
        }
    }

    private fun addNullableInt(parent: JsonObject, name: String, value: Int?) {
        if (value == null) parent.add(name, JsonNull.INSTANCE) else parent.addProperty(name, value)
    }
}
