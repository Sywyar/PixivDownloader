/* eslint-disable */
/** 布局调查 PostHog 适配、问卷获取与展示流程。 */
(function (global) {
    'use strict';

    var modules = global.PixivLayoutFeedbackModules
        || (global.PixivLayoutFeedbackModules = {});
    modules.survey = Object.freeze({
        install: function (ctx) {
            var runtime = ctx.runtime;

    function currentLayoutId() {
        if (runtime.configuredLayoutId) return runtime.configuredLayoutId;
        if (runtime.pageType === 'alt') return 'pixiv-batch-alt';
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
        return ctx.mapLayoutToken(token);
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
        if (runtime.appVersionPromise) return runtime.appVersionPromise;
        runtime.appVersionPromise = new Promise(function (resolve) {
            var settled = false;
            var timer = ctx.setTimeoutSafe(function () { finish('unknown'); }, ctx.APP_VERSION_TIMEOUT_MS);
            function finish(version) {
                if (settled) return;
                settled = true;
                ctx.clearTimerSafe(timer);
                resolve(version);
            }
            var request = null;
            try {
                request = runtime.fetchImpl('/api/app/info', {credentials: 'same-origin'});
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
        return runtime.appVersionPromise;
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
        if (runtime.sdkLoadOperation && runtime.sdkLoadOperation.generation === generation) {
            return runtime.sdkLoadOperation.promise;
        }
        var operation = {
            generation: generation,
            promise: null,
            settled: false,
            cancel: null
        };
        runtime.sdkLoadOperation = operation;
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
                    ownerKey: ctx.POSTHOG_OWNER_KEY,
                    posthog: ctx.POSTHOG,
                    trustedApiOrigins: ctx.TRUSTED_POSTHOG_API_ORIGINS,
                    distinctId: runtime.serverIdentityAvailable && runtime.serverDistinctId ? runtime.serverDistinctId : '',
                    storage: runtime.storage,
                    beforeSend: ctx.beforeSendFilter
                }).then(function (sdk) {
                    finish(ctx.isRuntimeGenerationActive(generation) ? sdk : null);
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
            ctx.warn('layout survey: posthog sdk does not expose get_distinct_id; survey disabled for this page');
            return false;
        }
        var actual = null;
        try {
            actual = sdk.get_distinct_id();
        } catch (_) {
            actual = null;
        }
        if (actual !== runtime.serverDistinctId) {
            ctx.warn('layout survey: posthog distinct id does not match the server scoped identity; survey disabled for this page');
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
                if (!ctx.isRuntimeGenerationActive(generation)) {
                    // destroy 后旧 generation 不再发送生命周期事件
                    finish(false);
                    return;
                }
                if (!sdk || typeof sdk.capture !== 'function') {
                    finish(false);
                    return;
                }
                if (name === 'survey sent') {
                    var manager = global.PixivPostHog;
                    if (!manager || typeof manager.captureSurveyWithAck !== 'function') {
                        finish(false);
                        return;
                    }
                    manager.captureSurveyWithAck(
                        ctx.POSTHOG_OWNER_KEY, name, properties, runtime.serverSubmissionId).then(function () {
                        finish(true);
                    }, function () {
                        finish(false);
                    });
                    return;
                }
                try {
                    var result = sdk.capture(name, properties);
                    // 只有 capture 返回非空 CaptureResult 对象（result.event === name）
                    // 才视为 SDK 已接受事件；undefined / null / false 均视为未接受。
                    // 这只证明 SDK 本地接受了事件，不保证 PostHog 服务端最终入库。
                    finish(ctx.isAcceptedCaptureResult(result, name));
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
            '$survey_id': runtime.dialogSurveyId,
            app_version: 'unknown',
            current_layout: runtime.layoutSnapshot,
            survey_schema_version: ctx.SURVEY_SCHEMA_VERSION
        };
        Object.keys(extra || {}).forEach(function (key) {
            props[key] = extra[key];
        });
        return Promise.resolve(loadAppVersion()).then(function (version) {
            if (!ctx.isRuntimeGenerationActive(generation)) return null;
            props.app_version = version || 'unknown';
            return props;
        });
    }

    function sendShown() {
        if (runtime.shownSent || !runtime.dialogSurveyId) return;
        var generation = ctx.currentRuntimeGeneration();
        runtime.shownSent = true;
        surveyEventProperties(generation).then(function (props) {
            if (!props) return;
            return sendSurveyEvent(generation, 'survey shown', props);
        }).catch(function () {
            // shown 发送失败不影响用户填写调查
        });
    }

    function sendDismissedBestEffort() {
        if (!runtime.dialogSurveyId) return Promise.resolve();
        var generation = ctx.currentRuntimeGeneration();
        return surveyEventProperties(generation).then(function (props) {
            if (!props) return;
            return sendSurveyEvent(generation, 'survey dismissed', props);
        }).catch(function () {
            // 本地永久关闭优先；事件尽力而为
        });
    }

    /* ============================================================
       Survey 获取
    ============================================================ */

    function findTargetSurvey(surveys) {
        if (!Array.isArray(surveys)) return null;
        var found = null;
        surveys.forEach(function (survey) {
            if (!survey || typeof survey !== 'object') return;
            if (survey.id !== runtime.config.surveyId) return;
            if (survey.type !== 'api') return;
            found = survey;
        });
        return found;
    }

    function findSurveyById(surveys) {
        if (!Array.isArray(surveys)) return null;
        return surveys.find(function (survey) {
            return survey && typeof survey === 'object' && survey.id === runtime.config.surveyId;
        }) || null;
    }

    function fetchPublishedSurvey(sdk, generation) {
        return new Promise(function (resolve) {
            var settled = false;
            var timer = ctx.setTimeoutSafe(function () { finish('unavailable'); }, ctx.SURVEY_TOTAL_TIMEOUT_MS);
            function finish(status, survey) {
                if (settled) return;
                settled = true;
                ctx.clearTimerSafe(timer);
                if (runtime.pendingSurveyCancel === cancel) runtime.pendingSurveyCancel = null;
                resolve({status: status, survey: survey || null});
            }
            function cancel() {
                finish('cancelled');
            }
            runtime.pendingSurveyCancel = cancel;
            if (!ctx.isRuntimeGenerationActive(generation) || typeof sdk.getSurveys !== 'function') {
                finish('unavailable');
                return;
            }
            try {
                sdk.getSurveys(function (surveys, context) {
                    if (!ctx.isRuntimeGenerationActive(generation)) {
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
            var totalTimer = ctx.setTimeoutSafe(function () { finish(null); }, ctx.SURVEY_TOTAL_TIMEOUT_MS);
            function finish(survey) {
                if (settled) return;
                settled = true;
                ctx.clearTimerSafe(totalTimer);
                if (flagTimer != null) ctx.clearTimerSafe(flagTimer);
                if (typeof off === 'function') {
                    try { off(); } catch (_) { /* 解除监听尽力而为 */ }
                    off = null;
                }
                if (runtime.pendingSurveyCancel === cancel) runtime.pendingSurveyCancel = null;
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
                if (!ctx.isRuntimeGenerationActive(generation)) return;
                surveyRequested = true;
                if (flagTimer != null) ctx.clearTimerSafe(flagTimer);
                try {
                    sdk.getActiveMatchingSurveys(function (surveys) {
                        if (settled) return;
                        finish(findTargetSurvey(surveys));
                    }, false);
                } catch (_) {
                    finish(null);
                }
            }
            runtime.pendingSurveyCancel = cancel;
            flagTimer = ctx.setTimeoutSafe(function () { proceed(); }, ctx.FLAGS_TIMEOUT_MS);
            try {
                off = sdk.onFeatureFlags(function () {
                    proceed();
                });
            } catch (_) {
                off = null;
                if (flagTimer != null) ctx.clearTimerSafe(flagTimer);
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
        if (!runtime.initialized || runtime.flowRunning || runtime.dialogOpen) {
            return Promise.resolve(flowResult('cancelled'));
        }
        var generation = ctx.currentRuntimeGeneration();
        runtime.flowRunning = true;
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
            if (ctx.isRuntimeGenerationActive(generation)) runtime.flowRunning = false;
            return result;
        }
        function proceedToSdk() {
            if (!ctx.isRuntimeGenerationActive(generation)) return sdkStep(null);
            return resolveSdk(generation).then(function (sdk) {
                return sdkStep(sdk);
            });
        }
        return ctx.loadServerContext(generation).then(function () {
            if (!ctx.isRuntimeGenerationActive(generation)) return flowResult('cancelled');
            if (!runtime.config || !runtime.config.enabled) return flowResult('cancelled');
            if (options.skipStateGate) return proceedToSdk();
            if (options.ignoreReminderGate) {
                return ctx.isSubmittedDecision(ctx.readStateFresh())
                    ? flowResult('blocked')
                    : proceedToSdk();
            }
            if (!ctx.stateAllowsShow(runtime.timers.now())) {
                // 服务端 snooze 未到期（canShow=false）：不加载 SDK、不请求 Survey，
                // 只在自己的本地截止时间（serverLocalBlockUntil）对齐安排自动检查。
                if (runtime.serverBacked && runtime.serverStatus === 'snoozed' && !runtime.serverCanShow
                        && runtime.serverLocalBlockUntil > runtime.timers.now()) {
                    return flowResult('blocked', null, runtime.serverLocalBlockUntil);
                }
                return flowResult('blocked');
            }
            // 本地截止时间已到但服务端视图仍是旧 snoozed / canShow=false：
            // 强制重新 GET 权威状态（服务端独立判断到期），再按新视图判断门禁。
            if (runtime.serverBacked && runtime.serverStatus === 'snoozed' && !runtime.serverCanShow) {
                return ctx.refreshServerContext(generation).then(function (result) {
                    if (!ctx.isRuntimeGenerationActive(generation)) return flowResult('cancelled');
                    if (result.status === ctx.REFRESH_INVALID) return flowResult('invalid');
                    if (result.status === ctx.REFRESH_CANCELLED) return flowResult('cancelled');
                    if (result.status === ctx.REFRESH_UNAVAILABLE) {
                        // 服务端暂时不可用：本地无阻断状态时按现有 availability 策略
                        // 继续（明确 fail-open，不解释任何服务端绝对时间点）。
                        if (!ctx.stateAllowsShow(runtime.timers.now())) return flowResult('blocked');
                        return proceedToSdk();
                    }
                    if (!ctx.stateAllowsShow(runtime.timers.now())) {
                        // 服务端延长 / 缩短 snooze：按最新 serverLocalBlockUntil 阻断。
                        if (runtime.serverBacked && runtime.serverStatus === 'snoozed' && !runtime.serverCanShow
                                && runtime.serverLocalBlockUntil > runtime.timers.now()) {
                            return flowResult('blocked', null, runtime.serverLocalBlockUntil);
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
            if (!ctx.isRuntimeGenerationActive(generation)) return flowResult('cancelled');
            var sdk = step.sdk;
            if (!sdk) return flowResult(options.verifyPublication ? 'unavailable' : 'started');
            if (runtime.serverIdentityAvailable && runtime.serverDistinctId) {
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
                if (!ctx.isRuntimeGenerationActive(generation) || published.status === 'cancelled') {
                    return flowResult('cancelled');
                }
                if (published.status !== 'available') return flowResult(published.status);
                return fetchMatchingSurvey(sdk, generation).then(function (survey) {
                    if (!ctx.isRuntimeGenerationActive(generation)) return flowResult('cancelled');
                    if (!survey) return flowResult(options.verifyPublication ? 'ineligible' : 'no-survey');
                    var choiceQuestion = ctx.resolveChoiceQuestion(survey);
                    if (!choiceQuestion) {
                        ctx.warn('layout survey: layout choice question schema invalid; survey hidden');
                        return flowResult(options.verifyPublication ? 'ineligible' : 'no-survey');
                    }
                    var suggestionQuestion = ctx.resolveSuggestionQuestion(survey);
                    if (!ctx.openDialog(survey, choiceQuestion, suggestionQuestion)) {
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

            Object.assign(ctx, {
                currentLayoutId: currentLayoutId,
                documentElement: documentElement,
                fetchMatchingSurvey: fetchMatchingSurvey,
                fetchPublishedSurvey: fetchPublishedSurvey,
                findSurveyById: findSurveyById,
                findTargetSurvey: findTargetSurvey,
                isCapturingDisabled: isCapturingDisabled,
                loadAppVersion: loadAppVersion,
                resolveSdk: resolveSdk,
                sendDismissedBestEffort: sendDismissedBestEffort,
                sendShown: sendShown,
                sendSurveyEvent: sendSurveyEvent,
                showSurveyFlow: showSurveyFlow,
                surveyEventProperties: surveyEventProperties,
                verifySdkDistinctId: verifySdkDistinctId
            });
        }
    });
})(window);
