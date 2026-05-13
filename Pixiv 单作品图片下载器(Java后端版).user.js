// ==UserScript==
// @name         Pixiv作品图片下载器（Java后端版）
// @namespace    http://tampermonkey.net/
// @version      2.0.6
// @description  通过Java后端服务下载 Pixiv 单个作品图片
// @author       Rewritten by ChatGPT,Claude,Sywyar
// @match        https://www.pixiv.net/*
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @connect      localhost
// @connect      YOUR_SERVER_HOST
// @run-at       document-start
// ==/UserScript==

(function () {
    'use strict';

    // 配置后端服务地址
    const KEY_SERVER_URL = 'pixiv_server_base';
    let serverBase = GM_getValue(KEY_SERVER_URL, 'http://localhost:6999').replace(/\/$/, '');

    const KEY_USER_UUID = 'pixiv_user_uuid';
    const KEY_BOOKMARK_AFTER_DL = 'pixiv_bookmark_after_dl';
    const KEY_SKIP_HISTORY = 'pixiv_single_skip_history';
    const KEY_VERIFY_HISTORY_FILES = 'pixiv_single_verify_history_files';
    const VERIFY_HISTORY_FILES_TOOLTIP = '通过检查记录的目录是否存在、文件夹是否为空、文件夹中的文件是否包含图片来判断是否有效，如果无效则会重新下载';

    // 动态 URL 计算
    const getBackendURL = () => serverBase + '/api/download/pixiv';
    const getQuotaInitURL = () => serverBase + '/api/quota/init';
    const getArchiveStatusBase = () => serverBase + '/api/archive/status';
    const getArchiveDownloadBase = () => serverBase + '/api/archive/download';
    const getDownloadedCheckBase = () => serverBase + '/api/downloaded';

    let userUUID = GM_getValue(KEY_USER_UUID, null);
    let quotaInfo = { enabled: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0 };

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
            'switcher.zh-CN': '简体中文',
            'switcher.en-US': 'English',
            'common.dialog.unauthorized': 'Backend requires login. Opening login page...',
            'common.dialog.connect-notice': 'Pixiv download script first-run hint\n\nIf you use an external server instead of localhost, replace this userscript header line:\n  // @connect      YOUR_SERVER_HOST\nwith your real server IP or domain, for example:\n  // @connect      192.168.1.100\n\nPath: Tampermonkey dashboard -> target script -> Edit -> Save\n\nOr use the web UI directly:\n{serverBase}/login.html\n\n(This hint is shown only once)',
            'common.option.verify-history-files.tooltip': 'Checks whether the recorded directory exists, whether it is empty, and whether it contains image files. Invalid records will be downloaded again.',
            'common.archive.download-link': 'Download Archive',
            'common.archive.expired': 'Download link expired',
            'common.archive.preparing': 'Preparing downloaded files, please wait...',
            'common.archive.ready': 'Archive is ready:',
            'common.archive.empty': 'No files available for packaging',
            'common.archive.validity': 'Valid for: {time}',
            'common.quota.exceeded': 'Download limit reached',
            'common.quota.summary': 'Quota: {used}/{max}',
            'single.title': 'Pixiv Downloader (Java Backend)',
            'single.status.ready': '⬇️ This artwork can be downloaded',
            'single.button.download': '📥 Download via Backend',
            'single.option.bookmark': 'Auto-bookmark after download',
            'single.option.skip-history': 'Skip download history',
            'single.option.verify-history-files': 'Verify saved directory',
            'single.artwork-id': 'Artwork ID: {id}',
            'single.backend.checking': 'Checking backend status...',
            'single.backend.available': '✅ Backend available',
            'single.backend.unavailable': '❌ Backend unavailable',
            'single.info': 'Uses the Java backend and keeps the full folder structure',
            'single.menu.download': 'Download current artwork via backend',
            'single.menu.server': '⚙️ Set backend server URL',
            'single.prompt.server': 'Enter backend server URL (without trailing slash):',
            'single.alert.server-updated': 'Server URL updated: {serverBase}',
            'single.alert.no-artwork-id': 'Cannot detect artwork ID',
            'single.alert.backend-not-running': 'Backend is not running.\nPlease make sure the Java Spring server is available at {serverBase}',
            'single.alert.history-skipped': 'Artwork {artworkId} already exists in download history and was skipped.',
            'single.alert.start-download': 'Starting download for artwork {artworkId}...',
            'single.alert.no-images': 'No images found',
            'single.alert.download-submitted': 'Download task submitted to backend.\n{typeHint}\n{message}',
            'single.alert.download-failed': 'Download failed: {message}',
            'single.type.ugoira': 'Animated illustration (will be converted to WebP)',
            'single.type.images': 'Images: {count}',
            'single.archive.download-limit': 'Download limit reached'
        },
        'zh-CN': {
            'switcher.label': '语言',
            'switcher.zh-CN': '简体中文',
            'switcher.en-US': 'English',
            'common.dialog.unauthorized': '后端服务需要登录验证，即将为您打开登录页面...',
            'common.dialog.connect-notice': 'Pixiv 下载脚本初始化提示\n\n如果您使用外部服务器（非 localhost），需将脚本头部的：\n  // @connect      YOUR_SERVER_HOST\n替换为实际的服务器 IP 或域名，例如：\n  // @connect      192.168.1.100\n\n修改路径：Tampermonkey 管理面板 -> 对应脚本 -> 编辑 -> 保存\n\n或者直接通过网页端下载作品（无需脚本）：\n{serverBase}/login.html\n\n（此提示只显示一次）',
            'common.option.verify-history-files.tooltip': '通过检查记录的目录是否存在、文件夹是否为空、文件夹中的文件是否包含图片来判断是否有效，如果无效则会重新下载',
            'common.archive.download-link': '下载压缩包',
            'common.archive.expired': '下载链接已过期',
            'common.archive.preparing': '正在打包已下载文件，请稍候...',
            'common.archive.ready': '压缩包已就绪：',
            'common.archive.empty': '暂无可打包文件',
            'common.archive.validity': '有效期：{time}',
            'common.quota.exceeded': '已达到下载限额',
            'common.quota.summary': '配额：{used}/{max}',
            'single.title': 'Pixiv下载器 (Java后端)',
            'single.status.ready': '⬇️ 可下载此作品',
            'single.button.download': '📥 通过后端下载',
            'single.option.bookmark': '下载后自动收藏',
            'single.option.skip-history': '跳过历史下载',
            'single.option.verify-history-files': '实际目录检测',
            'single.artwork-id': '作品ID: {id}',
            'single.backend.checking': '检查后端状态...',
            'single.backend.available': '✅ 后端服务可用',
            'single.backend.unavailable': '❌ 后端服务未启动',
            'single.info': '使用Java后端服务下载，支持完整文件夹结构',
            'single.menu.download': '通过后端下载当前作品',
            'single.menu.server': '⚙️ 设置服务器地址',
            'single.prompt.server': '请输入后端服务器地址（不含末尾斜杠）:',
            'single.alert.server-updated': '服务器地址已更新为: {serverBase}',
            'single.alert.no-artwork-id': '无法获取作品ID',
            'single.alert.backend-not-running': '后端下载服务未启动！\n请确保Java Spring程序正在 {serverBase} 运行',
            'single.alert.history-skipped': '作品 {artworkId} 已存在于下载历史中，已跳过本次下载。',
            'single.alert.start-download': '开始下载作品 {artworkId} 的图片...',
            'single.alert.no-images': '未找到图片',
            'single.alert.download-submitted': '下载任务已提交到后端处理！\n{typeHint}\n{message}',
            'single.alert.download-failed': '下载失败: {message}',
            'single.type.ugoira': '动图（将合成为WebP）',
            'single.type.images': '图片数量: {count}张',
            'single.archive.download-limit': '已达到下载限额'
        }
    });

    const t = (key, fallback, args) => PixivUserscriptI18n.t(key, fallback, args);

    function buildLangSwitcher() {
        const wrapper = document.createElement('span');
        wrapper.style.cssText = 'display:inline-flex;align-items:center;gap:6px;flex-shrink:0;';
        const select = document.createElement('select');
        select.title = t('switcher.label', 'Language');
        select.style.cssText = 'padding:2px 4px;border:1px solid #ccc;border-radius:4px;background:#fff;color:#333;font-size:11px;';
        PixivUserscriptI18n.listSupported().forEach(lang => {
            const option = document.createElement('option');
            option.value = lang;
            option.textContent = t('switcher.' + lang, lang);
            option.selected = lang === PixivUserscriptI18n.getLang();
            select.appendChild(option);
        });
        select.addEventListener('change', () => PixivUserscriptI18n.setLang(select.value));
        wrapper.appendChild(select);
        return wrapper;
    }

    // 首次启动提示（跨脚本只显示一次）
    function checkExternalServerNotice() {
        // 兼容旧的 GM_setValue 标记：历史用户已看过则继续跳过
        if (GM_getValue('pixiv_connect_notice_shown', false)) return;
        PromptGuard.once('connect-notice', { persist: true }, () => {
            GM_setValue('pixiv_connect_notice_shown', true);
            alert(t('common.dialog.connect-notice', null, { serverBase: serverBase }));
        });
    }

    // 检查登录状态（solo 模式未登录返回 401）
    function checkLoginStatus() {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: serverBase + '/api/download/status/0',
                timeout: 5000,
                onload: (res) => {
                    if (res.status === 401) { handleUnauthorized(); resolve(false); }
                    else resolve(true);
                },
                onerror: () => resolve(true),
                ontimeout: () => resolve(true)
            });
        });
    }

    // 处理 solo 模式未登录（401）：跨脚本去重交给 PromptGuard
    function handleUnauthorized() {
        PromptGuard.once('unauthorized', { ttlMs: 60000 }, () => {
            alert(t('common.dialog.unauthorized', '后端服务需要登录验证，即将为您打开登录页面...'));
            window.open(serverBase + '/login.html', '_blank');
        });
    }

    // 等待页面加载完成
    function waitForPageLoad() {
        return new Promise(resolve => {
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', resolve);
            } else {
                resolve();
            }
        });
    }

    // 获取作品ID
    function getArtworkId() {
        const url = window.location.href;
        const match = url.match(/artworks\/(\d+)/);
        return match ? match[1] : null;
    }

    // 获取图片URL
    async function getImageUrls(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}/pages`,
                headers: {
                    'Referer': 'https://www.pixiv.net/'
                },
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data.error) {
                            reject(new Error(data.message));
                            return;
                        }

                        const urls = data.body.map(page => page.urls.original);
                        resolve(urls);
                    } catch (e) {
                        reject(e);
                    }
                },
                onerror: function (error) {
                    reject(error);
                }
            });
        });
    }

    // 从 Pixiv meta 中提取 tags，返回 [{name, translatedName}] 数组
    function extractTagsFromMeta(meta) {
        const arr = meta && meta.tags && Array.isArray(meta.tags.tags) ? meta.tags.tags : [];
        return arr
            .filter(t => t && t.tag)
            .map(t => ({
                name: String(t.tag),
                translatedName: (t.translation && t.translation.en) ? String(t.translation.en) : null
            }));
    }

    // 单次脚本生命周期的系列元数据缓存：相同 seriesId 在同一页面里只查后端代理一次。
    const seriesEnrichmentCache = new Map();
    function fetchSeriesEnrichment(seriesId) {
        const sid = Number(seriesId);
        if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
        if (seriesEnrichmentCache.has(sid)) return seriesEnrichmentCache.get(sid);
        const p = new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `${serverBase}/api/pixiv/series/${sid}?page=1`,
                headers: {'X-Pixiv-Cookie': document.cookie || ''},
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        const meta = data && data.series ? data.series : null;
                        resolve(meta ? {caption: meta.caption || '', coverUrl: meta.coverUrl || ''} : null);
                    } catch (_) { resolve(null); }
                },
                onerror: () => resolve(null)
            });
        });
        seriesEnrichmentCache.set(sid, p);
        return p;
    }

    // 获取作品元数据
    async function getArtworkMeta(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}`,
                headers: { 'Referer': 'https://www.pixiv.net/' },
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data.error) reject(new Error(data.message));
                        else resolve(data.body);
                    } catch (e) { reject(e); }
                },
                onerror: reject
            });
        });
    }

    // 获取动图元数据
    async function getUgoiraMeta(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}/ugoira_meta`,
                headers: { 'Referer': 'https://www.pixiv.net/' },
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data.error) reject(new Error(data.message));
                        else resolve(data.body);
                    } catch (e) { reject(e); }
                },
                onerror: reject
            });
        });
    }

    // 初始化配额
    function initQuota() {
        const headers = { 'Content-Type': 'application/json' };
        if (userUUID) headers['X-User-UUID'] = userUUID;
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url: getQuotaInitURL(),
                headers,
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        if (data.uuid) {
                            userUUID = data.uuid;
                            GM_setValue(KEY_USER_UUID, userUUID);
                        }
                        resolve(data);
                    } catch { resolve({}); }
                },
                onerror: () => resolve({}),
                ontimeout: () => resolve({})
            });
        });
    }

    // 发送下载请求到后端
    async function sendDownloadRequest(artworkId, title, imageUrls, other) {
        return new Promise((resolve, reject) => {
            const requestData = {
                artworkId: parseInt(artworkId),
                title: title,
                imageUrls: imageUrls,
                referer: 'https://www.pixiv.net/',
                cookie: document.cookie,
                other: other || {}
            };
            const headers = { 'Content-Type': 'application/json' };
            if (userUUID) headers['X-User-UUID'] = userUUID;

            GM_xmlhttpRequest({
                method: 'POST',
                url: getBackendURL(),
                headers,
                data: JSON.stringify(requestData),
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (response.status === 401) {
                            handleUnauthorized();
                            reject(new Error('需要登录'));
                        } else if (response.status === 429 && data.quotaExceeded) {
                            const err = new Error('quota_exceeded');
                            err.quotaData = data;
                            reject(err);
                        } else if (response.status === 200 && data.success) {
                            resolve(data);
                        } else {
                            reject(new Error(data.message || '下载请求失败'));
                        }
                    } catch (e) {
                        reject(e);
                    }
                },
                onerror: function (error) {
                    reject(error);
                }
            });
        });
    }

    // 检查后端服务状态
    async function checkBackendStatus() {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: serverBase + '/api/download/status',
                timeout: 3000,
                onload: function (response) {
                    resolve(response.status === 200);
                },
                onerror: function () {
                    resolve(false);
                },
                ontimeout: function () {
                    resolve(false);
                }
            });
        });
    }

    // 下载图片
    async function checkDownloaded(artworkId, verifyFiles = false) {
        return new Promise((resolve) => {
            const query = verifyFiles ? '?verifyFiles=true' : '';
            GM_xmlhttpRequest({
                method: 'GET',
                url: `${getDownloadedCheckBase()}/${artworkId}${query}`,
                timeout: 5000,
                onload: function (response) {
                    if (response.status === 401) {
                        handleUnauthorized();
                        resolve(false);
                        return;
                    }
                    if (response.status !== 200) {
                        resolve(false);
                        return;
                    }
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(!!data.artworkId);
                    } catch (e) {
                        resolve(false);
                    }
                },
                onerror: function () {
                    resolve(false);
                },
                ontimeout: function () {
                    resolve(false);
                }
            });
        });
    }

    async function downloadImages() {
        const artworkId = getArtworkId();
        if (!artworkId) {
            alert(t('single.alert.no-artwork-id', '无法获取作品ID'));
            return;
        }

        // 检查后端服务是否可用
        const isBackendAvailable = await checkBackendStatus();
        if (!isBackendAvailable) {
            alert(t('single.alert.backend-not-running', null, { serverBase: serverBase }));
            return;
        }

        // 检查是否已下载
        const skipHistory = GM_getValue(KEY_SKIP_HISTORY, false);
        const verifyHistoryFiles = skipHistory && GM_getValue(KEY_VERIFY_HISTORY_FILES, false);
        if (skipHistory) {
            const alreadyDownloaded = await checkDownloaded(artworkId, verifyHistoryFiles);
            if (alreadyDownloaded) {
                alert(t('single.alert.history-skipped', null, { artworkId: artworkId }));
                return;
            }
        }

        try {
            // 显示下载开始提示
            alert(t('single.alert.start-download', null, { artworkId: artworkId }));

            // 获取作品元数据（用于标题和类型检测）
            const meta = await getArtworkMeta(artworkId);
            const title = (meta && meta.illustTitle) ? meta.illustTitle : `Artwork ${artworkId}`;
            const parsedAuthorId = Number.parseInt(String(meta?.userId || ''), 10);
            const authorId = Number.isFinite(parsedAuthorId) ? parsedAuthorId : null;
            const authorName = meta?.userName || null;
            const xRestrict = Number(meta?.xRestrict ?? 0);
            const isAi = Number(meta?.aiType ?? 0) >= 2;
            const description = meta?.description || '';
            const tags = extractTagsFromMeta(meta);

            let imageUrls;
            const bookmark = GM_getValue(KEY_BOOKMARK_AFTER_DL, false);
            // 系列导航：仅在 Pixiv 标注该作品属于某个漫画系列时附带。
            // 系列简介/封面通过后端代理缓存查询，失败时退回到 observe() 的轻量 upsert。
            const nav = meta && meta.seriesNavData;
            const seriesId = nav && Number(nav.seriesId) > 0 ? Number(nav.seriesId) : null;
            const seriesEnrich = seriesId ? await fetchSeriesEnrichment(seriesId) : null;
            const seriesFields = seriesId ? {
                seriesId,
                seriesOrder: Number(nav.order || 0),
                seriesTitle: nav.title || '',
                seriesDescription: seriesEnrich && seriesEnrich.caption ? seriesEnrich.caption : null,
                seriesCoverUrl: seriesEnrich && seriesEnrich.coverUrl ? seriesEnrich.coverUrl : null
            } : {};
            let other = { bookmark, authorId, authorName, xRestrict, isAi, description, tags, ...seriesFields };

            if (meta && meta.illustType === 2) {
                // 动图作品：获取ugoira元数据，下载ZIP并在后端合成WebP
                const ugoiraMeta = await getUgoiraMeta(artworkId);
                const zipSrc = ugoiraMeta.originalSrc || ugoiraMeta.src;
                imageUrls = [zipSrc];
                other = {
                    isUgoira: true,
                    ugoiraZipUrl: zipSrc,
                    ugoiraDelays: ugoiraMeta.frames.map(f => f.delay),
                    bookmark,
                    authorId,
                    authorName,
                    xRestrict,
                    isAi,
                    description,
                    tags,
                    ...seriesFields
                };
            } else if (meta && meta.pageCount === 1 && meta.urls && meta.urls.original) {
                // 单页插画：meta 已携带 original URL，无需再调用 /pages
                imageUrls = [meta.urls.original];
            } else {
                imageUrls = await getImageUrls(artworkId);
            }

            if (imageUrls.length === 0) {
                alert(t('single.alert.no-images', '未找到图片'));
                return;
            }

            // 发送下载请求到后端
            const response = await sendDownloadRequest(artworkId, title, imageUrls, other);

            // 刷新配额显示
            if (quotaInfo.enabled) {
                quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
            }
            updateDownloadUI();

            const typeHint = other.isUgoira
                ? t('single.type.ugoira', '动图（将合成为WebP）')
                : t('single.type.images', '图片数量: {count}张', { count: imageUrls.length });
            alert(t('single.alert.download-submitted', null, {
                typeHint: typeHint,
                message: response.message
            }));

        } catch (error) {
            if (error.message === 'quota_exceeded' && error.quotaData) {
                console.warn('已达到下载限额');
                showQuotaExceededUI(error.quotaData);
            } else {
                console.error('下载失败:', error);
                alert(t('single.alert.download-failed', null, { message: error.message }));
            }
        }
    }

    // 创建下载UI
    function createDownloadUI() {
        // 移除已存在的UI
        const existingUI = document.getElementById('pixiv-downloader-ui');
        if (existingUI) {
            existingUI.remove();
        }

        const artworkId = getArtworkId();
        if (!artworkId) return; // 如果不是作品页面，不显示UI

        const skipHistoryEnabled = GM_getValue(KEY_SKIP_HISTORY, false);
        const verifyHistoryFilesEnabled = GM_getValue(KEY_VERIFY_HISTORY_FILES, false);

        const container = document.createElement('div');
        container.id = 'pixiv-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 100px;
            right: 20px;
            z-index: 10000;
            background: white;
            border: 2px solid #0096fa;
            border-radius: 8px;
            padding: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.2);
            min-width: 260px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        `;

        const titleRow = document.createElement('div');
        titleRow.style.cssText = `
            display: flex;
            align-items: center;
            gap: 8px;
            margin-bottom: 5px;
            border-bottom: 1px solid #eee;
            padding-bottom: 5px;
        `;

        const titleDiv = document.createElement('div');
        titleDiv.textContent = t('single.title', 'Pixiv下载器 (Java后端)');
        titleDiv.style.cssText = `
            font-weight: bold;
            color: #333;
            text-align: center;
            font-size: 16px;
            flex: 1;
        `;
        titleRow.appendChild(titleDiv);
        titleRow.appendChild(buildLangSwitcher());

        const statusDiv = document.createElement('div');
        statusDiv.textContent = t('single.status.ready', '⬇️ 可下载此作品');
        statusDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 8px;
            color: #0096fa;
            text-align: center;
            font-size: 14px;
        `;

        const button = document.createElement('button');
        button.textContent = t('single.button.download', '📥 通过后端下载');
        button.style.cssText = `
            width: 100%;
            background: #0096fa;
            color: white;
            border: none;
            padding: 8px 12px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
            font-weight: bold;
            transition: background 0.2s;
            margin-bottom: 5px;
        `;
        button.addEventListener('mouseenter', () => {
            button.style.background = '#007acc';
        });
        button.addEventListener('mouseleave', () => {
            button.style.background = '#0096fa';
        });
        button.addEventListener('click', downloadImages);

        // 下载后收藏开关
        const bookmarkRow = document.createElement('div');
        bookmarkRow.style.cssText = 'display:flex;align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;';
        const bookmarkChk = document.createElement('input');
        bookmarkChk.type = 'checkbox';
        bookmarkChk.id = 'pixiv-dl-bookmark';
        bookmarkChk.checked = GM_getValue(KEY_BOOKMARK_AFTER_DL, false);
        bookmarkChk.addEventListener('change', () => GM_setValue(KEY_BOOKMARK_AFTER_DL, bookmarkChk.checked));
        const bookmarkLabel = document.createElement('label');
        bookmarkLabel.htmlFor = 'pixiv-dl-bookmark';
        bookmarkLabel.textContent = t('single.option.bookmark', '下载后自动收藏');
        bookmarkLabel.style.cursor = 'pointer';
        bookmarkRow.appendChild(bookmarkChk);
        bookmarkRow.appendChild(bookmarkLabel);

        const skipHistoryRow = document.createElement('div');
        skipHistoryRow.style.cssText = 'display:flex;align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;';
        const skipHistoryChk = document.createElement('input');
        skipHistoryChk.type = 'checkbox';
        skipHistoryChk.id = 'pixiv-dl-skip-history';
        skipHistoryChk.checked = skipHistoryEnabled;
        const skipHistoryLabel = document.createElement('label');
        skipHistoryLabel.htmlFor = 'pixiv-dl-skip-history';
        skipHistoryLabel.textContent = t('single.option.skip-history', '跳过历史下载');
        skipHistoryLabel.style.cursor = 'pointer';
        skipHistoryRow.appendChild(skipHistoryChk);
        skipHistoryRow.appendChild(skipHistoryLabel);

        const verifyHistoryFilesRow = document.createElement('div');
        verifyHistoryFilesRow.style.cssText = `display:${skipHistoryEnabled ? 'flex' : 'none'};align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;`;
        const verifyHistoryFilesChk = document.createElement('input');
        verifyHistoryFilesChk.type = 'checkbox';
        verifyHistoryFilesChk.id = 'pixiv-dl-verify-history-files';
        verifyHistoryFilesChk.checked = verifyHistoryFilesEnabled;
        const verifyHistoryFilesLabel = document.createElement('label');
        verifyHistoryFilesLabel.htmlFor = 'pixiv-dl-verify-history-files';
        verifyHistoryFilesLabel.textContent = t('single.option.verify-history-files', '实际目录检测');
        verifyHistoryFilesLabel.style.cursor = 'pointer';
        const verifyHistoryFilesHelp = document.createElement('span');
        verifyHistoryFilesHelp.textContent = '?';
        verifyHistoryFilesHelp.title = t('common.option.verify-history-files.tooltip', VERIFY_HISTORY_FILES_TOOLTIP);
        verifyHistoryFilesHelp.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;width:14px;height:14px;border:1px solid #999;border-radius:50%;color:#666;font-size:10px;font-weight:700;line-height:1;cursor:help;user-select:none;flex-shrink:0;';
        verifyHistoryFilesRow.appendChild(verifyHistoryFilesChk);
        verifyHistoryFilesRow.appendChild(verifyHistoryFilesLabel);
        verifyHistoryFilesRow.appendChild(verifyHistoryFilesHelp);

        skipHistoryChk.addEventListener('change', () => {
            GM_setValue(KEY_SKIP_HISTORY, skipHistoryChk.checked);
            verifyHistoryFilesRow.style.display = skipHistoryChk.checked ? 'flex' : 'none';
        });
        verifyHistoryFilesChk.addEventListener('change', () => {
            GM_setValue(KEY_VERIFY_HISTORY_FILES, verifyHistoryFilesChk.checked);
        });

        const artworkIdDiv = document.createElement('div');
        artworkIdDiv.textContent = t('single.artwork-id', '作品ID: {id}', { id: artworkId });
        artworkIdDiv.style.cssText = `
            font-size: 12px;
            color: #666;
            text-align: center;
            margin-bottom: 5px;
        `;

        const backendStatusDiv = document.createElement('div');
        backendStatusDiv.id = 'backend-status';
        backendStatusDiv.textContent = t('single.backend.checking', '检查后端状态...');
        backendStatusDiv.style.cssText = `
            font-size: 11px;
            color: #888;
            text-align: center;
            margin-bottom: 5px;
        `;

        const infoDiv = document.createElement('div');
        infoDiv.textContent = t('single.info', '使用Java后端服务下载，支持完整文件夹结构');
        infoDiv.style.cssText = `
            font-size: 10px;
            color: #999;
            text-align: center;
        `;

        // 配额栏
        const quotaBarDiv = document.createElement('div');
        quotaBarDiv.id = 'pixiv-single-quota-bar';
        quotaBarDiv.style.cssText = 'display:none;margin-bottom:6px;padding:5px 6px;background:#f8f9fa;border-radius:4px;font-size:11px;color:#555;';

        // 压缩包下载卡片
        const archiveCardDiv = document.createElement('div');
        archiveCardDiv.id = 'pixiv-single-archive-card';
        archiveCardDiv.style.cssText = 'display:none;margin-bottom:8px;padding:8px;background:#fff8e1;border:2px solid #ffc107;border-radius:4px;font-size:11px;';

        container.appendChild(titleRow);
        container.appendChild(statusDiv);
        container.appendChild(button);
        container.appendChild(bookmarkRow);
        container.appendChild(skipHistoryRow);
        container.appendChild(verifyHistoryFilesRow);
        container.appendChild(artworkIdDiv);
        container.appendChild(backendStatusDiv);
        container.appendChild(infoDiv);
        container.appendChild(quotaBarDiv);
        container.appendChild(archiveCardDiv);
        document.body.appendChild(container);

        // 渲染配额栏
        if (quotaInfo.enabled) renderSingleQuotaBar();

        // 恢复已有压缩包卡片（如果有）
        if (window._pixivSingleArchiveState) {
            const s = window._pixivSingleArchiveState;
            renderSingleArchiveCard(s.token, s.expireSec, s.ready);
        }

        // 检查后端状态
        checkBackendStatus().then(available => {
            const statusDiv = document.getElementById('backend-status');
            if (statusDiv) {
                statusDiv.textContent = available
                    ? t('single.backend.available', '✅ 后端服务可用')
                    : t('single.backend.unavailable', '❌ 后端服务未启动');
                statusDiv.style.color = available ? '#28a745' : '#dc3545';
            }
        });
    }

    // 渲染配额栏
    function renderSingleQuotaBar() {
        const bar = document.getElementById('pixiv-single-quota-bar');
        if (!bar || !quotaInfo.enabled) return;
        const pct = Math.min(100, Math.round(quotaInfo.artworksUsed / quotaInfo.maxArtworks * 100));
        const color = pct >= 90 ? '#dc3545' : pct >= 70 ? '#ffc107' : '#28a745';
        bar.style.display = 'block';
        bar.innerHTML = `<div style="display:flex;align-items:center;gap:5px;">
          <span style="white-space:nowrap;">${t('common.quota.summary', '配额：{used}/{max}', { used: quotaInfo.artworksUsed, max: quotaInfo.maxArtworks })}</span>
          <div style="flex:1;height:4px;background:#e0e0e0;border-radius:2px;overflow:hidden;">
            <div style="height:100%;width:${pct}%;background:${color};border-radius:2px;"></div>
          </div>
          <span style="color:#888;">${pct}%</span>
        </div>`;
    }

    function renderSingleArchiveCard(token, expireSec, ready) {
        window._pixivSingleArchiveState = { token, expireSec, ready };
        const card = document.getElementById('pixiv-single-archive-card');
        if (!card) return;
        card.style.display = 'block';
        card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:4px;">${t('single.archive.download-limit', '已达到下载限额')}</div>
          <div id="pixiv-single-ac-status" style="color:#666;">${ready ? t('common.archive.ready', '压缩包已就绪：') : t('common.archive.preparing', '正在打包已下载文件，请稍候...')}</div>
          <div id="pixiv-single-ac-dl" style="display:${ready ? 'block' : 'none'};margin-top:4px;"></div>
          <div id="pixiv-single-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;
        if (ready) {
            activateSingleArchiveDl(token, expireSec);
        } else {
            pollSingleArchive(token, expireSec);
        }
    }

    function pollSingleArchive(token, expireSec) {
        const timer = setInterval(() => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `${getArchiveStatusBase()}/${token}`,
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        if (data.status === 'ready') {
                            clearInterval(timer);
                            activateSingleArchiveDl(token, data.expireSeconds || expireSec);
                        } else if (data.status === 'expired') {
                            clearInterval(timer);
                            const expired = document.getElementById('pixiv-single-ac-expired');
                            const status = document.getElementById('pixiv-single-ac-status');
                            if (expired) expired.style.display = 'block';
                            if (status) status.textContent = '';
                        } else if (data.status === 'empty') {
                            clearInterval(timer);
                            const status = document.getElementById('pixiv-single-ac-status');
                            if (status) status.textContent = t('common.archive.empty', '暂无可打包文件');
                        }
                    } catch {}
                },
                onerror: () => {}
            });
        }, 2000);
    }

    function activateSingleArchiveDl(token, expireSec) {
        const statusEl = document.getElementById('pixiv-single-ac-status');
        const dlEl = document.getElementById('pixiv-single-ac-dl');
        if (statusEl) statusEl.textContent = t('common.archive.ready', '压缩包已就绪：');
        if (dlEl) {
            dlEl.style.display = 'block';
            const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
            dlEl.innerHTML = `<a href="${getArchiveDownloadBase()}/${token}" download="${filename}"
              style="display:inline-block;padding:4px 10px;background:#28a745;color:white;
                     border-radius:4px;text-decoration:none;font-size:11px;font-weight:bold;">
              ${t('common.archive.download-link', '下载压缩包')}
            </a>
            <span id="pixiv-single-ac-countdown" style="font-size:10px;color:#888;margin-left:6px;"></span>`;
            let remaining = Math.max(0, parseInt(expireSec));
            const fmtSec = (s) => {
                s = Math.max(0, Math.round(s));
                const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
                if (h > 0) return h + 'h ' + String(m).padStart(2,'0') + 'm ' + String(sec).padStart(2,'0') + 's';
                return String(m).padStart(2,'0') + ':' + String(sec).padStart(2,'0');
            };
            const el = () => document.getElementById('pixiv-single-ac-countdown');
            if (el()) el().textContent = t('common.archive.validity', '有效期：{time}', { time: fmtSec(remaining) });
            const timer = setInterval(() => {
                remaining--;
                if (remaining <= 0) {
                    clearInterval(timer);
                    const expired = document.getElementById('pixiv-single-ac-expired');
                    if (dlEl) dlEl.style.display = 'none';
                    if (expired) expired.style.display = 'block';
                } else {
                    if (el()) el().textContent = t('common.archive.validity', '有效期：{time}', { time: fmtSec(remaining) });
                }
            }, 1000);
        }
    }

    function showQuotaExceededUI(data) {
        window._pixivSingleArchiveState = null;
        const card = document.getElementById('pixiv-single-archive-card');
        if (card) {
            renderSingleArchiveCard(data.archiveToken, data.archiveExpireSeconds || 3600, false);
        } else {
            // UI 还未创建，暂存状态，等下次 createDownloadUI 时恢复
            window._pixivSingleArchiveState = { token: data.archiveToken, expireSec: data.archiveExpireSeconds || 3600, ready: false };
            updateDownloadUI();
        }
    }

    // 更新下载UI状态
    function updateDownloadUI() {
        createDownloadUI();
    }

    // 初始化下载UI
    async function initDownloadUI() {
        try {
            // 等待页面基本加载
            await waitForPageLoad();

            // 等待Pixiv主要内容区域加载
            setTimeout(createDownloadUI, 1000);

            // 监听URL变化
            let lastUrl = location.href;
            new MutationObserver(() => {
                const url = location.href;
                if (url !== lastUrl) {
                    lastUrl = url;
                    setTimeout(createDownloadUI, 500);
                }
            }).observe(document, {subtree: true, childList: true});

        } catch (error) {
            console.log('初始化下载UI失败:', error);
            setTimeout(createDownloadUI, 3000);
        }
    }

    // 注册菜单命令
    GM_registerMenuCommand(t('single.menu.download', '通过后端下载当前作品'), downloadImages);
    GM_registerMenuCommand(t('single.menu.server', '⚙️ 设置服务器地址'), () => {
        const input = prompt(t('single.prompt.server', '请输入后端服务器地址（不含末尾斜杠）:'), serverBase);
        if (input !== null) {
            serverBase = input.trim().replace(/\/$/, '') || 'http://localhost:6999';
            GM_setValue(KEY_SERVER_URL, serverBase);
            alert(t('single.alert.server-updated', null, { serverBase: serverBase }));
        }
    });

    PixivUserscriptI18n.onChange(() => {
        updateDownloadUI();
    });
    PixivUserscriptI18n.enrichFromBackend(serverBase);

    // 添加快捷键支持 (Ctrl+Shift+J)
    document.addEventListener('keydown', function (e) {
        if (e.ctrlKey && e.shiftKey && e.key === 'J') {
            e.preventDefault();
            downloadImages();
        }
    });

    // 首次启动提示
    checkExternalServerNotice();

    // 第一步：检查登录状态，通过后再初始化
    (async () => {
        const authed = await checkLoginStatus();
        if (!authed) return;

        // 初始化配额信息
        initQuota().then(data => {
            if (data && data.enabled) {
                quotaInfo = {
                    enabled: true,
                    artworksUsed: data.artworksUsed || 0,
                    maxArtworks: data.maxArtworks || 50,
                    resetSeconds: data.resetSeconds || 0
                };
                // 恢复已有压缩包
                if (data.archive && data.archive.token) {
                    window._pixivSingleArchiveState = {
                        token: data.archive.token,
                        expireSec: data.archive.expireSeconds || 3600,
                        ready: data.archive.status === 'ready'
                    };
                }
                renderSingleQuotaBar();
            }
        });

        // 启动初始化
        initDownloadUI();
    })();
})();
