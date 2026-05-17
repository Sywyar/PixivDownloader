// ==UserScript==
// @name              Pixiv作品图片下载器(Local download)
// @name:en           Pixiv Artwork Downloader (Local download)
// @namespace         http://tampermonkey.net/
// @version           1.4.0
// @updateURL         https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/Pixiv%20%E5%8D%95%E4%BD%9C%E5%93%81%E5%9B%BE%E7%89%87%E4%B8%8B%E8%BD%BD%E5%99%A8(Local%20Download).user.js
// @description       下载 Pixiv 单个作品的所有图片，并记录已下载的作品（本地存储，不通过后端数据库）
// @description:en    Download all images of a single Pixiv artwork and remember which works have been downloaded (local storage, no backend database)
// @author            Rewritten by ChatGPT,Claude,Sywyar
// @match             https://www.pixiv.net/*
// @grant             GM_registerMenuCommand
// @grant             GM_unregisterMenuCommand
// @grant             GM_xmlhttpRequest
// @grant             GM_download
// @grant             GM_setValue
// @grant             GM_getValue
// @grant             GM_listValues
// @grant             GM_deleteValue
// @connect           i.pximg.net
// @connect           www.pixiv.net
// @run-at            document-start
// ==/UserScript==

(function () {
    'use strict';

    /* ========== PixivUserscriptI18n：跨脚本一致的 i18n 运行时 ==========
     * 同源所有脚本共享 localStorage['pixiv_userscript_lang'] 与 BroadcastChannel
     * '__pixiv_userscript_lang_v1__'，因此单脚本、多脚本、Bundle 三种部署
     * 自动一致，无需额外协调代码。
     * 该模块在所有油猴脚本顶部保持字节级一致，修改时必须同步全部脚本。
     * --------------------------------------------------------------- */
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
            for (const candidate of SUPPORTED) {
                if (candidate.toLowerCase().startsWith(language + '-')) return candidate;
            }
            return null;
        }

        function readInitialLang() {
            try {
                const ls = localStorage.getItem(LS_KEY);
                const norm = normalize(ls);
                if (norm) return norm;
            } catch (e) {
            }
            try {
                const gm = (typeof GM_getValue === 'function') ? GM_getValue(GM_KEY, null) : null;
                const norm = normalize(gm);
                if (norm) return norm;
            } catch (e) {
            }
            const navLang = normalize(navigator.language);
            if (navLang) return navLang;
            return DEFAULT_LANG;
        }

        function ensureInit() {
            if (currentLang) return;
            currentLang = readInitialLang();
            try {
                if (typeof BroadcastChannel !== 'undefined') {
                    bc = new BroadcastChannel(BC_NAME);
                    bc.addEventListener('message', ev => {
                        if (ev && ev.data && ev.data.type === 'lang-changed') {
                            const next = normalize(ev.data.lang);
                            if (next && next !== currentLang) applyLang(next, false);
                        }
                    });
                }
            } catch (e) {
            }
            try {
                window.addEventListener('storage', ev => {
                    if (ev.key !== LS_KEY) return;
                    const next = normalize(ev.newValue);
                    if (next && next !== currentLang) applyLang(next, false);
                });
            } catch (e) {
            }
            // Cross-sandbox polling fallback (see other scripts for context).
            try {
                setInterval(() => {
                    try {
                        const stored = normalize(localStorage.getItem(LS_KEY));
                        if (stored && stored !== currentLang) applyLang(stored, false);
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
            listeners.forEach(fn => {
                try {
                    fn(next);
                } catch (e) {
                    console.error('[PixivUserscriptI18n]', e);
                }
            });
        }

        function interpolate(template, args) {
            if (!args) return template;
            if (Array.isArray(args)) {
                return String(template).replace(/\{(\d+)\}/g, (m, idx) => {
                    const i = parseInt(idx, 10);
                    return i < args.length ? String(args[i]) : m;
                });
            }
            return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (m, name) => {
                return Object.prototype.hasOwnProperty.call(args, name) ? String(args[name]) : m;
            });
        }

        function t(key, fallback, args) {
            ensureInit();
            const dict = DICT[currentLang] || {};
            let template = Object.prototype.hasOwnProperty.call(dict, key) ? dict[key] : null;
            if (template == null) {
                const fb = DICT[DEFAULT_LANG] || {};
                template = Object.prototype.hasOwnProperty.call(fb, key) ? fb[key] : null;
            }
            if (template == null) template = (fallback != null) ? fallback : key;
            if (typeof template === 'function') return template(args || {});
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

        const api = {register, t, onChange, setLang, getLang, listSupported};
        try {
            if (typeof window !== 'undefined') window[SHARED_KEY] = api;
        } catch (e) {
        }
        return api;
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

    /* ========== 嵌入字典（仅本脚本独有；不依赖后端 enrichment）========== */
    PixivUserscriptI18n.register({
        'en-US': {
            // Tampermonkey menu commands
            'local.menu.download-current': 'Download all images of current artwork',
            'local.menu.history': 'View download history',
            'local.menu.clear-history': 'Clear download history',
            'local.menu.reset-folder-index': 'Reset folder index',
            // Alerts
            'local.alert.no-artwork-id': 'Could not retrieve artwork ID',
            'local.alert.start-download': 'Starting download for artwork {id}...',
            'local.alert.ugoira-unsupported': 'Artwork {id} is an animation (ugoira).\nThis variant does not support frame composition. Use the "Pixiv Artwork Downloader (Java backend)" — it composes frames into WebP automatically.',
            'local.alert.no-images': 'No images found',
            'local.alert.download-summary': 'Download complete!\nSucceeded: {success}/{total} images\nSaved to: default download folder',
            'local.alert.download-failed': 'Download failed: {message}',
            'local.alert.history-empty': 'No download records yet',
            'local.alert.history-cleared': 'Download history cleared',
            'local.alert.folder-index-reset': 'Folder index reset',
            // Confirms
            'local.confirm.redownload': 'Artwork {id} was already downloaded at {time}\nLocation: folder {folder}\nImages: {count}\n\nRe-download?',
            'local.confirm.clear-history': 'Are you sure you want to clear all download records? This action cannot be undone.',
            'local.confirm.reset-folder-index': 'Reset the folder index? Next download will start from folder 0.',
            // History dialog
            'local.history.header': 'Total downloaded artworks: {count}\n\n',
            'local.history.entry': 'Artwork ID: {id}\nDownloaded at: {time}\nLocation: {folder}\nImages: {count}\n---\n',
            'local.history.novel-header': '\nTotal downloaded novels: {count}\n\n',
            'local.history.novel-entry': 'Novel ID: {id}\nTitle: {title}\nDownloaded at: {time}\n---\n',
            // Floating UI
            'local.ui.status.downloaded': '✅ Already downloaded',
            'local.ui.status.available': '⬇️ Ready to download',
            'local.ui.button.download': '📥 Download all images',
            'local.ui.label.artwork-id': 'Artwork ID: {id}',
            'local.ui.folder.default': 'default download folder',
            'local.menu.download-current-novel': 'Download current novel as EPUB',
            'local.alert.no-novel-id': 'Could not retrieve novel ID',
            'local.alert.novel-start-download': 'Starting EPUB download for novel {id}...',
            'local.alert.novel-summary': 'Novel downloaded!\nFile: {title}.epub\nInline images: {images}\nSaved to: default download folder',
            'local.alert.novel-failed': 'Novel download failed: {message}',
            'local.confirm.novel-redownload': 'Novel {id} was already downloaded at {time}\n\nDownload again?',
            'local.ui.status.novel-downloaded': '✅ Novel already downloaded',
            'local.ui.status.novel-available': '⬇️ Novel ready to download (EPUB)',
            'local.ui.button.download-novel': '📕 Download novel (EPUB)',
            'local.ui.label.novel-id': 'Novel ID: {id}',
            // Language switcher
            'local.switcher.label': 'Language',
            'switcher.option.en-US': 'English',
            'switcher.option.zh-CN': '简体中文',
            // Panel collapse / FAB
            'local.action.collapse': 'Collapse',
            'local.fab.title': 'Pixiv Local Downloader',
            'local.menu.open': 'Open Pixiv local downloader panel'
        },
        'zh-CN': {
            'local.menu.download-current': '下载当前作品所有图片',
            'local.menu.history': '查看下载历史',
            'local.menu.clear-history': '清空下载记录',
            'local.menu.reset-folder-index': '重置文件夹索引',
            'local.alert.no-artwork-id': '无法获取作品ID',
            'local.alert.start-download': '开始下载作品 {id} 的图片...',
            'local.alert.ugoira-unsupported': '作品 {id} 是动图（ugoira）。\n此版本不支持动图合成，请使用「Pixiv作品图片下载器（Java后端版）」下载动图，后端会自动合成为WebP格式。',
            'local.alert.no-images': '未找到图片',
            'local.alert.download-summary': '下载完成！\n成功下载: {success}/{total} 张图片\n保存到: 默认下载目录',
            'local.alert.download-failed': '下载失败: {message}',
            'local.alert.history-empty': '还没有下载记录',
            'local.alert.history-cleared': '下载记录已清空',
            'local.alert.folder-index-reset': '文件夹索引已重置',
            'local.confirm.redownload': '作品 {id} 已经在 {time} 下载过\n保存位置: 文件夹 {folder}\n图片数量: {count}张\n\n是否重新下载？',
            'local.confirm.clear-history': '确定要清空所有下载记录吗？此操作不可恢复。',
            'local.confirm.reset-folder-index': '确定要重置文件夹索引吗？下次下载将从文件夹0开始。',
            'local.history.header': '已下载作品数量: {count}\n\n',
            'local.history.entry': '作品ID: {id}\n下载时间: {time}\n保存位置: {folder}\n图片数量: {count}张\n---\n',
            'local.history.novel-header': '\n已下载小说数量: {count}\n\n',
            'local.history.novel-entry': '小说ID: {id}\n标题: {title}\n下载时间: {time}\n---\n',
            'local.ui.status.downloaded': '✅ 已下载过此作品',
            'local.ui.status.available': '⬇️ 可下载此作品',
            'local.ui.button.download': '📥 下载所有图片',
            'local.ui.label.artwork-id': '作品ID: {id}',
            'local.ui.folder.default': '默认下载目录',
            'local.menu.download-current-novel': '下载当前小说为 EPUB',
            'local.alert.no-novel-id': '无法获取小说ID',
            'local.alert.novel-start-download': '开始下载小说 {id} 为 EPUB...',
            'local.alert.novel-summary': '小说下载完成！\n文件：{title}.epub\n内嵌图片：{images} 张\n保存到：默认下载目录',
            'local.alert.novel-failed': '小说下载失败: {message}',
            'local.confirm.novel-redownload': '小说 {id} 已经在 {time} 下载过\n\n是否重新下载？',
            'local.ui.status.novel-downloaded': '✅ 已下载过此小说',
            'local.ui.status.novel-available': '⬇️ 可下载此小说（EPUB）',
            'local.ui.button.download-novel': '📕 下载小说（EPUB）',
            'local.ui.label.novel-id': '小说ID: {id}',
            'local.switcher.label': '语言',
            'switcher.option.en-US': 'English',
            'switcher.option.zh-CN': '简体中文'
        }
    });

    const t = (key, args) => PixivUserscriptI18n.t(key, key, args);

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

    // 等待特定元素出现
    function waitForElement(selector, timeout = 10000) {
        return new Promise((resolve, reject) => {
            if (document.querySelector(selector)) {
                resolve(document.querySelector(selector));
                return;
            }

            const observer = new MutationObserver(() => {
                if (document.querySelector(selector)) {
                    observer.disconnect();
                    resolve(document.querySelector(selector));
                }
            });

            observer.observe(document.body, {
                childList: true,
                subtree: true
            });

            setTimeout(() => {
                observer.disconnect();
                reject(new Error(`Element ${selector} not found within ${timeout}ms`));
            }, timeout);
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

    /* ================= 客户端小说 → EPUB（无后端） ================= */

    function novelHistory() {
        return GM_getValue('downloadedNovels', {});
    }

    function isNovelDownloaded(novelId) {
        return Object.prototype.hasOwnProperty.call(novelHistory(), String(novelId));
    }

    function markNovelDownloaded(novelId, title) {
        const list = novelHistory();
        list[String(novelId)] = {
            timestamp: new Date().toISOString(),
            title: title || '',
            url: window.location.href
        };
        GM_setValue('downloadedNovels', list);
    }

    function sanitizeFileName(name) {
        return String(name || '')
            .replace(/[\\/:*?"<>|\n\r\t]/g, '_')
            .replace(/\s+/g, ' ')
            .trim()
            .slice(0, 120) || 'novel';
    }

    function escapeXml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&apos;');
    }

    // 获取小说数据（Pixiv AJAX）
    function fetchNovelData(novelId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/novel/${novelId}`,
                headers: {'Referer': 'https://www.pixiv.net/'},
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
    }

    // 解析单个 pixivimage 引用为原图 URL（best-effort）
    function fetchPixivImageUrl(illustId) {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${illustId}`,
                headers: {'Referer': 'https://www.pixiv.net/'},
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        const url = data && data.body && data.body.urls
                            ? (data.body.urls.original || data.body.urls.regular) : null;
                        resolve(url || null);
                    } catch (_) {
                        resolve(null);
                    }
                },
                onerror: () => resolve(null)
            });
        });
    }

    // 下载二进制资源（图片/封面）
    function fetchBinary(url) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url,
                responseType: 'arraybuffer',
                headers: {'Referer': 'https://www.pixiv.net/'},
                onload: (res) => {
                    if (res.status >= 200 && res.status < 300 && res.response) {
                        resolve(new Uint8Array(res.response));
                    } else {
                        reject(new Error('HTTP ' + res.status));
                    }
                },
                onerror: reject
            });
        });
    }

    // 极简 ZIP（仅 store，无压缩）+ CRC32，用于手写 EPUB3
    const ZipWriter = (() => {
        const crcTable = (() => {
            const tbl = new Uint32Array(256);
            for (let n = 0; n < 256; n++) {
                let c = n;
                for (let k = 0; k < 8; k++) {
                    c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
                }
                tbl[n] = c >>> 0;
            }
            return tbl;
        })();
        function crc32(buf) {
            let c = 0xFFFFFFFF;
            for (let i = 0; i < buf.length; i++) {
                c = crcTable[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
            }
            return (c ^ 0xFFFFFFFF) >>> 0;
        }
        function strBytes(s) {
            return new TextEncoder().encode(s);
        }

        function build(entries) {
            // entries: [{name, data:Uint8Array}]
            const chunks = [];
            const central = [];
            let offset = 0;
            const u16 = (v) => new Uint8Array([v & 0xFF, (v >>> 8) & 0xFF]);
            const u32 = (v) => new Uint8Array([v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF, (v >>> 24) & 0xFF]);
            const push = (arr) => {
                chunks.push(arr);
                offset += arr.length;
            };
            entries.forEach(en => {
                const nameBytes = strBytes(en.name);
                const crc = crc32(en.data);
                const size = en.data.length;
                const local = [];
                local.push(u32(0x04034b50));
                local.push(u16(20));
                local.push(u16(0));
                local.push(u16(0)); // store
                local.push(u16(0));
                local.push(u16(0));
                local.push(u32(crc));
                local.push(u32(size));
                local.push(u32(size));
                local.push(u16(nameBytes.length));
                local.push(u16(0));
                local.push(nameBytes);
                const localOffset = offset;
                local.forEach(push);
                push(en.data);
                const cd = [];
                cd.push(u32(0x02014b50));
                cd.push(u16(20));
                cd.push(u16(20));
                cd.push(u16(0));
                cd.push(u16(0));
                cd.push(u16(0));
                cd.push(u16(0));
                cd.push(u32(crc));
                cd.push(u32(size));
                cd.push(u32(size));
                cd.push(u16(nameBytes.length));
                cd.push(u16(0));
                cd.push(u16(0));
                cd.push(u16(0));
                cd.push(u16(0));
                cd.push(u32(0));
                cd.push(u32(localOffset));
                cd.push(nameBytes);
                central.push(cd);
            });
            const cdStart = offset;
            let cdSize = 0;
            central.forEach(cd => cd.forEach(part => {
                chunks.push(part);
                cdSize += part.length;
                offset += part.length;
            }));
            const eocd = [];
            eocd.push(new Uint8Array([0x50, 0x4b, 0x05, 0x06]));
            eocd.push(u16(0));
            eocd.push(u16(0));
            eocd.push(u16(entries.length));
            eocd.push(u16(entries.length));
            eocd.push(u32(cdSize));
            eocd.push(u32(cdStart));
            eocd.push(u16(0));
            eocd.forEach(part => chunks.push(part));
            let total = 0;
            chunks.forEach(c => total += c.length);
            const out = new Uint8Array(total);
            let p = 0;
            chunks.forEach(c => {
                out.set(c, p);
                p += c.length;
            });
            return out;
        }

        return {build, strBytes};
    })();

    // 把 Pixiv 小说 markup 渲染为分章 XHTML（与后端 NovelMarkupParser 行为对齐的子集）
    function renderNovelPagesHtml(content, imageNameById) {
        const pages = String(content || '').split('[newpage]');
        return pages.map((raw) => {
            const placeholders = [];
            const ph = (html) => {
                placeholders.push(html);
                return 'PH' + (placeholders.length - 1) + 'PH';
            };
            let s = raw;
            s = s.replace(/\[\[rb:([^>\]]+)>([^\]]+)\]\]/g, (_, base, ruby) =>
                ph(`<ruby>${escapeXml(base.trim())}<rt>${escapeXml(ruby.trim())}</rt></ruby>`));
            s = s.replace(/\[\[jumpuri:([^>\]]+)>([^\]]+)\]\]/g, (_, text, url) =>
                ph(`<a href="${escapeXml(url.trim())}">${escapeXml(text.trim())}</a>`));
            s = s.replace(/\[chapter:([^\]]+)\]/g, (_, title) =>
                ph(`</p><h2>${escapeXml(title.trim())}</h2><p>`));
            s = s.replace(/\[(?:uploadedimage|pixivimage):([0-9]+(?:-\d+)?)\]/g, (_, id) => {
                const file = imageNameById[id] || imageNameById[String(id).split('-')[0]];
                return file ? ph(`</p><div class="img"><img src="images/${file}" alt=""/></div><p>`) : '';
            });
            s = s.replace(/\[jump:\d+\]/g, '');
            s = escapeXml(s);
            s = s.replace(/\r\n/g, '\n').replace(/\n/g, '<br/>');
            s = s.replace(/PH(\d+)PH/g, (_, i) => placeholders[Number(i)]);
            return `<p>${s}</p>`.replace(/<p><\/p>/g, '');
        });
    }

    async function downloadNovel() {
        const novelId = getNovelId();
        if (!novelId) {
            alert(t('local.alert.no-novel-id'));
            return;
        }
        if (isNovelDownloaded(novelId)) {
            const info = novelHistory()[String(novelId)];
            if (!confirm(t('local.confirm.novel-redownload', {
                id: novelId,
                time: new Date(info.timestamp).toLocaleString()
            }))) {
                return;
            }
        }
        try {
            alert(t('local.alert.novel-start-download', {id: novelId}));
            const body = await fetchNovelData(novelId);
            const title = body.title || ('Novel ' + novelId);
            const author = (body.userName || '').trim();
            const content = body.content || '';

            // 收集图片：uploadedimage 走 textEmbeddedImages；pixivimage 逐个解析（best-effort）
            const embedded = body.textEmbeddedImages || {};
            const imageUrlById = {};
            Object.keys(embedded).forEach(id => {
                const u = embedded[id] && embedded[id].urls
                    ? (embedded[id].urls.original || embedded[id].urls['1200'] || embedded[id].urls.regular) : null;
                if (u) imageUrlById[id] = u;
            });
            const pixivRefs = new Set();
            (content.match(/\[pixivimage:([0-9]+(?:-\d+)?)\]/g) || []).forEach(tok => {
                const m = tok.match(/\[pixivimage:([0-9]+)/);
                if (m && !imageUrlById[m[1]]) pixivRefs.add(m[1]);
            });
            for (const pid of pixivRefs) {
                const u = await fetchPixivImageUrl(pid);
                if (u) imageUrlById[pid] = u;
            }

            // 下载图片字节
            const imageEntries = [];
            const imageNameById = {};
            let imgSeq = 0;
            for (const id of Object.keys(imageUrlById)) {
                const url = imageUrlById[id];
                let ext = (url.split('?')[0].split('.').pop() || 'jpg').toLowerCase();
                if (!/^(jpg|jpeg|png|gif|webp)$/.test(ext)) ext = 'jpg';
                const file = `img${imgSeq++}.${ext}`;
                try {
                    const bytes = await fetchBinary(url);
                    imageEntries.push({name: `OEBPS/images/${file}`, data: bytes});
                    imageNameById[id] = file;
                } catch (e) {
                    console.warn('embedded image failed', id, e);
                }
            }

            // 封面（best-effort）
            let coverFile = null;
            const coverUrl = typeof body.coverUrl === 'string' ? body.coverUrl : null;
            if (coverUrl) {
                try {
                    let cext = (coverUrl.split('?')[0].split('.').pop() || 'jpg').toLowerCase();
                    if (!/^(jpg|jpeg|png|webp)$/.test(cext)) cext = 'jpg';
                    coverFile = `cover.${cext}`;
                    const cbytes = await fetchBinary(coverUrl);
                    imageEntries.push({name: `OEBPS/images/${coverFile}`, data: cbytes});
                } catch (e) {
                    coverFile = null;
                }
            }

            const pagesHtml = renderNovelPagesHtml(content, imageNameById);
            const enc = (s) => ZipWriter.strBytes(s);
            const entries = [];
            // mimetype 必须是第一项且 store
            entries.push({name: 'mimetype', data: enc('application/epub+zip')});
            entries.push({
                name: 'META-INF/container.xml',
                data: enc('<?xml version="1.0" encoding="UTF-8"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>\n</container>')
            });

            const chapterFiles = pagesHtml.map((html, i) => {
                const name = `chapter${i + 1}.xhtml`;
                const xhtml = `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja"><head><meta charset="utf-8"/><title>${escapeXml(title)} - ${i + 1}</title></head><body>${html || '<p/>'}</body></html>`;
                entries.push({name: `OEBPS/${name}`, data: enc(xhtml)});
                return name;
            });

            const manifestItems = [];
            const spineItems = [];
            chapterFiles.forEach((f, i) => {
                manifestItems.push(`<item id="chap${i + 1}" href="${f}" media-type="application/xhtml+xml"/>`);
                spineItems.push(`<itemref idref="chap${i + 1}"/>`);
            });
            imageEntries.forEach((e, i) => {
                const fname = e.name.replace('OEBPS/', '');
                const mt = fname.endsWith('.png') ? 'image/png'
                    : fname.endsWith('.gif') ? 'image/gif'
                        : fname.endsWith('.webp') ? 'image/webp' : 'image/jpeg';
                const isCover = coverFile && fname === ('images/' + coverFile);
                manifestItems.push(`<item id="img${i}" href="${fname}" media-type="${mt}"${isCover ? ' properties="cover-image"' : ''}/>`);
            });
            manifestItems.push('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>');
            manifestItems.push('<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>');

            const opf = `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">pixiv-novel-${escapeXml(novelId)}</dc:identifier>
<dc:title>${escapeXml(title)}</dc:title>
<dc:creator>${escapeXml(author)}</dc:creator>
<dc:language>${escapeXml(body.language || 'ja')}</dc:language>
</metadata>
<manifest>${manifestItems.join('')}</manifest>
<spine toc="ncx">${spineItems.join('')}</spine>
</package>`;
            entries.push({name: 'OEBPS/content.opf', data: enc(opf)});

            const navList = chapterFiles.map((f, i) =>
                `<li><a href="${f}">${escapeXml(title)} - ${i + 1}</a></li>`).join('');
            entries.push({
                name: 'OEBPS/nav.xhtml',
                data: enc(`<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><meta charset="utf-8"/><title>${escapeXml(title)}</title></head><body><nav epub:type="toc"><ol>${navList}</ol></nav></body></html>`)
            });
            const navPoints = chapterFiles.map((f, i) =>
                `<navPoint id="np${i + 1}" playOrder="${i + 1}"><navLabel><text>${escapeXml(title)} - ${i + 1}</text></navLabel><content src="${f}"/></navPoint>`).join('');
            entries.push({
                name: 'OEBPS/toc.ncx',
                data: enc(`<?xml version="1.0" encoding="UTF-8"?>\n<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="pixiv-novel-${escapeXml(novelId)}"/></head><docTitle><text>${escapeXml(title)}</text></docTitle><navMap>${navPoints}</navMap></ncx>`)
            });

            imageEntries.forEach(e => entries.push(e));

            const zip = ZipWriter.build(entries);
            const blob = new Blob([zip], {type: 'application/epub+zip'});
            const blobUrl = URL.createObjectURL(blob);
            const fname = sanitizeFileName(title) + '.epub';

            // 优先用原生 <a download> 触发浏览器下载（对 blob: 最可靠、跨管理器一致）；
            // 失败再回退 GM_download。GM_download 对 blob: URL 在多数管理器下会静默失败，
            // 之前 onerror/ontimeout 也 resolve 导致“假成功”，这里改为真实判定。
            let triggered = false;
            try {
                const a = document.createElement('a');
                a.href = blobUrl;
                a.download = fname;
                a.rel = 'noopener';
                a.style.display = 'none';
                (document.body || document.documentElement).appendChild(a);
                a.click();
                a.remove();
                triggered = true;
            } catch (e) {
                console.warn('anchor download failed, will try GM_download', e);
            }
            if (!triggered && typeof GM_download === 'function') {
                await new Promise((resolve, reject) => {
                    try {
                        GM_download({
                            url: blobUrl,
                            name: fname,
                            onload: () => {
                                triggered = true;
                                resolve();
                            },
                            onerror: (err) => reject(new Error((err && (err.error || err.details)) || 'GM_download error')),
                            ontimeout: () => reject(new Error('GM_download timeout'))
                        });
                    } catch (e) {
                        reject(e);
                    }
                });
            }
            setTimeout(() => URL.revokeObjectURL(blobUrl), 120000);
            if (!triggered) {
                throw new Error('download was not triggered');
            }

            markNovelDownloaded(novelId, title);
            updateDownloadUI();
            alert(t('local.alert.novel-summary', {
                title: sanitizeFileName(title),
                images: imageEntries.length
            }));
        } catch (error) {
            console.error('Novel download failed:', error);
            alert(t('local.alert.novel-failed', {message: error.message}));
        }
    }

    // 检查是否已下载过
    function isAlreadyDownloaded(artworkId) {
        const downloadedList = GM_getValue('downloadedArtworks', {});
        return downloadedList.hasOwnProperty(artworkId);
    }

    // 记录已下载的作品
    function markAsDownloaded(artworkId, folderName, imageCount) {
        const downloadedList = GM_getValue('downloadedArtworks', {});
        downloadedList[artworkId] = {
            timestamp: new Date().toISOString(),
            folder: folderName,
            imageCount: imageCount,
            url: window.location.href
        };
        GM_setValue('downloadedArtworks', downloadedList);
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

    // 检测作品类型
    async function getArtworkType(artworkId) {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}`,
                headers: {'Referer': 'https://www.pixiv.net/'},
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(data.error ? 0 : (data.body.illustType || 0));
                    } catch (e) {
                        resolve(0);
                    }
                },
                onerror: function () {
                    resolve(0);
                }
            });
        });
    }

    // 下载图片
    async function downloadImages() {
        const artworkId = getArtworkId();
        if (!artworkId) {
            alert(t('local.alert.no-artwork-id'));
            return;
        }

        // 检查是否已下载
        if (isAlreadyDownloaded(artworkId)) {
            const downloadedInfo = GM_getValue('downloadedArtworks', {})[artworkId];
            const confirmDownload = confirm(t('local.confirm.redownload', {
                id: artworkId,
                time: new Date(downloadedInfo.timestamp).toLocaleString(),
                folder: downloadedInfo.folder,
                count: downloadedInfo.imageCount
            }));

            if (!confirmDownload) {
                return;
            }
        }

        try {
            // 显示下载开始提示
            alert(t('local.alert.start-download', {id: artworkId}));

            // 检测动图
            const illustType = await getArtworkType(artworkId);
            if (illustType === 2) {
                alert(t('local.alert.ugoira-unsupported', {id: artworkId}));
                return;
            }

            const imageUrls = await getImageUrls(artworkId);

            if (imageUrls.length === 0) {
                alert(t('local.alert.no-images'));
                return;
            }

            // 获取下一个文件夹索引
            let folderIndex = GM_getValue('downloadFolderIndex', 0);

            // 创建下载文件夹
            const folderName = folderIndex.toString();

            // 下载所有图片
            let successCount = 0;
            for (let i = 0; i < imageUrls.length; i++) {
                const imageUrl = imageUrls[i];
                const extension = imageUrl.split('.').pop();
                const filename = `${artworkId}_p${i}.${extension}`;

                try {
                    await new Promise((resolve, reject) => {
                        GM_download({
                            url: imageUrl,
                            name: filename,
                            headers: {
                                'Referer': 'https://www.pixiv.net/'
                            },
                            onload: function () {
                                console.log(`Download complete: ${filename}`);
                                successCount++;
                                resolve();
                            },
                            onerror: function (error) {
                                console.error(`Download failed: ${filename}`, error);
                                reject(error);
                            }
                        });
                    });
                } catch (error) {
                    console.error(`Image ${i + 1} download failed:`, error);
                }

                // 添加延迟避免请求过快
                await new Promise(resolve => setTimeout(resolve, 1000));
            }

            // 更新文件夹索引
            GM_setValue('downloadFolderIndex', folderIndex + 1);

            // 记录已下载的作品
            if (successCount > 0) {
                markAsDownloaded(artworkId, t('local.ui.folder.default'), successCount);
                updateDownloadUI();
            }

            alert(t('local.alert.download-summary', {
                success: successCount,
                total: imageUrls.length
            }));

        } catch (error) {
            console.error('Download failed:', error);
            alert(t('local.alert.download-failed', {message: error.message}));
        }
    }

    // 显示下载历史
    function showDownloadHistory() {
        const downloadedList = GM_getValue('downloadedArtworks', {});
        const artworkIds = Object.keys(downloadedList);
        const novelList = novelHistory();
        const novelIds = Object.keys(novelList);

        if (artworkIds.length === 0 && novelIds.length === 0) {
            alert(t('local.alert.history-empty'));
            return;
        }

        let historyText = '';

        if (artworkIds.length > 0) {
            historyText += t('local.history.header', {count: artworkIds.length});
            artworkIds.sort((a, b) => new Date(downloadedList[b].timestamp) - new Date(downloadedList[a].timestamp));
            artworkIds.forEach(artworkId => {
                const info = downloadedList[artworkId];
                historyText += t('local.history.entry', {
                    id: artworkId,
                    time: new Date(info.timestamp).toLocaleString(),
                    folder: info.folder,
                    count: info.imageCount
                });
            });
        }

        if (novelIds.length > 0) {
            historyText += t('local.history.novel-header', {count: novelIds.length});
            novelIds.sort((a, b) => new Date(novelList[b].timestamp) - new Date(novelList[a].timestamp));
            novelIds.forEach(id => {
                const info = novelList[id];
                historyText += t('local.history.novel-entry', {
                    id: id,
                    title: info.title || '',
                    time: new Date(info.timestamp).toLocaleString()
                });
            });
        }

        alert(historyText);
    }

    // 清空下载记录
    function clearDownloadHistory() {
        const confirmClear = confirm(t('local.confirm.clear-history'));
        if (confirmClear) {
            GM_setValue('downloadedArtworks', {});
            GM_setValue('downloadedNovels', {});
            alert(t('local.alert.history-cleared'));
            updateDownloadUI();
        }
    }

    // 重置文件夹索引
    function resetFolderIndex() {
        const confirmReset = confirm(t('local.confirm.reset-folder-index'));
        if (confirmReset) {
            GM_setValue('downloadFolderIndex', 0);
            alert(t('local.alert.folder-index-reset'));
        }
    }

    // 构建语言切换器行（作品/小说面板共用）
    function buildSwitcherRow() {
        const switcherRow = document.createElement('div');
        switcherRow.style.cssText = `display:flex;align-items:center;justify-content:space-between;gap:6px;margin-top:8px;padding-top:6px;border-top:1px solid #eee;font-size:11px;color:#888;`;
        const switcherLabel = document.createElement('span');
        switcherLabel.textContent = t('local.switcher.label');
        const switcherSelect = document.createElement('select');
        switcherSelect.style.cssText = `flex:1;padding:2px 4px;border:1px solid #ccc;border-radius:3px;background:#fff;font-size:11px;cursor:pointer;`;
        PixivUserscriptI18n.listSupported().forEach(lang => {
            const opt = document.createElement('option');
            opt.value = lang;
            opt.textContent = t('switcher.option.' + lang);
            if (lang === PixivUserscriptI18n.getLang()) opt.selected = true;
            switcherSelect.appendChild(opt);
        });
        switcherSelect.addEventListener('change', () => PixivUserscriptI18n.setLang(switcherSelect.value));
        switcherRow.appendChild(switcherLabel);
        switcherRow.appendChild(switcherSelect);
        return switcherRow;
    }

    // 小说面板（客户端 EPUB 下载）
    function buildNovelUI(novelId) {
        const isDownloaded = isNovelDownloaded(novelId);
        const container = document.createElement('div');
        container.id = 'pixiv-local-downloader-ui';
        container.style.cssText = `position:fixed;top:310px;right:80px;z-index:9999;background:white;border:2px solid ${isDownloaded ? '#ff9500' : '#0d9488'};border-radius:8px;padding:10px;box-shadow:0 2px 10px rgba(0,0,0,0.2);min-width:200px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;`;

        const statusDiv = document.createElement('div');
        statusDiv.textContent = isDownloaded
            ? t('local.ui.status.novel-downloaded')
            : t('local.ui.status.novel-available');
        statusDiv.style.cssText = `font-weight:bold;margin-bottom:8px;color:${isDownloaded ? '#ff9500' : '#0d9488'};text-align:center;font-size:14px;`;

        const button = document.createElement('button');
        button.textContent = t('local.ui.button.download-novel');
        button.style.cssText = `width:100%;background:#0d9488;color:white;border:none;padding:8px 12px;border-radius:5px;cursor:pointer;font-size:14px;font-weight:bold;transition:background 0.2s;`;
        button.addEventListener('mouseenter', () => {
            button.style.background = '#0b7d72';
        });
        button.addEventListener('mouseleave', () => {
            button.style.background = '#0d9488';
        });
        button.addEventListener('click', downloadNovel);

        const idDiv = document.createElement('div');
        idDiv.textContent = t('local.ui.label.novel-id', {id: novelId});
        idDiv.style.cssText = `font-size:12px;color:#666;text-align:center;margin-top:5px;`;

        container.appendChild(statusDiv);
        container.appendChild(button);
        container.appendChild(idDiv);
        container.appendChild(buildSwitcherRow());
        document.body.appendChild(container);
        attachLocalPanelChrome(container, t('local.fab.title'));
    }

    // 创建下载UI
    function createDownloadUI() {
        // document-start 阶段 body 可能尚未出现：让后续轮询/MutationObserver 兜底
        if (!document.body) return;

        // 移除已存在的UI
        const existingUI = document.getElementById('pixiv-local-downloader-ui');
        if (existingUI) {
            existingUI.remove();
        }
        const existingFab = document.getElementById('pixiv-single-local-mini-fab');
        if (existingFab) existingFab.remove();

        const artworkId = getArtworkId();
        const novelId = !artworkId ? getNovelId() : null;
        if (!artworkId && !novelId) return; // 不是作品/小说页面，不显示UI

        if (novelId) {
            buildNovelUI(novelId);
            return;
        }

        const isDownloaded = isAlreadyDownloaded(artworkId);

        const container = document.createElement('div');
        container.id = 'pixiv-local-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 310px;
            right: 80px;
            z-index: 9999;
            background: white;
            border: 2px solid ${isDownloaded ? '#ff9500' : '#0096fa'};
            border-radius: 8px;
            padding: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.2);
            min-width: 200px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        `;

        const statusDiv = document.createElement('div');
        statusDiv.textContent = isDownloaded
            ? t('local.ui.status.downloaded')
            : t('local.ui.status.available');
        statusDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 8px;
            color: ${isDownloaded ? '#ff9500' : '#0096fa'};
            text-align: center;
            font-size: 14px;
        `;

        const button = document.createElement('button');
        button.textContent = t('local.ui.button.download');
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
        `;
        button.addEventListener('mouseenter', () => {
            button.style.background = '#007acc';
        });
        button.addEventListener('mouseleave', () => {
            button.style.background = '#0096fa';
        });
        button.addEventListener('click', downloadImages);

        const artworkIdDiv = document.createElement('div');
        artworkIdDiv.textContent = t('local.ui.label.artwork-id', {id: artworkId});
        artworkIdDiv.style.cssText = `
            font-size: 12px;
            color: #666;
            text-align: center;
            margin-top: 5px;
        `;

        // 语言切换器
        const switcherRow = document.createElement('div');
        switcherRow.style.cssText = `
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 6px;
            margin-top: 8px;
            padding-top: 6px;
            border-top: 1px solid #eee;
            font-size: 11px;
            color: #888;
        `;
        const switcherLabel = document.createElement('span');
        switcherLabel.textContent = t('local.switcher.label');
        const switcherSelect = document.createElement('select');
        switcherSelect.style.cssText = `
            flex: 1;
            padding: 2px 4px;
            border: 1px solid #ccc;
            border-radius: 3px;
            background: #fff;
            font-size: 11px;
            cursor: pointer;
        `;
        PixivUserscriptI18n.listSupported().forEach(lang => {
            const opt = document.createElement('option');
            opt.value = lang;
            opt.textContent = t('switcher.option.' + lang);
            if (lang === PixivUserscriptI18n.getLang()) opt.selected = true;
            switcherSelect.appendChild(opt);
        });
        switcherSelect.addEventListener('change', () => {
            PixivUserscriptI18n.setLang(switcherSelect.value);
        });
        switcherRow.appendChild(switcherLabel);
        switcherRow.appendChild(switcherSelect);

        container.appendChild(statusDiv);
        container.appendChild(button);
        container.appendChild(artworkIdDiv);
        container.appendChild(switcherRow);
        document.body.appendChild(container);
        attachLocalPanelChrome(container, t('local.fab.title'));
    }

    // 面板收起/展开：收起态显示 Mini FAB，展开态显示面板
    function applyLocalPanelCollapsed() {
        const container = document.getElementById('pixiv-local-downloader-ui');
        const fab = document.getElementById('pixiv-single-local-mini-fab');
        if (panelCollapsed) {
            if (container) container.style.display = 'none';
            if (fab) fab.style.display = 'block';
        } else {
            if (container) container.style.display = 'block';
            if (fab) fab.style.display = 'none';
        }
    }

    function setLocalPanelCollapsed(collapsed, manual) {
        panelCollapsed = !!collapsed;
        applyLocalPanelCollapsed();
        if (!panelCollapsed) {
            // 展开时与其它面板互斥：通知同侧面板收起
            document.dispatchEvent(new CustomEvent('pixiv_panel_active', {detail: 'single-local'}));
        }
        // 仅用户手动收/展写入共享状态；自动收起不持久化
        if (manual) savePanelCollapsed(panelCollapsed);
    }

    function toggleLocalPanel(manual) {
        setLocalPanelCollapsed(!panelCollapsed, manual);
    }

    // 给面板加上收起按钮 + Mini FAB（堆叠在工具箱 FAB 下方），并按共享状态收/展
    function attachLocalPanelChrome(container, fabTitle) {
        const collapseRow = document.createElement('div');
        collapseRow.style.cssText = 'display:flex;justify-content:flex-end;margin-bottom:4px;';
        const collapseBtn = document.createElement('button');
        collapseBtn.textContent = '◀';
        collapseBtn.title = t('local.action.collapse');
        collapseBtn.style.cssText = 'background:none;border:1px solid #ccc;border-radius:4px;cursor:pointer;font-size:12px;padding:2px 6px;color:#666;';
        collapseBtn.addEventListener('click', () => toggleLocalPanel(true));
        collapseRow.appendChild(collapseBtn);
        container.insertBefore(collapseRow, container.firstChild);

        const miniFab = document.createElement('button');
        miniFab.id = 'pixiv-single-local-mini-fab';
        miniFab.textContent = '💾';
        miniFab.title = fabTitle;
        miniFab.style.cssText = 'display:none;position:fixed;top:310px;right:20px;z-index:10001;background:#0d9488;color:white;border:none;border-radius:50%;width:40px;height:40px;cursor:pointer;font-size:18px;box-shadow:0 2px 8px rgba(0,0,0,0.3);line-height:40px;text-align:center;padding:0;';
        miniFab.addEventListener('click', () => toggleLocalPanel(true));
        document.body.appendChild(miniFab);

        // 重建面板时沿用当前折叠状态（初始值来自共享存储，跨脚本变更经 onChange 同步）
        applyLocalPanelCollapsed();
    }

    // 更新下载UI状态
    function updateDownloadUI() {
        createDownloadUI();
    }

    /* ========== Tampermonkey 菜单：切换语言时需先 unregister 再重注册 ==========
     * GM_unregisterMenuCommand 在 Tampermonkey 4.13+ / Violentmonkey 中可用；
     * 老版本管理器中静默跳过（接受"菜单标签滞后到下次刷新"作为已知限制）。 */
    const menuCommandIds = [];

    function registerAllMenuCommands() {
        if (typeof GM_unregisterMenuCommand === 'function') {
            while (menuCommandIds.length) {
                try {
                    GM_unregisterMenuCommand(menuCommandIds.pop());
                } catch (e) {
                }
            }
        } else {
            menuCommandIds.length = 0;
        }
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.download-current'), downloadImages));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.download-current-novel'), downloadNovel));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.history'), showDownloadHistory));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.clear-history'), clearDownloadHistory));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.reset-folder-index'), resetFolderIndex));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.open'), () => setLocalPanelCollapsed(false, true)));
    }

    // 初始化下载UI
    async function initDownloadUI() {
        try {
            // 等待页面基本加载
            await waitForPageLoad();

            // 等待Pixiv主要内容区域加载
            await waitForElement('main, [role="main"], .sc-1n7super, .gtm-first-view', 15000);

            // 创建UI
            createDownloadUI();

            // 监听URL变化（Pixiv是单页应用）
            let lastUrl = location.href;
            new MutationObserver(() => {
                const url = location.href;
                if (url !== lastUrl) {
                    lastUrl = url;
                    // 延迟一点时间确保新页面内容已加载
                    setTimeout(createDownloadUI, 500);
                }
            }).observe(document, {subtree: true, childList: true});

            // 额外监听：每2秒检查一次页面变化（备用方案）
            setInterval(() => {
                if ((getArtworkId() || getNovelId()) && !document.getElementById('pixiv-local-downloader-ui')) {
                    createDownloadUI();
                }
            }, 2000);

        } catch (error) {
            console.log('Failed to initialize download UI:', error);
            // 如果失败，尝试简单方式添加UI
            setTimeout(createDownloadUI, 3000);
        }
    }

    // 注册菜单命令（首次）
    registerAllMenuCommands();

    // 添加快捷键支持 (Ctrl+Shift+D)
    document.addEventListener('keydown', function (e) {
        if (e.ctrlKey && e.shiftKey && e.key === 'D') {
            e.preventDefault();
            if (!getArtworkId() && getNovelId()) downloadNovel();
            else downloadImages();
        }
    });

    // 语言切换：重建浮动 UI + 重注册菜单命令
    PixivUserscriptI18n.onChange(() => {
        if (getArtworkId() || getNovelId()) {
            createDownloadUI();
        }
        registerAllMenuCommands();
    });

    // 跨脚本共享折叠状态：仅“收起”跨脚本传播（收一个=全收），“展开”不在此级联
    PixivPanelState.onChange(c => {
        if (c && !panelCollapsed) {
            panelCollapsed = true;
            applyLocalPanelCollapsed();
        }
    });

    // 跨脚本面板互斥：其它面板展开时收起本面板，避免同侧堆叠遮挡
    document.addEventListener('pixiv_panel_active', e => {
        if (e.detail && e.detail !== 'single-local' && !panelCollapsed) {
            panelCollapsed = true;
            applyLocalPanelCollapsed();
        }
    });

    // 启动初始化
    initDownloadUI();
})();
