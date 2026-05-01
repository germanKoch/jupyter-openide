# Tasks: Cell Editing & Syntax Highlighting

**Input**: Design documents from `/specs/002-cell-editing-syntax-highlight/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/js-kotlin-bridge.md, quickstart.md

**Tests**: Manual testing only (no automated test framework). Each user story has an independent test section describing how to verify manually.

**Organization**: Tasks grouped by user story (P1–P4) for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- All file paths relative to repository root

---

## Phase 1: Setup

**Purpose**: No new project setup needed — project exists. This phase adds shared CSS foundation used by multiple stories.

- [x] T001 Add syntax token CSS classes (`.tok-keyword`, `.tok-string`, `.tok-number`, `.tok-comment`, `.tok-decorator`, `.tok-builtin`, `.tok-known-var`) to `src/main/resources/notebook/notebook.css`
- [x] T002 Add cell gap CSS (`.cell-gap`, `.add-cell-btn`, `.add-cell-dropdown`) and delete button CSS (`.delete-btn`) to `src/main/resources/notebook/notebook.css`

**Checkpoint**: CSS classes ready — no visual change yet, just infrastructure.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core JS infrastructure shared across multiple user stories.

**⚠️ CRITICAL**: Tokenizer and cursor utilities must be complete before US2 or US4 can begin.

- [x] T003 Implement `tokenize(source, knownNames)` regex-based Python syntax tokenizer function in `src/main/resources/notebook/notebook.js` — handles keywords, builtins, strings (single/double/triple-quoted, f-strings), numbers, comments, decorators; accepts optional `knownNames` set for cross-cell variables
- [x] T004 Implement `renderHighlighted(tokens)` function in `src/main/resources/notebook/notebook.js` — converts token array to HTML with `<span class="tok-{type}">` wrappers
- [x] T005 Implement `saveCursorOffset(element)` and `restoreCursorOffset(element, offset)` cursor save/restore utilities in `src/main/resources/notebook/notebook.js`

**Checkpoint**: Tokenizer, renderer, and cursor utilities ready. Can be tested by calling `tokenize()` from browser console.

---

## Phase 3: User Story 1 — Edit Cell Source Code (Priority: P1) 🎯 MVP

**Goal**: Users can double-click a cell to enter edit mode, type/modify code, press Tab for indentation, Escape to exit, and changes persist on save.

**Independent Test**: Open a notebook, double-click a code cell source, type new code, press Tab to indent, press Escape to exit. Save the notebook and reopen — verify changes persisted. Double-click a markdown cell — verify raw markdown appears; click outside — verify rendered view returns.

### Implementation for User Story 1

- [x] T006 [US1] Add double-click handler on `.cell-source` elements to activate `contentEditable` and add `.editable` class in `src/main/resources/notebook/notebook.js` — must set `contentEditable='true'`, add `oninput` handler calling `kotlinBridge.cellSourceChanged(id, textContent)`, and focus the element
- [x] T007 [US1] Add `keydown` listener on editable cells for Tab key (insert 4 spaces via `document.execCommand('insertText', false, '    ')`, `preventDefault()`) and Escape key (call `makeReadOnly`, blur) in `src/main/resources/notebook/notebook.js`
- [x] T008 [US1] Add Shift+Enter handling in edit mode — exit edit mode then trigger `kotlinBridge.runCell(id)` in `src/main/resources/notebook/notebook.js`
- [x] T009 [US1] Add double-click handler on `.markdown-rendered` to enter markdown edit mode (show raw source, hide rendered) in `src/main/resources/notebook/notebook.js`
- [x] T010 [US1] Add click-outside handler for markdown cells — detect blur/click outside cell, call `stopEditMarkdown` with re-rendered HTML in `src/main/resources/notebook/notebook.js`
- [x] T011 [US1] Disable editing (ignore double-click) while cell has `.executing` class in `src/main/resources/notebook/notebook.js`
- [x] T012 [US1] Verify `cellSourceChanged` bridge correctly syncs edited content to `Cell.source` and sets `Notebook.isDirty = true` in `src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt` — existing `onCellSourceChanged` handler should already work; verify and fix delimiter parsing (null byte `\0` separator) in `src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt`

**Checkpoint**: Cell editing works end-to-end. Users can edit code and markdown cells, changes save correctly.

---

## Phase 4: User Story 2 — Python Syntax Highlighting (Priority: P2)

**Goal**: Code cells display syntax-highlighted Python with distinct colors for keywords, strings, numbers, comments, decorators, and builtins. Highlighting updates in real-time during editing.

**Independent Test**: Open a notebook containing `def hello(name): # greet\n    print(f"Hello {name}")\n    x = 42\n    return True`. Verify: `def`, `return`, `True` in keyword color; `f"Hello {name}"` in string color; `42` in number color; `# greet` in comment color; `print` in builtin color. Double-click to edit, add a new keyword — verify colors update immediately.

