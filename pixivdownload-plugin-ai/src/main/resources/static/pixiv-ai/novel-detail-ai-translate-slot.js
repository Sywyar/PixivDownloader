PixivUiSlots.registerModule('/pixiv-ai/novel-detail-ai-translate-slot.js', async function (root, context) {
    'use strict';
    var global = window;
    var pageI18n = context.i18n;
    var toast = (message, kind) => context.emit('message', {message, kind: kind || 'info'});
    var PixivTranslate;
    var languageControl;
    var BASE = '/pixiv-ai/';

    function loadScript(url) { return context.loadScript(url); }
    function loadStyle(url) { context.loadStyle(url); }

    function slotHost() { return root; }

    async function isAdmin() {
        try {
            var res = await context.fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
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
        if (!PixivTranslate) return;
        var currentNovelId = context.properties.novelId;
        if (!currentNovelId) return;
        if (PixivTranslate.hasActiveJob()) {
            PixivTranslate.showActiveJob();
            return;
        }
        var choice = await PixivTranslate.openDialog({
            i18n: pageI18n, series: false, novelId: currentNovelId, onToast: toast
        });
        if (!choice) return;
        var nav = {seriesId: context.properties.seriesId};
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
        if (!await loadScript(BASE + 'pixiv-translate-dialog.js')) return;
        if (!await loadScript(BASE + 'pixiv-content-lang.js')) return;
        if (!await loadScript(BASE + 'pixiv-translate.js')) return;
        PixivTranslate = global.PixivTranslate.create(context);
        context.onCleanup(() => PixivTranslate.destroy());
        languageControl = global.PixivContentLang.mount({
            mountPoint: context.region('content-language'), i18n: pageI18n,
            languages: context.properties.contentLanguages, current: context.properties.contentLanguage,
            onChange: language => context.emit('content-language', {language: language || ''})
        });
        context.onCleanup(() => languageControl?.destroy());
        if (!await isAdmin() || !PixivTranslate || !PixivTranslate.isAiConfigured) return;
        var configured = await PixivTranslate.isAiConfigured();
        if (!configured) return;
        var host = slotHost();
        if (!host) return;
        renderButton(host);
        var btn = document.getElementById('aiTranslateBtn');
        if (btn) {
            btn.style.display = '';
            context.on(btn, 'click', openTranslateDialog);
        }
    }

    await init();
    return {update() { pageI18n = context.i18n; languageControl?.relabel(pageI18n); languageControl?.setLanguages(context.properties.contentLanguages, context.properties.contentLanguage); }, destroy() { root.replaceChildren(); }};
});
