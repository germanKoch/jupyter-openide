package com.openide.jupyter.kernel

import java.io.File

enum class KernelOwnership {
    OWNED,
    ATTACHED
}

/** Describes where a kernel connection comes from and who owns its lifecycle. */
sealed interface KernelTarget {
    val ownership: KernelOwnership
    /** Local interpreter whose filesystem is used for static source navigation. */
    val sourceInterpreter: String?

    data class Launch(
        val pythonPath: String,
        val workingDirectory: File? = null
    ) : KernelTarget {
        override val ownership: KernelOwnership = KernelOwnership.OWNED
        override val sourceInterpreter: String get() = pythonPath
    }

    data class ConnectionFile(
        val file: File,
        override val sourceInterpreter: String? = null
    ) : KernelTarget {
        override val ownership: KernelOwnership = KernelOwnership.ATTACHED

        init {
            require(sourceInterpreter == null || sourceInterpreter.isNotBlank()) {
                "sourceInterpreter must be null or non-blank"
            }
        }
    }

    data class Manual(
        val connectionInfo: KernelConnectionInfo,
        override val sourceInterpreter: String? = null
    ) : KernelTarget {
        override val ownership: KernelOwnership = KernelOwnership.ATTACHED

        init {
            require(sourceInterpreter == null || sourceInterpreter.isNotBlank()) {
                "sourceInterpreter must be null or non-blank"
            }
        }
    }
}

data class KernelSourceLocation(
    val file: String,
    val line: Int,
    val column: Int? = null
)

/**
 * Optional seam for a language-aware source resolver.
 *
 * Jupyter's standard inspect reply does not guarantee a source file/line, so
 * KernelManager deliberately does not invent a wire-level source-location API.
 */
fun interface KernelSourceLocationResolver {
    fun request(
        expression: String,
        callback: (Result<KernelSourceLocation?>) -> Unit
    )
}
