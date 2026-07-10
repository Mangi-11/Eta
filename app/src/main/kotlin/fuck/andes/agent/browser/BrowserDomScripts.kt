package fuck.andes.agent.browser

import org.json.JSONObject

/**
 * Agent 浏览器注入页面的只读/定向交互脚本。
 *
 * 模型不能执行任意 JavaScript。所有 DOM 遍历都有节点、时间、字段和输出上限；读取只接受
 * 计算后可见的内容，交互也不会回退到隐藏或已禁用的控件。
 */
internal object BrowserDomScripts {
    fun wrap(body: String): String =
        """
        (function() {
          var MAX_FIELD_CHARS = 240;
          var MAX_URL_CHARS = 320;
          var MAX_DOCUMENT_CHARS = 200000;
          var MAX_SELECTOR_CHARS = 240;

          function boundedString(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            var text = String(value == null ? '' : value);
            if (text.length > max * 4) text = text.slice(0, max * 4);
            return text.slice(0, max);
          }
          function cleanInline(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            return boundedString(value, max * 4)
              .replace(/[\t\r\n ]+/g, ' ')
              .trim()
              .slice(0, max);
          }
          function cleanBlock(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_DOCUMENT_CHARS);
            return boundedString(value, max * 2)
              .replace(/\r/g, '')
              .replace(/[\t ]+\n/g, '\n')
              .replace(/\n[\t ]+/g, '\n')
              .replace(/[\t ]{2,}/g, ' ')
              .replace(/\n{3,}/g, '\n\n')
              .trim()
              .slice(0, max);
          }
          function visible(element) {
            if (!element || !(element instanceof Element)) return false;
            if (element.tagName && element.tagName.toLowerCase() === 'input' &&
                String(element.getAttribute('type') || '').toLowerCase() === 'hidden') return false;
            var ancestor = element;
            var depth = 0;
            while (ancestor && depth < 40) {
              if (ancestor.hidden || ancestor.hasAttribute('inert') ||
                  ancestor.getAttribute('aria-hidden') === 'true') return false;
              var style = window.getComputedStyle(ancestor);
              if (style.display === 'none' || style.visibility === 'hidden' ||
                  style.visibility === 'collapse' || style.contentVisibility === 'hidden' ||
                  Number(style.opacity || 1) <= 0) return false;
              if ((style.clip && style.clip !== 'auto') ||
                  (style.clipPath && style.clipPath !== 'none')) return false;
              ancestor = ancestor.parentElement;
              depth++;
            }
            if (ancestor) return false;
            var rect = element.getBoundingClientRect();
            var tag = String(element.tagName || '').toLowerCase();
            if (window.getComputedStyle(element).display === 'contents' || tag === 'html' || tag === 'body') {
              return true;
            }
            if (rect.right + window.scrollX <= 0 || rect.bottom + window.scrollY <= 0) return false;
            return rect.width > 0 && rect.height > 0 && element.getClientRects().length > 0;
          }
          function enabled(element) {
            return visible(element) && !element.disabled &&
              element.getAttribute('aria-disabled') !== 'true' &&
              !element.hasAttribute('inert');
          }
          function editable(element) {
            if (!enabled(element) || element.readOnly) return false;
            if (element.isContentEditable) return true;
            var tag = (element.tagName || '').toLowerCase();
            if (tag === 'textarea') return true;
            if (tag !== 'input') return false;
            var type = String(element.getAttribute('type') || 'text').toLowerCase();
            return !['hidden','file','button','submit','reset','image','checkbox','radio'].includes(type);
          }
          function cssEscape(value) {
            if (window.CSS && CSS.escape) return CSS.escape(boundedString(value, 180));
            return boundedString(value, 180).replace(/[^a-zA-Z0-9_-]/g, function(ch) {
              return '\\' + ch.charCodeAt(0).toString(16) + ' ';
            });
          }
          function selectorFor(element) {
            if (!element || !(element instanceof Element)) return null;
            if (element.id) {
              var byId = '#' + cssEscape(element.id);
              try { if (document.querySelectorAll(byId).length === 1) return byId; } catch (_) {}
            }
            var parts = [];
            var node = element;
            var depth = 0;
            while (node && node.nodeType === Node.ELEMENT_NODE && node !== document.body && depth < 20) {
              var part = String(node.tagName || '').toLowerCase();
              var parent = node.parentElement;
              if (!part) break;
              if (parent) {
                var position = 0;
                var count = 0;
                for (var index = 0; index < parent.children.length && index < 2000; index++) {
                  if (parent.children[index].tagName === node.tagName) {
                    count++;
                    if (parent.children[index] === node) position = count;
                  }
                }
                if (count > 1 && position > 0) part += ':nth-of-type(' + position + ')';
              }
              parts.unshift(part);
              var candidate = parts.join(' > ');
              if (candidate.length > MAX_SELECTOR_CHARS) break;
              try { if (document.querySelectorAll(candidate).length === 1) return candidate; } catch (_) {}
              node = parent;
              depth++;
            }
            return boundedString(parts.join(' > '), MAX_SELECTOR_CHARS) || null;
          }
          function safeHttpsUrl(value) {
            if (!value) return null;
            try {
              var parsed = new URL(boundedString(value, 2048), document.baseURI);
              if (parsed.protocol !== 'https:') return null;
              parsed.username = '';
              parsed.password = '';
              parsed.search = '';
              parsed.hash = '';
              return boundedString(parsed.origin + '/', MAX_URL_CHARS);
            } catch (_) { return null; }
          }
          function collectVisibleText(root, maxChars, nodeLimit, sharedDeadline) {
            var limit = Math.max(0, Math.min(Number(maxChars) || 0, MAX_DOCUMENT_CHARS));
            var maxNodes = Math.max(1, Math.min(Number(nodeLimit) || 1, 12000));
            var parts = [];
            var chars = 0;
            var nodes = 0;
            var truncated = false;
            var deadline = Number(sharedDeadline) || (Date.now() + 500);
            if (!root) return { text: '', truncated: false, nodes: 0 };
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            var item;
            while ((item = walker.nextNode())) {
              nodes++;
              if (nodes > maxNodes || (nodes % 64 === 0 && Date.now() > deadline)) {
                truncated = true;
                break;
              }
              var parent = item.parentElement;
              if (!parent || !visible(parent)) continue;
              var tag = String(parent.tagName || '').toLowerCase();
              if (['script','style','noscript','template','svg','canvas','iframe'].includes(tag)) continue;
              var remaining = limit - chars;
              if (remaining <= 0) {
                truncated = true;
                break;
              }
              var text = cleanInline(item.nodeValue, Math.min(remaining, 2000));
              if (!text) continue;
              parts.push(text);
              chars += text.length + 1;
            }
            return {
              text: cleanBlock(parts.join('\n'), limit),
              truncated: truncated,
              nodes: Math.min(nodes, maxNodes)
            };
          }
          function visibleText(root, maxChars, nodeLimit, sharedDeadline) {
            return collectVisibleText(root, maxChars, nodeLimit, sharedDeadline).text;
          }
          function describe(element, sharedDeadline) {
            var rect = element.getBoundingClientRect();
            return {
              selector: selectorFor(element),
              tag: boundedString((element.tagName || '').toLowerCase(), 32),
              role: cleanInline(element.getAttribute('role'), 48) || null,
              text: visibleText(element, 160, 400, sharedDeadline),
              aria_label: cleanInline(element.getAttribute('aria-label'), 100),
              placeholder: cleanInline(element.getAttribute('placeholder'), 100),
              href: safeHttpsUrl(element.getAttribute('href')),
              type: cleanInline(element.getAttribute('type'), 32) || null,
              bounds: {
                x: Math.round(rect.left), y: Math.round(rect.top),
                width: Math.round(rect.width), height: Math.round(rect.height)
              }
            };
          }
          function resolveTarget(selector, x, y) {
            var target = null;
            var deadline = Date.now() + 300;
            if (selector) {
              var matches = document.querySelectorAll(selector);
              for (var index = 0; index < matches.length && index < 2000 && Date.now() <= deadline; index++) {
                if (visible(matches[index])) {
                  target = matches[index];
                  break;
                }
              }
            } else if (Number.isFinite(x) && Number.isFinite(y)) {
              target = document.elementFromPoint(x, y);
            }
            if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
            if (!enabled(target)) throw new Error('TARGET_DISABLED');
            return target;
          }
          function requireHitTarget(target) {
            var rect = target.getBoundingClientRect();
            var left = Math.max(0, rect.left);
            var right = Math.min(window.innerWidth, rect.right);
            var top = Math.max(0, rect.top);
            var bottom = Math.min(window.innerHeight, rect.bottom);
            if (right <= left || bottom <= top) throw new Error('TARGET_NOT_VISIBLE');
            var hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2);
            if (!hit || (hit !== target && !target.contains(hit))) throw new Error('TARGET_OCCLUDED');
          }
          function markdownEscape(value) {
            return boundedString(value, 4000).replace(/([\\`*_[\]<>])/g, '\\${'$'}1');
          }
          function markdownState() {
            return {
              parts: [], remainingNodes: 8000, remainingChars: MAX_DOCUMENT_CHARS,
              visited: 0, deadline: Date.now() + 750, truncated: false
            };
          }
          function consumeNode(state) {
            state.visited++;
            state.remainingNodes--;
            if (state.remainingNodes < 0 || (state.visited % 64 === 0 && Date.now() > state.deadline)) {
              state.truncated = true;
              return false;
            }
            return true;
          }
          function emit(state, value) {
            if (state.remainingChars <= 0) {
              state.truncated = true;
              return;
            }
            var text = String(value || '');
            if (text.length > state.remainingChars) {
              text = text.slice(0, state.remainingChars);
              state.truncated = true;
            }
            state.parts.push(text);
            state.remainingChars -= text.length;
          }
          function renderTable(table, state) {
            var output = [];
            var rows = table.rows || [];
            for (var rowIndex = 0; rowIndex < rows.length && rowIndex < 60; rowIndex++) {
              if (!consumeNode(state)) break;
              var row = [];
              var cells = rows[rowIndex].cells || [];
              for (var cellIndex = 0; cellIndex < cells.length && cellIndex < 12; cellIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                row.push(visibleText(cells[cellIndex], 300, 200, state.deadline).replace(/\|/g, '\\|'));
              }
              if (row.length) output.push(row);
            }
            if (!output.length) return;
            var width = Math.max.apply(null, output.map(function(row) { return row.length; }));
            output.forEach(function(row) { while (row.length < width) row.push(''); });
            emit(state, '\n\n| ' + output[0].join(' | ') + ' |\n');
            emit(state, '| ' + output[0].map(function() { return '---'; }).join(' | ') + ' |\n');
            for (var index = 1; index < output.length; index++) {
              emit(state, '| ' + output[index].join(' | ') + ' |\n');
            }
            emit(state, '\n');
          }
          function emitChildren(node, depth, state) {
            for (var index = 0; index < node.childNodes.length; index++) {
              if (state.truncated) break;
              emitMarkdown(node.childNodes[index], depth + 1, state);
            }
          }
          function emitMarkdown(node, depth, state) {
            if (!node || state.truncated || depth > 60 || !consumeNode(state)) return;
            if (node.nodeType === Node.TEXT_NODE) {
              var text = cleanInline(node.nodeValue, 2000);
              if (text) emit(state, markdownEscape(text) + ' ');
              return;
            }
            if (node.nodeType !== Node.ELEMENT_NODE || !visible(node)) return;
            var tag = String(node.tagName || '').toLowerCase();
            if (['script','style','noscript','template','svg','canvas','iframe','nav','form','button','input','textarea','select'].includes(tag)) return;
            if (/^h[1-6]$/.test(tag)) {
              emit(state, '\n\n' + '#'.repeat(Number(tag.substring(1))) + ' ');
              emit(state, markdownEscape(visibleText(node, 2000, 500, state.deadline)) + '\n\n');
              return;
            }
            if (tag === 'br') { emit(state, '\n'); return; }
            if (tag === 'hr') { emit(state, '\n\n---\n\n'); return; }
            if (tag === 'pre') {
              var pre = visibleText(node, 6000, 1200, state.deadline).replace(/```/g, '``\\`');
              if (pre) emit(state, '\n\n```\n' + pre + '\n```\n\n');
              return;
            }
            if (tag === 'code') {
              emit(state, '`' + visibleText(node, 1000, 300, state.deadline).replace(/`/g, '\\`') + '`');
              return;
            }
            if (tag === 'blockquote') {
              var quote = visibleText(node, 5000, 1200, state.deadline);
              if (quote) emit(state, '\n\n' + quote.split('\n').map(function(line) { return '> ' + line; }).join('\n') + '\n\n');
              return;
            }
            if (tag === 'table') { renderTable(node, state); return; }
            if (tag === 'a') {
              var label = visibleText(node, 600, 300, state.deadline) || cleanInline(node.getAttribute('aria-label'), 160);
              var href = safeHttpsUrl(node.getAttribute('href'));
              if (label) emit(state, href ? '[' + markdownEscape(label) + '](' + href + ')' : markdownEscape(label));
              return;
            }
            if (tag === 'img') {
              var alt = cleanInline(node.getAttribute('alt'), 200);
              if (alt) emit(state, '[图片：' + markdownEscape(alt) + ']');
              return;
            }
            if (tag === 'ul' || tag === 'ol') {
              emit(state, '\n\n');
              var number = 0;
              for (var itemIndex = 0; itemIndex < node.children.length && itemIndex < 200; itemIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                var item = node.children[itemIndex];
                if (String(item.tagName || '').toLowerCase() !== 'li' || !visible(item)) continue;
                number++;
                emit(state, tag === 'ol' ? String(number) + '. ' : '- ');
                emitChildren(item, depth + 1, state);
                emit(state, '\n');
              }
              emit(state, '\n');
              return;
            }
            var isBlock = ['p','div','main','article','section','header','footer','aside','figure','figcaption','details','summary','dl','dt','dd'].includes(tag);
            if (isBlock) emit(state, '\n\n');
            emitChildren(node, depth, state);
            if (isBlock) emit(state, '\n\n');
          }
          function readableTarget() {
            var selectors = ['article','main','[role="main"]','.article','.post','.entry-content','.content'];
            var candidates = [];
            var seen = new Set();
            var deadline = Date.now() + 300;
            for (var selectorIndex = 0; selectorIndex < selectors.length && Date.now() <= deadline; selectorIndex++) {
              var matches;
              try { matches = document.querySelectorAll(selectors[selectorIndex]); } catch (_) { continue; }
              for (var index = 0; index < matches.length && index < 40 && candidates.length < 80; index++) {
                var item = matches[index];
                if (visible(item) && !seen.has(item)) {
                  seen.add(item);
                  candidates.push(item);
                }
              }
            }
            var best = null;
            var bestScore = -1;
            for (var candidateIndex = 0; candidateIndex < candidates.length && Date.now() <= deadline; candidateIndex++) {
              var score = visibleText(candidates[candidateIndex], 20000, 1000, deadline).length;
              if (score > bestScore) {
                best = candidates[candidateIndex];
                bestScore = score;
              }
            }
            return best || document.body || document.documentElement;
          }
          try {
            var value = (function() {
              $body
            })();
            return JSON.stringify({ ok: true, value: value === undefined ? null : value });
          } catch (error) {
            return JSON.stringify({
              ok: false,
              error: cleanInline(error && error.message ? error.message : 'SCRIPT_FAILED', 160)
            });
          }
        })();
        """.trimIndent()

