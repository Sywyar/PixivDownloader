// ==UserScript==
// @name         Pixiv N-Tab 批量下载器 (修复版)
// @namespace    http://tampermonkey.net/
// @version      1.1.3
// @description  解析 N-Tab 导出，批量提交作品给本地后端下载，支持严格的下载状态校验（修复下载失败显示完成的Bug）。
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
        STATUS_TIMEOUT_MS: 300000,
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

        function getCurrentUserId() {
            const match = location.href.match(/users\/(\d+)/);
            return match ? match[1] : null;
        }
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
                const payload = { artworkId: parseInt(artworkId), imageUrls, title, referer: 'https://www.pixiv.net/',other:{
                        userDownload: false
                    } };
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
                    onload: (res) => { try { resolve(JSON.parse(res.responseText)); } catch (e) { resolve(null); } },
                    onerror: () => { resolve(null); }
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
                                resolve(!!data.artworkId);
                            } else {
                                resolve(false);
                            }
                        } catch (e) { resolve(false); }
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
            try {
                this.close(artworkId);
                const src = new EventSource(`${CONFIG.SSE_BASE}/${artworkId}`);
                src.addEventListener('download-status', (e) => {
                    try {
                        const data = JSON.parse(e.data);
                        (this.listeners.get(String(artworkId)) || []).forEach(fn => fn(data));
                    } catch (err) { }
                });
                this.sources.set(String(artworkId), src);
            } catch (err) { console.error('SSE open failed', err); }
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
            this.stats = { completed: 0, success: 0, failed: 0, active: 0, skipped: 0 };
            this.skipHistory = GM_getValue(CONFIG.SKIP_HISTORY_KEY, false);
        }

        loadFromStorage() {
            try {
                const raw = GM_getValue(CONFIG.STORAGE_KEY, null);
                if (!raw) return;
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed.queue)) {
                    this.queue = parsed.queue;
                    this.isRunning = false; // 启动时默认暂停，避免自动跑
                    this.isPaused = !!parsed.isPaused;
                    this.stats = parsed.stats || { completed: 0, success: 0, failed: 0, active: 0, skipped: 0 };
                    this.skipHistory = parsed.skipHistory !== undefined ? parsed.skipHistory : this.skipHistory;
                }
            } catch (e) { console.warn('loadFromStorage fail', e); }
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
            } catch (e) { }
        }

        deleteStorage() {
            try {
                GM_deleteValue(CONFIG.STORAGE_KEY);
                GM_deleteValue(CONFIG.SKIP_HISTORY_KEY);
            } catch (e) { }
        }

        setSkipHistory(skip) {
            this.skipHistory = skip;
            GM_setValue(CONFIG.SKIP_HISTORY_KEY, skip);
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
                alert('后端服务不可用。');
                return;
            }
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
            this.isRunning = false;
            this.saveToStorage();
            this.ui.setStatus('批量下载结束', 'info');
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
        }

        async workerLoop(intervalMs) {
            this.activeWorkers++;
            try {
                while (this.isRunning && !this.stopRequested) {
                    if (this.isPaused) { await this._sleep(300); continue; }
                    const next = this._getNextPending();
                    if (!next) {
                        if (this.queue.every(q => ['completed','failed','idle','paused','skipped'].includes(q.status))) break;
                        await this._sleep(500); continue;
                    }
                    try {
                        await this._processSingle(next);
                    } catch (e) { console.error('_processSingle error', e); }
                    finally { await this._sleep(intervalMs); }
                }
            } finally { this.activeWorkers--; }
        }

        _getNextPending() {
            const idx = this.queue.findIndex(q => q.status === 'pending');
            if (idx === -1) return null;
            this.queue[idx].status = 'downloading';
            this.queue[idx].startTime = new Date().toISOString();
            this.saveToStorage();
            this.ui.renderQueue(this.queue);
            return { idx, item: this.queue[idx] };
        }

        /* ========== 核心修复点：processSingle ========== */
        async _processSingle({ idx, item }) {
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
                    return;
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

                this.sse.open(item.id);
                const ssePromise = this._waitForFinalStatusBySSE(item.id, CONFIG.STATUS_TIMEOUT_MS);
                await Api.sendDownloadRequest(item.id, urls, item.title);
                const final = await ssePromise;

                if (final && final.completed) {
                    const dCount = final.downloadedCount !== undefined ? final.downloadedCount : item.totalImages;
                    item.downloadedCount = dCount;

                    if (dCount < item.totalImages) {
                        // 下载数小于总数，判定为失败
                        item.status = 'failed';
                        item.lastMessage = `下载不完整: ${dCount}/${item.totalImages}`;
                        this.ui.setStatus(`失败：${item.title} (文件缺失)`, 'error');
                    } else {
                        // 成功
                        item.status = 'completed';
                        item.lastMessage = '完成';
                        this.ui.setStatus(`完成：${item.title}`, 'success');
                    }
                } else if (final && final.failed) {
                    item.status = 'failed';
                    item.lastMessage = final.message || '失败';
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
                                item.lastMessage = `后端已结束但文件缺失 (${dCount}/${item.totalImages})`;
                            } else {
                                item.status = 'completed';
                                item.lastMessage = '完成(Check)';
                            }
                        } else {
                            item.status = 'failed';
                            item.lastMessage = '未知（超时或后端未完成）';
                        }
                    } catch (e) {
                        item.status = 'failed';
                        item.lastMessage = '状态查询错误';
                    }
                }
                // --- 修复结束 ---

            } catch (err) {
                item.status = 'failed';
                item.lastMessage = err.message || String(err);
                this.ui.setStatus(`错误：${item.title} - ${item.lastMessage}`, 'error');
            } finally {
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
                const timer = setTimeout(() => {
                    if (resolved) return;
                    resolved = true;
                    resolve(null);
                }, timeoutMs);
                const handler = (data) => {
                    if (data && (data.completed || data.failed || data.cancelled)) {
                        if (resolved) return;
                        resolved = true;
                        clearTimeout(timer);
                        resolve(data);
                    } else {
                        const q = this.queue.find(x => x.id === String(artworkId));
                        if (q && data.downloadedCount !== undefined) {
                            q.downloadedCount = data.downloadedCount;
                            this.saveToStorage();
                            this.ui.renderQueue(this.queue);
                        }
                    }
                };
                this.sse.addListener(String(artworkId), handler);
            });
        }

        pause() {
            if (!this.isRunning) return;
            this.isPaused = true;
            this.queue.forEach(q => { if (q.status === 'pending') q.status = 'paused'; });
            this.saveToStorage();
            this.ui.updateButtonsState(this.isRunning, this.isPaused);
            this.ui.setStatus('已暂停', 'warning');
        }

        resume() {
            if (!this.isRunning) {
                const interval = Math.max(1, parseInt(this.ui.elements.interval.value) || CONFIG.DEFAULT_INTERVAL);
                const concurrent = Math.max(1, Math.min(CONFIG.MAX_CONCURRENT, parseInt(this.ui.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT));
                return this.start(interval, concurrent);
            }
            this.isPaused = false;
            this.queue.forEach(q => { if (q.status === 'paused') q.status = 'pending'; });
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
               if(q.status==='downloading'||q.status==='pending') Api.cancelDownload(q.id).catch(()=>{});
            });
            this.queue = [];
            this.stats = { completed: 0, success: 0, failed: 0, active: 0, skipped: 0 };
            try { this.deleteStorage(); } catch (e) {}
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

            // 标题
            const title = $el('div', {
                html: '🎨 N-Tab批量下载器 v1.1.3 (Fixed)',
                style: { fontWeight: 'bold', marginBottom: '15px', color: '#333', textAlign: 'center', fontSize: '16px', borderBottom: '2px solid #eee', paddingBottom: '10px' }
            });

            const status = $el('div', { id: 'batch-status', innerText: '准备就绪', style: { marginBottom: '10px', color: '#666', fontSize: '12px', textAlign: 'center' } });
            const stats = $el('div', { id: 'batch-stats', innerText: '队列: 0 | 成功: 0 | 失败: 0 | 进行中: 0 | 跳过: 0', style: { marginBottom: '10px', color: '#007bff', fontSize: '12px', textAlign: 'center', fontWeight: 'bold' } });

            const inputSection = $el('div', { style: { marginBottom: '15px' } });
            const textarea = $el('textarea', { id: 'ntab-data-input', placeholder: `粘贴 N-Tab 导出的内容...`, style: { width: '100%', height: '120px', marginBottom: '10px', padding: '8px', border: '1px solid #ddd', borderRadius: '4px', fontSize: '12px', resize: 'vertical' } });
            inputSection.appendChild(textarea);

            const settings = $el('div', { style: { marginBottom: '15px' } });
            settings.innerHTML = `
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">下载间隔(秒):</label>
                    <input type="number" id="download-interval" value="${CONFIG.DEFAULT_INTERVAL}" min="1" max="60" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">最大并发数:</label>
                    <input type="number" id="max-concurrent" value="${CONFIG.DEFAULT_CONCURRENT}" min="1" max="${CONFIG.MAX_CONCURRENT}" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
                </div>
                <div style="display: flex; align-items: center; margin-bottom: 10px;">
                    <label style="font-size: 12px; margin-right: 10px; width: 120px;">跳过历史下载:</label>
                    <input type="checkbox" id="skip-history" style="width: 16px; height: 16px;">
                </div>
            `;

            const buttonContainer = $el('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '10px' } });
            const buttons = [
                { id: 'parse-btn', text: '📋 解析N-Tab数据', bgColor: '#007bff', onClick: () => this.manager.parseAndSetFromText(this.elements.textarea.value) },
                { id: 'start-btn', text: '🚀 开始批量下载', bgColor: '#28a745', onClick: () => this.handleStart() },
                { id: 'retry-failed-btn', text: '🔁 重新下载失败的作品', bgColor: '#17a2b8', onClick: () => this.handleRetryFailed() },
                { id: 'pause-btn', text: '⏸️ 暂停下载', bgColor: '#ffc107', onClick: () => this.handlePause(), disabled: true },
                { id: 'clear-btn', text: '🗑️ 清除队列', bgColor: '#6c757d', onClick: () => this.handleClear() }
            ];
            buttons.forEach(btnConfig => {
                const button = $el('button', { id: btnConfig.id, innerText: btnConfig.text, style: { width: '100%', background: btnConfig.bgColor, color: btnConfig.bgColor === '#ffc107' ? 'black' : 'white', border: 'none', padding: '10px', borderRadius: '5px', cursor: 'pointer', fontSize: '14px' } });
                button.disabled = !!btnConfig.disabled;
                button.addEventListener('click', btnConfig.onClick);
                buttonContainer.appendChild(button);
            });

            const currentDownload = $el('div', { id: 'current-download', style: { marginBottom: '10px', padding: '8px', background: '#f8f9fa', borderRadius: '5px', borderLeft: '4px solid #007bff', fontSize: '11px' } });
            currentDownload.innerHTML = '<strong>当前下载:</strong> 无';

            const queueContainer = $el('div', { id: 'queue-container', style: { maxHeight: '250px', overflowY: 'auto', border: '1px solid #ddd', borderRadius: '5px', padding: '10px', marginBottom: '10px', background: '#f8f9fa', fontSize: '11px' } });

            container.appendChild(title);
            container.appendChild(status);
            container.appendChild(stats);
            container.appendChild(inputSection);
            container.appendChild(settings);
            container.appendChild(buttonContainer);
            container.appendChild(currentDownload);
            container.appendChild(queueContainer);
            document.body.appendChild(container);
            this.root = container;

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
                queueContainer: queueContainer,
                currentDownload: currentDownload
            };
        }

        bindManager(manager) {
            this.manager = manager;
            this.elements.skipHistory.checked = manager.skipHistory;
            this.elements.skipHistory.addEventListener('change', (e) => {
                this.manager.setSkipHistory(e.target.checked);
            });
        }

        async handleStart() {
            const interval = Math.max(1, parseInt(this.elements.interval.value) || CONFIG.DEFAULT_INTERVAL);
            const concurrent = Math.max(1, Math.min(CONFIG.MAX_CONCURRENT, parseInt(this.elements.concurrent.value) || CONFIG.DEFAULT_CONCURRENT));
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
            if (failedItems.length === 0) { alert('当前没有失败的作品！'); return; }
            this.manager.queue = failedItems.map(f => ({ ...f, status: 'idle', startTime: null, endTime: null, downloadedCount: 0, totalImages: 0, lastMessage: '' }));
            this.manager.saveToStorage();
            this.renderQueue(this.manager.queue);
            alert(`已保留 ${failedItems.length} 个失败作品，开始重新下载。`);
            this.handleStart();
        }

        async handleClear() {
            if (confirm('确认强制清除队列？')) await this.manager.stopAndClear(true);
        }

        renderQueue(queue) {
            const node = this.elements.queueContainer;
            node.innerHTML = '<div style="font-weight: bold; margin-bottom: 5px;">下载队列:</div>';
            if (!queue || queue.length === 0) { node.innerHTML += '<div style="color: #666; text-align: center;">队列为空</div>'; return; }
            for (const q of queue) {
                const item = $el('div', { style: { padding: '5px', marginBottom: '3px', background: 'white', fontSize: '10px', borderLeft: `3px solid ${this._colorByStatus(q.status)}` } });
                const progressHtml = this._createProgressHtml(q);
                item.innerHTML = `<div><strong>${escapeHtml(q.title)}</strong></div><div>ID: ${q.id} | 状态: ${this._statusText(q.status)}</div>${progressHtml}`;
                node.appendChild(item);
            }
        }

        _createProgressHtml(q) {
            if (q.totalImages <= 0) return '';
            const downloadedCount = q.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / q.totalImages) * 100), 100);
            return `<div style="margin-top: 3px;"><div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;"><span>已下载: ${downloadedCount}/${q.totalImages}</span><span>${progressPercent}%</span></div><div style="width: 100%; height: 4px; background: #e0e0e0; border-radius: 2px; overflow: hidden;"><div style="height: 100%; background: #007bff; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }

        setStatus(msg, type = 'info') {
            this.elements.status.innerText = msg;
            this.elements.status.style.color = { 'info': '#007bff', 'success': '#28a745', 'error': '#dc3545', 'warning': '#ffc107' }[type] || '#666';
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
            if (!item) { container.innerHTML = '<strong>当前下载:</strong> 无'; return; }
            const progressHtml = this._createCurrentProgressHtml(item);
            container.innerHTML = `<strong>当前下载:</strong> ${item.title} (ID: ${item.id})${progressHtml}`;
        }

        _createCurrentProgressHtml(item) {
            if (item.totalImages <= 0) return '';
            const downloadedCount = item.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / item.totalImages) * 100), 100);
            return `<div style="margin-top: 5px;"><div style="display: flex; justify-content: space-between; font-size: 10px; margin-bottom: 3px;"><span>已下载 ${downloadedCount} 张 / 共 ${item.totalImages} 张</span><span>${progressPercent}%</span></div><div style="width: 100%; height: 6px; background: #e0e0e0; border-radius: 3px; overflow: hidden;"><div style="height: 100%; background: #28a745; width: ${progressPercent}%; transition: width 0.3s ease;"></div></div></div>`;
        }

        _colorByStatus(status) { return { 'completed': '#28a745', 'downloading': '#007bff', 'failed': '#dc3545', 'paused': '#6c757d', 'skipped': '#ffa500' }[status] || '#6c757d'; }
        _statusText(status) { return { 'idle': '等待中', 'pending': '等待中', 'downloading': '下载中', 'completed': '已完成', 'failed': '失败', 'paused': '暂停中', 'skipped': '已跳过' }[status] || status; }
    }

    function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]); }

    const ui = new UI();
    const manager = new DownloadManager(ui);
    ui.bindManager(manager);
    manager.loadFromStorage();
    ui.renderQueue(manager.queue);
    ui.setStatus('就绪');

    GM_registerMenuCommand('打开 Pixiv N-Tab 批量下载器', () => {
        const root = document.getElementById('pixiv-batch-downloader-ui');
        if (root) { root.style.display = 'block'; window.scrollTo(0, 0); } else { location.reload(); }
    });
})();