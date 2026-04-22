// ==UserScript==
// @name         Pixiv User 批量下载器
// @namespace    http://tampermonkey.net/
// @version      2.0.7
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

    // 首次启动提示（只显示一次）
    function checkExternalServerNotice() {
        const key = 'pixiv_connect_notice_shown';
        if (GM_getValue(key, false)) return;
        GM_setValue(key, true);
        alert(
            'Pixiv 下载脚本初始化提示\n\n' +
            '如果您使用外部服务器（非 localhost），需将三个脚本头部的：\n' +
            '  // @connect      YOUR_SERVER_HOST\n' +
            '替换为实际的服务器 IP 或域名，例如：\n' +
            '  // @connect      192.168.1.100\n\n' +
            '修改路径：Tampermonkey 管理面板 → 对应脚本 → 编辑 → 保存\n\n' +
            '或者直接通过网页端下载作品（无需脚本）：\n' +
            serverBase + '/login.html\n\n' +
            '（此提示只显示一次）'
        );
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

    // 处理 solo 模式未登录（401），标志位避免批量时重复弹窗
    let _unauthorizedHandled = false;
    function handleUnauthorized() {
        if (_unauthorizedHandled) return;
        _unauthorizedHandled = true;
        alert('后端服务需要登录验证，即将为您打开登录页面...');
        window.open(serverBase + '/login.html', '_blank');
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

    /* ========== SSE 管理器（基于 GM_xmlhttpRequest + ReadableStream，绕过 CORS / 混合内容限制） ========== */
    class SSEManager {
        constructor() {
            this.sources = new Map();   // artworkId -> GM_xmlhttpRequest abort handle
            this.listeners = new Map();
            this._buffers = new Map();  // artworkId -> 未解析完的 SSE 文本缓冲
            this._readers = new Map();  // artworkId -> ReadableStream reader
        }
        open(artworkId) {
            try {
                this.close(artworkId);
                const key = String(artworkId);
                this._buffers.set(key, '');
                const headers = {};
                if (userUUID) headers['X-User-UUID'] = userUUID;
                const handle = GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${CONFIG.SSE_BASE}/${artworkId}`,
                    headers,
                    responseType: 'stream',
                    onloadstart: (res) => {
                        const stream = res.response;
                        if (!stream || typeof stream.getReader !== 'function') {
                            console.warn('SSE: ReadableStream 不可用，将依赖轮询兜底');
                            return;
                        }
                        this._readStream(key, stream);
                    },
                    onerror: (err) => { console.error('SSE connection error', err); this._cleanup(key); },
                    ontimeout: () => this._cleanup(key)
                });
                this.sources.set(key, handle);
            } catch (err) { console.error('SSE open failed', err); }
        }
        _readStream(key, stream) {
            const reader = stream.getReader();
            this._readers.set(key, reader);
            const decoder = new TextDecoder();
            const pump = () => {
                reader.read().then(({ done, value }) => {
                    if (done) { this._cleanup(key); return; }
                    const chunk = decoder.decode(value, { stream: true });
                    let buffer = (this._buffers.get(key) || '') + chunk;
                    const parts = buffer.split('\n\n');
                    this._buffers.set(key, parts.pop());
                    for (const part of parts) {
                        if (!part.trim()) continue;
                        this._processEvent(key, part);
                    }
                    pump();
                }).catch(() => this._cleanup(key));
            };
            pump();
        }
        _processEvent(key, rawEvent) {
            let eventName = '';
            const dataLines = [];
            for (const line of rawEvent.split('\n')) {
                if (line.startsWith('event:')) eventName = line.substring(6).trim();
                else if (line.startsWith('data:')) dataLines.push(line.substring(5));
            }
            if (eventName === 'download-status' && dataLines.length > 0) {
                try {
                    const parsed = JSON.parse(dataLines.join('\n'));
                    (this.listeners.get(key) || []).forEach(fn => fn(parsed));
                } catch (e) { /* 心跳等非 JSON 事件忽略 */ }
            }
        }
        _cleanup(key) {
            this.sources.delete(key);
            this._buffers.delete(key);
            const reader = this._readers.get(key);
            if (reader) { try { reader.cancel(); } catch (e) {} this._readers.delete(key); }
        }
        close(artworkId) {
            const key = String(artworkId);
            const handle = this.sources.get(key);
            if (handle) { try { handle.abort(); } catch (e) {} }
            this._cleanup(key);
            this.listeners.delete(key);
        }
        closeAll() {
            for (const k of Array.from(this.sources.keys())) this.close(k);
            this.listeners.clear();
        }
        addListener(artworkId, fn) {
            const key = String(artworkId);
            if (!this.listeners.has(key)) this.listeners.set(key, []);
            this.listeners.get(key).push(fn);
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
                        q.status === 'downloading' ? {...q, status: 'idle', lastMessage: '刷新后重置'} : q
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
                        startTime: null, endTime: null, lastMessage: ''
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
                alert('后端服务不可用，如果您使用是非localhost地址，请遵循README说明替换 脚本头部 @connect YOUR_SERVER_HOST 为您的服务器地址');
                return;
            }

            const intervalMs = this.getIntervalMs();
            const maxConcurrent = this.globalSettings.concurrent;

            // 只重置 idle/failed/paused 的项目
            this.queue.forEach(q => {
                if (['idle', 'failed', 'paused'].includes(q.status)) q.status = 'pending';
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
                    this.ui.setStatus(`失败：${item.title}`, 'error');
                } else {
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
                        if (q && data.downloadedCount !== undefined) {
                            q.downloadedCount = data.downloadedCount;
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
                title: '收起',
                style: { background: 'none', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '12px', padding: '2px 6px', color: '#666', flexShrink: '0' }
            });
            const title = $el('div', {
                id: 'batch-ui-title',
                innerText: '🖼️ Pixiv User 批量下载器',
                style: { fontWeight: 'bold', color: '#333', textAlign: 'center', fontSize: '16px', flex: '1' }
            });
            collapseBtn.addEventListener('click', () => this.toggleCollapse());
            titleRow.appendChild(collapseBtn);
            titleRow.appendChild(title);

            // 收起后的悬浮按钮
            const existingFab = document.getElementById('user-batch-mini-fab');
            if (existingFab) existingFab.remove();
            const miniFab = $el('button', {
                id: 'user-batch-mini-fab',
                innerText: '🖼️',
                title: 'User批量下载器',
                style: { display: 'none', position: 'fixed', top: '110px', right: '20px', zIndex: '1000001', background: '#28a745', color: 'white', border: 'none', borderRadius: '50%', width: '40px', height: '40px', cursor: 'pointer', fontSize: '18px', boxShadow: '0 2px 8px rgba(0,0,0,0.3)', lineHeight: '40px', textAlign: 'center', padding: '0' }
            });
            miniFab.addEventListener('click', () => this.toggleCollapse());
            document.body.appendChild(miniFab);

            const status = $el('div', {
                innerText: '准备就绪',
                style: {marginBottom: '10px', color: '#666', fontSize: '12px', textAlign: 'center'}
            });
            const stats = $el('div', {
                innerText: '队列: 0 | 成功: 0 | 失败: 0 | 进行中: 0 | 跳过: 0',
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
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">作品间隔:</label>
                    <input type="number" id="download-interval" min="0" value="${CONFIG.DEFAULT_INTERVAL}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="interval-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">s</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">图片间隔:</label>
                    <input type="number" id="image-delay" min="0" value="0"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="image-delay-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">ms</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">最大并发数:</label>
                    <input type="number" id="max-concurrent" min="1" value="${CONFIG.DEFAULT_CONCURRENT}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px; cursor:pointer;">
                        <input type="checkbox" id="skip-history" style="vertical-align: middle;"> 跳过历史下载
                    </label>
                    <label style="font-size: 12px; cursor:pointer; margin-left:15px; color:#d63384;">
                        <input type="checkbox" id="r18-only" style="vertical-align: middle;"> 仅下载R18作品
                    </label>
                </div>
                <div id="verify-history-files-row" style="display:none; align-items:center; margin-top: 8px; margin-bottom: 8px;">
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="verify-history-files" style="vertical-align: middle;"> 实际目录检测
                    </label>
                    ${createHelpIcon(VERIFY_HISTORY_FILES_TOOLTIP).outerHTML}
                </div>
                <div style="display: flex; align-items: center; margin-top: 8px; margin-bottom: 8px;">
                    <label style="font-size: 12px; cursor:pointer;">
                        <input type="checkbox" id="bookmark-after-dl" style="vertical-align: middle;"> 下载后自动收藏
                    </label>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">服务器地址:</label>
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
                    text: '📥 获取该画师所有作品',
                    bgColor: '#17a2b8',
                    onClick: () => this.handleFetch(false)
                },
                {
                    id: 'fetch-new-btn',
                    text: '🆕 获取并仅下载新作品',
                    bgColor: '#6610f2',
                    onClick: () => this.handleFetch(true)
                },
                {id: 'start-btn', text: '🚀 开始批量下载', bgColor: '#28a745', onClick: () => this.handleStart()},
                {id: 'retry-btn', text: '🔁 重新下载失败的作品', bgColor: '#17a2b8', onClick: () => this.handleRetry()},
                {
                    id: 'pause-btn',
                    text: '⏸️ 暂停下载',
                    bgColor: '#ffc107',
                    onClick: () => this.handlePause(),
                    disabled: true
                },
                {id: 'export-btn', text: '📤 导出下载列表', bgColor: '#007bff', onClick: () => this.handleExport()},
                {id: 'export-failed-btn', text: '📋 导出未下载列表', bgColor: '#6610f2', onClick: () => this.handleExportFailed()},
                {id: 'clear-btn', text: '🗑️ 清除队列', bgColor: '#6c757d', onClick: () => this.handleClear()}
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
            currentDownload.innerHTML = '<strong>当前下载:</strong> 无';

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
                ? ` | 重置剩余：${this._fmtSeconds(info.resetSeconds)}` : '';
            bar.style.display = 'block';
            bar.innerHTML = `<div style="display:flex;align-items:center;gap:6px;">
              <span style="white-space:nowrap;">配额：${info.artworksUsed}/${info.maxArtworks} 个作品</span>
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
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${title}</div>
              <div id="pixiv-ac-status" style="font-size:11px;color:#666;">正在打包已下载文件，请稍候...</div>
              <div id="pixiv-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">下载链接已过期</div>`;

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
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">已有未下载的压缩包</div>
              <div id="pixiv-ac-status" style="font-size:11px;color:#666;"></div>
              <div id="pixiv-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">下载链接已过期</div>`;
            if (ready) {
                this._activateArchiveDl(token, expireSec);
            } else {
                document.getElementById('pixiv-ac-status').textContent = '正在打包已下载文件，请稍候...';
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
                    if (status) status.textContent = '暂无可打包文件';
                }
            }, 2000);
        }

        _activateArchiveDl(token, expireSec) {
            clearInterval(this._archiveCountdownTimer);
            const statusEl = document.getElementById('pixiv-ac-status');
            const dlEl = document.getElementById('pixiv-ac-dl');
            if (statusEl) statusEl.textContent = '压缩包已就绪：';
            if (dlEl) {
                dlEl.style.display = 'block';
                const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
                dlEl.innerHTML = `<a href="${CONFIG.ARCHIVE_DOWNLOAD_BASE}/${token}" download="${filename}"
                  style="display:inline-block;padding:5px 12px;background:#28a745;color:white;
                         border-radius:4px;text-decoration:none;font-size:12px;font-weight:bold;">
                  下载压缩包
                </a>
                <span id="pixiv-ac-countdown" style="font-size:10px;color:#888;margin-left:8px;"></span>`;
                let remaining = Math.max(0, parseInt(expireSec));
                const el = () => document.getElementById('pixiv-ac-countdown');
                if (el()) el().textContent = '有效期：' + this._fmtSeconds(remaining);
                this._archiveCountdownTimer = setInterval(() => {
                    remaining--;
                    if (remaining <= 0) {
                        clearInterval(this._archiveCountdownTimer);
                        const expired = document.getElementById('pixiv-ac-expired');
                        if (dlEl) dlEl.style.display = 'none';
                        if (expired) expired.style.display = 'block';
                    } else {
                        if (el()) el().textContent = '有效期：' + this._fmtSeconds(remaining);
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
                    // 显示格式：User: 用户名(用户ID)
                    displayText = `User: ${userName}(${uid})`;
                } else {
                    // 如果还没有获取到用户名，只显示用户ID
                    displayText = `User: ${uid}`;
                }
                this.elements.title.innerText = `🖼️ ${displayText}`;
            }
        }

        async handleFetch(onlyNew) {
            if (!this.manager.userId) return alert('未检测到用户ID');
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
            if (!failed.length) return alert('当前没有失败的作品！');
            failed.forEach(q => {
                q.status = 'pending';
                q.lastMessage = '';
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
                alert('没有未下载的作品');
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
            if (confirm('确认强制清除队列？')) this.manager.stopAndClear(false);
        }

        handleExport() {
            if (!this.manager.queue || this.manager.queue.length === 0) {
                alert('队列为空，无内容可导出');
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
            node.innerHTML = '<div style="font-weight: bold; margin-bottom: 5px;">下载队列:</div>';
            if (!queue || queue.length === 0) {
                node.innerHTML += '<div style="color: #666; text-align: center;">队列为空</div>';
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
                const desc = q.lastMessage || this._statusText(q.status);
                const canRemove = q.status !== 'downloading';
                const removeBtn = canRemove
                    ? `<button data-remove-id="${q.id}" title="从队列移除" style="background:none;border:none;color:#aaa;cursor:pointer;font-size:11px;padding:1px 2px;line-height:1;">✕</button>`
                    : '';
                const linkBtn = `<a href="https://www.pixiv.net/artworks/${q.id}" target="_blank" title="打开作品页面" style="color:#007bff;font-size:11px;padding:1px 2px;text-decoration:none;line-height:1;">🔗</a>`;
                item.innerHTML = `<div style="display:flex;justify-content:space-between;align-items:center;"><strong style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:4px;">${escapeHtml(q.title || 'ID: ' + q.id)}</strong><span style="display:flex;gap:1px;flex-shrink:0;">${linkBtn}${removeBtn}</span></div><div>ID: ${q.id} | <span style="color:${this._colorByStatus(q.status)};font-weight:bold;">${escapeHtml(desc)}</span></div>${progressHtml}`;
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
                container.innerHTML = '<strong>当前下载:</strong> 无';
                return;
            }
            const progressHtml = this._createProgressHtml(item, true);
            container.innerHTML = `<strong>当前下载:</strong> ${escapeHtml(item.title)} (ID: ${item.id})${progressHtml}`;
        }

        updateStats(stats) {
            const pendingCount = this.manager.queue.filter(q => ['pending', 'paused', 'idle'].includes(q.status)).length;
            this.elements.stats.textContent = `队列: ${pendingCount} | 成功: ${stats.success} | 失败: ${stats.failed} | 进行中: ${stats.active} | 跳过: ${stats.skipped}`;
        }

        setStatus(msg, type = 'info') {
            this.elements.status.innerText = msg;
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
            this.elements.pauseBtn.innerText = isPaused ? '▶️ 继续下载' : '⏸️ 暂停下载';
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
                'idle': '等待中',
                'pending': '等待中',
                'downloading': '下载中',
                'completed': '已完成',
                'failed': '失败',
                'paused': '暂停中',
                'skipped': '已跳过'
            }[status] || status;
        }

        _createProgressHtml(q, isMain = false) {
            if (q.totalImages <= 0) return '';
            const downloadedCount = q.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / q.totalImages) * 100), 100);
            return `<div style="margin-top: 3px;"><div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;"><span>已下载: ${downloadedCount}/${q.totalImages}</span><span>${progressPercent}%</span></div><div style="width: 100%; height: ${isMain ? '6px' : '4px'}; background: #e0e0e0; border-radius: 2px; overflow: hidden;"><div style="height: 100%; background: ${isMain ? '#28a745' : '#007bff'}; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }
    }

    const ui = new UI();
    const manager = new DownloadManager(ui);
    ui.bindManager(manager);

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

    GM_registerMenuCommand('强制打开下载面板', () => {
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
