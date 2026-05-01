# Contract: JS ↔ Kotlin Bridge (JCEF)

## Direction: JS → Kotlin (via JBCefJSQuery)

### Existing callbacks (no changes)
- `cellSelected(id: string)` — cell clicked
- `cellSourceChanged(id: string, src: string)` — cell source edited (oninput)
- `runCell(id: string)` — run button or Shift+Enter

### New callbacks

#### `addCell(afterCellId: string, cellType: string)`
- **Trigger**: User clicks "+" button and selects cell type
- **Parameters**: 
  - `afterCellId` — ID of the cell above the gap, or `""` for insertion at position 0
  - `cellType` — `"code"` or `"markdown"`
- **Kotlin handler**: Creates new `Cell`, inserts into `Notebook.cells` at correct index, calls `notebookPanel.insertCellAfter()` or `addCellToView()`, sets `isDirty = true`

#### `deleteCell(cellId: string)`
- **Trigger**: User clicks "×" button in cell header
- **Parameters**: `cellId` — ID of the cell to delete
- **Kotlin handler**: Removes cell from `Notebook.cells`, calls `notebookPanel.removeCellFromView()`, sets `isDirty = true`

## Direction: Kotlin → JS (via executeJavaScript)

### Existing functions (no changes)
- `addCell(id, type, source, outputsHtml, executionCount)`
- `removeCell(id)`
- `insertCellAfter(afterId, newId, type, source, outputsHtml, executionCount)`
- `makeEditable(id)` / `makeReadOnly(id)`
- `clearOutputs(id)`, `appendOutput(id, html)`, `setExecutionCount(id, count)`
- `setCellExecuting(id, executing)`

### New functions

#### `highlightAllCells()`
- **Purpose**: Re-run syntax highlighting on all code cells (e.g., after cell add/delete changes variable scope)
- **Behavior**: Rebuilds variable scope from all cells, re-tokenizes and re-renders each code cell's source

#### `highlightCell(id: string)`
- **Purpose**: Re-highlight a single cell (called from Kotlin after source change confirmation)
- **Behavior**: Re-tokenizes cell source with current variable scope, updates innerHTML

### Internal JS functions (not called from Kotlin)

#### `tokenize(source: string, knownNames: Set<string>) → SyntaxToken[]`
- Pure function, no side effects
- Returns ordered array of tokens covering the full source string

#### `buildVariableScope(upToCellIndex: number) → Set<string>`
- Scans all code cells from 0 to `upToCellIndex - 1`
- Extracts names via regex patterns
- Returns cumulative set

#### `renderHighlighted(tokens: SyntaxToken[]) → string`
- Converts token array to HTML with `<span class="tok-{type}">` wrappers
- Plain text tokens get no wrapper

#### `saveCursorOffset(element: HTMLElement) → number`
- Saves cursor position as text offset before innerHTML replacement

#### `restoreCursorOffset(element: HTMLElement, offset: number)`
- Restores cursor to saved text offset after innerHTML replacement

## Bridge Registration

New JBCefJSQuery instances needed in NotebookPanel.kt:
- `addCellQuery: JBCefJSQuery` — handles `addCell` callback
- `deleteCellQuery: JBCefJSQuery` — handles `deleteCell` callback

Bridge injection extends `initBridge({...})` with:
```javascript
addCell: function(afterId, type) { /* addCellQuery handler */ },
deleteCell: function(id) { /* deleteCellQuery handler */ }
```
