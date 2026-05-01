# Data Model: Cell Editing & Syntax Highlighting

## Existing Entities (unchanged)

### Cell
Already defined in `model/Cell.kt`. No schema changes needed.

| Field | Type | Notes |
|-------|------|-------|
| id | String (UUID) | Primary key |
| cellType | CellType (CODE, MARKDOWN) | Immutable after creation |
| source | String | Mutable — updated via edit bridge |
| outputs | MutableList\<CellOutput\> | Code cells only |
| executionCount | Int? | Set by kernel |
| metadata | MutableMap\<String, Any\> | Preserved on save |
| executionState | CellExecutionState | IDLE, QUEUED, EXECUTING, ERROR |

### Notebook
Already defined in `model/Notebook.kt`. No schema changes needed.

| Field | Type | Notes |
|-------|------|-------|
| cells | MutableList\<Cell\> | Ordered; add/remove operations mutate this list |
| isDirty | Boolean | Set true on any cell source/structure change |

## New Concepts (JS-only, no Kotlin model changes)

### SyntaxToken (JavaScript)
A classified segment of source code produced by the regex tokenizer. Exists only in the JS rendering layer — not persisted.

| Field | Type | Description |
|-------|------|-------------|
| type | string | `keyword`, `builtin`, `string`, `number`, `comment`, `decorator`, `known-var`, `text` |
| value | string | The raw text of the token |

### VariableScope (JavaScript)
The cumulative set of user-defined names extracted from code cells. Built by scanning cells in order. Exists only in the JS rendering layer.

| Field | Type | Description |
|-------|------|-------------|
| names | Set\<string\> | All extracted variable/function/class/import names |

**Extraction patterns** (applied per code cell, top-level lines only):
- `^(\w+)\s*=` — assignment
- `^def\s+(\w+)` — function
- `^class\s+(\w+)` — class
- `^import\s+(\w+)` — import
- `^from\s+\w+\s+import\s+(.+)` — from-import (split on comma, trim)
- `(\w+)\s+as\s+(\w+)` — alias (use alias name)
- `^for\s+(\w+)\s+in` — loop variable

### CellEditState (JavaScript)
Tracked per cell via DOM classes and `contentEditable` attribute. No explicit data structure — state is implicit in DOM.

| State | DOM Indicator | Transitions |
|-------|--------------|-------------|
| Read-only | `contentEditable='false'`, no `.editable` class | → Editing (double-click) |
| Editing | `contentEditable='true'`, `.editable` class | → Read-only (Escape, click outside) |
| Executing | `.executing` class | Editing disabled while executing |

## State Transitions

### Cell Edit Lifecycle
```
[Read-only] --double-click source--> [Editing]
[Editing]   --Escape/click outside-> [Read-only]
[Editing]   --Shift+Enter----------> [Read-only] → [Executing]
[Executing] --status:idle----------> [Read-only]
```

### Cell Add/Delete
```
Click "+" gap button → dropdown (Code | Markdown)
  → Select type → new Cell inserted at position → Notebook.isDirty = true

Click "×" header button → Cell removed from Notebook.cells → UI removal → Notebook.isDirty = true
```

## Persistence

No new persistence requirements. All changes flow through the existing `NotebookSerializer.serialize()` path on save. The `Notebook.cells` list is the source of truth; JS rendering is derived.
