'use strict';

/* ============================================================
   User 模式（画师批量下载）
   ============================================================ */
function renderUserMode(panel) {
    const def = AB_MODES[2];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('user');
    if (!sources.some(source => source.id === userState.source)) userState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, userState.source, source => {
            userState.source = source;
            userState.kind = (altTypesForSource('user', source)[0] || {}).type || 'illust';
            userState.rawItems = [];
            userState.items = [];
            renderStage();
        }));
    }

    const composer = el('div', 'ab-composer card');
    const row = el('div', 'ab-composer-row');
    const input = el('input', 'ab-input');
    input.id = 'abUserInput';
    input.type = 'text';
    input.placeholder = bt('user.input.placeholder', '输入用户 ID 或画师主页链接（https://www.pixiv.net/users/…）');
    input.value = userState.input || storeGet('pixiv_user_input') || '';
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter') loadUserWorks(1);
    });
    const kindSeg = el('div', 'ab-seg ab-seg--sm');
    const selections = altTypesForSource('user', userState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    const userAcquisitions = altQueueTypes().acquisitionList('user')
        .filter(item => altTypesForSource('user', userState.source).some(type => type.type === item.type));
    userAcquisitions.forEach(acquisition => {
        (Array.isArray(acquisition.variants) ? acquisition.variants : []).forEach(variant => {
            const id = String(variant.id || '');
            if (!id || selections.some(option => option[0] === id)) return;
            if (typeof acquisition.accepts === 'function' && !acquisition.accepts(id)) return;
            const namespace = variant.labelNamespace
                || (acquisition.dataSource && acquisition.dataSource.displayNamespace) || '';
            const key = variant.labelI18nKey || ('user.kind.' + id);
            selections.push([id, bt((namespace ? namespace + ':' : '') + key, variant.label || id)]);
        });
    });
    if (!selections.some(option => option[0] === userState.kind)) {
        userState.kind = selections[0] ? selections[0][0] : 'illust';
    }
    selections.forEach(([value, label]) => {
        const btn = el('button', 'ab-seg-item' + (userState.kind === value ? ' is-active' : ''), label);
        btn.type = 'button';
        btn.addEventListener('click', () => {
            userState.kind = value;
            kindSeg.querySelectorAll('.ab-seg-item').forEach(b => b.classList.remove('is-active'));
            btn.classList.add('is-active');
            if (userState.userId) loadUserWorks(1);
        });
        kindSeg.appendChild(btn);
    });
    const loadBtn = el('button', 'ab-btn ab-btn--primary');
    loadBtn.type = 'button';
    loadBtn.appendChild(abIconEl('search'));
    loadBtn.appendChild(el('span', '', bt('user.load', '读取作品')));
    loadBtn.addEventListener('click', () => loadUserWorks(1));
    row.appendChild(input);
    row.appendChild(kindSeg);
    row.appendChild(loadBtn);
    composer.appendChild(row);
    panel.appendChild(composer);

    const stage = el('div');
    stage.id = 'abUserStage';
    panel.appendChild(stage);
    renderUserStage();
}

function parseUserInput(raw) {
    const text = String(raw || '').trim();
    if (!text) return null;
    const urlMatch = text.match(/pixiv\.net\/(?:u\/)?users\/(\d+)/) || text.match(/pixiv\.net\/u\/(\d+)/);
    if (urlMatch) return urlMatch[1];
    if (/^\d+$/.test(text)) return text;
    return null;
}

