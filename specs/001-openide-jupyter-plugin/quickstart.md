# Quickstart: OpenIDE Jupyter Notebook Plugin

**Date**: 2026-05-01

## Prerequisites

- OpenIDE 2025.3 (or IntelliJ IDEA 2025.3+)
- JDK 21+ (for plugin development)
- Gradle 8.x (included via wrapper)
- Python 3.9+ with `jupyter` and `ipykernel` packages (for testing)

## Setup

1. Clone the repository and open in OpenIDE/IntelliJ IDEA
2. The project uses `org.jetbrains.intellij.platform` Gradle plugin targeting IntelliJ Platform 2025.3
3. Sync Gradle to download platform dependencies

## Build

```bash
./gradlew buildPlugin
```

The plugin ZIP will be generated in `build/distributions/`.

## Run (Development)

```bash
./gradlew runIde
```

This launches a sandboxed OpenIDE/IntelliJ instance with the plugin installed.

## Test

```bash
./gradlew test
```

## Install in OpenIDE

1. Build the plugin: `./gradlew buildPlugin`
2. In OpenIDE: Settings → Plugins → gear icon → Install Plugin from Disk
3. Select the ZIP from `build/distributions/`
4. Restart OpenIDE

## Manual Testing

1. Open a project that has a Python interpreter configured
2. Ensure the interpreter's environment has `jupyter` and `ipykernel` installed:
   ```bash
   pip install jupyter ipykernel
   ```
3. Create or open a `.ipynb` file — it should open in the notebook editor
4. Click "Start Kernel" to launch a kernel
5. Write code in a cell and execute it

## Key Dependencies

- `org.jetbrains.intellij.platform` — IntelliJ Platform Gradle Plugin 2.x
- `org.zeromq:jeromq` — Pure Java ZeroMQ for kernel communication
- `com.google.code.gson:gson` — JSON parsing for `.ipynb` files and Jupyter messages
- IntelliJ Python plugin (`PythonCore`) — for Python SDK detection
