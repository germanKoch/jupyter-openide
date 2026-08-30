#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../..');
const notebookHtml = path.join(repoRoot, 'src/main/resources/notebook/notebook.html');

function findChrome() {
    const candidates = [
        process.env.CHROME_BIN,
        '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        '/Applications/Chromium.app/Contents/MacOS/Chromium',
        '/usr/bin/google-chrome',
        '/usr/bin/google-chrome-stable',
        '/usr/bin/chromium',
        '/usr/bin/chromium-browser'
    ].filter(Boolean);
    const chrome = candidates.find(existsSync);
    if (!chrome) {
        throw new Error('Chrome/Chromium not found. Set CHROME_BIN to run the notebook UI test.');
    }
    return chrome;
}

function waitForDevtools(child) {
    return new Promise((resolve, reject) => {
        var stderr = '';
        var timer = setTimeout(function() {
            reject(new Error('Timed out waiting for Chrome DevTools. Output:\n' + stderr));
        }, 15000);
        child.stderr.on('data', function(chunk) {
            stderr += chunk.toString();
            var match = stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);
            if (match) {
                clearTimeout(timer);
                resolve(match[1]);
            }
        });
        child.once('exit', function(code) {
            clearTimeout(timer);
            reject(new Error('Chrome exited before DevTools was ready (code ' + code + ').\n' + stderr));
        });
    });
}

async function findPageTarget(browserWsUrl) {
    const devtoolsUrl = new URL(browserWsUrl);
    const listUrl = 'http://' + devtoolsUrl.host + '/json/list';
    for (var i = 0; i < 100; i++) {
        try {
            const targets = await fetch(listUrl).then(function(response) { return response.json(); });
            const page = targets.find(function(target) {
                return target.type === 'page' && target.url.startsWith('file:');
            });
            if (page) return page;
        } catch (e) {
            // Chrome may expose the port a moment before the first page target.
        }
        await new Promise(function(resolve) { setTimeout(resolve, 50); });
    }
    throw new Error('Notebook page target did not appear in Chrome DevTools.');
}

class CdpClient {
    constructor(url) {
        this.url = url;
        this.ws = null;
        this.nextId = 0;
        this.pending = new Map();
    }

    async connect() {
        this.ws = new WebSocket(this.url);
        await new Promise((resolve, reject) => {
            this.ws.onopen = resolve;
            this.ws.onerror = reject;
        });
        this.ws.onmessage = event => {
            const message = JSON.parse(event.data);
            if (!message.id || !this.pending.has(message.id)) return;
            const waiter = this.pending.get(message.id);
            this.pending.delete(message.id);
            if (message.error) waiter.reject(new Error(JSON.stringify(message.error)));
            else waiter.resolve(message.result);
        };
    }

    call(method, params = {}) {
        return new Promise((resolve, reject) => {
            const id = ++this.nextId;
            this.pending.set(id, { resolve, reject });
            this.ws.send(JSON.stringify({ id, method, params }));
        });
    }

    async evaluate(expression) {
        const result = await this.call('Runtime.evaluate', {
            expression,
            returnByValue: true,
            awaitPromise: true
        });
        if (result.exceptionDetails) {
            const detail = result.exceptionDetails.exception?.description ||
                result.exceptionDetails.text || 'JavaScript evaluation failed';
            throw new Error(detail);
        }
        return result.result.value;
    }

    close() {
        if (this.ws) this.ws.close();
    }
}

async function mouseClick(cdp, x, y, clickCount) {
    await cdp.call('Input.dispatchMouseEvent', {
        type: 'mousePressed', x, y, button: 'left', clickCount
    });
    await cdp.call('Input.dispatchMouseEvent', {
        type: 'mouseReleased', x, y, button: 'left', clickCount
    });
}

async function key(cdp, keyName, options = {}) {
    const code = options.code || keyName;
    const vk = options.windowsVirtualKeyCode || (keyName === 'Enter' ? 13 : keyName === 'Escape' ? 27 : 0);
    await cdp.call('Input.dispatchKeyEvent', {
        type: 'keyDown',
        key: keyName,
        code,
        text: options.text || '',
        unmodifiedText: options.text || '',
        modifiers: options.modifiers || 0,
        windowsVirtualKeyCode: vk,
        nativeVirtualKeyCode: vk
    });
    await cdp.call('Input.dispatchKeyEvent', {
        type: 'keyUp',
        key: keyName,
        code,
        modifiers: options.modifiers || 0,
        windowsVirtualKeyCode: vk,
        nativeVirtualKeyCode: vk
    });
}

