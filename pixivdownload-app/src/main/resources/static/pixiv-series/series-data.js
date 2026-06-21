'use strict';

    async function loadSeries() {
        if (!state.seriesId) {
            document.getElementById('seriesTitle').textContent = t('status.missing-id', 'Missing series id');
            document.getElementById('seriesStatus').textContent = modeText(
                'status.missing-id-hint',
                'Open this page from an artwork in a manga series.',
                'Open this page from a novel in a novel series.'
            );
            return;
        }
        renderLoading();
        if (isNovelMode()) {
            state.detail = buildNovelDetail();
            let detailLangs = [];
            // 同步拉取小说系列封面/简介（标题/作者由章节列表回填）。
            try {
                const series = await api(`/api/gallery/novel/series/${state.seriesId}`);
                if (series) {
                    state.detail = Object.assign({}, state.detail, {
                        title: series.title || state.detail.title,
                        authorId: series.authorId != null ? series.authorId : state.detail.authorId,
                        description: series.description,
                        coverExt: series.coverExt,
                        tags: Array.isArray(series.tags) ? series.tags : state.detail.tags,
                    });
                    detailLangs = Array.isArray(series.translatedLanguages) ? series.translatedLanguages : [];
                }
            } catch (_) {
                // 404 表示尚未观测过该系列：保留章节回填出的字段即可。
            }
            setupNovelContentControls(detailLangs);
            renderSeriesHeader();
            renderMeta();
            await loadArtworks();
            return;
        }
        try {
            const detail = await api(`/api/series/${state.seriesId}`);
            state.detail = detail;
            renderSeriesHeader();
            renderMeta();
            await loadArtworks();
        } catch (e) {
            document.getElementById('seriesTitle').textContent = modeText(
                'status.load-failed-title',
                'Unable to load series',
                'Unable to load novel series'
            );
            document.getElementById('seriesStatus').textContent = t('status.load-failed', 'Load failed: {message}', {message: e.message});
            document.getElementById('chapterGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
        }
    }

    async function loadArtworks() {
        if (isNovelMode()) {
            await loadNovels();
            return;
        }
        const params = new URLSearchParams();
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', 'series');
        params.set('order', 'asc');
        params.set('seriesId', String(state.seriesId));
        if (state.search) params.set('search', state.search);
        try {
            document.getElementById('seriesStatus').textContent = t('status.loading-artworks', 'Loading chapters...');
            const resp = await api('/api/gallery/artworks?' + params.toString());
            state.items = resp.content || [];
            state.totalPages = resp.totalPages || 0;
            state.totalElements = resp.totalElements || 0;
            if (!state.items.length && state.totalElements > 0 && state.page > 0) {
                state.page = 0;
                await loadArtworks();
                return;
            }
            renderMeta();
            renderStatus();
            renderGrid(state.items);
            renderPagination();
        } catch (e) {
            document.getElementById('seriesStatus').textContent = t('status.load-failed', 'Load failed: {message}', {message: e.message});
            document.getElementById('chapterGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
        }
    }

    async function loadNovels() {
        const params = new URLSearchParams();
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', 'series');
        params.set('order', 'asc');
        params.set('seriesId', String(state.seriesId));
        if (state.search) params.set('search', state.search);
        try {
            document.getElementById('seriesStatus').textContent = t('status.loading-novels', 'Loading novels...');
            const resp = await api('/api/gallery/novels?' + params.toString());
            state.items = resp.content || [];
            state.totalPages = resp.totalPages || 0;
            state.totalElements = resp.totalElements || 0;
            if (!state.items.length && state.totalElements > 0 && state.page > 0) {
                state.page = 0;
                await loadNovels();
                return;
            }
            state.detail = buildNovelDetail();
            // 当前选了内容语言：一次性拉本页章节的译后标题 + 该语言系列名（缺失时回退原文）。
            if (activeContentLang) {
                await Promise.all([
                    fetchSeriesTitleForLang(activeContentLang),
                    fetchChapterTitlesForLang(activeContentLang, state.items),
                ]);
            }
            renderSeriesHeader();
            renderMeta();
            renderStatus();
            renderGrid(state.items);
            renderPagination();
        } catch (e) {
            document.getElementById('seriesStatus').textContent = t('status.load-failed', 'Load failed: {message}', {message: e.message});
            document.getElementById('chapterGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
        }
    }

    function buildNovelDetail() {
        const first = state.items.find(item => Number(item.seriesId) === Number(state.seriesId)) || state.items[0] || {};
        const updatedTime = state.items.reduce((max, item) => Math.max(max, Number(item.time || 0)), 0);
        const previous = state.detail || {};
        return {
            seriesId: state.seriesId,
            title: previous.title || state.initialSeriesTitle
                || modeText('series.default', 'Series #{id}', 'Series #{id}', {id: state.seriesId}),
            authorId: previous.authorId != null ? previous.authorId : (first.authorId || null),
            authorName: previous.authorName || first.authorName || '',
            novelCount: state.totalElements || state.items.length || 0,
            updatedTime: updatedTime || previous.updatedTime || null,
            description: previous.description,
            coverExt: previous.coverExt,
            tags: Array.isArray(previous.tags) ? previous.tags : [],
        };
    }
