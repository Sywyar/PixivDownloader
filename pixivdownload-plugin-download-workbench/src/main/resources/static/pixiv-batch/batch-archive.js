'use strict';

class PixivArchiveMonitor {
    constructor(render, countdown) {
        this.render = render;
        this.countdown = countdown;
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
        request?.abort();
    }

    watch(token, seconds, ready, title) {
        this.stop();
        if (!this.current || this.current.token !== token) {
            this.current = {token, seconds, status: 'pending'};
        }
        const state = this.current;
        state.title = title;
        if (ready && state.status === 'pending') this.markReady(state, seconds);
        if (state.status === 'ready' && this.remainingSeconds() === 0) state.status = 'expired';
        this.render(state);
        this.resume();
    }

    markReady(state, seconds) {
        const duration = Number.parseInt(seconds, 10);
        state.deadline = Date.now() + (Number.isFinite(duration) ? Math.max(0, duration) : 0) * 1000;
        state.status = 'ready';
    }

    remainingSeconds() {
        return Math.max(0, Math.ceil((this.current.deadline - Date.now()) / 1000));
    }

    resume() {
        const state = this.current;
        if (!state || this.hidden) return;
        this.stop();
        if (state.status === 'ready') {
            const remaining = this.remainingSeconds();
            if (remaining === 0) {
                state.status = 'expired';
                this.render(state);
            } else {
                this.countdown(remaining);
                this.timer = setTimeout(() => this.resume(), 1000);
            }
        } else if (state.status === 'pending') {
            this.timer = setTimeout(() => this.poll(state), 2000);
        }
    }

    async poll(state) {
        if (this.current !== state || this.hidden) return;
        const request = this.request = new AbortController();
        this.timer = setTimeout(() => {
            if (this.request !== request) return;
            this.stop();
            this.resume();
        }, 10000);
        try {
            const response = await fetch(BASE + '/api/archive/status/' + encodeURIComponent(state.token), {
                credentials: 'same-origin', signal: request.signal
            });
            if (!response.ok) return;
            const data = await response.json();
            if (this.request !== request) return;
            if (data?.status === 'ready') this.markReady(state, data.expireSeconds ?? state.seconds);
            else if (data?.status === 'empty' || data?.status === 'expired') state.status = data.status;
            else return;
            if (state.status === 'ready' && this.remainingSeconds() === 0) state.status = 'expired';
            this.render(state);
        } catch { /* 普通查询失败留待下一轮。 */ }
        finally {
            if (this.request === request) {
                this.request = null;
                this.resume();
            }
        }
    }
}
