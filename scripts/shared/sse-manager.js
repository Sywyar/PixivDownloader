/* ========== SSE 管理器（共享单连接版：所有作品复用同一条聚合 SSE，按 artworkId 路由） ========== */
function _sseLogT(key, fallback, vars) {
    if (typeof t !== 'undefined') return t(key, fallback, vars);
    return fallback || key;
}

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
                        console.warn(_sseLogT('log.sse.no-readable-stream', 'SSE: ReadableStream 不可用，将依赖轮询兜底'));
                        return;
                    }
                    this._readStream(stream);
                },
                onerror: (err) => {
                    console.error(_sseLogT('log.sse.connection-error', 'SSE connection error'), err);
                    this._cleanup();
                    this._scheduleReconnect();
                },
                ontimeout: () => {
                    this._cleanup();
                    this._scheduleReconnect();
                }
            });
        } catch (err) {
            console.error(_sseLogT('log.sse.open-failed', 'SSE open failed'), err);
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
