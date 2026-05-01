package com.openide.jupyter.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

object PythonSdkDetector {

    fun detectPythonInterpreter(project: Project, notebookPath: String? = null): String? {
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk != null && projectSdk.sdkType.name.contains("Python", ignoreCase = true)) {
            projectSdk.homePath?.let { return it }
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
                val python = File(dir, "$venvName/bin/python")
                if (python.exists() && python.canExecute()) return python.absolutePath
            }
        }

        for (cmd in listOf("python3", "python")) {
            try {
                val process = ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start()
                if (process.waitFor() == 0) return cmd
            } catch (_: Exception) {}
        }
        return null
    }

    fun checkJupyterInstalled(pythonPath: String): Boolean {
        return try {
            val process = ProcessBuilder(pythonPath, "-c", "import ipykernel")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    fun installJupyter(pythonPath: String): Boolean {
        val strategies = listOf(
            listOf(pythonPath, "-m", "pip", "install", "ipykernel"),
            listOf(pythonPath, "-m", "pip", "install", "--user", "ipykernel"),
            listOf(pythonPath, "-m", "pip", "install", "--break-system-packages", "ipykernel"),
        )
        for (cmd in strategies) {
            try {
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                if (process.waitFor() == 0) return true
            } catch (_: Exception) {}
        }
        return false
    }
}