async function runTests(cdp) {
    await cdp.call('Emulation.setDeviceMetricsOverride', {
        width: 420,
        height: 760,
        deviceScaleFactor: 1,
        mobile: false
    });
    await cdp.evaluate(`
        window.auditEvents = { selected: [], changed: [], deleted: [], added: [], ran: [], definitions: [] };
        initBridge({
            cellSelected: id => auditEvents.selected.push(id),
            cellSourceChanged: (id, source) => auditEvents.changed.push([id, source]),
            deleteCell: id => auditEvents.deleted.push(id),
            addCell: (id, type) => auditEvents.added.push([id, type]),
            runCell: id => auditEvents.ran.push(id),
            saveNotebook: () => {},
            runIdeAction: () => {},
            goToDefinition: data => auditEvents.definitions.push(JSON.parse(data))
        });
    `);

    // Single click is command-mode selection. Double-click opens the overlay and
    // maps the clicked highlighted text point to the textarea selection offset.
    await cdp.evaluate(`
        clearNotebook();
        addCell('caret', 'code',
            'first = 1\\nsecond = 2\\nthird = 3\\nfourth = 4', '', null);
        renderNotebookComplete();
    `);
    const point = await cdp.evaluate(`(() => {
        const backdrop = document.querySelector('#cell-caret .source-backdrop');
        const rect = backdrop.getBoundingClientRect();
        const x = rect.left + 54;
        const y = rect.top + 8 + 20.8 * 2 + 10;
        return { x, y, expected: textOffsetAtPoint(backdrop, { clientX: x, clientY: y }) };
    })()`);
    await mouseClick(cdp, point.x, point.y, 1);
    const selectedOnly = await cdp.evaluate(`(() => {
        const wrapper = document.querySelector('#cell-caret .cell-source');
        const textarea = wrapper.querySelector('.source-input');
        return {
            selected: getSelectedCellId(),
            editing: wrapper.classList.contains('editing'),
            active: document.activeElement === textarea,
            display: getComputedStyle(textarea).display,
            cursor: getComputedStyle(wrapper.querySelector('.source-backdrop')).cursor,
            caretColor: getComputedStyle(textarea).caretColor
        };
    })()`);
    assert.equal(selectedOnly.selected, 'caret');
    assert.equal(selectedOnly.editing, false);
    assert.equal(selectedOnly.active, false);
    assert.equal(selectedOnly.display, 'none');
    assert.equal(selectedOnly.cursor, 'default');
    assert.equal(selectedOnly.caretColor, 'rgba(0, 0, 0, 0)');

    await mouseClick(cdp, point.x, point.y, 2);
    const editing = await cdp.evaluate(`(() => {
        const wrapper = document.querySelector('#cell-caret .cell-source');
        const textarea = wrapper.querySelector('.source-input');
        return {
            editing: wrapper.classList.contains('editing'),
            active: document.activeElement === textarea,
            selectionStart: textarea.selectionStart,
            cursor: getComputedStyle(textarea).cursor,
            scrollTop: textarea.scrollTop
        };
    })()`);
    assert.equal(editing.editing, true);
    assert.equal(editing.active, true);
    assert.equal(editing.selectionStart, point.expected);
    assert.equal(editing.cursor, 'text');
    assert.equal(editing.scrollTop, 0);

    // Empty sources and trailing newlines must allocate the same final line in
    // both layers, otherwise Chromium scrolls only the caret-bearing textarea.
    for (const [id, source, offset] of [
        ['empty', '', 0],
        ['trailing', 'alpha\nbeta\n', 11],
        ['double-trailing', 'alpha\n\n', 7]
    ]) {
        const geometry = await cdp.evaluate(`(() => {
            clearNotebook();
            addCell(${JSON.stringify(id)}, 'code', ${JSON.stringify(source)}, '', null);
            enterEditMode(${JSON.stringify(id)}, ${offset});
            const cell = document.getElementById('cell-' + ${JSON.stringify(id)});
            const backdrop = cell.querySelector('.source-backdrop');
            const textarea = cell.querySelector('.source-input');
            return {
                backdropHeight: backdrop.scrollHeight,
                textareaHeight: textarea.scrollHeight,
                clientHeight: textarea.clientHeight,
                scrollTop: textarea.scrollTop
            };
        })()`);
        assert.equal(geometry.backdropHeight, geometry.textareaHeight, id + ' backdrop height');
        assert.equal(geometry.textareaHeight, geometry.clientHeight, id + ' textarea height');
        assert.equal(geometry.scrollTop, 0, id + ' scrollTop');
    }

    // Rendered Markdown follows the same command/edit-mode split. Formatting
    // markers are skipped when translating the clicked rendered word back to
    // its raw source offset.
    await cdp.evaluate(`
        clearNotebook();
        addCell('md-click', 'markdown', '# Heading\\n\\n**Gamma** delta', '', null);
        setMarkdownSource('md-click', '# Heading\\n\\n**Gamma** delta');
    `);
    const markdownPoint = await cdp.evaluate(`(() => {
        const rendered = document.getElementById('md-rendered-md-click');
        const strong = rendered.querySelector('strong');
        const rect = strong.getBoundingClientRect();
        const x = rect.left + Math.min(8, rect.width / 2);
        const y = rect.top + rect.height / 2;
        const local = textOffsetAtPoint(strong, { clientX: x, clientY: y });
        return { x, y, expected: '# Heading\\n\\n**Gamma** delta'.indexOf('Gamma') + local };
    })()`);
    await mouseClick(cdp, markdownPoint.x, markdownPoint.y, 1);
    assert.equal(await cdp.evaluate(`document.getElementById('cell-md-click').classList.contains('editing-markdown')`),
        false);
    await mouseClick(cdp, markdownPoint.x, markdownPoint.y, 2);
    const markdownEditing = await cdp.evaluate(`(() => {
        const source = document.getElementById('md-source-md-click');
        return {
            editing: document.getElementById('cell-md-click').classList.contains('editing-markdown'),
            active: document.activeElement === source,
            selectionStart: source.selectionStart
        };
    })()`);
    assert.equal(markdownEditing.editing, true);
    assert.equal(markdownEditing.active, true);
    assert.equal(markdownEditing.selectionStart, markdownPoint.expected);

    // Markdown uses a real textarea, preserving newlines, and every exit route
    // refreshes the rendered pane before returning to command mode.
    await cdp.evaluate(`
        clearNotebook();
        auditEvents.changed = [];
        addCell('md', 'markdown', 'first', '', null);
        setMarkdownSource('md', 'first');
        startEditMarkdown('md');
        var mdSource = document.getElementById('md-source-md');
        mdSource.setSelectionRange(mdSource.value.length, mdSource.value.length);
    `);
    await key(cdp, 'Enter', { code: 'Enter', text: '\r', windowsVirtualKeyCode: 13 });
    await cdp.call('Input.insertText', { text: 'second' });
    assert.equal(await cdp.evaluate(`document.getElementById('md-source-md').value`), 'first\nsecond');
    await key(cdp, 'Escape', { code: 'Escape', windowsVirtualKeyCode: 27 });
    const escapedMarkdown = await cdp.evaluate(`(() => {
        const cell = document.getElementById('cell-md');
        const rendered = document.getElementById('md-rendered-md');
        return {
            editing: cell.classList.contains('editing-markdown'),
            html: rendered.innerHTML,
            lastChange: auditEvents.changed[auditEvents.changed.length - 1]
        };
    })()`);
    assert.equal(escapedMarkdown.editing, false);
    assert.equal(escapedMarkdown.html, '<p>first<br>second</p>');
    assert.deepEqual(escapedMarkdown.lastChange, ['md', 'first\nsecond']);

    await cdp.evaluate(`
        startEditMarkdown('md');
        var mdSource = document.getElementById('md-source-md');
        mdSource.value = '# Click outside';
        mdSource.dispatchEvent(new Event('input', { bubbles: true }));
        document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    `);
    assert.equal(await cdp.evaluate(`document.getElementById('md-rendered-md').innerHTML`),
        '<h1>Click outside</h1>');

    await cdp.evaluate(`
        startEditMarkdown('md');
        var mdSource = document.getElementById('md-source-md');
        mdSource.value = '**Shift+Enter**';
        mdSource.dispatchEvent(new Event('input', { bubbles: true }));
    `);
    await key(cdp, 'Enter', { code: 'Enter', modifiers: 8, windowsVirtualKeyCode: 13 });
    assert.equal(await cdp.evaluate(`document.getElementById('md-rendered-md').innerHTML`),
        '<p><strong>Shift+Enter</strong></p>');
    assert.equal(await cdp.evaluate(`document.getElementById('cell-md').classList.contains('editing-markdown')`),
        false);

    // Initial display and the display produced by a no-op edit share one
    // renderer. A fenced language tag must not appear as code text or vanish
    // merely because the user entered edit mode and pressed Escape.
    const stableMarkdown = await cdp.evaluate(`(() => {
        clearNotebook();
        const fence = String.fromCharCode(96).repeat(3);
        const source = '# Stable\\n\\n' + fence + 'python\\nprint("same")\\n' + fence;
        addCell('md-stable', 'markdown', source, '', null);
        const rendered = document.getElementById('md-rendered-md-stable');
        const before = rendered.innerHTML;
        startEditMarkdown('md-stable');
        finishMarkdownEdit('md-stable');
        return { before, after: rendered.innerHTML, text: rendered.textContent };
    })()`);
    assert.equal(stableMarkdown.before, stableMarkdown.after);
    assert.equal(stableMarkdown.before, '<h1>Stable</h1><pre><code>print("same")</code></pre>');
    assert.equal(stableMarkdown.text.includes('python'), false);

    // Jupyter-flavoured Markdown structures are rendered in the live browser,
    // then pass through the same sanitizer used for notebook and kernel HTML.
    const richMarkdown = await cdp.evaluate(`(() => {
        clearNotebook();
        addCell('md-list', 'markdown', '3. third\\n4. fourth', '', null);
        addCell('md-table', 'markdown',
            '| a\\\\|x | b |\\n| :--- | ---: |\\n| one | **two** |', '', null);
        addCell('md-commonmark', 'markdown',
            'Top <unsafe>\\n=====\\n\\nSecond & safe\\n---\\n\\n* * *\\n\\n' +
            '__<b>bold</b>__ and _italic_ and word_part_here and \\\\_literal\\\\_', '', null);
        addCell('md-setext-title', 'markdown', 'Title\\n---', '', null);
        addCell('md-setext-atx', 'markdown', '# Heading\\n---', '', null);
        addCell('md-setext-list', 'markdown', '- item\\n---', '', null);
        addCell('md-setext-quote', 'markdown', '> quote\\n---', '', null);

        const attachments = {
            'plot one.png': { 'image/png': ['iVBO', 'Rw0KGgo='] },
            "quote'ü.png": { 'image/png': 'AAAA' },
            'icon.svg': { 'image/svg+xml':
                '<svg xmlns="http://www.w3.org/2000/svg" onload="kotlinBridge.deleteCell(\\'svg\\')">' +
                '<script>kotlinBridge.deleteCell(\\'svg-script\\')</script>' +
                '<rect width="2" height="2" fill="#fff"/></svg>' },
            'unknown.bin': { 'application/octet-stream': 'AAAA' },
            'null.png': { 'image/png': null }
        };
        const imageSource = [
            '![plot](attachment:plot%20one.png)',
            '![quoted](attachment:quote%27%C3%BC.png)',
            '![svg](attachment:icon.svg)',
            '![unknown](attachment:unknown.bin)',
            '![null](attachment:null.png)',
            '![web](https://example.com/safe.png)',
            '![inline](data:image/png;base64,AAAA)',
            '![script](javascript:alert(1))',
            '![arbitrary](data:text/html;base64,AAAA)'
        ].join('\\n');
        addCell('md-attachments', 'markdown', imageSource, '', null, JSON.stringify(attachments));
        addCell('md-other', 'markdown', '![cross](attachment:plot%20one.png)', '', null,
            JSON.stringify({}));

        // Re-rendering after an edit must keep using this cell's own attachment bundle.
        startEditMarkdown('md-attachments');
        finishMarkdownEdit('md-attachments');

        const list = document.querySelector('#md-rendered-md-list ol');
        const table = document.querySelector('#md-rendered-md-table table');
        const commonmark = document.getElementById('md-rendered-md-commonmark');
        const headers = table ? table.querySelectorAll('th') : [];
        const bodyCells = table ? table.querySelectorAll('tbody td') : [];
        const images = Array.from(document.querySelectorAll('#md-rendered-md-attachments img'));
        const svgImage = images.find(img => img.alt === 'svg');
        const svgText = svgImage ? atob(svgImage.getAttribute('src').split(',')[1]) : '';
        return {
            orderedTag: list && list.tagName,
            orderedStart: list && list.getAttribute('start'),
            orderedItems: list ? Array.from(list.querySelectorAll('li')).map(li => li.textContent) : [],
            tablePresent: !!table,
            headerText: Array.from(headers).map(th => th.textContent),
            headerAlign: Array.from(headers).map(th => th.getAttribute('align')),
            bodyText: Array.from(bodyCells).map(td => td.textContent),
            bodyAlign: Array.from(bodyCells).map(td => td.getAttribute('align')),
            strongInTable: !!(table && table.querySelector('tbody strong')),
            setextH1: commonmark.querySelector('h1').textContent,
            setextH2: commonmark.querySelector('h2').textContent,
            horizontalRules: commonmark.querySelectorAll('hr').length,
            underscoreStrong: commonmark.querySelector('strong').textContent,
            underscoreEmphasis: commonmark.querySelector('em').textContent,
            emphasisCount: commonmark.querySelectorAll('em').length,
            unsafeBoldNodes: commonmark.querySelectorAll('strong b').length,
            commonmarkText: commonmark.textContent,
            setextTitle: document.getElementById('md-rendered-md-setext-title').innerHTML,
            setextAtx: document.getElementById('md-rendered-md-setext-atx').innerHTML,
            setextList: document.getElementById('md-rendered-md-setext-list').innerHTML,
            setextQuote: document.getElementById('md-rendered-md-setext-quote').innerHTML,
            imageAlts: images.map(img => img.alt),
            imageSources: images.map(img => img.getAttribute('src')),
            safeSvg: !/<script|onload=/i.test(svgText),
            crossCellImages: document.querySelectorAll('#md-rendered-md-other img').length,
            crossCellText: document.getElementById('md-rendered-md-other').textContent,
            deleted: auditEvents.deleted.slice()
        };
    })()`);
    assert.equal(richMarkdown.orderedTag, 'OL');
    assert.equal(richMarkdown.orderedStart, '3');
    assert.deepEqual(richMarkdown.orderedItems, ['third', 'fourth']);
    assert.equal(richMarkdown.tablePresent, true);
    assert.deepEqual(richMarkdown.headerText, ['a|x', 'b']);
    assert.deepEqual(richMarkdown.headerAlign, ['left', 'right']);
    assert.deepEqual(richMarkdown.bodyText, ['one', 'two']);
    assert.deepEqual(richMarkdown.bodyAlign, ['left', 'right']);
    assert.equal(richMarkdown.strongInTable, true);
    assert.equal(richMarkdown.setextH1, 'Top <unsafe>');
    assert.equal(richMarkdown.setextH2, 'Second & safe');
    assert.equal(richMarkdown.horizontalRules, 1);
    assert.equal(richMarkdown.underscoreStrong, '<b>bold</b>');
    assert.equal(richMarkdown.underscoreEmphasis, 'italic');
    assert.equal(richMarkdown.emphasisCount, 1);
    assert.equal(richMarkdown.unsafeBoldNodes, 0);
    assert.equal(richMarkdown.commonmarkText.includes('word_part_here'), true);
    assert.equal(richMarkdown.commonmarkText.includes('_literal_'), true);
    assert.equal(richMarkdown.setextTitle, '<h2>Title</h2>');
    assert.equal(richMarkdown.setextAtx, '<h1>Heading</h1><hr>');
    assert.equal(richMarkdown.setextList, '<ul><li>item</li></ul><hr>');
    assert.equal(richMarkdown.setextQuote, '<blockquote>quote</blockquote><hr>');
    assert.deepEqual(richMarkdown.imageAlts.sort(), ['inline', 'plot', 'quoted', 'svg', 'web']);
    assert.equal(richMarkdown.imageSources.some(src => src === 'data:image/png;base64,iVBORw0KGgo='), true);
    assert.equal(richMarkdown.imageSources.some(src => src === 'data:image/png;base64,AAAA'), true);
    assert.equal(richMarkdown.imageSources.some(src => src === 'https://example.com/safe.png'), true);
    assert.equal(richMarkdown.imageSources.some(src => src.startsWith('data:image/svg+xml;base64,')), true);
    assert.equal(richMarkdown.safeSvg, true);
    assert.equal(richMarkdown.crossCellImages, 0);
    assert.equal(richMarkdown.crossCellText, '![cross](attachment:plot%20one.png)');
    assert.deepEqual(richMarkdown.deleted, []);

    // Unmatched underscore delimiters must remain linear. The former regex
    // rescanned this 300k input quadratically and took several seconds.
    const hostileMarkdown = await cdp.evaluate(`(() => {
        const source = (' _a').repeat(100000);
        const started = performance.now();
        const output = renderMarkdownSource(source);
        return {
            elapsed: performance.now() - started,
            preserved: output.length === source.length + 7,
            accidentalEmphasis: output.indexOf('<em>') !== -1 || output.indexOf('<strong>') !== -1
        };
    })()`);
    assert.equal(hostileMarkdown.preserved, true);
    assert.equal(hostileMarkdown.accidentalEmphasis, false);
    assert.equal(hostileMarkdown.elapsed < 2000, true,
        'hostile unmatched emphasis took ' + hostileMarkdown.elapsed + 'ms');

    // A raw cell reuses the plain textarea lifecycle but never applies Markdown.
    await cdp.evaluate(`
        clearNotebook();
        addCell('raw', 'raw', '<b>literal</b>\\nline', '', null);
        setMarkdownSource('raw', '<b>literal</b>\\nline');
        startEditMarkdown('raw');
        finishMarkdownEdit('raw');
    `);
    const raw = await cdp.evaluate(`(() => {
        const rendered = document.getElementById('md-rendered-raw');
        return { html: rendered.innerHTML, text: rendered.textContent, type: rendered.closest('.cell').dataset.cellType };
    })()`);
    assert.equal(raw.type, 'raw');
    assert.equal(raw.text, '<b>literal</b>\nline');
    assert.equal(raw.html, '&lt;b&gt;literal&lt;/b&gt;\nline');

    // HTML/SVG from a file or kernel cannot run handlers, inject editor-state
    // classes/IDs, or retain executable URLs in the bridge-owning document.
    const exploit = `<div class="cell output-html" id="owned">
        <img src="invalid://x" onerror="kotlinBridge.deleteCell('victim')">
        <a href="javascript:kotlinBridge.deleteCell('victim2')">bad link</a>
        <svg onload="kotlinBridge.deleteCell('victim3')"><script>kotlinBridge.deleteCell('victim4')</script><rect width="10" height="10" fill="#fff"/></svg>
    </div>`;
    await cdp.evaluate(`
        clearNotebook();
        auditEvents.deleted = [];
        addCell('safe', 'code', 'x', '', null);
        appendOutput('safe', ${JSON.stringify(exploit)});
    `);
    await new Promise(function(resolve) { setTimeout(resolve, 100); });
    const sanitized = await cdp.evaluate(`(() => {
        const output = document.getElementById('output-safe');
        const wrapper = output.firstElementChild;
        const image = output.querySelector('img');
        const link = output.querySelector('a');
        return {
            deleted: auditEvents.deleted,
            html: output.innerHTML,
            wrapperId: wrapper && wrapper.id,
            wrapperClass: wrapper && wrapper.className,
            onerror: image && image.getAttribute('onerror'),
            href: link && link.getAttribute('href'),
            scripts: output.querySelectorAll('script').length
        };
    })()`);
    assert.deepEqual(sanitized.deleted, []);
    assert.equal(sanitized.wrapperId, '');
    assert.equal(sanitized.wrapperClass, 'output-html');
    assert.equal(sanitized.onerror, null);
    assert.equal(sanitized.href, null);
    assert.equal(sanitized.scripts, 0);

    // An in-flight cell cannot be submitted a second time from its page button;
    // the Kotlin guard remains authoritative for toolbar and bridge callers.
    const executingRunButton = await cdp.evaluate(`(() => {
        clearNotebook();
        auditEvents.ran = [];
        addCell('running', 'code', 'print("once")', '', null);
        setCellExecuting('running', true);
        const button = document.querySelector('#cell-running .run-btn');
        button.click();
        return { disabled: button.disabled, ran: auditEvents.ran.slice() };
    })()`);
    assert.equal(executingRunButton.disabled, true);
    assert.deepEqual(executingRunButton.ran, []);
    const finishedRunButton = await cdp.evaluate(`(() => {
        setCellExecuting('running', false);
        const button = document.querySelector('#cell-running .run-btn');
        button.click();
        return { disabled: button.disabled, ran: auditEvents.ran.slice() };
    })()`);
    assert.equal(finishedRunButton.disabled, false);
    assert.deepEqual(finishedRunButton.ran, ['running']);

    // Definition requests cross the bridge with a UTF-16 offset; returned
    // notebook columns are Unicode code points and are mapped back precisely.
    await cdp.evaluate(`
        clearNotebook();
        auditEvents.definitions = [];
        addCell('nav', 'code', 'x = 1\\nprint(x)', '', null);
        requestDefinitionAt('nav', 12);
    `);
    assert.deepEqual(await cdp.evaluate(`auditEvents.definitions[0]`), {
        requestId: 1,
        cellId: 'nav',
        cursorOffsetUtf16: 12
    });
    const unicodeDefinition = await cdp.evaluate(`(() => {
        clearNotebook();
        auditEvents.definitions = [];
        const source = 'def привет():\\n    return 1\\nпривет()';
        addCell('unicode-request', 'code', source, '', null);
        const offset = source.lastIndexOf('привет') + 2;
        const accepted = requestDefinitionAt('unicode-request', offset);
        const highlighted = document.querySelector('#cell-unicode-request .usage-highlight');
        return {
            accepted,
            request: auditEvents.definitions[0],
            offset,
            word: wordAtIndex(source, offset),
            highlighted: highlighted && highlighted.textContent
        };
    })()`);
    assert.equal(unicodeDefinition.accepted, true);
    assert.equal(unicodeDefinition.word, 'привет');
    assert.equal(unicodeDefinition.highlighted, 'привет');
    assert.deepEqual(unicodeDefinition.request, {
        requestId: 2,
        cellId: 'unicode-request',
        cursorOffsetUtf16: unicodeDefinition.offset
    });
    await cdp.evaluate(`
        clearNotebook();
        addCell('unicode-nav', 'code', "label = '😀'\\nprint(label)", '', null);
        navigateToCellLocation('unicode-nav', 0, 10, 'label');
    `);
    assert.equal(
        await cdp.evaluate(`document.querySelector('#cell-unicode-nav .source-input').selectionStart`),
        11
    );
}

const chrome = findChrome();
const profile = await mkdtemp(path.join(os.tmpdir(), 'jupyter-openide-ui-test-'));
const child = spawn(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--disable-background-networking',
    '--no-first-run',
    '--no-default-browser-check',
    '--allow-file-access-from-files',
    '--remote-debugging-port=0',
    '--user-data-dir=' + profile,
    pathToFileURL(notebookHtml).href
], { stdio: ['ignore', 'ignore', 'pipe'] });

let cdp;
try {
    const browserWsUrl = await waitForDevtools(child);
    const page = await findPageTarget(browserWsUrl);
    cdp = new CdpClient(page.webSocketDebuggerUrl);
    await cdp.connect();
    await new Promise(function(resolve) { setTimeout(resolve, 150); });
    await runTests(cdp);
    console.log('notebook-ui-headless: PASS');
} finally {
    if (cdp) cdp.close();
    if (child.exitCode === null) child.kill('SIGTERM');
    await rm(profile, { recursive: true, force: true });
}
