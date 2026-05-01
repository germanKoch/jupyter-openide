package com.openide.jupyter.model

import java.util.UUID

enum class CellType {
    CODE,
    MARKDOWN
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
    val metadata: MutableMap<String, Any> = mutableMapOf(),
    var executionState: CellExecutionState = CellExecutionState.IDLE
)
