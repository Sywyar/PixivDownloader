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

async function openSeriesBrowser(acquisition, cursor) {
    const browser = acquisition.browser;
    const body = el('div', 'ab-collection-grid');
    body.appendChild(el('p', 'ab-loading-line', browser.loadingLabel()));
    openDrawer({
        id: 'series-browser', icon: 'folder', title: browser.title(), body, footer: null
    });
    try {
        const context = {cursor: cursor ?? browser.initialCursor, limit: browser.pageSize};
        const data = await altAcquisitionJson(acquisition.type, 'series',
            browser.buildPageRequest(context), 'browser', context);
        const page = browser.readPage(data);
        body.innerHTML = '';
        if (!page.items.length) {
            body.appendChild(el('p', 'ab-empty-line', browser.emptyLabel()));
            return;
        }
        page.items.forEach(item => {
            const button = el('button', 'ab-collection-card card');
            button.type = 'button';
            button.appendChild(abIconEl('folder'));
            button.appendChild(el('span', 'ab-collection-meta', browser.itemLabel(item)));
            button.addEventListener('click', () => {
                const selected = browser.select(item);
                seriesState.source = acquisition.dataSource.id;
                seriesState.kind = acquisition.type;
                seriesState.url = String(selected.seriesId);
                seriesState.browserSelection = {type: acquisition.type, parsed: selected};
                closeDrawer();
                renderStage();
                loadSeries(1);
            });
            body.appendChild(button);
        });
        if (page.hasMore && page.nextCursor != null) {
            const more = el('button', 'ab-btn ab-btn--ghost', bt('common.next', '下一页'));
            more.type = 'button';
            more.addEventListener('click', () => openSeriesBrowser(acquisition, page.nextCursor));
            body.appendChild(more);
        }
    } catch (e) {
        body.replaceChildren(errorBox(String(e && e.message || bt('common.request-failed', '请求失败')),
            () => openSeriesBrowser(acquisition, cursor)));
    }
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
        if (page === 1) seriesState.cursor = acquisition.initialCursor ? acquisition.initialCursor(seriesId) : null;
        const context = {
            seriesId, seriesTitle: seriesState.info && seriesState.info.title || '', page,
            cursor: seriesState.cursor, limit: Number(acquisition.pageSize) || 12
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
            seriesAuthorId: seriesState.info.authorId,
            seriesAuthorName: seriesState.info.authorName
        };
        seriesState.rawItems = normalizeAcquisitionItems(data.items || [], acquisition, queueContext, 'series');
        seriesState.page = Number(data.page || page);
        seriesState.isLastPage = data.isLastPage === true || data.hasMore === false;
        seriesState.cursor = data.nextCursor == null ? null : String(data.nextCursor);
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
        allEnabled: seriesState.items.length > 0,
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
    const all = [];
    let cursor = typeof acquisition.initialCursor === 'function'
        ? acquisition.initialCursor(seriesId) : null;
    for (let page = 1; page <= 100; page++) {
        const context = {
            seriesId, seriesTitle: seriesState.info.title || '', page, cursor,
            limit: Number(acquisition.pageSize) || 12
        };
        let data;
        try {
            data = await altAcquisitionJson(acquisition.type, 'series',
                acquisition.apiPath(seriesId, page, context), 'page', context);
            if (typeof acquisition.normalizePage === 'function') data = acquisition.normalizePage(data, context);
        } catch (e) {
            abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
            return;
        }
        all.push(...normalizeAcquisitionItems(data.items || [], acquisition, {
            seriesId, seriesTitle: seriesState.info.title,
            seriesAuthorId: seriesState.info.authorId,
            seriesAuthorName: seriesState.info.authorName
        }, 'series'));
        if (data.isLastPage === true || data.hasMore === false || !(data.items || []).length) break;
        // ponytail: 防止失控循环；原子失败，避免静默加入不完整系列。
        if (page === 100) {
            abToast('error', bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
            return;
        }
        if (data.hasMore === true) cursor = altNextCursor(data, cursor, true);
    }
    const added = enqueueSeriesItems(all.length ? all : seriesState.items);
    abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
}

/* ============================================================
   筛选变更 → 当前模式预览实时重滤
   ============================================================ */
async function applyFiltersToCurrentMode() {
    if (state.mode === QUICK_FETCH_MODE && quickState.rawItems.length) {
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
