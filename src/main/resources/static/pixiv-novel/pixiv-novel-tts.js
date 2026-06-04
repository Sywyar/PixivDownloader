/* eslint-disable */
/**
 * 小说「听书」控制器。支持两种 TTS 引擎，前端可切换：
 *
 *   - browser : 浏览器内置 Web Speech API（speechSynthesis）。完全离线、即时、免费；
 *               音质与可用语言取决于操作系统已安装的语音包，纯前端运行，不经过后端。
 *   - edge    : 微软 Edge 在线神经语音，经后端 /api/tts/edge/* 代理。音质自然、语言丰富、免费；
 *               需要联网（走后端配置的代理），有轻微合成延迟。
 *
 * 朗读以「段落 / 章节标题」为粒度：从正文 DOM 抽取文本片段，逐段朗读、自动翻段、高亮当前段并滚动跟随。
 */
(function (global) {
    'use strict';

    const LS = {
        engine: 'pixiv_tts_engine',
        voiceBrowser: 'pixiv_tts_voice_browser',
        voiceEdge: 'pixiv_tts_voice_edge',
        rate: 'pixiv_tts_rate',
        progress: 'pixiv_tts_progress' // { [novelId]: segmentIndex }
    };
    const NOVEL_LIMIT = 100; // 进度记忆与合成音频缓存共用的小说数上限

    function lsGet(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch { return d; } }
    function lsSet(k, v) { try { localStorage.setItem(k, v); } catch {} }

    /**
     * IndexedDB 持久化在线合成音频。Blob 太大无法放 localStorage，故用 IndexedDB。
     * 按小说做 LRU 淘汰，最多保留 NOVEL_LIMIT 本小说的音频；每本含其全部段落/语音/语速变体。
     */
    const TtsStore = (function () {
        const DB_NAME = 'pixiv-tts';
        const AUDIO = 'audio';   // { key, novelId, blob }
        const META = 'meta';     // { novelId, lastUsed }
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

        // 标记某本小说为最近使用（打开页面时调用），避免被淘汰
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

        return { getAudio, putAudio, touch };
    })();

    const state = {
        i18n: null,
        toast: null,
        contentEl: null,
        novelId: '',           // 用于按小说记忆/恢复进度
        novelLang: '',         // 小说声明的语言（来自 Pixiv 元数据），用于自动选语音
        segments: [],          // [{ el, text }]
        index: -1,
        engine: 'browser',
        narrationAvailable: false, // 后端富感情朗读引擎是否可用（探测得到，决定引擎选项是否可选）
        playing: false,
        paused: false,
        token: 0,              // 失效令牌：停止 / 跳段 / 换引擎时自增，丢弃过期的异步回调
        browserVoices: [],
        edgeVoices: [],
        edgeVoicesLoaded: false,
        edgeAudio: null,       // 共享 <audio>
        edgeAudioIndex: -1,
        edgeFetching: null,
        edgePending: null,     // 在线合成完成但用户仍处于暂停状态时，暂存待播放音频
        edgeCache: new Map()   // cacheKey(段号|语音|语速) -> blob URL；浏览器端暂存，暂停/停止后复用，避免重复合成
    };

    const els = {};

    function t(key, fallback, params) {
        if (state.i18n) return state.i18n.t(key, fallback, params);
        return fallback != null ? fallback : key;
    }

    // ---------- 文本片段抽取 ----------
    function buildSegments() {
        const segs = [];
        if (!state.contentEl) return segs;
        const nodes = state.contentEl.querySelectorAll('h2.novel-chapter, p');
        nodes.forEach((node) => {
            const clone = node.cloneNode(true);
            // 去掉注音读音(rt)避免「汉字+假名」重复朗读，并剔除图片占位 / 翻页提示
            clone.querySelectorAll('rt, .novel-jump').forEach((x) => x.remove());
            const text = (clone.textContent || '').replace(/\s+/g, ' ').trim();
            if (text) segs.push({ el: node, text });
        });
        return segs;
    }

    function detectLang(sample) {
        const s = sample || '';
        if (/[぀-ヿ]/.test(s)) return 'ja';
        if (/[가-힯]/.test(s)) return 'ko';
        if (/[一-鿿]/.test(s)) return 'zh';
        return 'en';
    }

    function docLang() {
        // 优先用小说声明的语言（如 zh-cn / ja / en），否则按正文文本启发式判断
        const nl = (state.novelLang || '').toLowerCase();
        if (nl.startsWith('zh')) return 'zh';
        if (nl.startsWith('ja')) return 'ja';
        if (nl.startsWith('ko')) return 'ko';
        if (nl.startsWith('en')) return 'en';
        const sample = state.segments.slice(0, 5).map((s) => s.text).join(' ');
        return detectLang(sample);
    }

    // ---------- 速率换算 ----------
    function ratePercent() {
        const v = parseInt(els.rate.value, 10);
        return Number.isFinite(v) ? v : 0;
    }
    function browserRate() {
        // 百分比 → 倍率：+100% => 2.0，-50% => 0.5
        return Math.max(0.1, Math.min(10, 1 + ratePercent() / 100));
    }

    // ---------- 富感情朗读（多角色）引擎分发 ----------
    // 「富感情朗读」是听书的第三个引擎，复用同一控制条；播放逻辑委托给 PixivNovelNarration 驱动控制器。
    function isNarration() { return state.engine === 'narration'; }
    function narrationCall(fn, arg) {
        const N = window.PixivNovelNarration;
        if (N && typeof N[fn] === 'function') N[fn](arg);
    }
    function narrationActivate() { narrationCall('activate'); }
    function narrationDeactivate() { narrationCall('deactivate'); }

    // ---------- 引擎切换 / 语音填充 ----------
    function applyEngine(engine) {
        let next = engine === 'edge' ? 'edge' : (engine === 'narration' ? 'narration' : 'browser');
        // 富感情朗读只有在后端已配置且可用时才允许启用；不可用则退化为浏览器（无内置语音时退到在线）朗读。
        if (next === 'narration' && !state.narrationAvailable) {
            next = ('speechSynthesis' in window) ? 'browser' : 'edge';
        }
        // 拆除当前引擎：富感情朗读交给驱动停用，浏览器 / Edge 走自身停止。
        if (isNarration()) {
            narrationDeactivate();
        } else {
            stop();
            clearEdgeCache();
            if (next === 'narration') clearSegmentHighlight(); // 进入富感情朗读前清掉单声道段落高亮
        }
        state.engine = next;
        lsSet(LS.engine, state.engine);
        els.engine.value = state.engine;
        updateModeUi();
        updateEngineHint();
        if (state.engine === 'narration') {
            narrationActivate(); // 仅刷新控制条，绝不触发分析
        } else if (state.engine === 'edge') {
            ensureEdgeVoices().then(() => populateVoices());
            updateProgress();
            updateBar(0);
        } else {
            populateVoices();
            updateProgress();
            updateBar(0);
        }
    }

    function updateEngineHint() {
        if (isNarration()) {
            els.hint.textContent = t('tts.hint.narration');
        } else {
            els.hint.textContent = state.engine === 'edge' ? t('tts.hint.edge') : t('tts.hint.browser');
        }
    }

    // 引擎切换时显隐对应设置 / 控制项：富感情朗读隐藏语音 / 语速，显示字幕 /「朗读分析设置」（分段字数与花名册在弹窗内）。
    function updateModeUi() {
        const narr = isNarration();
        if (els.voiceField) els.voiceField.style.display = narr ? 'none' : '';
        if (els.rateField) els.rateField.style.display = narr ? 'none' : '';
        if (els.regenerate) els.regenerate.style.display = narr ? '' : 'none';
        if (els.showSpeakersField) els.showSpeakersField.style.display = narr ? '' : 'none';
        if (els.subtitle) els.subtitle.style.display = narr ? '' : 'none';
        els.prev.title = narr ? t('narration:bar.prev', '上一句') : t('tts.prev', '上一段');
        els.next.title = narr ? t('narration:bar.next', '下一句') : t('tts.next', '下一段');
    }

    function updateEngineOptionLabels() {
        if (!els.engineNarrationOpt) return;
        // 该选项仅在后端可用时才显示，故始终用可用态文案。
        els.engineNarrationOpt.textContent = t('tts.engine.narration', '富感情朗读（多角色）');
    }

    // 探测后端朗读引擎可用性 + 管理员可见性：/api/narration/* 为 admin-only，非管理员请求被拦截（非 2xx）。
    // 「富感情朗读」只有在后端已配置且可用时才可见且可选，否则隐藏不可选；非管理员 / 探测失败一律按不可用处理。
    // 上次选了富感情朗读时：可用则切回，不可用则退化为浏览器朗读并提示（不覆盖偏好，后端恢复后刷新自动切回）。
    function setupNarrationOption(wantNarration) {
        if (!els.engineNarrationOpt || !window.PixivNovelNarration) return;
        els.engineNarrationOpt.style.display = 'none';
        els.engineNarrationOpt.disabled = true;
        fetch('/api/narration/availability', { credentials: 'same-origin' })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => applyNarrationAvailability(!!(data && data.available), wantNarration))
            .catch(() => applyNarrationAvailability(false, wantNarration));
    }

    function applyNarrationAvailability(available, wantNarration) {
        state.narrationAvailable = available;
        els.engineNarrationOpt.style.display = available ? '' : 'none';
        els.engineNarrationOpt.disabled = !available;
        updateEngineOptionLabels();
        if (!wantNarration) return;
        if (available) {
            applyEngine('narration'); // 可用且上次正是它 → 切回富感情朗读
        } else {
            degradeFromNarration();   // 上次选了它但现在不可用 → 退化到浏览器朗读并提示
        }
    }

    // 富感情朗读不可用时的退化：运行引擎已在初始化阶段落到 browser/edge（savedEngine='narration' → 'browser'），
    // 这里只确保不停留在 narration 并给出可见反馈；不覆盖 localStorage 偏好，后端恢复后刷新会自动切回。
    function degradeFromNarration() {
        if (state.engine === 'narration') applyEngine(('speechSynthesis' in window) ? 'browser' : 'edge');
        const name = state.engine === 'edge'
            ? t('tts.engine.edge', '在线（Edge 神经语音）')
            : t('tts.engine.browser', '浏览器（离线）');
        state.toast && state.toast(t('tts.narration-degraded', '富感情朗读暂不可用，已切换为{engine}', { engine: name }));
    }

    function populateVoices() {
        const sel = els.voice;
        sel.innerHTML = '';
        const lang = docLang();
        if (state.engine === 'edge') {
            const saved = lsGet(LS.voiceEdge, '');
            const sorted = sortByLangFirst(state.edgeVoices, lang, (v) => v.locale);
            sorted.forEach((v) => {
                const opt = document.createElement('option');
                opt.value = v.shortName;
                opt.textContent = `${v.locale} · ${v.localName || v.shortName}${v.gender ? ' (' + v.gender + ')' : ''}`;
                sel.appendChild(opt);
            });
            sel.value = chooseVoice(sorted, saved, lang, (v) => v.shortName, (v) => v.locale);
        } else {
            const saved = lsGet(LS.voiceBrowser, '');
            const sorted = sortByLangFirst(state.browserVoices, lang, (v) => v.lang);
            sorted.forEach((v) => {
                const opt = document.createElement('option');
                opt.value = v.voiceURI || v.name;
                opt.textContent = `${v.lang} · ${v.name}`;
                sel.appendChild(opt);
            });
            sel.value = chooseVoice(sorted, saved, lang, (v) => v.voiceURI || v.name, (v) => v.lang);
        }
    }

    /**
     * 选默认语音：若记住的语音与当前小说语言一致则沿用；否则自动选当前语言下的第一个语音
     * （sorted 已把匹配语言的语音排在最前）。这样换一本不同语言的小说会自动切到对应语言的语音。
     */
    function chooseVoice(sorted, savedId, lang, getId, getLocale) {
        const savedVoice = sorted.find((v) => getId(v) === savedId);
        if (savedVoice && (getLocale(savedVoice) || '').toLowerCase().startsWith(lang)) {
            return savedId;
        }
        return sorted[0] ? getId(sorted[0]) : '';
    }

    function sortByLangFirst(list, lang, getLocale) {
        const match = [];
        const rest = [];
        list.forEach((v) => {
            const loc = (getLocale(v) || '').toLowerCase();
            if (loc.startsWith(lang)) match.push(v); else rest.push(v);
        });
        return match.concat(rest);
    }

    function loadBrowserVoices() {
        if (!('speechSynthesis' in window)) return;
        state.browserVoices = window.speechSynthesis.getVoices() || [];
        if (state.engine === 'browser') populateVoices();
    }

    function ensureEdgeVoices() {
        if (state.edgeVoicesLoaded) return Promise.resolve();
        return fetch('/api/tts/edge/voices', { credentials: 'same-origin' })
            .then((r) => (r.ok ? r.json() : []))
            .then((list) => {
                state.edgeVoices = Array.isArray(list) ? list : [];
                state.edgeVoicesLoaded = true;
            })
            .catch(() => {
                state.edgeVoices = [];
                state.edgeVoicesLoaded = true;
            });
    }

    // ---------- 高亮 / 进度 ----------
    function clearSegmentHighlight() {
        state.segments.forEach((s) => s.el.classList.remove('tts-active'));
    }

    function highlight(i, scroll) {
        state.segments.forEach((s, idx) => s.el.classList.toggle('tts-active', idx === i));
        const seg = state.segments[i];
        if (seg && scroll !== false) seg.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        updateProgress();
        updateBar(0);
    }

    function updateProgress(loading) {
        const n = state.segments.length;
        const cur = state.index >= 0 ? state.index + 1 : 0;
        els.progress.textContent = loading
            ? t('tts.synthesizing', '合成中… {cur}/{total}', { cur, total: n })
            : `${cur} / ${n}`;
    }

    // 进度条填充比例 = (当前段 + 段内播放比例) / 总段数；extra ∈ [0,1)
    function updateBar(extra) {
        if (!els.progressFill) return;
        const n = state.segments.length;
        let frac = 0;
        if (n > 0 && state.index >= 0) {
            const within = Math.max(0, Math.min(1, extra || 0));
            frac = Math.min(1, (state.index + within) / n);
        }
        els.progressFill.style.width = (frac * 100).toFixed(2) + '%';
    }

    // ---------- 进度记忆（按小说 ID 存 localStorage） ----------
    function loadProgressMap() {
        try {
            return JSON.parse(lsGet(LS.progress, '') || '{}') || {};
        } catch {
            return {};
        }
    }
    function savePos() {
        if (!state.novelId || state.index < 0) return;
        const map = loadProgressMap();
        if (map[state.novelId] === state.index) return;
        delete map[state.novelId];     // 重新插入到末尾，使键顺序近似「最近使用」
        map[state.novelId] = state.index;
        const keys = Object.keys(map);
        while (keys.length > NOVEL_LIMIT) delete map[keys.shift()]; // 超限淘汰最久未读
        lsSet(LS.progress, JSON.stringify(map));
    }
    function clearPos() {
        if (!state.novelId) return;
        const map = loadProgressMap();
        if (map[state.novelId] != null) {
            delete map[state.novelId];
            lsSet(LS.progress, JSON.stringify(map));
        }
    }
    function restorePos() {
        if (!state.novelId) return;
        const saved = loadProgressMap()[state.novelId];
        if (saved != null && saved >= 0 && saved < state.segments.length) {
            state.index = saved;
            highlight(saved, false); // 恢复时只高亮+更新进度条，不滚动
        }
    }

    function setPlayIcon(playing) {
        els.playPause.textContent = playing ? '⏸' : '▶';
        els.playPause.title = playing ? t('tts.pause', '暂停') : t('tts.play', '播放');
    }

    // ---------- 播放控制 ----------
    function start() {
        if (!state.segments.length) {
            state.segments = buildSegments();
        }
        if (!state.segments.length) {
            state.toast && state.toast(t('tts.no-content', '没有可朗读的正文'), 'error');
            return;
        }
        if (state.paused) { resume(); return; }
        if (state.playing) { return; }
        const startIndex = state.index >= 0 && state.index < state.segments.length ? state.index : 0;
        state.playing = true;
        state.paused = false;
        setPlayIcon(true);
        speak(startIndex);
    }

    function speak(i) {
        if (i < 0 || i >= state.segments.length) { finish(); return; }
        state.index = i;
        highlight(i);
        savePos();
        const myToken = state.token;
        if (state.engine === 'edge') {
            speakEdge(i, myToken);
        } else {
            speakBrowser(i, myToken);
        }
    }

    function next() {
        if (state.index + 1 >= state.segments.length) { stop(); return; }
        seekTo(state.index + 1);
    }
    function prev() {
        seekTo(Math.max(0, state.index - 1));
    }
    function seekTo(i) {
        const wasPlaying = state.playing && !state.paused;
        cancelCurrent();
        state.index = i;
        highlight(i);
        savePos();
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
        if (state.engine === 'edge') {
            if (state.edgeAudio) state.edgeAudio.pause();
        } else if ('speechSynthesis' in window) {
            window.speechSynthesis.pause();
        }
    }

    function resume() {
        if (!state.paused) return;
        state.paused = false;
        state.playing = true;
        setPlayIcon(true);
        if (state.engine === 'edge') {
            const pending = state.edgePending;
            if (pending && pending.token === state.token) {
                state.edgePending = null;
                playEdgeUrl(pending.url, pending.index, pending.token);
                prefetchEdge(pending.index + 1);
            } else if (state.edgeAudio && state.edgeAudioIndex === state.index) {
                state.edgeAudio.play().catch(() => {});
            } else if (state.edgeFetching && state.edgeFetching.token === state.token && state.edgeFetching.index === state.index) {
                // 合成请求仍在路上；保持 paused=false，完成后 speakEdge() 会继续播放。
            } else if (state.index >= 0) {
                speak(state.index);
            }
        } else if ('speechSynthesis' in window) {
            window.speechSynthesis.resume();
        }
    }

    function togglePlay() {
        if (state.playing && !state.paused) pause();
        else start();
    }

    function cancelCurrent() {
        state.token++;
        state.edgeFetching = null;
        state.edgePending = null;
        if ('speechSynthesis' in window) window.speechSynthesis.cancel();
        if (state.edgeAudio) { try { state.edgeAudio.pause(); } catch {} }
    }

    function stop() {
        cancelCurrent();
        state.playing = false;
        state.paused = false;
        setPlayIcon(false);
        updateProgress();
    }

    function finish() {
        state.playing = false;
        state.paused = false;
        setPlayIcon(false);
        clearSegmentHighlight();
        state.index = -1;
        clearPos(); // 读完一本，清掉记忆，下次从头开始
        updateProgress();
        if (els.progressFill) els.progressFill.style.width = '100%';
    }

    // ---------- 浏览器引擎 ----------
    function speakBrowser(i, myToken) {
        if (!('speechSynthesis' in window)) {
            state.toast && state.toast(t('tts.browser-unsupported', '当前浏览器不支持内置语音'), 'error');
            stop();
            return;
        }
        const seg = state.segments[i];
        const u = new SpeechSynthesisUtterance(seg.text);
        const voiceId = els.voice.value;
        const voice = state.browserVoices.find((v) => (v.voiceURI || v.name) === voiceId);
        if (voice) { u.voice = voice; u.lang = voice.lang; }
        u.rate = browserRate();
        u.onboundary = (e) => {
            if (myToken !== state.token) return;
            if (seg.text.length > 0 && e.charIndex != null) {
                updateBar(e.charIndex / seg.text.length);
            }
        };
        u.onend = () => {
            if (myToken !== state.token) return;
            speak(i + 1);
        };
        u.onerror = () => {
            if (myToken !== state.token) return;
            speak(i + 1);
        };
        window.speechSynthesis.cancel();
        window.speechSynthesis.speak(u);
    }

    // ---------- Edge 在线引擎 ----------
    function speakEdge(i, myToken) {
        updateProgress(true);
        state.edgeFetching = { index: i, token: myToken };
        fetchEdgeAudio(i)
            .then((url) => {
                if (myToken !== state.token) return;
                state.edgeFetching = null;
                updateProgress(false);
                if (state.paused) {
                    state.edgePending = { url: url, index: i, token: myToken };
                    return;
                }
                playEdgeUrl(url, i, myToken);
                prefetchEdge(i + 1);
            })
            .catch((err) => {
                if (myToken !== state.token) return;
                state.edgeFetching = null;
                state.toast && state.toast(String(err && err.message ? err.message : err), 'error');
                stop();
            });
    }

    function playEdgeUrl(url, i, myToken) {
        state.edgePending = null;
        if (!state.edgeAudio) state.edgeAudio = new Audio();
        const audio = state.edgeAudio;
        state.edgeAudioIndex = i;
        audio.src = url;
        audio.ontimeupdate = () => {
            if (myToken !== state.token) return;
            if (audio.duration > 0) updateBar(audio.currentTime / audio.duration);
        };
        audio.onended = () => {
            if (myToken !== state.token) return;
            speak(i + 1);
        };
        audio.onerror = () => {
            if (myToken !== state.token) return;
            speak(i + 1);
        };
        audio.play().catch(() => {});
    }

    // 缓存键含语音与语速：换语音/语速会生成不同条目，互不污染
    function cacheKey(i) {
        return i + '|' + els.voice.value + '|' + ratePercent();
    }
    // IndexedDB 持久化键再带上小说 ID，跨小说隔离
    function storeKey(i) {
        return state.novelId + '|' + cacheKey(i);
    }

    async function fetchEdgeAudio(i) {
        const key = cacheKey(i);
        const mem = state.edgeCache.get(key);
        if (mem) return mem;
        const seg = state.segments[i];
        if (!seg) throw new Error('no segment');

        // 先查本地持久化缓存（IndexedDB）
        const persisted = await TtsStore.getAudio(storeKey(i));
        if (persisted) {
            const url = URL.createObjectURL(persisted);
            state.edgeCache.set(key, url);
            // 命中即视为最近使用，避免被淘汰
            TtsStore.touch(state.novelId);
            return url;
        }

        // 未命中再请求服务端合成
        const r = await fetch('/api/tts/edge/synthesize', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ text: seg.text, voice: els.voice.value, rate: ratePercent(), pitch: 0 })
        });
        if (!r.ok) {
            let msg = 'HTTP ' + r.status;
            try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
            throw new Error(msg);
        }
        const blob = await r.blob();
        const url = URL.createObjectURL(blob);
        state.edgeCache.set(key, url);
        TtsStore.putAudio(storeKey(i), state.novelId, blob); // 持久化（fire-and-forget）
        return url;
    }

    function prefetchEdge(i) {
        if (i < 0 || i >= state.segments.length) return;
        if (state.edgeCache.has(cacheKey(i))) return;
        fetchEdgeAudio(i).catch(() => {});
    }

    // 仅在语音/语速/引擎变化或离开页面时清空（释放 blob）；暂停/停止/跳段都保留缓存以复用
    function clearEdgeCache() {
        state.edgeCache.forEach((url) => { try { URL.revokeObjectURL(url); } catch {} });
        state.edgeCache.clear();
    }

    // ---------- 显示 / 隐藏 ----------
    function showBar() {
        els.bar.style.display = 'block';
        if (isNarration()) {
            narrationActivate(); // 仅刷新控制条，绝不触发分析
            return;
        }
        if (!state.segments.length) state.segments = buildSegments();
        updateProgress();
        updateBar(0);
    }
    function hideBar() {
        if (isNarration()) narrationDeactivate();
        else stop();
        els.bar.style.display = 'none';
    }

    // ---------- 初始化 ----------
    function attach(opts) {
        state.i18n = opts.i18n;
        state.toast = opts.toast;
        state.contentEl = opts.contentEl;
        state.novelLang = opts.language || '';
        state.novelId = opts.novelId ? String(opts.novelId) : '';

        els.bar = document.getElementById('ttsBar');
        els.playPause = document.getElementById('ttsPlayPause');
        els.prev = document.getElementById('ttsPrev');
        els.next = document.getElementById('ttsNext');
        els.stop = document.getElementById('ttsStop');
        els.progress = document.getElementById('ttsProgress');
        els.progressBar = document.getElementById('ttsProgressBar');
        els.progressFill = document.getElementById('ttsProgressFill');
        els.settingsToggle = document.getElementById('ttsSettingsToggle');
        els.close = document.getElementById('ttsClose');
        els.settings = document.getElementById('ttsSettings');
        els.engine = document.getElementById('ttsEngine');
        els.voice = document.getElementById('ttsVoice');
        els.rate = document.getElementById('ttsRate');
        els.rateValue = document.getElementById('ttsRateValue');
        els.hint = document.getElementById('ttsEngineHint');
        // 富感情朗读（多角色）相关的共享 / 专属控件（分段字数与花名册选择都在「朗读分析设置」弹窗内）
        els.subtitle = document.getElementById('ttsSubtitle');
        els.voiceField = document.getElementById('ttsVoiceField');
        els.rateField = document.getElementById('ttsRateField');
        els.regenerate = document.getElementById('ttsRegenerate');
        els.showSpeakersField = document.getElementById('ttsShowSpeakersField');
        els.showSpeakers = document.getElementById('ttsShowSpeakers');
        els.engineNarrationOpt = document.getElementById('ttsEngineNarrationOpt');
        const toggle = document.getElementById('ttsToggle');
        if (!els.bar || !toggle) return;

        // 提前构建片段：语音语言启发式与进度恢复都依赖它
        state.segments = buildSegments();

        // 浏览器不支持 Web Speech 时默认用在线引擎
        const hasWebSpeech = 'speechSynthesis' in window;
        const savedEngine = lsGet(LS.engine, hasWebSpeech ? 'browser' : 'edge');
        // 富感情朗读引擎要异步探测可用性 + 管理员可见性，初始化先回落到浏览器 / Edge；可用且上次正是它时再切回
        const wantNarration = savedEngine === 'narration';
        state.engine = savedEngine === 'edge' ? 'edge' : 'browser';
        if (!hasWebSpeech) {
            const browserOpt = els.engine.querySelector('option[value="browser"]');
            if (browserOpt) browserOpt.disabled = true;
            state.engine = 'edge';
        }
        els.engine.value = state.engine;
        els.rate.value = lsGet(LS.rate, '0');
        els.rateValue.textContent = ratePercent() + '%';

        if (hasWebSpeech) {
            loadBrowserVoices();
            window.speechSynthesis.onvoiceschanged = loadBrowserVoices;
        }
        if (state.engine === 'edge') ensureEdgeVoices().then(() => populateVoices());
        else populateVoices();
        updateEngineHint();
        updateModeUi();

        // 富感情朗读（多角色）作为听书的一个引擎：把共享控制条元素交给驱动控制器，由本宿主按引擎分发控制。
        if (window.PixivNovelNarration) {
            window.PixivNovelNarration.attach({
                i18n: state.i18n, toast: state.toast, contentEl: state.contentEl,
                novelId: state.novelId, lang: opts.narrationLang || '',
                els: {
                    playPause: els.playPause, progress: els.progress, progressFill: els.progressFill,
                    subtitle: els.subtitle, regenerate: els.regenerate, showSpeakers: els.showSpeakers,
                    castModal: document.getElementById('modalNarrationCast'),
                    castList: document.getElementById('narrationCastList'),
                    conflicts: document.getElementById('narrationConflicts')
                }
            });
        }
        setupNarrationOption(wantNarration);

        toggle.addEventListener('click', () => {
            if (els.bar.style.display === 'none') { showBar(); toggle.classList.add('active'); }
            else { hideBar(); toggle.classList.remove('active'); }
        });
        // 播放 / 上一段 / 下一段 / 停止按引擎分发：富感情朗读交给驱动控制器，浏览器 / Edge 走本控制器。
        els.playPause.addEventListener('click', () => { isNarration() ? narrationCall('togglePlay') : togglePlay(); });
        els.prev.addEventListener('click', () => { isNarration() ? narrationCall('prev') : prev(); });
        els.next.addEventListener('click', () => { isNarration() ? narrationCall('next') : next(); });
        els.stop.addEventListener('click', () => { isNarration() ? narrationCall('stop') : stop(); });
        els.close.addEventListener('click', () => { hideBar(); toggle.classList.remove('active'); });
        els.settingsToggle.addEventListener('click', () => {
            els.settings.style.display = els.settings.style.display === 'none' ? 'flex' : 'none';
        });
        els.engine.addEventListener('change', () => applyEngine(els.engine.value));
        els.voice.addEventListener('change', () => {
            lsSet(state.engine === 'edge' ? LS.voiceEdge : LS.voiceBrowser, els.voice.value);
            clearEdgeCache(); // 换语音后旧音频作废
            if (state.playing && !state.paused) seekTo(state.index);
        });
        els.rate.addEventListener('input', () => {
            els.rateValue.textContent = ratePercent() + '%';
            lsSet(LS.rate, String(ratePercent()));
        });
        els.rate.addEventListener('change', () => {
            // 语速烘焙进 edge 合成音频，调整后需作废重合成；浏览器引擎下一段自动生效，无需清缓存
            clearEdgeCache();
            if (state.engine === 'edge' && state.playing && !state.paused) seekTo(state.index);
        });
        if (els.progressBar) {
            els.progressBar.addEventListener('click', (e) => {
                const rect = els.progressBar.getBoundingClientRect();
                const frac = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                if (isNarration()) { narrationCall('seekFrac', frac); return; }
                const n = state.segments.length;
                if (!n) return;
                seekTo(Math.min(n - 1, Math.floor(frac * n)));
            });
        }

        // 恢复上次进度（仅高亮+进度条，不自动播放、不滚动）
        restorePos();
        if (state.novelId) TtsStore.touch(state.novelId); // 标记最近使用，保护其缓存音频不被淘汰

        window.addEventListener('beforeunload', () => { savePos(); cancelCurrent(); clearEdgeCache(); });
    }

    function setI18n(i18n) {
        state.i18n = i18n;
        updateEngineHint();
        updateEngineOptionLabels();
        updateModeUi();
        // 富感情朗读模式下的字幕 / 进度 / 播放图标由 PixivNovelNarration.setI18n 负责（页面会单独调用）。
        if (!isNarration()) {
            setPlayIcon(state.playing && !state.paused);
            updateProgress();
        }
    }

    global.PixivNovelTts = { attach: attach, setI18n: setI18n };
})(window);
