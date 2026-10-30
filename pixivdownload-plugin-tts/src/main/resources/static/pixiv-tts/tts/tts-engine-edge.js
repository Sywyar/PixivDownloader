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
        let cacheGeneration = 0;

        ctx.speakEdge = function (i, myToken) {
            const generation = cacheGeneration;
            ctx.updateProgress(true);
            state.edgeFetching = { index: i, token: myToken };
            ctx.fetchEdgeAudio(i)
                .then((url) => {
                    if (myToken !== state.token || generation !== cacheGeneration || !url) return;
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
                    if (myToken !== state.token || generation !== cacheGeneration) return;
                    state.edgeFetching = null;
                    state.toast && state.toast(String(err && err.message ? err.message : err), 'error');
                    ctx.stop();
                });
        };

        ctx.playEdgeUrl = function (url, i, myToken) {
            const generation = cacheGeneration;
            state.edgePending = null;
            if (!state.edgeAudio) state.edgeAudio = new Audio();
            const audio = state.edgeAudio;
            state.edgeAudioIndex = i;
            audio.src = url;
            audio.ontimeupdate = () => {
                if (myToken !== state.token || generation !== cacheGeneration) return;
                if (audio.duration > 0) ctx.updateBar(audio.currentTime / audio.duration);
            };
            audio.onended = audio.onerror = () => {
                if (myToken !== state.token || generation !== cacheGeneration) return;
                ctx.speak(i + 1);
            };
            audio.play().catch(() => {});
        };

        ctx.fetchEdgeAudio = async function (i) {
            const generation = cacheGeneration;
            const novelId = state.novelId;
            const voice = els.voice.value;
            const rate = ctx.ratePercent();
            const key = i + '|' + voice + '|' + rate;
            const storeKey = novelId + '|' + key;
            const mem = state.edgeCache.get(key);
            if (mem) return mem;
            const seg = state.segments[i];
            if (!seg) throw new Error('no segment');
            const text = seg.text;

            // 先查本地持久化缓存（IndexedDB）
            const persisted = await ctx.store.getAudio(storeKey);
            if (generation !== cacheGeneration) return null;
            if (persisted) {
                const url = state.edgeCache.get(key) || URL.createObjectURL(persisted);
                state.edgeCache.set(key, url);
                // 命中即视为最近使用，避免被淘汰
                ctx.store.touch(novelId);
                return url;
            }

            // 未命中再请求服务端合成
            const r = await (ctx.fetch || global.fetch.bind(global))('/api/tts/edge/synthesize', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text, voice, rate, pitch: 0 })
            });
            if (generation !== cacheGeneration) {
                if (r.body) await r.body.cancel().catch(() => {});
                return null;
            }
            if (!r.ok) {
                let msg = 'HTTP ' + r.status;
                try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                throw new Error(msg);
            }
            const blob = await r.blob();
            if (generation !== cacheGeneration) return null;
            const url = state.edgeCache.get(key) || URL.createObjectURL(blob);
            state.edgeCache.set(key, url);
            ctx.store.putAudio(storeKey, novelId, blob); // 持久化（fire-and-forget）
            return url;
        };

        ctx.prefetchEdge = function (i) {
            if (i < 0 || i >= state.segments.length) return;
            ctx.fetchEdgeAudio(i).catch(() => {});
        };

        // 仅在语音/语速/引擎变化或离开页面时清空（释放 blob）；暂停/停止/跳段都保留缓存以复用
        ctx.clearEdgeCache = function () {
            cacheGeneration++;
            state.edgeFetching = null;
            state.edgePending = null;
            state.edgeAudioIndex = -1;
            if (state.edgeAudio) {
                state.edgeAudio.pause();
                state.edgeAudio.removeAttribute('src');
                state.edgeAudio.load();
            }
            state.edgeCache.forEach((url) => { try { URL.revokeObjectURL(url); } catch {} });
            state.edgeCache.clear();
        };
    }

    global.PixivTtsEngineEdge = { install: install };
})(window);
