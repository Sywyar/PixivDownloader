/* eslint-disable */
/** 朗读脚本加载、逐句播放、进度与音频缓存。 */
(function (global) {
    'use strict';

    const modules = global.PixivNovelNarrationModules || (global.PixivNovelNarrationModules = {});
    modules.playback = {
        install(ctx) {
            const { PREFETCH_AHEAD, PREFETCH_CONCURRENCY, NarrationStore, state, els, t } = ctx.core;
            const { buildBlocks, speakerLabel, renderMarks } = ctx.marks;
            const openCast = (...args) => ctx.cast.openCast(...args);
            const openAnalysisDialog = (...args) => ctx.dialog.openAnalysisDialog(...args);
    // ---------- 脚本加载 / 分析 ----------
    // 低层：调 /api/narration/script。analyzeIfMissing=false 时仅取缓存（无缓存返回 null，对应 204），绝不分析。
    async function requestScript(body) {
        const r = await fetch('/api/narration/script', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(Object.assign(
                { novelId: Number(state.novelId), lang: state.lang || '' }, body))
        });
        if (r.status === 204) return null;
        if (!r.ok) {
            let msg = 'HTTP ' + r.status;
            try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
            throw new Error(msg);
        }
        return r.json();
    }

    // 探测：仅取缓存脚本（绝不触发分析）；命中则 applyScript 并返回 true。
    async function peekScript() {
        const data = await requestScript({ analyzeIfMissing: false });
        if (data) applyScript(data);
        return !!data;
    }

    // 重新拉取已缓存脚本（音色编辑后刷新 castUpdatedTime 使音频缓存失效，不重算 LLM）。
    async function reloadCachedScript() {
        try {
            const data = await requestScript({ analyzeIfMissing: false });
            if (data) applyScript(data);
        } catch {}
    }

    // 按所选设置分析（segmentSize + castId + 旁白音色预设），完成后 applyScript；force 时清音频缓存。autoPlay 时分析完自动播放。
    async function runAnalysis(segmentSize, castId, force, autoPlay, narratorPreset) {
        if (state.loading) return;
        if (!state.blocks.length) state.blocks = buildBlocks();
        stop();
        state.loading = true;
        updateProgress(true);
        let data = null;
        try {
            data = await requestScript({
                segmentSize: segmentSize, castId: castId, force: !!force,
                narratorPreset: narratorPreset || undefined
            });
        } catch (e) {
            state.toast && state.toast(t('narration:toast.generate-failed', '生成失败：{message}',
                { message: String(e && e.message ? e.message : e) }), 'error');
        }
        state.loading = false;
        updateProgress(false);
        if (!data) return;
        applyScript(data);
        if (force) await NarrationStore.deleteAudioForNovel(state.novelId);
        state.toast && state.toast(t('narration:toast.generated', '多角色朗读脚本已生成'), 'success');
        if (data.conflicts && data.conflicts.length) openCast();
        if (autoPlay && state.lines.length) start();
    }

    function applyScript(data) {
        clearCache();
        state.lines = Array.isArray(data.lines) ? data.lines : [];
        state.cast = Array.isArray(data.cast) ? data.cast : [];
        state.conflicts = Array.isArray(data.conflicts) ? data.conflicts : [];
        state.scriptCastId = data.castId || 0;
        state.castUpdatedTime = data.castUpdatedTime || 0;
        state.analyzedTime = data.analyzedTime || 0;
        state.segmentSize = data.segmentSize || 0;
        state.scriptLoaded = true;
        if (state.index >= state.lines.length) state.index = -1;
        updateProgress(false);
        updateBar(0);
        renderMarks();
        if (state.index >= 0) highlight(state.index, false);
    }

    // ---------- 高亮 / 进度 / 字幕 ----------
    function clearHighlight() {
        state.blocks.forEach((el) => el.classList.remove('narration-active'));
    }

    function highlight(i, scroll) {
        clearHighlight();
        const line = state.lines[i];
        if (line && line.paragraphIndex >= 0 && line.paragraphIndex < state.blocks.length) {
            const el = state.blocks[line.paragraphIndex];
            el.classList.add('narration-active');
            if (scroll !== false) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
        updateSubtitle(line);
        updateProgress(false);
        updateBar(0);
    }

    function updateSubtitle(line) {
        if (!els.subtitle) return;
        if (!line) { els.subtitle.textContent = t('narration:subtitle.empty', '—'); return; }
        els.subtitle.innerHTML = '';
        const sp = document.createElement('span');
        sp.className = 'narration-speaker';
        sp.textContent = speakerLabel(line) + '：';
        const tx = document.createElement('span');
        tx.textContent = line.text || '';
        els.subtitle.appendChild(sp);
        els.subtitle.appendChild(tx);
    }

    function updateProgress(loading) {
        const n = state.lines.length;
        const cur = state.index >= 0 ? state.index + 1 : 0;
        if (loading) {
            els.progress.textContent = state.loading
                ? t('narration:status.analyzing', '分析中…')
                : t('narration:status.synthesizing', '合成中… {cur}/{total}', { cur, total: n });
        } else {
            els.progress.textContent = `${cur} / ${n}`;
        }
    }

    function updateBar(extra) {
        if (!els.progressFill) return;
        const n = state.lines.length;
        let frac = 0;
        if (n > 0 && state.index >= 0) {
            const within = Math.max(0, Math.min(1, extra || 0));
            frac = Math.min(1, (state.index + within) / n);
        }
        els.progressFill.style.width = (frac * 100).toFixed(2) + '%';
    }

    function setPlayIcon(playing) {
        els.playPause.textContent = playing ? '⏸' : '▶';
        els.playPause.title = playing ? t('narration:bar.pause', '暂停') : t('narration:bar.play', '播放');
    }

    // ---------- 播放控制 ----------
    function start() {
        if (state.loading) return;
        if (!state.scriptLoaded) { peekThenStart(); return; }
        if (!state.lines.length) { state.toast && state.toast(t('narration:toast.no-content', '没有可朗读的正文'), 'error'); return; }
        if (state.paused) { resume(); return; }
        if (state.playing) return;
        const startIndex = state.index >= 0 && state.index < state.lines.length ? state.index : 0;
        state.playing = true;
        state.paused = false;
        setPlayIcon(true);
        speak(startIndex);
    }

    // 首次点播放：先探测是否已有缓存脚本——命中直接播放/续播（重播不重算），未命中弹「朗读分析设置」弹窗
    // （确认后才分析、分析完自动播放）。绝不在点播放时静默自动分析。
    async function peekThenStart() {
        if (state.loading) return;
        if (!state.blocks.length) state.blocks = buildBlocks();
        state.loading = true;
        updateProgress(true);
        let found = false;
        try { found = await peekScript(); } catch {} finally { state.loading = false; updateProgress(false); }
        if (found && state.lines.length) start();
        else openAnalysisDialog(false, true);
    }

    function speak(i) {
        if (i < 0 || i >= state.lines.length) { finish(); return; }
        state.index = i;
        highlight(i);
        const myToken = state.token;
        updateProgress(true);
        state.fetching = { index: i, token: myToken };
        pumpPrefetch(); // 立即在后台铺满后续缓冲窗口，与当前句的合成 / 播放并行
        fetchLineAudio(i)
            .then((url) => {
                if (myToken !== state.token) return;
                state.fetching = null;
                updateProgress(false);
                if (state.paused) { state.pending = { url, index: i, token: myToken }; return; }
                playUrl(url, i, myToken);
                pumpPrefetch();
            })
            .catch((err) => {
                if (myToken !== state.token) return;
                state.fetching = null;
                state.toast && state.toast(t('narration:toast.synth-failed', '合成失败：{message}', { message: String(err && err.message ? err.message : err) }), 'error');
                stop();
            });
    }

    function playUrl(url, i, myToken) {
        state.pending = null;
        if (!state.audio) state.audio = new Audio();
        const audio = state.audio;
        state.audioIndex = i;
        audio.src = url;
        audio.ontimeupdate = () => {
            if (myToken !== state.token) return;
            if (audio.duration > 0) updateBar(audio.currentTime / audio.duration);
        };
        audio.onended = () => { if (myToken === state.token) speak(i + 1); };
        audio.onerror = () => { if (myToken === state.token) speak(i + 1); };
        audio.play().catch(() => {});
    }

    function next() { if (state.index + 1 >= state.lines.length) { stop(); return; } seekTo(state.index + 1); }
    function prev() { seekTo(Math.max(0, state.index - 1)); }

    function seekTo(i) {
        const wasPlaying = state.playing && !state.paused;
        cancelCurrent();
        state.index = i;
        highlight(i);
        if (wasPlaying || state.playing) {
            state.playing = true;
            state.paused = false;
            setPlayIcon(true);
            speak(i);
        }
    }

    function pause() {
        if (!state.playing || state.paused) return;
        state.paused = true;
        setPlayIcon(false);
        if (state.audio) state.audio.pause();
    }

    function resume() {
        if (!state.paused) return;
        state.paused = false;
        state.playing = true;
        setPlayIcon(true);
        const pending = state.pending;
        if (pending && pending.token === state.token) {
            state.pending = null;
            playUrl(pending.url, pending.index, pending.token);
            pumpPrefetch();
        } else if (state.audio && state.audioIndex === state.index) {
            state.audio.play().catch(() => {});
        } else if (state.fetching && state.fetching.token === state.token && state.fetching.index === state.index) {
            // 合成仍在路上，完成后继续
        } else if (state.index >= 0) {
            speak(state.index);
        }
    }

    function togglePlay() {
        if (state.playing && !state.paused) pause();
        else start();
    }

    function cancelCurrent() {
        state.token++;
        state.fetching = null;
        state.pending = null;
        if (state.audio) { try { state.audio.pause(); } catch {} }
    }

    function stop() {
        cancelCurrent();
        state.playing = false;
        state.paused = false;
        setPlayIcon(false);
        updateProgress(false);
    }

    function finish() {
        state.playing = false;
        state.paused = false;
        setPlayIcon(false);
        clearHighlight();
        updateSubtitle(null);
        state.index = -1;
        updateProgress(false);
        if (els.progressFill) els.progressFill.style.width = '100%';
    }

    // ---------- 音频获取 / 缓存 ----------
    function storeKey(i) {
        return state.novelId + '|' + (state.lang || '') + '|' + state.castUpdatedTime + '|'
            + state.analyzedTime + '|' + i;
    }

    // 取某句音频：命中内存缓存即返回；否则按「在途请求」去重，保证同一句最多只有一个合成 / 读盘请求，
    // 供「当前句直接播放」与「后台预取」共享，互不重复合成。
    function fetchLineAudio(i) {
        const mem = state.cache.get(i);
        if (mem) return Promise.resolve(mem);
        const inflight = state.inflight.get(i);
        if (inflight) return inflight;
        const p = loadLineAudio(i, state.cacheGen)
            .finally(() => { if (state.inflight.get(i) === p) state.inflight.delete(i); });
        state.inflight.set(i, p);
        return p;
    }

    async function loadLineAudio(i, gen) {
        const key = storeKey(i); // 入口定格：合成期间若改音色 / 重分析，仍按发起时的代际键读写 IndexedDB
        const persisted = await NarrationStore.getAudio(key);
        if (persisted) {
            const url = URL.createObjectURL(persisted);
            if (gen === state.cacheGen) { state.cache.set(i, url); NarrationStore.touch(state.novelId); }
            return url;
        }
        const r = await fetch('/api/narration/tts/line', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ novelId: Number(state.novelId), lineIndex: i, lang: state.lang || '' })
        });
        if (!r.ok) {
            let msg = 'HTTP ' + r.status;
            try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
            throw new Error(msg);
        }
        const blob = await r.blob();
        const url = URL.createObjectURL(blob);
        if (gen === state.cacheGen) state.cache.set(i, url); // 代际已变（换音色/重分析）则不污染新缓存
        NarrationStore.putAudio(key, state.novelId, blob);
        return url;
    }

    // 后台预取泵：维持「当前句起至少 PREFETCH_AHEAD 句」的音频被异步合成，并发不超过 PREFETCH_CONCURRENCY；
    // 播放只从缓存取音频、合成在后台进行，二者解耦（异步听与生成）。某句完成后回灌窗口，使缓冲持续保持纵深。
    function pumpPrefetch() {
        const myToken = state.token;
        const n = state.lines.length;
        if (!n) return;
        const base = state.index >= 0 ? state.index : 0;
        const end = Math.min(n - 1, base + PREFETCH_AHEAD);
        const repump = () => { if (myToken === state.token) pumpPrefetch(); };
        for (let i = base; i <= end; i++) {
            if (state.inflight.size >= PREFETCH_CONCURRENCY) break;
            if (state.cache.has(i) || state.inflight.has(i)) continue;
            fetchLineAudio(i).then(repump, repump);
        }
    }

    function clearCache() {
        state.cacheGen++;        // 在途请求据此失效：旧音色 / 旧脚本的结果不再写入新一代内存缓存
        state.inflight.clear();  // 放弃旧代际在途预取的归属（其结果仍按发起时的键落 IndexedDB，无害）
        state.cache.forEach((url) => { try { URL.revokeObjectURL(url); } catch {} });
        state.cache.clear();
    }


            ctx.playback = { runAnalysis, reloadCachedScript, clearHighlight, updateSubtitle, updateProgress, updateBar, setPlayIcon, next, prev, seekTo, togglePlay, cancelCurrent, stop, finish, clearCache };
        }
    };
})(window);
