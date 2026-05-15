(function (global) {
    'use strict';

    var STORAGE_KEY = 'pixiv.lang';
    var DEFAULT_NAMESPACE = 'common';
    // Static fallback bundles, used when the backend i18n API is unreachable
    // (e.g. the GitHub Pages demo site has no Spring backend). Relative path so
    // it resolves correctly under a project-site /<repo>/ prefix.
    var STATIC_BUNDLE_BASE = 'i18n-static/';
    var LANGUAGE_CHANNEL_NAME = 'pixiv.language';
    var LANGUAGE_CHANGE_TYPE = 'language-change';
    var INSTANCE_ID = String(Date.now()) + '-' + String(Math.random()).slice(2);
    var languageChannel = null;
    var languageChannelInitialized = false;
    var storageListenerInitialized = false;
    var languageChangeListeners = [];

    function buildFallbackMeta(preferredLang) {
        var lang = normalizeLang(preferredLang);
        return {
            currentLang: lang,
            defaultLang: 'en-US',
            cookieName: 'pixiv_lang',
            parameterName: 'lang',
            supportedLocales: [
                { tag: 'en-US', displayName: 'English' },
                { tag: 'zh-CN', displayName: '简体中文' }
            ]
        };
    }

    function normalizeLang(lang) {
        if (!lang) {
            return 'en-US';
        }
        return String(lang).trim().replace('_', '-');
    }

    function readStoredLang() {
        try {
            return global.localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            return null;
        }
    }

    function writeStoredLang(lang) {
        try {
            global.localStorage.setItem(STORAGE_KEY, lang);
        } catch (e) {
            // Ignore storage failures.
        }
    }

    function emitLanguageChange(payload) {
        languageChangeListeners.slice().forEach(function (listener) {
            try {
                listener(payload);
            } catch (e) {
                // Keep one page callback from blocking the rest.
            }
        });
    }

    function ensureLanguageChannel() {
        if (languageChannelInitialized) {
            return languageChannel;
        }
        languageChannelInitialized = true;
        if (!global.BroadcastChannel) {
            return null;
        }
        try {
            languageChannel = new global.BroadcastChannel(LANGUAGE_CHANNEL_NAME);
            languageChannel.onmessage = function (event) {
                var payload = event && event.data ? event.data : {};
                if (payload.type !== LANGUAGE_CHANGE_TYPE || payload.source === INSTANCE_ID || !payload.lang) {
                    return;
                }
                emitLanguageChange({
                    type: LANGUAGE_CHANGE_TYPE,
                    lang: normalizeLang(payload.lang),
                    source: payload.source || 'broadcast'
                });
            };
        } catch (e) {
            languageChannel = null;
        }
        return languageChannel;
    }

    function ensureStorageListener() {
        if (storageListenerInitialized || !global.addEventListener) {
            return;
        }
        storageListenerInitialized = true;
        global.addEventListener('storage', function (event) {
            if (!event || event.key !== STORAGE_KEY || !event.newValue) {
                return;
            }
            emitLanguageChange({
                type: LANGUAGE_CHANGE_TYPE,
                lang: normalizeLang(event.newValue),
                source: 'storage'
            });
        });
    }

    function notifyLanguageChange(lang) {
        var normalizedLang = normalizeLang(lang);
        var payload = {
            type: LANGUAGE_CHANGE_TYPE,
            lang: normalizedLang,
            source: INSTANCE_ID
        };
        var channel = ensureLanguageChannel();
        if (channel) {
            try {
                channel.postMessage(payload);
            } catch (e) {
                // The storage event remains as a fallback.
            }
        }
    }

    function onLanguageChange(listener) {
        if (typeof listener !== 'function') {
            return function () {};
        }
        ensureLanguageChannel();
        ensureStorageListener();
        languageChangeListeners.push(listener);
        return function () {
            languageChangeListeners = languageChangeListeners.filter(function (item) {
                return item !== listener;
            });
        };
    }

    async function fetchJson(url) {
        var response = await global.fetch(url, { credentials: 'same-origin' });
        var payload = null;
        try {
            payload = await response.json();
        } catch (e) {
            payload = null;
        }
        if (!response.ok) {
            var message = payload && payload.error ? payload.error : response.statusText;
            throw new Error(message || 'Request failed');
        }
        return payload || {};
    }

    async function fetchJsonOrDefault(url, fallbackValue) {
        try {
            return await fetchJson(url);
        } catch (e) {
            return fallbackValue;
        }
    }

    async function fetchMessagesBundle(namespace, lang) {
        try {
            return await fetchJson(
                '/api/i18n/messages/' + encodeURIComponent(namespace) + '?lang=' + encodeURIComponent(lang)
            );
        } catch (e) {
            // Backend unavailable (static hosting): fall back to a prebuilt bundle.
            var staticUrl = STATIC_BUNDLE_BASE +
                encodeURIComponent(namespace) + '.' + encodeURIComponent(lang) + '.json';
            return await fetchJsonOrDefault(staticUrl, { messages: {} });
        }
    }

    function resolveKey(namespaces, key) {
        if (!key) {
            return { namespace: namespaces[0], key: '' };
        }
        var index = key.indexOf(':');
        if (index < 0) {
            return { namespace: namespaces[0], key: key };
        }
        return {
            namespace: key.slice(0, index),
            key: key.slice(index + 1)
        };
    }

    function normalizeSupportedLocales(locales) {
        return (locales || []).map(function (item) {
            var tag = item && item.tag ? item.tag : '';
            return {
                tag: tag,
                displayName: (item && (item.displayName || item.label || item.name)) || tag
            };
        });
    }

    function interpolate(template, vars) {
        if (!vars) {
            return template;
        }
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (match, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : match;
        });
    }

    function translate(client, key, fallback, vars) {
        var resolved = resolveKey(client.namespaces, key);
        var namespaceMessages = client.bundleMap[resolved.namespace] || {};
        var template = namespaceMessages[resolved.key];
        if (template == null) {
            template = fallback != null ? fallback : key;
        }
        return interpolate(template, vars);
    }

    function findElements(root, selector) {
        var list = [];
        if (root.matches && root.matches(selector)) {
            list.push(root);
        }
        return list.concat(Array.prototype.slice.call(root.querySelectorAll(selector)));
    }

    function parseArgsAttribute(element) {
        var raw = element.getAttribute('data-i18n-args');
        if (!raw) {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (e) {
            return null;
        }
    }

    function applyAttributeBinding(root, client, selector, attrName, keyAttrName) {
        findElements(root, selector).forEach(function (element) {
            element.setAttribute(
                attrName,
                translate(client, element.getAttribute(keyAttrName), element.getAttribute(attrName), parseArgsAttribute(element))
            );
        });
    }

    function applyBindings(root, client) {
        findElements(root, '[data-i18n]').forEach(function (element) {
            element.textContent = translate(client, element.getAttribute('data-i18n'), element.textContent, parseArgsAttribute(element));
        });

        findElements(root, '[data-i18n-html]').forEach(function (element) {
            element.innerHTML = translate(client, element.getAttribute('data-i18n-html'), element.innerHTML, parseArgsAttribute(element));
        });

        applyAttributeBinding(root, client, '[data-i18n-placeholder]', 'placeholder', 'data-i18n-placeholder');
        applyAttributeBinding(root, client, '[data-i18n-title]', 'title', 'data-i18n-title');
        applyAttributeBinding(root, client, '[data-i18n-aria-label]', 'aria-label', 'data-i18n-aria-label');
    }

    function buildClient(meta, namespaces, bundleMap) {
        var client = {
            lang: meta.currentLang,
            defaultLang: meta.defaultLang,
            namespaces: namespaces.slice(),
            supportedLocales: normalizeSupportedLocales(meta.supportedLocales),
            bundleMap: bundleMap,
            t: function (key, fallback, vars) {
                return translate(client, key, fallback, vars);
            },
            has: function (key) {
                var resolved = resolveKey(client.namespaces, key);
                var namespaceMessages = client.bundleMap[resolved.namespace] || {};
                return Object.prototype.hasOwnProperty.call(namespaceMessages, resolved.key);
            },
            apply: function (root) {
                applyBindings(root || global.document, client);
                return client;
            },
            setLanguage: function (lang) {
                return create({
                    lang: lang,
                    namespaces: namespaces.slice()
                });
            }
        };
        return client;
    }

    async function create(options) {
        var config = options || {};
        var namespaces = Array.isArray(config.namespaces) && config.namespaces.length
            ? config.namespaces.slice()
            : [DEFAULT_NAMESPACE];
        var preferredLang = normalizeLang(config.lang || readStoredLang() || global.navigator.language);
        var meta = await fetchJsonOrDefault(
            '/api/i18n/meta?lang=' + encodeURIComponent(preferredLang),
            buildFallbackMeta(preferredLang)
        );
        var bundleMap = {};

        for (var i = 0; i < namespaces.length; i += 1) {
            var namespace = namespaces[i];
            var bundle = await fetchMessagesBundle(namespace, meta.currentLang);
            bundleMap[namespace] = bundle.messages || {};
        }

        writeStoredLang(meta.currentLang);
        if (global.document && global.document.documentElement) {
            global.document.documentElement.lang = meta.currentLang;
        }

        return buildClient(meta, namespaces, bundleMap);
    }

    global.PixivI18n = {
        create: create,
        notifyLanguageChange: notifyLanguageChange,
        onLanguageChange: onLanguageChange,
        normalizeLang: normalizeLang,
        storageKey: STORAGE_KEY
    };
})(window);
