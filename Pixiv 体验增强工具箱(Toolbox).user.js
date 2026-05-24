// ==UserScript==
// @name         Pixiv 体验增强工具箱
// @namespace    http://tampermonkey.net/
// @version      1.1.1
// @updateURL    https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/Pixiv%20%E4%BD%93%E9%AA%8C%E5%A2%9E%E5%BC%BA%E5%B7%A5%E5%85%B7%E7%AE%B1(Toolbox).user.js
// @downloadURL  https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/Pixiv%20%E4%BD%93%E9%AA%8C%E5%A2%9E%E5%BC%BA%E5%B7%A5%E5%85%B7%E7%AE%B1(Toolbox).user.js
// @description  Pixiv 使用体验增强工具箱
// @author       Sywyar
// @match        https://www.pixiv.net/*
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_deleteValue
// @grant        GM_registerMenuCommand
// @grant        GM_cookie
// @connect      pixiv.net
// @connect      www.pixiv.net
// @connect      self
// @connect      localhost
// @connect      YOUR_SERVER_HOST
// @run-at       document-end
// ==/UserScript==

(function () {
    'use strict';

    /* ========== 配置 ========== */
    const KEY_SERVER_URL = 'pixiv_server_base';
    let serverBase = GM_getValue(KEY_SERVER_URL, 'http://localhost:6999').replace(/\/$/, '');

    /* ========== PixivPanelState: 跨脚本共享的面板展开/收起状态 ==========
     * 所有 Pixiv 用户脚本共用同一个折叠状态：localStorage 权威 + BroadcastChannel +
     * storage 事件 + 1s 轮询兜底（跨 sandbox 对齐），同一 sandbox 内经 window 共享单例。
     * 默认展开（无存储值时 collapsed=false）。仅“用户手动收/展”写入；页面类型默认值与
     * 跨面板自动收起不写入（沿用各脚本既有语义）。收起任一面板 → 全部收起；展开时各脚本
     * 仍按自身页面类型 + pixiv_panel_active 互斥决定实际可见的那一个。
     * ------------------------------------------------------------------------ */
    const PixivPanelState = (() => {
        const SHARED_KEY = '__PixivPanelState_v1__';
        if (typeof window !== 'undefined' && window[SHARED_KEY]) return window[SHARED_KEY];
        const LS_KEY = 'pixiv_panel_collapsed_shared';
        const BC_NAME = '__pixiv_panel_collapsed_v1__';
        let collapsed = null;
        const listeners = new Set();
        let bc = null;
        const readLS = () => { try { return localStorage.getItem(LS_KEY) === '1'; } catch (e) { return false; } };
        const notify = () => listeners.forEach(fn => { try { fn(collapsed); } catch (e) { console.error('[PixivPanelState]', e); } });
        function ensureInit() {
            if (collapsed !== null) return;
            collapsed = readLS();
            try {
                if (typeof BroadcastChannel !== 'undefined') {
                    bc = new BroadcastChannel(BC_NAME);
                    bc.addEventListener('message', ev => {
                        if (ev && ev.data && ev.data.type === 'panel-collapsed') {
                            const v = ev.data.value === true;
                            if (v !== collapsed) { collapsed = v; notify(); }
                        }
                    });
                }
            } catch (e) {}
            try {
                window.addEventListener('storage', ev => {
                    if (ev.key !== LS_KEY) return;
                    const v = ev.newValue === '1';
                    if (v !== collapsed) { collapsed = v; notify(); }
                });
            } catch (e) {}
            try {
                setInterval(() => {
                    const v = readLS();
                    if (v !== collapsed) { collapsed = v; notify(); }
                }, 1000);
            } catch (e) {}
        }
        function get() { ensureInit(); return collapsed; }
        function set(v) {
            ensureInit();
            v = v === true;
            if (v === collapsed) return;
            collapsed = v;
            try { localStorage.setItem(LS_KEY, v ? '1' : '0'); } catch (e) {}
            if (bc) { try { bc.postMessage({ type: 'panel-collapsed', value: v }); } catch (e) {} }
            notify();
        }
        function onChange(fn) { ensureInit(); listeners.add(fn); return () => listeners.delete(fn); }
        const api = { get, set, onChange };
        try { if (typeof window !== 'undefined') window[SHARED_KEY] = api; } catch (e) {}
        return api;
    })();

    function loadPanelCollapsed() { return PixivPanelState.get(); }
    function savePanelCollapsed(collapsed) { PixivPanelState.set(collapsed); }

    // 每个功能的开关 / 设置持久化 key 前缀（功能之间互不影响）
    const featEnabledKey = id => 'pixiv_enhance_feat_' + id + '_enabled';
    const featSettingsKey = id => 'pixiv_enhance_feat_' + id + '_settings';

    /* ========== PromptGuard：跨脚本一次性弹窗协调 ========== */
    // localStorage + BroadcastChannel 在同源页面下被所有用户脚本共享，借此让多个脚本
    // （或 All-in-One bundle 的多个模块）相同的弹窗只触发一次。
    const PromptGuard = (() => {
        const LS_KEY = '__pixiv_prompt_state_v1__';
        const CHAN_NAME = '__pixiv_prompt_channel_v1__';
        const ownerId = Math.random().toString(36).slice(2) + Date.now().toString(36);
        const claimed = new Set();
        let bc = null;
        try { if (typeof BroadcastChannel !== 'undefined') bc = new BroadcastChannel(CHAN_NAME); } catch (e) {}
        if (bc) bc.addEventListener('message', ev => {
            if (ev && ev.data && ev.data.type === 'claim' && ev.data.id) claimed.add(ev.data.id);
        });
        const readState = () => {
            try { return JSON.parse(localStorage.getItem(LS_KEY) || '{}') || {}; } catch (e) { return {}; }
        };
        const writeState = s => {
            try { localStorage.setItem(LS_KEY, JSON.stringify(s)); } catch (e) {}
        };
        const isFresh = (entry, opts) => {
            if (!entry) return false;
            if (opts.persist) return true;
            return Date.now() - (entry.t || 0) < (opts.ttlMs || 60000);
        };
        function once(id, opts, fn) {
            opts = opts || {};
            if (claimed.has(id)) return false;
            const state = readState();
            if (isFresh(state[id], opts)) { claimed.add(id); return false; }
            claimed.add(id);
            state[id] = { t: Date.now(), owner: ownerId };
            writeState(state);
            if (bc) { try { bc.postMessage({ type: 'claim', id }); } catch (e) {} }
            // 30ms jitter：让同帧启动的对端有机会覆盖 owner，最终只有一个 ownerId 留存并执行 fn
            setTimeout(() => {
                const cur = readState()[id];
                if (!cur || cur.owner !== ownerId) return;
                try { fn(); } catch (e) { console.error('[PromptGuard]', e); }
            }, 30);
            return true;
        }
        return { once };
    })();

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
        const SUPPORTED = ['en-US', 'zh-CN'];
        const DEFAULT_LANG = 'en-US';

        let DICT = { 'en-US': {}, 'zh-CN': {} };
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
            // Cross-sandbox polling fallback: standalone userscripts each run
            // in a separate Tampermonkey sandbox; BroadcastChannel delivery
            // between sandboxes is unreliable and the storage event never
            // fires within the same browsing context. Polling every ~1s
            // picks up any change made by a sibling script.
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

    PixivUserscriptI18n.register({
        'en-US': {
            'switcher.label': 'Language',
            'common.dialog.unauthorized': 'Backend requires login. Opening login page...',
            'enhance.title': '🧰 Pixiv Experience Toolbox',
            'enhance.fab.title': 'Pixiv Experience Toolbox',
            'enhance.menu.open': 'Open Pixiv Experience Toolbox',
            'enhance.action.collapse': 'Collapse',
            'enhance.action.failed': 'Operation failed, please retry.',
            'enhance.section.features': 'Features',
            'enhance.setting.server': 'Server URL:',
            'enhance.footer.hint': 'Each feature is independent — toggle them on demand. More features will be added over time.',
            'enhance.multi-instance.warn': 'Pixiv Experience Toolbox is running more than once on this page — most likely both the standalone userscript and the All-in-One bundle are enabled. They use separate storage and would conflict (duplicate panels/borders, settings not shared), so this extra instance has been disabled. Please keep only one of them enabled in Tampermonkey.',
            'enhance.feature.enable': 'Enable',
            'enhance.gate.checking': 'Checking server status...',
            'enhance.gate.not-local': 'Requires the server URL to point to localhost / 127.0.0.1.',
            'enhance.gate.not-solo': 'Requires the server to run in solo mode.',
            'enhance.gate.not-login': 'Requires being logged in to the server (solo mode).',
            'enhance.gate.unreachable': 'Server unreachable. Check the server URL and that it is running.',
            'enhance.gate.ready': 'Conditions met. You can enable this feature.',
            'downloaded-border.name': 'Border on downloaded works',
            'downloaded-border.desc': 'Scans artwork and novel cards on the page and draws a border on the thumbnail of anything already downloaded by the server.',
            'downloaded-border.setting.width': 'Border width (px):',
            'downloaded-border.setting.color': 'Border color:',
            'downloaded-border.setting.style': 'Border style:',
            'downloaded-border.style.solid': 'Solid',
            'downloaded-border.style.dashed': 'Dashed',
            'downloaded-border.style.double': 'Double',
            'cookie-sync.name': 'One-click save Cookie',
            'cookie-sync.desc': 'Reads the current pixiv.net cookie and saves it to the server (shared with the batch page Cookie setting), so downloads/searches that need a login can use it. Requires solo mode and being logged in; the server URL does not matter. NOTE: the login token PHPSESSID is an HttpOnly cookie — this needs Tampermonkey → Settings → Config mode "Advanced" → Security → "Allow scripts to access cookies" = All. Keeping that on permanently is NOT recommended (any other userscript could then read your login credentials). Unless you trust every userscript installed, only set it to All temporarily for the fetch and change it back to "All except HttpOnly" right after; or prefer the manual method in the Cookie guide instead.',
            'cookie-sync.action': 'Get & save Cookie now',
            'cookie-sync.status.empty': 'No cookie found — make sure you are logged in to Pixiv in this browser.',
            'cookie-sync.status.sending': 'Sending to server...',
            'cookie-sync.status.success': 'Cookie saved to the server.',
            'cookie-sync.status.no-phpsessid': 'Could not read the login cookie (PHPSESSID), so saving was cancelled to avoid overwriting your existing cookie. The Pixiv HttpOnly session cookie was not exposed by your browser/userscript manager. See the Cookie guide in the Cookie section on the download page to set it manually.',
            'cookie-sync.status.failed': 'Save failed (HTTP {0}).',
            'cookie-sync.signal.done': 'Cookie synced. You can close this page and return to PixivDownloader.',
            'cookie-sync.signal.fail': 'Cookie sync failed. Make sure you are logged in to Pixiv and retry; you may close this page.',
            'cookie-sync.signal.nophp': 'Could not read the login cookie (PHPSESSID); nothing was saved. The Pixiv HttpOnly session cookie was not exposed by your browser/userscript manager. See the Cookie guide in the Cookie section on the download page to set it manually. You may close this page.'
        },
        'zh-CN': {
            'switcher.label': '语言',
            'common.dialog.unauthorized': '后端服务需要登录验证，即将为您打开登录页面...',
            'enhance.title': '🧰 Pixiv 体验增强工具箱',
            'enhance.fab.title': 'Pixiv 体验增强工具箱',
            'enhance.menu.open': '打开 Pixiv 体验增强工具箱',
            'enhance.action.collapse': '收起',
            'enhance.action.failed': '操作失败，请重试。',
            'enhance.section.features': '功能列表',
            'enhance.setting.server': '服务器地址:',
            'enhance.footer.hint': '每个功能相互独立，可按需开关。后续会持续增加更多功能。',
            'enhance.multi-instance.warn': '检测到「Pixiv 体验增强工具箱」在本页重复运行——很可能同时启用了独立脚本和 All-in-One 合并包。两者存储互相独立、会冲突（双面板/双边框、设置不互通），本重复实例已自动停用。请在 Tampermonkey 中只保留其中一个启用。',
            'enhance.feature.enable': '启用',
            'enhance.gate.checking': '正在检测服务器状态...',
            'enhance.gate.not-local': '需要服务器地址指向 localhost / 127.0.0.1。',
            'enhance.gate.not-solo': '需要服务器运行在 solo 模式。',
            'enhance.gate.not-login': '需要已登录服务器（solo 模式）。',
            'enhance.gate.unreachable': '无法连接服务器，请检查服务器地址以及服务是否已启动。',
            'enhance.gate.ready': '条件已满足，可以开启此功能。',
            'downloaded-border.name': '已下载作品加边框',
            'downloaded-border.desc': '抓取页面上的作品与小说卡片，对服务器已下载过的作品/小说在缩略图上添加边框。',
            'downloaded-border.setting.width': '边框宽度(px):',
            'downloaded-border.setting.color': '边框颜色:',
            'downloaded-border.setting.style': '边框样式:',
            'downloaded-border.style.solid': '实线',
            'downloaded-border.style.dashed': '虚线',
            'downloaded-border.style.double': '双线',
            'cookie-sync.name': '一键获取 Cookie',
            'cookie-sync.desc': '读取当前 pixiv.net 的 Cookie 并保存到服务器（与批量下载页的「Cookie」设置共用同一存储），需要登录态的下载/搜索即可直接使用。要求 solo 模式且已登录，服务器地址不限。注意：登录凭证 PHPSESSID 是 HttpOnly Cookie，需在 Tampermonkey「设置 → 配置模式选『高级』→ 安全 → 允许脚本访问 Cookie」设为 All 才能读取；但不建议长期开启（开启后其它油猴脚本也能任意读取您的登录凭证）。除非您信任已安装的所有脚本，否则建议仅在获取时临时设为 All、成功后立即改回『除了 HttpOnly』，或改用《获取 Cookie 指南》中的第一种手动方法。',
            'cookie-sync.action': '一键获取并保存 Cookie',
            'cookie-sync.status.empty': '未读取到 Cookie，请确认已在本浏览器登录 Pixiv。',
            'cookie-sync.status.sending': '正在发送到服务器...',
            'cookie-sync.status.success': 'Cookie 已保存到服务器。',
            'cookie-sync.status.no-phpsessid': '未能读取登录态 Cookie（PHPSESSID），已取消保存以免覆盖现有 Cookie。本环境的浏览器/脚本管理器未暴露 Pixiv 的 HttpOnly 会话 Cookie，请在下载页 Cookie 区查看《获取 Cookie 指南》手动设置。',
            'cookie-sync.status.failed': '保存失败（HTTP {0}）。',
            'cookie-sync.signal.done': 'Cookie 已同步，请关闭本页面并返回 PixivDownloader。',
            'cookie-sync.signal.fail': 'Cookie 同步失败，请确认已登录 Pixiv 后重试，可关闭本页面。',
            'cookie-sync.signal.nophp': '未能读取登录态 Cookie（PHPSESSID），未保存任何内容。本环境的浏览器/脚本管理器未暴露 Pixiv 的 HttpOnly 会话 Cookie，请在下载页 Cookie 区查看《获取 Cookie 指南》手动设置。可关闭本页面。'
        }
    });

    const t = (key, fallback, args) => PixivUserscriptI18n.t(key, fallback, args);

    /* ========== DOM 帮助函数 ========== */
    function $el(tag, props = {}, children = []) {
        const e = document.createElement(tag);
        Object.entries(props).forEach(([k, v]) => {
            if (k === 'style') {
                if (typeof v === 'string') e.style.cssText = v;
                else Object.assign(e.style, v);
            } else if (k === 'html') e.innerHTML = v;
            else e[k] = v;
        });
        (Array.isArray(children) ? children : [children]).forEach(c => {
            if (typeof c === 'string') e.appendChild(document.createTextNode(c));
            else if (c) e.appendChild(c);
        });
        return e;
    }

    /* ========== solo 模式未登录处理（跨脚本去重）========== */
    function handleUnauthorized() {
        PromptGuard.once('unauthorized', { ttlMs: 60000 }, () => {
            alert(t('common.dialog.unauthorized', '后端服务需要登录验证，即将为您打开登录页面...'));
            window.open(serverBase + '/login.html', '_blank');
        });
    }

    function gmRequest(opts) {
        return new Promise((resolve, reject) => {
            let settled = false;
            const finish = (fn, arg) => { if (settled) return; settled = true; clearTimeout(guard); fn(arg); };
            // 兜底超时：个别 Tampermonkey 版本对连接被拒/挂起不触发 onerror/ontimeout，
            // 否则 Promise 永不 settle，会让状态卡在「检测中」。
            const guard = setTimeout(() => finish(reject, new Error('timeout(guard)')), (opts.timeout || 8000) + 2000);
            try {
                GM_xmlhttpRequest(Object.assign({}, opts, {
                    onload: res => finish(resolve, res),
                    onerror: () => finish(reject, new Error('network error')),
                    ontimeout: () => finish(reject, new Error('timeout'))
                }));
            } catch (e) {
                finish(reject, e);
            }
        });
    }

    /* ========== Cookie 推送到服务器（功能按钮与 hash 信号共用）==========
     * 读取完整 Cookie（含 HttpOnly 的 PHPSESSID，见 readPixivCookieHeader），
     * 并入服务器端 solo 批量状态（/api/batch/state 的 pixiv_cookie /
     * pixiv_cookie_fmt 键）。返回 { code, status }：
     *   'empty'        无可读 Cookie
     *   'unauthorized' 401（未登录后端）
     *   'http'         其它非 200
     *   'network'      请求异常
     *   'ok'           成功且含 PHPSESSID
     *   'no-php'       读不到登录态 PHPSESSID（已取消保存，不覆盖现有 Cookie）
     */
    const COOKIE_LOG = '[Pixiv体验增强][cookie]';

    // 解析 GM_cookie / GM.cookie API（仅 Tampermonkey / FireMonkey 提供；
    // Violentmonkey / Greasemonkey 没有，document.cookie 读不到 HttpOnly 的 PHPSESSID）。
    function resolveCookieApi() {
        try {
            if (typeof GM_cookie !== 'undefined' && GM_cookie && typeof GM_cookie.list === 'function') {
                return GM_cookie;
            }
        } catch (e) {}
        try {
            if (typeof GM !== 'undefined' && GM && GM.cookie && typeof GM.cookie.list === 'function') {
                return GM.cookie;
            }
        } catch (e) {}
        return null;
    }

    // 用一组 details 调一次 GM_cookie.list，统一回调式与 Promise 式，返回 cookie 数组（失败 → null）
    function gmCookieList(api, details) {
        return new Promise(resolve => {
            let settled = false;
            const finish = (arr, err) => {
                if (settled) return;
                settled = true;
                if (err) console.warn(COOKIE_LOG, 'GM_cookie.list error', details, err);
                resolve(Array.isArray(arr) ? arr : null);
            };
            try {
                const ret = api.list(details, (cookies, error) => finish(cookies, error));
                if (ret && typeof ret.then === 'function') {
                    ret.then(c => finish(c)).catch(e => finish(null, e));
                }
            } catch (e) {
                finish(null, e);
            }
            setTimeout(() => finish(null, 'timeout'), 4000);
        });
    }

    // 读取完整 Cookie 头串：用 GM_cookie 取全部（含 HttpOnly 的 PHPSESSID），与
    // document.cookie 合并（GM 结果优先），尽力保证登录态 cookie 在内。
    async function readPixivCookieHeader() {
        const docCookie = (document.cookie || '').trim();
        const api = resolveCookieApi();
        if (!api) {
            console.warn(COOKIE_LOG, 'GM_cookie 不可用（脚本管理器可能是 Violentmonkey/Greasemonkey，或未授予 GM_cookie 权限）→ 回退 document.cookie');
            return docCookie;
        }
        const host = location.host;                 // www.pixiv.net
        const cleanUrl = location.protocol + '//' + host + '/';
        // 多种 details 形态依次尝试，合并去重（不同管理器/版本对 url/domain 支持不一）
        const attempts = [
            { url: cleanUrl },
            { domain: host },
            { domain: 'pixiv.net' },
            {}
        ];
        const map = new Map();
        // 先填入 document.cookie 的非 HttpOnly 项，GM 结果再覆盖/补充
        docCookie.split(';').forEach(part => {
            const i = part.indexOf('=');
            if (i > 0) map.set(part.slice(0, i).trim(), part.slice(i + 1).trim());
        });
        let gotAny = false;
        for (const details of attempts) {
            const cookies = await gmCookieList(api, details);
            const n = cookies ? cookies.length : -1;
            const names = cookies ? cookies.map(c => c && c.name).filter(Boolean) : [];
            console.warn(COOKIE_LOG, 'GM_cookie.list', JSON.stringify(details),
                '→ ' + (n < 0 ? 'null/失败' : n + ' 个'),
                names.length ? '名称=[' + names.join(',') + ']' : '');
            if (cookies && cookies.length) {
                gotAny = true;
                cookies.forEach(c => {
                    if (c && c.name) map.set(String(c.name), String(c.value != null ? c.value : ''));
                });
                if (map.has('PHPSESSID')) break;     // 拿到登录态关键 cookie 即可停
            }
        }
        const header = Array.from(map.entries()).map(([k, v]) => k + '=' + v).join('; ');
        console.warn(COOKIE_LOG,
            'GM_cookie 可用；合并后共 ' + map.size + ' 个 cookie；',
            'GM 返回=' + gotAny, 'PHPSESSID=' + map.has('PHPSESSID'),
            '名称=[' + Array.from(map.keys()).join(',') + ']');
        return header || docCookie;
    }

    async function pushPixivCookieToServer(base) {
        const raw = (await readPixivCookieHeader()).trim();
        const hasPhp = !!raw && /(?:^|;\s*)PHPSESSID=/.test(raw);
        const status = hasPhp ? 'ok' : (raw ? 'no-php' : 'empty');
        const stateUrl = base + '/api/batch/state';
        try {
            const getRes = await gmRequest({ method: 'GET', url: stateUrl, timeout: 10000 });
            if (getRes.status === 401) return { code: 'unauthorized' };
            if (getRes.status !== 200) return { code: 'http', status: getRes.status };

            let state = {};
            try {
                const parsed = JSON.parse(getRes.responseText || '{}');
                const s = parsed && parsed.state;
                if (s && typeof s === 'object') state = s;
            } catch (e) { state = {}; }

            // 仅在拿到登录态 PHPSESSID 时才写入 Cookie，避免用无登录态的
            // Cookie 覆盖用户已手动粘贴的可用 Cookie。
            if (hasPhp) {
                state['pixiv_cookie'] = raw;
                state['pixiv_cookie_fmt'] = 'header';
            }
            // 始终更新同步标记 + 结果状态：即使没写 Cookie，也让批量下载页能
            // 立即得知本次同步已结束及其结果，不必空等到超时。
            state['pixiv_cookie_sync_at'] = String(Date.now());
            state['pixiv_cookie_sync_status'] = status;

            const postRes = await gmRequest({
                method: 'POST',
                url: stateUrl,
                headers: { 'Content-Type': 'application/json' },
                data: JSON.stringify({ state }),
                timeout: 10000
            });
            if (postRes.status === 401) return { code: 'unauthorized' };
            if (postRes.status !== 200) return { code: 'http', status: postRes.status };
            return { code: status };
        } catch (e) {
            console.warn('[Pixiv体验增强] Cookie 推送失败：', e);
            return { code: 'network' };
        }
    }

    /* ========== 服务器状态（localhost + solo + 已登录）========== */
    const ServerStatus = (() => {
        const listeners = new Set();
        let state = { phase: 'checking', local: false, mode: null, loggedIn: false };
        let inFlight = null;

        function isLocalHost(base) {
            try {
                const host = new URL(base).hostname.toLowerCase();
                return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
            } catch (e) {
                return false;
            }
        }

        function notify() {
            listeners.forEach(fn => { try { fn(state); } catch (e) { console.error('[ServerStatus]', e); } });
        }

        function setState(next) {
            state = Object.assign({}, state, next);
            notify();
        }

        // 始终探测服务器（即使地址不是 localhost）：是否 localhost 单独记在 state.local，
        // 由各功能的 gate 决定是否把「非本机」当作阻塞条件（solo-login 门槛不要求本机）。
        async function refresh() {
            const base = serverBase;
            const local = isLocalHost(base);
            if (inFlight) return inFlight;
            setState({ phase: 'checking', local });
            inFlight = (async () => {
                try {
                    const [statusRes, checkRes] = await Promise.all([
                        gmRequest({ method: 'GET', url: base + '/api/setup/status', timeout: 6000 }),
                        gmRequest({ method: 'GET', url: base + '/api/auth/check', timeout: 6000 })
                    ]);
                    let mode = null;
                    let loggedIn = false;
                    try { mode = (JSON.parse(statusRes.responseText) || {}).mode || null; } catch (e) {}
                    try { loggedIn = (JSON.parse(checkRes.responseText) || {}).valid === true; } catch (e) {}
                    setState({ phase: 'resolved', local, mode, loggedIn });
                } catch (e) {
                    console.warn('[Pixiv体验增强] 服务器状态检测失败：', e);
                    setState({ phase: 'unreachable', local, mode: null, loggedIn: false });
                }
            })();
            try { await inFlight; } finally { inFlight = null; }
        }

        function get() { return state; }
        function onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }

        return { refresh, get, onChange };
    })();

    /* ========== 门槛求值 ==========
     * gate 取值：
     *   null              无门槛
     *   'local-solo-login' 需要本机 + solo 模式 + 已登录
     *   'solo-login'       需要 solo 模式 + 已登录（不要求本机，服务器地址不重要）
     * 返回 { ok, key, fb, color }，供功能可用性判断与面板提示文案复用。
     */
    function gateInfo(gate) {
        const s = ServerStatus.get();
        const GRAY = '#888', RED = '#dc3545', GREEN = '#28a745';
        if (gate !== 'local-solo-login' && gate !== 'solo-login') {
            return { ok: true, key: 'enhance.gate.ready', fb: '条件已满足，可以开启此功能。', color: GREEN };
        }
        if (s.phase === 'checking') {
            return { ok: false, key: 'enhance.gate.checking', fb: '正在检测服务器状态...', color: GRAY };
        }
        if (gate === 'local-solo-login' && !s.local) {
            return { ok: false, key: 'enhance.gate.not-local', fb: '需要服务器地址指向 localhost / 127.0.0.1。', color: RED };
        }
        if (s.phase === 'unreachable') {
            return { ok: false, key: 'enhance.gate.unreachable', fb: '无法连接服务器。', color: RED };
        }
        if (s.mode !== 'solo') {
            return { ok: false, key: 'enhance.gate.not-solo', fb: '需要服务器运行在 solo 模式。', color: RED };
        }
        if (!s.loggedIn) {
            return { ok: false, key: 'enhance.gate.not-login', fb: '需要已登录服务器（solo 模式）。', color: RED };
        }
        return { ok: true, key: 'enhance.gate.ready', fb: '条件已满足，可以开启此功能。', color: GREEN };
    }

    /* ========== 功能注册中心 ==========
     * 每个功能是一个独立对象，互不依赖：
     *   id            唯一标识
     *   gate          'local-solo-login' / 'solo-login' 时由 ServerStatus 决定可用性，null 表示无门槛
     *   nameKey/descKey  i18n key
     *   settingDefs   子设置字段（开启后展示），声明式渲染
     *   defaults      子设置默认值
     *   start(api)    功能启动；api.getSetting(key) 读子设置
     *   stop()        功能停止（必须可逆，清理所有副作用）
     *   onSettings(api)  子设置变化时回调（可选）
     * 新增功能 = push 一个对象，无需改动面板/门控逻辑。
     */
    const FeatureRegistry = (() => {
        const features = [];

        function register(def) {
            const defaults = def.defaults || {};
            let settings;
            try {
                const raw = GM_getValue(featSettingsKey(def.id), null);
                settings = raw ? Object.assign({}, defaults, JSON.parse(raw)) : Object.assign({}, defaults);
            } catch (e) {
                settings = Object.assign({}, defaults);
            }
            const feature = {
                def,
                running: false,
                enabled: GM_getValue(featEnabledKey(def.id), false) === true,
                settings,
                api: null
            };
            feature.api = {
                getSetting: key => feature.settings[key],
                get serverBase() { return serverBase; },
                gmRequest,
                handleUnauthorized
            };
            features.push(feature);
            return feature;
        }

        function available(feature) {
            const g = feature.def.gate;
            if (g !== 'local-solo-login' && g !== 'solo-login') return true;
            return gateInfo(g).ok;
        }

        function evaluate() {
            features.forEach(feature => {
                const shouldRun = feature.enabled && available(feature);
                if (shouldRun && !feature.running) {
                    feature.running = true;
                    try { feature.def.start(feature.api); } catch (e) { console.error('[' + feature.def.id + '] start', e); }
                } else if (!shouldRun && feature.running) {
                    feature.running = false;
                    try { feature.def.stop(feature.api); } catch (e) { console.error('[' + feature.def.id + '] stop', e); }
                }
            });
        }

        function setEnabled(feature, on) {
            feature.enabled = on === true;
            GM_setValue(featEnabledKey(feature.def.id), feature.enabled);
            evaluate();
        }

        function setSetting(feature, key, value) {
            feature.settings[key] = value;
            try {
                GM_setValue(featSettingsKey(feature.def.id), JSON.stringify(feature.settings));
            } catch (e) {
                console.error('[Pixiv体验增强] 保存设置失败：', e);
            }
            if (feature.running && typeof feature.def.onSettings === 'function') {
                try { feature.def.onSettings(feature.api); } catch (e) { console.error('[' + feature.def.id + '] onSettings', e); }
            }
        }

        ServerStatus.onChange(() => evaluate());

        return { register, list: () => features, available, evaluate, setEnabled, setSetting };
    })();

    /* ========== 功能：已下载作品加边框 ========== */
    FeatureRegistry.register({
        id: 'downloaded-border',
        gate: 'local-solo-login',
        nameKey: 'downloaded-border.name',
        descKey: 'downloaded-border.desc',
        defaults: { width: 3, color: '#00ff00', style: 'solid' },
        settingDefs: [
            { key: 'width', type: 'number', labelKey: 'downloaded-border.setting.width', min: 1, max: 12, step: 1 },
            { key: 'color', type: 'color', labelKey: 'downloaded-border.setting.color' },
            {
                key: 'style', type: 'select', labelKey: 'downloaded-border.setting.style',
                options: [
                    { value: 'solid', labelKey: 'downloaded-border.style.solid' },
                    { value: 'dashed', labelKey: 'downloaded-border.style.dashed' },
                    { value: 'double', labelKey: 'downloaded-border.style.double' }
                ]
            }
        ],

        // 内部状态
        _observer: null,
        _scanTimer: null,
        _urlTimer: null,
        _safetyTimer: null,
        _lastHref: '',
        _cache: new Map(),     // artworkId -> true(已下载) / false(未下载)
        _querying: new Set(),  // 正在查询的 id，避免重复请求
        HOST_CLASS: 'pixiv-enh-host',
        FRAME_CLASS: 'pixiv-enh-frame',

        // 直接给每个 overlay 元素写 inline 样式：不依赖注入 <style>，绕开页面 CSP
        // 对 style 元素的限制，也不会因功能 stop/start（每 60s 服务器复检会有一次
        // checking 抖动）丢失或读到过期的样式表。样式始终来自 feature.settings 这一唯一来源。
        _frameCss(api) {
            const w = Math.max(1, parseInt(api.getSetting('width'), 10) || 3);
            const color = api.getSetting('color') || '#00ff00';
            const style = api.getSetting('style') || 'solid';
            return 'position:absolute;inset:0;pointer-events:none;box-sizing:border-box;'
                + 'border:' + w + 'px ' + style + ' ' + color + ';'
                + 'border-radius:8px;z-index:2;';
        },

        // 设置变更时即时重绘所有已存在的边框
        _restyleAll(api) {
            const css = this._frameCss(api);
            document.querySelectorAll('.' + this.FRAME_CLASS).forEach(el => { el.style.cssText = css; });
        },

        // 作品与小说共用同一数字 ID 空间，缓存键统一加前缀（a: 作品 / n: 小说）避免串号
        _collectTargets() {
            const byKey = new Map();
            const push = (key, a) => {
                if (!byKey.has(key)) byKey.set(key, []);
                byKey.get(key).push(a);
            };
            document.querySelectorAll('a[href*="/artworks/"]').forEach(a => {
                const m = (a.getAttribute('href') || '').match(/\/artworks\/(\d+)/);
                if (m) push('a:' + m[1], a);
            });
            document.querySelectorAll('a[href*="/novel/show.php"]').forEach(a => {
                const m = (a.getAttribute('href') || '').match(/\/novel\/show\.php\?[^#]*\bid=(\d+)/);
                if (m) push('n:' + m[1], a);
            });
            return byKey;
        },

        // 同一作品通常有「缩略图链接 + 标题文本链接」两个 <a>。在「精选新作」等版面，
        // 标题链接是有高度的内联文本（约 184x22），而缩略图 <a> 的可见盒子由内部绝对
        // 定位子元素撑开、自身 rect 近乎为 0——只按面积比会错选到标题，边框就圈到了标题上。
        // 改为优先锁定真正承载缩略图 <img> 的元素，再向上取与图片等高的最大盒子作为 host，
        // 让边框始终贴住 184x184 的图片而非标题。其它版面缩略图 <a> 本身有尺寸，走兜底逻辑。
        _pickThumb(anchors) {
            let bestImg = null;
            let bestImgArea = 0;
            anchors.forEach(a => {
                a.querySelectorAll('img').forEach(img => {
                    const r = img.getBoundingClientRect();
                    const area = r.width * r.height;
                    if (area > bestImgArea) { bestImgArea = area; bestImg = img; }
                });
            });
            if (bestImg && bestImgArea >= 1600) {
                // <img> 自身不能挂子元素；从其父级开始向上，跳过 0 高度的包裹链接，
                // 取“仍没明显高于图片”（再往上会把标题行包进来，高度突增）的最大盒子。
                const imgH = bestImg.getBoundingClientRect().height;
                let host = bestImg.parentElement || bestImg;
                let hostArea = 0;
                let node = bestImg.parentElement;
                while (node && node !== document.body) {
                    const r = node.getBoundingClientRect();
                    if (r.height > imgH + 8) break;
                    const area = r.width * r.height;
                    if (area > hostArea) { hostArea = area; host = node; }
                    node = node.parentElement;
                }
                return host;
            }
            // 兜底：用户/搜索/排行等版面缩略图 <a> 本身就有尺寸，沿用原按面积选链接逻辑
            let best = null;
            let bestArea = 0;
            anchors.forEach(a => {
                const r = a.getBoundingClientRect();
                const area = r.width * r.height;
                if (area > bestArea) { bestArea = area; best = a; }
            });
            if (best && bestArea >= 1600) return best;
            return anchors.find(a => a.querySelector('img')) || null;
        },

        _applyMarks(byId, api) {
            const css = this._frameCss(api);
            byId.forEach((anchors, id) => {
                if (this._cache.get(id) !== true) return;
                const host = this._pickThumb(anchors);
                if (!host) return;
                if (getComputedStyle(host).position === 'static') {
                    host.style.position = 'relative';
                    host.dataset.pixivEnhPos = '1';
                }
                host.classList.add(this.HOST_CLASS);
                let frame = host.querySelector(':scope > .' + this.FRAME_CLASS);
                if (!frame) {
                    frame = document.createElement('div');
                    frame.className = this.FRAME_CLASS;
                    host.appendChild(frame);
                }
                // 每次都按当前设置重写，确保设置变更/卡片重建后样式始终最新
                frame.style.cssText = css;
            });
        },

        _scan(api) {
            const byKey = this._collectTargets();
            this._applyMarks(byKey, api);

            const unknownArt = [];
            const unknownNovel = [];
            byKey.forEach((_anchors, key) => {
                if (this._cache.has(key) || this._querying.has(key)) return;
                (key.charAt(0) === 'n' ? unknownNovel : unknownArt).push(key);
            });
            this._queryBatch(api, unknownArt, 'a');
            this._queryBatch(api, unknownNovel, 'n');
        },

        // kind: 'a' 作品 → /api/downloaded/batch；'n' 小说 → /api/gallery/novels/downloaded-batch
        _queryBatch(api, keys, kind) {
            if (!keys.length) return;
            const isNovel = kind === 'n';
            const url = api.serverBase + (isNovel ? '/api/gallery/novels/downloaded-batch' : '/api/downloaded/batch');
            const bodyKey = isNovel ? 'novelIds' : 'artworkIds';
            const CHUNK = 200;
            for (let i = 0; i < keys.length; i += CHUNK) {
                const chunk = keys.slice(i, i + CHUNK);
                chunk.forEach(k => this._querying.add(k));
                const ids = chunk.map(k => Number(k.slice(2)));
                api.gmRequest({
                    method: 'POST',
                    url,
                    headers: { 'Content-Type': 'application/json' },
                    data: JSON.stringify({ [bodyKey]: ids }),
                    timeout: 15000
                }).then(res => {
                    if (res.status === 401) { api.handleUnauthorized(); return; }
                    if (res.status !== 200) {
                        console.warn('[Pixiv体验增强] 批量查询返回非 200：', res.status);
                        return;
                    }
                    const downloaded = new Set();
                    try {
                        const data = JSON.parse(res.responseText) || {};
                        if (isNovel) {
                            (data.novelIds || []).forEach(n => { if (n != null) downloaded.add(String(n)); });
                        } else {
                            (data.artworks || []).forEach(a => {
                                if (a && a.artworkId != null) downloaded.add(String(a.artworkId));
                            });
                        }
                    } catch (e) { return; }
                    chunk.forEach(k => this._cache.set(k, downloaded.has(k.slice(2))));
                    this._applyMarks(this._collectTargets(), api);
                }).catch((err) => {
                    // 失败的 key 解除占用，下次扫描可重试
                    console.warn('[Pixiv体验增强] 批量查询失败：', err);
                }).finally(() => {
                    chunk.forEach(k => this._querying.delete(k));
                });
            }
        },

        _scheduleScan(api) {
            clearTimeout(this._scanTimer);
            this._scanTimer = setTimeout(() => this._scan(api), 400);
        },

        start(api) {
            this._observer = new MutationObserver(() => this._scheduleScan(api));
            this._observer.observe(document.body, { childList: true, subtree: true });

            // Pixiv 是 SPA，URL 变化后需要重新扫描（已下载状态可继续复用缓存）
            this._lastHref = location.href;
            this._urlTimer = setInterval(() => {
                if (location.href !== this._lastHref) {
                    this._lastHref = location.href;
                    this._scheduleScan(api);
                }
            }, 800);

            // 兜底：Pixiv 用 React，滚动时会重建卡片节点导致我们加的 overlay 被丢弃；
            // MutationObserver 通常能捕获，但定期复扫一次更稳（命中缓存，不产生额外请求）。
            this._safetyTimer = setInterval(() => this._scheduleScan(api), 3000);

            this._scan(api);
        },

        stop() {
            if (this._observer) { this._observer.disconnect(); this._observer = null; }
            clearTimeout(this._scanTimer);
            clearInterval(this._urlTimer);
            clearInterval(this._safetyTimer);
            this._scanTimer = null;
            this._urlTimer = null;
            this._safetyTimer = null;
            document.querySelectorAll('.' + this.FRAME_CLASS).forEach(el => el.remove());
            document.querySelectorAll('.' + this.HOST_CLASS).forEach(el => {
                el.classList.remove(this.HOST_CLASS);
                if (el.dataset.pixivEnhPos) { el.style.position = ''; delete el.dataset.pixivEnhPos; }
            });
        },

        onSettings(api) {
            this._restyleAll(api);
        }
    });

    /* ========== 功能：一键获取并保存 Cookie ==========
     * 读取当前 pixiv.net 的完整 Cookie（含 HttpOnly PHPSESSID），合并进服务器端 solo 模式的批量状态
     * （/api/batch/state 的 pixiv_cookie / pixiv_cookie_fmt 键），与网页端「Cookie」
     * 设置共用同一存储，下载/搜索等需要登录态的请求即可直接使用。
     * 门槛 solo-login：仅要求 solo 模式 + 已登录，不要求服务器地址为本机。
     */
    FeatureRegistry.register({
        id: 'cookie-sync',
        gate: 'solo-login',
        nameKey: 'cookie-sync.name',
        descKey: 'cookie-sync.desc',
        settingDefs: [
            { key: 'sync', type: 'button', labelKey: 'cookie-sync.action' }
        ],

        start() {},
        stop() {},

        async onAction(api, _key, ctx) {
            ctx.setStatus(t('cookie-sync.status.sending', '正在发送到服务器...'), 'info');
            const r = await pushPixivCookieToServer(api.serverBase);
            if (r.code === 'unauthorized') { api.handleUnauthorized(); return; }
            if (r.code === 'empty') {
                ctx.setStatus(t('cookie-sync.status.empty',
                    '未读取到 Cookie，请确认已在本浏览器登录 Pixiv'), 'error');
            } else if (r.code === 'http' || r.code === 'network') {
                ctx.setStatus(t('cookie-sync.status.failed', '保存失败（HTTP {0}）',
                    [r.status != null ? r.status : '-']), 'error');
            } else if (r.code === 'no-php') {
                ctx.setStatus(t('cookie-sync.status.no-phpsessid',
                    '未能读取登录态 Cookie（PHPSESSID），已取消保存以免覆盖现有 Cookie'), 'error');
            } else {
                ctx.setStatus(t('cookie-sync.status.success', 'Cookie 已保存到服务器'), 'success');
            }
        }
    });

    /* ========== 语言切换器 ========== */
    function buildLangSwitcher() {
        const wrapper = document.createElement('span');
        wrapper.style.cssText = 'display:inline-flex;align-items:center;gap:6px;flex-shrink:0;';
        const select = document.createElement('select');
        select.title = t('switcher.label', '语言');
        select.style.cssText = 'padding:2px 4px;border:1px solid #ccc;border-radius:4px;background:#fff;color:#333;font-size:11px;';
        PixivUserscriptI18n.listSupported().forEach(lang => {
            const option = document.createElement('option');
            option.value = lang;
            option.textContent = lang === 'zh-CN' ? '简体中文' : 'English';
            option.selected = lang === PixivUserscriptI18n.getLang();
            select.appendChild(option);
        });
        select.addEventListener('change', () => PixivUserscriptI18n.setLang(select.value));
        wrapper.appendChild(select);
        return wrapper;
    }

    /* ========== 控制面板 ========== */
    const PANEL_ID = 'pixiv-enhance-ui';
    const FAB_ID = 'pixiv-enhance-fab';
    const BRAND = '#6f42c1';

    class Panel {
        constructor() {
            this.root = null;
            this._collapsed = loadPanelCollapsed();
        }

        mount() {
            if (document.getElementById(PANEL_ID)) return;
            this._build();
            this._applyCollapsed();
            ServerStatus.onChange(() => this.refreshFeatureStates());
            PixivUserscriptI18n.onChange(() => this.rerender());
            // 跨脚本共享折叠状态：仅“收起”跨脚本传播（收一个=全收）；
            // “展开”是针对被点击面板的显式操作，不在此处级联展开，否则点任意 FAB
            // 都会让站内全页面适用的工具箱抢先展开。展开由各自导航/初始化按页面类型决定。
            PixivPanelState.onChange(c => {
                if (c && !this._collapsed) this.toggleCollapse();
            });
        }

        _build() {
            const container = $el('div', {
                id: PANEL_ID,
                style: {
                    position: 'fixed', top: '210px', right: '20px', zIndex: 10000,
                    background: 'white', border: '2px solid ' + BRAND, borderRadius: '8px',
                    padding: '15px', boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
                    minWidth: '360px', maxWidth: '420px',
                    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    maxHeight: '80vh', overflowY: 'auto'
                }
            });

            const titleRow = $el('div', {
                style: { display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '15px', borderBottom: '2px solid #eee', paddingBottom: '10px' }
            });
            const collapseBtn = $el('button', {
                innerText: '◀', title: t('enhance.action.collapse', '收起'),
                style: { background: 'none', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '12px', padding: '2px 6px', color: '#666', flexShrink: '0' }
            });
            collapseBtn.addEventListener('click', () => this.manualToggleCollapse());
            const titleEl = $el('div', {
                innerText: t('enhance.title', '🧰 Pixiv 体验增强工具箱'),
                style: { fontWeight: 'bold', color: '#333', textAlign: 'center', fontSize: '15px', flex: '1' }
            });
            titleRow.appendChild(collapseBtn);
            titleRow.appendChild(titleEl);
            titleRow.appendChild(buildLangSwitcher());

            const sectionTitle = $el('div', {
                innerText: t('enhance.section.features', '功能列表'),
                style: { fontSize: '12px', fontWeight: 'bold', color: '#888', margin: '4px 0 8px' }
            });

            const featuresBox = $el('div', { style: { display: 'flex', flexDirection: 'column', gap: '10px' } });
            this._featuresBox = featuresBox;

            const serverRow = $el('div', {
                style: { display: 'flex', alignItems: 'center', marginTop: '14px', borderTop: '1px solid #eee', paddingTop: '12px' }
            });
            const serverLabel = $el('label', {
                innerText: t('enhance.setting.server', '服务器地址:'),
                style: { fontSize: '12px', marginRight: '10px', whiteSpace: 'nowrap' }
            });
            const serverInput = $el('input', {
                type: 'text', value: serverBase, placeholder: 'http://localhost:6999',
                style: { flex: '1', padding: '4px', border: '1px solid #ddd', borderRadius: '4px', fontSize: '12px' }
            });
            serverInput.addEventListener('change', () => {
                serverBase = serverInput.value.trim().replace(/\/$/, '') || 'http://localhost:6999';
                GM_setValue(KEY_SERVER_URL, serverBase);
                PixivUserscriptI18n.enrichFromBackend(serverBase);
                ServerStatus.refresh();
            });
            serverRow.appendChild(serverLabel);
            serverRow.appendChild(serverInput);

            const footer = $el('div', {
                innerText: t('enhance.footer.hint', '每个功能相互独立，可按需开关。后续会持续增加更多功能。'),
                style: { fontSize: '11px', color: '#999', marginTop: '10px', lineHeight: '1.5' }
            });

            container.appendChild(titleRow);
            container.appendChild(sectionTitle);
            container.appendChild(featuresBox);
            container.appendChild(serverRow);
            container.appendChild(footer);
            document.body.appendChild(container);
            this.root = container;

            // Mini FAB
            const oldFab = document.getElementById(FAB_ID);
            if (oldFab) oldFab.remove();
            const fab = $el('button', {
                id: FAB_ID, innerText: '🧰', title: t('enhance.fab.title', 'Pixiv 体验增强工具箱'),
                style: {
                    display: 'none', position: 'fixed', top: '210px', right: '20px', zIndex: '999999',
                    background: BRAND, color: 'white', border: 'none', borderRadius: '50%',
                    width: '40px', height: '40px', cursor: 'pointer', fontSize: '18px',
                    boxShadow: '0 2px 8px rgba(0,0,0,0.3)', lineHeight: '40px', textAlign: 'center', padding: '0'
                }
            });
            fab.addEventListener('click', () => this.manualToggleCollapse());
            document.body.appendChild(fab);
            this._fab = fab;

            this._renderFeatures();
        }

        _renderFeatures() {
            const box = this._featuresBox;
            box.innerHTML = '';
            this._featureCards = [];
            FeatureRegistry.list().forEach(feature => {
                const card = $el('div', {
                    style: { border: '1px solid #e6e6e6', borderRadius: '6px', padding: '10px', background: '#fafafa' }
                });
                card.setAttribute('data-feature', feature.def.id);

                const header = $el('label', {
                    style: { display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: 'bold', color: '#333' }
                });
                const toggle = $el('input', { type: 'checkbox' });
                toggle.checked = feature.enabled;
                toggle.addEventListener('change', () => {
                    FeatureRegistry.setEnabled(feature, toggle.checked);
                    this._renderFeatureBody(feature, card);
                });
                header.appendChild(toggle);
                header.appendChild($el('span', { innerText: t(feature.def.nameKey, feature.def.id) }));

                const desc = $el('div', {
                    innerText: t(feature.def.descKey, ''),
                    style: { fontSize: '11px', color: '#777', margin: '6px 0 0', lineHeight: '1.5' }
                });

                const gate = $el('div', {
                    'data-role': 'gate',
                    style: { fontSize: '11px', margin: '6px 0 0', lineHeight: '1.5' }
                });

                const sub = $el('div', {
                    'data-role': 'sub',
                    style: { marginTop: '10px', paddingTop: '10px', borderTop: '1px dashed #ddd', display: 'none', flexDirection: 'column', gap: '8px' }
                });
                feature.def.settingDefs && feature.def.settingDefs.forEach(sd => {
                    sub.appendChild(this._buildSettingRow(feature, sd));
                });

                card._toggle = toggle;
                card._gate = gate;
                card._sub = sub;

                card.appendChild(header);
                card.appendChild(desc);
                card.appendChild(gate);
                card.appendChild(sub);
                box.appendChild(card);

                this._featureCards.push({ feature, card });
                this._renderFeatureBody(feature, card);
            });
        }

        _buildSettingRow(feature, sd) {
            const row = $el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px' } });

            // 动作按钮：整行按钮 + 下方状态行，点击调用 feature.def.onAction(api, key, ctx)
            if (sd.type === 'button') {
                row.style.flexDirection = 'column';
                row.style.alignItems = 'stretch';
                const btn = $el('button', {
                    innerText: t(sd.labelKey, sd.key),
                    style: {
                        padding: '7px 10px', border: 'none', borderRadius: '4px',
                        background: BRAND, color: '#fff', fontSize: '12px',
                        fontWeight: 'bold', cursor: 'pointer'
                    }
                });
                const statusEl = $el('div', {
                    style: { fontSize: '11px', color: '#777', minHeight: '16px', lineHeight: '1.5' }
                });
                const ctx = {
                    setStatus: (text, kind) => {
                        statusEl.innerText = text || '';
                        statusEl.style.color = kind === 'error' ? '#dc3545'
                            : kind === 'success' ? '#28a745' : '#777';
                    }
                };
                btn.addEventListener('click', () => {
                    if (typeof feature.def.onAction !== 'function') return;
                    btn.disabled = true;
                    Promise.resolve()
                        .then(() => feature.def.onAction(feature.api, sd.key, ctx))
                        .catch(e => {
                            console.error('[' + feature.def.id + '] onAction', e);
                            ctx.setStatus(t('enhance.action.failed', '操作失败，请重试'), 'error');
                        })
                        .finally(() => { btn.disabled = false; });
                });
                row.appendChild(btn);
                row.appendChild(statusEl);
                return row;
            }

            const label = $el('label', {
                innerText: t(sd.labelKey, sd.key),
                style: { width: '120px', flexShrink: '0' }
            });
            let input;
            if (sd.type === 'select') {
                input = $el('select', { style: { flex: '1', padding: '4px', border: '1px solid #ddd', borderRadius: '4px' } });
                (sd.options || []).forEach(opt => {
                    const o = document.createElement('option');
                    o.value = opt.value;
                    o.textContent = t(opt.labelKey, opt.value);
                    o.selected = feature.settings[sd.key] === opt.value;
                    input.appendChild(o);
                });
                input.addEventListener('change', () => FeatureRegistry.setSetting(feature, sd.key, input.value));
            } else if (sd.type === 'color') {
                input = $el('input', { type: 'color', value: feature.settings[sd.key] || '#6f42c1', style: { width: '48px', height: '28px', padding: '0', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' } });
                const applyColor = () => FeatureRegistry.setSetting(feature, sd.key, input.value);
                input.addEventListener('input', applyColor);  // 拖动取色时实时生效
                input.addEventListener('change', applyColor);
            } else {
                input = $el('input', {
                    type: 'number', value: feature.settings[sd.key],
                    style: { width: '70px', padding: '4px', border: '1px solid #ddd', borderRadius: '4px' }
                });
                if (sd.min != null) input.min = sd.min;
                if (sd.max != null) input.max = sd.max;
                if (sd.step != null) input.step = sd.step;
                const clamp = (v) => {
                    if (sd.min != null) v = Math.max(sd.min, v);
                    if (sd.max != null) v = Math.min(sd.max, v);
                    return v;
                };
                // 输入/调节过程中实时生效（不回写输入框，避免打断输入）
                const live = () => {
                    const v = parseFloat(input.value);
                    if (Number.isFinite(v)) FeatureRegistry.setSetting(feature, sd.key, clamp(v));
                };
                // 失焦/回车时归一并回写
                const commit = () => {
                    let v = parseFloat(input.value);
                    if (!Number.isFinite(v)) v = sd.min != null ? sd.min : 0;
                    v = clamp(v);
                    input.value = v;
                    FeatureRegistry.setSetting(feature, sd.key, v);
                };
                input.addEventListener('input', live);
                input.addEventListener('change', commit);
            }
            row.appendChild(label);
            row.appendChild(input);
            return row;
        }

        _gateMessage(feature) {
            const g = feature.def.gate;
            if (g !== 'local-solo-login' && g !== 'solo-login') return null;
            return gateInfo(g);
        }

        _renderFeatureBody(feature, card) {
            const ok = FeatureRegistry.available(feature);
            const gateInfo = this._gateMessage(feature);
            card._toggle.disabled = !ok;
            card._toggle.checked = feature.enabled;
            if (gateInfo) {
                card._gate.style.display = 'block';
                card._gate.style.color = gateInfo.color;
                card._gate.innerText = t(gateInfo.key, gateInfo.fb);
            } else {
                card._gate.style.display = 'none';
            }
            card._sub.style.display = (feature.enabled && ok) ? 'flex' : 'none';
        }

        refreshFeatureStates() {
            if (!this.root || !this._featureCards) return;
            this._featureCards.forEach(({ feature, card }) => this._renderFeatureBody(feature, card));
        }

        rerender() {
            if (!this.root) return;
            this.root.remove();
            if (this._fab) this._fab.remove();
            this.root = null;
            this._build();
            this._applyCollapsed();
        }

        _applyCollapsed() {
            if (this._collapsed) {
                if (this.root) this.root.style.display = 'none';
                if (this._fab) this._fab.style.display = 'block';
            } else {
                if (this.root) this.root.style.display = 'block';
                if (this._fab) this._fab.style.display = 'none';
            }
        }

        toggleCollapse() {
            this._collapsed = !this._collapsed;
            this._applyCollapsed();
            if (!this._collapsed) {
                document.dispatchEvent(new CustomEvent('pixiv_panel_active', { detail: 'enhance' }));
            }
        }

        manualToggleCollapse() {
            this.toggleCollapse();
            savePanelCollapsed(this._collapsed);
        }

        show() {
            if (!document.getElementById(PANEL_ID)) this.mount();
            this._collapsed = false;
            savePanelCollapsed(false);
            this._applyCollapsed();
            document.dispatchEvent(new CustomEvent('pixiv_panel_active', { detail: 'enhance' }));
        }
    }

    /* ========== 一键导入 Cookie：hash 信号处理 ==========
     * 批量下载页「一键导入 Cookie」会用 window.open 打开
     * https://www.pixiv.net/#__pixiv_cookie_sync__ 这个临时弹窗。本脚本在此
     * 命中信号时：直接读取并回传 Cookie，然后自动关闭，不挂面板、不做其它初始化，
     * 让用户感知尽量小。无需在面板里开启「一键获取 Cookie」功能即可工作。
     */
    const COOKIE_SYNC_HASH = '__pixiv_cookie_sync__';
    function isCookieSyncSignal() {
        try {
            return (location.hash || '').indexOf(COOKIE_SYNC_HASH) >= 0;
        } catch (e) {
            return false;
        }
    }

    if (isCookieSyncSignal()) {
        const showSignalNotice = state => {
            try {
                const box = document.createElement('div');
                box.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:flex;'
                    + 'align-items:center;justify-content:center;background:#fff;color:#333;'
                    + 'font:15px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;'
                    + 'text-align:center;padding:28px;';
                box.textContent = state === 'ok'
                    ? t('cookie-sync.signal.done', 'Cookie 已同步，请关闭本页面并返回 PixivDownloader。')
                    : state === 'nophp'
                        ? t('cookie-sync.signal.nophp', '未能读取登录态 Cookie（PHPSESSID），未保存任何内容。本环境的浏览器/脚本管理器未暴露 Pixiv 的 HttpOnly 会话 Cookie，请在下载页 Cookie 区查看《获取 Cookie 指南》手动设置。可关闭本页面。')
                        : t('cookie-sync.signal.fail', 'Cookie 同步失败，请确认已登录 Pixiv 后重试，可关闭本页面。');
                document.documentElement.appendChild(box);
            } catch (e) {}
        };
        (async () => {
            let result;
            try {
                result = await pushPixivCookieToServer(serverBase);
            } catch (e) {
                result = { code: 'network' };
            }
            const code = result && result.code;
            const state = code === 'ok' ? 'ok' : (code === 'no-php' ? 'nophp' : 'fail');
            // 无论结果如何都自动关闭弹窗；仅在浏览器拒绝关闭时才显示文字提示兜底。
            setTimeout(() => {
                try { window.close(); } catch (e) {}
                if (!window.closed) showSignalNotice(state);
            }, 300);
        })();
        return; // 临时弹窗：不构建面板 / 不做重复实例处理
    }

    /* ========== 重复实例检测 ==========
     * 独立脚本与 All-in-One 合并包是两个不同的 Tampermonkey 脚本（GM 存储互不相通），
     * 但共享同一页面 DOM。各 userscript 的顶层代码在 document-end 是依次同步执行的，
     * 因此“读 DOM 标记 → 写标记”这一步对另一实例而言是原子的：先跑的占位成为唯一活动
     * 实例，后跑的读到标记即判定为重复实例，自我停用并弹一次提示（PromptGuard 跨实例
     * 去重，保证只弹一次）。
     */
    const DUP_ATTR = 'data-pixiv-enhance-running';
    const isDuplicateInstance = document.documentElement.getAttribute(DUP_ATTR) === '1';
    document.documentElement.setAttribute(DUP_ATTR, '1');

    /* ========== 引导 ========== */
    if (isDuplicateInstance) {
        console.warn('[Pixiv体验增强] 检测到本页已有一个实例在运行，本实例已停用（请勿同时启用独立脚本与 All-in-One 合并包）。');
        PromptGuard.once('enhance-multi-instance', { ttlMs: 3600000 }, () => {
            alert(t('enhance.multi-instance.warn',
                '检测到「Pixiv 体验增强工具箱」在本页重复运行，本重复实例已停用。请在 Tampermonkey 中只保留其中一个启用。'));
        });
    } else {
        const panel = new Panel();

        const init = () => {
            if (!document.body) { setTimeout(init, 100); return; }
            panel.mount();
            PixivUserscriptI18n.enrichFromBackend(serverBase);
            ServerStatus.refresh();
            // 周期性复检服务器状态（登录态/模式可能在外部变化）
            setInterval(() => ServerStatus.refresh(), 60000);
        };

        init();

        // 跨脚本面板协调：其它下载器面板展开时收起本面板，避免左右堆叠遮挡
        document.addEventListener('pixiv_panel_active', e => {
            if (e.detail && e.detail !== 'enhance' && !panel._collapsed) {
                panel.toggleCollapse();
            }
        });

        GM_registerMenuCommand(t('enhance.menu.open', '打开 Pixiv 体验增强工具箱'), () => {
            panel.show();
        });
    }
})();
