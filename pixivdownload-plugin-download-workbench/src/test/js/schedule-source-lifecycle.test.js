'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {
    source,
    manifest,
    harness,
    validInitializer,
    pixivModuleSource
} = require('./schedule-sources-test-support');

test('publication 切换会撤销旧 handler 并丢弃旧凭证写入结果', async () => {
    let resolveOld;
    let oldAborted = false;
    const oldSummary = new Promise(resolve => { resolveOld = resolve; });
    const installers = new Map();
    installers.set('/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
        api.signal.addEventListener('abort', () => { oldAborted = true; });
        api.registerSource('source-a', {
            matches: () => true,
            capture: () => ({params: {generation: 1}}),
            restore: () => ({}),
            summary: () => ({sections: []}),
            bindSavedCredential: () => oldSummary
        });
    }));
    installers.set('/plugins/source-b.js', validInitializer('/plugins/source-b.js', {
        capture: () => ({params: {generation: 2}})
    }));
    const next = source({
        pluginGeneration: 2,
        publicationId: 22,
        activationToken: 'activation-b',
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-b.js'}
    });
    const runtime = harness([manifest(1, [source()]), manifest(2, [next])], installers);
    await runtime.refresh(false);
    const pending = runtime.bindSavedCredential('source-a', 'task-1', {});
    await runtime.refresh(false);
    assert.equal(oldAborted, true);
    assert.equal(runtime.captureForMode('user', {}).params.generation, 2);
    resolveOld({ok: true, status: 'bound'});
    await assert.rejects(pending, /stale/);
});
test('复制 token 但伪造 currentScript 不能注册来源模块', async () => {
    let registrationResult = null;
    const installers = new Map([
        ['/plugins/source-a.js', {
            forgeCurrentScript: true,
            install: runtime => {
                registrationResult = runtime.registerModule('/plugins/source-a.js', () => {});
            }
        }]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);
    assert.equal(registrationResult, false);
    assert.equal(runtime.isAvailable('source-a'), false);
});

test('来源 script 网络永不回调时受控超时且健康模块继续发布', async () => {
    const sourceB = source({
        sourceType: 'source-b', ownerPluginId: 'owner-b', packageId: 'package-b',
        pluginGeneration: 2, publicationId: 22, activationToken: 'activation-b',
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-b.js'}
    });
    const installers = new Map([
        ['/plugins/source-a.js', {never: true}],
        ['/plugins/source-b.js', validInitializer('/plugins/source-b.js')]
    ]);
    const runtime = harness([manifest(1, [source(), sourceB])], installers, {fastTimeout: true});
    await runtime.refresh(false);
    assert.equal(runtime.isAvailable('source-a'), false);
    assert.equal(runtime.isAvailable('source-b'), true);
});

test('refresh 安装期新通知会 dirty 补拉到最新来源 manifest', async () => {
    let releaseFirst;
    const first = new Promise(resolve => { releaseFirst = resolve; });
    const next = source({
        pluginGeneration: 2, publicationId: 22, activationToken: 'activation-b'
    });
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            capture: () => ({params: {generation: 2}})
        })]
    ]);
    const runtime = harness([first, manifest(2, [next])], installers);
    const refreshing = runtime.refresh(false);
    const queued = runtime.refresh(false);
    releaseFirst(manifest(1, [source()]));
    await Promise.all([refreshing, queued]);
    assert.equal(runtime.__test.requestCount, 2);
    assert.equal(runtime.captureForMode('user', {}).params.generation, 2);
});

