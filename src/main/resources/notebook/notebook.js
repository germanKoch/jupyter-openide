let selectedCellId = null;
let kotlinBridge = null;
let highlightDebounceTimer = null;
let diagnosticsByCell = {};
let attachmentsByCell = Object.create(null);
let usageHighlightName = null;
let lastShiftAt = 0;
let cmdLinkName = null;
let cmdLinkCellId = null;
let cmdLinkPos = null;
let diagTipTarget = null;
let pendingAdvanceCellId = null;
let definitionRequestCounter = 0;
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

function setTheme(isDark) {
    document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
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

// Python identifiers are Unicode (apart from a few normalization details), so
// an ASCII-only `\w` check must not prevent a valid symbol from ever reaching
// the language-aware resolver. Chromium's Unicode properties closely match
// Python's ID_Start/ID_Continue rules; underscore is added explicitly because
// ECMAScript exposes it outside the Unicode ID_Start property.
const PYTHON_IDENTIFIER_START = /^[_\p{ID_Start}]$/u;
const PYTHON_IDENTIFIER_CONTINUE = /^[_\p{ID_Continue}]$/u;

function codePointAtUtf16(text, index) {
    if (index < 0 || index >= text.length) return '';
    return String.fromCodePoint(text.codePointAt(index));
}

function isPythonIdentifierStart(value) {
    return PYTHON_IDENTIFIER_START.test(value);
}

function isPythonIdentifierContinue(value) {
    return PYTHON_IDENTIFIER_CONTINUE.test(value);
}

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

        var identifierStart = codePointAtUtf16(source, i);
        if (isPythonIdentifierStart(identifierStart)) {
            var endW = i + identifierStart.length;
            while (endW < len) {
                var identifierPart = codePointAtUtf16(source, endW);
                if (!isPythonIdentifierContinue(identifierPart)) break;
                endW += identifierPart.length;
            }
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

        var firstTextPoint = codePointAtUtf16(source, i);
        var endT = i + firstTextPoint.length;
        while (endT < len) {
            var nextTextPoint = codePointAtUtf16(source, endT);
            if (isPythonIdentifierStart(nextTextPoint) || /[0-9#"'@.]/.test(nextTextPoint)) break;
            endT += nextTextPoint.length;
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

// Rich notebook output and pre-rendered Markdown originate in notebook files or
// kernel messages. They must never be assigned to a live DOM node unchecked:
// inline event handlers would otherwise run in the same page as kotlinBridge.
const SAFE_HTML_TAGS = new Set([
    'a', 'b', 'blockquote', 'br', 'caption', 'code', 'col', 'colgroup',
    'dd', 'del', 'details', 'div', 'dl', 'dt', 'em', 'h1', 'h2', 'h3',
    'h4', 'h5', 'h6', 'hr', 'i', 'img', 'ins', 'kbd', 'li', 'mark',
    'ol', 'p', 'pre', 's', 'samp', 'small', 'span', 'strong', 'sub',
    'summary', 'sup', 'table', 'tbody', 'td', 'tfoot', 'th', 'thead',
    'tr', 'u', 'ul', 'var',
    // A deliberately small, non-interactive SVG subset for saved plot output.
    'svg', 'g', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline',
    'polygon', 'text', 'tspan', 'defs', 'clippath', 'mask',
    'lineargradient', 'radialgradient', 'stop', 'title', 'desc'
]);

const DROP_HTML_TAGS = new Set([
    'script', 'style', 'iframe', 'frame', 'frameset', 'object', 'embed',
    'applet', 'base', 'link', 'meta', 'form', 'input', 'button', 'select',
    'option', 'textarea', 'video', 'audio', 'source', 'track', 'foreignobject',
    'animate', 'animatemotion', 'animatetransform', 'set', 'use'
]);

const SAFE_HTML_CLASSES = new Set([
    'output-stream', 'output-error', 'output-html', 'output-image', 'dataframe'
]);

const SAFE_SVG_ATTRS = new Set([
    'viewbox', 'preserveaspectratio', 'width', 'height', 'x', 'y', 'x1',
    'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry', 'd', 'points',
    'transform', 'fill', 'fill-opacity', 'fill-rule', 'stroke',
    'stroke-width', 'stroke-opacity', 'stroke-linecap', 'stroke-linejoin',
    'stroke-dasharray', 'stroke-dashoffset', 'opacity', 'font-family',
    'font-size', 'font-weight', 'text-anchor', 'dominant-baseline',
    'offset', 'stop-color', 'stop-opacity', 'gradientunits',
    'gradienttransform', 'spreadmethod', 'clip-path', 'mask'
]);

function isSafeLinkUrl(value) {
    var url = String(value || '').trim();
    if (!url) return false;
    if (url.charAt(0) === '#') return true;
    try {
        var parsed = new URL(url, 'https://notebook.invalid/');
        return parsed.protocol === 'http:' || parsed.protocol === 'https:' ||
            parsed.protocol === 'mailto:';
    } catch (e) {
        return false;
    }
}

function isSafeImageUrl(value) {
    var url = String(value || '').trim();
    if (/^data:image\/(?:png|jpe?g|gif|webp|svg\+xml);base64,[a-z0-9+/=\s]+$/i.test(url)) {
        return true;
    }
    try {
        var parsed = new URL(url, 'https://notebook.invalid/');
        return parsed.protocol === 'http:' || parsed.protocol === 'https:';
    } catch (e) {
        return false;
    }
}

function sanitizeHtmlFragment(html) {
    var template = document.createElement('template');
    template.innerHTML = String(html || '');

    function clean(parent) {
        var children = Array.from(parent.childNodes);
        for (var i = 0; i < children.length; i++) {
            var node = children[i];
            if (node.nodeType === Node.COMMENT_NODE) {
                node.remove();
                continue;
            }
            if (node.nodeType !== Node.ELEMENT_NODE) continue;

            var tag = (node.localName || '').toLowerCase();
            if (DROP_HTML_TAGS.has(tag)) {
                node.remove();
                continue;
            }
            if (!SAFE_HTML_TAGS.has(tag)) {
                clean(node);
                node.replaceWith.apply(node, Array.from(node.childNodes));
                continue;
            }

            var isSvg = node.namespaceURI === 'http://www.w3.org/2000/svg';
            var attrs = Array.from(node.attributes);
            for (var ai = 0; ai < attrs.length; ai++) {
                var attr = attrs[ai];
                var name = attr.name.toLowerCase();
                var keep = false;

                if (name === 'class') {
                    var safeClasses = attr.value.split(/\s+/).filter(function(c) {
                        return SAFE_HTML_CLASSES.has(c);
                    });
                    if (safeClasses.length) node.setAttribute('class', safeClasses.join(' '));
                    else node.removeAttribute(attr.name);
                    continue;
                }
                if (name.startsWith('on') || name === 'style' || name === 'id' ||
                    name === 'name' || name === 'srcdoc' || name === 'formaction' ||
                    name === 'xlink:href') {
                    node.removeAttribute(attr.name);
                    continue;
                }

                if (tag === 'a' && name === 'href') {
                    keep = isSafeLinkUrl(attr.value);
                } else if (tag === 'img' && name === 'src') {
                    keep = isSafeImageUrl(attr.value);
                } else if (name === 'title' || name === 'aria-label' ||
                    name === 'aria-hidden') {
                    keep = true;
                } else if (tag === 'img' && (name === 'alt' || name === 'width' || name === 'height')) {
                    keep = true;
                } else if ((tag === 'td' || tag === 'th') &&
                    (name === 'colspan' || name === 'rowspan' || name === 'scope')) {
                    keep = true;
                } else if ((tag === 'td' || tag === 'th') && name === 'align') {
                    var alignment = attr.value.toLowerCase();
                    keep = alignment === 'left' || alignment === 'center' || alignment === 'right';
                    if (keep) node.setAttribute(attr.name, alignment);
                } else if (tag === 'ol' && (name === 'start' || name === 'reversed')) {
                    keep = true;
                } else if (tag === 'li' && name === 'value') {
                    keep = true;
                } else if (tag === 'details' && name === 'open') {
                    keep = true;
                } else if (isSvg && SAFE_SVG_ATTRS.has(name)) {
                    // SVG paint/filter URLs may only reference an element inside the
                    // same SVG. External URLs are unnecessary for notebook plots.
                    keep = !/url\s*\(/i.test(attr.value) || /^url\(#[A-Za-z_][\w:.-]*\)$/i.test(attr.value);
                }

                if (!keep) node.removeAttribute(attr.name);
            }

            if (tag === 'a' && node.hasAttribute('href')) {
                node.setAttribute('target', '_blank');
                node.setAttribute('rel', 'noopener noreferrer');
            }
            clean(node);
        }
    }

    clean(template.content);
    return template.content;
}

function setSanitizedHtml(element, html) {
    if (!element) return;
    element.replaceChildren(sanitizeHtmlFragment(html));
}

function appendSanitizedHtml(element, html) {
    if (!element) return;
    element.appendChild(sanitizeHtmlFragment(html));
}

function parseCellAttachments(value) {
    if (value == null || value === '') return null;
    try {
        var parsed = typeof value === 'string' ? JSON.parse(value) : value;
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
    } catch (e) {
        return null;
    }
}

function attachmentText(value) {
    if (typeof value === 'string') return value;
    if (Array.isArray(value) && value.every(function(item) { return typeof item === 'string'; })) {
        return value.join('');
    }
    return null;
}

function normalizeBase64(value) {
    var compact = String(value || '').replace(/\s+/g, '');
    if (!compact || compact.length % 4 === 1 || !/^[A-Za-z0-9+/]*={0,2}$/.test(compact)) return null;
    try {
        atob(compact);
        return compact;
    } catch (e) {
        return null;
    }
}

function utf8ToBase64(value) {
    var bytes = new TextEncoder().encode(value);
    var binary = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
}

function base64ToUtf8(value) {
    try {
        var binary = atob(value);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch (e) {
        return null;
    }
}

function sanitizeSvgAttachment(value) {
    var svgText = String(value || '').trim();
    if (!svgText || /<!doctype|<!entity/i.test(svgText)) return null;
    try {
        var parsed = new DOMParser().parseFromString(svgText, 'image/svg+xml');
        if (parsed.querySelector('parsererror') ||
            !parsed.documentElement || parsed.documentElement.localName.toLowerCase() !== 'svg') {
            return null;
        }
        var holder = document.createElement('div');
        holder.appendChild(document.importNode(parsed.documentElement, true));
        var sanitizedHolder = document.createElement('div');
        sanitizedHolder.appendChild(sanitizeHtmlFragment(holder.innerHTML));
        var root = sanitizedHolder.firstElementChild;
        if (!root || root.localName.toLowerCase() !== 'svg') return null;
        return root.outerHTML;
    } catch (e) {
        return null;
    }
}

const ATTACHMENT_IMAGE_MIMES = [
    'image/png', 'image/jpeg', 'image/jpg', 'image/gif', 'image/webp', 'image/svg+xml'
];

function resolveAttachmentImageUrl(name, attachments) {
    if (!attachments || !Object.prototype.hasOwnProperty.call(attachments, name)) return null;
    var bundle = attachments[name];
    if (!bundle || typeof bundle !== 'object' || Array.isArray(bundle)) return null;

    for (var i = 0; i < ATTACHMENT_IMAGE_MIMES.length; i++) {
        var mime = ATTACHMENT_IMAGE_MIMES[i];
        if (!Object.prototype.hasOwnProperty.call(bundle, mime)) continue;
        var value = attachmentText(bundle[mime]);
        if (value == null) continue;
        if (mime === 'image/svg+xml') {
            var trimmed = value.trim();
            var svgText = trimmed.charAt(0) === '<' ? trimmed : null;
            if (svgText == null) {
                var encodedSvg = normalizeBase64(trimmed);
                svgText = encodedSvg == null ? null : base64ToUtf8(encodedSvg);
            }
            var safeSvg = svgText == null ? null : sanitizeSvgAttachment(svgText);
            if (safeSvg != null) return 'data:image/svg+xml;base64,' + utf8ToBase64(safeSvg);
            continue;
        }
        var encoded = normalizeBase64(value);
        if (encoded != null) return 'data:' + mime + ';base64,' + encoded;
    }
    return null;
}

function markdownDestination(value) {
    var destination = String(value || '').trim();
    if (destination.charAt(0) === '<' && destination.charAt(destination.length - 1) === '>') {
        destination = destination.slice(1, -1);
    }
    return destination;
}

function resolveMarkdownImageUrl(value, attachments) {
    var destination = markdownDestination(value);
    if (destination.indexOf('attachment:') === 0) {
        var encodedName = destination.slice('attachment:'.length);
        var name;
        try { name = decodeURIComponent(encodedName); } catch (e) { return null; }
        return resolveAttachmentImageUrl(name, attachments);
    }
    if (isSafeImageUrl(destination) && /^data:image\//i.test(destination)) return destination;
    try {
        var parsed = new URL(destination);
        return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? destination : null;
    } catch (e) {
        return null;
    }
}

const MARKDOWN_WORD_CHAR = /^[\p{L}\p{N}_]$/u;

function codePointBeforeUtf16(text, index) {
    if (index <= 0) return '';
    var position = index - 1;
    var unit = text.charCodeAt(position);
    if (unit >= 0xDC00 && unit <= 0xDFFF && position > 0) {
        var previous = text.charCodeAt(position - 1);
        if (previous >= 0xD800 && previous <= 0xDBFF) position--;
    }
    return codePointAtUtf16(text, position);
}

// A single forward scan avoids the quadratic backtracking of delimiter regexes
// on large unmatched input. The restricted mode implements CommonMark's rule
// that underscores inside a Unicode word are literal (foo_bar stays intact).
function renderDelimitedEmphasis(text, marker, length, intrawordRestricted) {
    var output = [];
    var literalStart = 0;
    var active = null;
    var delimiter = marker.repeat(length);
    var i = 0;
    while (i < text.length) {
        if (text.substr(i, length) !== delimiter) {
            i++;
            continue;
        }

        if (active) {
            var beforeClose = codePointBeforeUtf16(text, i);
            var afterClose = codePointAtUtf16(text, i + length);
            var canClose = i > active.contentStart && beforeClose && !/\s/u.test(beforeClose) &&
                (!intrawordRestricted || !MARKDOWN_WORD_CHAR.test(afterClose));
            if (canClose) {
                var tag = length === 2 ? 'strong' : 'em';
                output.push('<' + tag + '>' + text.slice(active.contentStart, i) + '</' + tag + '>');
                i += length;
                literalStart = i;
                active = null;
                continue;
            }
            i++;
            continue;
        }

        var beforeOpen = codePointBeforeUtf16(text, i);
        var afterOpen = codePointAtUtf16(text, i + length);
        var canOpen = afterOpen && !/\s/u.test(afterOpen) &&
            (!intrawordRestricted || !MARKDOWN_WORD_CHAR.test(beforeOpen));
        if (canOpen) {
            output.push(text.slice(literalStart, i));
            active = { start: i, contentStart: i + length };
            i += length;
        } else {
            i++;
        }
    }

    output.push(active ? text.slice(active.start) : text.slice(literalStart));
    return output.join('');
}

function renderEmphasisMarkdown(text) {
    var escapedMarkers = [];
    var prepared = String(text || '').replace(/\\([\\*_])/g, function(_all, marker) {
        var placeholder = '\uE100JP_ESCAPE_' + escapedMarkers.length + '\uE101';
        escapedMarkers.push(marker);
        return placeholder;
    });
    var html = escapeHtmlJS(prepared);
    html = renderDelimitedEmphasis(html, '*', 2, false);
    html = renderDelimitedEmphasis(html, '*', 1, false);
    html = renderDelimitedEmphasis(html, '_', 2, true);
    html = renderDelimitedEmphasis(html, '_', 1, true);
    for (var i = 0; i < escapedMarkers.length; i++) {
        html = html.split('\uE100JP_ESCAPE_' + i + '\uE101').join(escapeHtmlJS(escapedMarkers[i]));
    }
    return html;
}

function renderInlineMarkdown(text, attachments) {
    var stashed = [];
    function stash(html) {
        var marker = '\uE000JP_INLINE_' + stashed.length + '\uE001';
        stashed.push(html);
        return marker;
    }

    var withPlaceholders = String(text || '').replace(/`([^`]+)`/g, function(_all, code) {
        return stash('<code>' + escapeHtmlJS(code) + '</code>');
    });
    withPlaceholders = withPlaceholders.replace(
        /!\[([^\]\n]*)\]\(\s*(<[^>\n]+>|[^\s)\n]+)(?:\s+(?:"[^"\n]*"|'[^'\n]*'))?\s*\)/g,
        function(all, alt, destination) {
            var src = resolveMarkdownImageUrl(destination, attachments);
            if (!src) return stash(escapeHtmlJS(all));
            return stash('<img src="' + attrEscape(src) + '" alt="' + attrEscape(alt) + '">');
        }
    );
    withPlaceholders = withPlaceholders.replace(
        /\[([^\]\n]+)\]\(\s*(<[^>\n]+>|[^\s)\n]+)(?:\s+(?:"[^"\n]*"|'[^'\n]*'))?\s*\)/g,
        function(all, label, destination) {
            var href = markdownDestination(destination);
            if (!isSafeLinkUrl(href)) return stash(escapeHtmlJS(all));
            return stash('<a href="' + attrEscape(href) + '">' + renderEmphasisMarkdown(label) + '</a>');
        }
    );

    var html = renderEmphasisMarkdown(withPlaceholders);
    // Placeholders may be nested (for example inline code in a link label), so
    // restore outer fragments first and their earlier inner fragments last.
    for (var i = stashed.length - 1; i >= 0; i--) {
        html = html.split('\uE000JP_INLINE_' + i + '\uE001').join(stashed[i]);
    }
    return html;
}

function splitMarkdownTableRow(line) {
    var input = String(line || '').trim();
    var cells = [];
    var current = '';
    var inCode = false;
    var sawDelimiter = false;
    for (var i = 0; i < input.length; i++) {
        var ch = input.charAt(i);
        if (ch === '\\' && input.charAt(i + 1) === '|') {
            current += '|';
            i++;
            continue;
        }
        if (ch === '`') {
            inCode = !inCode;
            current += ch;
            continue;
        }
        if (ch === '|' && !inCode) {
            cells.push(current.trim());
            current = '';
            sawDelimiter = true;
        } else {
            current += ch;
        }
    }
    cells.push(current.trim());
    if (cells.length && cells[0] === '') cells.shift();
    if (cells.length && cells[cells.length - 1] === '') cells.pop();
    return sawDelimiter && cells.length ? cells : null;
}

function markdownTableAlignments(line) {
    var cells = splitMarkdownTableRow(line);
    if (!cells) return null;
    var result = [];
    for (var i = 0; i < cells.length; i++) {
        var marker = cells[i].trim();
        if (!/^:?-{3,}:?$/.test(marker)) return null;
        var left = marker.charAt(0) === ':';
        var right = marker.charAt(marker.length - 1) === ':';
        result.push(left && right ? 'center' : right ? 'right' : left ? 'left' : null);
    }
    return result;
}

function isSetextHeadingText(line) {
    var text = String(line || '');
    if (/^\s{4}/.test(text)) return false;
    if (/^\s{0,3}(?:#{1,6}(?:\s|$)|>|[-*+]\s+|\d+[.)]\s+)/.test(text)) return false;
    var compact = text.trim().replace(/\s+/g, '');
    return !/^(?:\*{3,}|-{3,}|_{3,})$/.test(compact);
}

// Browser-side rendering keeps the hidden display pane current when Markdown
// edit mode ends. The Kotlin bridge only reports source changes and has no
// synchronous render response, so all exit paths share this deterministic,
// sanitizer-safe subset: headings, rules, paragraphs, quotes, flat lists,
// fences, links/images, emphasis and pipe tables. Nested lists, footnotes and
// math are deliberately outside this lightweight renderer.
function renderMarkdownSource(source, attachments) {
    var lines = String(source || '').replace(/\r\n?/g, '\n').split('\n');
    var html = '';
    var paragraph = [];
    var listItems = [];
    var listType = null;
    var listStart = 1;
    var quoteLines = [];
    var codeLines = [];
    var inFence = false;

    function flushParagraph() {
        if (!paragraph.length) return;
        html += '<p>' + paragraph.map(function(line) {
            return renderInlineMarkdown(line, attachments);
        }).join('<br>') + '</p>';
        paragraph = [];
    }
    function flushList() {
        if (!listItems.length) return;
        var start = listType === 'ol' && listStart !== 1 ? ' start="' + listStart + '"' : '';
        html += '<' + listType + start + '>' + listItems.map(function(item) {
            return '<li>' + renderInlineMarkdown(item, attachments) + '</li>';
        }).join('') + '</' + listType + '>';
        listItems = [];
        listType = null;
        listStart = 1;
    }
    function flushQuote() {
        if (!quoteLines.length) return;
        html += '<blockquote>' + quoteLines.map(function(line) {
            return renderInlineMarkdown(line, attachments);
        }).join('<br>') + '</blockquote>';
        quoteLines = [];
    }
    function flushTextBlocks() {
        flushParagraph();
        flushList();
        flushQuote();
    }

    for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (/^```/.test(line)) {
            if (inFence) {
                html += '<pre><code>' + escapeHtmlJS(codeLines.join('\n')) + '</code></pre>';
                codeLines = [];
                inFence = false;
            } else {
                flushTextBlocks();
                inFence = true;
            }
            continue;
        }
        if (inFence) {
            codeLines.push(line);
            continue;
        }
        if (!line.trim()) {
            flushTextBlocks();
            continue;
        }

        var setext = i + 1 < lines.length
            ? lines[i + 1].match(/^\s{0,3}(=+|-+)\s*$/)
            : null;
        if (setext && isSetextHeadingText(line)) {
            flushTextBlocks();
            var setextLevel = setext[1].charAt(0) === '=' ? 1 : 2;
            html += '<h' + setextLevel + '>' + renderInlineMarkdown(line.trim(), attachments) +
                '</h' + setextLevel + '>';
            i++;
            continue;
        }

        var tableHeader = splitMarkdownTableRow(line);
        var tableAlignment = i + 1 < lines.length ? markdownTableAlignments(lines[i + 1]) : null;
        if (tableHeader && tableAlignment && tableHeader.length === tableAlignment.length) {
            flushTextBlocks();
            html += '<table><thead><tr>' + tableHeader.map(function(cellText, column) {
                var align = tableAlignment[column] ? ' align="' + tableAlignment[column] + '"' : '';
                return '<th' + align + '>' + renderInlineMarkdown(cellText, attachments) + '</th>';
            }).join('') + '</tr></thead><tbody>';
            i += 2;
            while (i < lines.length) {
                var row = splitMarkdownTableRow(lines[i]);
                if (!row) break;
                while (row.length < tableHeader.length) row.push('');
                row = row.slice(0, tableHeader.length);
                html += '<tr>' + row.map(function(cellText, column) {
                    var align = tableAlignment[column] ? ' align="' + tableAlignment[column] + '"' : '';
                    return '<td' + align + '>' + renderInlineMarkdown(cellText, attachments) + '</td>';
                }).join('') + '</tr>';
                i++;
            }
            html += '</tbody></table>';
            i--;
            continue;
        }

        var heading = line.match(/^(#{1,6})\s+(.+)$/);
        if (heading) {
            flushTextBlocks();
            var level = heading[1].length;
            html += '<h' + level + '>' + renderInlineMarkdown(heading[2], attachments) + '</h' + level + '>';
            continue;
        }
        var rule = line.trim().replace(/\s+/g, '');
        if (/^(?:\*{3,}|-{3,}|_{3,})$/.test(rule)) {
            flushTextBlocks();
            html += '<hr>';
            continue;
        }
        var unorderedList = line.match(/^\s{0,3}[-*+]\s+(.+)$/);
        var orderedList = line.match(/^\s{0,3}(\d+)[.)]\s+(.+)$/);
        if (unorderedList || orderedList) {
            flushParagraph();
            flushQuote();
            var nextListType = orderedList ? 'ol' : 'ul';
            if (listItems.length && listType !== nextListType) flushList();
            if (!listItems.length) {
                listType = nextListType;
                listStart = orderedList ? Number(orderedList[1]) : 1;
            }
            listItems.push(orderedList ? orderedList[2] : unorderedList[1]);
            continue;
        }
        var quote = line.match(/^>\s?(.*)$/);
        if (quote) {
            flushParagraph();
            flushList();
            quoteLines.push(quote[1]);
            continue;
        }
        flushList();
        flushQuote();
        paragraph.push(line);
    }

    if (inFence) {
        html += '<pre><code>' + escapeHtmlJS(codeLines.join('\n')) + '</code></pre>';
    }
    flushTextBlocks();
    return html;
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

function syncMarkdownHeight(cellId) {
    var source = document.getElementById('md-source-' + cellId);
    if (!source) return;
    source.style.height = 'auto';
    source.style.height = Math.max(37, source.scrollHeight) + 'px';
}

// Convert a point in the highlighted <pre> into the UTF-16 offset used by a
// textarea selection. This keeps the caret under the double-click even though
// the editable textarea only becomes visible after the event was dispatched.
function caretRangeAtPoint(e) {
    if (!e) return null;
    var pointRange = null;
    if (document.caretRangeFromPoint) {
        pointRange = document.caretRangeFromPoint(e.clientX, e.clientY);
    } else if (document.caretPositionFromPoint) {
        var pos = document.caretPositionFromPoint(e.clientX, e.clientY);
        if (pos) {
            pointRange = document.createRange();
            pointRange.setStart(pos.offsetNode, pos.offset);
            pointRange.collapse(true);
        }
    }
    return pointRange;
}

function textOffsetAtPoint(element, e) {
    if (!element) return null;
    var pointRange = caretRangeAtPoint(e);
    if (!pointRange || !element.contains(pointRange.startContainer)) return null;
    try {
        var prefix = document.createRange();
        prefix.selectNodeContents(element);
        prefix.setEnd(pointRange.startContainer, pointRange.startOffset);
        return prefix.toString().length;
    } catch (err) {
        return null;
    }
}

// Rendered Markdown contains formatting nodes that are absent from its source.
// Walk its text nodes in source order and locate the clicked node in the raw
// source, so a double-click on a heading/emphasis/link still lands on that word.
function sourceOffsetAtPoint(element, source, e) {
    var pointRange = caretRangeAtPoint(e);
    if (!element || !pointRange || !element.contains(pointRange.startContainer)) return null;
    var pointNode = pointRange.startContainer;
    if (pointNode.nodeType !== Node.TEXT_NODE) {
        var fallback = textOffsetAtPoint(element, e);
        return fallback == null ? null : Math.min(fallback, String(source || '').length);
    }

    var raw = String(source || '');
    var walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    var searchFrom = 0;
    var node;
    while ((node = walker.nextNode())) {
        var nodeText = node.nodeValue || '';
        if (!nodeText) continue;
        var match = raw.indexOf(nodeText, searchFrom);
        if (node === pointNode) {
            if (match >= 0) {
                return Math.min(match + pointRange.startOffset, raw.length);
            }
            break;
        }
        if (match >= 0) searchFrom = match + nodeText.length;
    }

    var renderedOffset = textOffsetAtPoint(element, e);
    return renderedOffset == null ? null : Math.min(renderedOffset, raw.length);
}

// ── Cell Construction ──

function addCell(id, type, source, outputsHtml, executionCount, attachmentsJson) {
    var container = document.getElementById('notebook-container');
    var parsedAttachments = parseCellAttachments(attachmentsJson);
    if (parsedAttachments) attachmentsByCell[id] = parsedAttachments;
    else delete attachmentsByCell[id];
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
            if (cell.classList.contains('executing')) return;
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
                var definitionOffset = textOffsetAtPoint(backdrop, e);
                if (definitionOffset !== null && requestDefinitionAt(id, definitionOffset)) {
                    e.preventDefault();
                    return;
                }
            }
            clearUsageHighlight();
            selectCell(id);
        };

        sourceWrapper.ondblclick = function(e) {
            e.preventDefault();
            e.stopPropagation();
            if (cell.classList.contains('executing')) return;
            clearUsageHighlight();
            enterEditMode(id, textOffsetAtPoint(backdrop, e));
        };

        cell.appendChild(sourceWrapper);

        var outputDiv = document.createElement('div');
        outputDiv.className = 'cell-output';
        outputDiv.id = 'output-' + id;
        setSanitizedHtml(outputDiv, outputsHtml || '');
        cell.appendChild(outputDiv);
    } else {
        var isRaw = type === 'raw';
        var renderedDiv = document.createElement('div');
        renderedDiv.className = isRaw ? 'markdown-rendered raw-rendered' : 'markdown-rendered';
        renderedDiv.id = 'md-rendered-' + id;
        if (isRaw) {
            renderedDiv.textContent = source || '';
        } else {
            setSanitizedHtml(renderedDiv, renderMarkdownSource(source || '', attachmentsByCell[id]));
        }

        renderedDiv.onclick = function(e) {
            e.stopPropagation();
            if (cell.classList.contains('executing')) return;
            clearUsageHighlight();
            selectCell(id);
        };

        renderedDiv.ondblclick = function(e) {
            e.preventDefault();
            e.stopPropagation();
            if (cell.classList.contains('executing')) return;
            clearUsageHighlight();
            startEditMarkdown(id, sourceOffsetAtPoint(renderedDiv, mdSource.value, e));
        };

        cell.appendChild(renderedDiv);

        var mdSource = document.createElement('textarea');
        mdSource.className = isRaw ? 'markdown-source raw-source' : 'markdown-source';
        mdSource.id = 'md-source-' + id;
        mdSource.spellcheck = false;
        mdSource.autocomplete = 'off';
        mdSource.autocorrect = 'off';
        mdSource.autocapitalize = 'off';
        mdSource.value = source || '';
        cell.appendChild(mdSource);
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
        finishMarkdownEdit(editingMd[i].dataset.cellId);
    }
}

function enterEditMode(id, caretOffset) {
    exitAllEditModes();
    hideDiagTooltip();

    var cell = document.getElementById('cell-' + id);
    if (!cell) return;
    var sourceWrapper = cell.querySelector('.cell-source');
    var textarea = cell.querySelector('.source-input');
    var backdrop = cell.querySelector('.source-backdrop');
    if (!sourceWrapper || !textarea || !backdrop) return;

    selectCell(id);
    var rememberedOffset = textarea.selectionStart || 0;
    sourceWrapper.classList.add('editing');
    textarea.value = backdrop.textContent;
    syncTextareaHeight(id);
    textarea.scrollTop = 0;
    textarea.scrollLeft = 0;
    backdrop.scrollTop = 0;
    backdrop.scrollLeft = 0;
    textarea.focus();
    var nextOffset = Number.isInteger(caretOffset) ? caretOffset : rememberedOffset;
    nextOffset = Math.max(0, Math.min(nextOffset, textarea.value.length));
    textarea.setSelectionRange(nextOffset, nextOffset);

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
            requestDefinitionAt(id, textarea.selectionStart);
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
            requestDefinitionAt(id, textarea.selectionStart);
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
        } else if (nextEl.dataset.cellType === 'markdown' || nextEl.dataset.cellType === 'raw') {
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

function startEditMarkdown(id, caretOffset) {
    exitAllEditModes();
    selectCell(id);
    var cell = document.getElementById('cell-' + id);
    var mdSource = document.getElementById('md-source-' + id);
    if (cell && mdSource) {
        var rememberedOffset = mdSource.selectionStart || 0;
        cell.classList.add('editing-markdown');
        syncMarkdownHeight(id);
        mdSource.focus();
        var nextOffset = Number.isInteger(caretOffset) ? caretOffset : rememberedOffset;
        nextOffset = Math.max(0, Math.min(nextOffset, mdSource.value.length));
        mdSource.setSelectionRange(nextOffset, nextOffset);
        mdSource.oninput = function() {
            if (kotlinBridge) kotlinBridge.cellSourceChanged(id, mdSource.value);
            syncMarkdownHeight(id);
        };
        mdSource.onkeydown = function(e) {
            if (e.key === 'Tab' && !e.shiftKey) {
                e.preventDefault();
                var start = mdSource.selectionStart;
                var end = mdSource.selectionEnd;
                mdSource.setRangeText('    ', start, end, 'end');
                mdSource.dispatchEvent(new Event('input', { bubbles: true }));
                return;
            }
            if (e.key === 'Enter' && (e.shiftKey || e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                return;
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                finishMarkdownEdit(id);
                return;
            }
            if (e.key === 's' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                e.stopPropagation();
                if (kotlinBridge) kotlinBridge.saveNotebook();
            }
        };
    }
}

function finishMarkdownEdit(id) {
    var cell = document.getElementById('cell-' + id);
    var source = document.getElementById('md-source-' + id);
    var rendered = document.getElementById('md-rendered-' + id);
    if (!cell || !source) return;

    var text = source.value;
    if (kotlinBridge) kotlinBridge.cellSourceChanged(id, text);
    if (rendered) {
        if (cell.dataset.cellType === 'raw') {
            rendered.textContent = text;
        } else {
            setSanitizedHtml(rendered, renderMarkdownSource(text, attachmentsByCell[id]));
        }
    }
    cell.classList.remove('editing-markdown');
    source.oninput = null;
    source.onkeydown = null;
    source.blur();
    if (searchState.active) scheduleSearchRefresh();
}

function stopEditMarkdown(id, renderedHtml) {
    var cell = document.getElementById('cell-' + id);
    var source = document.getElementById('md-source-' + id);
    var rendered = document.getElementById('md-rendered-' + id);
    if (cell) cell.classList.remove('editing-markdown');
    if (source) {
        source.oninput = null;
        source.onkeydown = null;
        source.blur();
    }
    setSanitizedHtml(rendered, renderedHtml);
    if (searchState.active) scheduleSearchRefresh();
}

// Click-outside handler for markdown cells
document.addEventListener('mousedown', function(e) {
    var editingCells = document.querySelectorAll('.cell.editing-markdown');
    for (var i = 0; i < editingCells.length; i++) {
        var cell = editingCells[i];
        if (!cell.contains(e.target)) {
            finishMarkdownEdit(cell.dataset.cellId);
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
        delete attachmentsByCell[id];
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
    appendSanitizedHtml(outputEl, outputHtml);
    if (searchState.active) scheduleSearchRefresh();
}

function setExecutionCount(id, count) {
    var execCount = document.getElementById('exec-count-' + id);
    if (execCount) execCount.textContent = count != null ? '[' + count + ']' : '[ ]';
}

function setCellExecuting(id, executing) {
    var cell = document.getElementById('cell-' + id);
    if (!cell) return;
    var runBtn = cell.querySelector('.run-btn');
    if (executing) {
        cell.classList.add('executing');
        if (runBtn) runBtn.disabled = true;
        if (isEditing(id)) exitEditMode(id);
    } else {
        cell.classList.remove('executing');
        if (runBtn) runBtn.disabled = false;
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
    setSanitizedHtml(rendered, html);
    if (searchState.active) scheduleSearchRefresh();
}

function setMarkdownSource(id, source) {
    var mdSource = document.getElementById('md-source-' + id);
    if (mdSource) {
        mdSource.value = source;
        if (mdSource.closest('.editing-markdown')) syncMarkdownHeight(id);
    }
}

function clearNotebook() {
    document.getElementById('notebook-container').innerHTML = '';
    selectedCellId = null;
    attachmentsByCell = Object.create(null);
}

function renderNotebookComplete() {
    rebuildGaps();
    highlightAllCells();
}

function getSelectedCellId() {
    return selectedCellId;
}

function insertCellAfter(afterId, newId, type, source, outputsHtml, executionCount, attachmentsJson) {
    var container = document.getElementById('notebook-container');

    if (!afterId || afterId === '') {
        addCell(newId, type, source, outputsHtml, executionCount, attachmentsJson);
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
        addCell(newId, type, source, outputsHtml, executionCount, attachmentsJson);
        rebuildGaps();
        scheduleHighlightAll();
        selectCell(newId);
        if (type === 'code') enterEditMode(newId);
        return;
    }

    addCell(newId, type, source, outputsHtml, executionCount, attachmentsJson);
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
    var identifiers = /[_\p{ID_Start}][_\p{ID_Continue}]*/gu;
    var match;
    while ((match = identifiers.exec(text))) {
        var start = match.index;
        var end = start + match[0].length;
        if (start <= idx && idx <= end) return match[0];
        if (start > idx) break;
    }
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

/**
 * Prefer the language-aware Kotlin/Python resolver. The old in-page resolver is
 * retained only for the standalone HTML test page where no IDE bridge exists.
 */
function requestDefinitionAt(cellId, cursorOffsetUtf16) {
    var source = getCellText(cellId);
    var name = wordAtIndex(source, cursorOffsetUtf16);
    if (!name) return false;

    usageHighlightName = name;
    highlightAllCells();
    if (kotlinBridge && typeof kotlinBridge.goToDefinition === 'function') {
        kotlinBridge.goToDefinition(JSON.stringify({
            requestId: ++definitionRequestCounter,
            cellId: cellId,
            cursorOffsetUtf16: cursorOffsetUtf16
        }));
    } else {
        gotoDefinition(name, cellId);
    }
    return true;
}

/** Navigate to a zero-based Unicode code-point position returned by the resolver. */
function navigateToCellLocation(cellId, line, codePointColumn, symbol) {
    var cell = document.getElementById('cell-' + cellId);
    if (!cell || cell.dataset.cellType !== 'code') return;
    usageHighlightName = symbol || null;
    enterEditMode(cellId);
    var textarea = cell.querySelector('.source-input');
    if (!textarea) return;

    var lines = textarea.value.split('\n');
    var safeLine = Math.max(0, Math.min(Number(line) || 0, lines.length - 1));
    var lineStart = 0;
    for (var i = 0; i < safeLine; i++) lineStart += lines[i].length + 1;
    var codePoints = Array.from(lines[safeLine] || '');
    var safeColumn = Math.max(0, Math.min(Number(codePointColumn) || 0, codePoints.length));
    var utf16Column = codePoints.slice(0, safeColumn).join('').length;
    var offset = lineStart + utf16Column;
    textarea.setSelectionRange(offset, offset);
    textarea.focus();
    scrollToCell(cellId);
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
        if (cell.classList.contains('executing')) return;

        if (isEditing(cellId)) {
            exitEditMode(cellId);
        }
        if (cell.classList.contains('editing-markdown')) {
            finishMarkdownEdit(cellId);
        }

        if (cell.dataset.cellType === 'code' && kotlinBridge) {
            // Advance only once the cell finishes successfully (onCellExecuted).
            pendingAdvanceCellId = cellId;
            kotlinBridge.runCell(cellId);
        } else {
            // Markdown/raw cells have no kernel execution; advance immediately.
            moveToNextCell(cellId);
        }
    }
});
