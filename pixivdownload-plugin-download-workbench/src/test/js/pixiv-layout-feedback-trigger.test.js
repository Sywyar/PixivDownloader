'use strict';

/** 自动触发流程与安全整数边界。 */
const {
    CONFIG_SOURCE,
    CONFIG_SOURCE_PATH,
    CONFIG_WINDOW,
    CSS,
    CSS_PATH,
    EMBED_SOURCE,
    EMBED_SOURCE_PATH,
    LAYOUT_IDS,
    MiniClassList,
    MiniCustomEvent,
    MiniElement,
    MiniEventTarget,
    MiniStorage,
    POSTHOG_SOURCE,
    POSTHOG_SOURCE_PATH,
    SEEN_KEY,
    SERVER_RAW_UUID,
    SERVER_SCOPED_ID,
    SNOOZE_MS,
    SOURCE,
    SOURCES,
    SOURCE_NAMES,
    SOURCE_PATHS,
    STATE_KEY,
    SUBMISSION_ID,
    SUGGESTION_MAX,
    SURVEY_ID,
    ackEvents,
    assert,
    assertFailClosedInvariants,
    captureEvents,
    captureProps,
    createFakeAdapter,
    createFakeI18n,
    createFakeTimers,
    createHarness,
    crossTabFallbackMatrix,
    crossTabState,
    dataAttributeName,
    defaultSurvey,
    directRefresh,
    doesNotThrow,
    eq,
    fs,
    hasLoneSurrogates,
    initHarness,
    jsonEq,
    listenerCountFor,
    localStateValue,
    matchSelector,
    neverView,
    ok,
    openAndPrepareSubmit,
    path,
    refreshSecond,
    refreshWith,
    reinitOptions,
    runTests,
    seenObject,
    seenSeed,
    selectChoice,
    serverStateResponse,
    snoozeStorageValue,
    snoozedView,
    submitWithCaptureOverride,
    submittedView,
    surveyState,
    validFirst,
    vm,
    waitForFlush,
    waitForServerContext,
    walkAll
} = require('./pixiv-layout-feedback-test-support');

