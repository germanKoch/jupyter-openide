let selectedCellId = null;
let kotlinBridge = null;
let highlightDebounceTimer = null;

function initBridge(bridge) {
    kotlinBridge = bridge;
}

// ── Syntax Tokenizer (T003) ──

const PYTHON_KEYWORDS = new Set([
    'False', 'None', 'True', 'and', 'as', 'assert', 'async', 'await',
    'break', 'class', 'continue', 'def', 'del', 'elif', 'else', 'except',
    'finally', 'for', 'from', 'global', 'if', 'import', 'in', 'is',
    'lambda', 'nonlocal', 'not', 'or', 'pass', 'raise', 'return',
    'try', 'while', 'with', 'yield'
]);

const PYTHON_BUILTINS = new Set([
    'print', 'len', 'range', 'type', 'int', 'float', 'str', 'bool',
    'list', 'dict', 'set', 'tuple', 'enumerate', 'zip', 'map', 'filter',
    'sorted', 'reversed', 'min', 'max', 'sum', 'abs', 'round',
    'input', 'open', 'isinstance', 'issubclass', 'hasattr', 'getattr',
    'setattr', 'delattr', 'callable', 'super', 'property', 'staticmethod',
    'classmethod', 'object', 'repr', 'format', 'id', 'hash', 'dir',
    'vars', 'globals', 'locals', 'iter', 'next', 'any', 'all',
    'ord', 'chr', 'hex', 'oct', 'bin', 'pow', 'divmod',
    'complex', 'bytes', 'bytearray', 'memoryview', 'frozenset',
    'slice', 'exec', 'eval', 'compile', 'breakpoint',
    'KeyError', 'ValueError', 'TypeError', 'IndexError', 'AttributeError',
    'RuntimeError', 'StopIteration', 'GeneratorExit', 'Exception',
    'BaseException', 'ArithmeticError', 'LookupError', 'OSError',
    'IOError', 'FileNotFoundError', 'PermissionError', 'NotImplementedError',
    'ZeroDivisionError', 'OverflowError', 'ImportError', 'ModuleNotFoundError',
    'NameError', 'UnboundLocalError', 'SyntaxError', 'IndentationError',
    'SystemExit', 'KeyboardInterrupt', 'AssertionError', 'UnicodeError',
    'UnicodeDecodeError', 'UnicodeEncodeError'
]);

