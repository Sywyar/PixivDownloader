'use strict';
    // 共享「附加筛选」卡片：批量导入单作品 / Search / User / 系列四模式对所有用户显示。
    // Search/User/系列：实时过滤当前预览页；所有模式：实际下载时按此条件逐作品过滤并跳过；
    // 管理员另外可在 User/Search/系列模式下把同一份筛选条件快照进「存为计划任务」（后台逐作品执行）。
    function updateExtraFiltersCardVisibility() {
        const card = document.getElementById('extra-filters-card');
        if (!card) return;
        const mode = state.mode;
        // 快捷获取下附加筛选卡片**常驻**；未展开作品网格时给出提示（筛选仍会在下载 / 计划任务时生效，
        // 只是无可实时预览过滤的列表）。其它模式沿用原显隐规则。
        const visible = mode === 'search' || mode === 'user' || mode === 'series'
            || mode === 'single-import' || mode === QUICK_FETCH_MODE;
        card.style.display = visible ? '' : 'none';
        const hint = document.getElementById('extra-filters-quick-hint');
        if (hint) hint.style.display = (mode === QUICK_FETCH_MODE && !quickHasWorksGrid()) ? '' : 'none';
        if (visible) applySearchKindUI();
    }

    function defaultSearchFilters() {
        return {
            content: 'all',
            ai: 'all',
            type: 'all',
            pageMin: null,
            pageMax: null,
            bookmarkMin: null,
            bookmarkMax: null,
            wordsMin: null,
            wordsMax: null,
            tagsExact: [],
            tagsFuzzy: []
        };
    }

    // 标签输入：逗号（半/全角）分隔，去空白去重；不按空格切分（标签本身可能含空格）
    function parseTagTerms(value) {
        if (Array.isArray(value)) {
            value = value.join(',');
        }
        const seen = new Set();
        const out = [];
        String(value || '').split(/[,，]/).forEach(raw => {
            const t = raw.trim();
            if (t && !seen.has(t.toLowerCase())) {
                seen.add(t.toLowerCase());
                out.push(t);
            }
        });
        return out;
    }

    function parseSearchFilterNumber(value, min) {
        if (value === null || value === undefined) return null;
        const raw = String(value).trim();
        if (!raw) return null;
        const n = Math.floor(Number(raw));
        if (!Number.isFinite(n)) return null;
        return Math.max(min, n);
    }

    function normalizeSearchFilters(filters) {
        const out = defaultSearchFilters();
        out.content = ['all', 'safe', 'r18plus', 'r18', 'r18g'].includes(filters?.content) ? filters.content : 'all';
        out.ai = ['all', 'exclude', 'only'].includes(filters?.ai) ? filters.ai : 'all';
        out.type = ['all', 'illust', 'manga', 'ugoira'].includes(filters?.type) ? filters.type : 'all';
        out.pageMin = parseSearchFilterNumber(filters?.pageMin, 1);
        out.pageMax = parseSearchFilterNumber(filters?.pageMax, 1);
        out.bookmarkMin = parseSearchFilterNumber(filters?.bookmarkMin, 0);
        out.bookmarkMax = parseSearchFilterNumber(filters?.bookmarkMax, 0);
        out.wordsMin = parseSearchFilterNumber(filters?.wordsMin, 0);
        out.wordsMax = parseSearchFilterNumber(filters?.wordsMax, 0);
        out.tagsExact = parseTagTerms(filters?.tagsExact);
        out.tagsFuzzy = parseTagTerms(filters?.tagsFuzzy);
        if (out.pageMin !== null && out.pageMax !== null && out.pageMin > out.pageMax) {
            [out.pageMin, out.pageMax] = [out.pageMax, out.pageMin];
        }
        if (out.bookmarkMin !== null && out.bookmarkMax !== null && out.bookmarkMin > out.bookmarkMax) {
            [out.bookmarkMin, out.bookmarkMax] = [out.bookmarkMax, out.bookmarkMin];
        }
        if (out.wordsMin !== null && out.wordsMax !== null && out.wordsMin > out.wordsMax) {
            [out.wordsMin, out.wordsMax] = [out.wordsMax, out.wordsMin];
        }
        return out;
    }

    function setSearchFiltersUI(filters) {
        const contentEl = document.getElementById('search-content-filter');
        if (contentEl) contentEl.value = filters.content;
        document.getElementById('search-ai-filter').value = filters.ai;
        document.getElementById('search-type-filter').value = filters.type;
        document.getElementById('search-pages-min').value = filters.pageMin ?? '';
        document.getElementById('search-pages-max').value = filters.pageMax ?? '';
        document.getElementById('search-bookmarks-min').value = filters.bookmarkMin ?? '';
        document.getElementById('search-bookmarks-max').value = filters.bookmarkMax ?? '';
        const wMin = document.getElementById('search-words-min');
        if (wMin) wMin.value = filters.wordsMin ?? '';
        const wMax = document.getElementById('search-words-max');
        if (wMax) wMax.value = filters.wordsMax ?? '';
        const tEx = document.getElementById('search-tags-exact');
        if (tEx) tEx.value = (filters.tagsExact || []).join(', ');
        const tFz = document.getElementById('search-tags-fuzzy');
        if (tFz) tFz.value = (filters.tagsFuzzy || []).join(', ');
    }

    function getSearchFiltersFromUI() {
        const wMin = document.getElementById('search-words-min');
        const wMax = document.getElementById('search-words-max');
        return normalizeSearchFilters({
            content: (document.getElementById('search-content-filter') || {}).value || 'all',
            ai: document.getElementById('search-ai-filter').value,
            type: document.getElementById('search-type-filter').value,
            pageMin: document.getElementById('search-pages-min').value,
            pageMax: document.getElementById('search-pages-max').value,
            bookmarkMin: document.getElementById('search-bookmarks-min').value,
            bookmarkMax: document.getElementById('search-bookmarks-max').value,
            wordsMin: wMin ? wMin.value : null,
            wordsMax: wMax ? wMax.value : null,
            tagsExact: (document.getElementById('search-tags-exact') || {}).value || '',
            tagsFuzzy: (document.getElementById('search-tags-fuzzy') || {}).value || ''
        });
    }

    function saveSearchFilterPrefs(filters) {
        storeSet('pixiv_search_filters', JSON.stringify({
            content: filters.content,
            ai: filters.ai,
            type: filters.type,
            pageMin: filters.pageMin,
            pageMax: filters.pageMax,
            bookmarkMin: filters.bookmarkMin,
            bookmarkMax: filters.bookmarkMax,
            wordsMin: filters.wordsMin,
            wordsMax: filters.wordsMax,
            tagsExact: filters.tagsExact,
            tagsFuzzy: filters.tagsFuzzy
        }));
    }

    function loadSearchFilterPrefs() {
        try {
            const raw = storeGet('pixiv_search_filters');
            const filters = raw ? normalizeSearchFilters(JSON.parse(raw)) : defaultSearchFilters();
            searchState.currentFilters = filters;
            setSearchFiltersUI(filters);
        } catch {
            const filters = defaultSearchFilters();
            searchState.currentFilters = filters;
            setSearchFiltersUI(filters);
        }
    }

    function hasBookmarkFilter(filters = searchState.currentFilters) {
        return filters.bookmarkMin !== null || filters.bookmarkMax !== null;
    }

    function hasExtraSearchFilter(filters = searchState.currentFilters) {
        return filters.content !== 'all'
            || filters.ai !== 'all'
            || filters.type !== 'all'
            || filters.pageMin !== null
            || filters.pageMax !== null
            || filters.wordsMin !== null
            || filters.wordsMax !== null
            || (filters.tagsExact && filters.tagsExact.length > 0)
            || (filters.tagsFuzzy && filters.tagsFuzzy.length > 0)
            || hasBookmarkFilter(filters);
    }

    // 作品标签词元：兼容字符串数组（搜索 / 画师卡片）与 TagDto 对象数组（小说系列）。
    // 对象同时取原名与英文翻译作为可匹配词元（与计划任务后台逐作品筛选语义一致）。
    function itemTagTokens(item) {
        const out = [];
        for (const t of (Array.isArray(item.tags) ? item.tags : [])) {
            if (typeof t === 'string') {
                if (t) out.push(t.toLowerCase());
            } else if (t) {
                const name = t.name || t.tag;
                if (name) out.push(String(name).toLowerCase());
                const tr = t.translatedName || t.translation;
                if (tr) out.push(String(tr).toLowerCase());
            }
        }
        return out;
    }

    // 标签筛选：逗号分隔多标签全部命中(AND)；精确=与某个作品标签完全相等，
    // 模糊=被某个作品标签包含；两框都填则两者都需满足(AND)。大小写不敏感。
    function matchTagFilters(item, filters) {
        const exact = filters.tagsExact || [];
        const fuzzy = filters.tagsFuzzy || [];
        if (!exact.length && !fuzzy.length) return true;
        const tags = itemTagTokens(item);
        for (const term of exact) {
            const t = term.toLowerCase();
            if (!tags.some(x => x === t)) return false;
        }
        for (const term of fuzzy) {
            const t = term.toLowerCase();
            if (!tags.some(x => x.includes(t))) return false;
        }
        return true;
    }

    // 缓存键带 kind 前缀：插画与小说 ID 命名空间不同，相同数字可能指向不同作品。
    function searchMetaCacheKey(id, kind = searchState.kind) {
        return (kind === 'novel' ? 'n:' : 'i:') + String(id);
    }

    function getCachedSearchMeta(id, kind = searchState.kind) {
        return searchState.metaCache[searchMetaCacheKey(id, kind)] || null;
    }

    function getInlineSearchBookmarkCount(item) {
        const count = Number(item?.bookmarkCount);
        return Number.isFinite(count) && count >= 0 ? count : null;
    }

    function getSearchBookmarkCount(item, kind = searchState.kind) {
        const cached = getCachedSearchMeta(item.id, kind);
        if (cached && cached.bookmarkResolved) {
            const count = Number(cached.bookmarkCount);
            if (Number.isFinite(count) && count >= 0) return count;
        }
        return getInlineSearchBookmarkCount(item);
    }

    // 按需补齐逐作品收藏数 meta（收藏数筛选用）。收藏数缓存按 kind+id 全局共享（searchState.metaCache），
    // 故 Search / User / 系列三种预览可复用。isStale() 用于在新一轮筛选发起后提前放弃过期请求。
    async function ensureBookmarkMeta(items, kind, isStale) {
        const missingIds = [];
        const seen = new Set();
        for (const item of items) {
            const id = String(item.id);
            if (seen.has(id)) continue;
            seen.add(id);
            if (getInlineSearchBookmarkCount(item) !== null) continue;
            const cached = getCachedSearchMeta(id, kind);
            if (!cached || !cached.bookmarkResolved) missingIds.push(id);
        }
        if (!missingIds.length) return;

        const fetchMeta = kind === 'novel' ? getNovelBookmarkCountForSearch : getArtworkMeta;
        let cursor = 0;
        const workers = [];
        const workerCount = Math.min(6, missingIds.length);
        for (let i = 0; i < workerCount; i++) {
            workers.push((async () => {
                while (cursor < missingIds.length) {
                    if (isStale()) return;
                    const id = missingIds[cursor++];
                    const cacheKey = searchMetaCacheKey(id, kind);
                    try {
                        const meta = await fetchMeta(id);
                        if (isStale()) return;
                        searchState.metaCache[cacheKey] = {
                            ...(searchState.metaCache[cacheKey] || {}),
                            bookmarkCount: Number(meta?.bookmarkCount ?? -1),
                            bookmarkResolved: true
                        };
                    } catch {
                        if (isStale()) return;
                        searchState.metaCache[cacheKey] = {
                            ...(searchState.metaCache[cacheKey] || {}),
                            bookmarkCount: -1,
                            bookmarkResolved: true,
                            bookmarkError: true
                        };
                    }
                }
            })());
        }
        await Promise.all(workers);
    }

    // 通用「逐作品附加筛选」：对任意作品数组应用同一套 matchSearchFilters。
    // 返回 {filtered, stats}；当 isStale() 在 await 收藏数 meta 期间变 true 时返回 null（调用方应丢弃）。
    async function computeFilteredItems(items, filters, kind, isStale) {
        const source = Array.isArray(items) ? items : [];
        const bookmarkFilterActive = hasBookmarkFilter(filters);
        if (bookmarkFilterActive) {
            await ensureBookmarkMeta(source, kind, isStale);
            if (isStale()) return null;
        }
        const stats = {
            rawCount: source.length,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive
        };
        const filtered = source.filter(item => matchSearchFilters(item, filters, stats, kind));
        stats.filteredCount = filtered.length;
        return {filtered, stats};
    }

    // 内容分级匹配：all=不限 / safe=仅全年龄 / r18plus=R-18+R-18G / r18=仅 R-18 / r18g=仅 R-18G。
    function matchContentRating(xRestrict, content) {
        const xr = Number(xRestrict ?? 0);
        switch (content) {
            case 'safe': return xr === 0;
            case 'r18plus': return xr >= 1;
            case 'r18': return xr === 1;
            case 'r18g': return xr === 2;
            default: return true; // all
        }
    }

    function matchSearchFilters(item, filters, stats, kind = searchState.kind) {
        if (!matchContentRating(item.xRestrict, filters.content)) return false;

        const aiType = Number(item.aiType ?? 0);
        if (filters.ai === 'exclude' && aiType >= 2) return false;
        if (filters.ai === 'only' && aiType < 2) return false;

        if (!matchTagFilters(item, filters)) return false;

        if (kind === 'novel') {
            const wc = Number(item.wordCount ?? 0);
            if (filters.wordsMin !== null && wc < filters.wordsMin) return false;
            if (filters.wordsMax !== null && wc > filters.wordsMax) return false;
            if (hasBookmarkFilter(filters)) {
                const bookmarkCount = getSearchBookmarkCount(item, kind);
                if (bookmarkCount === null) {
                    stats.bookmarkMetaMissing++;
                    return false;
                }
                if (filters.bookmarkMin !== null && bookmarkCount < filters.bookmarkMin) return false;
                if (filters.bookmarkMax !== null && bookmarkCount > filters.bookmarkMax) return false;
            }
            return true;
        }

        const illustType = Number(item.illustType ?? 0);
        if (filters.type === 'illust' && illustType !== 0) return false;
        if (filters.type === 'manga' && illustType !== 1) return false;
        if (filters.type === 'ugoira' && illustType !== 2) return false;

        const pageCount = Number(item.pageCount ?? 0);
        if (filters.pageMin !== null && pageCount < filters.pageMin) return false;
        if (filters.pageMax !== null && pageCount > filters.pageMax) return false;

        if (hasBookmarkFilter(filters)) {
            const bookmarkCount = getSearchBookmarkCount(item, kind);
            if (bookmarkCount === null) {
                stats.bookmarkMetaMissing++;
                return false;
            }
            if (filters.bookmarkMin !== null && bookmarkCount < filters.bookmarkMin) return false;
            if (filters.bookmarkMax !== null && bookmarkCount > filters.bookmarkMax) return false;
        }

        return true;
    }

    // 实际下载时的「附加筛选」判定：拉到作品 meta 后调用，返回 null=通过、否则返回本地化的跳过原因。
    // 与预览用的 matchSearchFilters 同口径（内容分级 / AI / 标签 / 类型 / 页数 / 字数 / 收藏数），
    // 取当前附加筛选 UI 为准；插画/小说按 kind 分别套用对应专属判定。
    function evaluateDownloadFilterSkip(meta, kind) {
        const filters = normalizeSearchFilters(getSearchFiltersFromUI());
        const xr = Number(meta.xRestrict ?? meta.xrestrict ?? 0);
        if (!matchContentRating(xr, filters.content)) {
            return bt('queue.message.skipped-filter-content', '跳过 — 内容分级不符（要求 {label}）',
                {label: bt('search.content.' + filters.content, filters.content)});
        }
        const isAi = meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2;
        if (filters.ai === 'exclude' && isAi) {
            return bt('queue.message.skipped-filter-ai-exclude', '跳过 — AI 作品（附加筛选已设为排除 AI）');
        }
        if (filters.ai === 'only' && !isAi) {
            return bt('queue.message.skipped-filter-ai-only', '跳过 — 非 AI 作品（附加筛选已设为仅 AI）');
        }
        if (!matchTagFilters({tags: Array.isArray(meta.tags) ? meta.tags : []}, filters)) {
            return bt('queue.message.skipped-filter-tags', '跳过 — 标签不匹配附加筛选');
        }
        if (kind === 'novel') {
            const wc = Number(meta.wordCount ?? 0);
            if (wc > 0) {
                if (filters.wordsMin !== null && wc < filters.wordsMin) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
                if (filters.wordsMax !== null && wc > filters.wordsMax) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
            }
        } else {
            const illustType = Number(meta.illustType ?? 0);
            if ((filters.type === 'illust' && illustType !== 0)
                || (filters.type === 'manga' && illustType !== 1)
                || (filters.type === 'ugoira' && illustType !== 2)) {
                return bt('queue.message.skipped-filter-type', '跳过 — 作品类型不符附加筛选');
            }
            const pageCount = Number(meta.pageCount ?? 0);
            if (pageCount > 0) {
                if (filters.pageMin !== null && pageCount < filters.pageMin) return bt('queue.message.skipped-filter-pages', '跳过 — 页数不符附加筛选');
                if (filters.pageMax !== null && pageCount > filters.pageMax) return bt('queue.message.skipped-filter-pages', '跳过 — 页数不符附加筛选');
            }
        }
        if (hasBookmarkFilter(filters)) {
            const bc = Number(meta.bookmarkCount ?? -1);
            if (!Number.isFinite(bc) || bc < 0) {
                return bt('queue.message.skipped-filter-bookmarks-unavailable', '跳过 — 收藏数不可用（无法按附加筛选判定）');
            }
            if (filters.bookmarkMin !== null && bc < filters.bookmarkMin) return bt('queue.message.skipped-filter-bookmarks', '跳过 — 收藏数不符附加筛选');
            if (filters.bookmarkMax !== null && bc > filters.bookmarkMax) return bt('queue.message.skipped-filter-bookmarks', '跳过 — 收藏数不符附加筛选');
        }
        return null;
    }

    async function applyCurrentSearchFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        const kind = searchState.kind;
        searchState.currentFilters = filters;
        setSearchFiltersUI(filters);
        saveSearchFilterPrefs(filters);

        const requestSeq = ++searchState.filterSeq;
        const sourceItems = searchState.rawResults || [];
        const bookmarkFilterActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkFilterActive && sourceItems.some(item => {
            if (getInlineSearchBookmarkCount(item) !== null) return false;
            const cached = getCachedSearchMeta(item.id, kind);
            return !cached || !cached.bookmarkResolved;
        });

        if (bookmarkFilterActive && needsBookmarkMeta && sourceItems.length) {
            document.getElementById('search-results-area').innerHTML =
                `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            document.getElementById('btn-add-all').disabled = true;
        }

        if (bookmarkFilterActive) {
            await ensureBookmarkMeta(sourceItems, kind, () => requestSeq !== searchState.filterSeq);
            if (requestSeq !== searchState.filterSeq) return null;
        }

        const stats = {
            rawCount: sourceItems.length,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive
        };
        const filtered = sourceItems.filter(item => matchSearchFilters(item, filters, stats, kind));
        stats.filteredCount = filtered.length;

        searchState.results = filtered;
        searchState.filterSummary = stats;

        if (searchState.submode === 'batch') {
            const totalPages = batchLocalPageCount();
            if (searchState.localPage > totalPages) searchState.localPage = totalPages;
            if (searchState.localPage < 1) searchState.localPage = 1;
        }

        renderSearchResults();
        renderSearchPagination();
        document.getElementById('btn-add-all').disabled = searchState.results.length === 0;
        updateBatchQueueButtons();

        if (options.setStatus && searchState.currentWord) {
            const prefix = options.statusPrefix || bt('status.search-filters-applied', '已应用筛选：');
            const parts = [searchState.submode === 'batch'
                ? bt('search.batch.summary.fetched', '已抓取去重 {count} 个', {count: stats.rawCount})
                : bt('search.summary.current-page', '当前页 {count} 个', {count: stats.rawCount})];
            if (hasExtraSearchFilter(filters)) {
                parts.push(bt('search.summary.filtered-count', '筛选后 {count} 个', {count: stats.filteredCount}));
                if (stats.bookmarkMetaMissing > 0) {
                    parts.push(bt(
                        'search.summary.bookmark-missing',
                        '{count} 个收藏数不可用已排除',
                        {count: stats.bookmarkMetaMissing}
                    ));
                }
            } else {
                parts.push(bt('status.search-no-extra-filters', '未启用附加筛选'));
            }
            parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
            setStatus(prefix + (uiLang() === 'en-US' ? ' ' : '') + summaryJoin(parts), 'success');
        }

        return stats;
    }

    // 附加筛选卡片现为 Search / User / 系列三模式共享，实时过滤当前预览页：按当前模式分派。
    async function handleSearchFilterChange() {
        const filters = getSearchFiltersFromUI();
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        if (state.mode === QUICK_FETCH_MODE) {
            await quickReapplyFilters();
            return;
        }
        if (state.mode === 'user') {
            await applyUserFilters({setStatus: true});
            return;
        }
        if (state.mode === 'series') {
            await applySeriesFilters({setStatus: true});
            return;
        }
        if (!searchState.currentWord) return;
        if (searchState.submode === 'batch') searchState.localPage = 1;
        await applyCurrentSearchFilters({setStatus: true});
    }

    async function resetSearchFilters() {
        const filters = defaultSearchFilters();
        searchState.currentFilters = filters;
        setSearchFiltersUI(filters);
        saveSearchFilterPrefs(filters);
        if (state.mode === QUICK_FETCH_MODE) {
            await quickReapplyFilters();
            return;
        }
        if (state.mode === 'user') {
            await applyUserFilters({setStatus: true});
            return;
        }
        if (state.mode === 'series') {
            await applySeriesFilters({setStatus: true});
            return;
        }
        if (!searchState.currentWord) return;
        if (searchState.submode === 'batch') searchState.localPage = 1;
        await applyCurrentSearchFilters({setStatus: true});
    }


// ---- PixivBatch facade ----
window.PixivBatch.filters = window.PixivBatch.filters || {};
window.PixivBatch.filters = Object.assign(window.PixivBatch.filters, { getSearchFiltersFromUI, setSearchFiltersUI, resetSearchFilters, computeFilteredItems, applyCurrentSearchFilters, updateExtraFiltersCardVisibility, defaultSearchFilters });
