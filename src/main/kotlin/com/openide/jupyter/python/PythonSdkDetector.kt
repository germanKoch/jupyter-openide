package com.openide.jupyter.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.util.concurrent.TimeUnit

object PythonSdkDetector {

    fun detectPythonInterpreter(project: Project, notebookPath: String? = null): String? {
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk != null && projectSdk.sdkType.name.contains("Python", ignoreCase = true)) {
            projectSdk.homePath?.let { homePath ->
                if (
                    isUsableInterpreter(File(homePath)) &&
                    commandSucceeds(listOf(homePath, "--version"), 3, TimeUnit.SECONDS)
                ) {
                    return homePath
                }
            }
        }

        val searchDirs = mutableListOf<File>()
        project.basePath?.let { searchDirs.add(File(it)) }
        if (notebookPath != null) {
            var dir = File(notebookPath).parentFile
            while (dir != null) {
                if (dir !in searchDirs) searchDirs.add(dir)
                dir = dir.parentFile
            }
        }

        for (dir in searchDirs) {
            for (venvName in listOf(".venv", "venv")) {
                val candidates = listOf(
                    File(dir, "$venvName/bin/python"),
                    File(dir, "$venvName/bin/python3"),
                    File(dir, "$venvName/Scripts/python.exe")
                )
                candidates.firstOrNull {
                    isUsableInterpreter(it) && commandSucceeds(
                        listOf(it.absolutePath, "--version"),
                        3,
                        TimeUnit.SECONDS
                    )
                }?.let { return it.absolutePath }
            }
        }

        for (command in listOf(listOf("python3"), listOf("python"))) {
            if (commandSucceeds(command + "--version", 3, TimeUnit.SECONDS)) {
                return command.first()
            }
        }
        return null
    }

    fun checkJupyterInstalled(pythonPath: String): Boolean {
        return commandSucceeds(
            listOf(pythonPath, "-c", "import ipykernel"),
            8,
            TimeUnit.SECONDS
        )
    }

    fun installJupyter(pythonPath: String): Boolean {
        val strategies = listOf(
            listOf(pythonPath, "-m", "pip", "install", "ipykernel"),
            listOf(pythonPath, "-m", "pip", "install", "--user", "ipykernel"),
            listOf(pythonPath, "-m", "pip", "install", "--break-system-packages", "ipykernel"),
        )
        for (cmd in strategies) {
            if (commandSucceeds(cmd, 5, TimeUnit.MINUTES)) return true
        }
        return false
    }

    private fun isUsableInterpreter(file: File): Boolean =
        file.isFile && (file.canExecute() || System.getProperty("os.name").startsWith("Windows", true))

    /** Always bounds subprocesses and discards output so an unconsumed pipe cannot deadlock. */
    internal fun commandSucceeds(command: List<String>, timeout: Long, unit: TimeUnit): Boolean {
        var process: Process? = null
        return try {
            process = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(timeout, unit)) {
                val descendants = try {
                    process.descendants().toList()
                } catch (_: Exception) {
                    emptyList()
                }
                descendants.asReversed().forEach { child ->
                    try { child.destroyForcibly() } catch (_: Exception) {}
                }
                process.toHandle().destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                descendants.forEach { child ->
                    try { if (child.isAlive) child.destroyForcibly() } catch (_: Exception) {}
                }
                if (process.isAlive) process.toHandle().destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
        }
    }
}
