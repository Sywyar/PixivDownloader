PixivUiSlots.registerModule('/pixiv-ai/series-detail-ai-translate-slot.js', async function (root, context) {
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
        host.innerHTML = '<button class="btn" id="translateSeriesBtn" type="button" style="display:none" '
                + 'data-i18n="translate:button.translate-series">Translate series</button>';
        if (typeof pageI18n !== 'undefined' && pageI18n) {
            try { pageI18n.apply(host); } catch (_) {}
        }
    }

    function tx(key, fallback, vars) {
        if (typeof pageI18n !== 'undefined' && pageI18n) {
            return pageI18n.t('translate:' + key, fallback, vars);
        }
        return fallback || key;
    }

    async function translateSeriesWithAi() {
        if (!PixivTranslate || !context.properties.seriesId || !context.properties.novel) return;
        if (PixivTranslate.hasActiveJob()) {
            PixivTranslate.showActiveJob();
            return;
        }
        var choice = await PixivTranslate.openDialog({
            i18n: pageI18n,
            series: true,
            seriesId: context.properties.seriesId,
            onToast: function (msg) { toast(msg); }
        });
        if (!choice) return;
        var result = await PixivTranslate.runSeries({
            i18n: pageI18n,
            seriesId: context.properties.seriesId,
            choice: choice
        });
        if (!result) return;
        if (result.error) {
            toast(tx('toast.failed', 'Translation failed: {message}',
                { message: String(result.error.message || result.error) }));
            return;
        }
        if (result.empty) {
            toast(tx('toast.no-chapters', 'This series has no translatable chapters'));
            return;
        }
        if (result.invalid) {
            toast(tx('toast.invalid-language',
                'AI determined that the language does not exist or could not be recognized'));
            return;
        }
        if (result.mergeFailed) {
            toast(tx('toast.merge-failed', 'Merged volume generation failed: {message}',
                { message: String(result.mergeFailed.message || result.mergeFailed) }));
        }
        var refreshFailed = false;
        if (result.langCode) refreshFailed = !!await context.emit('translated', {langCode: result.langCode});
        if (refreshFailed) {
            toast(tx('toast.series-done-refresh-failed',
                'Series translation complete: {ok} done, {skipped} skipped, {failed} failed; translated titles failed to refresh, switch language again to retry',
                { ok: result.ok, skipped: result.skipped, failed: result.failed }));
        } else {
            toast(tx('toast.series-done',
                'Series translation complete: {ok} done, {skipped} skipped, {failed} failed',
                { ok: result.ok, skipped: result.skipped, failed: result.failed }));
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
        var btn = document.getElementById('translateSeriesBtn');
        if (btn) {
            context.on(btn, 'click', translateSeriesWithAi);
            if (context.properties.novel) btn.style.display = '';
        }
    }

    await init();
    return {update() { pageI18n = context.i18n; languageControl?.relabel(pageI18n); languageControl?.setLanguages(context.properties.contentLanguages, context.properties.contentLanguage); }, destroy() { root.replaceChildren(); }};
});
