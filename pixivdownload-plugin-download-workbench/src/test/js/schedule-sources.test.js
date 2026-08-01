'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const runtimeSource = fs.readFileSync(path.join(
    __dirname,
    '../../main/resources/static/pixiv-batch/batch-schedule-sources.js'
), 'utf8');
const pixivModuleSource = fs.readFileSync(path.join(
    __dirname,
    '../../main/resources/static/pixiv-batch/pixiv-schedule-sources.js'
), 'utf8');

function source(overrides) {
    return Object.assign({
        sourceType: 'source-a',
        legacyAliases: [],
        ownerPluginId: 'owner-a',
        packageId: 'package-a',
        pluginGeneration: 1,
        publicationId: 11,
        activationToken: 'activation-a',
        definitionSchema: 'example.definition',
        definitionVersion: 1,
        presentation: {
            displayNamespace: 'example',
            displayNameKey: 'source.name',
            descriptionKey: 'source.description',
            iconKey: 'download',
            colorToken: 'blue'
        },
        acquisitionModes: ['user'],
        possibleWorkTypes: ['work-a'],
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-a.js'}
    }, overrides || {});
}

function manifest(revision, sources, epoch) {
    return {epoch: epoch || 'epoch-a', revision, sources};
}

function harness(manifests, installers, options) {
    const responses = manifests.slice();
    let requestCount = 0;
    const listeners = new Map();
    const document = {
        currentScript: null,
        head: null,
        documentElement: null,
        createElement(tag) {
            assert.equal(tag, 'script');
            return {
                dataset: {},
                async: false,
                src: '',
                onload: null,
                onerror: null,
                remove() {}
            };
        }
    };
    const appendChild = script => {
        queueMicrotask(() => {
            const pathname = new URL(script.src, 'http://localhost').pathname;
            const spec = installers.get(pathname);
            if (!spec) {
                script.onerror();
                return;
            }
            if (spec && spec.never) return;
            const installer = typeof spec === 'function' ? spec : spec.install;
            const evaluatedScript = spec && spec.forgeCurrentScript
                ? Object.assign({dataset: {}}, script, {dataset: Object.assign({}, script.dataset)})
                : script;
            document.currentScript = evaluatedScript;
            installer(context.window.PixivBatch.scheduleSources);
            document.currentScript = null;
            script.onload();
        });
    };
    document.head = {appendChild};
    document.documentElement = document.head;
    const context = vm.createContext({
        console: {warn() {}, log() {}, error() {}},
        URL,
        AbortController,
        CustomEvent: class CustomEvent {
            constructor(type, options) {
                this.type = type;
                this.detail = options && options.detail;
            }
        },
        queueMicrotask,
        setTimeout: options && options.fastTimeout
            ? (callback => setTimeout(callback, 0)) : setTimeout,
        clearTimeout,
        fetch: async () => {
            requestCount += 1;
            const body = responses.shift();
            if (!body) throw new Error('unexpected manifest request');
            return {ok: true, status: 200, json: async () => body};
        },
        document,
        window: {
            location: {origin: 'http://localhost'},
            PixivBatch: {},
            dispatchEvent(event) {
                (listeners.get(event.type) || []).forEach(listener => listener(event));
            },
            addEventListener(type, listener) {
                const values = listeners.get(type) || [];
                values.push(listener);
                listeners.set(type, values);
            }
        }
    });
    vm.runInContext(runtimeSource, context, {filename: 'batch-schedule-sources.js'});
    const runtime = context.window.PixivBatch.scheduleSources;
    const exposed = Object.create(runtime);
    exposed.__test = {document, get requestCount() { return requestCount; }};
    return exposed;
}

function validInitializer(moduleUrl, values) {
    return runtime => runtime.registerModule(moduleUrl, api => {
        api.registerSource(api.descriptors[0].sourceType, Object.assign({
            matches: () => true,
            capture: () => ({params: {source: {id: '1'}}}),
            restore: () => ({mode: 'user'}),
            summary: () => ({kind: 'work-a', sections: []})
        }, values || {}));
    });
}

