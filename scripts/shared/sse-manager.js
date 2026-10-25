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
        this.connectionId = null;
        this.closing = false;
        this._buffer = '';
        this._reconnectTimer = null;
        this._connection = null;
    }

    // 共享连接由批量任务生命周期统一开关。
    openShared() {
        if (this.closing) this._cleanup();
        if (!this.batchActive) {
            this.url = CONFIG.SSE_BASE;
            this.closeUrl = CONFIG.SSE_CLOSE_BASE;
            this.uuid = userUUID;
        }
        this.batchActive = true;
        this.closing = false;
        this._ensureConnection();
    }

    close(artworkId) {
        this.listeners.delete(String(artworkId));
    }

    closeAll() {
        this.batchActive = false;
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
        const connection = this._connection = Symbol();
        this.connecting = true;
        this.closing = false;
        this._buffer = '';
        const headers = {};
        if (this.uuid) headers['X-User-UUID'] = this.uuid;
        try {
            const handle = GM_xmlhttpRequest({
                method: 'GET',
                url: this.url, // 聚合端点：不带 artworkId
                headers,
                responseType: 'stream',
                onloadstart: (res) => {
                    if (this._connection !== connection) return;
                    this.connecting = false;
                    this.connected = true;
                    const stream = res.response;
                    if (!stream || typeof stream.getReader !== 'function') {
                        console.warn(_sseLogT('log.sse.no-readable-stream', 'SSE: ReadableStream 不可用，将依赖轮询兜底'));
                        return;
                    }
                    this._readStream(stream, connection);
                },
                onerror: (err) => {
                    if (this._connection !== connection) return;
                    console.error(_sseLogT('log.sse.connection-error', 'SSE connection error'), err);
                    this._cleanup();
                    this._scheduleReconnect();
                },
                ontimeout: () => {
                    if (this._connection !== connection) return;
                    this._cleanup();
                    this._scheduleReconnect();
                }
            });
            if (this._connection === connection) this.handle = handle;
            else handle.abort();
        } catch (err) {
            if (this._connection !== connection) return;
            console.error(_sseLogT('log.sse.open-failed', 'SSE open failed'), err);
            this._cleanup();
            this._scheduleReconnect();
        }
    }

    _readStream(stream, connection) {
        const reader = this.reader = stream.getReader();
        const decoder = new TextDecoder();
        const pump = () => {
            reader.read().then(({done, value}) => {
                if (this._connection !== connection) return;
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
                    if (this._connection !== connection) return;
                }
                pump();
            }).catch(() => {
                if (this._connection !== connection) return;
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

    _closeNow() {
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
        const connection = this._connection;
        setTimeout(() => {
            if (this._connection === connection && this.closing) this._cleanup();
        }, 1000);
    }

    _notifyAggregatedClose(connectionId) {
        const headers = {};
        if (this.uuid) headers['X-User-UUID'] = this.uuid;
        try {
            headers.Origin = new URL(this.closeUrl).origin;
            GM_xmlhttpRequest({
                method: 'POST',
                url: `${this.closeUrl}/${encodeURIComponent(connectionId)}`,
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
        // 取消与中止也会触发回调，先撤销连接身份和资源引用。
        this._connection = null;
        const reader = this.reader, handle = this.handle;
        this.reader = null;
        this.handle = null;
        this.connected = false;
        this.connecting = false;
        this.connectionId = null;
        if (reader) {
            try {
                reader.cancel().catch(() => {});
            } catch (e) {
            }
        }
        if (handle) {
            try {
                handle.abort();
            } catch (e) {
            }
        }
        this._buffer = '';
        this.closing = false;
    }
}
