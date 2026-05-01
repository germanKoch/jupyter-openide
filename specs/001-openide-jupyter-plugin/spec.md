# Feature Specification: OpenIDE Jupyter Notebook Plugin

**Feature Branch**: `001-openide-jupyter-plugin`  
**Created**: 2026-05-01  
**Status**: Draft  
**Input**: User description: "Плагин для IDE OpenIDE для открытия jupyter notebook и запуска jupyter kernel в venv проекта"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Open and View Jupyter Notebook (Priority: P1)

A developer working in OpenIDE double-clicks a `.ipynb` file in the project tree. The IDE opens the notebook in a dedicated editor tab, displaying all cells (code and markdown) with their existing outputs. The developer can read through the notebook, scroll between cells, and see previously saved outputs (text, tables, images) rendered inline.

**Why this priority**: Without the ability to open and view notebooks, no other functionality is useful. This is the foundational capability that all other features build upon.

**Independent Test**: Can be fully tested by opening any existing `.ipynb` file and verifying that all cell types and outputs render correctly. Delivers value as a read-only notebook viewer even without kernel execution.

**Acceptance Scenarios**:

1. **Given** a project with a `.ipynb` file, **When** the user double-clicks the file in the project tree, **Then** the file opens in a dedicated notebook editor tab showing all cells and saved outputs
2. **Given** an open notebook with markdown cells, **When** the user views the notebook, **Then** markdown cells are rendered with formatting (headers, bold, italic, links, code blocks)
3. **Given** an open notebook with code cells that have saved outputs, **When** the user views the notebook, **Then** text outputs, tables, and images are displayed inline below their respective code cells
4. **Given** an open notebook, **When** the user closes the tab and reopens it, **Then** the notebook state is preserved as it was saved on disk

---

### User Story 2 - Start Jupyter Kernel from Project venv (Priority: P1)

A developer opens a notebook in view-only mode and wants to execute code. The developer explicitly starts a Jupyter kernel via a "Start Kernel" action. The plugin uses the project's IDE-configured Python interpreter to launch the kernel. The developer can see the kernel status (starting, idle, busy, disconnected) in the editor. If the configured interpreter does not have Jupyter installed, the plugin notifies the user with clear guidance.

**Why this priority**: Kernel management is the core differentiating feature — running code in the project's own venv ensures reproducibility and correct dependency resolution. Tied with P1 because viewing without execution is of limited value for interactive work.

**Independent Test**: Can be tested by opening a notebook in a project that has a venv with Jupyter installed, verifying the kernel starts, and checking that the kernel status indicator reflects the actual state.

**Acceptance Scenarios**:

1. **Given** a project with a configured Python interpreter that has Jupyter packages, **When** the user explicitly starts the kernel via the "Start Kernel" action, **Then** the plugin starts a Jupyter kernel process using the configured interpreter
2. **Given** a running kernel, **When** the user views the notebook editor, **Then** a kernel status indicator shows the current state (idle, busy, starting, disconnected)
3. **Given** a project with a configured interpreter that does not have Jupyter installed, **When** the user tries to start a kernel, **Then** the plugin displays a notification with a one-click button to install the required packages (`jupyter`, `ipykernel`)
4. **Given** a running kernel, **When** the user explicitly stops the kernel or closes the notebook, **Then** the kernel process is terminated and resources are released

---

### User Story 3 - Execute Code Cells (Priority: P2)

A developer executes individual code cells or all cells in sequence. The output appears inline below each cell. The developer can see which cell is currently being executed and can interrupt long-running computations.

**Why this priority**: Cell execution is the primary interactive workflow, but it depends on both notebook viewing (P1) and kernel management (P1) being functional first.

**Independent Test**: Can be tested by running a cell with a simple expression (e.g., `1+1`), verifying the output appears, then running a cell with a deliberate delay and interrupting it.

**Acceptance Scenarios**:

