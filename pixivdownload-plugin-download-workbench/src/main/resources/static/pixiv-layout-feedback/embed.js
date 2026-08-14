(function (global) {
    'use strict';

    var statusElement = null;
    var i18n = null;
    var notificationId = '';
    var opened = false;
    var lastReportedHeight = 0;
    var storage = null;

    function t(key, fallback) {
        return i18n ? i18n.t('layout-feedback:' + key, fallback) : fallback;
    }

    function showStatus(key, fallback) {
        if (!statusElement) return;
        statusElement.hidden = false;
        statusElement.textContent = t(key, fallback);
    }

    function reportHeight() {
        var content = global.document.querySelector('.plf-backdrop') || statusElement;
        if (!content) return;
        var height = Math.ceil(Math.max(content.scrollHeight, content.getBoundingClientRect().height));
        if (height <= 0 || height === lastReportedHeight) return;
        lastReportedHeight = height;
        global.PixivSurveyFrameBridge.post({type: 'pixiv-content-height', height: height});
    }

    function currentLayoutId() {
        var stored = null;
        try { stored = storage.getItem('pixiv:batch-layout:v1'); } catch (_) { stored = null; }
        if (stored === 'landscape') return 'pixiv-batch-landscape';
        if (stored === 'portrait') return 'pixiv-batch-portrait';
        return 'pixiv-batch-alt';
    }

    function watchCompletion() {
        if (typeof global.MutationObserver !== 'function') return;
        new global.MutationObserver(function () {
            if (opened && !global.document.querySelector('.plf-backdrop')) {
                opened = false;
                showStatus('embed-completed', '该调查已完成或已在其他页面处理。');
            }
            reportHeight();
        }).observe(global.document.body, {childList: true, subtree: true});
    }

    global.document.addEventListener('DOMContentLoaded', async function () {
        statusElement = global.document.getElementById('layoutSurveyEmbedStatus');
        var params = new URLSearchParams(global.location.search);
        notificationId = params.get('notificationId') || '';
        try {
            var bridge = await global.PixivSurveyFrameBridge.ready();
            storage = bridge.storage;
            if (global.PixivTheme) {
                global.PixivTheme.apply(storage.getItem('pixiv_theme'), false, false);
            }
            i18n = await global.PixivI18n.create({
                namespaces: ['layout-feedback'],
                lang: params.get('lang') || undefined
            });
            showStatus('embed-loading', '正在加载调查…');
            watchCompletion();
            global.PixivLayoutFeedback.init({
                page: 'embedded',
                currentLayoutId: currentLayoutId(),
                i18n: i18n,
                storage: storage,
                fetchImpl: global.fetch
            });
            var result = await global.PixivLayoutFeedback.openEmbedded();
            if (result && result.status === 'opened') {
                opened = true;
                statusElement.hidden = true;
            } else if (result && result.status === 'removed') {
                showStatus('embed-unavailable', '该调查已结束。');
                global.PixivSurveyFrameBridge.post({
                    type: 'pixiv-survey-unavailable',
                    notificationId: notificationId
                });
            } else if (result && (result.status === 'ineligible' || result.status === 'blocked')) {
                showStatus('embed-completed', '该调查已完成或当前无需填写。');
            } else {
                showStatus('embed-temporarily-unavailable', '调查暂时无法加载，请稍后重试。');
            }
        } catch (_) {
            showStatus('embed-temporarily-unavailable', '调查暂时无法加载，请稍后重试。');
        }
        reportHeight();
        if (typeof global.ResizeObserver === 'function') {
            var heightTarget = global.document.querySelector('.plf-backdrop') || statusElement;
            if (heightTarget) new global.ResizeObserver(reportHeight).observe(heightTarget);
        }
    });
})(window);
