// ==UserScript==
// @name         Pixiv 批量导入作品下载器
// @namespace    http://tampermonkey.net/
// @version      2.0.9
// @description  粘贴作品链接列表批量下载，格式为 url | title，兼容 One-Tab，N-Tab 等标签页管理插件导出格式，支持严格的下载状态校验。
// @author       Rewritten by ChatGPT,Claude,Sywyar
// @match        https://www.pixiv.net/*
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_deleteValue
// @grant        GM_registerMenuCommand
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @connect      localhost
// @connect      YOUR_SERVER_HOST
// @run-at       document-end
// ==/UserScript==

(function () {
    'use strict';

    /* ========== 配置 ========== */
    const KEY_SERVER_URL = 'pixiv_server_base';
    let serverBase = GM_getValue(KEY_SERVER_URL, 'http://localhost:6999').replace(/\/$/, '');

    const CONFIG = {
        get BACKEND_URL() {
            return serverBase + '/api/download/pixiv';
        },
        get STATUS_URL() {
            return serverBase + '/api/download/status';
        },    // GET /{artworkId}
        get CANCEL_URL() {
            return serverBase + '/api/download/cancel';
        },   // POST /{artworkId}
        get SSE_BASE() {
            return serverBase + '/api/sse/download';
        },        // /{artworkId}
        get QUOTA_INIT_URL() {
            return serverBase + '/api/quota/init';
        },
        get ARCHIVE_STATUS_BASE() {
            return serverBase + '/api/archive/status';
        },
        get ARCHIVE_DOWNLOAD_BASE() {
            return serverBase + '/api/archive/download';
        },
        DEFAULT_INTERVAL: 2,
        DEFAULT_CONCURRENT: 1,
        STATUS_TIMEOUT_MS: 300000,
        BACKEND_CHECK_TIMEOUT: 3000,
        STORAGE_KEY: 'pixiv_ntab_batch_v1',
        SKIP_HISTORY_KEY: 'pixiv_ntab_skip_history',
        VERIFY_HISTORY_FILES_KEY: 'pixiv_ntab_verify_history_files',
        R18_ONLY_KEY: 'pixiv_ntab_r18_only',
        INTERVAL_UNIT_KEY: 'pixiv_ntab_interval_unit',
        IMAGE_DELAY_KEY: 'pixiv_ntab_image_delay',
        IMAGE_DELAY_UNIT_KEY: 'pixiv_ntab_image_delay_unit',
        KEY_USER_UUID: 'pixiv_user_uuid',
        BOOKMARK_KEY: 'pixiv_ntab_bookmark'
    };

    // ====== 配额状态 ======
    let quotaInfo = {enabled: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0};
    let userUUID = GM_getValue(CONFIG.KEY_USER_UUID, null);
    const VERIFY_HISTORY_FILES_TOOLTIP = '通过检查记录的目录是否存在、文件夹是否为空、文件夹中的文件是否包含图片来判断是否有效，如果无效则会重新下载';

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
            'common.dialog.connect-notice': 'Pixiv download script first-run hint\n\nIf you use an external server instead of localhost, replace this userscript header line:\n  // @connect      YOUR_SERVER_HOST\nwith your real server IP or domain, for example:\n  // @connect      192.168.1.100\n\nPath: Tampermonkey dashboard -> target script -> Edit -> Save\n\nOr use the web UI directly:\n{serverBase}/login.html\n\n(This hint is shown only once)',
            'common.option.verify-history-files.tooltip': 'Checks whether the recorded directory exists, whether it is empty, and whether it contains image files. Invalid records will be downloaded again.',
            'common.status.ready': 'Ready',
            'common.queue.empty': 'Queue is empty',
            'common.current.none': 'None',
            'common.current.label': 'Current download:',
            'common.queue.label': 'Download Queue:',
            'common.button.pause': '⏸️ Pause',
            'common.button.resume': '▶️ Resume',
            'common.action.collapse': 'Collapse',
            'common.action.remove': 'Remove from queue',
            'common.action.open-artwork': 'Open artwork page',
            'common.stats.summary': 'Queue: {pending} | Success: {success} | Failed: {failed} | Active: {active} | Skipped: {skipped}',
            'common.progress.downloaded': 'Downloaded: {count}/{total}',
            'common.progress.current': 'Downloaded {count} / {total}',
            'common.status.idle': 'Pending',
            'common.status.pending': 'Pending',
            'common.status.downloading': 'Downloading',
            'common.status.completed': 'Completed',
            'common.status.failed': 'Failed',
            'common.status.paused': 'Paused',
            'common.status.skipped': 'Skipped',
            'common.quota.summary': 'Quota: {used}/{max} artworks',
            'common.quota.reset': ' | resets in: {time}',
            'common.archive.limit-title': 'Download limit reached',
            'common.archive.restore-title': 'An unfinished archive is available',
            'common.archive.preparing': 'Preparing downloaded files, please wait...',
            'common.archive.expired': 'Download link expired',
            'common.archive.empty': 'No files available for packaging',
            'common.archive.ready': 'Archive is ready:',
            'common.archive.download-link': 'Download Archive',
            'common.archive.validity': 'Valid for: {time}',
            'import.title': '🎨 Artwork URL Batch Importer',
            'import.fab.title': 'Artwork URL Batch Importer',
            'import.input.placeholder': 'Paste artwork URLs exported from One-Tab, N-Tab, and similar tab managers...',
            'import.format.title': 'Import format:',
            'import.format.example': 'One item per line, for example:',
            'import.format.example-title': 'Example Title',
            'import.format.fetch-title': 'Title can be empty. The real title will be fetched before download.',
            'import.format.compatible': 'Compatible with One-Tab, N-Tab, and similar tab manager exports.',
            'import.format.reimport': 'Also accepts files exported by the buttons below so they can be imported again directly.',
            'import.setting.interval': 'Artwork interval:',
            'import.setting.image-delay': 'Image delay:',
            'import.setting.concurrent': 'Max concurrency:',
            'import.setting.skip-history': 'Skip download history:',
            'import.setting.verify-history-files': 'Verify saved directory:',
            'import.setting.r18-only': 'R18 only:',
            'import.setting.bookmark': 'Auto-bookmark after download:',
            'import.setting.server': 'Server URL:',
            'import.button.parse': '📋 Import and Queue',
            'import.button.start': '🚀 Start Batch Download',
            'import.button.retry': '🔁 Retry Failed Artworks',
            'import.button.export-all': '📤 Export Download List',
            'import.button.export-failed': '📋 Export Undownloaded List',
            'import.button.clear': '🗑️ Clear Queue',
            'import.alert.backend-unavailable': 'Backend is unavailable. If you use a non-localhost server, replace @connect YOUR_SERVER_HOST in the userscript header as described in README.',
            'import.alert.no-failed': 'There are no failed artworks right now.',
            'import.alert.queue-empty-export': 'Queue is empty. Nothing to export.',
            'import.alert.no-undownloaded': 'There are no undownloaded artworks.',
            'import.confirm.clear': 'Force clear the queue?',
            'import.confirm.clear-running': 'Downloads are still running. Force stop and clear the queue? Cancel keeps the queue.',
            'import.status.cleared-persisted': 'Queue was force-cleared and persisted data was removed',
            'import.status.parsed': 'Parsed {count} artworks',
            'import.status.started': 'Batch download started',
            'import.status.finished': 'Batch download finished',
            'import.status.finished-packing': 'Batch download finished. Preparing archive...',
            'import.status.skipped-history': 'Skipped: {title} (already downloaded)',
            'import.status.fetching': 'Fetching artwork info: {title}',
            'import.status.skipped-r18': 'Skipped: {title} (not R18)',
            'import.status.downloading': 'Downloading: {title}',
            'import.status.skipped-existing': 'Skipped: {title} (already downloaded)',
            'import.status.failed-missing': 'Failed: {title} (missing files)',
            'import.status.completed': 'Completed: {title}',
            'import.status.failed': 'Failed: {title} - {message}',
            'import.status.need-login-stop': 'Login required. Download stopped',
            'import.status.quota-exceeded': 'Download limit reached',
            'import.status.error': 'Error: {title} - {message}',
            'import.status.paused': 'Paused',
            'import.status.resumed': 'Resumed',
            'import.status.exported': 'Exported {count} artworks',
            'import.status.exported-undownloaded': 'Exported {count} undownloaded artworks',
            'import.status.need-login-refresh': 'Login required. Please log in and refresh the page',
            'import.menu.open': 'Open Artwork URL Batch Importer'
        },
        'zh-CN': {
            'switcher.label': '语言',
            'common.dialog.unauthorized': '后端服务需要登录验证，即将为您打开登录页面...',
            'common.dialog.connect-notice': 'Pixiv 下载脚本初始化提示\n\n如果您使用外部服务器（非 localhost），需将脚本头部的：\n  // @connect      YOUR_SERVER_HOST\n替换为实际的服务器 IP 或域名，例如：\n  // @connect      192.168.1.100\n\n修改路径：Tampermonkey 管理面板 -> 对应脚本 -> 编辑 -> 保存\n\n或者直接通过网页端下载作品（无需脚本）：\n{serverBase}/login.html\n\n（此提示只显示一次）',
            'common.option.verify-history-files.tooltip': '通过检查记录的目录是否存在、文件夹是否为空、文件夹中的文件是否包含图片来判断是否有效，如果无效则会重新下载',
            'common.status.ready': '准备就绪',
            'common.queue.empty': '队列为空',
            'common.current.none': '无',
            'common.current.label': '当前下载:',
            'common.queue.label': '下载队列:',
            'common.button.pause': '⏸️ 暂停下载',
            'common.button.resume': '▶️ 继续下载',
            'common.action.collapse': '收起',
            'common.action.remove': '从队列移除',
            'common.action.open-artwork': '打开作品页面',
            'common.stats.summary': '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}',
            'common.progress.downloaded': '已下载: {count}/{total}',
            'common.progress.current': '已下载 {count} 张 / 共 {total} 张',
            'common.status.idle': '等待中',
            'common.status.pending': '等待中',
            'common.status.downloading': '下载中',
            'common.status.completed': '已完成',
            'common.status.failed': '失败',
            'common.status.paused': '暂停中',
            'common.status.skipped': '已跳过',
            'common.quota.summary': '配额：{used}/{max} 个作品',
            'common.quota.reset': ' | 重置剩余：{time}',
            'common.archive.limit-title': '已达到下载限额',
            'common.archive.restore-title': '已有未下载的压缩包',
            'common.archive.preparing': '正在打包已下载文件，请稍候...',
            'common.archive.expired': '下载链接已过期',
            'common.archive.empty': '暂无可打包文件',
            'common.archive.ready': '压缩包已就绪：',
            'common.archive.download-link': '下载压缩包',
            'common.archive.validity': '有效期：{time}',
            'import.title': '🎨 批量导入作品下载器',
            'import.fab.title': '批量导入作品下载器',
            'import.input.placeholder': '粘贴作品链接列表，兼容 One-Tab，N-Tab 等标签页管理插件导出格式...',
            'import.format.title': '导入格式：',
            'import.format.example': '每行一条，例如：',
            'import.format.example-title': '示例标题',
            'import.format.fetch-title': '标题可留空；下载前会自动获取真实标题。',
            'import.format.compatible': '兼容 One-Tab，N-Tab 等标签页管理插件导出格式。',
            'import.format.reimport': '也兼容下方“导出下载列表”“导出未下载列表”按钮生成的作品列表，可直接重新导入。',
            'import.setting.interval': '作品间隔:',
            'import.setting.image-delay': '图片间隔:',
            'import.setting.concurrent': '最大并发数:',
            'import.setting.skip-history': '跳过历史下载:',
            'import.setting.verify-history-files': '实际目录检测:',
            'import.setting.r18-only': '仅下载R18作品:',
            'import.setting.bookmark': '下载后自动收藏:',
            'import.setting.server': '服务器地址:',
            'import.button.parse': '📋 导入并加入队列',
            'import.button.start': '🚀 开始批量下载',
            'import.button.retry': '🔁 重新下载失败的作品',
            'import.button.export-all': '📤 导出下载列表',
            'import.button.export-failed': '📋 导出未下载列表',
            'import.button.clear': '🗑️ 清除队列',
            'import.alert.backend-unavailable': '后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址',
            'import.alert.no-failed': '当前没有失败的作品！',
            'import.alert.queue-empty-export': '队列为空，无内容可导出',
            'import.alert.no-undownloaded': '没有未下载的作品',
            'import.confirm.clear': '确认强制清除队列？',
            'import.confirm.clear-running': '当前有正在下载的作品，是否强制停止并清除？（取消将保留队列）',
            'import.status.cleared-persisted': '已强制清除队列并删除持久化数据',
            'import.status.parsed': '解析完成：找到 {count} 个作品',
            'import.status.started': '开始批量下载',
            'import.status.finished': '批量下载结束',
            'import.status.finished-packing': '批量下载结束，正在打包文件...',
            'import.status.skipped-history': '跳过：{title}（已下载过）',
            'import.status.fetching': '获取作品信息：{title}',
            'import.status.skipped-r18': '跳过：{title}（非R18）',
            'import.status.downloading': '开始下载：{title}',
            'import.status.skipped-existing': '跳过：{title}（已下载）',
            'import.status.failed-missing': '失败：{title} (文件缺失)',
            'import.status.completed': '完成：{title}',
            'import.status.failed': '失败：{title} - {message}',
            'import.status.need-login-stop': '需要登录，已停止下载',
            'import.status.quota-exceeded': '已达到下载限额',
            'import.status.error': '错误：{title} - {message}',
            'import.status.paused': '已暂停',
            'import.status.resumed': '继续下载',
            'import.status.exported': '已导出 {count} 个作品',
            'import.status.exported-undownloaded': '已导出 {count} 个未下载作品',
            'import.status.need-login-refresh': '需要登录，请登录后刷新页面',
            'import.menu.open': '打开 Pixiv 批量导入作品下载器'
        }
    });

    const t = (key, fallback, args) => PixivUserscriptI18n.t(key, fallback, args);

    const STATUS_TRANSLATORS = [
        [/^解析完成：找到 (\d+) 个作品$/, (_, count) => t('import.status.parsed', '解析完成：找到 {count} 个作品', { count: count })],
        [/^开始批量下载$/, () => t('import.status.started', '开始批量下载')],
        [/^批量下载结束$/, () => t('import.status.finished', '批量下载结束')],
        [/^批量下载结束，正在打包文件\.\.\.$/, () => t('import.status.finished-packing', '批量下载结束，正在打包文件...')],
        [/^跳过：(.+)（已下载过）$/, (_, title) => t('import.status.skipped-history', '跳过：{title}（已下载过）', { title: title })],
        [/^获取作品信息：(.+)$/, (_, title) => t('import.status.fetching', '获取作品信息：{title}', { title: title })],
        [/^跳过：(.+)（非R18）$/, (_, title) => t('import.status.skipped-r18', '跳过：{title}（非R18）', { title: title })],
        [/^开始下载：(.+)$/, (_, title) => t('import.status.downloading', '开始下载：{title}', { title: title })],
        [/^跳过：(.+)（已下载）$/, (_, title) => t('import.status.skipped-existing', '跳过：{title}（已下载）', { title: title })],
        [/^失败：(.+) \(文件缺失\)$/, (_, title) => t('import.status.failed-missing', '失败：{title} (文件缺失)', { title: title })],
        [/^完成：(.+)$/, (_, title) => t('import.status.completed', '完成：{title}', { title: title })],
        [/^失败：(.+) - (.+)$/, (_, title, message) => t('import.status.failed', '失败：{title} - {message}', { title: title, message: message })],
        [/^需要登录，已停止下载$/, () => t('import.status.need-login-stop', '需要登录，已停止下载')],
        [/^已达到下载限额$/, () => t('import.status.quota-exceeded', '已达到下载限额')],
        [/^错误：(.+) - (.+)$/, (_, title, message) => t('import.status.error', '错误：{title} - {message}', { title: title, message: message })],
        [/^已暂停$/, () => t('import.status.paused', '已暂停')],
        [/^继续下载$/, () => t('import.status.resumed', '继续下载')],
        [/^已强制清除队列并删除持久化数据$/, () => t('import.status.cleared-persisted', '已强制清除队列并删除持久化数据')],
        [/^已导出 (\d+) 个作品$/, (_, count) => t('import.status.exported', '已导出 {count} 个作品', { count: count })],
        [/^已导出 (\d+) 个未下载作品$/, (_, count) => t('import.status.exported-undownloaded', '已导出 {count} 个未下载作品', { count: count })]
    ];

    function translateStatusText(text) {
        const source = String(text ?? '');
        if (!source.trim()) return source;
        for (let i = 0; i < STATUS_TRANSLATORS.length; i += 1) {
            const [pattern, translate] = STATUS_TRANSLATORS[i];
            if (!pattern.test(source)) continue;
            pattern.lastIndex = 0;
            return source.replace(pattern, translate);
        }
        return source;
    }

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
                    if (res.status === 401) {
                        handleUnauthorized();
                        resolve(false);
                    } else resolve(true);
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

    /* ========== 简单 DOM 帮助函数 ========== */
    function $el(tag, props = {}, children = []) {
        const e = document.createElement(tag);
        Object.entries(props).forEach(([k, v]) => {
            if (k === 'style') Object.assign(e.style, v);
            else if (k === 'html') e.innerHTML = v;
            else e[k] = v;
        });
        (Array.isArray(children) ? children : [children]).forEach(c => {
            if (typeof c === 'string') e.appendChild(document.createTextNode(c));
            else if (c) e.appendChild(c);
        });
        return e;
    }

    /* ========== API 封装 ========== */
    const Api = {
        checkBackend() {
            return new Promise(resolve => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: CONFIG.STATUS_URL,
                    timeout: CONFIG.BACKEND_CHECK_TIMEOUT,
                    onload: (res) => resolve(res.status === 200),
                    onerror: () => resolve(false),
                    ontimeout: () => resolve(false)
                });
            });
        },
        getArtworkPages(artworkId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `https://www.pixiv.net/ajax/illust/${artworkId}/pages`,
                    headers: {Referer: 'https://www.pixiv.net/'},
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (data.error) reject(new Error(data.message || 'pixiv ajax error'));
                            else resolve((data.body || []).map(p => p.urls.original));
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        getArtworkMeta(artworkId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `https://www.pixiv.net/ajax/illust/${artworkId}`,
                    headers: {Referer: 'https://www.pixiv.net/'},
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (data.error) reject(new Error(data.message || 'pixiv ajax error'));
                            else resolve(data.body);
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        getUgoiraMeta(artworkId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `https://www.pixiv.net/ajax/illust/${artworkId}/ugoira_meta`,
                    headers: {Referer: 'https://www.pixiv.net/'},
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (data.error) reject(new Error(data.message || 'pixiv ajax error'));
                            else resolve(data.body);
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        sendDownloadRequest(artworkId, imageUrls, title, authorId, authorName, xRestrict, isAi, ugoiraData, delayMs, bookmark, description, tags) {
            return new Promise((resolve, reject) => {
                const parsedAuthorId = Number.parseInt(String(authorId ?? ''), 10);
                const other = {
                    userDownload: false,
                    authorId: Number.isFinite(parsedAuthorId) ? parsedAuthorId : null,
                    authorName: authorName || null,
                    xRestrict: Number(xRestrict) || 0,
                    isAi: !!isAi,
                    delayMs: delayMs || 0,
                    bookmark: !!bookmark,
                    description: description || null,
                    tags: Array.isArray(tags) && tags.length ? tags : null
                };
                if (ugoiraData) {
                    other.isUgoira = true;
                    other.ugoiraZipUrl = ugoiraData.zipUrl;
                    other.ugoiraDelays = ugoiraData.delays;
                }
                const payload = {
                    artworkId: parseInt(artworkId),
                    imageUrls,
                    title,
                    referer: 'https://www.pixiv.net/',
                    cookie: document.cookie,
                    other
                };
                const headers = {'Content-Type': 'application/json'};
                if (userUUID) headers['X-User-UUID'] = userUUID;
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: CONFIG.BACKEND_URL,
                    headers,
                    data: JSON.stringify(payload),
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (res.status === 401) {
                                handleUnauthorized();
                                reject(new Error('需要登录'));
                            } else if (res.status === 429 && data.quotaExceeded) {
                                const err = new Error('quota_exceeded');
                                err.quotaData = data;
                                reject(err);
                            } else if (res.status === 200 && (data.success === true || data.success === undefined)) {
                                resolve(data);
                            } else {
                                reject(new Error(data.message || '后端返回失败'));
                            }
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        initQuota() {
            const headers = {'Content-Type': 'application/json'};
            if (userUUID) headers['X-User-UUID'] = userUUID;
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: CONFIG.QUOTA_INIT_URL,
                    headers,
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (data.uuid) {
                                userUUID = data.uuid;
                                GM_setValue(CONFIG.KEY_USER_UUID, userUUID);
                            }
                            resolve(data);
                        } catch {
                            resolve({});
                        }
                    },
                    onerror: () => resolve({}),
                    ontimeout: () => resolve({})
                });
            });
        },
        pollArchiveStatus(token) {
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${CONFIG.ARCHIVE_STATUS_BASE}/${token}`,
                    onload: (res) => {
                        try {
                            resolve(JSON.parse(res.responseText));
                        } catch {
                            resolve({});
                        }
                    },
                    onerror: () => resolve({}),
                    ontimeout: () => resolve({})
                });
            });
        },
        getDownloadStatus(artworkId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${CONFIG.STATUS_URL}/${artworkId}`,
                    onload: (res) => {
                        if (res.status === 401) {
                            handleUnauthorized();
                            reject(new Error('需要登录'));
                            return;
                        }
                        try {
                            resolve(JSON.parse(res.responseText));
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        cancelDownload(artworkId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: `${CONFIG.CANCEL_URL}/${artworkId}`,
                    onload: (res) => {
                        try {
                            resolve(JSON.parse(res.responseText));
                        } catch (e) {
                            resolve(null);
                        }
                    },
                    onerror: () => {
                        resolve(null);
                    }
                });
            });
        },
        checkDownloaded(artworkId, verifyFiles = false) {
            return new Promise((resolve) => {
                const query = verifyFiles ? '?verifyFiles=true' : '';
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${serverBase}/api/downloaded/${artworkId}${query}`,
                    onload: (res) => {
                        try {
                            if (res.status === 200) {
                                const data = JSON.parse(res.responseText);
                                resolve(!!data.artworkId);
                            } else {
                                resolve(false);
                            }
                        } catch (e) {
                            resolve(false);
                        }
                    },
                    onerror: () => resolve(false),
                    ontimeout: () => resolve(false)
                });
            });
        }
    };

    /* ========== SSE 管理器（共享单连接版：所有作品复用同一条聚合 SSE，按 artworkId 路由） ========== */
    class SSEManager {
        constructor() {
            this.handle = null;
            this.reader = null;
            this.connected = false;
            this.connecting = false;
            this.listeners = new Map();        // artworkId -> [fn]
            this.activeArtworks = new Set();   // 当前需要监听的作品集合
            this._buffer = '';
            this._closeTimer = null;
            this._reconnectTimer = null;
        }

        // 兼容旧调用点的语义：
        // open(id) 表示开始关注该作品的进度（必要时建立共享连接）
        // close(id) 表示不再关注该作品；当所有关注都释放后延迟关闭共享连接
        open(artworkId) {
            const key = String(artworkId);
            this.activeArtworks.add(key);
            this._cancelDeferredClose();
            this._ensureConnection();
        }

        close(artworkId) {
            const key = String(artworkId);
            this.activeArtworks.delete(key);
            this.listeners.delete(key);
            if (this.activeArtworks.size === 0) this._scheduleDeferredClose();
        }

        closeAll() {
            this.activeArtworks.clear();
            this.listeners.clear();
            this._closeNow();
        }

        addListener(artworkId, fn) {
            const key = String(artworkId);
            if (!this.listeners.has(key)) this.listeners.set(key, []);
            this.listeners.get(key).push(fn);
        }

        _ensureConnection() {
            if (this.connected || this.connecting) return;
            this._openConnection();
        }

        _openConnection() {
            this.connecting = true;
            this._buffer = '';
            const headers = {};
            if (userUUID) headers['X-User-UUID'] = userUUID;
            try {
                this.handle = GM_xmlhttpRequest({
                    method: 'GET',
                    url: CONFIG.SSE_BASE, // 聚合端点：不带 artworkId
                    headers,
                    responseType: 'stream',
                    onloadstart: (res) => {
                        this.connecting = false;
                        this.connected = true;
                        const stream = res.response;
                        if (!stream || typeof stream.getReader !== 'function') {
                            console.warn('SSE: ReadableStream 不可用，将依赖轮询兜底');
                            return;
                        }
                        this._readStream(stream);
                    },
                    onerror: (err) => {
                        console.error('SSE connection error', err);
                        this._cleanup();
                        this._scheduleReconnect();
                    },
                    ontimeout: () => { this._cleanup(); this._scheduleReconnect(); }
                });
            } catch (err) {
                console.error('SSE open failed', err);
                this._cleanup();
                this._scheduleReconnect();
            }
        }

        _readStream(stream) {
            this.reader = stream.getReader();
            const decoder = new TextDecoder();
            const pump = () => {
                this.reader.read().then(({done, value}) => {
                    if (done) { this._cleanup(); this._scheduleReconnect(); return; }
                    const chunk = decoder.decode(value, {stream: true});
                    this._buffer += chunk;
                    const parts = this._buffer.split('\n\n');
                    this._buffer = parts.pop(); // 最后一段可能不完整，留作缓冲
                    for (const part of parts) {
                        if (!part.trim()) continue;
                        this._processEvent(part);
                    }
                    pump();
                }).catch(() => { this._cleanup(); this._scheduleReconnect(); });
            };
            pump();
        }

        _processEvent(rawEvent) {
            let eventName = '';
            const dataLines = [];
            for (const line of rawEvent.split('\n')) {
                if (line.startsWith('event:')) eventName = line.substring(6).trim();
                else if (line.startsWith('data:')) dataLines.push(line.substring(5));
            }
            if (eventName !== 'download-status' || dataLines.length === 0) return;
            try {
                const parsed = JSON.parse(dataLines.join('\n'));
                const aid = parsed && parsed.artworkId !== undefined && parsed.artworkId !== null
                    ? String(parsed.artworkId) : null;
                if (!aid) return;
                const fns = this.listeners.get(aid);
                if (fns) fns.forEach(fn => fn(parsed));
            } catch (e) { /* 握手 / 心跳等非 download-status JSON 忽略 */ }
        }

        _scheduleReconnect() {
            if (this.activeArtworks.size === 0) return;
            if (this._reconnectTimer) return;
            this._reconnectTimer = setTimeout(() => {
                this._reconnectTimer = null;
                if (this.activeArtworks.size > 0 && !this.connected && !this.connecting) {
                    this._openConnection();
                }
            }, 2000);
        }

        _scheduleDeferredClose() {
            this._cancelDeferredClose();
            this._closeTimer = setTimeout(() => {
                this._closeTimer = null;
                if (this.activeArtworks.size === 0) this._closeNow();
            }, 30000);
        }

        _cancelDeferredClose() {
            if (this._closeTimer) { clearTimeout(this._closeTimer); this._closeTimer = null; }
        }

        _closeNow() {
            this._cleanup();
            this._cancelDeferredClose();
            if (this._reconnectTimer) { clearTimeout(this._reconnectTimer); this._reconnectTimer = null; }
        }

        _cleanup() {
            this.connected = false;
            this.connecting = false;
            if (this.reader) { try { this.reader.cancel(); } catch (e) {} this.reader = null; }
            if (this.handle) { try { this.handle.abort(); } catch (e) {} this.handle = null; }
            this._buffer = '';
        }
    }

    /* ========== 下载管理器 ========== */
    class DownloadManager {
        constructor(ui) {
            this.ui = ui;
            this.queue = [];
            this.isRunning = false;
            this.isPaused = false;
            this.sse = new SSEManager();
            this.stopRequested = false;
            this.activeWorkers = 0;
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
            this.skipHistory = GM_getValue(CONFIG.SKIP_HISTORY_KEY, false);
            this.verifyHistoryFiles = GM_getValue(CONFIG.VERIFY_HISTORY_FILES_KEY, false);
            this.r18Only = GM_getValue(CONFIG.R18_ONLY_KEY, false);
            this.bookmark = GM_getValue(CONFIG.BOOKMARK_KEY, false);
            this._quotaExceededHandled = false;
        }

        dedupeQueueItems(items) {
            const seen = new Set();
            const uniqueItems = [];
            for (const item of items || []) {
                if (!item || item.id === undefined || item.id === null) continue;
                const id = String(item.id);
                if (seen.has(id)) continue;
                seen.add(id);
                uniqueItems.push({...item, id});
            }
            return uniqueItems;
        }

        loadFromStorage() {
            try {
                const raw = GM_getValue(CONFIG.STORAGE_KEY, null);
                if (!raw) return;
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed.queue)) {
                    // 刷新后 downloading 状态的任务实际已中断，重置为 idle 以便重新下载
                    this.queue = this.dedupeQueueItems(parsed.queue.map(q =>
                        q.status === 'downloading' ? {...q, status: 'idle', lastMessage: '刷新后重置'} : q
                    ));
                    this.isRunning = false; // 启动时默认暂停，避免自动跑
                    this.isPaused = !!parsed.isPaused;
                    this.stats = parsed.stats || {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
                    this.skipHistory = parsed.skipHistory !== undefined ? parsed.skipHistory : this.skipHistory;
                    this.verifyHistoryFiles = parsed.verifyHistoryFiles !== undefined ? parsed.verifyHistoryFiles : this.verifyHistoryFiles;
                    this.r18Only = parsed.r18Only !== undefined ? parsed.r18Only : this.r18Only;
                    this.bookmark = parsed.bookmark !== undefined ? parsed.bookmark : this.bookmark;
                }
            } catch (e) {
                console.warn('loadFromStorage fail', e);
            }
        }

        saveToStorage() {
            try {
                const snapshot = {
                    queue: this.queue,
                    isRunning: this.isRunning,
                    isPaused: this.isPaused,
                    stats: this.stats,
                    skipHistory: this.skipHistory,
                    verifyHistoryFiles: this.verifyHistoryFiles,
                    r18Only: this.r18Only,
                    bookmark: this.bookmark,
                    savedAt: new Date().toISOString()
                };
                GM_setValue(CONFIG.STORAGE_KEY, JSON.stringify(snapshot));
            } catch (e) {
            }
        }

        deleteStorage() {
            try {
                GM_deleteValue(CONFIG.STORAGE_KEY);
                GM_deleteValue(CONFIG.SKIP_HISTORY_KEY);
                GM_deleteValue(CONFIG.VERIFY_HISTORY_FILES_KEY);
                GM_deleteValue(CONFIG.R18_ONLY_KEY);
            } catch (e) {
            }
        }

        setSkipHistory(skip) {
            this.skipHistory = skip;
            GM_setValue(CONFIG.SKIP_HISTORY_KEY, skip);
            this.saveToStorage();
            if (this.ui && this.ui.updateSkipHistoryVisibility) {
                this.ui.updateSkipHistoryVisibility(skip);
            }
        }

        setVerifyHistoryFiles(verify) {
            this.verifyHistoryFiles = verify;
            GM_setValue(CONFIG.VERIFY_HISTORY_FILES_KEY, verify);
            this.saveToStorage();
        }

        setR18Only(r18Only) {
            this.r18Only = r18Only;
            GM_setValue(CONFIG.R18_ONLY_KEY, r18Only);
            this.saveToStorage();
        }

        setBookmark(bookmark) {
            this.bookmark = bookmark;
            GM_setValue(CONFIG.BOOKMARK_KEY, bookmark);
            this.saveToStorage();
        }

        setQueue(items) {
            const uniqueItems = this.dedupeQueueItems(items);
            this.queue = uniqueItems.map(it => ({
                id: String(it.id),
                title: it.title || `作品 ${it.id}`,
                url: it.url || `https://www.pixiv.net/artworks/${it.id}`,
                status: 'idle',
                totalImages: 0,
                downloadedCount: 0,
                startTime: null,
                endTime: null,
                lastMessage: ''
            }));
            this.updateStats();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return uniqueItems.length;
        }

        parseAndSetFromText(rawText) {
            const lines = rawText.split('\n').map(l => l.trim()).filter(Boolean);
            let items = [];
            const regex = /https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/;
            for (const ln of lines) {
                const m = ln.match(regex);
                if (m) {
                    const id = m[1];
                    let title = ln.split('|')[1] || '';
                    title = title.trim();
                    items.push({id, title: title || `作品 ${id}`, url: `https://www.pixiv.net/artworks/${id}`});
                }
            }
            items = this.dedupeQueueItems(items);
            this.setQueue(items);
            this.ui.setStatus(`解析完成：找到 ${items.length} 个作品`, 'success');
        }

        getIntervalMs() {
            const unit = GM_getValue(CONFIG.INTERVAL_UNIT_KEY, 's');
            const val = parseFloat(this.ui.elements.interval.value) || 0;
            return unit === 's' ? Math.round(val * 1000) : Math.round(val);
        }

        getImageDelayMs() {
            const unit = GM_getValue(CONFIG.IMAGE_DELAY_UNIT_KEY, 'ms');
            const val = parseFloat(this.ui.elements.imageDelay ? this.ui.elements.imageDelay.value : 0) || 0;
            return unit === 's' ? Math.round(val * 1000) : Math.round(val);
        }

        async start(intervalMs, maxConcurrent = CONFIG.DEFAULT_CONCURRENT) {
            if (intervalMs === undefined) intervalMs = this.getIntervalMs();
            if (this.queue.length === 0) {
                this.ui.setStatus('队列为空', 'error');
                return;
            }
            const backendOk = await Api.checkBackend();
            if (!backendOk) {
                alert(t('import.alert.backend-unavailable', '后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址'));
                return;
            }
            this.queue.forEach(q => {
                if (['idle', 'failed', 'paused'].includes(q.status)) q.status = 'pending';
            });
            this.isRunning = true;
            this.isPaused = false;
            this.stopRequested = false;
            this._quotaExceededHandled = false;
            this.updateStats();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            this.ui.setStatus('开始批量下载', 'info');

            const workers = [];
            for (let i = 0; i < Math.max(1, maxConcurrent); i++) {
                workers.push(this.workerLoop(intervalMs));
            }
            await Promise.all(workers);
            this.isRunning = false;
            this.saveToStorage();
            this.ui.setStatus('批量下载结束', 'info');
            this.ui.updateButtonsState(this.isRunning, this.isPaused);

            // 多人模式：队列完成后自动打包（配额超限时已在 _processSingle 中触发，不重复）
            const completed = this.queue.filter(q => q.status === 'completed').length;
            if (quotaInfo.enabled && completed > 0 && !this._quotaExceededHandled) {
                this._autoPackAfterQueue();
            }
        }

        async _autoPackAfterQueue() {
            try {
                const data = await new Promise((resolve) => {
                    const headers = {'Content-Type': 'application/json'};
                    if (userUUID) headers['X-User-UUID'] = userUUID;
                    GM_xmlhttpRequest({
                        method: 'POST',
                        url: serverBase + '/api/quota/pack',
                        headers,
                        onload: (res) => {
                            if (res.status === 204) {
                                resolve(null);
                                return;
                            }
                            try {
                                resolve(JSON.parse(res.responseText));
                            } catch {
                                resolve(null);
                            }
                        },
                        onerror: () => resolve(null),
                        ontimeout: () => resolve(null)
                    });
                });
                if (data && data.archiveToken) {
                    this.ui.setStatus('批量下载结束，正在打包文件...', 'info');
                    this.ui.showQuotaExceeded(data, '下载完成，正在打包');
                }
            } catch {
            }
        }

        async workerLoop(intervalMs) {
            this.activeWorkers++;
            try {
                while (this.isRunning && !this.stopRequested) {
                    if (this.isPaused) {
                        await this._sleep(300);
                        continue;
                    }
                    const next = this._getNextPending();
                    if (!next) {
                        if (this.queue.every(q => ['completed', 'failed', 'idle', 'paused', 'skipped'].includes(q.status))) break;
                        await this._sleep(500);
                        continue;
                    }
                    try {
                        await this._processSingle(next);
                    } catch (e) {
                        console.error('_processSingle error', e);
                    } finally {
                        await this._sleep(intervalMs);
                    }
                }
            } finally {
                this.activeWorkers--;
            }
        }

        _getNextPending() {
            const downloadingIds = new Set(
                this.queue.filter(q => q.status === 'downloading').map(q => q.id)
            );
            const idx = this.queue.findIndex(q => q.status === 'pending' && !downloadingIds.has(q.id));
            if (idx === -1) return null;
            this.queue[idx].status = 'downloading';
            this.queue[idx].startTime = new Date().toISOString();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return {idx, item: this.queue[idx]};
        }

        /* ========== 核心修复点：processSingle ========== */
        async _processSingle({idx, item}) {
            item.lastMessage = '正在检查历史记录...';
            this.ui.renderQueue(this.queue);

            if (this.skipHistory) {
                const isDownloaded = await Api.checkDownloaded(item.id, this.verifyHistoryFiles);
                if (isDownloaded) {
                    item.status = 'skipped';
                    item.lastMessage = '跳过 — 历史记录中已存在';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    this.ui.setStatus(`跳过：${item.title}（已下载过）`, 'warning');
                    return;
                }
            }

            item.lastMessage = '正在获取作品信息...';
            this.ui.setCurrent(item);
            this.ui.setStatus(`获取作品信息：${item.title}`, 'info');
            this.ui.renderQueue(this.queue);
            try {
                // 必须先获取 meta：illustType===2 表示动图，pages API 对动图只返回 1 张 JPG 缩略图
                // 不能依赖 pages 返回空来判断动图，必须用 illustType 判断
                const meta = await Api.getArtworkMeta(item.id);
                item.title = (meta && meta.illustTitle) || item.title || `作品 ${item.id}`;
                const authorId = meta?.userId ?? null;
                const authorName = meta?.userName || null;
                const xRestrict = Number(meta?.xRestrict ?? meta?.xrestrict ?? 0);
                const isAi = Number(meta?.aiType ?? 0) >= 2;
                const description = meta?.description || '';
                const tagsArr = meta && meta.tags && Array.isArray(meta.tags.tags) ? meta.tags.tags : [];
                const tags = tagsArr
                    .filter(t => t && t.tag)
                    .map(t => ({
                        name: String(t.tag),
                        translatedName: (t.translation && t.translation.en) ? String(t.translation.en) : null
                    }));

                if (this.r18Only && xRestrict < 1) {
                    item.status = 'skipped';
                    item.lastMessage = '跳过 — 非 R18 内容';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    this.ui.setStatus(`跳过：${item.title}（非R18）`, 'warning');
                    return;
                }

                item.lastMessage = '正在获取图片地址...';
                this.ui.renderQueue(this.queue);
                this.ui.setStatus(`开始下载：${item.title}`, 'info');

                let urls;
                let ugoiraData = null;

                if (meta && meta.illustType === 2) {
                    // 动图：pages API 仅返回缩略图 JPG，必须走 ugoira_meta 获取 ZIP
                    const ugoiraMeta = await Api.getUgoiraMeta(item.id);
                    const zipSrc = ugoiraMeta.originalSrc || ugoiraMeta.src;
                    ugoiraData = {
                        zipUrl: zipSrc,
                        delays: ugoiraMeta.frames.map(f => f.delay)
                    };
                    urls = [zipSrc];
                    item.totalImages = 1;
                } else if (meta && meta.pageCount === 1 && meta.urls && meta.urls.original) {
                    // 单页插画：meta 已带 original URL，无需再请求 /pages
                    urls = [meta.urls.original];
                    item.totalImages = 1;
                } else {
                    urls = await Api.getArtworkPages(item.id);
                    if (!Array.isArray(urls) || urls.length === 0) throw new Error('未获取到图片 URL');
                    item.totalImages = urls.length;
                }

                item.downloadedCount = 0;
                this.saveToStorage();
                this.ui.renderQueue(this.queue);

                const dlData = await Api.sendDownloadRequest(item.id, urls, item.title, authorId, authorName, xRestrict, isAi, ugoiraData, this.getImageDelayMs(), this.bookmark, description, tags);
                if (dlData && dlData.alreadyDownloaded) {
                    item.status = 'skipped';
                    item.lastMessage = '跳过 — 已下载（服务器确认）';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    this.ui.setStatus(`跳过：${item.title}（已下载）`, 'info');
                    return;
                }
                this.sse.open(item.id);
                const ssePromise = this._waitForFinalStatusBySSE(item.id, CONFIG.STATUS_TIMEOUT_MS);
                item.lastMessage = '下载中，等待完成...';
                this.ui.renderQueue(this.queue);
                const final = await ssePromise;

                if (final && final.completed) {
                    const dCount = final.downloadedCount !== undefined ? final.downloadedCount : item.totalImages;
                    item.downloadedCount = dCount;

                    if (dCount < item.totalImages) {
                        item.status = 'failed';
                        item.lastMessage = `失败 — 仅 ${dCount}/${item.totalImages} 张已下载`;
                        this.ui.setStatus(`失败：${item.title} (文件缺失)`, 'error');
                    } else {
                        item.status = 'completed';
                        item.lastMessage = `已完成，共 ${dCount} 张`;
                        this.ui.setStatus(`完成：${item.title}`, 'success');
                        // 刷新配额显示（每完成一个作品计 1）
                        if (quotaInfo.enabled) {
                            quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
                            this.ui.updateQuotaBar(quotaInfo);
                        }
                    }
                } else if (final && final.failed) {
                    item.status = 'failed';
                    item.lastMessage = `失败 — ${final.message || '后端报告失败'}`;
                    this.ui.setStatus(`失败：${item.title} - ${item.lastMessage}`, 'error');
                } else {
                    // 兜底查询
                    try {
                        const check = await Api.getDownloadStatus(item.id);
                        if (check && check.completed) {
                            const dCount = check.downloadedCount !== undefined ? check.downloadedCount : 0;
                            item.downloadedCount = dCount;
                            if (dCount < item.totalImages) {
                                item.status = 'failed';
                                item.lastMessage = `失败 — 文件缺失 (${dCount}/${item.totalImages})`;
                            } else {
                                item.status = 'completed';
                                item.lastMessage = `已完成（确认），共 ${dCount} 张`;
                            }
                        } else {
                            item.status = 'failed';
                            item.lastMessage = '失败 — 超时未收到完成状态';
                        }
                    } catch (e) {
                        item.status = 'failed';
                        item.lastMessage = '失败 — 状态查询异常';
                    }
                }

            } catch (err) {
                if (err.message === '需要登录') {
                    item.status = 'failed';
                    item.lastMessage = '失败 - 需要登录';
                    this.queue.forEach(q => {
                        if (['pending', 'idle', 'paused'].includes(q.status)) {
                            q.status = 'failed';
                            q.lastMessage = '失败 - 需要登录';
                        }
                    });
                    this.stopRequested = true;
                    this.isRunning = false;
                    this.ui.setStatus('需要登录，已停止下载', 'error');
                } else if (err.message === 'quota_exceeded') {
                    item.status = 'failed';
                    item.lastMessage = '失败 - 达到限额';
                    if (!this._quotaExceededHandled) {
                        this._quotaExceededHandled = true;
                        // 标记所有未开始的队列项
                        this.queue.forEach(q => {
                            if (['pending', 'idle', 'paused'].includes(q.status)) {
                                q.status = 'failed';
                                q.lastMessage = '失败 - 达到限额';
                            }
                        });
                        this.stopRequested = true;
                        this.isRunning = false;
                        this.ui.setStatus('已达到下载限额', 'error');
                        if (err.quotaData) {
                            this.ui.showQuotaExceeded(err.quotaData);
                        }
                    }
                } else {
                    item.status = 'failed';
                    item.lastMessage = `失败 — ${err.message || String(err)}`;
                    this.ui.setStatus(`错误：${item.title} - ${item.lastMessage}`, 'error');
                }
            } finally {
                try {
                    this.sse.close(item.id);
                } catch (e) {
                }
                item.endTime = item.endTime || new Date().toISOString();
                this.updateStats();
                this.saveToStorage();
                this.ui.renderQueue(this.queue);
                this.ui.setCurrent(null);
            }
        }

        _waitForFinalStatusBySSE(artworkId, timeoutMs) {
            return new Promise((resolve) => {
                let resolved = false;
                let timer = null;
                let pollTimer = null;

                const finish = (data) => {
                    if (resolved) return;
                    resolved = true;
                    clearTimeout(timer);
                    clearInterval(pollTimer);
                    resolve(data);
                };

                timer = setTimeout(() => finish(null), timeoutMs);

                // 每5秒轮询一次，防止 SSE 事件丢失导致任务卡死
                pollTimer = setInterval(async () => {
                    if (resolved) {
                        clearInterval(pollTimer);
                        return;
                    }
                    try {
                        const status = await Api.getDownloadStatus(String(artworkId));
                        if (status && (status.completed || status.failed)) finish(status);
                    } catch {
                    }
                }, 5000);

                const handler = (data) => {
                    if (data && (data.completed || data.failed || data.cancelled)) {
                        finish(data);
                    } else {
                        const q = this.queue.find(x => x.id === String(artworkId));
                        if (q && data.downloadedCount !== undefined) {
                            q.downloadedCount = data.downloadedCount;
                            this.saveToStorage();
                            this.ui.renderQueue(this.queue);
                        }
                        clearTimeout(timer);
                        timer = setTimeout(() => finish(null), timeoutMs);
                    }
                };
                this.sse.addListener(String(artworkId), handler);
            });
        }

        pause() {
            if (!this.isRunning) return;
            this.isPaused = true;
            this.queue.forEach(q => {
                if (q.status === 'pending') q.status = 'paused';
            });
            this.saveToStorage();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.ui.setStatus('已暂停', 'warning');
        }

        resume() {
            if (!this.isRunning) {
                const concurrent = Math.max(1, parseInt(this.ui.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT);
                return this.start(this.getIntervalMs(), concurrent);
            }
            this.isPaused = false;
            this.queue.forEach(q => {
                if (q.status === 'paused') q.status = 'pending';
            });
            this.saveToStorage();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.ui.setStatus('继续下载', 'info');
        }

        async stopAndClear(force = false) {
            if (!force) {
                if (this.queue.some(q => q.status === 'downloading')) {
            if (!confirm(t('import.confirm.clear-running', '当前有正在下载的作品，是否强制停止并清除？（取消将保留队列）'))) return;
                }
            }
            this.stopRequested = true;
            this.isRunning = false;
            this.isPaused = false;
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.sse.closeAll();
            this.queue.forEach(q => {
                if (q.status === 'downloading' || q.status === 'pending') Api.cancelDownload(q.id).catch(() => {
                });
            });
            this.queue = [];
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
            try {
                this.deleteStorage();
            } catch (e) {
            }
            this.ui.renderQueue(this.queue);
            this.ui.setStatus('已强制清除队列并删除持久化数据', 'info');
        }

        updateStats() {
            this.stats.success = this.queue.filter(q => q.status === 'completed').length;
            this.stats.failed = this.queue.filter(q => q.status === 'failed').length;
            this.stats.active = this.queue.filter(q => q.status === 'downloading').length;
            this.stats.skipped = this.queue.filter(q => q.status === 'skipped').length;
            this.stats.completed = this.stats.success + this.stats.failed + this.stats.skipped;
            this.ui.updateStats(this.stats);
        }

        removeFromQueue(id) {
            const idx = this.queue.findIndex(q => q.id === String(id));
            if (idx === -1) return false;
            if (this.queue[idx].status === 'downloading') return false;
            this.queue.splice(idx, 1);
            this.updateStats();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return true;
        }

        _sleep(ms) {
            return new Promise(r => setTimeout(r, ms));
        }
    }

    /* ========== UI 组件 ========== */
    class UI {
        constructor() {
            this.root = null;
            this.elements = {};
            this._archivePollTimer = null;
            this._archiveCountdownTimer = null;
            this._build();
        }

        _build() {
            const e = document.getElementById('pixiv-batch-downloader-ui');
            if (e) e.remove();

            const container = $el('div', {
                id: 'pixiv-batch-downloader-ui',
                style: {
                    position: 'fixed', top: '120px', right: '20px', zIndex: 10002,
                    background: 'white', border: '2px solid #28a745', borderRadius: '8px',
                    padding: '15px', boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
                    minWidth: '400px', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    maxHeight: '80vh', overflowY: 'auto'
                }
            });

            // 标题行（含收起按钮）
            const titleRow = $el('div', {
                style: {
                    display: 'flex',
                    alignItems: 'center',
                    marginBottom: '15px',
                    borderBottom: '2px solid #eee',
                    paddingBottom: '10px'
                }
            });
            const collapseBtn = $el('button', {
                innerText: '◀',
                title: t('common.action.collapse', '收起'),
                style: {
                    background: 'none',
                    border: '1px solid #ccc',
                    borderRadius: '4px',
                    cursor: 'pointer',
                    fontSize: '12px',
                    padding: '2px 6px',
                    color: '#666',
                    flexShrink: '0'
                }
            });
            const titleText = $el('div', {
                textContent: t('import.title', '🎨 批量导入作品下载器'),
                style: {fontWeight: 'bold', color: '#333', textAlign: 'center', fontSize: '16px', flex: '1'}
            });
            collapseBtn.addEventListener('click', () => this.toggleCollapse());
            titleRow.appendChild(collapseBtn);
            titleRow.appendChild(titleText);
            titleRow.appendChild(buildLangSwitcher());

            // 收起后的悬浮按钮
            const existingFab = document.getElementById('ntab-mini-fab');
            if (existingFab) existingFab.remove();
            const miniFab = $el('button', {
                id: 'ntab-mini-fab',
                innerText: '🎨',
                title: t('import.fab.title', '批量导入作品下载器'),
                style: {
                    display: 'none',
                    position: 'fixed',
                    top: '60px',
                    right: '20px',
                    zIndex: '10003',
                    background: '#28a745',
                    color: 'white',
                    border: 'none',
                    borderRadius: '50%',
                    width: '40px',
                    height: '40px',
                    cursor: 'pointer',
                    fontSize: '18px',
                    boxShadow: '0 2px 8px rgba(0,0,0,0.3)',
                    lineHeight: '40px',
                    textAlign: 'center',
                    padding: '0'
                }
            });
            miniFab.addEventListener('click', () => this.toggleCollapse());
            document.body.appendChild(miniFab);

            const status = $el('div', {
                id: 'batch-status',
                innerText: t('common.status.ready', '准备就绪'),
                style: {marginBottom: '10px', color: '#666', fontSize: '12px', textAlign: 'center'}
            });
            const stats = $el('div', {
                id: 'batch-stats',
                innerText: t('common.stats.summary', '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}', {
                    pending: 0,
                    success: 0,
                    failed: 0,
                    active: 0,
                    skipped: 0
                }),
                style: {
                    marginBottom: '10px',
                    color: '#007bff',
                    fontSize: '12px',
                    textAlign: 'center',
                    fontWeight: 'bold'
                }
            });

            const inputSection = $el('div', {style: {marginBottom: '15px'}});
            const textarea = $el('textarea', {
                id: 'ntab-data-input',
                placeholder: t('import.input.placeholder', '粘贴作品链接列表，兼容 One-Tab，N-Tab 等标签页管理插件导出格式...'),
                style: {
                    width: '100%',
                    height: '120px',
                    marginBottom: '10px',
                    padding: '8px',
                    border: '1px solid #ddd',
                    borderRadius: '4px',
                    fontSize: '12px',
                    resize: 'vertical'
                }
            });
            inputSection.appendChild(textarea);
            const formatNotice = $el('div', {
                html: `<div style="font-w1718eight:bold;margin-bottom:4px;">${t('import.format.title', '导入格式：')}<span style="font-family:monospace;background:#fff;border-radius:4px;padding:1px 6px;">url | title</span></div><div>${t('import.format.example', '每行一条，例如：')}<span style="font-family:monospace;background:#fff;border-radius:4px;padding:1px 6px;">https://www.pixiv.net/artworks/12345678 | ${t('import.format.example-title', '示例标题')}</span></div><div>${t('import.format.fetch-title', '标题可留空；下载前会自动获取真实标题。')} ${t('import.format.compatible', '兼容 One-Tab，N-Tab 等标签页管理插件导出格式。')}</div><div>${t('import.format.reimport', '也兼容下方“导出下载列表”“导出未下载列表”按钮生成的作品列表，可直接重新导入。')}</div>`,
                style: {
                    marginBottom: '12px',
                    padding: '10px 12px',
                    border: '1px solid #ffb84d',
                    background: '#fff4db',
                    borderRadius: '8px',
                    color: '#8a5100',
                    fontSize: '12px',
                    lineHeight: '1.6'
                }
            });
            inputSection.appendChild(formatNotice);

            const settings = $el('div', {style: {marginBottom: '15px'}});
            settings.innerHTML = `
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.interval', '作品间隔:')}</label>
                    <input type="number" id="download-interval" value="${CONFIG.DEFAULT_INTERVAL}" min="0" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="interval-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">s</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.image-delay', '图片间隔:')}</label>
                    <input type="number" id="image-delay" value="0" min="0" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="image-delay-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">ms</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.concurrent', '最大并发数:')}</label>
                    <input type="number" id="max-concurrent" value="${CONFIG.DEFAULT_CONCURRENT}" min="1" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.skip-history', '跳过历史下载:')}</label>
                    <input type="checkbox" id="skip-history" style="width: 16px; height: 16px;">
                </div>
                <div id="verify-history-files-row" style="display: none; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.verify-history-files', '实际目录检测:')}</label>
                    <span title="${t('common.option.verify-history-files.tooltip', VERIFY_HISTORY_FILES_TOOLTIP)}" style="display:inline-flex;align-items:center;justify-content:center;width:14px;height:14px;border:1px solid #999;border-radius:50%;color:#666;font-size:10px;font-weight:700;line-height:1;cursor:help;user-select:none;margin-right:8px;">?</span>
                    <input type="checkbox" id="verify-history-files" style="width: 16px; height: 16px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px; color: #d63384;">${t('import.setting.r18-only', '仅下载R18作品:')}</label>
                    <input type="checkbox" id="r18-only" style="width: 16px; height: 16px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.bookmark', '下载后自动收藏:')}</label>
                    <input type="checkbox" id="bookmark-after-dl" style="width: 16px; height: 16px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('import.setting.server', '服务器地址:')}</label>
                    <input type="text" id="server-base-url" value="${serverBase}" placeholder="http://localhost:6999" style="flex: 1; padding: 4px; border: 1px solid #ddd; border-radius: 4px; font-size: 12px;">
                </div>
            `;

            const buttonContainer = $el('div', {
                style: {
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '8px',
                    marginBottom: '10px'
                }
            });
            const buttons = [
                {
                    id: 'parse-btn',
                    text: t('import.button.parse', '📋 导入并加入队列'),
                    bgColor: '#007bff',
                    onClick: () => this.manager.parseAndSetFromText(this.elements.textarea.value)
                },
                {id: 'start-btn', text: t('import.button.start', '🚀 开始批量下载'), bgColor: '#28a745', onClick: () => this.handleStart()},
                {
                    id: 'retry-failed-btn',
                    text: t('import.button.retry', '🔁 重新下载失败的作品'),
                    bgColor: '#17a2b8',
                    onClick: () => this.handleRetryFailed()
                },
                {
                    id: 'pause-btn',
                    text: t('common.button.pause', '⏸️ 暂停下载'),
                    bgColor: '#ffc107',
                    onClick: () => this.handlePause(),
                    disabled: true
                },
                {
                    id: 'export-all-btn',
                    text: t('import.button.export-all', '📤 导出下载列表'),
                    bgColor: '#007bff',
                    onClick: () => this.handleExportAll()
                },
                {
                    id: 'export-failed-btn',
                    text: t('import.button.export-failed', '📋 导出未下载列表'),
                    bgColor: '#6610f2',
                    onClick: () => this.handleExportFailed()
                },
                {id: 'clear-btn', text: t('import.button.clear', '🗑️ 清除队列'), bgColor: '#6c757d', onClick: () => this.handleClear()}
            ];
            buttons.forEach(btnConfig => {
                const button = $el('button', {
                    id: btnConfig.id,
                    innerText: btnConfig.text,
                    style: {
                        width: '100%',
                        background: btnConfig.bgColor,
                        color: btnConfig.bgColor === '#ffc107' ? 'black' : 'white',
                        border: 'none',
                        padding: '10px',
                        borderRadius: '5px',
                        cursor: 'pointer',
                        fontSize: '14px'
                    }
                });
                button.disabled = !!btnConfig.disabled;
                button.addEventListener('click', btnConfig.onClick);
                buttonContainer.appendChild(button);
            });

            const currentDownload = $el('div', {
                id: 'current-download',
                style: {
                    marginBottom: '10px',
                    padding: '8px',
                    background: '#f8f9fa',
                    borderRadius: '5px',
                    borderLeft: '4px solid #007bff',
                    fontSize: '11px'
                }
            });
            currentDownload.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${t('common.current.none', '无')}`;

            const queueContainer = $el('div', {
                id: 'queue-container',
                style: {
                    maxHeight: '250px',
                    overflowY: 'auto',
                    border: '1px solid #ddd',
                    borderRadius: '5px',
                    padding: '10px',
                    marginBottom: '10px',
                    background: '#f8f9fa',
                    fontSize: '11px'
                }
            });

            // 配额栏（多人模式启用时显示）
            const quotaBar = $el('div', {
                id: 'pixiv-ntab-quota-bar',
                style: {
                    display: 'none', marginBottom: '10px', padding: '6px 8px',
                    background: '#f8f9fa', borderRadius: '5px', fontSize: '11px', color: '#555'
                }
            });

            // 压缩包下载卡片
            const archiveCard = $el('div', {
                id: 'pixiv-ntab-archive-card',
                style: {
                    display: 'none', marginBottom: '10px', padding: '10px',
                    background: '#fff8e1', border: '2px solid #ffc107',
                    borderRadius: '5px', fontSize: '12px'
                }
            });

            container.appendChild(titleRow);
            container.appendChild(status);
            container.appendChild(stats);
            container.appendChild(inputSection);
            container.appendChild(settings);
            container.appendChild(buttonContainer);
            container.appendChild(quotaBar);
            container.appendChild(archiveCard);
            container.appendChild(currentDownload);
            container.appendChild(queueContainer);
            document.body.appendChild(container);
            this.root = container;

            this.elements = {
                textarea: textarea,
                interval: container.querySelector('#download-interval'),
                intervalUnitBtn: container.querySelector('#interval-unit-btn'),
                imageDelay: container.querySelector('#image-delay'),
                imageDelayUnitBtn: container.querySelector('#image-delay-unit-btn'),
                concurrent: container.querySelector('#max-concurrent'),
                skipHistory: container.querySelector('#skip-history'),
                verifyHistoryFiles: container.querySelector('#verify-history-files'),
                verifyHistoryFilesRow: container.querySelector('#verify-history-files-row'),
                r18Only: container.querySelector('#r18-only'),
                bookmarkAfterDl: container.querySelector('#bookmark-after-dl'),
                serverBaseInput: container.querySelector('#server-base-url'),
                parseBtn: container.querySelector('#parse-btn'),
                startBtn: container.querySelector('#start-btn'),
                pauseBtn: container.querySelector('#pause-btn'),
                clearBtn: container.querySelector('#clear-btn'),
                status: status,
                stats: stats,
                queueContainer: queueContainer,
                currentDownload: currentDownload
            };

            // 恢复已存单位并绑定切换事件
            const savedUnit = GM_getValue(CONFIG.INTERVAL_UNIT_KEY, 's');
            if (this.elements.intervalUnitBtn) {
                this.elements.intervalUnitBtn.textContent = savedUnit;
                this.elements.intervalUnitBtn.addEventListener('click', () => {
                    const curUnit = GM_getValue(CONFIG.INTERVAL_UNIT_KEY, 's');
                    const cur = parseFloat(this.elements.interval.value) || 0;
                    if (curUnit === 's') {
                        GM_setValue(CONFIG.INTERVAL_UNIT_KEY, 'ms');
                        this.elements.interval.value = Math.round(cur * 1000);
                        this.elements.intervalUnitBtn.textContent = 'ms';
                    } else {
                        GM_setValue(CONFIG.INTERVAL_UNIT_KEY, 's');
                        this.elements.interval.value = +(cur / 1000).toFixed(3);
                        this.elements.intervalUnitBtn.textContent = 's';
                    }
                });
            }

            const savedImgUnit = GM_getValue(CONFIG.IMAGE_DELAY_UNIT_KEY, 'ms');
            if (this.elements.imageDelayUnitBtn) {
                this.elements.imageDelayUnitBtn.textContent = savedImgUnit;
                this.elements.imageDelayUnitBtn.addEventListener('click', () => {
                    const curUnit = GM_getValue(CONFIG.IMAGE_DELAY_UNIT_KEY, 'ms');
                    const cur = parseFloat(this.elements.imageDelay.value) || 0;
                    if (curUnit === 's') {
                        GM_setValue(CONFIG.IMAGE_DELAY_UNIT_KEY, 'ms');
                        this.elements.imageDelay.value = Math.round(cur * 1000);
                        this.elements.imageDelayUnitBtn.textContent = 'ms';
                    } else {
                        GM_setValue(CONFIG.IMAGE_DELAY_UNIT_KEY, 's');
                        this.elements.imageDelay.value = +(cur / 1000).toFixed(3);
                        this.elements.imageDelayUnitBtn.textContent = 's';
                    }
                });
            }
        }

        bindManager(manager) {
            this.manager = manager;
            this.elements.skipHistory.checked = manager.skipHistory;
            this.elements.verifyHistoryFiles.checked = manager.verifyHistoryFiles;
            this.elements.r18Only.checked = manager.r18Only;
            this.elements.bookmarkAfterDl.checked = manager.bookmark;
            this.elements.skipHistory.addEventListener('change', (e) => {
                this.manager.setSkipHistory(e.target.checked);
                this.updateSkipHistoryVisibility(e.target.checked);
            });
            this.elements.verifyHistoryFiles.addEventListener('change', (e) => {
                this.manager.setVerifyHistoryFiles(e.target.checked);
            });
            this.elements.r18Only.addEventListener('change', (e) => {
                this.manager.setR18Only(e.target.checked);
            });
            this.elements.bookmarkAfterDl.addEventListener('change', (e) => {
                this.manager.setBookmark(e.target.checked);
            });
            if (this.elements.serverBaseInput) {
                this.elements.serverBaseInput.addEventListener('change', (e) => {
                    serverBase = e.target.value.trim().replace(/\/$/, '') || 'http://localhost:6999';
                    GM_setValue(KEY_SERVER_URL, serverBase);
                });
            }
            this.updateSkipHistoryVisibility(manager.skipHistory);
        }

        updateSkipHistoryVisibility(enabled) {
            if (!this.elements.verifyHistoryFilesRow) return;
            this.elements.verifyHistoryFilesRow.style.display = enabled ? 'flex' : 'none';
        }

        async handleStart() {
            const concurrent = Math.max(1, parseInt(this.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT);
            this.manager.start(this.manager.getIntervalMs(), concurrent);
        }

        handlePause() {
            if (!this.manager) return;
            if (!this.manager.isPaused) {
                this.manager.pause();
                this.elements.pauseBtn.innerText = t('common.button.resume', '▶️ 继续下载');
            } else {
                this.manager.resume();
                this.elements.pauseBtn.innerText = t('common.button.pause', '⏸️ 暂停下载');
            }
        }

        handleRetryFailed() {
            if (!this.manager) return;
            const failedItems = this.manager.queue.filter(q => q.status === 'failed');
            if (failedItems.length === 0) {
                alert(t('import.alert.no-failed', '当前没有失败的作品！'));
                return;
            }
            failedItems.forEach(q => {
                q.status = 'pending';
                q.lastMessage = '';
                q.startTime = null;
                q.endTime = null;
            });
            this.manager.saveToStorage();
            this.renderQueue(this.manager.queue);
            this.handleStart();
        }

        async handleClear() {
            if (confirm(t('import.confirm.clear', '确认强制清除队列？'))) await this.manager.stopAndClear(true);
        }

        renderQueue(queue) {
            const node = this.elements.queueContainer;
            node.innerHTML = `<div style="font-weight: bold; margin-bottom: 5px;">${t('common.queue.label', '下载队列:')}</div>`;
            if (!queue || queue.length === 0) {
                node.innerHTML += `<div style="color: #666; text-align: center;">${t('common.queue.empty', '队列为空')}</div>`;
                return;
            }
            for (const q of queue) {
                const item = $el('div', {
                    style: {
                        padding: '5px',
                        marginBottom: '3px',
                        background: 'white',
                        fontSize: '10px',
                        borderLeft: `3px solid ${this._colorByStatus(q.status)}`
                    }
                });
                const progressHtml = this._createProgressHtml(q);
                const desc = translateStatusText(q.lastMessage || this._statusText(q.status));
                const canRemove = q.status !== 'downloading';
                const removeBtn = canRemove
                    ? `<button data-remove-id="${q.id}" title="${t('common.action.remove', '从队列移除')}" style="background:none;border:none;color:#aaa;cursor:pointer;font-size:11px;padding:1px 2px;line-height:1;">✕</button>`
                    : '';
                const linkBtn = `<a href="https://www.pixiv.net/artworks/${q.id}" target="_blank" title="${t('common.action.open-artwork', '打开作品页面')}" style="color:#007bff;font-size:11px;padding:1px 2px;text-decoration:none;line-height:1;">🔗</a>`;
                item.innerHTML = `<div style="display:flex;justify-content:space-between;align-items:center;"><strong style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:4px;">${escapeHtml(q.title)}</strong><span style="display:flex;gap:1px;flex-shrink:0;">${linkBtn}${removeBtn}</span></div><div>ID: ${q.id} | <span style="color:${this._colorByStatus(q.status)};font-weight:bold;">${escapeHtml(desc)}</span></div>${progressHtml}`;
                node.appendChild(item);
            }
            node.onclick = (e) => {
                const btn = e.target.closest('[data-remove-id]');
                if (btn) { e.stopPropagation(); this.manager.removeFromQueue(btn.dataset.removeId); }
            };
        }

        _createProgressHtml(q) {
            if (q.totalImages <= 0) return '';
            const downloadedCount = q.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / q.totalImages) * 100), 100);
            return `<div style="margin-top: 3px;"><div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;"><span>${t('common.progress.downloaded', '已下载: {count}/{total}', { count: downloadedCount, total: q.totalImages })}</span><span>${progressPercent}%</span></div><div style="width: 100%; height: 4px; background: #e0e0e0; border-radius: 2px; overflow: hidden;"><div style="height: 100%; background: #007bff; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }

        setStatus(msg, type = 'info') {
            this.elements.status.innerText = translateStatusText(msg);
            this.elements.status.style.color = {
                'info': '#007bff',
                'success': '#28a745',
                'error': '#dc3545',
                'warning': '#ffc107'
            }[type] || '#666';
        }

        updateStats(stats) {
            const pendingCount = this.manager.queue.filter(q => q.status === 'pending' || q.status === 'idle' || q.status === 'paused').length;
            this.elements.stats.textContent = t('common.stats.summary', '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}', {
                pending: pendingCount,
                success: stats.success,
                failed: stats.failed,
                active: stats.active,
                skipped: stats.skipped
            });
        }

        updateButtonsState(isRunning, isPaused) {
            this.elements.startBtn.disabled = isRunning;
            this.elements.pauseBtn.disabled = !isRunning;
            this.elements.pauseBtn.innerText = isPaused
                ? t('common.button.resume', '▶️ 继续下载')
                : t('common.button.pause', '⏸️ 暂停下载');
        }

        setCurrent(item) {
            const container = this.elements.currentDownload;
            if (!item) {
                container.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${t('common.current.none', '无')}`;
                return;
            }
            const progressHtml = this._createCurrentProgressHtml(item);
            container.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${item.title} (ID: ${item.id})${progressHtml}`;
        }

        _createCurrentProgressHtml(item) {
            if (item.totalImages <= 0) return '';
            const downloadedCount = item.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / item.totalImages) * 100), 100);
            return `<div style="margin-top: 5px;"><div style="display: flex; justify-content: space-between; font-size: 10px; margin-bottom: 3px;"><span>${t('common.progress.current', '已下载 {count} 张 / 共 {total} 张', { count: downloadedCount, total: item.totalImages })}</span><span>${progressPercent}%</span></div><div style="width: 100%; height: 6px; background: #e0e0e0; border-radius: 3px; overflow: hidden;"><div style="height: 100%; background: #28a745; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }

        toggleCollapse() {
            this._collapsed = !this._collapsed;
            const fab = document.getElementById('ntab-mini-fab');
            if (this._collapsed) {
                this.root.style.display = 'none';
                if (fab) fab.style.display = 'block';
            } else {
                this.root.style.display = 'block';
                if (fab) fab.style.display = 'none';
                document.dispatchEvent(new CustomEvent('pixiv_panel_active', {detail: 'ntab'}));
            }
        }

        handleExportAll() {
            if (!this.manager.queue || this.manager.queue.length === 0) {
                alert(t('import.alert.queue-empty-export', '队列为空，无内容可导出'));
                return;
            }
            const lines = this.manager.queue.map(item => `https://www.pixiv.net/artworks/${item.id} | ${item.title}`);
            this._downloadTxt(lines.join('\n'), 'pixiv_ntab_all_list.txt');
            this.setStatus(`已导出 ${lines.length} 个作品`, 'success');
        }

        handleExportFailed() {
            const items = this.manager.queue.filter(q => q.status !== 'completed');
            if (items.length === 0) {
                alert(t('import.alert.no-undownloaded', '没有未下载的作品'));
                return;
            }
            const lines = items.map(item => `https://www.pixiv.net/artworks/${item.id} | ${item.title}`);
            this._downloadTxt(lines.join('\n'), 'pixiv_ntab_undownloaded_list.txt');
            this.setStatus(`已导出 ${lines.length} 个未下载作品`, 'success');
        }

        _downloadTxt(content, filename) {
            const blob = new Blob([content], {type: 'text/plain'});
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }

        _colorByStatus(status) {
            return {
                'completed': '#28a745',
                'downloading': '#007bff',
                'failed': '#dc3545',
                'paused': '#6c757d',
                'skipped': '#ffa500'
            }[status] || '#6c757d';
        }

        _statusText(status) {
            return {
                'idle': t('common.status.idle', '等待中'),
                'pending': t('common.status.pending', '等待中'),
                'downloading': t('common.status.downloading', '下载中'),
                'completed': t('common.status.completed', '已完成'),
                'failed': t('common.status.failed', '失败'),
                'paused': t('common.status.paused', '暂停中'),
                'skipped': t('common.status.skipped', '已跳过')
            }[status] || status;
        }

        // ---- 配额 UI 方法 ----

        updateQuotaBar(info) {
            const bar = document.getElementById('pixiv-ntab-quota-bar');
            if (!bar || !info || !info.enabled) return;
            const pct = Math.min(100, Math.round(info.artworksUsed / info.maxArtworks * 100));
            const color = pct >= 90 ? '#dc3545' : pct >= 70 ? '#ffc107' : '#28a745';
            const resetTxt = info.resetSeconds > 0
                ? t('common.quota.reset', ' | 重置剩余：{time}', { time: this._fmtSeconds(info.resetSeconds) }) : '';
            bar.style.display = 'block';
            bar.innerHTML = `<div style="display:flex;align-items:center;gap:6px;">
              <span style="white-space:nowrap;">${t('common.quota.summary', '配额：{used}/{max} 个作品', { used: info.artworksUsed, max: info.maxArtworks })}</span>
              <div style="flex:1;height:5px;background:#e0e0e0;border-radius:3px;overflow:hidden;">
                <div style="height:100%;width:${pct}%;background:${color};border-radius:3px;"></div>
              </div>
              <span style="white-space:nowrap;color:#888;font-size:10px;">${pct}%${resetTxt}</span>
            </div>`;
        }

        showQuotaExceeded(data, title = '已达到下载限额') {
            clearInterval(this._archivePollTimer);
            clearInterval(this._archiveCountdownTimer);
            const card = document.getElementById('pixiv-ntab-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${title === '已达到下载限额' ? t('common.archive.limit-title', '已达到下载限额') : title}</div>
              <div id="pixiv-ntab-ac-status" style="font-size:11px;color:#666;">${t('common.archive.preparing', '正在打包已下载文件，请稍候...')}</div>
              <div id="pixiv-ntab-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ntab-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;
            const token = data.archiveToken;
            const expireSec = data.archiveExpireSeconds || 3600;
            this._pollArchive(token, expireSec);
        }

        restoreArchiveCard(token, expireSec, ready) {
            clearInterval(this._archivePollTimer);
            clearInterval(this._archiveCountdownTimer);
            const card = document.getElementById('pixiv-ntab-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${t('common.archive.restore-title', '已有未下载的压缩包')}</div>
              <div id="pixiv-ntab-ac-status" style="font-size:11px;color:#666;"></div>
              <div id="pixiv-ntab-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ntab-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;
            if (ready) {
                this._activateArchiveDl(token, expireSec);
            } else {
                document.getElementById('pixiv-ntab-ac-status').textContent = t('common.archive.preparing', '正在打包已下载文件，请稍候...');
                this._pollArchive(token, expireSec);
            }
        }

        _pollArchive(token, expireSec) {
            clearInterval(this._archivePollTimer);
            this._archivePollTimer = setInterval(async () => {
                const data = await Api.pollArchiveStatus(token);
                if (data.status === 'ready') {
                    clearInterval(this._archivePollTimer);
                    this._activateArchiveDl(token, data.expireSeconds || expireSec);
                } else if (data.status === 'expired') {
                    clearInterval(this._archivePollTimer);
                    const expired = document.getElementById('pixiv-ntab-ac-expired');
                    const status = document.getElementById('pixiv-ntab-ac-status');
                    if (expired) expired.style.display = 'block';
                    if (status) status.textContent = '';
                } else if (data.status === 'empty') {
                    clearInterval(this._archivePollTimer);
                    const status = document.getElementById('pixiv-ntab-ac-status');
                    if (status) status.textContent = t('common.archive.empty', '暂无可打包文件');
                }
            }, 2000);
        }

        _activateArchiveDl(token, expireSec) {
            clearInterval(this._archiveCountdownTimer);
            const statusEl = document.getElementById('pixiv-ntab-ac-status');
            const dlEl = document.getElementById('pixiv-ntab-ac-dl');
            if (statusEl) statusEl.textContent = t('common.archive.ready', '压缩包已就绪：');
            if (dlEl) {
                dlEl.style.display = 'block';
                const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
                dlEl.innerHTML = `<a href="${CONFIG.ARCHIVE_DOWNLOAD_BASE}/${token}" download="${filename}"
                  style="display:inline-block;padding:5px 12px;background:#28a745;color:white;
                         border-radius:4px;text-decoration:none;font-size:12px;font-weight:bold;">
                  ${t('common.archive.download-link', '下载压缩包')}
                </a>
                <span id="pixiv-ntab-ac-countdown" style="font-size:10px;color:#888;margin-left:8px;"></span>`;
                let remaining = Math.max(0, parseInt(expireSec));
                const el = () => document.getElementById('pixiv-ntab-ac-countdown');
                if (el()) el().textContent = t('common.archive.validity', '有效期：{time}', { time: this._fmtSeconds(remaining) });
                this._archiveCountdownTimer = setInterval(() => {
                    remaining--;
                    if (remaining <= 0) {
                        clearInterval(this._archiveCountdownTimer);
                        const expired = document.getElementById('pixiv-ntab-ac-expired');
                        if (dlEl) dlEl.style.display = 'none';
                        if (expired) expired.style.display = 'block';
                    } else {
                        if (el()) el().textContent = t('common.archive.validity', '有效期：{time}', { time: this._fmtSeconds(remaining) });
                    }
                }, 1000);
            }
        }

        _fmtSeconds(s) {
            s = Math.max(0, Math.round(s));
            const h = Math.floor(s / 3600);
            const m = Math.floor((s % 3600) / 60);
            const sec = s % 60;
            if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm ' + String(sec).padStart(2, '0') + 's';
            return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
        }
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        })[c]);
    }

    const ui = new UI();
    const manager = new DownloadManager(ui);
    ui.bindManager(manager);
    manager.loadFromStorage();
    ui.renderQueue(manager.queue);
    ui.setStatus('准备就绪');

    PixivUserscriptI18n.onChange(() => {
        if (ui.root) {
            const textareaValue = ui.elements && ui.elements.textarea ? ui.elements.textarea.value : '';
            const collapsed = ui._collapsed;
            ui.root.remove();
            const fab = document.getElementById('ntab-mini-fab');
            if (fab) fab.remove();
            ui._build();
            ui.bindManager(manager);
            if (ui.elements && ui.elements.textarea) {
                ui.elements.textarea.value = textareaValue;
            }
            ui.renderQueue(manager.queue);
            ui.updateStats(manager.stats);
            ui.updateButtonsState(manager.isRunning, manager.isPaused);
            ui.setCurrent(manager.queue.find(item => item.status === 'downloading') || null);
            ui._collapsed = !collapsed;
            ui.toggleCollapse();
            if (quotaInfo && quotaInfo.enabled) {
                ui.updateQuotaBar(quotaInfo);
            }
        }
    });
    PixivUserscriptI18n.enrichFromBackend(serverBase);

    // 首次启动提示
    checkExternalServerNotice();

    // 第一步：检查登录状态，通过后再初始化配额
    let quotaResetTimer = null;

    function startQuotaResetCountdown() {
        clearInterval(quotaResetTimer);
        if (quotaInfo.resetSeconds <= 0) return;
        quotaResetTimer = setInterval(() => {
            if (quotaInfo.resetSeconds > 0) quotaInfo.resetSeconds--;
            ui.updateQuotaBar(quotaInfo);
            if (quotaInfo.resetSeconds <= 0) clearInterval(quotaResetTimer);
        }, 1000);
    }

    (async () => {
        const authed = await checkLoginStatus();
        if (!authed) {
            ui.setStatus('需要登录，请登录后刷新页面', 'error');
            return;
        }
        Api.initQuota().then(data => {
            if (data && data.enabled) {
                quotaInfo = {
                    enabled: true,
                    artworksUsed: data.artworksUsed || 0,
                    maxArtworks: data.maxArtworks || 50,
                    resetSeconds: data.resetSeconds || 0
                };
                ui.updateQuotaBar(quotaInfo);
                startQuotaResetCountdown();
                // 恢复已有压缩包
                if (data.archive && data.archive.token) {
                    const ready = data.archive.status === 'ready';
                    ui.restoreArchiveCard(data.archive.token, data.archive.expireSeconds || 3600, ready);
                }
            }
        });
    })();

    GM_registerMenuCommand(t('import.menu.open', '打开 Pixiv 批量导入作品下载器'), () => {
        const root = document.getElementById('pixiv-batch-downloader-ui');
        if (root) {
            root.style.display = 'block';
            window.scrollTo(0, 0);
        } else {
            location.reload();
        }
    });

    // 页面类型判断
    const isUserPage = (href) => /\/users\/\d+/.test(href);
    const isHomePage = (href) => /^https:\/\/www\.pixiv\.net\/(en\/)?$/.test(href);

    // 根据页面类型决定初始折叠状态：主页默认展开 N-Tab，其他页面默认收起
    (function watchPages() {
        function updateNTabVisibility() {
            const shouldExpand = isHomePage(location.href);
            if (shouldExpand && ui._collapsed) {
                ui.toggleCollapse();
            } else if (!shouldExpand && !ui._collapsed) {
                ui.toggleCollapse();
            }
        }
        updateNTabVisibility();
        let lastHref = location.href;
        setInterval(() => {
            if (location.href !== lastHref) {
                lastHref = location.href;
                updateNTabVisibility();
            }
        }, 500);
    })();

    // 当其他面板展开时，自动收起本面板
    document.addEventListener('pixiv_panel_active', e => {
        if ((e.detail === 'user' || e.detail === 'page') && !ui._collapsed) {
            ui.toggleCollapse();
        }
    });
})();
