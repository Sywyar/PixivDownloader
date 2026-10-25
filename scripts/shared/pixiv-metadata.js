// 浏览器取得的 Pixiv 元数据保留原始正文与 HTML，由后端负责持久化校验和归一化。
function _pn_parsePositiveLong(value) {
    if (value == null) return null;
    const n = Number(String(value).trim());
    return Number.isFinite(n) && n > 0 ? n : null;
}

function _pn_extractTags(body) {
    let arr = body && body.tags && body.tags.tags;
    if (!Array.isArray(arr) || arr.length === 0) {
        arr = Array.isArray(body && body.tags) ? body.tags : null;
    }
    if (!Array.isArray(arr) || arr.length === 0) return [];
    const out = [];
    for (const t of arr) {
        let name = '';
        if (typeof t === 'string') name = t;
        else if (t && typeof t === 'object') name = String(t.tag || t.name || '');
        if (!name) continue;
        let translated = null;
        const tr = t && typeof t === 'object' ? t.translation : null;
        if (tr && typeof tr === 'object') {
            const en = String(tr.en || '');
            if (en) translated = en;
        }
        out.push({name, translatedName: translated});
    }
    return out;
}

function _pn_extractReadingTimeSeconds(node) {
    const fields = ['readingTimeSeconds', 'readingTime', 'readTime', 'estimatedReadingTime'];
    for (const f of fields) {
        const v = node ? node[f] : undefined;
        if (v == null) continue;
        if (typeof v === 'number') return v > 0 ? Math.floor(v) : null;
        const raw = String(v).trim();
        if (!raw) continue;
        const digits = raw.replace(/[^0-9]/g, '');
        if (!digits) continue;
        const n = parseInt(digits, 10);
        if (Number.isFinite(n) && n > 0) return n;
    }
    return null;
}

function _pn_countPages(content) {
    if (!content) return 1;
    let pages = 1, idx = 0;
    while ((idx = content.indexOf('[newpage]', idx)) >= 0) {
        pages++;
        idx += '[newpage]'.length;
    }
    return pages;
}

function _pn_extractCoverUrl(node) {
    if (!node) return '';
    for (const parent of ['imageUrls', 'urls']) {
        const urls = node[parent];
        if (urls && typeof urls === 'object') {
            for (const k of ['original', 'large', 'regular', 'medium', 'squareMedium']) {
                const u = String(urls[k] || '');
                if (u) return u;
            }
        }
    }
    for (const k of ['coverUrl', 'url', 'thumbnailUrl']) {
        const u = String(node[k] || '');
        if (u) return u;
    }
    return '';
}

function _pn_extractUploadTimestamp(node) {
    if (!node) return null;
    for (const f of ['uploadDate', 'createDate', 'updateDate']) {
        const iso = String(node[f] || '');
        if (!iso) continue;
        const ts = Date.parse(iso);
        if (Number.isFinite(ts) && ts > 0) return ts;
    }
    return null;
}

function _pn_extractTextEmbeddedImages(body) {
    const node = body && body.textEmbeddedImages;
    if (!node || typeof node !== 'object') return {};
    const out = {};
    for (const key of Object.keys(node)) {
        const item = node[key];
        const url = item && item.urls && item.urls.original ? String(item.urls.original) : '';
        if (!url) continue;
        try {
            const u = new URL(url);
            if (!u.host || !/\.pximg\.net$/.test(u.host)) continue;
        } catch (_) {
            continue;
        }
        out[String(key)] = url;
    }
    return out;
}

function _pn_extractSeriesCoverUrl(meta) {
    if (!meta) return '';
    const urls = meta.cover && meta.cover.urls;
    if (urls && typeof urls === 'object') {
        for (const k of ['original', '1200x1200', '720x720', '480mw', '240mw']) {
            const u = String(urls[k] || '');
            if (u) return u;
        }
    }
    for (const k of ['coverImageUrl', 'coverImage', 'thumbnailUrl']) {
        const u = String(meta[k] || '');
        if (u) return u;
    }
    return '';
}

function buildNovelMetaFromPixivBody(novelId, body) {
    const nav = body && body.seriesNavData;
    const hasSeries = nav && Number(nav.seriesId) > 0;
    return {
        novelId: Number(novelId),
        title: String((body && body.title) || ''),
        xRestrict: Number((body && body.xRestrict) || 0),
        isAi: Number((body && body.aiType) || 0) >= 2,
        bookmarkCount: body && body.bookmarkCount != null ? Number(body.bookmarkCount) : -1,
        authorId: _pn_parsePositiveLong(body && body.userId),
        authorName: String((body && body.userName) || ''),
        description: String((body && body.description) || ''),
        tags: _pn_extractTags(body),
        seriesId: hasSeries ? Number(nav.seriesId) : null,
        seriesOrder: hasSeries ? Number(nav.order || 0) : null,
        seriesTitle: hasSeries ? String(nav.title || '') : null,
        content: String((body && body.content) || ''),
        wordCount: body && body.wordCount != null ? Number(body.wordCount) : null,
        textLength: body && body.characterCount != null ? Number(body.characterCount) : null,
        readingTimeSeconds: _pn_extractReadingTimeSeconds(body),
        pageCount: _pn_countPages(body && body.content),
        isOriginal: !!(body && body.isOriginal),
        language: String((body && body.language) || ''),
        coverUrl: _pn_extractCoverUrl(body),
        uploadTimestamp: _pn_extractUploadTimestamp(body),
        textEmbeddedImages: _pn_extractTextEmbeddedImages(body)
    };
}

function buildForwardMetaJson(meta) {
    if (!meta || typeof meta !== 'object') return null;
    try {
        const pruned = { ...meta };
        const stripKeys = ['userIllusts', 'userNovels', 'zoneConfig', 'extraData', 'noLoginData',
            'comicPromotion', 'fanboxPromotion', 'contestBanners', 'contestData',
            'pollData', 'imageResponseData', 'imageResponseOutData'];
        stripKeys.forEach(k => { delete pruned[k]; });
        return JSON.stringify(pruned);
    } catch (_) {
        return null;
    }
}