function tokenize(source, knownNames) {
    const tokens = [];
    let i = 0;
    const len = source.length;
    const known = knownNames || new Set();

    while (i < len) {
        // Comments
        if (source[i] === '#') {
            let end = source.indexOf('\n', i);
            if (end === -1) end = len;
            tokens.push({ type: 'comment', value: source.slice(i, end) });
            i = end;
            continue;
        }

        // Triple-quoted strings
        if (i < len - 2) {
            const tri = source.slice(i, i + 3);
            let prefix = '';
            let startOffset = i;
            if (i > 0 && 'fFbBrRuU'.includes(source[i - 1]) && tokens.length > 0 && tokens[tokens.length - 1].type === 'text') {
                // Check if last text token ends with a string prefix
            }
            // Check for f/b/r/u prefix before quotes
            if ('fFbBrRuU'.includes(source[i]) && i + 3 < len) {
                const afterPrefix = source.slice(i + 1, i + 4);
                if (afterPrefix === '"""' || afterPrefix === "'''") {
                    prefix = source[i];
                    startOffset = i;
                    const quote = afterPrefix;
                    let end = source.indexOf(quote, i + 4);
                    if (end === -1) end = len - 3;
                    if (end < i + 4) end = len;
                    else end += 3;
                    tokens.push({ type: 'string', value: source.slice(startOffset, end) });
                    i = end;
                    continue;
                }
            }
            if (tri === '"""' || tri === "'''") {
                let end = source.indexOf(tri, i + 3);
                if (end === -1) end = len;
                else end += 3;
                tokens.push({ type: 'string', value: source.slice(i, end) });
                i = end;
                continue;
            }
        }

        // Single/double quoted strings (with optional f/b/r/u prefix)
        if (source[i] === '"' || source[i] === "'" ||
            ('fFbBrRuU'.includes(source[i]) && i + 1 < len && (source[i + 1] === '"' || source[i + 1] === "'"))) {
            let start = i;
            if ('fFbBrRuU'.includes(source[i])) i++;
            const quote = source[i];
            i++;
            while (i < len && source[i] !== quote && source[i] !== '\n') {
                if (source[i] === '\\') i++;
                i++;
            }
            if (i < len && source[i] === quote) i++;
            tokens.push({ type: 'string', value: source.slice(start, i) });
            continue;
        }

        // Decorators
        if (source[i] === '@' && (i === 0 || source[i - 1] === '\n' || /\s/.test(source[i - 1]))) {
            let end = i + 1;
            while (end < len && /[\w.]/.test(source[end])) end++;
            if (end > i + 1) {
                tokens.push({ type: 'decorator', value: source.slice(i, end) });
                i = end;
                continue;
            }
        }

        // Numbers (hex, octal, binary, float, scientific, int)
        if (/[0-9]/.test(source[i]) || (source[i] === '.' && i + 1 < len && /[0-9]/.test(source[i + 1]))) {
            let end = i;
            if (source[end] === '0' && end + 1 < len && 'xXoObB'.includes(source[end + 1])) {
                end += 2;
                while (end < len && /[0-9a-fA-F_]/.test(source[end])) end++;
            } else {
                while (end < len && /[0-9_]/.test(source[end])) end++;
                if (end < len && source[end] === '.') {
                    end++;
                    while (end < len && /[0-9_]/.test(source[end])) end++;
                }
                if (end < len && 'eE'.includes(source[end])) {
                    end++;
                    if (end < len && '+-'.includes(source[end])) end++;
                    while (end < len && /[0-9_]/.test(source[end])) end++;
                }
                if (end < len && 'jJ'.includes(source[end])) end++;
            }
            tokens.push({ type: 'number', value: source.slice(i, end) });
            i = end;
            continue;
        }

        // Identifiers and keywords
        if (/[a-zA-Z_]/.test(source[i])) {
            let end = i;
            while (end < len && /[\w]/.test(source[end])) end++;
            const word = source.slice(i, end);
            if (PYTHON_KEYWORDS.has(word)) {
                tokens.push({ type: 'keyword', value: word });
            } else if (PYTHON_BUILTINS.has(word)) {
                tokens.push({ type: 'builtin', value: word });
            } else if (known.has(word)) {
                tokens.push({ type: 'known-var', value: word });
            } else {
                tokens.push({ type: 'text', value: word });
            }
            i = end;
            continue;
        }

        // Whitespace and operators — collect as text
        let end = i + 1;
        while (end < len && !/[a-zA-Z_0-9#"'@.fFbBrRuU]/.test(source[end]) && source[end] !== '.' ) {
            end++;
        }
        tokens.push({ type: 'text', value: source.slice(i, end) });
        i = end;
    }
    return tokens;
}

// ── Highlighted HTML Renderer (T004) ──

function escapeHtmlJS(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function renderHighlighted(tokens) {
    let html = '';
    for (const tok of tokens) {
        const escaped = escapeHtmlJS(tok.value);
        if (tok.type === 'text') {
            html += escaped;
        } else {
            html += '<span class="tok-' + tok.type + '">' + escaped + '</span>';
        }
    }
    return html;
}

// ── Cursor Save/Restore (T005) ──

function saveCursorOffset(element) {
    const sel = window.getSelection();
    if (!sel.rangeCount) return 0;
    const range = sel.getRangeAt(0);
    const preRange = document.createRange();
    preRange.selectNodeContents(element);
    preRange.setEnd(range.startContainer, range.startOffset);
    return preRange.toString().length;
}

function restoreCursorOffset(element, offset) {
    const sel = window.getSelection();
    const range = document.createRange();
    let current = 0;
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, null);
    let node;
    while ((node = walker.nextNode())) {
        const nodeLen = node.textContent.length;
        if (current + nodeLen >= offset) {
            range.setStart(node, offset - current);
            range.collapse(true);
            sel.removeAllRanges();
            sel.addRange(range);
            return;
        }
        current += nodeLen;
    }
    range.selectNodeContents(element);
    range.collapse(false);
    sel.removeAllRanges();
    sel.addRange(range);
}

// ── Cross-Cell Variable Extraction (T025, T026) ──

function extractDefinedNames(source) {
    const names = new Set();
    const lines = source.split('\n');
    for (const line of lines) {
        const trimmed = line.trimStart();
        let m;
        if ((m = trimmed.match(/^(\w+)\s*=/))) names.add(m[1]);
        if ((m = trimmed.match(/^def\s+(\w+)/))) names.add(m[1]);
        if ((m = trimmed.match(/^class\s+(\w+)/))) names.add(m[1]);
        if ((m = trimmed.match(/^import\s+(\w+)/))) names.add(m[1]);
        if ((m = trimmed.match(/^from\s+\w+\s+import\s+(.+)/))) {
            m[1].split(',').forEach(function(part) {
                const asMatch = part.trim().match(/(\w+)\s+as\s+(\w+)/);
                if (asMatch) {
                    names.add(asMatch[2]);
                } else {
                    const name = part.trim().match(/^(\w+)/);
                    if (name) names.add(name[1]);
                }
            });
        }
        if ((m = trimmed.match(/^for\s+(\w+)\s+in/))) names.add(m[1]);
        if (!trimmed.startsWith('from') && (m = trimmed.match(/(\w+)\s+as\s+(\w+)/))) {
            names.add(m[2]);
        }
    }
    return names;
}

function buildVariableScope(upToCellIndex) {
    const names = new Set();
    const cells = document.querySelectorAll('#notebook-container .cell');
    let idx = 0;
    for (const cell of cells) {
        if (idx >= upToCellIndex) break;
        if (cell.dataset.cellType === 'code') {
            const sourceEl = cell.querySelector('.cell-source');
            if (sourceEl) {
                const cellNames = extractDefinedNames(sourceEl.textContent);
                cellNames.forEach(function(n) { names.add(n); });
            }
        }
        idx++;
    }
    return names;
}

// ── Highlight Helpers (T013, T014, T027, T028) ──

function getCellIndex(cellId) {
    const cells = document.querySelectorAll('#notebook-container .cell');
    let idx = 0;
    for (const cell of cells) {
        if (cell.dataset.cellId === cellId) return idx;
        idx++;
    }
    return -1;
}

function highlightCellSource(sourceEl, cellId) {
    const text = sourceEl.textContent;
    const cellIdx = getCellIndex(cellId);
    const scope = buildVariableScope(cellIdx);
    const tokens = tokenize(text, scope);
    const html = renderHighlighted(tokens);
    sourceEl.innerHTML = html;
}

function highlightAllCells() {
    const cells = document.querySelectorAll('#notebook-container .cell');
    for (const cell of cells) {
        if (cell.dataset.cellType === 'code') {
            const sourceEl = cell.querySelector('.cell-source');
            if (sourceEl) {
                const isEditing = sourceEl.contentEditable === 'true';
                let cursorOff = 0;
                if (isEditing) cursorOff = saveCursorOffset(sourceEl);
                highlightCellSource(sourceEl, cell.dataset.cellId);
                if (isEditing) restoreCursorOffset(sourceEl, cursorOff);
            }
        }
    }
}

function scheduleHighlightAll() {
    if (highlightDebounceTimer) clearTimeout(highlightDebounceTimer);
    highlightDebounceTimer = setTimeout(highlightAllCells, 300);
}

// ── Cell Gap Management (T021, T022, T024) ──

function createCellGap(afterCellId) {
    const gap = document.createElement('div');
    gap.className = 'cell-gap';
    gap.dataset.afterCellId = afterCellId || '';

    const btn = document.createElement('button');
    btn.className = 'add-cell-btn';
    btn.textContent = '+';
    btn.title = 'Add cell';
    btn.onclick = function(e) {
        e.stopPropagation();
        const dropdown = gap.querySelector('.add-cell-dropdown');
        if (dropdown) {
            dropdown.classList.toggle('visible');
        }
    };
    gap.appendChild(btn);

    const dropdown = document.createElement('div');
    dropdown.className = 'add-cell-dropdown';

    const codeBtn = document.createElement('button');
    codeBtn.textContent = '+ Code';
    codeBtn.onclick = function(e) {
        e.stopPropagation();
        dropdown.classList.remove('visible');
        if (kotlinBridge) kotlinBridge.addCell(afterCellId || '', 'code');
    };
    dropdown.appendChild(codeBtn);

    const mdBtn = document.createElement('button');
    mdBtn.textContent = '+ Markdown';
    mdBtn.onclick = function(e) {
        e.stopPropagation();
        dropdown.classList.remove('visible');
        if (kotlinBridge) kotlinBridge.addCell(afterCellId || '', 'markdown');
    };
    dropdown.appendChild(mdBtn);

    gap.appendChild(dropdown);
    return gap;
}

function rebuildGaps() {
    const container = document.getElementById('notebook-container');
    container.querySelectorAll('.cell-gap').forEach(function(g) { g.remove(); });

    const cells = container.querySelectorAll('.cell');
    // Gap before first cell
    const firstGap = createCellGap('');
    if (cells.length > 0) {
        container.insertBefore(firstGap, cells[0]);
    } else {
        container.appendChild(firstGap);
    }

    // Gap after each cell
    for (const cell of cells) {
        const gap = createCellGap(cell.dataset.cellId);
        if (cell.nextSibling) {
            container.insertBefore(gap, cell.nextSibling);
        } else {
            container.appendChild(gap);
        }
    }
}

// ── Cell Construction (modified addCell with all features) ──

function addCell(id, type, source, outputsHtml, executionCount) {
    const container = document.getElementById('notebook-container');
    const cell = document.createElement('div');
    cell.id = 'cell-' + id;
    cell.className = 'cell';
    cell.dataset.cellId = id;
    cell.dataset.cellType = type;
    cell.onclick = function(e) { selectCell(id); };

    const header = document.createElement('div');
    header.className = 'cell-header';

    const badge = document.createElement('span');
    badge.className = 'cell-type-badge ' + type;
    badge.textContent = type;
    header.appendChild(badge);

    if (type === 'code') {
        const runBtn = document.createElement('button');
        runBtn.className = 'run-btn';
        runBtn.textContent = '▶';
        runBtn.title = 'Run cell (Shift+Enter)';
        runBtn.onclick = function(e) {
            e.stopPropagation();
            selectCell(id);
            if (kotlinBridge) kotlinBridge.runCell(id);
        };
        header.appendChild(runBtn);

        const execCount = document.createElement('span');
        execCount.className = 'execution-count';
        execCount.id = 'exec-count-' + id;
        execCount.textContent = executionCount != null ? '[' + executionCount + ']' : '[ ]';
        header.appendChild(execCount);

        const indicator = document.createElement('span');
        indicator.className = 'execution-indicator';
        indicator.textContent = '●';
        header.appendChild(indicator);
    }

    // Delete button (T023)
    const delBtn = document.createElement('button');
    delBtn.className = 'delete-btn';
    delBtn.textContent = '×';
    delBtn.title = 'Delete cell';
    delBtn.onclick = function(e) {
        e.stopPropagation();
        if (kotlinBridge) kotlinBridge.deleteCell(id);
    };
    header.appendChild(delBtn);

    cell.appendChild(header);

    if (type === 'code') {
        const sourceDiv = document.createElement('div');
        sourceDiv.className = 'cell-source';
        sourceDiv.id = 'source-' + id;

        // Syntax highlighting on render (T013)
        const tempDiv = document.createElement('div');
        tempDiv.textContent = source;
        const rawText = tempDiv.textContent;
        const cellIdx = getCellIndexByContainer(container, id);
        const scope = buildVariableScope(cellIdx >= 0 ? cellIdx : 9999);
        const tokens = tokenize(rawText, scope);
        sourceDiv.innerHTML = renderHighlighted(tokens);

        // Double-click to edit (T006)
        sourceDiv.ondblclick = function(e) {
            e.stopPropagation();
            if (cell.classList.contains('executing')) return; // T011
            enterEditMode(id);
        };

        cell.appendChild(sourceDiv);

        const outputDiv = document.createElement('div');
        outputDiv.className = 'cell-output';
        outputDiv.id = 'output-' + id;
        outputDiv.innerHTML = outputsHtml || '';
        cell.appendChild(outputDiv);
    } else {
        const renderedDiv = document.createElement('div');
        renderedDiv.className = 'markdown-rendered';
        renderedDiv.id = 'md-rendered-' + id;
        renderedDiv.innerHTML = source;

        // Double-click to edit markdown (T009)
        renderedDiv.ondblclick = function(e) {
            e.stopPropagation();
            if (cell.classList.contains('executing')) return;
            startEditMarkdown(id);
        };

        cell.appendChild(renderedDiv);

        const sourceDiv = document.createElement('div');
        sourceDiv.className = 'markdown-source';
        sourceDiv.id = 'md-source-' + id;
        sourceDiv.contentEditable = 'true';
        cell.appendChild(sourceDiv);
    }

    container.appendChild(cell);
}

function getCellIndexByContainer(container, cellId) {
    const cells = container.querySelectorAll('.cell');
    let idx = 0;
    for (const c of cells) {
        if (c.dataset.cellId === cellId) return idx;
        idx++;
    }
    return -1;
}

// ── Edit Mode (T006, T007, T008, T011) ──

function enterEditMode(id) {
    const sourceEl = document.getElementById('source-' + id);
    if (!sourceEl) return;
    sourceEl.contentEditable = 'true';
    sourceEl.classList.add('editable');
    sourceEl.focus();

    sourceEl.onkeydown = function(e) {
        // Tab → insert 4 spaces (T007)
        if (e.key === 'Tab' && !e.shiftKey) {
            e.preventDefault();
            document.execCommand('insertText', false, '    ');
            return;
        }
        // Escape → exit edit mode (T007)
        if (e.key === 'Escape') {
            e.preventDefault();
            exitEditMode(id);
            return;
        }
        // Shift+Enter → run cell (T008)
        if (e.shiftKey && e.key === 'Enter') {
            e.preventDefault();
            exitEditMode(id);
            if (kotlinBridge) kotlinBridge.runCell(id);
            return;
        }
    };

    // Real-time highlighting during edit (T014)
    sourceEl.oninput = function() {
        const text = sourceEl.textContent;
        if (kotlinBridge) {
            kotlinBridge.cellSourceChanged(id, text);
        }
        const off = saveCursorOffset(sourceEl);
        const cellIdx = getCellIndex(id);
        const scope = buildVariableScope(cellIdx);
        const tokens = tokenize(text, scope);
        sourceEl.innerHTML = renderHighlighted(tokens);
        restoreCursorOffset(sourceEl, off);
        scheduleHighlightAll(); // T029: update downstream cells
    };
}

function exitEditMode(id) {
    const sourceEl = document.getElementById('source-' + id);
    if (!sourceEl) return;
    sourceEl.contentEditable = 'false';
    sourceEl.classList.remove('editable');
    sourceEl.onkeydown = null;
    sourceEl.oninput = null;
    sourceEl.blur();
    highlightCellSource(sourceEl, id);
}

function makeEditable(id) {
    enterEditMode(id);
}

function makeReadOnly(id) {
    exitEditMode(id);
}

// ── Markdown Edit Mode (T009, T010) ──

function startEditMarkdown(id) {
    const cell = document.getElementById('cell-' + id);
    const mdSource = document.getElementById('md-source-' + id);
    if (cell && mdSource) {
        cell.classList.add('editing-markdown');
        mdSource.focus();
        mdSource.oninput = function() {
            if (kotlinBridge) {
                kotlinBridge.cellSourceChanged(id, mdSource.textContent);
            }
        };
    }
}

function stopEditMarkdown(id, renderedHtml) {
    const cell = document.getElementById('cell-' + id);
    const rendered = document.getElementById('md-rendered-' + id);
    if (cell) {
        cell.classList.remove('editing-markdown');
    }
    if (rendered) {
        rendered.innerHTML = renderedHtml;
    }
}

// Click-outside handler for markdown cells (T010)
document.addEventListener('mousedown', function(e) {
    const editingCells = document.querySelectorAll('.cell.editing-markdown');
    for (const cell of editingCells) {
        if (!cell.contains(e.target)) {
            const cellId = cell.dataset.cellId;
            const mdSource = document.getElementById('md-source-' + cellId);
            if (mdSource && kotlinBridge) {
                kotlinBridge.cellSourceChanged(cellId, mdSource.textContent);
            }
            cell.classList.remove('editing-markdown');
        }
    }
});

// Close dropdowns on outside click
document.addEventListener('mousedown', function(e) {
    if (!e.target.closest('.cell-gap')) {
        document.querySelectorAll('.add-cell-dropdown.visible').forEach(function(d) {
            d.classList.remove('visible');
        });
    }
});

// ── Existing Functions (updated) ──

function updateCell(id, source) {
    const sourceEl = document.getElementById('source-' + id);
    if (sourceEl) {
        // Apply highlighting (T015)
        sourceEl.textContent = source;
        highlightCellSource(sourceEl, id);
    }
}

function removeCell(id) {
    const cell = document.getElementById('cell-' + id);
    if (cell) {
        cell.remove();
        if (selectedCellId === id) {
            selectedCellId = null;
        }
        rebuildGaps();
        scheduleHighlightAll(); // T029
    }
}

function clearOutputs(id) {
    const outputEl = document.getElementById('output-' + id);
    if (outputEl) {
        outputEl.innerHTML = '';
    }
    const execCount = document.getElementById('exec-count-' + id);
    if (execCount) {
        execCount.textContent = '[*]';
    }
}

function appendOutput(id, outputHtml) {
    const outputEl = document.getElementById('output-' + id);
    if (outputEl) {
        outputEl.innerHTML += outputHtml;
    }
}

function setExecutionCount(id, count) {
    const execCount = document.getElementById('exec-count-' + id);
    if (execCount) {
        execCount.textContent = count != null ? '[' + count + ']' : '[ ]';
    }
}

function setCellExecuting(id, executing) {
    const cell = document.getElementById('cell-' + id);
    if (cell) {
        if (executing) {
            cell.classList.add('executing');
            // Exit edit mode if entering execution (T011)
            const sourceEl = document.getElementById('source-' + id);
            if (sourceEl && sourceEl.contentEditable === 'true') {
                exitEditMode(id);
            }
        } else {
            cell.classList.remove('executing');
        }
    }
}

function selectCell(id) {
    if (selectedCellId) {
        const prev = document.getElementById('cell-' + selectedCellId);
        if (prev) prev.classList.remove('cell-selected');
    }
    selectedCellId = id;
    const cell = document.getElementById('cell-' + id);
    if (cell) {
        cell.classList.add('cell-selected');
    }
    if (kotlinBridge) {
        kotlinBridge.cellSelected(id);
    }
}

function scrollToCell(id) {
    const cell = document.getElementById('cell-' + id);
    if (cell) {
        cell.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
}

function updateMarkdownRendered(id, html) {
    const rendered = document.getElementById('md-rendered-' + id);
    if (rendered) {
        rendered.innerHTML = html;
    }
}

function setMarkdownSource(id, source) {
    const mdSource = document.getElementById('md-source-' + id);
    if (mdSource) {
        mdSource.textContent = source;
    }
}

function clearNotebook() {
    document.getElementById('notebook-container').innerHTML = '';
    selectedCellId = null;
}

function renderNotebookComplete() {
    rebuildGaps();
    highlightAllCells();
}

function getSelectedCellId() {
    return selectedCellId;
}

function insertCellAfter(afterId, newId, type, source, outputsHtml, executionCount) {
    const afterCell = document.getElementById('cell-' + afterId);
    if (!afterCell) {
        addCell(newId, type, source, outputsHtml, executionCount);
        rebuildGaps();
        scheduleHighlightAll(); // T029
        return;
    }

    addCell(newId, type, source, outputsHtml, executionCount);
    const newCell = document.getElementById('cell-' + newId);
    if (newCell) {
        afterCell.parentNode.insertBefore(newCell, afterCell.nextSibling);
    }
    rebuildGaps();
    scheduleHighlightAll(); // T029
}

// Global keyboard handler
document.addEventListener('keydown', function(e) {
    if (e.shiftKey && e.key === 'Enter') {
        // Only handle if not in an editable cell (edit mode handles its own Shift+Enter)
        const active = document.activeElement;
        if (active && active.contentEditable === 'true') return;

        e.preventDefault();
        if (selectedCellId && kotlinBridge) {
            const cell = document.getElementById('cell-' + selectedCellId);
            if (cell && cell.dataset.cellType === 'code') {
                kotlinBridge.runCell(selectedCellId);
            }
        }
    }
});
