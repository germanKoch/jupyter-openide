package com.openide.jupyter.model

enum class OutputType {
    STREAM,
    EXECUTE_RESULT,
    DISPLAY_DATA,
    ERROR
}

data class CellOutput(
    val outputType: OutputType,
    val text: String? = null,
    val data: Map<String, Any>? = null,
    val ename: String? = null,
    val evalue: String? = null,
    val traceback: List<String>? = null
)