function testTriggerAfterServerSnoozeDeadline() {
    // 服务端 snoozed / canShow=false / retryAfterMs=1000：本地截止未到前触发
    // 不展示、不重新 GET；本地截止过后触发会先重新 GET 权威状态，服务端确认
    // canShow=true 后才进入 SDK；SDK / Survey 网络流程只启动一次；重复事件
    // 不启动第二次。
    let getCalls = 0;
    let gateResolve = null;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            // 本地截止到达后的权威刷新：在途等待测试确认（模拟服务端延迟响应）。
            return new Promise(resolve => { gateResolve = resolve; });
        }
    });
    return waitForServerContext(h).then(() => {
        // 本地截止（now + 1000）前触发：不展示、不重新 GET、不加载 SDK。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('本地截止前不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('本地截止前不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('本地截止前不重新 GET', h.stateFetchCount(), 1);
        // 本地截止到达后触发：权威刷新在途。
        h.timers.advance(2000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('刷新在途时未展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        // 服务端确认 canShow=true。
        gateResolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: 'snoozed',
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('服务端确认后进入 SDK', h.adapter.calls.getSurveys.length, 1);
        eq('SDK 流程只启动一次', h.adapter.calls.init.length, 1);
        eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
        // 重复事件不启动第二次。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('重复事件不启动第二次 Survey 流程', h.adapter.calls.getSurveys.length, 1);
    });
}

function testTriggerDestroyDuringFlow() {
    // 触发流程中（权威刷新在途）destroy：迟到响应不重新展示、不加载 SDK、
    // 无残留 timer；destroy 后重新 init 再触发可正常展示。
    let getCalls = 0;
    let gateResolve = null;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            return new Promise(resolve => { gateResolve = resolve; });
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(2000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('刷新在途：未展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        // 迟到响应到达：不重新展示。
        gateResolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: 'snoozed',
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('迟到响应不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('迟到响应不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('无残留定时器', h.timers.pending().length, 0);
        // destroy 后重新 init 再触发：gen2 的服务端 GET 在途，释放后正常展示。
        h.api.init(reinitOptions(h));
        const promise = h.api.open();
        return waitForFlush().then(() => {
            gateResolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: null,
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            });
            return promise.then(() => waitForFlush());
        });
    }).then(() => {
        eq('destroy 后重新 init 的 open 不受影响', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testTriggerProtocolInvalidFailClosed() {
    // 触发后权威刷新返回非法视图：不加载 SDK、不展示；一次性标记已消耗，
    // 重复事件不再重试；手动 open 不被破坏。
    let getCalls = 0;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            // 非法视图（available=false）。
            return {ok: true, json: () => Promise.resolve({available: false})};
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(2000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('INVALID 不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('INVALID 不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        // 一次性标记已消耗：重复事件不重试。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('INVALID 后不无限重试', h.adapter.calls.getSurveys.length, 0);
        // 手动 open 不受影响。
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('手动 open 仍可用', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testTriggerUnavailableFailOpen() {
    // 服务端暂时不可用 + 本地无阻断状态：触发时允许进入 SDK（fail-open）；
    // 本地存在阻断状态：不进入 SDK、不展示。
    return Promise.resolve().then(() => {
        let getCalls = 0;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1) {
                    return {
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 0,
                            status: 'snoozed',
                            canShow: false,
                            retryAfterMs: 1000,
                            seenLayouts: LAYOUT_IDS.slice()
                        }))
                    };
                }
                return Promise.reject(new Error('network down'));
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(2000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('unavailable 本地无阻断：允许进入 SDK', h.adapter.calls.getSurveys.length, 1);
            eq('只启动一次', h.adapter.calls.init.length, 1);
            eq('弹窗展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        // 本地存在阻断状态（另一标签页写入 submitted fallback）：不进入 SDK。
        let getCalls = 0;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1) {
                    return {
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 0,
                            status: 'snoozed',
                            canShow: false,
                            retryAfterMs: 1000,
                            seenLayouts: LAYOUT_IDS.slice()
                        }))
                    };
                }
                return Promise.reject(new Error('network down'));
            }
        });
        return waitForServerContext(h).then(() => {
            // 另一标签页刚写入 submitted：storage 事件合并进 pendingLocalState。
            h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
            return waitForFlush();
        }).then(() => {
            h.timers.advance(2000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('本地阻断：不进入 SDK', h.adapter.calls.getSurveys.length, 0);
            eq('本地阻断：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testTriggerOldGenerationIsolation() {
    // generation 1 触发流程（权威刷新在途）；destroy → generation 2 init →
    // generation 2 触发；释放 generation 1 的旧 refresh 迟到响应：不得影响
    // generation 2 的流程（不展示、不加载 SDK、无新 warning）；
    // 完成 generation 2 后只启动一次 SDK / Survey 流程。
    let getCalls = 0;
    let gate1Resolve = null;
    let gate2Resolve = null;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1 || getCalls === 3) {
                // init GET（gen1 / gen2 各一次）：snoozed 阻断视图。
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                });
            }
            if (getCalls === 2) {
                // generation 1 的触发权威刷新：保持 pending。
                return new Promise(resolve => { gate1Resolve = resolve; });
            }
            // getCalls === 4：generation 2 的触发权威刷新：保持 pending。
            return new Promise(resolve => { gate2Resolve = resolve; });
        }
    });
    let gen1 = 0;
    let warningsBefore = 0;
    return waitForServerContext(h).then(() => {
        h.timers.advance(1000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        gen1 = h.api._internals.currentGeneration();
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        eq('generation 2 已初始化（generation 递增）',
            h.api._internals.currentGeneration() > gen1, true);
        h.timers.advance(1000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        warningsBefore = h.consoleWarn.length;
        // 释放 generation 1 的旧 refresh：canShow=true 迟到响应。
        gate1Resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: null,
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('generation 1 不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('generation 1 不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('generation 1 不输出新 warning', h.consoleWarn.length, warningsBefore);
        // 完成 generation 2 的权威刷新。
        gate2Resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: null,
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('generation 2 完成后只启动一次 SDK / Survey 流程', h.adapter.calls.getSurveys.length, 1);
        eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('generation 2 没有启动第二条流程', h.adapter.calls.getSurveys.length, 1);
        eq('只有 generation 1 的请求被取消（abort 恰好一次）', h.serverAbortCalls.length, 1);
    });
}

function testTriggerLateResultsNoSideEffects() {
    // generation 1 触发流程在途时 destroy → re-init → generation 2 触发；
    // generation 1 的旧 refresh 迟到响应按三种 payload 释放（blocked 形 /
    // invalid 形 / started 形），都不得影响 generation 2 的流程：
    // 不展示、不加载 SDK、不输出新 warning；随后完成 generation 2 只启动一次流程。
    const buildHarness = () => {
        let getCalls = 0;
        let gate1Resolve = null;
        let gate2Resolve = null;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1 || getCalls === 3) {
                    return Promise.resolve({
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 0,
                            status: 'snoozed',
                            canShow: false,
                            retryAfterMs: 1000,
                            seenLayouts: LAYOUT_IDS.slice()
                        }))
                    });
                }
                if (getCalls === 2) {
                    return new Promise(resolve => { gate1Resolve = resolve; });
                }
                return new Promise(resolve => { gate2Resolve = resolve; });
            }
        });
        return {h, gate1: () => gate1Resolve, gate2: () => gate2Resolve};
    };
    const runCase = (lateView) => {
        const built = buildHarness();
        const h = built.h;
        let warningsBefore = 0;
        return waitForServerContext(h).then(() => {
            h.timers.advance(1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.api.destroy();
            return waitForFlush();
        }).then(() => {
            h.api.init(reinitOptions(h));
            return waitForFlush();
        }).then(() => {
            h.timers.advance(1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            warningsBefore = h.consoleWarn.length;
            built.gate1()(lateView);
            return waitForFlush();
        }).then(() => {
            eq('迟到响应不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('迟到响应不加载 SDK', h.adapter.calls.getSurveys.length, 0);
            eq('迟到响应不输出新 warning', h.consoleWarn.length, warningsBefore);
            built.gate2()({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 1,
                    status: null,
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            });
            return waitForFlush();
        }).then(() => {
            eq('generation 2 完成后只启动一次流程', h.adapter.calls.getSurveys.length, 1);
            eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    };
    const lateViews = [
        // A. blocked 形：迟到 blocked 不得影响 generation 2。
        {
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1, status: 'snoozed', canShow: false, retryAfterMs: 7000,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        },
        // B. invalid 形：迟到 invalid 不得影响 generation 2。
        {ok: true, json: () => Promise.resolve({available: false})},
        // D. started 形：迟到 opened/started 不得影响 generation 2。
        {
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1, status: null, canShow: true, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        }
    ];
    return lateViews.reduce((chain, view) => chain.then(() => runCase(view)),
        Promise.resolve());
}

function testRevisionSafeIntegerBoundary() {
    // revision 边界：Number.MAX_SAFE_INTEGER 合法；
    // MAX_SAFE_INTEGER+1 / 非整数 / Infinity / NaN / string → VIEW_INVALID。
    const MAX_SAFE = Number.MAX_SAFE_INTEGER;
    const withRevision = (revision) => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({revision})
        });
        return h.api.open().then(() => waitForFlush()).then(() => h);
    };
    const usesFallbackIdentity = h => /^ps_[0-9a-f]{64}$/.test(
        h.adapter.sdkConfig().bootstrap.distinctID);
    return withRevision(MAX_SAFE).then(h => {
        ok('revision=Number.MAX_SAFE_INTEGER 合法',
            !!(h.adapter.sdkConfig() && h.adapter.sdkConfig().bootstrap));
    }).then(() => withRevision(MAX_SAFE + 1)).then(h => {
        eq('revision=MAX_SAFE_INTEGER+1 → VIEW_INVALID', usesFallbackIdentity(h), true);
    }).then(() => withRevision(1.5)).then(h => {
        eq('revision=非整数 → VIEW_INVALID', usesFallbackIdentity(h), true);
    }).then(() => withRevision(Infinity)).then(h => {
        eq('revision=Infinity → VIEW_INVALID', usesFallbackIdentity(h), true);
    }).then(() => withRevision(NaN)).then(h => {
        eq('revision=NaN → VIEW_INVALID', usesFallbackIdentity(h), true);
    }).then(() => withRevision('5')).then(h => {
        eq('revision=string → VIEW_INVALID', usesFallbackIdentity(h), true);
    });
}

function testRetryAfterMsSafeIntegerBoundary() {
    // retryAfterMs 边界：0 / 1 / Number.MAX_SAFE_INTEGER 合法（合法最大值进入
    // safeClientTimeAdd 后仍为安全整数，不视为协议错误）；
    // MAX_SAFE_INTEGER+1 / 1.5 / -1 / Infinity / NaN / '1000' → VIEW_INVALID；
    // 非法响应不得修改 serverRevision / serverLocalBlockUntil、不启动 SDK。
    const MAX_SAFE = Number.MAX_SAFE_INTEGER;
    const viewFor = (retryAfterMs) => retryAfterMs === 0
        ? {status: null, canShow: true, retryAfterMs: 0, seenLayouts: LAYOUT_IDS.slice()}
        : {status: 'snoozed', canShow: false, retryAfterMs, seenLayouts: LAYOUT_IDS.slice()};
    const withRetryAfter = (retryAfterMs, manualOpen) => {
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverState: serverStateResponse(Object.assign({revision: 3}, viewFor(retryAfterMs)))
        });
        return (manualOpen ? h.api.open().then(() => waitForFlush()) : waitForServerContext(h))
            .then(() => h);
    };
    return withRetryAfter(0, true).then(h => {
        eq('retryAfterMs=0 合法（status=null + canShow=true）', h.adapter.sdkConfig() !== null, true);
        ok('合法视图应用 bootstrap 身份',
            !!(h.adapter.sdkConfig().bootstrap && h.adapter.sdkConfig().bootstrap.distinctID));
        eq('retryAfterMs=0 不产生阻断截止', h.api._internals.serverLocalBlockUntil(), 0);
    }).then(() => withRetryAfter(1, true)).then(h => {
        eq('retryAfterMs=1 合法', h.adapter.sdkConfig() !== null, true);
        eq('serverRevision 已应用', h.api._internals.currentServerRevision(), 3);
        eq('serverLocalBlockUntil = now + 1', h.api._internals.serverLocalBlockUntil(), 1000000 + 1);
    }).then(() => withRetryAfter(MAX_SAFE, true)).then(h => {
        eq('retryAfterMs=Number.MAX_SAFE_INTEGER 合法', h.adapter.sdkConfig() !== null, true);
        const until = h.api._internals.serverLocalBlockUntil();
        ok('合法最大值进入 safeClientTimeAdd 后仍为安全整数',
            Number.isSafeInteger(until) && until >= 0 && until <= MAX_SAFE);
    }).then(() => withRetryAfter(MAX_SAFE + 1, false)).then(h => {
        eq('retryAfterMs=MAX_SAFE_INTEGER+1 → VIEW_INVALID', h.adapter.sdkConfig(), null);
        eq('非法响应不修改 serverRevision', h.api._internals.currentServerRevision(), 0);
        eq('非法响应不修改 serverLocalBlockUntil', h.api._internals.serverLocalBlockUntil(), 0);
        eq('非法响应不启动 SDK', h.adapter.calls.init.length, 0);
    }).then(() => withRetryAfter(1.5, false)).then(h => {
        eq('retryAfterMs=1.5 → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter(-1, false)).then(h => {
        eq('retryAfterMs=-1 → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter(Infinity, false)).then(h => {
        eq('retryAfterMs=Infinity → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter(NaN, false)).then(h => {
        eq('retryAfterMs=NaN → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter('1000', false)).then(h => {
        eq("retryAfterMs='1000' → VIEW_INVALID", h.adapter.sdkConfig(), null);
    });
}

runTests('pixiv-layout-feedback-trigger.test.js', [
    ['testTriggerAfterServerSnoozeDeadline', testTriggerAfterServerSnoozeDeadline],
    ['testTriggerDestroyDuringFlow', testTriggerDestroyDuringFlow],
    ['testTriggerProtocolInvalidFailClosed', testTriggerProtocolInvalidFailClosed],
    ['testTriggerUnavailableFailOpen', testTriggerUnavailableFailOpen],
    ['testTriggerOldGenerationIsolation', testTriggerOldGenerationIsolation],
    ['testTriggerLateResultsNoSideEffects', testTriggerLateResultsNoSideEffects],
    ['testRevisionSafeIntegerBoundary', testRevisionSafeIntegerBoundary],
    ['testRetryAfterMsSafeIntegerBoundary', testRetryAfterMsSafeIntegerBoundary]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
