/* eslint-disable */
/** 布局调查服务端状态协议与命令传输。 */
(function (global) {
    'use strict';

    var modules = global.PixivLayoutFeedbackModules
        || (global.PixivLayoutFeedbackModules = {});
    modules.server = Object.freeze({
        install: function (ctx) {
            var runtime = ctx.runtime;

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
        runtime.serverIdentityAvailable = distinctId !== '';
        runtime.serverDistinctId = distinctId || null;
        runtime.serverSubmissionId = data.submissionId;
        runtime.serverStateAvailable = data.stateAvailable;
        runtime.serverBacked = data.stateAvailable && runtime.serverIdentityAvailable;
        runtime.serverRevision = data.revision;
        runtime.serverStatus = data.status != null ? data.status : null;
        runtime.serverCanShow = data.canShow;
        runtime.serverRetryAfterMs = data.retryAfterMs;
        runtime.serverSeenLayouts = seenLayouts;
        runtime.serverSnapshotInitialized = true;
        // 只保存本地临时截止时间（clientNow + retryAfterMs），不解释服务端绝对时间。
        runtime.serverLocalBlockUntil = (!runtime.serverCanShow && runtime.serverRetryAfterMs > 0)
            ? ctx.safeClientTimeAdd(ctx.clientWallNow(), runtime.serverRetryAfterMs)
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
     * - 网络失败 / 超时 / 非法响应：整体回退 local 模式；没有稳定提交 UUID 时远端
     *   提交会 fail closed；
     * - 超时 abort 并 resolve，不永久 pending；destroy 时 cancel；
     * - GET 超时后 / destroy 后 / re-init 旧 generation 的迟到响应一律不得应用；
     *   同 generation 已进入 reconciliation 后不接受重复 GET callback。
     */
    function loadServerContext(generation) {
        // 同一 generation 复用同一个 operation 的 promise（已完成也复用其已 resolve
        // 的结果，不重复 GET）；done 标记用于避免旧 operation 被当作活动操作复用。
        if (runtime.serverLoadOperation && runtime.serverLoadOperation.generation === generation) {
            return runtime.serverLoadOperation.promise;
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
        runtime.serverLoadOperation = operation;
        operation.promise = new Promise(function (resolve) {
            var settled = false;
            function finish() {
                if (settled) return;
                settled = true;
                operation.settled = true;
                operation.done = true;
                if (operation.timeoutId != null) {
                    ctx.clearTimerSafe(operation.timeoutId);
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
            operation.timeoutId = ctx.setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish();
            }, ctx.SERVER_STATE_TIMEOUT_MS);
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
                request = runtime.fetchImpl(ctx.serverStateUrl(), init);
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
                if (!ctx.isRuntimeGenerationActive(generation) || settled) throw new Error('stale');
                // 在应用服务端视图前先保存本地 fallback 快照，避免后续写协调缓存时
                // 失去尚未确认的本地 submitted / never / snoozed / seen 原始数据。
                var localFallback = {
                    state: ctx.readLocalStateRaw(),
                    seen: ctx.readLocalSeenRaw()
                };
                var result = ctx.applyServerView(data);
                if (result === ctx.VIEW_INVALID) throw new Error('invalid');
                if (result === ctx.VIEW_APPLIED || result === ctx.VIEW_UPDATED) {
                    commitServerView(data);
                }
                // GET 阶段结束：立即清除 GET timeout，不允许它影响 reconciliation。
                if (operation.timeoutId != null) {
                    ctx.clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (runtime.serverBacked) {
                    // 阶段二：有限本地状态回放（reconciliation）；整体 promise 必须
                    // 等待它达成或确定失败（reconciled 守卫保证只回放一次）。
                    // 最终同步 effectiveState 前必须再次验证 operation / generation 活动：
                    // destroy 已调用 operation.cancel（settled=true）时最终同步不得执行。
                    operation.phase = 'reconcile';
                    return ctx.reconcileLocalState(operation, generation, localFallback)
                        .then(function (result) {
                            if (!ctx.isOperationActive(operation, generation)) return null;
                            ctx.syncServerViewToLocalCache();
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
        if (!ctx.isRuntimeGenerationActive(generation)) {
            return Promise.resolve({status: ctx.REFRESH_CANCELLED, reason: 'generation-stale'});
        }
        if (runtime.serverRefreshInFlight) return runtime.serverRefreshInFlight;
        runtime.serverRefreshInFlight = new Promise(function (resolve) {
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
            runtime.serverRefreshOperation = operation;
            function finish(result) {
                if (settled) return;
                settled = true;
                operation.settled = true;
                if (operation.timeoutId != null) {
                    ctx.clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (runtime.serverRefreshOperation === operation) runtime.serverRefreshOperation = null;
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
                    ? {status: ctx.REFRESH_UNAVAILABLE, reason: 'timeout'}
                    : {status: ctx.REFRESH_CANCELLED, reason: operation.cancelReason});
            };
            operation.timeoutId = ctx.setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                operation.cancel('timeout');
            }, ctx.SERVER_STATE_TIMEOUT_MS);
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
                request = runtime.fetchImpl(ctx.serverStateUrl(), init);
            } catch (_) {
                finish({status: ctx.REFRESH_UNAVAILABLE, reason: 'fetch'});
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish({status: ctx.REFRESH_UNAVAILABLE, reason: 'fetch'});
                return;
            }
            request.then(function (response) {
                if (!ctx.isOperationActive(operation, generation)) throw new Error('stale');
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
                if (!ctx.isOperationActive(operation, generation)) throw new Error('stale');
                var result = ctx.applyServerView(data);
                if (result === ctx.VIEW_APPLIED || result === ctx.VIEW_UPDATED) {
                    commitServerView(data);
                    if (runtime.serverBacked) {
                        ctx.prunePendingAfterView();
                        ctx.syncServerViewToLocalCache();
                    }
                    finish({status: ctx.REFRESH_FRESH, viewResult: result});
                } else if (result === ctx.VIEW_SAME || result === ctx.VIEW_STALE) {
                    // SAME / STALE：无副作用（不 prune、不同步旧缓存），视为已是最新。
                    finish({status: ctx.REFRESH_FRESH, viewResult: result});
                } else {
                    throw {refreshKind: 'invalid', reason: 'view'};
                }
            }).catch(function (error) {
                if (!ctx.isOperationActive(operation, generation)) {
                    // 迟到 / 已取消：不改变已有结果；settled 守卫保证不重复 finish。
                    finish({status: ctx.REFRESH_CANCELLED, reason: operation.cancelReason || 'superseded'});
                    return;
                }
                if (error && error.refreshKind === 'invalid') {
                    finish({status: ctx.REFRESH_INVALID, reason: error.reason || 'protocol'});
                    return;
                }
                // fetch reject / 无响应对象 / 其它网络层异常：暂时性不可用。
                finish({status: ctx.REFRESH_UNAVAILABLE, reason: 'network'});
            });
        });
        var promise = runtime.serverRefreshInFlight;
        promise.then(function () {
            if (runtime.serverRefreshInFlight === promise) runtime.serverRefreshInFlight = null;
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
            return runtime.serverStatus === 'submitted';
        }
        if (command === 'never') {
            return runtime.serverStatus === 'never' || runtime.serverStatus === 'submitted';
        }
        if (command === 'snooze') {
            return runtime.serverStatus === 'snoozed' || runtime.serverStatus === 'never'
                || runtime.serverStatus === 'submitted';
        }
        if (command === 'record_seen') {
            var layoutIds = options.layoutIds || [];
            return layoutIds.every(function (id) {
                return runtime.serverSeenLayouts.indexOf(id) >= 0;
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
            runtime.serverCommandOperations.add(operation);
            function finish(result) {
                if (settled) return;
                settled = true;
                operation.settled = true;
                if (operation.timeoutId != null) {
                    ctx.clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                runtime.serverCommandOperations.delete(operation);
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
                var result = ctx.applyServerView(data);
                if (result === ctx.VIEW_INVALID) throw new Error('invalid view');
                operation.lastViewResult = result;
                if (result === ctx.VIEW_APPLIED || result === ctx.VIEW_UPDATED) {
                    commitServerView(data);
                }
                // VIEW_SAME：同 revision 完全相同，无副作用；VIEW_STALE 迟到响应不应用。
                return result;
            }
            operation.cancel = function () {
                if (settled || operation.aborted) return;
                operation.aborted = true;
                if (operation.timeoutId != null) {
                    ctx.clearTimerSafe(operation.timeoutId);
                    operation.timeoutId = null;
                }
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish(failedResult());
            };
            operation.timeoutId = ctx.setTimeoutSafe(function () {
                operation.timeoutId = null;
                if (settled) return;
                // 超时：abort 在途请求并以失败结果安全结束。
                operation.aborted = true;
                if (operation.abortController) {
                    try { operation.abortController.abort(); } catch (_) { /* 安全 */ }
                }
                finish(failedResult());
            }, ctx.SERVER_COMMAND_TIMEOUT_MS);
            if (!ctx.isRuntimeGenerationActive(generation)) {
                finish(failedResult());
                return;
            }
            var body = {
                surveyId: runtime.config.surveyId,
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
                request = runtime.fetchImpl(ctx.serverStateUrl(), init);
            } catch (_) {
                finish(failedResult());
                return;
            }
            if (!request || typeof request.then !== 'function') {
                finish(failedResult());
                return;
            }
            request.then(function (response) {
                if (!ctx.isOperationActive(operation, generation)) {
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
                if (!ctx.isOperationActive(operation, generation)) {
                    throw new Error('stale attempt');
                }
                var viewResult = applyCommandView(data);
                if (viewResult === ctx.VIEW_STALE) {
                    // 低 revision 迟到响应：无法确认本次命令，保留 pending 与本地 fallback。
                    finish(failedResult());
                    return;
                }
                var satisfied = commandSatisfiedByView(command, {layoutIds: layoutIds});
                if (satisfied) {
                    ctx.prunePendingAfterView({
                        command: command,
                        acknowledged: true,
                        layoutIds: layoutIds
                    });
                    ctx.syncServerViewToLocalCache();
                } else {
                    // 服务端视图未满足命令（例如 record_seen 缺布局）：保留 pending。
                    ctx.syncServerViewToLocalCache();
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

            Object.assign(ctx, {
                commandSatisfiedByView: commandSatisfiedByView,
                commitServerView: commitServerView,
                loadServerContext: loadServerContext,
                refreshServerContext: refreshServerContext,
                sendServerCommand: sendServerCommand
            });
        }
    });
})(window);
