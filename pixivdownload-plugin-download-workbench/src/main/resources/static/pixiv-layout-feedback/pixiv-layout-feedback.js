/*
 * 下载工作台布局偏好调查（PostHog API Survey 自定义单屏弹窗）。
 *
 * 共享实现：pixiv-batch.html（横屏 / 竖屏布局）与 pixiv-batch-alt.html（新版工作台）
 * 共用同一份业务逻辑，页面只负责在核心初始化完成后调用 init() 并提供 i18n client。
 *
 * 顶层加载无副作用；只有调用 init() 后才读取公开配置、加载 SDK 与操作 DOM。
 * 调查初始化不阻塞页面核心初始化；调查的任何异常都不得中断下载功能。
 *
 * 依赖顺序：
 *   1. /pixiv-layout-feedback/public-config.js   （构建生成的公开客户端配置，enabled 推导）
 *   2. 本文件
 * 页面按需懒加载 /vendor/posthog-js/<version>/array.full.js（固定 vendor 版本，无 CDN）。
 *
 * 隐私约束：
 *   - Project token / Survey ID / apiHost / uiHost 都是公开客户端配置，不是 Secret；
 *   - 不调用 posthog.identify()，只使用匿名 distinct ID；
 *   - 不发送 Cookie / 账号 / 作品 / 路径 / 浏览器指纹；
 *   - autocapture / pageview / pageleave / replay / heatmap / error tracking / web vitals 全关；
 *   - before_send 只放行 survey shown / survey sent / survey dismissed 三个事件并做属性允许列表；
 *   - 本地弱去重（localStorage + 匿名 distinct ID），不是不可绕过的选举系统。
 */
