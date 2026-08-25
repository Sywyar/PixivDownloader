'use strict';

/** revision 单调性、并发命令与超时隔离。 */
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

function testSnapshotRevisionMonotonic() {
    // A. 先应用 revision=2 submitted；再送达 revision=1 空状态（低 revision 迟到响应）。
    // 不设置 batchLayout：避免 init 的 record_seen 触发 400ms flush 推进 revision。
    const h = initHarness({
        serverState: serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        })
    });
    return waitForServerContext(h).then(() => {
        eq('初始视图已应用（revision=2）', h.api._internals.currentServerRevision(), 2);
        // 另一标签页写入 submitted fallback → 当前标签页合并进 pending
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('storage 事件合并后 pending 生效', h.api._internals.effectiveState().status, 'submitted');
        // 服务器 refresh 返回低 revision 空状态：STALE，不得覆盖
        h.setServerState(serverStateResponse({revision: 1, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []}));
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('低 revision 不覆盖：serverRevision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('effectiveState 仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('STATE_KEY 仍 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('pending 不被 STALE 响应清理', h.api._internals.effectiveState().status, 'submitted');
    });
}

function testSnapshotSameRevisionPersistentContent() {
    // B. 同 revision 持久化字段（status）不同 → INVALID 拒绝；
    // 同 revision 完全相同 → SAME 无副作用。
    const h = initHarness({
        serverState: serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        })
    });
    let warnsBefore = 0;
    return waitForServerContext(h).then(() => {
        h.setServerState(serverStateResponse({
            revision: 2,
            status: null,
            canShow: true,
            retryAfterMs: 0,
            seenLayouts: []
        }));
        warnsBefore = h.consoleWarn.length;
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('同 revision status 不同被拒绝：serverRevision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('不覆盖：effectiveState 仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('不覆盖：STATE_KEY 仍 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        const newWarns = h.consoleWarn.slice(warnsBefore);
        ok('记录安全 warning', JSON.stringify(newWarns).indexOf('conflicting content for the same revision') >= 0);
        const warnText = JSON.stringify(newWarns);
        ok('warning 不含 token', warnText.indexOf(h.config.projectToken) < 0);
        ok('warning 不含 Survey ID', warnText.indexOf(h.config.surveyId) < 0);
        ok('warning 不含 scoped ID', warnText.indexOf('plf_') < 0);
    }).then(() => {
        // 同 revision 完全相同 → SAME：无副作用、无新 warning
        const h2 = initHarness({
            serverState: serverStateResponse({
                revision: 2,
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: []
            })
        });
        return waitForServerContext(h2).then(() => {
            const warnsBefore = h2.consoleWarn.length;
            h2.setServerState(serverStateResponse({
                revision: 2,
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: []
            }));
            h2.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
            return waitForFlush();
        }).then(() => {
            eq('SAME：revision 不变', h2.api._internals.currentServerRevision(), 2);
            eq('SAME：无新 warning', h2.consoleWarn.length, warnsBefore);
            eq('SAME：effectiveState 不变', h2.api._internals.effectiveState().status, 'submitted');
        });
    });
}

function testSameRevisionDynamicViewUpdate() {
    // C. 同 revision 只有动态字段（canShow / retryAfterMs）变化：
    // - retryAfterMs 递减（snoozed canShow=false）→ 合法 VIEW_UPDATED；
    // - snoozed 从 canShow=false 变为 canShow=true（服务端到期）→ 合法 VIEW_UPDATED。
    return Promise.resolve().then(() => {
        const h = initHarness({
            serverFetch: refreshWith(
                snoozedView(20 * 60 * 1000, {revision: 2}),
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 10 * 60 * 1000,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => {
            eq('初始 retryAfterMs 20 分钟', h.api._internals.serverRetryAfterMs(), 20 * 60 * 1000);
            eq('serverLocalBlockUntil = now + 20 分钟', h.api._internals.serverLocalBlockUntil(),
                1000000 + 20 * 60 * 1000);
            return directRefresh(h).then(result => {
                eq('retryAfterMs 递减 → fresh', result.status, 'fresh');
                eq('retryAfterMs 递减 → viewResult=updated', result.viewResult, 'updated');
                eq('revision 不变', h.api._internals.currentServerRevision(), 2);
                eq('动态字段已更新：retryAfterMs 10 分钟', h.api._internals.serverRetryAfterMs(), 10 * 60 * 1000);
                eq('serverLocalBlockUntil 已更新', h.api._internals.serverLocalBlockUntil(),
                    1000000 + 10 * 60 * 1000);
                eq('有效状态仍为 snoozed（本地截止时间更新）',
                    h.api._internals.effectiveState().status, 'snoozed');
                eq('无新 warning', h.consoleWarn.length, 0);
            });
        });
    }).then(() => {
        // 服务端到达 snoozedUntil：canShow=false → true，retryAfterMs=0。
        const h = initHarness({
            serverFetch: refreshWith(
                snoozedView(20 * 60 * 1000, {revision: 2}),
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    status: 'snoozed',
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => {
            return directRefresh(h).then(result => {
                eq('snoozed canShow false→true → fresh', result.status, 'fresh');
                eq('snoozed canShow false→true → viewResult=updated', result.viewResult, 'updated');
                eq('revision 不变', h.api._internals.currentServerRevision(), 2);
                eq('canShow 已更新', h.api._internals.serverCanShow(), true);
                eq('retryAfterMs 清零', h.api._internals.serverRetryAfterMs(), 0);
                eq('有效状态不再阻断（服务端已到期）', h.api._internals.effectiveState(), null);
                const localState = JSON.parse(h.storage.getItem(STATE_KEY));
                eq('本地 snooze 被清理（服务端已到期）', localState, null);
            });
        });
    });
}

function testLateResponseAfterCommandTimeout() {
    // D. server command 超时完成后再触发其 HTTP 响应：无副作用。
    let release = null;
    let warnsBefore = 0;
    let warnsAfterTimeout = 0;
    const h = initHarness({
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: () => new Promise(resolve => {
            release = () => resolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 9,
                    status: 'submitted',
                    canShow: false,
                    retryAfterMs: 0,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            });
        })
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        warnsBefore = h.consoleWarn.length;
        h.timers.advance(4000);
        return waitForFlush();
    }).then(() => {
        ok('超时产生失败 warning', h.consoleWarn.length > warnsBefore);
        warnsAfterTimeout = h.consoleWarn.length;
        release();
        return waitForFlush();
    }).then(() => {
        eq('迟到响应不推进 revision', h.api._internals.currentServerRevision(), 0);
        eq('迟到响应不修改 state', h.api._internals.effectiveState().status, 'never');
        eq('迟到响应不修改 localStorage', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        eq('迟到响应不写 seen', JSON.parse(h.storage.getItem(SEEN_KEY)) !== null, true);
        ok('迟到响应无新 warning', h.consoleWarn.length === warnsAfterTimeout);
        eq('operation 集合为空', h.api._internals.serverCommandOperations.size, 0);
    });
}

function testLateResponseAfterRefreshTimeout() {
    // E. refresh GET 超时后迟到响应：不 apply / 不 sync / 不修改 serverRevision。
    const h = initHarness({
        serverFetch: 'pending',
        serverState: serverStateResponse({
            revision: 2, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
        })
    });
    return waitForFlush().then(() => {
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        }))});
        return waitForFlush();
    }).then(() => {
        eq('初始视图已应用', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        // storage 事件触发 refresh：第二次 GET 进入 pending
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        h.timers.advance(3000);
        return waitForFlush();
    }).then(() => {
        // 迟到响应携带高 revision 的 never
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(serverStateResponse({
            revision: 9,
            status: 'never',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        }))});
        return waitForFlush();
    }).then(() => {
        eq('迟到 refresh 不修改 revision', h.api._internals.currentServerRevision(), 2);
        eq('迟到 refresh 不修改 state', h.api._internals.effectiveState().status, 'submitted');
        eq('迟到 refresh 不同步 localStorage', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testOutOfOrderCommandResponses() {
    // D. record_seen 的 revision 1 响应延迟；submitted 命令先以 revision 2 成功；
    // 最后 record_seen 的 revision 1 响应到达：低 revision 无副作用。
    let releaseRecordSeen = null;
    let submittedCount = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: ({body}) => {
            if (body.command === 'record_seen') {
                return new Promise(resolve => {
                    releaseRecordSeen = () => resolve({
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 1,
                            status: null,
                            canShow: true,
                            retryAfterMs: 0,
                            seenLayouts: []
                        }))
                    });
                });
            }
            if (body.command === 'submitted') {
                submittedCount++;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 2,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: ['pixiv-batch-portrait']
                    }))
                };
            }
            return undefined;
        }
    });
    return waitForServerContext(h).then(() => {
        // reconcileSeen 因本地 landscape fallback 发出第一个 record_seen（gated）；
        // 释放它（rev1 空状态）后 loadServerContext 才完成。
        ok('reconcileSeen record_seen 已发出',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length >= 1);
        releaseRecordSeen();
        return waitForFlush();
    }).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(400);
        return waitForFlush();
    }).then(() => {
        eq('submitted 成功后 revision 为 2', h.api._internals.currentServerRevision(), 2);
        eq('本地为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        ok('flush 的 record_seen 已发出且 gated',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length >= 2);
        releaseRecordSeen();
        return waitForFlush();
    }).then(() => {
        eq('迟到 revision 1 不覆盖：revision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('状态仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('本地缓存不被旧视图覆盖', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('localStorage seen 仍保留 portrait', seen && seen['pixiv-batch-portrait']);
        eq('operation 集合为空', h.api._internals.serverCommandOperations.size, 0);
    });
}

function testCrossTabStateFallback() {
    // E. 标签页 A 写 submitted / never / 有效 snoozed；标签页 B 收到 STATE_KEY 后
    // 合并 pending fallback；B 的服务器 GET 返回空状态时 fallback 仍保留。
    return Promise.resolve()
        .then(() => crossTabFallbackMatrix('submitted', 0, 'submitted'))
        .then(() => crossTabFallbackMatrix('never', 0, 'never'))
        .then(() => crossTabFallbackMatrix('snoozed', 2000000, 'snoozed'));
}

function testCrossTabSeenFallback() {
    // F. 标签页 A 写两个布局 seen；标签页 B 收到 SEEN_KEY 后合并 pendingLocalSeen；
    // B 的服务器 GET 返回空 seenLayouts 时 localStorage 不清空、seenCount 不下降。
    const localSeen = {};
    localSeen['pixiv-batch-landscape'] = {firstSeenAt: 1, lastSeenAt: 100};
    localSeen['pixiv-batch-portrait'] = {firstSeenAt: 2, lastSeenAt: 200};
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        h.storage.values.set(SEEN_KEY, JSON.stringify(localSeen));
        h.dispatchStorage(SEEN_KEY, JSON.stringify(localSeen));
        return waitForFlush();
    }).then(() => {
        // init 的 record_seen 去抖 flush 失败 settle；服务器旧 seen 不清理 pending。
        return (function drain(rounds) {
            if (h.timers.pending().length === 0) return Promise.resolve();
            if (rounds > 50) throw new Error('drain runaway');
            h.timers.advance(10000);
            return waitForFlush().then(() => drain(rounds + 1));
        })(0);
    }).then(() => {
        const effective = h.api._internals.effectiveSeen();
        eq('pendingLocalSeen 合并两个布局', h.api._internals.distinctSeenCount(effective), 2);
        const stored = JSON.parse(h.storage.getItem(SEEN_KEY));
        eq('localStorage 不被清空（保留两个布局）', h.api._internals.distinctSeenCount(stored), 2);
        ok('seenCount 达到 2', h.api._internals.distinctSeenCount(effective) >= 2);
    });
}

