'use strict';
    function parseSingleImport() {
        const text = document.getElementById('single-import-textarea').value;
        const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
        const qt = window.PixivBatch.queueTypes;
        // 裸 ID（可选 `| title`）。
        const bareIdRegex = /^(\d+)\s*(?:\|\s*(.*))?$/;
        // 区段头：整行就是 `单词:`（大小写不敏感、全/半角冒号），之后直到下一个区段头或结束，裸 ID 按该区段类型解析。
        const sectionHeaderRegex = /^([A-Za-z]+)\s*[:：]\s*$/;
        // acquisitionModes=single-import 且模块成功激活的类型才会出现在这里；未声明的 import hook
        // 已由 queueTypes runtime 隔离，宿主不会看见或调用。
        const importTypes = qt.contributionsOf('import');
        const bareDefaults = importTypes.filter(type => type.bareDefault === true);
        const importTypeFor = token => importTypes.find(t => {
            const names = [t.sectionType].concat(Array.isArray(t.sectionAliases) ? t.sectionAliases : []);
            return names.some(name => String(name || '').toLowerCase() === token.toLowerCase());
        }) || null;
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
        // 区段头命名了当前不可用 / 未知类型时的哨兵：唯一 Symbol，绝不与任何类型 id 相撞。
        const UNAVAILABLE_SECTION = Symbol('unavailable-section');
        // 按后端贡献顺序聚合解析结果。
        const buckets = new Map();   // type -> {source, items}
        const bucket = (type, source) => {
            let b = buckets.get(type);
            if (!b) { b = {source, items: []}; buckets.set(type, b); }
            return b;
        };
        let currentSection = bareDefaults.length === 1 ? bareDefaults[0] : UNAVAILABLE_SECTION;
        let currentSectionExplicit = false;
        let skippedUnavailable = 0;
        let skippedAmbiguous = 0;
        for (const ln of lines) {
            const head = ln.match(sectionHeaderRegex);
            if (head) {
                const token = head[1].toLowerCase();
                currentSection = importTypeFor(token) || UNAVAILABLE_SECTION;
                currentSectionExplicit = true;
                continue;
            }
            // 显式 URL 始终按其自身类型解析，无视所在区段。全部 matcher 都要执行；多个
            // 类型同时认领时拒绝该行，不能让后端排序决定谁抢到成熟 URL 语义。
            const urlMatches = [];
            for (const it of importTypes) {
                try {
                    const match = it.matchUrl ? it.matchUrl(ln) : null;
                    if (match != null) urlMatches.push({type: it, match});
                } catch (e) {
                    console.warn('[singleImport] 单作品解析钩子失败：', it.type, e);
                }
            }
            if (urlMatches.length > 1) {
                skippedAmbiguous++;
                continue;
            }
            if (urlMatches.length === 1) {
                const matched = urlMatches[0];
                try {
                    const titleRaw = (ln.split('|')[1] || '').trim();
                    const item = buildImportItem(matched.type, matched.match, titleRaw, ln);
                    if (item) bucket(matched.type.type, item.source || matched.type.source).items.push(item);
                } catch (e) {
                    console.warn('[singleImport] 单作品构造钩子失败：', matched.type.type, e);
                }
                continue;
            }
            // 裸 ID / `id | title`：显式区段按区段解析；无区段头时只允许唯一 bareDefault。
            const bare = ln.match(bareIdRegex);
            if (bare) {
                const id = bare[1];
                const titleRaw = (bare[2] || '').trim();
                if (currentSection === UNAVAILABLE_SECTION) {
                    if (!currentSectionExplicit && bareDefaults.length > 1) skippedAmbiguous++;
                    else skippedUnavailable++;
                } else {
                    try {
                        const item = buildImportItem(currentSection, id, titleRaw, ln);
                        if (item) bucket(currentSection.type, item.source || currentSection.source).items.push(item);
                    } catch (e) {
                        console.warn('[singleImport] 单作品构造钩子失败：', currentSection.type, e);
                    }
                }
            }
        }
        // 各桶去重后按后端贡献顺序入队。
        const orderedTypes = importTypes.map(t => t.type);
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
                skippedAmbiguous > 0
                    ? bt('status.single-import-ambiguous',
                        '已拒绝 {count} 个归属不明确的单作品输入', {count: skippedAmbiguous})
                    : skippedUnavailable > 0
                    ? bt('status.single-import-skipped-unavailable',
                        '已跳过 {count} 个：所属作品类型当前不可用', {count: skippedUnavailable})
                    : bt('status.single-import-none', '未解析到任何单作品链接'),
                (skippedAmbiguous > 0 || skippedUnavailable > 0) ? 'warning' : 'error');
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
