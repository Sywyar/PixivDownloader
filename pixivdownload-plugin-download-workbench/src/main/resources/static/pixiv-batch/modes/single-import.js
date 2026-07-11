'use strict';
    function parseSingleImport() {
        const text = document.getElementById('single-import-textarea').value;
        const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
        const qt = window.PixivBatch.queueTypes;
        const illustRegex = /https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/;
        // 裸 ID（可选 `| title`）。
        const bareIdRegex = /^(\d+)\s*(?:\|\s*(.*))?$/;
        // 旧 single-import 支持过的小说单作品链接；当小说类型不可用时跳过并计入 unavailable。
        // 其它不支持的 Pixiv URL 保持旧行为：静默忽略，最终按未解析到单作品链接提示。
        const unavailableSingleWorkUrlRegex = /^https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=\d+/i;
        // 区段头：整行就是 `单词:`（大小写不敏感、全/半角冒号），之后直到下一个区段头或结束，裸 ID 按该区段类型解析。
        const sectionHeaderRegex = /^([A-Za-z]+)\s*[:：]\s*$/;
        // 各**可用**作品类型贡献的导入解析（区段头关键字 / 链接匹配 / 入队项构造 / 队列 source）。
        // 不可用类型不在其中——其区段头被识别为未知区段（裸 ID 跳过、计数）。
        // 链接侧只对旧支持的小说单作品 URL 做 unavailable 兼容；其它不支持 URL 保持旧的无匹配行为。
        // 插画为内置类型（无 import 钩子）。
        const importTypes = qt.contributionsOf('import');
        const importTypeFor = token =>
            importTypes.find(t => String(t.sectionType || '').toLowerCase() === token.toLowerCase()) || null;
        const normalizeImportMatch = match => {
            if (match && typeof match === 'object') {
                const id = match.id ?? match.workId ?? match.value;
                return id == null ? null : Object.assign({}, match, {id: String(id)});
            }
            return {id: String(match)};
        };
        const buildImportItem = (it, match, titleRaw, line) => {
            if (!it.buildItem) return null;
            const structuredMatch = match && typeof match === 'object';
            const normalized = normalizeImportMatch(match);
            if (!normalized) return null;
            return (structuredMatch || it.buildItem.length >= 3)
                ? it.buildItem(normalized, titleRaw, line)
                : it.buildItem(String(normalized.id), titleRaw);
        };
        // 区段头命名了一个当前不可用 / 未知类型时的哨兵区段：唯一 Symbol，绝不与任何字符串区段类型相撞。
        const UNAVAILABLE_SECTION = Symbol('unavailable-section');
        // 按类型聚合解析结果；保持「插画在前、其余类型按贡献序在后」的旧入队顺序。
        const buckets = new Map();   // type -> {source, items}
        const bucket = (type, source) => {
            let b = buckets.get(type);
            if (!b) { b = {source, items: []}; buckets.set(type, b); }
            return b;
        };
        let currentSection = 'illust';   // 区段头决定裸 ID 的解析类型，默认插画
        let skippedUnavailable = 0;
        for (const ln of lines) {
            const head = ln.match(sectionHeaderRegex);
            if (head) {
                const token = head[1].toLowerCase();
                if (token === 'artwork') currentSection = 'illust';
                else if (importTypeFor(token)) currentSection = token;
                else currentSection = UNAVAILABLE_SECTION; // 未知 / 不可用类型区段：其下裸 ID 跳过、不误判为插画
                continue;
            }
            // 显式 URL 始终按其自身类型解析，无视所在区段。先试各可用类型贡献的链接匹配。
            let matchedUrl = false;
            for (const it of importTypes) {
                const match = it.matchUrl ? it.matchUrl(ln) : null;
                if (match != null) {
                    const titleRaw = (ln.split('|')[1] || '').trim();
                    const item = buildImportItem(it, match, titleRaw, ln);
                    if (item) {
                        bucket(it.type, item.source || it.source).items.push(item);
                        matchedUrl = true;
                    }
                    break;
                }
            }
            if (matchedUrl) continue;
            const m = ln.match(illustRegex);
            if (m) {
                const id = m[1];
                const titleRaw = (ln.split('|')[1] || '').trim();
                bucket('illust', SINGLE_IMPORT_MODE).items.push({id, title: titleRaw || bt('queue.artwork-fallback', '作品 {id}', {id})});
                continue;
            }
            // 旧支持的小说单作品 URL 在小说类型不可用时跳过并计数；其它 Pixiv URL 保持旧的无匹配行为。
            if (unavailableSingleWorkUrlRegex.test(ln)) {
                skippedUnavailable++;
                continue;
            }
            // 裸 ID / `id | title`：默认按当前区段（无区段头时为插画）解析。
            const bare = ln.match(bareIdRegex);
            if (bare) {
                const id = bare[1];
                const titleRaw = (bare[2] || '').trim();
                if (currentSection === 'illust') {
                    bucket('illust', SINGLE_IMPORT_MODE).items.push({id, title: titleRaw || bt('queue.artwork-fallback', '作品 {id}', {id})});
                } else if (currentSection === UNAVAILABLE_SECTION) {
                    skippedUnavailable++;   // 区段头命名了不可用类型：跳过、不入队、不报错
                } else {
                    const it = importTypeFor(currentSection);
                    if (it) {
                        const item = buildImportItem(it, id, titleRaw, ln);
                        if (item) bucket(it.type, item.source || it.source).items.push(item);
                    }
                    else skippedUnavailable++;
                }
            }
        }
        // 各桶去重后按「插画 → 各贡献类型」顺序入队（保持旧队列顺序）。
        const orderedTypes = ['illust'].concat(importTypes.map(t => t.type));
        let total = 0;
        let added = 0;
        orderedTypes.forEach(type => {
            const b = buckets.get(type);
            if (!b) return;
            const deduped = dedupeQueueItems(b.items);
            if (!deduped.length) return;
            total += deduped.length;
            added += addItemsToQueue(deduped.map(x => x.id), deduped, b.source, '');
        });
        if (!total) {
            setStatus(
                skippedUnavailable > 0
                    ? bt('status.single-import-skipped-unavailable',
                        '已跳过 {count} 个：所属作品类型当前不可用', {count: skippedUnavailable})
                    : bt('status.single-import-none', '未解析到任何单作品链接'),
                skippedUnavailable > 0 ? 'warning' : 'error');
            return;
        }
        setStatus(
            bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
                {total, added}),
            'success'
        );
    }

    async function parseSingleImportFresh() {
        if (!await uiConfirmKey('dialog.confirm-reparse', '确认清除当前队列并重新解析？')) return;
        stopAndClear();
        parseSingleImport();
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.singleImport = window.PixivBatch.modes.singleImport || {};
window.PixivBatch.modes.singleImport = Object.assign(window.PixivBatch.modes.singleImport, { parseSingleImport, parseSingleImportFresh });
