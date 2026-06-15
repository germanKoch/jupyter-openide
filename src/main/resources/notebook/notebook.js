let selectedCellId = null;
let kotlinBridge = null;
let highlightDebounceTimer = null;
let diagnosticsByCell = {};
let usageHighlightName = null;
let lastShiftAt = 0;
let cmdLinkName = null;
let cmdLinkCellId = null;
let cmdLinkPos = null;
let diagTipTarget = null;
let pendingAdvanceCellId = null;
let searchState = {
    active: false,
    replaceMode: false,
    query: '',
    matches: [],      // [{ range, cellId, kind, start, length }] in document order
    index: -1,
    returnFocus: null,
    refreshTimer: null
};

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

function attrEscape(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function diagAt(diags, line, col) {
    for (var i = 0; i < diags.length; i++) {
        var d = diags[i];
        if (d.line === line && col >= d.col && col < d.endCol) return d;
    }
    return null;
}

function renderHighlighted(tokens, diags, linkName, linkPos) {
    diags = diags || [];
    var html = '';
    var line = 0, col = 0;
    for (var ti = 0; ti < tokens.length; ti++) {
        var tok = tokens[ti];
        var val = tok.value;
        var tokLine = line, tokCol = col; // token start, for precise cmd-link matching
        var isIdent = (tok.type === 'text' || tok.type === 'known-var' || tok.type === 'builtin');
        var classes = [];
        if (tok.type !== 'text') classes.push('tok-' + tok.type);
        if (isIdent && usageHighlightName && val === usageHighlightName) classes.push('usage-highlight');
        if (isIdent && linkName && val === linkName && linkPos &&
            tokLine === linkPos.line && tokCol === linkPos.col) {
            classes.push('cmd-link');
        }
        var openTok = classes.length ? ('<span class="' + classes.join(' ') + '">') : '';
        var closeTok = classes.length ? '</span>' : '';

        html += openTok;
        var chars = Array.from(val); // iterate by code point so columns match the analyzer
        var inDiag = false, curDiag = null;
        for (var ci = 0; ci < chars.length; ci++) {
            var ch = chars[ci];
            if (ch === '\n') {
                if (inDiag) { html += '</span>'; inDiag = false; curDiag = null; }
                html += '\n';
                line++; col = 0;
                continue;
            }
            var d = diags.length ? diagAt(diags, line, col) : null;
            if (d && (!inDiag || d !== curDiag)) {
                if (inDiag) html += '</span>';
                html += '<span class="diag diag-' + d.severity + '" data-diag="' +
                        attrEscape(d.message) + '" data-sev="' + d.severity + '">';
                inDiag = true; curDiag = d;
            } else if (!d && inDiag) {
                html += '</span>'; inDiag = false; curDiag = null;
            }
            html += escapeHtmlJS(ch);
            col++;
        }
        if (inDiag) html += '</span>';
        html += closeTok;
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
    // A re-render removes the span the tooltip is anchored to; hide it first so
    // it can't be left floating (DOM removal does not fire mouseout).
    if (diagTipTarget && backdrop.contains(diagTipTarget)) hideDiagTooltip();
    var text = getCellText(cellId);
    var cellIdx = getCellIndex(cellId);
    var scope = buildVariableScope(cellIdx);
    var tokens = tokenize(text, scope);
    var linkName = (cellId === cmdLinkCellId) ? cmdLinkName : null;
    var linkPos = (cellId === cmdLinkCellId) ? cmdLinkPos : null;
    backdrop.innerHTML = renderHighlighted(tokens, diagnosticsByCell[cellId] || [], linkName, linkPos);
    // A re-render replaces the text nodes our search ranges point at, detaching
    // them; recompute (debounced) while a search is active.
    if (searchState.active) scheduleSearchRefresh();
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
        backdrop.innerHTML = renderHighlighted(tokens, diagnosticsByCell[id] || []);

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
            if ((e.metaKey || e.ctrlKey) && !sourceWrapper.classList.contains('editing')) {
                var w = wordAtBackdropPoint(backdrop, e);
                if (w) {
                    e.preventDefault();
                    gotoDefinition(w, id);
                    return;
                }
            }
            clearUsageHighlight();
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
            clearUsageHighlight();
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
    hideDiagTooltip();

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
            usageHighlightName = null;
            exitEditMode(id);
            scheduleHighlightAll();
            return;
        }
        if (e.key === 'Enter' && (e.shiftKey || e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            return;
        }
        if ((e.metaKey || e.ctrlKey) && !e.shiftKey && (e.key === 'b' || e.key === 'B')) {
            e.preventDefault();
            var bw = wordAtIndex(textarea.value, textarea.selectionStart);
            if (bw) gotoDefinition(bw, id);
            return;
        }
        if ((e.metaKey || e.ctrlKey) && e.key === '/') {
            e.preventDefault();
            toggleComment(textarea);
            return;
        }
        if ((e.metaKey || e.ctrlKey) && !e.shiftKey && (e.key === 'd' || e.key === 'D')) {
            e.preventDefault();
            duplicateSelection(textarea);
            return;
        }
        if ((e.metaKey || e.ctrlKey) && e.key === 'Backspace') {
            e.preventDefault();
            deleteLine(textarea);
            return;
        }
        if (e.altKey && e.shiftKey && e.key === 'ArrowUp') {
            e.preventDefault();
            moveLine(textarea, -1);
            return;
        }
        if (e.altKey && e.shiftKey && e.key === 'ArrowDown') {
            e.preventDefault();
            moveLine(textarea, 1);
            return;
        }
        if (e.key === 's' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            e.stopPropagation();
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
        // Diagnostics become stale the moment the text changes; drop them
        // until the analyzer returns fresh results.
        if (diagnosticsByCell[id]) delete diagnosticsByCell[id];
        highlightBackdrop(id);
        syncTextareaHeight(id);
        scheduleHighlightAll();
    };

    textarea.onclick = function(e) {
        if (e.metaKey || e.ctrlKey) {
            e.preventDefault();
            e.stopPropagation();
            var w = wordAtIndex(textarea.value, textarea.selectionStart);
            if (w) gotoDefinition(w, id);
        }
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
    var idx = -1;
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellId === currentId) { idx = i; break; }
    }
    if (idx === -1) return;
    if (idx < cells.length - 1) {
        var nextEl = cells[idx + 1];
        var nextId = nextEl.dataset.cellId;
        selectCell(nextId);
        scrollToCell(nextId);
        if (nextEl.dataset.cellType === 'code') {
            enterEditMode(nextId);
        } else if (nextEl.dataset.cellType === 'markdown') {
            startEditMarkdown(nextId);
        }
    } else {
        // Last cell: create a new code cell below and focus it.
        // insertCellAfter (triggered via the bridge) selects + edits the new cell.
        if (kotlinBridge) kotlinBridge.addCell(currentId, 'code');
    }
}

function onCellExecuted(cellId, success) {
    if (cellId === pendingAdvanceCellId) {
        pendingAdvanceCellId = null;
        // Only advance if the user is still on this cell (don't yank focus away
        // if they navigated elsewhere while it was running).
        if (success && cellId === selectedCellId) moveToNextCell(cellId);
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
                e.stopPropagation();
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
    if (searchState.active) scheduleSearchRefresh();
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

// ── Diagnostic tooltip (IDE-style overlay) ──

function getDiagTooltip() {
    var t = document.getElementById('diag-tooltip');
    if (!t) {
        t = document.createElement('div');
        t.id = 'diag-tooltip';
        document.body.appendChild(t);
    }
    return t;
}

function pickDiagRect(target, e) {
    var rects = target.getClientRects();
    if (rects && rects.length > 1 && e) {
        for (var i = 0; i < rects.length; i++) {
            var rc = rects[i];
            if (e.clientY >= rc.top && e.clientY <= rc.bottom) return rc;
        }
        return rects[0];
    }
    return target.getBoundingClientRect();
}

function showDiagTooltip(target, e) {
    var msg = target.getAttribute('data-diag');
    if (!msg) return;
    var sev = target.getAttribute('data-sev') || 'error';
    var tip = getDiagTooltip();
    tip.textContent = msg;
    tip.className = 'visible diag-tip-' + sev;
    diagTipTarget = target;
    var r = pickDiagRect(target, e);
    var tw = tip.offsetWidth, th = tip.offsetHeight;
    var left = r.left;
    if (left + tw > window.innerWidth - 8) left = window.innerWidth - tw - 8;
    if (left < 8) left = 8;
    var top = r.bottom + 6;
    if (top + th > window.innerHeight - 8) top = r.top - th - 6; // flip above if no room
    tip.style.left = left + 'px';
    tip.style.top = top + 'px';
}

function hideDiagTooltip() {
    var t = document.getElementById('diag-tooltip');
    if (t) t.className = '';
    diagTipTarget = null;
}

document.addEventListener('mouseover', function(e) {
    var d = e.target.closest && e.target.closest('.diag');
    if (d) showDiagTooltip(d, e);
});

document.addEventListener('mouseout', function(e) {
    var d = e.target.closest && e.target.closest('.diag');
    if (d) {
        var to = e.relatedTarget;
        if (!to || !to.closest || !to.closest('.diag')) hideDiagTooltip();
    }
});

// ── Cmd/Ctrl-hover link affordance (underline + pointer cursor) ──

function clearCmdLink() {
    if (cmdLinkCellId) {
        var c = cmdLinkCellId;
        cmdLinkName = null;
        cmdLinkCellId = null;
        cmdLinkPos = null;
        highlightBackdrop(c);
    }
}

// Returns {word, line, col} for the identifier under the cursor, where line/col
// are the word's 0-based start position (code points) within the cell source.
function wordInfoAtBackdropPoint(bd, e) {
    if (!document.caretRangeFromPoint) return null;
    var range = document.caretRangeFromPoint(e.clientX, e.clientY);
    if (!range) return null;
    var node = range.startContainer;
    if (!node || node.nodeType !== 3) return null;
    var text = node.textContent || '';
    var l = range.startOffset, r = range.startOffset;
    while (l > 0 && /\w/.test(text[l - 1])) l--;
    while (r < text.length && /\w/.test(text[r])) r++;
    var word = text.slice(l, r);
    if (!/^[A-Za-z_]\w*$/.test(word)) return null;
    var pre = document.createRange();
    pre.selectNodeContents(bd);
    pre.setEnd(node, l);
    var before = pre.toString();
    var lastNl = before.lastIndexOf('\n');
    var lineText = (lastNl === -1) ? before : before.slice(lastNl + 1);
    return {
        word: word,
        line: (before.match(/\n/g) || []).length,
        col: Array.from(lineText).length
    };
}

function updateCmdHover(e) {
    var down = e.metaKey || e.ctrlKey;
    // Don't disturb an active text selection (e.g. Cmd+drag) by re-rendering.
    var sel = window.getSelection();
    if (sel && !sel.isCollapsed) return;

    var info = null, cellId = null;
    if (down) {
        var bd = e.target.closest && e.target.closest('.source-backdrop');
        if (bd) {
            var cellEl = bd.closest('.cell');
            var src = bd.closest('.cell-source');
            if (cellEl && src && !src.classList.contains('editing')) {
                info = wordInfoAtBackdropPoint(bd, e);
                if (info) cellId = cellEl.dataset.cellId;
            }
        }
    }
    var name = info ? info.word : '';
    var changed = name !== (cmdLinkName || '') || cellId !== cmdLinkCellId ||
        (info && cmdLinkPos && (info.line !== cmdLinkPos.line || info.col !== cmdLinkPos.col));
    if (changed) {
        var prevCell = cmdLinkCellId;
        cmdLinkName = name || null;
        cmdLinkCellId = cellId;
        cmdLinkPos = info ? { line: info.line, col: info.col } : null;
        if (prevCell && prevCell !== cellId) highlightBackdrop(prevCell);
        if (cellId) highlightBackdrop(cellId);
    }
}

document.addEventListener('mousemove', updateCmdHover);

// Robust clears: a keyup with no modifier held, or focus/visibility loss
// (the Meta/Control keyup is often not delivered to the JCEF document).
document.addEventListener('keyup', function(e) {
    if (!(e.metaKey || e.ctrlKey)) clearCmdLink();
});
window.addEventListener('blur', clearCmdLink);
document.addEventListener('visibilitychange', function() {
    if (document.hidden) clearCmdLink();
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
    if (searchState.active) scheduleSearchRefresh();
}

function appendOutput(id, outputHtml) {
    var outputEl = document.getElementById('output-' + id);
    if (outputEl) outputEl.innerHTML += outputHtml;
    if (searchState.active) scheduleSearchRefresh();
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
    if (searchState.active) scheduleSearchRefresh();
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

// ── Diagnostics ──

function setDiagnostics(json) {
    var arr;
    try { arr = JSON.parse(json); } catch (e) { return; }
    diagnosticsByCell = {};
    for (var i = 0; i < arr.length; i++) {
        var d = arr[i];
        if (!diagnosticsByCell[d.cellId]) diagnosticsByCell[d.cellId] = [];
        diagnosticsByCell[d.cellId].push(d);
    }
    highlightAllCells();
}

// ── Go to definition / usages ──

function wordAtIndex(text, idx) {
    if (idx < 0) idx = 0;
    if (idx > text.length) idx = text.length;
    var l = idx, r = idx;
    while (l > 0 && /\w/.test(text[l - 1])) l--;
    while (r < text.length && /\w/.test(text[r])) r++;
    var w = text.slice(l, r);
    if (/^[A-Za-z_]\w*$/.test(w)) return w;
    return '';
}

function wordAtBackdropPoint(el, e) {
    if (!document.caretRangeFromPoint) return '';
    var range = document.caretRangeFromPoint(e.clientX, e.clientY);
    if (!range) return '';
    var node = range.startContainer;
    // Only a text-node container gives a real character offset; an element
    // container reports a child-node index, which would yield a bogus word.
    if (!node || node.nodeType !== 3) return '';
    return wordAtIndex(node.textContent || '', range.startOffset);
}

function clearUsageHighlight() {
    if (usageHighlightName !== null) {
        usageHighlightName = null;
        highlightAllCells();
    }
}

function extractDefinitions(source) {
    var defs = [];
    var lines = source.split('\n');
    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        var lead = line.length - line.trimStart().length;
        var trimmed = line.trimStart();
        var m;
        function pushDef(name, colInTrimmed) {
            defs.push({ name: name, line: li, col: lead + (colInTrimmed || 0) });
        }
        if ((m = trimmed.match(/^(\w+)\s*=(?!=)/))) pushDef(m[1], 0);
        else if ((m = trimmed.match(/^(\w+)\s*:[^=]*=(?!=)/))) pushDef(m[1], 0);
        if ((m = trimmed.match(/^def\s+(\w+)/))) pushDef(m[1], trimmed.indexOf(m[1], 3));
        if ((m = trimmed.match(/^class\s+(\w+)/))) pushDef(m[1], trimmed.indexOf(m[1], 5));
        if ((m = trimmed.match(/^import\s+(.+)/))) {
            m[1].split(',').forEach(function(part) {
                var as = part.trim().match(/(\w[\w.]*)\s+as\s+(\w+)/);
                if (as) pushDef(as[2], 0);
                else { var n = part.trim().match(/^(\w+)/); if (n) pushDef(n[1], 0); }
            });
        }
        if ((m = trimmed.match(/^from\s+[\w.]+\s+import\s+(.+)/))) {
            m[1].replace(/[()]/g, '').split(',').forEach(function(part) {
                var as = part.trim().match(/(\w+)\s+as\s+(\w+)/);
                if (as) pushDef(as[2], 0);
                else { var n = part.trim().match(/^(\w+)/); if (n && n[1] !== '*') pushDef(n[1], 0); }
            });
        }
        if ((m = trimmed.match(/^for\s+(\w+)\s+in/))) pushDef(m[1], trimmed.indexOf(m[1], 3));
        if (/^(?:async\s+)?with\b/.test(trimmed)) {
            var wre = /\bas\s+(\w+)/g, wm;
            while ((wm = wre.exec(trimmed))) pushDef(wm[1], wm.index + wm[0].indexOf(wm[1]));
        }
    }
    return defs;
}

function findDefinition(name, currentCellId) {
    var cells = document.querySelectorAll('#notebook-container .cell');
    var codeIds = [];
    var curPos = -1;
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellType === 'code') {
            codeIds.push(cells[i].dataset.cellId);
            if (cells[i].dataset.cellId === currentCellId) curPos = codeIds.length - 1;
        }
    }
    var searchOrder = [];
    if (curPos >= 0) {
        searchOrder.push(codeIds[curPos]);
        for (var j = curPos - 1; j >= 0; j--) searchOrder.push(codeIds[j]);
    } else {
        searchOrder = codeIds.slice();
    }
    for (var k = 0; k < searchOrder.length; k++) {
        var cid = searchOrder[k];
        var defs = extractDefinitions(getCellText(cid));
        for (var di = 0; di < defs.length; di++) {
            if (defs[di].name === name) {
                return { cellId: cid, line: defs[di].line, col: defs[di].col };
            }
        }
    }
    return null;
}

function gotoDefinition(name, currentCellId) {
    usageHighlightName = name;
    var loc = findDefinition(name, currentCellId);
    if (!loc) {
        highlightAllCells();
        return;
    }
    selectCell(loc.cellId);
    enterEditMode(loc.cellId);
    var ta = document.getElementById('cell-' + loc.cellId);
    ta = ta && ta.querySelector('.source-input');
    if (ta) {
        var lines = ta.value.split('\n');
        var idx = 0;
        for (var i = 0; i < loc.line && i < lines.length; i++) idx += lines[i].length + 1;
        idx += Math.min(loc.col, (lines[loc.line] || '').length);
        ta.selectionStart = ta.selectionEnd = idx;
        ta.focus();
    }
    scrollToCell(loc.cellId);
    highlightAllCells();
}

// ── In-cell editor operations ──

function toggleComment(ta) {
    var v = ta.value, s = ta.selectionStart, e = ta.selectionEnd;
    var startLineStart = v.lastIndexOf('\n', s - 1) + 1;
    // A selection that ends exactly at a line start belongs to the previous line.
    var ee = (e > s && e > 0 && v.charAt(e - 1) === '\n') ? e - 1 : e;
    var endLineEnd = v.indexOf('\n', ee);
    if (endLineEnd === -1) endLineEnd = v.length;
    var block = v.substring(startLineStart, endLineEnd);
    var lines = block.split('\n');
    var allCommented = lines.every(function(l) {
        return l.trim() === '' || l.trimStart().charAt(0) === '#';
    });
    var newLines;
    if (allCommented) {
        newLines = lines.map(function(l) {
            if (l.trim() === '') return l;
            var lead = l.length - l.trimStart().length;
            return l.slice(0, lead) + l.slice(lead).replace(/^#\s?/, '');
        });
    } else {
        var minIndent = Infinity;
        lines.forEach(function(l) {
            if (l.trim() !== '') {
                var ind = l.length - l.trimStart().length;
                if (ind < minIndent) minIndent = ind;
            }
        });
        if (minIndent === Infinity) minIndent = 0;
        newLines = lines.map(function(l) {
            if (l.trim() === '') return l;
            return l.slice(0, minIndent) + '# ' + l.slice(minIndent);
        });
    }
    var newBlock = newLines.join('\n');
    ta.value = v.substring(0, startLineStart) + newBlock + v.substring(endLineEnd);
    ta.selectionStart = startLineStart;
    ta.selectionEnd = startLineStart + newBlock.length;
    ta.dispatchEvent(new Event('input'));
}

function duplicateSelection(ta) {
    var v = ta.value, s = ta.selectionStart, e = ta.selectionEnd;
    if (s !== e) {
        var sel = v.substring(s, e);
        ta.value = v.substring(0, e) + sel + v.substring(e);
        ta.selectionStart = e;
        ta.selectionEnd = e + sel.length;
    } else {
        var ls = v.lastIndexOf('\n', s - 1) + 1;
        var le = v.indexOf('\n', s);
        if (le === -1) le = v.length;
        var lineText = v.substring(ls, le);
        ta.value = v.substring(0, le) + '\n' + lineText + v.substring(le);
        ta.selectionStart = ta.selectionEnd = s + lineText.length + 1;
    }
    ta.dispatchEvent(new Event('input'));
}

function deleteLine(ta) {
    var v = ta.value, s = ta.selectionStart;
    var ls = v.lastIndexOf('\n', s - 1) + 1;
    var le = v.indexOf('\n', s);
    var removeStart = ls;
    var removeEnd = (le === -1) ? v.length : le + 1;
    if (le === -1 && ls > 0) removeStart = ls - 1;
    ta.value = v.substring(0, removeStart) + v.substring(removeEnd);
    ta.selectionStart = ta.selectionEnd = Math.min(removeStart, ta.value.length);
    ta.dispatchEvent(new Event('input'));
}

function moveLine(ta, dir) {
    var v = ta.value, s = ta.selectionStart, e = ta.selectionEnd;
    var lines = v.split('\n');
    function lineAt(off) {
        var idx = 0, acc = 0;
        for (; idx < lines.length; idx++) {
            if (acc + lines[idx].length >= off) break;
            acc += lines[idx].length + 1;
        }
        return { idx: Math.min(idx, lines.length - 1), acc: acc };
    }
    var startInfo = lineAt(s);
    var startL = startInfo.idx;
    var endInfo = lineAt(e);
    var endL = endInfo.idx;
    // selection ending exactly at a line start does not include that line
    if (e > s && e === endInfo.acc && endL > startL) endL -= 1;

    if (dir < 0 ? startL - 1 < 0 : endL + 1 >= lines.length) return;

    var block = lines.splice(startL, endL - startL + 1);
    var insertAt = (dir < 0) ? startL - 1 : startL + 1;
    Array.prototype.splice.apply(lines, [insertAt, 0].concat(block));
    ta.value = lines.join('\n');

    var off = 0;
    for (var i = 0; i < insertAt; i++) off += lines[i].length + 1;
    if (s === e) {
        var colInLine = s - startInfo.acc;
        ta.selectionStart = ta.selectionEnd = off + Math.min(colInLine, lines[insertAt].length);
    } else {
        var blockLen = 0;
        for (var j = 0; j < block.length; j++) {
            blockLen += lines[insertAt + j].length;
            if (j < block.length - 1) blockLen += 1;
        }
        ta.selectionStart = off;
        ta.selectionEnd = off + blockLen;
    }
    ta.dispatchEvent(new Event('input'));
}

// Forward selected OpenIDE-global shortcuts to the IDE action system.
function forwardIdeShortcut(e) {
    var meta = e.metaKey || e.ctrlKey;
    var key = e.key.toLowerCase();
    if (meta && e.shiftKey && key === 'f') return 'FindInPath';
    if (meta && e.shiftKey && key === 'a') return 'GotoAction';
    if (meta && e.shiftKey && key === 'o') return 'GotoFile';
    // Cmd only (not Ctrl) so Ctrl+E stays "move caret to end of line" while editing.
    if (e.metaKey && !e.shiftKey && key === 'e') return 'RecentFiles';
    return null;
}

// ── In-notebook Find / Replace ──
//
// Cmd/Ctrl+F opens a find bar; Cmd/Ctrl+R opens it with a replace row. Matches
// are highlighted with the CSS Custom Highlight API (CSS.highlights / Highlight /
// Range) so highlighting never touches the DOM — it can't be wiped by the
// syntax-highlight innerHTML regeneration, the transparent textarea overlay, or
// the diagnostic/cmd-link spans. Search covers the visible text of code cells
// (source), markdown (rendered) and outputs; Replace targets editable code-cell
// source only (outputs and rendered markdown are derived/read-only).

function ensureFindBar() {
    var bar = document.getElementById('jp-find-bar');
    if (bar) return bar;

    bar = document.createElement('div');
    bar.id = 'jp-find-bar';
    bar.innerHTML =
        '<div class="jp-find-row">' +
            '<input id="jp-find-input" class="jp-find-field" type="text" ' +
                'placeholder="Find" spellcheck="false" autocomplete="off">' +
            '<span id="jp-find-count" class="jp-find-count"></span>' +
            '<button id="jp-find-prev" class="jp-find-btn" title="Previous (Shift+Enter)">&#8593;</button>' +
            '<button id="jp-find-next" class="jp-find-btn" title="Next (Enter)">&#8595;</button>' +
            '<button id="jp-find-close" class="jp-find-btn" title="Close (Esc)">&times;</button>' +
        '</div>' +
        '<div id="jp-replace-row" class="jp-find-row">' +
            '<input id="jp-replace-input" class="jp-find-field" type="text" ' +
                'placeholder="Replace" spellcheck="false" autocomplete="off">' +
            '<button id="jp-replace-one" class="jp-find-btn jp-find-text-btn" title="Replace current match">Replace</button>' +
            '<button id="jp-replace-all" class="jp-find-btn jp-find-text-btn" title="Replace all in code cells">All</button>' +
        '</div>';
    document.body.appendChild(bar);

    var findInput = bar.querySelector('#jp-find-input');
    var replaceInput = bar.querySelector('#jp-replace-input');

    // Clicks in the bar must not reach the document mousedown handlers (which would
    // commit/exit a markdown cell or close cell dropdowns as an "outside" click).
    bar.addEventListener('mousedown', function(e) { e.stopPropagation(); });

    findInput.addEventListener('input', function() {
        searchState.query = findInput.value;
        runQuery();
    });

    findInput.addEventListener('keydown', function(e) { findBarKeydown(e, false); });
    replaceInput.addEventListener('keydown', function(e) { findBarKeydown(e, true); });

    bar.querySelector('#jp-find-prev').onclick = function() { goToMatch(-1); findInput.focus(); };
    bar.querySelector('#jp-find-next').onclick = function() { goToMatch(1); findInput.focus(); };
    bar.querySelector('#jp-find-close').onclick = function() { closeFind(); };
    bar.querySelector('#jp-replace-one').onclick = function() { doReplaceCurrent(); };
    bar.querySelector('#jp-replace-all').onclick = function() { doReplaceAll(); };

    return bar;
}

// Keystrokes inside the find/replace inputs. We isolate the bar from the global
// keydown handler (so double-Shift / Shift+Enter run-cell don't fire while typing)
// but still honour the few IDE shortcuts a user expects everywhere: save and the
// global navigation actions.
function findBarKeydown(e, isReplace) {
    if (e.key === 'Enter') {
        e.preventDefault();
        e.stopPropagation();
        if (isReplace) {
            if (e.metaKey || e.ctrlKey) doReplaceAll(); else doReplaceCurrent();
        } else {
            goToMatch(e.shiftKey ? -1 : 1);
        }
        return;
    }
    if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        closeFind();
        return;
    }
    if ((e.metaKey || e.ctrlKey) && !e.shiftKey && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        e.stopPropagation();
        if (kotlinBridge) kotlinBridge.saveNotebook();
        return;
    }
    var ideAction = forwardIdeShortcut(e);
    if (ideAction) {
        e.preventDefault();
        e.stopPropagation();
        if (kotlinBridge) kotlinBridge.runIdeAction(ideAction);
        return;
    }
    // Everything else is normal typing: keep it inside the bar so the global
    // handler never sees it, but let the input receive the character.
    e.stopPropagation();
}

function openFind(replaceMode) {
    var bar = ensureFindBar();
    var wasActive = searchState.active;
    searchState.replaceMode = !!replaceMode;
    searchState.active = true;
    bar.classList.add('visible');
    document.getElementById('jp-replace-row').style.display = replaceMode ? 'flex' : 'none';

    var findInput = document.getElementById('jp-find-input');
    if (!wasActive) {
        searchState.returnFocus = document.activeElement;
        // Seed the query from a single-line selection, like the IDE's Find.
        var sel = window.getSelection ? window.getSelection().toString() : '';
        if (sel && sel.indexOf('\n') === -1 && sel.length <= 200) {
            findInput.value = sel;
            searchState.query = sel;
        }
    }
    findInput.focus();
    findInput.select();
    runQuery();
}

function closeFind() {
    if (searchState.refreshTimer) { clearTimeout(searchState.refreshTimer); searchState.refreshTimer = null; }
    clearSearchHighlights();
    searchState.active = false;
    searchState.matches = [];
    searchState.index = -1;
    var bar = document.getElementById('jp-find-bar');
    if (bar) bar.classList.remove('visible');
    var rf = searchState.returnFocus;
    searchState.returnFocus = null;
    if (rf && rf !== document.body && document.contains(rf) && typeof rf.focus === 'function') {
        try { rf.focus(); } catch (e) {}
    }
}

function clearSearchHighlights() {
    if (typeof CSS === 'undefined' || !CSS.highlights) return;
    try {
        CSS.highlights.delete('jp-find');
        CSS.highlights.delete('jp-find-current');
    } catch (e) {}
}

// Visible, searchable roots in document (reading) order, each tagged with how it
// maps back to a cell. Order matters so match navigation follows the notebook.
function collectSearchRoots() {
    var roots = [];
    var cells = document.querySelectorAll('#notebook-container .cell');
    for (var i = 0; i < cells.length; i++) {
        var cell = cells[i];
        var cellId = cell.dataset.cellId;
        if (cell.dataset.cellType === 'code') {
            var bd = cell.querySelector('.source-backdrop');
            if (bd && bd.offsetParent !== null) roots.push({ el: bd, cellId: cellId, kind: 'code' });
        } else {
            var md = cell.querySelector('.markdown-rendered');
            if (md && md.offsetParent !== null) roots.push({ el: md, cellId: cellId, kind: 'markdown' });
        }
        var out = cell.querySelector('.cell-output');
        if (out && out.offsetParent !== null && out.textContent.length) {
            roots.push({ el: out, cellId: cellId, kind: 'output' });
        }
    }
    return roots;
}

function escapeRegExp(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Case-insensitive regex matching the literal query. Returns null for an empty
// query or if construction fails. Matching runs on the ORIGINAL text (not a
// lowercased copy): toLowerCase() can change string length (e.g. ß→ss, İ→i̇),
// which would desync match offsets from the live text nodes.
function buildQueryRegex(query, flags) {
    if (!query) return null;
    try { return new RegExp(escapeRegExp(query), flags); } catch (e) { return null; }
}

// Build Range objects for every (non-overlapping, case-insensitive) occurrence of
// `re` inside one root. A match may span several text nodes (token spans), which
// the Range and Highlight API handle natively.
function buildRangesForRoot(root, re) {
    var walker = document.createTreeWalker(root.el, NodeFilter.SHOW_TEXT, null);
    var segs = [];
    var full = '';
    var node;
    while ((node = walker.nextNode())) {
        var text = node.nodeValue || '';
        if (!text.length) continue;
        segs.push({ node: node, start: full.length, end: full.length + text.length });
        full += text;
    }
    if (!segs.length) return [];

    function locate(offset) {
        var lo = 0, hi = segs.length - 1;
        while (lo < hi) {
            var mid = (lo + hi) >> 1;
            if (segs[mid].end <= offset) lo = mid + 1; else hi = mid;
        }
        return { node: segs[lo].node, offset: offset - segs[lo].start };
    }

    var out = [];
    re.lastIndex = 0;
    var m;
    while ((m = re.exec(full)) !== null) {
        var len = m[0].length;
        if (len === 0) { re.lastIndex++; continue; } // guard (cannot happen for non-empty literal)
        var pos = m.index;
        var s = locate(pos);
        var e = locate(pos + len);
        var range = document.createRange();
        try {
            range.setStart(s.node, s.offset);
            range.setEnd(e.node, e.offset);
        } catch (err) {
            re.lastIndex = pos + len;
            continue;
        }
        out.push({ range: range, cellId: root.cellId, kind: root.kind, start: pos, length: len });
        re.lastIndex = pos + len; // non-overlapping
    }
    return out;
}

function collectMatches(query) {
    var re = buildQueryRegex(query, 'gi');
    if (!re) return [];
    var roots = collectSearchRoots();
    var matches = [];
    for (var i = 0; i < roots.length; i++) {
        var rm = buildRangesForRoot(roots[i], re);
        for (var j = 0; j < rm.length; j++) matches.push(rm[j]);
    }
    return matches;
}

function isRangeLive(range) {
    var n = range && range.startContainer;
    return !!(n && n.isConnected !== false);
}

function applySearchHighlights() {
    if (typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight === 'undefined') return;
    if (!searchState.matches.length) { clearSearchHighlights(); return; }
    try {
        var allH = new Highlight();
        var added = 0;
        for (var k = 0; k < searchState.matches.length; k++) {
            var r = searchState.matches[k].range;
            if (isRangeLive(r)) { allH.add(r); added++; }
        }
        if (added) CSS.highlights.set('jp-find', allH); else CSS.highlights.delete('jp-find');
        var cm = (searchState.index >= 0 && searchState.index < searchState.matches.length)
            ? searchState.matches[searchState.index].range : null;
        if (cm && isRangeLive(cm)) {
            var cur = new Highlight();
            cur.add(cm);
            cur.priority = 1; // win over the all-matches highlight where they overlap
            CSS.highlights.set('jp-find-current', cur);
        } else {
            CSS.highlights.delete('jp-find-current');
        }
    } catch (e) {}
}

function updateFindCount() {
    var el = document.getElementById('jp-find-count');
    if (!el) return;
    var n = searchState.matches.length;
    if (!searchState.query) { el.textContent = ''; el.classList.remove('jp-find-none'); return; }
    el.textContent = n ? (searchState.index + 1) + '/' + n : 'No results';
    el.classList.toggle('jp-find-none', n === 0);
}

// Recompute matches for the current query and re-apply highlights. `opts.reset`
// jumps the cursor to the first match (fresh query); otherwise the previous index
// is clamped (after edits/re-renders). `opts.scroll` reveals the current match.
function applyMatches(opts) {
    var prevIndex = searchState.index;
    searchState.matches = collectMatches(searchState.query);
    var n = searchState.matches.length;
    if (n === 0) {
        searchState.index = -1;
    } else if (opts.reset || prevIndex < 0) {
        searchState.index = 0;
    } else {
        searchState.index = Math.min(prevIndex, n - 1);
    }
    applySearchHighlights();
    updateFindCount();
    if (opts.scroll && n > 0) scrollToCurrentMatch();
}

// A fresh query (typing / opening the bar): reset to the first match and scroll.
function runQuery() {
    applyMatches({ reset: true, scroll: true });
}

function scheduleSearchRefresh() {
    if (searchState.refreshTimer) clearTimeout(searchState.refreshTimer);
    searchState.refreshTimer = setTimeout(function() {
        searchState.refreshTimer = null;
        // Re-renders that preserve text don't move the cursor and must not scroll.
        if (searchState.active && searchState.query) applyMatches({ reset: false, scroll: false });
    }, 80);
}

// Run a pending debounced refresh now, so navigation/replace always operate on
// ranges rebuilt from the current DOM rather than detached ones.
function flushSearchRefresh() {
    if (searchState.refreshTimer) {
        clearTimeout(searchState.refreshTimer);
        searchState.refreshTimer = null;
        if (searchState.active && searchState.query) applyMatches({ reset: false, scroll: false });
    }
}

function goToMatch(dir) {
    flushSearchRefresh();
    var n = searchState.matches.length;
    if (!n) return;
    searchState.index = (searchState.index + dir + n) % n;
    applySearchHighlights();
    updateFindCount();
    scrollToCurrentMatch();
}

function scrollToCurrentMatch() {
    if (searchState.index < 0 || searchState.index >= searchState.matches.length) return;
    var range = searchState.matches[searchState.index].range;
    if (!isRangeLive(range)) return;
    var rect;
    try { rect = range.getBoundingClientRect(); } catch (e) { return; }
    if (!rect || (rect.width === 0 && rect.height === 0)) return;
    // Keep the match clear of the fixed find bar at the top of the viewport.
    var bar = document.getElementById('jp-find-bar');
    var topMargin = (bar && bar.classList.contains('visible')) ? bar.offsetHeight + 24 : 24;
    if (rect.top < topMargin || rect.bottom > window.innerHeight - 24) {
        window.scrollBy({ top: rect.top - window.innerHeight * 0.4, behavior: 'smooth' });
    }
}

function replaceInputValue() {
    var el = document.getElementById('jp-replace-input');
    return el ? el.value : '';
}

function doReplaceCurrent() {
    flushSearchRefresh(); // operate on ranges/offsets rebuilt from the current DOM
    if (!searchState.matches.length) return;
    var m = searchState.matches[searchState.index];
    // Outputs and rendered markdown are derived/read-only; skip to the next match.
    if (m.kind !== 'code') { goToMatch(1); return; }
    var src = getCellText(m.cellId);
    // Verify the offset still maps to the query before mutating the source; if the
    // cell changed out from under us, recompute instead of corrupting the text.
    var anchored = new RegExp('^(?:' + escapeRegExp(searchState.query) + ')$', 'i');
    if (m.start + m.length > src.length || !anchored.test(src.substr(m.start, m.length))) {
        applyMatches({ reset: false, scroll: true });
        return;
    }
    var newSrc = src.slice(0, m.start) + replaceInputValue() + src.slice(m.start + m.length);
    applyCellSourceUpdate(m.cellId, newSrc);
    // The cell re-rendered; recompute and keep the cursor on the following match.
    applyMatches({ reset: false, scroll: true });
}

function doReplaceAll() {
    if (!searchState.query) return;
    var replacement = replaceInputValue();
    var total = 0;
    var cells = document.querySelectorAll('#notebook-container .cell');
    for (var i = 0; i < cells.length; i++) {
        if (cells[i].dataset.cellType !== 'code') continue;
        var cellId = cells[i].dataset.cellId;
        var src = getCellText(cellId);
        var res = replaceAllCI(src, searchState.query, replacement);
        if (res.count > 0) {
            total += res.count;
            applyCellSourceUpdate(cellId, res.text);
        }
    }
    applyMatches({ reset: false, scroll: false });
    var el = document.getElementById('jp-find-count');
    if (el && total > 0) { el.textContent = 'Replaced ' + total; el.classList.remove('jp-find-none'); }
}

// Case-insensitive replace-all over the original (un-lowercased) source. String
// replace with a function replacer treats `replacement` literally (no $-patterns)
// and never rescans inserted text, so replacing "a" with "aa" terminates.
function replaceAllCI(src, query, replacement) {
    var re = buildQueryRegex(query, 'gi');
    if (!re) return { text: src, count: 0 };
    var count = 0;
    var out = src.replace(re, function() { count++; return replacement; });
    return { text: out, count: count };
}

function applyCellSourceUpdate(cellId, newSrc) {
    var cell = document.getElementById('cell-' + cellId);
    if (!cell) return;
    var ta = cell.querySelector('.source-input');
    if (ta) ta.value = newSrc;
    if (diagnosticsByCell[cellId]) delete diagnosticsByCell[cellId];
    if (kotlinBridge) kotlinBridge.cellSourceChanged(cellId, newSrc);
    highlightBackdrop(cellId);
    syncTextareaHeight(cellId);
}

// Global keyboard handler
document.addEventListener('keydown', function(e) {
    if (e.repeat) return; // ignore key-autorepeat so held keys fire once

    // Cmd/Ctrl+F → find; Cmd/Ctrl+R → find & replace. Always preventDefault:
    // CEF's default Cmd+R would reload the loadHTML page and blank the notebook.
    var findMod = (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey;
    if (findMod && (e.key === 'f' || e.key === 'F')) {
        e.preventDefault();
        openFind(false);
        return;
    }
    if (findMod && (e.key === 'r' || e.key === 'R')) {
        e.preventDefault();
        openFind(true);
        return;
    }

    // Double-Shift → Search Everywhere
    if (e.key === 'Shift' && !e.metaKey && !e.ctrlKey && !e.altKey) {
        var now = Date.now();
        if (now - lastShiftAt < 350) {
            lastShiftAt = 0;
            if (kotlinBridge) kotlinBridge.runIdeAction('SearchEverywhere');
        } else {
            lastShiftAt = now;
        }
        return;
    }
    if (e.key !== 'Shift') lastShiftAt = 0;

    var ideAction = forwardIdeShortcut(e);
    if (ideAction) {
        e.preventDefault();
        if (kotlinBridge) kotlinBridge.runIdeAction(ideAction);
        return;
    }

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
            // Advance only once the cell finishes successfully (onCellExecuted).
            pendingAdvanceCellId = cellId;
            kotlinBridge.runCell(cellId);
        } else {
            // Markdown has no kernel execution; advance immediately.
            moveToNextCell(cellId);
        }
    }
});
