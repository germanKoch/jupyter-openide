let selectedCellId = null;
let kotlinBridge = null;
let highlightDebounceTimer = null;

function initBridge(bridge) {
    kotlinBridge = bridge;
}

// ── Syntax Tokenizer ──

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
    var tokens = [];
    var i = 0;
    var len = source.length;
    var known = knownNames || new Set();

    while (i < len) {
        if (source[i] === '#') {
            var end = source.indexOf('\n', i);
            if (end === -1) end = len;
            tokens.push({ type: 'comment', value: source.slice(i, end) });
            i = end;
            continue;
        }

        if (i < len - 2) {
            if ('fFbBrRuU'.includes(source[i]) && i + 3 < len) {
                var afterPrefix = source.slice(i + 1, i + 4);
                if (afterPrefix === '"""' || afterPrefix === "'''") {
                    var startOffset = i;
                    var quote3 = afterPrefix;
                    var end3 = source.indexOf(quote3, i + 4);
                    if (end3 === -1) end3 = len;
                    else end3 += 3;
                    tokens.push({ type: 'string', value: source.slice(startOffset, end3) });
                    i = end3;
                    continue;
                }
            }
            var tri = source.slice(i, i + 3);
            if (tri === '"""' || tri === "'''") {
                var end3b = source.indexOf(tri, i + 3);
                if (end3b === -1) end3b = len;
                else end3b += 3;
                tokens.push({ type: 'string', value: source.slice(i, end3b) });
                i = end3b;
                continue;
            }
        }

        if (source[i] === '"' || source[i] === "'" ||
            ('fFbBrRuU'.includes(source[i]) && i + 1 < len && (source[i + 1] === '"' || source[i + 1] === "'"))) {
            var start = i;
            if ('fFbBrRuU'.includes(source[i])) i++;
            var q = source[i];
            i++;
            while (i < len && source[i] !== q && source[i] !== '\n') {
                if (source[i] === '\\') i++;
                i++;
            }
            if (i < len && source[i] === q) i++;
            tokens.push({ type: 'string', value: source.slice(start, i) });
            continue;
        }

        if (source[i] === '@' && (i === 0 || source[i - 1] === '\n' || /\s/.test(source[i - 1]))) {
            var endD = i + 1;
            while (endD < len && /[\w.]/.test(source[endD])) endD++;
            if (endD > i + 1) {
                tokens.push({ type: 'decorator', value: source.slice(i, endD) });
                i = endD;
                continue;
            }
        }

        if (/[0-9]/.test(source[i]) || (source[i] === '.' && i + 1 < len && /[0-9]/.test(source[i + 1]))) {
            var endN = i;
            if (source[endN] === '0' && endN + 1 < len && 'xXoObB'.includes(source[endN + 1])) {
                endN += 2;
                while (endN < len && /[0-9a-fA-F_]/.test(source[endN])) endN++;
            } else {
                while (endN < len && /[0-9_]/.test(source[endN])) endN++;
                if (endN < len && source[endN] === '.') {
                    endN++;
                    while (endN < len && /[0-9_]/.test(source[endN])) endN++;
                }
                if (endN < len && 'eE'.includes(source[endN])) {
                    endN++;
                    if (endN < len && '+-'.includes(source[endN])) endN++;
                    while (endN < len && /[0-9_]/.test(source[endN])) endN++;
                }
                if (endN < len && 'jJ'.includes(source[endN])) endN++;
            }
            tokens.push({ type: 'number', value: source.slice(i, endN) });
            i = endN;
            continue;
        }

        if (/[a-zA-Z_]/.test(source[i])) {
            var endW = i;
            while (endW < len && /[\w]/.test(source[endW])) endW++;
            var word = source.slice(i, endW);
            if (PYTHON_KEYWORDS.has(word)) {
                tokens.push({ type: 'keyword', value: word });
            } else if (PYTHON_BUILTINS.has(word)) {
                tokens.push({ type: 'builtin', value: word });
            } else if (known.has(word)) {
                tokens.push({ type: 'known-var', value: word });
            } else {
                tokens.push({ type: 'text', value: word });
            }
            i = endW;
            continue;
        }

        var endT = i + 1;
        while (endT < len && !/[a-zA-Z_0-9#"'@.fFbBrRuU]/.test(source[endT]) && source[endT] !== '.') {
            endT++;
        }
        tokens.push({ type: 'text', value: source.slice(i, endT) });
        i = endT;
    }
    return tokens;
}

