let selectedCellId = null;
let kotlinBridge = null;

function initBridge(bridge) {
    kotlinBridge = bridge;
}

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

    cell.appendChild(header);

    if (type === 'code') {
        const sourceDiv = document.createElement('div');
        sourceDiv.className = 'cell-source';
        sourceDiv.id = 'source-' + id;
        sourceDiv.textContent = source;
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
        cell.appendChild(renderedDiv);

        const sourceDiv = document.createElement('div');
        sourceDiv.className = 'markdown-source';
        sourceDiv.id = 'md-source-' + id;
        sourceDiv.contentEditable = 'true';
        cell.appendChild(sourceDiv);
    }

    container.appendChild(cell);
}

function updateCell(id, source) {
    const sourceEl = document.getElementById('source-' + id);
    if (sourceEl) {
        sourceEl.textContent = source;
    }
}

function removeCell(id) {
    const cell = document.getElementById('cell-' + id);
    if (cell) {
        cell.remove();
        if (selectedCellId === id) {
            selectedCellId = null;
        }
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

function makeEditable(id) {
    const sourceEl = document.getElementById('source-' + id);
    if (sourceEl) {
        sourceEl.contentEditable = 'true';
        sourceEl.classList.add('editable');
        sourceEl.focus();
        sourceEl.oninput = function() {
            if (kotlinBridge) {
                kotlinBridge.cellSourceChanged(id, sourceEl.textContent);
            }
        };
    }
}

function makeReadOnly(id) {
    const sourceEl = document.getElementById('source-' + id);
    if (sourceEl) {
        sourceEl.contentEditable = 'false';
        sourceEl.classList.remove('editable');
        sourceEl.oninput = null;
    }
}

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

function getSelectedCellId() {
    return selectedCellId;
}

function insertCellAfter(afterId, newId, type, source, outputsHtml, executionCount) {
    const afterCell = document.getElementById('cell-' + afterId);
    if (!afterCell) {
        addCell(newId, type, source, outputsHtml, executionCount);
        return;
    }
    const tempContainer = document.createElement('div');
    document.body.appendChild(tempContainer);
    const origContainer = document.getElementById('notebook-container');
    const origInnerHtml = origContainer.innerHTML;

    addCell(newId, type, source, outputsHtml, executionCount);
    const newCell = document.getElementById('cell-' + newId);
    if (newCell) {
        afterCell.parentNode.insertBefore(newCell, afterCell.nextSibling);
    }
    tempContainer.remove();
}