test('来源 manifest 声明的模式与模块共同决定可调用行为', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js')]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    assert.equal(runtime.isAvailable('source-a'), true);
    assert.equal(runtime.previewForMode('search', {mode: 'search'}), null);
    assert.equal(runtime.previewForMode('user', {mode: 'user'}).sourceType, 'source-a');
    assert.deepEqual(
        JSON.parse(JSON.stringify(runtime.captureForMode('user', {mode: 'user'}).params)),
        {source: {id: '1'}}
    );
});

test('取得输入与回灌 helper 绑定 owner publication 并在失活后拒绝调用', async () => {
    let leasedContext = null;
    const restores = [];
    const quickSource = {
        sourceType: 'source-a',
        source: {id: 'quick-1'},
        metadata: ['stable']
    };
    const host = {
        input(mode) {
            assert.equal(mode, 'single-import');
            return 'https://example.invalid/work/456 | title';
        },
        restore(mode, value) {
            restores.push({mode, value});
            return true;
        }
    };
    const install = runtime => runtime.registerModule('/plugins/source-a.js', api => {
        api.registerSource('source-a', {
            matches(context) {
                return context.acquisitionInput('single-import').includes('/456');
            },
            capture(context) {
                leasedContext = context;
                return {params: {input: context.acquisitionInput('single-import')}};
            },
            restore(_task, context) {
                context.restoreAcquisition('single-import', 'restored-value');
                return {mode: 'single-import'};
            },
            summary: () => ({sections: []})
        });
    });
    const installers = new Map([['/plugins/source-a.js', install]]);
    const first = source({acquisitionModes: ['single-import']});
    const second = source({
        acquisitionModes: ['single-import'],
        pluginGeneration: 2,
        publicationId: 22,
        activationToken: 'activation-b'
    });
    const runtime = harness([manifest(1, [first]), manifest(2, [second])], installers);
    const context = {
        mode: 'single-import',
        editingSourceType: 'source-a',
        workTypes: ['work-a'],
        admin: true,
        unknownHostState: {secret: 'must-not-cross'},
        quickSource,
        __scheduleAcquisitionHost: host
    };

    await runtime.refresh(false);
    const captured = runtime.captureForMode('single-import', context);
    assert.equal(captured.params.input, 'https://example.invalid/work/456 | title');
    assert.equal(Object.prototype.hasOwnProperty.call(leasedContext,
        '__scheduleAcquisitionHost'), false);
    assert.deepEqual(Object.keys(leasedContext).sort(), [
        'acquisitionInput', 'mode', 'quickSource', 'restoreAcquisition'
    ]);
    assert.notEqual(leasedContext.quickSource, quickSource);
    assert.deepEqual(JSON.parse(JSON.stringify(leasedContext.quickSource)), quickSource);
    assert.equal(Object.isFrozen(leasedContext.quickSource), true);
    assert.equal(Object.isFrozen(leasedContext.quickSource.source), true);
    assert.equal(Object.isFrozen(leasedContext.quickSource.metadata), true);
    assert.throws(() => { leasedContext.quickSource.source.id = 'forged'; },
        /read only|Cannot assign/i);
    assert.throws(() => leasedContext.quickSource.metadata.push('forged'),
        /not extensible|Cannot add property/i);
    assert.equal(quickSource.source.id, 'quick-1');
    assert.throws(() => leasedContext.acquisitionInput('search'),
        /acquisition mode is unavailable/);
    assert.deepEqual(JSON.parse(JSON.stringify(runtime.restoreTask({
        sourceType: 'source-a'
    }, context))), {mode: 'single-import'});
    assert.deepEqual(restores, [{mode: 'single-import', value: 'restored-value'}]);

    const cyclicQuickSource = {sourceType: 'source-a'};
    cyclicQuickSource.self = cyclicQuickSource;
    runtime.captureForMode('single-import', Object.assign({}, context, {
        quickSource: cyclicQuickSource
    }));
    assert.equal(leasedContext.quickSource, null);

    const oldContext = leasedContext;
    await runtime.refresh(false);
    assert.throws(() => oldContext.acquisitionInput('single-import'), /stale/);
    assert.throws(() => oldContext.restoreAcquisition('single-import', 'late'), /stale/);
    assert.equal(restores.length, 1);
});

test('来源模块返回无效任务定义时提供稳定错误码', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            capture: () => null
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    assert.throws(() => runtime.captureForMode('user', {}), error =>
        error && error.code === 'SCHEDULE_SOURCE_DEFINITION_INVALID'
        && /invalid definition/.test(error.message));
});

