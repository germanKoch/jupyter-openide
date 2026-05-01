# Research: OpenIDE Jupyter Notebook Plugin

**Date**: 2026-05-01  
**Feature**: OpenIDE Jupyter Notebook Plugin  
**Spec**: [spec.md](./spec.md)

## R-001: IntelliJ Plugin Custom Editor for .ipynb Files

**Decision**: Use `FileEditorProvider` extension point to register a custom editor for `.ipynb` files.

**Rationale**: This is the standard IntelliJ Platform mechanism for custom file editors. The `FileEditorProvider.accept()` method checks file extension, and `createEditor()` returns a custom `FileEditor` implementation. Setting `FileEditorPolicy.HIDE_DEFAULT_EDITOR` ensures the notebook editor replaces the default text editor.

**Alternatives considered**:
- Custom `FileType` with text editor overlay — too limited for notebook cell layout
- External browser window — poor IDE integration, loses focus/state management

## R-002: Notebook Cell Rendering Approach

**Decision**: Use JCEF (JetBrains Chromium Embedded Framework) for rendering notebook cells, with HTML/CSS for layout and output display.

**Rationale**: JCEF provides `JBCefBrowser` which returns a Swing `JComponent` for seamless IDE integration. HTML/CSS naturally handles the diverse output types notebooks produce (text, tables, images, HTML fragments). Communication between Kotlin and the browser is handled via `JBCefJSQuery` callbacks. JCEF is bundled with IntelliJ Platform 2020.2+ and is the recommended approach for web content rendering.

**Alternatives considered**:
- Pure Swing/JPanel — would require custom renderers for each output type (HTML tables, images, styled text); significantly more complex and less flexible
- JavaFX WebView — not bundled with IntelliJ Platform, adds external dependency, less maintained in JetBrains ecosystem

## R-003: Python SDK Detection from Plugin

**Decision**: Use `ProjectJdkTable` API and Python plugin's `PythonSdkType` to detect the project's configured Python interpreter.

**Rationale**: IntelliJ's `ProjectJdkTable.getInstance().getAllJdks()` provides access to all configured SDKs. Filtering by `PythonSdkType` gives the Python interpreter path. The plugin should depend on the Python plugin (`com.intellij.modules.python` or `PythonCore`) to access these APIs. This aligns with the spec decision to use the IDE-configured interpreter as the single source of truth.

**Alternatives considered**:
- Scanning filesystem for `venv/`/`.venv/` — rejected per spec clarification; IDE config is the source of truth
- Requiring user to configure path in plugin settings — redundant with IDE's existing Python SDK configuration

## R-004: Jupyter Kernel Communication

**Decision**: Start kernel as a subprocess via `python -m ipykernel_launcher`, communicate via JeroMQ (pure Java ZeroMQ implementation) using the Jupyter wire protocol.

**Rationale**: Direct ZeroMQ communication avoids an intermediary Python process and provides full control over message handling. JeroMQ is a mature, pure-Java library (no native dependencies), simplifying plugin distribution. The kernel writes a connection file (JSON) with ports and auth key, which the plugin reads to establish ZeroMQ connections on 5 sockets (shell, iopub, stdin, control, heartbeat).

**Alternatives considered**:
- Thin Python wrapper subprocess (HTTP bridge) — adds complexity of managing two processes, introduces latency, and requires additional Python code distribution
- Launching a full Jupyter Server — heavyweight, unnecessary for single-kernel use case, adds HTTP API overhead
- Kzmq (Kotlin multiplatform ZeroMQ) — promising but less mature than JeroMQ; JeroMQ has stronger ecosystem support

## R-005: Plugin Project Structure (Gradle)

**Decision**: Use `org.jetbrains.intellij.platform` Gradle plugin (2.x) targeting IntelliJ Platform 2025.3. Standard Kotlin/JVM project structure with `plugin.xml` descriptor.

**Rationale**: The IntelliJ Platform Gradle Plugin 2.x is the current recommended build tool for IntelliJ plugins. OpenIDE 2025.3 is based on IntelliJ Platform 2025.3, so we target that platform version. The plugin descriptor (`plugin.xml`) declares extension points, dependencies on the Python plugin, and actions.

**Alternatives considered**:
- IntelliJ Platform Gradle Plugin 1.x — deprecated, fewer features
- DevKit-based project — older approach, Gradle is now standard

## R-006: Kernel Process Lifecycle Management

**Decision**: Use IntelliJ's `GeneralCommandLine` + `OSProcessHandler` for kernel process management, with `Disposer` API for cleanup on editor/project close.

**Rationale**: `GeneralCommandLine` handles cross-platform command construction and escaping. `OSProcessHandler` provides output streaming and process state tracking. The `Disposer` hierarchy ensures kernel processes are terminated when the editor tab is closed, the project is unloaded, or the IDE shuts down — satisfying FR-012.

**Alternatives considered**:
- Raw `ProcessBuilder` — loses IntelliJ's process monitoring and Disposer integration
- IntelliJ's `ExecutionManager` / Run Configuration — too heavyweight; we don't need run configurations or console tabs for kernel processes

## R-007: Code Cell Editor Component

**Decision**: Use IntelliJ's `EditorFactory.createEditor()` with Python `FileType` for code cells, embedded within the JCEF-based notebook layout via a hybrid Swing/JCEF approach.

**Rationale**: Using IntelliJ's native editor for code cells provides syntax highlighting, code completion (if Python plugin supports it), and keyboard shortcuts for free. The notebook layout uses JCEF for overall structure and output rendering, while code cells use embedded IntelliJ editors. This hybrid approach gives the best of both worlds.

**Alternatives considered**:
- Pure JCEF with CodeMirror/Monaco — would lose IntelliJ-native code intelligence and key bindings
- Pure Swing with RSyntaxTextArea — functional but lacks IntelliJ integration depth
