'use strict';
(function () {
    const runtime = window.PixivBatch && window.PixivBatch.scheduleSources;
    if (!runtime) return;

    const MODULE_URL = '/pixiv-batch/pixiv-schedule-sources.js';
    const SOURCE = Object.freeze({
        USER_NEW: 'user-new',
        USER_REQUEST: 'user-request',
        SEARCH: 'search',
        SERIES: 'series',
        MY_BOOKMARKS: 'my-bookmarks',
        FOLLOW_LATEST: 'follow-latest',
        COLLECTION: 'collection'
    });
    const LEGACY_SOURCE = Object.freeze({
        USER_NEW: SOURCE.USER_NEW,
        USER_REQUEST: SOURCE.USER_REQUEST,
        SEARCH: SOURCE.SEARCH,
        SERIES: SOURCE.SERIES,
        MY_BOOKMARKS: SOURCE.MY_BOOKMARKS,
        FOLLOW_LATEST: SOURCE.FOLLOW_LATEST,
        COLLECTION: SOURCE.COLLECTION
    });
    const FETCH_LIMIT_PRESENTATION = Object.freeze({
        namespace: 'batch',
        watermarkHintKey: 'schedule.pixiv.fetch-limit.hint.watermark',
        perRunHintKey: 'schedule.pixiv.fetch-limit.hint.per-run',
        fullFetchConfirmKey: 'schedule.pixiv.confirm.full-fetch'
    });

    function canonicalSourceType(value) {
        const normalized = value == null ? '' : String(value).trim();
        return LEGACY_SOURCE[normalized] || normalized;
    }

    function parsePixivUserInput(raw) {
        const value = String(raw || '').trim();
        if (!value) return '';
        if (/^\d+$/.test(value)) return value;
        const match = value.match(/\/users\/(\d+)/);
        return match ? match[1] : '';
    }

    function parseParams(task) {
        try {
            const parsed = JSON.parse((task || {}).paramsJson || '{}');
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
        } catch (e) {
            return null;
        }
    }

    function taskSourceType(task) {
        return canonicalSourceType(task && (task.sourceType || task.type));
    }

    function normalizedQuickSource(context) {
        const value = context && context.quickSource;
        if (!value || typeof value !== 'object') return null;
        const sourceType = canonicalSourceType(value.sourceType || value.type);
        if (!sourceType) return null;
        return {
            sourceType,
            source: value.source && typeof value.source === 'object' ? value.source : {},
            kind: value.kind || 'illust',
            label: value.label || ''
        };
    }

    function modeFor(sourceType) {
        if (sourceType === SOURCE.SEARCH) return 'search';
        if (sourceType === SOURCE.SERIES) return 'series';
        if (sourceType === SOURCE.MY_BOOKMARKS
            || sourceType === SOURCE.FOLLOW_LATEST
            || sourceType === SOURCE.COLLECTION) return QUICK_FETCH_MODE;
        return 'user';
    }

    function matches(sourceType, context) {
        const mode = context && context.mode;
        const quick = normalizedQuickSource(context);
        if (mode === QUICK_FETCH_MODE) return !!quick && quick.sourceType === sourceType;
        if (sourceType === SOURCE.USER_REQUEST) {
            return mode === 'user' && state.settings.userKind === 'request';
        }
        if (sourceType === SOURCE.USER_NEW) {
            return mode === 'user' && state.settings.userKind !== 'request';
        }
        return modeFor(sourceType) === mode;
    }

    function fetchLimitMode(sourceType, source) {
        if (sourceType === SOURCE.USER_NEW || sourceType === SOURCE.USER_REQUEST
            || sourceType === SOURCE.FOLLOW_LATEST) return 'watermark';
        if (sourceType === SOURCE.MY_BOOKMARKS || sourceType === SOURCE.COLLECTION) return 'per-run';
        if (sourceType === SOURCE.SEARCH && source && source.maxPages === -1) {
            return (source.order || 'date_d') === 'date_d' ? 'watermark' : 'per-run';
        }
        return null;
    }

    function currentSearchMaxPages() {
        const batchSubmode = searchState.submode === 'batch'
            || ((document.querySelector('input[name="search-submode"]:checked') || {}).value === 'batch');
        if (!batchSubmode) return 1;
        const raw = parseInt((document.getElementById('batch-end-page') || {}).value, 10);
        return (isAdmin && raw === -1) ? -1 : Math.max(1, Number.isFinite(raw) ? raw : 3);
    }

    function sourceFromUi(sourceType, context) {
        const quick = normalizedQuickSource(context);
        if (context.mode === QUICK_FETCH_MODE) {
            if (!quick || quick.sourceType !== sourceType) {
                throw new Error(bt('schedule.error.quick-source',
                    '请先展开具体的作品列表后再存为计划任务'));
            }
            return {source: quick.source, kind: quick.kind, label: quick.label};
        }
        if (sourceType === SOURCE.USER_NEW || sourceType === SOURCE.USER_REQUEST) {
            const userId = parsePixivUserInput((document.getElementById('user-id-input') || {}).value || '');
            if (!userId) {
                throw new Error(bt('schedule.error.user-id', '请填写有效的画师 ID 或画师主页链接'));
            }
            const kind = sourceType === SOURCE.USER_REQUEST
                ? 'illust' : (state.settings.userKind === 'novel' ? 'novel' : 'illust');
            return {source: {userId}, kind, label: ''};
        }
        if (sourceType === SOURCE.SEARCH) {
            const word = ((document.getElementById('search-word') || {}).value || '').trim();
            if (!word) throw new Error(bt('schedule.error.word', '请填写搜索关键词'));
            const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
            const sMode = (document.querySelector('input[name="search-smode"]:checked') || {}).value || 's_tag';
            const order = (document.querySelector('input[name="search-order"]:checked') || {}).value || 'date_d';
            const pixivMode = ['r18', 'r18g', 'r18plus'].includes(uiMode) ? 'r18' : uiMode;
            return {
                source: {word, order, mode: pixivMode, sMode, maxPages: currentSearchMaxPages()},
                kind: state.settings.searchKind === 'novel' ? 'novel' : 'illust',
                label: ''
            };
        }
        if (sourceType === SOURCE.SERIES) {
            if (!seriesState.seriesId) {
                throw new Error(bt('schedule.error.series-id', '请先在上方解析并预览系列'));
            }
            return {
                source: {seriesId: String(seriesState.seriesId)},
                kind: seriesState.kind === 'novel' ? 'novel' : 'illust',
                label: ''
            };
        }
        throw new Error(bt('schedule.error.mode', '当前模式不支持创建计划任务'));
    }

    function snapshotFilters() {
        const value = getSearchFiltersFromUI();
        return {
            content: value.content,
            aiFilter: value.ai,
            tagsExact: value.tagsExact,
            tagsFuzzy: value.tagsFuzzy,
            typeFilter: value.type,
            pagesMin: value.pageMin,
            pagesMax: value.pageMax,
            wordsMin: value.wordsMin,
            wordsMax: value.wordsMax,
            bookmarksMin: value.bookmarkMin,
            bookmarksMax: value.bookmarkMax
        };
    }

    function snapshotDownload() {
        return {
            fileNameTemplate: state.settings.fileNameTemplate,
            bookmark: !!state.settings.bookmark,
            collectionId: state.settings.collectionId,
            concurrent: Math.max(1, parseInt(state.settings.concurrent, 10) || 1),
            intervalMs: getIntervalMs(),
            imageDelayMs: getImageDelayMs(),
            verifyFiles: !!state.settings.verifyHistoryFiles,
            redownloadDeleted: !!state.settings.redownloadDeleted,
            novelFormat: state.settings.novelFormat || 'txt',
            novelMerge: !!state.settings.mergeNovelSeries,
            novelMergeFormat: state.settings.mergeNovelFormat || 'epub',
            novelAutoTranslate: !!state.settings.novelAutoTranslate,
            novelTranslateLanguage: state.settings.novelTranslateLang || defaultNovelTranslateLang(),
            novelTranslateSegmentSize: Math.max(0, parseInt(state.settings.novelTranslateSeg, 10) || 0)
        };
    }

    function capture(sourceType, context) {
        const selected = sourceFromUi(sourceType, context);
        syncSettings();
        const limitMode = fetchLimitMode(sourceType, selected.source);
        let fetchLimit = 0;
        if (limitMode) {
            const raw = parseInt((document.getElementById('sch-fetch-limit') || {}).value, 10);
            fetchLimit = Number.isFinite(raw) && raw > 0 ? raw : 0;
        }
        return {
            params: {
                kind: selected.kind,
                source: selected.source,
                filters: snapshotFilters(),
                download: snapshotDownload(),
                fetchLimit
            },
            fetchLimitMode: limitMode,
            fetchLimitPresentation: FETCH_LIMIT_PRESENTATION,
            quickLabel: selected.label,
            workType: selected.kind
        };
    }

    function preview(sourceType, context) {
        const quick = normalizedQuickSource(context);
        if (quick && quick.sourceType === sourceType) {
            return {
                label: quick.label,
                fetchLimitMode: fetchLimitMode(sourceType, quick.source),
                fetchLimitPresentation: FETCH_LIMIT_PRESENTATION
            };
        }
        let source = {};
        if (sourceType === SOURCE.SEARCH) {
            source = {
                maxPages: currentSearchMaxPages(),
                order: (document.querySelector('input[name="search-order"]:checked') || {}).value || 'date_d'
            };
        }
        return {
            label: '',
            fetchLimitMode: fetchLimitMode(sourceType, source),
            fetchLimitPresentation: FETCH_LIMIT_PRESENTATION
        };
    }

    function setRadio(name, value) {
        const radio = document.querySelector(`input[name="${name}"][value="${value}"]`);
        if (radio) radio.checked = true;
    }

    function applyKind(modePrefix, kind) {
        const value = kind === 'novel' ? 'novel' : (kind === 'request' ? 'request' : 'illust');
        if (modePrefix === 'user') state.settings.userKind = value;
        else state.settings.searchKind = value;
        const radio = document.querySelector(`input[name="${modePrefix}-kind"][value="${value}"]`);
        if (radio) radio.checked = true;
        applyKindSwitcherUI(`${modePrefix}-kind-switcher`, value);
    }

    function selectSeriesDataSource() {
        const seriesMode = window.PixivBatch && window.PixivBatch.modes
            && window.PixivBatch.modes.series;
        if (seriesMode && typeof seriesMode.selectSeriesDataSource === 'function') {
            seriesMode.selectSeriesDataSource('pixiv');
        }
    }

    function applyDownload(value) {
        const download = value || {};
        if (typeof download.fileNameTemplate === 'string' && download.fileNameTemplate) {
            const field = document.getElementById('s-file-name-template');
            if (field) field.value = download.fileNameTemplate;
        }
        const bm = document.getElementById('s-bookmark');
        if (bm) bm.checked = !!download.bookmark;
        const col = document.getElementById('s-collection');
        if (col) col.value = download.collectionId != null ? String(download.collectionId) : '';
        const fmt = document.getElementById('s-novel-format');
        if (fmt && download.novelFormat) fmt.value = download.novelFormat;
        const merge = document.getElementById('s-novel-merge');
        if (merge) merge.checked = !!download.novelMerge;
        const mergeFormat = document.getElementById('s-novel-merge-format');
        if (mergeFormat && download.novelMergeFormat) mergeFormat.value = download.novelMergeFormat;
        const translate = document.getElementById('s-novel-auto-translate');
        if (translate) {
            translate.checked = !!download.novelAutoTranslate;
            state.settings.novelAutoTranslate = !!download.novelAutoTranslate;
        }
        const language = document.getElementById('s-novel-translate-lang');
        if (language && typeof download.novelTranslateLanguage === 'string'
            && download.novelTranslateLanguage) {
            language.value = download.novelTranslateLanguage;
            state.settings.novelTranslateLang = download.novelTranslateLanguage;
        }
        const segment = document.getElementById('s-novel-translate-seg');
        if (segment && Number.isFinite(download.novelTranslateSegmentSize)
            && download.novelTranslateSegmentSize >= 0) {
            segment.value = download.novelTranslateSegmentSize;
            state.settings.novelTranslateSeg = download.novelTranslateSegmentSize;
        }
        const concurrent = document.getElementById('s-concurrent');
        if (concurrent && Number.isFinite(download.concurrent) && download.concurrent >= 1) {
            concurrent.value = download.concurrent;
            state.settings.concurrent = download.concurrent;
        }
        if (Number.isFinite(download.intervalMs) && download.intervalMs >= 0) {
            const field = document.getElementById('s-interval');
            const unit = document.getElementById('s-interval-unit');
            const ms = Math.round(download.intervalMs);
            if (field) field.value = ms;
            if (unit) unit.textContent = 'ms';
            state.settings.intervalUnit = 'ms';
            state.settings.interval = ms;
        }
        if (Number.isFinite(download.imageDelayMs) && download.imageDelayMs >= 0) {
            const field = document.getElementById('s-image-delay');
            const unit = document.getElementById('s-image-delay-unit');
            const ms = Math.round(download.imageDelayMs);
            if (field) field.value = ms;
            if (unit) unit.textContent = 'ms';
            state.settings.imageDelayUnit = 'ms';
            state.settings.imageDelay = ms;
        }
        const verify = document.getElementById('s-verify-files');
        if (verify) {
            verify.checked = !!download.verifyFiles;
            state.settings.verifyHistoryFiles = !!download.verifyFiles;
        }
        const redownload = document.getElementById('s-redownload-deleted');
        if (redownload) {
            redownload.checked = !!download.redownloadDeleted;
            state.settings.redownloadDeleted = !!download.redownloadDeleted;
        }
        updateMergeFormatVisibility();
        updateNovelTranslateVisibility();
    }

    function restore(sourceType, task) {
        const params = parseParams(task);
        if (!params) throw new Error(bt('schedule.snapshot.error.parse', '任务快照解析失败'));
        const kind = params.kind === 'novel' ? 'novel' : (params.kind === 'mixed' ? 'mixed' : 'illust');
        const source = params.source || {};
        const filters = params.filters || {};
        const targetMode = modeFor(sourceType);
        switchMode(targetMode);
        let quickSource = null;
        if (sourceType === SOURCE.USER_NEW) {
            applyKind('user', kind);
            const input = document.getElementById('user-id-input');
            if (input) input.value = source.userId || '';
        } else if (sourceType === SOURCE.USER_REQUEST) {
            applyKind('user', 'request');
            const input = document.getElementById('user-id-input');
            if (input) input.value = source.userId || '';
        } else if (sourceType === SOURCE.SEARCH) {
            applyKind('search', kind);
            const input = document.getElementById('search-word');
            if (input) input.value = source.word || '';
            setRadio('search-order', source.order || 'date_d');
            setRadio('search-smode', source.sMode || 's_tag');
            const maxPages = source.maxPages;
            const batch = maxPages === -1 || (typeof maxPages === 'number' && maxPages >= 2);
            applySubmodeUI(batch ? 'batch' : 'search', {clear: false});
            const end = document.getElementById('batch-end-page');
            if (end && batch) end.value = maxPages;
        } else if (sourceType === SOURCE.SERIES) {
            selectSeriesDataSource();
            const seriesId = source.seriesId || '';
            const input = document.getElementById('series-input-url');
            if (input) {
                input.value = seriesId
                    ? (kind === 'novel' ? `https://www.pixiv.net/novel/series/${seriesId}` : String(seriesId))
                    : '';
            }
            seriesState.kind = kind;
            seriesState.seriesId = seriesId ? Number(seriesId) : null;
            if (seriesId) loadSeriesPreview();
        } else {
            quickSource = {
                sourceType,
                type: sourceType,
                source,
                kind,
                label: quickSourceLabel(sourceType, source, kind)
            };
        }
        applyDownload(params.download || {});
        setSearchFiltersUI(normalizeSearchFilters({
            content: filters.content,
            ai: filters.aiFilter,
            type: filters.typeFilter,
            pageMin: filters.pagesMin,
            pageMax: filters.pagesMax,
            bookmarkMin: filters.bookmarksMin,
            bookmarkMax: filters.bookmarksMax,
            wordsMin: filters.wordsMin,
            wordsMax: filters.wordsMax,
            tagsExact: filters.tagsExact,
            tagsFuzzy: filters.tagsFuzzy
        }));
        syncSettings();
        applyNovelSettingsVisibility();
        updateExtraFiltersCardVisibility();
        return {mode: targetMode, quickSource, params, kind};
    }

    function valueOrUnset(value) {
        return value == null || value === ''
            ? bt('schedule.snapshot.value.unset', '未设置') : String(value);
    }

    function boolLabel(value) {
        return value ? bt('schedule.snapshot.value.yes', '是') : bt('schedule.snapshot.value.no', '否');
    }

    function listValue(value) {
        const list = Array.isArray(value)
            ? value.map(item => String(item).trim()).filter(Boolean)
            : String(value || '').split(',').map(item => item.trim()).filter(Boolean);
        return list.length ? list.join(', ') : valueOrUnset(null);
    }

    function rangeValue(min, max) {
        const hasMin = min != null && min !== '';
        const hasMax = max != null && max !== '';
        if (hasMin && hasMax) return bt('schedule.snapshot.value.range', '{min} - {max}', {min, max});
        if (hasMin) return bt('schedule.snapshot.value.at-least', '≥ {value}', {value: min});
        if (hasMax) return bt('schedule.snapshot.value.at-most', '≤ {value}', {value: max});
        return bt('schedule.snapshot.value.unlimited', '不限');
    }

    function mapped(value, fallback, values) {
        return values[value || fallback] || valueOrUnset(value);
    }

    function kindLabel(kind) {
        return mapped(kind, '', {
            novel: bt('schedule.kind.novel', '小说'),
            illust: bt('schedule.kind.illust', '插画'),
            mixed: bt('schedule.kind.mixed', '插画+小说')
        });
    }

    function quickSourceLabel(sourceType, source, kind) {
        if (sourceType === SOURCE.MY_BOOKMARKS) {
            const rest = source.rest === 'hide'
                ? bt('quick.schedule.rest.hide', '不公开') : bt('quick.schedule.rest.show', '公开');
            return bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）',
                {kind: kindLabel(kind), rest});
        }
        if (sourceType === SOURCE.FOLLOW_LATEST) {
            return bt('quick.title.following-new', '已关注的用户的新作');
        }
        if (sourceType === SOURCE.COLLECTION) {
            return bt('quick.schedule.source.collection', '珍藏集 {name}（ID {id}）',
                {name: source.collectionId, id: source.collectionId});
        }
        return '';
    }

    function collectionLabel(collectionId) {
        if (collectionId == null || collectionId === '') {
            return bt('option.collection.none', '（不加入收藏夹）');
        }
        const id = String(collectionId);
        const select = document.getElementById('s-collection');
        const option = select && select.options
            ? Array.from(select.options).find(item => String(item.value) === id) : null;
        const optionText = option ? (option.textContent || '').trim() : '';
        const idText = bt('schedule.snapshot.value.id', 'ID');
        return optionText ? `${optionText} (${idText} ${id})` : `${idText} ${id}`;
    }

    function sourceRows(sourceType, source) {
        if (sourceType === SOURCE.USER_NEW || sourceType === SOURCE.USER_REQUEST) {
            return [[bt('schedule.snapshot.field.user-id', '画师 ID'), valueOrUnset(source.userId)]];
        }
        if (sourceType === SOURCE.SEARCH) {
            const maxPages = Number(source.maxPages);
            const pageValue = maxPages === -1
                ? bt('schedule.snapshot.value.until-downloaded', '直到遇到已下载作品为止')
                : bt('schedule.snapshot.value.pages-count', '{count} 页',
                    {count: Number.isFinite(maxPages) && maxPages > 0 ? maxPages : 1});
            return [
                [bt('schedule.snapshot.field.keyword', '搜索关键词'), valueOrUnset(source.word)],
                [bt('schedule.snapshot.field.search-order', '排序'), mapped(source.order, 'date_d', {
                    date_d: bt('search.order.latest', '最新'),
                    date: bt('search.order.oldest', '最旧'),
                    popular_d: bt('search.order.popular', '热门 ⚠')
                })],
                [bt('schedule.snapshot.field.search-mode', '搜索方式'), mapped(source.sMode, 's_tag', {
                    s_tag: bt('search.mode.tag', '标签'),
                    s_tc: bt('search.mode.title-desc', '标题/描述')
                })],
                [bt('schedule.snapshot.field.pixiv-mode', 'Pixiv 内容范围'), mapped(source.mode, 'all', {
                    all: bt('search.content.all', '全部'),
                    safe: bt('search.content.safe', '全年龄'),
                    r18: bt('search.content.r18', 'R-18')
                })],
                [bt('schedule.snapshot.field.max-pages', '发现页数'), pageValue]
            ];
        }
        if (sourceType === SOURCE.SERIES) {
            return [[bt('schedule.snapshot.field.series-id', '系列 ID'), valueOrUnset(source.seriesId)]];
        }
        if (sourceType === SOURCE.MY_BOOKMARKS) {
            return [[bt('schedule.snapshot.field.bookmark-visibility', '收藏可见性'),
                source.rest === 'hide' ? bt('quick.schedule.rest.hide', '不公开')
                    : bt('quick.schedule.rest.show', '公开')]];
        }
        if (sourceType === SOURCE.FOLLOW_LATEST) {
            return [[bt('schedule.snapshot.field.source', '来源'),
                bt('schedule.type.follow-latest', '📰 已关注用户的新作')]];
        }
        if (sourceType === SOURCE.COLLECTION) {
            return [[bt('schedule.snapshot.field.collection-id', '珍藏集 ID'), valueOrUnset(source.collectionId)]];
        }
        return [];
    }

    function summary(sourceType, task) {
        const params = parseParams(task);
        if (!params) throw new Error('invalid Pixiv schedule definition');
        const kind = params.kind === 'novel' ? 'novel' : (params.kind === 'mixed' ? 'mixed' : 'illust');
        const source = params.source || {};
        const filters = params.filters || {};
        const download = params.download || {};
        const rows = sourceRows(sourceType, source);
        if (fetchLimitMode(sourceType, source)) {
            rows.push([bt('schedule.snapshot.field.fetch-limit', '首次抓取上限'),
                typeof params.fetchLimit === 'number' && params.fetchLimit > 0
                    ? bt('schedule.snapshot.value.fetch-limit', '{n} 个作品', {n: params.fetchLimit})
                    : bt('schedule.snapshot.value.fetch-limit-all', '全量（不限）')]);
        }
        const format = value => mapped(value, 'txt', {
            txt: bt('novel:format.txt', '纯文本（TXT）'),
            html: bt('novel:format.html', '网页（HTML）'),
            epub: bt('novel:format.epub', '电子书（EPUB）')
        });
        const ms = value => Number.isFinite(value) && value >= 0
            ? bt('schedule.snapshot.value.ms', '{ms} ms', {ms: value}) : valueOrUnset(null);
        const concurrent = Number.isFinite(download.concurrent) && download.concurrent >= 1
            ? bt('schedule.snapshot.value.concurrent', '{n} 路并发', {n: download.concurrent})
            : valueOrUnset(null);
        const downloadRows = [
            [bt('label.settings.skip', '跳过已下载作品'), bt('schedule.snapshot.value.always-on', '始终开启')],
            [bt('label.settings.redownload-deleted', '允许已删除的作品被重新下载'), boolLabel(!!download.redownloadDeleted)],
            [bt('label.settings.filename-template', '文件名格式:'), valueOrUnset(download.fileNameTemplate)],
            [bt('label.settings.bookmark', '下载后自动收藏'), boolLabel(!!download.bookmark)],
            [bt('label.settings.collection', '收藏到:'), collectionLabel(download.collectionId)],
            [bt('label.settings.concurrent', '最大并发数:'), concurrent],
            [bt('label.settings.interval', '作品间隔:'), ms(download.intervalMs)]
        ];
        if (kind !== 'novel') {
            downloadRows.push([bt('label.settings.image-delay', '图片间隔:'), ms(download.imageDelayMs)]);
            downloadRows.push([bt('label.settings.verify', '实际目录检测'), boolLabel(!!download.verifyFiles)]);
        }
        downloadRows.push([bt('novel:batch.format-label', '小说格式'), format(download.novelFormat)]);
        downloadRows.push([bt('novel:batch.merge-label', '系列下载完成后生成合订本'), boolLabel(!!download.novelMerge)]);
        downloadRows.push([bt('novel:batch.merge-format-label', '合订本格式'), format(download.novelMergeFormat || 'epub')]);
        downloadRows.push([bt('ai:batch.auto-translate-label', '新下载小说自动翻译'), boolLabel(!!download.novelAutoTranslate)]);
        if (download.novelAutoTranslate) {
            downloadRows.push([bt('ai:batch.translate-lang-label', '目标语言:'),
                valueOrUnset(download.novelTranslateLanguage)]);
            downloadRows.push([bt('ai:batch.translate-seg-label', '分段字数:'),
                Number.isFinite(download.novelTranslateSegmentSize) && download.novelTranslateSegmentSize > 0
                    ? String(download.novelTranslateSegmentSize)
                    : bt('schedule.snapshot.value.whole-chapter', '整章一次性')]);
        }
        return {
            kind,
            sections: [
                {title: bt('schedule.snapshot.section.source', '来源快照'), rows},
                {title: bt('schedule.snapshot.section.filters', '筛选快照'), rows: [
                    [bt('label.search-content-rating', '内容分级'), mapped(filters.content, 'all', {
                        all: bt('search.content.all', '全部'),
                        safe: bt('search.content.safe', '全年龄'),
                        r18plus: bt('search.content.r18plus', 'R18+(R-18 + R-18G)'),
                        r18: bt('search.content.r18', 'R-18'),
                        r18g: bt('search.content.r18g', 'R-18G')
                    })],
                    [bt('label.search-ai', 'AI 作品'), mapped(filters.aiFilter, 'all', {
                        all: bt('search.filter.all', '全部'),
                        exclude: bt('search.filter.exclude-ai', '排除 AI'),
                        only: bt('search.filter.only-ai', '仅 AI')
                    })],
                    [bt('label.search-tags-exact', '标签(精确匹配)'), listValue(filters.tagsExact)],
                    [bt('label.search-tags-fuzzy', '标签(模糊匹配)'), listValue(filters.tagsFuzzy)],
                    [bt('label.search-type', '作品类型'), mapped(filters.typeFilter, 'all', {
                        all: bt('search.filter.all', '全部'),
                        illust: bt('search.type.illust', '插画'),
                        manga: bt('search.type.manga', '漫画'),
                        ugoira: bt('search.type.ugoira', '动图')
                    })],
                    [bt('schedule.snapshot.field.pages-range', '页数范围'), rangeValue(filters.pagesMin, filters.pagesMax)],
                    [bt('schedule.snapshot.field.words-range', '字数范围'), rangeValue(filters.wordsMin, filters.wordsMax)],
                    [bt('schedule.snapshot.field.bookmarks-range', '收藏数范围'), rangeValue(filters.bookmarksMin, filters.bookmarksMax)]
                ]},
                {title: bt('schedule.snapshot.section.download', '下载设置快照'), rows: downloadRows}
            ]
        };
    }

    function credentialActions(api) {
        return Object.freeze({
            supportsCookie: true,
            supportsProxy: true,
            presentation: Object.freeze({
                boundLabel: bt('schedule.cookie.bound', '已绑定 Cookie'),
                unboundLabel: bt('schedule.cookie.restricted', '受限模式（无 Cookie）'),
                overrideLabel: bt('schedule.action.override', '🔑 指定单独的 代理/cookie'),
                modalTitle: bt('schedule.override.title', '指定单独的 代理/cookie'),
                modalIntro: bt('schedule.override.intro', '为该计划任务指定独立的下载代理与 Cookie。未勾选的项使用默认行为：全局代理设置 / 任务当前绑定的 Cookie；取消已生效的勾选并保存会清除对应设置。'),
                proxyToggleLabel: bt('schedule.field.proxy-enabled', '设置单独的代理'),
                credentialToggleLabel: bt('schedule.field.cookie-enabled', '设置单独的cookie'),
                savedCredentialLabel: bt('schedule.action.use-saved-cookie', '使用当前保存的cookie'),
                boundPlaceholder: bt('schedule.field.cookie.placeholder-bound', '已绑定 Cookie（不回显）；留空保持不变，填写则替换'),
                placeholder: bt('schedule.field.cookie.placeholder', '粘贴含 PHPSESSID 的 Cookie，或点右侧按钮填入'),
                proxyHint: bt('schedule.field.proxy.hint', '该任务每轮运行中对 Pixiv 的全部访问（发现 / 元数据 / 下载 / 站内信检测）都会改走此 HTTP 代理（host:port）；取消勾选则使用全局代理设置。'),
                credentialHint: bt('schedule.field.cookie.hint', '该任务将使用这份 Cookie 访问 Pixiv（替代默认绑定的 Cookie）。出于安全考虑，已绑定的 Cookie 不会回显。'),
                emptyCredentialMessage: bt('schedule.error.override-cookie-empty', '请填写单独的 Cookie（或点「使用当前保存的cookie」），或取消勾选'),
                namespace: 'batch',
                clearProxyConfirmKey: 'schedule.pixiv.confirm.clear-proxy',
                clearCredentialConfirmKey: 'schedule.pixiv.confirm.clear-cookie'
            }),
            savedCookie() {
                api.assertActive();
                return getCookieInputHeaderString().trim();
            },
            validateCookie(cookie) {
                api.assertActive();
                return /(?:^|;\s*)PHPSESSID=/.test(String(cookie || ''))
                    ? null
                    : bt('schedule.error.cookie-no-phpsessid', '当前 Cookie 不含 PHPSESSID，无法授权');
            },
            async autoAuthorize(taskId, lease) {
                api.assertActive();
                const cookie = getCookieInputHeaderString().trim();
                if (!cookie || !/(?:^|;\s*)PHPSESSID=/.test(cookie)) return 'no-cookie';
                try {
                    const response = await fetch(`${BASE}/api/schedule/tasks/${taskId}/authorize-cookie`, {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {
                            'Content-Type': 'application/json',
                            'X-Acquisition-Credential': cookie
                        },
                        signal: lease && lease.signal ? lease.signal : api.signal,
                        body: JSON.stringify({
                            activationToken: lease && lease.activationToken
                        })
                    });
                    api.assertActive();
                    if (lease && typeof lease.assertCurrent === 'function') lease.assertCurrent();
                    return response.ok ? 'authorized' : 'failed';
                } catch (e) {
                    api.assertActive();
                    return 'failed';
                }
            }
        });
    }

    runtime.registerModule(MODULE_URL, function (api) {
        const declared = new Set(api.descriptors.map(item => item.sourceType));
        Object.values(SOURCE).forEach(sourceType => {
            if (!declared.has(sourceType)) return;
            api.registerSource(sourceType, {
                matches: context => matches(sourceType, context),
                preview: context => preview(sourceType, context),
                capture: context => capture(sourceType, context),
                restore: task => restore(sourceType, task),
                summary: task => summary(sourceType, task),
                fetchLimitMode: definition => fetchLimitMode(
                    sourceType, definition && definition.source ? definition.source : definition),
                quickSourceNote: context => {
                    const quick = normalizedQuickSource(context);
                    return quick && quick.sourceType === sourceType ? quick.label : null;
                },
                credentialActions: () => credentialActions(api)
            });
        });
    });
})();