function testSnoozeStrength() {
    const DAY = 24 * 60 * 60 * 1000;
    return Promise.resolve().then(() => {
        // server snooze 1 天，local snooze 7 天：local 更强，必须回放，pending 不提前清除。
        // 服务端只提供剩余时长（retryAfterMs），本地剩余时长按本地截止时间计算——
        // 只比较两个「剩余时长」，不比较任何绝对时间点。
        const serverRetry = DAY;
        const localUntil = 1000000 + 7 * DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: serverRetry,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('本地更强：回放 snooze 命令', posts.length, 1);
            // 服务端按自己的时钟保存 7 天 snooze；命令确认后本地截止时间由服务端
            // retryAfterMs（7 天）重新生成。
            eq('命令确认后采用服务端剩余时长（本地截止 = clientNow + 7 天）',
                JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, 1000000 + SNOOZE_MS);
        });
    }).then(() => {
        // server snooze 7 天，local snooze 1 天：server 更强，不回放，pending 可清理。
        const serverRetry = 7 * DAY;
        const localUntil = 1000000 + DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: serverRetry,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze'
                || p.body.command === 'never' || p.body.command === 'submitted');
            eq('服务端更强：不回放', posts.length, 0);
            eq('effectiveState 为服务端剩余时长（本地截止 = clientNow + 7 天）',
                h.api._internals.effectiveState().snoozedUntil, 1000000 + 7 * DAY);
            eq('localStorage 覆盖为服务端剩余时长', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil,
                1000000 + 7 * DAY);
        });
    }).then(() => {
        // 本地 snooze 与服务器剩余时长在容差内等价：不回放（服务器已提供至少相同的
        // 阻断效果）。
        const localUntil = 1000000 + 7 * DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: 7 * DAY - 1000,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('服务器剩余时长与本地在容差内：不回放', posts.length, 0);
        });
    });
}

