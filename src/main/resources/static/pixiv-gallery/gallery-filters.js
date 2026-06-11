'use strict';

    function normalizeFilterText(value) {
        if (value == null) return '';
        return String(value).trim().toLowerCase();
    }

    function rememberTagOption(tag) {
        if (!tag || tag.tagId == null) return;
        if (tag.name) state.tagNames.set(tag.tagId, tag.name);
        if (tag.translatedName) state.tagTranslatedNames.set(tag.tagId, tag.translatedName);
    }

    function rememberSeriesOption(series) {
        if (!series || series.seriesId == null) return;
        if (series.title) state.seriesNames.set(series.seriesId, series.title);
    }

    function getFilterBucket(kind) {
        return kind === 'tag' ? state.tagFilters : state.authorFilters;
    }

    function getFilterMode(kind) {
        return getFilterBucket(kind).mode;
    }

    function setFilterMode(kind, mode) {
        const bucket = getFilterBucket(kind);
        if (!FILTER_MODE_META[mode] || bucket.mode === mode) return;
        bucket.mode = mode;
        renderFilterModeButtons(kind);
    }

    function getFilterSelectionMode(kind, id) {
        const bucket = getFilterBucket(kind);
        if (bucket.must.has(id)) return 'must';
        if (bucket.not.has(id)) return 'not';
        if (bucket.or.has(id)) return 'or';
        return null;
    }

    function getFilterSelectionRank(kind, id) {
        const mode = getFilterSelectionMode(kind, id);
        if (mode === 'must') return 0;
        if (mode === 'not') return 1;
        if (mode === 'or') return 2;
        return 3;
    }

    function removeFilterQueueItem(bucket, mode, id) {
        const queue = bucket.queue[mode];
        const index = queue.indexOf(id);
        if (index >= 0) queue.splice(index, 1);
    }

    function clearFilterSelection(kind, id) {
        const bucket = getFilterBucket(kind);
        ['must', 'not', 'or'].forEach(mode => {
            bucket[mode].delete(id);
            removeFilterQueueItem(bucket, mode, id);
        });
    }

    function setFilterSelectionMode(kind, id, mode, metadata = {}) {
        if (!FILTER_MODE_META[mode]) return;
        if (kind === 'tag') {
            rememberTagOption({tagId: id, ...metadata});
        } else if (metadata.name) {
            state.authorNames.set(id, metadata.name);
        }
        const bucket = getFilterBucket(kind);
        clearFilterSelection(kind, id);
        bucket[mode].add(id);
        bucket.queue[mode].push(id);
    }

    function getFilterQueueIndex(kind, id) {
        const mode = getFilterSelectionMode(kind, id);
        if (!mode) return Number.MAX_SAFE_INTEGER;
        const index = getFilterBucket(kind).queue[mode].indexOf(id);
        return index >= 0 ? index : Number.MAX_SAFE_INTEGER;
    }

    function compareFilterSelectionOrder(kind, leftId, rightId) {
        const rankDiff = getFilterSelectionRank(kind, leftId) - getFilterSelectionRank(kind, rightId);
        if (rankDiff !== 0) return rankDiff;
        if (getFilterSelectionMode(kind, leftId) == null) return 0;
        return getFilterQueueIndex(kind, leftId) - getFilterQueueIndex(kind, rightId);
    }

    function getAllFilterIds(kind) {
        const bucket = getFilterBucket(kind);
        return [...new Set([
            ...bucket.queue.must,
            ...bucket.queue.not,
            ...bucket.queue.or,
            ...bucket.must,
            ...bucket.not,
            ...bucket.or,
        ])];
    }

    function getFilterCount(kind) {
        const bucket = getFilterBucket(kind);
        return bucket.must.size + bucket.not.size + bucket.or.size;
    }

    function clearFilterSearch(kind) {
        if (kind === 'tag') {
            clearTimeout(tagSearchTimer);
            state.tagFilterText = '';
            document.getElementById('tagSearchInput').value = '';
            return;
        }
        if (kind === 'series') {
            clearTimeout(seriesSearchTimer);
            state.seriesFilterText = '';
            document.getElementById('seriesSearchInput').value = '';
            return;
        }
        clearTimeout(authorSearchTimer);
        state.authorFilterText = '';
        document.getElementById('authorSearchInput').value = '';
    }

    function clearFilterSelections(kind) {
        const bucket = getFilterBucket(kind);
        bucket.must.clear();
        bucket.not.clear();
        bucket.or.clear();
        bucket.queue.must.length = 0;
        bucket.queue.not.length = 0;
        bucket.queue.or.length = 0;
    }

    function resetFilterKind(kind) {
        clearFilterSelections(kind);
        clearFilterSearch(kind);
        getFilterBucket(kind).mode = 'must';
        renderFilterModeButtons(kind);
        state.page = 0;
        if (kind === 'author') {
            syncAuthorFilterBar();
            renderAuthorChips();
        } else {
            syncTagFilterBar();
            renderTagChips();
        }
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    function renderFilterModeButtons(kind) {
        const containerId = kind === 'tag' ? 'tagModeButtons' : 'authorModeButtons';
        const currentMode = getFilterMode(kind);
        const container = document.getElementById(containerId);
        if (!container) return;
        container.querySelectorAll('[data-filter-mode]').forEach(btn => {
            const mode = btn.dataset.filterMode;
            btn.classList.toggle('active', mode === currentMode);
            btn.setAttribute('aria-pressed', mode === currentMode ? 'true' : 'false');
        });
    }

    function toggleFilterSelection(kind, id, metadata = {}) {
        const bucket = getFilterBucket(kind);
        const targetMode = bucket.mode;
        const currentMode = getFilterSelectionMode(kind, id);
        if (currentMode !== targetMode) {
            setFilterSelectionMode(kind, id, targetMode, metadata);
        } else {
            clearFilterSelection(kind, id);
        }
    }

    function formatAuthorLabel(id) {
        const name = state.authorNames.get(id);
        return name ? `${name} (#${id})` : `#${id}`;
    }

    function formatTagLabel(id) {
        const name = state.tagNames.get(id) || t('tag.default', 'Tag #{id}', {id});
        const translated = state.tagTranslatedNames.get(id);
        if (translated && translated !== name) {
            return `${name} (${translated})`;
        }
        return name;
    }

    function buildFilterSummarySegment(ids, label, modeClass, formatter) {
        if (!ids.length) return null;
        const names = ids.map(id => escapeHtml(formatter(id)));
        const summary = names.length <= 3
            ? names.join(', ')
            : `${names.slice(0, 2).join(', ')} +${names.length}`;
        return `<span class="filter-summary-item ${modeClass}">${escapeHtml(label)}${summary}</span>`;
    }

    function buildTagFilterSummary(ids, label, modeClass) {
        return buildFilterSummarySegment(ids, label, modeClass, formatTagLabel);
    }

    function buildAuthorFilterSummary(ids, label, modeClass) {
        return buildFilterSummarySegment(ids, label, modeClass, formatAuthorLabel);
    }

    function ensureFilterOptionsLoaded() {
        return Promise.all([
            loadTagOptions(),
            loadSeriesOptions(),
            loadAuthorOptions(),
        ]);
    }

    function setFilterPanelOpen(open) {
        const panel = document.getElementById('filterPanel');
        document.getElementById('filterToggle').classList.toggle('active', open);
        panel.classList.toggle('open', open);
        if (open) {
            ensureFilterOptionsLoaded();
            renderTagChips();
            renderSeriesFilterChips();
            renderAuthorChips();
        }
    }

    function clearNavigationFilterQuery() {
        const url = new URL(location.href);
        let changed = false;
        GALLERY_FILTER_QUERY_KEYS.forEach(key => {
            if (url.searchParams.has(key)) {
                url.searchParams.delete(key);
                changed = true;
            }
        });
        if (!changed) return;
        const nextUrl = url.pathname + (url.search ? url.search : '') + url.hash;
        history.replaceState(history.state, '', nextUrl);
    }

    function parsePositiveIdList(raw) {
        return [...new Set(String(raw || '')
            .split(',')
            .map(value => Number(value))
            .filter(value => Number.isInteger(value) && value > 0))];
    }

    function syncTagFilterBar() {
        const bar = document.getElementById('tagFilterBar');
        const labelEl = bar.querySelector('.label');
        if (labelEl) labelEl.textContent = t('summary.tags', 'Tag Filter:');
        if (getFilterCount('tag') === 0 || state.view !== 'all') {
            bar.classList.remove('visible');
            return;
        }
        const parts = [
            buildTagFilterSummary([...state.tagFilters.must], t('summary.mode.must', 'Must Have '), 'mode-must'),
            buildTagFilterSummary([...state.tagFilters.not], t('summary.mode.not', 'Must Not Have '), 'mode-not'),
            buildTagFilterSummary([...state.tagFilters.or], t('summary.mode.or', 'Either '), 'mode-or'),
        ].filter(Boolean);
        document.getElementById('tagFilterName').innerHTML = parts.join('');
        bar.classList.add('visible');
    }

    function syncAuthorFilterBar() {
        const bar = document.getElementById('authorFilterBar');
        const labelEl = bar.querySelector('.label');
        if (labelEl) labelEl.textContent = t('summary.authors', 'Author Filter:');
        if (getFilterCount('author') === 0 || state.view !== 'all') {
            bar.classList.remove('visible');
            return;
        }
        const parts = [
            buildAuthorFilterSummary([...state.authorFilters.must], t('summary.mode.must', 'Must Have '), 'mode-must'),
            buildAuthorFilterSummary([...state.authorFilters.not], t('summary.mode.not', 'Must Not Have '), 'mode-not'),
            buildAuthorFilterSummary([...state.authorFilters.or], t('summary.mode.or', 'Either '), 'mode-or'),
        ].filter(Boolean);
        document.getElementById('authorFilterName').innerHTML = parts.join('');
        bar.classList.add('visible');
    }

    function syncSeriesFilterBar() {
        const bar = document.getElementById('seriesFilterBar');
        if (!bar) return;
        const labelEl = bar.querySelector('.label');
        if (labelEl) labelEl.textContent = t('summary.series', 'Series Filter:');
        if (!state.seriesFilter.id || state.view !== 'all') {
            bar.classList.remove('visible');
            return;
        }
        const title = state.seriesFilter.title || state.seriesNames.get(state.seriesFilter.id) ||
            t('series.default', 'Series #{id}', {id: state.seriesFilter.id});
        document.getElementById('seriesFilterName').textContent = `${title} (#${state.seriesFilter.id})`;
        bar.classList.add('visible');
    }

    async function resolveIncomingTagOption(params) {
        const tagId = Number(params.get('filterTagId'));
        const tagName = (params.get('filterTag') || '').trim();
        const translatedName = (params.get('filterTagTranslated') || '').trim();
        const normalizedTagName = normalizeFilterText(tagName);
        const normalizedTranslatedName = normalizeFilterText(translatedName);

        if (Number.isFinite(tagId) && tagId > 0) {
            const loaded = state.tags.find(t => t.tagId === tagId);
            if (loaded) return loaded;
        }

        if (normalizedTagName) {
            const loadedByName = state.tags.find(t => normalizeFilterText(t.name) === normalizedTagName);
            if (loadedByName) return loadedByName;
        }

        if (normalizedTagName || normalizedTranslatedName) {
            try {
                const lookupParams = new URLSearchParams();
                if (tagName) lookupParams.set('name', tagName);
                if (translatedName) lookupParams.set('translatedName', translatedName);
                return await api('/api/gallery/tags/lookup?' + lookupParams.toString());
            } catch (_) {
                // Ignore lookup failures and fall back below.
            }
        }

        if (Number.isFinite(tagId) && tagId > 0) {
            return {
                tagId,
                name: tagName || state.tagNames.get(tagId) || t('tag.default', 'Tag #{id}', {id: tagId}),
                translatedName: translatedName || state.tagTranslatedNames.get(tagId) || null,
                artworkCount: 0,
            };
        }
        return null;
    }

    async function applyNavigationFiltersFromQuery() {
        const params = new URLSearchParams(location.search);
        const hasNavigationFilter = GALLERY_FILTER_QUERY_KEYS.some(key => params.has(key));
        if (!hasNavigationFilter) return null;

        let changed = false;
        const requestedView = readViewParam(params);

        const tag = await resolveIncomingTagOption(params);
        if (tag && tag.tagId != null) {
            clearFilterSelections('tag');
            setFilterSelectionMode('tag', tag.tagId, 'must', {
                name: tag.name,
                translatedName: tag.translatedName,
            });
            setFilterMode('tag', 'must');
            changed = true;
        }

        const authorId = Number(params.get('filterAuthorId'));
        const authorName = (params.get('filterAuthorName') || '').trim();
        if (Number.isFinite(authorId) && authorId > 0) {
            clearFilterSelections('author');
            setFilterSelectionMode('author', authorId, 'must', {name: authorName});
            setFilterMode('author', 'must');
            changed = true;
        }

        const seriesId = Number(params.get('filterSeriesId'));
        const seriesTitle = (params.get('filterSeriesTitle') || '').trim();
        if (Number.isFinite(seriesId) && seriesId > 0) {
            state.seriesFilter = {id: seriesId, title: seriesTitle};
            if (seriesTitle) state.seriesNames.set(seriesId, seriesTitle);
            changed = true;
        }

        const collectionIds = parsePositiveIdList(params.get('collectionIds'));
        if (collectionIds.length) {
            state.collectionIds = new Set(collectionIds);
            changed = true;
        }

        if (params.get('openFilter') === '1') {
            setFilterPanelOpen(true);
        }

        if (changed) {
            state.page = 0;
            syncTagFilterBar();
            syncAuthorFilterBar();
            syncSeriesFilterBar();
            renderCollections();
            renderCollectionFilterChips();
            renderTagChips();
            renderSeriesFilterChips();
            renderAuthorChips();
            updateFilterBadge();
        }

        clearNavigationFilterQuery();
        return requestedView;
    }

    function updateFilterBadge() {
        let n = 0;
        if (state.r18 !== 'any') n++;
        if (state.ai !== 'any') n++;
        n += state.formats.size;
        n += state.collectionIds.size;
        n += getFilterCount('tag');
        n += getFilterCount('author');
        if (state.seriesFilter.id) n++;
        const badge = document.getElementById('filterBadge');
        badge.textContent = n;
        badge.classList.toggle('visible', n > 0);
    }

    // ---------- Tag filter ----------
    async function loadTagOptions() {
        if (state.tagsLoaded) return state.tags;
        if (tagOptionsPromise) return tagOptionsPromise;
        tagOptionsPromise = (async () => {
            try {
                const resp = await api('/api/gallery/tags?limit=500');
                state.tags = resp.tags || [];
                state.tags.forEach(rememberTagOption);
                state.tagsLoaded = true;
            } catch (e) {
                console.error(t('log.tags-failed', '加载标签失败'), e);
                state.tags = [];
            }
            renderTagChips();
            return state.tags;
        })().finally(() => {
            tagOptionsPromise = null;
        });
        return tagOptionsPromise;
    }

    function renderTagChips() {
        const box = document.getElementById('filterTagChips');
        const toggleBtn = document.getElementById('tagExpandToggle');
        if (!state.tagsLoaded && !state.tags.length) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.loading', 'Loading...')) + '</span>';
            toggleBtn.style.display = 'none';
            return;
        }
        const kw = state.tagFilterText.trim().toLowerCase();
        let filtered = kw
            ? state.tags.filter(t =>
                (t.name && t.name.toLowerCase().includes(kw)) ||
                (t.translatedName && t.translatedName.toLowerCase().includes(kw)))
            : state.tags.slice();

        // 已选标签置顶，未选按原顺序（后端已按使用量降序）
        filtered.sort((a, b) => compareFilterSelectionOrder('tag', a.tagId, b.tagId));

        const loadedIds = new Set(filtered.map(t => t.tagId));
        const missing = getAllFilterIds('tag').filter(id => !loadedIds.has(id));
        if (missing.length) {
            const stubs = missing.map(id => ({
                tagId: id,
                name: state.tagNames.get(id) || t('tag.default', 'Tag #{id}', {id}),
                translatedName: state.tagTranslatedNames.get(id) || null,
                artworkCount: 0,
            }));
            filtered = stubs.concat(filtered);
            filtered.sort((a, b) => compareFilterSelectionOrder('tag', a.tagId, b.tagId));
        }

        const expanded = state.tagsExpanded || kw.length > 0;

        if (filtered.length === 0) {
            box.innerHTML = state.tags.length
                ? '<span class="chip-empty">' + escapeHtml(t('status.no-matching-tags', 'No matching tags')) + '</span>'
                : '<span class="chip-empty">' + escapeHtml(t('status.no-tags', 'No tags')) + '</span>';
            box.classList.remove('has-overflow');
            box.classList.toggle('expanded', expanded);
            toggleBtn.style.display = 'none';
            return;
        }

        box.innerHTML = filtered.map(t => {
            const mode = getFilterSelectionMode('tag', t.tagId);
            const meta = mode ? FILTER_MODE_META[mode] : null;
            const trans = t.translatedName
                ? `<span class="tag-trans">${escapeHtml(t.translatedName)}</span>`
                : '';
            const count = t.artworkCount > 0
                ? `<span class="chip-count">${t.artworkCount}</span>`
                : '';
            return `<button class="chip ${mode ? `filter-selected ${meta.className}` : ''}" type="button" data-tag-id="${t.tagId}">
                ${escapeHtml(t.name || '')}
                ${trans}
                ${count}
            </button>`;
        }).join('');
        const tagById = new Map(filtered.map(t => [String(t.tagId), t]));
        box.querySelectorAll('.chip[data-tag-id]').forEach(chip => {
            chip.addEventListener('click', () => toggleTagFilter(tagById.get(chip.dataset.tagId)));
        });
        box.classList.toggle('expanded', expanded);

        requestAnimationFrame(() => {
            const hasOverflow = box.scrollHeight > TAG_COLLAPSED_MAX_HEIGHT + 2;
            box.classList.toggle('has-overflow', hasOverflow && !expanded);
            if (hasOverflow || expanded) {
                toggleBtn.style.display = '';
                toggleBtn.textContent = state.tagsExpanded
                    ? t('filter.collapse', 'Collapse')
                    : t('filter.expand', 'Expand All');
                toggleBtn.classList.toggle('active', state.tagsExpanded);
            } else {
                toggleBtn.style.display = 'none';
            }
        });
    }

    function toggleTagFilter(tag) {
        if (!tag || tag.tagId == null) return;
        toggleFilterSelection('tag', Number(tag.tagId), {
            name: tag.name,
            translatedName: tag.translatedName,
        });
        state.page = 0;
        syncTagFilterBar();
        renderTagChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    function clearTagFilter() {
        clearFilterSelections('tag');
        state.page = 0;
        syncTagFilterBar();
        renderTagChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    let tagSearchTimer = null;
    // ---------- Series filter ----------
    async function loadSeriesOptions() {
        if (state.seriesOptionsLoaded) return state.seriesOptions;
        if (seriesOptionsPromise) return seriesOptionsPromise;
        seriesOptionsPromise = (async () => {
            try {
                const params = new URLSearchParams();
                params.set('page', 0);
                params.set('size', SERIES_FILTER_OPTIONS_SIZE);
                params.set('sort', 'artworks');
                const resp = await api('/api/series/paged?' + params.toString());
                state.seriesOptions = resp.content || [];
                state.seriesOptions.forEach(rememberSeriesOption);
                state.seriesOptionsLoaded = true;
            } catch (e) {
                console.error(t('log.series-options-failed', '加载系列选项失败'), e);
                state.seriesOptions = [];
            }
            renderSeriesFilterChips();
            syncSeriesFilterBar();
            return state.seriesOptions;
        })().finally(() => {
            seriesOptionsPromise = null;
        });
        return seriesOptionsPromise;
    }

    function renderSeriesFilterChips() {
        const box = document.getElementById('filterSeriesChips');
        const toggleBtn = document.getElementById('seriesExpandToggle');
        if (!box || !toggleBtn) return;
        if (!state.seriesOptionsLoaded && !state.seriesOptions.length && !state.seriesFilter.id) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.loading', 'Loading...')) + '</span>';
            toggleBtn.style.display = 'none';
            return;
        }
        const kw = state.seriesFilterText.trim().toLowerCase();
        const selectedId = state.seriesFilter.id ? Number(state.seriesFilter.id) : null;
        let filtered = kw
            ? state.seriesOptions.filter(s =>
                (s.title && s.title.toLowerCase().includes(kw)) ||
                (s.authorName && s.authorName.toLowerCase().includes(kw)) ||
                String(s.seriesId).includes(kw))
            : state.seriesOptions.slice();

        if (selectedId && !filtered.some(s => Number(s.seriesId) === selectedId)) {
            const loaded = state.seriesOptions.find(s => Number(s.seriesId) === selectedId);
            filtered = [{
                seriesId: selectedId,
                title: state.seriesFilter.title || state.seriesNames.get(selectedId) || loaded?.title,
                artworkCount: loaded?.artworkCount || 0,
                authorName: loaded?.authorName || null,
            }].concat(filtered);
        }

        filtered.sort((a, b) => {
            const aSelected = selectedId != null && Number(a.seriesId) === selectedId;
            const bSelected = selectedId != null && Number(b.seriesId) === selectedId;
            if (aSelected !== bSelected) return aSelected ? -1 : 1;
            return 0;
        });

        const expanded = state.seriesExpanded || kw.length > 0;

        if (filtered.length === 0) {
            box.innerHTML = state.seriesOptions.length
                ? '<span class="chip-empty">' + escapeHtml(t('status.no-matching-series', 'No matching manga series')) + '</span>'
                : '<span class="chip-empty">' + escapeHtml(t('status.no-series', 'No manga series')) + '</span>';
            box.classList.remove('has-overflow');
            box.classList.toggle('expanded', expanded);
            toggleBtn.style.display = 'none';
            return;
        }

        box.innerHTML = filtered.map(s => {
            const id = Number(s.seriesId);
            const active = selectedId === id;
            const title = s.title || t('series.default', 'Series #{id}', {id});
            const author = s.authorName
                ? `<span class="tag-trans">${escapeHtml(s.authorName)}</span>`
                : '';
            const count = s.artworkCount > 0
                ? `<span class="chip-count">${s.artworkCount}</span>`
                : '';
            return `<button class="chip ${active ? 'active' : ''}" type="button" data-series-id="${id}" title="#${id}">
                ${escapeHtml(title)}
                ${author}
                ${count}
            </button>`;
        }).join('');
        const seriesById = new Map(filtered.map(s => [String(s.seriesId), s]));
        box.querySelectorAll('.chip[data-series-id]').forEach(chip => {
            chip.addEventListener('click', () => {
                const id = Number(chip.dataset.seriesId);
                const opt = seriesById.get(chip.dataset.seriesId);
                toggleSeriesFilter(id, opt ? opt.title : state.seriesNames.get(id));
            });
        });
        box.classList.toggle('expanded', expanded);

        requestAnimationFrame(() => {
            const hasOverflow = box.scrollHeight > SERIES_CHIPS_COLLAPSED_MAX_HEIGHT + 2;
            box.classList.toggle('has-overflow', hasOverflow && !expanded);
            if (hasOverflow || expanded) {
                toggleBtn.style.display = '';
                toggleBtn.textContent = state.seriesExpanded
                    ? t('filter.collapse', 'Collapse')
                    : t('filter.expand', 'Expand All');
                toggleBtn.classList.toggle('active', state.seriesExpanded);
            } else {
                toggleBtn.style.display = 'none';
            }
        });
    }

    function toggleSeriesFilter(seriesId, seriesTitle) {
        const id = Number(seriesId);
        if (!Number.isFinite(id) || id <= 0) return;
        if (state.seriesFilter.id === id) {
            state.seriesFilter = {id: null, title: ''};
        } else {
            state.seriesFilter = {id, title: seriesTitle || ''};
            if (seriesTitle) state.seriesNames.set(id, seriesTitle);
        }
        refreshSeriesFilter();
    }

    function resetSeriesFilterOptions() {
        state.seriesFilter = {id: null, title: ''};
        state.seriesExpanded = false;
        clearFilterSearch('series');
        refreshSeriesFilter();
    }

    function refreshSeriesFilter() {
        state.page = 0;
        syncSeriesFilterBar();
        renderSeriesFilterChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    let seriesSearchTimer = null;
    // ---------- Author filter ----------
    async function loadAuthorOptions() {
        if (state.authorOptionsLoaded) return state.authorOptions;
        if (authorOptionsPromise) return authorOptionsPromise;
        authorOptionsPromise = (async () => {
            try {
                const params = new URLSearchParams();
                params.set('page', 0);
                params.set('size', AUTHOR_FILTER_OPTIONS_SIZE);
                params.set('sort', 'artworks');
                const resp = await api('/api/authors/paged?' + params.toString());
                state.authorOptions = resp.content || [];
                state.authorOptions.forEach(author => {
                    if (author && author.authorId != null && author.name) {
                        state.authorNames.set(author.authorId, author.name);
                    }
                });
                state.authorOptionsLoaded = true;
            } catch (e) {
                console.error(t('log.author-options-failed', '加载作者选项失败'), e);
                state.authorOptions = [];
            }
            renderAuthorChips();
            return state.authorOptions;
        })().finally(() => {
            authorOptionsPromise = null;
        });
        return authorOptionsPromise;
    }

    function renderAuthorChips() {
        const box = document.getElementById('filterAuthorChips');
        const toggleBtn = document.getElementById('authorExpandToggle');
        if (!state.authorOptionsLoaded && !state.authorOptions.length && getFilterCount('author') === 0) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.loading', 'Loading...')) + '</span>';
            toggleBtn.style.display = 'none';
            return;
        }
        if (!state.authorOptions.length && getFilterCount('author') === 0) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.no-authors', 'No authors')) + '</span>';
            toggleBtn.style.display = 'none';
            return;
        }
        const kw = state.authorFilterText.trim().toLowerCase();
        let filtered = kw
            ? state.authorOptions.filter(a =>
                (a.name && a.name.toLowerCase().includes(kw)) ||
                String(a.authorId).includes(kw))
            : state.authorOptions.slice();

        // 已选作者置顶
        filtered.sort((a, b) => compareFilterSelectionOrder('author', a.authorId, b.authorId));

        // 若选中的作者不在加载的前 N 个里，补齐到列表首部
        const loadedIds = new Set(filtered.map(a => a.authorId));
        const missing = getAllFilterIds('author').filter(id => !loadedIds.has(id));
        if (missing.length) {
            const stubs = missing.map(id => ({
                authorId: id,
                name: state.authorNames.get(id) || t('author.default', 'Author {id}', {id}),
                artworkCount: 0,
            }));
            filtered = stubs.concat(filtered);
            filtered.sort((a, b) => compareFilterSelectionOrder('author', a.authorId, b.authorId));
        }

        const expanded = state.authorsExpanded || kw.length > 0;

        if (filtered.length === 0) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.no-matching-authors', 'No matching authors')) + '</span>';
            box.classList.remove('has-overflow');
            box.classList.toggle('expanded', expanded);
            toggleBtn.style.display = 'none';
            return;
        }

        box.innerHTML = filtered.map(a => {
            const mode = getFilterSelectionMode('author', a.authorId);
            const meta = mode ? FILTER_MODE_META[mode] : null;
            const count = a.artworkCount > 0
                ? `<span class="chip-count">${a.artworkCount}</span>`
                : '';
            return `<button class="chip ${mode ? `filter-selected ${meta.className}` : ''}" type="button" data-author-id="${a.authorId}" title="#${a.authorId}">
                ${escapeHtml(a.name || t('author.default', 'Author {id}', {id: a.authorId}))}
                ${count}
            </button>`;
        }).join('');
        box.querySelectorAll('.chip[data-author-id]').forEach(chip => {
            chip.addEventListener('click', () => {
                const id = Number(chip.dataset.authorId);
                const opt = state.authorOptions.find(a => a.authorId === id);
                toggleAuthorFilter(id, opt ? opt.name : state.authorNames.get(id));
            });
        });
        box.classList.toggle('expanded', expanded);

        requestAnimationFrame(() => {
            const hasOverflow = box.scrollHeight > AUTHOR_CHIPS_COLLAPSED_MAX_HEIGHT + 2;
            box.classList.toggle('has-overflow', hasOverflow && !expanded);
            if (hasOverflow || expanded) {
                toggleBtn.style.display = '';
                toggleBtn.textContent = state.authorsExpanded
                    ? t('filter.collapse', 'Collapse')
                    : t('filter.expand', 'Expand All');
                toggleBtn.classList.toggle('active', state.authorsExpanded);
            } else {
                toggleBtn.style.display = 'none';
            }
        });
    }

    function toggleAuthorFilter(authorId, authorName) {
        toggleFilterSelection('author', authorId, {name: authorName});
        refreshAuthorFilter();
    }

    function setAuthorFilterExclusive(authorId, authorName) {
        clearFilterSelections('author');
        if (authorId) {
            setFilterSelectionMode('author', authorId, 'must', {name: authorName});
        }
        setFilterMode('author', 'must');
        refreshAuthorFilter({switchToAll: true});
    }

    function clearAuthorFilter() {
        clearFilterSelections('author');
        refreshAuthorFilter();
    }

    function refreshAuthorFilter({switchToAll = false} = {}) {
        state.page = 0;
        syncAuthorFilterBar();
        renderAuthorChips();
        updateFilterBadge();
        if (switchToAll || state.view !== 'all') {
            switchView('all');
        } else {
            loadGallery();
        }
    }

    function setSeriesFilterExclusive(seriesId, seriesTitle) {
        if (seriesId) {
            state.seriesFilter = {id: Number(seriesId), title: seriesTitle || ''};
            if (seriesTitle) state.seriesNames.set(Number(seriesId), seriesTitle);
        } else {
            state.seriesFilter = {id: null, title: ''};
        }
        refreshSeriesFilter();
    }

    function clearSeriesFilter() {
        state.seriesFilter = {id: null, title: ''};
        refreshSeriesFilter();
    }

    function buildGalleryFilterHref({seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (seriesId != null) params.set('filterSeriesId', String(seriesId));
        if (seriesTitle) params.set('filterSeriesTitle', seriesTitle);
        return `/pixiv-gallery.html?${params.toString()}`;
    }

    function buildSeriesDirectoryHref({seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('type', 'artwork');
        if (seriesId != null) params.set('seriesId', String(seriesId));
        if (seriesTitle) params.set('seriesTitle', seriesTitle);
        return `/pixiv-series.html?${params.toString()}`;
    }

    let authorSearchTimer = null;

// ---- PixivGallery facade ----
window.PixivGallery.filters = Object.assign(window.PixivGallery.filters || {}, { normalizeFilterText, rememberTagOption, rememberSeriesOption, getFilterBucket, getFilterMode, setFilterMode, getFilterSelectionMode, getFilterSelectionRank, removeFilterQueueItem, clearFilterSelection, setFilterSelectionMode, getFilterQueueIndex, compareFilterSelectionOrder, getAllFilterIds, getFilterCount, clearFilterSearch, clearFilterSelections, resetFilterKind, renderFilterModeButtons, toggleFilterSelection, formatAuthorLabel, formatTagLabel, buildFilterSummarySegment, buildTagFilterSummary, buildAuthorFilterSummary, ensureFilterOptionsLoaded, setFilterPanelOpen, clearNavigationFilterQuery, parsePositiveIdList, syncTagFilterBar, syncAuthorFilterBar, syncSeriesFilterBar, resolveIncomingTagOption, applyNavigationFiltersFromQuery, updateFilterBadge, loadTagOptions, renderTagChips, toggleTagFilter, clearTagFilter, loadSeriesOptions, renderSeriesFilterChips, toggleSeriesFilter, resetSeriesFilterOptions, refreshSeriesFilter, loadAuthorOptions, renderAuthorChips, toggleAuthorFilter, setAuthorFilterExclusive, clearAuthorFilter, refreshAuthorFilter, setSeriesFilterExclusive, clearSeriesFilter, buildGalleryFilterHref, buildSeriesDirectoryHref });
