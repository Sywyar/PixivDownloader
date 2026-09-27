'use strict';

/* ============================================================
   系列下载模式
   ============================================================ */
function renderSeriesMode(panel) {
    const def = AB_MODES[4];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('series');
    if (!sources.some(source => source.id === seriesState.source)) seriesState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, seriesState.source, source => {
            seriesState.source = source;
            seriesState.kind = (altTypesForSource('series', source)[0] || {}).type || 'illust';
            seriesState.info = null;
            seriesState.rawItems = [];
            renderStage();
        }));
    }
    const typeOptions = altTypesForSource('series', seriesState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    if (!typeOptions.some(option => option[0] === seriesState.kind)) {
        seriesState.kind = typeOptions[0] ? typeOptions[0][0] : 'illust';
    }
    if (typeOptions.length > 1) {
        panel.appendChild(smallSeg(typeOptions, seriesState.kind, kind => {
            seriesState.kind = kind;
            seriesState.info = null;
            seriesState.rawItems = [];
            renderSeriesStage();
        }));
    }

    const composer = el('div', 'ab-composer card');
    const row = el('div', 'ab-composer-row');
    const input = el('input', 'ab-input');
    input.id = 'abSeriesInput';
    input.type = 'text';
    input.placeholder = bt('series.input.placeholder', '粘贴系列 / 合集 / 关联作品链接');
    input.value = seriesState.url || '';
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter') loadSeries(1);
    });
    const loadBtn = el('button', 'ab-btn ab-btn--primary');
    loadBtn.type = 'button';
    loadBtn.appendChild(abIconEl('layers'));
    loadBtn.appendChild(el('span', '', bt('series.load', '读取系列')));
    loadBtn.addEventListener('click', () => loadSeries(1));
    row.appendChild(input);
    row.appendChild(loadBtn);
    composer.appendChild(row);
    const browserAcquisition = altQueueTypes().acquisitionList('series')
        .find(item => item.browser
            && altTypesForSource('series', seriesState.source).some(type => type.type === item.type));
    if (browserAcquisition) {
        const browseBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
        browseBtn.type = 'button';
        browseBtn.appendChild(abIconEl('folder'));
        browseBtn.appendChild(el('span', '', bt('series.browser.open', '浏览可用合集')));
        browseBtn.addEventListener('click', () => openSeriesBrowser(browserAcquisition));
        const actions = el('div', 'ab-composer-actions');
        actions.appendChild(browseBtn);
        composer.appendChild(actions);
    }
    panel.appendChild(composer);

    const stage = el('div');
    stage.id = 'abSeriesStage';
    panel.appendChild(stage);
    renderSeriesStage();
}

// 解析系列链接：插画系列 / 小说系列 / 关联作品（取其 seriesId）
function parseSeriesUrl(raw) {
    const text = String(raw || '').trim();
    if (!text) return null;
    let m = text.match(/pixiv\.net\/user\/\d+\/series\/(\d+)/) || text.match(/pixiv\.net\/series\/(\d+)/);
    if (m) return {kind: 'illust', id: m[1]};
    m = text.match(/pixiv\.net\/novel\/series\/(\d+)/);
    if (m) return {kind: 'novel', id: m[1]};
    m = text.match(/pixiv\.net\/novel\/show\.php\?[^\s]*?series_id=(\d+)/);
    if (m) return {kind: 'novel', id: m[1]};
    if (/^\d+$/.test(text)) return {kind: seriesState.kind || 'illust', id: text};
    return null;
}