async function loadUserWorks(page) {
    const input = document.getElementById('abUserInput');
    const raw = input ? input.value : userState.input;
    const acquisition = altAcquisition('user', userState.source, userState.kind);
    const userId = acquisition && acquisition.parseInput ? acquisition.parseInput(raw) : parseUserInput(raw);
    userState.input = raw;
    storeSet('pixiv_user_input', raw || '');   // 输入草稿持久化（刷新后恢复）
    if (!userId) {
        userState.error = bt('user.error.invalid', '请输入有效的用户 ID 或画师主页链接');
        renderUserStage();
        return;
    }
    userState.userId = userId;
    userState.loading = true;
    userState.error = '';
    renderUserStage();
    try {
        if (!acquisition) throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'user');
        const variant = typeof acquisition.detectVariant === 'function'
            ? acquisition.detectVariant(raw, userState.kind) || userState.kind : userState.kind;
        const name = typeof acquisition.fetchMeta === 'function'
            ? await acquisition.fetchMeta(userId, {signal: lease.signal}) : null;
        lease.assertCurrent();
        userState.username = typeof name === 'string' && name ? name : String(userId);
        userState.pageSize = Math.max(1, Number(acquisition.pageSize) || 30);
        const context = {variant, userId, username: userState.username};
        let rawItems;
        if (typeof acquisition.fetchPage === 'function') {
            if (page === 1 || !userState.cursors) {
                userState.cursors = new Map([[1, acquisition.initialCursor ?? null]]);
            }
            const cursor = page === 1 ? acquisition.initialCursor ?? null : userState.cursors.get(page);
            if (page > 1 && cursor == null) throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
            const data = await acquisition.fetchPage(userId, {
                variant, signal: lease.signal, page,
                offset: (page - 1) * userState.pageSize,
                limit: userState.pageSize, cursor
            });
            lease.assertCurrent();
            rawItems = Array.isArray(data && data.items) ? data.items : [];
            userState.total = Math.max(Number(data && data.total) || 0,
                (page - 1) * userState.pageSize + rawItems.length + (data && data.hasMore ? 1 : 0));
            if (data && data.hasMore) {
                userState.cursors.set(page + 1, altNextCursor(data, cursor, true));
            }
        } else {
            const ids = await acquisition.fetchIds(userId, {variant, signal: lease.signal});
            lease.assertCurrent();
            userState.ids = (ids || []).map(String);
            userState.total = userState.ids.length;
            const pageIds = userState.ids.slice((page - 1) * userState.pageSize, page * userState.pageSize);
            const data = await altAcquisitionJson(acquisition.type, 'user', {
                endpoint: acquisition.cardsEndpoint(userId), params: {ids: pageIds}
            }, 'cards', {userId, ids: pageIds});
            rawItems = data.items || [];
        }
        userState.rawItems = normalizeAcquisitionItems(rawItems, acquisition, context, 'user');
        userState.page = page;
    } catch (e) {
        userState.ids = [];
        userState.total = 0;
        userState.rawItems = [];
        userState.page = page;
        userState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    userState.loading = false;
    await applyUserFilters();
}

async function applyUserFilters() {
    const seq = ++searchState.filterSeq;
    const acquisition = altAcquisition('user', userState.source, userState.kind);
    const result = await computeFilteredItems(userState.rawItems, extraFilters,
        acquisition ? acquisition.type : userState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    userState.items = result.filtered;
    userState.filterSummary = result.stats;
    renderUserStage();
}

function userFilterSummaryText() {
    const stats = userState.filterSummary;
    if (!stats || !hasExtraSearchFilter()) return '';
    const parts = [bt('user.summary.filtered', '附加筛选后 {count} 个', {count: stats.filteredCount})];
    if (stats.bookmarkMetaMissing > 0) {
        parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
            {count: stats.bookmarkMetaMissing}));
    }
    return parts.join(' · ');
}

