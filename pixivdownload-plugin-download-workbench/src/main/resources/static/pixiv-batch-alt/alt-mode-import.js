'use strict';

/* ============================================================
   批量导入单作品模式
   ============================================================ */
function renderImportMode(panel) {
    const def = AB_MODES[1];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton()]));

    const sources = el('div', 'ab-import-sources');
    sources.appendChild(el('span', 'ab-muted', bt('import.sources', '支持的数据来源：')));
    altSourcesForMode('single-import').forEach(src => {
        sources.appendChild(el('span', 'ab-pill ab-pill--brand', src.label));
    });
    panel.appendChild(sources);

    const composer = el('div', 'ab-composer card');
    const textarea = el('textarea', 'ab-input ab-import-input');
    textarea.id = 'abImportInput';
    textarea.setAttribute('aria-label', bt('modes.import', '批量导入'));
    textarea.setAttribute('aria-describedby', 'abImportResult');
    textarea.rows = 5;
    textarea.spellcheck = false;
    textarea.placeholder = bt('batch:input.single-import.placeholder',
        '粘贴插画/漫画/动图/小说单作品链接列表，兼容 One-Tab，N-Tab 等标签页管理插件导出格式...');
    composer.appendChild(textarea);
    composer.appendChild(el('p', 'ab-field-note', bt('import.id-hint', '数字 ID 默认为插画；小说请粘贴完整链接，或在 ID 前另起一行写 novel:。')));

    const help = el('details', 'ab-import-help');
    help.appendChild(el('summary', '', bt('import.format.title', '导入格式说明')));
    const list = el('ul', 'ab-note-list');
    [
        bt('batch:label.import-format', '导入格式：') + ' url | title '
            + bt('batch:label.import-format-or', '或') + ' id | title',
        bt('batch:label.import-example', '每行一条，例如：')
            + bt('batch:label.import-example-value', 'https://www.pixiv.net/artworks/12345678 | 示例标题'),
        bt('batch:label.import-bare-id-example', '仅 ID 示例：')
            + bt('batch:label.import-bare-id-example-value', '12345678 | 示例标题'),
        bt('batch:hint.import-bare-id', '仅写数字 ID 时默认按插画解析（等同于 https://www.pixiv.net/artworks/{id}）；若需要按小说解析，请在该行之前加一行 <code>novel:</code> 作为区段头。'),
        bt('batch:hint.import-section-header', '区段头 <code>artwork:</code> / <code>novel:</code> 单独成行（大小写不敏感，全/半角冒号均可）；其下方的所有「仅 ID / id | title」按该类型解析，直到遇到下一个区段头或文本结束。明确的链接始终按链接自身类型解析，与所在区段无关。'),
        bt('batch:hint.import-title-optional', '标题可留空；下载前会自动获取真实标题。兼容 One-Tab，N-Tab 等标签页管理插件导出格式。'),
        bt('batch:hint.import-reimport', '也兼容下方“导出全部”“导出未下载”按钮生成的作品列表，可直接重新导入。')
    ].forEach(text => {
        const li = el('li');
        li.textContent = text.replace(/<\/?code>/g, '');
        list.appendChild(li);
    });
    help.appendChild(list);
    // 取得侧导入示例槽位：作品类型插件经 queueTypes 贡献各自来源的链接示例
    //（如来源插件自己的 URL 示例），与旧布局 import-hint 槽位同契约；插件禁用时缺席。
    const importHintSlot = document.createElement('template');
    importHintSlot.setAttribute('data-qt-slot', 'import-hint');
    help.appendChild(importHintSlot);
    composer.appendChild(help);

    const actions = el('div', 'ab-composer-actions');
    const importBtn = el('button', 'ab-btn ab-btn--primary');
    importBtn.id = 'abBtnImport';
    importBtn.type = 'button';
    importBtn.appendChild(abIconEl('plus'));
    importBtn.appendChild(el('span', '', bt('import.enqueue', '导入并加入队列')));
    importBtn.addEventListener('click', () => runImportParse(false));
    const freshBtn = el('button', 'ab-btn ab-btn--danger-ghost');
    freshBtn.type = 'button';
    freshBtn.appendChild(abIconEl('refresh'));
    freshBtn.appendChild(el('span', '', bt('import.reimport', '清空队列后重新导入')));
    freshBtn.addEventListener('click', async () => {
        if (!await abConfirm('dialog.confirm-reparse', '确认清除当前队列并重新解析？')) return;
        runImportParse(true);
    });
    actions.appendChild(importBtn);
    help.appendChild(freshBtn);
    composer.appendChild(actions);

    const result = el('div', 'ab-import-result');
    result.id = 'abImportResult';
    const announcement = el('p', 'ab-sr-only');
    announcement.id = 'abImportAnnouncement';
    announcement.setAttribute('role', 'status');
    panel.appendChild(announcement);
    composer.appendChild(result);
    panel.appendChild(composer);
}