async function openSeriesBrowser(acquisition) {
    const browser = acquisition.browser;
    const body = el('div', 'ab-collection-grid');
    const lease = altQueueTypes().acquisitionLease(acquisition.type, 'series');
    const session = {};
    seriesState.browserSession = session;
    const cursors = new Map([[1, browser.initialCursor ?? null]]);
    let sequence = 0;
    openDrawer({
        id: 'series-browser', icon: 'folder',
        title: typeof browser.title === 'function' ? browser.title() : bt('series.browser.open', '浏览可用合集'),
        body, footer: null,
        beforeClose: () => { if (seriesState.browserSession === session) seriesState.browserSession = null; return true; }
    });
    async function showPage(pageNumber) {
        const request = ++sequence;
        body.replaceChildren(el('p', 'ab-loading-line',
            typeof browser.loadingLabel === 'function' ? browser.loadingLabel() : bt('common.loading', '加载中…')));
        try {
            lease.assertCurrent();
            const context = {cursor: cursors.get(pageNumber), limit: browser.pageSize, page: pageNumber};
            const data = await altAcquisitionJson(acquisition.type, 'series',
                browser.buildPageRequest(context), 'browser', context);
            lease.assertCurrent();
            if (seriesState.browserSession !== session || request !== sequence) return;
            const page = browser.readPage(data);
            body.replaceChildren();
            if (!page.items.length) body.appendChild(el('p', 'ab-empty-line',
                typeof browser.emptyLabel === 'function' ? browser.emptyLabel() : bt('series.status.empty', '该系列没有可用条目')));
            page.items.forEach(item => {
                const button = el('button', 'ab-collection-card card');
                button.type = 'button';
                button.appendChild(abIconEl('folder'));
                button.appendChild(el('span', 'ab-collection-meta', browser.itemLabel(item)));
                button.addEventListener('click', async () => {
                    button.disabled = true;
                    try {
                        lease.assertCurrent();
                        const selected = await browser.select(item);
                        lease.assertCurrent();
                        if (seriesState.browserSession !== session || request !== sequence) return;
                        seriesState.source = acquisition.dataSource.id;
                        seriesState.kind = acquisition.type;
                        seriesState.url = String(selected.seriesId);
                        seriesState.browserSelection = {type: acquisition.type, parsed: selected};
                        closeDrawer();
                        renderStage();
                        await loadSeries(1);
                    } catch (error) {
                        abToast('error', String(error && error.message || bt('common.request-failed', '请求失败')));
                    } finally { button.disabled = false; }
                });
                body.appendChild(button);
            });
            if (page.hasMore) cursors.set(pageNumber + 1, altNextCursor(page, context.cursor, true));
            const controls = el('div', 'ab-composer-actions');
            for (const [step, key, fallback] of [[-1, 'common.prev', '上一页'], [1, 'common.next', '下一页']]) {
                const button = el('button', 'ab-btn ab-btn--ghost', bt(key, fallback));
                button.type = 'button';
                button.disabled = step < 0 ? pageNumber === 1 : !page.hasMore;
                button.addEventListener('click', () => showPage(pageNumber + step));
                controls.appendChild(button);
            }
            body.appendChild(controls);
        } catch (error) {
            if (seriesState.browserSession !== session || request !== sequence) return;
            body.replaceChildren(errorBox(String(error && error.message || bt('common.request-failed', '请求失败')),
                () => showPage(pageNumber)));
        }
    }
    await showPage(1);
}

async function loadSeries(page) {
    const input = document.getElementById('abSeriesInput');
    const raw = input ? input.value : seriesState.url;
    seriesState.url = raw;
    const candidates = altQueueTypes().acquisitionList('series')
        .filter(item => altTypesForSource('series', seriesState.source).some(type => type.type === item.type));
    const selected = seriesState.browserSelection && seriesState.browserSelection.type === seriesState.kind
        && String(seriesState.browserSelection.parsed.seriesId) === String(raw)
        ? {acquisition: candidates.find(item => item.type === seriesState.kind), parsed: seriesState.browserSelection.parsed}
        : null;
    const matches = selected && selected.acquisition ? [selected] : candidates.map(acquisition => {
        try {
            const parsed = acquisition.parseUrl(raw);
            return parsed ? {acquisition, parsed} : null;
        } catch (e) {
            return null;
        }
    }).filter(Boolean);
    if (matches.length !== 1) {
        seriesState.error = bt('series.status.no-url', '请先粘贴有效的系列链接');
        renderSeriesStage();
        return;
    }
    const acquisition = matches[0].acquisition;
    const parsed = matches[0].parsed;
    seriesState.kind = acquisition.type;
    seriesState.loading = true;
    seriesState.error = '';
    renderSeriesStage();
    try {
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'series');
        let seriesId = parsed.seriesId ?? parsed.id;
        if (seriesId == null && parsed.resolveWorkId != null && typeof acquisition.resolveSeriesId === 'function') {
            seriesId = await acquisition.resolveSeriesId(parsed.resolveWorkId, {signal: lease.signal});
            lease.assertCurrent();
        }
        if (seriesId == null) throw new Error(bt('series.status.no-url', '请先粘贴有效的系列链接'));
        if (page === 1) {
            const firstCursor = typeof acquisition.initialCursor === 'function'
                ? acquisition.initialCursor(seriesId) : acquisition.initialCursor ?? null;
            seriesState.cursors = new Map([[1, firstCursor]]);
        }
        const cursor = seriesState.cursors?.get(page) ?? null;
        if (page > 1 && acquisition.initialCursor != null && cursor == null) {
            throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
        }
        const context = {
            seriesId, seriesTitle: seriesState.info && seriesState.info.title || '', page,
            cursor, limit: Number(acquisition.pageSize) || 12
        };
        const spec = acquisition.apiPath(seriesId, page, context);
        let data = await altAcquisitionJson(acquisition.type, 'series', spec, 'page', context);
        if (typeof acquisition.normalizePage === 'function') data = acquisition.normalizePage(data, context);
        const info = data.series || {};
        seriesState.info = Object.assign({}, info, {
            seriesId,
            title: info.title || context.seriesTitle || String(seriesId),
            total: Number(info.total ?? data.total ?? 0)
        });
        const queueContext = {
            seriesId, seriesTitle: seriesState.info.title,
            orderOffset: (page - 1) * context.limit,
            seriesAuthorId: seriesState.info.authorId,
            seriesAuthorName: seriesState.info.authorName
        };
        seriesState.rawItems = normalizeAcquisitionItems(data.items || [], acquisition, queueContext, 'series');
        seriesState.page = Number(data.page || page);
        seriesState.isLastPage = data.isLastPage === true || data.hasMore === false;
        if (data.hasMore === true) seriesState.cursors.set(page + 1, altNextCursor(data, cursor, true));
    } catch (e) {
        seriesState.info = null;
        seriesState.rawItems = [];
        seriesState.page = page;
        seriesState.isLastPage = true;
        seriesState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    seriesState.loading = false;
    await applySeriesFilters();
}

