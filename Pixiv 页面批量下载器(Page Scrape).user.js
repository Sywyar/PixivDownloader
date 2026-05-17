// ==UserScript==
// @name         Pixiv 页面批量下载器
// @namespace    http://tampermonkey.net/
// @version      1.1.0
// @updateURL    https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/Pixiv%20%E9%A1%B5%E9%9D%A2%E6%89%B9%E9%87%8F%E4%B8%8B%E8%BD%BD%E5%99%A8(Page%20Scrape).user.js
// @description  抓取当前 Pixiv 页面（搜索页、关注动态、排行榜、主页等）上的所有作品
// @author       Sywyar
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

    /* ========== 页面类型判断 ========== */
    const isUserPage = (href) => /\/users\/\d+/.test(href);
    const isSingleArtworkPage = (href) => /\/artworks\/\d+/.test(href);
    const isHomePage = (href) => /^https:\/\/www\.pixiv\.net\/(en\/)?$/.test(href);
    // Page 脚本在非主页、非用户页、非单作品页时默认展开，其他页面收起为 FAB（从不隐藏）
    const shouldDefaultExpand = () => !isUserPage(location.href) && !isSingleArtworkPage(location.href) && !isHomePage(location.href);

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

    const CONFIG = {
        get BACKEND_URL() {
            return serverBase + '/api/download/pixiv';
        },
        get STATUS_URL() {
            return serverBase + '/api/download/status';
        },
        get CANCEL_URL() {
            return serverBase + '/api/download/cancel';
        },
        get CLEAR_QUEUE_URL() {
            return serverBase + '/api/download/queue/clear';
        },
        get SSE_BASE() {
            return serverBase + '/api/sse/download';
        },
        get SSE_CLOSE_BASE() {
            return serverBase + '/api/sse/close/aggregated';
        },
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
        STORAGE_KEY: 'pixiv_page_batch_v1',
        KEY_SKIP_HISTORY: 'pixiv_global_skip_history',
        KEY_VERIFY_HISTORY_FILES: 'pixiv_global_verify_history_files',
        KEY_R18_ONLY: 'pixiv_global_r18_only',
        KEY_INTERVAL: 'pixiv_global_interval',
        KEY_INTERVAL_UNIT: 'pixiv_global_interval_unit',
        KEY_IMAGE_DELAY: 'pixiv_global_image_delay',
        KEY_IMAGE_DELAY_UNIT: 'pixiv_global_image_delay_unit',
        KEY_CONCURRENT: 'pixiv_global_concurrent',
        KEY_USER_UUID: 'pixiv_user_uuid',
        KEY_BOOKMARK: 'pixiv_global_bookmark',
        KEY_NOVEL_FORMAT: 'pixiv_global_novel_format',
        KEY_NOVEL_MERGE: 'pixiv_global_novel_merge',
        KEY_NOVEL_MERGE_FORMAT: 'pixiv_global_novel_merge_format',
        KEY_PAGE_KIND: 'pixiv_page_batch_kind',
        KEY_QUEUE_FRAME: 'pixiv_page_batch_queue_frame',
        KEY_QUEUE_FRAME_WIDTH: 'pixiv_page_batch_queue_frame_width',
        KEY_QUEUE_FRAME_COLOR: 'pixiv_page_batch_queue_frame_color',
        KEY_QUEUE_FRAME_STYLE: 'pixiv_page_batch_queue_frame_style'
    };

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
        try {
            if (typeof BroadcastChannel !== 'undefined') bc = new BroadcastChannel(CHAN_NAME);
        } catch (e) {
        }
        if (bc) bc.addEventListener('message', ev => {
            if (ev && ev.data && ev.data.type === 'claim' && ev.data.id) claimed.add(ev.data.id);
        });
        const readState = () => {
            try {
                return JSON.parse(localStorage.getItem(LS_KEY) || '{}') || {};
            } catch (e) {
                return {};
            }
        };
        const writeState = s => {
            try {
                localStorage.setItem(LS_KEY, JSON.stringify(s));
            } catch (e) {
            }
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
            if (isFresh(state[id], opts)) {
                claimed.add(id);
                return false;
            }
            claimed.add(id);
            state[id] = {t: Date.now(), owner: ownerId};
            writeState(state);
            if (bc) {
                try {
                    bc.postMessage({type: 'claim', id});
                } catch (e) {
                }
            }
            // 30ms jitter：让同帧启动的对端有机会覆盖 owner，最终只有一个 ownerId 留存并执行 fn
            setTimeout(() => {
                const cur = readState()[id];
                if (!cur || cur.owner !== ownerId) return;
                try {
                    fn();
                } catch (e) {
                    console.error('[PromptGuard]', e);
                }
            }, 30);
            return true;
        }

        return {once};
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

        let DICT = {'en-US': {}, 'zh-CN': {}};
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
            } catch (e) {
            }
            try {
                if (typeof GM_getValue === 'function') {
                    const stored = normalize(GM_getValue(GM_KEY, null));
                    if (stored) return stored;
                }
            } catch (e) {
            }
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
            } catch (e) {
            }
            try {
                window.addEventListener('storage', ev => {
                    if (ev.key !== LS_KEY) return;
                    const next = normalize(ev.newValue);
                    if (next && next !== currentLang) {
                        applyLang(next, false);
                    }
                });
            } catch (e) {
            }
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
                    } catch (e) {
                    }
                }, 1000);
            } catch (e) {
            }
        }

        function applyLang(lang, broadcast) {
            const next = normalize(lang) || DEFAULT_LANG;
            currentLang = next;
            if (broadcast) {
                try {
                    localStorage.setItem(LS_KEY, next);
                } catch (e) {
                }
                try {
                    if (typeof GM_setValue === 'function') GM_setValue(GM_KEY, next);
                } catch (e) {
                }
                if (bc) {
                    try {
                        bc.postMessage({type: 'lang-changed', lang: next});
                    } catch (e) {
                    }
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
                            } catch (e) {
                            }
                        }
                    });
                } catch (e) {
                }
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
        } catch (e) {
        }
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
            'page.title': '📄 Pixiv Page Batch Downloader',
            'page.fab.title': 'Page Batch Downloader',
            'page.setting.interval': 'Artwork interval:',
            'page.setting.image-delay': 'Image delay:',
            'page.setting.concurrent': 'Max concurrency:',
            'page.setting.skip-history': 'Skip download history',
            'page.setting.r18-only': 'R18 only',
            'page.setting.bookmark': 'Auto-bookmark after download',
            'page.setting.queue-frame': 'Border on queued items',
            'page.frame.settings-title': '🔲 Border settings',
            'page.frame.width': 'Border width (px):',
            'page.frame.color': 'Border color:',
            'page.frame.style': 'Border style:',
            'page.frame.style-solid': 'Solid',
            'page.frame.style-dashed': 'Dashed',
            'page.frame.style-double': 'Double',
            'page.setting.verify-history-files': 'Verify saved directory',
            'page.setting.server': 'Server URL:',
            'page.button.scrape': '📷 Scrape artworks on this page',
            'page.button.scrape-current': '🎯 Queue current artwork',
            'page.button.scrape-related': '🧩 Queue related artworks',
            'page.button.start': '🚀 Start Batch Download',
            'page.button.retry': '🔁 Retry Failed Artworks',
            'page.button.export': '📤 Export Download List',
            'page.button.export-failed': '📋 Export Undownloaded List',
            'page.button.clear': '🗑️ Clear Queue',
            'page.alert.backend-unavailable': 'Backend is unavailable. If you use a non-localhost server, replace @connect YOUR_SERVER_HOST in the userscript header as described in README.',
            'page.alert.no-failed': 'There are no failed artworks right now.',
            'page.alert.queue-empty-export': 'Queue is empty. Nothing to export.',
            'page.alert.no-undownloaded': 'There are no undownloaded artworks.',
            'page.confirm.clear': 'Force clear the queue? This force-stops and deletes all related download tasks (including the artwork currently downloading, no matter how much has been downloaded). Terminated downloads will not be recorded and cannot be undone.',
            'page.status.started': 'Batch download started ({concurrent} concurrent, {interval}ms interval)',
            'page.status.finished': 'Batch download finished',
            'page.status.finished-packing': 'Batch download finished. Preparing archive...',
            'page.status.fetching-meta': 'Fetching info: {id}',
            'page.status.downloading': 'Downloading: {title}',
            'page.status.skipped-existing': 'Skipped: {title} (already downloaded)',
            'page.status.failed-missing': 'Failed: {title} (missing files)',
            'page.status.completed': 'Completed: {title}',
            'page.status.failed': 'Failed: {title}',
            'page.status.need-login-stop': 'Login required. Download stopped',
            'page.status.quota-exceeded': 'Download limit reached',
            'page.status.error': 'Error: {title} - {message}',
            'page.status.pause-waiting': 'Pausing... waiting for {count} active task(s)',
            'page.status.paused': 'Paused',
            'page.status.resumed': 'Resumed',
            'page.status.cleared': 'Queue was force-cleared',
            'page.status.no-links': 'No artwork links were found on the current page',
            'page.status.scraped': 'Scraped {total} artworks on this page, {added} added to queue',
            'page.status.not-artwork-page': 'This is not an artwork page',
            'page.status.current-added': 'Added artwork {id} to queue',
            'page.status.current-exists': 'Artwork {id} is already in the queue',
            'page.status.fetching-related': 'Fetching related artworks...',
            'page.status.no-related': 'No related artworks found',
            'page.status.related-added': 'Related artworks: {total} found, {added} added to queue',
            'page.status.related-failed': 'Failed to fetch related artworks: {message}',
            'page.status.exported': 'Exported {count} artworks',
            'page.status.exported-undownloaded': 'Exported {count} undownloaded artworks',
            'page.status.need-login-refresh': 'Login required. Please log in and refresh the page',
            'page.overlay.remove': 'Queued. Click to remove',
            'page.overlay.add': 'Click to add to queue',
            'page.menu.open': 'Open Pixiv Page Batch Downloader',
            'page.msg.reset-on-refresh': 'Reset after refresh',
            'page.msg.checking-history': 'Checking download history...',
            'page.msg.skip-history-exists': 'Skipped — already in download history',
            'page.msg.fetching-meta': 'Fetching artwork info...',
            'page.msg.skip-non-r18': 'Skipped — not R18 content',
            'page.msg.submitting': 'Submitting download...',
            'page.msg.skip-downloaded-confirmed': 'Skipped — already downloaded (server-confirmed)',
            'page.msg.downloading-wait': 'Downloading, waiting for completion...',
            'page.msg.failed-detail': 'Failed — {message}',
            'page.msg.backend-reported-failure': 'Backend reported failure',
            'page.msg.failed-timeout': 'Failed — timed out waiting for completion status',
            'page.msg.failed-status-query': 'Failed — status query error',
            'page.msg.failed-need-login': 'Failed - login required',
            'page.msg.failed-quota': 'Failed - quota reached',
            'page.archive.packing-after-complete': 'Download complete, packaging',
            'page.err.need-login': 'Login required',
            'page.err.backend-failed': 'Backend request failed',
            'page.err.no-image-url': 'No image URL',
            'page.kind.illust': 'Illust+Manga+Ugoira',
            'page.kind.novel': 'Novels',
            'page.button.scrape-novel': '📕 Scrape novels on this page',
            'page.status.no-novel-links': 'No novel links were found on the current page',
            'page.status.scraped-novel': 'Scraped {total} novels on this page, {added} added to queue',
            'page.novel.settings-title': '📕 Novel Settings',
            'page.novel.format-label': 'Novel format:',
            'page.novel.format-txt': 'Plain text (TXT)',
            'page.novel.format-html': 'Web page (HTML)',
            'page.novel.format-epub': 'eBook (EPUB)',
            'page.novel.merge-label': 'Consolidate series',
            'page.novel.merge-format-epub': 'eBook (EPUB, recommended)',
            'page.novel.merge-format-txt': 'Plain text (TXT)',
            'page.novel.merge-format-html': 'Web page (HTML)',
            'page.novel.merge-format-hint': 'EPUB recommended: embeds cover & inline images, builds a clickable Novel → Chapter multi-level table of contents, and carries title/author/synopsis so it shows on a reader bookshelf. TXT/HTML are plain-text / single-page fallbacks without images.',
            'page.novel.tag': 'Novel',
            'page.msg.novel-series-merged': 'Series consolidated file generated (series {id})',
            'page.msg.novel-completed': 'Completed',
            'page.msg.novel-stage': 'Stage: {stage}',
            'page.msg.novel-stage-images': 'Stage: downloading inline images ({done}/{total})',
            'page.novel.stage.pending': 'Queued',
            'page.novel.stage.preparing': 'Preparing',
            'page.novel.stage.downloading-images': 'Downloading inline images',
            'page.novel.stage.writing': 'Generating file',
            'page.novel.stage.downloading-cover': 'Downloading cover',
            'page.novel.stage.saving': 'Saving',
            'page.novel.stage.bookmarking': 'Bookmarking',
            'page.novel.stage.collecting': 'Adding to collection',
            'page.novel.stage.completed': 'Completed',
            'page.novel.stage.cancelled': 'Cancelled'
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
            'common.progress.current': '已下载 {count} / {total}',
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
            'page.title': '📄 Pixiv 页面批量下载器',
            'page.fab.title': '页面批量下载器',
            'page.setting.interval': '作品间隔:',
            'page.setting.image-delay': '图片间隔:',
            'page.setting.concurrent': '最大并发数:',
            'page.setting.skip-history': '跳过历史下载',
            'page.setting.r18-only': '仅R18作品',
            'page.setting.bookmark': '下载后自动收藏',
            'page.setting.queue-frame': '抓取项加边框',
            'page.frame.settings-title': '🔲 边框设置',
            'page.frame.width': '边框宽度(px):',
            'page.frame.color': '边框颜色:',
            'page.frame.style': '边框样式:',
            'page.frame.style-solid': '实线',
            'page.frame.style-dashed': '虚线',
            'page.frame.style-double': '双线',
            'page.setting.verify-history-files': '实际目录检测',
            'page.setting.server': '服务器地址:',
            'page.button.scrape': '📷 抓取当前页面作品',
            'page.button.scrape-current': '🎯 抓取当前作品',
            'page.button.scrape-related': '🧩 抓取相关作品',
            'page.button.start': '🚀 开始批量下载',
            'page.button.retry': '🔁 重新下载失败的作品',
            'page.button.export': '📤 导出下载列表',
            'page.button.export-failed': '📋 导出未下载列表',
            'page.button.clear': '🗑️ 清除队列',
            'page.alert.backend-unavailable': '后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址',
            'page.alert.no-failed': '当前没有失败的作品！',
            'page.alert.queue-empty-export': '队列为空，无内容可导出',
            'page.alert.no-undownloaded': '没有未下载的作品',
            'page.confirm.clear': '确认强制清除队列？此操作会强制停止并删除所有相关下载任务（包括正在下载中的作品，无论已下载多少），被终止的下载不会被记录，且无法恢复。',
            'page.status.started': '开始下载 (并发:{concurrent}, 间隔:{interval}ms)',
            'page.status.finished': '批量下载结束',
            'page.status.finished-packing': '批量下载结束，正在打包文件...',
            'page.status.fetching-meta': '获取信息：{id}',
            'page.status.downloading': '下载中：{title}',
            'page.status.skipped-existing': '跳过：{title}（已下载）',
            'page.status.failed-missing': '失败：{title} (文件缺失)',
            'page.status.completed': '完成：{title}',
            'page.status.failed': '失败：{title}',
            'page.status.need-login-stop': '需要登录，已停止下载',
            'page.status.quota-exceeded': '已达到下载限额',
            'page.status.error': '错误：{title} - {message}',
            'page.status.pause-waiting': '正在暂停... (等待 {count} 个当前任务完成)',
            'page.status.paused': '已暂停',
            'page.status.resumed': '继续下载',
            'page.status.cleared': '已强制清除队列',
            'page.status.no-links': '当前页面未找到任何作品链接',
            'page.status.scraped': '抓取完成：页面共 {total} 个作品，新增 {added} 个到队列',
            'page.status.not-artwork-page': '当前不在单作品页面',
            'page.status.current-added': '已将当前作品 {id} 加入队列',
            'page.status.current-exists': '作品 {id} 已在队列中',
            'page.status.fetching-related': '正在获取相关作品...',
            'page.status.no-related': '未找到相关作品',
            'page.status.related-added': '相关作品：共 {total} 个，新增 {added} 个到队列',
            'page.status.related-failed': '获取相关作品失败：{message}',
            'page.status.exported': '已导出 {count} 个作品',
            'page.status.exported-undownloaded': '已导出 {count} 个未下载作品',
            'page.status.need-login-refresh': '需要登录，请登录后刷新页面',
            'page.overlay.remove': '已加入队列，点击移出',
            'page.overlay.add': '点击加入队列',
            'page.menu.open': '打开 Pixiv 页面批量下载器',
            'page.msg.reset-on-refresh': '刷新后重置',
            'page.msg.checking-history': '正在检查历史记录...',
            'page.msg.skip-history-exists': '跳过 — 历史记录中已存在',
            'page.msg.fetching-meta': '正在获取作品信息...',
            'page.msg.skip-non-r18': '跳过 — 非 R18 内容',
            'page.msg.submitting': '正在提交下载...',
            'page.msg.skip-downloaded-confirmed': '跳过 — 已下载（服务器确认）',
            'page.msg.downloading-wait': '下载中，等待完成...',
            'page.msg.failed-detail': '失败 — {message}',
            'page.msg.backend-reported-failure': '后端报告失败',
            'page.msg.failed-timeout': '失败 — 超时未收到完成状态',
            'page.msg.failed-status-query': '失败 — 状态查询异常',
            'page.msg.failed-need-login': '失败 - 需要登录',
            'page.msg.failed-quota': '失败 - 达到限额',
            'page.archive.packing-after-complete': '下载完成，正在打包',
            'page.err.need-login': '需要登录',
            'page.err.backend-failed': '后端返回失败',
            'page.err.no-image-url': '无图片URL',
            'page.kind.illust': '插画+漫画+动图',
            'page.kind.novel': '小说',
            'page.button.scrape-novel': '📕 抓取当前页面小说',
            'page.status.no-novel-links': '当前页面未找到小说链接',
            'page.status.scraped-novel': '抓取完成：页面共 {total} 篇小说，新增 {added} 篇到队列',
            'page.novel.settings-title': '📕 小说设置',
            'page.novel.format-label': '小说格式:',
            'page.novel.format-txt': '纯文本（TXT）',
            'page.novel.format-html': '网页（HTML）',
            'page.novel.format-epub': '电子书（EPUB）',
            'page.novel.merge-label': '系列生成合订本',
            'page.novel.merge-format-epub': '电子书（EPUB，推荐）',
            'page.novel.merge-format-txt': '纯文本（TXT）',
            'page.novel.merge-format-html': '网页（HTML）',
            'page.novel.merge-format-hint': '推荐 EPUB：内嵌封面与插图、按「小说 → 章节」生成可跳转的多级目录、带书名/作者/简介等信息可在阅读器书架显示；TXT/HTML 为无插图的纯文本 / 单页备选。',
            'page.novel.tag': '小说',
            'page.msg.novel-series-merged': '小说系列合订本已生成（系列 {id}）',
            'page.msg.novel-completed': '完成',
            'page.msg.novel-stage': '阶段：{stage}',
            'page.msg.novel-stage-images': '阶段：下载内嵌图片（{done}/{total}）',
            'page.novel.stage.pending': '排队中',
            'page.novel.stage.preparing': '准备中',
            'page.novel.stage.downloading-images': '下载内嵌图片',
            'page.novel.stage.writing': '生成文件',
            'page.novel.stage.downloading-cover': '下载封面',
            'page.novel.stage.saving': '保存中',
            'page.novel.stage.bookmarking': '收藏中',
            'page.novel.stage.collecting': '加入收藏夹',
            'page.novel.stage.completed': '已完成',
            'page.novel.stage.cancelled': '已取消'
        }
    });

    const t = (key, fallback, args) => PixivUserscriptI18n.t(key, fallback, args);

    const STATUS_TRANSLATORS = [
        [/^队列为空$/, () => t('common.queue.empty', '队列为空')],
        [/^开始下载 \(并发:(\d+), 间隔:(\d+)ms\)$/, (_, concurrent, interval) => t('page.status.started', '开始下载 (并发:{concurrent}, 间隔:{interval}ms)', {
            concurrent: concurrent,
            interval: interval
        })],
        [/^批量下载结束$/, () => t('page.status.finished', '批量下载结束')],
        [/^批量下载结束，正在打包文件\.\.\.$/, () => t('page.status.finished-packing', '批量下载结束，正在打包文件...')],
        [/^获取信息：(.+)$/, (_, id) => t('page.status.fetching-meta', '获取信息：{id}', {id: id})],
        [/^下载中：(.+)$/, (_, title) => t('page.status.downloading', '下载中：{title}', {title: title})],
        [/^跳过：(.+)（已下载）$/, (_, title) => t('page.status.skipped-existing', '跳过：{title}（已下载）', {title: title})],
        [/^失败：(.+) \(文件缺失\)$/, (_, title) => t('page.status.failed-missing', '失败：{title} (文件缺失)', {title: title})],
        [/^完成：(.+)$/, (_, title) => t('page.status.completed', '完成：{title}', {title: title})],
        [/^失败：(.+)$/, (_, title) => t('page.status.failed', '失败：{title}', {title: title})],
        [/^需要登录，已停止下载$/, () => t('page.status.need-login-stop', '需要登录，已停止下载')],
        [/^已达到下载限额$/, () => t('page.status.quota-exceeded', '已达到下载限额')],
        [/^错误：(.+) - (.+)$/, (_, title, message) => t('page.status.error', '错误：{title} - {message}', {
            title: title,
            message: message
        })],
        [/^正在暂停\.\.\. \(等待 (\d+) 个当前任务完成\)$/, (_, count) => t('page.status.pause-waiting', '正在暂停... (等待 {count} 个当前任务完成)', {count: count})],
        [/^已暂停$/, () => t('page.status.paused', '已暂停')],
        [/^继续下载$/, () => t('page.status.resumed', '继续下载')],
        [/^已强制清除队列$/, () => t('page.status.cleared', '已强制清除队列')],
        [/^当前页面未找到任何作品链接$/, () => t('page.status.no-links', '当前页面未找到任何作品链接')],
        [/^抓取完成：页面共 (\d+) 个作品，新增 (\d+) 个到队列$/, (_, total, added) => t('page.status.scraped', '抓取完成：页面共 {total} 个作品，新增 {added} 个到队列', {
            total: total,
            added: added
        })],
        [/^当前页面未找到小说链接$/, () => t('page.status.no-novel-links', '当前页面未找到小说链接')],
        [/^抓取完成：页面共 (\d+) 篇小说，新增 (\d+) 篇到队列$/, (_, total, added) => t('page.status.scraped-novel', '抓取完成：页面共 {total} 篇小说，新增 {added} 篇到队列', {
            total: total,
            added: added
        })],
        [/^当前不在单作品页面$/, () => t('page.status.not-artwork-page', '当前不在单作品页面')],
        [/^已将当前作品 (\d+) 加入队列$/, (_, id) => t('page.status.current-added', '已将当前作品 {id} 加入队列', {id: id})],
        [/^作品 (\d+) 已在队列中$/, (_, id) => t('page.status.current-exists', '作品 {id} 已在队列中', {id: id})],
        [/^正在获取相关作品\.\.\.$/, () => t('page.status.fetching-related', '正在获取相关作品...')],
        [/^未找到相关作品$/, () => t('page.status.no-related', '未找到相关作品')],
        [/^相关作品：共 (\d+) 个，新增 (\d+) 个到队列$/, (_, total, added) => t('page.status.related-added', '相关作品：共 {total} 个，新增 {added} 个到队列', {
            total: total,
            added: added
        })],
        [/^获取相关作品失败：(.+)$/, (_, message) => t('page.status.related-failed', '获取相关作品失败：{message}', {message: message})],
        [/^已导出 (\d+) 个作品$/, (_, count) => t('page.status.exported', '已导出 {count} 个作品', {count: count})],
        [/^已导出 (\d+) 个未下载作品$/, (_, count) => t('page.status.exported-undownloaded', '已导出 {count} 个未下载作品', {count: count})],
        [/^需要登录，请登录后刷新页面$/, () => t('page.status.need-login-refresh', '需要登录，请登录后刷新页面')],
        [/^刷新后重置$/, () => t('page.msg.reset-on-refresh', '刷新后重置')],
        [/^正在检查历史记录\.\.\.$/, () => t('page.msg.checking-history', '正在检查历史记录...')],
        [/^跳过 — 历史记录中已存在$/, () => t('page.msg.skip-history-exists', '跳过 — 历史记录中已存在')],
        [/^正在获取作品信息\.\.\.$/, () => t('page.msg.fetching-meta', '正在获取作品信息...')],
        [/^跳过 — 非 R18 内容$/, () => t('page.msg.skip-non-r18', '跳过 — 非 R18 内容')],
        [/^正在提交下载\.\.\.$/, () => t('page.msg.submitting', '正在提交下载...')],
        [/^跳过 — 已下载（服务器确认）$/, () => t('page.msg.skip-downloaded-confirmed', '跳过 — 已下载（服务器确认）')],
        [/^下载中，等待完成\.\.\.$/, () => t('page.msg.downloading-wait', '下载中，等待完成...')],
        [/^失败 — 超时未收到完成状态$/, () => t('page.msg.failed-timeout', '失败 — 超时未收到完成状态')],
        [/^失败 — 状态查询异常$/, () => t('page.msg.failed-status-query', '失败 — 状态查询异常')],
        [/^失败 - 需要登录$/, () => t('page.msg.failed-need-login', '失败 - 需要登录')],
        [/^失败 - 达到限额$/, () => t('page.msg.failed-quota', '失败 - 达到限额')],
        [/^下载完成，正在打包$/, () => t('page.archive.packing-after-complete', '下载完成，正在打包')],
        [/^失败 — (.+)$/, (_, message) => t('page.msg.failed-detail', '失败 — {message}', {message: message})]
    ];

    function novelStageLabel(stage) {
        if (!stage) return '';
        return t('page.novel.stage.' + stage, stage);
    }

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

    /* ========== 初始化提示（跨脚本只显示一次）========== */
    function checkExternalServerNotice() {
        // 兼容旧的 GM_setValue 标记：历史用户已看过则继续跳过
        if (GM_getValue('pixiv_connect_notice_shown', false)) return;
        PromptGuard.once('connect-notice', {persist: true}, () => {
            GM_setValue('pixiv_connect_notice_shown', true);
            alert(t('common.dialog.connect-notice', null, {serverBase: serverBase}));
        });
    }

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
        PromptGuard.once('unauthorized', {ttlMs: 60000}, () => {
            alert(t('common.dialog.unauthorized', '后端服务需要登录验证，即将为您打开登录页面...'));
            window.open(serverBase + '/login.html', '_blank');
        });
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

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        })[c]);
    }

    /* ========== 进度/后处理结果渲染辅助 ========== */
    function uiLang() {
        try {
            return PixivUserscriptI18n.getLang();
        } catch (e) {
            return 'zh-CN';
        }
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
        return {text, tone};
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
        const parts = [{text: base, tone: baseTone}].concat(postDownloadOutcomeParts(data));
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
        return {...(existing || {}), ...incoming};
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
            ? t('common.image-download.index', '第 {current}/{total} 张', {
                current: progress.imageNumber,
                total: progress.totalImages
            })
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

    /* ========== 页面作品 ID 抓取 ========== */
    function scrapePageArtworkIds() {
        const seen = new Set();
        const ids = [];
        document.querySelectorAll('a[href]').forEach(a => {
            const m = a.href.match(/\/artworks\/(\d+)/);
            if (m && !seen.has(m[1])) {
                seen.add(m[1]);
                ids.push(m[1]);
            }
        });
        return ids;
    }

    /* ========== 页面小说 ID 抓取 ========== */
    function scrapePageNovelIds() {
        const seen = new Set();
        const ids = [];
        document.querySelectorAll('a[href]').forEach(a => {
            const m = a.href.match(/\/novel\/show\.php\?[^#]*\bid=(\d+)/);
            if (m && !seen.has(m[1])) {
                seen.add(m[1]);
                ids.push(m[1]);
            }
        });
        return ids;
    }

    /* ========== API 封装 ========== */
    const Api = {
        checkBackend() {
            return new Promise(resolve => {
                GM_xmlhttpRequest({
                    method: 'GET', url: CONFIG.STATUS_URL, timeout: 2000,
                    onload: (res) => resolve(res.status === 200),
                    onerror: () => resolve(false), ontimeout: () => resolve(false)
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
        getRelatedArtworks(artworkId, limit = 18) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `https://www.pixiv.net/ajax/illust/${artworkId}/recommend/init?limit=${limit}`,
                    headers: {Referer: `https://www.pixiv.net/artworks/${artworkId}`},
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
        _seriesMetaPromises: new Map(),
        getSeriesEnrichment(seriesId) {
            const sid = Number(seriesId);
            if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
            if (this._seriesMetaPromises.has(sid)) return this._seriesMetaPromises.get(sid);
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
                        } catch (_) {
                            resolve(null);
                        }
                    },
                    onerror: () => resolve(null)
                });
            });
            this._seriesMetaPromises.set(sid, p);
            return p;
        },
        async sendDownloadRequest(artworkId, imageUrls, title, authorId, authorName, xRestrict, isAi, ugoiraData, delayMs, bookmark, description, tags, seriesInfo) {
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
            if (seriesInfo && seriesInfo.seriesId) {
                other.seriesId = Number(seriesInfo.seriesId);
                other.seriesOrder = Number(seriesInfo.seriesOrder ?? 0);
                other.seriesTitle = seriesInfo.seriesTitle || null;
                const enrich = await this.getSeriesEnrichment(seriesInfo.seriesId);
                if (enrich) {
                    if (enrich.caption) other.seriesDescription = enrich.caption;
                    if (enrich.coverUrl) other.seriesCoverUrl = enrich.coverUrl;
                }
            }
            return new Promise((resolve, reject) => {
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
                    method: 'POST', url: CONFIG.BACKEND_URL, headers, data: JSON.stringify(payload),
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (res.status === 401) {
                                handleUnauthorized();
                                reject(new Error(t('page.err.need-login', '需要登录')));
                            } else if (res.status === 429 && data.quotaExceeded) {
                                const err = new Error('quota_exceeded');
                                err.quotaData = data;
                                reject(err);
                            } else if (res.status === 200) {
                                resolve(data);
                            } else {
                                reject(new Error(data.message || t('page.err.backend-failed', '后端返回失败')));
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
                    method: 'GET', url: `${CONFIG.STATUS_URL}/${artworkId}`,
                    onload: (res) => {
                        if (res.status === 401) {
                            handleUnauthorized();
                            reject(new Error(t('page.err.need-login', '需要登录')));
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
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'POST', url: `${CONFIG.CANCEL_URL}/${artworkId}`,
                    onload: () => resolve(true), onerror: () => resolve(false)
                });
            });
        },
        clearQueue() {
            // 强制清除后端队列并终止所有正在进行的下载（多人模式下后端仅终止当前 owner 的任务）
            const headers = {};
            if (userUUID) headers['X-User-UUID'] = userUUID;
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'POST', url: CONFIG.CLEAR_QUEUE_URL, headers,
                    onload: () => resolve(true), onerror: () => resolve(false)
                });
            });
        },
        checkDownloaded(artworkId, verifyFiles = false) {
            return new Promise((resolve) => {
                const query = verifyFiles ? '?verifyFiles=true' : '';
                GM_xmlhttpRequest({
                    method: 'GET', url: `${serverBase}/api/downloaded/${artworkId}${query}`,
                    onload: (res) => {
                        try {
                            resolve(res.status === 200 && !!JSON.parse(res.responseText).artworkId);
                        } catch {
                            resolve(false);
                        }
                    },
                    onerror: () => resolve(false), ontimeout: () => resolve(false)
                });
            });
        },
        getNovelMeta(novelId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${serverBase}/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`,
                    headers: {'X-Pixiv-Cookie': document.cookie || ''},
                    onload: (res) => {
                        if (res.status === 401) {
                            handleUnauthorized();
                            reject(new Error(t('page.err.need-login', '需要登录')));
                            return;
                        }
                        try {
                            const data = JSON.parse(res.responseText);
                            if (res.status < 200 || res.status >= 300) {
                                reject(new Error(data.error || ('meta HTTP ' + res.status)));
                            } else {
                                resolve(data);
                            }
                        } catch (e) {
                            reject(e);
                        }
                    },
                    onerror: reject
                });
            });
        },
        checkNovelDownloaded(novelId) {
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${serverBase}/api/gallery/novel/${encodeURIComponent(novelId)}`,
                    onload: (res) => resolve(res.status === 200),
                    onerror: () => resolve(false), ontimeout: () => resolve(false)
                });
            });
        },
        sendNovelDownloadRequest(body) {
            return new Promise((resolve, reject) => {
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
                            reject(new Error(t('page.err.need-login', '需要登录')));
                        } else if (res.status === 429 && data.quotaExceeded) {
                            const err = new Error('quota_exceeded');
                            err.quotaData = data;
                            reject(err);
                        } else if (res.status === 200) {
                            resolve(data);
                        } else {
                            reject(new Error(data.message || t('page.err.backend-failed', '后端返回失败')));
                        }
                    },
                    onerror: reject
                });
            });
        },
        getNovelStatus(novelId) {
            return new Promise((resolve, reject) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${serverBase}/api/download/novel/status/${encodeURIComponent(novelId)}`,
                    onload: (res) => {
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
        _novelSeriesMetaPromises: new Map(),
        getNovelSeriesEnrichment(seriesId) {
            const sid = Number(seriesId);
            if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
            if (this._novelSeriesMetaPromises.has(sid)) return this._novelSeriesMetaPromises.get(sid);
            const p = new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${serverBase}/api/pixiv/novel/series/${sid}?page=1`,
                    headers: {'X-Pixiv-Cookie': document.cookie || ''},
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            const meta = data && data.series ? data.series : null;
                            resolve(meta ? {
                                caption: meta.caption || '',
                                coverUrl: meta.coverUrl || '',
                                tags: Array.isArray(meta.tags) ? meta.tags : []
                            } : null);
                        } catch (_) {
                            resolve(null);
                        }
                    },
                    onerror: () => resolve(null)
                });
            });
            this._novelSeriesMetaPromises.set(sid, p);
            return p;
        },
        initQuota() {
            const headers = {'Content-Type': 'application/json'};
            if (userUUID) headers['X-User-UUID'] = userUUID;
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'POST', url: CONFIG.QUOTA_INIT_URL, headers,
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
                    onerror: () => resolve({}), ontimeout: () => resolve({})
                });
            });
        },
        pollArchiveStatus(token) {
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET', url: `${CONFIG.ARCHIVE_STATUS_BASE}/${token}`,
                    onload: (res) => {
                        try {
                            resolve(JSON.parse(res.responseText));
                        } catch {
                            resolve({});
                        }
                    },
                    onerror: () => resolve({}), ontimeout: () => resolve({})
                });
            });
        }
    };

    // >>> SHARED:sse-manager.js
    /* ========== SSE 管理器（共享单连接版：所有作品复用同一条聚合 SSE，按 artworkId 路由） ========== */
    class SSEManager {
        constructor() {
            this.handle = null;
            this.reader = null;
            this.connected = false;
            this.connecting = false;
            this.batchActive = false;
            this.listeners = new Map();        // artworkId -> [fn]
            this.activeArtworks = new Set();   // 当前需要监听的作品集合
            this.connectionId = null;
            this.closing = false;
            this._buffer = '';
            this._closeTimer = null;
            this._reconnectTimer = null;
        }

        // 兼容旧调用点的语义：
        // open(id) / close(id) 只增删作品监听；共享连接由批量任务生命周期统一开关
        openShared() {
            this._cancelDeferredClose();
            if (this.closing) this._cleanup();
            this.batchActive = true;
            this.closing = false;
            this._ensureConnection();
        }

        open(artworkId) {
            const key = String(artworkId);
            this.activeArtworks.add(key);
        }

        close(artworkId) {
            const key = String(artworkId);
            this.activeArtworks.delete(key);
            this.listeners.delete(key);
        }

        closeAll() {
            this.batchActive = false;
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
            this.closing = false;
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
                    ontimeout: () => {
                        this._cleanup();
                        this._scheduleReconnect();
                    }
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
                    if (done) {
                        const shouldReconnect = !this.closing;
                        this._cleanup();
                        if (shouldReconnect) this._scheduleReconnect();
                        return;
                    }
                    const chunk = decoder.decode(value, {stream: true});
                    this._buffer += chunk;
                    const parts = this._buffer.split('\n\n');
                    this._buffer = parts.pop(); // 最后一段可能不完整，留作缓冲
                    for (const part of parts) {
                        if (!part.trim()) continue;
                        this._processEvent(part);
                    }
                    if (!this.reader) return;
                    pump();
                }).catch(() => {
                    const shouldReconnect = !this.closing;
                    this._cleanup();
                    if (shouldReconnect) this._scheduleReconnect();
                });
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
            if (eventName === 'aggregated-ready') {
                this.connectionId = dataLines.join('\n').trim() || null;
                return;
            }
            if (eventName === 'sse-closing') {
                this.closing = true;
                this._cleanup();
                return;
            }
            if (eventName !== 'download-status' || dataLines.length === 0) return;
            try {
                const parsed = JSON.parse(dataLines.join('\n'));
                const aid = parsed && parsed.artworkId !== undefined && parsed.artworkId !== null
                    ? String(parsed.artworkId) : null;
                if (!aid) return;
                const fns = this.listeners.get(aid);
                if (fns) fns.forEach(fn => fn(parsed));
            } catch (e) { /* 握手 / 心跳等非 download-status JSON 忽略 */
            }
        }

        _scheduleReconnect() {
            if (this.closing) return;
            if (!this.batchActive) return;
            if (this._reconnectTimer) return;
            this._reconnectTimer = setTimeout(() => {
                this._reconnectTimer = null;
                if (this.batchActive && !this.connected && !this.connecting) {
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
            if (this._closeTimer) {
                clearTimeout(this._closeTimer);
                this._closeTimer = null;
            }
        }

        _closeNow() {
            this._cancelDeferredClose();
            if (this._reconnectTimer) {
                clearTimeout(this._reconnectTimer);
                this._reconnectTimer = null;
            }
            if (!this.connected && !this.connecting && !this.handle && !this.reader) {
                this.connectionId = null;
                this.closing = false;
                return;
            }
            this.closing = true;
            const connectionId = this.connectionId;
            this.connectionId = null;
            if (connectionId) this._notifyAggregatedClose(connectionId);
            setTimeout(() => {
                if (this.closing) this._cleanup();
            }, 1000);
        }

        _notifyAggregatedClose(connectionId) {
            const headers = {};
            if (userUUID) headers['X-User-UUID'] = userUUID;
            try {
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: `${CONFIG.SSE_CLOSE_BASE}/${encodeURIComponent(connectionId)}`,
                    headers,
                    timeout: 2000,
                    onload: () => {
                    },
                    onerror: () => {
                    },
                    ontimeout: () => {
                    }
                });
            } catch (e) {
            }
        }

        _cleanup() {
            this.connected = false;
            this.connecting = false;
            this.connectionId = null;
            if (this.reader) {
                try {
                    this.reader.cancel();
                } catch (e) {
                }
                this.reader = null;
            }
            if (this.handle) {
                try {
                    this.handle.abort();
                } catch (e) {
                }
                this.handle = null;
            }
            this._buffer = '';
            this.closing = false;
        }
    }

    // <<< SHARED:sse-manager.js
    /* ========== 下载管理器 ========== */
    class DownloadManager {
        constructor(ui) {
            this.ui = ui;
            this.queue = [];
            this.isRunning = false;
            this.isPaused = false;

            this.stopRequested = false;
            this.activeWorkers = 0;
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
                bookmark: GM_getValue(CONFIG.KEY_BOOKMARK, false),
                novelFormat: GM_getValue(CONFIG.KEY_NOVEL_FORMAT, 'txt'),
                mergeNovelSeries: GM_getValue(CONFIG.KEY_NOVEL_MERGE, false),
                mergeNovelFormat: GM_getValue(CONFIG.KEY_NOVEL_MERGE_FORMAT, 'epub'),
                pageKind: GM_getValue(CONFIG.KEY_PAGE_KIND, 'illust') === 'novel' ? 'novel' : 'illust',
                queueFrame: GM_getValue(CONFIG.KEY_QUEUE_FRAME, true) !== false,
                queueFrameWidth: Math.min(12, Math.max(1, parseInt(GM_getValue(CONFIG.KEY_QUEUE_FRAME_WIDTH, 3), 10) || 3)),
                queueFrameColor: GM_getValue(CONFIG.KEY_QUEUE_FRAME_COLOR, '#17a2b8') || '#17a2b8',
                queueFrameStyle: GM_getValue(CONFIG.KEY_QUEUE_FRAME_STYLE, 'solid') || 'solid'
            };
        }

        loadFromStorage() {
            try {
                const raw = GM_getValue(CONFIG.STORAGE_KEY, null);
                if (!raw) return;
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed.queue)) {
                    this.queue = parsed.queue.map(q =>
                        q.status === 'downloading' ? {
                            ...q,
                            status: 'idle',
                            lastMessage: '刷新后重置',
                            lastMessageParts: null
                        } : q
                    );
                    this.isPaused = !!parsed.isPaused;
                    this.stats = parsed.stats || {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
                    this.updateStats();
                    this.ui.updateOverlays();
                }
            } catch {
                this.queue = [];
            }
        }

        saveToStorage() {
            try {
                GM_setValue(CONFIG.STORAGE_KEY, JSON.stringify({
                    queue: this.queue, isPaused: this.isPaused, stats: this.stats,
                    savedAt: new Date().toISOString()
                }));
            } catch {
            }
        }

        deleteStorage() {
            try {
                GM_deleteValue(CONFIG.STORAGE_KEY);
            } catch {
            }
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

        setNovelFormat(val) {
            this.globalSettings.novelFormat = val;
            GM_setValue(CONFIG.KEY_NOVEL_FORMAT, val);
        }

        setMergeNovelSeries(val) {
            this.globalSettings.mergeNovelSeries = !!val;
            GM_setValue(CONFIG.KEY_NOVEL_MERGE, !!val);
        }

        setMergeNovelFormat(val) {
            this.globalSettings.mergeNovelFormat = (val || 'epub').toLowerCase();
            GM_setValue(CONFIG.KEY_NOVEL_MERGE_FORMAT, this.globalSettings.mergeNovelFormat);
        }

        // 队列结束后，对已完成的小说按其所属系列各生成一次合订本
        _mergeFinishedNovelSeries() {
            if (!this.globalSettings.mergeNovelSeries) return;
            const seriesIds = new Set();
            for (const q of this.queue) {
                if (q.kind === 'novel' && q.status === 'completed' && Number(q.seriesId) > 0) {
                    seriesIds.add(Number(q.seriesId));
                }
            }
            if (!seriesIds.size) return;
            const fmt = (this.globalSettings.mergeNovelFormat || 'epub').toLowerCase();
            for (const sid of seriesIds) {
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: `${serverBase}/api/gallery/novel/series/${encodeURIComponent(sid)}/merge?format=${encodeURIComponent(fmt)}`,
                    headers: {'X-User-UUID': userUUID || ''},
                    onload: () => this.ui && this.ui.setStatus(
                        t('page.msg.novel-series-merged', '小说系列合订本已生成（系列 {id}）', {id: sid}), 'success'),
                    onerror: () => console.warn('[PageScrape] novel series merge failed', sid)
                });
            }
        }

        setPageKind(val) {
            this.globalSettings.pageKind = val === 'novel' ? 'novel' : 'illust';
            GM_setValue(CONFIG.KEY_PAGE_KIND, this.globalSettings.pageKind);
        }

        setQueueFrame(val) {
            this.globalSettings.queueFrame = val !== false;
            GM_setValue(CONFIG.KEY_QUEUE_FRAME, this.globalSettings.queueFrame);
            if (this.ui && this.ui.updateFrameSettingsVisibility) {
                this.ui.updateFrameSettingsVisibility(this.globalSettings.queueFrame);
            }
            if (this.ui && this.ui.updateOverlays) this.ui.updateOverlays();
        }

        setQueueFrameWidth(val) {
            let n = parseInt(val, 10);
            if (!Number.isFinite(n)) n = 3;
            this.globalSettings.queueFrameWidth = Math.min(12, Math.max(1, n));
            GM_setValue(CONFIG.KEY_QUEUE_FRAME_WIDTH, this.globalSettings.queueFrameWidth);
            if (this.ui && this.ui.updateOverlays) this.ui.updateOverlays();
        }

        setQueueFrameColor(val) {
            this.globalSettings.queueFrameColor = val || '#17a2b8';
            GM_setValue(CONFIG.KEY_QUEUE_FRAME_COLOR, this.globalSettings.queueFrameColor);
            if (this.ui && this.ui.updateOverlays) this.ui.updateOverlays();
        }

        setQueueFrameStyle(val) {
            this.globalSettings.queueFrameStyle = ['solid', 'dashed', 'double'].includes(val) ? val : 'solid';
            GM_setValue(CONFIG.KEY_QUEUE_FRAME_STYLE, this.globalSettings.queueFrameStyle);
            if (this.ui && this.ui.updateOverlays) this.ui.updateOverlays();
        }

        setInterval(val) {
            this.globalSettings.interval = Math.max(0, parseFloat(val) || 0);
            GM_setValue(CONFIG.KEY_INTERVAL, this.globalSettings.interval);
        }

        setIntervalUnit(unit) {
            this.globalSettings.intervalUnit = unit;
            GM_setValue(CONFIG.KEY_INTERVAL_UNIT, unit);
        }

        setImageDelay(val) {
            this.globalSettings.imageDelay = Math.max(0, parseFloat(val) || 0);
            GM_setValue(CONFIG.KEY_IMAGE_DELAY, this.globalSettings.imageDelay);
        }

        setImageDelayUnit(unit) {
            this.globalSettings.imageDelayUnit = unit;
            GM_setValue(CONFIG.KEY_IMAGE_DELAY_UNIT, unit);
        }

        setConcurrent(val) {
            let n = parseInt(val) || CONFIG.DEFAULT_CONCURRENT;
            if (n < 1) n = 1;
            this.globalSettings.concurrent = n;
            GM_setValue(CONFIG.KEY_CONCURRENT, n);
        }

        getIntervalMs() {
            const {interval, intervalUnit} = this.globalSettings;
            return intervalUnit === 's' ? Math.round(interval * 1000) : Math.round(interval);
        }

        getImageDelayMs() {
            const {imageDelay, imageDelayUnit} = this.globalSettings;
            return imageDelayUnit === 's' ? Math.round(imageDelay * 1000) : Math.round(imageDelay);
        }

        addItemsToQueue(idList, kind = 'illust') {
            const isNovel = kind === 'novel';
            const existingIds = new Set(this.queue.map(q => q.id));
            let added = 0;
            const newItems = [];
            for (const rawId of idList) {
                const nid = String(rawId);
                const qid = isNovel ? 'n' + nid : nid;
                if (!existingIds.has(qid)) {
                    newItems.push({
                        id: qid,
                        kind: isNovel ? 'novel' : 'illust',
                        novelId: isNovel ? nid : null,
                        title: isNovel ? `小说 ${nid}` : `ID: ${nid}`,
                        url: isNovel
                            ? `https://www.pixiv.net/novel/show.php?id=${nid}`
                            : `https://www.pixiv.net/artworks/${nid}`,
                        status: 'idle', totalImages: 0, downloadedCount: 0,
                        startTime: null, endTime: null, lastMessage: '',
                        lastMessageParts: null,
                        bookmarkResult: null,
                        collectionResult: null,
                        ugoiraProgress: null,
                        imageProgress: null
                    });
                    added++;
                }
            }
            this.queue = [...this.queue, ...newItems];
            this.updateStats();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            this.ui.updateOverlays();
            return added;
        }

        async start() {
            if (this.queue.length === 0) {
                this.ui.setStatus('队列为空', 'error');
                return;
            }
            if (!await Api.checkBackend()) {
                alert(t('page.alert.backend-unavailable', '后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址'));
                return;
            }

            const intervalMs = this.getIntervalMs();
            const maxConcurrent = this.globalSettings.concurrent;

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

            this.sse.openShared();
            try {
                const workers = [];
                for (let i = 0; i < Math.max(1, maxConcurrent); i++) workers.push(this.workerLoop(intervalMs));
                await Promise.all(workers);
            } finally {
                this.sse.closeAll();
            }

            this.isRunning = false;
            this.saveToStorage();
            this.ui.setStatus('批量下载结束', 'info');
            this.ui.updateButtonsState(false, false);
            this._mergeFinishedNovelSeries();

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
                        method: 'POST', url: serverBase + '/api/quota/pack', headers,
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
                        onerror: () => resolve(null), ontimeout: () => resolve(null)
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
                        await this._sleep(500);
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
                        console.error(e);
                    } finally {
                        await this._sleep(intervalMs);
                    }
                }
            } finally {
                this.activeWorkers--;
            }
        }

        _getNextPending() {
            const downloadingIds = new Set(this.queue.filter(q => q.status === 'downloading').map(q => q.id));
            const idx = this.queue.findIndex(q => q.status === 'pending' && !downloadingIds.has(q.id));
            if (idx === -1) return null;
            this.queue[idx].status = 'downloading';
            this.queue[idx].startTime = new Date().toISOString();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return {idx, item: this.queue[idx]};
        }

        async _processNovel({item}) {
            item.lastMessageParts = null;
            item.bookmarkResult = null;
            item.collectionResult = null;
            item.ugoiraProgress = null;
            item.imageProgress = null;
            const novelId = item.novelId || String(item.id).replace(/^n/, '');
            item.lastMessage = '正在检查历史记录...';
            this.ui.renderQueue(this.queue);
            try {
                if (this.globalSettings.skipHistory) {
                    const downloaded = await Api.checkNovelDownloaded(novelId);
                    if (downloaded) {
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
                this.ui.setStatus(`获取信息：${novelId}`, 'info');
                this.ui.renderQueue(this.queue);

                const meta = await Api.getNovelMeta(novelId);

                if (this.globalSettings.r18Only && Number(meta.xRestrict || 0) < 1) {
                    item.status = 'skipped';
                    item.lastMessage = '跳过 — 非 R18 内容';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    return;
                }

                item.title = meta.title || item.title;
                item.totalImages = 1;
                item.downloadedCount = 0;
                this.saveToStorage();
                this.ui.setStatus(`下载中：${item.title}`, 'info');
                this.ui.renderQueue(this.queue);

                const fmt = (this.globalSettings.novelFormat || 'txt').toLowerCase();
                const seriesInfo = meta.seriesId ? {
                    seriesId: meta.seriesId,
                    seriesOrder: meta.seriesOrder,
                    seriesTitle: meta.seriesTitle
                } : null;
                if (seriesInfo && seriesInfo.seriesId) item.seriesId = Number(seriesInfo.seriesId);
                const seriesEnrich = seriesInfo
                    ? await Api.getNovelSeriesEnrichment(seriesInfo.seriesId)
                    : null;
                const cookie = document.cookie || '';
                const body = {
                    novelId: Number(novelId),
                    title: meta.title,
                    cookie: cookie || null,
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
                        bookmark: !!this.globalSettings.bookmark,
                        collectionId: null,
                        format: fmt,
                        uploadTimestamp: meta.uploadTimestamp || null,
                        coverUrl: meta.coverUrl || '',
                        embeddedImages: meta.textEmbeddedImages || {}
                    }
                };

                const dlData = await Api.sendNovelDownloadRequest(body);
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

                item.lastMessage = '下载中，等待完成...';
                this.ui.renderQueue(this.queue);

                const start = Date.now();
                while (Date.now() - start < CONFIG.STATUS_TIMEOUT_MS) {
                    await this._sleep(800);
                    let status;
                    try {
                        status = await Api.getNovelStatus(novelId);
                    } catch (_) {
                        continue;
                    }
                    if (status && status.completed) {
                        if (status.failed) {
                            item.status = 'failed';
                            item.lastMessage = `失败 — ${status.message || t('page.msg.backend-reported-failure', '后端报告失败')}`;
                            this.ui.setStatus(`失败：${item.title}`, 'error');
                        } else {
                            item.status = 'completed';
                            item.downloadedCount = 1;
                            item.bookmarkResult = status.bookmarkResult || null;
                            item.collectionResult = status.collectionResult || null;
                            const baseMessage = t('page.msg.novel-completed', '完成');
                            item.lastMessage = appendPostDownloadOutcome(baseMessage, status);
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', status);
                            this.ui.setStatus(`完成：${item.title}`, 'success');
                            if (quotaInfo.enabled) {
                                quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
                                this.ui.updateQuotaBar(quotaInfo);
                            }
                        }
                        item.endTime = new Date().toISOString();
                        this.updateStats();
                        this.saveToStorage();
                        this.ui.renderQueue(this.queue);
                        return;
                    }
                    if (status && status.stage) {
                        const eTotal = Number(status.embeddedTotal || 0);
                        const eDone = Number(status.embeddedDone || 0);
                        if (status.stage === 'downloading-images' && eTotal > 0) {
                            item.lastMessage = t('page.msg.novel-stage-images',
                                '阶段：下载内嵌图片（{done}/{total}）', {done: eDone, total: eTotal});
                        } else {
                            item.lastMessage = t('page.msg.novel-stage', '阶段：{stage}',
                                {stage: novelStageLabel(status.stage)});
                        }
                        this.ui.renderQueue(this.queue);
                    }
                }
                item.status = 'failed';
                item.lastMessage = '失败 — 超时未收到完成状态';
                this.ui.setStatus(`失败：${item.title}`, 'error');
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
                    this.ui.setStatus(`错误：${item.title} - ${item.lastMessage}`, 'error');
                }
            } finally {
                item.endTime = item.endTime || new Date().toISOString();
                this.updateStats();
                this.saveToStorage();
                this.ui.renderQueue(this.queue);
                this.ui.setCurrent(null);
            }
        }

        async _processSingle({item}) {
            if (item.kind === 'novel') {
                return this._processNovel({item});
            }
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
                // 系列导航：仅在 Pixiv 标注作品属于漫画系列时附带，封面/简介由 Api.getSeriesEnrichment 缓存查询。
                const nav = meta && meta.seriesNavData;
                const seriesInfo = (nav && Number(nav.seriesId) > 0) ? {
                    seriesId: Number(nav.seriesId),
                    seriesOrder: Number(nav.order || 0),
                    seriesTitle: nav.title || ''
                } : null;

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

                if (meta && meta.illustType === 2) {
                    const ugoiraMeta = await Api.getUgoiraMeta(item.id);
                    const zipSrc = ugoiraMeta.originalSrc || ugoiraMeta.src;
                    ugoiraData = {zipUrl: zipSrc, delays: ugoiraMeta.frames.map(f => f.delay)};
                    urls = [zipSrc];
                    item.totalImages = 1;
                } else if (meta && meta.pageCount === 1 && meta.urls && meta.urls.original) {
                    // 单页插画：meta 已带 original URL，无需再请求 /pages
                    urls = [meta.urls.original];
                    item.totalImages = 1;
                } else {
                    urls = await Api.getArtworkPages(item.id);
                    if (!urls || !urls.length) throw new Error(t('page.err.no-image-url', '无图片URL'));
                    item.totalImages = urls.length;
                }

                item.lastMessage = '正在提交下载...';
                this.saveToStorage();
                this.ui.renderQueue(this.queue);
                this.ui.setStatus(`下载中：${item.title}`, 'info');

                const dlData = await Api.sendDownloadRequest(item.id, urls, item.title, authorId, authorName, xRestrict, isAi, ugoiraData, this.getImageDelayMs(), this.globalSettings.bookmark, description, tags, seriesInfo);
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
                        if (quotaInfo.enabled) {
                            quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
                            this.ui.updateQuotaBar(quotaInfo);
                        }
                    }
                } else if (final && final.failed) {
                    item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
                    item.imageProgress = final.imageProgress || item.imageProgress || null;
                    item.status = 'failed';
                    item.lastMessage = `失败 — ${final.message || t('page.msg.backend-reported-failure', '后端报告失败')}`;
                    this.ui.setStatus(`失败：${item.title}`, 'error');
                } else {
                    try {
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
                    } catch {
                        item.status = 'failed';
                        item.lastMessage = '失败 — 状态查询异常';
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
                        this.queue.forEach(q => {
                            if (['pending', 'idle', 'paused'].includes(q.status)) {
                                q.status = 'failed';
                                q.lastMessage = '失败 - 达到限额';
                            }
                        });
                        this.stopRequested = true;
                        this.isRunning = false;
                        this.ui.setStatus('已达到下载限额', 'error');
                        if (e.quotaData) this.ui.showQuotaExceeded(e.quotaData);
                    }
                } else {
                    item.status = 'failed';
                    item.lastMessage = `失败 — ${e.message || String(e)}`;
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
            this.queue.forEach(q => {
                if (q.status === 'pending') q.status = 'paused';
            });
            this.saveToStorage();
            const activeCount = this.queue.filter(q => q.status === 'downloading').length;
            this.ui.updateButtonsState(true, true);
            this.ui.setStatus(activeCount > 0 ? `正在暂停... (等待 ${activeCount} 个当前任务完成)` : '已暂停', 'warning');
        }

        resume() {
            if (!this.isRunning) {
                this.start();
                return;
            }
            this.isPaused = false;
            this.queue.forEach(q => {
                if (q.status === 'paused') q.status = 'pending';
            });
            this.saveToStorage();
            this.ui.updateButtonsState(true, false);
            this.ui.setStatus('继续下载', 'info');
        }

        stopAndClear() {
            this.stopRequested = true;
            this.isRunning = false;
            this.isPaused = false;
            this.sse.closeAll();
            Api.clearQueue().catch(() => {
            });
            this.queue = [];
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
            this.deleteStorage();
            this.ui.renderQueue(this.queue);
            this.ui.updateButtonsState(false, false);
            this.ui.setStatus('已强制清除队列', 'info');
            this.ui.updateOverlays();
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
            this.ui.updateOverlays();
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
            this._collapsed = false;
            this._archivePollTimer = null;
            this._archiveCountdownTimer = null;
        }

        mount() {
            if (document.getElementById('pixiv-page-batch-ui')) return;
            this._injectOverlayStyles();
            this._build();
            if (this.manager) this.syncSettings();
            this.updateSinglePageButtonsVisibility();
        }

        _injectOverlayStyles() {
            if (document.getElementById('pixiv-queue-overlay-style')) return;
            const style = document.createElement('style');
            style.id = 'pixiv-queue-overlay-style';
            style.textContent = 'div.pixiv-queue-overlay{opacity:0;transition:opacity .15s,background .15s;}div.pixiv-queue-overlay.pqo-checked{opacity:1;}a:hover div.pixiv-queue-overlay{opacity:1;}div.pixiv-queue-overlay.pqo-unchecked:hover{opacity:1;}:hover>div.pixiv-queue-overlay{opacity:1;}';
            document.head.appendChild(style);
        }

        _build() {
            const container = $el('div', {
                id: 'pixiv-page-batch-ui',
                style: {
                    position: 'fixed', top: '160px', right: '80px', zIndex: 10000,
                    background: 'white', border: '2px solid #17a2b8', borderRadius: '8px',
                    padding: '15px', boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
                    minWidth: '400px', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    maxHeight: '80vh', overflowY: 'auto'
                }
            });

            // Title row
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
                innerText: '◀', title: t('common.action.collapse', '收起'),
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
            const titleEl = $el('div', {
                id: 'page-batch-ui-title',
                innerText: t('page.title', '📄 Pixiv 页面批量下载器'),
                style: {fontWeight: 'bold', color: '#333', textAlign: 'center', fontSize: '16px', flex: '1'}
            });
            collapseBtn.addEventListener('click', () => this.manualToggleCollapse());
            titleRow.appendChild(collapseBtn);
            titleRow.appendChild(titleEl);
            titleRow.appendChild(buildLangSwitcher());

            // Mini FAB
            const existingFab = document.getElementById('page-batch-mini-fab');
            if (existingFab) existingFab.remove();
            const miniFab = $el('button', {
                id: 'page-batch-mini-fab', innerText: '📄', title: t('page.fab.title', '页面批量下载器'),
                style: {
                    display: 'none',
                    position: 'fixed',
                    top: '160px',
                    right: '20px',
                    zIndex: '10001',
                    background: '#17a2b8',
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
            miniFab.addEventListener('click', () => this.manualToggleCollapse());
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
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('page.setting.interval', '作品间隔:')}</label>
                    <input type="number" id="pbd-interval" min="0" value="${CONFIG.DEFAULT_INTERVAL}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="pbd-interval-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">s</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('page.setting.image-delay', '图片间隔:')}</label>
                    <input type="number" id="pbd-image-delay" min="0" value="0"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="pbd-image-delay-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">ms</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('page.setting.concurrent', '最大并发数:')}</label>
                    <input type="number" id="pbd-concurrent" min="1" value="${CONFIG.DEFAULT_CONCURRENT}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px;">
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="pbd-skip-history" style="vertical-align: middle;"> ${t('page.setting.skip-history', '跳过历史下载')}
                    </label>
                    <label style="font-size: 12px; cursor:pointer; color:#d63384;">
                        <input type="checkbox" id="pbd-r18-only" style="vertical-align: middle;"> ${t('page.setting.r18-only', '仅R18作品')}
                    </label>
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="pbd-bookmark" style="vertical-align: middle;"> ${t('page.setting.bookmark', '下载后自动收藏')}
                    </label>
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="pbd-queue-frame" style="vertical-align: middle;"> ${t('page.setting.queue-frame', '抓取项加边框')}
                    </label>
                </div>
                <div id="pbd-frame-settings" style="display:none;margin:6px 0 10px;padding-top:8px;border-top:1px dashed #ddd;">
                    <div style="font-size:12px;font-weight:bold;color:#17a2b8;margin-bottom:8px;">${t('page.frame.settings-title', '🔲 边框设置')}</div>
                    <div style="display:flex;align-items:center;margin-bottom:6px;">
                        <label style="font-size:12px;margin-right:10px;width:120px;">${t('page.frame.width', '边框宽度(px):')}</label>
                        <input type="number" id="pbd-frame-width" min="1" max="12" step="1"
                               style="width:70px;padding:4px;border:1px solid #ddd;border-radius:4px;font-size:12px;">
                    </div>
                    <div style="display:flex;align-items:center;margin-bottom:6px;">
                        <label style="font-size:12px;margin-right:10px;width:120px;">${t('page.frame.color', '边框颜色:')}</label>
                        <input type="color" id="pbd-frame-color"
                               style="width:48px;height:28px;padding:0;border:1px solid #ddd;border-radius:4px;cursor:pointer;">
                    </div>
                    <div style="display:flex;align-items:center;">
                        <label style="font-size:12px;margin-right:10px;width:120px;">${t('page.frame.style', '边框样式:')}</label>
                        <select id="pbd-frame-style" style="flex:1;padding:4px;border:1px solid #ddd;border-radius:4px;font-size:12px;">
                            <option value="solid">${t('page.frame.style-solid', '实线')}</option>
                            <option value="dashed">${t('page.frame.style-dashed', '虚线')}</option>
                            <option value="double">${t('page.frame.style-double', '双线')}</option>
                        </select>
                    </div>
                </div>
                <div id="pbd-kind-switcher" role="radiogroup" style="display:flex;gap:0;margin:6px 0 10px;border:1px solid #17a2b8;border-radius:6px;overflow:hidden;">
                    <label data-kind="illust" style="flex:1;text-align:center;font-size:12px;padding:6px 4px;cursor:pointer;">
                        <input type="radio" name="pbd-kind" value="illust" style="display:none;"> ${t('page.kind.illust', '插画+漫画+动图')}
                    </label>
                    <label data-kind="novel" style="flex:1;text-align:center;font-size:12px;padding:6px 4px;cursor:pointer;border-left:1px solid #17a2b8;">
                        <input type="radio" name="pbd-kind" value="novel" style="display:none;"> ${t('page.kind.novel', '小说')}
                    </label>
                </div>
                <div id="pbd-novel-settings" style="display:none;margin:6px 0 10px;padding-top:8px;border-top:1px dashed #ddd;">
                    <div style="font-size:12px;font-weight:bold;color:#0d9488;margin-bottom:8px;">${t('page.novel.settings-title', '📕 小说设置')}</div>
                    <div style="display:flex;align-items:center;">
                        <label style="font-size:12px;margin-right:10px;width:120px;">${t('page.novel.format-label', '小说格式:')}</label>
                        <select id="pbd-novel-format" style="flex:1;padding:4px;border:1px solid #ddd;border-radius:4px;font-size:12px;">
                            <option value="txt">${t('page.novel.format-txt', '纯文本（TXT）')}</option>
                            <option value="html">${t('page.novel.format-html', '网页（HTML）')}</option>
                            <option value="epub">${t('page.novel.format-epub', '电子书（EPUB）')}</option>
                        </select>
                    </div>
                    <div style="display:flex;align-items:center;margin-top:8px;">
                        <label style="font-size:12px;margin-right:10px;width:120px;cursor:pointer;">
                            <input type="checkbox" id="pbd-novel-merge" style="vertical-align:middle;margin-right:4px;">${t('page.novel.merge-label', '系列生成合订本')}</label>
                        <select id="pbd-novel-merge-format" style="flex:1;padding:4px;border:1px solid #ddd;border-radius:4px;font-size:12px;">
                            <option value="epub">${t('page.novel.merge-format-epub', '电子书（EPUB，推荐）')}</option>
                            <option value="txt">${t('page.novel.merge-format-txt', '纯文本（TXT）')}</option>
                            <option value="html">${t('page.novel.merge-format-html', '网页（HTML）')}</option>
                        </select>
                    </div>
                    <div style="font-size:11px;color:#888;line-height:1.5;margin-top:6px;">${t('page.novel.merge-format-hint', '推荐 EPUB：内嵌封面与插图、按「小说 → 章节」生成可跳转的多级目录、带书名/作者/简介等信息可在阅读器书架显示；TXT/HTML 为无插图的纯文本 / 单页备选。')}</div>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">${t('page.setting.server', '服务器地址:')}</label>
                    <label id="pbd-verify-history-files-row" style="display:none; font-size: 12px; cursor:pointer; margin-right: 10px;">
                        <input type="checkbox" id="pbd-verify-history-files" style="vertical-align: middle;"> ${t('page.setting.verify-history-files', '实际目录检测')}
                        <span title="${t('common.option.verify-history-files.tooltip', VERIFY_HISTORY_FILES_TOOLTIP)}" style="display:inline-flex;align-items:center;justify-content:center;width:14px;height:14px;border:1px solid #999;border-radius:50%;color:#666;font-size:10px;font-weight:700;line-height:1;cursor:help;user-select:none;vertical-align:middle;margin-left:4px;">?</span>
                    </label>
                    <input type="text" id="pbd-server-url" value="${serverBase}" placeholder="http://localhost:6999"
                           style="flex: 1; padding: 4px; border: 1px solid #ddd; border-radius: 4px; font-size: 12px;">
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
                    id: 'pbd-scrape-btn',
                    text: t('page.button.scrape', '📷 抓取当前页面作品'),
                    bgColor: '#17a2b8',
                    onClick: () => this.handleScrape()
                },
                {
                    id: 'pbd-scrape-current-btn',
                    text: t('page.button.scrape-current', '🎯 抓取当前作品'),
                    bgColor: '#17a2b8',
                    onClick: () => this.handleScrapeCurrent(),
                    singlePageOnly: true
                },
                {
                    id: 'pbd-scrape-related-btn',
                    text: t('page.button.scrape-related', '🧩 抓取相关作品'),
                    bgColor: '#17a2b8',
                    onClick: () => this.handleScrapeRelated(),
                    singlePageOnly: true
                },
                {
                    id: 'pbd-start-btn',
                    text: t('page.button.start', '🚀 开始批量下载'),
                    bgColor: '#28a745',
                    onClick: () => this.handleStart()
                },
                {
                    id: 'pbd-retry-btn',
                    text: t('page.button.retry', '🔁 重新下载失败的作品'),
                    bgColor: '#17a2b8',
                    onClick: () => this.handleRetry()
                },
                {
                    id: 'pbd-pause-btn',
                    text: t('common.button.pause', '⏸️ 暂停下载'),
                    bgColor: '#ffc107',
                    onClick: () => this.handlePause(),
                    disabled: true
                },
                {
                    id: 'pbd-export-btn',
                    text: t('page.button.export', '📤 导出下载列表'),
                    bgColor: '#007bff',
                    onClick: () => this.handleExport()
                },
                {
                    id: 'pbd-export-failed-btn',
                    text: t('page.button.export-failed', '📋 导出未下载列表'),
                    bgColor: '#6610f2',
                    onClick: () => this.handleExportFailed()
                },
                {
                    id: 'pbd-clear-btn',
                    text: t('page.button.clear', '🗑️ 清除队列'),
                    bgColor: '#6c757d',
                    onClick: () => this.handleClear()
                }
            ];
            const singlePageButtons = [];
            buttons.forEach(cfg => {
                const btn = $el('button', {
                    id: cfg.id, innerText: cfg.text,
                    style: {
                        width: '100%',
                        background: cfg.bgColor,
                        color: cfg.bgColor === '#ffc107' ? 'black' : 'white',
                        border: 'none',
                        padding: '10px',
                        borderRadius: '5px',
                        cursor: 'pointer',
                        fontSize: '14px'
                    }
                });
                btn.disabled = !!cfg.disabled;
                btn.addEventListener('click', cfg.onClick);
                if (cfg.singlePageOnly) {
                    btn.style.display = 'none';
                    singlePageButtons.push(btn);
                }
                buttonContainer.appendChild(btn);
            });
            this._singlePageButtons = singlePageButtons;

            const currentDownload = $el('div', {
                style: {
                    marginBottom: '10px',
                    padding: '8px',
                    background: '#f8f9fa',
                    borderRadius: '5px',
                    borderLeft: '4px solid #17a2b8',
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

            const quotaBar = $el('div', {
                id: 'pixiv-page-quota-bar',
                style: {
                    display: 'none',
                    marginBottom: '10px',
                    padding: '6px 8px',
                    background: '#f8f9fa',
                    borderRadius: '5px',
                    fontSize: '11px',
                    color: '#555'
                }
            });

            const archiveCard = $el('div', {
                id: 'pixiv-page-archive-card',
                style: {
                    display: 'none',
                    marginBottom: '10px',
                    padding: '10px',
                    background: '#fff8e1',
                    border: '2px solid #ffc107',
                    borderRadius: '5px',
                    fontSize: '12px'
                }
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
                title: titleEl, status, stats, currentDownload, queueContainer,
                interval: container.querySelector('#pbd-interval'),
                intervalUnitBtn: container.querySelector('#pbd-interval-unit-btn'),
                imageDelay: container.querySelector('#pbd-image-delay'),
                imageDelayUnitBtn: container.querySelector('#pbd-image-delay-unit-btn'),
                concurrent: container.querySelector('#pbd-concurrent'),
                skipHistory: container.querySelector('#pbd-skip-history'),
                verifyHistoryFiles: container.querySelector('#pbd-verify-history-files'),
                verifyHistoryFilesRow: container.querySelector('#pbd-verify-history-files-row'),
                r18Only: container.querySelector('#pbd-r18-only'),
                bookmark: container.querySelector('#pbd-bookmark'),
                queueFrame: container.querySelector('#pbd-queue-frame'),
                frameSettings: container.querySelector('#pbd-frame-settings'),
                frameWidth: container.querySelector('#pbd-frame-width'),
                frameColor: container.querySelector('#pbd-frame-color'),
                frameStyle: container.querySelector('#pbd-frame-style'),
                novelFormat: container.querySelector('#pbd-novel-format'),
                novelMerge: container.querySelector('#pbd-novel-merge'),
                novelMergeFormat: container.querySelector('#pbd-novel-merge-format'),
                kindSwitcher: container.querySelector('#pbd-kind-switcher'),
                novelSettings: container.querySelector('#pbd-novel-settings'),
                scrapeBtn: container.querySelector('#pbd-scrape-btn'),
                serverUrl: container.querySelector('#pbd-server-url'),
                startBtn: container.querySelector('#pbd-start-btn'),
                pauseBtn: container.querySelector('#pbd-pause-btn')
            };

            // Bind settings changes
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
            bindChange(this.elements.bookmark, (e) => this.manager && this.manager.setBookmark(e.target.checked));
            if (this.elements.queueFrame) {
                bindChange(this.elements.queueFrame, (e) => this.manager && this.manager.setQueueFrame(e.target.checked));
            }
            if (this.elements.frameWidth) {
                bindChange(this.elements.frameWidth, (e) => this.manager && this.manager.setQueueFrameWidth(e.target.value));
            }
            if (this.elements.frameColor) {
                const applyColor = (e) => this.manager && this.manager.setQueueFrameColor(e.target.value);
                this.elements.frameColor.addEventListener('input', applyColor);
                this.elements.frameColor.addEventListener('change', applyColor);
            }
            if (this.elements.frameStyle) {
                bindChange(this.elements.frameStyle, (e) => this.manager && this.manager.setQueueFrameStyle(e.target.value));
            }
            if (this.elements.novelFormat) {
                bindChange(this.elements.novelFormat, (e) => this.manager && this.manager.setNovelFormat(e.target.value));
            }
            if (this.elements.novelMerge) {
                bindChange(this.elements.novelMerge, (e) => this.manager && this.manager.setMergeNovelSeries(e.target.checked));
            }
            if (this.elements.novelMergeFormat) {
                bindChange(this.elements.novelMergeFormat, (e) => this.manager && this.manager.setMergeNovelFormat(e.target.value));
            }
            if (this.elements.kindSwitcher) {
                this.elements.kindSwitcher.querySelectorAll('input[name="pbd-kind"]').forEach(radio => {
                    radio.addEventListener('change', (e) => {
                        if (!this.manager || !e.target.checked) return;
                        this.manager.setPageKind(e.target.value);
                        this.applyKindUI();
                    });
                });
            }
            bindChange(this.elements.interval, (e) => this.manager && this.manager.setInterval(e.target.value));
            bindChange(this.elements.imageDelay, (e) => this.manager && this.manager.setImageDelay(e.target.value));
            bindChange(this.elements.concurrent, (e) => this.manager && this.manager.setConcurrent(e.target.value));
            this.elements.serverUrl.addEventListener('change', (e) => {
                serverBase = e.target.value.trim().replace(/\/$/, '') || 'http://localhost:6999';
                GM_setValue(KEY_SERVER_URL, serverBase);
            });

            // Unit toggle: interval
            this.elements.intervalUnitBtn.addEventListener('click', () => {
                if (!this.manager) return;
                const cur = parseFloat(this.elements.interval.value) || 0;
                const curUnit = this.manager.globalSettings.intervalUnit;
                if (curUnit === 's') {
                    this.manager.setIntervalUnit('ms');
                    this.elements.interval.value = Math.round(cur * 1000);
                    this.elements.intervalUnitBtn.textContent = 'ms';
                } else {
                    this.manager.setIntervalUnit('s');
                    this.elements.interval.value = +(cur / 1000).toFixed(3);
                    this.elements.intervalUnitBtn.textContent = 's';
                }
                this.manager.setInterval(this.elements.interval.value);
            });

            // Unit toggle: image delay
            this.elements.imageDelayUnitBtn.addEventListener('click', () => {
                if (!this.manager) return;
                const cur = parseFloat(this.elements.imageDelay.value) || 0;
                const curUnit = this.manager.globalSettings.imageDelayUnit;
                if (curUnit === 's') {
                    this.manager.setImageDelayUnit('ms');
                    this.elements.imageDelay.value = Math.round(cur * 1000);
                    this.elements.imageDelayUnitBtn.textContent = 'ms';
                } else {
                    this.manager.setImageDelayUnit('s');
                    this.elements.imageDelay.value = +(cur / 1000).toFixed(3);
                    this.elements.imageDelayUnitBtn.textContent = 's';
                }
                this.manager.setImageDelay(this.elements.imageDelay.value);
            });
        }

        bindManager(manager) {
            this.manager = manager;
            if (this.root) this.syncSettings();
        }

        updateSkipHistoryVisibility(enabled) {
            if (!this.elements || !this.elements.verifyHistoryFilesRow) return;
            this.elements.verifyHistoryFilesRow.style.display = enabled ? 'flex' : 'none';
        }

        updateFrameSettingsVisibility(enabled) {
            if (!this.elements || !this.elements.frameSettings) return;
            this.elements.frameSettings.style.display = enabled ? 'block' : 'none';
        }

        syncSettings() {
            if (!this.manager || !this.elements.interval) return;
            const s = this.manager.globalSettings;
            this.elements.skipHistory.checked = s.skipHistory;
            this.elements.verifyHistoryFiles.checked = s.verifyHistoryFiles ?? false;
            this.elements.r18Only.checked = s.r18Only;
            this.elements.bookmark.checked = s.bookmark;
            if (this.elements.queueFrame) this.elements.queueFrame.checked = s.queueFrame !== false;
            if (this.elements.frameWidth) this.elements.frameWidth.value = s.queueFrameWidth ?? 3;
            if (this.elements.frameColor) this.elements.frameColor.value = s.queueFrameColor || '#17a2b8';
            if (this.elements.frameStyle) this.elements.frameStyle.value = s.queueFrameStyle || 'solid';
            this.updateFrameSettingsVisibility(s.queueFrame !== false);
            this.elements.interval.value = s.interval;
            this.elements.intervalUnitBtn.textContent = s.intervalUnit || 's';
            this.elements.imageDelay.value = s.imageDelay ?? 0;
            this.elements.imageDelayUnitBtn.textContent = s.imageDelayUnit || 'ms';
            this.elements.concurrent.value = s.concurrent;
            if (this.elements.novelFormat) {
                this.elements.novelFormat.value = s.novelFormat || 'txt';
            }
            if (this.elements.novelMerge) {
                this.elements.novelMerge.checked = !!s.mergeNovelSeries;
            }
            if (this.elements.novelMergeFormat) {
                this.elements.novelMergeFormat.value = s.mergeNovelFormat || 'epub';
            }
            if (this.elements.kindSwitcher) {
                const target = this.elements.kindSwitcher.querySelector(`input[value="${s.pageKind === 'novel' ? 'novel' : 'illust'}"]`);
                if (target) target.checked = true;
            }
            this.applyKindUI();
            this.updateSkipHistoryVisibility(s.skipHistory);
        }

        applyKindUI() {
            const isNovel = this.manager && this.manager.globalSettings.pageKind === 'novel';
            if (this.elements.kindSwitcher) {
                this.elements.kindSwitcher.querySelectorAll('label[data-kind]').forEach(lbl => {
                    const active = (lbl.dataset.kind === 'novel') === !!isNovel;
                    lbl.style.background = active ? '#17a2b8' : 'transparent';
                    lbl.style.color = active ? '#fff' : '#666';
                    lbl.style.fontWeight = active ? '700' : '400';
                });
            }
            if (this.elements.novelSettings) {
                this.elements.novelSettings.style.display = isNovel ? 'block' : 'none';
            }
            if (this.elements.scrapeBtn) {
                this.elements.scrapeBtn.innerText = isNovel
                    ? t('page.button.scrape-novel', '📕 抓取当前页面小说')
                    : t('page.button.scrape', '📷 抓取当前页面作品');
            }
        }

        toggleCollapse() {
            this._collapsed = !this._collapsed;
            const fab = document.getElementById('page-batch-mini-fab');
            if (this._collapsed) {
                if (this.root) this.root.style.display = 'none';
                if (fab) fab.style.display = 'block';
            } else {
                if (this.root) this.root.style.display = 'block';
                if (fab) fab.style.display = 'none';
                document.dispatchEvent(new CustomEvent('pixiv_panel_active', {detail: 'page'}));
            }
        }

        // 用户手动收起/展开：在切换后持久化状态
        manualToggleCollapse() {
            this.toggleCollapse();
            savePanelCollapsed(this._collapsed);
        }

        show() {
            if (!document.getElementById('pixiv-page-batch-ui')) this.mount();
            this._collapsed = false;
            if (this.root) this.root.style.display = 'block';
            const fab = document.getElementById('page-batch-mini-fab');
            if (fab) fab.style.display = 'none';
        }

        hide() {
            if (this.root) this.root.style.display = 'none';
            const fab = document.getElementById('page-batch-mini-fab');
            if (fab) fab.style.display = 'none';
        }

        handleScrape() {
            const isNovel = this.manager.globalSettings.pageKind === 'novel';
            if (isNovel) {
                const ids = scrapePageNovelIds();
                if (ids.length === 0) {
                    this.setStatus('当前页面未找到小说链接', 'warning');
                    return;
                }
                const added = this.manager.addItemsToQueue(ids, 'novel');
                this.setStatus(`抓取完成：页面共 ${ids.length} 篇小说，新增 ${added} 篇到队列`, 'success');
                return;
            }
            const ids = scrapePageArtworkIds();
            if (ids.length === 0) {
                this.setStatus('当前页面未找到任何作品链接', 'warning');
                return;
            }
            const added = this.manager.addItemsToQueue(ids, 'illust');
            this.setStatus(`抓取完成：页面共 ${ids.length} 个作品，新增 ${added} 个到队列`, 'success');
        }

        handleScrapeCurrent() {
            const m = location.pathname.match(/\/artworks\/(\d+)/);
            if (!m) {
                this.setStatus('当前不在单作品页面', 'warning');
                return;
            }
            const id = m[1];
            const added = this.manager.addItemsToQueue([id]);
            if (added > 0) this.setStatus(`已将当前作品 ${id} 加入队列`, 'success');
            else this.setStatus(`作品 ${id} 已在队列中`, 'warning');
        }

        async handleScrapeRelated() {
            const m = location.pathname.match(/\/artworks\/(\d+)/);
            if (!m) {
                this.setStatus('当前不在单作品页面', 'warning');
                return;
            }
            const currentId = m[1];
            this.setStatus('正在获取相关作品...', 'info');
            try {
                const body = await Api.getRelatedArtworks(currentId);
                const seen = new Set();
                const ids = [];
                const pushId = (rawId) => {
                    const s = String(rawId);
                    if (!/^\d+$/.test(s)) return;
                    if (s === currentId || seen.has(s)) return;
                    seen.add(s);
                    ids.push(s);
                };
                if (body && Array.isArray(body.illusts)) {
                    for (const it of body.illusts) if (it && it.id) pushId(it.id);
                }
                if (body && Array.isArray(body.nextIds)) {
                    for (const nid of body.nextIds) pushId(nid);
                }
                if (!ids.length) {
                    this.setStatus('未找到相关作品', 'warning');
                    return;
                }
                const added = this.manager.addItemsToQueue(ids);
                this.setStatus(`相关作品：共 ${ids.length} 个，新增 ${added} 个到队列`, 'success');
            } catch (e) {
                this.setStatus(`获取相关作品失败：${e.message || e}`, 'error');
            }
        }

        updateSinglePageButtonsVisibility() {
            if (!this._singlePageButtons) return;
            const show = /\/artworks\/\d+/.test(location.pathname);
            this._singlePageButtons.forEach(btn => {
                btn.style.display = show ? 'block' : 'none';
            });
        }

        handleStart() {
            this.manager && this.manager.start();
        }

        handlePause() {
            if (!this.manager) return;
            if (this.manager.isPaused) this.manager.resume(); else this.manager.pause();
        }

        handleRetry() {
            if (!this.manager) return;
            const failed = this.manager.queue.filter(q => q.status === 'failed');
            if (!failed.length) {
                alert(t('page.alert.no-failed', '当前没有失败的作品！'));
                return;
            }
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

        handleClear() {
            if (confirm(t('page.confirm.clear', '确认强制清除队列？此操作会强制停止并删除所有相关下载任务（包括正在下载中的作品，无论已下载多少），被终止的下载不会被记录，且无法恢复。'))) this.manager && this.manager.stopAndClear();
        }

        handleExport() {
            if (!this.manager || !this.manager.queue.length) {
                alert(t('page.alert.queue-empty-export', '队列为空，无内容可导出'));
                return;
            }
            const lines = this.manager.queue.map(item => `${item.kind === 'novel' ? 'https://www.pixiv.net/novel/show.php?id=' + (item.novelId || String(item.id).replace(/^n/, '')) : 'https://www.pixiv.net/artworks/' + item.id} | ${item.title}`);
            this._downloadTxt(lines.join('\n'), 'pixiv_page_all_list.txt');
            this.setStatus(`已导出 ${lines.length} 个作品`, 'success');
        }

        handleExportFailed() {
            if (!this.manager) return;
            const items = this.manager.queue.filter(q => q.status !== 'completed');
            if (!items.length) {
                alert(t('page.alert.no-undownloaded', '没有未下载的作品'));
                return;
            }
            const lines = items.map(item => `${item.kind === 'novel' ? 'https://www.pixiv.net/novel/show.php?id=' + (item.novelId || String(item.id).replace(/^n/, '')) : 'https://www.pixiv.net/artworks/' + item.id} | ${item.title}`);
            this._downloadTxt(lines.join('\n'), 'pixiv_page_undownloaded_list.txt');
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
                const desc = translateStatusText(q.lastMessage || this._statusText(q.status));
                const descHtml = renderQueueDescHtml(q, desc, (s) => this._colorByStatus(s));
                const detailProgress = formatImageDownloadProgressHtml(q.imageProgress, q.status)
                    + formatUgoiraProgressHtml(q.ugoiraProgress, q.status);
                const canRemove = q.status !== 'downloading';
                const removeBtn = canRemove
                    ? `<button data-remove-id="${q.id}" title="${t('common.action.remove', '从队列移除')}" style="background:none;border:none;color:#aaa;cursor:pointer;font-size:11px;padding:1px 2px;line-height:1;">✕</button>`
                    : '';
                const isNovel = q.kind === 'novel';
                const nid = isNovel ? (q.novelId || String(q.id).replace(/^n/, '')) : q.id;
                const linkHref = isNovel
                    ? `https://www.pixiv.net/novel/show.php?id=${nid}`
                    : `https://www.pixiv.net/artworks/${q.id}`;
                const linkBtn = `<a href="${linkHref}" target="_blank" title="${t('common.action.open-artwork', '打开作品页面')}" style="color:#007bff;font-size:11px;padding:1px 2px;text-decoration:none;line-height:1;">🔗</a>`;
                const novelTag = isNovel
                    ? `<span style="background:#0d9488;color:white;border-radius:3px;padding:0 4px;font-size:9px;margin-left:3px;vertical-align:middle;">📕 ${escapeHtml(t('page.novel.tag', '小说'))}</span>`
                    : '';
                const idLabel = isNovel ? `${nid} (Novel)` : q.id;
                item.innerHTML = `<div style="display:flex;justify-content:space-between;align-items:center;"><strong style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:4px;">${escapeHtml(q.title || 'ID: ' + q.id)}${novelTag}</strong><span style="display:flex;gap:1px;flex-shrink:0;">${linkBtn}${removeBtn}</span></div><div>ID: ${idLabel} | ${descHtml}</div>${this._progressHtml(q)}${detailProgress}`;
                node.appendChild(item);
            }
            node.onclick = (e) => {
                const btn = e.target.closest('[data-remove-id]');
                if (btn) {
                    e.stopPropagation();
                    this.manager.removeFromQueue(btn.dataset.removeId);
                }
            };
        }

        _progressHtml(q, isMain = false) {
            if (q.totalImages <= 0) return '';
            const pct = Math.min(Math.round(((q.downloadedCount || 0) / q.totalImages) * 100), 100);
            return `<div style="margin-top:3px;"><div style="display:flex;justify-content:space-between;font-size:9px;margin-bottom:2px;"><span>${isMain ? t('common.progress.current', '已下载 {count} / {total}', {
                count: q.downloadedCount || 0,
                total: q.totalImages
            }) : t('common.progress.downloaded', '已下载: {count}/{total}', {
                count: q.downloadedCount || 0,
                total: q.totalImages
            })}</span><span>${pct}%</span></div><div style="width:100%;height:${isMain ? 6 : 4}px;background:#e0e0e0;border-radius:2px;overflow:hidden;"><div style="height:100%;background:${isMain ? '#17a2b8' : '#007bff'};width:${pct}%;transition:width 0.3s;"></div></div></div>`;
        }

        setCurrent(item) {
            const c = this.elements.currentDownload;
            if (!item) {
                c.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${t('common.current.none', '无')}`;
                return;
            }
            const detailProgress = formatImageDownloadProgressHtml(item.imageProgress, item.status)
                + formatUgoiraProgressHtml(item.ugoiraProgress, item.status);
            c.innerHTML = `<strong>${t('common.current.label', '当前下载:')}</strong> ${escapeHtml(item.title)} (ID: ${item.id})${this._progressHtml(item, true)}${detailProgress}`;
        }

        setStatus(msg, type = 'info') {
            this.elements.status.innerText = translateStatusText(msg);
            this.elements.status.style.color = {
                info: '#007bff',
                success: '#28a745',
                error: '#dc3545',
                warning: '#ffc107'
            }[type] || '#666';
        }

        updateStats(stats) {
            const pending = this.manager.queue.filter(q => ['pending', 'paused', 'idle'].includes(q.status)).length;
            this.elements.stats.textContent = t('common.stats.summary', '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}', {
                pending: pending,
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

        _colorByStatus(status) {
            return {
                completed: '#28a745',
                downloading: '#17a2b8',
                failed: '#dc3545',
                paused: '#6c757d',
                skipped: '#ffa500'
            }[status] || '#6c757d';
        }

        _statusText(status) {
            return {
                idle: t('common.status.idle', '等待中'),
                pending: t('common.status.pending', '等待中'),
                downloading: t('common.status.downloading', '下载中'),
                completed: t('common.status.completed', '已完成'),
                failed: t('common.status.failed', '失败'),
                paused: t('common.status.paused', '暂停中'),
                skipped: t('common.status.skipped', '已跳过')
            }[status] || status;
        }

        // ---- 配额 UI ----

        updateQuotaBar(info) {
            const bar = document.getElementById('pixiv-page-quota-bar');
            if (!bar || !info || !info.enabled) return;
            const pct = Math.min(100, Math.round(info.artworksUsed / info.maxArtworks * 100));
            const color = pct >= 90 ? '#dc3545' : pct >= 70 ? '#ffc107' : '#28a745';
            const resetTxt = info.resetSeconds > 0 ? t('common.quota.reset', ' | 重置剩余：{time}', {time: this._fmtSeconds(info.resetSeconds)}) : '';
            bar.style.display = 'block';
            bar.innerHTML = `<div style="display:flex;align-items:center;gap:6px;"><span style="white-space:nowrap;">${t('common.quota.summary', '配额：{used}/{max} 个作品', {
                used: info.artworksUsed,
                max: info.maxArtworks
            })}</span><div style="flex:1;height:5px;background:#e0e0e0;border-radius:3px;overflow:hidden;"><div style="height:100%;width:${pct}%;background:${color};border-radius:3px;"></div></div><span style="white-space:nowrap;color:#888;font-size:10px;">${pct}%${resetTxt}</span></div>`;
        }

        showQuotaExceeded(data, title = '已达到下载限额') {
            clearInterval(this._archivePollTimer);
            clearInterval(this._archiveCountdownTimer);
            const card = document.getElementById('pixiv-page-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${translateStatusText(title)}</div><div id="pixiv-page-ac-status" style="font-size:11px;color:#666;">${t('common.archive.preparing', '正在打包已下载文件，请稍候...')}</div><div id="pixiv-page-ac-dl" style="display:none;margin-top:6px;"></div><div id="pixiv-page-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;
            this._pollArchive(data.archiveToken, data.archiveExpireSeconds || 3600);
        }

        restoreArchiveCard(token, expireSec, ready) {
            clearInterval(this._archivePollTimer);
            clearInterval(this._archiveCountdownTimer);
            const card = document.getElementById('pixiv-page-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${t('common.archive.restore-title', '已有未下载的压缩包')}</div><div id="pixiv-page-ac-status" style="font-size:11px;color:#666;"></div><div id="pixiv-page-ac-dl" style="display:none;margin-top:6px;"></div><div id="pixiv-page-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">${t('common.archive.expired', '下载链接已过期')}</div>`;
            if (ready) this._activateArchiveDl(token, expireSec);
            else {
                document.getElementById('pixiv-page-ac-status').textContent = t('common.archive.preparing', '正在打包已下载文件，请稍候...');
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
                } else if (data.status === 'expired' || data.status === 'empty') {
                    clearInterval(this._archivePollTimer);
                    const s = document.getElementById('pixiv-page-ac-status');
                    if (data.status === 'expired') {
                        const ex = document.getElementById('pixiv-page-ac-expired');
                        if (ex) ex.style.display = 'block';
                        if (s) s.textContent = '';
                    } else {
                        if (s) s.textContent = t('common.archive.empty', '暂无可打包文件');
                    }
                }
            }, 2000);
        }

        _activateArchiveDl(token, expireSec) {
            clearInterval(this._archiveCountdownTimer);
            const statusEl = document.getElementById('pixiv-page-ac-status');
            const dlEl = document.getElementById('pixiv-page-ac-dl');
            if (statusEl) statusEl.textContent = t('common.archive.ready', '压缩包已就绪：');
            if (dlEl) {
                dlEl.style.display = 'block';
                const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
                dlEl.innerHTML = `<a href="${CONFIG.ARCHIVE_DOWNLOAD_BASE}/${token}" download="${filename}" style="display:inline-block;padding:5px 12px;background:#28a745;color:white;border-radius:4px;text-decoration:none;font-size:12px;font-weight:bold;">${t('common.archive.download-link', '下载压缩包')}</a><span id="pixiv-page-ac-countdown" style="font-size:10px;color:#888;margin-left:8px;"></span>`;
                let remaining = Math.max(0, parseInt(expireSec));
                const countdownEl = () => document.getElementById('pixiv-page-ac-countdown');
                if (countdownEl()) countdownEl().textContent = t('common.archive.validity', '有效期：{time}', {time: this._fmtSeconds(remaining)});
                this._archiveCountdownTimer = setInterval(() => {
                    remaining--;
                    if (remaining <= 0) {
                        clearInterval(this._archiveCountdownTimer);
                        const ex = document.getElementById('pixiv-page-ac-expired');
                        if (dlEl) dlEl.style.display = 'none';
                        if (ex) ex.style.display = 'block';
                    } else {
                        if (countdownEl()) countdownEl().textContent = t('common.archive.validity', '有效期：{time}', {time: this._fmtSeconds(remaining)});
                    }
                }, 1000);
            }
        }

        _fmtSeconds(s) {
            s = Math.max(0, Math.round(s));
            const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
            if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm ' + String(sec).padStart(2, '0') + 's';
            return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
        }

        updateOverlays() {
            if (!this.manager) return;
            this._injectOverlayStyles();
            const queuedIds = new Set(this.manager.queue.map(q => q.id));
            const gs = this.manager.globalSettings;
            const frameOn = gs.queueFrame !== false;
            const fw = Math.min(12, Math.max(1, parseInt(gs.queueFrameWidth, 10) || 3));
            const fc = gs.queueFrameColor || '#17a2b8';
            const fs = ['solid', 'dashed', 'double'].includes(gs.queueFrameStyle) ? gs.queueFrameStyle : 'solid';
            const frameCss = 'position:absolute;inset:0;pointer-events:none;box-sizing:border-box;'
                + 'border:' + fw + 'px ' + fs + ' ' + fc + ';border-radius:8px;z-index:9998;';

            // 在缩略图链接上标记复选框（点击加入/移出队列）+ 可选边框。
            // 插画与小说处理一致；小说的队列 id 形如 'n<id>'。
            const mark = (a, queueId, rawId, kind) => {
                if (!a.querySelector('img')) return; // 只标记缩略图链接，跳过标题链接

                // 确定 overlay 容器：如果 <a> 本身已定位则直接使用，
                // 否则查找最近的已定位祖先元素，避免给 <a> 强制设置 position:relative
                // 导致其内部 absolute 定位的子元素（如主页的缩略图 img）布局错乱
                let container;
                if (['relative', 'absolute', 'fixed', 'sticky'].includes(getComputedStyle(a).position)) {
                    container = a;
                } else {
                    container = null;
                    let p = a.parentElement;
                    while (p && p !== document.body) {
                        if (['relative', 'absolute', 'fixed', 'sticky'].includes(getComputedStyle(p).position)) {
                            container = p;
                            break;
                        }
                        p = p.parentElement;
                    }
                    if (!container) {
                        a.style.position = 'relative';
                        container = a;
                    }
                }

                let overlay = container.querySelector(`.pixiv-queue-overlay[data-queue-id="${queueId}"]`);
                if (!overlay) {
                    overlay = document.createElement('div');
                    overlay.className = 'pixiv-queue-overlay';
                    overlay.dataset.queueId = queueId;
                    overlay.dataset.rawId = rawId;
                    overlay.dataset.kind = kind;
                    overlay.style.cssText = 'position:absolute;bottom:4px;left:4px;width:18px;height:18px;border-radius:3px;font-size:13px;line-height:14px;text-align:center;z-index:9999;cursor:pointer;pointer-events:all;user-select:none;font-weight:bold;box-sizing:border-box;border:2px solid #007bff;';
                    overlay.addEventListener('click', (e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        const qid = overlay.dataset.queueId;
                        if (this.manager.queue.some(q => q.id === qid)) {
                            this.manager.removeFromQueue(qid);
                        } else {
                            this.manager.addItemsToQueue([overlay.dataset.rawId], overlay.dataset.kind);
                        }
                    });
                    container.appendChild(overlay);
                }
                if (queuedIds.has(queueId)) {
                    overlay.classList.add('pqo-checked');
                    overlay.classList.remove('pqo-unchecked');
                    overlay.title = t('page.overlay.remove', '已加入队列，点击移出');
                    overlay.style.background = '#007bff';
                    overlay.style.color = 'white';
                    overlay.textContent = '✓';
                } else {
                    overlay.classList.remove('pqo-checked');
                    overlay.classList.add('pqo-unchecked');
                    overlay.title = t('page.overlay.add', '点击加入队列');
                    overlay.style.background = 'rgba(255,255,255,0.85)';
                    overlay.style.color = '#007bff';
                    overlay.textContent = '';
                }

                // 已加入队列（抓取到）的项在缩略图上画一圈边框，一眼可辨（可在设置中关闭）
                let frame = container.querySelector(`.pixiv-queue-frame[data-queue-id="${queueId}"]`);
                if (frameOn && queuedIds.has(queueId)) {
                    if (!frame) {
                        frame = document.createElement('div');
                        frame.className = 'pixiv-queue-frame';
                        frame.dataset.queueId = queueId;
                        container.appendChild(frame);
                    }
                    // 每次都按当前设置重写，确保宽度/颜色/样式变更后即时生效
                    frame.style.cssText = frameCss;
                } else if (frame) {
                    frame.remove();
                }
            };

            document.querySelectorAll('a[href*="/artworks/"]').forEach(a => {
                const m = a.href.match(/\/artworks\/(\d+)/);
                if (m) mark(a, m[1], m[1], 'illust');
            });
            document.querySelectorAll('a[href*="/novel/show.php"]').forEach(a => {
                const m = a.href.match(/\/novel\/show\.php\?[^#]*\bid=(\d+)/);
                if (m) mark(a, 'n' + m[1], m[1], 'novel');
            });
        }
    }

    /* ========== 初始化 ========== */
    const ui = new UI();
    const manager = new DownloadManager(ui);
    ui.bindManager(manager);

    PixivUserscriptI18n.onChange(() => {
        if (ui.root) {
            const collapsed = ui._collapsed;
            ui.root.remove();
            const fab = document.getElementById('page-batch-mini-fab');
            if (fab) fab.remove();
            ui._build();
            ui.bindManager(manager);
            ui.renderQueue(manager.queue);
            ui.updateStats(manager.stats);
            ui.updateButtonsState(manager.isRunning, manager.isPaused);
            ui.setCurrent(manager.queue.find(item => item.status === 'downloading') || null);
            ui.updateOverlays();
            ui.updateSinglePageButtonsVisibility();
            if (quotaInfo && quotaInfo.enabled) {
                ui.updateQuotaBar(quotaInfo);
            }
            ui._collapsed = !collapsed;
            ui.toggleCollapse();
        }
    });
    PixivUserscriptI18n.enrichFromBackend(serverBase);

    checkExternalServerNotice();

    // Load persisted queue
    manager.loadFromStorage();

    // SPA navigation — show/hide based on current URL
    let lastHref = location.href;

    function updateVisibility(isNavigation, isInitial) {
        if (!document.getElementById('pixiv-page-batch-ui')) ui.mount();
        if (isInitial) {
            // 首次加载优先沿用上次记忆的展开/收缩状态
            ui._collapsed = loadPanelCollapsed();
        } else if (isNavigation) {
            if (loadPanelCollapsed()) {
                // 用户已手动收起：存储值优先级最高，跳转到任何页面（含默认展开的页面）都不自动展开
                ui._collapsed = true;
            } else {
                // 未被手动收起时，站内导航按页面类型决定默认展开/收起
                const shouldExpand = shouldDefaultExpand();
                if (shouldExpand && ui._collapsed) {
                    ui._collapsed = false;
                } else if (!shouldExpand && !ui._collapsed) {
                    ui._collapsed = true;
                }
            }
        }
        if (!ui._collapsed) {
            if (ui.root) ui.root.style.display = 'block';
            const fab = document.getElementById('page-batch-mini-fab');
            if (fab) fab.style.display = 'none';
            if (isNavigation) document.dispatchEvent(new CustomEvent('pixiv_panel_active', {detail: 'page'}));
        } else {
            if (ui.root) ui.root.style.display = 'none';
            const fab = document.getElementById('page-batch-mini-fab');
            if (fab) fab.style.display = 'block';
        }
        ui.renderQueue(manager.queue);
        ui.updateOverlays();
        ui.updateSinglePageButtonsVisibility();
    }

    updateVisibility(true, true);

    // 跨脚本共享折叠状态：仅“收起”跨脚本传播（收一个=全收）；展开不在此级联，
    // 由用户点击本面板 FAB 或站内导航按页面类型决定，避免点任意 FAB 误展开本面板
    PixivPanelState.onChange(c => {
        if (c && ui && !ui._collapsed) ui.toggleCollapse();
    });

    // MutationObserver: re-apply overlays when Pixiv's SPA injects new thumbnail nodes
    let _overlayDebounce = null;
    new MutationObserver(() => {
        clearTimeout(_overlayDebounce);
        _overlayDebounce = setTimeout(() => ui.updateOverlays(), 250);
    }).observe(document.body, {childList: true, subtree: true});

    setInterval(() => {
        if (location.href !== lastHref) {
            lastHref = location.href;
            updateVisibility(true);
        }
    }, 500);

    // Quota init
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
            if (!data.enabled) return;
            quotaInfo = {
                enabled: true,
                artworksUsed: data.artworksUsed || 0,
                maxArtworks: data.maxArtworks || 50,
                resetSeconds: data.resetSeconds || 0
            };
            ui.updateQuotaBar(quotaInfo);
            startQuotaResetCountdown();
            if (data.archive && data.archive.token) {
                ui.restoreArchiveCard(data.archive.token, data.archive.expireSeconds || 3600, data.archive.status === 'ready');
            }
        }).catch(() => {
        });
    })();

    // Cross-script panel coordination
    document.addEventListener('pixiv_panel_active', e => {
        if (e.detail && e.detail !== 'page' && ui && !ui._collapsed) {
            ui.toggleCollapse();
        }
    });

    GM_registerMenuCommand(t('page.menu.open', '打开 Pixiv 页面批量下载器'), () => {
        ui.show();
        window.scrollTo(0, 0);
    });
})();