function renderUserStage() {
    const stage = document.getElementById('abUserStage');
    if (!stage) return;
    stage.innerHTML = '';
    if (userState.error && !userState.rawItems.length) {
        stage.appendChild(errorBox(userState.error, () => loadUserWorks(1)));
        return;
    }
    if (userState.loading) {
        stage.appendChild(loadingGrid(bt('user.status.loading', '正在获取画师作品…')));
        return;
    }
    if (!userState.userId || !userState.rawItems.length) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('user'));
        hint.appendChild(el('p', '', bt('user.hint', '输入画师 ID 或主页链接，读取其全部作品')));
        stage.appendChild(hint);
        return;
    }
    const artistCard = el('div', 'ab-artist card');
    const avatar = el('span', 'ab-avatar ab-avatar--lg');
    applyThumbHue(avatar, 'u' + userState.userId + userState.username);
    avatar.appendChild(abIconEl('user'));
    artistCard.appendChild(avatar);
    const artistMeta = el('div', 'ab-artist-meta');
    artistMeta.appendChild(el('strong', '', userState.username || userState.userId));
    artistMeta.appendChild(el('span', 'ab-muted',
        bt('user.summary.total', '共 {count} 个作品', {count: userState.total})));
    artistCard.appendChild(artistMeta);
    const acquisition = altAcquisition('user', userState.source, userState.kind);
    const profileHref = acquisition && typeof acquisition.profileUrl === 'function'
        ? acquisition.profileUrl(userState.userId) : null;
    if (profileHref) {
        const profile = el('a', 'ab-btn ab-btn--ghost ab-btn--sm');
        profile.href = profileHref;
        profile.target = '_blank';
        profile.rel = 'noopener';
        profile.appendChild(abIconEl('external'));
        artistCard.appendChild(profile);
    }
    stage.appendChild(artistCard);

    const totalPages = Math.max(1, Math.ceil(userState.total / userState.pageSize));
    stage.appendChild(enqueueBar({
        summary: bt('user.summary.page', '第 {page} 页 · 当前页 {count} 个',
            {page: userState.page, count: userState.items.length}),
        filterSummary: userFilterSummaryText(),
        pageEnabled: userState.items.length > 0,
        allEnabled: userState.total > 0,
        onEnqueuePage: () => enqueueItems(userState.items, null,
            {source: 'user', username: userState.username, authorId: userState.userId, authorName: userState.username}),
        onEnqueueAll: enqueueUserAll
    }));
    stage.appendChild(worksGrid(userState.items, {
        source: 'user',
        authorId: userState.userId, authorName: userState.username
    }));
    stage.appendChild(paginationBar({
        page: userState.page,
        totalPages,
        total: userState.total,
        onPage: p => loadUserWorks(p)
    }));
    syncAllResultsQueueState();
}

async function enqueueUserAll() {
    const filtered = hasExtraSearchFilter();
    const totalPages = Math.max(1, Math.ceil(userState.total / userState.pageSize));
    const needFetch = totalPages > 1 || filtered;
    if (needFetch && !await abConfirm('user.enqueue-all.confirm',
        '将逐页请求该画师全部 {total} 个作品条目并加入队列？', {total: userState.total})) return;

    const acquisition = altAcquisition('user', userState.source, userState.kind);
    if (!acquisition) return;
    if (typeof acquisition.fetchPage === 'function') {
        const source = userState.source;
        const kind = userState.kind;
        const userId = userState.userId;
        const username = userState.username;
        const variant = typeof acquisition.detectVariant === 'function'
            ? acquisition.detectVariant(userState.input, kind) || kind : kind;
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'user');
        const matched = [];
        let cursor = acquisition.initialCursor ?? null;
        try {
            for (let page = 1; page <= 1000; page++) {
                const context = {variant, userId, username};
                const data = await acquisition.fetchPage(userId, {
                    variant, signal: lease.signal, page,
                    offset: (page - 1) * userState.pageSize,
                    limit: userState.pageSize, cursor
                });
                lease.assertCurrent();
                if (userState.source !== source || userState.kind !== kind || userState.userId !== userId) return;
                const pageItems = normalizeAcquisitionItems(
                    Array.isArray(data && data.items) ? data.items : [], acquisition, context, 'user');
                if (filtered) {
                    const result = await computeFilteredItems(pageItems, extraFilters, acquisition.type,
                        () => userState.source !== source || userState.kind !== kind || userState.userId !== userId);
                    lease.assertCurrent();
                    if (!result) return;
                    matched.push(...result.filtered);
                } else {
                    matched.push(...pageItems);
                }
                const hasMore = !!(data && data.hasMore);
                if (!hasMore || !pageItems.length) break;
                // ponytail: 防止失控循环；原子失败，避免静默加入不完整账号。
                if (page === 1000) {
                    throw new Error(bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
                }
                cursor = altNextCursor(data, cursor, hasMore);
            }
        } catch (e) {
            abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
            return;
        }
        const added = enqueueItems(matched, null, {
            source: 'user', username: userState.username,
            authorId: userState.userId, authorName: userState.username, silent: true
        });
        abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
        return;
    }
    const ids = userState.ids.slice();
    const context = {variant: userState.kind, userId: userState.userId, username: userState.username};
    const metas = ids.map(id => Object.assign({id, kind: acquisition.type},
        acquisition.buildQueueMetaFromId ? acquisition.buildQueueMetaFromId(id, context) : {}));
    const added = addItemsToQueue(ids, metas, 'user', userState.username,
        userState.userId, userState.username);
    abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
}