async function applySeriesFilters() {
    const seq = ++searchState.filterSeq;
    const result = await computeFilteredItems(seriesState.rawItems, extraFilters, seriesState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    seriesState.items = result.filtered;
    seriesState.filterSummary = result.stats;
    renderSeriesStage();
}

function renderSeriesStage() {
    const stage = document.getElementById('abSeriesStage');
    if (!stage) return;
    stage.innerHTML = '';
    if (seriesState.loading) {
        stage.appendChild(loadingGrid(bt('series.status.loading-page', '正在加载该页…')));
        return;
    }
    if (seriesState.error && !seriesState.rawItems.length) {
        stage.appendChild(errorBox(seriesState.error, () => loadSeries(1)));
        return;
    }
    if (!seriesState.info) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('layers'));
        hint.appendChild(el('p', '', bt('series.status.no-url', '请先粘贴系列链接')));
        stage.appendChild(hint);
        return;
    }
    const info = seriesState.info;
    const head = el('div', 'ab-series-head card');
    const cover = el('span', 'ab-series-cover');
    applyThumbHue(cover, 's' + String(info.seriesId) + (info.title || ''));
    cover.appendChild(abIconEl('layers'));
    head.appendChild(cover);
    const meta = el('div', 'ab-series-meta');
    meta.appendChild(el('strong', 'ab-series-title', info.title || String(info.seriesId)));
    const sub = el('span', 'ab-muted');
    sub.textContent = summaryJoin([
        altTypeLabel(seriesState.kind),
        bt('series.info.author', '作者：{name}', {name: info.authorName || info.authorId || '-'}),
        bt('series.info.total', '共 {count} 个作品', {count: info.total ?? seriesState.rawItems.length})
    ]);
    meta.appendChild(sub);
    head.appendChild(meta);
    stage.appendChild(head);

    if (!seriesState.rawItems.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('layers'));
        empty.appendChild(el('p', '', bt('series.status.empty', '该系列没有可用条目')));
        stage.appendChild(empty);
        return;
    }

    const filterSummary = (seriesState.filterSummary && hasExtraSearchFilter())
        ? bt('user.summary.filtered', '附加筛选后 {count} 个', {count: seriesState.filterSummary.filteredCount})
        : '';
    stage.appendChild(enqueueBar({
        summary: bt('series.info.page', '第 {current} 页{last}', {
            current: seriesState.page,
            last: seriesState.isLastPage ? '' : ''
        }),
        filterSummary,
        pageEnabled: seriesState.items.length > 0,
        allEnabled: seriesState.rawItems.length > 0 || Number(info.total) > 0,
        onEnqueuePage: () => enqueueSeriesItems(seriesState.items),
        onEnqueueAll: enqueueSeriesAll
    }));
    stage.appendChild(worksGrid(seriesState.items, {
        source: 'series',
        seriesId: info.seriesId,
        seriesTitle: info.title,
        emptyText: bt('series.status.empty', '该系列没有可用条目')
    }));
    stage.appendChild(paginationBar({
        page: seriesState.page,
        totalPages: 0,
        total: info.total || 0,
        hasNext: !seriesState.isLastPage,
        onPage: p => loadSeries(p)
    }));
    syncAllResultsQueueState();
}

