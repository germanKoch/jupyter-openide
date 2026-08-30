package com.openide.jupyter.navigation

import java.nio.file.Path

/** A notebook cell in visual/execution-document order. */
data class NavigationCell(
    val id: String,
    val source: String,
)

/**
 * Input for a source-navigation lookup.
 *
 * [cursorOffsetUtf16] uses the same UTF-16 indexing as Swing/JavaScript strings. Successful result
 * positions are zero-based Unicode code-point positions; use [NavigationPositions] when an editor
 * needs a UTF-16 column again.
 */
data class NavigationRequest(
    val cells: List<NavigationCell>,
    val currentCellId: String,
    val cursorOffsetUtf16: Int,
    val pythonInterpreter: String,
    val workingDirectory: Path? = null,
)

sealed interface NavigationResult

data class NotebookLocation(
    val cellId: String,
    val line: Int,
    val column: Int,
    val symbol: String,
) : NavigationResult

data class FileLocation(
    val path: Path,
    val line: Int,
    val column: Int,
    val symbol: String,
) : NavigationResult

data class NavigationUnresolved(
    val reason: NavigationFailure,
    val message: String? = null,
) : NavigationResult

enum class NavigationFailure {
    INVALID_REQUEST,
    NO_SYMBOL,
    NOT_FOUND,
    SOURCE_UNAVAILABLE,
    INPUT_TOO_LARGE,
    OUTPUT_TOO_LARGE,
    TIMEOUT,
    PROCESS_ERROR,
    INVALID_RESPONSE,
    HELPER_ERROR,
}

fun interface NavigationResolver {
    /** This is a blocking call and must not run on the IDE event-dispatch thread. */
    fun resolve(request: NavigationRequest): NavigationResult
}

data class NavigationResolverLimits(
    val timeoutMillis: Long = 2_000,
    val terminationGraceMillis: Long = 200,
    val streamJoinMillis: Long = 500,
    val maxInputBytes: Int = 4 * 1024 * 1024,
    val maxOutputBytes: Int = 1024 * 1024,
    val maxSourceBytes: Int = 2 * 1024 * 1024,
    val maxModules: Int = 64,
    val maxReexportDepth: Int = 8,
) {
    init {
        require(timeoutMillis > 0)
        require(terminationGraceMillis >= 0)
        require(streamJoinMillis > 0)
        require(maxInputBytes > 0)
        require(maxOutputBytes > 0)
        require(maxSourceBytes > 0)
        require(maxModules > 0)
        require(maxReexportDepth in 1..64)
    }
}

/** Unicode-index conversion helpers for the editor integration boundary. */
object NavigationPositions {
    /** Returns null for an out-of-range offset or an offset splitting a surrogate pair. */
    fun utf16OffsetToCodePointOffset(text: String, utf16Offset: Int): Int? {
        if (utf16Offset !in 0..text.length) return null
        if (
            utf16Offset > 0 &&
            utf16Offset < text.length &&
            Character.isHighSurrogate(text[utf16Offset - 1]) &&
            Character.isLowSurrogate(text[utf16Offset])
        ) {
            return null
        }
        return text.codePointCount(0, utf16Offset)
    }

    /** Returns null for an invalid line or code-point column. */
    fun codePointColumnToUtf16(text: String, line: Int, codePointColumn: Int): Int? {
        if (line < 0 || codePointColumn < 0) return null
        var lineStart = 0
        repeat(line) {
            val newline = text.indexOf('\n', lineStart)
            if (newline < 0) return null
            lineStart = newline + 1
        }
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        val lineCodePoints = text.codePointCount(lineStart, lineEnd)
        if (codePointColumn > lineCodePoints) return null
        return text.offsetByCodePoints(lineStart, codePointColumn) - lineStart
    }
}
