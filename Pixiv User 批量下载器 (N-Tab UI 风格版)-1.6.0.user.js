// ==UserScript==
// @name         Pixiv User 批量下载器 (N-Tab UI 风格版)
// @namespace    http://tampermonkey.net/
// @version      1.7.3
// @description  适配 Pixiv 用户页面，自动获取所有作品 ID，对接本地 Go 后端。界面复刻 N-Tab 风格。优化暂停逻辑：确保当前任务完成后再停止。已加入全局 username 机制。在标题显示用户名。
// @author       Rewritten by ChatGPT
// @match        https://www.pixiv.net/*
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_deleteValue
// @grant        GM_registerMenuCommand
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @connect      localhost
// @run-at       document-end
// ==/UserScript==

(function () {
    'use strict';

    console.log('[Pixiv Batch] Script Loaded (v1.7.2 - Username in title)');

    /* ========== 配置 ========== */
    const CONFIG = {
        BACKEND_URL: "http://localhost:6999/api/download/pixiv",
        STATUS_URL: "http://localhost:6999/api/download/status",
        CANCEL_URL: "http://localhost:6999/api/download/cancel",
        SSE_BASE: "http://localhost:6999/api/sse/download",
        CHECK_DOWNLOADED_URL: "http://localhost:6999/api/downloaded",
        DEFAULT_INTERVAL: 2,
        DEFAULT_CONCURRENT: 1,
        MAX_CONCURRENT: 5,
        STATUS_TIMEOUT_MS: 300000,

        // 全局持久化 Keys
        KEY_SKIP_HISTORY: 'pixiv_global_skip_history',
        KEY_R18_ONLY: 'pixiv_global_r18_only',
        KEY_INTERVAL: 'pixiv_global_interval',
        KEY_CONCURRENT: 'pixiv_global_concurrent'
    };

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
        sendDownloadRequest(artworkId, imageUrls, title, usernameParam, isR18) {
            return new Promise((resolve, reject) => {
                const payload = {
                    artworkId: parseInt(artworkId),
                    imageUrls,
                    title,
                    referer: 'https://www.pixiv.net/',
                    other: {
                        userDownload: true,
                        username: usernameParam,
                        isR18: isR18
                    }
                };
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: CONFIG.BACKEND_URL,
                    headers: {'Content-Type': 'application/json'},
                    data: JSON.stringify(payload),
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (res.status === 200) resolve(data);
                            else reject(new Error(data.message || '后端返回失败'));
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
        checkDownloaded(artworkId) {
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `${CONFIG.CHECK_DOWNLOADED_URL}/${artworkId}`,
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
        }
    };

    /* ========== SSE 管理器 ========== */
    class SSEManager {
        constructor() {
            this.sources = new Map();
            this.listeners = new Map();
        }

        open(artworkId) {
            this.close(artworkId);
            try {
                const src = new EventSource(`${CONFIG.SSE_BASE}/${artworkId}`);
                src.addEventListener('download-status', (e) => {
                    try {
                        const data = JSON.parse(e.data);
                        (this.listeners.get(String(artworkId)) || []).forEach(fn => fn(data));
                    } catch (err) {
                    }
                });
                this.sources.set(String(artworkId), src);
            } catch (err) {
                console.error('SSE Error', err);
            }
        }

        close(artworkId) {
            const key = String(artworkId);
            const s = this.sources.get(key);
            if (s) {
                s.close();
                this.sources.delete(key);
            }
            this.listeners.delete(key);
        }

        closeAll() {
            for (const k of Array.from(this.sources.keys())) this.close(k);
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
            this.sse = new SSEManager();
            this.stopRequested = false;
            this.activeWorkers = 0; // 追踪活跃 Worker 数量
            this.stats = {completed: 0, success: 0, failed: 0, active: 0, skipped: 0};

            this.globalSettings = {
                interval: GM_getValue(CONFIG.KEY_INTERVAL, CONFIG.DEFAULT_INTERVAL) || CONFIG.DEFAULT_INTERVAL,
                concurrent: GM_getValue(CONFIG.KEY_CONCURRENT, CONFIG.DEFAULT_CONCURRENT) || CONFIG.DEFAULT_CONCURRENT,
                skipHistory: GM_getValue(CONFIG.KEY_SKIP_HISTORY, false),
                r18Only: GM_getValue(CONFIG.KEY_R18_ONLY, false)
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
                    this.queue = parsed.queue;
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
        }

        setR18Only(val) {
            this.globalSettings.r18Only = val;
            GM_setValue(CONFIG.KEY_R18_ONLY, val);
        }

        setInterval(val) {
            this.globalSettings.interval = parseInt(val) || CONFIG.DEFAULT_INTERVAL;
            GM_setValue(CONFIG.KEY_INTERVAL, this.globalSettings.interval);
        }

        setConcurrent(val) {
            let num = parseInt(val) || CONFIG.DEFAULT_CONCURRENT;
            if (num > CONFIG.MAX_CONCURRENT) num = CONFIG.MAX_CONCURRENT;
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
                alert('后端服务不可用');
                return;
            }

            const intervalSec = this.globalSettings.interval;
            const maxConcurrent = this.globalSettings.concurrent;

            // 只重置 idle/failed/paused 的项目
            this.queue.forEach(q => {
                if (['idle', 'failed', 'paused'].includes(q.status)) q.status = 'pending';
            });
            this.isRunning = true;
            this.isPaused = false;
            this.stopRequested = false;
            this.activeWorkers = 0;
            this.updateStats();
            this.ui.updateButtonsState(true, false);
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            this.ui.setStatus(`开始下载 (并发:${maxConcurrent}, 间隔:${intervalSec}s)`, 'info');

            const workers = [];
            for (let i = 0; i < Math.max(1, Math.min(maxConcurrent, 5)); i++) {
                workers.push(this.workerLoop(intervalSec * 1000));
            }
            await Promise.all(workers);

            // 所有 Worker 结束
            this.isRunning = false;
            this.saveToStorage();
            this.ui.setStatus('批量下载结束', 'info');
            this.ui.updateButtonsState(false, false);
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
            const idx = this.queue.findIndex(q => q.status === 'pending');
            if (idx === -1) return null;
            this.queue[idx].status = 'downloading';
            this.queue[idx].startTime = new Date().toISOString();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return {idx, item: this.queue[idx]};
        }

        async _processSingle({item}) {
            if (this.globalSettings.skipHistory) {
                const isDownloaded = await Api.checkDownloaded(item.id);
                if (isDownloaded) {
                    item.status = 'skipped';
                    item.lastMessage = '已跳过（历史存在）';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    return;
                }
            }

            this.ui.setCurrent(item);
            this.ui.setStatus(`获取信息：${item.id}`, 'info');

            try {
                const meta = await Api.getArtworkMeta(item.id);
                const safeTitle = (meta && meta.illustTitle) ? meta.illustTitle : `Artwork ${item.id}`;
                item.title = safeTitle;

                if (this.globalSettings.r18Only) {
                    const restriction = meta.xRestrict !== undefined ? meta.xRestrict : 0;
                    if (restriction === 0) {
                        item.status = 'skipped';
                        item.lastMessage = '已跳过（非R18）';
                        item.endTime = new Date().toISOString();
                        this.updateStats();
                        this.saveToStorage();
                        this.ui.renderQueue(this.queue);
                        return;
                    }
                }

                const urls = await Api.getArtworkPages(item.id);
                if (!urls || !urls.length) throw new Error('无图片URL');
                item.totalImages = urls.length;
                this.saveToStorage();
                this.ui.renderQueue(this.queue);

                this.sse.open(item.id);
                const ssePromise = this._waitForFinalStatusBySSE(item.id, CONFIG.STATUS_TIMEOUT_MS);

                // 判断是否是 R18 内容
                const isR18 = meta.xRestrict !== undefined && meta.xRestrict > 0;

                this.ui.setStatus(`下载中：${item.title}`, 'info');

                // 使用全局 username（可能为 null — 后端可处理；若需要强制填充请在 start() 前检查）
                await Api.sendDownloadRequest(item.id, urls, item.title, username, isR18);

                const final = await ssePromise;

                // 校验下载数量
                if (final && final.completed) {
                    const dCount = final.downloadedCount !== undefined ? final.downloadedCount : item.totalImages;
                    item.downloadedCount = dCount;
                    if (dCount < item.totalImages) {
                        item.status = 'failed';
                        item.lastMessage = `失败: 仅下载 ${dCount}/${item.totalImages}`;
                        this.ui.setStatus(`失败：${item.title} (文件缺失)`, 'error');
                    } else {
                        item.status = 'completed';
                        item.lastMessage = '完成';
                        this.ui.setStatus(`完成：${item.title}`, 'success');
                    }
                } else if (final && final.failed) {
                    item.status = 'failed';
                    item.lastMessage = final.message || '失败';
                    this.ui.setStatus(`失败：${item.title}`, 'error');
                } else {
                    const check = await Api.getDownloadStatus(item.id);
                    if (check && check.completed) {
                        const dCount = check.downloadedCount !== undefined ? check.downloadedCount : 0;
                        item.downloadedCount = dCount;
                        if (dCount < item.totalImages) {
                            item.status = 'failed';
                            item.lastMessage = `后端已结束但文件缺失 (${dCount}/${item.totalImages})`;
                        } else {
                            item.status = 'completed';
                            item.lastMessage = '完成(Check)';
                        }
                    } else {
                        item.status = 'failed';
                        item.lastMessage = '状态查询超时';
                    }
                }

            } catch (e) {
                item.status = 'failed';
                item.lastMessage = e.message;
                this.ui.setStatus(`错误：${item.title}`, 'error');
            } finally {
                this.sse.close(item.id);
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
                const timer = setTimeout(() => {
                    if (!resolved) {
                        resolved = true;
                        resolve(null);
                    }
                }, timeoutMs);
                const handler = (data) => {
                    if (data && (data.completed || data.failed || data.cancelled)) {
                        if (!resolved) {
                            resolved = true;
                            clearTimeout(timer);
                            resolve(data);
                        }
                    } else if (data && data.downloadedCount !== undefined) {
                        const q = this.queue.find(x => x.id === String(artworkId));
                        if (q) {
                            q.downloadedCount = data.downloadedCount;
                            this.ui.renderQueue(this.queue);
                            this.ui.setCurrent(q);
                        }
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

        _sleep(ms) {
            return new Promise(r => setTimeout(r, ms));
        }
    }

    /* ========== UI ========== */
    class UI {
        constructor() {
            this.root = null;
            this.elements = {};
        }

        ensureMounted() {
            if (document.getElementById('pixiv-user-batch-ui')) {
                if (this.root && this.root.style.display === 'none') {
                    this.root.style.display = 'block';
                }
                return;
            }
            this._build();
            this.syncSettings();
        }

        hide() {
            if (this.root) this.root.style.display = 'none';
        }

        syncSettings() {
            if (!this.manager || !this.elements.interval) return;
            const s = this.manager.globalSettings;
            this.elements.skipHistory.checked = s.skipHistory;
            this.elements.r18Only.checked = s.r18Only;
            this.elements.interval.value = s.interval;
            this.elements.concurrent.value = s.concurrent;
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

            // 标题部分 - 使用动态生成
            const title = $el('div', {
                id: 'batch-ui-title',
                innerText: '🖼️ Pixiv User 批量下载器',
                style: {
                    fontWeight: 'bold',
                    marginBottom: '15px',
                    color: '#333',
                    textAlign: 'center',
                    fontSize: '16px',
                    borderBottom: '2px solid #eee',
                    paddingBottom: '10px'
                }
            });

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
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">下载间隔(秒):</label>
                    <input type="number" id="download-interval" min="1" max="60" value="${CONFIG.DEFAULT_INTERVAL}"
                           style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 8px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">最大并发数:</label>
                    <input type="number" id="max-concurrent" min="1" max="${CONFIG.MAX_CONCURRENT}" value="${CONFIG.DEFAULT_CONCURRENT}"
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
                {id: 'export-btn', text: '📤 导出列表', bgColor: '#007bff', onClick: () => this.handleExport()},
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

            container.appendChild(title);
            container.appendChild(status);
            container.appendChild(stats);
            container.appendChild(settings);
            container.appendChild(buttonContainer);
            container.appendChild(currentDownload);
            container.appendChild(queueContainer);

            document.body.appendChild(container);
            this.root = container;

            this.elements = {
                title, status, stats, currentDownload, queueContainer,
                interval: container.querySelector('#download-interval'),
                concurrent: container.querySelector('#max-concurrent'),
                skipHistory: container.querySelector('#skip-history'),
                r18Only: container.querySelector('#r18-only'),
                startBtn: container.querySelector('#start-btn'),
                pauseBtn: container.querySelector('#pause-btn')
            };

            const bindChange = (el, fn) => {
                el.addEventListener('change', fn);
                if (el.type === 'number') el.addEventListener('input', fn);
            };
            bindChange(this.elements.skipHistory, (e) => this.manager && this.manager.setSkipHistory(e.target.checked));
            bindChange(this.elements.r18Only, (e) => this.manager && this.manager.setR18Only(e.target.checked));
            bindChange(this.elements.interval, (e) => this.manager && this.manager.setInterval(e.target.value));
            bindChange(this.elements.concurrent, (e) => this.manager && this.manager.setConcurrent(e.target.value));
        }

        bindManager(manager) {
            this.manager = manager;
            if (this.root) this.syncSettings();
        }

        // 新增方法：更新用户信息显示
        setUserInfo(uid, userName = null) {
            this.ensureMounted();
            if (this.elements.title) {
                let displayText;
                if (userName) {
                    // 显示格式：User: 用户名(用户ID)
                    displayText = `User: ${userName}(${uid}) (批量下载)`;
                } else {
                    // 如果还没有获取到用户名，只显示用户ID
                    displayText = `User: ${uid} (批量下载)`;
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
                item.innerHTML = `<div><strong>${escapeHtml(q.title || 'ID: ' + q.id)}</strong></div><div>ID: ${q.id} | 状态: ${this._statusText(q.status)}</div>${progressHtml}`;
                node.appendChild(item);
            }
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
})();