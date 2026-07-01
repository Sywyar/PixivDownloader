(function (global) {
    'use strict';

    var loaded = {};
    var BASE = '/pixiv-ai/';

    function loadScript(url) {
        if (loaded[url]) return loaded[url];
        loaded[url] = new Promise(function (resolve) {
            var script = document.createElement('script');
            script.src = url;
            script.async = false;
            script.onload = function () { resolve(true); };
            script.onerror = function () {
                console.warn('[AI] script load failed:', url);
                resolve(false);
            };
            (document.head || document.documentElement).appendChild(script);
        });
        return loaded[url];
    }

    function loadStyle(url) {
        if (document.querySelector('link[href="' + url + '"]')) return;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = url;
        document.head.appendChild(link);
    }

    function slotHost() {
        if (global.PixivVue && typeof global.PixivVue.anchorFor === 'function') {
            return global.PixivVue.anchorFor('novel-detail-ai-translate');
        }
        return document.querySelector('[data-vue-slot="novel-detail-ai-translate"]')
                || document.getElementById('novel-detail-ai-translate');
    }

    async function isAdmin() {
        try {
            var res = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
            return res.ok;
        } catch (_) {
            return false;
        }
    }

    function renderButton(host) {
        host.innerHTML = '<button class="btn" id="aiTranslateBtn" type="button" style="display:none" '
                + 'data-i18n="translate:button.translate">AI Translate</button>';
        if (typeof pageI18n !== 'undefined' && pageI18n) {
            try { pageI18n.apply(host); } catch (_) {}
        }
    }

    async function openTranslateDialog() {
        if (!global.PixivTranslate) return;
        var currentNovelId = global.PixivNovel && global.PixivNovel.core
                ? global.PixivNovel.core.novelId : '';
        if (!currentNovelId) return;
        if (PixivTranslate.hasActiveJob()) {
            PixivTranslate.showActiveJob();
            return;
        }
        var choice = await PixivTranslate.openDialog({
            i18n: pageI18n, series: false, novelId: currentNovelId, onToast: toast
        });
        if (!choice) return;
        var nav = global.PixivNovel && global.PixivNovel.series
                && typeof global.PixivNovel.series.getCachedSeriesNav === 'function'
                ? global.PixivNovel.series.getCachedSeriesNav() : null;
        var outcome = await PixivTranslate.runSingleNovel({
            i18n: pageI18n,
            novelId: currentNovelId,
            choice: choice,
            seriesId: nav && nav.seriesId ? nav.seriesId : null
        });
        if (!outcome || outcome.cancelled) return;
        if (outcome.error) {
            toast(pageI18n.t('translate:toast.failed', 'Translation failed: {message}',
                { message: String(outcome.error.message || outcome.error) }), 'error');
            return;
        }
        if (outcome.mergeFailed) {
            toast(pageI18n.t('translate:toast.merge-failed', 'Merged volume generation failed: {message}',
                { message: String(outcome.mergeFailed.message || outcome.mergeFailed) }), 'error');
        }
        var resp = outcome.result;
        if (resp.status === PixivTranslate.STATUS_INVALID_LANGUAGE) {
            toast(resp.message || pageI18n.t('translate:toast.invalid-language',
                'The language does not exist or could not be recognized'), 'error');
        } else if (resp.status === PixivTranslate.STATUS_SAME_LANGUAGE) {
            toast(resp.message || pageI18n.t('translate:toast.same-language',
                'The source is already in the target language; skipped'), 'success');
        } else if (resp.status === PixivTranslate.STATUS_OK || resp.status === PixivTranslate.STATUS_SKIPPED) {
            toast(resp.message || pageI18n.t('translate:toast.success', 'Translation complete'), 'success');
            if (resp.langCode) {
                if (global.PixivContentLang) PixivContentLang.setStored(resp.langCode);
                var params = new URLSearchParams(location.search);
                params.set('lang', resp.langCode);
                location.search = params.toString();
            }
        } else {
            toast(resp.message || pageI18n.t('translate:toast.failed', 'Translation failed: {message}',
                { message: '' }), 'error');
        }
    }

    async function init() {
        loadStyle(BASE + 'pixiv-translate.css');
        await loadScript(BASE + 'pixiv-translate.js');
        if (global.PixivNovel && global.PixivNovel.content
                && typeof global.PixivNovel.content.mountContentLangSwitcher === 'function') {
            global.PixivNovel.content.mountContentLangSwitcher();
        }
        if (!await isAdmin() || !global.PixivTranslate || !PixivTranslate.isAiConfigured) return;
        var configured = await PixivTranslate.isAiConfigured();
        if (!configured) return;
        var host = slotHost();
        if (!host) return;
        renderButton(host);
        var btn = document.getElementById('aiTranslateBtn');
        if (btn) {
            btn.style.display = '';
            btn.addEventListener('click', openTranslateDialog);
        }
    }

    init();
})(window);
