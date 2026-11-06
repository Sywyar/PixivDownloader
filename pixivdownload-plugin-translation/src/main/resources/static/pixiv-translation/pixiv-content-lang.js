/* 小说内容语言切换器；独立于界面语言。 */
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

    function languageLabel(language) {
        var locale = document.documentElement.lang || 'en';
        try { language = new Intl.DisplayNames([locale], { type: 'language' }).of(language) || language; } catch (_) {}
        return language;
    }

    function engineOf(code) { return code.split('@')[1] || 'unknown'; }

    function engineLabel(engine, i18n) {
        return ['ai', 'youdao', 'libretranslate', 'unknown'].indexOf(engine) >= 0
            ? tt(i18n, 'switcher.engine.' + engine, engine) : engine;
    }

    function variantLabel(code, i18n) {
        return languageLabel(code.split('@')[0]) + ' · ' + engineLabel(engineOf(code), i18n);
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
        wrap.setAttribute('role', 'group');
        function field(className) {
            var label = document.createElement('label');
            label.className = 'pt-version-field ' + className;
            var caption = document.createElement('span');
            caption.className = 'pt-version-label';
            var select = document.createElement('select');
            select.className = 'pt-lang-select';
            label.appendChild(caption);
            label.appendChild(select);
            wrap.appendChild(label);
            return {caption: caption, select: select};
        }
        var language = field('pt-version-language');
        var engine = field('pt-version-engine');
        mountPoint.appendChild(wrap);

        var current = opts.current || '';
        var variants = [];
        var onChange = typeof opts.onChange === 'function' ? opts.onChange : function () {};

        function option(select, value, label) {
            var item = document.createElement('option');
            item.value = value;
            item.textContent = label;
            select.appendChild(item);
        }

        function rebuild(languages, cur) {
            variants = Array.isArray(languages) ? languages.filter(Boolean) : [];
            var want = cur != null ? cur : current;
            current = variants.indexOf(want) >= 0 ? want : '';
            var selectedLanguage = current.split('@')[0];
            wrap.setAttribute('aria-label', tt(i18n, 'switcher.title', 'Reading version'));
            language.caption.textContent = tt(i18n, 'switcher.language', 'Reading language');
            engine.caption.textContent = tt(i18n, 'switcher.engine', 'Translation engine');
            language.select.title = language.caption.textContent;
            engine.select.title = engine.caption.textContent;
            language.select.innerHTML = '';
            engine.select.innerHTML = '';
            option(language.select, '', tt(i18n, 'switcher.original', 'Original'));
            var seen = new Set();
            variants.forEach(function (code) {
                var lang = code.split('@')[0];
                if (!seen.has(lang)) {
                    option(language.select, lang, languageLabel(lang));
                    seen.add(lang);
                }
                if (lang === selectedLanguage) option(engine.select, engineOf(code), engineLabel(engineOf(code), i18n));
            });
            if (!current) option(engine.select, '', tt(i18n, 'switcher.not-applicable', 'Not applicable'));
            language.select.value = selectedLanguage;
            engine.select.value = current ? engineOf(current) : '';
            engine.select.disabled = !current;
            wrap.hidden = !variants.length;
        }

        function change(next) {
            rebuild(variants, next);
            writeStoredContentLang(current);
            onChange(current || null);
        }

        language.select.addEventListener('change', function () {
            var matches = variants.filter(function (code) { return code.split('@')[0] === language.select.value; });
            var next = matches.find(function (code) { return engineOf(code) === engineOf(current); });
            change(next || matches[0] || '');
        });
        engine.select.addEventListener('change', function () {
            change(variants.find(function (code) {
                return code.split('@')[0] === language.select.value && engineOf(code) === engine.select.value;
            }) || '');
        });

        rebuild(opts.languages, opts.current);

        return {
            destroy: function () { wrap.remove(); },
            element: wrap,
            getValue: function () { return current || ''; },
            setLanguages: function (languages, cur) { rebuild(languages, cur); },
            relabel: function (nextI18n) {
                i18n = nextI18n || i18n;
                rebuild(variants, current);
            }
        };
    }

    global.PixivContentLang = {
        mount: mountContentLang,
        label: variantLabel,
        getStored: readStoredContentLang,
        setStored: writeStoredContentLang,
        STORAGE_KEY: CONTENT_LANG_STORAGE_KEY
    };
})(window);
