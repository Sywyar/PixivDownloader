/* eslint-disable */
/** 布局调查常量、纯函数与共享运行期状态。 */
(function (global) {
    'use strict';

    var modules = global.PixivLayoutFeedbackModules
        || (global.PixivLayoutFeedbackModules = {});
    modules.core = Object.freeze({
        install: function (ctx) {
            var runtime = {
            initialized: false,
            pageType: 'batch',
            configuredLayoutId: null,
            config: null,
            storage: null,
            timers: null,
            fetchImpl: null,
            i18nClient: null,
            runtimeGeneration: 0,
            sdkLoadOperation: null,
            firstDownloadTriggered: false,
            flowRunning: false,
            pendingSurveyCancel: null,
            dialogOpen: false,
            submitting: false,
            shownSent: false,
            dialogSurveyId: null,
            dialogChoiceQuestion: null,
            dialogSuggestionQuestion: null,
            layoutSnapshot: null,
            selectedChoice: null,
            dialogElements: null,
            dialogFocusBefore: null,
            dialogKeydownHandler: null,
            dialogStorageBound: false,
            sessionState: null,
            sessionSeen: {},
            appVersionPromise: null,
            pendingTimers: [],
            serverBacked: false,
            serverIdentityAvailable: false,
            serverDistinctId: null,
            serverSubmissionId: null,
            serverStatus: null,
            serverCanShow: true,
            serverRetryAfterMs: 0,
            serverSeenLayouts: [],
            serverRevision: 0,
            serverStateAvailable: false,
            serverSnapshotInitialized: false,
            serverLocalBlockUntil: 0,
            pendingLocalState: null,
            pendingLocalSeen: {},
            serverLoadOperation: null,
            serverRefreshOperation: null,
            serverRefreshInFlight: null,
            serverCommandOperations: new Set(),
            pendingSeenLayouts: {},
            serverSaveTimerId: null,
            reconciled: false
            };

    /* ============================================================
       常量与纯函数（_internals 暴露给自动化测试）
    ============================================================ */

    var LAYOUT_IDS = ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt'];
    var STATE_KEY = 'pixiv:layout-feedback:state:v1';
    var SEEN_KEY = 'pixiv:layout-feedback:seen:v1';
    var SERVER_STATE_URL = '/api/layout-feedback/state';
    var SERVER_STATE_TIMEOUT_MS = 3 * 1000;
    var SERVER_COMMAND_TIMEOUT_MS = 4 * 1000;
    // 服务端视图应用结果：applyServerView 的明确返回值。
    // - VIEW_APPLIED：高 revision 合法视图，已提交；
    // - VIEW_SAME：同 revision 持久化字段与动态字段完全相同，合法无副作用响应；
    // - VIEW_UPDATED：同 revision 只有动态字段（canShow / retryAfterMs）变化，合法应用；
    // - VIEW_STALE：低 revision 迟到响应，安全忽略；
    // - VIEW_INVALID：协议 / 字段 / 组合非法，整份拒绝，不部分应用。
    var VIEW_APPLIED = 'applied';
    var VIEW_SAME = 'same';
    var VIEW_UPDATED = 'updated';
    var VIEW_STALE = 'stale';
    var VIEW_INVALID = 'invalid';
    // refreshServerContext 的明确业务结果契约：
    // - REFRESH_FRESH：视图应用成功（APPLIED / SAME / UPDATED / STALE 均合法，携带 viewResult）；
    // - REFRESH_UNAVAILABLE：明确的暂时性可用性问题（网络 / 超时 / 408 / 429 / 5xx），
    //   允许按产品策略 fail-open，但提交前仍重新读取本地有效状态；
    // - REFRESH_INVALID：协议 / 身份 / 安全一致性问题（视图非法、身份变化、同 revision
    //   内容冲突、2xx 非 JSON / schema 非法、400 / 401 / 403 / 404 / 其它 4xx），必须 fail-closed；
    // - REFRESH_CANCELLED：destroy / generation 失效 / 被取代，不得继续提交、
    //   不得显示误导性提交失败。
    var REFRESH_FRESH = 'fresh';
    var REFRESH_UNAVAILABLE = 'unavailable';
    var REFRESH_INVALID = 'invalid';
    var REFRESH_CANCELLED = 'cancelled';
    var SERVER_SAVE_DEBOUNCE_MS = 400;
    var SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;
    // 同 Survey 本地 snooze 截止时间与本次转换值的允许差距：超过该差距才重写
    // localStorage，避免每次 GET 因毫秒级差异产生无意义写入（本地时间域比较）。
    var LOCAL_SNOOZE_WRITE_TOLERANCE_MS = 5 * 1000;
    // reconciliation 中「服务端已提供至少相同阻断效果」的剩余时长容差。
    var RECONCILE_REMAINING_TOLERANCE_MS = 5 * 1000;
    var SCOPED_ID_PATTERN = /^plf_[0-9a-f]{64}$/;
    var SUBMISSION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    var SUGGESTION_MAX_CODE_POINTS = 1000;
    var SURVEY_SCHEMA_VERSION = '1';
    var FLAGS_TIMEOUT_MS = 10 * 1000;
    var SURVEY_TOTAL_TIMEOUT_MS = 30 * 1000;
    var APP_VERSION_TIMEOUT_MS = 10 * 1000;
    var POSTHOG_OWNER_KEY = 'download-workbench.layout-feedback';
    var TRUSTED_POSTHOG_API_ORIGINS = Object.freeze(['https://layout-survey.sywyar.top']);
    var POSTHOG = global.PixivLayoutSurveyPostHog || Object.freeze({});
    var I18N_NS = 'layout-feedback';
    var ALLOWED_SURVEY_EVENTS = ['survey shown', 'survey sent', 'survey dismissed'];
    var PROTOCOL_PROPERTIES = [
        'distinct_id', 'token', '$survey_id',
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
        runtime.runtimeGeneration += 1;
        return runtime.runtimeGeneration;
    }

    function currentRuntimeGeneration() {
        return runtime.runtimeGeneration;
    }

    function isRuntimeGenerationActive(generation) {
        return runtime.initialized && generation === runtime.runtimeGeneration;
    }

    /**
     * 异步回调统一活性检查：runtime generation 仍活动、operation 未 settled /
     * 未 aborted，且 attempt token 仍是最新（未提供 attemptId 时跳过 token 检查）。
     * 任何迟到 / 过期回调在继续处理前都必须先通过它。
     */
    function isOperationActive(operation, generation, attemptId) {
        return isRuntimeGenerationActive(generation)
            && !!operation
            && !operation.settled
            && !operation.aborted
            && (attemptId === undefined || operation.attemptSequence === attemptId);
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
            id = runtime.timers.setTimeout(fn, ms);
        } catch (_) {
            id = null;
        }
        if (id != null) runtime.pendingTimers.push(id);
        return id;
    }

    function clearTimerSafe(id) {
        if (id == null) return;
        var index = runtime.pendingTimers.indexOf(id);
        if (index >= 0) runtime.pendingTimers.splice(index, 1);
        try {
            runtime.timers.clearTimeout(id);
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

    /* ============================================================
       时间概念
       - clientWallNow()：客户端墙钟（Date.now() 风格 Unix epoch 毫秒）。
         localStorage / pendingLocalState / 本地 snooze / 本地 updatedAt /
         serverLocalBlockUntil / UI 定时器一律使用它（timers.now()）。
       - 服务端绝对时间点（snoozedUntil / updatedAt / serverTime）不进入浏览器：
         浏览器只把 retryAfterMs 转换为本地临时截止时间，绝不解释、比较或缓存
         服务端绝对时间。
    ============================================================ */

    /** 客户端墙钟：Date.now() 风格 Unix epoch 毫秒。 */
    function clientWallNow() {
        return runtime.timers.now();
    }

    /**
     * 客户端时间安全加法：base + duration 限制在 Number 安全整数范围内。
     * - 两者必须有限；duration < 0 按 0；
     * - 结果超过 Number.MAX_SAFE_INTEGER 时钳制到上限；
     * - 结果转换为整数；绝不产生 Infinity / NaN。
     */
    function safeClientTimeAdd(base, duration) {
        if (typeof base !== 'number' || !isFinite(base)) return 0;
        if (typeof duration !== 'number' || !isFinite(duration) || duration < 0) {
            duration = 0;
        }
        var result = base + duration;
        if (!isFinite(result)) return Number.MAX_SAFE_INTEGER;
        if (result > Number.MAX_SAFE_INTEGER) return Number.MAX_SAFE_INTEGER;
        return Math.floor(result);
    }

    function defaultFetch() {
        return typeof global.fetch === 'function'
            ? function (url, options) { return global.fetch(url, options); }
            : function () { return Promise.reject(new Error('fetch unavailable')); };
    }

    /* ============================================================
       公开配置与本地状态
    ============================================================ */

    function readSurveyConfig() {
        var manager = global.PixivPostHog;
        if (!manager || typeof manager.createSurveyClient !== 'function'
                || typeof POSTHOG.surveyId !== 'string' || !POSTHOG.surveyId) return null;
        return {
            enabled: true,
            surveyId: POSTHOG.surveyId
        };
    }

    function isPlainObject(value) {
        return value != null && typeof value === 'object' && !Array.isArray(value);
    }

    function serverStateUrl() {
        return SERVER_STATE_URL + '?surveyId=' + encodeURIComponent(runtime.config.surveyId);
    }

    function isFiniteInteger(value) {
        return typeof value === 'number' && isFinite(value) && Math.floor(value) === value;
    }

    /**
     * JavaScript 安全整数校验（{@code Number.MAX_SAFE_INTEGER} 范围内的有限整数）。
     * 服务端 revision 必须能被 JS 精确表示：仅 isFiniteInteger 不足以拒绝
     * 9007199254740992 这类超出安全范围的整数。
     */
    function isSafeInteger(value) {
        return isFiniteInteger(value) && Math.abs(value) <= Number.MAX_SAFE_INTEGER;
    }

    /** 两个布局 ID 数组内容完全相同（有序）。 */
    function seenLayoutsEqual(a, b) {
        if (!Array.isArray(a) || !Array.isArray(b)) return false;
        if (a.length !== b.length) return false;
        for (var i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }

    /**
     * 严格校验并应用服务端权威展示视图（GET / POST 200 响应通用）。
     *
     * <p>先完整解析 / 验证到局部 candidate，任何验证完成前不得修改全局 server* 状态；
     * 返回明确结果：
     * <ul>
     *   <li>{@code VIEW_APPLIED}：高 revision 合法视图，已应用；</li>
     *   <li>{@code VIEW_SAME}：同 revision 持久化字段与动态字段完全相同，合法无副作用
     *       响应；</li>
     *   <li>{@code VIEW_UPDATED}：同 revision 只有动态字段（canShow / retryAfterMs）
     *       变化——例如 retryAfterMs 递减、snoozed 从 canShow=false 变为 true——合法应用
     *       动态字段；</li>
     *   <li>{@code VIEW_STALE}：低 revision 迟到响应，安全忽略（不清理 pending、
     *       不同步旧缓存、不视为协议错误）；</li>
     *   <li>{@code VIEW_INVALID}：字段非法 / 组合非法 / 同 revision 持久化字段冲突 /
     *       scoped 身份变化，整份拒绝，调用方按失败处理。</li>
     * </ul>
     *
     * - revision 必须单调：低 revision 永不覆盖；同 revision 持久化字段
     *   （stateAvailable / distinctId / submissionId / status / seenLayouts）必须一致，动态字段
     *   （canShow / retryAfterMs）允许随服务端时间流逝变化；
     * - 同一页面 generation 内 scoped 身份与提交 UUID 必须稳定：任一变化一律 INVALID；
     * - 任一字段非法或组合非法（status=null 必须 canShow=true / retryAfterMs=0；
     *   submitted / never 必须 canShow=false / retryAfterMs=0；snoozed + canShow=true
     *   必须 retryAfterMs=0；snoozed + canShow=false 必须 retryAfterMs&gt;0；
     *   stateAvailable=false 必须 status=null / canShow=false / retryAfterMs=0）
     *   整份视图拒绝，不使用部分字段；
     * - 服务端声明可用却缺失 distinctId 或合法 submissionId：整份视图拒绝；
     * - 只写 server* / session* 变量，不直接写 localStorage（由 syncServerViewToLocalCache 决定）。
     */
    function applyServerView(data) {
        if (!data || typeof data !== 'object') return VIEW_INVALID;
        if (data.available !== true) return VIEW_INVALID;
        if (typeof data.stateAvailable !== 'boolean') return VIEW_INVALID;
        var distinctId = typeof data.distinctId === 'string' ? data.distinctId : '';
        if (distinctId && !SCOPED_ID_PATTERN.test(distinctId)) return VIEW_INVALID;
        // 服务端声明可用（available=true）却不下发 scoped 身份：整份视图非法，不得部分使用。
        if (!distinctId) return VIEW_INVALID;
        var submissionId = typeof data.submissionId === 'string' ? data.submissionId : '';
        if (!SUBMISSION_ID_PATTERN.test(submissionId)) return VIEW_INVALID;
        if (!isSafeInteger(data.revision) || data.revision < 0) return VIEW_INVALID;
        var status = null;
        if (data.status != null) {
            if (typeof data.status !== 'string'
                    || (data.status !== 'submitted' && data.status !== 'never'
                        && data.status !== 'snoozed')) {
                return VIEW_INVALID;
            }
            status = data.status;
        }
        if (typeof data.canShow !== 'boolean') return VIEW_INVALID;
        // retryAfterMs 必须是 JavaScript 安全整数（服务端响应已保证上限，前端仍独立
        // fail-closed）：Number.MAX_SAFE_INTEGER + 1 / Infinity / -Infinity / NaN /
        // 小数 / string / null / 负数一律整份拒绝。
        if (!isSafeInteger(data.retryAfterMs) || data.retryAfterMs < 0) return VIEW_INVALID;
        var seenLayouts = [];
        if (data.seenLayouts != null) {
            if (!Array.isArray(data.seenLayouts)) return VIEW_INVALID;
            var seenIds = {};
            var validSeenLayouts = true;
            data.seenLayouts.forEach(function (layoutId) {
                if (!validSeenLayouts) return;
                if (LAYOUT_IDS.indexOf(layoutId) < 0 || seenIds[layoutId]) {
                    validSeenLayouts = false;
                    return;
                }
                seenIds[layoutId] = true;
                seenLayouts.push(layoutId);
            });
            if (!validSeenLayouts) return VIEW_INVALID;
            if (seenLayouts.length > LAYOUT_IDS.length) return VIEW_INVALID;
        }
        // 组合规则校验：status / canShow / retryAfterMs 必须自洽。
        if (data.stateAvailable === false) {
            if (status !== null || data.canShow !== false || data.retryAfterMs !== 0) {
                return VIEW_INVALID;
            }
        } else if (status === null) {
            if (data.canShow !== true || data.retryAfterMs !== 0) return VIEW_INVALID;
        } else if (status === 'submitted' || status === 'never') {
            if (data.canShow !== false || data.retryAfterMs !== 0) return VIEW_INVALID;
        } else if (status === 'snoozed') {
            if (data.canShow === true) {
                if (data.retryAfterMs !== 0) return VIEW_INVALID;
            } else if (data.canShow === false) {
                if (data.retryAfterMs <= 0) return VIEW_INVALID;
            } else {
                return VIEW_INVALID;
            }
        }
        // 同一页面 generation 内 scoped 身份必须稳定：不得因 revision 更高而接受身份变化。
        if (runtime.serverSnapshotInitialized
                && (distinctId !== runtime.serverDistinctId || submissionId !== runtime.serverSubmissionId)) {
            warn('layout survey: server submission identity changed within this page; view rejected');
            return VIEW_INVALID;
        }
        if (!runtime.serverSnapshotInitialized) return VIEW_APPLIED;
        if (data.revision < runtime.serverRevision) return VIEW_STALE;
        if (data.revision > runtime.serverRevision) return VIEW_APPLIED;
        // 同 revision：持久化字段必须一致，动态字段（canShow / retryAfterMs）允许变化。
        var samePersistent = data.stateAvailable === runtime.serverStateAvailable
            && distinctId === runtime.serverDistinctId
            && submissionId === runtime.serverSubmissionId
            && status === runtime.serverStatus
            && (data.stateAvailable === false || seenLayoutsEqual(seenLayouts, runtime.serverSeenLayouts));
        if (!samePersistent) {
            warn('layout survey: server returned conflicting content for the same revision; view rejected');
            return VIEW_INVALID;
        }
        if (data.canShow === runtime.serverCanShow && data.retryAfterMs === runtime.serverRetryAfterMs) {
            return VIEW_SAME;
        }
        return VIEW_UPDATED;
    }

            Object.assign(ctx, {
                ALLOWED_SURVEY_EVENTS: ALLOWED_SURVEY_EVENTS,
                APP_VERSION_TIMEOUT_MS: APP_VERSION_TIMEOUT_MS,
                FLAGS_TIMEOUT_MS: FLAGS_TIMEOUT_MS,
                I18N_NS: I18N_NS,
                LAYOUT_IDS: LAYOUT_IDS,
                LOCAL_SNOOZE_WRITE_TOLERANCE_MS: LOCAL_SNOOZE_WRITE_TOLERANCE_MS,
                POSTHOG: POSTHOG,
                POSTHOG_OWNER_KEY: POSTHOG_OWNER_KEY,
                PROTOCOL_PROPERTIES: PROTOCOL_PROPERTIES,
                RECONCILE_REMAINING_TOLERANCE_MS: RECONCILE_REMAINING_TOLERANCE_MS,
                REFRESH_CANCELLED: REFRESH_CANCELLED,
                REFRESH_FRESH: REFRESH_FRESH,
                REFRESH_INVALID: REFRESH_INVALID,
                REFRESH_UNAVAILABLE: REFRESH_UNAVAILABLE,
                SCOPED_ID_PATTERN: SCOPED_ID_PATTERN,
                SEEN_KEY: SEEN_KEY,
                SERVER_COMMAND_TIMEOUT_MS: SERVER_COMMAND_TIMEOUT_MS,
                SERVER_SAVE_DEBOUNCE_MS: SERVER_SAVE_DEBOUNCE_MS,
                SERVER_STATE_TIMEOUT_MS: SERVER_STATE_TIMEOUT_MS,
                SERVER_STATE_URL: SERVER_STATE_URL,
                SNOOZE_MS: SNOOZE_MS,
                STATE_KEY: STATE_KEY,
                SUBMISSION_ID_PATTERN: SUBMISSION_ID_PATTERN,
                SUGGESTION_MAX_CODE_POINTS: SUGGESTION_MAX_CODE_POINTS,
                SURVEY_SCHEMA_VERSION: SURVEY_SCHEMA_VERSION,
                SURVEY_TOTAL_TIMEOUT_MS: SURVEY_TOTAL_TIMEOUT_MS,
                TRUSTED_POSTHOG_API_ORIGINS: TRUSTED_POSTHOG_API_ORIGINS,
                VIEW_APPLIED: VIEW_APPLIED,
                VIEW_INVALID: VIEW_INVALID,
                VIEW_SAME: VIEW_SAME,
                VIEW_STALE: VIEW_STALE,
                VIEW_UPDATED: VIEW_UPDATED,
                applyServerView: applyServerView,
                beforeSendFilter: beforeSendFilter,
                clearTimerSafe: clearTimerSafe,
                clientWallNow: clientWallNow,
                codePointLength: codePointLength,
                currentRuntimeGeneration: currentRuntimeGeneration,
                defaultFetch: defaultFetch,
                defaultTimers: defaultTimers,
                distinctSeenCount: distinctSeenCount,
                isAcceptedCaptureResult: isAcceptedCaptureResult,
                isDateObject: isDateObject,
                isFiniteInteger: isFiniteInteger,
                isOperationActive: isOperationActive,
                isPlainObject: isPlainObject,
                isRuntimeGenerationActive: isRuntimeGenerationActive,
                isSafeInteger: isSafeInteger,
                mapLayoutToken: mapLayoutToken,
                nextRuntimeGeneration: nextRuntimeGeneration,
                readSurveyConfig: readSurveyConfig,
                resolveChoiceQuestion: resolveChoiceQuestion,
                resolveSuggestionQuestion: resolveSuggestionQuestion,
                runtime: runtime,
                safeClientTimeAdd: safeClientTimeAdd,
                safeLocalStorage: safeLocalStorage,
                seenLayoutsEqual: seenLayoutsEqual,
                serverStateUrl: serverStateUrl,
                setTimeoutSafe: setTimeoutSafe,
                warn: warn
            });
        }
    });
})(window);
