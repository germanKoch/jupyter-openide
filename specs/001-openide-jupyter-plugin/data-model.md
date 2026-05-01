# Data Model: OpenIDE Jupyter Notebook Plugin

**Date**: 2026-05-01  
**Feature**: OpenIDE Jupyter Notebook Plugin  
**Spec**: [spec.md](./spec.md)

## Entities

### Notebook

Represents a `.ipynb` file loaded into the editor.

| Field | Type | Description |
|-------|------|-------------|
| filePath | String | Absolute path to the `.ipynb` file on disk |
| nbformatVersion | Int | Notebook format major version (expected: 4) |
| nbformatMinor | Int | Notebook format minor version |
| metadata | NotebookMetadata | Kernel spec, language info, and other metadata |
| cells | List\<Cell\> | Ordered list of cells in the notebook |
| isDirty | Boolean | Whether the notebook has unsaved changes |

**Validation rules**:
- `nbformatVersion` must be 4 (only nbformat v4 supported)
- `cells` must be a valid list (can be empty)
- File must be valid JSON; otherwise treated as malformed (FR-014)

### NotebookMetadata

Metadata block from the `.ipynb` file.

| Field | Type | Description |
|-------|------|-------------|
| kernelSpec | KernelSpec? | Kernel specification (name, display_name, language) |
| languageInfo | LanguageInfo? | Language name, version, mimetype |

### Cell

An individual cell within a notebook.

| Field | Type | Description |
|-------|------|-------------|
| id | String | Unique cell identifier (generated if absent) |
| cellType | CellType | `CODE` or `MARKDOWN` |
| source | String | Cell content (code or markdown text) |
| outputs | List\<CellOutput\> | Execution outputs (code cells only) |
| executionCount | Int? | Execution counter (code cells only, null if not executed) |
| metadata | Map\<String, Any\> | Cell-level metadata (collapsed state, etc.) |

**State transitions (code cells)**:
- `IDLE` → `QUEUED` (user initiates execution)
- `QUEUED` → `EXECUTING` (kernel begins processing)
- `EXECUTING` → `IDLE` (execution complete or interrupted)
- `EXECUTING` → `ERROR` (execution produced an error)
- `ERROR` → `IDLE` (user acknowledges or re-executes)

**Validation rules**:
- `cellType` must be `CODE` or `MARKDOWN` (raw cells treated as markdown)
- `source` can be empty
- `outputs` only present on code cells

### CellOutput

Output produced by a code cell execution.

| Field | Type | Description |
|-------|------|-------------|
| outputType | OutputType | `STREAM`, `EXECUTE_RESULT`, `DISPLAY_DATA`, `ERROR` |
| text | String? | Text content (for STREAM outputs — stdout/stderr) |
| data | Map\<String, Any\>? | MIME-bundle (for EXECUTE_RESULT and DISPLAY_DATA) |
| ename | String? | Error name (for ERROR outputs) |
| evalue | String? | Error value (for ERROR outputs) |
| traceback | List\<String\>? | Error traceback lines (for ERROR outputs) |

**Supported MIME types in `data`**:
- `text/plain` — plain text fallback
- `text/html` — HTML tables, formatted output
- `image/png` — PNG images (base64 encoded)
- `image/svg+xml` — SVG images

### Kernel

A running Jupyter kernel process, one per open notebook.

| Field | Type | Description |
|-------|------|-------------|
| notebookPath | String | Path to the notebook this kernel serves |
| pythonPath | String | Path to the Python interpreter used |
| process | Process | OS process handle for the kernel |
| connectionFile | String | Path to the kernel connection JSON file |
| status | KernelStatus | Current kernel lifecycle state |
| session | String | Unique session identifier (UUID) |
| shellPort | Int | ZeroMQ shell socket port |
| iopubPort | Int | ZeroMQ iopub socket port |
| stdinPort | Int | ZeroMQ stdin socket port |
| controlPort | Int | ZeroMQ control socket port |
| hbPort | Int | ZeroMQ heartbeat socket port |
| key | String | HMAC authentication key |

**State transitions**:
- `DISCONNECTED` → `STARTING` (user clicks "Start Kernel")
- `STARTING` → `IDLE` (kernel reports ready via iopub status message)
- `IDLE` → `BUSY` (kernel begins executing code)
- `BUSY` → `IDLE` (execution completes)
- `BUSY` → `IDLE` (interrupt received)
- Any → `DISCONNECTED` (kernel process dies or user stops it)
- Any → `RESTARTING` (user restarts; transitions to STARTING after process restart)

**Validation rules**:
- `pythonPath` must point to an existing executable
- All ports must be valid (1024–65535)
- `key` must be non-empty for HMAC signing

### KernelConnectionInfo

Contents of the kernel connection file (written by ipykernel on startup).

| Field | Type | Description |
|-------|------|-------------|
| ip | String | IP address (typically "127.0.0.1") |
| transport | String | Transport protocol (typically "tcp") |
| shellPort | Int | Shell channel port |
| iopubPort | Int | IOPub channel port |
| stdinPort | Int | Stdin channel port |
| controlPort | Int | Control channel port |
| hbPort | Int | Heartbeat channel port |
| key | String | HMAC key for message signing |
| signatureScheme | String | Signing scheme (typically "hmac-sha256") |
| kernelName | String | Kernel name (typically "python3") |

## Enumerations

### CellType
- `CODE` — executable code cell
- `MARKDOWN` — markdown/documentation cell

### OutputType
- `STREAM` — stdout/stderr text output
- `EXECUTE_RESULT` — execution result with MIME bundle
- `DISPLAY_DATA` — rich display output with MIME bundle
- `ERROR` — error with traceback

### KernelStatus
- `DISCONNECTED` — no kernel process running
- `STARTING` — kernel process launched, waiting for ready signal
- `IDLE` — kernel running, ready to accept execution requests
- `BUSY` — kernel executing code
- `RESTARTING` — kernel being restarted

## Relationships

```
Notebook 1──* Cell
Cell 1──* CellOutput (code cells only)
Notebook 1──0..1 Kernel (kernel started manually)
Kernel 1──1 KernelConnectionInfo
```
