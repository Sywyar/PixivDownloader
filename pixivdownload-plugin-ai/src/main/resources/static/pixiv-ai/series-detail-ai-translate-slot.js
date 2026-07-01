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
            return global.PixivVue.anchorFor('series-detail-ai-translate');
        }
        return document.querySelector('[data-vue-slot="series-detail-ai-translate"]')
                || document.getElementById('series-detail-ai-translate');
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
        if (!global.PixivTranslate || typeof state === 'undefined' || !state.seriesId
                || typeof isNovelMode !== 'function' || !isNovelMode()) return;
        if (PixivTranslate.hasActiveJob()) {
            PixivTranslate.showActiveJob();
            return;
        }
        var choice = await PixivTranslate.openDialog({
            i18n: pageI18n,
            series: true,
            seriesId: state.seriesId,
            onToast: function (msg) { setSeriesMessage(msg); }
        });
        if (!choice) return;
        var result = await PixivTranslate.runSeries({
            i18n: pageI18n,
            seriesId: state.seriesId,
            choice: choice
        });
        if (!result) return;
        if (result.error) {
            setSeriesMessage(tx('toast.failed', 'Translation failed: {message}',
                { message: String(result.error.message || result.error) }));
            return;
        }
        if (result.empty) {
            setSeriesMessage(tx('toast.no-chapters', 'This series has no translatable chapters'));
            return;
        }
        if (result.invalid) {
            setSeriesMessage(tx('toast.invalid-language',
                'AI determined that the language does not exist or could not be recognized'));
            return;
        }
        if (result.mergeFailed) {
            setSeriesMessage(tx('toast.merge-failed', 'Merged volume generation failed: {message}',
                { message: String(result.mergeFailed.message || result.mergeFailed) }));
        }
        var refreshFailed = false;
        if (result.langCode) {
            if (seriesTranslatedLangs.indexOf(result.langCode) === -1) {
                seriesTranslatedLangs = seriesTranslatedLangs.concat([result.langCode]);
            }
            activeContentLang = result.langCode;
            if (global.PixivContentLang) PixivContentLang.setStored(result.langCode);
            if (contentLangCtl) contentLangCtl.setLanguages(seriesTranslatedLangs, result.langCode);
            else setupNovelContentControls(seriesTranslatedLangs);
            delete seriesTitleByLang[activeContentLang];
            delete seriesDescriptionByLang[activeContentLang];
            delete chapterTitlesByLang[activeContentLang];
            try {
                await Promise.all([
                    fetchSeriesTitleForLang(activeContentLang),
                    fetchChapterTitlesForLang(activeContentLang, state.items)
                ]);
            } catch (err) {
                refreshFailed = true;
                console.warn(t('log.post-translate-refresh-failed', '译文刷新失败'), err);
            }
            renderSeriesHeader();
            renderGrid(state.items);
        }
        if (refreshFailed) {
            setSeriesMessage(tx('toast.series-done-refresh-failed',
                'Series translation complete: {ok} done, {skipped} skipped, {failed} failed; translated titles failed to refresh, switch language again to retry',
                { ok: result.ok, skipped: result.skipped, failed: result.failed }));
        } else {
            setSeriesMessage(tx('toast.series-done',
                'Series translation complete: {ok} done, {skipped} skipped, {failed} failed',
                { ok: result.ok, skipped: result.skipped, failed: result.failed }));
        }
    }

    async function init() {
        loadStyle(BASE + 'pixiv-translate.css');
        await loadScript(BASE + 'pixiv-translate.js');
        if (typeof setupNovelContentControls === 'function') {
            setupNovelContentControls();
        }
        if (!await isAdmin() || !global.PixivTranslate || !PixivTranslate.isAiConfigured) return;
        var configured = await PixivTranslate.isAiConfigured();
        if (!configured) return;
        var host = slotHost();
        if (!host) return;
        renderButton(host);
        var btn = document.getElementById('translateSeriesBtn');
        if (btn) {
            btn.addEventListener('click', translateSeriesWithAi);
            if (typeof isNovelMode === 'function' && isNovelMode()) btn.style.display = '';
        }
    }

    init();
})(window);
