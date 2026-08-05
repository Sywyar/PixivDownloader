(function (global) {
    'use strict';

    var STORAGE_KEY = 'pixiv.lang';
    var DEFAULT_NAMESPACE = 'common';
    // Static fallback metadata + bundles, used when the backend i18n API is unreachable
    // (e.g. a static hosting deployment without a Spring backend). Relative path so
    // it resolves correctly under a project-site /<repo>/ prefix.
    var STATIC_BUNDLE_BASE = 'i18n-static/';
    var LANGUAGE_CHANNEL_NAME = 'pixiv.language';
    var LANGUAGE_CHANGE_TYPE = 'language-change';
    var INSTANCE_ID = String(Date.now()) + '-' + String(Math.random()).slice(2);
    var languageChannel = null;
    var languageChannelInitialized = false;
    var storageListenerInitialized = false;
    var languageChangeListeners = [];

    function normalizeLang(lang) {
        if (!lang) {
            return null;
        }
        return String(lang).trim().replace(/_/g, '-');
    }

    // BCP 47 规范化：Intl.getCanonicalLocales（标准实现），不可用时退化为 _ / - 归一化。
    // zh-hant → zh-Hant、sr-latn → sr-Latn、en-us → en-US、ZH_hans → zh-Hans。
    function canonicalizeTag(tag) {
        var normalized = normalizeLang(tag);
        if (!normalized) {
            return null;
        }
        if (typeof Intl !== 'undefined' && Intl.getCanonicalLocales) {
            try {
                var canonical = Intl.getCanonicalLocales(normalized);
                if (canonical && canonical.length === 1) {
                    return canonical[0];
                }
            } catch (e) {
                // fall through to the normalized form
            }
        }
        return normalized;
    }

    // 把首选语言解析为 meta 中的正式 tag：精确 tag（规范化后）→ alias（规范化后）→
    // 唯一语言级匹配 → default。大小写不敏感、容忍 _ / - 混用。
    function resolveSupportedLang(preferred, meta) {
        if (!meta || !meta.supportedLocales || !meta.supportedLocales.length) {
            return (meta && meta.defaultLang) || preferred || null;
        }
        var preferredCanonical = canonicalizeTag(preferred);
        if (!preferredCanonical) {
            return meta.defaultLang || preferred || null;
        }
        var canonicalToTag = {};
        var tags = meta.supportedLocales.map(function (item) {
            var canonical = canonicalizeTag(item.tag);
            if (canonical) {
                canonicalToTag[canonical] = item.tag;
            }
            return item.tag;
        });
        // 1. 精确 tag 匹配优先
        if (canonicalToTag[preferredCanonical]) {
            return canonicalToTag[preferredCanonical];
        }
        // 2. alias 次之（规范化后比较）
        for (var i = 0; i < meta.supportedLocales.length; i += 1) {
            var item = meta.supportedLocales[i];
            var aliases = item.aliases || [];
            for (var j = 0; j < aliases.length; j += 1) {
                if (canonicalizeTag(aliases[j]) === preferredCanonical) {
                    return item.tag;
                }
            }
        }
        // 3. language-only 只有唯一结果时才匹配（多个同语言地区版本时不随意选择）
        var preferredLang = preferredCanonical.split('-')[0].toLowerCase();
        var languageMatches = tags.filter(function (tag) {
            return String(tag).split('-')[0].toLowerCase() === preferredLang;
        });
        if (languageMatches.length === 1) {
            return languageMatches[0];
        }
        return meta.defaultLang || preferred || null;
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
        if (!normalizedLang) {
            return;
        }
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

    // 语言元数据来源：优先后端 /api/i18n/meta，后端不可用时读构建生成的 i18n-static/meta.json。
    // 不允许在 JavaScript 中写死默认语言或语言数组。
    async function fetchMeta(preferredLang) {
        var backendMeta = await fetchJsonOrDefault(
            '/api/i18n/meta?lang=' + encodeURIComponent(preferredLang || ''),
            null
        );
        if (backendMeta && backendMeta.supportedLocales && backendMeta.supportedLocales.length) {
            return backendMeta;
        }
        return await fetchJsonOrDefault(STATIC_BUNDLE_BASE + 'meta.json', null);
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
                displayName: (item && (item.displayName || item.label || item.nativeName || item.name)) || tag,
                direction: (item && item.direction) || 'ltr'
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
        applyAttributeBinding(root, client, '[data-i18n-href]', 'href', 'data-i18n-href');
    }

    function buildClient(meta, namespaces, bundleMap) {
        var client = {
            lang: meta.currentLang,
            defaultLang: meta.defaultLang,
            sourceLang: meta.sourceLang,
            fallbackLang: meta.fallbackLang,
            cookieName: meta.languageCookieName,
            namespaces: namespaces.slice(),
            supportedLocales: normalizeSupportedLocales(meta.supportedLocales),
            bundleMap: bundleMap,
            t: function (key, fallback, vars) {
                return translate(client, key, fallback, vars);
            },
            // 显式 namespace 解析：key 为纯 key，在指定 namespace 内查（对应导航 labelNamespace / 插件 displayNamespace
            // 等「namespace 与 key 分离」契约）。namespace 为 null / "" / 纯空白（有意回退语义）时统一退回 t() 的裸 key
            // 行为（首个 namespace）；非空 namespace 先 trim 再查（容忍贡献方写入的首尾空白）。
            tns: function (namespace, key, fallback, vars) {
                var ns = namespace == null ? '' : String(namespace).trim();
                if (!ns) {
                    return translate(client, key, fallback, vars);
                }
                var namespaceMessages = client.bundleMap[ns] || {};
                var template = namespaceMessages[key];
                if (template == null) {
                    template = fallback != null ? fallback : key;
                }
                return interpolate(template, vars);
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
        var meta = await fetchMeta(preferredLang);
        if (!meta || !meta.defaultLang || !meta.currentLang) {
            // 后端与静态 meta 都不可用：客户端仍可工作，但语言菜单 / 归一化缺失，
            // 直接以首选语言尝试加载 bundle。不在此处写死任何语言。
            meta = {
                currentLang: preferredLang || '',
                sourceLang: '',
                defaultLang: '',
                fallbackLang: '',
                languageCookieName: '',
                languageParamName: '',
                supportedLocales: [],
                supportedNamespaces: []
            };
        } else {
            meta.currentLang = resolveSupportedLang(preferredLang || meta.currentLang, meta) || meta.currentLang;
        }
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