function testConcurrentCommandOperations() {
    // H. 三个并发 operation：A(snooze) → C(never) → B(record_seen) 完成顺序。
    // 完成 A 不删除 B/C；完成 C 不删除 B；B 最终正常完成；Set 最终为空。
    // 服务端 seenLayouts 缺 portrait：切换布局后 portrait 触发 record_seen 去抖 flush。
    const gates = [];
    const h = initHarness({
        serverState: serverStateResponse({
            seenLayouts: ['pixiv-batch-landscape']
        }),
        serverPostResponse: ({body}) => {
            return new Promise(resolve => gates.push(() => resolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: body.command === 'never' ? 2 : 1,
                    status: body.command === 'submitted'
                        ? 'submitted'
                        : body.command === 'never'
                            ? 'never'
                            : body.command === 'snooze'
                                ? 'snoozed'
                                : null,
                    canShow: body.command === 'snooze' ? false : body.command === 'submitted' ? false
                        : body.command === 'never' ? false : true,
                    retryAfterMs: body.command === 'snooze' ? SNOOZE_MS : 0,
                    seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-portrait']
                }))
            })));
        }
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => h.api.open().then(() => waitForFlush())).then(() => {
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(400);
        return waitForFlush();
    }).then(() => {
        eq('三个命令并发在途', h.api._internals.serverCommandOperations.size, 3);
        gates[0]();
        return waitForFlush();
    }).then(() => {
        eq('A 完成不删除 B/C', h.api._internals.serverCommandOperations.size, 2);
        gates[1]();
        return waitForFlush();
    }).then(() => {
        eq('C 完成不删除 B', h.api._internals.serverCommandOperations.size, 1);
        gates[2]();
        return waitForFlush();
    }).then(() => {
        eq('B 最终正常完成', h.api._internals.serverCommandOperations.size, 0);
        eq('Set 最终为空', h.api._internals.serverCommandOperations.size, 0);
    });
}

