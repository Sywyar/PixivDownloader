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

    // ---------- 脚本加载 ----------
    function currentSegmentSize() {
        const v = parseInt(els.segmentSize ? els.segmentSize.value : '0', 10);
        return Number.isFinite(v) && v > 0 ? v : 0;
    }

    async function loadScript(force, quiet) {
        if (state.loading) return;
        if (!state.blocks.length) state.blocks = buildBlocks();
        state.loading = true;
        if (!quiet) { stop(); updateProgress(true); }
        try {
            const r = await fetch('/api/narration/script', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    novelId: Number(state.novelId),
                    lang: state.lang || '',
                    segmentSize: currentSegmentSize(),
                    force: !!force
                })
            });
            if (!r.ok) {
                let msg = 'HTTP ' + r.status;
                try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                if (!quiet) state.toast && state.toast(t('narration:toast.generate-failed', '生成失败：{message}', { message: msg }), 'error');
                return;
            }
            const data = await r.json();
            applyScript(data);
            if (force) await NarrationStore.deleteAudioForNovel(state.novelId);
            if (force && !quiet) state.toast && state.toast(t('narration:toast.generated', '多角色朗读脚本已生成'), 'success');
            if (!quiet && data.conflicts && data.conflicts.length) openCast();
        } catch (e) {
            if (!quiet) state.toast && state.toast(t('narration:toast.generate-failed', '生成失败：{message}', { message: String(e && e.message ? e.message : e) }), 'error');
        } finally {
            state.loading = false;
            updateProgress(false);
        }
    }

    function applyScript(data) {
        clearCache();
        state.lines = Array.isArray(data.lines) ? data.lines : [];
        state.cast = Array.isArray(data.cast) ? data.cast : [];
        state.conflicts = Array.isArray(data.conflicts) ? data.conflicts : [];
        state.castUpdatedTime = data.castUpdatedTime || 0;
        state.analyzedTime = data.analyzedTime || 0;
        state.segmentSize = data.segmentSize || 0;
        state.scriptLoaded = true;
        if (els.segmentSize) setSegmentValue(state.segmentSize);
        if (state.index >= state.lines.length) state.index = -1;
        updateProgress(false);
        updateBar(0);
        if (state.index >= 0) highlight(state.index, false);
    }

    // 分段字数为手动数字输入：0（或留空）= 整章一次分析，故 0 时清空让占位符「整章（0）」显示。
    function setSegmentValue(size) {
        if (!els.segmentSize) return;
        els.segmentSize.value = size > 0 ? String(size) : '';
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
        if (!state.scriptLoaded) { loadScript(false).then(() => { if (state.lines.length) start(); }); return; }
        if (!state.lines.length) { state.toast && state.toast(t('narration:toast.no-content', '没有可朗读的正文'), 'error'); return; }
        if (state.paused) { resume(); return; }
        if (state.playing) return;
        const startIndex = state.index >= 0 && state.index < state.lines.length ? state.index : 0;
        state.playing = true;
        state.paused = false;
        setPlayIcon(true);
        speak(startIndex);
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
    async function openCast() {
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
        try {
            const r = await fetch('/api/narration/cast?novelId=' + encodeURIComponent(state.novelId), { credentials: 'same-origin' });
            if (r.ok) { const data = await r.json(); voices = Array.isArray(data.voices) ? data.voices : []; }
        } catch {}
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
                body: JSON.stringify({ novelId: Number(state.novelId), characterId, controlInstruction: instruction })
            });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            state.toast && state.toast(t('narration:toast.saved', '已保存'), 'success');
            // 音色变更：清音频缓存并刷新 castUpdatedTime（重取已缓存脚本，不重算 LLM），使后续合成用新音色
            clearCache();
            await loadScript(false, true);
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

    // ---------- 激活 / 停用（由听书宿主 pixiv-novel-tts.js 在引擎切换时调用） ----------
    // 激活只刷新控制条 UI，<b>绝不</b>触发分析（不调 loadScript）：分析只在用户按播放 / 重新分析时发生。
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
    // 进度跳转等按钮事件由宿主统一分发到本控制器；本控制器只额外接管「选角 / 分段字数 / 重新分析 / 冲突」
    // 这些朗读专属控件的监听。
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
        els.segmentSize = shared.segmentSize;
        els.regenerate = shared.regenerate;
        els.castBtn = shared.castBtn;
        els.castModal = shared.castModal;
        els.castList = shared.castList;
        els.conflicts = shared.conflicts;
        if (!els.playPause || !els.progress) return;

        state.blocks = buildBlocks();
        if (els.segmentSize) setSegmentValue(parseInt(lsGet(LS.segment, '0'), 10) || 0);

        // 朗读专属控件（与浏览器 / Edge 引擎无冲突，由本控制器自行接管）
        els.castBtn && els.castBtn.addEventListener('click', openCast);
        els.segmentSize && els.segmentSize.addEventListener('change', () => lsSet(LS.segment, String(currentSegmentSize())));
        els.regenerate && els.regenerate.addEventListener('click', () => loadScript(true));
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
