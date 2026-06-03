'use strict';
    function parseSingleImport() {
        const text = document.getElementById('single-import-textarea').value;
        const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
        const illustRegex = /https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/;
        const novelRegex = /https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=(\d+)/;
        // 区段头：整行就是 `artwork:` 或 `novel:`，大小写不敏感、支持全/半角冒号；之后直到下一个区段头或结束，裸 ID 按此类型解析。
        const sectionHeaderRegex = /^(artwork|novel)\s*[:：]\s*$/i;
        // 裸 ID（可选 `| title`）。
        const bareIdRegex = /^(\d+)\s*(?:\|\s*(.*))?$/;
        let illustItems = [];
        const novelItems = [];
        let currentKind = 'illust';
        for (const ln of lines) {
            const head = ln.match(sectionHeaderRegex);
            if (head) {
                currentKind = head[1].toLowerCase() === 'novel' ? 'novel' : 'illust';
                continue;
            }
            // 显式 URL 始终按其自身类型解析，无视所在区段。
            const n = ln.match(novelRegex);
            if (n) {
                const novelId = n[1];
                const titleRaw = (ln.split('|')[1] || '').trim();
                novelItems.push({
                    id: 'n' + novelId,
                    novelId,
                    kind: 'novel',
                    title: titleRaw || bt('queue.novel-fallback', '小说 {id}', {id: novelId})
                });
                continue;
            }
            const m = ln.match(illustRegex);
            if (m) {
                const id = m[1];
                const titleRaw = (ln.split('|')[1] || '').trim();
                illustItems.push({id, title: titleRaw || bt('queue.artwork-fallback', '作品 {id}', {id})});
                continue;
            }
            // 裸 ID / `id | title`：默认按当前区段（无区段头时为插画）解析。
            const bare = ln.match(bareIdRegex);
            if (bare) {
                const id = bare[1];
                const titleRaw = (bare[2] || '').trim();
                if (currentKind === 'novel') {
                    novelItems.push({
                        id: 'n' + id,
                        novelId: id,
                        kind: 'novel',
                        title: titleRaw || bt('queue.novel-fallback', '小说 {id}', {id})
                    });
                } else {
                    illustItems.push({id, title: titleRaw || bt('queue.artwork-fallback', '作品 {id}', {id})});
                }
            }
        }
        illustItems = dedupeQueueItems(illustItems);
        const dedupedNovelItems = dedupeQueueItems(novelItems);
        if (!illustItems.length && !dedupedNovelItems.length) {
            setStatus(bt('status.single-import-none', '未解析到任何单作品链接'), 'error');
            return;
        }
        const addedIllusts = illustItems.length
            ? addItemsToQueue(illustItems.map(x => x.id), illustItems, SINGLE_IMPORT_MODE, '') : 0;
        const addedNovels = dedupedNovelItems.length
            ? addItemsToQueue(dedupedNovelItems.map(x => x.id), dedupedNovelItems, SINGLE_IMPORT_NOVEL_SOURCE, '') : 0;
        const total = illustItems.length + dedupedNovelItems.length;
        const added = addedIllusts + addedNovels;
        setStatus(
            bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
                {total, added}),
            'success'
        );
    }

    function parseSingleImportFresh() {
        if (!uiConfirmKey('dialog.confirm-reparse', '确认清除当前队列并重新解析？')) return;
        stopAndClear();
        parseSingleImport();
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.singleImport = window.PixivBatch.modes.singleImport || {};
window.PixivBatch.modes.singleImport = Object.assign(window.PixivBatch.modes.singleImport, { parseSingleImport, parseSingleImportFresh });
