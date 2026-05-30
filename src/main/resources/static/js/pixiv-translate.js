/*
 * 小说 AI 翻译共享模块：翻译弹窗 + 翻译/合订接口 + 内容语言切换器。
 * 由 pixiv-novel.html 与 pixiv-series.html 共用，保证两端弹窗与交互一致。
 *
 * 依赖：调用方提供已加载 'translate'（+'common'）命名空间的 PixivI18n 实例。
 * 所有面向用户的字符串走 i18n（translate: 命名空间），不写死单一语言。
 */
(function (global) {
    'use strict';

    var CONTENT_LANG_STORAGE_KEY = 'pixiv.contentLang';

    function tt(i18n, key, fallback, vars) {
        if (i18n && typeof i18n.t === 'function') {
            return i18n.t('translate:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (m, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m;
        });
    }

    // ── 翻译弹窗（单例，懒创建）─────────────────────────────────────────────────
    var dialogEl = null;
    var dialogRefs = null;

    function buildDialog() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title"></span>' +
            '    <button class="pt-close" type="button" aria-label="close">×</button>' +
            '  </div>' +
            '  <div class="pt-body">' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-lang-label"></span>' +
            '      <input class="pt-input pt-lang-input" type="text" maxlength="60">' +
            '    </label>' +
            '    <div class="pt-hint pt-lang-hint"></div>' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-seg-label"></span>' +
            '      <input class="pt-input pt-seg-input" type="number" min="0" step="500" value="0">' +
            '    </label>' +
            '    <div class="pt-hint pt-seg-hint"></div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-existing-label"></span>' +
            '      <div class="pt-radio-row">' +
            '        <label class="pt-radio"><input type="radio" name="ptOverwrite" value="overwrite"><span class="pt-ow-label"></span></label>' +
            '        <label class="pt-radio"><input type="radio" name="ptOverwrite" value="skip"><span class="pt-sk-label"></span></label>' +
            '      </div>' +
            '    </div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-cancel" type="button"></button>' +
            '    <button class="pt-btn pt-btn-primary pt-confirm" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        dialogRefs = {
            backdrop: backdrop,
            modal: backdrop.querySelector('.pt-modal'),
            title: backdrop.querySelector('.pt-title'),
            close: backdrop.querySelector('.pt-close'),
            langLabel: backdrop.querySelector('.pt-lang-label'),
            langInput: backdrop.querySelector('.pt-lang-input'),
            langHint: backdrop.querySelector('.pt-lang-hint'),
            segLabel: backdrop.querySelector('.pt-seg-label'),
            segInput: backdrop.querySelector('.pt-seg-input'),
            segHint: backdrop.querySelector('.pt-seg-hint'),
            existingLabel: backdrop.querySelector('.pt-existing-label'),
            owLabel: backdrop.querySelector('.pt-ow-label'),
            skLabel: backdrop.querySelector('.pt-sk-label'),
            cancel: backdrop.querySelector('.pt-cancel'),
            confirm: backdrop.querySelector('.pt-confirm')
        };
        return backdrop;
    }

    /**
     * 打开翻译弹窗。返回 Promise，确认时 resolve { targetLanguage, segmentSize, overwrite }，取消时 resolve null。
     * @param opts { i18n, series:boolean }
     */
    function openDialog(opts) {
        opts = opts || {};
        var i18n = opts.i18n;
        var series = !!opts.series;
        if (!dialogEl) {
            dialogEl = buildDialog();
        }
        var r = dialogRefs;
        r.title.textContent = series
            ? tt(i18n, 'dialog.title-series', 'Translate whole series')
            : tt(i18n, 'dialog.title', 'AI Translate');
        r.langLabel.textContent = tt(i18n, 'dialog.language-label', 'Target language');
        r.langHint.textContent = tt(i18n, 'dialog.language-hint', '');
        r.segLabel.textContent = tt(i18n, 'dialog.segment-label', 'Segment size');
        r.segHint.textContent = tt(i18n, 'dialog.segment-hint', '');
        r.existingLabel.textContent = tt(i18n, 'dialog.existing-label', 'When already translated');
        r.owLabel.textContent = tt(i18n, 'dialog.existing-overwrite', 'Overwrite');
        r.skLabel.textContent = tt(i18n, 'dialog.existing-skip', 'Skip');
        r.cancel.textContent = tt(i18n, 'dialog.cancel', 'Cancel');
        r.confirm.textContent = series
            ? tt(i18n, 'dialog.confirm-series', 'Translate series')
            : tt(i18n, 'dialog.confirm', 'Start translating');
        r.langInput.value = tt(i18n, 'dialog.language-default', 'english');
        r.segInput.value = '0';
        // 默认：系列批量倾向「跳过已译」，单作品倾向「覆盖」
        var defaultMode = series ? 'skip' : 'overwrite';
        var radios = r.modal.querySelectorAll('input[name="ptOverwrite"]');
        radios.forEach(function (radio) { radio.checked = radio.value === defaultMode; });

        dialogEl.classList.add('open');
        setTimeout(function () { r.langInput.focus(); r.langInput.select(); }, 30);

        return new Promise(function (resolve) {
            function cleanup(result) {
                dialogEl.classList.remove('open');
                r.close.onclick = null;
                r.cancel.onclick = null;
                r.confirm.onclick = null;
                r.backdrop.onclick = null;
                document.removeEventListener('keydown', onKey);
                resolve(result);
            }
            function confirmChoice() {
                var lang = r.langInput.value.trim();
                if (!lang) { r.langInput.focus(); return; }
                var seg = parseInt(r.segInput.value, 10);
                if (!Number.isFinite(seg) || seg < 0) seg = 0;
                var modeEl = r.modal.querySelector('input[name="ptOverwrite"]:checked');
                var overwrite = !modeEl || modeEl.value === 'overwrite';
                cleanup({ targetLanguage: lang, segmentSize: seg, overwrite: overwrite });
            }
            function onKey(e) {
                if (e.key === 'Escape') cleanup(null);
                else if (e.key === 'Enter' && document.activeElement === r.langInput) confirmChoice();
            }
            r.close.onclick = function () { cleanup(null); };
            r.cancel.onclick = function () { cleanup(null); };
            r.confirm.onclick = confirmChoice;
            r.backdrop.onclick = function (e) { if (e.target === r.backdrop) cleanup(null); };
            document.addEventListener('keydown', onKey);
        });
    }

    // ── 后端接口 ─────────────────────────────────────────────────────────────────

    async function translateNovel(novelId, opts) {
        opts = opts || {};
        var res = await fetch('/api/gallery/novel/' + encodeURIComponent(novelId) + '/translate', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                targetLanguage: opts.targetLanguage,
                segmentSize: opts.segmentSize == null ? 0 : opts.segmentSize,
                overwrite: !!opts.overwrite,
                langHint: opts.langHint || null
            })
        });
        if (!res.ok) {
            var msg = 'HTTP ' + res.status;
            try {
                var j = await res.json();
                if (j && (j.error || j.message)) msg = j.error || j.message;
            } catch (_) {}
            throw new Error(msg);
        }
        return res.json();
    }

    async function mergeSeriesLang(seriesId, langCode, format) {
        var params = new URLSearchParams();
        params.set('format', format || 'epub');
        if (langCode) params.set('lang', langCode);
        var res = await fetch('/api/gallery/novel/series/' + encodeURIComponent(seriesId)
            + '/merge?' + params.toString(), { method: 'POST', credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }

    async function fetchSeriesNovelIds(seriesId) {
        var res = await fetch('/api/gallery/novel/series/' + encodeURIComponent(seriesId) + '/novel-ids',
            { credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var data = await res.json();
        return (data && data.novelIds) || [];
    }

    global.PixivTranslate = {
        openDialog: openDialog,
        translateNovel: translateNovel,
        mergeSeriesLang: mergeSeriesLang,
        fetchSeriesNovelIds: fetchSeriesNovelIds,
        STATUS_OK: 'OK',
        STATUS_SKIPPED: 'SKIPPED',
        STATUS_INVALID_LANGUAGE: 'INVALID_LANGUAGE'
    };

    // ── 内容语言切换器（独立于界面语言，localStorage 全局记忆）──────────────────────
    function readStoredContentLang() {
        try { return localStorage.getItem(CONTENT_LANG_STORAGE_KEY) || ''; } catch (_) { return ''; }
    }
    function writeStoredContentLang(lang) {
        try {
            if (lang) localStorage.setItem(CONTENT_LANG_STORAGE_KEY, lang);
            else localStorage.removeItem(CONTENT_LANG_STORAGE_KEY);
        } catch (_) {}
    }

    /**
     * 挂载内容语言切换器。返回控制器 { setLanguages, getValue, element }。
     * @param opts { mountPoint, i18n, languages:[code], current, onChange(langOrNull) }
     */
    function mountContentLang(opts) {
        opts = opts || {};
        var mountPoint = opts.mountPoint;
        if (!mountPoint) return null;
        var i18n = opts.i18n;
        var wrap = document.createElement('span');
        wrap.className = 'pt-lang-switch';
        wrap.title = tt(i18n, 'switcher.title', '');
        var select = document.createElement('select');
        select.className = 'pt-lang-select';
        wrap.appendChild(select);
        mountPoint.appendChild(wrap);

        var current = opts.current || '';
        var onChange = typeof opts.onChange === 'function' ? opts.onChange : function () {};

        function rebuild(languages, cur) {
            var langs = Array.isArray(languages) ? languages.slice() : [];
            select.innerHTML = '';
            var optOriginal = document.createElement('option');
            optOriginal.value = '';
            optOriginal.textContent = tt(i18n, 'switcher.original', 'Original');
            select.appendChild(optOriginal);
            langs.forEach(function (code) {
                if (!code) return;
                var o = document.createElement('option');
                o.value = code;
                o.textContent = code;
                select.appendChild(o);
            });
            // 选定值：优先入参 cur，否则当前 current（若仍可用），否则原文
            var want = (cur != null) ? cur : current;
            if (want && langs.indexOf(want) === -1) want = '';
            current = want || '';
            select.value = current;
            wrap.style.display = langs.length ? '' : 'none';
        }

        select.addEventListener('change', function () {
            current = select.value || '';
            writeStoredContentLang(current);
            onChange(current || null);
        });

        rebuild(opts.languages, opts.current);

        return {
            element: wrap,
            getValue: function () { return current || ''; },
            setLanguages: function (languages, cur) { rebuild(languages, cur); },
            relabel: function (nextI18n) {
                i18n = nextI18n || i18n;
                wrap.title = tt(i18n, 'switcher.title', '');
                var sel = select.value;
                rebuild(Array.prototype.slice.call(select.options).slice(1).map(function (o) { return o.value; }), sel);
            }
        };
    }

    global.PixivContentLang = {
        mount: mountContentLang,
        getStored: readStoredContentLang,
        setStored: writeStoredContentLang,
        STORAGE_KEY: CONTENT_LANG_STORAGE_KEY
    };
})(window);