(function (global) {
    'use strict';

    /* ============================================================
       常量与纯函数（_internals 暴露给自动化测试）
    ============================================================ */

    var LAYOUT_IDS = ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt'];
    var STATE_KEY = 'pixiv:layout-feedback:state:v1';
    var SEEN_KEY = 'pixiv:layout-feedback:seen:v1';
    var SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;
    var MIN_DISTINCT_LAYOUTS_SEEN = 2;
    var AUTO_DELAY_MS = 10 * 1000;
    var AUTO_RETRY_DELAY_MS = 5 * 1000;
    var SUGGESTION_MAX_CODE_POINTS = 1000;
    var SURVEY_SCHEMA_VERSION = '1';
    var SDK_LOAD_TIMEOUT_MS = 10 * 1000;
    var FLAGS_TIMEOUT_MS = 10 * 1000;
    var SURVEY_TOTAL_TIMEOUT_MS = 30 * 1000;
    var APP_VERSION_TIMEOUT_MS = 10 * 1000;
    var POSTHOG_JS_VERSION = '1.409.5';
    var SDK_URL = '/vendor/posthog-js/' + POSTHOG_JS_VERSION + '/array.full.js';
    var I18N_NS = 'layout-feedback';
    var ALLOWED_SURVEY_EVENTS = ['survey shown', 'survey sent', 'survey dismissed'];
    var PROTOCOL_PROPERTIES = [
        'distinct_id', 'token', 'time', '$lib', '$lib_version', '$lib_variant',
        '$device_id', '$session_id', '$window_id', '$pageview_id', '$survey_id',
        'app_version', 'current_layout', 'survey_schema_version'
    ];

    function codePointLength(value) {
        return Array.from(String(value == null ? '' : value)).length;
    }

    function mapLayoutToken(token) {
        if (token === 'landscape') return 'pixiv-batch-landscape';
        if (token === 'portrait') return 'pixiv-batch-portrait';
        if (LAYOUT_IDS.indexOf(token) >= 0) return token;
        return null;
    }

    /**
     * 识别「布局单选题」。规则：
     * - type 必须是 single_choice；id 非空；不能 optional=true；
     * - choices 必须恰好包含三个稳定布局 ID（不依赖后台顺序），不得含未知选项；
     * - 存在多个满足条件的题时视为 schema 歧义，返回 null（不显示调查）。
     */
    function resolveChoiceQuestion(survey) {
        if (!survey || !Array.isArray(survey.questions)) return null;
        var matches = [];
        survey.questions.forEach(function (question) {
            if (!question || typeof question !== 'object') return;
            if (question.type !== 'single_choice') return;
            if (typeof question.id !== 'string' || !question.id) return;
            if (question.optional === true) return;
            if (!Array.isArray(question.choices)) return;
            var ids = [];
            var allValid = true;
            question.choices.forEach(function (choice) {
                var id = null;
                if (typeof choice === 'string') {
                    id = choice;
                } else if (choice && typeof choice.id === 'string') {
                    id = choice.id;
                }
                if (LAYOUT_IDS.indexOf(id) < 0) {
                    // 未知 / 畸形选项一律使该题无效（不得包含第四个未知选项）
                    allValid = false;
                    return;
                }
                if (ids.indexOf(id) >= 0) {
                    allValid = false;
                    return;
                }
                ids.push(id);
            });
            if (!allValid) return;
            var complete = LAYOUT_IDS.every(function (id) { return ids.indexOf(id) >= 0; });
            if (!complete || ids.length !== LAYOUT_IDS.length) return;
            matches.push(question);
        });
        return matches.length === 1 ? matches[0] : null;
    }

    /**
     * 识别「优化建议开放题」。规则：
     * - 接受 open / open_text；
     * - id 非空；应为可选（optional=false 视为 schema 异常）；
     * - 第二题缺失时返回 null（仍可只收集布局选择）；
     * - 多个开放文本题无法唯一判断时返回 null 并记录不含用户数据的安全警告。
     */
    function resolveSuggestionQuestion(survey) {
        if (!survey || !Array.isArray(survey.questions)) return null;
        var matches = [];
        survey.questions.forEach(function (question) {
            if (!question || typeof question !== 'object') return;
            if (question.type !== 'open' && question.type !== 'open_text') return;
            if (typeof question.id !== 'string' || !question.id) return;
            if (question.optional === false) return;
            matches.push(question);
        });
        if (matches.length > 1) {
            warn('layout survey: multiple open text questions; suggestion input hidden (schema ambiguity)');
            return null;
        }
        return matches.length === 1 ? matches[0] : null;
    }

    /**
     * before_send 过滤器：只放行三个调查事件，并对属性执行允许列表，
     * 删除 $current_url / $referrer / $referring_domain / pathname / hostname
     * 等无关浏览器环境属性，保留 distinct_id、$survey_id、$survey_response_*
     * 与 SDK 必要协议字段。
     */
    function beforeSendFilter(event) {
        if (!event || typeof event !== 'object') return null;
        if (ALLOWED_SURVEY_EVENTS.indexOf(event.event) === -1) return null;
        var props = event.properties && typeof event.properties === 'object'
            ? event.properties
            : {};
        var out = {};
        Object.keys(props).forEach(function (key) {
            if (key.indexOf('$survey_response_') === 0) {
                out[key] = props[key];
                return;
            }
            if (PROTOCOL_PROPERTIES.indexOf(key) >= 0) {
                out[key] = props[key];
            }
        });
        return Object.assign({}, event, {properties: out});
    }

    function distinctSeenCount(seen) {
        var count = 0;
        LAYOUT_IDS.forEach(function (id) {
            var entry = seen && seen[id];
            if (entry && typeof entry.lastSeenAt === 'number' && entry.lastSeenAt > 0) count++;
        });
        return count;
    }

    function warn() {
        if (global.console && typeof global.console.warn === 'function') {
            try { global.console.warn.apply(global.console, arguments); } catch (_) { /* 安全 */ }
        }
    }

    /* ============================================================
       运行期状态
    ============================================================ */

    var initialized = false;
    var pageType = 'batch';
    var config = null;
    var storage = null;
    var timers = null;
    var fetchImpl = null;
    var minDistinctLayouts = MIN_DISTINCT_LAYOUTS_SEEN;
    var autoDelay = AUTO_DELAY_MS;
    var i18nClient = null;
    var injectedAdapter = null;
    var realSdkPromise = null;
    var sdkLoading = false;
    var postHogInitialized = false;
    var autoScheduled = false;
    var autoRetried = false;
    var autoClaimed = false;
    var flowRunning = false;
    var dialogOpen = false;
    var submitting = false;
    var shownSent = false;
    var dialogSurveyId = null;
    var dialogChoiceQuestion = null;
    var dialogSuggestionQuestion = null;
    var layoutSnapshot = null;
    var selectedChoice = null;
    var dialogElements = null;
    var dialogFocusBefore = null;
    var dialogKeydownHandler = null;
    var dialogStorageBound = false;
    var sessionState = null;
    var sessionSeen = {};
    var appVersionPromise = null;
    var pendingTimers = [];

    function safeLocalStorage() {
        try {
            return global.localStorage;
        } catch (_) {
            return null;
        }
    }

    function setTimeoutSafe(fn, ms) {
        var id = null;
        try {
            id = timers.setTimeout(fn, ms);
        } catch (_) {
            id = null;
        }
        if (id != null) pendingTimers.push(id);
        return id;
    }

    function clearTimerSafe(id) {
        if (id == null) return;
        var index = pendingTimers.indexOf(id);
        if (index >= 0) pendingTimers.splice(index, 1);
        try {
            timers.clearTimeout(id);
        } catch (_) {
            // 已触发或已清理
        }
    }

    function defaultTimers() {
        return {
            now: function () { return Date.now(); },
            setTimeout: function (fn, ms) { return global.setTimeout(fn, ms); },
            clearTimeout: function (id) { return global.clearTimeout(id); }
        };
    }

    function defaultFetch() {
        return typeof global.fetch === 'function'
            ? function (url, options) { return global.fetch(url, options); }
            : function () { return Promise.reject(new Error('fetch unavailable')); };
    }

    /* ============================================================
       公开配置与本地状态
    ============================================================ */

    function readPublicConfig() {
        var raw = global.PixivLayoutFeedbackPublicConfig;
        if (!raw || typeof raw !== 'object') return null;
        return {
            enabled: raw.enabled === true,
            projectToken: typeof raw.projectToken === 'string' ? raw.projectToken : '',
            surveyId: typeof raw.surveyId === 'string' ? raw.surveyId : '',
            apiHost: typeof raw.apiHost === 'string' ? raw.apiHost : '',
            uiHost: typeof raw.uiHost === 'string' ? raw.uiHost : ''
        };
    }

    function readStateRaw() {
        if (sessionState) return sessionState;
        if (!storage) return null;
        try {
            var raw = storage.getItem(STATE_KEY);
            if (!raw) return null;
            var parsed = JSON.parse(raw);
            if (!parsed || typeof parsed !== 'object') return null;
            sessionState = parsed;
            return parsed;
        } catch (_) {
            return null;
        }
    }

    function readState() {
        if (!config) return null;
        var state = readStateRaw();
        if (!state || state.surveyId !== config.surveyId) return null;
        return state;
    }

    function writeState(status, snoozedUntil) {
        if (!config) return;
        var state = {
            surveyId: config.surveyId,
            status: status,
            updatedAt: timers.now(),
            snoozedUntil: snoozedUntil || 0
        };
        sessionState = state;
        if (storage) {
            try {
                storage.setItem(STATE_KEY, JSON.stringify(state));
            } catch (_) {
                // 存储不可用时仅保留内存态（页面会话内仍生效）
            }
        }
    }

    function stateAllowsShow(now) {
        var state = readState();
        if (!state) return true;
        if (state.status === 'submitted' || state.status === 'never') return false;
        if (state.status === 'snoozed') {
            return now >= (typeof state.snoozedUntil === 'number' ? state.snoozedUntil : 0);
        }
        return true;
    }

    function readSeenRaw() {
        if (storage) {
            try {
                var raw = storage.getItem(SEEN_KEY);
                if (!raw) return Object.assign({}, sessionSeen);
                var parsed = JSON.parse(raw);
                if (!parsed || typeof parsed !== 'object') return Object.assign({}, sessionSeen);
                sessionSeen = parsed;
                return parsed;
            } catch (_) {
                return Object.assign({}, sessionSeen);
            }
        }
        return Object.assign({}, sessionSeen);
    }

    function writeSeen(seen) {
        sessionSeen = seen;
        if (storage) {
            try {
                storage.setItem(SEEN_KEY, JSON.stringify(seen));
            } catch (_) {
                // 存储不可用时仅保留会话记录
            }
        }
    }

    function recordSeen(layoutId, now) {
        if (!layoutId || LAYOUT_IDS.indexOf(layoutId) < 0) return null;
        var seen = readSeenRaw();
        var entry = seen[layoutId] || {firstSeenAt: 0, lastSeenAt: 0};
        if (!entry.firstSeenAt) entry.firstSeenAt = now;
        entry.lastSeenAt = now;
        seen[layoutId] = entry;
        writeSeen(seen);
        return distinctSeenCount(seen);
    }

    function seenCount() {
        return distinctSeenCount(readSeenRaw());
    }

    function currentLayoutId() {
        if (pageType === 'alt') return 'pixiv-batch-alt';
        var token = null;
        if (global.PixivBatch && global.PixivBatch.layout
                && typeof global.PixivBatch.layout.currentLayout === 'function') {
            try {
                token = global.PixivBatch.layout.currentLayout();
            } catch (_) {
                token = null;
            }
        }
        if (token == null && documentElement()) {
            try {
                token = documentElement().getAttribute('data-batch-layout');
            } catch (_) {
                token = null;
            }
        }
        return mapLayoutToken(token);
    }

    function documentElement() {
        try {
            return global.document && global.document.documentElement
                ? global.document.documentElement
                : null;
        } catch (_) {
            return null;
        }
    }

    /* ============================================================
       应用版本
    ============================================================ */

    function loadAppVersion() {
        if (appVersionPromise) return appVersionPromise;
        appVersionPromise = new Promise(function (resolve) {
            var settled = false;
            var timer = setTimeoutSafe(function () { finish('unknown'); }, APP_VERSION_TIMEOUT_MS);
            function finish(version) {
                if (settled) return;
                settled = true;
                clearTimerSafe(timer);
                resolve(version);
            }
            var request = null;
            try {
                request = fetchImpl('/api/app/info', {credentials: 'same-origin'});
            } catch (_) {
                finish('unknown');
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish('unknown');
                return;
            }
            request.then(function (response) {
                if (!response || !response.ok) throw new Error('http');
                return response.json();
            }).then(function (data) {
                finish(data && typeof data.version === 'string' && data.version
                    ? data.version
                    : 'unknown');
            }).catch(function () {
                finish('unknown');
            });
        });
        return appVersionPromise;
    }

    /* ============================================================
       SDK 加载与 PostHog 初始化
    ============================================================ */

    function buildSdkConfig() {
        return {
            api_host: config.apiHost,
            ui_host: config.uiHost,
            autocapture: false,
            capture_pageview: false,
            capture_pageleave: false,
            capture_performance: false,
            capture_dead_clicks: false,
            capture_exceptions: false,
            enable_heatmaps: false,
            disable_session_recording: true,
            disable_surveys: false,
            person_profiles: 'identified_only',
            persistence: 'localStorage',
            cross_subdomain_cookie: false,
            respect_dnt: true,
            mask_all_text: true,
            mask_all_element_attributes: true,
            before_send: beforeSendFilter
        };
    }

    function loadSdkScript() {
        if (sdkLoading) return realSdkPromise;
        sdkLoading = true;
        realSdkPromise = new Promise(function (resolve) {
            var script = null;
            var settled = false;
            var timer = setTimeoutSafe(function () { finish(null); }, SDK_LOAD_TIMEOUT_MS);
            function finish(sdk) {
                if (settled) return;
                settled = true;
                clearTimerSafe(timer);
                if (script) {
                    try {
                        script.removeEventListener('load', onLoad);
                        script.removeEventListener('error', onError);
                    } catch (_) {
                        // 清理尽力而为
                    }
                }
                resolve(sdk);
            }
            function onLoad() {
                var sdk = global.posthog && typeof global.posthog.init === 'function'
                    ? global.posthog
                    : null;
                finish(sdk);
            }
            function onError() {
                finish(null);
            }
            try {
                script = global.document.createElement('script');
                script.src = SDK_URL;
                script.async = true;
                script.addEventListener('load', onLoad);
                script.addEventListener('error', onError);
                var head = global.document.head || documentElement();
                (head || global.document.body || global.document.documentElement).appendChild(script);
            } catch (_) {
                finish(null);
            }
        });
        return realSdkPromise;
    }

    function initPostHog(sdk) {
        if (postHogInitialized || !sdk || typeof sdk.init !== 'function') return;
        postHogInitialized = true;
        try {
            sdk.init(config.projectToken, buildSdkConfig());
        } catch (_) {
            postHogInitialized = false;
            throw _;
        }
    }

    function resolveSdk() {
        if (injectedAdapter) return Promise.resolve(injectedAdapter);
        return loadSdkScript();
    }

    /* ============================================================
       事件发送
    ============================================================ */

    function sendSurveyEvent(name, properties) {
        return new Promise(function (resolve, reject) {
            var settled = false;
            function finish(accepted) {
                if (settled) return;
                settled = true;
                if (accepted) resolve();
                else reject(new Error('posthog capture rejected event: ' + name));
            }
            resolveSdk().then(function (sdk) {
                if (!sdk || typeof sdk.capture !== 'function') {
                    finish(false);
                    return;
                }
                try {
                    var result = sdk.capture(name, properties);
                    finish(result !== false);
                } catch (_) {
                    finish(false);
                }
            }, function () {
                finish(false);
            });
        });
    }

    function surveyEventProperties(extra) {
        var props = {
            '$survey_id': dialogSurveyId,
            app_version: 'unknown',
            current_layout: layoutSnapshot,
            survey_schema_version: SURVEY_SCHEMA_VERSION
        };
        Object.keys(extra || {}).forEach(function (key) {
            props[key] = extra[key];
        });
        return Promise.resolve(loadAppVersion()).then(function (version) {
            props.app_version = version || 'unknown';
            return props;
        });
    }

    function sendShown() {
        if (shownSent || !dialogSurveyId) return;
        shownSent = true;
        surveyEventProperties().then(function (props) {
            return sendSurveyEvent('survey shown', props);
        }).catch(function () {
            // shown 发送失败不影响用户填写调查
        });
    }

    function sendDismissedBestEffort() {
        if (!dialogSurveyId) return Promise.resolve();
        return surveyEventProperties().then(function (props) {
            return sendSurveyEvent('survey dismissed', props);
        }).catch(function () {
            // 本地永久关闭优先；事件尽力而为
        });
    }

    /* ============================================================
       i18n 与文案
    ============================================================ */

    function t(key, fallback) {
        if (i18nClient && typeof i18nClient.t === 'function') {
            try {
                return i18nClient.t(I18N_NS + ':' + key, fallback);
            } catch (_) {
                return fallback;
            }
        }
        return fallback;
    }

    function applyDialogTranslations() {
        if (!dialogElements || !dialogElements.root) return;
        if (i18nClient && typeof i18nClient.apply === 'function') {
            try {
                i18nClient.apply(dialogElements.root);
            } catch (_) {
                // 翻译失败不破坏弹窗
            }
        }
        updateCounterText();
        updateSubmitLabel();
        if (dialogElements.error && dialogElements.error.hidden === false) {
            updateErrorText();
        }
    }

    /* ============================================================
       弹窗交互
    ============================================================ */

    function isPageVisible() {
        try {
            var visibility = global.document && global.document.visibilityState;
            return !visibility || visibility === 'visible';
        } catch (_) {
            return true;
        }
    }

    function isElementVisible(element) {
        if (!element) return false;
        try {
            return !element.hidden;
        } catch (_) {
            return true;
        }
    }

    function hasBlockingOverlay() {
        try {
            var body = global.document && global.document.body;
            if (body && body.classList && body.classList.contains('pixiv-feedback-open')) return true;
            if (global.document.querySelector('.pt-root') && isElementVisible(global.document.querySelector('.pt-root'))) return true;
            if (global.document.querySelector('.po-root')) return true;
            var modalRoot = global.document.getElementById('abModalRoot');
            if (modalRoot && !modalRoot.hidden && modalRoot.children && modalRoot.children.length) return true;
            var drawerRoot = global.document.getElementById('abDrawerRoot');
            if (drawerRoot && !drawerRoot.hidden && drawerRoot.children && drawerRoot.children.length) return true;
        } catch (_) {
            // 任一探测失败按「无阻塞」继续，调查异常不得影响页面
        }
        return false;
    }

    function buildElement(tagName, className) {
        var element = global.document.createElement(tagName);
        if (className) element.className = className;
        return element;
    }

    function openDialog(survey, choiceQuestion, suggestionQuestion) {
        if (dialogOpen || !global.document || !global.document.body) return false;
        dialogOpen = true;
        dialogSurveyId = survey.id;
        dialogChoiceQuestion = choiceQuestion;
        dialogSuggestionQuestion = suggestionQuestion;
        layoutSnapshot = currentLayoutId();
        selectedChoice = null;
        shownSent = false;
        submitting = false;

        try {
            dialogFocusBefore = global.document.activeElement;
            var backdrop = buildElement('div', 'plf-backdrop');
            var dialog = buildElement('section', 'plf-dialog');
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.setAttribute('aria-labelledby', 'plf-title');
            dialog.setAttribute('aria-describedby', 'plf-description');
            dialog.tabIndex = -1;

            var closeButton = buildElement('button', 'plf-close');
            closeButton.type = 'button';
            closeButton.setAttribute('data-plf-action', 'close');
            closeButton.setAttribute('data-i18n-aria-label', I18N_NS + ':close');
            closeButton.setAttribute('aria-label', t('close', '关闭'));
            closeButton.textContent = '\u00d7';

            var title = buildElement('h2', 'plf-title');
            title.id = 'plf-title';
            title.setAttribute('data-i18n', I18N_NS + ':title');
            title.textContent = t('title', '帮助我们选择默认布局');

            var description = buildElement('p', 'plf-description');
            description.id = 'plf-description';
            description.setAttribute('data-i18n', I18N_NS + ':description');
            description.textContent = t('description', '新版本提供了三种下载工作台布局。请选择你更愿意长期使用的一种。');

            var group = buildElement('div', 'plf-cards');
            group.setAttribute('role', 'radiogroup');
            group.setAttribute('aria-label', t('choices-group', '选择你更愿意长期使用的布局'));
            group.setAttribute('data-i18n-aria-label', I18N_NS + ':choices-group');

            var currentId = currentLayoutId();
            LAYOUT_IDS.forEach(function (layoutId, index) {
                var card = buildElement('label', 'plf-card');
                card.setAttribute('data-plf-layout', layoutId);
                var input = buildElement('input');
                input.type = 'radio';
                input.name = 'plf-layout-choice';
                input.value = layoutId;
                input.className = 'plf-radio';
                if (index === 0) input.setAttribute('data-plf-first-radio', 'true');
                var nameSpan = buildElement('span', 'plf-card-name');
                var descSpan = buildElement('span', 'plf-card-desc');
                var currentBadge = buildElement('span', 'plf-current-badge');
                currentBadge.setAttribute('data-i18n', I18N_NS + ':current-layout');
                currentBadge.textContent = t('current-layout', '当前布局');
                currentBadge.hidden = currentId !== layoutId;
                nameSpan.setAttribute('data-i18n', I18N_NS + ':option-' + layoutOptionKey(layoutId));
                nameSpan.textContent = t('option-' + layoutOptionKey(layoutId), optionFallbackName(layoutId));
                descSpan.setAttribute('data-i18n', I18N_NS + ':option-' + layoutOptionKey(layoutId) + '-desc');
                descSpan.textContent = t('option-' + layoutOptionKey(layoutId) + '-desc', optionFallbackDesc(layoutId));
                card.appendChild(input);
                card.appendChild(nameSpan);
                card.appendChild(descSpan);
                card.appendChild(currentBadge);
                input.addEventListener('change', onChoiceChange);
                group.appendChild(card);
            });

            var suggestionWrap = buildElement('div', 'plf-suggestion');
            var suggestionLabel = buildElement('label', 'plf-suggestion-label');
            suggestionLabel.setAttribute('for', 'plf-suggestion-input');
            suggestionLabel.setAttribute('data-i18n', I18N_NS + ':suggestion-label');
            suggestionLabel.textContent = t('suggestion-label', '优化建议（可选）');
            var textarea = buildElement('textarea', 'plf-suggestion-input');
            textarea.id = 'plf-suggestion-input';
            textarea.rows = 3;
            textarea.setAttribute('maxlength', String(SUGGESTION_MAX_CODE_POINTS));
            textarea.setAttribute('data-i18n-placeholder', I18N_NS + ':suggestion-placeholder');
            textarea.placeholder = t('suggestion-placeholder', '例如：信息密度、导航位置、按钮大小、队列展示或移动端体验……');
            var counter = buildElement('span', 'plf-suggestion-counter');
            counter.id = 'plf-suggestion-counter';
            counter.setAttribute('data-plf-counter', 'true');
            counter.setAttribute('aria-live', 'polite');
            textarea.setAttribute('aria-describedby', 'plf-suggestion-counter');
            textarea.addEventListener('input', onSuggestionInput);
            if (!suggestionQuestion) {
                suggestionWrap.hidden = true;
                suggestionWrap.setAttribute('data-plf-no-suggestion', 'true');
            }
            suggestionWrap.appendChild(suggestionLabel);
            suggestionWrap.appendChild(textarea);
            suggestionWrap.appendChild(counter);

            var privacy = buildElement('p', 'plf-privacy');
            privacy.setAttribute('data-i18n', I18N_NS + ':privacy');
            privacy.textContent = t('privacy', '提交后会向 PostHog 发送你的布局选择、可选建议、应用版本和匿名浏览器标识；不会发送 Pixiv Cookie、账号信息、作品信息、下载内容或本地路径。');

            var error = buildElement('p', 'plf-error');
            error.setAttribute('role', 'alert');
            error.setAttribute('aria-live', 'polite');
            error.hidden = true;
            error.setAttribute('data-plf-error', 'true');

            var actions = buildElement('div', 'plf-actions');
            var snoozeButton = buildElement('button', 'plf-button plf-button--secondary');
            snoozeButton.type = 'button';
            snoozeButton.setAttribute('data-plf-action', 'snooze');
            snoozeButton.setAttribute('data-i18n', I18N_NS + ':snooze');
            snoozeButton.textContent = t('snooze', '稍后再说');
            var neverButton = buildElement('button', 'plf-button plf-button--ghost');
            neverButton.type = 'button';
            neverButton.setAttribute('data-plf-action', 'never');
            neverButton.setAttribute('data-i18n', I18N_NS + ':never');
            neverButton.textContent = t('never', '不再询问');
            var submitButton = buildElement('button', 'plf-button plf-button--primary');
            submitButton.type = 'button';
            submitButton.setAttribute('data-plf-action', 'submit');
            submitButton.setAttribute('data-i18n', I18N_NS + ':submit');
            submitButton.textContent = t('submit', '提交反馈');
            submitButton.disabled = true;
            actions.appendChild(snoozeButton);
            actions.appendChild(neverButton);
            actions.appendChild(submitButton);

            dialog.appendChild(closeButton);
            dialog.appendChild(title);
            dialog.appendChild(description);
            dialog.appendChild(group);
            dialog.appendChild(suggestionWrap);
            dialog.appendChild(privacy);
            dialog.appendChild(error);
            dialog.appendChild(actions);
            backdrop.appendChild(dialog);
            global.document.body.appendChild(backdrop);

            dialogElements = {
                root: backdrop,
                dialog: dialog,
                backdrop: backdrop,
                closeButton: closeButton,
                group: group,
                textarea: textarea,
                counter: counter,
                error: error,
                submitButton: submitButton,
                snoozeButton: snoozeButton,
                neverButton: neverButton,
                radios: Array.prototype.slice.call(group.querySelectorAll('input[type="radio"]'))
            };

            dialog.addEventListener('click', onDialogActionClick);
            backdrop.addEventListener('mousedown', onBackdropMouseDown);
            dialogKeydownHandler = onDialogKeyDown;
            global.document.addEventListener('keydown', dialogKeydownHandler, true);
            updateCounterText();
            updateSubmitLabel();
            applyDialogTranslations();
            setTimeoutSafe(function () {
                try {
                    dialog.focus();
                } catch (_) {
                    // 焦点移动失败不阻断弹窗
                }
            }, 0);
            return true;
        } catch (_) {
            dialogOpen = false;
            dialogElements = null;
            throw _;
        }
    }

    function layoutOptionKey(layoutId) {
        if (layoutId === 'pixiv-batch-landscape') return 'landscape';
        if (layoutId === 'pixiv-batch-portrait') return 'portrait';
        return 'alt';
    }

    function optionFallbackName(layoutId) {
        if (layoutId === 'pixiv-batch-landscape') return '横屏工作台';
        if (layoutId === 'pixiv-batch-portrait') return '竖屏经典布局';
        return '新版工作台';
    }

    function optionFallbackDesc(layoutId) {
        if (layoutId === 'pixiv-batch-landscape') return '适合宽屏显示器，模式、设置和下载队列同时展示。';
        if (layoutId === 'pixiv-batch-portrait') return '内容按纵向顺序排列，更接近原有使用方式。';
        return '采用独立的新工作台界面和下载面板。';
    }

    function onChoiceChange(event) {
        var input = event && event.target;
        if (!input || !input.value) return;
        selectedChoice = input.value;
        if (dialogElements && dialogElements.group) {
            dialogElements.group.querySelectorAll('input[type="radio"]').forEach(function (radio) {
                var card = radio.parentNode;
                if (card && card.classList) {
                    card.classList.toggle('is-checked', radio.checked);
                }
            });
        }
        if (dialogElements) {
            dialogElements.submitButton.disabled = false;
            hideError();
        }
    }

    function onSuggestionInput() {
        updateCounterText();
        hideError();
    }

    function updateCounterText() {
        if (!dialogElements || !dialogElements.counter || !dialogElements.textarea) return;
        var count = codePointLength(dialogElements.textarea.value);
        var template = t('suggestion-counter', '{count} / {max}');
        var isFallback = template === '{count} / {max}';
        var text = template;
        if (isFallback) {
            text = count + ' / ' + SUGGESTION_MAX_CODE_POINTS;
        } else {
            text = template
                .replace('{count}', String(count))
                .replace('{max}', String(SUGGESTION_MAX_CODE_POINTS));
        }
        dialogElements.counter.textContent = text;
    }

    function updateSubmitLabel() {
        if (!dialogElements || !dialogElements.submitButton) return;
        dialogElements.submitButton.textContent = submitting
            ? t('submitting', '提交中…')
            : t('submit', '提交反馈');
    }

    function showError(key) {
        lastErrorKey = key;
        if (!dialogElements || !dialogElements.error) return;
        dialogElements.error.textContent = t(key, errorFallback(key));
        dialogElements.error.hidden = false;
    }

    function hideError() {
        if (!dialogElements || !dialogElements.error) return;
        dialogElements.error.hidden = true;
        dialogElements.error.textContent = '';
    }

    function updateErrorText() {
        if (!dialogElements || !dialogElements.error) return;
        dialogElements.error.textContent = t(currentErrorKey(), errorFallback(currentErrorKey()));
    }

    var lastErrorKey = null;
    function currentErrorKey() {
        return lastErrorKey || 'error-submit-failed';
    }

    function errorFallback(key) {
        if (key === 'error-required') return '请先选择一种布局。';
        if (key === 'error-suggestion-too-long') return '建议内容过长，请精简到 1000 字以内。';
        if (key === 'survey-unavailable') return '调查暂不可用。';
        return '提交失败，请重试。';
    }

    function setSubmittingState(active) {
        if (!dialogElements) return;
        dialogElements.dialog.setAttribute('aria-busy', active ? 'true' : 'false');
        dialogElements.radios.forEach(function (input) { input.disabled = active; });
        if (dialogElements.textarea) dialogElements.textarea.disabled = active;
        dialogElements.submitButton.disabled = active || !selectedChoice;
        dialogElements.snoozeButton.disabled = active;
        dialogElements.neverButton.disabled = active;
        dialogElements.closeButton.disabled = active;
        updateSubmitLabel();
    }

    function onDialogActionClick(event) {
        if (submitting) return;
        var button = event && event.target && event.target.closest
            ? event.target.closest('button[data-plf-action]')
            : null;
        if (!button) return;
        var action = button.getAttribute('data-plf-action');
        if (action === 'submit') submitFeedback();
        else if (action === 'snooze') snooze();
        else if (action === 'never') dismissNever();
        else if (action === 'close') snooze();
    }

    function onBackdropMouseDown(event) {
        if (submitting) return;
        if (event.target === dialogElements.backdrop) snooze();
    }

    function onDialogKeyDown(event) {
        if (!dialogOpen) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
            else if (typeof event.stopPropagation === 'function') event.stopPropagation();
            if (!submitting) snooze();
            return;
        }
        if (event.key !== 'Tab' || !dialogElements) return;
        var focusable = dialogElements.dialog.querySelectorAll(
            'button:not([disabled]), input:not([disabled]), textarea:not([disabled])'
        );
        if (!focusable.length) {
            event.preventDefault();
            dialogElements.dialog.focus();
            return;
        }
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && global.document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && global.document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    function closeDialog(restoreFocus) {
        if (!dialogOpen) return;
        dialogOpen = false;
        var previousFocus = dialogFocusBefore;
        try {
            if (dialogKeydownHandler) {
                global.document.removeEventListener('keydown', dialogKeydownHandler, true);
            }
            if (dialogElements && dialogElements.root && dialogElements.root.parentNode) {
                dialogElements.root.parentNode.removeChild(dialogElements.root);
            }
        } catch (_) {
            // DOM 清理尽力而为
        }
        dialogElements = null;
        dialogKeydownHandler = null;
        dialogFocusBefore = null;
        if (restoreFocus && previousFocus && typeof previousFocus.focus === 'function') {
            try {
                if (global.document.contains && global.document.contains(previousFocus)) {
                    previousFocus.focus();
                }
            } catch (_) {
                // 焦点恢复失败不阻断
            }
        }
    }

    function snooze() {
        if (submitting || !dialogOpen) return;
        writeState('snoozed', timers.now() + SNOOZE_MS);
        closeDialog(true);
    }

    function dismissNever() {
        if (submitting || !dialogOpen) return;
        writeState('never');
        var id = dialogSurveyId;
        var snapshot = layoutSnapshot;
        closeDialog(true);
        // 本地永久关闭优先；即使 PostHog 请求失败也必须尊重
        sendDismissedBestEffort().catch(function () {});
    }

    function trimSuggestion() {
        if (!dialogElements || !dialogElements.textarea) return '';
        return String(dialogElements.textarea.value).trim();
    }

    function submitFeedback() {
        if (submitting || !dialogOpen) return;
        if (!selectedChoice || !dialogChoiceQuestion) {
            lastErrorKey = 'error-required';
            showError('error-required');
            return;
        }
        var suggestion = trimSuggestion();
        if (suggestion.length > SUGGESTION_MAX_CODE_POINTS) {
            lastErrorKey = 'error-suggestion-too-long';
            showError('error-suggestion-too-long');
            return;
        }
        submitting = true;
        setSubmittingState(true);
        hideError();

        var choiceId = dialogChoiceQuestion.id;
        var suggestionQuestion = dialogSuggestionQuestion;
        var suggestionId = suggestionQuestion ? suggestionQuestion.id : null;
        var surveyId = dialogSurveyId;
        var snapshot = layoutSnapshot;

        surveyEventProperties({}).then(function (base) {
            var props = {
                '$survey_id': surveyId,
                app_version: base.app_version,
                current_layout: snapshot,
                survey_schema_version: SURVEY_SCHEMA_VERSION
            };
            props['$survey_response_' + choiceId] = selectedChoice;
            if (suggestion && suggestionId) {
                props['$survey_response_' + suggestionId] = suggestion;
            }
            return sendSurveyEvent('survey sent', props);
        }).then(function () {
            submitting = false;
            writeState('submitted');
            closeDialog(true);
            showSuccessToast();
        }).catch(function () {
            submitting = false;
            setSubmittingState(false);
            lastErrorKey = 'error-submit-failed';
            showError('error-submit-failed');
        });
    }

    function showSuccessToast() {
        if (global.PixivFeedback && typeof global.PixivFeedback.toast === 'function') {
            try {
                global.PixivFeedback.toast({kind: 'success', message: t('submit-success', '感谢反馈，已提交你的布局偏好。')});
            } catch (_) {
                // toast 失败不影响提交结果
            }
        }
    }

    /* ============================================================
       Survey 获取
    ============================================================ */

    function findTargetSurvey(surveys) {
        if (!Array.isArray(surveys)) return null;
        var found = null;
        surveys.forEach(function (survey) {
            if (!survey || typeof survey !== 'object') return;
            if (survey.id !== config.surveyId) return;
            if (survey.type !== 'api') return;
            found = survey;
        });
        return found;
    }

    function fetchMatchingSurvey(sdk) {
        return new Promise(function (resolve) {
            var settled = false;
            var off = null;
            var flagTimer = null;
            var totalTimer = setTimeoutSafe(function () { finish(null); }, SURVEY_TOTAL_TIMEOUT_MS);
            function finish(survey) {
                if (settled) return;
                settled = true;
                clearTimerSafe(totalTimer);
                if (flagTimer != null) clearTimerSafe(flagTimer);
                if (off) {
                    try { off(); } catch (_) { /* 解除监听尽力而为 */ }
                }
                resolve(survey);
            }
            function proceed() {
                try {
                    sdk.getActiveMatchingSurveys(function (surveys) {
                        finish(findTargetSurvey(surveys));
                    }, false);
                } catch (_) {
                    finish(null);
                }
            }
            flagTimer = setTimeoutSafe(function () { proceed(); }, FLAGS_TIMEOUT_MS);
            try {
                off = sdk.onFeatureFlags(function () {
                    if (flagTimer != null) clearTimerSafe(flagTimer);
                    proceed();
                });
            } catch (_) {
                if (flagTimer != null) clearTimerSafe(flagTimer);
                proceed();
            }
        });
    }

    function showSurveyFlow() {
        if (flowRunning || dialogOpen) return Promise.resolve();
        flowRunning = true;
        return resolveSdk().then(function (sdk) {
            if (!sdk || typeof sdk.init !== 'function') return null;
            initPostHog(sdk);
            return fetchMatchingSurvey(sdk);
        }).then(function (survey) {
            if (!survey) return null;
            var choiceQuestion = resolveChoiceQuestion(survey);
            if (!choiceQuestion) {
                warn('layout survey: layout choice question schema invalid; survey hidden');
                return null;
            }
            var suggestionQuestion = resolveSuggestionQuestion(survey);
            if (!openDialog(survey, choiceQuestion, suggestionQuestion)) return null;
            sendShown();
            return survey;
        }).catch(function () {
            // 调查的任何故障都静默结束，不影响下载工作台
            return null;
        }).then(function (result) {
            flowRunning = false;
            return result;
        }, function () {
            flowRunning = false;
            return null;
        });
    }

    /* ============================================================
       自动展示与事件监听
    ============================================================ */

    function detectPageType() {
        try {
            var path = global.location && global.location.pathname;
            if (path && path.indexOf('pixiv-batch-alt') >= 0) return 'alt';
        } catch (_) {
            // 无法读取路径时按 batch 处理
        }
        return 'batch';
    }

    function onLayoutChanged(event) {
        var detail = event && event.detail;
        if (!detail || typeof detail.layout !== 'string') return;
        var layoutId = mapLayoutToken(detail.layout);
        if (!layoutId) return;
        recordSeen(layoutId, timers.now());
    }

    function onStorageEvent(event) {
        if (!event) return;
        if (event.key === STATE_KEY) {
            sessionState = null;
        } else if (event.key === SEEN_KEY) {
            sessionSeen = {};
        }
    }

    function registerListeners() {
        if (typeof global.addEventListener === 'function') {
            try {
                global.addEventListener('storage', onStorageEvent);
            } catch (_) {
                // 跨标签同步不可用时静默降级
            }
        }
        if (global.document && typeof global.document.addEventListener === 'function') {
            try {
                global.document.addEventListener('pixiv:batch-layout-changed', onLayoutChanged);
            } catch (_) {
                // 布局事件不可用时静默降级
            }
        }
    }

    function removeListeners() {
        if (typeof global.removeEventListener === 'function') {
            try {
                global.removeEventListener('storage', onStorageEvent);
            } catch (_) {
                // 清理尽力而为
            }
        }
        if (global.document && typeof global.document.removeEventListener === 'function') {
            try {
                global.document.removeEventListener('pixiv:batch-layout-changed', onLayoutChanged);
            } catch (_) {
                // 清理尽力而为
            }
        }
    }

    function scheduleAutoShow() {
        if (autoScheduled) return;
        autoScheduled = true;
        setTimeoutSafe(runAutoAttempt, autoDelay);
    }

    function runAutoAttempt() {
        if (autoClaimed) return;
        if (!config || !config.enabled) return;
        if (!stateAllowsShow(timers.now())) return;
        if (!isPageVisible()) return;
        if (hasBlockingOverlay()) {
            if (autoRetried) return;
            autoRetried = true;
            setTimeoutSafe(runAutoAttempt, AUTO_RETRY_DELAY_MS);
            return;
        }
        if (seenCount() < minDistinctLayouts) return;
        autoClaimed = true;
        showSurveyFlow();
    }

    /* ============================================================
       公共 API
    ============================================================ */

    function init(options) {
        if (initialized) return;
        initialized = true;
        options = options || {};
        pageType = options.page || detectPageType();
        storage = options.storage != null ? options.storage : safeLocalStorage();
        timers = options.timers || defaultTimers();
        fetchImpl = options.fetchImpl || defaultFetch();
        injectedAdapter = options.adapter || null;
        i18nClient = options.i18n || null;
        minDistinctLayouts = typeof options.minDistinctLayoutsSeen === 'number'
            ? options.minDistinctLayoutsSeen
            : MIN_DISTINCT_LAYOUTS_SEEN;
        autoDelay = typeof options.autoDelayMs === 'number'
            ? options.autoDelayMs
            : AUTO_DELAY_MS;
        config = readPublicConfig();
        if (!config) {
            warn('layout survey: public config missing; survey disabled');
            return;
        }
        if (typeof global.addEventListener !== 'function') {
            // 无事件环境（如纯脚本执行）不注册监听，仅保留手动 open
        }
        registerListeners();
        var now = timers.now();
        recordSeen(currentLayoutId(), now);
        loadAppVersion();
        scheduleAutoShow();
    }

    /**
     * 手动打开展示流程（调试 / 自动化测试入口）。不做自动门禁
     * （可见性 / 体验数量 / 本地状态），但受 enabled 配置约束。
     */
    function open() {
        if (!config || !config.enabled) return Promise.resolve(null);
        return showSurveyFlow();
    }

    function destroy() {
        if (dialogOpen) closeDialog(false);
        removeListeners();
        pendingTimers.forEach(function (id) {
            try {
                timers.clearTimeout(id);
            } catch (_) {
                // 清理尽力而为
            }
        });
        pendingTimers = [];
        initialized = false;
        autoScheduled = false;
        autoRetried = false;
        autoClaimed = false;
        flowRunning = false;
        submitting = false;
        realSdkPromise = null;
        sdkLoading = false;
        postHogInitialized = false;
        sessionState = null;
        appVersionPromise = null;
    }

    /**
     * 页面语言切换后刷新已打开弹窗的文案：
     * 不丢失布局选择、不丢失建议文本、不重建弹窗、不重复发送 survey shown。
     * 可传入页面当前 i18n client（页面每次切换语言都会重建 client 对象）。
     */
    function refreshLanguage(client) {
        if (client && typeof client.t === 'function') {
            i18nClient = client;
        }
        if (!dialogOpen) return;
        applyDialogTranslations();
    }

    global.PixivLayoutFeedback = Object.freeze({
        init: init,
        open: open,
        destroy: destroy,
        currentLayoutId: currentLayoutId,
        refreshLanguage: refreshLanguage,
        _internals: Object.freeze({
            LAYOUT_IDS: Object.freeze(LAYOUT_IDS.slice()),
            STATE_KEY: STATE_KEY,
            SEEN_KEY: SEEN_KEY,
            SNOOZE_MS: SNOOZE_MS,
            MIN_DISTINCT_LAYOUTS_SEEN: MIN_DISTINCT_LAYOUTS_SEEN,
            AUTO_DELAY_MS: AUTO_DELAY_MS,
            AUTO_RETRY_DELAY_MS: AUTO_RETRY_DELAY_MS,
            SUGGESTION_MAX_CODE_POINTS: SUGGESTION_MAX_CODE_POINTS,
            SURVEY_SCHEMA_VERSION: SURVEY_SCHEMA_VERSION,
            SDK_LOAD_TIMEOUT_MS: SDK_LOAD_TIMEOUT_MS,
            FLAGS_TIMEOUT_MS: FLAGS_TIMEOUT_MS,
            SURVEY_TOTAL_TIMEOUT_MS: SURVEY_TOTAL_TIMEOUT_MS,
            POSTHOG_JS_VERSION: POSTHOG_JS_VERSION,
            SDK_URL: SDK_URL,
            codePointLength: codePointLength,
            mapLayoutToken: mapLayoutToken,
            resolveChoiceQuestion: resolveChoiceQuestion,
            resolveSuggestionQuestion: resolveSuggestionQuestion,
            beforeSendFilter: beforeSendFilter,
            distinctSeenCount: distinctSeenCount
        })
    });
})(window);