**Depends on**: Phase 2 (tokenizer), US1 (editing for real-time highlighting)

### Implementation for User Story 2

- [x] T013 [US2] Apply syntax highlighting on initial cell render — modify `addCell()` in `src/main/resources/notebook/notebook.js` to call `tokenize()` + `renderHighlighted()` and set `innerHTML` instead of `textContent` for code cells
- [x] T014 [US2] Apply real-time syntax highlighting during editing — in the `oninput` handler for editable code cells, save cursor, re-tokenize, replace `innerHTML` with highlighted HTML, restore cursor in `src/main/resources/notebook/notebook.js`
- [x] T015 [US2] Apply syntax highlighting when `updateCell(id, source)` is called from Kotlin in `src/main/resources/notebook/notebook.js`
- [x] T016 [US2] Verify CSS token colors render correctly against the dark theme (keyword: `--keyword-color` #569cd6, string: `--string-color` #ce9178, number: `--number-color` #b5cea8, comment: `--comment-color` #6a9955) in `src/main/resources/notebook/notebook.css`

**Checkpoint**: All code cells display syntax-highlighted Python. Real-time highlighting works during editing.

---

## Phase 5: User Story 3 — Add and Delete Cells (Priority: P3)

**Goal**: Users can add new code/markdown cells via "+" hover buttons between cells, and delete cells via "×" button in cell header. Changes reflect in the model and persist on save.

**Independent Test**: Open a notebook with 2 cells. Hover between cells — verify "+" button appears. Click it, select "Code" — verify new empty code cell appears between. Click "×" on the new cell — verify it disappears. Save and reopen — verify structure matches.

**Depends on**: US1 (editing, so new cells are editable)

### Implementation for User Story 3

- [x] T017 [P] [US3] Add `addCellQuery` and `deleteCellQuery` JBCefJSQuery instances in `src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt` — register handlers, add `onAddCell: ((String, String) -> Unit)?` and `onDeleteCell: ((String) -> Unit)?` callback properties
- [x] T018 [P] [US3] Extend `injectBridge()` in `src/main/kotlin/com/openide/jupyter/editor/NotebookPanel.kt` to include `addCell` and `deleteCell` functions in the bridge object
- [x] T019 [US3] Implement `onAddCell` handler in `src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt` — create new `Cell(cellType=...)`, insert at correct index in `Notebook.cells`, call `notebookPanel.insertCellAfter()` or `addCellToView()`, set `isDirty = true`
- [x] T020 [US3] Implement `onDeleteCell` handler in `src/main/kotlin/com/openide/jupyter/editor/JupyterNotebookEditor.kt` — remove cell from `Notebook.cells`, call `notebookPanel.removeCellFromView()`, set `isDirty = true`
- [x] T021 [US3] Add cell gap divs between cells in `addCell()` and `insertCellAfter()` functions in `src/main/resources/notebook/notebook.js` — each gap has a "+" button that appears on hover, clicking shows a small dropdown with "Code" / "Markdown" options
- [x] T022 [US3] Add gap before first cell and after last cell (and when notebook is empty) in `renderNotebook()` / `clearNotebook()` in `src/main/resources/notebook/notebook.js`
- [x] T023 [US3] Add delete "×" button in cell header (alongside type badge and run button) in `addCell()` in `src/main/resources/notebook/notebook.js` — calls `kotlinBridge.deleteCell(id)`, styled with `opacity: 0.5` default, `opacity: 1` on cell hover
- [x] T024 [US3] Update gap management when cells are added/deleted — insert new gaps around added cells, remove gaps around deleted cells in `src/main/resources/notebook/notebook.js`

**Checkpoint**: Full cell management works. Users can add and delete cells, changes persist on save.

---

## Phase 6: User Story 4 — Cross-Cell Variable Highlighting (Priority: P4)

**Goal**: Variables, functions, classes, and imports defined in earlier cells are visually distinguished when used in subsequent cells. Highlighting updates when cell source changes.

**Independent Test**: Create cell 1 with `import pandas as pd\nx = 10\ndef my_func():`. Create cell 2 with `pd.read_csv(...)\nprint(x)\nmy_func()`. Verify `pd`, `x`, `my_func` are highlighted as known variables in cell 2 but not in cell 1. Edit cell 1 to rename `x` to `y` — verify cell 2 no longer highlights `x`.

**Depends on**: US2 (syntax highlighting / tokenizer)

### Implementation for User Story 4

- [x] T025 [US4] Implement `extractDefinedNames(source)` function in `src/main/resources/notebook/notebook.js` — regex extraction of assignments, functions, classes, imports, aliases, for-loop variables from a single cell's source
- [x] T026 [US4] Implement `buildVariableScope(upToCellIndex)` function in `src/main/resources/notebook/notebook.js` — iterates code cells in DOM order up to given index, calls `extractDefinedNames()` for each, returns cumulative `Set<string>`
- [x] T027 [US4] Integrate variable scope into highlighting — modify cell highlighting to pass `buildVariableScope(cellIndex)` as `knownNames` to `tokenize()` in `src/main/resources/notebook/notebook.js`
- [x] T028 [US4] Add `highlightAllCells()` function in `src/main/resources/notebook/notebook.js` — rebuilds scope and re-highlights all code cells; called after cell add, delete, or source change to refresh cross-cell highlighting
- [x] T029 [US4] Trigger `highlightAllCells()` on cell source change (`oninput`), cell add, and cell delete events in `src/main/resources/notebook/notebook.js` — debounce with 300ms delay to avoid excessive re-computation during typing

**Checkpoint**: Cross-cell variable highlighting works. Names defined in earlier cells are visually distinct in later cells.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories.

- [x] T030 Verify `./gradlew buildPlugin` succeeds with all changes
- [x] T031 End-to-end manual test: open a real .ipynb notebook, edit cells, run cells, add/delete cells, verify syntax highlighting and cross-cell variables all work together
- [x] T032 Verify save round-trip: edit cells, add/delete cells, save, reopen notebook — all changes persisted correctly

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — CSS classes only
- **Foundational (Phase 2)**: Depends on Phase 1 CSS — tokenizer needs token class names
- **US1 Edit Cells (Phase 3)**: Depends on Phase 1 CSS only — editing works without highlighting
- **US2 Syntax Highlighting (Phase 4)**: Depends on Phase 2 (tokenizer) + US1 (for real-time editing)
- **US3 Add/Delete Cells (Phase 5)**: Depends on US1 (so new cells are editable) — can start in parallel with US2
- **US4 Cross-Cell Variables (Phase 6)**: Depends on US2 (extends tokenizer with knownNames)
- **Polish (Phase 7)**: Depends on all user stories

### User Story Dependencies

```
Phase 1 (CSS) ──→ Phase 2 (Tokenizer) ──→ US2 (Highlighting) ──→ US4 (Variables)
                                            ↑
Phase 1 (CSS) ──→ US1 (Editing) ───────────┘
                       ↓
                   US3 (Add/Delete) ─────────→ Phase 7 (Polish)
```

- **US1 (P1)**: Can start immediately after Phase 1
- **US2 (P2)**: Needs Phase 2 + US1
- **US3 (P3)**: Needs US1, can run in parallel with US2
- **US4 (P4)**: Needs US2

### Parallel Opportunities

- T001 and T002 can run in parallel (different CSS sections)
- T003, T004, T005 can run in parallel (independent JS functions)
- US1 (T006–T012) and Phase 2 (T003–T005) can proceed in parallel
- T017 and T018 can run in parallel (different aspects of NotebookPanel.kt, but same file — best done sequentially)
- T019 and T020 can run in parallel (different handlers in JupyterNotebookEditor.kt, but same file — best done together)
- US3 Kotlin work (T017–T020) can run in parallel with US3 JS work (T021–T024) since they're different files

---

## Parallel Example: User Story 3

```
# Kotlin side and JS side can be implemented in parallel:

# Agent A (Kotlin):
T017: Add addCellQuery/deleteCellQuery to NotebookPanel.kt
T018: Extend injectBridge() in NotebookPanel.kt
T019: onAddCell handler in JupyterNotebookEditor.kt
T020: onDeleteCell handler in JupyterNotebookEditor.kt

# Agent B (JS/CSS):
T021: Cell gap divs with "+" button in notebook.js
T022: Boundary gaps in notebook.js
T023: Delete "×" button in notebook.js
T024: Gap management on add/delete in notebook.js
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: CSS setup (T001–T002)
2. Complete US1: Cell editing (T006–T012)
3. **STOP and VALIDATE**: Edit cells, save, reopen
4. Users can now edit notebooks — core value delivered

### Incremental Delivery

1. Phase 1 + Phase 2 + US1 → Editing works (MVP)
2. Add US2 → Syntax highlighting during view and edit
3. Add US3 → Add/delete cells
4. Add US4 → Cross-cell variable awareness
5. Each story adds value without breaking previous stories

### Recommended Single-Developer Order

```
T001–T002 (CSS) → T003–T005 (Tokenizer) → T006–T012 (US1: Editing) →
T013–T016 (US2: Highlighting) → T017–T024 (US3: Add/Delete) →
T025–T029 (US4: Variables) → T030–T032 (Polish)
```

---

## Notes

- All JS changes in one file: `src/main/resources/notebook/notebook.js`
- All CSS changes in one file: `src/main/resources/notebook/notebook.css`
- Kotlin changes in two files: `NotebookPanel.kt` (bridge) and `JupyterNotebookEditor.kt` (model)
- No automated tests — manual testing at each checkpoint
- Build verification: `./gradlew buildPlugin` after each phase
