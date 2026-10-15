(function (global) {
    'use strict';

    var DEFAULT_INLINE_WRAPPER = 'display:inline-flex;align-items:center;gap:6px;font-size:12px;color:inherit;';
    var DEFAULT_INLINE_SELECT = 'padding:2px 6px;border:1px solid rgba(0,0,0,.2);border-radius:4px;background:#fff;color:#333;cursor:pointer;';

    function cookieString(lang, cookieName) {
        var maxAge = 365 * 24 * 3600;
        var secure = global.location && global.location.protocol === 'https:' ? '; Secure' : '';
        return cookieName + '=' + encodeURIComponent(lang) +
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

        var revision = 0;
        var requestedLang = client.lang;
        var destroyed = false;
        var unsubscribeLanguageChange = null;
        var unsubscribeUpdate = null;

        function observeClient() {
            if (unsubscribeUpdate) unsubscribeUpdate();
            unsubscribeUpdate = typeof client.onUpdate === 'function' ? client.onUpdate(function (updated) {
                if (!destroyed && typeof options.onChange === 'function') options.onChange(updated);
            }) : null;
        }
        observeClient();

        async function applyChange(newLang, notifyOthers) {
            var normalizedLang = global.PixivI18n && typeof global.PixivI18n.normalizeLang === 'function'
                ? global.PixivI18n.normalizeLang(newLang)
                : newLang;
            if (normalizedLang === requestedLang) {
                select.value = client.lang;
                return;
            }
            var request = ++revision;
            requestedLang = normalizedLang;
            try {
                var newClient = await client.setLanguage(normalizedLang);
                if (destroyed || request !== revision) {
                    if (newClient.dispose) newClient.dispose();
                    return;
                }
                if (client.dispose) client.dispose();
                client = newClient;
                requestedLang = client.lang;
                observeClient();
                try {
                    if (client.cookieName) document.cookie = cookieString(client.lang, client.cookieName);
                } catch (e) { /* 存储被禁用时仍可更新当前页面。 */ }
                newClient.apply();
                select.value = newClient.lang;
                if (notifyOthers !== false && global.PixivI18n && typeof global.PixivI18n.notifyLanguageChange === 'function') {
                    global.PixivI18n.notifyLanguageChange(newClient.lang);
                }
                if (typeof options.onChange === 'function') {
                    options.onChange(newClient);
                }
            } catch (failure) {
                if (request === revision) {
                    requestedLang = client.lang;
                    select.value = client.lang;
                }
                global.console.warn('Language change failed', failure);
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
                revision++;
                if (nextClient && nextClient !== client && client.dispose) client.dispose();
                client = nextClient || client;
                requestedLang = client.lang;
                observeClient();
                select.value = client.lang;
            },
            destroy: function () {
                destroyed = true;
                revision++;
                if (unsubscribeUpdate) unsubscribeUpdate();
                if (client.dispose) client.dispose();
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
