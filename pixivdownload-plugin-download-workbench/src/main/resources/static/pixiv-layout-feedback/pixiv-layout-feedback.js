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
 *   - 不调用 identify() / reset() 建立命名身份；solo 模式下使用服务端下发的调查
 *     作用域匿名身份（plf_ 前缀，由随机安装身份与当前调查 ID 单向派生，经
 *     bootstrap.distinctID + isIdentifiedID=false 初始化匿名 distinct ID，绝不发送
 *     data/install_identity.txt 的原始安装 UUID），multi 模式继续使用 SDK 生成的
 *     匿名浏览器 ID；
 *   - 不发送 Cookie / 账号 / 作品 / 路径 / 浏览器指纹；
 *   - autocapture / pageview / pageleave / replay / heatmap / error tracking / web vitals 全关；
 *   - before_send 只放行 survey shown / survey sent / survey dismissed 三个事件并做属性允许列表；
 *   - 本地弱去重（localStorage + 匿名身份）不是不可绕过的选举系统；
 *     solo 模式下「稍后再说 / 不再询问 / 已提交」与已体验布局由服务端
 *     /api/layout-feedback/state 以动作式命令 + revision / CAS 持久化到 state/，
 *     多个浏览器 / 设备共享同一去重结论；
 *   - 跨标签页弱去重：storage 事件与提交前 fresh 读取（serverBacked 下为强制 GET
 *     最新服务端状态）会关闭另一标签页已处理的弹窗，但无法消除两个标签页完全
 *     同时点击的竞态，不引入浏览器指纹或账号绑定。
 */
