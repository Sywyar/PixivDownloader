'use strict';
    const COOKIE_FORMATS = new Set(['header', 'json', 'netscape']);

    function normalizeCookieType(type) {
        const value = String(type || 'pixiv').trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '');
        return value || 'pixiv';
    }

    function cookieStorageKey(type) {
        const normalized = normalizeCookieType(type);
        return normalized === 'pixiv' ? 'pixiv_cookie' : 'pixiv_' + normalized + '_cookie';
    }

    function normalizeCookieFormat(fmt) {
        const value = String(fmt || '').trim().toLowerCase();
        return COOKIE_FORMATS.has(value) ? value : 'header';
    }

    function getCookieFmt() {
        return normalizeCookieFormat(storeGet('pixiv_cookie_fmt') || 'header');
    }

    function parseCookieToHeaderString(raw, fmt) {
        if (!raw) return '';
        const format = normalizeCookieFormat(fmt);
        try {
            if (format === 'json') {
                const obj = JSON.parse(raw);
                return Object.entries(obj).map(([k, v]) => `${k}=${String(v)}`).join('; ');
            }
            if (format === 'netscape') {
                return raw.split('\n')
                    .filter(l => l.trim() && !l.trim().startsWith('#'))
                    .map(l => {
                        const p = l.split('\t');
                        return p.length >= 7 ? `${p[5]}=${p[6].trim()}` : null;
                    })
                    .filter(Boolean)
                    .join('; ');
            }
        } catch (e) {
            console.warn(bt('cookie.warn.parse-fallback', 'Cookie 解析失败，原样使用: {message}', {message: e.message}));
        }
        // header string 或解析失败时原样返回
        return raw;
    }

    function getStoredCookie(type) {
        return storeGet(cookieStorageKey(type)) || '';
    }

    function setStoredCookie(type, raw) {
        storeSet(cookieStorageKey(type), raw || '');
    }

    function removeStoredCookie(type) {
        storeRemove(cookieStorageKey(type));
    }

    function getCookieHeaderStringFor(type) {
        return parseCookieToHeaderString(getStoredCookie(type), getCookieFmt());
    }

    function getCookie() {
        return getCookieHeaderStringFor('pixiv');
    }
