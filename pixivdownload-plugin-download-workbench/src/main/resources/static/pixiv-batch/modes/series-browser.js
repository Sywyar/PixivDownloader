'use strict';
    /* ============================================================
       Series mode
    ============================================================ */
    const seriesState = {
        dataSourceId: null,
        kind: null,
        ownerIdentity: null,
        seriesId: null,
        seriesTitle: '',
        seriesAuthorId: null,
        seriesAuthorName: '',
        seriesTotal: 0,
        currentPage: 1,
        totalPages: 1,
        isLastPage: true,
        rawItems: [],   // 当前页未过滤的成员（itemsByPage 缓存的也是原始未过滤数据）
        items: [],      // 当前页经附加筛选后的成员（渲染 / 「加入当前页」据此）
        allItems: [],
        itemsByPage: new Map(),
        pageCursors: new Map(),
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        filterSeq: 0,
        requestSeq: 0,
        requestController: null,
        browserIdentity: null,
        browserSeq: 0,
        browserController: null,
        renderToken: 0,
        activeBlobUrls: []
    };

    function seriesDataSourceDescriptor(acquisition, ownerType = acquisition && acquisition.type) {
        const registry = window.PixivBatch.queueTypes;
        const metadata = acquisition && acquisition.dataSource && typeof acquisition.dataSource === 'object'
            ? acquisition.dataSource : {};
        const manifest = registry && typeof registry.manifestDescriptor === 'function'
            ? (registry.manifestDescriptor(ownerType) || {}) : {};
        const type = ownerType != null ? String(ownerType).trim() : '';
        const id = String(metadata.id || type).trim();
        const rawOrder = metadata.order == null ? manifest.order : metadata.order;
        const order = Number(rawOrder);
        return {
            id,
            displayNamespace: String(metadata.displayNamespace || manifest.displayNamespace || '').trim(),
            displayI18nKey: String(metadata.displayI18nKey || manifest.displayI18nKey || '').trim(),
            order: Number.isFinite(order) ? order : 0,
            ownerType: type
        };
    }
    // 多个 series 类型可以共享同一数据来源（例如 Pixiv 插画与小说系列）。来源元数据由受控
    // acquisition 贡献；旧模块未声明时按自身 type / display token 降级为独立来源。
    function seriesDataSources() {
        const byId = new Map();
        window.PixivBatch.queueTypes.acquisitionList('series').forEach(acquisition => {
            const candidate = seriesDataSourceDescriptor(acquisition);
            if (!candidate.id || !candidate.ownerType) return;
            const existing = byId.get(candidate.id);
            if (!existing) {
                byId.set(candidate.id, Object.assign({}, candidate, {ownerTypes: [candidate.ownerType]}));
                return;
            }
            if (!existing.ownerTypes.includes(candidate.ownerType)) existing.ownerTypes.push(candidate.ownerType);
            if (existing.displayNamespace !== candidate.displayNamespace
                || existing.displayI18nKey !== candidate.displayI18nKey
                || existing.order !== candidate.order) {
                console.warn('[series] 同一数据来源的展示元数据不一致，保留先声明的元数据：', candidate.id);
            }
        });
        return Array.from(byId.values())
            .sort((left, right) => (left.order - right.order) || left.id.localeCompare(right.id));
    }

    function seriesDataSourceIdForOwnerType(ownerType) {
        const owner = ownerType == null ? '' : String(ownerType).trim();
        if (!owner) return null;
        const source = seriesDataSources().find(item => item.ownerTypes.includes(owner));
        return source ? source.id : null;
    }

    function selectedSeriesAcquisitions() {
        const selected = seriesState.dataSourceId;
        if (!selected) return [];
        return window.PixivBatch.queueTypes.acquisitionList('series')
            .filter(acquisition => seriesDataSourceDescriptor(acquisition).id === selected);
    }

    function seriesSourceBrowserAcquisition() {
        return selectedSeriesAcquisitions().find(acquisition => acquisition.browser) || null;
    }

    function seriesBrowserRequestUrl(spec) {
        if (typeof spec === 'string') return spec;
        if (!spec || typeof spec !== 'object') {
            throw new Error('series browser request builder returned no request');
        }
        const endpoint = String(spec.endpoint || '');
        const params = new URLSearchParams();
        Object.entries(spec.params || {}).forEach(([key, value]) => {
            if (Array.isArray(value)) value.forEach(item => params.append(key, item));
            else if (value != null) params.append(key, value);
        });
        return endpoint + (params.toString() ? (endpoint.includes('?') ? '&' : '?') + params : '');
    }

    function invalidateSeriesBrowser() {
        if (seriesState.browserController) {
            try { seriesState.browserController.abort(); } catch (e) { /* best effort */ }
        }
        seriesState.browserController = typeof AbortController === 'function' ? new AbortController() : null;
        seriesState.browserSeq += 1;
    }

    async function renderSeriesSourceBrowser(force = false) {
        const host = document.getElementById('series-source-browser');
        if (!host) return;
        const acquisition = seriesSourceBrowserAcquisition();
        const browser = acquisition && acquisition.browser;
        const identity = acquisition
            ? [seriesState.dataSourceId, acquisition.type, seriesOwnerIdentity(acquisition.type)].join(':')
            : null;
        if (!force && identity && identity === seriesState.browserIdentity && !host.hidden) return;

        invalidateSeriesBrowser();
        seriesState.browserIdentity = identity;
        host.replaceChildren();
        host.hidden = !browser;
        if (!browser) return;

        const sequence = seriesState.browserSeq;
        const controller = seriesState.browserController;
        const lease = window.PixivBatch.queueTypes.acquisitionLease(acquisition.type, 'series');
        const isCurrent = () => sequence === seriesState.browserSeq
            && controller === seriesState.browserController
            && (!controller || !controller.signal.aborted)
            && identity === seriesState.browserIdentity
            && lease.isCurrent();
        const assertCurrent = () => {
            if (!isCurrent()) {
                const error = new Error('series browser is stale');
                error.code = 'STALE_SERIES_BROWSER';
                throw error;
            }
        };

        const heading = document.createElement('div');
        heading.className = 'series-source-browser-title';
        heading.textContent = typeof browser.title === 'function'
            ? browser.title() : bt('series.browser.title', '浏览当前数据来源');
        const status = document.createElement('div');
        status.className = 'series-source-browser-status';
        status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite');
        const list = document.createElement('div');
        list.className = 'series-source-browser-list';
        const navigation = document.createElement('div');
        navigation.className = 'series-source-browser-navigation';
        host.appendChild(heading);
        host.appendChild(status);
        host.appendChild(list);
        host.appendChild(navigation);

        const cursors = new Map([[1, String(browser.initialCursor || '0')]]);
        let currentPage = 1;
        let currentHasMore = false;

        const renderNavigation = () => {
            navigation.replaceChildren();
            const previous = document.createElement('button');
            previous.type = 'button';
            previous.className = 'btn btn-gray';
            previous.textContent = bt('series.browser.previous', '上一页');
            previous.disabled = currentPage <= 1;
            const page = document.createElement('span');
            page.textContent = bt('series.browser.page', '第 {page} 页', {page: currentPage});
            const next = document.createElement('button');
            next.type = 'button';
            next.className = 'btn btn-gray';
            next.textContent = bt('series.browser.next', '下一页');
            next.disabled = !currentHasMore || !cursors.has(currentPage + 1);
            previous.addEventListener('click', () => loadPage(currentPage - 1));
            next.addEventListener('click', () => loadPage(currentPage + 1));
            navigation.appendChild(previous);
            navigation.appendChild(page);
            navigation.appendChild(next);
        };

        const renderItems = page => {
            list.replaceChildren();
            const items = Array.isArray(page.items) ? page.items : [];
            if (!items.length) {
                status.textContent = typeof browser.emptyLabel === 'function'
                    ? browser.emptyLabel() : bt('series.browser.empty', '当前没有可浏览项目');
                return;
            }
            status.textContent = '';
            items.forEach(item => {
                const id = String(browser.itemId(item) || '').trim();
                if (!id) return;
                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'series-source-browser-item';
                button.textContent = String(browser.itemLabel(item) || id);
                button.addEventListener('click', async () => {
                    try {
                        assertCurrent();
                        const selection = await browser.select(item);
                        assertCurrent();
                        await loadSeriesSelection(acquisition.type, selection);
                    } catch (error) {
                        if (!isCurrent()) return;
                        setStatus(bt('status.series-load-failed', '加载失败：{message}', {
                            message: error && error.message ? error.message : String(error)
                        }), 'error');
                    }
                });
                list.appendChild(button);
            });
        };

        const loadPage = async page => {
            const targetPage = Math.max(1, Number(page) || 1);
            const cursor = cursors.get(targetPage);
            if (cursor == null) return;
            status.textContent = typeof browser.loadingLabel === 'function'
                ? browser.loadingLabel() : bt('series.browser.loading', '正在加载可浏览列表...');
            list.replaceChildren();
            navigation.replaceChildren();
            try {
                assertCurrent();
                const requestSpec = browser.buildPageRequest({
                    cursor,
                    page: targetPage,
                    limit: browser.pageSize,
                    signal: controller && controller.signal
                });
                const request = window.PixivBatch.queueTypes.prepareAcquisitionRequest(
                    acquisition.type, 'series', seriesBrowserRequestUrl(requestSpec), 'browser', {
                        cursor, page: targetPage, limit: browser.pageSize
                    });
                const scope = seriesSignalScope([controller && controller.signal, request.signal]);
                let raw;
                try {
                    const response = await fetch(request.url, Object.assign({}, request.init, {signal: scope.signal}));
                    if (!response.ok) {
                        const data = await response.json().catch(() => ({}));
                        request.assertCurrent();
                        throw new Error(data.error || `HTTP ${response.status}`);
                    }
                    raw = await response.json();
                } finally {
                    scope.dispose();
                }
                request.assertCurrent();
                assertCurrent();
                const result = browser.readPage(raw, {cursor, page: targetPage, limit: browser.pageSize}) || {};
                assertCurrent();
                const nextCursor = result.nextCursor == null ? '' : String(result.nextCursor);
                if (result.hasMore === true && (!nextCursor || nextCursor === cursor)) {
                    throw new Error(bt('series.pagination.cursor-stalled', '数据来源分页游标未推进'));
                }
                currentPage = targetPage;
                currentHasMore = result.hasMore === true && !!nextCursor;
                if (currentHasMore) cursors.set(targetPage + 1, nextCursor);
                else cursors.delete(targetPage + 1);
                renderItems(result);
                renderNavigation();
            } catch (error) {
                if (!isCurrent()) return;
                status.textContent = bt('series.browser.load-failed', '加载列表失败：{message}', {
                    message: error && error.message ? error.message : String(error)
                });
                renderNavigation();
            }
        };

        await loadPage(1);
    }
