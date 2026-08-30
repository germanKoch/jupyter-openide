package com.openide.jupyter.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

enum class CellType {
    CODE,
    MARKDOWN,
    RAW
}

enum class CellExecutionState {
    IDLE,
    QUEUED,
    EXECUTING,
    ERROR
}

data class Cell(
    val id: String = UUID.randomUUID().toString(),
    val cellType: CellType,
    var source: String = "",
    val outputs: MutableList<CellOutput> = mutableListOf(),
    var executionCount: Int? = null,
    val metadata: JsonObject = JsonObject(),
    val attachments: JsonElement? = null,
    var executionState: CellExecutionState = CellExecutionState.IDLE,
    internal val originalJson: JsonObject? = null
)
