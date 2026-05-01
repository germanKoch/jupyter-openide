package com.openide.jupyter.kernel

enum class KernelStatus {
    DISCONNECTED,
    STARTING,
    IDLE,
    BUSY,
    RESTARTING
}
