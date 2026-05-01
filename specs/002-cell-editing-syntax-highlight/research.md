# Research: Cell Editing & Syntax Highlighting

## Decision 1: Syntax Highlighting Approach

**Decision**: Custom regex-based tokenizer in JavaScript (no external library)

**Rationale**: The JCEF environment loads HTML via `loadHTML()` with inlined JS/CSS. Adding external libraries (highlight.js, Prism.js, CodeMirror) would bloat the inline HTML and add complexity. Python syntax is regular enough that a simple tokenizer covering keywords, strings, numbers, comments, decorators, and builtins is feasible. The existing CSS already defines `--keyword-color`, `--string-color`, `--number-color`, `--comment-color` variables.

**Alternatives considered**:
- highlight.js (~40KB minified) — too large to inline, limited editing integration
- CodeMirror 6 — excellent editing but massive dependency (~150KB), overkill for contentEditable cells
- Prism.js (~15KB) — lighter but still display-only, no editing mode integration

## Decision 2: Editing Mechanism

**Decision**: Use `contentEditable` on cell source divs with custom keyboard handling

**Rationale**: The existing JS already has `makeEditable(id)` and `makeReadOnly(id)` functions using `contentEditable`. This is the simplest path — double-click activates `contentEditable='true'`, Escape reverts to `'false'`. The `oninput` handler already syncs content to Kotlin via `kotlinBridge.cellSourceChanged`.

**Alternatives considered**:
- Embedded Monaco editor (VS Code core) — would provide full IDE editing but is a massive dependency and complex to integrate in JCEF inline HTML
- Custom `<textarea>` overlay — simpler than contentEditable for plain text but loses inline highlighting
- Ace editor — medium-weight, but still a significant bundle to inline

## Decision 3: Syntax Highlighting in Edit Mode

**Decision**: Re-tokenize and re-render highlighted HTML on each input event, using `innerHTML` replacement with cursor position restoration

**Rationale**: `contentEditable` renders HTML, so we can replace the div's `innerHTML` with tokenized/highlighted HTML after each keystroke. The challenge is preserving cursor position — we save the text offset before replacement and restore it after. This approach gives real-time highlighting during editing. For cells < 200 lines, regex tokenization + DOM update completes well within the 200ms budget.

**Alternatives considered**:
- Highlight on blur only (simpler but no real-time feedback during editing)
- Overlay approach (transparent textarea over highlighted pre) — works but complex z-index/scroll sync

## Decision 4: Cross-Cell Variable Extraction

**Decision**: Regex-based static extraction from cell source text (no kernel introspection)

**Rationale**: Extracting names from `x = ...`, `def func(...):`, `class Cls:`, `import X`, `from X import Y`, `X as alias` patterns with regex is simple and fast. No kernel roundtrip means it works even with the kernel disconnected. The scope is built by scanning all code cells in order; each cell's defined names are collected into a cumulative set available to subsequent cells.

**Patterns to extract**:
- Assignment: `^(\w+)\s*=` (top-level variable assignment)
- Function: `^def\s+(\w+)` 
- Class: `^class\s+(\w+)`
- Import: `^import\s+(\w+)`, `^from\s+\w+\s+import\s+(.+)` (split on comma)
- Import alias: `(\w+)\s+as\s+(\w+)` (use the alias)
- For loop variable: `^for\s+(\w+)\s+in`

**Alternatives considered**:
- Kernel introspection (`who` / `dir()`) — requires running kernel, adds latency, misses unexecuted cells
- Python AST parsing in JS — complex to implement correctly, overkill for highlighting hints

## Decision 5: Between-Cell "+" Button Implementation

**Decision**: CSS-styled hover zones between cells with absolutely-positioned button

**Rationale**: Each cell gap gets a thin div (class `cell-gap`) that shows a "+" button on hover. Clicking opens a small dropdown with "Code" / "Markdown" options. The gap div is added between every pair of cells, plus before the first and after the last. This matches the Jupyter/Colab UX pattern.

**Alternatives considered**:
- Floating toolbar that follows mouse — more complex positioning logic
- Right-click context menu — less discoverable

## Decision 6: Delete Button (×) Placement

**Decision**: Always-visible × button in cell header, right-aligned with `opacity: 0.5` default, `opacity: 1` on cell hover

**Rationale**: Consistent with the existing run button (▶) styling. Placed in the cell header alongside the type badge and execution count. Subtly visible at all times so users know it's there, brightens on hover for discoverability.

## Decision 7: Edit Mode Keyboard Handling

**Decision**: Intercept Tab (insert 4 spaces), Escape (exit edit mode), preserve Shift+Enter (run cell)

**Rationale**: Tab must be intercepted in `contentEditable` to prevent focus navigation — Python requires indentation. Escape is the standard "exit mode" key in editor paradigms. Shift+Enter already works for cell execution and should continue to work in edit mode (run and exit edit).

**Implementation**: Add `keydown` listener on the editable element that calls `e.preventDefault()` for Tab and inserts spaces via `document.execCommand('insertText', false, '    ')` (4 spaces). Escape triggers `makeReadOnly`.