1. **Given** an open notebook with a running kernel, **When** the user executes a code cell, **Then** the cell's code is sent to the kernel and the output is displayed inline below the cell
2. **Given** a cell that produces text output, **When** execution completes, **Then** the text output appears below the cell with the execution count updated
3. **Given** a cell that produces a plot or image, **When** execution completes, **Then** the image is rendered inline below the cell
4. **Given** a long-running cell, **When** the user requests an interrupt, **Then** the kernel receives an interrupt signal and the cell execution stops
5. **Given** a user selects "Run All Cells", **When** execution proceeds, **Then** cells are executed sequentially from top to bottom, each displaying its output as it completes

---

### User Story 4 - Edit Notebook Cells (Priority: P2)

A developer edits code and markdown cells within the notebook editor. The editor provides syntax highlighting for Python code cells and basic text editing for markdown cells. Changes are saved back to the `.ipynb` file.

**Why this priority**: Editing is essential for the full notebook workflow but the plugin still provides value as a viewer/executor without editing capabilities.

**Independent Test**: Can be tested by modifying a code cell's content, saving the file, and verifying the `.ipynb` file on disk reflects the changes in the correct JSON format.

**Acceptance Scenarios**:

1. **Given** an open notebook, **When** the user clicks on a code cell, **Then** the cell becomes editable with syntax highlighting for Python
2. **Given** an edited notebook, **When** the user saves (via standard IDE save action), **Then** the changes are written to the `.ipynb` file in valid notebook JSON format
3. **Given** an open notebook, **When** the user adds a new cell (code or markdown), **Then** the cell is inserted at the requested position
4. **Given** an open notebook, **When** the user deletes a cell, **Then** the cell is removed and the notebook structure is updated

---

### User Story 5 - Manage Kernel Lifecycle (Priority: P3)

A developer can restart the kernel, change to a different kernel/venv, and view kernel information. When the IDE closes or the project is unloaded, all running kernels are gracefully shut down.

**Why this priority**: Advanced kernel management improves the developer experience but is not essential for the core open-execute workflow.

**Independent Test**: Can be tested by restarting a kernel mid-session and verifying that the kernel state is reset while the notebook content is preserved.

**Acceptance Scenarios**:

1. **Given** a running kernel, **When** the user restarts the kernel, **Then** the kernel process is stopped and a new one is started, clearing all in-memory variables
2. **Given** a running kernel, **When** the IDE is closed, **Then** all kernel processes are gracefully terminated
3. **Given** multiple projects open, **When** each has notebooks with kernels, **Then** each kernel is isolated to its respective project's venv

---

### Edge Cases

- What happens when the `.ipynb` file is malformed or not valid JSON? The plugin should display an error message and offer to open the file as plain text.
- What happens when no Python interpreter is configured in the project settings? The plugin should notify the user and direct them to configure a Python interpreter in the IDE's project settings.
- What happens when the kernel process crashes during execution? The plugin should detect the crash, update the kernel status to "disconnected", and allow the user to restart the kernel.
- What happens when the notebook file is modified externally while open in the editor? The plugin should detect the change and prompt the user to reload or keep the current version.
- What happens when the user opens a very large notebook (hundreds of cells)? The plugin should handle it without freezing the IDE, loading cells progressively if needed.

## Clarifications

### Session 2026-05-01