test('固定凭证 surface 过滤任意动作袋并按完整策略 identity 隔离分组', async () => {
    const installers = new Map();
    installers.set('/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
        api.registerSource('source-a', {
            matches: () => true,
            capture: () => ({params: {generation: 1}}),
            restore: () => ({}),
            summary: () => ({sections: []}),
            credentialActions: () => ({secret: 'must-not-cross'}),
            credentialContribution: () => ({
                supportsCredential: true,
                supportsProxy: false,
                supportsCookie: true,
                savedCredential: 'must-not-cross'
            }),
            credentialPolicyGroups: () => [{
                identity: {
                    ownerPluginId: 'owner-a', policyId: 'policy-a', publicationId: 11,
                    accountKey: 'account-a', suspendReason: 'POLICY', suspendCode: 'INCIDENT_A'
                },
                title: 'Incident A',
                description: 'First incident',
                actions: [{
                    actionId: 'recover-a', label: 'Recover A', tone: 'primary',
                    credential: 'must-not-cross'
                }]
            }, {
                identity: {
                    ownerPluginId: 'owner-a', policyId: 'policy-a', publicationId: 11,
                    accountKey: 'account-a', suspendReason: 'POLICY', suspendCode: 'INCIDENT_B'
                },
                title: 'Incident B',
                description: 'Second incident',
                actions: [{actionId: 'recover-b', label: 'Recover B', tone: 'danger'}]
            }, {
                identity: {
                    ownerPluginId: 'forged-owner', policyId: 'policy-a', publicationId: 11,
                    accountKey: 'account-a', suspendReason: 'POLICY', suspendCode: 'INCIDENT_C'
                },
                title: 'Forged owner',
                description: 'Must be filtered',
                actions: [{actionId: 'forged-owner', label: 'Forged owner'}]
            }, {
                identity: {
                    ownerPluginId: 'owner-a', policyId: 'policy-a', publicationId: 22,
                    accountKey: 'account-a', suspendReason: 'POLICY', suspendCode: 'INCIDENT_D'
                },
                title: 'Stale publication',
                description: 'Must be filtered',
                actions: [{actionId: 'stale-publication', label: 'Stale publication'}]
            }]
        });
    }));
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    assert.equal(runtime.credentialActions, undefined);
    assert.equal(runtime.invokeCredentialAction, undefined);
    const contribution = runtime.credentialContribution('source-a', {});
    assert.equal(contribution.supportsCredential, true);
    assert.equal(contribution.supportsCookie, undefined);
    assert.equal(contribution.savedCredential, undefined);

    const groups = runtime.credentialPolicyGroups([{
        sourceType: 'source-a',
        credentialPolicy: {
            ownerPluginId: 'owner-a', policyId: 'policy-a', publicationId: 11,
            accountKey: 'account-a', bound: true, available: true,
            statusCode: 'PLUGIN_INCIDENT', acknowledgedEventTime: null
        }
    }], {});
    assert.equal(groups.length, 2);
    assert.deepEqual(Array.from(groups, group => group.identity.suspendCode), [
        'INCIDENT_A', 'INCIDENT_B'
    ]);
    assert.notEqual(groups[0].identityKey, groups[1].identityKey);
    assert.equal(groups[0].sourceType, 'source-a');
    assert.equal(groups[0].actions[0].credential, undefined);
});

test('固定凭证策略 action 绑定 publication lease 并拒绝 A→B 晚结果', async () => {
    let releaseAction;
    let actionLease = null;
    const delayedAction = new Promise(resolve => { releaseAction = resolve; });
    const installers = new Map();
    installers.set('/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
        api.registerSource('source-a', {
            matches: () => true,
            capture: () => ({params: {generation: 1}}),
            restore: () => ({}),
            summary: () => ({sections: []}),
            applyCredentialPolicyAction(_request, _context, lease) {
                actionLease = lease;
                return delayedAction;
            }
        });
    }));
    installers.set('/plugins/source-b.js', validInitializer('/plugins/source-b.js', {
        capture: () => ({params: {generation: 2}})
    }));
    const next = source({
        pluginGeneration: 2, publicationId: 22, activationToken: 'activation-b',
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-b.js'}
    });
    const runtime = harness([manifest(1, [source()]), manifest(2, [next])], installers);
    await runtime.refresh(false);
    const pending = runtime.applyCredentialPolicyAction('source-a', {
        identity: {
            ownerPluginId: 'owner-a', policyId: 'policy-a', publicationId: 11,
            accountKey: 'account-a', suspendReason: 'POLICY', suspendCode: 'INCIDENT_A'
        },
        actionId: 'recover',
        parameters: {delay: 15}
    }, {});
    await runtime.refresh(false);
    assert.equal(actionLease.signal.aborted, true);
    assert.equal(actionLease.activationToken, 'activation-a');
    assert.equal(actionLease.isCurrent(), false);
    releaseAction({ok: true, status: 'applied'});
    await assert.rejects(pending, /stale/);
});