function enqueueSeriesItems(items) {
    return enqueueItems(items, null, {
        source: 'series',
        seriesId: seriesState.info.seriesId,
        seriesTitle: seriesState.info.title
    });
}

async function enqueueSeriesAll() {
    if (!await abConfirm('series.enqueue-all.confirm',
        '将抓取并加入该系列全部作品（已加载第 {page} 页）？', {page: seriesState.page})) return;
    const acquisition = altAcquisition('series', seriesState.source, seriesState.kind);
    if (!acquisition || !seriesState.info) return;
    const seriesId = seriesState.info.seriesId;
    const info = seriesState.info;
    const source = seriesState.source, kind = seriesState.kind;
    const isStale = () => seriesState.info !== info || source !== seriesState.source || kind !== seriesState.kind;
    const lease = altQueueTypes().acquisitionLease(acquisition.type, 'series');
    const all = [];
    let cursor = typeof acquisition.initialCursor === 'function'
        ? acquisition.initialCursor(seriesId) : acquisition.initialCursor ?? null;
    for (let page = 1; ; page++) {
        const context = {
            seriesId, seriesTitle: seriesState.info.title || '', page, cursor,
            limit: Number(acquisition.pageSize) || 12
        };
        let data;
        try {
            if (isStale()) return;
            lease.assertCurrent();
            data = await altAcquisitionJson(acquisition.type, 'series',
                acquisition.apiPath(seriesId, page, context), 'page', context);
            if (typeof acquisition.normalizePage === 'function') data = acquisition.normalizePage(data, context);
            lease.assertCurrent();
            if (isStale()) return;
        } catch (e) {
            abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
            return;
        }
        all.push(...normalizeAcquisitionItems(data.items || [], acquisition, {
            seriesId, seriesTitle: seriesState.info.title,
            orderOffset: all.length,
            seriesAuthorId: seriesState.info.authorId,
            seriesAuthorName: seriesState.info.authorName
        }, 'series'));
        const totalPages = Number(data.totalPages) > 0 ? Number(data.totalPages)
            : Math.ceil(Number(data.series?.total ?? data.total ?? info.total) / context.limit);
        const knownTotal = Number.isFinite(totalPages) && totalPages > 0;
        if (data.isLastPage === true || data.hasMore === false
            || (knownTotal ? page >= totalPages : !(data.items || []).length)) break;
        if (!knownTotal && page >= 1000) {
            abToast('error', bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
            return;
        }
        if (data.hasMore === true) {
            try { cursor = altNextCursor(data, cursor, true); }
            catch (error) {
                abToast('error', String(error && error.message || bt('common.request-failed', '请求失败')));
                return;
            }
        }
    }
    let result;
    try {
        result = await computeFilteredItems(all, extraFilters, acquisition.type, isStale);
        lease.assertCurrent();
    } catch (e) {
        if (!isStale()) abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
        return;
    }
    if (!result || isStale()) return;
    const added = enqueueSeriesItems(result.filtered);
    abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
}

/* ============================================================
   筛选变更 → 当前模式预览实时重滤
   ============================================================ */
async function applyFiltersToCurrentMode() {
    if (state.mode === QUICK_FETCH_MODE && (quickState.rawItems.length || quickState.drill)) {
        await applyQuickFilters();
    } else if (state.mode === 'user' && userState.rawItems.length) {
        await applyUserFilters();
    } else if (state.mode === 'series' && seriesState.rawItems.length) {
        await applySeriesFilters();
    } else if (state.mode === 'search' && searchState.rawResults.length) {
        await applySearchFilters();
    }
}

window.PixivBatchAlt.modes = Object.assign(window.PixivBatchAlt.modes, {
    renderRail, switchMode, renderStage, fetchExtensions, acquisitionSources,
    syncAllResultsQueueState, enqueueItems, buildQueueMeta, pixivCancelWorkKey,
    refreshQuickCredentialGate, applyFiltersToCurrentMode, pixivJson, parseSeriesUrl,
    workCard, worksGrid, paginationBar, enqueueBar, errorBox, loadingGrid
});