/* ============================================================
   Search 模式
   ============================================================ */
function renderSearchMode(panel) {
    const def = AB_MODES[3];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('search');
    if (!sources.some(source => source.id === searchState.source)) searchState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, searchState.source, source => {
            searchState.source = source;
            searchState.kind = (altTypesForSource('search', source)[0] || {}).type || 'illust';
            searchState.rawResults = [];
            searchState.results = [];
            renderStage();
        }));
    }
    const typeOptions = altTypesForSource('search', searchState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    if (!typeOptions.some(option => option[0] === searchState.kind)) {
        searchState.kind = typeOptions[0] ? typeOptions[0][0] : 'illust';
    }
    if (typeOptions.length > 1) {
        panel.appendChild(smallSeg(typeOptions, searchState.kind, kind => {
            searchState.kind = kind;
            if (searchState.word) runSearch(1);
        }));
    }

    if (searchState.source === 'pixiv' && !hasPixivCookie()) {
        const warn = el('div', 'ab-inline-warn');
        warn.appendChild(abIconEl('alert'));
        warn.appendChild(el('span', '', bt('search.no-cookie-warning', '未保存 Cookie：搜索结果可能减少')));
        panel.appendChild(warn);
    }

    const composer = el('div', 'ab-composer card');
    const row = el('div', 'ab-composer-row');
    const input = el('input', 'ab-input');
    input.id = 'abSearchInput';
    input.type = 'search';
    input.placeholder = bt('search.input.placeholder', '输入搜索词（标签 / 标题）');
    input.value = searchState.word || '';
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter') runSearch(1);
    });
    const searchBtn = el('button', 'ab-btn ab-btn--primary');
    searchBtn.type = 'button';
    searchBtn.appendChild(abIconEl('search'));
    searchBtn.appendChild(el('span', '', bt('search.run', '搜索')));
    searchBtn.addEventListener('click', () => runSearch(1));
    row.appendChild(input);
    row.appendChild(searchBtn);
    composer.appendChild(row);

    const controls = el('div', 'ab-search-controls');
    const searchAcquisition = altAcquisition('search', searchState.source, searchState.kind);
    const contributionControls = searchAcquisition && searchAcquisition.controls || {};
    const supportsBatchRange = contributionControls.batchRange !== false
        && searchAcquisition && typeof searchAcquisition.buildRangeRequest === 'function';
    if (!supportsBatchRange && searchState.submode === 'batch') searchState.submode = 'search';
    if (contributionControls.searchMode !== false) {
        controls.appendChild(el('span', 'ab-control-label', bt('search.mode.label', '搜索方式')));
        controls.appendChild(smallSeg([
            ['s_tag', bt('search.mode.tag', '标签')],
            ['s_tc', bt('search.mode.tc', '标题/描述')]
        ], searchState.sMode, v => { searchState.sMode = v; if (searchState.word) runSearch(1); }));
    }
    if (contributionControls.order !== false) {
        controls.appendChild(el('span', 'ab-control-label', bt('search.order.label', '排序')));
        controls.appendChild(smallSeg([
            ['date_d', bt('search.order.new', '最新')],
            ['date', bt('search.order.old', '最旧')],
            ['popular_d', bt('search.order.popular', '热门')]
        ], searchState.order, v => {
            searchState.order = v;
            if (v === 'popular_d') {
                abToast('info', bt('search.order.premium-note', '热门排序需要 Pixiv Premium，未购买时将自动按最新排序'));
            }
            if (searchState.word) runSearch(1);
        }));
    }
    controls.appendChild(el('span', 'ab-control-label', bt('search.sub.label', '子模式')));
    const submodes = [['search', bt('search.sub.search', '搜索模式')]];
    if (supportsBatchRange) submodes.push(['batch', bt('search.sub.batch', '批量获取')]);
    controls.appendChild(smallSeg(submodes, searchState.submode, v => {
        searchState.submode = v;
        renderStage();
    }));
    const blurLabel = el('label', 'ab-check');
    const blurBox = el('input');
    blurBox.type = 'checkbox';
    blurBox.checked = searchState.blurR18;
    blurBox.addEventListener('change', () => {
        searchState.blurR18 = blurBox.checked;
        storeSet('pixiv_search_blur_r18', String(blurBox.checked));
        renderSearchStage();
    });
    blurLabel.appendChild(blurBox);
    blurLabel.appendChild(el('span', '', bt('search.blur-r18', '模糊 R18 缩略图')));
    if (contributionControls.r18Blur !== false) controls.appendChild(blurLabel);
    composer.appendChild(controls);

    if (searchState.submode === 'batch') {
        const batchRow = el('div', 'ab-composer-row ab-batch-range');
        batchRow.appendChild(el('span', 'ab-control-label', bt('search.batch.range', '抓取页码范围')));
        const startInput = el('input', 'ab-input ab-input--num');
        startInput.type = 'number';
        startInput.min = '1';
        startInput.value = searchState.startPage;
        startInput.addEventListener('change', () => {
            searchState.startPage = Math.max(1, parseInt(startInput.value, 10) || 1);
        });
        const endInput = el('input', 'ab-input ab-input--num');
        endInput.type = 'number';
        endInput.min = '-1';
        endInput.value = searchState.endPage;
        endInput.addEventListener('change', () => {
            searchState.endPage = parseInt(endInput.value, 10) || 1;
        });
        batchRow.appendChild(startInput);
        batchRow.appendChild(el('span', 'ab-range-dash', '—'));
        batchRow.appendChild(endInput);
        const note = el('span', 'ab-field-note ab-field-note--inline');
        note.textContent = (isAdmin
            ? bt('search.batch.end-minus-one', '结束页填 -1 = 一直翻页直到遇到已下载作品（仅管理员）')
            : '')
            + (appMode === 'multi' && multiModeLimitPage > 0
                ? ' ' + bt('search.batch.multi-limit', 'multi 模式：每次最多 {limit} 页', {limit: multiModeLimitPage})
                : '');
        batchRow.appendChild(note);
        composer.appendChild(batchRow);
    }
    panel.appendChild(composer);

    const stage = el('div');
    stage.id = 'abSearchStage';
    panel.appendChild(stage);
    renderSearchStage();
}