test('matches 同步触发 unload 后不得返回旧 entry 或继续误选', async () => {
    let runtime;
    const installers = new Map([
        ['/plugins/source-a.js', value => value.registerModule('/plugins/source-a.js', api => {
            api.registerSource('source-a', {
                matches() {
                    runtime.dispose();
                    return true;
                },
                capture: () => ({params: {source: 'stale'}}),
                restore: () => ({}),
                summary: () => ({sections: []})
            });
        })]
    ]);
    runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);
    assert.equal(runtime.previewForMode('user', {mode: 'user'}), null);
    assert.equal(runtime.isAvailable('source-a'), false);
});

test('同步宿主 API 拒绝 thenable，凭证展示隔离异步违约而固定写入可异步', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
            api.registerSource('source-a', {
                matches: () => true,
                preview: () => Promise.resolve({label: 'late'}),
                capture: () => Promise.resolve({params: {late: true}}),
                restore: () => Promise.resolve({mode: 'user'}),
                summary: () => Promise.resolve({sections: []}),
                fetchLimitMode: () => Promise.resolve('watermark'),
                quickSourceNote: () => Promise.resolve('late'),
                credentialContribution: () => Promise.resolve({supportsCredential: true}),
                bindSavedCredential: () => Promise.resolve({ok: true, status: 'bound'})
            });
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);
    assert.throws(() => runtime.previewForMode('user', {}), /must return synchronously/);
    assert.throws(() => runtime.captureForMode('user', {}), error =>
        error && error.code === 'SCHEDULE_SOURCE_DEFINITION_INVALID'
        && /must return synchronously/.test(error.message));
    assert.throws(() => runtime.restoreTask({sourceType: 'source-a'}, {}), /must return synchronously/);
    assert.throws(() => runtime.summary({sourceType: 'source-a'}, {}), /must return synchronously/);
    assert.throws(() => runtime.fetchLimitMode('source-a', {}, {}), /must return synchronously/);
    assert.throws(() => runtime.quickSourceNote('source-a', {}), /must return synchronously/);
    assert.equal(runtime.credentialContribution('source-a', {}), null);
    const result = await runtime.bindSavedCredential('source-a', 'task-1', {});
    assert.equal(result.ok, true);
    assert.equal(result.status, 'bound');
});

test('matches thenable 被当作违约贡献隔离', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
            api.registerSource('source-a', {
                matches: () => Promise.resolve(true),
                capture: () => ({params: {late: true}}),
                restore: () => ({}),
                summary: () => ({sections: []})
            });
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);
    assert.equal(runtime.previewForMode('user', {}), null);
    assert.throws(() => runtime.captureForMode('user', {}), error =>
        error && error.code === 'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE'
        && /unavailable/.test(error.message));
});

test('模块不能注册 manifest 未声明的来源', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
            api.registerSource('source-b', {
                capture: () => ({}), restore: () => ({}), summary: () => ({})
            });
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);
    assert.equal(runtime.isAvailable('source-a'), false);
    assert.equal(runtime.isAvailable('source-b'), false);
});

test('同一 manifest 会重试此前未成功注册的来源模块', async () => {
    let attempts = 0;
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
            attempts += 1;
            if (attempts < 2) return;
            api.registerSource('source-a', {
                matches: () => true,
                capture: () => ({params: {attempts}}),
                restore: () => ({}),
                summary: () => ({sections: []})
            });
        })]
    ]);
    const same = manifest(1, [source()]);
    const runtime = harness([same, same], installers);

    await runtime.refresh(false);
    assert.equal(runtime.isAvailable('source-a'), false);
    await runtime.refresh(false);
    assert.equal(attempts, 2);
    assert.equal(runtime.captureForMode('user', {}).params.attempts, 2);
});

