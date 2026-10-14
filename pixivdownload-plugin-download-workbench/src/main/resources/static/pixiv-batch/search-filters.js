'use strict';

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

    function getInlineSearchBookmarkCount(item) {
        if (item?.bookmarkCount == null || item.bookmarkCount === '') return null;
        const count = Number(item?.bookmarkCount);
        return Number.isFinite(count) && count >= 0 ? count : null;
    }