test('抓取上限展示只投影受控 i18n token 并拒绝嵌套 thenable', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            preview: () => ({
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: {
                    namespace: 'example',
                    watermarkHintKey: 'schedule.fetch.watermark',
                    perRunHintKey: 'schedule.fetch.per-run',
                    fullFetchConfirmKey: 'schedule.fetch.confirm',
                    ignoredText: 'not projected'
                }
            }),
            capture: () => ({
                params: {source: {id: '1'}},
                fetchLimitMode: 'per-run',
                fetchLimitPresentation: {
                    namespace: 'example',
                    watermarkHintKey: 'schedule.fetch.watermark',
                    perRunHintKey: 'schedule.fetch.per-run',
                    fullFetchConfirmKey: 'schedule.fetch.confirm',
                    ignoredText: 'not projected'
                }
            })
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    const preview = runtime.previewForMode('user', {});
    assert.equal(preview.fetchLimitMode, 'watermark');
    assert.deepEqual(JSON.parse(JSON.stringify(preview.fetchLimitPresentation)), {
        namespace: 'example',
        watermarkHintKey: 'schedule.fetch.watermark',
        perRunHintKey: 'schedule.fetch.per-run',
        fullFetchConfirmKey: 'schedule.fetch.confirm'
    });
    const captured = runtime.captureForMode('user', {});
    assert.equal(captured.fetchLimitMode, 'per-run');
    assert.deepEqual(JSON.parse(JSON.stringify(captured.fetchLimitPresentation)), {
        namespace: 'example',
        watermarkHintKey: 'schedule.fetch.watermark',
        perRunHintKey: 'schedule.fetch.per-run',
        fullFetchConfirmKey: 'schedule.fetch.confirm'
    });

    const invalidInstallers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            preview: () => ({
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: Promise.reject(new Error('rejected preview presentation'))
            }),
            capture: () => ({
                params: {},
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: Promise.reject(
                    new Error('rejected capture presentation'))
            })
        })]
    ]);
    const invalid = harness([manifest(1, [source()])], invalidInstallers);
    await invalid.refresh(false);
    assert.equal(invalid.previewForMode('user', {}).fetchLimitPresentation, null);
    assert.equal(invalid.captureForMode('user', {}).fetchLimitPresentation, null);
    await new Promise(resolve => setImmediate(resolve));

    const mismatchedInstallers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            preview: () => ({
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: {
                    namespace: 'another-plugin', fullFetchConfirmKey: 'schedule.fetch.confirm'
                }
            })
        })]
    ]);
    const mismatched = harness([manifest(1, [source()])], mismatchedInstallers);
    await mismatched.refresh(false);
    assert.equal(mismatched.previewForMode('user', {}).fetchLimitPresentation, null);
});

test('旧来源别名在描述、回灌、摘要、凭据与 activation lease 上统一归一化', async () => {
    let credentialInvocation = null;
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
            api.registerSource('source-a', {
                matches: () => true,
                capture: () => ({params: {captured: true}}),
                restore: task => ({restoredFrom: task.sourceType}),
                summary: task => ({summaryFrom: task.sourceType}),
                bindCredential: (taskId, value, _context, lease) => {
                    lease.assertCurrent();
                    credentialInvocation = {
                        taskId, value, sourceType: lease.sourceType,
                        activationToken: lease.activationToken
                    };
                    return {ok: true, status: 'bound'};
                }
            });
        })]
    ]);
    const runtime = harness([
        manifest(1, [source({legacyAliases: ['SOURCE_A']})])
    ], installers);
    await runtime.refresh(false);

    assert.equal(runtime.descriptor('SOURCE_A').sourceType, 'source-a');
    assert.equal(runtime.isAvailable('SOURCE_A'), true);
    assert.equal(runtime.activationToken('SOURCE_A'), 'activation-a');
    assert.equal(runtime.activationLease('SOURCE_A').sourceType, 'source-a');
    assert.equal(runtime.activationLease('SOURCE_A').activationToken, 'activation-a');
    assert.equal(runtime.previewForMode('user', {editingSourceType: 'SOURCE_A'}).sourceType, 'source-a');
    assert.equal(runtime.captureForMode('user', {editingSourceType: 'SOURCE_A'}).sourceType, 'source-a');
    assert.equal(runtime.restoreTask({sourceType: 'SOURCE_A'}, {}).restoredFrom, 'SOURCE_A');
    assert.equal(runtime.summary({sourceType: 'SOURCE_A'}, {}).summaryFrom, 'SOURCE_A');
    const credentialResult = await runtime.bindCredential('SOURCE_A', 7, 'ok', {});
    assert.equal(credentialResult.ok, true);
    assert.equal(credentialResult.status, 'bound');
    assert.deepEqual(JSON.parse(JSON.stringify(credentialInvocation)), {
        taskId: 7, value: 'ok', sourceType: 'source-a', activationToken: 'activation-a'
    });
});