// 解析语义与 batch single-import 对齐：区段头 / 显式 URL 优先 / 裸 ID 按区段。
function parseImportText(text) {
    const contributed = altParseImportText(text);
    if (contributed) return contributed;
    const lines = String(text || '').split('\n').map(l => l.trim()).filter(Boolean);
    const bareIdRegex = /^(\d+)\s*(?:\|\s*(.*))?$/;
    const sectionHeaderRegex = /^([A-Za-z]+)\s*[:：]\s*$/;
    const urlMatchers = [
        {kind: 'illust', re: /https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/},
        {kind: 'novel', re: /https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=(\d+)/}
    ];
    let section = 'illust';
    const items = [];
    let skippedUnavailable = 0;
    const rejected = [];
    for (const ln of lines) {
        const head = ln.match(sectionHeaderRegex);
        if (head) {
            const token = head[1].toLowerCase();
            if (token === 'artwork' || token === 'illust') {
                section = 'illust';
            } else if (token === 'novel') {
                section = 'novel';
            } else {
                section = null;
            }
            continue;
        }
        let matched = null;
        for (const m of urlMatchers) {
            const hit = ln.match(m.re);
            if (hit) {
                if (matched) { matched = 'ambiguous'; break; }
                matched = {kind: m.kind, id: hit[1]};
            }
        }
        if (matched === 'ambiguous') {
            rejected.push(ln);
            continue;
        }
        if (matched) {
            const titleRaw = (ln.split('|')[1] || '').trim();
            items.push({id: matched.id, kind: matched.kind, title: titleRaw});
            continue;
        }
        const bare = ln.match(bareIdRegex);
        if (bare) {
            if (!section) {
                skippedUnavailable++;
                continue;
            }
            items.push({id: bare[1], kind: section, title: (bare[2] || '').trim()});
            continue;
        }
        if (/^https?:\/\//.test(ln)) rejected.push(ln);
    }
    // 按 id+kind 去重
    const seen = new Set();
    const unique = items.filter(item => {
        const key = item.kind + ':' + item.id;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
    return {items: unique, skippedUnavailable, rejected};
}

function runImportParse(clearFirst) {
    const textarea = document.getElementById('abImportInput');
    const result = document.getElementById('abImportResult');
    if (!textarea || !result) return;
    const announce = message => {
        const live = document.getElementById('abImportAnnouncement');
        if (live) live.textContent = message;
    };
    const parsed = parseImportText(textarea.value);
    importState.parsed = parsed.items;
    result.innerHTML = '';
    if (!parsed.items.length) {
        const msg = parsed.skippedUnavailable > 0
            ? bt('status.single-import-skipped-unavailable', '已跳过 {count} 个：所属作品类型当前不可用', {count: parsed.skippedUnavailable})
            : parsed.rejected.length
                ? bt('status.single-import-ambiguous', '已拒绝 {count} 个归属不明确的单作品输入', {count: parsed.rejected.length})
                : bt('status.single-import-none', '未解析到任何单作品链接');
        result.appendChild(el('p', 'ab-import-summary ab-import-summary--error', msg));
        announce(msg);
        textarea.setAttribute('aria-invalid', 'true');
        return;
    }
    const summary = el('p', 'ab-import-summary');
    summary.textContent = bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
        {total: parsed.items.length, added: parsed.items.filter(i => !queueHas(i.id)).length});
    result.appendChild(summary);
    announce(summary.textContent);
    textarea.removeAttribute('aria-invalid');
    const preview = el('div', 'ab-import-preview');
    parsed.items.slice(0, 60).forEach(item => {
        const row = el('div', 'ab-import-row');
        row.appendChild(abIconEl(item.kind === 'novel' ? 'book' : 'image'));
        row.appendChild(el('span', 'ab-import-id', String(item.id)));
        row.appendChild(el('span', 'ab-import-title',
            item.title || bt('import.auto-title', '（下载前自动获取标题）')));
        const inQueue = queueHas(item.id);
        if (inQueue) row.appendChild(el('span', 'ab-pill ab-pill--ok', bt('queue.toast.in-queue', '已在队列中')));
        preview.appendChild(row);
    });
    if (parsed.items.length > 60) {
        preview.appendChild(el('p', 'ab-muted',
            bt('import.preview-more', '…以及另外 {count} 个', {count: parsed.items.length - 60})));
    }
    result.appendChild(preview);
    commitImport(!!clearFirst);
}

function commitImport(clearFirst) {
    if (clearFirst) stopAndClear();
    const items = importState.parsed;
    if (!items.length) return;
    let added = 0;
    const groups = new Map();
    items.forEach(item => {
        const source = item.source || SINGLE_IMPORT_MODE;
        if (!groups.has(source)) groups.set(source, []);
        groups.get(source).push(item);
    });
    groups.forEach((group, source) => {
        added += addItemsToQueue(group.map(item => item.id),
            group.map(item => buildQueueMeta(item, item.kind, {})), source);
    });
    abToast('success', bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
        {total: items.length, added}));
}
