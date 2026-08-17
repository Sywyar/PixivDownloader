/* ========== PixivUserscriptI18n: shared userscript i18n runtime ==========
 * Same-origin Pixiv userscripts share localStorage + BroadcastChannel state
 * so standalone installs, parallel installs, and bundle installs stay aligned.
 * Within a single sandbox (e.g. the All-in-One bundle), all modules share the
 * same instance via window.__PixivUserscriptI18n_v1__ so a single switch toggle
 * updates every module without relying on BroadcastChannel delivery.
 * ------------------------------------------------------------------------ */
const PixivUserscriptI18n = (() => {
    const SHARED_KEY = '__PixivUserscriptI18n_v1__';
    if (typeof window !== 'undefined' && window[SHARED_KEY]) {
        return window[SHARED_KEY];
    }
    const LS_KEY = 'pixiv_userscript_lang';
    const GM_KEY = 'pixiv_userscript_lang';
    const BC_NAME = '__pixiv_userscript_lang_v1__';
    const SUPPORTED = ['en-US', 'zh-CN', 'zh-Hant'];
    const DEFAULT_LANG = 'en-US';

    let DICT = { 'en-US': {}, 'zh-CN': {}, 'zh-Hant': {} };
    let currentLang = null;
    const listeners = new Set();
    let bc = null;

    function normalize(lang) {
        if (!lang) return null;
        const tag = String(lang).trim().replace('_', '-');
        if (SUPPORTED.indexOf(tag) >= 0) return tag;
        const language = tag.split('-')[0].toLowerCase();
        for (let i = 0; i < SUPPORTED.length; i += 1) {
            if (SUPPORTED[i].toLowerCase().startsWith(language + '-')) return SUPPORTED[i];
        }
        return null;
    }

    function readInitialLang() {
        try {
            const stored = normalize(localStorage.getItem(LS_KEY));
            if (stored) return stored;
        } catch (e) {}
        try {
            if (typeof GM_getValue === 'function') {
                const stored = normalize(GM_getValue(GM_KEY, null));
                if (stored) return stored;
            }
        } catch (e) {}
        return normalize(navigator.language) || DEFAULT_LANG;
    }

    function notify(next) {
        listeners.forEach(fn => {
            try {
                fn(next);
            } catch (e) {
                console.error('[PixivUserscriptI18n]', e);
            }
        });
    }

    function ensureInit() {
        if (currentLang) return;
        currentLang = readInitialLang();
        try {
            if (typeof BroadcastChannel !== 'undefined') {
                bc = new BroadcastChannel(BC_NAME);
                bc.addEventListener('message', ev => {
                    if (!ev || !ev.data || ev.data.type !== 'lang-changed') return;
                    const next = normalize(ev.data.lang);
                    if (next && next !== currentLang) {
                        applyLang(next, false);
                    }
                });
            }
        } catch (e) {}
        try {
            window.addEventListener('storage', ev => {
                if (ev.key !== LS_KEY) return;
                const next = normalize(ev.newValue);
                if (next && next !== currentLang) {
                    applyLang(next, false);
                }
            });
        } catch (e) {}
        // Cross-sandbox polling fallback: when standalone userscripts run in
        // separate Tampermonkey sandboxes on the same page, BroadcastChannel
        // delivery is unreliable and the storage event never fires within the
        // same browsing context. Polling localStorage every ~1s catches any
        // change made by a sibling sandbox.
        try {
            setInterval(() => {
                try {
                    const stored = normalize(localStorage.getItem(LS_KEY));
                    if (stored && stored !== currentLang) {
                        applyLang(stored, false);
                    }
                } catch (e) {}
            }, 1000);
        } catch (e) {}
    }

    function applyLang(lang, broadcast) {
        const next = normalize(lang) || DEFAULT_LANG;
        currentLang = next;
        if (broadcast) {
            try {
                localStorage.setItem(LS_KEY, next);
            } catch (e) {}
            try {
                if (typeof GM_setValue === 'function') GM_setValue(GM_KEY, next);
            } catch (e) {}
            if (bc) {
                try {
                    bc.postMessage({ type: 'lang-changed', lang: next });
                } catch (e) {}
            }
        }
        notify(next);
    }

    function interpolate(template, args) {
        if (!args) return String(template);
        if (Array.isArray(args)) {
            return String(template).replace(/\{(\d+)\}/g, (match, index) => {
                const idx = parseInt(index, 10);
                return idx < args.length ? String(args[idx]) : match;
            });
        }
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
            return Object.prototype.hasOwnProperty.call(args, name) ? String(args[name]) : match;
        });
    }

    function t(key, fallback, args) {
        ensureInit();
        const active = DICT[currentLang] || {};
        let template = Object.prototype.hasOwnProperty.call(active, key) ? active[key] : null;
        if (template == null) {
            const defaults = DICT[DEFAULT_LANG] || {};
            template = Object.prototype.hasOwnProperty.call(defaults, key) ? defaults[key] : null;
        }
        if (template == null) {
            template = fallback != null ? fallback : key;
        }
        if (typeof template === 'function') {
            return template(args || {});
        }
        return interpolate(template, args);
    }

    function register(dict) {
        if (!dict || typeof dict !== 'object') return;
        Object.keys(dict).forEach(lang => {
            DICT[lang] = Object.assign({}, DICT[lang] || {}, dict[lang] || {});
        });
    }

    function onChange(fn) {
        listeners.add(fn);
        return () => listeners.delete(fn);
    }

    function setLang(lang) {
        ensureInit();
        applyLang(lang, true);
    }

    function getLang() {
        ensureInit();
        return currentLang;
    }

    function listSupported() {
        return SUPPORTED.slice();
    }

    function enrichFromBackend(serverBase) {
        if (!serverBase || typeof GM_xmlhttpRequest !== 'function') return;
        SUPPORTED.forEach(lang => {
            try {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: serverBase.replace(/\/$/, '') + '/api/i18n/messages/userscript?lang=' + encodeURIComponent(lang),
                    timeout: 3000,
                    onload: res => {
                        if (res.status !== 200) return;
                        try {
                            const data = JSON.parse(res.responseText);
                            if (!data || !data.messages) return;
                            const incoming = {};
                            incoming[lang] = data.messages;
                            register(incoming);
                            if (lang === currentLang) notify(currentLang);
                        } catch (e) {}
                    }
                });
            } catch (e) {}
        });
    }

    const api = {
        register,
        t,
        onChange,
        setLang,
        getLang,
        listSupported,
        enrichFromBackend
    };
    try {
        if (typeof window !== 'undefined') window[SHARED_KEY] = api;
    } catch (e) {}
    return api;
})();