- Q: Kernel sharing model — one kernel per notebook or shared per project? → A: One kernel per notebook — each notebook starts its own isolated kernel process
- Q: Python environment detection scope — scan for venv directories or use IDE interpreter? → A: Use the IDE-configured project interpreter as the single source of truth; no directory scanning
- Q: Kernel start trigger — auto-start on notebook open or manual? → A: Manual start — user explicitly clicks "Start Kernel"; notebook opens in view-only mode first
- Q: Jupyter not installed — text guidance only or one-click install? → A: One-click install — offer a button to run pip install jupyter ipykernel via the configured interpreter
- Q: Target OpenIDE version — latest only or multiple versions? → A: Latest stable OpenIDE version only; no backward compatibility with older versions

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST open `.ipynb` files in a dedicated notebook editor when the user opens them in the IDE
- **FR-002**: System MUST render code cells with syntax highlighting and display saved outputs (text, images, tables, errors)
- **FR-003**: System MUST render markdown cells with standard formatting (headers, bold, italic, links, lists, code blocks)
- **FR-004**: System MUST detect the project's Python environment by reading the interpreter configured in the IDE's project settings (the single source of truth for the Python binary path)
- **FR-005**: System MUST start a separate Jupyter kernel process per notebook only when the user explicitly initiates it (each notebook has its own isolated kernel; no variable sharing between notebooks; notebook opens in view-only mode until kernel is started)
- **FR-006**: System MUST display kernel status (starting, idle, busy, disconnected) in the notebook editor
- **FR-007**: System MUST send code cell contents to the running kernel for execution and display results inline
- **FR-008**: System MUST support interrupting a running cell execution
- **FR-009**: System MUST support executing all cells sequentially ("Run All")
- **FR-010**: System MUST allow editing of code and markdown cells with changes saved to the `.ipynb` file in valid notebook format
- **FR-011**: System MUST allow adding and deleting cells
- **FR-012**: System MUST terminate all kernel processes when the IDE closes or the project is unloaded
- **FR-013**: System MUST notify the user when Jupyter is not installed in the configured interpreter's environment, and offer a one-click action to install the required packages (`jupyter`, `ipykernel`) using the configured interpreter
- **FR-014**: System MUST handle malformed `.ipynb` files gracefully by showing an error and offering to open as plain text
- **FR-015**: System MUST support display of common output types: plain text, error tracebacks, HTML tables, and PNG/SVG images

### Key Entities

- **Notebook**: Represents a `.ipynb` file — contains an ordered list of cells, metadata (kernel spec, language), and format version
- **Cell**: An individual unit within a notebook — has a type (code or markdown), source content, and optionally outputs and execution count
- **Kernel**: A running Jupyter kernel process — one per open notebook, associated with a specific Python interpreter from a project's venv, has a lifecycle state, and communicates via the Jupyter messaging protocol
- **Virtual Environment (venv)**: The project's Python environment — contains the Python interpreter and installed packages including Jupyter dependencies

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can open and view any standard Jupyter notebook file within 3 seconds of double-clicking it
- **SC-002**: Kernel starts and becomes ready for code execution within 10 seconds of user initiation
- **SC-003**: Simple code cell execution (e.g., arithmetic expression) returns output within 2 seconds after kernel is idle
- **SC-004**: 95% of notebook files created by JupyterLab or standard Jupyter tools open without errors
- **SC-005**: All kernel processes are terminated within 5 seconds of IDE shutdown, with no orphaned processes remaining
- **SC-006**: Users can complete a full workflow (open notebook, start kernel, execute cells, edit, save) without leaving the IDE
- **SC-007**: Plugin does not degrade IDE startup time by more than 1 second

## Assumptions

- OpenIDE is based on the IntelliJ IDEA Platform and supports the standard IntelliJ plugin development approach
- Users have Python installed on their system and have created a virtual environment for their project
- If Jupyter packages are not installed in the configured environment, the plugin offers one-click installation; no manual terminal commands required
- The plugin targets standard Jupyter notebook format (nbformat v4)
- The plugin supports Python kernels only in the initial version; other language kernels are out of scope
- Python environment detection relies solely on the interpreter configured in the IDE's project settings; the plugin does not independently scan for venv directories
- Rich output types beyond text, HTML, and images (e.g., interactive widgets like ipywidgets) are out of scope for the initial version
- The plugin targets the latest stable version of OpenIDE only; no backward compatibility with older versions is required
- The plugin is designed for OpenIDE but should be compatible with other IntelliJ-based IDEs given the shared platform