// ── Highlighted HTML Renderer ──

function escapeHtmlJS(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function renderHighlighted(tokens) {
    var html = '';
    for (var ti = 0; ti < tokens.length; ti++) {
        var tok = tokens[ti];
        var escaped = escapeHtmlJS(tok.value);
        if (tok.type === 'text') {
            html += escaped;
        } else {
            html += '<span class="tok-' + tok.type + '">' + escaped + '</span>';
        }
    }
    return html;
}

// ── Cross-Cell Variable Extraction ──

function extractDefinedNames(source) {
    var names = new Set();
    var lines = source.split('\n');
    for (var li = 0; li < lines.length; li++) {
        var trimmed = lines[li].trimStart();
        var m;
        if ((m = trimmed.match(/^(\w+)\s*=/))) names.add(m[1]);
        if ((m = trimmed.match(/^def\s+(\w+)/))) names.add(m[1]);
        if ((m = trimmed.match(/^class\s+(\w+)/))) names.add(m[1]);
        if ((m = trimmed.match(/^import\s+(\w+)/))) names.add(m[1]);
        if ((m = trimmed.match(/^from\s+\w+\s+import\s+(.+)/))) {
            m[1].split(',').forEach(function(part) {
                var asMatch = part.trim().match(/(\w+)\s+as\s+(\w+)/);
                if (asMatch) {
                    names.add(asMatch[2]);
                } else {
                    var name = part.trim().match(/^(\w+)/);
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

function getCellText(cellId) {
    var cell = document.getElementById('cell-' + cellId);
    if (!cell) return '';
    var textarea = cell.querySelector('.source-input');
    if (textarea && cell.querySelector('.cell-source.editing')) {
        return textarea.value;
    }
    var backdrop = cell.querySelector('.source-backdrop');
    if (backdrop) return backdrop.textContent;
    return '';
}

function buildVariableScope(upToCellIndex) {
    var names = new Set();
    var cells = document.querySelectorAll('#notebook-container .cell');
    var idx = 0;
    for (var ci = 0; ci < cells.length; ci++) {
        if (idx >= upToCellIndex) break;
        var cell = cells[ci];
        if (cell.dataset.cellType === 'code') {
            var text = getCellText(cell.dataset.cellId);
            var cellNames = extractDefinedNames(text);
            cellNames.forEach(function(n) { names.add(n); });
        }
        idx++;
    }
    return names;
}

// ── Highlight Helpers ──

function getCellIndex(cellId) {
    var cells = document.querySelectorAll('#notebook-container .cell');
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellId === cellId) return i;
    }
    return -1;
}

function highlightBackdrop(cellId) {
    var cell = document.getElementById('cell-' + cellId);
    if (!cell || cell.dataset.cellType !== 'code') return;
    var backdrop = cell.querySelector('.source-backdrop');
    if (!backdrop) return;
    var text = getCellText(cellId);
    var cellIdx = getCellIndex(cellId);
    var scope = buildVariableScope(cellIdx);
    var tokens = tokenize(text, scope);
    backdrop.innerHTML = renderHighlighted(tokens);
}

function highlightAllCells() {
    var cells = document.querySelectorAll('#notebook-container .cell');
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellType === 'code') {
            highlightBackdrop(cells[i].dataset.cellId);
        }
    }
}

function scheduleHighlightAll() {
    if (highlightDebounceTimer) clearTimeout(highlightDebounceTimer);
    highlightDebounceTimer = setTimeout(highlightAllCells, 300);
}

// ── Cell Gap Management ──

function createCellGap(afterCellId) {
    var gap = document.createElement('div');
    gap.className = 'cell-gap';
    gap.dataset.afterCellId = afterCellId || '';

    var btn = document.createElement('button');
    btn.className = 'add-cell-btn';
    btn.textContent = '+';
    btn.title = 'Add cell';
    btn.onclick = function(e) {
        e.stopPropagation();
        var dropdown = gap.querySelector('.add-cell-dropdown');
        if (dropdown) dropdown.classList.toggle('visible');
    };
    gap.appendChild(btn);

    var dropdown = document.createElement('div');
    dropdown.className = 'add-cell-dropdown';

    var codeBtn = document.createElement('button');
    codeBtn.textContent = '+ Code';
    codeBtn.onclick = function(e) {
        e.stopPropagation();
        dropdown.classList.remove('visible');
        if (kotlinBridge) kotlinBridge.addCell(afterCellId || '', 'code');
    };
    dropdown.appendChild(codeBtn);

    var mdBtn = document.createElement('button');
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
    var container = document.getElementById('notebook-container');
    var oldGaps = container.querySelectorAll('.cell-gap');
    for (var g = 0; g < oldGaps.length; g++) oldGaps[g].remove();

    var cells = container.querySelectorAll('.cell');
    var firstGap = createCellGap('');
    if (cells.length > 0) {
        container.insertBefore(firstGap, cells[0]);
    } else {
        container.appendChild(firstGap);
    }

    for (var c = 0; c < cells.length; c++) {
        var gap = createCellGap(cells[c].dataset.cellId);
        if (cells[c].nextSibling) {
            container.insertBefore(gap, cells[c].nextSibling);
        } else {
            container.appendChild(gap);
        }
    }
}

// ── Textarea height sync ──

function syncTextareaHeight(cellId) {
    var cell = document.getElementById('cell-' + cellId);
    if (!cell) return;
    var backdrop = cell.querySelector('.source-backdrop');
    var textarea = cell.querySelector('.source-input');
    if (!backdrop || !textarea) return;
    var h = backdrop.scrollHeight;
    if (h < 24) h = 24;
    textarea.style.height = h + 'px';
}

// ── Cell Construction ──

function addCell(id, type, source, outputsHtml, executionCount) {
    var container = document.getElementById('notebook-container');
    var cell = document.createElement('div');
    cell.id = 'cell-' + id;
    cell.className = 'cell';
    cell.dataset.cellId = id;
    cell.dataset.cellType = type;
    cell.onclick = function(e) { selectCell(id); };

    var header = document.createElement('div');
    header.className = 'cell-header';

    var badge = document.createElement('span');
    badge.className = 'cell-type-badge ' + type;
    badge.textContent = type;
    header.appendChild(badge);

    if (type === 'code') {
        var runBtn = document.createElement('button');
        runBtn.className = 'run-btn';
        runBtn.textContent = '▶';
        runBtn.title = 'Run cell (Shift+Enter)';
        runBtn.onclick = function(e) {
            e.stopPropagation();
            selectCell(id);
            if (kotlinBridge) kotlinBridge.runCell(id);
        };
        header.appendChild(runBtn);

        var execCount = document.createElement('span');
        execCount.className = 'execution-count';
        execCount.id = 'exec-count-' + id;
        execCount.textContent = executionCount != null ? '[' + executionCount + ']' : '[ ]';
        header.appendChild(execCount);

        var indicator = document.createElement('span');
        indicator.className = 'execution-indicator';
        indicator.textContent = '●';
        header.appendChild(indicator);
    }

    var delBtn = document.createElement('button');
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
        var sourceWrapper = document.createElement('div');
        sourceWrapper.className = 'cell-source';
        sourceWrapper.id = 'source-' + id;

        var backdrop = document.createElement('pre');
        backdrop.className = 'source-backdrop';

        var decodedSource = source || '';
        var cellIdx = getCellIndexByContainer(container, id);
        var scope = buildVariableScope(cellIdx >= 0 ? cellIdx : 9999);
        var tokens = tokenize(decodedSource, scope);
        backdrop.innerHTML = renderHighlighted(tokens);

        var textarea = document.createElement('textarea');
        textarea.className = 'source-input';
        textarea.spellcheck = false;
        textarea.autocomplete = 'off';
        textarea.autocorrect = 'off';
        textarea.autocapitalize = 'off';
        textarea.value = decodedSource;

        sourceWrapper.appendChild(backdrop);
        sourceWrapper.appendChild(textarea);

        sourceWrapper.onclick = function(e) {
            e.stopPropagation();
            if (cell.classList.contains('executing')) return;
            enterEditMode(id);
        };

        cell.appendChild(sourceWrapper);

        var outputDiv = document.createElement('div');
        outputDiv.className = 'cell-output';
        outputDiv.id = 'output-' + id;
        outputDiv.innerHTML = outputsHtml || '';
        cell.appendChild(outputDiv);
    } else {
        var renderedDiv = document.createElement('div');
        renderedDiv.className = 'markdown-rendered';
        renderedDiv.id = 'md-rendered-' + id;
        renderedDiv.innerHTML = source;

        renderedDiv.onclick = function(e) {
            e.stopPropagation();
            if (cell.classList.contains('executing')) return;
            startEditMarkdown(id);
        };

        cell.appendChild(renderedDiv);

        var mdSourceDiv = document.createElement('div');
        mdSourceDiv.className = 'markdown-source';
        mdSourceDiv.id = 'md-source-' + id;
        mdSourceDiv.contentEditable = 'true';
        cell.appendChild(mdSourceDiv);
    }

    container.appendChild(cell);
}

function getCellIndexByContainer(container, cellId) {
    var cells = container.querySelectorAll('.cell');
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellId === cellId) return i;
    }
    return -1;
}

