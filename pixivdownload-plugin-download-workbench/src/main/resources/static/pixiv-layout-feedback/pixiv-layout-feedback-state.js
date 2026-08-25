/* eslint-disable */
/** 布局调查本地状态、对账与去重。 */
(function (global) {
    'use strict';

    var modules = global.PixivLayoutFeedbackModules
        || (global.PixivLayoutFeedbackModules = {});
    modules.state = Object.freeze({
        install: function (ctx) {
            var runtime = ctx.runtime;

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
        if (!ctx.isOperationActive(operation, generation)) {
            return Promise.resolve({decisionResult: null, seenResult: null});
        }
        if (runtime.reconciled) {
            return Promise.resolve({decisionResult: null, seenResult: null});
        }
        runtime.reconciled = true;
        return reconcileDecision(operation, generation, localFallback).then(function (decisionResult) {
            if (!ctx.isOperationActive(operation, generation)) {
                // destroy / 被取代：不得进入 reconcileSeen、不得写 pendingLocalSeen。
                return {decisionResult: null, seenResult: null};
            }
            return reconcileSeen(operation, generation, localFallback).then(function (seenResult) {
                if (!ctx.isOperationActive(operation, generation)) {
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
        if (!ctx.isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        var localState = localFallback && ctx.isPlainObject(localFallback.state)
            ? localFallback.state
            : null;
        if (!localState || localState.surveyId !== runtime.config.surveyId) {
            return Promise.resolve({replayed: false});
        }
        var now = runtime.timers.now();
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
                if (localRemaining <= serverRemaining + ctx.RECONCILE_REMAINING_TOLERANCE_MS) {
                    return Promise.resolve({replayed: false});
                }
            } else if (compareDecisionState(serverStrong, localStrong, now) >= 0) {
                // 服务端已更强或相同：本地 fallback 由 syncServerViewToLocalCache 覆盖。
                return Promise.resolve({replayed: false});
            }
        }
        // 发送前先记入 pendingLocalState：请求失败 / 超时时本地回退仍参与 effectiveState。
        // 写入前重新验证活性：destroy 后旧链不得修改新 generation 的 pendingLocalState。
        if (!ctx.isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        runtime.pendingLocalState = localStrong;
        var command = localStrong.status === 'snoozed' ? 'snooze' : localStrong.status;
        return ctx.sendServerCommand(generation, command, null).then(function (result) {
            if (!ctx.isOperationActive(operation, generation)) {
                // 命令完成后旧链已失效：不输出旧 generation warning、不再继续。
                return {replayed: false, cancelled: true};
            }
            if (!result.ok) {
                ctx.warn('layout survey: local state replay failed; keeping local fallback');
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
        if (!ctx.isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        var localSeen = localFallback && ctx.isPlainObject(localFallback.seen)
            ? localFallback.seen
            : {};
        var layoutIds = [];
        ctx.LAYOUT_IDS.forEach(function (id) {
            var entry = localSeen[id];
            if (entry && typeof entry.lastSeenAt === 'number' && entry.lastSeenAt > 0
                    && runtime.serverSeenLayouts.indexOf(id) < 0) {
                layoutIds.push(id);
            }
        });
        if (!layoutIds.length) {
            return Promise.resolve({replayed: false});
        }
        // 写 pendingLocalSeen 前重新验证活性：destroy 后旧链不得写新 generation 的
        // pendingLocalSeen。
        if (!ctx.isOperationActive(operation, generation)) {
            return Promise.resolve({replayed: false, cancelled: true});
        }
        layoutIds.forEach(function (id) {
            var entry = localSeen[id];
            if (entry && typeof entry === 'object') {
                runtime.pendingLocalSeen[id] = {
                    firstSeenAt: typeof entry.firstSeenAt === 'number' ? entry.firstSeenAt : 0,
                    lastSeenAt: entry.lastSeenAt
                };
            }
        });
        return ctx.sendServerCommand(generation, 'record_seen', {layoutIds: layoutIds})
            .then(function (result) {
                if (!ctx.isOperationActive(operation, generation)) {
                    // 命令完成后旧链已失效：不输出旧 generation warning。
                    return {replayed: false, cancelled: true};
                }
                if (!result.ok) {
                    ctx.warn('layout survey: local seen replay failed; keeping local fallback');
                }
                return {replayed: !!result.ok};
            });
    }

    /**
     * 统一决策状态归一化（客户端时钟域）：只接受当前 config.surveyId 的合法状态；
     * 过期 snoozed 视为无状态；非法状态 / 其它 Survey 一律返回 null。
     */
    function normalizeDecisionState(state, now) {
        if (!state || state.surveyId !== runtime.config.surveyId) return null;
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
        if (!runtime.serverBacked || !runtime.serverStatus) return null;
        if (runtime.serverStatus === 'snoozed' && !runtime.serverCanShow) {
            var until = runtime.serverLocalBlockUntil > 0
                ? runtime.serverLocalBlockUntil
                : ctx.safeClientTimeAdd(clientNow, runtime.serverRetryAfterMs);
            return {surveyId: runtime.config.surveyId, status: 'snoozed', updatedAt: 0, snoozedUntil: until};
        }
        return {surveyId: runtime.config.surveyId, status: runtime.serverStatus, updatedAt: 0, snoozedUntil: 0};
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
        if (runtime.pendingLocalState && runtime.pendingLocalState.surveyId === runtime.config.surveyId) {
            sources.push({state: runtime.pendingLocalState, source: 'local'});
        }
        var serverState = serverViewAsState(clientNow);
        if (serverState) {
            sources.push({state: serverState, source: 'server'});
        }
        var local = readLocalStateRaw();
        if (local && local.surveyId === runtime.config.surveyId) {
            sources.push({state: local, source: 'local'});
        }
        if (runtime.sessionState && runtime.sessionState.surveyId === runtime.config.surveyId) {
            sources.push({state: runtime.sessionState, source: 'local'});
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
        if (state && state.surveyId === runtime.dialogSurveyId && isSubmittedDecision(state)) {
            return true;
        }
        var raw = readLocalStateRaw();
        if (raw && raw.surveyId === runtime.dialogSurveyId && isSubmittedDecision(raw)) {
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
        if (!runtime.config) {
            return null;
        }
        var candidates = [];
        var serverState = serverViewAsState(ctx.clientWallNow());
        if (serverState) {
            candidates.push({state: serverState, source: 'server'});
        }
        if (runtime.pendingLocalState && runtime.pendingLocalState.surveyId === runtime.config.surveyId) {
            candidates.push({state: runtime.pendingLocalState, source: 'local'});
        }
        if (!runtime.serverBacked) {
            // local 模式：localStorage 是事实来源（损坏清理语义见 readStateFresh）。
            var local = readLocalStateRaw();
            if (local && local.surveyId === runtime.config.surveyId) {
                candidates.push({state: local, source: 'local'});
            }
            if (runtime.sessionState && runtime.sessionState.surveyId === runtime.config.surveyId) {
                candidates.push({state: runtime.sessionState, source: 'local'});
            }
        }
        var clientNow = ctx.clientWallNow();
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
            if (!ctx.isFiniteInteger(state.snoozedUntil) || state.snoozedUntil <= clientNow) {
                return null;
            }
            var candidateUntil = state.snoozedUntil;
            var existing = existingLocalState;
            if (existing && existing.surveyId === state.surveyId
                    && existing.status === 'snoozed'
                    && ctx.isFiniteInteger(existing.snoozedUntil)
                    && Math.abs(existing.snoozedUntil - candidateUntil)
                        <= ctx.LOCAL_SNOOZE_WRITE_TOLERANCE_MS) {
                return {
                    surveyId: existing.surveyId,
                    status: 'snoozed',
                    updatedAt: ctx.isFiniteInteger(existing.updatedAt) ? existing.updatedAt : clientNow,
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
                && ctx.isFiniteInteger(existingState.updatedAt)) {
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
        ctx.LAYOUT_IDS.forEach(function (id) {
            var existing = existingLocalSeen && existingLocalSeen[id];
            var pending = runtime.pendingLocalSeen && runtime.pendingLocalSeen[id];
            var serverHas = runtime.serverBacked && runtime.serverSeenLayouts.indexOf(id) >= 0;
            var firstSeenAt = null;
            var lastSeenAt = null;
            if (existing && ctx.isFiniteInteger(existing.firstSeenAt)
                    && ctx.isFiniteInteger(existing.lastSeenAt)) {
                firstSeenAt = existing.firstSeenAt;
                lastSeenAt = existing.lastSeenAt;
            }
            if (pending && ctx.isFiniteInteger(pending.firstSeenAt)
                    && ctx.isFiniteInteger(pending.lastSeenAt)) {
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
        if (runtime.serverBacked && runtime.serverSeenLayouts.length) {
            var serverSeen = {};
            runtime.serverSeenLayouts.forEach(function (id) {
                serverSeen[id] = {firstSeenAt: 1, lastSeenAt: 1};
            });
            sources.push(serverSeen);
        }
        if (runtime.pendingLocalSeen && typeof runtime.pendingLocalSeen === 'object') {
            sources.push(runtime.pendingLocalSeen);
        }
        if (!runtime.serverBacked) {
            var local = readLocalSeenRaw();
            if (local && typeof local === 'object') {
                sources.push(local);
            }
        }
        var merged = {};
        ctx.LAYOUT_IDS.forEach(function (id) {
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
        if (runtime.pendingLocalState && runtime.pendingLocalState.surveyId === runtime.config.surveyId && runtime.serverBacked) {
            var commandMatches = acknowledged
                && ((command === 'submitted' && runtime.pendingLocalState.status === 'submitted')
                    || (command === 'never' && runtime.pendingLocalState.status === 'never')
                    || (command === 'snooze' && runtime.pendingLocalState.status === 'snoozed'));
            var serverCovers = (runtime.pendingLocalState.status === 'submitted'
                    && runtime.serverStatus === 'submitted')
                || (runtime.pendingLocalState.status === 'never'
                    && (runtime.serverStatus === 'submitted' || runtime.serverStatus === 'never'))
                || (runtime.pendingLocalState.status === 'snoozed'
                    && (runtime.serverStatus === 'submitted' || runtime.serverStatus === 'never'));
            if (commandMatches || serverCovers) {
                runtime.pendingLocalState = null;
            }
        }
        Object.keys(runtime.pendingLocalSeen).forEach(function (id) {
            if (runtime.serverSeenLayouts.indexOf(id) >= 0
                    || (acknowledged && command === 'record_seen' && layoutIds
                        && layoutIds.indexOf(id) >= 0)) {
                delete runtime.pendingLocalSeen[id];
            }
        });
    }

    function readLocalStateRaw() {
        if (!runtime.storage) return null;
        var raw = null;
        try {
            raw = runtime.storage.getItem(ctx.STATE_KEY);
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
        if (!runtime.storage) return {};
        var raw = null;
        try {
            raw = runtime.storage.getItem(ctx.SEEN_KEY);
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
        if (!runtime.storage) return;
        try {
            if (runtime.storage.getItem(key) === serializedValue) return;
            runtime.storage.setItem(key, serializedValue);
        } catch (_) {
            // 存储不可用时仅保留内存态
        }
    }

    /** 安全删除 helper：不存在时不再重复 removeItem；localStorage 异常安全降级。 */
    function removeStorageIfPresent(key) {
        if (!runtime.storage) return;
        try {
            if (runtime.storage.getItem(key) === null) return;
            runtime.storage.removeItem(key);
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
        if (!runtime.storage) return;
        var record = effectiveStateRecord();
        var localState = serverViewToLocalState(record, ctx.clientWallNow(), readLocalStateRaw());
        runtime.sessionState = localState;
        if (localState) {
            setStorageIfChanged(ctx.STATE_KEY, JSON.stringify(localState));
        } else if (!runtime.pendingLocalState || runtime.pendingLocalState.surveyId !== runtime.config.surveyId) {
            // 只有确认不存在未确认 fallback 时才允许 removeItem。
            removeStorageIfPresent(ctx.STATE_KEY);
        }
        if (runtime.serverBacked) {
            // 服务端只提供布局 ID 存在性，绝对时间戳不进入本地缓存。
            setStorageIfChanged(ctx.SEEN_KEY,
                JSON.stringify(localSeenForLocalCache(ctx.clientWallNow(), readLocalSeenRaw())));
        } else {
            setStorageIfChanged(ctx.SEEN_KEY, JSON.stringify(effectiveSeen()));
        }
    }

    /**
     * 布局体验记录合并：serverBacked 下把本地尚未被服务器确认的布局记入
     * pendingSeenLayouts（按布局 ID 存在性判断，不比较任何服务端时间戳），去抖后以
     * record_seen 命令提交（不再发送完整 seen）。已确认布局不再重复提交。
     */
    function scheduleServerSeenFlush() {
        if (!runtime.serverBacked) return;
        var local = runtime.sessionSeen && typeof runtime.sessionSeen === 'object' ? runtime.sessionSeen : {};
        ctx.LAYOUT_IDS.forEach(function (id) {
            var entry = local[id];
            if (entry && typeof entry.lastSeenAt === 'number' && entry.lastSeenAt > 0
                    && runtime.serverSeenLayouts.indexOf(id) < 0 && !runtime.pendingSeenLayouts[id]) {
                runtime.pendingSeenLayouts[id] = true;
            }
        });
        if (runtime.serverSaveTimerId != null) ctx.clearTimerSafe(runtime.serverSaveTimerId);
        runtime.serverSaveTimerId = ctx.setTimeoutSafe(function () {
            runtime.serverSaveTimerId = null;
            flushServerSeen();
        }, ctx.SERVER_SAVE_DEBOUNCE_MS);
    }

    function flushServerSeen() {
        if (!runtime.serverBacked) return;
        var generation = ctx.currentRuntimeGeneration();
        var layoutIds = Object.keys(runtime.pendingSeenLayouts).filter(function (id) {
            return ctx.LAYOUT_IDS.indexOf(id) >= 0;
        });
        if (!layoutIds.length) return;
        ctx.sendServerCommand(generation, 'record_seen', {layoutIds: layoutIds}).then(function (result) {
            if (result && result.ok) {
                layoutIds.forEach(function (id) { delete runtime.pendingSeenLayouts[id]; });
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
        if (!runtime.config) return null;
        if (runtime.serverBacked) {
            // serverBacked：服务端权威视图（已转换为客户端时钟域伪状态）与未确认
            // 本地 fallback 合并后的有效状态。
            return effectiveState();
        }
        // local 模式：来源一律为客户端时钟域。
        if (!runtime.storage) return runtime.sessionState;
        var raw = null;
        try {
            raw = runtime.storage.getItem(ctx.STATE_KEY);
        } catch (_) {
            // 存储不可读：保留内存态。
            return runtime.sessionState;
        }
        if (!raw) {
            runtime.sessionState = null;
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
            removeStorageIfPresent(ctx.STATE_KEY);
            runtime.sessionState = null;
            return null;
        }
        runtime.sessionState = parsed;
        return parsed;
    }

    function readState() {
        if (!runtime.config) return null;
        var state = readStateFresh();
        if (!state || state.surveyId !== runtime.config.surveyId) return null;
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
        if (!runtime.config) return null;
        var clientNow = runtime.timers.now();
        var candidate = {
            surveyId: runtime.config.surveyId,
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
        if (runtime.serverBacked) {
            // sessionState 与 STATE_KEY 只保存客户端时钟域转换后的本地协调缓存；
            // 服务端绝对时间点绝不落本地（serverViewAsState 已是本地截止时间）。
            var localCacheNext = serverViewToLocalState(
                {state: effectiveNext, source: effectiveSource}, clientNow, readLocalStateRaw());
            runtime.sessionState = localCacheNext;
            var serverStrong = serverViewAsState(clientNow);
            if (serverStrong && compareDecisionState(serverStrong, effectiveNext, clientNow) >= 0) {
                // 服务端已更强或等价：没有未确认的本地 fallback。
                runtime.pendingLocalState = null;
            } else {
                // 本地决策最强（或尚待服务端确认）：保留为未确认 fallback，
                // 绝不写弱于 effectiveNext 的状态。
                runtime.pendingLocalState = effectiveNext;
            }
            if (runtime.storage) {
                if (localCacheNext) {
                    setStorageIfChanged(ctx.STATE_KEY, JSON.stringify(localCacheNext));
                } else {
                    removeStorageIfPresent(ctx.STATE_KEY);
                }
            }
            if (transitionAccepted) {
                var command = status === 'submitted' ? 'submitted'
                    : status === 'never' ? 'never' : 'snooze';
                // 命令不带任何时间戳；snooze 时长完全由服务端按自己的时钟计算。
                serverCommandStarted = true;
                ctx.sendServerCommand(ctx.currentRuntimeGeneration(), command, null).then(function (result) {
                    if (!result.ok) {
                        ctx.warn('layout survey: server state save failed; keeping local fallback');
                    }
                }).catch(function () { /* 安全降级 */ });
            }
        } else {
            runtime.sessionState = effectiveNext;
            if (runtime.storage) {
                setStorageIfChanged(ctx.STATE_KEY, JSON.stringify(effectiveNext));
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
        if (runtime.serverBacked) {
            // 服务端绝对时间戳不进入会话 seen：只保留客户端时钟域（存在性语义）。
            runtime.sessionSeen = localSeenForLocalCache(ctx.clientWallNow(), readLocalSeenRaw());
            return Object.assign({}, runtime.sessionSeen);
        }
        if (!runtime.storage) return Object.assign({}, runtime.sessionSeen);
        var raw = null;
        try {
            raw = runtime.storage.getItem(ctx.SEEN_KEY);
        } catch (_) {
            // 存储不可读：保留会话记录。
            return Object.assign({}, runtime.sessionSeen);
        }
        if (!raw) return Object.assign({}, runtime.sessionSeen);
        var parsed = null;
        try {
            var candidate = JSON.parse(raw);
            if (candidate && typeof candidate === 'object') parsed = candidate;
        } catch (_) {
            parsed = null;
        }
        if (!parsed) {
            // 损坏 JSON：清理并清空会话记录。
            removeStorageIfPresent(ctx.SEEN_KEY);
            runtime.sessionSeen = {};
            return {};
        }
        runtime.sessionSeen = parsed;
        return Object.assign({}, parsed);
    }

    function writeSeen(seen) {
        runtime.sessionSeen = seen;
        if (runtime.serverBacked) {
            // 本地协调缓存：同浏览器跨标签体验阈值同步 + 服务端恢复前的临时保护。
            // 未确认部分记入 pendingLocalSeen（由 recordSeen 维护），绝不直接当成
            // 服务端已确认事实写入 serverSeenLayouts。
            if (runtime.storage) {
                setStorageIfChanged(ctx.SEEN_KEY, JSON.stringify(seen));
            }
            scheduleServerSeenFlush();
            return;
        }
        if (runtime.storage) {
            setStorageIfChanged(ctx.SEEN_KEY, JSON.stringify(seen));
        }
    }

    function recordSeen(layoutId, now) {
        if (!layoutId || ctx.LAYOUT_IDS.indexOf(layoutId) < 0) return null;
        var seen = readSeenRaw();
        var entry = seen[layoutId] || {firstSeenAt: 0, lastSeenAt: 0};
        if (!entry.firstSeenAt) entry.firstSeenAt = now;
        entry.lastSeenAt = now;
        seen[layoutId] = entry;
        if (runtime.serverBacked) {
            // 服务器确认前保留本地贡献：earliest firstSeenAt / latest lastSeenAt。
            var pending = runtime.pendingLocalSeen[layoutId] || {};
            pending.firstSeenAt = typeof pending.firstSeenAt === 'number'
                ? Math.min(pending.firstSeenAt, entry.firstSeenAt)
                : entry.firstSeenAt;
            pending.lastSeenAt = Math.max(
                typeof pending.lastSeenAt === 'number' ? pending.lastSeenAt : 0,
                entry.lastSeenAt);
            runtime.pendingLocalSeen[layoutId] = pending;
        }
        writeSeen(seen);
        return ctx.distinctSeenCount(seen);
    }

            Object.assign(ctx, {
                compareDecisionState: compareDecisionState,
                effectiveSeen: effectiveSeen,
                effectiveState: effectiveState,
                effectiveStateRecord: effectiveStateRecord,
                flushServerSeen: flushServerSeen,
                hasSubmittedLocalDecision: hasSubmittedLocalDecision,
                isBlockingDecision: isBlockingDecision,
                isDecisionAtLeastAsStrong: isDecisionAtLeastAsStrong,
                isSubmittedDecision: isSubmittedDecision,
                localSeenForLocalCache: localSeenForLocalCache,
                normalizeDecisionState: normalizeDecisionState,
                prunePendingAfterView: prunePendingAfterView,
                readLocalSeenRaw: readLocalSeenRaw,
                readLocalStateRaw: readLocalStateRaw,
                readSeenRaw: readSeenRaw,
                readState: readState,
                readStateFresh: readStateFresh,
                reconcileDecision: reconcileDecision,
                reconcileLocalState: reconcileLocalState,
                reconcileSeen: reconcileSeen,
                recordSeen: recordSeen,
                remainingSnoozeMs: remainingSnoozeMs,
                removeStorageIfPresent: removeStorageIfPresent,
                scheduleServerSeenFlush: scheduleServerSeenFlush,
                serverViewAsState: serverViewAsState,
                serverViewToLocalState: serverViewToLocalState,
                setStorageIfChanged: setStorageIfChanged,
                stateAllowsShow: stateAllowsShow,
                strongerDecisionState: strongerDecisionState,
                strongestLocalExcludingCandidate: strongestLocalExcludingCandidate,
                syncServerViewToLocalCache: syncServerViewToLocalCache,
                writeSeen: writeSeen,
                writeState: writeState
            });
        }
    });
})(window);
