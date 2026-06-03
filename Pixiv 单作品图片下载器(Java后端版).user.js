// ==UserScript==
// @name         Pixiv作品图片下载器（Java后端版）
// @namespace    http://tampermonkey.net/
// @version      2.1.0
// @updateURL    https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/Pixiv%20%E5%8D%95%E4%BD%9C%E5%93%81%E5%9B%BE%E7%89%87%E4%B8%8B%E8%BD%BD%E5%99%A8(Java%E5%90%8E%E7%AB%AF%E7%89%88).user.js
// @downloadURL  https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/Pixiv%20%E5%8D%95%E4%BD%9C%E5%93%81%E5%9B%BE%E7%89%87%E4%B8%8B%E8%BD%BD%E5%99%A8(Java%E5%90%8E%E7%AB%AF%E7%89%88).user.js
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
    const KEY_NOVEL_FORMAT = 'pixiv_single_novel_format';
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
        const readLS = () => {
            try {
                return localStorage.getItem(LS_KEY) === '1';
            } catch (e) {
                return false;
            }
        };
        const notify = () => listeners.forEach(fn => {
            try {
                fn(collapsed);
            } catch (e) {
                console.error('[PixivPanelState]', e);
            }
        });

        function ensureInit() {
            if (collapsed !== null) return;
            collapsed = readLS();
            try {
                if (typeof BroadcastChannel !== 'undefined') {
                    bc = new BroadcastChannel(BC_NAME);
                    bc.addEventListener('message', ev => {
                        if (ev && ev.data && ev.data.type === 'panel-collapsed') {
                            const v = ev.data.value === true;
                            if (v !== collapsed) {
                                collapsed = v;
                                notify();
                            }
                        }
                    });
                }
            } catch (e) {
            }
            try {
                window.addEventListener('storage', ev => {
                    if (ev.key !== LS_KEY) return;
                    const v = ev.newValue === '1';
                    if (v !== collapsed) {
                        collapsed = v;
                        notify();
                    }
                });
            } catch (e) {
            }
            try {
                setInterval(() => {
                    const v = readLS();
                    if (v !== collapsed) {
                        collapsed = v;
                        notify();
                    }
                }, 1000);
            } catch (e) {
            }
        }

        function get() {
            ensureInit();
            return collapsed;
        }

        function set(v) {
            ensureInit();
            v = v === true;
            if (v === collapsed) return;
            collapsed = v;
            try {
                localStorage.setItem(LS_KEY, v ? '1' : '0');
            } catch (e) {
            }
            if (bc) {
                try {
                    bc.postMessage({type: 'panel-collapsed', value: v});
                } catch (e) {
                }
            }
            notify();
        }

        function onChange(fn) {
            ensureInit();
            listeners.add(fn);
            return () => listeners.delete(fn);
        }

        const api = {get, set, onChange};
        try {
            if (typeof window !== 'undefined') window[SHARED_KEY] = api;
        } catch (e) {
        }
        return api;
    })();

    function loadPanelCollapsed() {
        return PixivPanelState.get();
    }

    function savePanelCollapsed(collapsed) {
        PixivPanelState.set(collapsed);
    }

    let panelCollapsed = loadPanelCollapsed();

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
            'single.action.collapse': 'Collapse',
            'single.fab.title': 'Pixiv Downloader (Java Backend)',
            'single.menu.open': 'Open Pixiv single-artwork downloader panel',
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
            'single.type.novel': 'Novel ({format})',
            'single.archive.download-limit': 'Download limit reached',
            'single.novel.title': 'Pixiv Novel Downloader (Java Backend)',
            'single.novel.status.ready': '⬇️ This novel can be downloaded',
            'single.novel.button.download': '📥 Download novel via Backend',
            'single.novel.id': 'Novel ID: {id}',
            'single.novel.format-label': 'Novel format:',
            'single.novel.format-txt': 'Plain text (TXT)',
            'single.novel.format-html': 'Web page (HTML)',
            'single.novel.format-epub': 'eBook (EPUB)',
            'single.novel.menu.download': 'Download current novel via backend',
            'single.alert.no-novel-id': 'Cannot detect novel ID',
            'single.alert.novel-history-skipped': 'Novel {novelId} already exists in download history and was skipped.',
            'single.alert.novel-start-download': 'Starting download for novel {novelId}...'
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
            'single.action.collapse': '收起',
            'single.fab.title': 'Pixiv 单作品下载器 (Java后端)',
            'single.menu.open': '打开 Pixiv 单作品下载器面板',
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
            'single.type.novel': '小说（{format}）',
            'single.archive.download-limit': '已达到下载限额',
            'single.novel.title': 'Pixiv小说下载器 (Java后端)',
            'single.novel.status.ready': '⬇️ 可下载此小说',
            'single.novel.button.download': '📥 通过后端下载小说',
            'single.novel.id': '小说ID: {id}',
            'single.novel.format-label': '小说格式:',
            'single.novel.format-txt': '纯文本（TXT）',
            'single.novel.format-html': '网页（HTML）',
            'single.novel.format-epub': '电子书（EPUB）',
            'single.novel.menu.download': '通过后端下载当前小说',
            'single.alert.no-novel-id': '无法获取小说ID',
            'single.alert.novel-history-skipped': '小说 {novelId} 已存在于下载历史中，已跳过本次下载。',
            'single.alert.novel-start-download': '开始下载小说 {novelId} ...'
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

    // 获取小说ID（/novel/show.php?id=N）
    function getNovelId() {
        const m = window.location.href.match(/\/novel\/show\.php\?[^#]*\bid=(\d+)/);
        return m ? m[1] : null;
    }

    // 当前页面目标：插画单作品或小说单作品
    function getPageTarget() {
        const novelId = getNovelId();
        if (novelId) return {kind: 'novel', id: novelId};
        const artworkId = getArtworkId();
        if (artworkId) return {kind: 'illust', id: artworkId};
        return null;
    }

    /* ========== Pixiv 小说 meta 解析 helper ==========
     * 直连 https://www.pixiv.net/ajax/novel/{id} 时，把 Pixiv 原始 body 转成与后端
     * NovelMetaResponse 等价的 JS 对象，保持 downloadNovel 的字段访问不变。description
     * 与 series caption 的 HTML 归一化由后端 NovelDownloadService / NovelSeriesService
     * 在落库前完成，脚本传原始字符串即可。
     * ------------------------------------------------------------------------ */
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

    /* ========== Pixiv bookmark helper ==========
     * 直连 Pixiv 发 bookmark 请求（GM_xmlhttpRequest 自带浏览器登录态含 HttpOnly
     * PHPSESSID）；不再把 document.cookie 透传给后端让后端代发，那条路径取不到 PHPSESSID
     * 必然失败。CSRF token 通过首页 HTML 正则提取（与后端 PixivBookmarkService.fetchCsrfToken
     * 同款）+ 缓存复用。返回 {status, message} 与现有 bookmarkResult 渲染管线兼容。
     * ------------------------------------------------------------------------ */
    let _pixivCsrfToken = null;

    function _resetPixivCsrfToken() {
        _pixivCsrfToken = null;
    }

    function fetchPixivCsrfToken() {
        if (_pixivCsrfToken) return Promise.resolve(_pixivCsrfToken);
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: 'https://www.pixiv.net/',
                headers: {Referer: 'https://www.pixiv.net/'},
                onload: (res) => {
                    const body = res.responseText || '';
                    const patterns = [
                        /token\\":\\"([^\\"]+)\\"/,
                        /"token":"([^"]+)"/,
                        /&quot;token&quot;\s*:\s*&quot;([^&]+)&quot;/
                    ];
                    for (const p of patterns) {
                        const m = body.match(p);
                        if (m && m[1]) {
                            _pixivCsrfToken = m[1];
                            return resolve(_pixivCsrfToken);
                        }
                    }
                    reject(new Error('无法获取 CSRF token（可能未登录 Pixiv）'));
                },
                onerror: () => reject(new Error('获取 CSRF token 请求失败')),
                ontimeout: () => reject(new Error('获取 CSRF token 请求失败'))
            });
        });
    }

    async function _pixivBookmark(url, payload) {
        let token;
        try {
            token = await fetchPixivCsrfToken();
        } catch (e) {
            return {status: 'failed', message: e && e.message ? e.message : 'csrf failed'};
        }
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url,
                headers: {
                    'Content-Type': 'application/json; charset=utf-8',
                    'x-csrf-token': token,
                    Referer: 'https://www.pixiv.net/'
                },
                data: JSON.stringify(payload),
                onload: (res) => {
                    let body = null;
                    try { body = JSON.parse(res.responseText || '{}'); } catch (_) {}
                    if (res.status === 200) {
                        if (body && body.error) {
                            _resetPixivCsrfToken();
                            resolve({status: 'failed', message: body.message || 'pixiv error'});
                            return;
                        }
                        resolve({status: 'success'});
                        return;
                    }
                    if (res.status === 401 || res.status === 403) {
                        _resetPixivCsrfToken();
                        resolve({status: 'failed', message: '收藏失败（未登录或登录已过期）'});
                        return;
                    }
                    resolve({status: 'failed', message: 'HTTP ' + res.status});
                },
                onerror: () => resolve({status: 'failed', message: '收藏请求失败'}),
                ontimeout: () => resolve({status: 'failed', message: '收藏请求失败'})
            });
        });
    }

    function pixivBookmarkArtwork(artworkId) {
        return _pixivBookmark('https://www.pixiv.net/ajax/illusts/bookmarks/add', {
            illust_id: String(artworkId),
            restrict: 0,
            comment: '',
            tags: []
        });
    }

    function pixivBookmarkNovel(novelId) {
        return _pixivBookmark('https://www.pixiv.net/ajax/novels/bookmarks/add', {
            novel_id: String(novelId),
            restrict: 0,
            comment: '',
            tags: []
        });
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

    // Pixiv 小说元数据（直连 Pixiv：GM_xmlhttpRequest 会带上浏览器的登录 Cookie，
    // 含 HttpOnly 的 PHPSESSID，从而能取到受限 / R18 小说的完整 meta 与正文 content。
    // 不要走后端代理 + document.cookie —— document.cookie 取不到 HttpOnly 会话 Cookie，
    // 导致后端以匿名身份请求 Pixiv，受限小说被隐藏或元信息缺失）。
    async function getNovelMeta(novelId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/novel/${encodeURIComponent(novelId)}?lang=zh`,
                headers: {Referer: 'https://www.pixiv.net/'},
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data && data.error) {
                            reject(new Error(data.message || 'pixiv novel meta error'));
                            return;
                        }
                        if (response.status < 200 || response.status >= 300) {
                            reject(new Error('meta HTTP ' + response.status));
                            return;
                        }
                        resolve(buildNovelMetaFromPixivBody(novelId, data.body || {}));
                    } catch (e) {
                        reject(e);
                    }
                },
                onerror: reject
            });
        });
    }

    // 小说是否已下载（后端画廊存在即视为已下载）
    async function checkNovelDownloaded(novelId) {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `${serverBase}/api/gallery/novel/${encodeURIComponent(novelId)}`,
                timeout: 5000,
                onload: (res) => resolve(res.status === 200),
                onerror: () => resolve(false),
                ontimeout: () => resolve(false)
            });
        });
    }

    // 单次脚本生命周期的小说系列元数据缓存
    const novelSeriesEnrichmentCache = new Map();
    // 直连 Pixiv 系列接口（与 getNovelMeta 同理，避免后端代理 + document.cookie
    // 取不到 HttpOnly 会话 Cookie 导致受限系列封面 / 简介 / 标签缺失）。
    function fetchNovelSeriesEnrichment(seriesId) {
        const sid = Number(seriesId);
        if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
        if (novelSeriesEnrichmentCache.has(sid)) return novelSeriesEnrichmentCache.get(sid);
        const p = new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/novel/series/${sid}?lang=zh`,
                headers: {Referer: 'https://www.pixiv.net/'},
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        if (!data || data.error) return resolve(null);
                        const meta = data.body || null;
                        if (!meta) return resolve(null);
                        resolve({
                            caption: String(meta.caption || ''),
                            coverUrl: _pn_extractSeriesCoverUrl(meta),
                            tags: _pn_extractTags(meta)
                        });
                    } catch (_) {
                        resolve(null);
                    }
                },
                onerror: () => resolve(null)
            });
        });
        novelSeriesEnrichmentCache.set(sid, p);
        return p;
    }

    async function downloadNovel() {
        const novelId = getNovelId();
        if (!novelId) {
            alert(t('single.alert.no-novel-id', '无法获取小说ID'));
            return;
        }
        const isBackendAvailable = await checkBackendStatus();
        if (!isBackendAvailable) {
            alert(t('single.alert.backend-not-running', null, {serverBase: serverBase}));
            return;
        }
        const skipHistory = GM_getValue(KEY_SKIP_HISTORY, false);
        if (skipHistory) {
            const already = await checkNovelDownloaded(novelId);
            if (already) {
                alert(t('single.alert.novel-history-skipped', null, {novelId: novelId}));
                return;
            }
        }
        try {
            alert(t('single.alert.novel-start-download', null, {novelId: novelId}));
            const meta = await getNovelMeta(novelId);
            const fmt = (GM_getValue(KEY_NOVEL_FORMAT, 'txt') || 'txt').toLowerCase();
            const bookmark = GM_getValue(KEY_BOOKMARK_AFTER_DL, false);
            const seriesInfo = meta.seriesId ? {
                seriesId: meta.seriesId,
                seriesOrder: meta.seriesOrder,
                seriesTitle: meta.seriesTitle
            } : null;
            const seriesEnrich = seriesInfo
                ? await fetchNovelSeriesEnrichment(seriesInfo.seriesId)
                : null;
            const body = {
                novelId: Number(novelId),
                title: meta.title,
                // bookmark 已迁到脚本端，后端不再需要用户 Pixiv cookie；pximg 下封面/内嵌图
                // 只看 Referer，不需要 cookie。
                cookie: null,
                content: meta.content,
                other: {
                    authorId: meta.authorId,
                    authorName: meta.authorName,
                    xRestrict: meta.xRestrict,
                    ai: meta.isAi,
                    original: meta.isOriginal,
                    language: meta.language,
                    wordCount: meta.wordCount,
                    textLength: meta.textLength,
                    readingTimeSeconds: meta.readingTimeSeconds ?? null,
                    pageCount: meta.pageCount,
                    description: meta.description,
                    tags: Array.isArray(meta.tags) ? meta.tags : [],
                    seriesId: seriesInfo ? seriesInfo.seriesId : null,
                    seriesOrder: seriesInfo ? seriesInfo.seriesOrder : null,
                    seriesTitle: seriesInfo ? seriesInfo.seriesTitle : null,
                    seriesDescription: seriesEnrich && seriesEnrich.caption ? seriesEnrich.caption : null,
                    seriesCoverUrl: seriesEnrich && seriesEnrich.coverUrl ? seriesEnrich.coverUrl : null,
                    seriesTags: seriesEnrich && seriesEnrich.tags && seriesEnrich.tags.length
                        ? seriesEnrich.tags : null,
                    // bookmark 由脚本侧直连 Pixiv 完成（见下方 pixivBookmarkNovel 调用），
                    // 永远不让后端代发 —— document.cookie 取不到 HttpOnly PHPSESSID。
                    bookmark: false,
                    collectionId: null,
                    format: fmt,
                    uploadTimestamp: meta.uploadTimestamp || null,
                    coverUrl: meta.coverUrl || '',
                    embeddedImages: meta.textEmbeddedImages || {}
                }
            };
            const response = await new Promise((resolve, reject) => {
                const headers = {'Content-Type': 'application/json'};
                if (userUUID) headers['X-User-UUID'] = userUUID;
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: `${serverBase}/api/download/pixiv/novel`,
                    headers,
                    data: JSON.stringify(body),
                    onload: (res) => {
                        let data = {};
                        try {
                            data = JSON.parse(res.responseText);
                        } catch (_) {
                        }
                        if (res.status === 401) {
                            handleUnauthorized();
                            reject(new Error('需要登录'));
                        } else if (res.status === 429 && data.quotaExceeded) {
                            const err = new Error('quota_exceeded');
                            err.quotaData = data;
                            reject(err);
                        } else if (res.status === 200) {
                            resolve(data);
                        } else {
                            reject(new Error(data.message || '下载请求失败'));
                        }
                    },
                    onerror: reject
                });
            });
            if (quotaInfo.enabled) {
                quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
            }
            updateDownloadUI();
            const typeHint = t('single.type.novel', '小说（{format}）', {format: fmt.toUpperCase()});
            alert(t('single.alert.download-submitted', null, {
                typeHint: typeHint,
                message: response.message || ''
            }));
            // 脚本端直连 Pixiv 完成 bookmark（best-effort，不阻断下载结果）。
            if (bookmark) {
                const bookmarkResult = await pixivBookmarkNovel(novelId);
                if (bookmarkResult.status !== 'success') {
                    alert(t('single.alert.bookmark-failed', '收藏失败：{message}', {
                        message: bookmarkResult.message || ''
                    }));
                }
            }
        } catch (error) {
            if (error.message === 'quota_exceeded' && error.quotaData) {
                console.warn(t('log.download-limit-reached', '已达到下载限额'));
                showQuotaExceededUI(error.quotaData);
            } else {
                console.error(t('log.novel-download-failed', '小说下载失败'), error);
                alert(t('single.alert.download-failed', null, {message: error.message}));
            }
        }
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

    // 单次脚本生命周期的系列元数据缓存。插画系列：直连 Pixiv（与 fetchNovelSeriesEnrichment
    // 同理，避免后端代理 + document.cookie 取不到 HttpOnly PHPSESSID 导致受限漫画系列 caption
    // 缺失）。漫画系列通常没有独立封面字段（Pixiv UI 展示的"封面"是作者指定某话首图），
    // _pn_extractSeriesCoverUrl 大概率返回空串，与之前后端代理路径行为一致。
    const seriesEnrichmentCache = new Map();
    function fetchSeriesEnrichment(seriesId) {
        const sid = Number(seriesId);
        if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
        if (seriesEnrichmentCache.has(sid)) return seriesEnrichmentCache.get(sid);
        const p = new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/series/${sid}?p=1&lang=zh`,
                headers: {Referer: 'https://www.pixiv.net/'},
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        if (!data || data.error) return resolve(null);
                        const arr = data.body && data.body.illustSeries;
                        const meta = Array.isArray(arr) && arr.length > 0 ? arr[0] : null;
                        if (!meta) return resolve(null);
                        resolve({
                            caption: String(meta.caption || ''),
                            coverUrl: _pn_extractSeriesCoverUrl(meta)
                        });
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
                // bookmark 已迁到脚本端，后端不再需要用户 Pixiv cookie；pximg 下图只看 Referer。
                cookie: null,
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
        if (getNovelId()) {
            return downloadNovel();
        }
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
            // 系列简介/封面通过 fetchSeriesEnrichment 直连 Pixiv 查询。
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
            // bookmark 由脚本侧直连 Pixiv 完成（见下方 pixivBookmarkArtwork 调用），
            // 永远不让后端代发 —— document.cookie 取不到 HttpOnly PHPSESSID。
            let other = { bookmark: false, authorId, authorName, xRestrict, isAi, description, tags, ...seriesFields };

            if (meta && meta.illustType === 2) {
                // 动图作品：获取ugoira元数据，下载ZIP并在后端合成WebP
                const ugoiraMeta = await getUgoiraMeta(artworkId);
                const zipSrc = ugoiraMeta.originalSrc || ugoiraMeta.src;
                imageUrls = [zipSrc];
                other = {
                    isUgoira: true,
                    ugoiraZipUrl: zipSrc,
                    ugoiraDelays: ugoiraMeta.frames.map(f => f.delay),
                    bookmark: false,
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

            // 脚本端直连 Pixiv 完成 bookmark（best-effort，不阻断下载结果）。
            if (bookmark) {
                const bookmarkResult = await pixivBookmarkArtwork(artworkId);
                if (bookmarkResult.status !== 'success') {
                    alert(t('single.alert.bookmark-failed', '收藏失败：{message}', {
                        message: bookmarkResult.message || ''
                    }));
                }
            }

        } catch (error) {
            if (error.message === 'quota_exceeded' && error.quotaData) {
                console.warn(t('log.download-limit-reached', '已达到下载限额'));
                showQuotaExceededUI(error.quotaData);
            } else {
                console.error(t('log.download-failed', '下载失败'), error);
                alert(t('single.alert.download-failed', null, { message: error.message }));
            }
        }
    }

    // 创建下载UI
    function createDownloadUI() {
        // 移除已存在的UI
        const existingUI = document.getElementById('pixiv-java-downloader-ui');
        if (existingUI) {
            existingUI.remove();
        }
        const existingFab = document.getElementById('pixiv-single-java-mini-fab');
        if (existingFab) existingFab.remove();

        const target = getPageTarget();
        if (!target) return; // 不是单作品/单小说页面，不显示UI
        const isNovel = target.kind === 'novel';
        const artworkId = target.id;

        const skipHistoryEnabled = GM_getValue(KEY_SKIP_HISTORY, false);
        const verifyHistoryFilesEnabled = GM_getValue(KEY_VERIFY_HISTORY_FILES, false);

        const container = document.createElement('div');
        container.id = 'pixiv-java-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 260px;
            right: 80px;
            z-index: 9999;
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
        titleDiv.textContent = isNovel
            ? t('single.novel.title', 'Pixiv小说下载器 (Java后端)')
            : t('single.title', 'Pixiv下载器 (Java后端)');
        titleDiv.style.cssText = `
            font-weight: bold;
            color: #333;
            text-align: center;
            font-size: 16px;
            flex: 1;
        `;
        const collapseBtn = document.createElement('button');
        collapseBtn.textContent = '◀';
        collapseBtn.title = t('single.action.collapse', '收起');
        collapseBtn.style.cssText = 'background:none;border:1px solid #ccc;border-radius:4px;cursor:pointer;font-size:12px;padding:2px 6px;color:#666;flex-shrink:0;';
        collapseBtn.addEventListener('click', () => toggleSinglePanel(true));
        titleRow.appendChild(collapseBtn);
        titleRow.appendChild(titleDiv);
        titleRow.appendChild(buildLangSwitcher());

        const statusDiv = document.createElement('div');
        statusDiv.textContent = isNovel
            ? t('single.novel.status.ready', '⬇️ 可下载此小说')
            : t('single.status.ready', '⬇️ 可下载此作品');
        statusDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 8px;
            color: #0096fa;
            text-align: center;
            font-size: 14px;
        `;

        const button = document.createElement('button');
        button.textContent = isNovel
            ? t('single.novel.button.download', '📥 通过后端下载小说')
            : t('single.button.download', '📥 通过后端下载');
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

        // 小说格式选择（仅小说页面显示）
        const novelFormatRow = document.createElement('div');
        novelFormatRow.style.cssText = `display:${isNovel ? 'flex' : 'none'};align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;`;
        const novelFormatLabel = document.createElement('label');
        novelFormatLabel.htmlFor = 'pixiv-dl-novel-format';
        novelFormatLabel.textContent = t('single.novel.format-label', '小说格式:');
        const novelFormatSel = document.createElement('select');
        novelFormatSel.id = 'pixiv-dl-novel-format';
        novelFormatSel.style.cssText = 'flex:1;padding:3px 4px;border:1px solid #ddd;border-radius:4px;font-size:12px;';
        [['txt', t('single.novel.format-txt', '纯文本（TXT）')],
            ['html', t('single.novel.format-html', '网页（HTML）')],
            ['epub', t('single.novel.format-epub', '电子书（EPUB）')]].forEach(([val, label]) => {
            const opt = document.createElement('option');
            opt.value = val;
            opt.textContent = label;
            novelFormatSel.appendChild(opt);
        });
        novelFormatSel.value = GM_getValue(KEY_NOVEL_FORMAT, 'txt');
        novelFormatSel.addEventListener('change', () => GM_setValue(KEY_NOVEL_FORMAT, novelFormatSel.value));
        novelFormatRow.appendChild(novelFormatLabel);
        novelFormatRow.appendChild(novelFormatSel);

        const artworkIdDiv = document.createElement('div');
        artworkIdDiv.textContent = isNovel
            ? t('single.novel.id', '小说ID: {id}', { id: artworkId })
            : t('single.artwork-id', '作品ID: {id}', { id: artworkId });
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
        if (isNovel) {
            container.appendChild(novelFormatRow);
        } else {
            container.appendChild(verifyHistoryFilesRow);
        }
        container.appendChild(artworkIdDiv);
        container.appendChild(backendStatusDiv);
        container.appendChild(infoDiv);
        container.appendChild(quotaBarDiv);
        container.appendChild(archiveCardDiv);
        document.body.appendChild(container);

        // Mini FAB（收起态显示，固定在视口右侧，堆叠在工具箱 FAB 下方）
        const miniFab = document.createElement('button');
        miniFab.id = 'pixiv-single-java-mini-fab';
        miniFab.textContent = '☁️';
        miniFab.title = isNovel
            ? t('single.novel.title', 'Pixiv小说下载器 (Java后端)')
            : t('single.fab.title', 'Pixiv 单作品下载器 (Java后端)');
        miniFab.style.cssText = 'display:none;position:fixed;top:260px;right:20px;z-index:10001;background:#0096fa;color:white;border:none;border-radius:50%;width:40px;height:40px;cursor:pointer;font-size:18px;box-shadow:0 2px 8px rgba(0,0,0,0.3);line-height:40px;text-align:center;padding:0;';
        miniFab.addEventListener('click', () => toggleSinglePanel(true));
        document.body.appendChild(miniFab);

        // 重建面板时沿用当前折叠状态（初始值来自共享存储，跨脚本变更经 onChange 同步）
        applySinglePanelCollapsed();

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

    // 面板收起/展开：收起态显示 Mini FAB，展开态显示面板
    function applySinglePanelCollapsed() {
        const container = document.getElementById('pixiv-java-downloader-ui');
        const fab = document.getElementById('pixiv-single-java-mini-fab');
        if (panelCollapsed) {
            if (container) container.style.display = 'none';
            if (fab) fab.style.display = 'block';
        } else {
            if (container) container.style.display = 'block';
            if (fab) fab.style.display = 'none';
        }
    }

    function setSinglePanelCollapsed(collapsed, manual) {
        panelCollapsed = !!collapsed;
        applySinglePanelCollapsed();
        if (!panelCollapsed) {
            // 展开时与其它面板互斥：通知同侧面板收起
            document.dispatchEvent(new CustomEvent('pixiv_panel_active', { detail: 'single-java' }));
        }
        // 仅用户手动收/展写入共享状态；自动收起不持久化
        if (manual) savePanelCollapsed(panelCollapsed);
    }

    function toggleSinglePanel(manual) {
        setSinglePanelCollapsed(!panelCollapsed, manual);
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
            console.log(t('log.init-download-ui-failed', '初始化下载UI失败'), error);
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

    // 跨脚本共享折叠状态：仅“收起”跨脚本传播（收一个=全收），“展开”不在此级联
    PixivPanelState.onChange(c => {
        if (c && !panelCollapsed) {
            panelCollapsed = true;
            applySinglePanelCollapsed();
        }
    });

    // 跨脚本面板互斥：其它面板展开时收起本面板，避免同侧堆叠遮挡
    document.addEventListener('pixiv_panel_active', e => {
        if (e.detail && e.detail !== 'single-java' && !panelCollapsed) {
            panelCollapsed = true;
            applySinglePanelCollapsed();
        }
    });

    GM_registerMenuCommand(t('single.menu.open', '打开 Pixiv 单作品下载器面板'), () => {
        setSinglePanelCollapsed(false, true);
    });

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
