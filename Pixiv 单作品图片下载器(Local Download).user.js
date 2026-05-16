// ==UserScript==
// @name              Pixiv作品图片下载器(Local download)
// @name:en           Pixiv Artwork Downloader (Local download)
// @namespace         http://tampermonkey.net/
// @version           1.3
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
            // Floating UI
            'local.ui.status.downloaded': '✅ Already downloaded',
            'local.ui.status.available': '⬇️ Ready to download',
            'local.ui.button.download': '📥 Download all images',
            'local.ui.label.artwork-id': 'Artwork ID: {id}',
            'local.ui.folder.default': 'default download folder',
            // Language switcher
            'local.switcher.label': 'Language',
            'switcher.option.en-US': 'English',
            'switcher.option.zh-CN': '简体中文'
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
            'local.ui.status.downloaded': '✅ 已下载过此作品',
            'local.ui.status.available': '⬇️ 可下载此作品',
            'local.ui.button.download': '📥 下载所有图片',
            'local.ui.label.artwork-id': '作品ID: {id}',
            'local.ui.folder.default': '默认下载目录',
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

        if (artworkIds.length === 0) {
            alert(t('local.alert.history-empty'));
            return;
        }

        let historyText = t('local.history.header', {count: artworkIds.length});

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

        alert(historyText);
    }

    // 清空下载记录
    function clearDownloadHistory() {
        const confirmClear = confirm(t('local.confirm.clear-history'));
        if (confirmClear) {
            GM_setValue('downloadedArtworks', {});
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

    // 创建下载UI
    function createDownloadUI() {
        // document-start 阶段 body 可能尚未出现：让后续轮询/MutationObserver 兜底
        if (!document.body) return;

        // 移除已存在的UI
        const existingUI = document.getElementById('pixiv-downloader-ui');
        if (existingUI) {
            existingUI.remove();
        }

        const artworkId = getArtworkId();
        if (!artworkId) return; // 如果不是作品页面，不显示UI

        const isDownloaded = isAlreadyDownloaded(artworkId);

        const container = document.createElement('div');
        container.id = 'pixiv-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 100px;
            right: 20px;
            z-index: 10000;
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
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.history'), showDownloadHistory));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.clear-history'), clearDownloadHistory));
        menuCommandIds.push(GM_registerMenuCommand(t('local.menu.reset-folder-index'), resetFolderIndex));
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
                const artworkId = getArtworkId();
                if (artworkId && !document.getElementById('pixiv-downloader-ui')) {
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
            downloadImages();
        }
    });

    // 语言切换：重建浮动 UI + 重注册菜单命令
    PixivUserscriptI18n.onChange(() => {
        if (getArtworkId()) {
            createDownloadUI();
        }
        registerAllMenuCommands();
    });

    // 启动初始化
    initDownloadUI();
})();
