/*
 * 下载工作台布局偏好调查（PostHog API Survey 自定义单屏弹窗）。
 *
 * 触发时机：仅新版工作台（pixiv-batch-alt.html）在本页面会话中第一个下载项
 * 完成时弹出一次；经典下载页（pixiv-batch.html）不参与调查，不再加载本模块。
 * alt 下载引擎在首个作品完成时派发 pixiv:first-download-completed 事件，本模块
 * 在事件到达时评估状态门禁（submitted / never / 未到期 snoozed 不展示）并启动
 * 展示流程；不再使用延迟定时器 / 布局体验数量阈值 / 页面可见性等自动展示门禁。
 *
 * 顶层加载无副作用；只有官方发行激活位为 true 且调用 init() 后才创建客户端与操作 DOM。
 * 调查初始化不阻塞页面核心初始化；调查的任何异常都不得中断下载功能。
 *
 * 依赖顺序：
 *   1. /pixiv-layout-feedback/release-activation.js（构建生成的官方发行激活位）
 *   2. /pixiv-posthog/pixiv-posthog.js          （PostHog SDK loader / adapter）
 *   3. 本文件
 * PostHog 插件按需加载固定版本的 vendored SDK（无 CDN）。
 *
 * 隐私约束：
 *   - Project token / Survey ID / apiHost / uiHost 由本调查发布者持有，都是公开客户端参数，不是 Secret；
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
 *     /api/layout-feedback/state 以动作式命令（无 CAS）持久化到 state/，
 *     多个浏览器 / 设备共享同一去重结论；
 *   - 服务端绝对时间点（snoozedUntil / updatedAt / serverTime）一律不进入浏览器：
 *     服务端只返回 status / canShow / retryAfterMs / seenLayouts，snooze 是否到期完全
 *     由服务端按自己的时钟判断，浏览器只把 retryAfterMs 转换为本地临时截止时间；
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
    var SUGGESTION_MAX_CODE_POINTS = 1000;
    var SURVEY_SCHEMA_VERSION = '1';
    var FLAGS_TIMEOUT_MS = 10 * 1000;
    var SURVEY_TOTAL_TIMEOUT_MS = 30 * 1000;
    var APP_VERSION_TIMEOUT_MS = 10 * 1000;
    var POSTHOG_OWNER_KEY = 'download-workbench.layout-feedback';
    var POSTHOG = global.PixivLayoutSurveyPostHog || Object.freeze({});
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
    var configuredLayoutId = null;
    var config = null;
    var storage = null;
    var timers = null;
    var fetchImpl = null;
    var i18nClient = null;
    var runtimeGeneration = 0;
    var sdkLoadOperation = null;
    // 首次下载完成触发的一次性标记：事件到达且全部门禁通过后置位，本页面会话
    // 不再发起第二次调查流程（destroy 时重置）。
    var firstDownloadTriggered = false;
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
    // 服务端权威展示视图：只允许由成功 GET / POST 200 响应经 applyServerView 写入。
    // 视图只含状态语义（status / canShow / retryAfterMs / seenLayouts），不含任何
    // 服务端绝对时间点；snooze 是否到期由服务端判断，浏览器只消费 retryAfterMs。
    var serverStatus = null;
    var serverCanShow = true;
    var serverRetryAfterMs = 0;
    var serverSeenLayouts = [];
    var serverRevision = 0;
    var serverStateAvailable = false;
    // 是否已应用过至少一份合法服务端视图（初始合法响应可以是 revision=0，
    // 不能仅用 serverRevision === 0 判断；destroy 时重置）。
    var serverSnapshotInitialized = false;
    // 收到 canShow=false / retryAfterMs 后转换出的客户端本地截止时间
    //（clientNow + retryAfterMs，属于客户端时钟域；不得保存服务端绝对时间）。
    var serverLocalBlockUntil = 0;
    // 尚未被服务器确认的本地 fallback：本地 submitted / never / 有效 snoozed 与
    // 尚未确认的本地布局体验。服务器确认（或服务器已更强）后按项清除。
    var pendingLocalState = null;
    var pendingLocalSeen = {};
    var serverLoadOperation = null;
    var serverRefreshOperation = null;
    var serverRefreshInFlight = null;
    // 活动服务端命令 operation 集合（Set 管理：add / finish / cancel / destroy，
    // 不依赖数组下标，压缩不会错位）。
    var serverCommandOperations = new Set();
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
        return timers.now();
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
        return SERVER_STATE_URL + '?surveyId=' + encodeURIComponent(config.surveyId);
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
     *   （stateAvailable / distinctId / status / seenLayouts）必须一致，动态字段
     *   （canShow / retryAfterMs）允许随服务端时间流逝变化；
     * - 同一页面 generation 内 scoped 身份必须稳定：identity 变化一律 INVALID；
     * - 任一字段非法或组合非法（status=null 必须 canShow=true / retryAfterMs=0；
     *   submitted / never 必须 canShow=false / retryAfterMs=0；snoozed + canShow=true
     *   必须 retryAfterMs=0；snoozed + canShow=false 必须 retryAfterMs&gt;0；
     *   stateAvailable=false 必须 status=null / canShow=false / retryAfterMs=0）
     *   整份视图拒绝，不使用部分字段；
     * - 服务端声明可用（available=true）却缺失 / 空 distinctId：整份视图拒绝；
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
        if (serverSnapshotInitialized && serverDistinctId && distinctId !== serverDistinctId) {
            warn('layout survey: server scoped identity changed within this page; view rejected');
            return VIEW_INVALID;
        }
        if (!serverSnapshotInitialized) return VIEW_APPLIED;
        if (data.revision < serverRevision) return VIEW_STALE;
        if (data.revision > serverRevision) return VIEW_APPLIED;
        // 同 revision：持久化字段必须一致，动态字段（canShow / retryAfterMs）允许变化。
        var samePersistent = data.stateAvailable === serverStateAvailable
            && distinctId === serverDistinctId
            && status === serverStatus
            && (data.stateAvailable === false || seenLayoutsEqual(seenLayouts, serverSeenLayouts));
        if (!samePersistent) {
            warn('layout survey: server returned conflicting content for the same revision; view rejected');
            return VIEW_INVALID;
        }
        if (data.canShow === serverCanShow && data.retryAfterMs === serverRetryAfterMs) {
            return VIEW_SAME;
        }
        return VIEW_UPDATED;
    }

    /**
     * 把已完整校验的 candidate 视图写入全局 server* 状态，并把 retryAfterMs 转换为
     * 客户端本地临时截止时间（serverLocalBlockUntil）。绝不保存服务端绝对时间。
     */
    function commitServerView(data) {
        var distinctId = data.distinctId;
        var seenLayouts = [];
        if (Array.isArray(data.seenLayouts)) {
            data.seenLayouts.forEach(function (layoutId) {
                seenLayouts.push(layoutId);
            });
        }
        serverIdentityAvailable = distinctId !== '';
        serverDistinctId = distinctId || null;
        serverStateAvailable = data.stateAvailable;
        serverBacked = data.stateAvailable && serverIdentityAvailable;
        serverRevision = data.revision;
        serverStatus = data.status != null ? data.status : null;
        serverCanShow = data.canShow;
        serverRetryAfterMs = data.retryAfterMs;
        serverSeenLayouts = seenLayouts;
        serverSnapshotInitialized = true;
        // 只保存本地临时截止时间（clientNow + retryAfterMs），不解释服务端绝对时间。
        serverLocalBlockUntil = (!serverCanShow && serverRetryAfterMs > 0)
            ? safeClientTimeAdd(clientWallNow(), serverRetryAfterMs)
            : 0;
        // 注意：不把视图复制进 sessionState / sessionSeen。
        // sessionState / sessionSeen 只由 syncServerViewToLocalCache 在完成本地时钟域
        // 转换后更新；服务端权威视图继续只保留在 server* 变量。
    }

    /**
     * 可取消的 solo 模式服务端上下文装载 operation（两阶段）。
     * - fetch 阶段：服务端 GET，由 SERVER_STATE_TIMEOUT_MS 控制；GET 完成、
     *   JSON 解析成功并应用视图后立即清除 GET timeout，该 timeout 不得继续影响后续流程；
     * - reconcile 阶段：有限本地状态回放（reconciliation），由每个
     *   sendServerCommand 自己的 SERVER_COMMAND_TIMEOUT_MS 控制；整体 promise 必须
     *   等待 reconciliation 达成或确定失败，不得因 GET timeout 提前 resolve；
     * - 同一 generation 只创建一个请求（完成后标记 done，避免旧 operation 被误复用）；
     * - 成功且 available=true：启用服务端 scoped 身份；stateAvailable=true 时启用
     *   serverBacked（服务端状态权威），随后先执行有限本地状态回放（必须在覆盖本地
     *   缓存之前读取 localFallback），再用权威视图更新本地协调缓存；
     * - 403（multi 模式）/ 网络失败 / 超时 / 非法响应：整体回退 local 模式，
     *   调查仍按浏览器本地去重工作；
     * - 超时 abort 并 resolve，不永久 pending；destroy 时 cancel；
     * - GET 超时后 / destroy 后 / re-init 旧 generation 的迟到响应一律不得应用；
     *   同 generation 已进入 reconciliation 后不接受重复 GET callback。
     */
    function loadServerContext(generation) {
        // 同一 generation 复用同一个 operation 的 promise（已完成也复用其已 resolve
        // 的结果，不重复 GET）；done 标记用于避免旧 operation 被当作活动操作复用。
        if (serverLoadOperation && serverLoadOperation.generation === generation) {
            return serverLoadOperation.promise;
        }
        var operation = {
            generation: generation,
            promise: null,
            abortController: null,
            timeoutId: null,
            settled: false,
            done: false,
            cancel: null
        };
        serverLoadOperation = operation;
        operation.promise = new Promise(function (resolve) {
            var settled = false;
            function finish() {
                if (settled) return;
                settled = true;
                operation.settled = true;
                operation.done = true;
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
            // GET 阶段 timeout：只覆盖 GET；进入 reconciliation 前必须清除。
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
                headers: {'Accept': 'application/json'},
                cache: 'no-store'
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
                // 在应用服务端视图前先保存本地 fallback 快照，避免后续写协调缓存时
                // 失去尚未确认的本地 submitted / never / snoozed / seen 原始数据。
                var localFallback = {
                    state: readLocalStateRaw(),
                    seen: readLocalSeenRaw()
                };
                var result = applyServerView(data);
                if (result === VIEW_INVALID) throw new Error('invalid');
                if (result === VIEW_APPLIED || result === VIEW_UPDATED) {
                    commitServerView(data);
                }
                // GET 阶段结束：立即清除 GET timeout，不允许它影响 reconciliation。
                if (operation.timeoutId != null) {
                    clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (serverBacked) {
                    // 阶段二：有限本地状态回放（reconciliation）；整体 promise 必须
                    // 等待它达成或确定失败（reconciled 守卫保证只回放一次）。
                    // 最终同步 effectiveState 前必须再次验证 operation / generation 活动：
                    // destroy 已调用 operation.cancel（settled=true）时最终同步不得执行。
                    operation.phase = 'reconcile';
                    return reconcileLocalState(operation, generation, localFallback)
                        .then(function (result) {
                            if (!isOperationActive(operation, generation)) return null;
                            syncServerViewToLocalCache();
                            return result;
                        });
                }
                return null;
            }).catch(function () {
                // 服务端不可用 / 非法响应 / 迟到 GET：保留 localStorage 模式
            }).then(finish);
        });
        return operation.promise;
    }

    /**
     * 强制重新 GET 最新服务端状态（提交前 preflight / storage 提示后的有限刷新）。
     * 同一时间最多一个在途请求；超时 abort；返回明确的 refresh 结果契约对象：
     * {status: REFRESH_FRESH, viewResult} / {status: REFRESH_UNAVAILABLE, reason} /
     * {status: REFRESH_INVALID, reason} / {status: REFRESH_CANCELLED, reason}。
     *
     * <p>分类规则：
     * - FRESH：VIEW_APPLIED / VIEW_UPDATED（已应用更新视图或动态字段，APPLIED / UPDATED
     *   已同步本地缓存）/ VIEW_SAME（完全相同，无副作用）/ VIEW_STALE（迟到的低 revision
     *   响应被安全忽略，当前客户端已拥有更新视图；preflight 基于当前 effective state 判断）；
     * - UNAVAILABLE：网络失败 / fetch reject / 本模块超时 / HTTP 408 / 429 / 5xx；
     * - INVALID：VIEW_INVALID、scoped 身份变化、同 revision 持久化字段冲突、2xx 响应
     *   JSON 结构非法或无法解析、400 / 401 / 403 / 404 / 其它 4xx；
     * - CANCELLED：runtime generation 失效、destroy、operation 被取代 / 显式取消。
     *
     * <p>operation.cancel(reason) 幂等并携带取消原因：timeout → UNAVAILABLE；
     * destroy / generation stale → CANCELLED；超时后 / destroy 后 / 已被取代的迟到响应
     * 不 apply、不 sync、不 prune、不修改 serverRevision；多个 refresh 请求共用
     * serverRefreshInFlight，完成后可靠清空；operation finish 后迟到 callback
     * 不再次 finish、不改变状态、不改变结果。
     */
    function refreshServerContext(generation) {
        if (!isRuntimeGenerationActive(generation)) {
            return Promise.resolve({status: REFRESH_CANCELLED, reason: 'generation-stale'});
        }
        if (serverRefreshInFlight) return serverRefreshInFlight;
        serverRefreshInFlight = new Promise(function (resolve) {
            var settled = false;
            var operation = {
                generation: generation,
                aborted: false,
                abortController: null,
                timeoutId: null,
                settled: false,
                cancelReason: null,
                cancel: null
            };
            serverRefreshOperation = operation;
            function finish(result) {
                if (settled) return;
                settled = true;
                operation.settled = true;
                if (operation.timeoutId != null) {
                    clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (serverRefreshOperation === operation) serverRefreshOperation = null;
                resolve(result);
            }
            operation.cancel = function (reason) {
                if (settled) return;
                operation.aborted = true;
                operation.cancelReason = reason || 'cancelled';
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                // timeout 属于明确的暂时性可用性问题（UNAVAILABLE）；destroy /
                // generation stale / 取代属于 CANCELLED。
                finish(operation.cancelReason === 'timeout'
                    ? {status: REFRESH_UNAVAILABLE, reason: 'timeout'}
                    : {status: REFRESH_CANCELLED, reason: operation.cancelReason});
            };
            operation.timeoutId = setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                operation.cancel('timeout');
            }, SERVER_STATE_TIMEOUT_MS);
            var init = {
                credentials: 'same-origin',
                headers: {'Accept': 'application/json'},
                cache: 'no-store'
            };
            if (typeof global.AbortController === 'function') {
                operation.abortController = new global.AbortController();
                init.signal = operation.abortController.signal;
            }
            var request = null;
            try {
                request = fetchImpl(serverStateUrl(), init);
            } catch (_) {
                finish({status: REFRESH_UNAVAILABLE, reason: 'fetch'});
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish({status: REFRESH_UNAVAILABLE, reason: 'fetch'});
                return;
            }
            request.then(function (response) {
                if (!isOperationActive(operation, generation)) throw new Error('stale');
                if (!response) throw new Error('http');
                if (!response.ok) {
                    if (response.status === 408 || response.status === 429 || response.status >= 500) {
                        throw {refreshKind: 'unavailable', reason: 'http-' + response.status};
                    }
                    // 400 / 401 / 403 / 404 / 其它 4xx：协议或身份一致性问题。
                    throw {refreshKind: 'invalid', reason: 'http-' + response.status};
                }
                return response.json().catch(function () {
                    // 2xx 响应不是合法 JSON：协议错误。
                    throw {refreshKind: 'invalid', reason: 'bad-json'};
                });
            }).then(function (data) {
                if (!isOperationActive(operation, generation)) throw new Error('stale');
                var result = applyServerView(data);
                if (result === VIEW_APPLIED || result === VIEW_UPDATED) {
                    commitServerView(data);
                    if (serverBacked) {
                        prunePendingAfterView();
                        syncServerViewToLocalCache();
                    }
                    finish({status: REFRESH_FRESH, viewResult: result});
                } else if (result === VIEW_SAME || result === VIEW_STALE) {
                    // SAME / STALE：无副作用（不 prune、不同步旧缓存），视为已是最新。
                    finish({status: REFRESH_FRESH, viewResult: result});
                } else {
                    throw {refreshKind: 'invalid', reason: 'view'};
                }
            }).catch(function (error) {
                if (!isOperationActive(operation, generation)) {
                    // 迟到 / 已取消：不改变已有结果；settled 守卫保证不重复 finish。
                    finish({status: REFRESH_CANCELLED, reason: operation.cancelReason || 'superseded'});
                    return;
                }
                if (error && error.refreshKind === 'invalid') {
                    finish({status: REFRESH_INVALID, reason: error.reason || 'protocol'});
                    return;
                }
                // fetch reject / 无响应对象 / 其它网络层异常：暂时性不可用。
                finish({status: REFRESH_UNAVAILABLE, reason: 'network'});
            });
        });
        var promise = serverRefreshInFlight;
        promise.then(function () {
            if (serverRefreshInFlight === promise) serverRefreshInFlight = null;
        });
        return promise;
    }

    /**
     * 命令是否已被当前服务端权威视图满足（只在 HTTP 200 + 合法视图时评估）。
     * 不比较任何服务端绝对时间点：
     * - submitted：响应 status 必须为 submitted；
     * - never：响应 status 为 never 或 submitted；
     * - snooze：响应 status 为 snoozed / never / submitted；
     * - record_seen：响应 seenLayouts 必须包含本次全部 layoutIds。
     * 不比较 snoozedUntil / retryAfterMs 是否达到本地目标 / firstSeenAt / lastSeenAt。
     */
    function commandSatisfiedByView(command, options) {
        options = options || {};
        if (command === 'submitted') {
            return serverStatus === 'submitted';
        }
        if (command === 'never') {
            return serverStatus === 'never' || serverStatus === 'submitted';
        }
        if (command === 'snooze') {
            return serverStatus === 'snoozed' || serverStatus === 'never'
                || serverStatus === 'submitted';
        }
        if (command === 'record_seen') {
            var layoutIds = options.layoutIds || [];
            return layoutIds.every(function (id) {
                return serverSeenLayouts.indexOf(id) >= 0;
            });
        }
        return false;
    }

    /**
     * 发送服务端状态命令（动作式协议，无 CAS）。
     * - 构造 {surveyId, command[, layoutIds]}，POST JSON；POST body 不包含任何客户端
     *   时间、不包含 expectedRevision / snoozedUntil / updatedAt / retryAfterMs；
     * - 每个命令只发送一次 POST：单次请求超时（SERVER_COMMAND_TIMEOUT_MS）与单次
     *   AbortController；不存在 409 / 重试 / 第二次 attempt；
     * - HTTP 200 且视图合法（VIEW_APPLIED / VIEW_SAME / VIEW_UPDATED）后，按
     *   commandSatisfiedByView 判断命令是否被服务端权威视图满足；只有满足才视为
     *   成功并清理 pending（prunePendingAfterView + syncServerViewToLocalCache）；
     *   VIEW_STALE（低 revision 迟到响应）无法确认本次命令，按失败处理；
     * - 网络错误 / 非法响应 / 超时安全降级：resolve({ok:false, acknowledged:false})，
     *   不抛未处理 rejection，不影响下载工作台，保留本地 fallback；
     * - operation 由 Set 管理：add 时入集合，finish / cancel 时 delete 自身，
     *   cancel() 幂等（aborted=true、清 timeout、abort 在途请求、结束 Promise）；
     * - 不发送用户建议、布局选择、Cookie、token 或原始安装身份。
     */
    function sendServerCommand(generation, command, options) {
        options = options || {};
        var layoutIds = options.layoutIds || null;
        return new Promise(function (resolve) {
            var settled = false;
            var operation = {
                generation: generation,
                aborted: false,
                abortController: null,
                timeoutId: null,
                settled: false,
                cancel: null,
                lastViewResult: null
            };
            serverCommandOperations.add(operation);
            function finish(result) {
                if (settled) return;
                settled = true;
                operation.settled = true;
                if (operation.timeoutId != null) {
                    clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                serverCommandOperations.delete(operation);
                resolve(result);
            }
            function failedResult() {
                return {
                    ok: false,
                    command: command,
                    acknowledged: false,
                    viewResult: operation.lastViewResult || null
                };
            }
            /** 应用命令响应视图：提交权威状态；不在这里清理 pending（确认后统一处理）。 */
            function applyCommandView(data) {
                var result = applyServerView(data);
                if (result === VIEW_INVALID) throw new Error('invalid view');
                operation.lastViewResult = result;
                if (result === VIEW_APPLIED || result === VIEW_UPDATED) {
                    commitServerView(data);
                }
                // VIEW_SAME：同 revision 完全相同，无副作用；VIEW_STALE 迟到响应不应用。
                return result;
            }
            operation.cancel = function () {
                if (settled || operation.aborted) return;
                operation.aborted = true;
                if (operation.timeoutId != null) {
                    clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish(failedResult());
            };
            operation.timeoutId = setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                // 超时：abort 在途请求并以失败结果安全结束。
                operation.aborted = true;
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish(failedResult());
            }, SERVER_COMMAND_TIMEOUT_MS);
            if (!isRuntimeGenerationActive(generation)) {
                finish(failedResult());
                return;
            }
            var body = {
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
                finish(failedResult());
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish(failedResult());
                return;
            }
            request.then(function (response) {
                if (!isOperationActive(operation, generation)) {
                    throw new Error('stale attempt');
                }
                if (!response) throw new Error('http');
                if (!response.ok) {
                    // 服务端错误（503 等）与任何 4xx（包括旧协议 409）一律失败：
                    // 无 CAS 协议下不存在需要重试的冲突。
                    throw new Error('http ' + response.status);
                }
                return response.json();
            }).then(function (data) {
                if (!isOperationActive(operation, generation)) {
                    throw new Error('stale attempt');
                }
                var viewResult = applyCommandView(data);
                if (viewResult === VIEW_STALE) {
                    // 低 revision 迟到响应：无法确认本次命令，保留 pending 与本地 fallback。
                    finish(failedResult());
                    return;
                }
                var satisfied = commandSatisfiedByView(command, {layoutIds: layoutIds});
                if (satisfied) {
                    prunePendingAfterView({
                        command: command,
                        acknowledged: true,
                        layoutIds: layoutIds
                    });
                    syncServerViewToLocalCache();
                } else {
                    // 服务端视图未满足命令（例如 record_seen 缺布局）：保留 pending。
                    syncServerViewToLocalCache();
                }
                finish({
                    ok: satisfied,
                    command: command,
                    acknowledged: satisfied,
                    reason: satisfied ? 'satisfied' : 'not-satisfied',
                    viewResult: viewResult
                });
            }).catch(function () {
                finish(failedResult());
            });
        });
    }

    /**
     * 服务端恢复后的有限本地状态回放（每次 init 最多一次，必须返回明确 Promise）：
     * - 绑定发起方 serverLoadOperation：每个阶段与每个 continuation 都重新验证
     *   isOperationActive(operation, generation)，任何检查失败立即返回 cancelled /
     *   no-op 结果——不进入下一阶段、不修改 pendingLocalState / pendingLocalSeen、
     *   不写 localStorage、不输出旧 generation warning；
     * - 先处理决策状态（submitted / never / snooze 按优先级回放），再处理 seen；
     *   两个命令顺序执行，不制造任何并发状态竞态；
     * - 只回放本地更强 / 服务器缺失的合法数据，不上传客户端时间戳（snooze 由服务端
     *   重新计算 7 天）；
     * - 每个命令都有单次请求超时，整个 reconciliation 不会永久 pending；
     * - 不发送 PostHog 事件，失败保留本地回退（pendingLocal* 不清除）。
     */
    function reconcileLocalState(operation, generation, localFallback) {
        if (!isOperationActive(operation, generation)) {
            return Promise.resolve({decisionResult: null, seenResult: null});
        }
        if (reconciled) {
            return Promise.resolve({decisionResult: null, seenResult: null});
        }
        reconciled = true;
        return reconcileDecision(operation, generation, localFallback).then(function (decisionResult) {
            if (!isOperationActive(operation, generation)) {
                // destroy / 被取代：不得进入 reconcileSeen、不得写 pendingLocalSeen。
                return {decisionResult: null, seenResult: null};
            }
            return reconcileSeen(operation, generation, localFallback).then(function (seenResult) {
                if (!isOperationActive(operation, generation)) {
                    return {decisionResult: null, seenResult: null};
                }
                return {
                    decisionResult: decisionResult,
                    seenResult: seenResult
                };
            });
        });
    }

    /**
     * 决策回放：
     * - local submitted + server null / never / 更短 snoozed → submitted；
     * - local never + server null / snoozed → never；
     * - local 有效 snoozed + server null / 更短 snoozed → snooze（只比较两个「剩余
     *   时长」：localRemaining 与 serverRemaining，绝不比较跨时钟绝对 snoozedUntil）；
     * - server 已等于或强于 local → 不回放。
     * 写 pendingLocalState 前与命令 Promise resolve 后都重新验证 operation 活性。
     */
    function reconcileDecision(operation, generation, localFallback) {
        if (!isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        var localState = localFallback && isPlainObject(localFallback.state)
            ? localFallback.state
            : null;
        if (!localState || localState.surveyId !== config.surveyId) {
            return Promise.resolve({replayed: false});
        }
        var now = timers.now();
        var localStrong = normalizeDecisionState(localState, now);
        if (!localStrong) {
            return Promise.resolve({replayed: false});
        }
        var serverStrong = serverViewAsState(now);
        if (serverStrong) {
            if (serverStrong.status === 'snoozed' && localStrong.status === 'snoozed') {
                // 双方都是 snoozed：只比较两个「剩余时长」，服务器已提供至少相同的
                // 阻断效果（localRemaining <= serverRemaining + 容差）时不回放。
                var localRemaining = remainingSnoozeMs(localStrong, now);
                var serverRemaining = remainingSnoozeMs(serverStrong, now);
                if (localRemaining <= serverRemaining + RECONCILE_REMAINING_TOLERANCE_MS) {
                    return Promise.resolve({replayed: false});
                }
            } else if (compareDecisionState(serverStrong, localStrong, now) >= 0) {
                // 服务端已更强或相同：本地 fallback 由 syncServerViewToLocalCache 覆盖。
                return Promise.resolve({replayed: false});
            }
        }
        // 发送前先记入 pendingLocalState：请求失败 / 超时时本地回退仍参与 effectiveState。
        // 写入前重新验证活性：destroy 后旧链不得修改新 generation 的 pendingLocalState。
        if (!isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        pendingLocalState = localStrong;
        var command = localStrong.status === 'snoozed' ? 'snooze' : localStrong.status;
        return sendServerCommand(generation, command, null).then(function (result) {
            if (!isOperationActive(operation, generation)) {
                // 命令完成后旧链已失效：不输出旧 generation warning、不再继续。
                return {replayed: false, cancelled: true};
            }
            if (!result.ok) {
                warn('layout survey: local state replay failed; keeping local fallback');
            }
            return {replayed: !!result.ok, command: command};
        });
    }

    /**
     * seen 回放：只发送服务器缺失的合法布局 ID（seen 的业务含义是「该安装已经体验过
     * 此布局」，按布局 ID 存在性判断，不比较任何服务端时间戳），经
     * record_seen 合并；请求失败 / 超时保留 pendingLocalSeen。
     * 入口、写 pendingLocalSeen 前、命令 Promise resolve 后都重新验证 operation 活性。
     */
    function reconcileSeen(operation, generation, localFallback) {
        if (!isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        var localSeen = localFallback && isPlainObject(localFallback.seen)
            ? localFallback.seen
            : {};
        var layoutIds = [];
        LAYOUT_IDS.forEach(function (id) {
            var entry = localSeen[id];
            if (entry && typeof entry.lastSeenAt === 'number' && entry.lastSeenAt > 0
                    && serverSeenLayouts.indexOf(id) < 0) {
                layoutIds.push(id);
            }
        });
        if (!layoutIds.length) {
            return Promise.resolve({replayed: false});
        }
        // 写 pendingLocalSeen 前重新验证活性：destroy 后旧链不得写新 generation 的
        // pendingLocalSeen。
        if (!isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        layoutIds.forEach(function (id) {
            var entry = localSeen[id];
            if (entry && typeof entry === 'object') {
                pendingLocalSeen[id] = {
                    firstSeenAt: typeof entry.firstSeenAt === 'number' ? entry.firstSeenAt : 0,
                    lastSeenAt: entry.lastSeenAt
                };
            }
        });
        return sendServerCommand(generation, 'record_seen', {layoutIds: layoutIds})
            .then(function (result) {
                if (!isOperationActive(operation, generation)) {
                    // 命令完成后旧链已失效：不输出旧 generation warning。
                    return {replayed: false, cancelled: true};
                }
                if (!result.ok) {
                    warn('layout survey: local seen replay failed; keeping local fallback');
                }
                return {replayed: !!result.ok};
            });
    }

    /**
     * 统一决策状态归一化（客户端时钟域）：只接受当前 config.surveyId 的合法状态；
     * 过期 snoozed 视为无状态；非法状态 / 其它 Survey 一律返回 null。
     */
    function normalizeDecisionState(state, now) {
        if (!state || state.surveyId !== config.surveyId) return null;
        if (typeof state.status !== 'string'
                || (state.status !== 'submitted' && state.status !== 'never'
                    && state.status !== 'snoozed')) {
            return null;
        }
        if (state.status === 'snoozed'
                && remainingSnoozeMs(state, now) <= 0) {
            return null;
        }
        return state;
    }

    /**
     * snoozed 剩余时长（纯函数，毫秒，客户端时钟域）：submitted / never / 空状态
     * 一律 0；已过期返回 0。
     */
    function remainingSnoozeMs(state, clientNow) {
        if (!state || state.status !== 'snoozed') return 0;
        return Math.max(0, (typeof state.snoozedUntil === 'number' ? state.snoozedUntil : 0) - clientNow);
    }

    /**
     * 服务端权威视图转换为客户端时钟域的伪状态（绝不保存服务端绝对时间）：
     * - serverStatus=snoozed 且 canShow=false：本地截止时间 =
     *   max(serverLocalBlockUntil, clientNow + retryAfterMs)，与本地状态同域可比较；
     * - 其它状态原样映射（updatedAt 为占位 0，不参与业务比较）。
     * serverBacked 关闭或无状态时返回 null。
     */
    function serverViewAsState(clientNow) {
        if (!serverBacked || !serverStatus) return null;
        if (serverStatus === 'snoozed' && !serverCanShow) {
            var until = serverLocalBlockUntil > 0
                ? serverLocalBlockUntil
                : safeClientTimeAdd(clientNow, serverRetryAfterMs);
            return {surveyId: config.surveyId, status: 'snoozed', updatedAt: 0, snoozedUntil: until};
        }
        return {surveyId: config.surveyId, status: serverStatus, updatedAt: 0, snoozedUntil: 0};
    }

    /**
     * 统一决策状态强度比较（客户端时钟域）：submitted > never > 未过期 snoozed > null；
     * 双方都是 snoozed 时比较各自剩余时长（更长者更强）；同 submitted / 同 never
     * 强度相同；updatedAt 不参与业务优先级。
     * 返回 > 0：left 更强；= 0：等价强度；< 0：right 更强。
     */
    function compareDecisionState(left, right, now) {
        var l = normalizeDecisionState(left, now);
        var r = normalizeDecisionState(right, now);
        if (!l && !r) return 0;
        if (!l) return -1;
        if (!r) return 1;
        if (l.status !== r.status) {
            if (l.status === 'submitted') return 1;
            if (r.status === 'submitted') return -1;
            if (l.status === 'never') return 1;
            return -1;
        }
        if (l.status === 'snoozed') {
            var lr = remainingSnoozeMs(l, now);
            var rr = remainingSnoozeMs(r, now);
            if (lr !== rr) return lr > rr ? 1 : -1;
        }
        return 0;
    }

    /** 取两者中更强（或等价时返回 left）的决策状态。 */
    function strongerDecisionState(a, b, now) {
        return compareDecisionState(a, b, now) >= 0 ? a : b;
    }

    /** a 是否至少与 b 一样强。 */
    function isDecisionAtLeastAsStrong(a, b, now) {
        return compareDecisionState(a, b, now) >= 0;
    }

    /**
     * 除 candidate 之外的已知最强状态（全部为客户端时钟域：服务端视图已转换为本地
     * 截止时间）。只有 candidate 严格强于该结果时才接受状态转移并发送服务端命令。
     * 返回 {state, source} 或 null。
     */
    function strongestLocalExcludingCandidate(candidate, clientNow) {
        var sources = [];
        if (pendingLocalState && pendingLocalState.surveyId === config.surveyId) {
            sources.push({state: pendingLocalState, source: 'local'});
        }
        var serverState = serverViewAsState(clientNow);
        if (serverState) {
            sources.push({state: serverState, source: 'server'});
        }
        var local = readLocalStateRaw();
        if (local && local.surveyId === config.surveyId) {
            sources.push({state: local, source: 'local'});
        }
        if (sessionState && sessionState.surveyId === config.surveyId) {
            sources.push({state: sessionState, source: 'local'});
        }
        var best = null;
        sources.forEach(function (entry) {
            var normalized = normalizeDecisionState(entry.state, clientNow);
            if (!normalized) return;
            if (!best || compareDecisionState(normalized, best.state, clientNow) > 0) {
                best = {state: normalized, source: entry.source};
            }
        });
        return best;
    }

    /**
     * 状态是否阻断调查主动展示（客户端时钟域）：submitted / never / 未到期的 snoozed。
     */
    function isBlockingDecision(state, now) {
        if (!state) return false;
        return state.status === 'submitted' || state.status === 'never'
            || (state.status === 'snoozed' && remainingSnoozeMs(state, now) > 0);
    }

    /**
     * 已显示的表单只用 submitted 去重；never / snoozed 是提醒决策，不能覆盖用户随后
     * 主动填写并提交的反馈。
     */
    function isSubmittedDecision(state) {
        return !!state && state.status === 'submitted';
    }

    /**
     * 提交前（refresh 不可用时）重新读取本地 submitted：effectiveState（serverBacked 为
     * 服务端权威视图 + 未确认 pending）与 localStorage STATE_KEY 协调缓存（另一标签页刚写入
     * 但 storage 事件尚未送达时同样必须阻止提交）。
     */
    function hasSubmittedLocalDecision() {
        var state = readStateFresh();
        if (state && state.surveyId === dialogSurveyId && isSubmittedDecision(state)) {
            return true;
        }
        var raw = readLocalStateRaw();
        if (raw && raw.surveyId === dialogSurveyId && isSubmittedDecision(raw)) {
            return true;
        }
        return false;
    }

    /**
     * 有效状态记录：合并服务端权威视图（已转换为客户端时钟域伪状态）与尚未确认的
     * 本地 fallback（必要时含 localStorage / sessionState），按客户端时钟域强度比较取
     * 最强（submitted > never > 未过期 snoozed > 无状态，snoozed 按剩余时长比较）；
     * 过期 snoozed 视为无状态。自动展示门禁不得忽略未确认的本地
     * submitted / never / snoozed。返回 {state, source} 或 null。
     */
    function effectiveStateRecord() {
        if (!config) {
            return null;
        }
        var candidates = [];
        var serverState = serverViewAsState(clientWallNow());
        if (serverState) {
            candidates.push({state: serverState, source: 'server'});
        }
        if (pendingLocalState && pendingLocalState.surveyId === config.surveyId) {
            candidates.push({state: pendingLocalState, source: 'local'});
        }
        if (!serverBacked) {
            // local 模式：localStorage 是事实来源（损坏清理语义见 readStateFresh）。
            var local = readLocalStateRaw();
            if (local && local.surveyId === config.surveyId) {
                candidates.push({state: local, source: 'local'});
            }
            if (sessionState && sessionState.surveyId === config.surveyId) {
                candidates.push({state: sessionState, source: 'local'});
            }
        }
        var clientNow = clientWallNow();
        var best = null;
        candidates.forEach(function (entry) {
            var normalized = normalizeDecisionState(entry.state, clientNow);
            if (!normalized) return;
            if (!best || compareDecisionState(normalized, best.state, clientNow) > 0) {
                best = {state: normalized, source: entry.source};
            }
        });
        return best ? {state: best.state, source: best.source} : null;
    }

    /**
     * 有效状态（兼容包装）：只返回最强状态本身。
     */
    function effectiveState() {
        var record = effectiveStateRecord();
        return record ? record.state : null;
    }

    /**
     * STATE_KEY 本地协调缓存契约：snoozedUntil 永远属于客户端墙钟域；record 中的
     * snoozedUntil（无论来源是本地 fallback 还是已转换的服务端视图）已经是客户端
     * 时钟域，这里只做一致性转换：
     * - record 为空 → null（允许清理状态）；
     * - snoozed：已过期 → null；否则写本地截止时间；已有同 Survey 本地 snooze 且
     *   截止时间差距不超过 {@link #LOCAL_SNOOZE_WRITE_TOLERANCE_MS} 时保留旧对象
     *   （避免每次 GET 微小重写）；
     * - submitted / never：状态类型保持；同业务状态已有本地对象时保留旧对象
     *   （保留其 updatedAt；updatedAt 不参与业务强度）。
     * 禁止把服务端 snoozedUntil / serverTime / retryAfterMs 作为绝对时间写入 STATE_KEY。
     */
    function serverViewToLocalState(record, clientNow, existingLocalState) {
        if (!record || !record.state) return null;
        var state = record.state;
        if (state.status === 'snoozed') {
            if (!isFiniteInteger(state.snoozedUntil) || state.snoozedUntil <= clientNow) {
                return null;
            }
            var candidateUntil = state.snoozedUntil;
            var existing = existingLocalState;
            if (existing && existing.surveyId === state.surveyId
                    && existing.status === 'snoozed'
                    && isFiniteInteger(existing.snoozedUntil)
                    && Math.abs(existing.snoozedUntil - candidateUntil)
                        <= LOCAL_SNOOZE_WRITE_TOLERANCE_MS) {
                return {
                    surveyId: existing.surveyId,
                    status: 'snoozed',
                    updatedAt: isFiniteInteger(existing.updatedAt) ? existing.updatedAt : clientNow,
                    snoozedUntil: existing.snoozedUntil
                };
            }
            return {
                surveyId: state.surveyId,
                status: 'snoozed',
                updatedAt: clientNow,
                snoozedUntil: candidateUntil
            };
        }
        var existingState = existingLocalState;
        if (state.status === 'never' && existingState && existingState.surveyId === state.surveyId
                && existingState.status === 'submitted') {
            // 本地已有 submitted：更强状态不被服务端 never 覆盖（保留本地对象）。
            return existingState;
        }
        if (existingState && existingState.surveyId === state.surveyId
                && existingState.status === state.status
                && isFiniteInteger(existingState.updatedAt)) {
            // 已有相同业务状态：保留旧对象（updatedAt 不后退）。
            return existingState;
        }
        return {
            surveyId: state.surveyId,
            status: state.status,
            updatedAt: clientNow,
            snoozedUntil: 0
        };
    }

    /**
     * SEEN_KEY 本地协调缓存：只要求布局 ID 存在性，时间戳一律客户端时钟域。
     * - 已有本地 entry：保留本地时间（不复制任何服务端时间戳）；
     * - pendingLocalSeen（未确认的本地贡献）：保持原客户端时间；
     * - 服务端新增而本地没有的布局（serverSeenLayouts）：用当前客户端时间作为本地
     *   firstSeenAt / lastSeenAt。
     */
    function localSeenForLocalCache(clientNow, existingLocalSeen) {
        var seen = {};
        LAYOUT_IDS.forEach(function (id) {
            var existing = existingLocalSeen && existingLocalSeen[id];
            var pending = pendingLocalSeen && pendingLocalSeen[id];
            var serverHas = serverBacked && serverSeenLayouts.indexOf(id) >= 0;
            var firstSeenAt = null;
            var lastSeenAt = null;
            if (existing && isFiniteInteger(existing.firstSeenAt)
                    && isFiniteInteger(existing.lastSeenAt)) {
                firstSeenAt = existing.firstSeenAt;
                lastSeenAt = existing.lastSeenAt;
            }
            if (pending && isFiniteInteger(pending.firstSeenAt)
                    && isFiniteInteger(pending.lastSeenAt)) {
                firstSeenAt = firstSeenAt === null
                    ? pending.firstSeenAt
                    : Math.min(firstSeenAt, pending.firstSeenAt);
                lastSeenAt = lastSeenAt === null
                    ? pending.lastSeenAt
                    : Math.max(lastSeenAt, pending.lastSeenAt);
            }
            if (serverHas) {
                firstSeenAt = firstSeenAt === null ? clientNow : firstSeenAt;
                lastSeenAt = lastSeenAt === null ? clientNow : lastSeenAt;
            }
            if (firstSeenAt !== null && lastSeenAt !== null) {
                seen[id] = {firstSeenAt: firstSeenAt, lastSeenAt: lastSeenAt};
            }
        });
        return seen;
    }

    /**
     * 有效 seen：合并服务端权威 seenLayouts（存在性伪 entry）与 pendingLocalSeen
     * （local 模式再并入 localStorage），只接受三个稳定布局 ID；同一布局 firstSeenAt
     * 取较早值、lastSeenAt 取较晚值。服务器旧 seen 不得清除尚未确认的本地布局记录
     * （pending 合并语义保证）。
     */
    function effectiveSeen() {
        var sources = [];
        if (serverBacked && serverSeenLayouts.length) {
            var serverSeen = {};
            serverSeenLayouts.forEach(function (id) {
                serverSeen[id] = {firstSeenAt: 1, lastSeenAt: 1};
            });
            sources.push(serverSeen);
        }
        if (pendingLocalSeen && typeof pendingLocalSeen === 'object') {
            sources.push(pendingLocalSeen);
        }
        if (!serverBacked) {
            var local = readLocalSeenRaw();
            if (local && typeof local === 'object') {
                sources.push(local);
            }
        }
        var merged = {};
        LAYOUT_IDS.forEach(function (id) {
            var firstSeenAt = null;
            var lastSeenAt = null;
            sources.forEach(function (source) {
                var entry = source[id];
                if (!entry || typeof entry.firstSeenAt !== 'number'
                        || typeof entry.lastSeenAt !== 'number') {
                    return;
                }
                firstSeenAt = firstSeenAt === null
                    ? entry.firstSeenAt
                    : Math.min(firstSeenAt, entry.firstSeenAt);
                lastSeenAt = lastSeenAt === null
                    ? entry.lastSeenAt
                    : Math.max(lastSeenAt, entry.lastSeenAt);
            });
            if (firstSeenAt !== null && lastSeenAt !== null) {
                merged[id] = {firstSeenAt: firstSeenAt, lastSeenAt: lastSeenAt};
            }
        });
        return merged;
    }

    /**
     * 视图应用后按服务端权威状态清除已确认的 pending 项。ackContext 可空：
     * {command, acknowledged, layoutIds}。
     * - pendingLocalState 只在「服务端已满足」时清除：本次命令 acknowledged（明确确认），
     *   或服务端视图按 pending 自身状态规则已经覆盖（submitted ← server submitted；
     *   never ← server submitted / never；snoozed ← server submitted / never）。
     *   pending snooze 只在命令明确确认后清除——refresh 等无确认路径不得仅因服务端
     *   已有 snooze 而缩短本地 fallback（本地截止时间由 syncServerViewToLocalCache 按
     *   最强状态转换）；
     * - pendingLocalSeen 按布局 ID 存在性逐项清理：服务端已存在该布局，或
     *   record_seen 命令 acknowledged 且该布局在本次 layoutIds 中。
     *   不比较任何服务端时间戳。
     * 只做比较，不直接写 localStorage（由 syncServerViewToLocalCache 统一同步）。
     * STALE / INVALID 响应不得调用本函数。
     */
    function prunePendingAfterView(ackContext) {
        ackContext = ackContext || null;
        var acknowledged = !!(ackContext && ackContext.acknowledged === true);
        var command = ackContext ? ackContext.command : null;
        var layoutIds = ackContext && Array.isArray(ackContext.layoutIds)
            ? ackContext.layoutIds
            : null;
        if (pendingLocalState && pendingLocalState.surveyId === config.surveyId && serverBacked) {
            var commandMatches = acknowledged
                && ((command === 'submitted' && pendingLocalState.status === 'submitted')
                    || (command === 'never' && pendingLocalState.status === 'never')
                    || (command === 'snooze' && pendingLocalState.status === 'snoozed'));
            var serverCovers = (pendingLocalState.status === 'submitted'
                    && serverStatus === 'submitted')
                || (pendingLocalState.status === 'never'
                    && (serverStatus === 'submitted' || serverStatus === 'never'))
                || (pendingLocalState.status === 'snoozed'
                    && (serverStatus === 'submitted' || serverStatus === 'never'));
            if (commandMatches || serverCovers) {
                pendingLocalState = null;
            }
        }
        Object.keys(pendingLocalSeen).forEach(function (id) {
            if (serverSeenLayouts.indexOf(id) >= 0
                    || (acknowledged && command === 'record_seen' && layoutIds
                        && layoutIds.indexOf(id) >= 0)) {
                delete pendingLocalSeen[id];
            }
        });
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
     * 安全写入 helper：写入前读取当前值，完全相同时不重复 setItem（避免多标签页
     * 之间无意义的反复写入与 storage 事件循环）；localStorage 异常安全降级。
     */
    function setStorageIfChanged(key, serializedValue) {
        if (!storage) return;
        try {
            if (storage.getItem(key) === serializedValue) return;
            storage.setItem(key, serializedValue);
        } catch (_) {
            // 存储不可用时仅保留内存态
        }
    }

    /** 安全删除 helper：不存在时不再重复 removeItem；localStorage 异常安全降级。 */
    function removeStorageIfPresent(key) {
        if (!storage) return;
        try {
            if (storage.getItem(key) === null) return;
            storage.removeItem(key);
        } catch (_) {
            // 清理尽力而为
        }
    }

    /**
     * 统一本地协调缓存同步：STATE_KEY 只写客户端时钟域状态（经 serverViewToLocalState
     * 转换，绝不把任何服务端绝对时间点写入），SEEN_KEY 在 serverBacked 下同样只写
     * 客户端时钟域 seen（服务端布局 ID 存在性 + 本地时间戳）。
     * sessionState 同步为转换后的 localState，而不是服务端视图；服务端权威视图继续
     * 只保留在 server* 变量。
     * 本函数不修改 serverRevision、不把 localStorage 当作服务器权威 revision；
     * 初始 GET / reconciliation / refresh GET / POST 200 / 本地 submitted·never·snooze /
     * record_seen 成功或失败全部走这里。
     */
    function syncServerViewToLocalCache() {
        if (!storage) return;
        var record = effectiveStateRecord();
        var localState = serverViewToLocalState(record, clientWallNow(), readLocalStateRaw());
        sessionState = localState;
        if (localState) {
            setStorageIfChanged(STATE_KEY, JSON.stringify(localState));
        } else if (!pendingLocalState || pendingLocalState.surveyId !== config.surveyId) {
            // 只有确认不存在未确认 fallback 时才允许 removeItem。
            removeStorageIfPresent(STATE_KEY);
        }
        if (serverBacked) {
            // 服务端只提供布局 ID 存在性，绝对时间戳不进入本地缓存。
            setStorageIfChanged(SEEN_KEY,
                JSON.stringify(localSeenForLocalCache(clientWallNow(), readLocalSeenRaw())));
        } else {
            setStorageIfChanged(SEEN_KEY, JSON.stringify(effectiveSeen()));
        }
    }

    /**
     * 布局体验记录合并：serverBacked 下把本地尚未被服务器确认的布局记入
     * pendingSeenLayouts（按布局 ID 存在性判断，不比较任何服务端时间戳），去抖后以
     * record_seen 命令提交（不再发送完整 seen）。已确认布局不再重复提交。
     */
    function scheduleServerSeenFlush() {
        if (!serverBacked) return;
        var local = sessionSeen && typeof sessionSeen === 'object' ? sessionSeen : {};
        LAYOUT_IDS.forEach(function (id) {
            var entry = local[id];
            if (entry && typeof entry.lastSeenAt === 'number' && entry.lastSeenAt > 0
                    && serverSeenLayouts.indexOf(id) < 0 && !pendingSeenLayouts[id]) {
                pendingSeenLayouts[id] = true;
            }
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
            // serverBacked：服务端权威视图（已转换为客户端时钟域伪状态）与未确认
            // 本地 fallback 合并后的有效状态。
            return effectiveState();
        }
        // local 模式：来源一律为客户端时钟域。
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
            removeStorageIfPresent(STATE_KEY);
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

    /**
     * 本地决策状态单调写入（writeState(status, snoozedUntil)）。
     * 先计算不含 candidate 的已有最强状态 previousStrongest（全部为客户端时钟域：
     * pendingLocalState / 已转换的服务端视图 / localStorage STATE_KEY / sessionState），
     * 再比较 candidate：
     * - 只有 candidate 严格强于 previousStrongest（compareDecisionState > 0）才接受
     *   转移（transitionAccepted=true，effectiveNext=candidate）并发送对应服务端命令；
     * - 等强度（=== 0）或更弱时：transitionAccepted=false，effectiveNext=previousStrongest
     *   原对象（保留其 updatedAt），不写新 candidate、不写 localStorage（序列化未变化）、
     *   不发送服务端命令——绝不因 candidate 位于来源数组首位而隐式赢得 tie-break；
     * - previousStrongest 不存在时 candidate 被接受；
     * - submitted / never 不参与 updatedAt 比较；相同 snoozedUntil 的两个 snoozed 业务
     *   等价；更长 snoozedUntil（客户端时钟域剩余时长）才是严格升级；
     * - storage 是否写入只看最终序列化结果是否变化（setStorageIfChanged 去重）；
     * - 返回 {requestedState, previousState, effectiveState, transitionAccepted,
     *   serverCommandStarted}，供 dismissed 等生命周期事件按最终有效状态决策。
     */
    function writeState(status, snoozedUntil) {
        if (!config) return null;
        var clientNow = timers.now();
        var candidate = {
            surveyId: config.surveyId,
            status: status,
            updatedAt: clientNow,
            snoozedUntil: snoozedUntil || 0
        };
        var previousStrongest = strongestLocalExcludingCandidate(candidate, clientNow);
        var comparison = previousStrongest
            ? compareDecisionState(candidate, previousStrongest.state, clientNow)
            : 1;
        var transitionAccepted = comparison > 0;
        var effectiveNext = null;
        var effectiveSource = 'local';
        if (transitionAccepted) {
            effectiveNext = candidate;
        } else if (previousStrongest) {
            // 等强或更弱：保留已有对象（原 updatedAt），绝不覆盖。
            effectiveNext = previousStrongest.state;
            effectiveSource = previousStrongest.source;
        } else {
            effectiveNext = candidate;
        }
        var serverCommandStarted = false;
        if (serverBacked) {
            // sessionState 与 STATE_KEY 只保存客户端时钟域转换后的本地协调缓存；
            // 服务端绝对时间点绝不落本地（serverViewAsState 已是本地截止时间）。
            var localCacheNext = serverViewToLocalState(
                {state: effectiveNext, source: effectiveSource}, clientNow, readLocalStateRaw());
            sessionState = localCacheNext;
            var serverStrong = serverViewAsState(clientNow);
            if (serverStrong && compareDecisionState(serverStrong, effectiveNext, clientNow) >= 0) {
                // 服务端已更强或等价：没有未确认的本地 fallback。
                pendingLocalState = null;
            } else {
                // 本地决策最强（或尚待服务端确认）：保留为未确认 fallback，
                // 绝不写弱于 effectiveNext 的状态。
                pendingLocalState = effectiveNext;
            }
            if (storage) {
                if (localCacheNext) {
                    setStorageIfChanged(STATE_KEY, JSON.stringify(localCacheNext));
                } else {
                    removeStorageIfPresent(STATE_KEY);
                }
            }
            if (transitionAccepted) {
                var command = status === 'submitted' ? 'submitted'
                    : status === 'never' ? 'never' : 'snooze';
                // 命令不带任何时间戳；snooze 时长完全由服务端按自己的时钟计算。
                serverCommandStarted = true;
                sendServerCommand(currentRuntimeGeneration(), command, null).then(function (result) {
                    if (!result.ok) {
                        warn('layout survey: server state save failed; keeping local fallback');
                    }
                }).catch(function () { /* 安全降级 */ });
            }
        } else {
            sessionState = effectiveNext;
            if (storage) {
                setStorageIfChanged(STATE_KEY, JSON.stringify(effectiveNext));
            }
        }
        return {
            requestedState: candidate,
            previousState: previousStrongest ? previousStrongest.state : null,
            effectiveState: effectiveNext,
            transitionAccepted: transitionAccepted,
            serverCommandStarted: serverCommandStarted
        };
    }

    /**
     * 展示门禁（全部基于客户端时钟域，服务端绝对时间不参与）：
     * - localStorage / pending 中存在 submitted / never / 有效本地 snooze → false；
     * - serverBacked 且 serverStatus 为 submitted / never → false；
     * - serverBacked 且 serverStatus=snoozed、canShow=false 且当前本地时间小于
     *   serverLocalBlockUntil → false；
     * - serverBacked 且 snoozed 的本地截止已到 → true（允许重新 GET 服务端状态，
     *   见 showSurveyFlow 的到期刷新）；
     * - 服务端暂时不可用：localStorage fallback 已过期且无 terminal 状态时按现有
     *   availability 策略继续（明确的弱去重 fail-open，不是解释服务端绝对时间）。
     */
    function stateAllowsShow(clientNow) {
        var state = readState();
        if (!state) return true;
        return !isBlockingDecision(state, clientNow);
    }

    function readSeenRaw() {
        if (serverBacked) {
            // 服务端绝对时间戳不进入会话 seen：只保留客户端时钟域（存在性语义）。
            sessionSeen = localSeenForLocalCache(clientWallNow(), readLocalSeenRaw());
            return Object.assign({}, sessionSeen);
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
            removeStorageIfPresent(SEEN_KEY);
            sessionSeen = {};
            return {};
        }
        sessionSeen = parsed;
        return Object.assign({}, parsed);
    }

    function writeSeen(seen) {
        sessionSeen = seen;
        if (serverBacked) {
            // 本地协调缓存：同浏览器跨标签体验阈值同步 + 服务端恢复前的临时保护。
            // 未确认部分记入 pendingLocalSeen（由 recordSeen 维护），绝不直接当成
            // 服务端已确认事实写入 serverSeenLayouts。
            if (storage) {
                setStorageIfChanged(SEEN_KEY, JSON.stringify(seen));
            }
            scheduleServerSeenFlush();
            return;
        }
        if (storage) {
            setStorageIfChanged(SEEN_KEY, JSON.stringify(seen));
        }
    }

    function recordSeen(layoutId, now) {
        if (!layoutId || LAYOUT_IDS.indexOf(layoutId) < 0) return null;
        var seen = readSeenRaw();
        var entry = seen[layoutId] || {firstSeenAt: 0, lastSeenAt: 0};
        if (!entry.firstSeenAt) entry.firstSeenAt = now;
        entry.lastSeenAt = now;
        seen[layoutId] = entry;
        if (serverBacked) {
            // 服务器确认前保留本地贡献：earliest firstSeenAt / latest lastSeenAt。
            var pending = pendingLocalSeen[layoutId] || {};
            pending.firstSeenAt = typeof pending.firstSeenAt === 'number'
                ? Math.min(pending.firstSeenAt, entry.firstSeenAt)
                : entry.firstSeenAt;
            pending.lastSeenAt = Math.max(
                typeof pending.lastSeenAt === 'number' ? pending.lastSeenAt : 0,
                entry.lastSeenAt);
            pendingLocalSeen[layoutId] = pending;
        }
        writeSeen(seen);
        return distinctSeenCount(seen);
    }

    function currentLayoutId() {
        if (configuredLayoutId) return configuredLayoutId;
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
       PostHog 插件客户端
    ============================================================ */

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

    /** 可取消的 consumer 等待层；SDK 脚本与命名实例的生命周期由 PostHog 插件统一拥有。 */
    function resolveSdk(generation) {
        if (sdkLoadOperation && sdkLoadOperation.generation === generation) {
            return sdkLoadOperation.promise;
        }
        var operation = {
            generation: generation,
            promise: null,
            settled: false,
            cancel: null
        };
        sdkLoadOperation = operation;
        operation.promise = new Promise(function (resolve) {
            var settled = false;
            function finish(sdk) {
                if (settled) return;
                settled = true;
                operation.settled = true;
                resolve(sdk);
            }
            operation.cancel = function () {
                finish(null);
            };
            try {
                global.PixivPostHog.createSurveyClient({
                    ownerKey: POSTHOG_OWNER_KEY,
                    posthog: POSTHOG,
                    distinctId: serverIdentityAvailable && serverDistinctId ? serverDistinctId : '',
                    beforeSend: beforeSendFilter
                }).then(function (sdk) {
                    finish(isRuntimeGenerationActive(generation) ? sdk : null);
                }, function () {
                    finish(null);
                });
            } catch (_) {
                finish(null);
            }
        });
        return operation.promise;
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
            privacy.textContent = t('privacy', '本问卷使用 PostHog SDK 提交，只收集您填写的内容和由随机安装身份单向散列生成的匿名标识（不可逆，仅用于避免重复弹窗），不会发送其他任何信息。');

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

            var footer = buildElement('div', 'plf-footer');
            var githubLink = buildElement('a', 'plf-github');
            githubLink.href = 'https://github.com/Sywyar/PixivDownloader';
            githubLink.target = '_blank';
            githubLink.rel = 'noopener noreferrer';
            githubLink.setAttribute('data-i18n-title', I18N_NS + ':github-repo');
            githubLink.setAttribute('data-i18n-aria-label', I18N_NS + ':github-repo');
            githubLink.title = t('github-repo', '跳转到代码仓库');
            githubLink.setAttribute('aria-label', t('github-repo', '跳转到代码仓库'));
            var svgNs = 'http://www.w3.org/2000/svg';
            var githubSvg = global.document.createElementNS(svgNs, 'svg');
            githubSvg.setAttribute('viewBox', '0 0 16 16');
            githubSvg.setAttribute('width', '18');
            githubSvg.setAttribute('height', '18');
            githubSvg.setAttribute('fill', 'currentColor');
            githubSvg.setAttribute('aria-hidden', 'true');
            var githubPath = global.document.createElementNS(svgNs, 'path');
            githubPath.setAttribute('d', 'M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z');
            githubSvg.appendChild(githubPath);
            githubLink.appendChild(githubSvg);
            footer.appendChild(githubLink);
            footer.appendChild(actions);

            dialog.appendChild(closeButton);
            dialog.appendChild(title);
            dialog.appendChild(description);
            dialog.appendChild(group);
            dialog.appendChild(suggestionWrap);
            dialog.appendChild(privacy);
            dialog.appendChild(error);
            dialog.appendChild(footer);
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
        if (key === 'error-state-verification') return '调查状态暂不可用，请稍后重试。';
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
        // 本地 snooze 使用安全加法（防 Number 非安全溢出）；snoozedUntil 属于客户端
        // 时钟域，只用于本地 fallback（服务端恢复前），绝不上传服务端。
        writeState('snoozed', safeClientTimeAdd(clientWallNow(), SNOOZE_MS));
        closeDialog(true);
    }

    function dismissNever() {
        if (submitting || !dialogOpen) return;
        var result = writeState('never');
        closeDialog(true);
        // 生命周期事件必须与最终有效状态一致：只有 writeState 的转移被接受且
        // effectiveState 确实为 never 时才发送 dismissed（transitionAccepted 守卫：
        // 重复 never / 已有更强 submitted / 更长 snooze 时不得重复发送 dismissed）。
        // 重复 never 操作不是重试 dismissed 的隐式机制。
        if (result && result.transitionAccepted === true
                && result.effectiveState && result.effectiveState.status === 'never') {
            sendDismissedBestEffort().catch(function () {});
        }
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

        // 发送前执行一次不走缓存的持久化状态读取：另一标签页可能已经提交，
        // 此时取消本次提交并关闭弹窗，不发送
        // 第二条 survey sent（弱去重，无法消除完全同时点击的竞态；
        // never / snoozed 只控制主动展示，不覆盖当前表单里的主动提交）。
        var freshState = readStateFresh();
        if (freshState && freshState.surveyId === dialogSurveyId
                && isSubmittedDecision(freshState)) {
            submitting = false;
            closeDialog(true);
            showHandledElsewhereNote();
            return;
        }

        var choiceId = dialogChoiceQuestion.id;
        var suggestionQuestion = dialogSuggestionQuestion;
        var suggestionId = suggestionQuestion ? suggestionQuestion.id : null;
        var surveyId = dialogSurveyId;
        var snapshot = layoutSnapshot;
        var generation = currentRuntimeGeneration();

        function sendCapture() {
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
        }

        // serverBacked：提交前强制 GET 最新服务端状态（跨设备 preflight）。
        // refresh 结果按明确契约分类：
        // - FRESH：重新读取当前 effective state；已 submitted → 取消本次 capture、关闭
        //   弹窗、显示「已在其他页面处理」、不发送 dismissed；never / snoozed 仍允许提交；
        // - UNAVAILABLE：按明确产品策略 fail-open（网络暂时不可用时允许提交），但
        //   capture 前重新读取一次本地 effective / localStorage 状态，本地已 submitted
        //   时仍然阻止；记录不含 token / Survey ID / 身份 / 用户输入的
        //   安全 warning；不向用户显示网络错误；
        // - INVALID：协议 / 身份 / 快照一致性异常，fail-closed——不发送 survey sent /
        //   dismissed、不关闭弹窗、保留布局选择 / 建议 / 字数 / 焦点、恢复控件、
        //   显示可重试错误 error-state-verification（不显示技术原因 / 身份值 / token）；
        // - CANCELLED：generation 已失效直接安全结束；generation 仍活动但 operation
        //   被取代时不继续 capture，恢复控件并显示同一可重试错误。
        // 这是弱去重：两台设备完全同时通过 preflight 并 capture 仍可能产生重复事件，
        // 不引入账号绑定、IP 或浏览器指纹。
        var preflight = Promise.resolve({status: REFRESH_FRESH, viewResult: VIEW_SAME});
        if (serverBacked) {
            preflight = refreshServerContext(generation);
        }
        preflight.then(function (result) {
            if (!isRuntimeGenerationActive(generation)) return;
            result = result || {status: REFRESH_UNAVAILABLE, reason: 'unknown'};
            if (result.status === REFRESH_CANCELLED) {
                // 当前 generation 仍活动但 operation 被取代：不继续 capture，
                // 恢复控件并显示同一可重试错误（不得静默继续）。
                submitting = false;
                setSubmittingState(false);
                lastErrorKey = 'error-state-verification';
                showError('error-state-verification');
                return;
            }
            if (result.status === REFRESH_INVALID) {
                // 协议 / 身份 / 快照异常：fail-closed。
                submitting = false;
                setSubmittingState(false);
                lastErrorKey = 'error-state-verification';
                showError('error-state-verification');
                return;
            }
            if (result.status === REFRESH_UNAVAILABLE) {
                // 暂时不可用：按明确产品策略 fail-open，但提交前重新读取一次本地
                // effective / localStorage 状态；本地已 submitted 时仍然阻止。
                if (hasSubmittedLocalDecision()) {
                    submitting = false;
                    closeDialog(true);
                    showHandledElsewhereNote();
                    return;
                }
                warn('layout survey: preflight state refresh unavailable; proceeding with local decision only');
                return sendCapture();
            }
            // REFRESH_FRESH：只以当前 effective submitted 去重（STALE 同样基于当前
            // 更高 revision 的权威状态，不会因迟到低 revision 响应放宽或收紧门禁）。
            var state = readState();
            if (isSubmittedDecision(state)) {
                submitting = false;
                closeDialog(true);
                showHandledElsewhereNote();
                return;
            }
            return sendCapture();
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

    function findSurveyById(surveys) {
        if (!Array.isArray(surveys)) return null;
        return surveys.find(function (survey) {
            return survey && typeof survey === 'object' && survey.id === config.surveyId;
        }) || null;
    }

    function fetchPublishedSurvey(sdk, generation) {
        return new Promise(function (resolve) {
            var settled = false;
            var timer = setTimeoutSafe(function () { finish('unavailable'); }, SURVEY_TOTAL_TIMEOUT_MS);
            function finish(status, survey) {
                if (settled) return;
                settled = true;
                clearTimerSafe(timer);
                if (pendingSurveyCancel === cancel) pendingSurveyCancel = null;
                resolve({status: status, survey: survey || null});
            }
            function cancel() {
                finish('cancelled');
            }
            pendingSurveyCancel = cancel;
            if (!isRuntimeGenerationActive(generation) || typeof sdk.getSurveys !== 'function') {
                finish('unavailable');
                return;
            }
            try {
                sdk.getSurveys(function (surveys, context) {
                    if (!isRuntimeGenerationActive(generation)) {
                        finish('cancelled');
                        return;
                    }
                    if (context && context.isLoaded === false) {
                        finish('unavailable');
                        return;
                    }
                    var survey = findSurveyById(surveys);
                    finish(survey && survey.start_date && !survey.end_date ? 'available' : 'removed', survey);
                }, true);
            } catch (_) {
                finish('unavailable');
            }
        });
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
     *   skipStateGate 保留调试绕过语义；嵌入入口只阻断 submitted）/ DNT / config；
     * - solo scoped 身份存在时验证 get_distinct_id() 一致，不一致 fail closed；
     * - 自动流程（首次下载完成触发）在服务端 status = submitted / never / 有效
     *   snooze 时不加载 SDK、不请求 Survey、不显示；
     * - serverBacked 且 serverStatus=snoozed、canShow=false 时，本地截止时间
     *   （serverLocalBlockUntil / localStorage 本地 snooze）到期后允许重新 GET 服务端
     *   权威状态：snooze 是否到期由服务端判断，浏览器只负责在本地截止时间过后重新询问；
     * - 内部返回明确结构化结果 {status: 'opened' | 'started' | 'blocked' | 'invalid'
     *   | 'cancelled' | 'no-survey', survey, retryAt}：blocked 携带服务端本地截止时间
     *   （retryAt），invalid 表示协议 / 身份失败，cancelled 表示 generation 失效 /
     *   destroy / 流程互斥。
     */
    function showSurveyFlow(options) {
        options = options || {};
        if (!initialized || flowRunning || dialogOpen) {
            return Promise.resolve(flowResult('cancelled'));
        }
        var generation = currentRuntimeGeneration();
        flowRunning = true;
        function flowResult(status, survey, retryAt) {
            return {
                status: status,
                survey: survey || null,
                retryAt: typeof retryAt === 'number' && isFinite(retryAt) ? retryAt : 0
            };
        }
        function sdkStep(sdk) {
            return {kind: 'sdk', sdk: sdk};
        }
        function finishFlow(result) {
            if (isRuntimeGenerationActive(generation)) flowRunning = false;
            return result;
        }
        function proceedToSdk() {
            if (!isRuntimeGenerationActive(generation)) return sdkStep(null);
            return resolveSdk(generation).then(function (sdk) {
                return sdkStep(sdk);
            });
        }
        return loadServerContext(generation).then(function () {
            if (!isRuntimeGenerationActive(generation)) return flowResult('cancelled');
            if (!config || !config.enabled) return flowResult('cancelled');
            if (options.skipStateGate) return proceedToSdk();
            if (options.ignoreReminderGate) {
                return isSubmittedDecision(readStateFresh())
                    ? flowResult('blocked')
                    : proceedToSdk();
            }
            if (!stateAllowsShow(timers.now())) {
                // 服务端 snooze 未到期（canShow=false）：不加载 SDK、不请求 Survey，
                // 只在自己的本地截止时间（serverLocalBlockUntil）对齐安排自动检查。
                if (serverBacked && serverStatus === 'snoozed' && !serverCanShow
                        && serverLocalBlockUntil > timers.now()) {
                    return flowResult('blocked', null, serverLocalBlockUntil);
                }
                return flowResult('blocked');
            }
            // 本地截止时间已到但服务端视图仍是旧 snoozed / canShow=false：
            // 强制重新 GET 权威状态（服务端独立判断到期），再按新视图判断门禁。
            if (serverBacked && serverStatus === 'snoozed' && !serverCanShow) {
                return refreshServerContext(generation).then(function (result) {
                    if (!isRuntimeGenerationActive(generation)) return flowResult('cancelled');
                    if (result.status === REFRESH_INVALID) return flowResult('invalid');
                    if (result.status === REFRESH_CANCELLED) return flowResult('cancelled');
                    if (result.status === REFRESH_UNAVAILABLE) {
                        // 服务端暂时不可用：本地无阻断状态时按现有 availability 策略
                        // 继续（明确 fail-open，不解释任何服务端绝对时间点）。
                        if (!stateAllowsShow(timers.now())) return flowResult('blocked');
                        return proceedToSdk();
                    }
                    if (!stateAllowsShow(timers.now())) {
                        // 服务端延长 / 缩短 snooze：按最新 serverLocalBlockUntil 阻断。
                        if (serverBacked && serverStatus === 'snoozed' && !serverCanShow
                                && serverLocalBlockUntil > timers.now()) {
                            return flowResult('blocked', null, serverLocalBlockUntil);
                        }
                        return flowResult('blocked');
                    }
                    return proceedToSdk();
                });
            }
            return proceedToSdk();
        }).then(function (step) {
            if (!step || step.kind !== 'sdk') {
                // 结构化结果（blocked / invalid / cancelled）直接透传。
                return step || flowResult('cancelled');
            }
            if (!isRuntimeGenerationActive(generation)) return flowResult('cancelled');
            var sdk = step.sdk;
            if (!sdk) return flowResult(options.verifyPublication ? 'unavailable' : 'started');
            if (serverIdentityAvailable && serverDistinctId) {
                if (!verifySdkDistinctId(sdk)) {
                    return flowResult(options.verifyPublication ? 'unavailable' : 'started');
                }
            }
            // DNT / opt-out：不请求 Survey、不显示、不发 shown、不写状态、不报错。
            if (isCapturingDisabled(sdk)) {
                return flowResult(options.verifyPublication ? 'ineligible' : 'started');
            }
            var publication = options.verifyPublication
                ? fetchPublishedSurvey(sdk, generation)
                : Promise.resolve({status: 'available', survey: null});
            return publication.then(function (published) {
                if (!isRuntimeGenerationActive(generation) || published.status === 'cancelled') {
                    return flowResult('cancelled');
                }
                if (published.status !== 'available') return flowResult(published.status);
                return fetchMatchingSurvey(sdk, generation).then(function (survey) {
                    if (!isRuntimeGenerationActive(generation)) return flowResult('cancelled');
                    if (!survey) return flowResult(options.verifyPublication ? 'ineligible' : 'no-survey');
                    var choiceQuestion = resolveChoiceQuestion(survey);
                    if (!choiceQuestion) {
                        warn('layout survey: layout choice question schema invalid; survey hidden');
                        return flowResult(options.verifyPublication ? 'ineligible' : 'no-survey');
                    }
                    var suggestionQuestion = resolveSuggestionQuestion(survey);
                    if (!openDialog(survey, choiceQuestion, suggestionQuestion)) {
                        return flowResult('no-survey');
                    }
                    sendShown();
                    return flowResult('opened', survey);
                });
            });
        }).then(finishFlow, function () {
            return finishFlow(flowResult('cancelled'));
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
        // 仅记录布局体验（服务端 seen 统计）；不再参与展示门禁。
        recordSeen(layoutId, timers.now());
    }

    function refreshStateFromStorage() {
        readStateFresh();
        if (!dialogOpen || !dialogSurveyId) return;
        var state = readState();
        if (!state) return;
        var handledElsewhere = isSubmittedDecision(state);
        if (!handledElsewhere) return;
        // 另一标签页已处理该调查：关闭当前弹窗；不重复发送 dismissed；
        // 不改写另一标签页的状态；不显示提交失败；只显示非阻塞提示。
        closeDialog(true);
        showHandledElsewhereNote();
    }

    /**
     * 严格解析 storage 事件中的 STATE_KEY 新值：只接受当前 Survey ID 的合法
     * submitted / never / 未过期 snoozed（有限整数时间戳、snoozedUntil >= 0）；
     * 非法状态返回 null（不合并、不当作权威事实）。
     */
    function parseLocalStateValue(newValue) {
        if (typeof newValue !== 'string' || !newValue) return null;
        var parsed = null;
        try {
            parsed = JSON.parse(newValue);
        } catch (_) {
            return null;
        }
        if (!isPlainObject(parsed)) return null;
        if (parsed.surveyId !== config.surveyId) return null;
        if (typeof parsed.status !== 'string'
                || (parsed.status !== 'submitted' && parsed.status !== 'never'
                    && parsed.status !== 'snoozed')) {
            return null;
        }
        if (!isFiniteInteger(parsed.updatedAt) || parsed.updatedAt < 0) return null;
        if (!isFiniteInteger(parsed.snoozedUntil) || parsed.snoozedUntil < 0) return null;
        return parsed;
    }

    /**
     * serverBacked 模式下收到 STATE_KEY storage 事件（同一浏览器另一标签页的本地协调
     * 缓存写入）：先把合法 fallback 合并进 pendingLocalState，再关闭弹窗 / 同步本地
     * 协调缓存，最后发起一次有限服务端刷新确认权威状态（不无限请求）。
     * 服务器 refresh 返回旧空状态时不得删除 pendingLocalState / 本地缓存，调查不得
     * 重新展示；event.newValue == null 不清除 pendingLocalState，只触发有限刷新。
     */
    function handleServerBackedStateEvent(event) {
        var now = timers.now();
        var incoming = parseLocalStateValue(event.newValue);
        if (incoming) {
            var normalized = normalizeDecisionState(incoming, now);
            if (normalized) {
                // 合法状态先与 pendingLocalState 合并：另一个标签页刚写入的 fallback
                // 必须被当前标签页接纳，否则后续 sync 可能把它删除。
                pendingLocalState = strongerDecisionState(pendingLocalState, normalized, now);
            }
        }
        // 立即同步：确保另一个标签页刚写入的 fallback 不会被当前标签页删除。
        syncServerViewToLocalCache();
        var effective = effectiveState();
        if (isSubmittedDecision(effective)) {
            if (dialogOpen) {
                closeDialog(true);
                showHandledElsewhereNote();
            }
        }
        var generation = currentRuntimeGeneration();
        if (isRuntimeGenerationActive(generation)) refreshServerContext(generation);
    }

    /**
     * 严格解析 storage 事件中的 SEEN_KEY 新值：只接受三个稳定布局 ID；每个 entry 的
     * firstSeenAt / lastSeenAt 必须是有限整数且 >= 0、lastSeenAt >= firstSeenAt；
     * 非法 entry 忽略。
     */
    function parseLocalSeenValue(newValue) {
        if (typeof newValue !== 'string' || !newValue) return null;
        var parsed = null;
        try {
            parsed = JSON.parse(newValue);
        } catch (_) {
            return null;
        }
        if (!isPlainObject(parsed)) return null;
        var seen = {};
        Object.keys(parsed).forEach(function (key) {
            var entry = parsed[key];
            if (LAYOUT_IDS.indexOf(key) < 0 || !isPlainObject(entry)
                    || !isFiniteInteger(entry.firstSeenAt) || !isFiniteInteger(entry.lastSeenAt)
                    || entry.firstSeenAt < 0 || entry.lastSeenAt < 0
                    || entry.lastSeenAt < entry.firstSeenAt) {
                return;
            }
            seen[key] = {firstSeenAt: entry.firstSeenAt, lastSeenAt: entry.lastSeenAt};
        });
        return Object.keys(seen).length ? seen : null;
    }

    /**
     * serverBacked 模式下收到 SEEN_KEY storage 事件：合法新值先合并进 pendingLocalSeen
     * （firstSeenAt 取较早、lastSeenAt 取较晚），再同步本地协调缓存、按 effectiveSeen
     * 重新判断自动展示阈值，最后发起一次有限服务端刷新。服务器返回旧 seen 时不得清除
     * pendingLocalSeen / localStorage，seenCount 不得下降。
     */
    function handleServerBackedSeenEvent(event) {
        var incoming = parseLocalSeenValue(event.newValue);
        if (incoming) {
            Object.keys(incoming).forEach(function (id) {
                var entry = incoming[id];
                var pending = pendingLocalSeen[id] || {};
                var firstSeenAt = typeof pending.firstSeenAt === 'number'
                    ? pending.firstSeenAt : null;
                var lastSeenAt = typeof pending.lastSeenAt === 'number'
                    ? pending.lastSeenAt : null;
                pendingLocalSeen[id] = {
                    firstSeenAt: firstSeenAt === null
                        ? entry.firstSeenAt
                        : Math.min(firstSeenAt, entry.firstSeenAt),
                    lastSeenAt: lastSeenAt === null
                        ? entry.lastSeenAt
                        : Math.max(lastSeenAt, entry.lastSeenAt)
                };
            });
        }
        syncServerViewToLocalCache();
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
                handleServerBackedSeenEvent(event);
                return;
            }
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
            try {
                global.document.addEventListener('pixiv:first-download-completed', onFirstDownloadCompleted);
            } catch (_) {
                // 首次下载完成事件不可用时静默降级（调查不弹出）
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
                global.document.removeEventListener('pixiv:first-download-completed', onFirstDownloadCompleted);
            } catch (_) {
                // 清理尽力而为
            }
        }
    }

    /**
     * 首次下载完成触发（仅新版工作台）。
     * - alt 下载引擎在本页面会话第一个下载项完成时派发
     *   pixiv:first-download-completed，本模块据此评估并展示调查；
     * - 每页面会话只触发一次（firstDownloadTriggered，destroy 时重置）；
     * - 门禁：enabled 配置、仅 alt 页面、状态允许（submitted / never / 未到期
     *   snoozed 不展示）、无阻塞弹窗；阻塞弹窗存在时不消耗触发机会，后续事件
     *   可再次评估；
     * - 状态门禁、服务端 snooze 到期重新 GET 与 SDK / Survey 流程全部在
     *   showSurveyFlow 内部完成；任何异常不得中断下载功能。
     */
    function onFirstDownloadCompleted() {
        if (!initialized || pageType !== 'alt') return;
        if (firstDownloadTriggered) return;
        if (!config || !config.enabled) return;
        if (!stateAllowsShow(timers.now())) return;
        if (hasBlockingOverlay()) return;
        firstDownloadTriggered = true;
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
        configuredLayoutId = LAYOUT_IDS.indexOf(options.currentLayoutId) >= 0
            ? options.currentLayoutId : null;
        storage = options.storage != null ? options.storage : safeLocalStorage();
        timers = options.timers || defaultTimers();
        fetchImpl = options.fetchImpl || defaultFetch();
        i18nClient = options.i18n || null;
        if (global.PixivLayoutFeedbackOfficialRelease !== true) return;
        config = readSurveyConfig();
        if (!config) {
            warn('layout survey: posthog plugin unavailable; survey disabled');
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
            //（reconciliation，只一次；触发时读取服务端状态，去重语义不变）。
            var initGeneration = runtimeGeneration;
            loadServerContext(initGeneration).then(function () {
                if (!isRuntimeGenerationActive(initGeneration)) return;
                if (!serverBacked) return;
                recordSeen(currentLayoutId(), timers.now());
            });
        }
        // 展示时机完全由 pixiv:first-download-completed 事件驱动（仅 alt 页面），
        // init 不安排任何定时器。
    }

    /**
     * 手动打开展示流程（调试 / 自动化测试入口）。不做展示门禁
     * （本地状态 / 阻塞弹窗），但受 enabled 配置约束，且身份加载
     * 不能绕过：showSurveyFlow 内部始终先等待服务端身份上下文。
     * 不受首次下载完成触发的一次性标记限制。
     * 未 init（含 destroy 后尚未重新 init）时是安全的 no-op：
     * 返回 resolved null，不加载 SDK、不设置 flowRunning、不插 script、
     * 不注册 listener、不请求 Survey、不打开弹窗。
     * 返回 Survey 或 null，不暴露内部状态机对象。
     */
    function open() {
        if (!initialized || !config || !config.enabled) {
            return Promise.resolve(null);
        }
        return showSurveyFlow({skipStateGate: true}).then(function (result) {
            return result && result.survey ? result.survey : null;
        });
    }

    function openEmbedded() {
        if (!initialized || !config || !config.enabled) {
            return Promise.resolve({status: 'unavailable', survey: null, retryAt: 0});
        }
        return showSurveyFlow({ignoreReminderGate: true, verifyPublication: true});
    }

    /**
     * 预加载（点击「开始下载」后异步预热，不弹窗、不发事件、不写状态）：
     * 提前装载服务端状态，并在本地状态当前允许展示时创建 PostHog 命名客户端
     * （flags 请求随之提前），使首次下载完成事件到达时展示流程
     * 只剩 Survey 获取与弹窗，消除弹窗出现前的空白等待。
     * - 与展示流程共享按 generation 缓存的 operation：showSurveyFlow 直接复用，
     *   不会重复加载脚本或重复 GET；
     * - 展示门禁语义不变：事件到达时仍由 showSurveyFlow 按最新状态重新评估，
     *   预加载自身不跳过任何门禁、不展示；
     * - 本地状态已阻断（submitted / never / 未到期 snoozed）时不初始化 SDK，
     *   已决定用户仍不产生任何对 PostHog 的请求（脚本与服务端状态均为本地资源）；
     * - 任何异常静默吞掉（不影响下载功能），Promise 必然 resolve。
     */
    function preload() {
        if (!initialized || !config || !config.enabled) return Promise.resolve();
        var generation = currentRuntimeGeneration();
        return loadServerContext(generation).then(function () {
            if (!isRuntimeGenerationActive(generation)) return;
            if (!stateAllowsShow(timers.now())) return;
            return resolveSdk(generation).then(function (sdk) {
                if (!isRuntimeGenerationActive(generation) || !sdk) return;
                if (serverIdentityAvailable && serverDistinctId
                        && !verifySdkDistinctId(sdk)) return;
            });
        }).catch(function () {
            // 预加载任何失败都不影响下载与正式展示流程
        });
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
                serverRefreshOperation.cancel('destroy');
            } catch (_) {
                // 取消尽力而为
            }
            serverRefreshOperation = null;
        }
        // 取消全部可取消的 server command operation（每个 operation 的 cancel() 幂等：
        // aborted=true、清 timeout、abort 在途请求、结束 Promise、从 Set 删除自身；
        // 迟到响应经 settled / aborted / attempt token 守卫失效）。
        Array.from(serverCommandOperations).forEach(function (operation) {
            if (operation && typeof operation.cancel === 'function') {
                try { operation.cancel(); } catch (_) {
                    // 取消尽力而为
                }
            }
        });
        serverCommandOperations.clear();
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
        configuredLayoutId = null;
        // 使当前 generation 失效：后续（destroy 后重新 init 之前的）异步
        // continuation 全部被 isRuntimeGenerationActive 拦截。
        runtimeGeneration += 1;
        firstDownloadTriggered = false;
        flowRunning = false;
        submitting = false;
        sessionState = null;
        sessionSeen = {};
        appVersionPromise = null;
        // 服务端视图全部清空：旧 generation 的 GET / POST / storage 消息不得影响新
        // generation；destroy 后重新 init 必须重新探测 server context，不复用旧视图。
        serverIdentityAvailable = false;
        serverBacked = false;
        serverDistinctId = null;
        serverRevision = 0;
        serverStateAvailable = false;
        serverSnapshotInitialized = false;
        serverStatus = null;
        serverCanShow = true;
        serverRetryAfterMs = 0;
        serverSeenLayouts = [];
        serverLocalBlockUntil = 0;
        pendingLocalState = null;
        pendingLocalSeen = {};
        serverLoadOperation = null;
        serverRefreshOperation = null;
        serverRefreshInFlight = null;
        serverCommandOperations.clear();
        pendingSeenLayouts = {};
        serverSaveTimerId = null;
        reconciled = false;
        // PostHog 插件拥有已加载的 vendor script 与命名客户端；destroy 不调用 reset() /
        // opt-out 方法、不改变匿名 distinct ID、不清除 PostHog 本地
        // 持久化；已加载的命名客户端只随本模块不再被驱动发送调查事件。
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
        openEmbedded: openEmbedded,
        preload: preload,
        destroy: destroy,
        currentLayoutId: currentLayoutId,
        refreshLanguage: refreshLanguage,
        _internals: Object.freeze({
            LAYOUT_IDS: Object.freeze(LAYOUT_IDS.slice()),
            STATE_KEY: STATE_KEY,
            SEEN_KEY: SEEN_KEY,
            SERVER_STATE_URL: SERVER_STATE_URL,
            SERVER_STATE_TIMEOUT_MS: SERVER_STATE_TIMEOUT_MS,
            SERVER_COMMAND_TIMEOUT_MS: SERVER_COMMAND_TIMEOUT_MS,
            SERVER_SAVE_DEBOUNCE_MS: SERVER_SAVE_DEBOUNCE_MS,
            SNOOZE_MS: SNOOZE_MS,
            SUGGESTION_MAX_CODE_POINTS: SUGGESTION_MAX_CODE_POINTS,
            SURVEY_SCHEMA_VERSION: SURVEY_SCHEMA_VERSION,
            FLAGS_TIMEOUT_MS: FLAGS_TIMEOUT_MS,
            SURVEY_TOTAL_TIMEOUT_MS: SURVEY_TOTAL_TIMEOUT_MS,
            POSTHOG_OWNER_KEY: POSTHOG_OWNER_KEY,
            POSTHOG: POSTHOG,
            VIEW_APPLIED: VIEW_APPLIED,
            VIEW_SAME: VIEW_SAME,
            VIEW_UPDATED: VIEW_UPDATED,
            VIEW_STALE: VIEW_STALE,
            VIEW_INVALID: VIEW_INVALID,
            REFRESH_FRESH: REFRESH_FRESH,
            REFRESH_UNAVAILABLE: REFRESH_UNAVAILABLE,
            REFRESH_INVALID: REFRESH_INVALID,
            REFRESH_CANCELLED: REFRESH_CANCELLED,
            codePointLength: codePointLength,
            mapLayoutToken: mapLayoutToken,
            resolveChoiceQuestion: resolveChoiceQuestion,
            resolveSuggestionQuestion: resolveSuggestionQuestion,
            beforeSendFilter: beforeSendFilter,
            isDateObject: isDateObject,
            isAcceptedCaptureResult: isAcceptedCaptureResult,
            distinctSeenCount: distinctSeenCount,
            clientWallNow: clientWallNow,
            safeClientTimeAdd: safeClientTimeAdd,
            effectiveState: effectiveState,
            effectiveStateRecord: effectiveStateRecord,
            effectiveSeen: effectiveSeen,
            syncServerViewToLocalCache: syncServerViewToLocalCache,
            prunePendingAfterView: prunePendingAfterView,
            compareDecisionState: compareDecisionState,
            normalizeDecisionState: normalizeDecisionState,
            strongerDecisionState: strongerDecisionState,
            isDecisionAtLeastAsStrong: isDecisionAtLeastAsStrong,
            remainingSnoozeMs: remainingSnoozeMs,
            isOperationActive: isOperationActive,
            refreshServerContext: refreshServerContext,
            writeState: writeState,
            setStorageIfChanged: setStorageIfChanged,
            removeStorageIfPresent: removeStorageIfPresent,
            isBlockingDecision: isBlockingDecision,
            hasSubmittedLocalDecision: hasSubmittedLocalDecision,
            serverViewToLocalState: serverViewToLocalState,
            serverViewAsState: serverViewAsState,
            serverCommandOperations: serverCommandOperations,
            currentServerRevision: function () { return serverRevision; },
            currentGeneration: function () { return runtimeGeneration; },
            isServerSnapshotInitialized: function () { return serverSnapshotInitialized; },
            serverStatus: function () { return serverStatus; },
            serverCanShow: function () { return serverCanShow; },
            serverRetryAfterMs: function () { return serverRetryAfterMs; },
            serverSeenLayouts: function () { return serverSeenLayouts.slice(); },
            serverStateAvailable: function () { return serverStateAvailable; },
            serverLocalBlockUntil: function () { return serverLocalBlockUntil; },
            serverBacked: function () { return serverBacked; },
            // 首次下载完成触发的一次性标记（只读观测）。
            firstDownloadTriggered: function () { return firstDownloadTriggered; },
            pendingTimerCount: function () { return pendingTimers.length; }
        })
    });
})(window);