(function (global) {
    'use strict';

    /* ============================================================
       常量与纯函数（_internals 暴露给自动化测试）
    ============================================================ */

    var LAYOUT_IDS = ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt'];
    var STATE_KEY = 'pixiv:layout-feedback:state:v1';
    var SEEN_KEY = 'pixiv:layout-feedback:seen:v1';
    var SERVER_STATE_URL = '/api/layout-feedback/state';
    var SERVER_STATE_TIMEOUT_MS = 3 * 1000;
    var SERVER_SAVE_DEBOUNCE_MS = 400;
    var SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;
    var SCOPED_ID_PATTERN = /^plf_[0-9a-f]{64}$/;
    var MIN_DISTINCT_LAYOUTS_SEEN = 2;
    var AUTO_DELAY_MS = 10 * 1000;
    var AUTO_RETRY_DELAY_MS = 5 * 1000;
    var AUTO_OVERLAY_RETRY_LIMIT = 3;
    var AUTO_OVERLAY_RETRY_DELAY_MS = 5 * 1000;
    var AUTO_OVERLAY_RETRY_WINDOW_MS = 30 * 1000;
    var AUTO_RESCHEDULE_DELAY_MS = 0;
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
     * 判断值是否为合法 Date 对象（跨 realm 安全，不依赖 instanceof）。
     * 非法日期（getTime() 为 NaN）不算有效时间戳。
     * 完全 no-throw：null / undefined / Symbol.toStringTag 伪装对象 / getTime
     * 抛错或返回 NaN / 其它不可信对象一律返回 false，绝不让异常逃逸。
     */
    function isDateObject(value) {
        try {
            return value != null
                && Object.prototype.toString.call(value) === '[object Date]'
                && typeof value.getTime === 'function'
                && !Number.isNaN(value.getTime());
        } catch (_) {
            return false;
        }
    }

    /**
     * before_send 过滤器：只放行三个调查事件，并对属性执行允许列表，
     * 删除 $current_url / $referrer / $referring_domain / pathname / hostname
     * 等无关浏览器环境属性，保留 distinct_id、$survey_id、$survey_response_*
     * 与 SDK 必要协议字段。
     *
     * 顶层字段：posthog-js 1.409.5 会把 Survey 生命周期事件的 $set / $set_once /
     * $unset 提升为事件顶层字段（capture 方法内 b.$set = ...，详见 vendored
     * array.full.js 的 capture 实现）。$set 只用于写 person property
     * （$survey_<id>_responded / $survey_last_seen_date），与调查响应匹配无关，
     * 且本项目不调用 identify、不建立命名 Person，因此按最小化设计删除顶层
     * $set / $set_once / $unset，只保留摄取必需的 uuid / event / timestamp /
     * properties。
     *
     * timestamp：1.409.5 的 capture 在 before_send 阶段传递 Date 对象
     * （b.timestamp = (options.timestamp) || new Date()），这里原样保留 Date
     * 对象本身（不转字符串、不调用 Date.prototype.toJSON、不用当前时间替换）；
     * 合法 ISO 字符串同样保留（测试 / 防御性输入）。null / undefined / 未知
     * 类型一律省略，且不因未知类型抛错。
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
        var minimal = {event: event.event, properties: out};
        if (typeof event.uuid === 'string') minimal.uuid = event.uuid;
        if (isDateObject(event.timestamp) || typeof event.timestamp === 'string') {
            minimal.timestamp = event.timestamp;
        }
        return minimal;
    }

    /**
     * 判断 capture 返回值是否为 SDK 已接受事件的 CaptureResult。
     * posthog-js 1.409.5 的 capture() 成功时返回 CaptureResult 对象（含 event
     * 字段）；被丢弃（before_send 拒绝 / bot / 客户端限流 / DNT / 未初始化 /
     * 已 opt-out）时返回 undefined。null / undefined / false 均视为未接受。
     */
    function isAcceptedCaptureResult(result, expectedEventName) {
        return !!result
            && typeof result === 'object'
            && result.event === expectedEventName;
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
    var runtimeGeneration = 0;
    var sdkLoadOperation = null;
    var sdkInitSignature = null;
    var autoTimerId = null;
    var autoFlowStarted = false;
    var autoRetryCount = 0;
    var autoRetryStartedAt = 0;
    var flowRunning = false;
    var pendingSurveyCancel = null;
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
    var serverBacked = false;
    var serverIdentityAvailable = false;
    var serverDistinctId = null;
    var serverState = null;
    var serverSeen = null;
    var serverRevision = 0;
    var serverLoadOperation = null;
    var serverRefreshOperation = null;
    var serverRefreshInFlight = null;
    var serverCommandOperations = [];
    var pendingSeenLayouts = {};
    var serverSaveTimerId = null;
    var reconciled = false;

    /* ------------------------------------------------------------
       Runtime generation：异步生命周期代际。
       - init 从未初始化状态进入时生成新 generation；
       - destroy 使当前 generation 失效（initialized=false 且 generation 递增）；
       - 所有异步流程在启动时捕获 generation，continuation 在打开/关闭 DOM、
         写反馈状态、显示 Toast / 错误、请求 Survey、发送生命周期事件、
         修改 submitting / flowRunning 等状态之前验证 generation；
       - 旧 generation 的回调只能安全结束，不得影响新 generation。
       generation 不上传 PostHog、不写入 localStorage。
    ------------------------------------------------------------ */

    function nextRuntimeGeneration() {
        runtimeGeneration += 1;
        return runtimeGeneration;
    }

    function currentRuntimeGeneration() {
        return runtimeGeneration;
    }

    function isRuntimeGenerationActive(generation) {
        return initialized && generation === runtimeGeneration;
    }

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

    function isPlainObject(value) {
        return value != null && typeof value === 'object' && !Array.isArray(value);
    }

    function serverStateUrl() {
        return SERVER_STATE_URL + '?surveyId=' + encodeURIComponent(config.surveyId);
    }

    /**
     * 严格校验并应用服务端权威快照（GET / POST / 409 响应通用）。
     * - available / stateAvailable / scoped distinct ID 格式 / revision / state / seen
     *   全部合法才写入；非法响应整体回退（返回 false），不使用部分字段；
     * - state 只接受当前 surveyId 的状态（服务端已按请求 surveyId 过滤）；
     * - seen 只接受三个稳定布局 ID 与数值时间戳；
     * - 只写 server* / session* 变量，不直接写 localStorage（由调用方决定）。
     */
    function applyServerSnapshot(data) {
        if (!data || typeof data !== 'object') return false;
        if (data.available !== true) return false;
        if (typeof data.stateAvailable !== 'boolean') return false;
        var distinctId = typeof data.distinctId === 'string' ? data.distinctId : '';
        if (distinctId && !SCOPED_ID_PATTERN.test(distinctId)) return false;
        var revision = typeof data.revision === 'number' && data.revision >= 0
            ? data.revision
            : 0;
        var state = null;
        if (data.state != null) {
            if (!isPlainObject(data.state)) return false;
            if (typeof data.state.surveyId !== 'string'
                    || typeof data.state.status !== 'string') {
                return false;
            }
            if (data.state.status !== 'submitted' && data.state.status !== 'never'
                    && data.state.status !== 'snoozed') {
                return false;
            }
            if (data.state.status === 'snoozed'
                    && typeof data.state.snoozedUntil !== 'number') {
                return false;
            }
            state = {
                surveyId: data.state.surveyId,
                status: data.state.status,
                updatedAt: typeof data.state.updatedAt === 'number' ? data.state.updatedAt : 0,
                snoozedUntil: typeof data.state.snoozedUntil === 'number'
                    ? data.state.snoozedUntil
                    : 0
            };
        }
        var seen = {};
        if (data.seen != null) {
            if (!isPlainObject(data.seen)) return false;
            var validSeen = true;
            Object.keys(data.seen).forEach(function (key) {
                if (!validSeen) return;
                var entry = data.seen[key];
                if (LAYOUT_IDS.indexOf(key) < 0 || !isPlainObject(entry)
                        || typeof entry.firstSeenAt !== 'number'
                        || typeof entry.lastSeenAt !== 'number'
                        || entry.firstSeenAt < 0 || entry.lastSeenAt < 0) {
                    validSeen = false;
                    return;
                }
                seen[key] = {firstSeenAt: entry.firstSeenAt, lastSeenAt: entry.lastSeenAt};
            });
            if (!validSeen) return false;
        }
        serverIdentityAvailable = distinctId !== '';
        serverDistinctId = distinctId || null;
        serverBacked = data.stateAvailable && serverIdentityAvailable;
        serverRevision = revision;
        serverState = state;
        serverSeen = seen;
        sessionState = state;
        sessionSeen = Object.assign({}, seen);
        return true;
    }

    /**
     * 可取消的 solo 模式服务端上下文装载 operation。
     * - 同一 generation 只创建一个请求，重复调用返回同一 promise；
     * - 成功且 available=true：启用服务端 scoped 身份；stateAvailable=true 时启用
     *   serverBacked（服务端状态权威），随后先执行有限本地状态回放（reconciliation，
     *   必须在覆盖本地缓存之前读取），再用权威快照更新本地协调缓存；
     * - 403（multi 模式）/ 网络失败 / 超时 / 非法响应：整体回退 local 模式，
     *   调查仍按浏览器本地去重工作；
     * - 超时 abort 并 resolve，不永久 pending；destroy 时 cancel；
     * - 只有 generation 仍活动且未 settled 才允许写 server* 变量。
     */
    function loadServerContext(generation) {
        if (serverLoadOperation && serverLoadOperation.generation === generation) {
            return serverLoadOperation.promise;
        }
        var operation = {
            generation: generation,
            promise: null,
            abortController: null,
            timeoutId: null,
            settled: false,
            cancel: null
        };
        serverLoadOperation = operation;
        operation.promise = new Promise(function (resolve) {
            var settled = false;
            function finish() {
                if (settled) return;
                settled = true;
                operation.settled = true;
                if (operation.timeoutId != null) {
                    clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                resolve();
            }
            operation.cancel = function () {
                if (settled) return;
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish();
            };
            operation.timeoutId = setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish();
            }, SERVER_STATE_TIMEOUT_MS);
            var init = {
                credentials: 'same-origin',
                headers: {'Accept': 'application/json'}
            };
            if (typeof global.AbortController === 'function') {
                operation.abortController = new global.AbortController();
                init.signal = operation.abortController.signal;
            }
            var request = null;
            try {
                request = fetchImpl(serverStateUrl(), init);
            } catch (_) {
                finish();
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish();
                return;
            }
            request.then(function (response) {
                if (!response || !response.ok) throw new Error('http');
                return response.json();
            }).then(function (data) {
                if (!isRuntimeGenerationActive(generation) || settled) throw new Error('stale');
                if (!applyServerSnapshot(data)) throw new Error('invalid');
                if (serverBacked) {
                    reconcileLocalState(generation);
                    writeServerCacheToLocal();
                }
            }).catch(function () {
                // 服务端不可用 / 非法响应：保留 localStorage 模式
            }).then(finish);
        });
        return operation.promise;
    }

    /**
     * 强制重新 GET 最新服务端状态（提交前 preflight / storage 提示后的有限刷新）。
     * 同一时间最多一个在途请求；超时 abort；失败 resolve(false)，调用方安全降级。
     * 成功后用权威快照更新本地协调缓存（不做重复 reconciliation）。
     */
    function refreshServerContext(generation) {
        if (!isRuntimeGenerationActive(generation)) return Promise.resolve(false);
        if (serverRefreshInFlight) return serverRefreshInFlight;
        serverRefreshInFlight = new Promise(function (resolve) {
            var settled = false;
            var operation = {
                generation: generation,
                aborted: false,
                abortController: null,
                cancel: null
            };
            serverRefreshOperation = operation;
            function finish(result) {
                if (settled) return;
                settled = true;
                clearTimerSafe(operation.timeoutId);
                serverRefreshOperation = null;
                resolve(result);
            }
            operation.cancel = function () {
                if (settled) return;
                operation.aborted = true;
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish(false);
            };
            operation.timeoutId = setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                operation.cancel();
            }, SERVER_STATE_TIMEOUT_MS);
            var init = {
                credentials: 'same-origin',
                headers: {'Accept': 'application/json'}
            };
            if (typeof global.AbortController === 'function') {
                operation.abortController = new global.AbortController();
                init.signal = operation.abortController.signal;
            }
            var request = null;
            try {
                request = fetchImpl(serverStateUrl(), init);
            } catch (_) {
                finish(false);
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish(false);
                return;
            }
            request.then(function (response) {
                if (!response || !response.ok) throw new Error('http');
                return response.json();
            }).then(function (data) {
                if (!isRuntimeGenerationActive(generation)) throw new Error('stale');
                if (!applyServerSnapshot(data)) throw new Error('invalid');
                if (serverBacked) writeServerCacheToLocal();
                finish(true);
            }).catch(function () {
                finish(false);
            });
        });
        var promise = serverRefreshInFlight;
        promise.then(function () {
            if (serverRefreshInFlight === promise) serverRefreshInFlight = null;
        });
        return promise;
    }

    /**
     * 发送服务端状态命令（动作式协议 + revision / CAS）。
     * - 构造 {expectedRevision, surveyId, command[, layoutIds]}，POST JSON；
     * - 200 / 409 都解析完整快照并更新权威 serverRevision / serverState / serverSeen
     *   （并同步本地协调缓存）；
     * - 409 后按命令语义最多重试一次（record_seen 始终可重试；submitted / never /
     *   snooze 仅在当前状态允许升级时重试，绝不把 submitted / never 降级）；
     * - 网络错误 / 非法响应安全降级：resolve({ok:false})，不抛未处理 rejection，
     *   不影响下载工作台；
     * - 不发送用户建议、布局选择、Cookie、token 或原始安装身份。
     */
    function sendServerCommand(generation, command, options) {
        options = options || {};
        var layoutIds = options.layoutIds || null;
        return new Promise(function (resolve) {
            var settled = false;
            var attemptCount = 0;
            var operation = {
                generation: generation,
                aborted: false,
                abortController: null
            };
            var operationKey = serverCommandOperations.length;
            serverCommandOperations.push(operation);
            function finish(result) {
                if (settled) return;
                settled = true;
                serverCommandOperations[operationKey] = null;
                resolve(result);
            }
            function attempt() {
                if (!isRuntimeGenerationActive(generation) || operation.aborted) {
                    finish({ok: false, conflict: false});
                    return;
                }
                attemptCount++;
                var body = {
                    expectedRevision: serverRevision,
                    surveyId: config.surveyId,
                    command: command
                };
                if (layoutIds) body.layoutIds = layoutIds;
                var init = {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
                    body: JSON.stringify(body),
                    credentials: 'same-origin'
                };
                if (typeof global.AbortController === 'function') {
                    operation.abortController = new global.AbortController();
                    init.signal = operation.abortController.signal;
                }
                var request = null;
                try {
                    request = fetchImpl(serverStateUrl(), init);
                } catch (_) {
                    finish({ok: false, conflict: false});
                    return;
                }
                if (!request || typeof request.then !== 'function') {
                    finish({ok: false, conflict: false});
                    return;
                }
                request.then(function (response) {
                    if (!response) throw new Error('http');
                    if (!response.ok) {
                        if (response.status === 409 && attemptCount < 2) {
                            // 409 分支内部完成冲突快照应用与重试；返回值（undefined）
                            // 由外层 then 的 data==null 守卫跳过，避免二次 apply 或提前 finish。
                            return response.json().then(function (data) {
                                if (!isRuntimeGenerationActive(generation)) {
                                    throw new Error('stale');
                                }
                                if (!applyServerSnapshot(data)) throw new Error('invalid snapshot');
                                // 冲突快照是权威状态：同步本地协调缓存。
                                if (serverBacked) writeServerCacheToLocal();
                                if (!shouldRetryAfterConflict(command, data)) {
                                    finish({ok: false, conflict: true});
                                    return;
                                }
                                attempt();
                            });
                        }
                        throw new Error('http ' + response.status);
                    }
                    return response.json();
                }).then(function (data) {
                    if (!isRuntimeGenerationActive(generation)) throw new Error('stale');
                    if (data == null) return;
                    if (!applyServerSnapshot(data)) throw new Error('invalid snapshot');
                    if (serverBacked) writeServerCacheToLocal();
                    finish({ok: true, conflict: false});
                }).catch(function () {
                    finish({ok: false, conflict: false});
                });
            }
            attempt();
        });
    }

    /** 409 后是否允许基于最新 revision 重试：绝不降级 submitted / never。 */
    function shouldRetryAfterConflict(command, data) {
        if (command === 'record_seen') return true;
        var state = data && isPlainObject(data.state) ? data.state : null;
        var status = state && state.surveyId === config.surveyId
            && typeof state.status === 'string'
            ? state.status
            : null;
        if (command === 'submitted') return status !== 'submitted';
        if (command === 'never') return status !== 'submitted' && status !== 'never';
        if (command === 'snooze') return status !== 'submitted' && status !== 'never';
        return false;
    }

    /**
     * 服务端恢复后的有限本地状态回放（每次 init 最多一次）：
     * - localStorage STATE_KEY 中同一 Survey 的 submitted / never / 有效 snoozed
     *   比服务端更强时用 server command 回放（不上传客户端时间；snooze 由服务端
     *   重新计算 7 天）；
     * - 本地 SEEN_KEY 只提取三个合法布局 ID，经 record_seen 合并；
     * - generation-safe，不发送 PostHog 事件，失败保留本地回退。
     */
    function reconcileLocalState(generation) {
        if (!isRuntimeGenerationActive(generation)) return;
        if (reconciled) return;
        reconciled = true;
        var localState = readLocalStateRaw();
        if (localState && isPlainObject(localState) && localState.surveyId === config.surveyId) {
            var status = localState.status;
            var now = timers.now();
            var localStrong = (status === 'submitted' || status === 'never'
                    || (status === 'snoozed'
                        && now < (typeof localState.snoozedUntil === 'number'
                            ? localState.snoozedUntil : 0)));
            if (localStrong) {
                var serverStatus = serverState && serverState.surveyId === config.surveyId
                    ? serverState.status
                    : null;
                if (!serverStatus || statusPriority(status) > statusPriority(serverStatus)) {
                    var command = status === 'snoozed' ? 'snooze' : status;
                    sendServerCommand(generation, command).then(function (result) {
                        if (!result.ok) {
                            warn('layout survey: local state replay failed; keeping local fallback');
                        }
                    }).catch(function () { /* 安全降级 */ });
                }
            }
        }
        var localSeen = readLocalSeenRaw();
        var layoutIds = [];
        LAYOUT_IDS.forEach(function (id) {
            var entry = localSeen && localSeen[id];
            if (entry && typeof entry.lastSeenAt === 'number' && entry.lastSeenAt > 0) {
                layoutIds.push(id);
            }
        });
        if (layoutIds.length) {
            sendServerCommand(generation, 'record_seen', {layoutIds: layoutIds})
                .catch(function () { /* 安全降级 */ });
        }
    }

    function statusPriority(status) {
        if (status === 'submitted') return 3;
        if (status === 'never') return 2;
        if (status === 'snoozed') return 1;
        return 0;
    }

    function readLocalStateRaw() {
        if (!storage) return null;
        var raw = null;
        try {
            raw = storage.getItem(STATE_KEY);
        } catch (_) {
            return null;
        }
        if (!raw) return null;
        try {
            var parsed = JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (_) {
            return null;
        }
    }

    function readLocalSeenRaw() {
        if (!storage) return {};
        var raw = null;
        try {
            raw = storage.getItem(SEEN_KEY);
        } catch (_) {
            return {};
        }
        if (!raw) return {};
        try {
            var parsed = JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : {};
        } catch (_) {
            return {};
        }
    }

    /**
     * 用服务端权威快照更新本地协调缓存（跨标签页即时提示 + 服务端恢复前的
     * 临时保护；不代表服务器 revision）。
     */
    function writeServerCacheToLocal() {
        if (!storage) return;
        try {
            if (serverState && serverState.surveyId === config.surveyId) {
                storage.setItem(STATE_KEY, JSON.stringify(serverState));
            } else {
                storage.removeItem(STATE_KEY);
            }
            storage.setItem(SEEN_KEY, JSON.stringify(serverSeen || {}));
        } catch (_) {
            // 协调缓存尽力而为
        }
    }

    /**
     * 布局体验记录合并：serverBacked 下仍写 SEEN_KEY 本地协调缓存，并把待同步布局
     * 加入 pendingSeenLayouts，去抖后以 record_seen 命令提交（不再发送完整 seen）。
     */
    function scheduleServerSeenFlush() {
        if (!serverBacked) return;
        var seen = serverSeen || {};
        LAYOUT_IDS.forEach(function (id) {
            if (seen[id] && !pendingSeenLayouts[id]) pendingSeenLayouts[id] = true;
        });
        if (serverSaveTimerId != null) clearTimerSafe(serverSaveTimerId);
        serverSaveTimerId = setTimeoutSafe(function () {
            serverSaveTimerId = null;
            flushServerSeen();
        }, SERVER_SAVE_DEBOUNCE_MS);
    }

    function flushServerSeen() {
        if (!serverBacked) return;
        var generation = currentRuntimeGeneration();
        var layoutIds = Object.keys(pendingSeenLayouts).filter(function (id) {
            return LAYOUT_IDS.indexOf(id) >= 0;
        });
        if (!layoutIds.length) return;
        sendServerCommand(generation, 'record_seen', {layoutIds: layoutIds}).then(function (result) {
            if (result && result.ok) {
                layoutIds.forEach(function (id) { delete pendingSeenLayouts[id]; });
            }
            // 失败保留 pending：下一次 recordSeen 会重新调度去抖提交
        }).catch(function () { /* 安全降级 */ });
    }

    /**
     * 直接读取持久化状态（不走 sessionState 缓存），并以此刷新缓存。
     * storage 不可用 / 不可读时降级到内存态；STATE_KEY JSON 损坏时尝试
     * removeItem 清理并清空 sessionState，绝不因存储损坏中断页面。
     */
    function readStateFresh() {
        if (!config) return null;
        if (serverBacked) {
            sessionState = serverState;
            return serverState;
        }
        if (!storage) return sessionState;
        var raw = null;
        try {
            raw = storage.getItem(STATE_KEY);
        } catch (_) {
            // 存储不可读：保留内存态。
            return sessionState;
        }
        if (!raw) {
            sessionState = null;
            return null;
        }
        var parsed = null;
        try {
            var candidate = JSON.parse(raw);
            if (candidate && typeof candidate === 'object') parsed = candidate;
        } catch (_) {
            parsed = null;
        }
        if (!parsed) {
            // 损坏 JSON：清理并清空会话状态。
            try {
                storage.removeItem(STATE_KEY);
            } catch (_) {
                // 清理尽力而为。
            }
            sessionState = null;
            return null;
        }
        sessionState = parsed;
        return parsed;
    }

    function readState() {
        if (!config) return null;
        var state = readStateFresh();
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
        if (serverBacked) {
            serverState = state;
            // 本地协调缓存：同一浏览器另一标签页即时提示 + 服务端恢复前的临时保护；
            // 不代表服务器 revision，权威状态以服务端快照为准。
            if (storage) {
                try {
                    storage.setItem(STATE_KEY, JSON.stringify(state));
                } catch (_) {
                    // 存储不可用时仅保留内存态
                }
            }
            // 动作式命令：submitted / never / snooze（snoozedUntil 由服务端重新计算）。
            var command = status === 'submitted' ? 'submitted'
                : status === 'never' ? 'never' : 'snooze';
            var generation = currentRuntimeGeneration();
            sendServerCommand(generation, command).then(function (result) {
                if (!result.ok) {
                    warn('layout survey: server state save failed; keeping local fallback');
                }
            }).catch(function () { /* 安全降级 */ });
            return;
        }
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
        if (serverBacked) {
            sessionSeen = Object.assign({}, serverSeen);
            return Object.assign({}, serverSeen);
        }
        if (!storage) return Object.assign({}, sessionSeen);
        var raw = null;
        try {
            raw = storage.getItem(SEEN_KEY);
        } catch (_) {
            // 存储不可读：保留会话记录。
            return Object.assign({}, sessionSeen);
        }
        if (!raw) return Object.assign({}, sessionSeen);
        var parsed = null;
        try {
            var candidate = JSON.parse(raw);
            if (candidate && typeof candidate === 'object') parsed = candidate;
        } catch (_) {
            parsed = null;
        }
        if (!parsed) {
            // 损坏 JSON：清理并清空会话记录。
            try {
                storage.removeItem(SEEN_KEY);
            } catch (_) {
                // 清理尽力而为。
            }
            sessionSeen = {};
            return {};
        }
        sessionSeen = parsed;
        return Object.assign({}, parsed);
    }

    function writeSeen(seen) {
        sessionSeen = seen;
        if (serverBacked) {
            serverSeen = seen;
            // serverBacked 下仍写 SEEN_KEY 本地协调缓存（同浏览器跨标签体验阈值同步），
            // 待同步布局经 pendingSeenLayouts 去抖后以 record_seen 命令提交。
            if (storage) {
                try {
                    storage.setItem(SEEN_KEY, JSON.stringify(seen));
                } catch (_) {
                    // 存储不可用时仅保留会话记录
                }
            }
            scheduleServerSeenFlush();
            return;
        }
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
        var sdkConfig = {
            api_host: config.apiHost,
            ui_host: config.uiHost,
            autocapture: false,
            capture_pageview: false,
            capture_pageleave: false,
            capture_performance: false,
            capture_dead_clicks: false,
            capture_exceptions: false,
            capture_heatmaps: false,
            disable_session_recording: true,
            disable_surveys: false,
            person_profiles: 'identified_only',
            persistence: 'localStorage',
            cross_subdomain_cookie: false,
            respect_dnt: true,
            save_campaign_params: false,
            save_referrer: false,
            rageclick: false,
            disable_surveys_automatic_display: true,
            advanced_only_evaluate_survey_feature_flags: true,
            disable_external_dependency_loading: true,
            feature_flag_request_timeout_ms: 5000,
            surveys_request_timeout_ms: 15000,
            mask_all_text: true,
            mask_all_element_attributes: true,
            before_send: beforeSendFilter
        };
        // solo 模式：用服务端下发的调查作用域身份初始化匿名 distinct ID。
        // posthog-js 1.409.5 只通过 bootstrap.distinctID 初始化身份（isIdentifiedID=false
        // 表示匿名安装作用域，不触发 identify 语义）；sdkConfig.distinct_id 不参与
        // 初始化，一律不得使用。multi 模式 / 服务端不可用时不设置 bootstrap，
        // 保持 SDK 生成的匿名浏览器 ID。
        if (serverIdentityAvailable && serverDistinctId) {
            sdkConfig.bootstrap = {
                distinctID: serverDistinctId,
                isIdentifiedID: false
            };
        }
        return sdkConfig;
    }

    /**
     * DNT / opt-out 门禁：在 PostHog 初始化完成后、请求 Survey 之前检查。
     * has_opted_out_capturing() 与 is_capturing() 都是 1.409.5 的正式公开方法
     * （vendored array.full.js 中 this.has_opted_out_capturing=... 直接暴露）。
     * opt-out / 不捕获时静默结束：不请求 Survey、不显示弹窗、不发 shown、
     * 不写任何反馈状态、不向用户显示错误。方法不存在或抛错时不得阻断调查。
     */
    function isCapturingDisabled(sdk) {
        if (!sdk) return false;
        try {
            if (typeof sdk.has_opted_out_capturing === 'function'
                    && sdk.has_opted_out_capturing()) {
                return true;
            }
        } catch (_) {
            // 兼容性失败不得阻断调查。
        }
        try {
            if (typeof sdk.is_capturing === 'function'
                    && sdk.is_capturing() === false) {
                return true;
            }
        } catch (_) {
            // 兼容性失败不得阻断调查。
        }
        return false;
    }

    /**
     * 可取消的 SDK 加载器。保存显式操作状态（generation / promise / script /
     * timeoutId / settled / cancel），不依赖私有 API 判断加载状态：
     * - 已存在公开可用的 global.posthog（含 init 函数）时直接复用，不插 script；
     * - 同一 generation 已有未取消的加载操作时返回同一个 Promise，不插第二个 script；
     * - load 成功：清理 timeout 与 listener，generation 已失效则 resolve(null)；
     * - load 失败 / 超时：清理 listener 与 timeout，resolve(null)，不永久悬挂；
     * - cancel()（destroy 调用）：幂等，清理 timeout 与 listener，尝试移除模块
     *   创建的 script，Promise 必然安全完成；浏览器后续即使完成底层资源下载，
     *   旧回调也因 listener 已移除 + settled 守卫不生效。
     */
    function loadSdkScript(generation) {
        if (global.posthog && typeof global.posthog.init === 'function') {
            return Promise.resolve(global.posthog);
        }
        if (sdkLoadOperation && sdkLoadOperation.generation === generation) {
            return sdkLoadOperation.promise;
        }
        var operation = {
            generation: generation,
            promise: null,
            script: null,
            timeoutId: null,
            settled: false,
            cancel: null,
            onLoad: null,
            onError: null
        };
        sdkLoadOperation = operation;
        operation.promise = new Promise(function (resolve) {
            var script = null;
            var settled = false;
            function finish(sdk) {
                if (settled) return;
                settled = true;
                operation.settled = true;
                if (operation.timeoutId != null) {
                    clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (script) {
                    try {
                        script.removeEventListener('load', operation.onLoad);
                        script.removeEventListener('error', operation.onError);
                    } catch (_) {
                        // 清理尽力而为
                    }
                }
                resolve(sdk);
            }
            operation.cancel = function () {
                if (settled) return;
                finish(null);
                if (script && script.parentNode) {
                    try {
                        script.parentNode.removeChild(script);
                    } catch (_) {
                        // 移除尽力而为
                    }
                }
            };
            operation.onLoad = function () {
                var sdk = global.posthog && typeof global.posthog.init === 'function'
                    ? global.posthog
                    : null;
                if (!isRuntimeGenerationActive(generation)) sdk = null;
                finish(sdk);
            };
            operation.onError = function () {
                finish(null);
            };
            operation.timeoutId = setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                finish(null);
                if (script && script.parentNode) {
                    try {
                        script.parentNode.removeChild(script);
                    } catch (_) {
                        // 移除尽力而为
                    }
                }
            }, SDK_LOAD_TIMEOUT_MS);
            try {
                script = global.document.createElement('script');
                script.src = SDK_URL;
                script.async = true;
                script.addEventListener('load', operation.onLoad);
                script.addEventListener('error', operation.onError);
                operation.script = script;
                var head = global.document.head || documentElement();
                (head || global.document.body || global.document.documentElement).appendChild(script);
            } catch (_) {
                finish(null);
            }
        });
        return operation.promise;
    }

    /**
     * PostHog 初始化幂等（不访问 __loaded 等私有字段）：
     * - 同一页面中本模块已用相同公开配置签名初始化过的 singleton 直接复用，
     *   不再次调用 sdk.init；
     * - 先前的 init 抛错时不记录成功签名，后续允许重新尝试；
     * - 重新 init 时配置签名发生变化 → fail closed：不静默切换 Project、
     *   不把事件发送到不确定项目，只记录不含 token / Survey ID / scoped ID /
     *   查询参数的 safe warning，当前页面不显示调查。
     * - 签名纳入 projectToken / apiHost / uiHost / posthog-js 版本 / identity 模式
     *   （server-scoped 或 browser-anonymous）/ scoped distinct ID：同一页面中的
     *   singleton 不会在身份变化时被静默复用。
     */
    function initPostHog(sdk) {
        if (!sdk || typeof sdk.init !== 'function') return true;
        var identityMode = serverIdentityAvailable ? 'server-scoped' : 'browser-anonymous';
        var identity = serverIdentityAvailable && serverDistinctId ? serverDistinctId : '';
        var signature = config.projectToken + '|' + config.apiHost + '|'
            + config.uiHost + '|' + POSTHOG_JS_VERSION + '|' + identityMode + '|' + identity;
        if (sdkInitSignature === signature) return true;
        if (sdkInitSignature !== null) {
            warn('layout survey: posthog already initialized with a different public configuration by this module; survey disabled for this page');
            return false;
        }
        try {
            sdk.init(config.projectToken, buildSdkConfig());
        } catch (_) {
            sdkInitSignature = null;
            throw _;
        }
        sdkInitSignature = signature;
        return true;
    }

    /**
     * SDK 初始化完成后验证匿名 distinct ID 与调查作用域身份一致（solo 模式）。
     * - SDK 公开提供 get_distinct_id() 时必须验证；不一致 fail closed：不请求
     *   Survey、不发送事件、不显示弹窗；
     * - 记录不含实际 ID / token / Survey ID 的安全 warning；
     * - 不得通过 identify() / reset() 修正不一致。
     */
    function verifySdkDistinctId(sdk) {
        if (typeof sdk.get_distinct_id !== 'function') {
            warn('layout survey: posthog sdk does not expose get_distinct_id; survey disabled for this page');
            return false;
        }
        var actual = null;
        try {
            actual = sdk.get_distinct_id();
        } catch (_) {
            actual = null;
        }
        if (actual !== serverDistinctId) {
            warn('layout survey: posthog distinct id does not match the server scoped identity; survey disabled for this page');
            return false;
        }
        return true;
    }

    function resolveSdk(generation) {
        if (injectedAdapter) return Promise.resolve(injectedAdapter);
        return loadSdkScript(generation);
    }

    /* ============================================================
       事件发送
    ============================================================ */

    function sendSurveyEvent(generation, name, properties) {
        return new Promise(function (resolve, reject) {
            var settled = false;
            function finish(accepted) {
                if (settled) return;
                settled = true;
                if (accepted) resolve();
                else reject(new Error('posthog capture rejected event: ' + name));
            }
            resolveSdk(generation).then(function (sdk) {
                if (!isRuntimeGenerationActive(generation)) {
                    // destroy 后旧 generation 不再发送生命周期事件
                    finish(false);
                    return;
                }
                if (!sdk || typeof sdk.capture !== 'function') {
                    finish(false);
                    return;
                }
                try {
                    var result = sdk.capture(name, properties);
                    // 只有 capture 返回非空 CaptureResult 对象（result.event === name）
                    // 才视为 SDK 已接受事件；undefined / null / false 均视为未接受。
                    // 这只证明 SDK 本地接受了事件，不保证 PostHog 服务端最终入库。
                    finish(isAcceptedCaptureResult(result, name));
                } catch (_) {
                    finish(false);
                }
            }, function () {
                finish(false);
            });
        });
    }

    /**
     * 构建生命周期事件属性。generation 已失效时解析为 null，调用方据此跳过
     * 发送；属性本身不包含 generation。
     */
    function surveyEventProperties(generation, extra) {
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
            if (!isRuntimeGenerationActive(generation)) return null;
            props.app_version = version || 'unknown';
            return props;
        });
    }

    function sendShown() {
        if (shownSent || !dialogSurveyId) return;
        var generation = currentRuntimeGeneration();
        shownSent = true;
        surveyEventProperties(generation).then(function (props) {
            if (!props) return;
            return sendSurveyEvent(generation, 'survey shown', props);
        }).catch(function () {
            // shown 发送失败不影响用户填写调查
        });
    }

    function sendDismissedBestEffort() {
        if (!dialogSurveyId) return Promise.resolve();
        var generation = currentRuntimeGeneration();
        return surveyEventProperties(generation).then(function (props) {
            if (!props) return;
            return sendSurveyEvent(generation, 'survey dismissed', props);
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
        var generation = currentRuntimeGeneration();
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
            // 不设原生 maxlength：它按 UTF-16 code unit 计数，与 1000 个
            // Unicode code point 的限制语义冲突；截断统一在 input 事件中按
            // code point 完成（onSuggestionInput）。
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
            privacy.textContent = t('privacy', '提交后会向 PostHog 发送你的布局选择、可选建议、应用版本和匿名调查标识。单人模式的标识由随机安装身份与当前调查 ID 单向派生，仅用于该调查在同一安装的多个浏览器或访问设备之间去重；多人模式使用 PostHog 生成的匿名浏览器标识。不会发送 Pixiv Cookie、账号信息、作品信息、下载内容、本地路径或原始安装身份。');

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
                if (!isRuntimeGenerationActive(generation)) return;
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
        if (dialogElements && dialogElements.textarea) {
            var value = String(dialogElements.textarea.value);
            var points = Array.from(value);
            if (points.length > SUGGESTION_MAX_CODE_POINTS) {
                // 按 code point 截断（不会切断代理对 / 组合字符）。
                dialogElements.textarea.value = points.slice(0, SUGGESTION_MAX_CODE_POINTS).join('');
            }
        }
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
        if (codePointLength(suggestion) > SUGGESTION_MAX_CODE_POINTS) {
            lastErrorKey = 'error-suggestion-too-long';
            showError('error-suggestion-too-long');
            return;
        }
        submitting = true;
        setSubmittingState(true);
        hideError();

        // 发送前执行一次不走缓存的持久化状态读取：另一标签页可能已提交 /
        // 永久关闭 / 进入有效 snooze，此时取消本次提交并关闭弹窗，不发送
        // 第二条 survey sent（弱去重，无法消除完全同时点击的竞态）。
        var freshState = readStateFresh();
        if (freshState && freshState.surveyId === dialogSurveyId) {
            var stateNow = timers.now();
            if (freshState.status === 'submitted' || freshState.status === 'never'
                    || (freshState.status === 'snoozed'
                        && stateNow < (typeof freshState.snoozedUntil === 'number'
                            ? freshState.snoozedUntil
                            : 0))) {
                submitting = false;
                closeDialog(true);
                showHandledElsewhereNote();
                return;
            }
        }

        var choiceId = dialogChoiceQuestion.id;
        var suggestionQuestion = dialogSuggestionQuestion;
        var suggestionId = suggestionQuestion ? suggestionQuestion.id : null;
        var surveyId = dialogSurveyId;
        var snapshot = layoutSnapshot;
        var generation = currentRuntimeGeneration();

        // serverBacked：提交前强制 GET 最新服务端状态（跨设备 preflight）。
        // 同一 Survey 已被 submitted / never / 未到期 snoozed → 取消本次 capture、
        // 关闭弹窗、显示「已在其他页面处理」、不发送 dismissed。
        // 服务端刷新失败按安全降级放行：不能因服务器暂时不可用而阻止用户提交反馈。
        // 这是弱去重：两台设备完全同时通过 preflight 并 capture 仍可能产生重复事件，
        // 不引入账号绑定、IP 或浏览器指纹。
        var preflight = Promise.resolve(true);
        if (serverBacked) {
            preflight = refreshServerContext(generation).then(function (fresh) {
                if (!isRuntimeGenerationActive(generation)) return false;
                if (!fresh) return true;
                var state = readState();
                var now = timers.now();
                if (state && (state.status === 'submitted' || state.status === 'never'
                        || (state.status === 'snoozed'
                            && now < (typeof state.snoozedUntil === 'number'
                                ? state.snoozedUntil : 0)))) {
                    submitting = false;
                    closeDialog(true);
                    showHandledElsewhereNote();
                    return false;
                }
                return true;
            });
        }
        preflight.then(function (allowed) {
            if (!isRuntimeGenerationActive(generation)) return;
            if (!allowed) return;
            return surveyEventProperties(generation, {}).then(function (base) {
                if (!base) return;
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
                return sendSurveyEvent(generation, 'survey sent', props);
            }).then(function () {
                // capture 已同步返回被接受的 CaptureResult，但若 generation 在结果
                // 处理前已失效（destroy），旧回调不得再写状态 / 显示 Toast / 动 DOM。
                if (!isRuntimeGenerationActive(generation)) return;
                submitting = false;
                // PostHog 已接受：本地同步写 submitted（含服务端 submitted 命令；
                // 服务端保存失败不撤销已接受的提交，保留本地回退，不显示
                // “PostHog 提交失败”，只记录不含用户数据的 warning）。
                writeState('submitted');
                closeDialog(true);
                showSuccessToast();
            });
        }).catch(function () {
            if (!isRuntimeGenerationActive(generation)) return;
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

    function showHandledElsewhereNote() {
        // 非阻塞提示：同一调查已在另一标签页处理。不重复发送 dismissed、
        // 不改写其它标签页的状态、不显示提交失败。
        if (global.PixivFeedback && typeof global.PixivFeedback.toast === 'function') {
            try {
                global.PixivFeedback.toast({kind: 'info', message: t('handled-elsewhere', '该布局调查已在其他标签页处理。')});
            } catch (_) {
                // 提示失败不影响已关闭的弹窗
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

    function fetchMatchingSurvey(sdk, generation) {
        return new Promise(function (resolve) {
            var settled = false;
            var surveyRequested = false;
            var off = null;
            var flagTimer = null;
            var totalTimer = setTimeoutSafe(function () { finish(null); }, SURVEY_TOTAL_TIMEOUT_MS);
            function finish(survey) {
                if (settled) return;
                settled = true;
                clearTimerSafe(totalTimer);
                if (flagTimer != null) clearTimerSafe(flagTimer);
                if (typeof off === 'function') {
                    try { off(); } catch (_) { /* 解除监听尽力而为 */ }
                    off = null;
                }
                if (pendingSurveyCancel === cancel) pendingSurveyCancel = null;
                resolve(survey);
            }
            function cancel() {
                // destroy() 等场景的外部取消：终止 flags 监听与定时器，让
                // 待处理 Promise 安全结束（此后 getActiveMatchingSurveys
                // 回调因 settled 直接返回，不产生额外副作用）。
                finish(null);
            }
            function proceed() {
                if (settled || surveyRequested) return;
                if (!isRuntimeGenerationActive(generation)) return;
                surveyRequested = true;
                if (flagTimer != null) clearTimerSafe(flagTimer);
                try {
                    sdk.getActiveMatchingSurveys(function (surveys) {
                        if (settled) return;
                        finish(findTargetSurvey(surveys));
                    }, false);
                } catch (_) {
                    finish(null);
                }
            }
            pendingSurveyCancel = cancel;
            flagTimer = setTimeoutSafe(function () { proceed(); }, FLAGS_TIMEOUT_MS);
            try {
                off = sdk.onFeatureFlags(function () {
                    proceed();
                });
            } catch (_) {
                off = null;
                if (flagTimer != null) clearTimerSafe(flagTimer);
                proceed();
            }
            // onFeatureFlags 可能在注册阶段同步调用 callback 并完成流程：
            // 此时取消订阅返回值刚产生，必须立即注销，避免残留活动监听。
            if (settled && typeof off === 'function') {
                try { off(); } catch (_) { /* 解除监听尽力而为 */ }
                off = null;
            }
        });
    }

    /**
     * 调查展示流程。必须先等待服务端身份上下文（loadServerContext）确定，再加载 /
     * 初始化 SDK：不允许先用浏览器 ID init、稍后再收到安装 scoped ID。
     * - 身份确定后重新检查 generation / 状态门禁（自动流程；手动 open 经
     *   skipStateGate 保留调试绕过语义）/ DNT / config；
     * - solo scoped 身份存在时验证 get_distinct_id() 一致，不一致 fail closed；
     * - 自动流程在服务端 state = submitted / never / 有效 snooze 时不加载 SDK、
     *   不请求 Survey、不显示。
     */
    function showSurveyFlow(options) {
        if (!initialized || flowRunning || dialogOpen) return Promise.resolve(null);
        options = options || {};
        var generation = currentRuntimeGeneration();
        flowRunning = true;
        function finishFlow(result) {
            if (isRuntimeGenerationActive(generation)) flowRunning = false;
            return result;
        }
        return loadServerContext(generation).then(function () {
            if (!isRuntimeGenerationActive(generation)) return null;
            if (!config || !config.enabled) return null;
            if (!options.skipStateGate && !stateAllowsShow(timers.now())) return null;
            return resolveSdk(generation);
        }).then(function (sdk) {
            if (!isRuntimeGenerationActive(generation)) return null;
            if (!sdk || typeof sdk.init !== 'function') return null;
            if (!initPostHog(sdk)) return null;
            if (serverIdentityAvailable && serverDistinctId) {
                if (!verifySdkDistinctId(sdk)) return null;
            }
            // DNT / opt-out：不请求 Survey、不显示、不发 shown、不写状态、不报错。
            if (isCapturingDisabled(sdk)) return null;
            return fetchMatchingSurvey(sdk, generation);
        }).then(function (survey) {
            if (!isRuntimeGenerationActive(generation)) return null;
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
        }).then(finishFlow, function () {
            finishFlow(null);
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
        // 达到体验阈值且尚未开始自动流程时，重新调度一次自动检查。
        if (!autoFlowStarted && seenCount() >= minDistinctLayouts) {
            scheduleAutoEvaluation(AUTO_RESCHEDULE_DELAY_MS);
        }
    }

    function refreshStateFromStorage() {
        readStateFresh();
        if (!dialogOpen || !dialogSurveyId) return;
        var state = readState();
        if (!state) return;
        var now = timers.now();
        var handledElsewhere = state.status === 'submitted' || state.status === 'never'
            || (state.status === 'snoozed'
                && now < (typeof state.snoozedUntil === 'number' ? state.snoozedUntil : 0));
        if (!handledElsewhere) return;
        // 另一标签页已处理该调查：关闭当前弹窗；不重复发送 dismissed；
        // 不改写另一标签页的状态；不显示提交失败；只显示非阻塞提示。
        closeDialog(true);
        showHandledElsewhereNote();
    }

    /**
     * serverBacked 模式下收到 STATE_KEY storage 事件（同一浏览器另一标签页的
     * 本地协调缓存写入）：可以即时关闭弹窗，但绝不能只凭 storage 消息伪造
     * serverRevision；随后触发一次有限的服务端刷新确认权威状态（不无限请求）。
     */
    function handleServerBackedStateEvent(event) {
        var localState = null;
        if (typeof event.newValue === 'string' && event.newValue) {
            try {
                var parsed = JSON.parse(event.newValue);
                if (parsed && typeof parsed === 'object') localState = parsed;
            } catch (_) {
                localState = null;
            }
        } else {
            localState = readLocalStateRaw();
        }
        var now = timers.now();
        if (localState && localState.surveyId === config.surveyId
                && (localState.status === 'submitted' || localState.status === 'never'
                    || (localState.status === 'snoozed'
                        && now < (typeof localState.snoozedUntil === 'number'
                            ? localState.snoozedUntil : 0)))) {
            if (dialogOpen) {
                closeDialog(true);
                showHandledElsewhereNote();
            }
        }
        var generation = currentRuntimeGeneration();
        if (isRuntimeGenerationActive(generation)) refreshServerContext(generation);
    }

    function onStorageEvent(event) {
        if (!event) return;
        if (event.key === STATE_KEY) {
            if (serverBacked) {
                handleServerBackedStateEvent(event);
                return;
            }
            refreshStateFromStorage();
        } else if (event.key === SEEN_KEY) {
            if (serverBacked) {
                var generation = currentRuntimeGeneration();
                if (isRuntimeGenerationActive(generation)) refreshServerContext(generation);
                return;
            }
            sessionSeen = {};
            if (!autoFlowStarted && seenCount() >= minDistinctLayouts) {
                scheduleAutoEvaluation(AUTO_RESCHEDULE_DELAY_MS);
            }
        }
    }

    function onVisibilityChange() {
        // 页面重新可见时重新调度自动检查（初次检查时 hidden 不消耗机会）。
        if (!isPageVisible()) return;
        scheduleAutoEvaluation(AUTO_RESCHEDULE_DELAY_MS);
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
            try {
                global.document.addEventListener('visibilitychange', onVisibilityChange);
            } catch (_) {
                // 可见性事件不可用时静默降级
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
            try {
                global.document.removeEventListener('visibilitychange', onVisibilityChange);
            } catch (_) {
                // 清理尽力而为
            }
        }
    }

    /**
     * 自动资格检查状态机：
     * - 初始化后至少延迟 autoDelay（默认 10 秒）再评估；
     * - 定时器真正执行时清除「已调度」标记，允许未来事件重新调度；
     * - 页面不可见 / 未达体验阈值时不加载 SDK、不消耗本页面唯一自动流程机会；
     * - 阻塞弹窗存在时按有限次数与有限总时间重试，不无限轮询；
     * - 只有真正准备调用 showSurveyFlow() 时才设置 autoFlowStarted=true，
     *   同一页面此后不再发起第二次自动 Survey 网络流程。
     */
    function scheduleAutoEvaluation(delay) {
        if (autoTimerId != null) return;
        if (autoFlowStarted) return;
        var generation = currentRuntimeGeneration();
        var id = setTimeoutSafe(function () {
            autoTimerId = null;
            if (!isRuntimeGenerationActive(generation)) return;
            evaluateAutoEligibility();
        }, delay);
        autoTimerId = id;
    }

    function evaluateAutoEligibility() {
        if (autoFlowStarted) return;
        if (!config || !config.enabled) return;
        if (!stateAllowsShow(timers.now())) return;
        if (!isPageVisible()) return;
        if (hasBlockingOverlay()) {
            // 有限重试：次数与总时间双上限，不无限轮询。
            if (autoRetryCount >= AUTO_OVERLAY_RETRY_LIMIT) return;
            if (autoRetryStartedAt === 0) autoRetryStartedAt = timers.now();
            if (timers.now() - autoRetryStartedAt > AUTO_OVERLAY_RETRY_WINDOW_MS) return;
            autoRetryCount++;
            scheduleAutoEvaluation(AUTO_OVERLAY_RETRY_DELAY_MS);
            return;
        }
        if (seenCount() < minDistinctLayouts) return;
        autoFlowStarted = true;
        showSurveyFlow();
    }

    /* ============================================================
       公共 API
    ============================================================ */

    function init(options) {
        if (initialized) return;
        initialized = true;
        // 从未初始化状态进入 init 时生成新 generation（destroy 后重新 init
        // 必然得到不同的 generation，旧异步回调不会影响新 generation）。
        runtimeGeneration = nextRuntimeGeneration();
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
        // enabled=false：不请求服务端状态、不加载 SDK（调查整体关闭）。
        if (config.enabled) {
            // solo 模式服务端上下文异步接管：localStorage 模式下的首屏写入不会回放，
            // 服务端接管后把当前布局补录到服务端 seen，并执行有限本地状态回放
            //（reconciliation，只一次；自动评估仍按原计划在 autoDelay 之后读取
            // 服务端状态，去重与阈值语义不变）。
            var initGeneration = runtimeGeneration;
            loadServerContext(initGeneration).then(function () {
                if (!isRuntimeGenerationActive(initGeneration)) return;
                if (!serverBacked) return;
                recordSeen(currentLayoutId(), timers.now());
            });
        }
        scheduleAutoEvaluation(autoDelay);
    }

    /**
     * 手动打开展示流程（调试 / 自动化测试入口）。不做自动门禁
     * （可见性 / 体验数量 / 本地状态），但受 enabled 配置约束，且身份加载
     * 不能绕过：showSurveyFlow 内部始终先等待服务端身份上下文。
     * 未 init（含 destroy 后尚未重新 init）时是安全的 no-op：
     * 返回 resolved null，不加载 SDK、不设置 flowRunning、不插 script、
     * 不注册 listener、不请求 Survey、不打开弹窗。
     */
    function open() {
        if (!initialized || !config || !config.enabled) {
            return Promise.resolve(null);
        }
        return showSurveyFlow({skipStateGate: true});
    }

    function destroy() {
        if (dialogOpen) closeDialog(false);
        if (typeof pendingSurveyCancel === 'function') {
            try {
                pendingSurveyCancel();
            } catch (_) {
                // 取消尽力而为
            }
            pendingSurveyCancel = null;
        }
        if (sdkLoadOperation && typeof sdkLoadOperation.cancel === 'function') {
            try {
                sdkLoadOperation.cancel();
            } catch (_) {
                // 取消尽力而为
            }
            sdkLoadOperation = null;
        }
        if (serverLoadOperation && typeof serverLoadOperation.cancel === 'function') {
            try {
                serverLoadOperation.cancel();
            } catch (_) {
                // 取消尽力而为
            }
            serverLoadOperation = null;
        }
        if (serverRefreshOperation && typeof serverRefreshOperation.cancel === 'function') {
            try {
                serverRefreshOperation.cancel();
            } catch (_) {
                // 取消尽力而为
            }
            serverRefreshOperation = null;
        }
        // 取消全部可取消的 server command operation（迟到响应经 generation 守卫失效）。
        serverCommandOperations.forEach(function (operation) {
            if (operation && operation.abortController) {
                try {
                    operation.abortController.abort();
                } catch (_) {
                    // 取消尽力而为
                }
            }
        });
        serverCommandOperations = [];
        if (serverSaveTimerId != null) {
            clearTimerSafe(serverSaveTimerId);
            serverSaveTimerId = null;
        }
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
        // 使当前 generation 失效：后续（destroy 后重新 init 之前的）异步
        // continuation 全部被 isRuntimeGenerationActive 拦截。
        runtimeGeneration += 1;
        autoTimerId = null;
        autoFlowStarted = false;
        autoRetryCount = 0;
        autoRetryStartedAt = 0;
        flowRunning = false;
        submitting = false;
        sessionState = null;
        sessionSeen = {};
        appVersionPromise = null;
        // 服务端状态全部清空：旧 generation 的 GET / POST / 409 重试 / storage
        // 消息不得影响新 generation；destroy 后重新 init 必须重新探测 server context，
        // 不复用旧 server 快照。
        serverIdentityAvailable = false;
        serverBacked = false;
        serverDistinctId = null;
        serverRevision = 0;
        serverState = null;
        serverSeen = null;
        serverLoadOperation = null;
        serverRefreshOperation = null;
        serverRefreshInFlight = null;
        serverCommandOperations = [];
        pendingSeenLayouts = {};
        serverSaveTimerId = null;
        reconciled = false;
        // 注意：sdkInitSignature 故意保留 —— destroy 后重新 init 时用于同一
        // singleton 的幂等复用（签名一致）与 fail closed（签名变化）。
        // destroy 不删除已加载完成的 vendor script、不调用 reset() /
        // opt-out 方法、不改变匿名 distinct ID、不清除 PostHog 本地
        // 持久化；已加载的 singleton 只随本模块不再被驱动发送调查事件。
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
            SERVER_STATE_URL: SERVER_STATE_URL,
            SERVER_STATE_TIMEOUT_MS: SERVER_STATE_TIMEOUT_MS,
            SERVER_SAVE_DEBOUNCE_MS: SERVER_SAVE_DEBOUNCE_MS,
            SNOOZE_MS: SNOOZE_MS,
            MIN_DISTINCT_LAYOUTS_SEEN: MIN_DISTINCT_LAYOUTS_SEEN,
            AUTO_DELAY_MS: AUTO_DELAY_MS,
            AUTO_RETRY_DELAY_MS: AUTO_RETRY_DELAY_MS,
            AUTO_OVERLAY_RETRY_LIMIT: AUTO_OVERLAY_RETRY_LIMIT,
            AUTO_OVERLAY_RETRY_DELAY_MS: AUTO_OVERLAY_RETRY_DELAY_MS,
            AUTO_OVERLAY_RETRY_WINDOW_MS: AUTO_OVERLAY_RETRY_WINDOW_MS,
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
            isDateObject: isDateObject,
            isAcceptedCaptureResult: isAcceptedCaptureResult,
            distinctSeenCount: distinctSeenCount
        })
    });
})(window);
