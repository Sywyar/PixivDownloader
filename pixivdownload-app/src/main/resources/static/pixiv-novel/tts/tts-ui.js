/* eslint-disable */
/**
 * 听书「UI」子模块：段落高亮、进度文本、进度条填充、播放图标、控制条显示 / 隐藏。
 * 全部操作主控制器共享上下文 ctx 里的 state / els；跨文件调用（buildSegments / stop /
 * isNarration / narrationActivate|Deactivate / updateProgress|Bar）一律走 ctx.xxx()。
 * install(ctx) 把这些函数挂到 ctx 上。
 */
(function (global) {
    'use strict';

    function install(ctx) {
        const state = ctx.state;
        const els = ctx.els;

        ctx.clearSegmentHighlight = function () {
            state.segments.forEach((s) => s.el.classList.remove('tts-active'));
        };

        ctx.highlight = function (i, scroll) {
            state.segments.forEach((s, idx) => s.el.classList.toggle('tts-active', idx === i));
            const seg = state.segments[i];
            if (seg && scroll !== false) seg.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            ctx.updateProgress();
            ctx.updateBar(0);
        };

        ctx.updateProgress = function (loading) {
            const n = state.segments.length;
            const cur = state.index >= 0 ? state.index + 1 : 0;
            els.progress.textContent = loading
                ? ctx.t('tts.synthesizing', '合成中… {cur}/{total}', { cur, total: n })
                : `${cur} / ${n}`;
        };

        // 进度条填充比例 = (当前段 + 段内播放比例) / 总段数；extra ∈ [0,1)
        ctx.updateBar = function (extra) {
            if (!els.progressFill) return;
            const n = state.segments.length;
            let frac = 0;
            if (n > 0 && state.index >= 0) {
                const within = Math.max(0, Math.min(1, extra || 0));
                frac = Math.min(1, (state.index + within) / n);
            }
            els.progressFill.style.width = (frac * 100).toFixed(2) + '%';
        };

        ctx.setPlayIcon = function (playing) {
            els.playPause.textContent = playing ? '⏸' : '▶';
            els.playPause.title = playing ? ctx.t('tts.pause', '暂停') : ctx.t('tts.play', '播放');
        };

        ctx.showBar = function () {
            els.bar.style.display = 'block';
            if (ctx.isNarration()) {
                ctx.narrationActivate(); // 仅刷新控制条，绝不触发分析
                return;
            }
            if (!state.segments.length) state.segments = ctx.buildSegments();
            ctx.updateProgress();
            ctx.updateBar(0);
        };

        ctx.hideBar = function () {
            if (ctx.isNarration()) ctx.narrationDeactivate();
            else ctx.stop();
            els.bar.style.display = 'none';
        };
    }

    global.PixivTtsUi = { install: install };
})(window);
