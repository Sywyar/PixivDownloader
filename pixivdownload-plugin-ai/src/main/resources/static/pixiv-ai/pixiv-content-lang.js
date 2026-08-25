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
