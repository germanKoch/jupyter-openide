# Quickstart: Cell Editing & Syntax Highlighting

## What changes

All UI features are implemented in the JCEF browser layer (JS/CSS) with Kotlin handling model sync.

### JavaScript (`notebook.js`) — primary change area
1. **Syntax tokenizer**: `tokenize(source, knownNames)` — regex-based Python tokenizer
2. **Variable extractor**: `buildVariableScope(upToCellIndex)` — regex-based name extraction from code cells
3. **Edit mode**: Double-click activates `contentEditable`, Escape exits, Tab inserts 4 spaces
4. **Highlight renderer**: Wraps tokens in `<span class="tok-*">`, replaces innerHTML with cursor restoration
5. **Cell gaps**: `.cell-gap` divs between cells with "+" hover button and type dropdown
6. **Delete button**: "×" button in cell header, calls `kotlinBridge.deleteCell(id)`

### CSS (`notebook.css`)
- Token color classes: `.tok-keyword`, `.tok-string`, `.tok-number`, `.tok-comment`, `.tok-decorator`, `.tok-builtin`, `.tok-known-var`
- Cell gap styling: `.cell-gap`, `.add-cell-btn`, `.add-cell-dropdown`
- Delete button: `.delete-btn`
- Edit mode indicator refinements

### Kotlin (`NotebookPanel.kt`)
- New `JBCefJSQuery` instances: `addCellQuery`, `deleteCellQuery`
- Bridge injection extended with `addCell` and `deleteCell` callbacks
- New callback properties: `onAddCell`, `onDeleteCell`

### Kotlin (`JupyterNotebookEditor.kt`)
- `onAddCell` handler: creates Cell, inserts into model, renders in panel
- `onDeleteCell` handler: removes from model and panel

## Implementation order

1. **Syntax highlighting** (JS/CSS only, no Kotlin changes) — tokenizer + CSS classes + apply on cell render
2. **Cell editing** (JS + minor Kotlin) — double-click/Escape/Tab handling, real-time re-highlight on input
3. **Cross-cell variables** (JS only) — scope builder + highlight known names in tokenizer
4. **Add/Delete cells** (JS + Kotlin bridge) — gap buttons, delete button, new JBCefJSQuery, model mutations

## Build & test

```bash
./gradlew buildPlugin
```

Manual test: open any .ipynb in the IntelliJ sandbox, verify each feature visually.

## Key constraints

- No external JS libraries (everything inlined via `loadHTML()`)
- `contentEditable` is the editing mechanism (not textarea or Monaco)
- All Kotlin↔JS communication via `JBCefJSQuery` (JS→Kotlin) or `executeJavaScript` (Kotlin→JS)
- Syntax highlighting must complete in <200ms per cell for cells up to 200 lines