test('异步 initializer 完成后才原子发布 handler 并在切换时清理', async () => {
    let cleanupCount = 0;
    const installers = new Map();
    installers.set('/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', async api => {
        api.onCleanup(() => { cleanupCount += 1; });
        await Promise.resolve();
        api.registerSource('source-a', {
            matches: () => true,
            capture: () => ({params: {generation: 1}}),
            restore: () => ({}),
            summary: () => ({sections: []})
        });
        return () => { cleanupCount += 1; };
    }));
    installers.set('/plugins/source-b.js', validInitializer('/plugins/source-b.js', {
        capture: () => ({params: {generation: 2}})
    }));
    const next = source({
        pluginGeneration: 2,
        publicationId: 22,
        activationToken: 'activation-b',
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-b.js'}
    });
    const runtime = harness([manifest(1, [source()]), manifest(2, [next])], installers);

    await runtime.refresh(false);
    assert.equal(runtime.captureForMode('user', {}).params.generation, 1);
    await runtime.refresh(false);
    assert.equal(cleanupCount, 2);
    assert.equal(runtime.captureForMode('user', {}).params.generation, 2);
});

test('initializer 失败会立即清理本模块且不发布部分 handler', async () => {
    let cleanupCount = 0;
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', async api => {
            api.onCleanup(() => { cleanupCount += 1; });
            api.registerSource('source-a', {
                capture: () => ({}), restore: () => ({}), summary: () => ({})
            });
            await Promise.resolve();
            throw new Error('initializer failed');
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);

    await runtime.refresh(false);
    assert.equal(cleanupCount, 1);
    assert.equal(runtime.isAvailable('source-a'), false);
});

test('异步 initializer 在失活后返回的 disposer 会立即执行', async () => {
    let releaseInitializer;
    let signalStarted;
    let cleanupCount = 0;
    const started = new Promise(resolve => { signalStarted = resolve; });
    const blocked = new Promise(resolve => { releaseInitializer = resolve; });
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', async () => {
            signalStarted();
            await blocked;
            return () => { cleanupCount += 1; };
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);

    const installing = runtime.refresh(false);
    await started;
    runtime.dispose();
    releaseInitializer();
    await installing;
    assert.equal(cleanupCount, 1);
    assert.equal(runtime.isAvailable('source-a'), false);
});

test('挂起的 initializer 超时只隔离自身且不阻塞健康模块', async () => {
    let cleanupCount = 0;
    const installers = new Map();
    installers.set('/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
        api.onCleanup(() => { cleanupCount += 1; });
        return new Promise(() => {});
    }));
    installers.set('/plugins/source-b.js', validInitializer('/plugins/source-b.js'));
    const sourceB = source({
        sourceType: 'source-b',
        ownerPluginId: 'owner-b',
        packageId: 'package-b',
        pluginGeneration: 2,
        publicationId: 22,
        activationToken: 'activation-b',
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-b.js'}
    });
    const runtime = harness([manifest(1, [source(), sourceB])], installers, {fastTimeout: true});

    await runtime.refresh(false);
    assert.equal(cleanupCount, 1);
    assert.equal(runtime.isAvailable('source-a'), false);
    assert.equal(runtime.isAvailable('source-b'), true);
    runtime.dispose();
});

test('失活后的晚 onCleanup 会立即清理副作用', async () => {
    let releaseInitializer;
    let signalStarted;
    let cleanupCount = 0;
    const started = new Promise(resolve => { signalStarted = resolve; });
    const blocked = new Promise(resolve => { releaseInitializer = resolve; });
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', async api => {
            signalStarted();
            await blocked;
            api.onCleanup(() => { cleanupCount += 1; });
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);

    const installing = runtime.refresh(false);
    await started;
    runtime.dispose();
    releaseInitializer();
    await installing;
    assert.equal(cleanupCount, 1);
    assert.equal(runtime.isAvailable('source-a'), false);
});

test('缺少前端模块时仍保留 descriptor 并进入只读降级', async () => {
    const runtime = harness([
        manifest(1, [source({frontend: null})])
    ], new Map());
    await runtime.refresh(false);
    assert.equal(runtime.descriptor('source-a').sourceType, 'source-a');
    assert.equal(runtime.isAvailable('source-a'), false);
    assert.equal(runtime.summary({sourceType: 'source-a'}, {}), null);
});
