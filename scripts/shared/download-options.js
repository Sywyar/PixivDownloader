// 四个独立下载器共用设置与请求参数；收藏夹只在当前后端会话内选择。
const UserscriptDownloadOptions = (() => {
    const STORAGE_KEY = USERSCRIPT_DOWNLOAD_OPTIONS_KEY;
    let collection = null;
    const text = (key, args) => PixivUserscriptI18n.t('download.options.' + key, null, args);
    const scope = () => serverBase + '\n' + (userUUID || '');
    const choice = (value, values, fallback) => values.includes(value) ? value : fallback;
    const number = value => value === '' || value == null || !Number.isSafeInteger(Number(value))
        || Number(value) < 0 ? null : Number(value);

    function read(legacyR18 = false) {
        const saved = GM_getValue(STORAGE_KEY, {}) || {};
        const settings = {
            content: choice(saved.content, ['all', 'safe', 'r18plus', 'r18', 'r18g'], legacyR18 ? 'r18plus' : 'all'),
            ai: choice(saved.ai, ['all', 'exclude', 'only'], 'all'),
            type: choice(saved.type, ['all', 'illust', 'manga', 'ugoira'], 'all'),
            tagsExact: String(saved.tagsExact || ''),
            tagsFuzzy: String(saved.tagsFuzzy || ''),
            fileNameTemplate: String(saved.fileNameTemplate || ''),
            autoTranslate: saved.autoTranslate === true,
            autoTranslateLanguage: String(saved.autoTranslateLanguage || ''),
            autoTranslateSegmentSize: number(saved.autoTranslateSegmentSize) ?? 0,
            autoTranslateMerge: saved.autoTranslateMerge === true,
            autoTranslateMergeFormat: choice(saved.autoTranslateMergeFormat, ['txt', 'html', 'epub'], 'epub')
        };
        for (const prefix of ['bookmark', 'page', 'words']) {
            settings[prefix + 'Min'] = number(saved[prefix + 'Min']);
            settings[prefix + 'Max'] = number(saved[prefix + 'Max']);
            if (settings[prefix + 'Min'] !== null && settings[prefix + 'Max'] !== null
                && settings[prefix + 'Min'] > settings[prefix + 'Max']) {
                [settings[prefix + 'Min'], settings[prefix + 'Max']] = [settings[prefix + 'Max'], settings[prefix + 'Min']];
            }
        }
        return settings;
    }

    function matches(meta, kind, legacyR18 = false) {
        const f = read(legacyR18);
        const xr = Number(meta.xRestrict ?? meta.xrestrict ?? 0);
        if (f.content === 'safe' && xr !== 0 || f.content === 'r18plus' && xr !== 1 && xr !== 2
            || f.content === 'r18' && xr !== 1 || f.content === 'r18g' && xr !== 2) return false;
        const ai = Number(meta.aiType ?? (meta.isAi ? 2 : 0)) >= 2;
        if (f.ai === 'exclude' && ai || f.ai === 'only' && !ai) return false;
        const tags = (Array.isArray(meta.tags) ? meta.tags : meta.tags?.tags || []).flatMap(tag =>
            typeof tag === 'string' ? [tag] : [tag?.name || tag?.tag, tag?.translatedName || tag?.translation?.en]
        ).filter(Boolean).map(tag => String(tag).toLowerCase());
        const terms = value => value.split(/[,，\n]/).map(tag => tag.trim().toLowerCase()).filter(Boolean);
        if (!terms(f.tagsExact).every(term => tags.includes(term))
            || !terms(f.tagsFuzzy).every(term => tags.some(tag => tag.includes(term)))) return false;
        const within = (value, prefix) => {
            const min = f[prefix + 'Min'], max = f[prefix + 'Max'];
            if (min === null && max === null) return true;
            const n = number(value);
            return n !== null && (min === null || n >= min) && (max === null || n <= max);
        };
        if (!within(meta.bookmarkCount, 'bookmark')) return false;
        if (kind === 'novel') return within(meta.wordCount, 'words');
        const types = {illust: 0, manga: 1, ugoira: 2};
        return (f.type === 'all' || Number(meta.illustType) === types[f.type]) && within(meta.pageCount, 'page');
    }

    function request(path, base = serverBase) {
        return new Promise((resolve, reject) => {
            const headers = {Accept: 'application/json'};
            if (userUUID) headers['X-User-UUID'] = userUUID;
            GM_xmlhttpRequest({
                method: 'GET', url: base + path, headers, timeout: 10000,
                onload: response => {
                    try {
                        resolve({status: response.status, data: response.status === 204 ? null : JSON.parse(response.responseText)});
                    } catch (_) { reject(new Error(text('unavailable'))); }
                },
                onerror: () => reject(new Error(text('unavailable'))),
                ontimeout: () => reject(new Error(text('unavailable')))
            });
        });
    }

    async function collections() {
        const captured = scope();
        const result = await request('/api/collections');
        if (captured !== scope()) throw new Error(text('unavailable'));
        if (result.status !== 200 || !Array.isArray(result.data?.collections)) {
            collection = null;
            throw new Error(text('unavailable'));
        }
        const rows = result.data.collections.filter(row => number(row.id) > 0);
        if (collection && (collection.scope !== captured || !rows.some(row => Number(row.id) === collection.id))) collection = null;
        return rows;
    }

    function selectCollection(id) {
        collection = number(id) > 0 ? {id: number(id), scope: scope()} : null;
    }

    async function other(kind) {
        const settings = read();
        if (settings.fileNameTemplate.length > 512 || settings.autoTranslateLanguage.length > 100
            || settings.autoTranslateSegmentSize > 1000000) throw new Error(text('invalid'));
        const captured = scope();
        if (collection?.scope !== captured) collection = null;
        const selected = collection;
        if (selected) {
            await collections();
            if (collection !== selected) throw new Error(text('collection-changed'));
        }
        const result = {fileNameTemplate: settings.fileNameTemplate || null, collectionId: collection?.id || null};
        if (kind === 'novel') {
            if (settings.autoTranslate) {
                const auth = await request('/api/auth/check');
                if (auth.status !== 200 || auth.data?.valid !== true) throw new Error(text('admin'));
            }
            Object.assign(result, {
                autoTranslate: settings.autoTranslate,
                autoTranslateLanguage: settings.autoTranslateLanguage || null,
                autoTranslateSegmentSize: settings.autoTranslateSegmentSize,
                autoTranslateMerge: settings.autoTranslateMerge,
                autoTranslateMergeFormat: settings.autoTranslateMergeFormat
            });
        }
        if (captured !== scope()) throw new Error(text('unavailable'));
        return result;
    }

    const selectedCollectionId = () => collection?.scope === scope() ? collection.id : null;
    return {STORAGE_KEY, text, read, matches, request, collections, selectCollection, selectedCollectionId, other};
})();
