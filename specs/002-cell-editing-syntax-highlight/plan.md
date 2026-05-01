# Implementation Plan: Cell Editing & Syntax Highlighting

**Branch**: `002-cell-editing-syntax-highlight` | **Date**: 2026-05-02 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-cell-editing-syntax-highlight/spec.md`

## Summary

Add inline cell editing (double-click to edit, Escape to exit), Python syntax highlighting with cross-cell variable awareness, and Jupyter/Colab-style cell management buttons ("+" between cells on hover, "×" delete in cell header). All rendering and interaction happens in the JCEF browser component via JavaScript/CSS; Kotlin side handles model sync and bridge callbacks.

## Technical Context

**Language/Version**: Kotlin 2.x (JVM 21) + JavaScript (ES6, JCEF/Chromium)  
**Primary Dependencies**: IntelliJ Platform SDK 2025.3, JCEF (bundled), JeroMQ, Gson  
**Storage**: N/A (in-memory notebook model, .ipynb file on save)  
**Testing**: Manual testing in IntelliJ sandbox (no automated test framework configured)  
**Target Platform**: OpenIDE / IntelliJ IDEA Community 2025.1+  
**Project Type**: IDE plugin (desktop-app)  
**Performance Goals**: Syntax highlighting < 200ms per cell re-render, editing input latency imperceptible  
**Constraints**: All UI rendering in JCEF `contentEditable` — no external JS libraries (bundle size), no iframe sandboxing  
**Scale/Scope**: Single notebook open at a time, typically < 100 cells

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution is unpopulated (template placeholders only). No gates to enforce. Proceeding.

## Project Structure

### Documentation (this feature)

```text
specs/002-cell-editing-syntax-highlight/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (via /speckit.tasks)
```

### Source Code (repository root)

```text
src/main/kotlin/com/openide/jupyter/
├── actions/             # IDE actions (AddCell, DeleteCell, RunCell, etc.)
├── editor/              # JupyterNotebookEditor, NotebookPanel (JCEF bridge)
├── filetype/            # .ipynb file type registration
├── kernel/              # KernelManager, ZMQ communication
├── model/               # Cell, Notebook, NotebookSerializer
└── python/              # PythonSdkDetector

src/main/resources/notebook/
├── notebook.js          # Cell rendering, selection, editing, highlighting (PRIMARY CHANGE AREA)
├── notebook.css         # Styles including syntax token colors (PRIMARY CHANGE AREA)
└── notebook.html        # HTML template

tests/                   # (empty — manual testing only)
```

**Structure Decision**: All changes fit within the existing structure. JS/CSS files are the primary change area for UI features. Kotlin changes are limited to NotebookPanel.kt (new bridge callbacks) and JupyterNotebookEditor.kt (model sync for new operations).

## Phase 0: Research — Complete

See [research.md](research.md). All 7 technical decisions documented:
1. Custom regex tokenizer (no external JS libs)
2. `contentEditable` for editing
3. Re-tokenize on input with cursor restoration
4. Regex-based cross-cell variable extraction
5. CSS hover zones for "+" button
6. Always-visible "×" in cell header
7. Tab/Escape keyboard handling

## Phase 1: Design & Contracts — Complete

Generated artifacts:
- [data-model.md](data-model.md) — Entity definitions, state transitions, JS-only concepts (SyntaxToken, VariableScope)
- [contracts/js-kotlin-bridge.md](contracts/js-kotlin-bridge.md) — JS↔Kotlin bridge contract (new `addCell`, `deleteCell` callbacks; internal JS functions)
- [quickstart.md](quickstart.md) — Implementation order, build/test instructions, constraints

### Constitution Re-Check (Post Phase 1)

Constitution is unpopulated. No gates to enforce. Proceeding to Phase 2.

## Next Step

Run `/speckit-tasks` to generate the Phase 2 task breakdown.
