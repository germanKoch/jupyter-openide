# Tasks: OpenIDE Jupyter Notebook Plugin

**Input**: Design documents from `specs/001-openide-jupyter-plugin/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Reconfigure the existing Gradle project as an IntelliJ Platform plugin with all dependencies

- [x] T001 Reconfigure build.gradle.kts: replace kotlin("jvm") with IntelliJ Platform Gradle Plugin 2.x (`org.jetbrains.intellij.platform`), add JeroMQ (`org.zeromq:jeromq`) and Gson (`com.google.code.gson:gson`) dependencies, target IntelliJ Platform 2025.3, set JVM 21, add optional dependency on PythonCore plugin in src/main/kotlin/com/openide/jupyter/ package root
- [x] T002 Update settings.gradle.kts: add IntelliJ Platform Gradle Plugin repository, update rootProject.name to "jupyter-openide"
- [x] T003 Create plugin descriptor in src/main/resources/META-INF/plugin.xml: define plugin id (`com.openide.jupyter`), name, vendor, description, depends on `com.intellij.modules.platform`, optional depends on Python plugin with config-file attribute
- [x] T004 [P] Create package directory structure under src/main/kotlin/com/openide/jupyter/ with subdirectories: filetype/, editor/, model/, kernel/, actions/, python/
- [x] T005 [P] Create test package directory structure under src/test/kotlin/com/openide/jupyter/ with subdirectories: model/, kernel/, editor/

**Checkpoint**: Project builds and `./gradlew runIde` launches a sandboxed IDE instance

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core data model and serialization that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T006 [P] Implement CellType enum (CODE, MARKDOWN) and OutputType enum (STREAM, EXECUTE_RESULT, DISPLAY_DATA, ERROR) in src/main/kotlin/com/openide/jupyter/model/CellOutput.kt
- [x] T007 [P] Implement KernelStatus enum (DISCONNECTED, STARTING, IDLE, BUSY, RESTARTING) in src/main/kotlin/com/openide/jupyter/kernel/KernelStatus.kt
- [x] T008 [P] Implement Cell data class with fields: id, cellType, source, outputs (List<CellOutput>), executionCount, metadata — per data-model.md in src/main/kotlin/com/openide/jupyter/model/Cell.kt
- [x] T009 [P] Implement CellOutput data class with fields: outputType, text, data (Map<String, Any>?), ename, evalue, traceback — per data-model.md in src/main/kotlin/com/openide/jupyter/model/CellOutput.kt
- [x] T010 Implement Notebook data class with fields: filePath, nbformatVersion, nbformatMinor, metadata (NotebookMetadata), cells (List<Cell>), isDirty — and NotebookMetadata data class in src/main/kotlin/com/openide/jupyter/model/Notebook.kt (depends on T008, T009)
- [x] T011 Implement NotebookSerializer: parse .ipynb JSON (Gson) into Notebook model and serialize Notebook back to .ipynb JSON, validate nbformat version = 4, handle malformed JSON gracefully (return error result) in src/main/kotlin/com/openide/jupyter/model/NotebookSerializer.kt (depends on T010)
- [x] T012 Implement JupyterNotebookFileType: register `.ipynb` file extension, set icon, displayName "Jupyter Notebook" in src/main/kotlin/com/openide/jupyter/filetype/JupyterNotebookFileType.kt
- [x] T013 Register JupyterNotebookFileType in src/main/resources/META-INF/plugin.xml as `<fileType>` extension

**Checkpoint**: Foundation ready — Notebook model can parse/serialize .ipynb files, file type is registered

---

## Phase 3: User Story 1 — Open and View Jupyter Notebook (Priority: P1) 🎯 MVP

**Goal**: User double-clicks a `.ipynb` file and sees all cells (code with syntax highlighting, markdown rendered) and saved outputs (text, tables, images) in a dedicated editor tab

**Independent Test**: Open any existing `.ipynb` file and verify all cell types and outputs render correctly

### Implementation for User Story 1

- [x] T014 [US1] Create JCEF notebook HTML template in src/main/resources/notebook/notebook.html: define the notebook container layout with cell structure (code cell area, output area, markdown area), include placeholders for dynamic cell injection
- [x] T015 [P] [US1] Create notebook CSS styles in src/main/resources/notebook/notebook.css: style code cells (monospace font, line numbers gutter), markdown cells (rendered HTML), output areas (text, images, tables), cell separators, scrollable container
- [x] T016 [P] [US1] Create notebook JS bridge in src/main/resources/notebook/notebook.js: implement functions for addCell(id, type, source, outputs), updateCell(id, source), removeCell(id), clearOutputs(id), appendOutput(id, outputHtml), scrollToCell(id), and callback registration for Kotlin↔JS communication via JBCefJSQuery
- [x] T017 [US1] Implement NotebookPanel: create JBCefBrowser instance, load notebook.html, implement methods to render a Notebook model by calling JS bridge functions for each cell, handle MIME type selection (text/html > image/png > image/svg+xml > text/plain) for output rendering, convert base64 PNG/SVG to img tags in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (depends on T014, T015, T016)
- [x] T018 [US1] Implement JupyterNotebookEditor: implement FileEditor interface, create toolbar panel (placeholder for kernel actions), embed NotebookPanel as main component, load notebook via NotebookSerializer on open, implement getFile(), getName(), isModified(), getComponent() in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt (depends on T017, T011)
- [x] T019 [US1] Implement JupyterNotebookEditorProvider: implement FileEditorProvider, accept() checks for .ipynb extension, createEditor() returns JupyterNotebookEditor, set policy to HIDE_DEFAULT_EDITOR in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditorProvider.kt (depends on T018)
- [x] T020 [US1] Register JupyterNotebookEditorProvider in src/main/resources/META-INF/plugin.xml as `<fileEditorProvider>` extension
- [x] T021 [US1] Handle malformed .ipynb files: when NotebookSerializer returns error, show IDE notification with error message and offer to open file as plain text editor via FileEditorManager in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditorProvider.kt (update existing)
- [x] T022 [US1] Implement markdown rendering in NotebookPanel: convert markdown cell source to HTML (headers, bold, italic, links, lists, code blocks) using a lightweight markdown-to-HTML converter, render via JS bridge in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (update existing)

**Checkpoint**: User can open .ipynb files, see code cells with highlighting, rendered markdown, and saved outputs (text, tables, images). This is a functional read-only notebook viewer (MVP).

---

## Phase 4: User Story 2 — Start Jupyter Kernel from Project venv (Priority: P1)

**Goal**: User explicitly starts a Jupyter kernel using the IDE-configured Python interpreter, sees kernel status, gets one-click install if Jupyter is missing

**Independent Test**: Open a notebook, click "Start Kernel", verify kernel starts and status indicator shows idle

### Implementation for User Story 2

- [x] T023 [P] [US2] Implement PythonSdkDetector: use ProjectJdkTable API to find the project's configured Python SDK, extract Python executable path, handle case when no Python SDK is configured (return null) in src/main/kotlin/com/openide/jupyter/python/PythonSdkDetector.kt
- [x] T024 [P] [US2] Implement KernelConnectionInfo data class: parse kernel connection JSON file (ip, transport, shell/iopub/stdin/control/hb ports, key, signatureScheme, kernelName) using Gson in src/main/kotlin/com/openide/jupyter/kernel/KernelConnection.kt
- [x] T025 [P] [US2] Implement MessageSigner: HMAC-SHA256 signing of Jupyter messages using the key from connection file, implement sign(key, header, parentHeader, metadata, content) returning hex digest in src/main/kotlin/com/openide/jupyter/kernel/MessageSigner.kt
- [x] T026 [P] [US2] Implement JupyterMessage data class: header (msg_id, msg_type, session, username, timestamp, version), parent_header, metadata, content fields, implement serialize/deserialize to/from JSON, implement toZmqFrames() and fromZmqFrames() for multipart ZeroMQ encoding with delimiter and HMAC signature in src/main/kotlin/com/openide/jupyter/kernel/JupyterMessage.kt (depends on T025)
- [x] T027 [US2] Implement KernelConnection: manage 5 JeroMQ sockets (ZContext, ZMQ.DEALER for shell/stdin/control, ZMQ.SUB for iopub, ZMQ.REQ for heartbeat), connect to ports from KernelConnectionInfo, implement sendMessage(socket, message), receiveMessage(socket) with HMAC validation, subscribe iopub to all topics, implement close() to disconnect all sockets in src/main/kotlin/com/openide/jupyter/kernel/KernelConnection.kt (update, depends on T024, T026)
- [x] T028 [US2] Implement KernelManager: launch kernel subprocess via GeneralCommandLine (`python -m ipykernel_launcher -f {connection_file}`), generate connection file with random available ports and HMAC key, create OSProcessHandler, wait for kernel ready (kernel_info_request/reply on shell), manage KernelStatus state transitions (DISCONNECTED→STARTING→IDLE), implement stop() to send shutdown_request then destroy process, register with Disposer for cleanup in src/main/kotlin/com/openide/jupyter/kernel/KernelManager.kt (depends on T023, T027)
- [x] T029 [US2] Implement StartKernelAction: AnAction that gets PythonSdkDetector, checks for Python SDK, checks if ipykernel is available (run `python -c "import ipykernel"` via GeneralCommandLine), if missing show notification with "Install" action button that runs `python -m pip install jupyter ipykernel`, if available call KernelManager.start(), update editor toolbar state in src/main/kotlin/com/openide/jupyter/actions/StartKernelAction.kt (depends on T028)
- [x] T030 [US2] Implement StopKernelAction: AnAction that calls KernelManager.stop() for current notebook's kernel, update toolbar state in src/main/kotlin/com/openide/jupyter/actions/StopKernelAction.kt
- [x] T031 [US2] Add kernel status indicator to JupyterNotebookEditor toolbar: display KernelStatus as a label/icon in the editor toolbar, subscribe to KernelManager status changes, update indicator on status transitions in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt (update existing)
- [x] T032 [US2] Register StartKernelAction and StopKernelAction in src/main/resources/META-INF/plugin.xml as `<action>` elements in the editor toolbar group
- [x] T033 [US2] Handle no-Python-configured edge case: when PythonSdkDetector returns null, show notification directing user to configure Python interpreter in IDE project settings in src/main/kotlin/com/openide/jupyter/actions/StartKernelAction.kt (update existing)

**Checkpoint**: User can start/stop a kernel per notebook, see status transitions, get one-click Jupyter install if missing. Core kernel infrastructure is ready.

---

## Phase 5: User Story 3 — Execute Code Cells (Priority: P2)

**Goal**: User executes individual cells or all cells, sees output inline, can interrupt long-running computations

**Independent Test**: Run a cell with `1+1`, verify output "2" appears. Run a cell with `import time; time.sleep(60)`, interrupt it.

**Depends on**: US1 (notebook viewer), US2 (kernel management)

### Implementation for User Story 3

- [x] T034 [US3] Implement IOPub message listener in KernelManager: spawn background thread polling iopub socket, parse incoming messages (status, stream, execute_result, display_data, error), dispatch to registered callbacks keyed by parent msg_id, update KernelStatus on status messages in src/main/kotlin/com/openide/jupyter/kernel/KernelManager.kt (update existing)
- [x] T035 [US3] Implement cell execution in KernelManager: sendExecuteRequest(code) builds execute_request message per contracts/jupyter-messages.md, sends on shell socket, returns msg_id for callback matching, track cell execution state (IDLE→QUEUED→EXECUTING→IDLE/ERROR) in src/main/kotlin/com/openide/jupyter/kernel/KernelManager.kt (update existing)
- [x] T036 [US3] Implement output rendering pipeline in NotebookPanel: handle stream outputs (append text), execute_result/display_data (render MIME bundle selecting richest type), error (render traceback with ANSI stripping), update execution count display, all via JS bridge calls in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (update existing)
- [x] T037 [US3] Implement RunCellAction: AnAction that gets current cell from editor, calls KernelManager.sendExecuteRequest(cell.source), registers IOPub callback to update cell outputs in NotebookPanel, shows visual indicator on executing cell in src/main/kotlin/com/openide/jupyter/actions/RunCellAction.kt
- [x] T038 [US3] Implement RunAllCellsAction: AnAction that iterates all code cells top-to-bottom, executes each sequentially (wait for execute_reply before sending next), update outputs as each cell completes in src/main/kotlin/com/openide/jupyter/actions/RunAllCellsAction.kt
- [x] T039 [US3] Implement InterruptKernelAction: AnAction that sends interrupt_request on control socket via KernelManager, update cell state back to IDLE in src/main/kotlin/com/openide/jupyter/actions/InterruptKernelAction.kt
- [x] T040 [US3] Register RunCellAction, RunAllCellsAction, InterruptKernelAction in src/main/resources/META-INF/plugin.xml as `<action>` elements, assign keyboard shortcuts (Shift+Enter for run cell, Ctrl+Shift+Enter for run all)
- [x] T041 [US3] Add cell execution visual state to NotebookPanel: show executing indicator (e.g., spinner or [*]) on currently running cell, show queued indicator for cells waiting in Run All, clear indicators on completion/interrupt via JS bridge in src/main/resources/notebook/notebook.js (update existing) and src/main/resources/notebook/notebook.css (update existing)

**Checkpoint**: User can execute cells, see outputs inline (text, images, tables, errors), interrupt running cells, run all cells. Full interactive notebook workflow works.

---

## Phase 6: User Story 4 — Edit Notebook Cells (Priority: P2)

**Goal**: User can edit code and markdown cells, add/delete cells, save changes back to .ipynb file

**Independent Test**: Edit a code cell, save file, verify .ipynb on disk reflects changes in valid JSON format

**Depends on**: US1 (notebook viewer)

### Implementation for User Story 4

- [x] T042 [US4] Make code cells editable in NotebookPanel: when user clicks a code cell, replace the read-only display with an editable text area (via JS bridge), add syntax highlighting for Python (use CodeMirror or simple `<textarea>` with monospace styling), sync edits back to the Cell model via JBCefJSQuery callback in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (update existing) and src/main/resources/notebook/notebook.js (update existing)
- [x] T043 [US4] Make markdown cells editable in NotebookPanel: on double-click show raw markdown source in editable area, on blur re-render markdown to HTML, sync edits back to Cell model in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (update existing) and src/main/resources/notebook/notebook.js (update existing)
- [x] T044 [US4] Implement save functionality in JupyterNotebookEditor: on standard IDE save action (Ctrl+S), serialize current Notebook model back to .ipynb JSON via NotebookSerializer, write to VirtualFile, clear isDirty flag, update editor tab title (remove modification indicator) in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt (update existing)
- [x] T045 [US4] Track dirty state: when any cell source is modified via JS callback, set Notebook.isDirty = true, update editor modification indicator (asterisk in tab title), implement isModified() in FileEditor in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt (update existing)
- [x] T046 [P] [US4] Implement AddCellAction: AnAction that inserts a new empty cell (code or markdown, selectable) at the current position in the cell list, update NotebookPanel via JS bridge addCell(), set notebook dirty in src/main/kotlin/com/openide/jupyter/actions/AddCellAction.kt
- [x] T047 [P] [US4] Implement DeleteCellAction: AnAction that removes the currently selected cell from the cell list, update NotebookPanel via JS bridge removeCell(), set notebook dirty in src/main/kotlin/com/openide/jupyter/actions/DeleteCellAction.kt
- [x] T048 [US4] Register AddCellAction and DeleteCellAction in src/main/resources/META-INF/plugin.xml as `<action>` elements in editor toolbar and cell context menu

**Checkpoint**: User can edit code/markdown cells, add/delete cells, save to .ipynb. Full editing workflow functional.

---

## Phase 7: User Story 5 — Manage Kernel Lifecycle (Priority: P3)

**Goal**: User can restart kernel, kernels are cleaned up on IDE shutdown, each project's kernels are isolated

**Independent Test**: Restart a kernel mid-session, verify variables are cleared while notebook content is preserved

**Depends on**: US2 (kernel start/stop)

### Implementation for User Story 5

- [x] T049 [US5] Implement RestartKernelAction: AnAction that calls KernelManager.stop() then KernelManager.start(), transition through RESTARTING state, clear all cell execution counts and outputs in NotebookPanel in src/main/kotlin/com/openide/jupyter/actions/RestartKernelAction.kt
- [x] T050 [US5] Implement project-level kernel registry: create a project-scoped service (ProjectService) that tracks all active KernelManager instances for the project, implement disposeAllKernels() called on project close in src/main/kotlin/com/openide/jupyter/kernel/KernelRegistry.kt
- [x] T051 [US5] Register KernelRegistry as a project service in src/main/resources/META-INF/plugin.xml, wire JupyterNotebookEditor to register/unregister kernels with the registry on start/stop
- [x] T052 [US5] Implement IDE shutdown cleanup: register an AppLifecycleListener or use Disposer hierarchy to ensure all kernel processes across all projects are terminated within 5 seconds on IDE exit, send shutdown_request first, then destroy process if still running in src/main/kotlin/com/openide/jupyter/kernel/KernelRegistry.kt (update existing)
- [x] T053 [US5] Register RestartKernelAction in src/main/resources/META-INF/plugin.xml as `<action>` element in editor toolbar

**Checkpoint**: Kernel lifecycle is fully managed — restart works, IDE shutdown cleans up all kernels, multi-project isolation works.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Edge cases, robustness, and quality improvements that affect multiple user stories

- [ ] T054 [P] Handle external file modification: detect VirtualFile changes via VirtualFileListener, prompt user to reload or keep current version in JupyterNotebookEditor in src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt (update existing)
- [x] T055 [P] Handle kernel process crash: detect unexpected process termination in KernelManager via OSProcessHandler termination callback, transition status to DISCONNECTED, show notification allowing user to restart in src/main/kotlin/com/openide/jupyter/kernel/KernelManager.kt (update existing)
- [ ] T056 [P] Handle large notebooks: implement lazy/virtual cell rendering in NotebookPanel — only render visible cells plus a buffer, load more on scroll via JS bridge, prevent IDE freeze on notebooks with hundreds of cells in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (update existing)
- [x] T057 [P] Add ANSI color code handling for error tracebacks: strip or convert ANSI escape sequences to HTML spans with appropriate CSS colors in output rendering in src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt (update existing)
- [ ] T058 Run quickstart.md validation: verify build, runIde, and test commands work end-to-end per specs/001-openide-jupyter-plugin/quickstart.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational — no dependencies on other stories
- **US2 (Phase 4)**: Depends on Foundational — no dependencies on other stories (can parallel with US1)
- **US3 (Phase 5)**: Depends on US1 + US2 (needs viewer + kernel)
- **US4 (Phase 6)**: Depends on US1 (needs viewer) — can parallel with US2 and US3
- **US5 (Phase 7)**: Depends on US2 (needs kernel management)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

```
Phase 1: Setup
    ↓
