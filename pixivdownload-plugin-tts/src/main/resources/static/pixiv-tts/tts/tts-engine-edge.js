/* eslint-disable */
/**
 * 听书「Edge 在线」引擎子模块：微软 Edge 在线神经语音，经后端 /api/tts/edge/* 代理。
 * 音质自然、语言丰富、免费；需联网（走后端配置的代理），有轻微合成延迟。
 *
 * 合成音频先查内存 blob 缓存与 IndexedDB 持久化缓存（ctx.store / PixivTtsStore），未命中再请求服务端。
 * 缓存键含语音与语速（换语音/语速生成不同条目，互不污染），持久化键再带上小说 ID 跨小说隔离。
 * 通过 ctx 读写共享 state / els，并按引擎分发回调主控制器状态机（ctx.speak）。install(ctx) 注入。
 */
(function (global) {
    'use strict';

    function install(ctx) {
        const state = ctx.state;
        const els = ctx.els;

        ctx.speakEdge = function (i, myToken) {
            ctx.updateProgress(true);
            state.edgeFetching = { index: i, token: myToken };
            ctx.fetchEdgeAudio(i)
                .then((url) => {
                    if (myToken !== state.token) return;
                    state.edgeFetching = null;
                    ctx.updateProgress(false);
                    if (state.paused) {
                        state.edgePending = { url: url, index: i, token: myToken };
                        return;
                    }
                    ctx.playEdgeUrl(url, i, myToken);
                    ctx.prefetchEdge(i + 1);
                })
                .catch((err) => {
                    if (myToken !== state.token) return;
                    state.edgeFetching = null;
                    state.toast && state.toast(String(err && err.message ? err.message : err), 'error');
                    ctx.stop();
                });
        };

        ctx.playEdgeUrl = function (url, i, myToken) {
            state.edgePending = null;
            if (!state.edgeAudio) state.edgeAudio = new Audio();
            const audio = state.edgeAudio;
            state.edgeAudioIndex = i;
            audio.src = url;
            audio.ontimeupdate = () => {
                if (myToken !== state.token) return;
                if (audio.duration > 0) ctx.updateBar(audio.currentTime / audio.duration);
            };
            audio.onended = () => {
                if (myToken !== state.token) return;
                ctx.speak(i + 1);
            };
            audio.onerror = () => {
                if (myToken !== state.token) return;
                ctx.speak(i + 1);
            };
            audio.play().catch(() => {});
        };

        // 缓存键含语音与语速：换语音/语速会生成不同条目，互不污染
        ctx.cacheKey = function (i) {
            return i + '|' + els.voice.value + '|' + ctx.ratePercent();
        };
        // IndexedDB 持久化键再带上小说 ID，跨小说隔离
        ctx.storeKey = function (i) {
            return state.novelId + '|' + ctx.cacheKey(i);
        };

        ctx.fetchEdgeAudio = async function (i) {
            const key = ctx.cacheKey(i);
            const mem = state.edgeCache.get(key);
            if (mem) return mem;
            const seg = state.segments[i];
            if (!seg) throw new Error('no segment');

            // 先查本地持久化缓存（IndexedDB）
            const persisted = await ctx.store.getAudio(ctx.storeKey(i));
            if (persisted) {
                const url = URL.createObjectURL(persisted);
                state.edgeCache.set(key, url);
                // 命中即视为最近使用，避免被淘汰
                ctx.store.touch(state.novelId);
                return url;
            }

            // 未命中再请求服务端合成
            const r = await fetch('/api/tts/edge/synthesize', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: seg.text, voice: els.voice.value, rate: ctx.ratePercent(), pitch: 0 })
            });
            if (!r.ok) {
                let msg = 'HTTP ' + r.status;
                try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                throw new Error(msg);
            }
            const blob = await r.blob();
            const url = URL.createObjectURL(blob);
            state.edgeCache.set(key, url);
            ctx.store.putAudio(ctx.storeKey(i), state.novelId, blob); // 持久化（fire-and-forget）
            return url;
        };

        ctx.prefetchEdge = function (i) {
            if (i < 0 || i >= state.segments.length) return;
            if (state.edgeCache.has(ctx.cacheKey(i))) return;
            ctx.fetchEdgeAudio(i).catch(() => {});
        };

        // 仅在语音/语速/引擎变化或离开页面时清空（释放 blob）；暂停/停止/跳段都保留缓存以复用
        ctx.clearEdgeCache = function () {
            state.edgeCache.forEach((url) => { try { URL.revokeObjectURL(url); } catch {} });
            state.edgeCache.clear();
        };
    }

    global.PixivTtsEngineEdge = { install: install };
})(window);
