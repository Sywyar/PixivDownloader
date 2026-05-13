(function (global) {
    'use strict';

    var DEFAULT_INLINE_WRAPPER = 'display:inline-flex;align-items:center;gap:6px;font-size:12px;color:inherit;';
    var DEFAULT_INLINE_SELECT = 'padding:2px 6px;border:1px solid rgba(0,0,0,.2);border-radius:4px;background:#fff;color:#333;cursor:pointer;';

    function cookieString(lang) {
        var maxAge = 365 * 24 * 3600;
        var secure = global.location && global.location.protocol === 'https:' ? '; Secure' : '';
        return 'pixiv_lang=' + encodeURIComponent(lang) +
            '; path=/; max-age=' + maxAge + '; SameSite=Lax' + secure;
    }

    async function mount(options) {
        var mountPoint = options && options.mountPoint;
        var client = options && options.i18n;
        if (!mountPoint) {
            throw new Error('mountPoint is required');
        }
        if (!client) {
            throw new Error('i18n client is required');
        }

        var variant = (options && options.variant) || 'default';
        var wrapper = document.createElement('span');
        wrapper.className = 'pixiv-lang-switcher pixiv-lang-switcher--' + variant;
        if (variant === 'default') {
            wrapper.style.cssText = DEFAULT_INLINE_WRAPPER;
        }

        var select = document.createElement('select');
        select.className = 'pixiv-lang-switcher__select';
        if (variant === 'default') {
            select.style.cssText = DEFAULT_INLINE_SELECT;
        }

        (client.supportedLocales || []).forEach(function (item) {
            var option = document.createElement('option');
            option.value = item.tag;
            option.textContent = item.displayName || item.label || item.name || item.tag;
            if (item.tag === client.lang) {
                option.selected = true;
            }
            select.appendChild(option);
        });
        wrapper.appendChild(select);

        var applyingLanguageChange = false;
        var unsubscribeLanguageChange = null;

        async function applyChange(newLang, notifyOthers) {
            var normalizedLang = global.PixivI18n && typeof global.PixivI18n.normalizeLang === 'function'
                ? global.PixivI18n.normalizeLang(newLang)
                : newLang;
            if (applyingLanguageChange) {
                return;
            }
            if (normalizedLang === client.lang) {
                select.value = client.lang;
                return;
            }
            applyingLanguageChange = true;
            try {
                document.cookie = cookieString(normalizedLang);
            } catch (e) {
                // Ignore cookie failures.
            }
            try {
                var newClient = await client.setLanguage(normalizedLang);
                newClient.apply();
                select.value = newClient.lang;
                client = newClient;
                if (notifyOthers !== false && global.PixivI18n && typeof global.PixivI18n.notifyLanguageChange === 'function') {
                    global.PixivI18n.notifyLanguageChange(newClient.lang);
                }
                if (typeof options.onChange === 'function') {
                    options.onChange(newClient);
                }
            } finally {
                applyingLanguageChange = false;
            }
        }

        select.addEventListener('change', function () {
            applyChange(select.value, true);
        });

        if (global.PixivI18n && typeof global.PixivI18n.onLanguageChange === 'function') {
            unsubscribeLanguageChange = global.PixivI18n.onLanguageChange(function (payload) {
                if (!payload || !payload.lang) {
                    return;
                }
                applyChange(payload.lang, false);
            });
        }

        mountPoint.appendChild(wrapper);
        return {
            element: wrapper,
            refresh: function (nextClient) {
                client = nextClient || client;
                select.value = client.lang;
            },
            destroy: function () {
                if (typeof unsubscribeLanguageChange === 'function') {
                    unsubscribeLanguageChange();
                    unsubscribeLanguageChange = null;
                }
                if (wrapper.parentNode) {
                    wrapper.parentNode.removeChild(wrapper);
                }
            }
        };
    }

    global.PixivLangSwitcher = {
        mount: mount
    };
})(window);