function smallSeg(options, current, onSelect) {
    const seg = el('div', 'ab-seg ab-seg--sm');
    options.forEach(([value, label]) => {
        const btn = el('button', 'ab-seg-item' + (current === value ? ' is-active' : ''), label);
        btn.type = 'button';
        btn.addEventListener('click', () => {
            seg.querySelectorAll('.ab-seg-item').forEach(b => b.classList.remove('is-active'));
            btn.classList.add('is-active');
            onSelect(value);
        });
        seg.appendChild(btn);
    });
    return seg;
}

// 附加筛选的内容分级映射为搜索 API 的 mode 参数（与现行页同口径）
function searchApiMode() {
    const c = extraFilters.content;
    if (c === 'safe') return 'safe';
    if (c === 'r18plus' || c === 'r18' || c === 'r18g') return 'r18';
    return 'all';
}

async function runSearch(page) {
    const input = document.getElementById('abSearchInput');
    const word = (input ? input.value : searchState.word || '').trim();
    if (!word) {
        abToast('warning', bt('search.error.empty', '请输入搜索词'));
        return;
    }
    searchState.word = word;
    searchState.loading = true;
    searchState.error = '';
    searchState.batchInfo = null;
    renderSearchStage();
    try {
        const acquisition = altAcquisition('search', searchState.source, searchState.kind);
        if (!acquisition) throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
        const context = {
            word, order: searchState.order, uiMode: searchApiMode(),
            searchMode: searchState.sMode, page,
            startPage: searchState.startPage, endPage: searchState.endPage
        };
        const range = searchState.submode === 'batch';
        const builder = range ? acquisition.buildRangeRequest : acquisition.buildRequest;
        if (typeof builder !== 'function') {
            throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
        }
        const spec = builder(context);
        const data = await altAcquisitionJson(acquisition.type, 'search', spec,
            range ? 'range' : 'search', context);
        searchState.rawResults = normalizeAcquisitionItems(data.items || [], acquisition, context, 'search');
        searchState.total = Number(data.total || searchState.rawResults.length);
        if (searchState.submode === 'batch') {
            searchState.batchInfo = {
                startPage: data.startPage, endPage: data.endPage,
                requestedPages: data.requestedPages, acceptedPages: data.acceptedPages,
                fetchedPages: data.fetchedPages, limitPage: data.limitPage
            };
            searchState.localPage = 1;
        } else {
            searchState.page = Number(data.page || page);
        }
    } catch (e) {
        searchState.rawResults = [];
        searchState.total = 0;
        searchState.page = page;
        searchState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    searchState.loading = false;
    await applySearchFilters();
}

async function applySearchFilters() {
    const seq = ++searchState.filterSeq;
    const acquisition = altAcquisition('search', searchState.source, searchState.kind);
    const result = await computeFilteredItems(searchState.rawResults, extraFilters,
        acquisition ? acquisition.type : searchState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    searchState.results = result.filtered;
    searchState.filterSummary = result.stats;
    renderSearchStage();
}

function searchSummaryText() {
    const parts = [];
    if (searchState.batchInfo) {
        const info = searchState.batchInfo;
        parts.push(bt('search.summary.batch-range', '抓取第 {start}–{end} 页',
            {start: info.startPage, end: info.endPage}));
        if (info.limitPage) {
            parts.push(bt('search.summary.batch-limited', '受限至第 {page} 页', {page: info.limitPage}));
        }
        parts.push(bt('search.summary.batch-fetched', '本批 {count} 个', {count: searchState.filterSummary ? searchState.filterSummary.rawCount : searchState.rawResults.length}));
    } else {
        parts.push(bt('search.summary.page', '第 {page} 页 · 当前页 {count} 个',
            {page: searchState.page, count: searchState.results.length}));
    }
    if (hasExtraSearchFilter() && searchState.filterSummary) {
        parts.push(bt('search.summary.filtered-count', '筛选后 {count} 个',
            {count: searchState.filterSummary.filteredCount}));
        if (searchState.filterSummary.bookmarkMetaMissing > 0) {
            parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
                {count: searchState.filterSummary.bookmarkMetaMissing}));
        }
    }
    parts.push(bt('search.summary.total', '共 {count} 个', {count: searchState.total}));
    return summaryJoin(parts);
}