// ── Edit Mode (Textarea overlay) ──

function exitAllEditModes() {
    var editingWrappers = document.querySelectorAll('.cell-source.editing');
    for (var i = 0; i < editingWrappers.length; i++) {
        var cell = editingWrappers[i].closest('.cell');
        if (cell) exitEditMode(cell.dataset.cellId);
    }
    var editingMd = document.querySelectorAll('.cell.editing-markdown');
    for (var i = 0; i < editingMd.length; i++) {
        var cellId = editingMd[i].dataset.cellId;
        var mdSource = document.getElementById('md-source-' + cellId);
        if (mdSource && kotlinBridge) {
            kotlinBridge.cellSourceChanged(cellId, mdSource.textContent);
        }
        editingMd[i].classList.remove('editing-markdown');
    }
}

function enterEditMode(id) {
    exitAllEditModes();

    var cell = document.getElementById('cell-' + id);
    if (!cell) return;
    var sourceWrapper = cell.querySelector('.cell-source');
    var textarea = cell.querySelector('.source-input');
    var backdrop = cell.querySelector('.source-backdrop');
    if (!sourceWrapper || !textarea || !backdrop) return;

    selectCell(id);
    sourceWrapper.classList.add('editing');
    textarea.value = backdrop.textContent;
    syncTextareaHeight(id);
    textarea.focus();

    textarea.onkeydown = function(e) {
        if (e.key === 'Tab' && !e.shiftKey) {
            e.preventDefault();
            var start = textarea.selectionStart;
            var end = textarea.selectionEnd;
            var v = textarea.value;
            textarea.value = v.substring(0, start) + '    ' + v.substring(end);
            textarea.selectionStart = textarea.selectionEnd = start + 4;
            textarea.dispatchEvent(new Event('input'));
            return;
        }
        if (e.key === 'Escape') {
            e.preventDefault();
            exitEditMode(id);
            return;
        }
        if (e.key === 'Enter' && (e.shiftKey || e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            return;
        }
        if (e.key === 's' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            if (kotlinBridge) kotlinBridge.saveNotebook();
            return;
        }
        if ((e.metaKey || e.ctrlKey) && e.key === 'ArrowLeft') {
            e.preventDefault();
            var pos = textarea.selectionStart;
            var text = textarea.value;
            var lineStart = text.lastIndexOf('\n', pos - 1) + 1;
            textarea.selectionStart = textarea.selectionEnd = lineStart;
            return;
        }
        if ((e.metaKey || e.ctrlKey) && e.key === 'ArrowRight') {
            e.preventDefault();
            var pos = textarea.selectionStart;
            var text = textarea.value;
            var lineEnd = text.indexOf('\n', pos);
            if (lineEnd === -1) lineEnd = text.length;
            textarea.selectionStart = textarea.selectionEnd = lineEnd;
            return;
        }
        if (e.altKey && e.key === 'ArrowLeft') {
            e.preventDefault();
            var pos = textarea.selectionStart;
            var text = textarea.value;
            var i = pos - 1;
            while (i > 0 && /\s/.test(text[i])) i--;
            while (i > 0 && /\w/.test(text[i - 1])) i--;
            textarea.selectionStart = textarea.selectionEnd = i;
            return;
        }
        if (e.altKey && e.key === 'ArrowRight') {
            e.preventDefault();
            var pos = textarea.selectionStart;
            var text = textarea.value;
            var i = pos;
            while (i < text.length && /\s/.test(text[i])) i++;
            while (i < text.length && /\w/.test(text[i])) i++;
            textarea.selectionStart = textarea.selectionEnd = i;
            return;
        }
    };

    textarea.oninput = function() {
        var text = textarea.value;
        if (kotlinBridge) kotlinBridge.cellSourceChanged(id, text);
        var cellIdx = getCellIndex(id);
        var scope = buildVariableScope(cellIdx);
        var tokens = tokenize(text, scope);
        backdrop.innerHTML = renderHighlighted(tokens);
        syncTextareaHeight(id);
        scheduleHighlightAll();
    };

    textarea.onscroll = function() {
        backdrop.scrollTop = textarea.scrollTop;
        backdrop.scrollLeft = textarea.scrollLeft;
    };
}

function exitEditMode(id) {
    var cell = document.getElementById('cell-' + id);
    if (!cell) return;
    var sourceWrapper = cell.querySelector('.cell-source');
    var textarea = cell.querySelector('.source-input');
    if (!sourceWrapper || !textarea) return;

    sourceWrapper.classList.remove('editing');
    textarea.onkeydown = null;
    textarea.oninput = null;
    textarea.onscroll = null;
    textarea.blur();
    highlightBackdrop(id);
}

function isEditing(id) {
    var cell = document.getElementById('cell-' + id);
    if (!cell) return false;
    var sw = cell.querySelector('.cell-source');
    return sw && sw.classList.contains('editing');
}

function moveToNextCell(currentId) {
    var cells = document.querySelectorAll('#notebook-container .cell');
    var found = false;
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellId === currentId) {
            found = true;
            continue;
        }
        if (found) {
            var nextId = cells[i].dataset.cellId;
            selectCell(nextId);
            scrollToCell(nextId);
            if (cells[i].dataset.cellType === 'code') {
                enterEditMode(nextId);
            } else if (cells[i].dataset.cellType === 'markdown') {
                startEditMarkdown(nextId);
            }
            return;
        }
    }
}

