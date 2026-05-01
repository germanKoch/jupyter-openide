# Implementation Plan: OpenIDE Jupyter Notebook Plugin

**Branch**: `001-openide-jupyter-plugin` | **Date**: 2026-05-01 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/001-openide-jupyter-plugin/spec.md`

## Summary

Build an IntelliJ Platform plugin for OpenIDE that opens `.ipynb` files in a dedicated notebook editor, manages Jupyter kernel processes via ZeroMQ (JeroMQ), and supports cell execution, editing, and output rendering. The plugin uses the IDE-configured Python interpreter for kernel launch and JCEF for notebook rendering.

## Technical Context

**Language/Version**: Kotlin 2.x (JVM 21)  
**Primary Dependencies**: IntelliJ Platform SDK 2025.3, JeroMQ (ZeroMQ), Gson, JCEF (bundled)  
**Storage**: File-based (`.ipynb` JSON files on disk)  
**Testing**: JUnit 5 via IntelliJ test framework, manual testing via `runIde`  
**Target Platform**: OpenIDE 2025.3 (IntelliJ Platform 2025.3)  
**Project Type**: IntelliJ Platform plugin  
**Performance Goals**: Notebook open <3s, kernel start <10s, cell execution feedback <2s  
**Constraints**: Plugin must not degrade IDE startup by >1s; kernel processes must be cleaned up on IDE exit  
**Scale/Scope**: Single-user desktop IDE plugin; one kernel per notebook

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution is not configured (template only). No gates to enforce. Proceeding.

**Post-Phase 1 re-check**: No constitution violations — constitution is unconfigured.

## Project Structure

### Documentation (this feature)

```text
specs/001-openide-jupyter-plugin/
├── plan.md              # This file
├── research.md          # Phase 0 output — technical decisions
├── data-model.md        # Phase 1 output — entity definitions
├── quickstart.md        # Phase 1 output — setup guide
├── contracts/
│   ├── plugin-extension-points.md   # IntelliJ plugin.xml contract
│   └── jupyter-messages.md          # Jupyter wire protocol messages
└── tasks.md             # Phase 2 output (via /speckit.tasks)
```

### Source Code (repository root)

```text
src/
├── main/
│   ├── kotlin/com/openide/jupyter/
│   │   ├── filetype/
│   │   │   └── JupyterNotebookFileType.kt          # .ipynb file type registration
│   │   ├── editor/
│   │   │   ├── JupyterNotebookEditorProvider.kt     # FileEditorProvider for .ipynb
│   │   │   ├── JupyterNotebookEditor.kt             # Main notebook editor (FileEditor)
│   │   │   └── NotebookPanel.kt                     # JCEF-based notebook rendering panel
│   │   ├── model/
│   │   │   ├── Notebook.kt                          # Notebook data model
│   │   │   ├── Cell.kt                              # Cell data model
│   │   │   ├── CellOutput.kt                        # Cell output types
│   │   │   └── NotebookSerializer.kt                # .ipynb JSON read/write
│   │   ├── kernel/
│   │   │   ├── KernelManager.kt                     # Kernel process lifecycle
│   │   │   ├── KernelConnection.kt                  # ZeroMQ connection management
│   │   │   ├── JupyterMessage.kt                    # Message serialization/deserialization
│   │   │   ├── MessageSigner.kt                     # HMAC-SHA256 message signing
│   │   │   └── KernelStatus.kt                      # Kernel status enum
│   │   ├── actions/
│   │   │   ├── RunCellAction.kt                     # Execute current cell
│   │   │   ├── RunAllCellsAction.kt                 # Execute all cells
│   │   │   ├── StartKernelAction.kt                 # Start kernel
│   │   │   ├── StopKernelAction.kt                  # Stop kernel
│   │   │   ├── RestartKernelAction.kt               # Restart kernel
│   │   │   ├── InterruptKernelAction.kt             # Interrupt execution
│   │   │   ├── AddCellAction.kt                     # Add code/markdown cell
│   │   │   └── DeleteCellAction.kt                  # Delete cell
│   │   └── python/
│   │       └── PythonSdkDetector.kt                 # Detect configured Python interpreter
│   └── resources/
│       ├── META-INF/
│       │   └── plugin.xml                           # Plugin descriptor
│       └── notebook/
│           ├── notebook.html                        # JCEF notebook HTML template
│           ├── notebook.css                         # Notebook styling
│           └── notebook.js                          # JS bridge for Kotlin ↔ JCEF communication
└── test/
    └── kotlin/com/openide/jupyter/
        ├── model/
        │   ├── NotebookSerializerTest.kt            # .ipynb parsing tests
        │   └── CellTest.kt                          # Cell model tests
        ├── kernel/
        │   ├── JupyterMessageTest.kt                # Message format tests
        │   └── MessageSignerTest.kt                 # HMAC signing tests
        └── editor/
            └── NotebookEditorProviderTest.kt        # Editor registration tests
```

**Structure Decision**: Single IntelliJ plugin project using the standard `src/main/kotlin` + `src/main/resources` layout. Code organized into packages by responsibility: `filetype` (file registration), `editor` (UI), `model` (data), `kernel` (Jupyter communication), `actions` (user actions), `python` (SDK detection). Web resources for JCEF under `resources/notebook/`.

## Complexity Tracking

No constitution violations to justify.