function testDestroyCancelsInFlightCommands() {
    // H2. A 完成、B/C 在途、destroy：B/C 被 abort、Promise 结束、timeout 清除、
    // 无残留 operation、迟到响应无副作用。
    // 服务端 seenLayouts 缺 portrait：切换布局后 portrait 触发 record_seen 去抖 flush。
    const gates = [];
    let storedBefore = null;
    let seenBefore = null;
    const h = initHarness({
        serverState: serverStateResponse({
            seenLayouts: ['pixiv-batch-landscape']
        }),
        serverPostResponse: () => new Promise(resolve => gates.push(() => resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 5,
                status: null,
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        })))
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => h.api.open().then(() => waitForFlush())).then(() => {
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(400);
        return waitForFlush();
    }).then(() => {
        eq('三个命令在途', h.api._internals.serverCommandOperations.size, 3);
        gates[0]();
        return waitForFlush();
    }).then(() => {
        eq('A 完成后 B/C 仍在途', h.api._internals.serverCommandOperations.size, 2);
        storedBefore = h.storage.getItem(STATE_KEY);
        seenBefore = h.storage.getItem(SEEN_KEY);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无残留 operation', h.api._internals.serverCommandOperations.size, 0);
        eq('destroy 后无残留定时器', h.timers.pending().length, 0);
        // 迟到响应：B/C 的 gate 现在才解析
        gates[1]();
        gates[2]();
        return waitForFlush();
    }).then(() => {
        eq('迟到响应不修改 localStorage', h.storage.getItem(STATE_KEY), storedBefore);
        eq('迟到响应不修改 seen 缓存', h.storage.getItem(SEEN_KEY), seenBefore);
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testGetReconciliationTimeoutSeparation() {
    // I. GET 快速成功；reconciliation POST 在 3500ms 成功：
    // GET timeout(3000) 在进入 reconciliation 前已清除，3 秒时 loadServerContext 未结束，
    // 3.5 秒 POST 成功后才结束；SDK 在此之前不初始化；无并发 recordSeen。
    let release = null;
    let settled = false;
    const h = initHarness({
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                release = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        // seen 与本地 fallback 相同：reconcileSeen 无需再发 record_seen，
                        // 避免默认 mock 的空视图覆盖 submitted。
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                });
            });
        }
    });
    const promise = h.api.open().then(v => { settled = true; return v; });
    return waitForFlush().then(() => {
        eq('决策回放命令已发出', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        h.timers.advance(3000);
        return waitForFlush();
    }).then(() => {
        eq('GET timeout 不覆盖 reconciliation：3 秒时 loadServerContext 未结束', settled, false);
        eq('reconciliation 完成前 SDK 不加载', h.adapter.sdkConfig() === null, true);
        eq('决策 POST 在途时不产生并发 recordSeen',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length, 0);
        h.timers.advance(500);
        release();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('3.5 秒 POST 成功后才结束', settled, true);
        eq('确认后 localStorage 为服务端 submitted（同业务状态保留本地 updatedAt，不复制服务端时间戳）',
            JSON.parse(h.storage.getItem(STATE_KEY)).updatedAt, 100);
        // 手动 open 的弹窗在 reconciliation 完成后正常打开（skipStateGate 不受
        // 服务端 submitted 门禁影响；触发式门禁由其它测试覆盖）。
        eq('手动 open 流程正常完成', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('不会叠加第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testReconciliationCommandTimeoutBounded() {
    // I2. GET 快速成功；reconciliation POST 永不完成：4000ms command timeout 后
    // loadServerContext 有限结束；local fallback 保留；门禁使用 effectiveState；
    // 无永久 pending；无残留 timer。
    let settled = false;
    const h = initHarness({
        // 不设置 batchLayout：避免 init 的 recordSeen 触发 reconcileSeen 的
        // record_seen（也会被 gate），保证只有 decision 命令一个 4000ms timeout。
        storage: {[STATE_KEY]: localStateValue('never')},
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: () => new Promise(() => {})
    });
    const promise = h.api.open().then(v => { settled = true; return v; });
    return waitForFlush().then(() => {
        h.timers.advance(4000);
        return waitForFlush();
    }).then(() => {
        eq('command 超时后 loadServerContext 有限结束', settled, true);
        eq('local fallback 保留', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        eq('门禁使用 effectiveState', h.api._internals.effectiveState().status, 'never');
        // 手动 open 的弹窗（skipStateGate）正常打开。
        eq('手动 open 流程正常完成', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('不叠加第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        // 弹窗焦点定时器（0ms）等一次性定时器已执行，无残留。
        h.timers.advance(0);
        return waitForFlush();
    }).then(() => {
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testStorageWriteDedup() {
    // J. syncServerViewToLocalCache 写入与现有值相同：不重复 setItem，
    // 不产生无意义 storage 协调。
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: []})
    });
    let seenSets = 0;
    return waitForServerContext(h).then(() => {
        seenSets = h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length;
        const stateSets = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        ok('初始同步写入过协调缓存', seenSets >= 1);
        eq('无状态时不写 STATE_KEY（走 remove 路径）', stateSets, 0);
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        // 相同 SEEN_KEY 值（dispatch 未改变 seen）不重复 setItem
        eq('相同 SEEN_KEY 值不重复 setItem',
            h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length, seenSets);
        eq('STATE_KEY 只写一次（值不同才写）',
            h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
        // 再触发一次相同值同步：仍然去重
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('第二次相同值同步不重复写 STATE_KEY',
            h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
        eq('不产生多余 removeItem',
            h.storage.removeCalls.filter(k => k === STATE_KEY).length, 0);
    });
}

runTests('pixiv-layout-feedback-revision.test.js', [
    ['testSnapshotRevisionMonotonic', testSnapshotRevisionMonotonic],
    ['testSnapshotSameRevisionPersistentContent', testSnapshotSameRevisionPersistentContent],
    ['testSameRevisionDynamicViewUpdate', testSameRevisionDynamicViewUpdate],
    ['testLateResponseAfterCommandTimeout', testLateResponseAfterCommandTimeout],
    ['testLateResponseAfterRefreshTimeout', testLateResponseAfterRefreshTimeout],
    ['testOutOfOrderCommandResponses', testOutOfOrderCommandResponses],
    ['testCrossTabStateFallback', testCrossTabStateFallback],
    ['testCrossTabSeenFallback', testCrossTabSeenFallback],
    ['testSnoozeStrength', testSnoozeStrength],
    ['testConcurrentCommandOperations', testConcurrentCommandOperations],
    ['testDestroyCancelsInFlightCommands', testDestroyCancelsInFlightCommands],
    ['testGetReconciliationTimeoutSeparation', testGetReconciliationTimeoutSeparation],
    ['testReconciliationCommandTimeoutBounded', testReconciliationCommandTimeoutBounded],
    ['testStorageWriteDedup', testStorageWriteDedup]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
