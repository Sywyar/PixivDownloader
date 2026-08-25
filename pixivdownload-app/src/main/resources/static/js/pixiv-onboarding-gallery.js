/* 跨页新用户引导的画廊与作品详情流程。 */
(function (global) {
    'use strict';

    var ctx = global.PixivOnboardingRuntime;
    if (!ctx || !ctx.overlay || !ctx.footHtml || !ctx.bindFoot) {
        return;
    }

    var EXAMPLE_ID = ctx.EXAMPLE_ID;
    var EXAMPLE_URL = ctx.EXAMPLE_URL;
    var t = ctx.t;
    var escapeHtml = ctx.escapeHtml;
    var loadState = ctx.loadState;
    var patchState = ctx.patchState;
    var markCompleted = ctx.markCompleted;
    var SKIP_BTN = ctx.SKIP_BTN;
    var overlay = ctx.overlay;
    var footHtml = ctx.footHtml;
    var bindFoot = ctx.bindFoot;
    var skip = ctx.skip;
    var finish = ctx.finish;
    var hook = ctx.hook;
    var callHook = ctx.callHook;
    var waitFor = ctx.waitFor;
    var waitForElement = ctx.waitForElement;
    var notifyCompletionStepDone = ctx.notifyCompletionStepDone;

    function phaseGallery() {
        patchState({phase: 'gallery'});
        // 进入画廊讲解即视为「画廊操作指引」已抵达，通知后端供 GUI 引导推进（取代旧版画廊指引的信号）
        notifyCompletionStepDone();
        // 先逐区域认识画廊（视图 / 搜索 / 筛选 / 作品网格），再高亮刚下载的作品卡片
        runGalleryRegions(highlightExampleCard);
    }

    // 画廊各区域讲解（按当前可见者裁剪）
    function galleryRegions() {
        return [
            {
                sels: [ctx.config.sel.viewNav],
                title: t('onboarding.gallery.views.title', '① 浏览视图'),
                body: t('onboarding.gallery.views.body', '左侧可在「全部图片 / 按作者 / 按系列漫画」之间切换，用不同维度浏览你下载的作品；下方还有收藏夹与各页面导航。')
            },
            {
                sels: [ctx.config.sel.searchBox],
                title: t('onboarding.gallery.search.title', '② 搜索'),
                body: t('onboarding.gallery.search.body', '输入作品标题、画师名、标签等快速查找已下载的内容；左侧下拉可切换搜索范围。')
            },
            {
                sels: [ctx.config.sel.filterToggle],
                title: t('onboarding.gallery.filter.title', '③ 筛选'),
                body: t('onboarding.gallery.filter.body', '点开筛选可按排序、分级、AI、格式、收藏夹、系列等条件组合过滤，精准定位想看的作品。')
            },
            {
                sels: [ctx.config.sel.grid],
                title: t('onboarding.gallery.grid.title', '④ 作品网格'),
                body: t('onboarding.gallery.grid.body', '下载完成的作品都展示在这里，点任意一张卡片即可进入作品详情页。')
            }
        ];
    }

    function runGalleryRegions(onComplete) {
        var steps = galleryRegions()
            .map(function (r) {
                var sel = firstVisibleSelector(r.sels);
                return sel ? {sel: sel, title: r.title, body: r.body} : null;
            })
            .filter(Boolean);
        runRegionSteps(steps, onComplete, t('onboarding.common.next', '下一步'), function (i, n) {
            return t('onboarding.gallery.progress', '认识画廊 · {step}/{total}', {step: i + 1, total: n});
        });
    }

    function highlightExampleCard() {
        // 卡片可能随网格异步渲染，等它出现再聚光
        waitForElement(function () {
            var fn = hook('getExampleCard');
            return fn ? fn(EXAMPLE_ID) : null;
        }, function (el) {
            renderGalleryCard(!!el);
        }, function () {
            renderGalleryCard(false);
        }, 8000);
    }

    function renderGalleryCard(found) {
        var sel = found ? callHook('getExampleCardSelector', EXAMPLE_ID) : (ctx.config.sel.grid || null);
        overlay.render({
            targetSelector: sel,
            interactiveSelector: found ? sel : null,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.gallery.card.title', '🖼 这是你刚下载的作品')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(found
                    ? t('onboarding.gallery.card.body', '点击这张高亮的作品卡片，进入作品详情页。')
                    : t('onboarding.gallery.card.body-fallback', '你下载的作品会出现在这里，点任意一张卡片可进入作品详情页。')) + '</p></div>'
                + footHtml([SKIP_BTN()])
        });
        bindFoot({skip: skip});
        if (found) {
            // 点击卡片会跳转到详情页；先把阶段推进到 detail，详情页 boot 续跑
            var card = callHook('getExampleCard', EXAMPLE_ID);
            if (card) {
                card.addEventListener('click', function () {
                    patchState({phase: 'detail'});
                }, {capture: true, once: true});
            }
        }
    }

    // 用户先打开画廊（未走下载页）：引导回下载页
    function phaseGalleryRedirect() {
        patchState({status: 'active', phase: 'welcome'});
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.redirect.title', '👋 欢迎使用 PixivDownloader')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.redirect.body',
                    '新手指引从「下载页」开始，会带你下载第一份示例作品。要现在前往吗？')) + '</p></div>'
                + footHtml([
                    {act: 'later', label: t('onboarding.redirect.later', '以后再说'), variant: 'ghost'},
                    {act: 'go', label: t('onboarding.redirect.go', '前往下载页'), variant: 'primary'}
                ])
        });
        bindFoot({
            later: skip,
            go: function () {
                global.location.href = '/pixiv-batch.html';
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  详情页阶段（逐区域讲解）
    // ════════════════════════════════════════════════════════════════════════════

    function isVisible(el) {
        return !!(el && el.offsetParent !== null);
    }

    // 解析一组候选选择器中第一个「存在且可见」的，返回其选择器（用于聚光），无则 null
    function firstVisibleSelector(sels) {
        for (var i = 0; i < sels.length; i++) {
            var s = sels[i];
            if (s && isVisible(document.querySelector(s))) {
                return s;
            }
        }
        return null;
    }

    function detailRegions() {
        return [
            {
                sels: [ctx.config.sel.viewer],
                title: t('onboarding.detail.viewer.title', '① 作品图区'),
                body: t('onboarding.detail.viewer.body', '这里展示作品的图片，点击任意图片可放大查看；多图作品会逐页平铺浏览。')
            },
            {
                sels: [ctx.config.sel.actions],
                title: t('onboarding.detail.actions.title', '② 功能区'),
                body: t('onboarding.detail.actions.body', '作品的操作区：展开 / 收起多图、跳转 Pixiv 原作品、作品展示，以及删除本地作品等，旁边还显示页数与格式信息。')
            },
            {
                sels: [ctx.config.sel.detail],
                title: t('onboarding.detail.meta.title', '③ 简介区'),
                body: t('onboarding.detail.meta.body', '作品标题、简介与标签都在这里。点标签可在画廊里筛选同标签的作品。')
            },
            {
                sels: [ctx.config.sel.author],
                title: t('onboarding.detail.author.title', '④ 作者区'),
                body: t('onboarding.detail.author.body', '作品的作者（画师）信息。点击可查看该作者在本地的其他作品，或跳转其 Pixiv 主页。')
            },
            {
                // 系列面板可能因作品不属于系列而隐藏，此时退而高亮「相关作品」面板
                sels: [ctx.config.sel.series, ctx.config.sel.related],
                title: t('onboarding.detail.series.title', '⑤ 系列 / 相关区'),
                body: t('onboarding.detail.series.body', '若作品属于某个系列，这里可翻阅同系列其他话；下方还会推荐相关作品。')
            }
        ];
    }

    // 解析当前页可见的详情区域步骤（带聚光选择器）
    function resolveDetailSteps() {
        return detailRegions()
            .map(function (r) {
                var sel = firstVisibleSelector(r.sels);
                return sel ? {sel: sel, title: r.title, body: r.body} : null;
            })
            .filter(Boolean);
    }

    function phaseDetail() {
        var doneLabel = t('onboarding.common.done', '完成');
        var runResolved = function () {
            var steps = resolveDetailSteps();
            if (steps.length) {
                runRegionSteps(steps, renderDetailDone, doneLabel);
            } else {
                renderDetailDone();
            }
        };
        // 作品图区一开始就显示「加载中」占位、始终可见，但功能区 / 简介区 / 作者区要等作品数据加载后才渲染。
        // 因此等「简介区」面板出现（此时作品图 / 功能区 / 作者区已一并渲染）再解析步骤，并留一点时间让
        // 系列 / 相关区异步加载完成，避免过早只讲到作品图区。
        waitForElement(function () {
            var el = ctx.config.sel.detail ? document.querySelector(ctx.config.sel.detail) : null;
            return isVisible(el) ? el : null;
        }, function () {
            global.setTimeout(runResolved, 500);
        }, function () {
            // 超时（异常情况）：按当前可见区域尽力讲解，没有可讲再直接完成
            runResolved();
        }, 10000);
    }

    // 通用：逐区域聚光讲解，跑完调用 onComplete。lastLabel 为最后一步主按钮文案；
    // progressFn(idx,total)→进度文案（缺省 "x / total"）。画廊与详情区域讲解共用此渲染器。
    function runRegionSteps(steps, onComplete, lastLabel, progressFn) {
        if (!steps || !steps.length) {
            onComplete();
            return;
        }
        var idx = 0;
        var total = steps.length;
        var prog = progressFn || function (i, n) { return (i + 1) + ' / ' + n; };
        var show = function () {
            var s = steps[idx];
            var isLast = idx === total - 1;
            overlay.render({
                targetSelector: s.sel,
                html:
                    '<h3 class="po-pop-title">' + escapeHtml(s.title) + '</h3>'
                    + '<div class="po-pop-body"><p>' + escapeHtml(s.body) + '</p></div>'
                    + footHtml([
                        SKIP_BTN(),
                        {act: 'next', label: isLast ? lastLabel : t('onboarding.common.next', '下一步'), variant: 'primary'}
                    ], prog(idx, total))
            });
            bindFoot({
                skip: skip,
                next: function () {
                    if (isLast) {
                        onComplete();
                    } else {
                        idx++;
                        show();
                    }
                }
            });
        };
        show();
    }

    function renderDetailDone() {
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.done.title', '🎉 新手指引完成！')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.done.body',
                    '你已经走完了从下载到浏览的完整流程。接下来可以用「快捷获取 / 搜索 / 系列」等方式批量下载更多作品，尽情探索吧！')) + '</p></div>'
                + footHtml([{act: 'done', label: t('onboarding.done.close', '开始使用'), variant: 'primary'}])
        });
        bindFoot({done: finish});
    }

    ctx.gallery = {
        phaseGallery: phaseGallery,
        phaseGalleryRedirect: phaseGalleryRedirect,
        phaseDetail: phaseDetail
    };
})(window);