test('来源选择先按作品类型收窄并对无上下文的多重匹配拒绝歧义', async () => {
    const sourceA = source({
        sourceType: 'a-source', acquisitionModes: ['user', 'quick'],
        possibleWorkTypes: ['work-a'], frontend: {contractVersion: 1, moduleUrl: '/plugins/a-source.js'}
    });
    const sourceB = source({
        sourceType: 'z-source', ownerPluginId: 'owner-b', packageId: 'package-b',
        publicationId: 22, activationToken: 'activation-b',
        acquisitionModes: ['user', 'quick'], possibleWorkTypes: ['work-b'],
        frontend: {contractVersion: 1, moduleUrl: '/plugins/z-source.js'}
    });
    const installers = new Map([
        ['/plugins/a-source.js', validInitializer('/plugins/a-source.js', {
            capture: () => ({params: {selected: 'a-source'}})
        })],
        ['/plugins/z-source.js', validInitializer('/plugins/z-source.js', {
            capture: () => ({params: {selected: 'z-source'}})
        })]
    ]);
    const runtime = harness([manifest(1, [sourceA, sourceB])], installers);
    await runtime.refresh(false);

    assert.equal(runtime.captureForMode('user', {workTypes: ['work-b']}).params.selected, 'z-source');
    assert.throws(() => runtime.captureForMode('user', {}), error =>
        error && error.code === 'SCHEDULE_SOURCE_EDITOR_AMBIGUOUS'
        && /ambiguous/.test(error.message));
    assert.equal(runtime.captureForMode('quick', {
        quickSource: {sourceType: 'z-source'}
    }).params.selected, 'z-source');
});

test('页面快捷取得码会规范化为中性 quick 契约码', async () => {
    const quickSource = source({
        acquisitionModes: ['quick'],
        possibleWorkTypes: ['work-a']
    });
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            capture: context => ({params: {
                selected: context.quickSource.sourceType,
                pageMode: context.mode
            }})
        })]
    ]);
    const runtime = harness([manifest(1, [quickSource])], installers);
    await runtime.refresh(false);

    const captured = runtime.captureForMode('quick-fetch', {
        mode: 'quick-fetch',
        workTypes: ['work-a'],
        quickSource: {sourceType: 'source-a'}
    });
    assert.equal(captured.sourceType, 'source-a');
    assert.deepEqual(JSON.parse(JSON.stringify(captured.params)), {
        selected: 'source-a',
        pageMode: 'quick-fetch'
    });
});

test('来源别名与其它 canonical 来源冲突时拒绝整个 manifest', async () => {
    const sourceB = source({
        sourceType: 'source-b', ownerPluginId: 'owner-b', packageId: 'package-b',
        publicationId: 22, activationToken: 'activation-b', frontend: null
    });
    const runtime = harness([
        manifest(1, [source({legacyAliases: ['source-b'], frontend: null}), sourceB])
    ], new Map());
    await assert.rejects(runtime.refresh(false), /conflicting schedule source alias/);
});

test('同一 owner publication 的来源模块拒绝不一致 activation token', async () => {
    const sourceB = source({
        sourceType: 'source-b',
        activationToken: 'activation-b'
    });
    const runtime = harness([manifest(1, [source(), sourceB])], new Map());
    await runtime.refresh(false);
    assert.equal(runtime.isAvailable('source-a'), false);
    assert.equal(runtime.isAvailable('source-b'), false);
    assert.equal(runtime.descriptor('source-a').sourceType, 'source-a');
});

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