    fun readable(offset: Int, maxChars: Int): String =
        """
        var target = readableTarget();
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        var state = markdownState();
        emitMarkdown(target, 0, state);
        var markdown = cleanBlock(state.parts.join(''), MAX_DOCUMENT_CHARS);
        var total = markdown.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        return {
          text: markdown.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || state.truncated,
          source_truncated: state.truncated,
          visited_nodes: state.visited,
          selector_used: selectorFor(target),
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: (function() {
            var item = document.querySelector('link[rel="canonical"]');
            return item ? safeHttpsUrl(item.getAttribute('href')) : null;
          })()
        };
        """.trimIndent()

    fun text(selector: String?, offset: Int, maxChars: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var target = null;
        if (selector) {
          var matches = document.querySelectorAll(selector);
          for (var index = 0; index < matches.length && index < 2000; index++) {
            if (visible(matches[index])) { target = matches[index]; break; }
          }
        } else {
          target = document.body || document.documentElement;
        }
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        var collected = collectVisibleText(target, MAX_DOCUMENT_CHARS, 12000);
        var value = collected.text;
        var total = value.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        return {
          text: value.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || collected.truncated,
          source_truncated: collected.truncated,
          visited_nodes: collected.nodes,
          selector_used: selector || selectorFor(target)
        };
        """.trimIndent()
    }

    fun findElements(selector: String?): String {
        val selectorLiteral = JSONObject.quote(
            selector ?: "a,button,input,textarea,select,[role=\"button\"],[role=\"link\"],[contenteditable=\"true\"],[tabindex]"
        )
        return """
        var selector = $selectorLiteral;
        var matches = document.querySelectorAll(selector);
        var elements = [];
        var scanned = 0;
        var deadline = Date.now() + 500;
        for (var index = 0; index < matches.length && index < 3000 && elements.length < 16 && Date.now() <= deadline; index++) {
          scanned++;
          if (enabled(matches[index])) elements.push(describe(matches[index], deadline));
        }
        return {
          selector_used: selector,
          element_count: elements.length,
          scanned_elements: scanned,
          truncated: scanned < matches.length,
          elements: elements
        };
        """.trimIndent()
    }

    fun click(selector: String?, x: Int?, y: Int?): String =
        targeted(selector, x, y) +
            """
            target.scrollIntoView({ block: 'center', inline: 'center' });
            requireHitTarget(target);
            var rect = target.getBoundingClientRect();
            var cx = rect.left + rect.width / 2;
            var cy = rect.top + rect.height / 2;
            ['mousemove','mouseover','mousedown','mouseup'].forEach(function(kind) {
              target.dispatchEvent(new MouseEvent(kind, { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
            });
            target.click();
            return { matched_element: describe(target) };
            """.trimIndent()

    fun type(selector: String?, x: Int?, y: Int?, text: String, submit: Boolean): String =
        targeted(selector, x, y) +
            """
            if (!editable(target)) throw new Error('TARGET_NOT_EDITABLE');
            target.scrollIntoView({ block: 'center', inline: 'center' });
            requireHitTarget(target);
            target.focus();
            var value = ${JSONObject.quote(text)};
            if (target.isContentEditable) {
              target.textContent = value;
            } else {
              var prototype = target.tagName.toLowerCase() === 'textarea' ?
                window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(prototype, 'value');
              if (setter && setter.set) setter.set.call(target, value); else target.value = value;
            }
            target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: null }));
            target.dispatchEvent(new Event('change', { bubbles: true }));
            if ($submit) {
              var form = target.form || target.closest('form');
              if (form && form.requestSubmit) form.requestSubmit();
              else target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
            }
            return { matched_element: describe(target), typed_chars: value.length, submitted: $submit };
            """.trimIndent()

    fun scroll(selector: String?, direction: String, amount: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var target = selector ? document.querySelector(selector) : (document.scrollingElement || document.documentElement);
        if (!target || (selector && !visible(target))) throw new Error('TARGET_NOT_VISIBLE');
        var delta = ${if (direction == "up") -amount else amount};
        var documentTarget = target === document.scrollingElement || target === document.documentElement || target === document.body;
        var before = documentTarget ? window.scrollY : target.scrollTop;
        if (documentTarget) window.scrollBy(0, delta); else target.scrollBy(0, delta);
        var after = documentTarget ? window.scrollY : target.scrollTop;
        return {
          selector_used: selector || selectorFor(target),
          direction: ${JSONObject.quote(direction)}, amount: $amount, before: before, after: after
        };
        """.trimIndent()
    }