function makeEditable(id) {
    enterEditMode(id);
}

function makeReadOnly(id) {
    exitEditMode(id);
}

// ── Markdown Edit Mode ──

function startEditMarkdown(id) {
    exitAllEditModes();
    selectCell(id);
    var cell = document.getElementById('cell-' + id);
    var mdSource = document.getElementById('md-source-' + id);
    if (cell && mdSource) {
        cell.classList.add('editing-markdown');
        mdSource.focus();
        mdSource.oninput = function() {
            if (kotlinBridge) kotlinBridge.cellSourceChanged(id, mdSource.textContent);
        };
        mdSource.onkeydown = function(e) {
            if (e.key === 'Enter' && (e.shiftKey || e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                return;
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                if (kotlinBridge) kotlinBridge.cellSourceChanged(id, mdSource.textContent);
                cell.classList.remove('editing-markdown');
            }
            if (e.key === 's' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                if (kotlinBridge) kotlinBridge.saveNotebook();
            }
        };
    }
}

function stopEditMarkdown(id, renderedHtml) {
    var cell = document.getElementById('cell-' + id);
    var rendered = document.getElementById('md-rendered-' + id);
    if (cell) cell.classList.remove('editing-markdown');
    if (rendered) rendered.innerHTML = renderedHtml;
}

// Click-outside handler for markdown cells
document.addEventListener('mousedown', function(e) {
    var editingCells = document.querySelectorAll('.cell.editing-markdown');
    for (var i = 0; i < editingCells.length; i++) {
        var cell = editingCells[i];
        if (!cell.contains(e.target)) {
            var cellId = cell.dataset.cellId;
            var mdSource = document.getElementById('md-source-' + cellId);
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
        var dropdowns = document.querySelectorAll('.add-cell-dropdown.visible');
        for (var i = 0; i < dropdowns.length; i++) dropdowns[i].classList.remove('visible');
    }
});

// ── Existing Functions (updated for textarea overlay) ──

function updateCell(id, source) {
    var cell = document.getElementById('cell-' + id);
    if (!cell) return;
    var backdrop = cell.querySelector('.source-backdrop');
    var textarea = cell.querySelector('.source-input');
    if (backdrop) {
        if (textarea) textarea.value = source;
        highlightBackdrop(id);
        syncTextareaHeight(id);
    }
}

function removeCell(id) {
    var cell = document.getElementById('cell-' + id);
    if (cell) {
        cell.remove();
        if (selectedCellId === id) selectedCellId = null;
        rebuildGaps();
        scheduleHighlightAll();
    }
}

function clearOutputs(id) {
    var outputEl = document.getElementById('output-' + id);
    if (outputEl) outputEl.innerHTML = '';
    var execCount = document.getElementById('exec-count-' + id);
    if (execCount) execCount.textContent = '[*]';
}

function appendOutput(id, outputHtml) {
    var outputEl = document.getElementById('output-' + id);
    if (outputEl) outputEl.innerHTML += outputHtml;
}

function setExecutionCount(id, count) {
    var execCount = document.getElementById('exec-count-' + id);
    if (execCount) execCount.textContent = count != null ? '[' + count + ']' : '[ ]';
}

function setCellExecuting(id, executing) {
    var cell = document.getElementById('cell-' + id);
    if (!cell) return;
    if (executing) {
        cell.classList.add('executing');
        if (isEditing(id)) exitEditMode(id);
    } else {
        cell.classList.remove('executing');
    }
}

function selectCell(id) {
    if (selectedCellId && selectedCellId !== id) {
        var prev = document.getElementById('cell-' + selectedCellId);
        if (prev) {
            prev.classList.remove('cell-selected');
            if (isEditing(selectedCellId)) exitEditMode(selectedCellId);
        }
    }
    selectedCellId = id;
    var cell = document.getElementById('cell-' + id);
    if (cell) cell.classList.add('cell-selected');
    if (kotlinBridge) kotlinBridge.cellSelected(id);
}

function scrollToCell(id) {
    var cell = document.getElementById('cell-' + id);
    if (cell) cell.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function updateMarkdownRendered(id, html) {
    var rendered = document.getElementById('md-rendered-' + id);
    if (rendered) rendered.innerHTML = html;
}

function setMarkdownSource(id, source) {
    var mdSource = document.getElementById('md-source-' + id);
    if (mdSource) mdSource.textContent = source;
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
    var container = document.getElementById('notebook-container');

    if (!afterId || afterId === '') {
        addCell(newId, type, source, outputsHtml, executionCount);
        var newCell = document.getElementById('cell-' + newId);
        if (newCell) {
            var firstCell = container.querySelector('.cell');
            if (firstCell && firstCell !== newCell) {
                container.insertBefore(newCell, firstCell);
            }
        }
        rebuildGaps();
        scheduleHighlightAll();
        selectCell(newId);
        if (type === 'code') enterEditMode(newId);
        return;
    }

    var afterCell = document.getElementById('cell-' + afterId);
    if (!afterCell) {
        addCell(newId, type, source, outputsHtml, executionCount);
        rebuildGaps();
        scheduleHighlightAll();
        selectCell(newId);
        if (type === 'code') enterEditMode(newId);
        return;
    }

    addCell(newId, type, source, outputsHtml, executionCount);
    var newCellEl = document.getElementById('cell-' + newId);
    if (newCellEl) {
        afterCell.parentNode.insertBefore(newCellEl, afterCell.nextSibling);
    }
    rebuildGaps();
    scheduleHighlightAll();
    selectCell(newId);
    if (type === 'code') enterEditMode(newId);
}

// Global keyboard handler
document.addEventListener('keydown', function(e) {
    if (e.key === 's' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        if (kotlinBridge) kotlinBridge.saveNotebook();
        return;
    }

    if (e.key === 'Enter' && (e.shiftKey || e.metaKey || e.ctrlKey)) {
        if (!selectedCellId) return;
        e.preventDefault();
        var cellId = selectedCellId;
        var cell = document.getElementById('cell-' + cellId);
        if (!cell) return;

        if (isEditing(cellId)) {
            exitEditMode(cellId);
        }
        if (cell.classList.contains('editing-markdown')) {
            var mdSource = document.getElementById('md-source-' + cellId);
            if (mdSource && kotlinBridge) kotlinBridge.cellSourceChanged(cellId, mdSource.textContent);
            cell.classList.remove('editing-markdown');
        }

        if (cell.dataset.cellType === 'code' && kotlinBridge) {
            kotlinBridge.runCell(cellId);
        }
        moveToNextCell(cellId);
    }
});
