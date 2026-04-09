// ==UserScript==
// @name         Pixiv N-Tab 批量下载器 (修复版)
// @namespace    http://tampermonkey.net/
// @version      2.0.4
// @description  解析 N-Tab 导出，批量提交作品给本地后端下载，支持严格的下载状态校验（修复下载失败显示完成的Bug）。
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

    // artworks 页由单作品脚本处理
    if (/^https:\/\/www\.pixiv\.net\/artworks\/\d+/.test(location.href)) {
        return;
    }

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

    // 处理 solo 模式未登录（401），标志位避免批量时重复弹窗
    let _unauthorizedHandled = false;

    function handleUnauthorized() {
        if (_unauthorizedHandled) return;
        _unauthorizedHandled = true;
        alert('后端服务需要登录验证，即将为您打开登录页面...');
        window.open(serverBase + '/login.html', '_blank');
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
        sendDownloadRequest(artworkId, imageUrls, title, ugoiraData, delayMs, bookmark) {
            return new Promise((resolve, reject) => {
                const other = {userDownload: false, delayMs: delayMs || 0, bookmark: !!bookmark};
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
        checkDownloaded(artworkId) {
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${serverBase}/api/downloaded/${artworkId}`,
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
                    onerror: (err) => {
                        console.error('SSE connection error', err);
                        this._cleanup(key);
                    },
                    ontimeout: () => this._cleanup(key)
                });

                this.sources.set(key, handle);
            } catch (err) {
                console.error('SSE open failed', err);
            }
        }

        _readStream(key, stream) {
            const reader = stream.getReader();
            this._readers.set(key, reader);
            const decoder = new TextDecoder();
            const pump = () => {
                reader.read().then(({done, value}) => {
                    if (done) {
                        this._cleanup(key);
                        return;
                    }
                    const chunk = decoder.decode(value, {stream: true});
                    let buffer = (this._buffers.get(key) || '') + chunk;
                    const parts = buffer.split('\n\n');
                    this._buffers.set(key, parts.pop()); // 最后一段可能不完整，留作缓冲
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
                } catch (e) { /* 心跳等非 JSON 事件忽略 */
                }
            }
        }

        _cleanup(key) {
            this.sources.delete(key);
            this._buffers.delete(key);
            const reader = this._readers.get(key);
            if (reader) {
                try {
                    reader.cancel();
                } catch (e) {
                }
                this._readers.delete(key);
            }
        }

        close(artworkId) {
            const key = String(artworkId);
            const handle = this.sources.get(key);
            if (handle) {
                try {
                    handle.abort();
                } catch (e) {
                }
            }
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
            this.queue = [];
            this.isRunning = false;
            this.isPaused = false;
            this.sse = new SSEManager();
            this.stopRequested = false;
            this.activeWorkers = 0;
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
            this.skipHistory = GM_getValue(CONFIG.SKIP_HISTORY_KEY, false);
            this.r18Only = GM_getValue(CONFIG.R18_ONLY_KEY, false);
            this.bookmark = GM_getValue(CONFIG.BOOKMARK_KEY, false);
            this._quotaExceededHandled = false;
        }

        loadFromStorage() {
            try {
                const raw = GM_getValue(CONFIG.STORAGE_KEY, null);
                if (!raw) return;
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed.queue)) {
                    // 刷新后 downloading 状态的任务实际已中断，重置为 idle 以便重新下载
                    this.queue = parsed.queue.map(q =>
                        q.status === 'downloading' ? {...q, status: 'idle', lastMessage: '刷新后重置'} : q
                    );
                    this.isRunning = false; // 启动时默认暂停，避免自动跑
                    this.isPaused = !!parsed.isPaused;
                    this.stats = parsed.stats || {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};
                    this.skipHistory = parsed.skipHistory !== undefined ? parsed.skipHistory : this.skipHistory;
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
                GM_deleteValue(CONFIG.R18_ONLY_KEY);
            } catch (e) {
            }
        }

        setSkipHistory(skip) {
            this.skipHistory = skip;
            GM_setValue(CONFIG.SKIP_HISTORY_KEY, skip);
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
            this.queue = items.map(it => ({
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
        }

        parseAndSetFromText(rawText) {
            const lines = rawText.split('\n').map(l => l.trim()).filter(Boolean);
            const items = [];
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
                alert('后端服务不可用。');
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
                const isDownloaded = await Api.checkDownloaded(item.id);
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
                if (meta && meta.illustTitle) item.title = meta.illustTitle;

                if (this.r18Only) {
                    const restriction = meta ? (meta.xRestrict ?? meta.xrestrict ?? 0) : 0;
                    if (restriction === 0) {
                        item.status = 'skipped';
                        item.lastMessage = '跳过 — 非 R18 内容';
                        item.endTime = new Date().toISOString();
                        this.updateStats();
                        this.saveToStorage();
                        this.ui.renderQueue(this.queue);
                        this.ui.setStatus(`跳过：${item.title}（非R18）`, 'warning');
                        return;
                    }
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
                } else {
                    urls = await Api.getArtworkPages(item.id);
                    if (!Array.isArray(urls) || urls.length === 0) throw new Error('未获取到图片 URL');
                    item.totalImages = urls.length;
                }

                item.downloadedCount = 0;
                this.saveToStorage();
                this.ui.renderQueue(this.queue);

                const dlData = await Api.sendDownloadRequest(item.id, urls, item.title, ugoiraData, this.getImageDelayMs(), this.bookmark);
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
                    if (!confirm('当前有正在下载的作品，是否强制停止并清除？（取消将保留队列）')) return;
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
                    position: 'fixed', top: '120px', right: '20px', zIndex: 10000,
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
                title: '收起',
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
                html: '🎨 N-Tab批量下载器 v1.2.1 (支持动图)',
                style: {fontWeight: 'bold', color: '#333', textAlign: 'center', fontSize: '16px', flex: '1'}
            });
            collapseBtn.addEventListener('click', () => this.toggleCollapse());
            titleRow.appendChild(collapseBtn);
            titleRow.appendChild(titleText);

            // 收起后的悬浮按钮
            const existingFab = document.getElementById('ntab-mini-fab');
            if (existingFab) existingFab.remove();
            const miniFab = $el('button', {
                id: 'ntab-mini-fab',
                innerText: '🎨',
                title: 'N-Tab批量下载器',
                style: {
                    display: 'none',
                    position: 'fixed',
                    top: '60px',
                    right: '20px',
                    zIndex: '10001',
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
                innerText: '准备就绪',
                style: {marginBottom: '10px', color: '#666', fontSize: '12px', textAlign: 'center'}
            });
            const stats = $el('div', {
                id: 'batch-stats',
                innerText: '队列: 0 | 成功: 0 | 失败: 0 | 进行中: 0 | 跳过: 0',
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
                placeholder: `粘贴 N-Tab 导出的内容...`,
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

            const settings = $el('div', {style: {marginBottom: '15px'}});
            settings.innerHTML = `
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">作品间隔:</label>
                    <input type="number" id="download-interval" value="${CONFIG.DEFAULT_INTERVAL}" min="0" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="interval-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">s</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">图片间隔:</label>
                    <input type="number" id="image-delay" value="0" min="0" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px 0 0 4px;">
                    <button id="image-delay-unit-btn" style="padding: 4px 7px; font-size: 12px; font-weight: bold; border: 1px solid #ddd; border-left: none; border-radius: 0 4px 4px 0; background: #f0f0f0; cursor: pointer;">ms</button>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">最大并发数:</label>
                    <input type="number" id="max-concurrent" value="${CONFIG.DEFAULT_CONCURRENT}" min="1" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">跳过历史下载:</label>
                    <input type="checkbox" id="skip-history" style="width: 16px; height: 16px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px; color: #d63384;">仅下载R18作品:</label>
                    <input type="checkbox" id="r18-only" style="width: 16px; height: 16px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">下载后自动收藏:</label>
                    <input type="checkbox" id="bookmark-after-dl" style="width: 16px; height: 16px;">
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
                    id: 'parse-btn',
                    text: '📋 解析N-Tab数据',
                    bgColor: '#007bff',
                    onClick: () => this.manager.parseAndSetFromText(this.elements.textarea.value)
                },
                {id: 'start-btn', text: '🚀 开始批量下载', bgColor: '#28a745', onClick: () => this.handleStart()},
                {
                    id: 'retry-failed-btn',
                    text: '🔁 重新下载失败的作品',
                    bgColor: '#17a2b8',
                    onClick: () => this.handleRetryFailed()
                },
                {
                    id: 'pause-btn',
                    text: '⏸️ 暂停下载',
                    bgColor: '#ffc107',
                    onClick: () => this.handlePause(),
                    disabled: true
                },
                {
                    id: 'export-all-btn',
                    text: '📤 导出下载列表',
                    bgColor: '#007bff',
                    onClick: () => this.handleExportAll()
                },
                {
                    id: 'export-failed-btn',
                    text: '📋 导出未下载列表',
                    bgColor: '#6610f2',
                    onClick: () => this.handleExportFailed()
                },
                {id: 'clear-btn', text: '🗑️ 清除队列', bgColor: '#6c757d', onClick: () => this.handleClear()}
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
            currentDownload.innerHTML = '<strong>当前下载:</strong> 无';

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
            this.elements.r18Only.checked = manager.r18Only;
            this.elements.bookmarkAfterDl.checked = manager.bookmark;
            this.elements.skipHistory.addEventListener('change', (e) => {
                this.manager.setSkipHistory(e.target.checked);
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
        }

        async handleStart() {
            const concurrent = Math.max(1, parseInt(this.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT);
            this.manager.start(this.manager.getIntervalMs(), concurrent);
        }

        handlePause() {
            if (!this.manager) return;
            if (!this.manager.isPaused) {
                this.manager.pause();
                this.elements.pauseBtn.innerText = '▶️ 继续下载';
            } else {
                this.manager.resume();
                this.elements.pauseBtn.innerText = '⏸️ 暂停下载';
            }
        }

        handleRetryFailed() {
            if (!this.manager) return;
            const failedItems = this.manager.queue.filter(q => q.status === 'failed');
            if (failedItems.length === 0) {
                alert('当前没有失败的作品！');
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
            if (confirm('确认强制清除队列？')) await this.manager.stopAndClear(true);
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
            return `<div style="margin-top: 3px;"><div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;"><span>已下载: ${downloadedCount}/${q.totalImages}</span><span>${progressPercent}%</span></div><div style="width: 100%; height: 4px; background: #e0e0e0; border-radius: 2px; overflow: hidden;"><div style="height: 100%; background: #007bff; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }

        setStatus(msg, type = 'info') {
            this.elements.status.innerText = msg;
            this.elements.status.style.color = {
                'info': '#007bff',
                'success': '#28a745',
                'error': '#dc3545',
                'warning': '#ffc107'
            }[type] || '#666';
        }

        updateStats(stats) {
            const pendingCount = this.manager.queue.filter(q => q.status === 'pending' || q.status === 'idle' || q.status === 'paused').length;
            this.elements.stats.textContent = `队列: ${pendingCount} | 成功: ${stats.success} | 失败: ${stats.failed} | 进行中: ${stats.active} | 跳过: ${stats.skipped}`;
        }

        updateButtonsState(isRunning, isPaused) {
            this.elements.startBtn.disabled = isRunning;
            this.elements.pauseBtn.disabled = !isRunning;
            this.elements.pauseBtn.innerText = isPaused ? '▶️ 继续下载' : '⏸️ 暂停下载';
        }

        setCurrent(item) {
            const container = this.elements.currentDownload;
            if (!item) {
                container.innerHTML = '<strong>当前下载:</strong> 无';
                return;
            }
            const progressHtml = this._createCurrentProgressHtml(item);
            container.innerHTML = `<strong>当前下载:</strong> ${item.title} (ID: ${item.id})${progressHtml}`;
        }

        _createCurrentProgressHtml(item) {
            if (item.totalImages <= 0) return '';
            const downloadedCount = item.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / item.totalImages) * 100), 100);
            return `<div style="margin-top: 5px;"><div style="display: flex; justify-content: space-between; font-size: 10px; margin-bottom: 3px;"><span>已下载 ${downloadedCount} 张 / 共 ${item.totalImages} 张</span><span>${progressPercent}%</span></div><div style="width: 100%; height: 6px; background: #e0e0e0; border-radius: 3px; overflow: hidden;"><div style="height: 100%; background: #28a745; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
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
                alert('队列为空，无内容可导出');
                return;
            }
            const lines = this.manager.queue.map(item => `https://www.pixiv.net/artworks/${item.id} | ${item.title}`);
            this._downloadTxt(lines.join('\n'), 'pixiv_ntab_all_list.txt');
            this.setStatus(`已导出 ${lines.length} 个作品`, 'success');
        }

        handleExportFailed() {
            const items = this.manager.queue.filter(q => q.status !== 'completed');
            if (items.length === 0) {
                alert('没有未下载的作品');
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
                'idle': '等待中',
                'pending': '等待中',
                'downloading': '下载中',
                'completed': '已完成',
                'failed': '失败',
                'paused': '暂停中',
                'skipped': '已跳过'
            }[status] || status;
        }

        // ---- 配额 UI 方法 ----

        updateQuotaBar(info) {
            const bar = document.getElementById('pixiv-ntab-quota-bar');
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
            const card = document.getElementById('pixiv-ntab-archive-card');
            if (!card) return;
            card.style.display = 'block';
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">${title}</div>
              <div id="pixiv-ntab-ac-status" style="font-size:11px;color:#666;">正在打包已下载文件，请稍候...</div>
              <div id="pixiv-ntab-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ntab-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">下载链接已过期</div>`;
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
            card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:6px;">已有未下载的压缩包</div>
              <div id="pixiv-ntab-ac-status" style="font-size:11px;color:#666;"></div>
              <div id="pixiv-ntab-ac-dl" style="display:none;margin-top:6px;"></div>
              <div id="pixiv-ntab-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">下载链接已过期</div>`;
            if (ready) {
                this._activateArchiveDl(token, expireSec);
            } else {
                document.getElementById('pixiv-ntab-ac-status').textContent = '正在打包已下载文件，请稍候...';
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
                    if (status) status.textContent = '暂无可打包文件';
                }
            }, 2000);
        }

        _activateArchiveDl(token, expireSec) {
            clearInterval(this._archiveCountdownTimer);
            const statusEl = document.getElementById('pixiv-ntab-ac-status');
            const dlEl = document.getElementById('pixiv-ntab-ac-dl');
            if (statusEl) statusEl.textContent = '压缩包已就绪：';
            if (dlEl) {
                dlEl.style.display = 'block';
                const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
                dlEl.innerHTML = `<a href="${CONFIG.ARCHIVE_DOWNLOAD_BASE}/${token}" download="${filename}"
                  style="display:inline-block;padding:5px 12px;background:#28a745;color:white;
                         border-radius:4px;text-decoration:none;font-size:12px;font-weight:bold;">
                  下载压缩包
                </a>
                <span id="pixiv-ntab-ac-countdown" style="font-size:10px;color:#888;margin-left:8px;"></span>`;
                let remaining = Math.max(0, parseInt(expireSec));
                const el = () => document.getElementById('pixiv-ntab-ac-countdown');
                if (el()) el().textContent = '有效期：' + this._fmtSeconds(remaining);
                this._archiveCountdownTimer = setInterval(() => {
                    remaining--;
                    if (remaining <= 0) {
                        clearInterval(this._archiveCountdownTimer);
                        const expired = document.getElementById('pixiv-ntab-ac-expired');
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
    ui.setStatus('就绪');

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

    GM_registerMenuCommand('打开 Pixiv N-Tab 批量下载器', () => {
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