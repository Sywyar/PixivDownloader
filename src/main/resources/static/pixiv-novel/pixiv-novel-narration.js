/* eslint-disable */
/**
 * 小说「AI 多角色朗读」控制器（admin-only）。区别于单声道 pixiv-novel-tts.js：
 *
 *   - 朗读以「句」为粒度：后端 LLM 把整章逐句归属到说话人（旁白 / 角色），每个说话人有固定音色画像；
 *   - 每句按其说话人的音色用 VoxCPM 等富情感引擎逐句合成、顺序播放；
 *   - 高亮以「段落（渲染块）」为粒度：每句带 paragraphIndex（与正文 DOM 块顺序对齐），逐句高亮+滚动其所在段落，
 *     条上字幕显示「说话人：当前句」；
 *   - 分析结果持久化，重播不重算；可查看 / 编辑花名册音色（锁定），处理未解决冲突；编辑音色立即对后续合成生效。
 *
 * 模型只存与语言无关的原始数据（speaker / delivery / paragraphIndex / 原始码），渲染时再用 i18n 派生显示文案，
 * 切换界面语言可重新派生。
 */
(function (global) {
    'use strict';

    const LS = { segment: 'pixiv_narration_segment' };
    const NOVEL_LIMIT = 50; // 音频缓存保留的小说数上限
    const PREFETCH_AHEAD = 20;       // 维持的预生成缓冲句数：播放时后台至少向前合成这么多句，消除逐句合成的停顿
    const PREFETCH_CONCURRENCY = 3;  // 后台并发合成上限：避免把整个缓冲窗口一次性压向合成后端

    function lsGet(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch { return d; } }
    function lsSet(k, v) { try { localStorage.setItem(k, v); } catch {} }

    /** IndexedDB 持久化逐句合成音频，按小说 LRU 淘汰；键含 castUpdatedTime，音色编辑 / 重分析后自动失效。 */
    const NarrationStore = (function () {
        const DB_NAME = 'pixiv-narration';
        const AUDIO = 'audio';
        const META = 'meta';
        let dbPromise = null;
        const available = typeof indexedDB !== 'undefined';

        function open() {
            if (dbPromise) return dbPromise;
            dbPromise = new Promise((resolve, reject) => {
                const req = indexedDB.open(DB_NAME, 1);
                req.onupgradeneeded = () => {
                    const db = req.result;
                    if (!db.objectStoreNames.contains(AUDIO)) {
                        db.createObjectStore(AUDIO, { keyPath: 'key' })
                            .createIndex('novelId', 'novelId', { unique: false });
                    }
                    if (!db.objectStoreNames.contains(META)) {
                        db.createObjectStore(META, { keyPath: 'novelId' });
                    }
                };
                req.onsuccess = () => resolve(req.result);
                req.onerror = () => reject(req.error);
            });
            return dbPromise;
        }

        async function getAudio(key) {
            if (!available) return null;
            try {
                const db = await open();
                return await new Promise((resolve) => {
                    const r = db.transaction(AUDIO, 'readonly').objectStore(AUDIO).get(key);
                    r.onsuccess = () => resolve(r.result ? r.result.blob : null);
                    r.onerror = () => resolve(null);
                });
            } catch { return null; }
        }

        async function putAudio(key, novelId, blob) {
            if (!available || !novelId) return;
            try {
                const db = await open();
                await new Promise((resolve) => {
                    const tx = db.transaction([AUDIO, META], 'readwrite');
                    tx.objectStore(AUDIO).put({ key, novelId, blob });
                    tx.objectStore(META).put({ novelId, lastUsed: Date.now() });
                    tx.oncomplete = resolve;
                    tx.onerror = resolve;
                    tx.onabort = resolve;
                });
                await evict();
            } catch {}
        }

        async function touch(novelId) {
            if (!available || !novelId) return;
            try {
                const db = await open();
                await new Promise((resolve) => {
                    const tx = db.transaction(META, 'readwrite');
                    tx.objectStore(META).put({ novelId, lastUsed: Date.now() });
                    tx.oncomplete = resolve;
                    tx.onerror = resolve;
                    tx.onabort = resolve;
                });
            } catch {}
        }

        async function evict() {
            try {
                const db = await open();
                const metas = await new Promise((resolve) => {
                    const r = db.transaction(META, 'readonly').objectStore(META).getAll();
                    r.onsuccess = () => resolve(r.result || []);
                    r.onerror = () => resolve([]);
                });
                if (metas.length <= NOVEL_LIMIT) return;
                metas.sort((a, b) => (a.lastUsed || 0) - (b.lastUsed || 0));
                const victims = metas.slice(0, metas.length - NOVEL_LIMIT);
                for (const m of victims) await deleteNovel(db, m.novelId);
            } catch {}
        }

        function deleteNovel(db, novelId) {
            return new Promise((resolve) => {
                const tx = db.transaction([AUDIO, META], 'readwrite');
                const store = tx.objectStore(AUDIO);
                const idxReq = store.index('novelId').getAllKeys(novelId);
                idxReq.onsuccess = () => (idxReq.result || []).forEach((k) => store.delete(k));
                tx.objectStore(META).delete(novelId);
                tx.oncomplete = resolve;
                tx.onerror = resolve;
                tx.onabort = resolve;
            });
        }

        async function deleteAudioForNovel(novelId) {
            if (!available || !novelId) return;
            try {
                const db = await open();
                await deleteNovel(db, novelId);
            } catch {}
        }

        return { getAudio, putAudio, touch, deleteAudioForNovel };
    })();

    const state = {
        i18n: null,
        toast: null,
        contentEl: null,
        novelId: '',
        lang: '',
        blocks: [],            // [Element] 可朗读渲染块（与 paragraphIndex 对齐）
        lines: [],             // [{index, speakerId, speakerName, delivery, paragraphIndex, text}]
        cast: [],              // [{id, name, gender, age}]
        conflicts: [],         // [{characterId, name, type, reason, currentInstruction, suggestion}]
        scriptCastId: 0,       // 当前脚本所用花名册 id（0=纯旁白/无花名册）；冲突解决 / 选角编辑以它为对象
        editCastId: 0,         // 选角弹窗当前编辑的花名册 id（脚本册或弹窗内所选册）
        castUpdatedTime: 0,
        analyzedTime: 0,
        segmentSize: 0,
        scriptLoaded: false,
        loading: false,
        active: false,         // 是否为当前选中的听书引擎（仅 active 时才写共享控制条 DOM）
        index: -1,
        playing: false,
        paused: false,
        token: 0,
        audio: null,
        audioIndex: -1,
        pending: null,         // 合成完成但仍暂停时暂存 {url,index,token}
        fetching: null,        // {index, token}
        cache: new Map(),      // lineIndex -> blob URL（内存，当前 castUpdatedTime 下复用）
        inflight: new Map(),   // lineIndex -> Promise<url>：合成 / 读盘在途请求，去重「当前句直接取」与「后台预取」
        cacheGen: 0            // 缓存代际：clearCache 自增；在途请求据此判断结果是否仍可写入当前内存缓存
    };

    const els = {};

    function t(key, fallback, params) {
        if (state.i18n) return state.i18n.t(key, fallback, params);
        return fallback != null ? fallback : key;
    }

    // ---------- 渲染块 ----------
    function buildBlocks() {
        if (!state.contentEl) return [];
        return Array.from(state.contentEl.querySelectorAll('h2.novel-chapter, p'));
    }

    function speakerLabel(line) {
        if (!line) return '';
        if (line.speakerId === 0) return t('narration:narrator', '旁白');
        return line.speakerName || t('narration:narrator', '旁白');
    }

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

    // 按所选设置分析（segmentSize + castId），完成后 applyScript；force 时清音频缓存。autoPlay 时分析完自动播放。
    async function runAnalysis(segmentSize, castId, force, autoPlay) {
        if (state.loading) return;
        if (!state.blocks.length) state.blocks = buildBlocks();
        stop();
        state.loading = true;
        updateProgress(true);
        let data = null;
        try {
            data = await requestScript({ segmentSize: segmentSize, castId: castId, force: !!force });
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

    // ---------- 选角 / 冲突面板 ----------
    // 冲突触发时编辑「当前脚本所用花名册」；从设置弹窗触发时编辑「所选花名册」（借用别人的花名册即编辑那份共享册）。
    async function openCast() { return openCastFor(state.scriptCastId); }

    async function openCastFor(castId) {
        state.editCastId = castId > 0 ? castId : 0;
        renderConflicts();
        await renderCastList();
        els.castModal.classList.add('open');
    }
    function closeCast() { els.castModal.classList.remove('open'); }

    function conflictTypeLabel(type) {
        if (type === 'contradiction') return t('narration:conflict.type.contradiction', '与正文矛盾');
        if (type === 'incomplete') return t('narration:conflict.type.incomplete', '画像不完整');
        return type || '';
    }

    function renderConflicts() {
        const box = els.conflicts;
        if (!box) return;
        if (!state.conflicts.length) { box.style.display = 'none'; box.innerHTML = ''; return; }
        box.style.display = 'flex';
        box.innerHTML = '';
        state.conflicts.forEach((c, idx) => {
            const card = document.createElement('div');
            card.className = 'narration-conflict';
            const head = document.createElement('div');
            head.className = 'narration-conflict-head';
            head.innerHTML = `<span>${escapeHtml(c.name || '')}</span>`
                + `<span class="narration-conflict-type">${escapeHtml(conflictTypeLabel(c.type))}</span>`;
            const body = document.createElement('div');
            body.className = 'narration-conflict-body';
            body.innerHTML = `<div>${escapeHtml(t('narration:conflict.current', '当前画像'))}：<span class="narration-instr">${escapeHtml(c.currentInstruction || '')}</span></div>`
                + `<div>${escapeHtml(t('narration:conflict.suggestion', '建议画像'))}：<span class="narration-instr">${escapeHtml(c.suggestion || '')}</span></div>`;
            if (c.reason) body.innerHTML += `<div>${escapeHtml(c.reason)}</div>`;
            const actions = document.createElement('div');
            actions.className = 'narration-conflict-actions';
            const adopt = document.createElement('button');
            adopt.className = 'btn btn-sm';
            adopt.textContent = t('narration:conflict.adopt', '采纳建议');
            adopt.addEventListener('click', () => resolveConflict(idx, c.suggestion));
            const keep = document.createElement('button');
            keep.className = 'btn btn-sm';
            keep.textContent = t('narration:conflict.keep', '保留当前');
            keep.addEventListener('click', () => dismissConflict(idx));
            const rewrite = document.createElement('button');
            rewrite.className = 'btn btn-sm';
            rewrite.textContent = t('narration:conflict.rewrite', '改写');
            rewrite.addEventListener('click', () => rewriteConflict(idx, c));
            actions.appendChild(adopt);
            actions.appendChild(keep);
            actions.appendChild(rewrite);
            card.appendChild(head);
            card.appendChild(body);
            card.appendChild(actions);
            box.appendChild(card);
        });
    }

    async function resolveConflict(idx, instruction) {
        const c = state.conflicts[idx];
        if (!c) return;
        const ok = await saveVoice(c.characterId, instruction);
        if (ok) { state.conflicts.splice(idx, 1); renderConflicts(); renderCastList(); state.toast && state.toast(t('narration:toast.conflict-resolved', '已处理冲突'), 'success'); }
    }
    function dismissConflict(idx) {
        state.conflicts.splice(idx, 1);
        renderConflicts();
    }
    function rewriteConflict(idx, c) {
        const instr = window.prompt(t('narration:cast.instruction-placeholder', '英文音色画像'), c.suggestion || c.currentInstruction || '');
        if (instr == null) return;
        const trimmed = String(instr).trim();
        if (!trimmed) return;
        resolveConflict(idx, trimmed);
    }

    async function renderCastList() {
        const list = els.castList;
        if (!list) return;
        let voices = [];
        if (state.editCastId > 0) {
            try {
                const r = await fetch('/api/narration/casts/' + encodeURIComponent(state.editCastId) + '/voices',
                    { credentials: 'same-origin' });
                if (r.ok) { const data = await r.json(); voices = Array.isArray(data.voices) ? data.voices : []; }
            } catch {}
        }
        if (!voices.length) {
            list.innerHTML = `<div class="narration-cast-empty">${escapeHtml(t('narration:cast.empty', '尚无角色，请先生成多角色朗读脚本。'))}</div>`;
            return;
        }
        list.innerHTML = '';
        voices.forEach((v) => list.appendChild(renderVoiceRow(v)));
    }

    function metaLabel(kind, value) {
        const key = 'narration:' + kind + '.' + (value || 'unknown');
        return t(key, value || '');
    }

    function renderVoiceRow(v) {
        const row = document.createElement('div');
        row.className = 'narration-voice';
        const head = document.createElement('div');
        head.className = 'narration-voice-head';
        const isNarrator = v.id === 0;
        const name = document.createElement('span');
        name.className = 'narration-voice-name';
        name.textContent = isNarrator ? t('narration:narrator', '旁白') : (v.name || '');
        const meta = document.createElement('span');
        meta.className = 'narration-voice-meta';
        meta.textContent = `${metaLabel('gender', v.gender)} · ${metaLabel('age', v.age)}`;
        const flag = document.createElement('span');
        flag.className = 'narration-voice-flag' + (v.editedByUser ? ' locked' : '');
        flag.textContent = v.editedByUser ? t('narration:cast.locked', '已锁定') : t('narration:cast.ai', 'AI 生成');
        const actions = document.createElement('span');
        actions.className = 'narration-voice-actions';
        const editBtn = document.createElement('button');
        editBtn.className = 'btn btn-sm';
        editBtn.textContent = t('narration:cast.edit', '编辑音色');
        const previewBtn = document.createElement('button');
        previewBtn.className = 'btn btn-sm';
        previewBtn.textContent = t('narration:cast.preview', '试听');
        actions.appendChild(previewBtn);
        actions.appendChild(editBtn);
        head.appendChild(name);
        head.appendChild(meta);
        head.appendChild(flag);
        head.appendChild(actions);

        const instr = document.createElement('div');
        instr.className = 'narration-voice-instr';
        instr.textContent = v.controlInstruction || '';

        const edit = document.createElement('div');
        edit.className = 'narration-voice-edit';
        const ta = document.createElement('textarea');
        ta.value = v.controlInstruction || '';
        ta.placeholder = t('narration:cast.instruction-placeholder', '');
        const editActions = document.createElement('div');
        editActions.className = 'narration-voice-edit-actions';
        const save = document.createElement('button');
        save.className = 'btn btn-sm';
        save.textContent = t('narration:cast.save', '保存');
        const cancel = document.createElement('button');
        cancel.className = 'btn btn-sm';
        cancel.textContent = t('narration:cast.cancel', '取消');
        editActions.appendChild(save);
        editActions.appendChild(cancel);
        edit.appendChild(ta);
        edit.appendChild(editActions);

        editBtn.addEventListener('click', () => { edit.classList.toggle('open'); });
        cancel.addEventListener('click', () => { ta.value = v.controlInstruction || ''; edit.classList.remove('open'); });
        save.addEventListener('click', async () => {
            const text = ta.value.trim();
            if (!text) return;
            save.disabled = true;
            const ok = await saveVoice(v.id, text);
            save.disabled = false;
            if (ok) { v.controlInstruction = text; v.editedByUser = true; edit.classList.remove('open'); renderCastList(); }
        });
        previewBtn.addEventListener('click', () => previewVoice(previewBtn, ta.value.trim() || v.controlInstruction || '', v));

        row.appendChild(head);
        row.appendChild(instr);
        row.appendChild(edit);
        return row;
    }

    // 单角色试听：用该角色当前音色画像合成一小段示例文本（/preview）
    async function previewVoice(btn, instruction, v) {
        const sample = sampleLineFor(v.id) || t('narration:narrator', '旁白');
        btn.disabled = true;
        try {
            const r = await fetch('/api/narration/tts/preview', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: sample, controlInstruction: instruction })
            });
            if (!r.ok) {
                let msg = 'HTTP ' + r.status;
                try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                throw new Error(msg);
            }
            const blob = await r.blob();
            const url = URL.createObjectURL(blob);
            const audio = new Audio(url);
            audio.onended = () => { try { URL.revokeObjectURL(url); } catch {} };
            audio.play().catch(() => {});
        } catch (e) {
            state.toast && state.toast(t('narration:toast.synth-failed', '合成失败：{message}', { message: String(e && e.message ? e.message : e) }), 'error');
        } finally {
            btn.disabled = false;
        }
    }

    // 取该角色在脚本中的第一句作为试听样例；找不到时回退
    function sampleLineFor(speakerId) {
        const line = state.lines.find((l) => l.speakerId === speakerId && l.text && l.text.trim());
        return line ? line.text.trim().slice(0, 120) : '';
    }

    async function saveVoice(characterId, instruction) {
        try {
            const r = await fetch('/api/narration/cast/voice', {
                method: 'PUT',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ castId: state.editCastId, characterId, controlInstruction: instruction })
            });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            state.toast && state.toast(t('narration:toast.saved', '已保存'), 'success');
            // 音色变更：清音频缓存并刷新 castUpdatedTime（重取已缓存脚本，不重算 LLM），使后续合成用新音色
            clearCache();
            await reloadCachedScript();
            return true;
        } catch (e) {
            state.toast && state.toast(t('narration:toast.save-failed', '保存失败'), 'error');
            return false;
        }
    }

    function escapeHtml(s) {
        if (window.PixivNovelRender) return PixivNovelRender.escapeHtml(s);
        return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    // ---------- 朗读分析设置弹窗（分段字数 + 花名册选择）----------
    // 体验对齐翻译弹窗的「名词映射表」下拉：不使用（纯旁白）/ 本作默认（默认）/ 其它已有花名册 / 新建花名册；
    // 「选角与音色」按钮编辑的是「当前所选花名册」（按 castId），借用别人的花名册即编辑那份共享册。
    const CAST_OPT_NONE = '';            // 不使用（纯旁白）→ castId 0（不调 LLM）
    const CAST_OPT_DEFAULT = '__default__';
    const CAST_OPT_NEW = '__new__';
    let dialogEl = null;
    let dialogRefs = null;
    let castCtx = { def: null, list: [] }; // def: {castId,name,seriesId,novelId}; list: [{id,name,...}]

    async function castApi(url, options) {
        const res = await fetch(url, Object.assign({ credentials: 'same-origin' }, options || {}));
        if (!res.ok) {
            let msg = 'HTTP ' + res.status;
            try { const j = await res.json(); if (j && j.message) msg = j.message; } catch {}
            throw new Error(msg);
        }
        if (res.status === 204) return null;
        return res.json();
    }
    function castListAll() { return castApi('/api/narration/casts').then((d) => (d && d.casts) || []); }
    function castNovelDefault() {
        return castApi('/api/narration/casts/novel/' + encodeURIComponent(state.novelId) + '/default');
    }
    function castCreate(body) {
        return castApi('/api/narration/casts', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
    }

    function buildDialog() {
        const backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-narration-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title pt-nd-title"></span>' +
            '    <button class="pt-close pt-nd-close" type="button" aria-label="close">×</button>' +
            '  </div>' +
            '  <div class="pt-body">' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-nd-seg-label"></span>' +
            '      <input class="pt-input pt-nd-seg-input" type="number" min="0" step="100" inputmode="numeric">' +
            '    </label>' +
            '    <div class="pt-hint pt-nd-seg-hint"></div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-nd-cast-label"></span>' +
            '      <div class="pt-glossary-row">' +
            '        <select class="pt-input pt-glossary-select pt-nd-cast-select"></select>' +
            '        <button class="pt-btn pt-nd-cast-edit" type="button"></button>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-hint pt-nd-cast-hint"></div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-nd-cancel" type="button"></button>' +
            '    <button class="pt-btn pt-btn-primary pt-nd-confirm" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        dialogRefs = {
            backdrop: backdrop,
            title: backdrop.querySelector('.pt-nd-title'),
            close: backdrop.querySelector('.pt-nd-close'),
            segLabel: backdrop.querySelector('.pt-nd-seg-label'),
            segInput: backdrop.querySelector('.pt-nd-seg-input'),
            segHint: backdrop.querySelector('.pt-nd-seg-hint'),
            castLabel: backdrop.querySelector('.pt-nd-cast-label'),
            castSelect: backdrop.querySelector('.pt-nd-cast-select'),
            castEdit: backdrop.querySelector('.pt-nd-cast-edit'),
            castHint: backdrop.querySelector('.pt-nd-cast-hint'),
            cancel: backdrop.querySelector('.pt-nd-cancel'),
            confirm: backdrop.querySelector('.pt-nd-confirm')
        };
        return backdrop;
    }

    // 按当前 castCtx 重建花名册下拉
    function rebuildCastSelect(selectValue) {
        const sel = dialogRefs.castSelect;
        const prev = selectValue != null ? selectValue : sel.value;
        sel.innerHTML = '';
        const none = document.createElement('option');
        none.value = CAST_OPT_NONE;
        none.textContent = t('narration:dialog.cast-none', '不使用（纯旁白）');
        sel.appendChild(none);
        const def = castCtx.def;
        const defaultId = def && def.castId != null ? def.castId : null;
        if (def) {
            const o = document.createElement('option');
            o.value = CAST_OPT_DEFAULT;
            o.textContent = (def.name || '') + ' ' + t('narration:dialog.cast-default-suffix', '（默认）');
            sel.appendChild(o);
        }
        (castCtx.list || []).forEach((c) => {
            if (defaultId != null && c.id === defaultId) return; // 默认册已单列
            const o = document.createElement('option');
            o.value = String(c.id);
            o.textContent = c.name || ('#' + c.id);
            sel.appendChild(o);
        });
        const newOpt = document.createElement('option');
        newOpt.value = CAST_OPT_NEW;
        newOpt.textContent = t('narration:dialog.cast-new', '＋ 新建花名册');
        sel.appendChild(newOpt);

        const values = Array.prototype.map.call(sel.options, (o) => o.value);
        if (prev != null && values.indexOf(prev) !== -1 && prev !== CAST_OPT_NEW) sel.value = prev;
        else sel.value = def ? CAST_OPT_DEFAULT : CAST_OPT_NONE;
    }

    async function loadCastContext() {
        castCtx = { def: null, list: [] };
        try {
            const [def, list] = await Promise.all([
                castNovelDefault().catch(() => null),
                castListAll().catch(() => [])
            ]);
            castCtx.def = def || null;
            castCtx.list = list || [];
        } catch {}
        // 默认选中本作默认花名册（存在时），否则「不使用」。
        rebuildCastSelect(castCtx.def ? CAST_OPT_DEFAULT : CAST_OPT_NONE);
    }

    // 解析下拉当前选择对应的 castId：不使用→0（纯旁白）；默认→默认册 id（未建则按需创建）；其它→该册 id。
    async function resolveSelectedCastId() {
        const v = dialogRefs.castSelect.value;
        if (v === CAST_OPT_NONE || v === CAST_OPT_NEW) return 0;
        if (v === CAST_OPT_DEFAULT) {
            const def = castCtx.def;
            if (!def) return 0;
            if (def.castId != null) return def.castId;
            const created = await castCreate({ name: def.name, seriesId: def.seriesId, novelId: def.novelId });
            def.castId = created.id;
            return created.id;
        }
        const n = Number(v);
        return Number.isFinite(n) && n > 0 ? n : 0;
    }

    // 打开设置弹窗。返回 Promise：确认 → { segmentSize, castId }；取消 → null。
    function openDialog(opts) {
        opts = opts || {};
        if (!dialogEl) dialogEl = buildDialog();
        const r = dialogRefs;
        r.title.textContent = t('narration:dialog.title', '朗读分析设置');
        r.segLabel.textContent = t('narration:settings.segment-size', '分段字数');
        r.segInput.placeholder = t('narration:settings.segment-whole', '整章（0）');
        r.segHint.textContent = t('narration:settings.hint', '');
        r.castLabel.textContent = t('narration:dialog.cast-label', '朗读花名册');
        r.castEdit.textContent = t('narration:dialog.cast-edit', '选角与音色');
        r.castHint.textContent = t('narration:dialog.cast-hint', '');
        r.cancel.textContent = t('narration:dialog.cancel', '取消');
        r.confirm.textContent = opts.reanalyze
            ? t('narration:dialog.confirm-reanalyze', '重新分析')
            : t('narration:dialog.confirm', '开始分析并播放');
        const seg = parseInt(lsGet(LS.segment, '0'), 10);
        r.segInput.value = Number.isFinite(seg) && seg >= 0 ? String(seg) : '0';
        r.castSelect.innerHTML = '';
        loadCastContext();

        dialogEl.classList.add('open');
        setTimeout(() => { r.segInput.focus(); }, 30);

        return new Promise((resolve) => {
            let lastCastValue = CAST_OPT_DEFAULT;
            function cleanup(result) {
                dialogEl.classList.remove('open');
                r.close.onclick = null; r.cancel.onclick = null; r.confirm.onclick = null;
                r.backdrop.onclick = null; r.castEdit.onclick = null; r.castSelect.onchange = null;
                document.removeEventListener('keydown', onKey);
                resolve(result);
            }
            function onKey(e) {
                // 选角弹窗（叠在本弹窗之上）打开时让出键盘，避免一次 Escape 连关两层
                if (els.castModal && els.castModal.classList.contains('open')) return;
                if (e.key === 'Escape') cleanup(null);
            }
            async function confirmChoice() {
                let segVal = parseInt(r.segInput.value, 10);
                if (!Number.isFinite(segVal) || segVal < 0) segVal = 0;
                r.confirm.disabled = true;
                let castId;
                try { castId = await resolveSelectedCastId(); }
                catch (e) {
                    state.toast && state.toast(t('narration:toast.save-failed', '保存失败'), 'error');
                    r.confirm.disabled = false;
                    return;
                }
                r.confirm.disabled = false;
                cleanup({ segmentSize: segVal, castId: castId });
            }
            // 编辑当前所选花名册的角色音色（不使用 / 纯旁白时无册可编辑）
            async function editSelectedCast() {
                let castId;
                try { castId = await resolveSelectedCastId(); } catch { return; }
                if (castId > 0) { rebuildCastSelect(); openCastFor(castId); }
            }
            function onCastChange() {
                const v = r.castSelect.value;
                if (v === CAST_OPT_NEW) {
                    const name = window.prompt(t('narration:dialog.new-name-prompt', '新花名册名称'),
                        t('narration:dialog.new-name-default', '新花名册'));
                    if (name == null || !name.trim()) { rebuildCastSelect(lastCastValue); return; }
                    castCreate({ name: name.trim(), seriesId: null, novelId: null })
                        .then((created) => castListAll().then((list) => {
                            castCtx.list = list || [];
                            rebuildCastSelect(String(created.id));
                            lastCastValue = r.castSelect.value;
                        }))
                        .catch(() => {
                            state.toast && state.toast(t('narration:toast.save-failed', '保存失败'), 'error');
                            rebuildCastSelect(lastCastValue);
                        });
                } else {
                    lastCastValue = v;
                }
            }
            r.close.onclick = () => cleanup(null);
            r.cancel.onclick = () => cleanup(null);
            r.confirm.onclick = confirmChoice;
            r.castEdit.onclick = editSelectedCast;
            r.castSelect.onchange = onCastChange;
            r.backdrop.onclick = (e) => { if (e.target === r.backdrop) cleanup(null); };
            document.addEventListener('keydown', onKey);
        });
    }

    // 打开设置弹窗并在确认后分析。force=true 表示「重新分析」入口（覆盖缓存、不自动播放）；
    // autoPlay=true 表示首次点播放路径（分析完自动开始播放）。
    async function openAnalysisDialog(force, autoPlay) {
        if (state.loading) return;
        const choice = await openDialog({ reanalyze: !!force });
        if (!choice) return;
        lsSet(LS.segment, String(choice.segmentSize));
        await runAnalysis(choice.segmentSize, choice.castId, !!force, !!autoPlay);
    }

    // ---------- 激活 / 停用（由听书宿主 pixiv-novel-tts.js 在引擎切换时调用） ----------
    // 激活只刷新控制条 UI，<b>绝不</b>触发分析或缓存探测：分析只在用户按播放（首次弹窗确认后）/「朗读分析设置」时发生。
    function activate() {
        state.active = true;
        if (!state.blocks.length) state.blocks = buildBlocks();
        setPlayIcon(state.playing && !state.paused);
        updateProgress(false);
        updateBar(0);
        updateSubtitle(state.index >= 0 ? state.lines[state.index] : null);
        if (state.novelId) NarrationStore.touch(state.novelId);
    }
    function deactivate() {
        stop();
        clearHighlight();
        updateSubtitle(null);
        state.active = false;
    }

    // 进度条点击跳转（宿主把点击位置 frac∈[0,1] 转发进来）
    function seekFrac(frac) {
        const n = state.lines.length;
        if (!n) return;
        const f = Math.max(0, Math.min(1, frac || 0));
        seekTo(Math.min(n - 1, Math.floor(f * n)));
    }

    // ---------- 初始化（驱动模式） ----------
    // 本控制器不再拥有独立的控制条 / 入口按钮，而是作为听书（pixiv-novel-tts.js）的一个引擎被驱动：
    // 共享控制条元素（播放 / 进度 / 字幕等）由宿主在 opts.els 里传入，播放 / 上一句 / 下一句 / 停止 /
    // 进度跳转等按钮事件由宿主统一分发到本控制器；本控制器只额外接管「朗读分析设置 / 选角与音色 / 冲突」
    // 这些朗读专属控件的监听（分段字数与花名册选择都在设置弹窗内）。
    function attach(opts) {
        state.i18n = opts.i18n;
        state.toast = opts.toast;
        state.contentEl = opts.contentEl;
        state.novelId = opts.novelId ? String(opts.novelId) : '';
        state.lang = opts.lang || '';

        const shared = opts.els || {};
        els.playPause = shared.playPause;
        els.progress = shared.progress;
        els.progressFill = shared.progressFill;
        els.subtitle = shared.subtitle;
        els.regenerate = shared.regenerate;
        els.castModal = shared.castModal;
        els.castList = shared.castList;
        els.conflicts = shared.conflicts;
        if (!els.playPause || !els.progress) return;

        state.blocks = buildBlocks();

        // 朗读专属控件：「朗读分析设置」按钮打开设置弹窗（确认即按新设置 force 重新分析，不自动播放）。
        els.regenerate && els.regenerate.addEventListener('click', () => openAnalysisDialog(true, false));
        if (els.castModal) {
            els.castModal.addEventListener('click', (e) => { if (e.target.id === 'modalNarrationCast') closeCast(); });
            const closeBtn = document.getElementById('narrationCastClose');
            const doneBtn = document.getElementById('narrationCastDone');
            if (closeBtn) closeBtn.addEventListener('click', closeCast);
            if (doneBtn) doneBtn.addEventListener('click', closeCast);
        }

        window.addEventListener('beforeunload', () => { cancelCurrent(); clearCache(); });
    }

    function setI18n(i18n) {
        state.i18n = i18n;
        if (state.active) {
            setPlayIcon(state.playing && !state.paused);
            updateProgress(false);
            if (state.index >= 0) updateSubtitle(state.lines[state.index]);
        }
        if (els.castModal && els.castModal.classList.contains('open')) { renderConflicts(); renderCastList(); }
    }

    global.PixivNovelNarration = {
        attach: attach,
        setI18n: setI18n,
        activate: activate,
        deactivate: deactivate,
        togglePlay: togglePlay,
        prev: prev,
        next: next,
        stop: stop,
        seekFrac: seekFrac,
        openCast: openCast
    };
})(window);
