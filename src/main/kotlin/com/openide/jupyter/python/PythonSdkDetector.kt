package com.openide.jupyter.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk

object PythonSdkDetector {

    fun detectPythonInterpreter(project: Project): String? {
        val allSdks = ProjectJdkTable.getInstance().allJdks
        val pythonSdk = allSdks.firstOrNull { sdk ->
            sdk.sdkType.name.contains("Python", ignoreCase = true)
        }
        return pythonSdk?.homePath
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
        return try {
            val process = ProcessBuilder(
                pythonPath, "-m", "pip", "install", "jupyter", "ipykernel"
            ).redirectErrorStream(true).start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }
}
