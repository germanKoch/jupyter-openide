package com.openide.jupyter.model

data class KernelSpec(
    val name: String = "python3",
    val displayName: String = "Python 3",
    val language: String = "python"
)

data class LanguageInfo(
    val name: String = "python",
    val version: String = "",
    val mimetype: String = "text/x-python",
    val fileExtension: String = ".py"
)

data class NotebookMetadata(
    val kernelSpec: KernelSpec? = null,
    val languageInfo: LanguageInfo? = null
)

data class Notebook(
    val filePath: String,
    val nbformatVersion: Int = 4,
    val nbformatMinor: Int = 5,
    val metadata: NotebookMetadata = NotebookMetadata(),
    val cells: MutableList<Cell> = mutableListOf(),
    var isDirty: Boolean = false
)
