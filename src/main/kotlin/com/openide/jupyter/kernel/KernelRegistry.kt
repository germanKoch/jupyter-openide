package com.openide.jupyter.kernel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class KernelRegistry : Disposable {

    private val kernels = ConcurrentHashMap<String, KernelManager>()

    fun register(notebookPath: String, manager: KernelManager) {
        kernels[notebookPath] = manager
    }

    fun unregister(notebookPath: String) {
        kernels.remove(notebookPath)
    }

    fun get(notebookPath: String): KernelManager? = kernels[notebookPath]

    fun disposeAllKernels() {
        for ((_, manager) in kernels) {
            try {
                manager.stop()
            } catch (_: Exception) {}
        }
        kernels.clear()
    }

    override fun dispose() {
        disposeAllKernels()
    }

    companion object {
        fun getInstance(project: Project): KernelRegistry {
            return project.getService(KernelRegistry::class.java)
        }
    }
}