    fun pageInfo(): String =
        """
        var canonical = document.querySelector('link[rel="canonical"]');
        return {
          viewport_width: window.innerWidth,
          viewport_height: window.innerHeight,
          content_width: Math.min(200000, Math.max(document.body ? document.body.scrollWidth : 0, document.documentElement.scrollWidth)),
          content_height: Math.min(200000, Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement.scrollHeight)),
          scroll_x: window.scrollX || 0,
          scroll_y: window.scrollY || 0,
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: canonical ? safeHttpsUrl(canonical.getAttribute('href')) : null
        };
        """.trimIndent()

    fun selectorState(selector: String): String =
        """
        var matches = document.querySelectorAll(${JSONObject.quote(selector)});
        var target = null;
        for (var index = 0; index < matches.length && index < 2000; index++) {
          if (visible(matches[index])) { target = matches[index]; break; }
        }
        return { found: !!target, visible: !!target, enabled: target ? enabled(target) : false };
        """.trimIndent()

    fun riskSample(): String =
        """
        return {
          title: cleanInline(document.title || '', 160),
          text: visibleText(document.body || document.documentElement, 8000, 1200)
        };
        """.trimIndent()

    private fun targeted(selector: String?, x: Int?, y: Int?): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        val xLiteral = x?.toString() ?: "null"
        val yLiteral = y?.toString() ?: "null"
        return "var target = resolveTarget($selectorLiteral, $xLiteral, $yLiteral);\n"
    }
}
