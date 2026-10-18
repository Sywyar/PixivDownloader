class UserscriptArchiveMonitor {
    constructor() {
        this.current = null;
        this.timer = null;
        this.request = null;
        this.hidden = false;
        window.addEventListener('pagehide', () => {
            this.hidden = true;
            this.stop();
        });
        window.addEventListener('pageshow', () => {
            this.hidden = false;
            this.resume();
        });
    }

    stop() {
        clearTimeout(this.timer);
        this.timer = null;
        const request = this.request;
        this.request = null;
        try { request?.handle?.abort(); } catch { /* 旧回调已失效。 */ }
    }

    watch(token, expireSeconds, ready, update, countdown) {
        this.stop();
        if (!this.current || this.current.token !== token || this.current.base !== serverBase) {
            this.current = {token, base: serverBase, status: 'pending', seconds: expireSeconds};
        }
        if (ready && this.current.status === 'pending') this.markReady(this.current, expireSeconds);
        Object.assign(this.current, {update, countdown});
        this.resume();
    }

    markReady(state, expireSeconds) {
        const seconds = Number.parseInt(expireSeconds, 10);
        state.status = 'ready';
        state.deadline = Date.now() + (Number.isFinite(seconds) ? Math.max(0, seconds) : 0) * 1000;
    }

    snapshot() {
        const state = this.current;
        return state && state.base === serverBase ? {
            token: state.token, expireSec: state.seconds, ready: state.status === 'ready'
        } : null;
    }

    resume() {
        const state = this.current;
        if (!state || this.hidden || state.base !== serverBase) return;
        this.stop();
        if (state.status === 'ready' && Date.now() >= state.deadline) state.status = 'expired';
        state.update(state.status, state.token, state.base);
        if (state.status === 'ready') this.countdown(state);
        else if (state.status === 'pending') this.timer = setTimeout(() => this.poll(state), 2000);
    }

    countdown(state) {
        const remaining = Math.max(0, Math.ceil((state.deadline - Date.now()) / 1000));
        state.countdown(remaining);
        this.timer = setTimeout(() => {
            if (this.current !== state || this.hidden || state.base !== serverBase) return;
            if (Date.now() >= state.deadline) {
                state.status = 'expired';
                state.update('expired', state.token, state.base);
                this.timer = null;
            } else this.countdown(state);
        }, 1000);
    }

    poll(state) {
        if (this.current !== state || this.hidden || state.base !== serverBase) return;
        this.timer = null;
        const request = {};
        this.request = request;
        const finish = data => {
            if (this.request !== request) return;
            this.request = null;
            if (this.current !== state || this.hidden || state.base !== serverBase) return;
            if (['ready', 'expired', 'empty'].includes(data?.status)) {
                if (data.status === 'ready') this.markReady(state, data.expireSeconds ?? state.seconds);
                else state.status = data.status;
                this.resume();
            } else this.timer = setTimeout(() => this.poll(state), 2000);
        };
        try {
            request.handle = GM_xmlhttpRequest({
                method: 'GET', url: state.base + '/api/archive/status/' + encodeURIComponent(state.token),
                timeout: 10000,
                onload: response => {
                    let data;
                    try { if (response.status === 200) data = JSON.parse(response.responseText); } catch {}
                    finish(data);
                },
                onerror: () => finish(), ontimeout: () => finish(), onabort: () => finish()
            });
        } catch { finish(); }
    }
}
