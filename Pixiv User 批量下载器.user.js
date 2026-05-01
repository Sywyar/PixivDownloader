// ==UserScript==
// @name         Pixiv User 批量下载器
// @namespace    http://tampermonkey.net/
// @version      2.0.9
// @description  适配 Pixiv 用户页面，自动获取所有作品 ID，对接本地 Java 后端。
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
        get BACKEND_URL() { return serverBase + '/api/download/pixiv'; },
        get STATUS_URL() { return serverBase + '/api/download/status'; },
        get CANCEL_URL() { return serverBase + '/api/download/cancel'; },
        get SSE_BASE() { return serverBase + '/api/sse/download'; },
        get CHECK_DOWNLOADED_URL() { return serverBase + '/api/downloaded'; },
        get QUOTA_INIT_URL() { return serverBase + '/api/quota/init'; },
        get ARCHIVE_STATUS_BASE() { return serverBase + '/api/archive/status'; },
        get ARCHIVE_DOWNLOAD_BASE() { return serverBase + '/api/archive/download'; },
        DEFAULT_INTERVAL: 2,
        DEFAULT_CONCURRENT: 1,
        STATUS_TIMEOUT_MS: 300000,

        // 全局持久化 Keys
        KEY_SKIP_HISTORY: 'pixiv_global_skip_history',
        KEY_VERIFY_HISTORY_FILES: 'pixiv_global_verify_history_files',
        KEY_R18_ONLY: 'pixiv_global_r18_only',
        KEY_INTERVAL: 'pixiv_global_interval',
        KEY_INTERVAL_UNIT: 'pixiv_global_interval_unit',
        KEY_IMAGE_DELAY: 'pixiv_global_image_delay',
        KEY_IMAGE_DELAY_UNIT: 'pixiv_global_image_delay_unit',
        KEY_CONCURRENT: 'pixiv_global_concurrent',
        KEY_USER_UUID: 'pixiv_user_uuid',
        KEY_BOOKMARK: 'pixiv_global_bookmark'
    };

    // ====== 配额状态 ======
    let quotaInfo = { enabled: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0 };
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
            'user.title': '🖼️ Pixiv User Batch Downloader',
            'user.fab.title': 'User Batch Downloader',
            'user.setting.interval': 'Artwork interval:',
            'user.setting.image-delay': 'Image delay:',
            'user.setting.concurrent': 'Max concurrency:',
            'user.setting.skip-history': 'Skip download history',
            'user.setting.r18-only': 'R18 only',
            'user.setting.verify-history-files': 'Verify saved directory',
            'user.setting.bookmark': 'Auto-bookmark after download',
            'user.setting.server': 'Server URL:',
            'user.button.fetch-all': '📥 Fetch all artworks by this author',
            'user.button.fetch-new': '🆕 Fetch and download only new artworks',
            'user.button.start': '🚀 Start Batch Download',
            'user.button.retry': '🔁 Retry Failed Artworks',
            'user.button.export': '📤 Export Download List',
            'user.button.export-failed': '📋 Export Undownloaded List',
            'user.button.clear': '🗑️ Clear Queue',
            'user.title.user-id': '🖼️ User: {id}',
            'user.title.user-name': '🖼️ User: {name} ({id})',
            'user.alert.backend-unavailable': 'Backend is unavailable. If you use a non-localhost server, replace @connect YOUR_SERVER_HOST in the userscript header as described in README.',
            'user.alert.no-user-id': 'User ID was not detected',
            'user.alert.no-failed': 'There are no failed artworks right now.',
            'user.alert.no-undownloaded': 'There are no undownloaded artworks.',
            'user.alert.queue-empty-export': 'Queue is empty. Nothing to export.',
            'user.confirm.clear': 'Force clear the queue?',
            'user.status.started': 'Batch download started ({concurrent} concurrent, {interval}ms interval)',
            'user.status.finished': 'Batch download finished',
            'user.status.finished-packing': 'Batch download finished. Preparing archive...',
            'user.status.fetching-meta': 'Fetching info: {id}',
            'user.status.downloading': 'Downloading: {title}',
            'user.status.skipped-existing': 'Skipped: {title} (already downloaded)',
            'user.status.failed-missing': 'Failed: {title} (missing files)',
            'user.status.completed': 'Completed: {title}',
            'user.status.failed': 'Failed: {title}',
            'user.status.need-login-stop': 'Login required. Download stopped',
            'user.status.quota-exceeded': 'Download limit reached',
            'user.status.error': 'Error: {title}',
            'user.status.pause-waiting': 'Pausing... waiting for {count} active task(s)',
            'user.status.paused': 'Paused',
            'user.status.resumed': 'Resumed',
            'user.status.cleared': 'Queue was force-cleared',
            'user.status.fetching-list': 'Fetching artwork list...',
            'user.status.no-artworks': 'This user has no artworks',
            'user.status.fetch-success': 'Fetched {total} artworks, {added} added',
            'user.status.fetch-failed': 'Failed to fetch artwork list: {message}',
            'user.status.exported-undownloaded': 'Exported {count} undownloaded artworks',
            'user.status.exported': 'Export succeeded',
            'user.status.need-login-refresh': 'Login required. Please log in and refresh the page',
            'user.menu.open': 'Force open the download panel'
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
            'user.title': '🖼️ Pixiv User 批量下载器',
            'user.fab.title': 'User批量下载器',
            'user.setting.interval': '作品间隔:',
            'user.setting.image-delay': '图片间隔:',
            'user.setting.concurrent': '最大并发数:',
            'user.setting.skip-history': '跳过历史下载',
            'user.setting.r18-only': '仅下载R18作品',
            'user.setting.verify-history-files': '实际目录检测',
            'user.setting.bookmark': '下载后自动收藏',
            'user.setting.server': '服务器地址:',
            'user.button.fetch-all': '📥 获取该画师所有作品',
            'user.button.fetch-new': '🆕 获取并仅下载新作品',
            'user.button.start': '🚀 开始批量下载',
            'user.button.retry': '🔁 重新下载失败的作品',
            'user.button.export': '📤 导出下载列表',
            'user.button.export-failed': '📋 导出未下载列表',
            'user.button.clear': '🗑️ 清除队列',
            'user.title.user-id': '🖼️ User: {id}',
            'user.title.user-name': '🖼️ User: {name}({id})',
            'user.alert.backend-unavailable': '后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址',
            'user.alert.no-user-id': '未检测到用户ID',
            'user.alert.no-failed': '当前没有失败的作品！',
            'user.alert.no-undownloaded': '没有未下载的作品',
            'user.alert.queue-empty-export': '队列为空，无内容可导出',
            'user.confirm.clear': '确认强制清除队列？',
            'user.status.started': '开始下载 (并发:{concurrent}, 间隔:{interval}ms)',
            'user.status.finished': '批量下载结束',
            'user.status.finished-packing': '批量下载结束，正在打包文件...',
            'user.status.fetching-meta': '获取信息：{id}',
            'user.status.downloading': '下载中：{title}',
            'user.status.skipped-existing': '跳过：{title}（已下载）',
            'user.status.failed-missing': '失败：{title} (文件缺失)',
            'user.status.completed': '完成：{title}',
            'user.status.failed': '失败：{title}',
            'user.status.need-login-stop': '需要登录，已停止下载',
            'user.status.quota-exceeded': '已达到下载限额',
            'user.status.error': '错误：{title}',
            'user.status.pause-waiting': '正在暂停... (等待 {count} 个当前任务完成)',
            'user.status.paused': '已暂停',
            'user.status.resumed': '继续下载',
            'user.status.cleared': '已强制清除队列',
            'user.status.fetching-list': '正在获取作品列表...',
            'user.status.no-artworks': '该用户没有作品',
            'user.status.fetch-success': '获取成功：共 {total} 个作品，新增 {added} 个',
            'user.status.fetch-failed': '获取列表失败: {message}',
            'user.status.exported-undownloaded': '已导出 {count} 个未下载作品',
            'user.status.exported': '导出成功',
            'user.status.need-login-refresh': '需要登录，请登录后刷新页面',
            'user.menu.open': '强制打开下载面板'
        }
    });

    const t = (key, fallback, args) => PixivUserscriptI18n.t(key, fallback, args);

    const STATUS_TRANSLATORS = [
        [/^队列为空$/, () => t('common.queue.empty', '队列为空')],
        [/^开始下载 \(并发:(\d+), 间隔:(\d+)ms\)$/, (_, concurrent, interval) => t('user.status.started', '开始下载 (并发:{concurrent}, 间隔:{interval}ms)', { concurrent: concurrent, interval: interval })],
        [/^批量下载结束$/, () => t('user.status.finished', '批量下载结束')],
        [/^批量下载结束，正在打包文件\.\.\.$/, () => t('user.status.finished-packing', '批量下载结束，正在打包文件...')],
        [/^获取信息：(.+)$/, (_, id) => t('user.status.fetching-meta', '获取信息：{id}', { id: id })],
        [/^下载中：(.+)$/, (_, title) => t('user.status.downloading', '下载中：{title}', { title: title })],
        [/^跳过：(.+)（已下载）$/, (_, title) => t('user.status.skipped-existing', '跳过：{title}（已下载）', { title: title })],
        [/^失败：(.+) \(文件缺失\)$/, (_, title) => t('user.status.failed-missing', '失败：{title} (文件缺失)', { title: title })],
        [/^完成：(.+)$/, (_, title) => t('user.status.completed', '完成：{title}', { title: title })],
        [/^失败：(.+)$/, (_, title) => t('user.status.failed', '失败：{title}', { title: title })],
        [/^需要登录，已停止下载$/, () => t('user.status.need-login-stop', '需要登录，已停止下载')],
        [/^已达到下载限额$/, () => t('user.status.quota-exceeded', '已达到下载限额')],
        [/^错误：(.+)$/, (_, title) => t('user.status.error', '错误：{title}', { title: title })],
        [/^正在暂停\.\.\. \(等待 (\d+) 个当前任务完成\)$/, (_, count) => t('user.status.pause-waiting', '正在暂停... (等待 {count} 个当前任务完成)', { count: count })],
        [/^已暂停$/, () => t('user.status.paused', '已暂停')],
        [/^继续下载$/, () => t('user.status.resumed', '继续下载')],
        [/^已强制清除队列$/, () => t('user.status.cleared', '已强制清除队列')],
        [/^正在获取作品列表\.\.\.$/, () => t('user.status.fetching-list', '正在获取作品列表...')],
        [/^该用户没有作品$/, () => t('user.status.no-artworks', '该用户没有作品')],
        [/^获取成功：共 (\d+) 个作品，新增 (\d+) 个$/, (_, total, added) => t('user.status.fetch-success', '获取成功：共 {total} 个作品，新增 {added} 个', { total: total, added: added })],
        [/^获取列表失败: (.+)$/, (_, message) => t('user.status.fetch-failed', '获取列表失败: {message}', { message: message })],
        [/^已导出 (\d+) 个未下载作品$/, (_, count) => t('user.status.exported-undownloaded', '已导出 {count} 个未下载作品', { count: count })],
        [/^导出成功$/, () => t('user.status.exported', '导出成功')],
        [/^需要登录，请登录后刷新页面$/, () => t('user.status.need-login-refresh', '需要登录，请登录后刷新页面')]
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

    // ====== 全局 username 变量 ======
    let username = null;

    function getCurrentUserId() {
        const match = location.href.match(/users\/(\d+)/);
        return match ? match[1] : null;
    }

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

    function createHelpIcon(title) {
        return $el('span', {
            title,
            textContent: '?',
            style: {
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '14px',
                height: '14px',
                border: '1px solid #999',
                borderRadius: '50%',
                color: '#666',
                fontSize: '10px',
                fontWeight: '700',
                lineHeight: '1',
                cursor: 'help',
                userSelect: 'none',
                flexShrink: '0'
            }
        });
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

    /* ========== 进度/后处理结果渲染辅助 ========== */
    function uiLang() {
        try { return PixivUserscriptI18n.getLang(); } catch (e) { return 'zh-CN'; }
    }

    function actionOutcomePart(action, labels) {
        if (!action) return null;
        const status = String(action.status || '').toLowerCase();
        const reason = action.message ? String(action.message) : '';
        let text = labels.unknown;
        if (status === 'success') text = labels.success;
        else if (status === 'failed') text = labels.failed;
        else if (status === 'skipped') text = labels.skipped;
        else if (status === 'exists') text = labels.exists;
        if ((status === 'failed' || status === 'skipped') && reason) {
            text = text + (uiLang() === 'en-US' ? ': ' : '：') + reason;
        } else if (!['success', 'failed', 'skipped', 'exists'].includes(status) && reason) {
            text = text + (uiLang() === 'en-US' ? ': ' : '：') + reason;
        }
        const tone = status === 'success' || status === 'exists'
            ? 'success'
            : status === 'failed' || status === 'skipped'
                ? 'error'
                : 'warning';
        return { text, tone };
    }

    function postDownloadOutcomeParts(data) {
        const parts = [];
        if (data && data.bookmarkResult) {
            parts.push(actionOutcomePart(data.bookmarkResult, {
                success: t('common.outcome.pixiv-bookmark.success', 'Pixiv 收藏成功'),
                failed: t('common.outcome.pixiv-bookmark.failed', 'Pixiv 收藏失败'),
                skipped: t('common.outcome.pixiv-bookmark.skipped', 'Pixiv 收藏跳过'),
                exists: t('common.outcome.pixiv-bookmark.exists', 'Pixiv 已收藏'),
                unknown: t('common.outcome.pixiv-bookmark.unknown', 'Pixiv 收藏状态未知')
            }));
        }
        if (data && data.collectionResult) {
            parts.push(actionOutcomePart(data.collectionResult, {
                success: t('common.outcome.collection.success', '加入收藏夹成功'),
                failed: t('common.outcome.collection.failed', '加入收藏夹失败'),
                skipped: t('common.outcome.collection.skipped', '收藏夹加入跳过'),
                exists: t('common.outcome.collection.exists', '已在收藏夹中'),
                unknown: t('common.outcome.collection.unknown', '收藏夹状态未知')
            }));
        }
        return parts.filter(Boolean);
    }

    function appendPostDownloadOutcome(base, data) {
        const parts = postDownloadOutcomeParts(data);
        if (!parts.length) return base;
        const sep = uiLang() === 'en-US' ? '; ' : '；';
        return base + sep + parts.map(p => p.text).join(sep);
    }

    function buildPostDownloadMessageParts(base, baseTone, data) {
        const sep = uiLang() === 'en-US' ? '; ' : '；';
        const parts = [{ text: base, tone: baseTone }].concat(postDownloadOutcomeParts(data));
        return parts.map((part, idx) => ({
            text: part.text + (idx < parts.length - 1 ? sep : ''),
            tone: part.tone
        }));
    }

    function toneColor(tone, fallback) {
        return ({
            success: '#28a745',
            error: '#dc3545',
            warning: '#e6a700',
            info: '#007bff'
        })[tone] || fallback || '#666';
    }

    function mergeUgoiraProgress(existing, incoming) {
        if (!incoming) return existing || null;
        return { ...(existing || {}), ...incoming };
    }

    function clampProgressValue(value) {
        const n = Number(value);
        if (!Number.isFinite(n)) return null;
        return Math.max(0, Math.min(100, Math.round(n)));
    }

    function formatBytes(bytes) {
        const n = Number(bytes);
        if (!Number.isFinite(n) || n < 0) return '';
        const units = ['B', 'KB', 'MB', 'GB'];
        let value = n;
        let idx = 0;
        while (value >= 1024 && idx < units.length - 1) {
            value /= 1024;
            idx++;
        }
        const digits = idx === 0 || value >= 10 ? 0 : 1;
        return `${value.toFixed(digits)} ${units[idx]}`;
    }

    function formatDurationMs(ms) {
        const n = Number(ms);
        if (!Number.isFinite(n) || n < 0) return '';
        const totalSeconds = Math.round(n / 1000);
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        return minutes > 0 ? `${minutes}:${String(seconds).padStart(2, '0')}` : `${seconds}s`;
    }

    function miniProgressHtml(label, valueText, progress, color) {
        const pctValue = clampProgressValue(progress);
        const pctText = pctValue === null ? '' : `${pctValue}%`;
        const right = [valueText, pctText].filter(Boolean).join(' · ');
        const width = pctValue === null ? 100 : pctValue;
        const opacity = pctValue === null ? '.28' : '1';
        return `<div style="margin-top:4px;">
            <div style="display:flex;justify-content:space-between;font-size:9px;margin-bottom:2px;color:#666;"><span>${escapeHtml(label)}</span><span>${escapeHtml(right)}</span></div>
            <div style="width:100%;height:4px;background:#e0e0e0;border-radius:2px;overflow:hidden;"><div style="height:100%;width:${width}%;background:${color};opacity:${opacity};transition:width 0.3s;"></div></div>
           </div>`;
    }

    function formatImageDownloadProgressHtml(progress, status) {
        if (!progress || ['completed', 'failed', 'skipped'].includes(status)) return '';
        const imageText = progress.imageNumber && progress.totalImages
            ? t('common.image-download.index', '第 {current}/{total} 张', { current: progress.imageNumber, total: progress.totalImages })
            : '';
        const bytesText = progress.totalBytes > 0
            ? `${formatBytes(progress.downloadedBytes || 0)} / ${formatBytes(progress.totalBytes)}`
            : formatBytes(progress.downloadedBytes || 0);
        const valueText = [imageText, bytesText].filter(Boolean).join(' · ');
        return miniProgressHtml(
            t('common.image-download.label', '图片下载'),
            valueText,
            progress.progress,
            progress.status === 'failed' ? '#dc3545' : '#0ea5e9'
        );
    }

    function formatUgoiraProgressHtml(progress, itemStatus) {
        if (!progress || itemStatus === 'completed' || progress.status === 'completed') return '';
        const phase = String(progress.phase || '');
        const status = String(progress.status || '');
        const parts = [];

        const hasZip = phase === 'zip' || phase === 'extract' || phase === 'ffmpeg'
            || progress.zipDownloadedBytes !== undefined || progress.zipProgress !== undefined;
        if (hasZip) {
            const zipBytes = progress.zipTotalBytes > 0
                ? `${formatBytes(progress.zipDownloadedBytes || 0)} / ${formatBytes(progress.zipTotalBytes)}`
                : formatBytes(progress.zipDownloadedBytes || 0);
            parts.push(miniProgressHtml(
                t('common.ugoira.zip', '动图压缩包'),
                zipBytes,
                progress.zipProgress,
                '#0ea5e9'
            ));
        }

        const hasFfmpeg = phase === 'ffmpeg' || progress.ffmpegProgress !== undefined || status === 'completed';
        if (hasFfmpeg) {
            const timeText = progress.ffmpegDurationMs > 0
                ? `${formatDurationMs(progress.ffmpegOutTimeMs || 0)} / ${formatDurationMs(progress.ffmpegDurationMs)}`
                : '';
            parts.push(miniProgressHtml(
                t('common.ugoira.ffmpeg', 'ffmpeg 转换'),
                timeText,
                progress.ffmpegProgress,
                status === 'failed' ? '#dc3545' : '#6610f2'
            ));
        }

        if (phase === 'extract') {
            const extracted = progress.totalFrames > 0
                ? t('common.ugoira.extracting-count', '正在解压帧 {current}/{total}', {
                    current: progress.extractedFrames || 0,
                    total: progress.totalFrames
                })
                : t('common.ugoira.extracting', '正在解压帧');
            parts.push(`<div style="font-size:10px;color:#666;margin-top:4px;">${escapeHtml(extracted)}</div>`);
        } else if (status === 'failed') {
            parts.push(`<div style="font-size:10px;color:#dc3545;margin-top:4px;">${escapeHtml(t('common.ugoira.failed', '动图处理失败'))}</div>`);
        }

        return parts.length ? `<div>${parts.join('')}</div>` : '';
    }

    function renderQueueDescHtml(q, fallbackText, statusColorFn) {
        const fallbackColor = statusColorFn(q.status);
        if (Array.isArray(q.lastMessageParts) && q.lastMessageParts.length) {
            return q.lastMessageParts
                .map(part => `<span style="color:${toneColor(part.tone, fallbackColor)};font-weight:bold;">${escapeHtml(part.text)}</span>`)
                .join('');
        }
        return `<span style="color:${fallbackColor};font-weight:bold;">${escapeHtml(fallbackText)}</span>`;
    }

    /* ========== API 封装 ========== */
    const Api = {
        checkBackend() {
            return new Promise(resolve => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: CONFIG.STATUS_URL,
                    timeout: 2000,
                    onload: (res) => resolve(res.status === 200),
                    onerror: () => resolve(false),
                    ontimeout: () => resolve(false)
                });
            });
        },
        async getUserMeta(userId) {
            try {
                // 从URL提取用户ID（保底）
                const userIdMatch = String(userId).match(/(\d+)/);
                if (!userIdMatch || !userIdMatch[1]) {
                    console.error('无法从userId提取数字ID:', userId);
                    return null;
                }

                const uid = userIdMatch[1];

                const apiUrl = `https://www.pixiv.net/ajax/user/${uid}?lang=zh`;

                const response = await fetch(apiUrl, {
                    method: 'GET',
                    credentials: 'include', // 包含cookies，保持登录状态
                    headers: {
                        'Accept': 'application/json',
                        'User-Agent': navigator.userAgent,
                        'Referer': window.location.href
                    }
                });

                if (!response.ok) {
                    console.error(`API请求失败: ${response.status}`);
                    return null;
                }

                const data = await response.json();

                if (data.error) {
                    console.error('API返回错误:', data.message);
                    return null;
                }

                if (data.body && data.body.name) {
                    console.log('API获取的用户名:', data.body.name);
                    return data.body.name;
                }

                return null;
            } catch (error) {
                console.error('获取用户名失败:', error);
                return null;
            }
        },
        getAllUserArtworkIds(userId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `https://www.pixiv.net/ajax/user/${userId}/profile/all`,
                    headers: {Referer: 'https://www.pixiv.net/'},
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (data.error) return reject(new Error(data.message));
                            const ids = [];
                            if (data.body.illusts) ids.push(...Object.keys(data.body.illusts));
                            if (data.body.manga) ids.push(...Object.keys(data.body.manga));
                            ids.sort((a, b) => b - a);
                            resolve(ids);
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
                            if (data.error) reject(new Error(data.message));
                            else resolve(data.body);
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
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
                            if (data.error) reject(new Error(data.message));
                            else resolve((data.body || []).map(p => p.urls.original));
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
                            if (data.error) reject(new Error(data.message));
                            else resolve(data.body);
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        sendDownloadRequest(artworkId, imageUrls, title, usernameParam, authorId, authorName, xRestrict, isAi, ugoiraData, delayMs, bookmark, description, tags) {
            return new Promise((resolve, reject) => {
                const parsedAuthorId = Number.parseInt(String(authorId || ''), 10);
                const other = {
                    userDownload: true,
                    username: usernameParam,
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
                            } else if (res.status === 200) {
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
        getDownloadStatus(artworkId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${CONFIG.STATUS_URL}/${artworkId}`,
                    onload: (res) => {
                        if (res.status === 401) { handleUnauthorized(); reject(new Error('需要登录')); return; }
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
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: `${CONFIG.CANCEL_URL}/${artworkId}`,
                    onload: () => resolve(true),
                    onerror: () => resolve(false)
                });
            });
        },
        checkDownloaded(artworkId, verifyFiles = false) {
            return new Promise((resolve) => {
                const query = verifyFiles ? '?verifyFiles=true' : '';
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${CONFIG.CHECK_DOWNLOADED_URL}/${artworkId}${query}`,
                    onload: (res) => {
                        try {
                            if (res.status === 200) {
                                const data = JSON.parse(res.responseText);
                                resolve(!!data.artworkId);
                            } else resolve(false);
                        } catch (e) {
                            resolve(false);
                        }
                    },
                    onerror: () => resolve(false),
                    ontimeout: () => resolve(false)
                });
            });
        },

        initQuota() {
            const headers = { 'Content-Type': 'application/json' };
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
                        } catch { resolve({}); }
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
                        try { resolve(JSON.parse(res.responseText)); } catch { resolve({}); }
                    },
                    onerror: () => resolve({}),
                    ontimeout: () => resolve({})
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
                    onerror: (err) => { console.error('SSE connection error', err); this._cleanup(); this._scheduleReconnect(); },
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
                this.reader.read().then(({ done, value }) => {
                    if (done) { this._cleanup(); this._scheduleReconnect(); return; }
                    const chunk = decoder.decode(value, { stream: true });
                    this._buffer += chunk;
                    const parts = this._buffer.split('\n\n');
                    this._buffer = parts.pop();
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
            this.userId = null;
            this.userName = null; // 新增：存储用户名
            this.queue = [];
            this.isRunning = false;
            this.isPaused = false;

            this.stopRequested = false;
            this.activeWorkers = 0; // 追踪活跃 Worker 数量
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
            this.sse = new SSEManager();
            this._quotaExceededHandled = false;

            this.globalSettings = {
                interval: GM_getValue(CONFIG.KEY_INTERVAL, CONFIG.DEFAULT_INTERVAL) || CONFIG.DEFAULT_INTERVAL,
                intervalUnit: GM_getValue(CONFIG.KEY_INTERVAL_UNIT, 's') || 's',
                imageDelay: GM_getValue(CONFIG.KEY_IMAGE_DELAY, 0),
                imageDelayUnit: GM_getValue(CONFIG.KEY_IMAGE_DELAY_UNIT, 'ms') || 'ms',
                concurrent: GM_getValue(CONFIG.KEY_CONCURRENT, CONFIG.DEFAULT_CONCURRENT) || CONFIG.DEFAULT_CONCURRENT,
                skipHistory: GM_getValue(CONFIG.KEY_SKIP_HISTORY, false),
                verifyHistoryFiles: GM_getValue(CONFIG.KEY_VERIFY_HISTORY_FILES, false),
                r18Only: GM_getValue(CONFIG.KEY_R18_ONLY, false),
                bookmark: GM_getValue(CONFIG.KEY_BOOKMARK, false)
            };
        }

        initForUser(userId) {
            if (this.userId && this.userId !== userId) {
                if (this.isRunning) this.stopAndClear(false);
            }
            this.userId = userId;
            this.userName = null; // 重置用户名
            this.loadFromStorage();
            this.ui.renderQueue(this.queue);
            this.ui.setUserInfo(this.userId, this.userName); // 使用新方法更新UI
        }

        setUserName(name) {
            this.userName = name;
            // 更新UI显示
            if (this.ui) {
                this.ui.setUserInfo(this.userId, this.userName);
            }
        }

        get storageKey() {
            return `pixiv_user_${this.userId}_queue_v1`;
        }

        loadFromStorage() {
            if (!this.userId) return;
            try {
                const raw = GM_getValue(this.storageKey, null);
                if (!raw) {
                    this.queue = [];
                    return;
                }
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed.queue)) {
                    // 刷新后 downloading 状态的任务已中断，重置为 idle 以便重新下载
                    this.queue = parsed.queue.map(q =>
                        q.status === 'downloading' ? {...q, status: 'idle', lastMessage: '刷新后重置', lastMessageParts: null} : q
                    );
                    this.isPaused = !!parsed.isPaused;
                    this.stats = parsed.stats || {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
                    this.updateStats();
                }
            } catch (e) {
                this.queue = [];
            }
        }

        saveToStorage() {
            if (!this.userId) return;
            try {
                const snapshot = {
                    queue: this.queue,
                    isRunning: this.isRunning,
                    isPaused: this.isPaused,
                    stats: this.stats,
                    savedAt: new Date().toISOString()
                };
                GM_setValue(this.storageKey, JSON.stringify(snapshot));
            } catch (e) {
            }
        }

        deleteStorage() {
            if (this.userId) GM_deleteValue(this.storageKey);
        }

        setSkipHistory(val) {
            this.globalSettings.skipHistory = val;
            GM_setValue(CONFIG.KEY_SKIP_HISTORY, val);
            if (this.ui && this.ui.updateSkipHistoryVisibility) {
                this.ui.updateSkipHistoryVisibility(val);
            }
        }

        setVerifyHistoryFiles(val) {
            this.globalSettings.verifyHistoryFiles = val;
            GM_setValue(CONFIG.KEY_VERIFY_HISTORY_FILES, val);
        }

        setR18Only(val) {
            this.globalSettings.r18Only = val;
            GM_setValue(CONFIG.KEY_R18_ONLY, val);
        }

        setBookmark(val) {
            this.globalSettings.bookmark = val;
            GM_setValue(CONFIG.KEY_BOOKMARK, val);
        }

        setInterval(val) {
            this.globalSettings.interval = Math.max(0, parseFloat(val) || 0);
            GM_setValue(CONFIG.KEY_INTERVAL, this.globalSettings.interval);
        }

        setIntervalUnit(unit) {
            this.globalSettings.intervalUnit = unit;
            GM_setValue(CONFIG.KEY_INTERVAL_UNIT, unit);
        }

        getIntervalMs() {
            const { interval, intervalUnit } = this.globalSettings;
            return intervalUnit === 's' ? Math.round(interval * 1000) : Math.round(interval);
        }

        setImageDelay(val) {
            this.globalSettings.imageDelay = Math.max(0, parseFloat(val) || 0);
            GM_setValue(CONFIG.KEY_IMAGE_DELAY, this.globalSettings.imageDelay);
        }

        setImageDelayUnit(unit) {
            this.globalSettings.imageDelayUnit = unit;
            GM_setValue(CONFIG.KEY_IMAGE_DELAY_UNIT, unit);
        }

        getImageDelayMs() {
            const { imageDelay, imageDelayUnit } = this.globalSettings;
            return imageDelayUnit === 's' ? Math.round(imageDelay * 1000) : Math.round(imageDelay);
        }

        setConcurrent(val) {
            let num = parseInt(val) || CONFIG.DEFAULT_CONCURRENT;
            if (num < 1) num = 1;
            this.globalSettings.concurrent = num;
            GM_setValue(CONFIG.KEY_CONCURRENT, num);
        }

        addItemsToQueue(idList) {
            const existingIds = new Set(this.queue.map(q => q.id));
            let addedCount = 0;
            const newItems = [];
            for (const id of idList) {
                if (!existingIds.has(String(id))) {
                    newItems.push({
                        id: String(id),
                        title: `ID: ${id}`,
                        url: `https://www.pixiv.net/artworks/${id}`,
                        status: 'idle',
                        totalImages: 0, downloadedCount: 0,
                        startTime: null, endTime: null, lastMessage: '',
                        lastMessageParts: null,
                        bookmarkResult: null,
                        collectionResult: null,
                        ugoiraProgress: null,
                        imageProgress: null
                    });
                    addedCount++;
                }
            }
            this.queue = [...this.queue, ...newItems];
            this.updateStats();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return addedCount;
        }

        async start() {
            if (this.queue.length === 0) {
                this.ui.setStatus('队列为空', 'error');
                return;
            }
            if (!await Api.checkBackend()) {
            alert(t('user.alert.backend-unavailable', '后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址'));
                return;
            }

            const intervalMs = this.getIntervalMs();
            const maxConcurrent = this.globalSettings.concurrent;

            // 只重置 idle/failed/paused 的项目
            this.queue.forEach(q => {
                if (['idle', 'failed', 'paused'].includes(q.status)) {
                    q.status = 'pending';
                    q.lastMessageParts = null;
                }
            });
            this.isRunning = true;
            this.isPaused = false;
            this.stopRequested = false;
            this.activeWorkers = 0;
            this._quotaExceededHandled = false;
            this.updateStats();
            this.ui.updateButtonsState(true, false);
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            this.ui.setStatus(`开始下载 (并发:${maxConcurrent}, 间隔:${intervalMs}ms)`, 'info');

            const workers = [];
            for (let i = 0; i < Math.max(1, maxConcurrent); i++) {
                workers.push(this.workerLoop(intervalMs));
            }
            await Promise.all(workers);

            // 所有 Worker 结束
            this.isRunning = false;
            this.saveToStorage();
            this.ui.setStatus('批量下载结束', 'info');
            this.ui.updateButtonsState(false, false);

            // 多人模式：队列完成后自动打包（配额超限时已在 _processSingle 中触发，不重复）
            const completed = this.queue.filter(q => q.status === 'completed').length;
            if (quotaInfo.enabled && completed > 0 && !this._quotaExceededHandled) {
                this._autoPackAfterQueue();
            }
        }

        async _autoPackAfterQueue() {
            try {
                const data = await new Promise((resolve) => {
                    const headers = { 'Content-Type': 'application/json' };
                    if (userUUID) headers['X-User-UUID'] = userUUID;
                    GM_xmlhttpRequest({
                        method: 'POST',
                        url: serverBase + '/api/quota/pack',
                        headers,
                        onload: (res) => {
                            if (res.status === 204) { resolve(null); return; }
                            try { resolve(JSON.parse(res.responseText)); } catch { resolve(null); }
                        },
                        onerror: () => resolve(null),
                        ontimeout: () => resolve(null)
                    });
                });
                if (data && data.archiveToken) {
                    this.ui.setStatus('批量下载结束，正在打包文件...', 'info');
                    this.ui.showQuotaExceeded(data, '下载完成，正在打包');
                }
            } catch {}
        }

        async workerLoop(intervalMs) {
            this.activeWorkers++;
            try {
                while (this.isRunning && !this.stopRequested) {
                    if (this.isPaused) {
                        // 如果暂停，进入等待，不领取新任务
                        await this._sleep(500);
                        continue;
                    }

                    const next = this._getNextPending();
                    if (!next) {
                        // 没有待办任务，检查是否全部完成
                        if (this.queue.every(q => ['completed', 'failed', 'idle', 'paused', 'skipped'].includes(q.status))) break;
                        await this._sleep(500);
                        continue;
                    }

                    // *** 关键：这里 await 保证了必须等当前任务处理完，循环才会继续 ***
                    try {
                        await this._processSingle(next);
                    } catch (e) {
                        console.error(e);
                    } finally {
                        // 完成任务后，休息间隔
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

        async _processSingle({item}) {
            item.lastMessageParts = null;
            item.bookmarkResult = null;
            item.collectionResult = null;
            item.ugoiraProgress = null;
            item.imageProgress = null;
            item.lastMessage = '正在检查历史记录...';
            this.ui.renderQueue(this.queue);

            if (this.globalSettings.skipHistory) {
                const isDownloaded = await Api.checkDownloaded(item.id, this.globalSettings.verifyHistoryFiles);
                if (isDownloaded) {
                    item.status = 'skipped';
                    item.lastMessage = '跳过 — 历史记录中已存在';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    return;
                }
            }

            item.lastMessage = '正在获取作品信息...';
            this.ui.setCurrent(item);
            this.ui.setStatus(`获取信息：${item.id}`, 'info');
            this.ui.renderQueue(this.queue);

            try {
                const meta = await Api.getArtworkMeta(item.id);
                const safeTitle = (meta && meta.illustTitle) ? meta.illustTitle : `Artwork ${item.id}`;
                item.title = safeTitle;
                this.saveToStorage();

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

                if (this.globalSettings.r18Only && xRestrict < 1) {
                    item.status = 'skipped';
                    item.lastMessage = '跳过 — 非 R18 内容';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    return;
                }

                let urls;
                let ugoiraData = null;

                if (meta.illustType === 2) {
                    // 动图作品：获取ugoira元数据，由后端下载ZIP并合成WebP
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
                    if (!urls || !urls.length) throw new Error('无图片URL');
                    item.totalImages = urls.length;
                }

                item.lastMessage = '正在获取图片地址...';
                this.saveToStorage();
                this.ui.renderQueue(this.queue);

                this.ui.setStatus(`下载中：${item.title}`, 'info');
                const dlData = await Api.sendDownloadRequest(
                    item.id,
                    urls,
                    item.title,
                    username,
                    this.userId,
                    this.userName || username,
                    xRestrict,
                    isAi,
                    ugoiraData,
                    this.getImageDelayMs(),
                    this.globalSettings.bookmark,
                    description,
                    tags
                );
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

                // 校验下载数量
                if (final && final.completed) {
                    const dCount = final.downloadedCount !== undefined ? final.downloadedCount : item.totalImages;
                    item.downloadedCount = dCount;
                    item.bookmarkResult = final.bookmarkResult || null;
                    item.collectionResult = final.collectionResult || null;
                    item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
                    item.imageProgress = final.imageProgress || item.imageProgress || null;
                    if (dCount < item.totalImages) {
                        item.status = 'failed';
                        const baseMessage = `失败 — 仅 ${dCount}/${item.totalImages} 张已下载`;
                        item.lastMessage = appendPostDownloadOutcome(baseMessage, final);
                        item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'error', final);
                        this.ui.setStatus(`失败：${item.title} (文件缺失)`, 'error');
                    } else {
                        item.status = 'completed';
                        const baseMessage = `已完成，共 ${dCount} 张`;
                        item.lastMessage = appendPostDownloadOutcome(baseMessage, final);
                        item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', final);
                        this.ui.setStatus(`完成：${item.title}`, 'success');
                        // 刷新配额显示（每完成一个作品计 1）
                        if (quotaInfo.enabled) {
                            quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
                            this.ui.updateQuotaBar(quotaInfo);
                        }
                    }
                } else if (final && final.failed) {
                    item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
                    item.imageProgress = final.imageProgress || item.imageProgress || null;
                    item.status = 'failed';
                    item.lastMessage = `失败 — ${final.message || '后端报告失败'}`;
                    this.ui.setStatus(`失败：${item.title}`, 'error');
                } else {
                    const check = await Api.getDownloadStatus(item.id);
                    if (check && check.completed) {
                        const dCount = check.downloadedCount !== undefined ? check.downloadedCount : 0;
                        item.downloadedCount = dCount;
                        item.bookmarkResult = check.bookmarkResult || null;
                        item.collectionResult = check.collectionResult || null;
                        item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, check.ugoiraProgress);
                        item.imageProgress = check.imageProgress || item.imageProgress || null;
                        if (dCount < item.totalImages) {
                            item.status = 'failed';
                            const baseMessage = `失败 — 文件缺失 (${dCount}/${item.totalImages})`;
                            item.lastMessage = appendPostDownloadOutcome(baseMessage, check);
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'error', check);
                        } else {
                            item.status = 'completed';
                            const baseMessage = `已完成（确认），共 ${dCount} 张`;
                            item.lastMessage = appendPostDownloadOutcome(baseMessage, check);
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', check);
                        }
                    } else {
                        item.status = 'failed';
                        item.lastMessage = '失败 — 超时未收到完成状态';
                    }
                }

            } catch (e) {
                if (e.message === '需要登录') {
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
                } else if (e.message === 'quota_exceeded') {
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
                        if (e.quotaData) {
                            this.ui.showQuotaExceeded(e.quotaData);
                        }
                    }
                } else {
                    item.status = 'failed';
                    item.lastMessage = `失败 — ${e.message}`;
                    this.ui.setStatus(`错误：${item.title}`, 'error');
                }
            } finally {
                try { this.sse.close(item.id); } catch (e) {}
                item.endTime = new Date().toISOString();
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
                    if (resolved) { clearInterval(pollTimer); return; }
                    try {
                        const status = await Api.getDownloadStatus(String(artworkId));
                        if (status && (status.completed || status.failed)) finish(status);
                    } catch {}
                }, 5000);

                const handler = (data) => {
                    if (data && (data.completed || data.failed || data.cancelled)) {
                        finish(data);
                    } else {
                        const q = this.queue.find(x => x.id === String(artworkId));
                        if (q) {
                            if (data.downloadedCount !== undefined) q.downloadedCount = data.downloadedCount;
                            if (data.ugoiraProgress) q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
                            if (data.imageProgress) q.imageProgress = data.imageProgress;
                            this.saveToStorage();
                            this.ui.renderQueue(this.queue);
                            this.ui.setCurrent(q);
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
            // pending -> paused (让还没开始的任务暂停)
            this.queue.forEach(q => {
                if (q.status === 'pending') q.status = 'paused';
            });
            this.saveToStorage();

            // 计算正在运行的任务
            const activeCount = this.queue.filter(q => q.status === 'downloading').length;
            this.ui.updateButtonsState(true, true);

            if (activeCount > 0) {
                this.ui.setStatus(`正在暂停... (等待 ${activeCount} 个当前任务完成)`, 'warning');
            } else {
                this.ui.setStatus('已暂停', 'warning');
            }
        }

        resume() {
            if (!this.isRunning) {
                this.start();
                return;
            }
            this.isPaused = false;
            // paused -> pending
            this.queue.forEach(q => {
                if (q.status === 'paused') q.status = 'pending';
            });
            this.saveToStorage();
            this.ui.updateButtonsState(true, false);
            this.ui.setStatus('继续下载', 'info');
        }

        stopAndClear(force) {
            this.stopRequested = true;
            this.isRunning = false;
            this.isPaused = false;
            this.sse.closeAll();
            this.queue = [];
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
            this.deleteStorage();
            this.ui.renderQueue(this.queue);
            this.ui.updateButtonsState(false, false);
            this.ui.setStatus('已强制清除队列', 'info');
        }

        updateStats() {
            this.stats.success = this.queue.filter(q => q.status === 'completed').length;
            this.stats.failed = this.queue.filter(q => q.status === 'failed').length;
            this.stats.skipped = this.queue.filter(q => q.status === 'skipped').length;
            this.stats.active = this.queue.filter(q => q.status === 'downloading').length;
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

    /* ========== UI ========== */
    class UI {
        constructor() {
            this.root = null;
            this.elements = {};
            this._archivePollTimer = null;
            this._archiveCountdownTimer = null;
        }

        ensureMounted() {
            if (document.getElementById('pixiv-user-batch-ui')) {
                const fab = document.getElementById('user-batch-mini-fab');
                if (this._collapsed) {
                    if (fab) fab.style.display = 'block';
                } else {
                    if (this.root) this.root.style.display = 'block';
                    if (fab) fab.style.display = 'none';
                }
                return;
            }
            this._collapsed = false;
            this._build();
            document.dispatchEvent(new CustomEvent('pixiv_panel_active', { detail: 'user' }));
            this.syncSettings();
        }

        hide() {
            if (this.root) this.root.style.display = 'none';
            const fab = document.getElementById('user-batch-mini-fab');
            if (fab) fab.style.display = 'none';
        }

        toggleCollapse() {
            this._collapsed = !this._collapsed;
            const fab = document.getElementById('user-batch-mini-fab');
            if (this._collapsed) {
                if (this.root) this.root.style.display = 'none';
                if (fab) fab.style.display = 'block';
            } else {
                if (this.root) this.root.style.display = 'block';
                if (fab) fab.style.display = 'none';
                document.dispatchEvent(new CustomEvent('pixiv_panel_active', { detail: 'user' }));
            }
        }

        syncSettings() {
            if (!this.manager || !this.elements.interval) return;
            const s = this.manager.globalSettings;
            this.elements.skipHistory.checked = s.skipHistory;
            this.elements.verifyHistoryFiles.checked = s.verifyHistoryFiles ?? false;
            this.elements.r18Only.checked = s.r18Only;
            if (this.elements.bookmarkAfterDl) this.elements.bookmarkAfterDl.checked = s.bookmark ?? false;
            this.elements.interval.value = s.interval;
            this.elements.concurrent.value = s.concurrent;
            if (this.elements.intervalUnitBtn) {
                this.elements.intervalUnitBtn.textContent = s.intervalUnit || 's';
            }
            if (this.elements.imageDelay) {
                this.elements.imageDelay.value = s.imageDelay ?? 0;
            }
            if (this.elements.imageDelayUnitBtn) {
                this.elements.imageDelayUnitBtn.textContent = s.imageDelayUnit || 'ms';
            }
            this.updateSkipHistoryVisibility(s.skipHistory);
        }

        _build() {
            const container = $el('div', {
                id: 'pixiv-user-batch-ui',
                style: {
                    position: 'fixed', top: '120px', right: '20px', zIndex: 999999,
                    background: 'white', border: '2px solid #28a745', borderRadius: '8px',
                    padding: '15px', boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
                    minWidth: '400px', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    maxHeight: '80vh', overflowY: 'auto'
                }
            });

            // 标题行（含收起按钮）
            const titleRow = $el('div', {
                style: { display: 'flex', alignItems: 'center', marginBottom: '15px', borderBottom: '2px solid #eee', paddingBottom: '10px' }
            });
            const collapseBtn = $el('button', {
                innerText: '◀',
                title: t('common.action.collapse', '收起'),
                style: { background: 'none', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '12px', padding: '2px 6px', color: '#666', flexShrink: '0' }
            });
            const title = $el('div', {
                id: 'batch-ui-title',
                innerText: t('user.title', '🖼️ Pixiv User 批量下载器'),
                style: { fontWeight: 'bold', color: '#333', textAlign: 'center', fontSize: '16px', flex: '1' }
            });
            collapseBtn.addEventListener('click', () => this.toggleCollapse());
            titleRow.appendChild(collapseBtn);
            titleRow.appendChild(title);
            titleRow.appendChild(buildLangSwitcher());

            // 收起后的悬浮按钮
            const existingFab = document.getElementById('user-batch-mini-fab');
            if (existingFab) existingFab.remove();
            const miniFab = $el('button', {
                id: 'user-batch-mini-fab',
                innerText: '🖼️',
                title: t('user.fab.title', 'User批量下载器'),
                style: { display: 'none', position: 'fixed', top: '110px', right: '20px', zIndex: '1000001', background: '#28a745', color: 'white', border: 'none', borderRadius: '50%', width: '40px', height: '40px', cursor: 'pointer', fontSize: '18px', boxShadow: '0 2px 8px rgba(0,0,0,0.3)', lineHeight: '40px', textAlign: 'center', padding: '0' }
            });
            miniFab.addEventListener('click', () => this.toggleCollapse());
            document.body.appendChild(miniFab);

            const status = $el('div', {
                innerText: t('common.status.ready', '准备就绪'),
                style: {marginBottom: '10px', color: '#666', fontSize: '12px', textAlign: 'center'}
            });
            const stats = $el('div', {
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

            const settings = $el('div', {style: {marginBottom: '15px'}});
            settings.innerHTML = `
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('user.setting.interval', '作品间隔:')}</label>
                    <input type="number" id="download-interval" min="0" value="${CONFIG.DEFAULT_INTERVAL}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="interval-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">s</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('user.setting.image-delay', '图片间隔:')}</label>
                    <input type="number" id="image-delay" min="0" value="0"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="image-delay-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">ms</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('user.setting.concurrent', '最大并发数:')}</label>
                    <input type="number" id="max-concurrent" min="1" value="${CONFIG.DEFAULT_CONCURRENT}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px; cursor:pointer;">
                        <input type="checkbox" id="skip-history" style="vertical-align: middle;"> ${t('user.setting.skip-history', '跳过历史下载')}
                    </label>
                    <label style="font-size: 12px; cursor:pointer; margin-left:15px; color:#d63384;">
                        <input type="checkbox" id="r18-only" style="vertical-align: middle;"> ${t('user.setting.r18-only', '仅下载R18作品')}
                    </label>
                </div>
                <div id="verify-history-files-row" style="display:none; align-items:center; margin-top: 8px; margin-bottom: 8px;">
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="verify-history-files" style="vertical-align: middle;"> ${t('user.setting.verify-history-files', '实际目录检测')}
                    </label>
                    ${createHelpIcon(t('common.option.verify-history-files.tooltip', VERIFY_HISTORY_FILES_TOOLTIP)).outerHTML}
                </div>
                <div style="display: flex; align-items: center; margin-top: 8px; margin-bottom: 8px;">
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="bookmark-after-dl" style="vertical-align: middle;"> ${t('user.setting.bookmark', '下载后自动收藏')}
                    </label>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('user.setting.server', '服务器地址:')}</label>
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
                    id: 'fetch-all-btn',
                    text: t('user.button.fetch-all', '📥 获取该画师所有作品'),
                    bgColor: '#17a2b8',
                    onClick: () => this.handleFetch(false)
                },
                {
                    id: 'fetch-new-btn',
                    text: t('user.button.fetch-new', '🆕 获取并仅下载新作品'),
                    bgColor: '#6610f2',
                    onClick: () => this.handleFetch(true)
                },
                {id: 'start-btn', text: t('user.button.start', '🚀 开始批量下载'), bgColor: '#28a745', onClick: () => this.handleStart()},
                {id: 'retry-btn', text: t('user.button.retry', '🔁 重新下载失败的作品'), bgColor: '#17a2b8', onClick: () => this.handleRetry()},
                {
                    id: 'pause-btn',
                    text: t('common.button.pause', '⏸️ 暂停下载'),
                    bgColor: '#ffc107',
                    onClick: () => this.handlePause(),
                    disabled: true
                },
                {id: 'export-btn', text: t('user.button.export', '📤 导出下载列表'), bgColor: '#007bff', onClick: () => this.handleExport()},
                {id: 'export-failed-btn', text: t('user.button.export-failed', '📋 导出未下载列表'), bgColor: '#6610f2', onClick: () => this.handleExportFailed()},
                {id: 'clear-btn', text: t('user.button.clear', '🗑️ 清除队列'), bgColor: '#6c757d', onClick: () => this.handleClear()}
            ];

            buttons.forEach(btnConfig => {
                const button = $el('button', {
                    id: btnConfig.id, innerText: btnConfig.text,
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
                id: 'pixiv-quota-bar',
                style: { display: 'none', marginBottom: '10px', padding: '6px 8px',
                         background: '#f8f9fa', borderRadius: '5px', fontSize: '11px', color: '#555' }
            });

            // 压缩包下载卡片（配额超出时显示）
            const archiveCard = $el('div', {
                id: 'pixiv-archive-card',
                style: { display: 'none', marginBottom: '10px', padding: '10px',
                         background: '#fff8e1', border: '2px solid #ffc107',
                         borderRadius: '5px', fontSize: '12px' }
            });

            container.appendChild(titleRow);
            container.appendChild(status);
            container.appendChild(stats);
            container.appendChild(settings);
            container.appendChild(buttonContainer);
            container.appendChild(quotaBar);
            container.appendChild(archiveCard);
            container.appendChild(currentDownload);
            container.appendChild(queueContainer);

            document.body.appendChild(container);
            this.root = container;

            this.elements = {
                title, status, stats, currentDownload, queueContainer,
                interval: container.querySelector('#download-interval'),
                imageDelay: container.querySelector('#image-delay'),
                concurrent: container.querySelector('#max-concurrent'),
                skipHistory: container.querySelector('#skip-history'),
                verifyHistoryFiles: container.querySelector('#verify-history-files'),
                verifyHistoryFilesRow: container.querySelector('#verify-history-files-row'),
                r18Only: container.querySelector('#r18-only'),
                bookmarkAfterDl: container.querySelector('#bookmark-after-dl'),
                serverBaseInput: container.querySelector('#server-base-url'),
                startBtn: container.querySelector('#start-btn'),
                pauseBtn: container.querySelector('#pause-btn')
            };

            const bindChange = (el, fn) => {
                el.addEventListener('change', fn);
                if (el.type === 'number') el.addEventListener('input', fn);
            };
            bindChange(this.elements.skipHistory, (e) => {
                if (!this.manager) return;
                this.manager.setSkipHistory(e.target.checked);
                this.updateSkipHistoryVisibility(e.target.checked);
            });
            bindChange(this.elements.verifyHistoryFiles, (e) => this.manager && this.manager.setVerifyHistoryFiles(e.target.checked));
            bindChange(this.elements.r18Only, (e) => this.manager && this.manager.setR18Only(e.target.checked));
            if (this.elements.bookmarkAfterDl) {
                bindChange(this.elements.bookmarkAfterDl, (e) => this.manager && this.manager.setBookmark(e.target.checked));
            }
            bindChange(this.elements.interval, (e) => this.manager && this.manager.setInterval(e.target.value));
            bindChange(this.elements.imageDelay, (e) => this.manager && this.manager.setImageDelay(e.target.value));
            bindChange(this.elements.concurrent, (e) => this.manager && this.manager.setConcurrent(e.target.value));

            // 图片间隔单位切换
            const imageDelayUnitBtn = container.querySelector('#image-delay-unit-btn');
            if (imageDelayUnitBtn) {
                imageDelayUnitBtn.addEventListener('click', () => {
                    if (!this.manager) return;
                    const cur = parseFloat(this.elements.imageDelay.value) || 0;
                    const curUnit = this.manager.globalSettings.imageDelayUnit;
                    if (curUnit === 's') {
                        this.manager.setImageDelayUnit('ms');
                        this.elements.imageDelay.value = Math.round(cur * 1000);
                        imageDelayUnitBtn.textContent = 'ms';
                    } else {
                        this.manager.setImageDelayUnit('s');
                        this.elements.imageDelay.value = +(cur / 1000).toFixed(3);
                        imageDelayUnitBtn.textContent = 's';
                    }
                    this.manager.setImageDelay(this.elements.imageDelay.value);
                });
                this.elements.imageDelayUnitBtn = imageDelayUnitBtn;
            }

            const unitBtn = container.querySelector('#interval-unit-btn');
            if (unitBtn) {
                unitBtn.addEventListener('click', () => {
                    if (!this.manager) return;
                    const cur = parseFloat(this.elements.interval.value) || 0;
                    const curUnit = this.manager.globalSettings.intervalUnit;
                    if (curUnit === 's') {
                        this.manager.setIntervalUnit('ms');
                        this.elements.interval.value = Math.round(cur * 1000);
                        unitBtn.textContent = 'ms';
                    } else {
                        this.manager.setIntervalUnit('s');
                        this.elements.interval.value = +(cur / 1000).toFixed(3);
                        unitBtn.textContent = 's';
                    }
                    this.manager.setInterval(this.elements.interval.value);
                });
                this.elements.intervalUnitBtn = unitBtn;
            }

            const serverBaseInput = container.querySelector('#server-base-url');
            if (serverBaseInput) {
                serverBaseInput.addEventListener('change', (e) => {
                    serverBase = e.target.value.trim().replace(/\/$/, '') || 'http://localhost:6999';
                    GM_setValue(KEY_SERVER_URL, serverBase);
                });
            }
        }

        bindManager(manager) {
            this.manager = manager;
            if (this.root) this.syncSettings();
        }

        updateSkipHistoryVisibility(enabled) {
            if (!this.elements || !this.elements.verifyHistoryFilesRow) return;
            this.elements.verifyHistoryFilesRow.style.display = enabled ? 'flex' : 'none';
        }

        // ---- 配额 UI 方法 ----

        updateQuotaBar(info) {
            const bar = document.getElementById('pixiv-quota-bar');
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
            const card = document.getElementById('pixiv-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${title === '已达到下载限额' ? t('common.archive.limit-title', '已达到下载限额') : title}</div>
              <div id="pixiv-ac-status" style="font-size:11px;color:#666;">${t('common.archive.preparing', '正在打包已下载文件，请稍候...')}</div>
              <div id="pixiv-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;

            const token = data.archiveToken;
            const expireSec = data.archiveExpireSeconds || 3600;
            this._pollArchive(token, expireSec);
        }

        restoreArchiveCard(token, expireSec, ready) {
            clearInterval(this._archivePollTimer);
            clearInterval(this._archiveCountdownTimer);
            const card = document.getElementById('pixiv-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${t('common.archive.restore-title', '已有未下载的压缩包')}</div>
              <div id="pixiv-ac-status" style="font-size:11px;color:#666;"></div>
              <div id="pixiv-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;
            if (ready) {
                this._activateArchiveDl(token, expireSec);
            } else {
                document.getElementById('pixiv-ac-status').textContent = t('common.archive.preparing', '正在打包已下载文件，请稍候...');
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
                    const expired = document.getElementById('pixiv-ac-expired');
                    const status = document.getElementById('pixiv-ac-status');
                    if (expired) expired.style.display = 'block';
                    if (status) status.textContent = '';
                } else if (data.status === 'empty') {
                    clearInterval(this._archivePollTimer);
                    const status = document.getElementById('pixiv-ac-status');
                    if (status) status.textContent = t('common.archive.empty', '暂无可打包文件');
                }
            }, 2000);
        }

        _activateArchiveDl(token, expireSec) {
            clearInterval(this._archiveCountdownTimer);
            const statusEl = document.getElementById('pixiv-ac-status');
            const dlEl = document.getElementById('pixiv-ac-dl');
            if (statusEl) statusEl.textContent = t('common.archive.ready', '压缩包已就绪：');
            if (dlEl) {
                dlEl.style.display = 'block';
                const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
                dlEl.innerHTML = `<a href="${CONFIG.ARCHIVE_DOWNLOAD_BASE}/${token}" download="${filename}"
                  style="display:inline-block;padding:5px 12px;background:#28a745;color:white;
                         border-radius:4px;text-decoration:none;font-size:12px;font-weight:bold;">
                  ${t('common.archive.download-link', '下载压缩包')}
                </a>
                <span id="pixiv-ac-countdown" style="font-size:10px;color:#888;margin-left:8px;"></span>`;
                let remaining = Math.max(0, parseInt(expireSec));
                const el = () => document.getElementById('pixiv-ac-countdown');
                if (el()) el().textContent = t('common.archive.validity', '有效期：{time}', { time: this._fmtSeconds(remaining) });
                this._archiveCountdownTimer = setInterval(() => {
                    remaining--;
                    if (remaining <= 0) {
                        clearInterval(this._archiveCountdownTimer);
                        const expired = document.getElementById('pixiv-ac-expired');
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
            if (h > 0) return h + 'h ' + String(m).padStart(2,'0') + 'm ' + String(sec).padStart(2,'0') + 's';
            return String(m).padStart(2,'0') + ':' + String(sec).padStart(2,'0');
        }

        // 新增方法：更新用户信息显示
        setUserInfo(uid, userName = null) {
            this.ensureMounted();
            if (this.elements.title) {
                let displayText;
                if (userName) {
                    displayText = t('user.title.user-name', '🖼️ User: {name}({id})', { name: userName, id: uid });
                } else {
                    displayText = t('user.title.user-id', '🖼️ User: {id}', { id: uid });
                }
                this.elements.title.innerText = displayText;
            }
        }

        async handleFetch(onlyNew) {
            if (!this.manager.userId) return alert(t('user.alert.no-user-id', '未检测到用户ID'));
            this.setStatus('正在获取作品列表...', 'info');
            try {
                const ids = await Api.getAllUserArtworkIds(this.manager.userId);
                if (!ids.length) return this.setStatus('该用户没有作品', 'warning');
                const added = this.manager.addItemsToQueue(ids);
                this.setStatus(`获取成功：共 ${ids.length} 个作品，新增 ${added} 个`, 'success');
                if (onlyNew) {
                    this.elements.skipHistory.checked = true;
                    this.manager.setSkipHistory(true);
                    this.updateSkipHistoryVisibility(true);
                    this.handleStart();
                }
            } catch (e) {
                this.setStatus('获取列表失败: ' + e.message, 'error');
            }
        }

        handleStart() {
            this.manager.start();
        }

        handlePause() {
            if (this.manager.isPaused) this.manager.resume(); else this.manager.pause();
        }

        handleRetry() {
            const failed = this.manager.queue.filter(q => q.status === 'failed');
            if (!failed.length) return alert(t('user.alert.no-failed', '当前没有失败的作品！'));
            failed.forEach(q => {
                q.status = 'pending';
                q.lastMessage = '';
                q.lastMessageParts = null;
                q.bookmarkResult = null;
                q.collectionResult = null;
                q.ugoiraProgress = null;
                q.imageProgress = null;
                q.startTime = null;
                q.endTime = null;
            });
            this.manager.saveToStorage();
            this.renderQueue(this.manager.queue);
            this.handleStart();
        }

        handleExportFailed() {
            const items = this.manager.queue.filter(q => q.status !== 'completed');
            if (items.length === 0) {
                alert(t('user.alert.no-undownloaded', '没有未下载的作品'));
                return;
            }
            const exportContent = items.map(item => `https://www.pixiv.net/artworks/${item.id}`).join('\n');
            const blob = new Blob([exportContent], { type: 'text/plain' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `pixiv_undownloaded_list_${this.manager.userId || 'unknown'}.txt`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            this.setStatus(`已导出 ${items.length} 个未下载作品`, 'success');
        }

        handleClear() {
            if (confirm(t('user.confirm.clear', '确认强制清除队列？'))) this.manager.stopAndClear(false);
        }

        handleExport() {
            if (!this.manager.queue || this.manager.queue.length === 0) {
                alert(t('user.alert.queue-empty-export', '队列为空，无内容可导出'));
                return;
            }
            
            const exportContent = this.manager.queue.map(item => `https://www.pixiv.net/artworks/${item.id}`).join('\n');
            const blob = new Blob([exportContent], { type: 'text/plain' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `pixiv_works_list_${this.manager.userId || 'unknown'}.txt`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            this.setStatus('导出成功', 'success');
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
                const detailProgress = formatImageDownloadProgressHtml(q.imageProgress, q.status)
                    + formatUgoiraProgressHtml(q.ugoiraProgress, q.status);
                const desc = translateStatusText(q.lastMessage || this._statusText(q.status));
                const descHtml = renderQueueDescHtml(q, desc, (s) => this._colorByStatus(s));
                const canRemove = q.status !== 'downloading';
                const removeBtn = canRemove
                    ? `<button data-remove-id="${q.id}" title="${t('common.action.remove', '从队列移除')}" style="background:none;border:none;color:#aaa;cursor:pointer;font-size:11px;padding:1px 2px;line-height:1;">✕</button>`
                    : '';
                const linkBtn = `<a href="https://www.pixiv.net/artworks/${q.id}" target="_blank" title="${t('common.action.open-artwork', '打开作品页面')}" style="color:#007bff;font-size:11px;padding:1px 2px;text-decoration:none;line-height:1;">🔗</a>`;
                item.innerHTML = `<div style="display:flex;justify-content:space-between;align-items:center;"><strong style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:4px;">${escapeHtml(q.title || 'ID: ' + q.id)}</strong><span style="display:flex;gap:1px;flex-shrink:0;">${linkBtn}${removeBtn}</span></div><div>ID: ${q.id} | ${descHtml}</div>${progressHtml}${detailProgress}`;
                node.appendChild(item);
            }
            node.onclick = (e) => {
                const btn = e.target.closest('[data-remove-id]');
                if (btn) { e.stopPropagation(); this.manager.removeFromQueue(btn.dataset.removeId); }
            };
        }

        setCurrent(item) {
            const container = this.elements.currentDownload;
            if (!item) {
                container.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${t('common.current.none', '无')}`;
                return;
            }
            const progressHtml = this._createProgressHtml(item, true);
            const detailProgress = formatImageDownloadProgressHtml(item.imageProgress, item.status)
                + formatUgoiraProgressHtml(item.ugoiraProgress, item.status);
            container.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${escapeHtml(item.title)} (ID: ${item.id})${progressHtml}${detailProgress}`;
        }

        updateStats(stats) {
            const pendingCount = this.manager.queue.filter(q => ['pending', 'paused', 'idle'].includes(q.status)).length;
            this.elements.stats.textContent = t('common.stats.summary', '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}', {
                pending: pendingCount,
                success: stats.success,
                failed: stats.failed,
                active: stats.active,
                skipped: stats.skipped
            });
        }

        setStatus(msg, type = 'info') {
            this.elements.status.innerText = translateStatusText(msg);
            const color = {
                'info': '#007bff',
                'success': '#28a745',
                'error': '#dc3545',
                'warning': '#ffc107'
            }[type] || '#666';
            this.elements.status.style.color = color;
        }

        updateButtonsState(isRunning, isPaused) {
            this.elements.startBtn.disabled = isRunning;
            this.elements.pauseBtn.disabled = !isRunning;
            this.elements.pauseBtn.innerText = isPaused
                ? t('common.button.resume', '▶️ 继续下载')
                : t('common.button.pause', '⏸️ 暂停下载');
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

        _createProgressHtml(q, isMain = false) {
            if (q.totalImages <= 0) return '';
            const downloadedCount = q.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / q.totalImages) * 100), 100);
            return `<div style="margin-top: 3px;"><div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;"><span>${isMain ? t('common.progress.current', '已下载 {count} 张 / 共 {total} 张', { count: downloadedCount, total: q.totalImages }) : t('common.progress.downloaded', '已下载: {count}/{total}', { count: downloadedCount, total: q.totalImages })}</span><span>${progressPercent}%</span></div><div style="width: 100%; height: ${isMain ? '6px' : '4px'}; background: #e0e0e0; border-radius: 2px; overflow: hidden;"><div style="height: 100%; background: ${isMain ? '#28a745' : '#007bff'}; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }
    }

    const ui = new UI();
    const manager = new DownloadManager(ui);
    ui.bindManager(manager);

    PixivUserscriptI18n.onChange(() => {
        if (ui.root) {
            const collapsed = ui._collapsed;
            const userId = manager.userId;
            const userName = manager.userName || username;
            ui.root.remove();
            const fab = document.getElementById('user-batch-mini-fab');
            if (fab) fab.remove();
            ui._build();
            ui.bindManager(manager);
            if (userId) {
                ui.setUserInfo(userId, userName);
            }
            ui.renderQueue(manager.queue);
            ui.updateStats(manager.stats);
            ui.updateButtonsState(manager.isRunning, manager.isPaused);
            ui.setCurrent(manager.queue.find(item => item.status === 'downloading') || null);
            if (quotaInfo && quotaInfo.enabled) {
                ui.updateQuotaBar(quotaInfo);
            }
            ui._collapsed = !collapsed;
            ui.toggleCollapse();
        }
    });
    PixivUserscriptI18n.enrichFromBackend(serverBase);

    // 首次启动提示
    checkExternalServerNotice();

    // 第一步：检查登录状态，结果供后续所有后端操作使用
    const _authPromise = checkLoginStatus();

    // 初始化配额（仅执行一次，且必须在 auth 通过后）
    let quotaInitDone = false;
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
    function maybeInitQuota() {
        if (quotaInitDone) return;
        quotaInitDone = true;
        _authPromise.then(authed => {
            if (!authed) return;
            Api.initQuota().then(data => {
                if (!data.enabled) return;
                quotaInfo = {
                    enabled: true,
                    artworksUsed: data.artworksUsed || 0,
                    maxArtworks: data.maxArtworks || 50,
                    resetSeconds: data.resetSeconds || 0
                };
                ui.updateQuotaBar(quotaInfo);
                startQuotaResetCountdown();
                // 恢复已有的压缩包链接
                if (data.archive && data.archive.token) {
                    ui.restoreArchiveCard(data.archive.token, data.archive.expireSeconds,
                        data.archive.status === 'ready');
                }
            }).catch(() => {});
        });
    }

    let lastUid = null;
    setInterval(() => {
        const uid = getCurrentUserId();
        if (uid) {
            ui.ensureMounted();
            if (lastUid !== uid) {
                lastUid = uid;
                // 先更新用户ID
                manager.initForUser(uid);
                ui.setUserInfo(uid); // 初始只显示ID

                // 异步获取用户名并更新UI
                Api.getUserMeta(uid).then(name => {
                    username = name || uid;   // 获取失败就用 userId
                    manager.setUserName(username); // 更新manager中的用户名
                    console.log("[Pixiv Batch] Loaded username:", username);
                }).catch(err => {
                    username = uid;
                    manager.setUserName(uid);
                    console.warn("[Pixiv Batch] 获取用户名异常，使用 uid 作为 username", err);
                });

                // 初始化配额
                maybeInitQuota();
            }
        } else {
            ui.hide();
            lastUid = null;
        }
    }, 1000);

    GM_registerMenuCommand(t('user.menu.open', '强制打开下载面板'), () => {
        ui.ensureMounted();
        if (ui.root) ui.root.style.display = 'block';
    });

    // 当其他面板展开时，自动收起本面板
    document.addEventListener('pixiv_panel_active', e => {
        if ((e.detail === 'ntab' || e.detail === 'page') && ui && !ui._collapsed) {
            ui.toggleCollapse();
        }
    });
})();
