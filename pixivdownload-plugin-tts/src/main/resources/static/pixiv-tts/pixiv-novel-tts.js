/* eslint-disable */
/**
 * 小说「听书」控制器（主控制器）。支持三种 TTS 引擎，前端可切换：
 *
 *   - browser   : 浏览器内置 Web Speech API（speechSynthesis）。完全离线、即时、免费；
 *                 音质与可用语言取决于操作系统已安装的语音包，纯前端运行，不经过后端。
 *   - edge      : 微软 Edge 在线神经语音，经后端 /api/tts/edge/* 代理。音质自然、语言丰富、免费；
 *                 需要联网（走后端配置的代理），有轻微合成延迟。
 *   - narration : 富感情朗读（多角色·beta）。复用同一控制条，但播放逻辑委托给 PixivNovelNarration 驱动；
 *                 仅在后端文本模型已配置且 TTS 引擎可用（或调试模式）+ 管理员登录时可见可选。
 *
 * 朗读以「段落 / 章节标题」为粒度：从正文 DOM 抽取文本片段，逐段朗读、自动翻段、高亮当前段并滚动跟随。
 *
 * 本文件是按职责拆分后的主控制器：共享上下文 ctx（state / els / 工具 / 各子模块函数）在此组装，
 * 协作子模块经 install(ctx) 注入（加载顺序见 pixiv-novel.html）：
 *   tts-store.js（lsGet/lsSet + IndexedDB 缓存）、tts-voices.js（语言 / 声库）、
 *   tts-ui.js（高亮 / 进度 / 显隐）、tts-engine-browser.js、tts-engine-edge.js。
 * 主控制器保留：buildSegments / 速率换算 / 引擎分发(含 narration) / 进度记忆 / 播放状态机 / attach / setI18n。
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
    // 进度记忆的小说数上限。与 tts-store.js 的「合成音频缓存上限」保持同一数值。
    const NOVEL_LIMIT = 100;

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
        narrationDebug: false,     // 调试模式：开启时即便引擎不可用也允许选中富感情朗读，仅用于运行分析 / 查看结果
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

    const store = global.PixivTtsStore; // localStorage 小工具 + IndexedDB 音频缓存

    // 主控制器共享上下文：状态 / DOM / 工具 / 各子模块函数都挂在 ctx 上；子模块经 install(ctx) 注入自己的函数。
    // 跨文件函数调用一律走 ctx.xxx()（运行期查属性，定义顺序无关，只要 install 都在任何调用之前完成）。
    const ctx = { state: state, els: els, LS: LS, store: store, lsGet: store.lsGet, lsSet: store.lsSet };

    ctx.t = function (key, fallback, params) {
        if (state.i18n) {
            const mappedKey = key && key.startsWith('tts.') ? 'tts:' + key.substring(4) : key;
            return state.i18n.t(mappedKey, fallback, params);
        }
        return fallback != null ? fallback : key;
    };

    // ---------- 文本片段抽取 ----------
    ctx.buildSegments = function () {
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
    };

    // ---------- 速率换算 ----------
    ctx.ratePercent = function () {
        const v = parseInt(els.rate.value, 10);
        return Number.isFinite(v) ? v : 0;
    };
    ctx.browserRate = function () {
        // 百分比 → 倍率：+100% => 2.0，-50% => 0.5
        return Math.max(0.1, Math.min(10, 1 + ctx.ratePercent() / 100));
    };

    // ---------- 富感情朗读（多角色）引擎分发 ----------
    // 「富感情朗读」是听书的第三个引擎，复用同一控制条；播放逻辑委托给 PixivNovelNarration 驱动控制器。
    ctx.isNarration = function () { return state.engine === 'narration'; };
    ctx.narrationCall = function (fn, arg) {
        const N = window.PixivNovelNarration;
        if (N && typeof N[fn] === 'function') N[fn](arg);
    };
    ctx.narrationActivate = function () { ctx.narrationCall('activate'); };
    ctx.narrationDeactivate = function () { ctx.narrationCall('deactivate'); };

    // ---------- 引擎切换 / 语音填充 ----------
    ctx.applyEngine = function (engine) {
        let next = engine === 'edge' ? 'edge' : (engine === 'narration' ? 'narration' : 'browser');
        // 富感情朗读只有在后端已配置且可用时才允许启用；不可用则退化为浏览器（无内置语音时退到在线）朗读。
        // 调试模式例外：即便引擎不可用也允许选中，便于运行分析、查看结果（逐句合成会失败）。
        if (next === 'narration' && !state.narrationAvailable && !state.narrationDebug) {
            next = ('speechSynthesis' in window) ? 'browser' : 'edge';
        }
        // 拆除当前引擎：富感情朗读交给驱动停用，浏览器 / Edge 走自身停止。
        if (ctx.isNarration()) {
            ctx.narrationDeactivate();
        } else {
            ctx.stop();
            ctx.clearEdgeCache();
            if (next === 'narration') ctx.clearSegmentHighlight(); // 进入富感情朗读前清掉单声道段落高亮
        }
        state.engine = next;
        ctx.lsSet(LS.engine, state.engine);
        els.engine.value = state.engine;
        ctx.updateModeUi();
        ctx.updateEngineHint();
        if (state.engine === 'narration') {
            ctx.narrationActivate(); // 仅刷新控制条，绝不触发分析
        } else if (state.engine === 'edge') {
            ctx.ensureEdgeVoices().then(() => ctx.populateVoices());
            ctx.updateProgress();
            ctx.updateBar(0);
        } else {
            ctx.populateVoices();
            ctx.updateProgress();
            ctx.updateBar(0);
        }
    };

    ctx.updateEngineHint = function () {
        if (ctx.isNarration()) {
            // 调试模式下引擎不可用时，提示「仅可运行分析、查看结果，合成会失败」。
            els.hint.textContent = (!state.narrationAvailable && state.narrationDebug)
                ? ctx.t('tts.hint.narration-debug')
                : ctx.t('tts.hint.narration');
        } else {
            els.hint.textContent = state.engine === 'edge' ? ctx.t('tts.hint.edge') : ctx.t('tts.hint.browser');
        }
    };

    // 引擎切换时显隐对应设置 / 控制项：富感情朗读隐藏语音 / 语速，显示字幕 /「朗读分析设置」（分段字数与花名册在弹窗内）。
    ctx.updateModeUi = function () {
        const narr = ctx.isNarration();
        if (els.voiceField) els.voiceField.style.display = narr ? 'none' : '';
        if (els.rateField) els.rateField.style.display = narr ? 'none' : '';
        if (els.regenerate) els.regenerate.style.display = narr ? '' : 'none';
        if (els.showSpeakersField) els.showSpeakersField.style.display = narr ? '' : 'none';
        if (els.subtitle) els.subtitle.style.display = narr ? '' : 'none';
        els.prev.title = narr ? ctx.t('narration:bar.prev', '上一句') : ctx.t('tts.prev', '上一段');
        els.next.title = narr ? ctx.t('narration:bar.next', '下一句') : ctx.t('tts.next', '下一段');
    };

    ctx.updateEngineOptionLabels = function () {
        if (!els.engineNarrationOpt) return;
        // 引擎可用时用常规文案；调试模式下引擎不可用但仍可选，用带「调试」标记的文案。
        els.engineNarrationOpt.textContent = (!state.narrationAvailable && state.narrationDebug)
            ? ctx.t('tts.engine.narration-debug', '富感情朗读（多角色·beta·调试）')
            : ctx.t('tts.engine.narration', '富感情朗读（多角色·beta）');
    };

    // 探测后端朗读引擎可用性 + 管理员可见性：/api/narration/* 为 admin-only，非管理员请求被拦截（非 2xx）。
    // 「富感情朗读」只有在后端已配置且可用时才可见且可选，否则隐藏不可选；非管理员 / 探测失败一律按不可用处理。
    // 上次选了富感情朗读时：可用则切回，不可用则退化为浏览器朗读并提示（不覆盖偏好，后端恢复后刷新自动切回）。
    ctx.setupNarrationOption = function (wantNarration) {
        if (!els.engineNarrationOpt || !window.PixivNovelNarration) return;
        els.engineNarrationOpt.style.display = 'none';
        els.engineNarrationOpt.disabled = true;
        fetch('/api/narration/availability', { credentials: 'same-origin' })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => ctx.applyNarrationAvailability(
                !!(data && data.available), !!(data && data.debug),
                !!(data && data.textModelConfigured), wantNarration))
            .catch(() => ctx.applyNarrationAvailability(false, false, false, wantNarration));
    };

    // 选项是否可选：需文本模型(LLM)已配置（逐句分析依赖它），且 TTS 引擎可用或处于调试模式（不可达时也允许选中以
    // 运行分析、查看结果）。文本模型未配置时分析无从谈起，无论 TTS 是否可达 / 是否调试模式一律隐藏。
    ctx.applyNarrationAvailability = function (available, debug, textModelConfigured, wantNarration) {
        state.narrationAvailable = available;
        state.narrationDebug = debug;
        const selectable = textModelConfigured && (available || debug);
        els.engineNarrationOpt.style.display = selectable ? '' : 'none';
        els.engineNarrationOpt.disabled = !selectable;
        ctx.updateEngineOptionLabels();
        if (!wantNarration) return;
        if (selectable) {
            ctx.applyEngine('narration'); // 可用或调试可选，且上次正是它 → 切回富感情朗读
        } else {
            ctx.degradeFromNarration();   // 上次选了它但现在不可用且非调试 → 退化到浏览器朗读并提示
        }
    };

    // 富感情朗读不可用时的退化：运行引擎已在初始化阶段落到 browser/edge（savedEngine='narration' → 'browser'），
    // 这里只确保不停留在 narration 并给出可见反馈；不覆盖 localStorage 偏好，后端恢复后刷新会自动切回。
    ctx.degradeFromNarration = function () {
        if (state.engine === 'narration') ctx.applyEngine(('speechSynthesis' in window) ? 'browser' : 'edge');
        const name = state.engine === 'edge'
            ? ctx.t('tts.engine.edge', '在线（Edge 神经语音）')
            : ctx.t('tts.engine.browser', '浏览器（离线）');
        state.toast && state.toast(ctx.t('tts.narration-degraded', '富感情朗读暂不可用，已切换为{engine}', { engine: name }));
    };

    // ---------- 进度记忆（按小说 ID 存 localStorage） ----------
    ctx.loadProgressMap = function () {
        try {
            return JSON.parse(ctx.lsGet(LS.progress, '') || '{}') || {};
        } catch {
            return {};
        }
    };
    ctx.savePos = function () {
        if (!state.novelId || state.index < 0) return;
        const map = ctx.loadProgressMap();
        if (map[state.novelId] === state.index) return;
        delete map[state.novelId];     // 重新插入到末尾，使键顺序近似「最近使用」
        map[state.novelId] = state.index;
        const keys = Object.keys(map);
        while (keys.length > NOVEL_LIMIT) delete map[keys.shift()]; // 超限淘汰最久未读
        ctx.lsSet(LS.progress, JSON.stringify(map));
    };
    ctx.clearPos = function () {
        if (!state.novelId) return;
        const map = ctx.loadProgressMap();
        if (map[state.novelId] != null) {
            delete map[state.novelId];
            ctx.lsSet(LS.progress, JSON.stringify(map));
        }
    };
    ctx.restorePos = function () {
        if (!state.novelId) return;
        const saved = ctx.loadProgressMap()[state.novelId];
        if (saved != null && saved >= 0 && saved < state.segments.length) {
            state.index = saved;
            ctx.highlight(saved, false); // 恢复时只高亮+更新进度条，不滚动
        }
    };

    // ---------- 播放控制（状态机） ----------
    ctx.start = function () {
        if (!state.segments.length) {
            state.segments = ctx.buildSegments();
        }
        if (!state.segments.length) {
            state.toast && state.toast(ctx.t('tts.no-content', '没有可朗读的正文'), 'error');
            return;
        }
        if (state.paused) { ctx.resume(); return; }
        if (state.playing) { return; }
        const startIndex = state.index >= 0 && state.index < state.segments.length ? state.index : 0;
        state.playing = true;
        state.paused = false;
        ctx.setPlayIcon(true);
        ctx.speak(startIndex);
    };

    ctx.speak = function (i) {
        if (i < 0 || i >= state.segments.length) { ctx.finish(); return; }
        state.index = i;
        ctx.highlight(i);
        ctx.savePos();
        const myToken = state.token;
        if (state.engine === 'edge') {
            ctx.speakEdge(i, myToken);
        } else {
            ctx.speakBrowser(i, myToken);
        }
    };

    ctx.next = function () {
        if (state.index + 1 >= state.segments.length) { ctx.stop(); return; }
        ctx.seekTo(state.index + 1);
    };
    ctx.prev = function () {
        ctx.seekTo(Math.max(0, state.index - 1));
    };
    ctx.seekTo = function (i) {
        const wasPlaying = state.playing && !state.paused;
        ctx.cancelCurrent();
        state.index = i;
        ctx.highlight(i);
        ctx.savePos();
        if (wasPlaying || state.playing) {
            state.playing = true;
            state.paused = false;
            ctx.setPlayIcon(true);
            ctx.speak(i);
        }
    };

    ctx.pause = function () {
        if (!state.playing || state.paused) return;
        state.paused = true;
        ctx.setPlayIcon(false);
        if (state.engine === 'edge') {
            if (state.edgeAudio) state.edgeAudio.pause();
        } else if ('speechSynthesis' in window) {
            window.speechSynthesis.pause();
        }
    };

    ctx.resume = function () {
        if (!state.paused) return;
        state.paused = false;
        state.playing = true;
        ctx.setPlayIcon(true);
        if (state.engine === 'edge') {
            const pending = state.edgePending;
            if (pending && pending.token === state.token) {
                state.edgePending = null;
                ctx.playEdgeUrl(pending.url, pending.index, pending.token);
                ctx.prefetchEdge(pending.index + 1);
            } else if (state.edgeAudio && state.edgeAudioIndex === state.index) {
                state.edgeAudio.play().catch(() => {});
            } else if (state.edgeFetching && state.edgeFetching.token === state.token && state.edgeFetching.index === state.index) {
                // 合成请求仍在路上；保持 paused=false，完成后 speakEdge() 会继续播放。
            } else if (state.index >= 0) {
                ctx.speak(state.index);
            }
        } else if ('speechSynthesis' in window) {
            window.speechSynthesis.resume();
        }
    };

    ctx.togglePlay = function () {
        if (state.playing && !state.paused) ctx.pause();
        else ctx.start();
    };

    ctx.cancelCurrent = function () {
        state.token++;
        state.edgeFetching = null;
        state.edgePending = null;
        if ('speechSynthesis' in window) window.speechSynthesis.cancel();
        if (state.edgeAudio) { try { state.edgeAudio.pause(); } catch {} }
    };

    ctx.stop = function () {
        ctx.cancelCurrent();
        state.playing = false;
        state.paused = false;
        ctx.setPlayIcon(false);
        ctx.updateProgress();
    };

    ctx.finish = function () {
        state.playing = false;
        state.paused = false;
        ctx.setPlayIcon(false);
        ctx.clearSegmentHighlight();
        state.index = -1;
        ctx.clearPos(); // 读完一本，清掉记忆，下次从头开始
        ctx.updateProgress();
        if (els.progressFill) els.progressFill.style.width = '100%';
    };

    // ---------- 组合子模块（注入共享 ctx）----------
    global.PixivTtsVoices.install(ctx);
    global.PixivTtsUi.install(ctx);
    global.PixivTtsEngineBrowser.install(ctx);
    global.PixivTtsEngineEdge.install(ctx);

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
        state.segments = ctx.buildSegments();

        // 浏览器不支持 Web Speech 时默认用在线引擎
        const hasWebSpeech = 'speechSynthesis' in window;
        const savedEngine = ctx.lsGet(LS.engine, hasWebSpeech ? 'browser' : 'edge');
        // 富感情朗读引擎要异步探测可用性 + 管理员可见性，初始化先回落到浏览器 / Edge；可用且上次正是它时再切回
        const wantNarration = savedEngine === 'narration';
        state.engine = savedEngine === 'edge' ? 'edge' : 'browser';
        if (!hasWebSpeech) {
            const browserOpt = els.engine.querySelector('option[value="browser"]');
            if (browserOpt) browserOpt.disabled = true;
            state.engine = 'edge';
        }
        els.engine.value = state.engine;
        els.rate.value = ctx.lsGet(LS.rate, '0');
        els.rateValue.textContent = ctx.ratePercent() + '%';

        if (hasWebSpeech) {
            ctx.loadBrowserVoices();
            window.speechSynthesis.onvoiceschanged = ctx.loadBrowserVoices;
        }
        if (state.engine === 'edge') ctx.ensureEdgeVoices().then(() => ctx.populateVoices());
        else ctx.populateVoices();
        ctx.updateEngineHint();
        ctx.updateModeUi();

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
        ctx.setupNarrationOption(wantNarration);

        toggle.addEventListener('click', () => {
            if (els.bar.style.display === 'none') { ctx.showBar(); toggle.classList.add('active'); }
            else { ctx.hideBar(); toggle.classList.remove('active'); }
        });
        // 播放 / 上一段 / 下一段 / 停止按引擎分发：富感情朗读交给驱动控制器，浏览器 / Edge 走本控制器。
        els.playPause.addEventListener('click', () => { ctx.isNarration() ? ctx.narrationCall('togglePlay') : ctx.togglePlay(); });
        els.prev.addEventListener('click', () => { ctx.isNarration() ? ctx.narrationCall('prev') : ctx.prev(); });
        els.next.addEventListener('click', () => { ctx.isNarration() ? ctx.narrationCall('next') : ctx.next(); });
        els.stop.addEventListener('click', () => { ctx.isNarration() ? ctx.narrationCall('stop') : ctx.stop(); });
        els.close.addEventListener('click', () => { ctx.hideBar(); toggle.classList.remove('active'); });
        els.settingsToggle.addEventListener('click', () => {
            els.settings.style.display = els.settings.style.display === 'none' ? 'flex' : 'none';
        });
        els.engine.addEventListener('change', () => ctx.applyEngine(els.engine.value));
        els.voice.addEventListener('change', () => {
            ctx.lsSet(state.engine === 'edge' ? LS.voiceEdge : LS.voiceBrowser, els.voice.value);
            ctx.clearEdgeCache(); // 换语音后旧音频作废
            if (state.playing && !state.paused) ctx.seekTo(state.index);
        });
        els.rate.addEventListener('input', () => {
            els.rateValue.textContent = ctx.ratePercent() + '%';
            ctx.lsSet(LS.rate, String(ctx.ratePercent()));
        });
        els.rate.addEventListener('change', () => {
            // 语速烘焙进 edge 合成音频，调整后需作废重合成；浏览器引擎下一段自动生效，无需清缓存
            ctx.clearEdgeCache();
            if (state.engine === 'edge' && state.playing && !state.paused) ctx.seekTo(state.index);
        });
        if (els.progressBar) {
            els.progressBar.addEventListener('click', (e) => {
                const rect = els.progressBar.getBoundingClientRect();
                const frac = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                if (ctx.isNarration()) { ctx.narrationCall('seekFrac', frac); return; }
                const n = state.segments.length;
                if (!n) return;
                ctx.seekTo(Math.min(n - 1, Math.floor(frac * n)));
            });
        }

        // 恢复上次进度（仅高亮+进度条，不自动播放、不滚动）
        ctx.restorePos();
        if (state.novelId) store.touch(state.novelId); // 标记最近使用，保护其缓存音频不被淘汰

        window.addEventListener('beforeunload', () => { ctx.savePos(); ctx.cancelCurrent(); ctx.clearEdgeCache(); });
    }

    function setI18n(i18n) {
        state.i18n = i18n;
        ctx.updateEngineHint();
        ctx.updateEngineOptionLabels();
        ctx.updateModeUi();
        // 富感情朗读模式下的字幕 / 进度 / 播放图标由 PixivNovelNarration.setI18n 负责（页面会单独调用）。
        if (!ctx.isNarration()) {
            ctx.setPlayIcon(state.playing && !state.paused);
            ctx.updateProgress();
        }
    }

    global.PixivNovelTts = { attach: attach, setI18n: setI18n };
})(window);
