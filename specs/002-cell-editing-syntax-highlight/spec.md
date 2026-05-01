# Feature Specification: Cell Editing & Syntax Highlighting

**Feature Branch**: `002-cell-editing-syntax-highlight`  
**Created**: 2026-05-01  
**Status**: Draft  
**Input**: User description: "Кнопки добавления/удаления ячеек, подсветка синтаксиса Python, подсветка переменных из предыдущих ячеек, редактирование ячеек"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Edit Cell Source Code (Priority: P1)

A user opens a Jupyter notebook and wants to modify the code in an existing cell. They click on the cell source area and it becomes editable — they can type, delete, paste code. Changes are tracked so the notebook can be saved with the updated content.

**Why this priority**: Without editing, the notebook viewer is read-only and users cannot iterate on their code — the most fundamental interaction in a notebook workflow.

**Independent Test**: Open a notebook with existing code cells, click on a cell's source area, modify the text, verify changes persist in the model and can be saved.

**Acceptance Scenarios**:

1. **Given** a notebook is open with code cells, **When** the user double-clicks on a code cell's source area, **Then** the cell becomes editable with a visible editing indicator
2. **Given** a code cell is in edit mode, **When** the user types new code, **Then** the cell content updates in real-time and the notebook is marked as modified
3. **Given** a markdown cell is displayed in rendered mode, **When** the user double-clicks it, **Then** the raw markdown source becomes editable
4. **Given** a markdown cell is being edited, **When** the user clicks outside the cell, **Then** the markdown is re-rendered and the cell returns to display mode
5. **Given** a code cell is in edit mode, **When** the user presses Tab, **Then** 4 spaces are inserted at the cursor position
6. **Given** a cell is in edit mode, **When** the user presses Escape, **Then** the cell exits edit mode and returns to read-only selected state
7. **Given** a cell has been edited, **When** the user saves the notebook, **Then** the updated source is persisted to the .ipynb file

---

### User Story 2 - Python Syntax Highlighting (Priority: P2)

A user views or edits Python code in notebook cells and sees syntax highlighting — keywords (`def`, `class`, `import`, `for`, `if`, etc.) are colored distinctly, strings are highlighted, numbers stand out, comments are visually de-emphasized. This matches the dark theme of the editor.

**Why this priority**: Syntax highlighting dramatically improves code readability and is expected in any code editing environment. Without it, reading code in cells is painful.

**Independent Test**: Open a notebook with Python code containing keywords, strings, numbers, and comments. Verify each token type renders in a distinct color consistent with the dark theme.

**Acceptance Scenarios**:

1. **Given** a code cell contains Python keywords (`def`, `class`, `import`, `for`, `if`, `return`, `while`, `try`, `except`, `with`, `as`, `in`, `not`, `and`, `or`, `True`, `False`, `None`), **When** the cell is displayed, **Then** keywords appear in a distinct color
2. **Given** a code cell contains string literals (single-quoted, double-quoted, triple-quoted, f-strings), **When** the cell is displayed, **Then** strings appear in a distinct color
3. **Given** a code cell contains numeric literals (integers, floats, hex, scientific notation), **When** the cell is displayed, **Then** numbers appear in a distinct color
4. **Given** a code cell contains comments (`#`), **When** the cell is displayed, **Then** comments appear in a muted/dimmed color
5. **Given** the user edits a code cell, **When** they type new code, **Then** syntax highlighting updates in real-time as they type

---

### User Story 3 - Add and Delete Cells (Priority: P3)

A user wants to add new code or markdown cells to their notebook, or remove cells they no longer need. Buttons in the notebook UI allow adding a cell below the current selection and deleting the selected cell.

**Why this priority**: Cell management is essential for building notebooks, but users can work with existing cells (edit + run) without it.

**Independent Test**: Open a notebook, click "Add Code Cell" button, verify a new empty cell appears. Select a cell and click "Delete", verify it is removed.

**Acceptance Scenarios**:

1. **Given** a notebook has cells, **When** the user hovers between two cells, **Then** a "+" button appears in the gap between them
2. **Given** the "+" button is visible, **When** the user clicks it, **Then** a menu appears offering "Code" and "Markdown" cell types
3. **Given** the user selects a cell type from the "+" menu, **Then** a new empty cell of that type is inserted at that position
4. **Given** a cell is displayed, **When** the user looks at the cell header, **Then** a delete (×) button is visible
5. **Given** a cell has a delete (×) button, **When** the user clicks it, **Then** the cell is removed from the notebook
6. **Given** the notebook has only one cell, **When** the user deletes it, **Then** the cell is removed and the notebook is empty
7. **Given** cells have been added or deleted, **When** the user saves, **Then** the new cell structure is persisted to the .ipynb file

---

### User Story 4 - Cross-Cell Variable Highlighting (Priority: P4)

A user writes code across multiple cells and the editor visually distinguishes user-defined variables, functions, and classes that were defined in cells above. When a name like `df` or `model` is used in a lower cell, it is highlighted differently from unknown identifiers, giving the user confidence that the name is in scope.

**Why this priority**: This is an advanced editing aid. The notebook is fully functional without it, but it significantly improves the authoring experience for multi-cell workflows.