Phase 2: Foundational
    ↓
    ├── US1 (Phase 3) ──────┐
    │                        ├── US3 (Phase 5)
    ├── US2 (Phase 4) ──��───┘
    │       │                    
    │       └── US5 (Phase 7)
    │
    └── US4 (Phase 6) [needs US1 only]
            ↓
Phase 8: Polish
```

### Within Each User Story

- Models/data before services/logic
- Core implementation before UI integration
- Register actions in plugin.xml after implementation
- Story complete before moving to next priority

### Parallel Opportunities

- T004, T005 (directory creation) can run in parallel
- T006, T007, T008, T009 (model classes) can run in parallel
- T023, T024, T025, T026 (US2 foundation) can run in parallel
- T046, T047 (add/delete cell actions) can run in parallel
- US1 and US2 can proceed in parallel after Foundational phase
- US4 can proceed in parallel with US2 (only needs US1)
- All Polish tasks (T054-T057) can run in parallel

---

## Parallel Example: User Story 2

```bash
# Launch all independent US2 foundation tasks together:
Task: T023 "PythonSdkDetector in python/PythonSdkDetector.kt"
Task: T024 "KernelConnectionInfo in kernel/KernelConnection.kt"
Task: T025 "MessageSigner in kernel/MessageSigner.kt"
Task: T026 "JupyterMessage in kernel/JupyterMessage.kt"

# Then sequentially:
Task: T027 "KernelConnection (depends on T024, T026)"
Task: T028 "KernelManager (depends on T023, T027)"
Task: T029 "StartKernelAction (depends on T028)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup → project builds as IntelliJ plugin
2. Complete Phase 2: Foundational → Notebook model parses .ipynb files
3. Complete Phase 3: User Story 1 → **Read-only notebook viewer works**
4. **STOP and VALIDATE**: Open real .ipynb files, verify rendering
5. This is a usable product — a notebook viewer for OpenIDE

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US1 → Test independently → **MVP: Notebook Viewer**
3. Add US2 → Test independently → **Kernel Management**
4. Add US3 → Test independently → **Interactive Execution** (killer feature)
5. Add US4 → Test independently → **Full Editing**
6. Add US5 → Test independently → **Lifecycle Management**
7. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers after Foundational is complete:

- Developer A: US1 (notebook viewer) → US3 (execution, after US2 is ready)
- Developer B: US2 (kernel management) → US5 (lifecycle)
- Developer C: US4 (editing, after US1 is ready) → Polish

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- JCEF rendering approach means HTML/CSS/JS changes can be iterated quickly via browser dev tools in the sandboxed IDE