function renderSearchStage() {
    const stage = document.getElementById('abSearchStage');
    if (!stage) return;
    stage.innerHTML = '';
    if (searchState.loading) {
        stage.appendChild(loadingGrid(bt('common.loading', '加载中…')));
        return;
    }
    if (!searchState.word) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('search'));
        hint.appendChild(el('p', '', bt('search.hint', '输入关键词开始搜索 Pixiv 作品')));
        stage.appendChild(hint);
        return;
    }
    if (searchState.error) {
        stage.appendChild(errorBox(searchState.error, () => runSearch(searchState.page || 1)));
        return;
    }
    const localPageSize = 60;
    const visibleResults = searchState.submode === 'batch'
        ? searchState.results.slice((searchState.localPage - 1) * localPageSize,
            searchState.localPage * localPageSize)
        : searchState.results;
    stage.appendChild(enqueueBar({
        summary: searchSummaryText(),
        pageEnabled: visibleResults.length > 0,
        allEnabled: searchState.results.length > 0,
        onEnqueuePage: () => enqueueItems(visibleResults, null, {source: 'search'}),
        onEnqueueAll: () => enqueueItems(searchState.results, null, {source: 'search'})
    }));
    if (!searchState.results.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('search'));
        empty.appendChild(el('p', '', bt('status.search-no-results', '无搜索结果')));
        stage.appendChild(empty);
        return;
    }
    stage.appendChild(worksGrid(visibleResults, {
        source: 'search', blurR18: searchState.blurR18
    }));
    if (searchState.submode !== 'batch') {
        const acquisition = altAcquisition('search', searchState.source, searchState.kind);
        const totalPages = Math.max(1, Math.ceil(searchState.total / Math.max(1, Number(acquisition && acquisition.pageSize) || 60)));
        stage.appendChild(paginationBar({
            page: searchState.page,
            totalPages,
            total: searchState.total,
            onPage: p => runSearch(p)
        }));
    } else {
        // 批量子模式：本地分页浏览已抓取结果
        const totalPages = Math.max(1, Math.ceil(searchState.results.length / localPageSize));
        stage.appendChild(paginationBar({
            page: searchState.localPage,
            totalPages,
            total: searchState.results.length,
            onPage: p => {
                searchState.localPage = p;
                renderSearchStage();
            }
        }));
    }
    syncAllResultsQueueState();
}