**Independent Test**: Create a cell with `df = pd.DataFrame(...)`, execute it, then in a subsequent cell type `df.head()` — verify `df` is highlighted as a known variable.

**Acceptance Scenarios**:

1. **Given** cell 1 defines `x = 10` and cell 2 uses `print(x)`, **When** both cells are displayed, **Then** `x` in cell 2 is highlighted as a known variable
2. **Given** cell 1 defines `def my_func():` and cell 3 calls `my_func()`, **When** displayed, **Then** `my_func` in cell 3 is highlighted as a known function
3. **Given** cell 1 defines `import pandas as pd`, **When** cell 2 uses `pd.read_csv(...)`, **Then** `pd` is highlighted as a known import alias
4. **Given** the user edits cell 1 and renames a variable, **When** they move to cell 2, **Then** the highlighting updates to reflect the new variable name (old name no longer highlighted as known)

---

### Edge Cases

- What happens when the user deletes a cell that defines a variable used in cells below? — The variable highlighting in downstream cells updates (variable no longer marked as known)
- How does syntax highlighting handle incomplete code (e.g., unclosed string)? — Best-effort highlighting; unterminated strings are highlighted up to end of line
- What happens when adding a cell to an empty notebook? — The cell is added as the first and only cell
- How does editing interact with a running cell? — Editing is disabled while a cell is executing; the user must wait for execution to complete

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Code cells MUST become editable when the user double-clicks on the source area (single click selects the cell)
- **FR-002**: Markdown cells MUST enter edit mode on double-click, showing raw markdown source
- **FR-003**: Markdown cells MUST return to rendered mode when the user clicks outside the cell
- **FR-004**: All edits MUST sync back to the notebook model so they persist on save
- **FR-005**: Python keywords MUST be highlighted with a distinct color in code cells
- **FR-006**: String literals (single, double, triple-quoted, f-strings) MUST be highlighted
- **FR-007**: Numeric literals MUST be highlighted
- **FR-008**: Comments MUST be highlighted in a muted color
- **FR-009**: Decorators (`@`) MUST be highlighted
- **FR-010**: Built-in functions (`print`, `len`, `range`, `type`, etc.) MUST be highlighted
- **FR-011**: Syntax highlighting MUST update in real-time as the user edits code
- **FR-012**: A "+" button MUST appear between cells on hover, offering options to add a code or markdown cell at that position
- **FR-013**: A delete button (×) MUST appear in each cell's header to remove that cell
- **FR-014**: Adding a cell via the "+" button MUST insert it at the exact position between the two adjacent cells
- **FR-015**: A "+" button MUST also appear after the last cell and before the first cell (or when the notebook is empty) to allow insertion at boundaries
- **FR-016**: Deleting a cell MUST remove it from both the UI and the notebook model
- **FR-017**: The system MUST extract variable, function, class, and import names defined in code cells
- **FR-018**: Names defined in cells above MUST be visually distinguished when used in cells below
- **FR-019**: Cross-cell variable highlighting MUST update when cell source changes
- **FR-020**: Editing MUST be disabled while a cell is executing
- **FR-021**: In edit mode, the Tab key MUST insert 4 spaces (indentation) instead of moving focus
- **FR-022**: In edit mode, pressing Escape MUST exit edit mode and return the cell to read-only/selected state

### Key Entities

- **Cell**: A notebook unit (code or markdown) with source text, output, execution count, and edit state
- **Variable Scope**: The set of names (variables, functions, classes, imports) defined across all code cells in order, used to drive cross-cell highlighting
- **Syntax Token**: A classified segment of source code (keyword, string, number, comment, identifier, known-variable) used for rendering highlighted code

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can edit any cell and save within the same session — round-trip from click to saved file works without data loss
- **SC-002**: All standard Python syntax elements (keywords, strings, numbers, comments, decorators, builtins) are visually distinguishable in the editor theme
- **SC-003**: Users can add and remove cells in under 2 seconds per operation
- **SC-004**: Variables defined in an earlier cell are visually distinguishable from unknown names in subsequent cells within 1 second of edit
- **SC-005**: Syntax highlighting update latency during typing does not exceed 200ms perceived delay

## Clarifications

### Session 2026-05-02

- Q: Where should add/delete cell buttons be placed? → A: "+" button appears between cells on hover (Jupyter/Colab style), delete button (×) in cell header
- Q: How should code cell edit mode activate without conflicting with click-to-select? → A: Double-click on source area to enter edit mode (consistent with markdown cells)
- Q: What should Tab key do inside an editable code cell? → A: Tab inserts 4 spaces (indentation); Escape exits edit mode

## Assumptions

- The notebook editor runs in JCEF (Chromium embedded browser), so syntax highlighting is implemented in JavaScript/CSS within the browser component
- The dark theme color palette already defined in CSS variables (`--keyword-color`, `--string-color`, `--number-color`, `--comment-color`) will be used for highlighting
- Python is the only language requiring syntax highlighting (no R, Julia, etc.)
- Variable extraction uses simple regex-based static analysis of cell source code (not runtime introspection of the kernel)
- The `contentEditable` mechanism already partially implemented in the JS layer will be the basis for cell editing
- Add cell buttons appear as "+" between cells on hover (Jupyter/Colab pattern); delete (×) appears in each cell header
