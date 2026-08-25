/* eslint-disable */
/** 小说多角色朗读公共门面与宿主生命周期接线。 */
(function (global) {
    'use strict';

    const modules = global.PixivNovelNarrationModules;
    const ctx = {};
    for (const name of ['core', 'marks', 'playback', 'cast', 'dialog']) {
        const module = modules && modules[name];
        if (!module || typeof module.install !== 'function') {
            throw new Error('Pixiv novel narration module is missing: ' + name);
        }
        module.install(ctx);
    }

    const { LS, lsGet, NarrationStore, state, els } = ctx.core;
    const { buildBlocks, clearMarks, renderMarks, setShowSpeakers, bindResize, hasMarkedBlocks } = ctx.marks;
    const {
        clearHighlight, updateSubtitle, updateProgress, updateBar, setPlayIcon, next, prev,
        seekTo, togglePlay, cancelCurrent, stop, clearCache
    } = ctx.playback;
    const { openCast, closeCast, renderConflicts, renderCastList, bindModal } = ctx.cast;
    const { openAnalysisDialog } = ctx.dialog;

    // ---------- 激活 / 停用（由听书宿主 pixiv-novel-tts.js 在引擎切换时调用） ----------
    // 激活只刷新控制条 UI，<b>绝不</b>触发分析或缓存探测：分析只在用户按播放（首次弹窗确认后）/「朗读分析设置」时发生。
    function activate() {
        state.active = true;
        if (!state.blocks.length) state.blocks = buildBlocks();
        setPlayIcon(state.playing && !state.paused);
        updateProgress(false);
        updateBar(0);
        updateSubtitle(state.index >= 0 ? state.lines[state.index] : null);
        renderMarks();
        if (state.novelId) NarrationStore.touch(state.novelId);
    }
    function deactivate() {
        stop();
        clearHighlight();
        updateSubtitle(null);
        clearMarks();
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
        els.showSpeakers = shared.showSpeakers;
        els.castModal = shared.castModal;
        els.castList = shared.castList;
        els.conflicts = shared.conflicts;
        if (!els.playPause || !els.progress) return;

        state.blocks = buildBlocks();

        // 朗读专属控件：「朗读分析设置」按钮打开设置弹窗（确认即按新设置 force 重新分析，不自动播放）。
        els.regenerate && els.regenerate.addEventListener('click', () => openAnalysisDialog(true, false));
        // 「显示分析出的说话人」复选框：勾选即在正文逐句标注说话人；偏好持久化、跨会话恢复。
        state.showSpeakers = lsGet(LS.showSpeakers, '0') === '1';
        if (els.showSpeakers) {
            els.showSpeakers.checked = state.showSpeakers;
            els.showSpeakers.addEventListener('change', () => setShowSpeakers(els.showSpeakers.checked));
        }
        bindResize();
        bindModal();

        window.addEventListener('beforeunload', () => { cancelCurrent(); clearCache(); });
    }

    function setI18n(i18n) {
        state.i18n = i18n;
        if (state.active) {
            setPlayIcon(state.playing && !state.paused);
            updateProgress(false);
            if (state.index >= 0) updateSubtitle(state.lines[state.index]);
            if (state.showSpeakers && hasMarkedBlocks()) renderMarks(); // 切换语言后重新派生说话人列文案
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
