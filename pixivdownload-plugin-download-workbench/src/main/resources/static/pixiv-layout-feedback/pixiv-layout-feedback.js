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
 *   3. 本目录下的 core / server / state / survey / dialog 模块
 *   4. 本文件（生命周期与公共门面）
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

    var modules = global.PixivLayoutFeedbackModules;
    var ctx = {};
    ['core', 'server', 'state', 'survey', 'dialog'].forEach(function (name) {
        var module = modules && modules[name];
        if (!module || typeof module.install !== 'function') {
            throw new Error('Pixiv layout feedback module is missing: ' + name);
        }
        module.install(ctx);
    });
    var runtime = ctx.runtime;

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
        var layoutId = ctx.mapLayoutToken(detail.layout);
        if (!layoutId) return;
        // 仅记录布局体验（服务端 seen 统计）；不再参与展示门禁。
        ctx.recordSeen(layoutId, runtime.timers.now());
    }

    function refreshStateFromStorage() {
        ctx.readStateFresh();
        if (!runtime.dialogOpen || !runtime.dialogSurveyId) return;
        var state = ctx.readState();
        if (!state) return;
        var handledElsewhere = ctx.isSubmittedDecision(state);
        if (!handledElsewhere) return;
        // 另一标签页已处理该调查：关闭当前弹窗；不重复发送 dismissed；
        // 不改写另一标签页的状态；不显示提交失败；只显示非阻塞提示。
        ctx.closeDialog(true);
        ctx.showHandledElsewhereNote();
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
        if (!ctx.isPlainObject(parsed)) return null;
        if (parsed.surveyId !== runtime.config.surveyId) return null;
        if (typeof parsed.status !== 'string'
                || (parsed.status !== 'submitted' && parsed.status !== 'never'
                    && parsed.status !== 'snoozed')) {
            return null;
        }
        if (!ctx.isFiniteInteger(parsed.updatedAt) || parsed.updatedAt < 0) return null;
        if (!ctx.isFiniteInteger(parsed.snoozedUntil) || parsed.snoozedUntil < 0) return null;
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
        var now = runtime.timers.now();
        var incoming = parseLocalStateValue(event.newValue);
        if (incoming) {
            var normalized = ctx.normalizeDecisionState(incoming, now);
            if (normalized) {
                // 合法状态先与 pendingLocalState 合并：另一个标签页刚写入的 fallback
                // 必须被当前标签页接纳，否则后续 sync 可能把它删除。
                runtime.pendingLocalState = ctx.strongerDecisionState(runtime.pendingLocalState, normalized, now);
            }
        }
        // 立即同步：确保另一个标签页刚写入的 fallback 不会被当前标签页删除。
        ctx.syncServerViewToLocalCache();
        var effective = ctx.effectiveState();
        if (ctx.isSubmittedDecision(effective)) {
            if (runtime.dialogOpen) {
                ctx.closeDialog(true);
                ctx.showHandledElsewhereNote();
            }
        }
        var generation = ctx.currentRuntimeGeneration();
        if (ctx.isRuntimeGenerationActive(generation)) ctx.refreshServerContext(generation);
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
        if (!ctx.isPlainObject(parsed)) return null;
        var seen = {};
        Object.keys(parsed).forEach(function (key) {
            var entry = parsed[key];
            if (ctx.LAYOUT_IDS.indexOf(key) < 0 || !ctx.isPlainObject(entry)
                    || !ctx.isFiniteInteger(entry.firstSeenAt) || !ctx.isFiniteInteger(entry.lastSeenAt)
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
                var pending = runtime.pendingLocalSeen[id] || {};
                var firstSeenAt = typeof pending.firstSeenAt === 'number'
                    ? pending.firstSeenAt : null;
                var lastSeenAt = typeof pending.lastSeenAt === 'number'
                    ? pending.lastSeenAt : null;
                runtime.pendingLocalSeen[id] = {
                    firstSeenAt: firstSeenAt === null
                        ? entry.firstSeenAt
                        : Math.min(firstSeenAt, entry.firstSeenAt),
                    lastSeenAt: lastSeenAt === null
                        ? entry.lastSeenAt
                        : Math.max(lastSeenAt, entry.lastSeenAt)
                };
            });
        }
        ctx.syncServerViewToLocalCache();
        var generation = ctx.currentRuntimeGeneration();
        if (ctx.isRuntimeGenerationActive(generation)) ctx.refreshServerContext(generation);
    }

    function onStorageEvent(event) {
        if (!event) return;
        if (event.key === ctx.STATE_KEY) {
            if (runtime.serverBacked) {
                handleServerBackedStateEvent(event);
                return;
            }
            refreshStateFromStorage();
        } else if (event.key === ctx.SEEN_KEY) {
            if (runtime.serverBacked) {
                handleServerBackedSeenEvent(event);
                return;
            }
            runtime.sessionSeen = {};
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
        if (!runtime.initialized || runtime.pageType !== 'alt') return;
        if (runtime.firstDownloadTriggered) return;
        if (!runtime.config || !runtime.config.enabled) return;
        if (!ctx.stateAllowsShow(runtime.timers.now())) return;
        if (ctx.hasBlockingOverlay()) return;
        runtime.firstDownloadTriggered = true;
        ctx.showSurveyFlow();
    }

    /* ============================================================
       公共 API
    ============================================================ */

    function init(options) {
        if (runtime.initialized) return;
        runtime.initialized = true;
        // 从未初始化状态进入 init 时生成新 generation（destroy 后重新 init
        // 必然得到不同的 generation，旧异步回调不会影响新 generation）。
        runtime.runtimeGeneration = ctx.nextRuntimeGeneration();
        options = options || {};
        runtime.pageType = options.page || detectPageType();
        runtime.configuredLayoutId = ctx.LAYOUT_IDS.indexOf(options.currentLayoutId) >= 0
            ? options.currentLayoutId : null;
        runtime.storage = options.storage != null ? options.storage : ctx.safeLocalStorage();
        runtime.timers = options.timers || ctx.defaultTimers();
        runtime.fetchImpl = options.fetchImpl || ctx.defaultFetch();
        runtime.i18nClient = options.i18n || null;
        if (global.PixivLayoutFeedbackOfficialRelease !== true) return;
        runtime.config = ctx.readSurveyConfig();
        if (!runtime.config) {
            ctx.warn('layout survey: posthog plugin unavailable; survey disabled');
            return;
        }
        if (typeof global.addEventListener !== 'function') {
            // 无事件环境（如纯脚本执行）不注册监听，仅保留手动 open
        }
        registerListeners();
        var now = runtime.timers.now();
        ctx.recordSeen(ctx.currentLayoutId(), now);
        ctx.loadAppVersion();
        // enabled=false：不请求服务端状态、不加载 SDK（调查整体关闭）。
        if (runtime.config.enabled) {
            // solo 模式服务端上下文异步接管：localStorage 模式下的首屏写入不会回放，
            // 服务端接管后把当前布局补录到服务端 seen，并执行有限本地状态回放
            //（reconciliation，只一次；触发时读取服务端状态，去重语义不变）。
            var initGeneration = runtime.runtimeGeneration;
            ctx.loadServerContext(initGeneration).then(function () {
                if (!ctx.isRuntimeGenerationActive(initGeneration)) return;
                if (!runtime.serverBacked) return;
                ctx.recordSeen(ctx.currentLayoutId(), runtime.timers.now());
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
        if (!runtime.initialized || !runtime.config || !runtime.config.enabled) {
            return Promise.resolve(null);
        }
        return ctx.showSurveyFlow({skipStateGate: true}).then(function (result) {
            return result && result.survey ? result.survey : null;
        });
    }

    function openEmbedded() {
        if (!runtime.initialized || !runtime.config || !runtime.config.enabled) {
            return Promise.resolve({status: 'unavailable', survey: null, retryAt: 0});
        }
        return ctx.showSurveyFlow({ignoreReminderGate: true, verifyPublication: true});
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
        if (!runtime.initialized || !runtime.config || !runtime.config.enabled) return Promise.resolve();
        var generation = ctx.currentRuntimeGeneration();
        return ctx.loadServerContext(generation).then(function () {
            if (!ctx.isRuntimeGenerationActive(generation)) return;
            if (!ctx.stateAllowsShow(runtime.timers.now())) return;
            return ctx.resolveSdk(generation).then(function (sdk) {
                if (!ctx.isRuntimeGenerationActive(generation) || !sdk) return;
                if (runtime.serverIdentityAvailable && runtime.serverDistinctId
                        && !ctx.verifySdkDistinctId(sdk)) return;
            });
        }).catch(function () {
            // 预加载任何失败都不影响下载与正式展示流程
        });
    }

    function destroy() {
        if (runtime.dialogOpen) ctx.closeDialog(false);
        if (typeof runtime.pendingSurveyCancel === 'function') {
            try {
                runtime.pendingSurveyCancel();
            } catch (_) {
                // 取消尽力而为
            }
            runtime.pendingSurveyCancel = null;
        }
        if (runtime.sdkLoadOperation && typeof runtime.sdkLoadOperation.cancel === 'function') {
            try {
                runtime.sdkLoadOperation.cancel();
            } catch (_) {
                // 取消尽力而为
            }
            runtime.sdkLoadOperation = null;
        }
        if (runtime.serverLoadOperation && typeof runtime.serverLoadOperation.cancel === 'function') {
            try {
                runtime.serverLoadOperation.cancel();
            } catch (_) {
                // 取消尽力而为
            }
            runtime.serverLoadOperation = null;
        }
        if (runtime.serverRefreshOperation && typeof runtime.serverRefreshOperation.cancel === 'function') {
            try {
                runtime.serverRefreshOperation.cancel('destroy');
            } catch (_) {
                // 取消尽力而为
            }
            runtime.serverRefreshOperation = null;
        }
        // 取消全部可取消的 server command operation（每个 operation 的 cancel() 幂等：
        // aborted=true、清 timeout、abort 在途请求、结束 Promise、从 Set 删除自身；
        // 迟到响应经 settled / aborted / attempt token 守卫失效）。
        Array.from(runtime.serverCommandOperations).forEach(function (operation) {
            if (operation && typeof operation.cancel === 'function') {
                try { operation.cancel(); } catch (_) {
                    // 取消尽力而为
                }
            }
        });
        runtime.serverCommandOperations.clear();
        if (runtime.serverSaveTimerId != null) {
            ctx.clearTimerSafe(runtime.serverSaveTimerId);
            runtime.serverSaveTimerId = null;
        }
        removeListeners();
        runtime.pendingTimers.forEach(function (id) {
            try {
                runtime.timers.clearTimeout(id);
            } catch (_) {
                // 清理尽力而为
            }
        });
        runtime.pendingTimers = [];
        runtime.initialized = false;
        runtime.configuredLayoutId = null;
        // 使当前 generation 失效：后续（destroy 后重新 init 之前的）异步
        // continuation 全部被 isRuntimeGenerationActive 拦截。
        runtime.runtimeGeneration += 1;
        runtime.firstDownloadTriggered = false;
        runtime.flowRunning = false;
        runtime.submitting = false;
        runtime.sessionState = null;
        runtime.sessionSeen = {};
        runtime.appVersionPromise = null;
        // 服务端视图全部清空：旧 generation 的 GET / POST / storage 消息不得影响新
        // generation；destroy 后重新 init 必须重新探测 server context，不复用旧视图。
        runtime.serverIdentityAvailable = false;
        runtime.serverBacked = false;
        runtime.serverDistinctId = null;
        runtime.serverSubmissionId = null;
        runtime.serverRevision = 0;
        runtime.serverStateAvailable = false;
        runtime.serverSnapshotInitialized = false;
        runtime.serverStatus = null;
        runtime.serverCanShow = true;
        runtime.serverRetryAfterMs = 0;
        runtime.serverSeenLayouts = [];
        runtime.serverLocalBlockUntil = 0;
        runtime.pendingLocalState = null;
        runtime.pendingLocalSeen = {};
        runtime.serverLoadOperation = null;
        runtime.serverRefreshOperation = null;
        runtime.serverRefreshInFlight = null;
        runtime.serverCommandOperations.clear();
        runtime.pendingSeenLayouts = {};
        runtime.serverSaveTimerId = null;
        runtime.reconciled = false;
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
            runtime.i18nClient = client;
        }
        if (!runtime.dialogOpen) return;
        ctx.applyDialogTranslations();
    }

    global.PixivLayoutFeedback = Object.freeze({
        init: init,
        open: open,
        openEmbedded: openEmbedded,
        preload: preload,
        destroy: destroy,
        currentLayoutId: ctx.currentLayoutId,
        refreshLanguage: refreshLanguage,
        _internals: Object.freeze({
            LAYOUT_IDS: Object.freeze(ctx.LAYOUT_IDS.slice()),
            STATE_KEY: ctx.STATE_KEY,
            SEEN_KEY: ctx.SEEN_KEY,
            SERVER_STATE_URL: ctx.SERVER_STATE_URL,
            SERVER_STATE_TIMEOUT_MS: ctx.SERVER_STATE_TIMEOUT_MS,
            SERVER_COMMAND_TIMEOUT_MS: ctx.SERVER_COMMAND_TIMEOUT_MS,
            SERVER_SAVE_DEBOUNCE_MS: ctx.SERVER_SAVE_DEBOUNCE_MS,
            SNOOZE_MS: ctx.SNOOZE_MS,
            SUGGESTION_MAX_CODE_POINTS: ctx.SUGGESTION_MAX_CODE_POINTS,
            SURVEY_SCHEMA_VERSION: ctx.SURVEY_SCHEMA_VERSION,
            FLAGS_TIMEOUT_MS: ctx.FLAGS_TIMEOUT_MS,
            SURVEY_TOTAL_TIMEOUT_MS: ctx.SURVEY_TOTAL_TIMEOUT_MS,
            POSTHOG_OWNER_KEY: ctx.POSTHOG_OWNER_KEY,
            POSTHOG: ctx.POSTHOG,
            VIEW_APPLIED: ctx.VIEW_APPLIED,
            VIEW_SAME: ctx.VIEW_SAME,
            VIEW_UPDATED: ctx.VIEW_UPDATED,
            VIEW_STALE: ctx.VIEW_STALE,
            VIEW_INVALID: ctx.VIEW_INVALID,
            REFRESH_FRESH: ctx.REFRESH_FRESH,
            REFRESH_UNAVAILABLE: ctx.REFRESH_UNAVAILABLE,
            REFRESH_INVALID: ctx.REFRESH_INVALID,
            REFRESH_CANCELLED: ctx.REFRESH_CANCELLED,
            codePointLength: ctx.codePointLength,
            mapLayoutToken: ctx.mapLayoutToken,
            resolveChoiceQuestion: ctx.resolveChoiceQuestion,
            resolveSuggestionQuestion: ctx.resolveSuggestionQuestion,
            beforeSendFilter: ctx.beforeSendFilter,
            isDateObject: ctx.isDateObject,
            isAcceptedCaptureResult: ctx.isAcceptedCaptureResult,
            distinctSeenCount: ctx.distinctSeenCount,
            clientWallNow: ctx.clientWallNow,
            safeClientTimeAdd: ctx.safeClientTimeAdd,
            effectiveState: ctx.effectiveState,
            effectiveStateRecord: ctx.effectiveStateRecord,
            effectiveSeen: ctx.effectiveSeen,
            syncServerViewToLocalCache: ctx.syncServerViewToLocalCache,
            prunePendingAfterView: ctx.prunePendingAfterView,
            compareDecisionState: ctx.compareDecisionState,
            normalizeDecisionState: ctx.normalizeDecisionState,
            strongerDecisionState: ctx.strongerDecisionState,
            isDecisionAtLeastAsStrong: ctx.isDecisionAtLeastAsStrong,
            remainingSnoozeMs: ctx.remainingSnoozeMs,
            isOperationActive: ctx.isOperationActive,
            refreshServerContext: ctx.refreshServerContext,
            writeState: ctx.writeState,
            setStorageIfChanged: ctx.setStorageIfChanged,
            removeStorageIfPresent: ctx.removeStorageIfPresent,
            isBlockingDecision: ctx.isBlockingDecision,
            hasSubmittedLocalDecision: ctx.hasSubmittedLocalDecision,
            serverViewToLocalState: ctx.serverViewToLocalState,
            serverViewAsState: ctx.serverViewAsState,
            serverCommandOperations: runtime.serverCommandOperations,
            currentServerRevision: function () { return runtime.serverRevision; },
            currentGeneration: function () { return runtime.runtimeGeneration; },
            isServerSnapshotInitialized: function () { return runtime.serverSnapshotInitialized; },
            serverStatus: function () { return runtime.serverStatus; },
            serverCanShow: function () { return runtime.serverCanShow; },
            serverRetryAfterMs: function () { return runtime.serverRetryAfterMs; },
            serverSeenLayouts: function () { return runtime.serverSeenLayouts.slice(); },
            serverStateAvailable: function () { return runtime.serverStateAvailable; },
            serverLocalBlockUntil: function () { return runtime.serverLocalBlockUntil; },
            serverBacked: function () { return runtime.serverBacked; },
            // 首次下载完成触发的一次性标记（只读观测）。
            firstDownloadTriggered: function () { return runtime.firstDownloadTriggered; },
            pendingTimerCount: function () { return runtime.pendingTimers.length; }
        })
    });

})(window);
