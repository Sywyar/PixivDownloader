/* eslint-disable */
/** 正文块、逐句说话人标注与尺寸刷新。 */
(function (global) {
    'use strict';

    const modules = global.PixivNovelNarrationModules || (global.PixivNovelNarrationModules = {});
    modules.marks = {
        install(ctx) {
            const { LS, MARK_GAP, MARK_NAME_CAP_RATIO, lsSet, state, t } = ctx.core;
    // ---------- 渲染块 ----------
    function buildBlocks() {
        if (!state.contentEl) return [];
        return Array.from(state.contentEl.querySelectorAll('h2.novel-chapter, p'));
    }

    function speakerLabel(line) {
        if (!line) return '';
        if (line.speakerId === 0) return t('narration:narrator', '旁白');
        return line.speakerName || t('narration:narrator', '旁白');
    }

    // ---------- 「显示分析出的说话人」正文标注 ----------
    // 勾选后：正文每个段落按逐句脚本切成「逐句块」，每句左侧固定宽度的说话人列里标出说话人，正文整体右移
    // 一个说话人列宽（最长名字宽，上限 = 正文宽度的 10%，超出上限的名字在列内换行）。非破坏式：保存原 innerHTML，
    // 取消勾选 / 退出富感情朗读时还原。逐句切分基于脚本逐句 text 与 DOM 可朗读文本（剔除 ruby 注音 / 翻页 / 图片占位）
    // 的顺序匹配；某段无法对齐时该段只缩进、不加逐句标注，绝不破坏正文。
    let markedBlocks = [];           // [{ el, html }] 已标注块与其原始 innerHTML
    let markResizeBound = false;

    // 与后端断句 String.trim() 对齐：只把码位 <= U+0020 的字符视作句间空白（全角空格 / NBSP 等保留在句内）。
    function isMarkWs(ch) { return ch <= ' '; }

    // 一个内联子树还原成「可朗读文本」：与后端 NovelMarkupParser.plainText 对齐——ruby 取基词（剔除 rt）、
    // 外链取链接文字、翻页提示与内嵌图片占位剔除。
    function subtreeReadable(el) {
        let s = '';
        el.childNodes.forEach((n) => {
            if (n.nodeType === 3) { s += n.data; return; }
            if (n.nodeType !== 1) return;
            if (n.tagName === 'RT' || n.tagName === 'FIGURE') return;
            if (n.classList && n.classList.contains('novel-jump')) return;
            s += subtreeReadable(n);
        });
        return s;
    }

    // 把一个段落 <p> 按其逐句脚本切成逐句块。成功返回 true（已改写 innerHTML），匹配失败返回 false（原段落不动）。
    function markBlock(block, lines) {
        // 1) 收集顶层子节点为「原子」，并拼出该块的可朗读文本（与断句所用文本对齐）。
        const atoms = [];
        let readable = '';
        block.childNodes.forEach((child) => {
            if (child.nodeType === 3) {
                atoms.push({ kind: 'text', node: child, start: readable.length, len: child.data.length });
                readable += child.data;
            } else if (child.nodeType === 1) {
                if (child.tagName === 'BR') {
                    atoms.push({ kind: 'br', start: readable.length, len: 1 });
                    readable += '\n';
                } else {
                    const r = subtreeReadable(child);
                    atoms.push({ kind: 'el', node: child, start: readable.length, len: r.length });
                    readable += r;
                }
            }
        });
        // 2) 逐句在可朗读文本里顺序定位（跳过句间空白；优先 startsWith，失败再 indexOf 容错）。
        const ranges = [];
        let cursor = 0;
        for (let li = 0; li < lines.length; li++) {
            const s = lines[li].text || '';
            if (!s) continue;
            while (cursor < readable.length && isMarkWs(readable.charAt(cursor))) cursor++;
            let st = readable.startsWith(s, cursor) ? cursor : readable.indexOf(s, cursor);
            if (st < 0) return false; // 对不齐：整段放弃逐句标注
            ranges.push({ start: st, end: st + s.length, line: lines[li] });
            cursor = st + s.length;
        }
        if (!ranges.length) return false;
        // 3) 按区间重建为逐句块：文本原子按交集切片复制，内联元素原子按其起点归属唯一一句（克隆），<br> 作分隔丢弃。
        const frag = document.createDocumentFragment();
        ranges.forEach((rg) => {
            const span = document.createElement('span');
            span.className = 'nm-sentence';
            const label = document.createElement('span');
            label.className = 'nm-label';
            label.textContent = speakerLabel(rg.line);
            span.appendChild(label);
            const text = document.createElement('span');
            text.className = 'nm-text';
            atoms.forEach((a) => {
                if (a.kind === 'br') return;
                if (a.kind === 'text') {
                    const aEnd = a.start + a.len;
                    if (aEnd <= rg.start || a.start >= rg.end) return;
                    const from = Math.max(rg.start, a.start) - a.start;
                    const to = Math.min(rg.end, aEnd) - a.start;
                    text.appendChild(document.createTextNode(a.node.data.slice(from, to)));
                } else if (a.start >= rg.start && a.start < rg.end) {
                    text.appendChild(a.node.cloneNode(true));
                }
            });
            span.appendChild(text);
            frag.appendChild(span);
        });
        block.textContent = '';
        block.appendChild(frag);
        return true;
    }

    // 量出说话人列宽：取所有说话人名字的最长渲染宽（按 nm-label 字体），上限为正文宽度的 10%，并写入 CSS 变量。
    function computeMarkGutter() {
        if (!state.contentEl || !markedBlocks.length) return;
        const cs = window.getComputedStyle(state.contentEl);
        const innerWidth = state.contentEl.clientWidth
            - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0);
        const labels = new Set();
        state.lines.forEach((l) => { const s = speakerLabel(l); if (s) labels.add(s); });
        let canvas = computeMarkGutter._c || (computeMarkGutter._c = document.createElement('canvas'));
        const ctx = canvas.getContext('2d');
        ctx.font = '600 13px ' + (cs.fontFamily || 'serif');
        let max = 0;
        labels.forEach((s) => { max = Math.max(max, ctx.measureText(s).width); });
        const cap = Math.max(40, innerWidth * MARK_NAME_CAP_RATIO);
        const nameCol = Math.min(Math.ceil(max) + 2, Math.round(cap));
        state.contentEl.style.setProperty('--nm-name-col', nameCol + 'px');
        state.contentEl.style.setProperty('--nm-gutter', (nameCol + MARK_GAP) + 'px');
        enforceSentenceHeights();
    }

    // 说话人名字过长会在固定列宽内换成多行，而该句正文很短只占一行时，绝对定位的名字列会比句子高、
    // 向下溢出并与下一句左侧重叠。为这类句子按名字实际渲染高度补一个 min-height，把这一行撑到能放下左侧说话人。
    // 名字列宽由上面写入的 CSS 变量决定，故必须在变量写入后量；resize / 切换语言重算列宽时也会随之复算。
    function enforceSentenceHeights() {
        if (!markedBlocks.length) return;
        const sentences = [];
        markedBlocks.forEach((m) => {
            m.el.querySelectorAll('.nm-sentence').forEach((sent) => {
                if (sent.querySelector('.nm-label')) sentences.push(sent);
            });
        });
        if (!sentences.length) return;
        // 分三批读写，避免在长章节里逐句交替读 offsetHeight / 写 style 触发反复重排：
        // 先清空上次补高（写），再统一量名字与句子高度（读），最后只给名字更高的句子补 min-height（写）。
        sentences.forEach((sent) => { sent.style.minHeight = ''; });
        const fixes = sentences.map((sent) => {
            const labelH = sent.querySelector('.nm-label').offsetHeight;
            return labelH > sent.offsetHeight ? labelH : 0;
        });
        sentences.forEach((sent, i) => { if (fixes[i]) sent.style.minHeight = fixes[i] + 'px'; });
    }

    function clearMarks() {
        if (markedBlocks.length) {
            markedBlocks.forEach((m) => { m.el.innerHTML = m.html; });
            markedBlocks = [];
        }
        if (state.contentEl) {
            state.contentEl.classList.remove('narration-marks-on');
            state.contentEl.style.removeProperty('--nm-gutter');
            state.contentEl.style.removeProperty('--nm-name-col');
        }
    }

    function applyMarks() {
        if (!state.showSpeakers || !state.active || !state.scriptLoaded) return;
        if (!state.blocks.length) state.blocks = buildBlocks();
        if (!state.lines.length || !state.blocks.length) return;
        const byBlock = new Map();
        state.lines.forEach((l) => {
            if (l.paragraphIndex >= 0 && l.paragraphIndex < state.blocks.length) {
                if (!byBlock.has(l.paragraphIndex)) byBlock.set(l.paragraphIndex, []);
                byBlock.get(l.paragraphIndex).push(l);
            }
        });
        byBlock.forEach((lns, idx) => {
            const el = state.blocks[idx];
            if (!el || el.tagName !== 'P') return; // 章节标题只随正文缩进、不加逐句标注
            const html = el.innerHTML;
            if (markBlock(el, lns)) markedBlocks.push({ el, html });
        });
        if (!markedBlocks.length) return; // 整章都对不齐（如正文为译文）：不缩进、不加类，保持原样
        state.contentEl.classList.add('narration-marks-on');
        computeMarkGutter();
    }

    function renderMarks() { clearMarks(); applyMarks(); }

    function setShowSpeakers(on) {
        state.showSpeakers = !!on;
        lsSet(LS.showSpeakers, state.showSpeakers ? '1' : '0');
        renderMarks();
    }

    function bindResize() {
        if (markResizeBound) return;
        markResizeBound = true;
        let rt = null;
        window.addEventListener('resize', () => {
            if (!markedBlocks.length) return;
            if (rt) clearTimeout(rt);
            rt = setTimeout(computeMarkGutter, 150);
        });
    }

    function hasMarkedBlocks() { return markedBlocks.length > 0; }


            ctx.marks = { buildBlocks, speakerLabel, clearMarks, renderMarks, setShowSpeakers, bindResize, hasMarkedBlocks };
        }
    };
})(window);
