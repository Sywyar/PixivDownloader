// ==UserScript==
// @name         Pixiv N-Tab 批量下载器
// @namespace    http://tampermonkey.net/
// @version      1.1.2
// @description  解析 N-Tab 导出，批量提交作品给本地后端下载，支持并发/间隔/暂停(让当前完成)/继续/强制清除/持久化保存/SSE 实时进度。
// @author       Rewritten by ChatGPT
// @match        https://www.pixiv.net/
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

    if (/^https:\/\/www\.pixiv\.net\/artworks\/\d+/.test(location.href)) {
        return;
    }

    /* ========== 配置 ========== */
    const CONFIG = {
        BACKEND_URL: "http://localhost:6999/api/download/pixiv",
        STATUS_URL: "http://localhost:6999/api/download/status",    // GET /{artworkId}
        CANCEL_URL: "http://localhost:6999/api/download/cancel",   // POST /{artworkId}
        SSE_BASE: "http://localhost:6999/api/sse/download",        // /{artworkId}
        DEFAULT_INTERVAL: 2,
        DEFAULT_CONCURRENT: 1,
        MAX_CONCURRENT: 5,
        STATUS_TIMEOUT_MS: 300000,  // 等待最终状态超时 (5min)
        BACKEND_CHECK_TIMEOUT: 3000,
        STORAGE_KEY: 'pixiv_ntab_batch_v1',
        SKIP_HISTORY_KEY: 'pixiv_ntab_skip_history'
    };

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

    /* ========== API 封装（使用 GM_xmlhttpRequest） ========== */
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
                    headers: { Referer: 'https://www.pixiv.net/' },
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (data.error) reject(new Error(data.message || 'pixiv ajax error'));
                            else resolve((data.body || []).map(p => p.urls.original));
                        } catch (e) { reject(e); }
                    },
                    onerror: reject
                });
            });
        },

        sendDownloadRequest(artworkId, imageUrls, title) {
            return new Promise((resolve, reject) => {
                const payload = { artworkId: parseInt(artworkId), imageUrls, title, referer: 'https://www.pixiv.net/' };
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: CONFIG.BACKEND_URL,
                    headers: { 'Content-Type': 'application/json' },
                    data: JSON.stringify(payload),
                    onload: (res) => {
                        try {
                            const data = JSON.parse(res.responseText);
                            if (res.status === 200 && (data.success === true || data.success === undefined)) resolve(data);
                            else reject(new Error(data.message || '后端返回失败'));
                        } catch (e) { reject(e); }
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
                        try { resolve(JSON.parse(res.responseText)); } catch (e) { reject(e); }
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
                        try { resolve(JSON.parse(res.responseText)); } catch (e) { resolve(null); }
                    },
                    onerror: (e) => { resolve(null); }
                });
            });
        },

        checkDownloaded(artworkId) {
            return new Promise((resolve) => {
                GM_xmlhttpRequest({
                    method: 'GET',
                    url: `http://localhost:6999/api/downloaded/${artworkId}`,
                    onload: (res) => {
                        try {
                            if (res.status === 200) {
                                const data = JSON.parse(res.responseText);
                                resolve(!!data.artworkId); // 如果返回了作品ID，说明已下载
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

    /* ========== SSE 管理器 ========== */
    class SSEManager {
        constructor() {
            this.sources = new Map(); // artworkId -> EventSource
            this.listeners = new Map(); // artworkId -> [fn,...]
        }

        open(artworkId) {
            try {
                this.close(artworkId);
                const src = new EventSource(`${CONFIG.SSE_BASE}/${artworkId}`);
                src.addEventListener('download-status', (e) => {
                    try {
                        const data = JSON.parse(e.data);
                        (this.listeners.get(String(artworkId)) || []).forEach(fn => {
                            try { fn(data); } catch (err) { console.error(err); }
                        });
                    } catch (err) { console.warn('SSE parse fail', err); }
                });
                src.onerror = (err) => {
                    // 不自动重连，避免对后端压力。用户可重试。
                    console.warn('SSE error for', artworkId, err);
                };
                this.sources.set(String(artworkId), src);
            } catch (err) {
                console.error('SSE open failed', err);
            }
        }

        close(artworkId) {
            const key = String(artworkId);
            const s = this.sources.get(key);
            if (s) { try { s.close(); } catch (e) {} this.sources.delete(key); }
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

    /* ========== 下载管理器：队列／并发／状态持久化／UI 更新 ========== */
    class DownloadManager {
        constructor(ui) {
            this.ui = ui;
            this.queue = []; // 每项 { id, title, url, status, totalImages, downloadedCount, startTime, endTime, lastMessage }
            this.isRunning = false;
            this.isPaused = false;
            this.sse = new SSEManager();
            this.stopRequested = false;
            this.activeWorkers = 0;
            this.stats = {
                completed: 0,
                success: 0,
                failed: 0,
                active: 0,
                skipped: 0
            };
            this.skipHistory = GM_getValue(CONFIG.SKIP_HISTORY_KEY, false);
        }

        loadFromStorage() {
            try {
                const raw = GM_getValue(CONFIG.STORAGE_KEY, null);
                if (!raw) return;
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed.queue)) {
                    this.queue = parsed.queue;
                    this.isRunning = !!parsed.isRunning;
                    this.isPaused = !!parsed.isPaused;
                    this.stats = parsed.stats || { completed: 0, success: 0, failed: 0, active: 0, skipped: 0 };
                    this.skipHistory = parsed.skipHistory !== undefined ? parsed.skipHistory : this.skipHistory;
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
                    savedAt: new Date().toISOString()
                };
                GM_setValue(CONFIG.STORAGE_KEY, JSON.stringify(snapshot));
            } catch (e) { console.warn('save fail', e); }
        }

        deleteStorage() {
            try {
                GM_deleteValue(CONFIG.STORAGE_KEY);
                GM_deleteValue(CONFIG.SKIP_HISTORY_KEY);
            } catch (e) { console.warn('delete storage fail', e); }
        }

        setSkipHistory(skip) {
            this.skipHistory = skip;
            GM_setValue(CONFIG.SKIP_HISTORY_KEY, skip);
            this.saveToStorage();
        }

        setQueue(items) {
            // items: [{id, title, url}]
            this.queue = items.map(it => ({
                id: String(it.id),
                title: it.title || `作品 ${it.id}`,
                url: it.url || `https://www.pixiv.net/artworks/${it.id}`,
                status: 'idle', // idle | pending | downloading | completed | failed | paused | skipped
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
                    const title = ln.replace(/https?:\/\/www\.pixiv\.net\/artworks\/\d+\s*\|\s*/, '').trim();
                    items.push({ id, title: title || `作品 ${id}`, url: `https://www.pixiv.net/artworks/${id}` });
                }
            }
            this.setQueue(items);
            this.ui.setStatus(`解析完成：找到 ${items.length} 个作品`, 'success');
        }

        async start(intervalSec = CONFIG.DEFAULT_INTERVAL, maxConcurrent = CONFIG.DEFAULT_CONCURRENT) {
            if (this.queue.length === 0) {
                this.ui.setStatus('队列为空', 'error'); return;
            }

            const backendOk = await Api.checkBackend();
            if (!backendOk) {
                alert('后端服务不可用。请确保后端在 localhost:6999 上运行。');
                return;
            }

            // 标记尚未处理的项为 pending（但不要触碰正在下载的）
            this.queue.forEach(q => {
                if (['idle','failed','paused'].includes(q.status)) q.status = 'pending';
            });
            this.isRunning = true;
            this.isPaused = false;
            this.stopRequested = false;
            this.updateStats();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            this.ui.setStatus('开始批量下载', 'info');

            const workerCount = Math.max(1, Math.min(maxConcurrent, CONFIG.MAX_CONCURRENT));
            const workers = [];
            for (let i = 0; i < workerCount; i++) {
                workers.push(this.workerLoop(intervalSec * 1000));
            }
            await Promise.all(workers);
            // 所有 worker 完成
            this.isRunning = false;
            this.saveToStorage();
            this.ui.setStatus('批量下载结束', 'info');
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
        }

        async workerLoop(intervalMs) {
            this.activeWorkers++;
            try {
                while (this.isRunning && !this.stopRequested) {
                    // don't start new if paused
                    if (this.isPaused) {
                        await this._sleep(300);
                        continue;
                    }
                    const next = this._getNextPending();
                    if (!next) {
                        // 所有项是否都完成或失败？
                        if (this.queue.every(q => ['completed','failed','idle','paused','skipped'].includes(q.status))) break;
                        await this._sleep(500);
                        continue;
                    }
                    try {
                        await this._processSingle(next);
                    } catch (e) {
                        console.error('_processSingle error', e);
                    } finally {
                        // 间隔后继续下一项
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
            // 标记为 downloading — 重要：点击暂停后我们不再 start 新任务，因为 isPaused 会阻止 worker loop
            this.queue[idx].status = 'downloading';
            this.queue[idx].startTime = new Date().toISOString();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return { idx, item: this.queue[idx] };
        }

        async _processSingle({ idx, item }) {
            // 新增：检查是否跳过历史下载
            if (this.skipHistory) {
                const isDownloaded = await Api.checkDownloaded(item.id);
                if (isDownloaded) {
                    item.status = 'skipped';
                    item.lastMessage = '已跳过（历史记录中存在）';
                    item.endTime = new Date().toISOString();
                    this.updateStats();
                    this.saveToStorage();
                    this.ui.renderQueue(this.queue);
                    this.ui.setStatus(`跳过：${item.title}（已下载过）`, 'warning');
                    return; // 直接返回，不进行下载
                }
            }

            this.ui.setCurrent(item);
            this.ui.setStatus(`开始下载：${item.title}`, 'info');
            try {
                const urls = await Api.getArtworkPages(item.id);
                if (!Array.isArray(urls) || urls.length === 0) throw new Error('未获取到图片 URL');
                item.totalImages = urls.length;
                item.downloadedCount = 0;
                this.saveToStorage();
                this.ui.renderQueue(this.queue);

                // SSE 监听
                this.sse.open(item.id);
                const ssePromise = this._waitForFinalStatusBySSE(item.id, CONFIG.STATUS_TIMEOUT_MS);

                // 发送后端下载请求（不阻塞 SSE）
                await Api.sendDownloadRequest(item.id, urls, item.title);

                // 等待 SSE 最终结果或 timeout
                const final = await ssePromise;
                if (final && final.completed) {
                    item.status = 'completed';
                    item.downloadedCount = final.downloadedCount || item.totalImages;
                    item.endTime = new Date().toISOString();
                    item.lastMessage = '完成';
                    this.ui.setStatus(`完成：${item.title}`, 'success');
                } else if (final && final.failed) {
                    item.status = 'failed';
                    item.endTime = new Date().toISOString();
                    item.lastMessage = final.message || '失败';
                    this.ui.setStatus(`失败：${item.title} - ${item.lastMessage}`, 'error');
                } else {
                    // 超时或 SSE 不可得 -> 退回后端查询一次
                    try {
                        const backendState = await Api.getDownloadStatus(item.id);
                        if (backendState && backendState.completed) {
                            item.status = 'completed';
                            item.downloadedCount = backendState.downloadedCount || item.totalImages;
                            item.endTime = new Date().toISOString();
                            item.lastMessage = '通过后端查询确认完成';
                        } else {
                            item.status = 'failed';
                            item.lastMessage = '未知（超时或后端未完成）';
                        }
                    } catch (e) {
                        item.status = 'failed';
                        item.lastMessage = '状态查询错误';
                    }
                }
            } catch (err) {
                item.status = 'failed';
                item.lastMessage = err.message || String(err);
                this.ui.setStatus(`错误：${item.title} - ${item.lastMessage}`, 'error');
            } finally {
                // 无论如何，确保关闭该作品的 SSE、保存状态并更新 UI（满足第1点）
                try { this.sse.close(item.id); } catch (e) {}
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
                const key = String(artworkId);
                const timer = setTimeout(() => {
                    if (resolved) return;
                    resolved = true;
                    resolve(null); // timeout
                }, timeoutMs);

                const handler = (data) => {
                    // data expected: { artworkId, completed, failed, cancelled, downloadedCount, totalImages, message }
                    if (data && (data.completed || data.failed || data.cancelled)) {
                        if (resolved) return;
                        resolved = true;
                        clearTimeout(timer);
                        resolve(data);
                    } else {
                        // 进度更新，更新队列显示
                        const q = this.queue.find(x => x.id === key);
                        if (q) {
                            if (data.downloadedCount !== undefined) q.downloadedCount = data.downloadedCount;
                            if (data.totalImages !== undefined) q.totalImages = data.totalImages;
                            this.saveToStorage();
                            this.ui.renderQueue(this.queue);
                        }
                    }
                };

                this.sse.addListener(key, handler);
            });
        }

        pause() {
            if (!this.isRunning) return;
            this.isPaused = true;
            // 只把 pending -> paused，正在 downloading 的保持其状态直到其完成或失败
            this.queue.forEach(q => { if (q.status === 'pending') q.status = 'paused'; });
            this.saveToStorage();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.ui.setStatus('已暂停（正在进行的作品会继续完成）', 'warning');
        }

        resume() {
            if (!this.isRunning) {
                // 如果之前没有运行，直接 start（使用 UI 中的参数）
                const interval = Math.max(1, parseInt(this.ui.elements.interval.value) || CONFIG.DEFAULT_INTERVAL);
                const concurrent = Math.max(1, Math.min(CONFIG.MAX_CONCURRENT, parseInt(this.ui.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT));
                return this.start(interval, concurrent);
            }
            this.isPaused = false;
            // paused -> pending
            this.queue.forEach(q => { if (q.status === 'paused') q.status = 'pending'; });
            this.saveToStorage();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.ui.setStatus('继续下载', 'info');
        }

        /**
         * stopAndClear(force)
         * force = true: 强制停止所有活动（关闭 SSE、尝试向后端 cancel）、删除持久化并清空队列
         * force = false: 仅当队列已停止时清空
         */
        async stopAndClear(force = false) {
            if (!force) {
                // 仅在没有正在进行时允许普通清空
                if (this.queue.some(q => q.status === 'downloading')) {
                    if (!confirm('当前有正在下载的作品，是否强制停止并清除？（取消将保留队列）')) return;
                }
            }

            // 立即阻止 worker 启动新任务
            this.stopRequested = true;
            this.isRunning = false;
            this.isPaused = false;
            this.ui.updateButtonsState(this.isRunning, this.isPaused);

            // 强制关闭所有 SSE 连接
            this.sse.closeAll();

            // 尝试向后端发出取消请求（对每个正在 downloading 的项）
            const downloadingItems = this.queue.filter(q => q.status === 'downloading' || q.status === 'pending' || q.status === 'paused');
            for (const q of downloadingItems) {
                try {
                    await Api.cancelDownload(q.id);
                } catch (e) {
                    // 忽略错误
                }
            }

            // 清空队列以及持久化
            this.queue = [];
            this.stats = { completed: 0, success: 0, failed: 0, active: 0, skipped: 0 };
            try { this.deleteStorage(); } catch (e) { console.warn('delete storage fail', e); }

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

        _sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
    }

    /* ========== UI 组件 ========== */
    class UI {
        constructor() {
            this.root = null;
            this.elements = {};
            this._build();
        }

        _build() {
            this._removeIfExists();

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

            // 标题
            const title = $el('div', {
                html: '🎨 N-Tab批量下载器 v1.1.1',
                style: {
                    fontWeight: 'bold', marginBottom: '15px', color: '#333',
                    textAlign: 'center', fontSize: '16px', borderBottom: '2px solid #eee',
                    paddingBottom: '10px'
                }
            });

            // 状态显示
            const status = $el('div', {
                id: 'batch-status',
                innerText: '准备就绪',
                style: {
                    marginBottom: '10px', color: '#666', fontSize: '12px', textAlign: 'center'
                }
            });

            // 统计信息
            const stats = $el('div', {
                id: 'batch-stats',
                innerText: '队列: 0 | 成功: 0 | 失败: 0 | 进行中: 0 | 跳过: 0',
                style: {
                    marginBottom: '10px', color: '#007bff', fontSize: '12px',
                    textAlign: 'center', fontWeight: 'bold'
                }
            });

            // 输入区域
            const inputSection = $el('div', { style: { marginBottom: '15px' } });
            const textarea = $el('textarea', {
                id: 'ntab-data-input',
                placeholder: `粘贴 N-Tab 导出的内容（每行包含https://www.pixiv.net/artworks/ID）`,
                style: {
                    width: '100%', height: '120px', marginBottom: '10px', padding: '8px',
                    border: '1px solid #ddd', borderRadius: '4px', fontSize: '12px', resize: 'vertical'
                }
            });
            inputSection.appendChild(textarea);

            // 设置区域
            const settings = $el('div', { style: { marginBottom: '15px' } });
            settings.innerHTML = `
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">下载间隔(秒):</label>
                    <input type="number" id="download-interval" value="${CONFIG.DEFAULT_INTERVAL}"
                           min="1" max="60" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">实时通知:</label>
                    <span style="font-size: 12px; color: #28a745;">SSE已启用</span>
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">最大并发数:</label>
                    <input type="number" id="max-concurrent" value="${CONFIG.DEFAULT_CONCURRENT}"
                           min="1" max="${CONFIG.MAX_CONCURRENT}" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">跳过历史下载:</label>
                    <input type="checkbox" id="skip-history" 
                           style="width: 16px; height: 16px;">
                </div>
            `;

            // 按钮区域
            const buttonContainer = $el('div', {
                style: {
                    display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '10px'
                }
            });

            const buttons = [
                { id: 'parse-btn', text: '📋 解析N-Tab数据', bgColor: '#007bff', onClick: () => this.manager.parseAndSetFromText(this.elements.textarea.value) },
                { id: 'start-btn', text: '🚀 开始批量下载', bgColor: '#28a745', onClick: () => this.handleStart() },
                { id: 'retry-failed-btn', text: '🔁 重新下载失败的作品', bgColor: '#17a2b8', onClick: () => this.handleRetryFailed() },
                { id: 'pause-btn', text: '⏸️ 暂停下载', bgColor: '#ffc107', onClick: () => this.handlePause(), disabled: true },
                { id: 'clear-btn', text: '🗑️ 清除队列', bgColor: '#6c757d', onClick: () => this.handleClear() }
            ];

            buttons.forEach(btnConfig => {
                const button = this._createButton(btnConfig);
                buttonContainer.appendChild(button);
            });

            // 当前下载显示
            const currentDownload = $el('div', {
                id: 'current-download',
                style: {
                    marginBottom: '10px', padding: '8px', background: '#f8f9fa',
                    borderRadius: '5px', borderLeft: '4px solid #007bff', fontSize: '11px'
                }
            });
            currentDownload.innerHTML = '<strong>当前下载:</strong> 无';

            // 队列显示
            const queueContainer = $el('div', {
                id: 'queue-container',
                style: {
                    maxHeight: '250px', overflowY: 'auto', border: '1px solid #ddd',
                    borderRadius: '5px', padding: '10px', marginBottom: '10px',
                    background: '#f8f9fa', fontSize: '11px'
                }
            });

            // 后端状态
            const backendStatus = $el('div', {
                id: 'batch-backend-status',
                innerText: '检查后端状态...',
                style: {
                    fontSize: '11px', color: '#888', textAlign: 'center', marginBottom: '5px'
                }
            });

            // 组装所有元素
            container.appendChild(title);
            container.appendChild(status);
            container.appendChild(stats);
            container.appendChild(inputSection);
            container.appendChild(settings);
            container.appendChild(buttonContainer);
            container.appendChild(currentDownload);
            container.appendChild(queueContainer);
            container.appendChild(backendStatus);

            document.body.appendChild(container);
            this.root = container;

            // 记录元素
            this.elements = {
                textarea: textarea,
                interval: container.querySelector('#download-interval'),
                concurrent: container.querySelector('#max-concurrent'),
                skipHistory: container.querySelector('#skip-history'),
                parseBtn: container.querySelector('#parse-btn'),
                startBtn: container.querySelector('#start-btn'),
                pauseBtn: container.querySelector('#pause-btn'),
                clearBtn: container.querySelector('#clear-btn'),
                status: status,
                stats: stats,
                backendStatus: backendStatus,
                queueContainer: queueContainer,
                currentDownload: currentDownload
            };
        }

        _createButton({ id, text, bgColor, onClick, disabled = false }) {
            const button = $el('button', {
                id: id,
                innerText: text,
                style: {
                    width: '100%', background: bgColor, color: bgColor === '#ffc107' ? 'black' : 'white',
                    border: 'none', padding: '10px', borderRadius: '5px', cursor: 'pointer', fontSize: '14px'
                }
            });
            button.disabled = disabled;
            button.addEventListener('click', onClick);
            return button;
        }

        _removeIfExists() {
            const e = document.getElementById('pixiv-batch-downloader-ui');
            if (e) e.remove();
        }

        bindManager(manager) {
            this.manager = manager;
            // 设置跳过历史复选框的初始状态
            this.elements.skipHistory.checked = manager.skipHistory;

            // 添加事件监听
            this.elements.skipHistory.addEventListener('change', (e) => {
                this.manager.setSkipHistory(e.target.checked);
            });
        }

        async handleStart() {
            const interval = Math.max(1, parseInt(this.elements.interval.value) || CONFIG.DEFAULT_INTERVAL);
            const concurrent = Math.max(1, Math.min(CONFIG.MAX_CONCURRENT, parseInt(this.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT));
            this.setStatus('正在检查后端...', 'info');
            const ok = await Api.checkBackend();
            this.updateBackendStatus(ok);
            if (!ok) {
                alert('后端不可用，请启动后重试');
                return;
            }
            this.manager.start(interval, concurrent);
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

            // 只保留失败作品
            this.manager.queue = failedItems.map(f => ({
                ...f,
                status: 'idle',
                startTime: null,
                endTime: null,
                downloadedCount: 0,
                totalImages: 0,
                lastMessage: ''
            }));

            this.manager.saveToStorage();
            this.renderQueue(this.manager.queue);

            alert(`已保留 ${failedItems.length} 个失败作品，开始重新下载。`);

            // 自动重新开始
            this.handleStart();
        }

        async handleClear() {
            if (confirm('确认强制清除队列？这会立即停止所有下载（并尝试取消后端任务）并删除本地持久化状态。')) {
                await this.manager.stopAndClear(true);
            }
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
                        padding: '5px', marginBottom: '3px', background: 'white', fontSize: '10px',
                        borderLeft: `3px solid ${this._colorByStatus(q.status)}`
                    }
                });

                const progressHtml = this._createProgressHtml(q);

                item.innerHTML = `
                    <div><strong>${escapeHtml(q.title)}</strong></div>
                    <div>ID: ${q.id} | 状态: ${this._statusText(q.status)}</div>
                    ${progressHtml}
                `;
                node.appendChild(item);
            }
        }

        _createProgressHtml(q) {
            if (q.totalImages <= 0) return '';
            const downloadedCount = q.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / q.totalImages) * 100), 100);

            return `
                <div style="margin-top: 3px;">
                    <div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;">
                        <span>已下载: ${downloadedCount}/${q.totalImages}</span>
                        <span>${progressPercent}%</span>
                    </div>
                    <div style="width: 100%; height: 4px; background: #e0e0e0; border-radius: 2px; overflow: hidden;">
                        <div style="height: 100%; background: #007bff; width: ${progressPercent}%; transition: width 0.3s ease;"></div>
                    </div>
                </div>
            `;
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

        updateStats(stats) {
            const pendingCount = this.manager.queue.filter(q =>
                q.status === 'pending' || q.status === 'idle' || q.status === 'paused'
            ).length;
            this.elements.stats.textContent =
                `队列: ${pendingCount} | 成功: ${stats.success} | 失败: ${stats.failed} | 进行中: ${stats.active} | 跳过: ${stats.skipped}`;
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
            container.innerHTML = `
                <strong>当前下载:</strong> ${item.title} (ID: ${item.id})
                ${progressHtml}
            `;
        }

        _createCurrentProgressHtml(item) {
            if (item.totalImages <= 0) return '';
            const downloadedCount = item.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / item.totalImages) * 100), 100);

            return `
                <div style="margin-top: 5px;">
                    <div style="display: flex; justify-content: space-between; font-size: 10px; margin-bottom: 3px;">
                        <span>已下载 ${downloadedCount} 张 / 共 ${item.totalImages} 张</span>
                        <span>${progressPercent}%</span>
                    </div>
                    <div style="width: 100%; height: 6px; background: #e0e0e0; border-radius: 3px; overflow: hidden;">
                        <div style="height: 100%; background: #28a745; width: ${progressPercent}%; transition: width 0.3s ease;"></div>
                    </div>
                </div>
            `;
        }

        updateBackendStatus(available) {
            this.elements.backendStatus.innerHTML = available ? '✅ 后端服务可用' : '❌ 后端服务未启动';
            this.elements.backendStatus.style.color = available ? '#28a745' : '#dc3545';
        }

        _colorByStatus(status) {
            const colorMap = {
                'completed': '#28a745',
                'downloading': '#007bff',
                'failed': '#dc3545',
                'paused': '#6c757d',
                'skipped': '#ffa500'
            };
            return colorMap[status] || '#6c757d';
        }

        _statusText(status) {
            const statusMap = {
                'idle': '等待中',
                'pending': '等待中',
                'downloading': '下载中',
                'completed': '已完成',
                'failed': '失败',
                'paused': '暂停中',
                'skipped': '已跳过'
            };
            return statusMap[status] || status;
        }
    }

    /* ========== utils ========== */
    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
    }

    /* ========== 主流程 ========== */
    const ui = new UI();
    const manager = new DownloadManager(ui);
    ui.bindManager(manager);
    manager.loadFromStorage(); // 如果有上次保存的队列则加载
    ui.renderQueue(manager.queue);
    ui.setStatus('就绪');

    // 菜单命令：在油猴菜单中打开 UI
    GM_registerMenuCommand('打开 Pixiv N-Tab 批量下载器', () => {
        const root = document.getElementById('pixiv-batch-downloader-ui');
        if (root) {
            root.style.display = 'block';
            window.scrollTo(0, 0);
        } else {
            location.reload();
        }
    });

})();