test('Pixiv 来源模块只经固定凭证贡献面注册并在插件内部读取已保存凭证', async () => {
    let initializer = null;
    const requests = [];
    const context = vm.createContext({
        BASE: '',
        AbortController,
        bt: (_key, fallback) => fallback,
        getCookieInputHeaderString: () => 'PHPSESSID=42_secret',
        fetch(url, init) {
            requests.push({url, init});
            return Promise.resolve({ok: true});
        },
        window: {
            PixivBatch: {
                scheduleSources: {
                    registerModule(moduleUrl, value) {
                        assert.equal(moduleUrl, '/pixiv-batch/pixiv-schedule-sources.js');
                        initializer = value;
                        return true;
                    }
                }
            }
        }
    });
    vm.runInContext(pixivModuleSource, context, {filename: 'pixiv-schedule-sources.js'});
    assert.equal(typeof initializer, 'function');

    const sourceTypes = [
        'user-new', 'user-request', 'search', 'series',
        'my-bookmarks', 'follow-latest', 'collection'
    ];
    const contributions = new Map();
    const apiSignal = new AbortController().signal;
    initializer({
        descriptors: sourceTypes.map(sourceType => ({sourceType})),
        signal: apiSignal,
        assertActive() {},
        registerSource(sourceType, contribution) {
            contributions.set(sourceType, contribution);
        }
    });
    assert.deepEqual(Array.from(contributions.keys()).sort(), sourceTypes.slice().sort());
    contributions.forEach(value => {
        assert.equal(typeof value.capture, 'function');
        assert.equal(typeof value.restore, 'function');
        assert.equal(typeof value.summary, 'function');
        assert.equal(typeof value.credentialContribution, 'function');
        assert.equal(typeof value.bindSavedCredential, 'function');
        assert.equal(typeof value.credentialPolicyGroups, 'function');
        assert.equal(typeof value.applyCredentialPolicyAction, 'function');
        assert.equal(value.credentialActions, undefined);
    });
    assert.doesNotMatch(pixivModuleSource,
        /cookieMode|cookieBound|accountId|ackWarningTime|sourceOwnerPluginId|task\.lastStatus/);
    assert.match(pixivModuleSource, /schedule\.pixiv\.fetch-limit\.hint\.watermark/);
    assert.match(pixivModuleSource, /schedule\.pixiv\.fetch-limit\.hint\.per-run/);
    assert.match(pixivModuleSource, /schedule\.pixiv\.confirm\.full-fetch/);
    assert.match(pixivModuleSource, /selectSeriesDataSource\('pixiv'\)/);

    const credentialLease = {
        sourceType: 'user-new',
        ownerPluginId: 'pixivdownload.plugin.download-workbench',
        packageId: 'pixivdownload-plugin-download-workbench',
        pluginGeneration: 1,
        publicationId: 11,
        activationToken: 'activation-pixiv',
        signal: apiSignal,
        isCurrent: () => true,
        assertCurrent() {}
    };
    const contribution = contributions.get('user-new');
    assert.equal(contribution.credentialContribution({}, credentialLease).supportsCredential, true);
    const presentation = contribution.credentialTaskPresentation({
        credentialPolicy: {
            ownerPluginId: 'pixivdownload.plugin.download-workbench',
            policyId: 'pixiv-cookie',
            publicationId: 11,
            statusCode: 'AUTH_EXPIRED'
        }
    }, {}, credentialLease);
    assert.equal(presentation.lightTone, 'red');
    assert.equal(contribution.credentialTaskPresentation({
        sourceOwnerPluginId: 'pixivdownload.plugin.download-workbench',
        lastStatus: 'AUTH_EXPIRED'
    }, {}, credentialLease), null);
    const result = await contribution.bindSavedCredential(42, {}, credentialLease);
    assert.equal(result.ok, true);
    assert.equal(result.status, 'bound');
    assert.equal(requests[0].url, '/api/schedule/tasks/42/authorize-cookie');
    assert.equal(requests[0].init.headers['X-Acquisition-Credential'], 'PHPSESSID=42_secret');
    assert.equal(Object.values(result).some(value => String(value).includes('42_secret')), false);
    assert.deepEqual(JSON.parse(requests[0].init.body), {
        activationToken: 'activation-pixiv'
    });
});
