'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const moduleSource = fs.readFileSync(path.join(
    __dirname,
    '../../main/resources/static/pixiv-douyin-download/douyin-schedule-sources.js'
), 'utf8');
const scheduleRuntimeRoot = path.join(__dirname,
    '../../../../pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch');
const runtimeSource = [
    'batch-schedule-sources-normalize.js',
    'batch-schedule-sources-runtime.js',
    'batch-schedule-sources.js'
].map(file => fs.readFileSync(path.join(scheduleRuntimeRoot, file), 'utf8')).join('\n');

const SOURCE_TYPES = [
    'douyin.user',
    'douyin.search',
    'douyin.collection',
    'douyin.music',
    'douyin.account.own-works',
    'douyin.account.liked-works',
    'douyin.account.favorite-works',
    'douyin.account.favorite-folder',
    'douyin.account.favorite-collection'
];
const SAVED_COOKIE = 'ttwid=tt; passport_csrf_token=csrf; sessionid=sid';

function definition(source, fetchLimit = 25) {
    return JSON.stringify({source, fetchLimit});
}

function harness() {
    const elements = new Map([
        ['user-id-input', {value: ''}],
        ['search-word', {value: ''}],
        ['series-input-url', {value: ''}],
        ['sch-fetch-limit', {value: '25'}]
    ]);
    const contributions = new Map();
    let initializer = null;
    const selectedSeriesSources = [];
    const requests = [];
    const runtime = {
        registerModule(moduleUrl, value) {
            assert.equal(moduleUrl, '/pixiv-douyin-download/douyin-schedule-sources.js');
            initializer = value;
            return true;
        }
    };
    const state = {mode: 'user', settings: {userKind: 'douyin', searchKind: 'douyin'}};
    const seriesState = {kind: 'douyin', seriesId: null, seriesTitle: ''};
    const sandbox = {
        window: {
            PixivBatch: {
                scheduleSources: runtime,
                modes: {
                    series: {
                        selectSeriesDataSource(sourceId) {
                            selectedSeriesSources.push(sourceId);
                        }
                    }
                },
                queueTypes: {
                    descriptor(type) {
                        assert.equal(type, 'douyin');
                        return {
                            cookie: {
                                validate(cookie) {
                                    const ok = /(?:^|;\s*)ttwid=/.test(cookie)
                                        && /(?:^|;\s*)passport_csrf_token=/.test(cookie)
                                        && /(?:^|;\s*)(?:sessionid|sessionid_ss|sid_tt|sid_guard)=/.test(cookie);
                                    return {
                                        ok,
                                        empty: !String(cookie || '').trim(),
                                        missing: ok ? [] : ['sessionid']
                                    };
                                }
                            }
                        };
                    }
                },
                cookie: {
                    getCookieHeaderStringFor(type) {
                        assert.equal(type, 'douyin');
                        return SAVED_COOKIE;
                    }
                }
            }
        },
        document: {
            getElementById(id) { return elements.get(id) || null; },
            querySelector() { return null; }
        },
        state,
        seriesState,
        QUICK_FETCH_MODE: 'quick-fetch',
        BASE: '',
        URL,
        Set,
        Object,
        JSON,
        Number,
        String,
        Array,
        Promise,
        fetch: async (url, init) => {
            requests.push({url, init});
            return {ok: true};
        },
        bt(_key, fallback, args) {
            let value = fallback;
            Object.entries(args || {}).forEach(([key, replacement]) => {
                value = value.replace('{' + key + '}', String(replacement));
            });
            return value;
        },
        switchMode(mode) { state.mode = mode; },
        applyKindSwitcherUI() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(moduleSource, sandbox, {filename: 'douyin-schedule-sources.js'});
    assert.equal(typeof initializer, 'function');
    initializer({
        descriptors: SOURCE_TYPES.map(sourceType => ({sourceType})),
        signal: new AbortController().signal,
        assertActive() {},
        registerSource(sourceType, contribution) {
            assert.ok(SOURCE_TYPES.includes(sourceType));
            assert.equal(contributions.has(sourceType), false);
            contributions.set(sourceType, contribution);
        }
    });
    return {
        sandbox,
        state,
        seriesState,
        elements,
        contributions,
        requests,
        selectedSeriesSources
    };
}

function credentialLease() {
    const controller = new AbortController();
    let current = true;
    return {
        lease: {
            sourceType: 'douyin.user',
            ownerPluginId: 'douyin',
            packageId: 'douyin',
            pluginGeneration: 1,
            publicationId: 10,
            activationToken: 'activation-douyin',
            signal: controller.signal,
            isCurrent() { return current; },
            assertCurrent() {
                if (!current) throw new Error('stale Douyin credential lease');
            }
        },
        expire() {
            current = false;
            controller.abort();
        }
    };
}

test('固定凭证接口绑定已保存 Cookie 且不向宿主或 DOM 返回明文', async () => {
    const h = harness();
    const contribution = h.contributions.get('douyin.account.favorite-works');
    const leaseState = credentialLease();

    const result = await contribution.bindSavedCredential(42, null, leaseState.lease);

    assert.deepEqual(JSON.parse(JSON.stringify(result)), {ok: true, status: 'bound'});
    assert.equal(h.requests[0].url, '/api/schedule/tasks/42/credential');
    assert.equal(h.requests[0].init.headers['X-Acquisition-Credential'], SAVED_COOKIE);
    assert.deepEqual(JSON.parse(h.requests[0].init.body), {
        activationToken: 'activation-douyin'
    });
    assert.equal(JSON.stringify(result).includes('passport_csrf_token'), false);
    assert.equal(contribution.savedCookie, undefined);
    assert.equal(contribution.credentialActions, undefined);
    assert.equal(Array.from(h.elements.values()).some(element =>
        String(element.value || '').includes('passport_csrf_token')), false);
});

function manifestSource(sourceType, generation) {
    const mode = sourceType === 'douyin.user' ? 'user'
        : sourceType === 'douyin.search' ? 'search'
            : sourceType === 'douyin.collection' || sourceType === 'douyin.music'
                || sourceType === 'douyin.account.favorite-folder' ? 'series' : 'quick';
    return {
        sourceType,
        legacyAliases: [],
        ownerPluginId: 'douyin',
        packageId: 'douyin',
        pluginGeneration: generation,
        publicationId: generation * 10,
        activationToken: `douyin-${generation}`,
        definitionSchema: 'douyin.schedule.definition',
        definitionVersion: 1,
        presentation: {
            displayNamespace: 'douyin',
            displayNameKey: 'schedule.source.user.name',
            descriptionKey: 'schedule.source.user.description',
            iconKey: 'schedule',
            colorToken: 'douyin'
        },
        acquisitionModes: [mode],
        possibleWorkTypes: ['douyin'],
        frontend: {
            contractVersion: 1,
            moduleUrl: '/pixiv-douyin-download/douyin-schedule-sources.js'
        }
    };
}

function runtimeHarness(manifests) {
    const responses = manifests.slice();
    const requests = [];
    let savedCookieReads = 0;
    const document = {
        currentScript: null,
        head: null,
        documentElement: null,
        createElement(tag) {
            assert.equal(tag, 'script');
            return {dataset: {}, async: false, src: '', onload: null, onerror: null, remove() {}};
        }
    };
    const sandbox = {
        console: {warn() {}, log() {}, error() {}},
        URL,
        AbortController,
        CustomEvent: class CustomEvent {
            constructor(type, options) { this.type = type; this.detail = options && options.detail; }
        },
        queueMicrotask,
        setTimeout,
        clearTimeout,
        BASE: '',
        bt(_key, fallback, args) {
            let value = fallback;
            Object.entries(args || {}).forEach(([key, replacement]) => {
                value = value.replace('{' + key + '}', String(replacement));
            });
            return value;
        },
        fetch: async (url, init) => {
            requests.push({url, init: init || {}});
            if (String(url).includes('/api/schedule/sources')) {
                return {ok: true, status: 200, json: async () => responses.shift()};
            }
            return {ok: true, status: 200, json: async () => ({})};
        },
        document,
        window: {
            location: {origin: 'http://localhost'},
            PixivBatch: {
                queueTypes: {
                    descriptor(type) {
                        assert.equal(type, 'douyin');
                        return {
                            cookie: {
                                validate(cookie) {
                                    const value = String(cookie || '');
                                    const ok = /(?:^|;\s*)ttwid=/.test(value)
                                        && /(?:^|;\s*)passport_csrf_token=/.test(value)
                                        && /(?:^|;\s*)(?:sessionid|sessionid_ss|sid_tt|sid_guard)=/.test(value);
                                    return {ok, empty: !value.trim(), missing: ok ? [] : ['sessionid']};
                                }
                            }
                        };
                    }
                },
                cookie: {
                    getCookieHeaderStringFor(type) {
                        assert.equal(type, 'douyin');
                        savedCookieReads++;
                        return SAVED_COOKIE;
                    }
                }
            },
            dispatchEvent() {},
            addEventListener() {}
        }
    };
    const context = vm.createContext(sandbox);
    document.head = {
        appendChild(script) {
            queueMicrotask(() => {
                document.currentScript = script;
                vm.runInContext(moduleSource, context, {filename: 'douyin-schedule-sources.js'});
                document.currentScript = null;
                script.onload();
            });
        }
    };
    document.documentElement = document.head;
    vm.runInContext(runtimeSource, context, {filename: 'batch-schedule-sources.js'});
    return {
        runtime: context.window.PixivBatch.scheduleSources,
        requests,
        savedCookieReads() { return savedCookieReads; }
    };
}

test('模块只注册九类稳定 Douyin 周期来源并统一生成字符串作品定义', () => {
    const h = harness();
    assert.deepEqual(Array.from(h.contributions.keys()), SOURCE_TYPES);
    for (const sourceType of SOURCE_TYPES) {
        const contribution = h.contributions.get(sourceType);
        assert.equal(typeof contribution.capture, 'function');
        assert.equal(typeof contribution.restore, 'function');
        assert.equal(typeof contribution.summary, 'function');
        assert.equal(contribution.fetchLimitMode(), 'per-run');
    }

    h.elements.get('user-id-input').value = 'https://www.douyin.com/user/sec-user-1';
    const user = h.contributions.get('douyin.user').capture({mode: 'user'});
    assert.deepEqual(JSON.parse(JSON.stringify(user.params)), {
        source: {userId: 'sec-user-1'},
        fetchLimit: 25
    });
    assert.equal(user.workType, 'douyin');
    assert.equal(user.fetchLimitMode, 'per-run');

    h.elements.get('search-word').value = '猫咪';
    const search = h.contributions.get('douyin.search').capture({mode: 'search'});
    assert.deepEqual(JSON.parse(JSON.stringify(search.params)), {
        source: {keyword: '猫咪'},
        fetchLimit: 25
    });

    h.seriesState.seriesId = 'mix-9';
    const collection = h.contributions.get('douyin.collection')
        .capture({mode: 'series'});
    assert.deepEqual(JSON.parse(JSON.stringify(collection.params.source)), {collectionId: 'mix-9'});
    h.seriesState.seriesId = 'music:music-9';
    const music = h.contributions.get('douyin.music')
        .capture({mode: 'series'});
    assert.deepEqual(JSON.parse(JSON.stringify(music.params.source)), {musicId: 'music-9'});
    h.seriesState.seriesId = 'favorite-folder:folder-9';
    const favoriteFolder = h.contributions.get('douyin.account.favorite-folder')
        .capture({mode: 'series'});
    assert.deepEqual(JSON.parse(JSON.stringify(favoriteFolder.params.source)), {folderId: 'folder-9'});

    const quickDefinitions = new Map([
        ['douyin.account.own-works', {}],
        ['douyin.account.liked-works', {}],
        ['douyin.account.favorite-works', {}],
        ['douyin.account.favorite-collection', {collectionId: 'favorite-7'}]
    ]);
    for (const [sourceType, source] of quickDefinitions) {
        const captured = h.contributions.get(sourceType).capture({
            mode: 'quick-fetch',
            quickSource: {sourceType, source, kind: 'douyin', workTypes: ['douyin']}
        });
        assert.deepEqual(JSON.parse(JSON.stringify(captured.params)), {source, fetchLimit: 25});
        assert.equal(JSON.stringify(captured.params).includes('Cookie'), false);
        assert.equal(JSON.stringify(captured.params).includes('http'), false);
    }
});

test('douyin.user 仅在 User 作品二级项匹配，喜欢不误存为作品', () => {
    const h = harness();
    const user = h.contributions.get('douyin.user');
    const context = {mode: 'user'};

    assert.equal(user.matches(context), true);
    h.elements.get('user-id-input').value =
        'https://www.douyin.com/user/self?showTab=favorite_collection';
    assert.equal(user.matches(context), false);
    assert.throws(() => user.capture(context), /stable Douyin user ID/);
    h.elements.get('user-id-input').value = '';
    h.state.settings.userKind = 'douyin-user-liked';
    assert.equal(user.matches(context), false);
});

test('真实来源 runtime 受控加载模块并在 publication 更替后使旧 lease 失效', async () => {
    const first = {
        epoch: 'douyin-epoch',
        revision: 1,
        sources: SOURCE_TYPES.map(sourceType => manifestSource(sourceType, 1))
    };
    const empty = {epoch: 'douyin-epoch', revision: 2, sources: []};
    const second = {
        epoch: 'douyin-epoch',
        revision: 3,
        sources: SOURCE_TYPES.map(sourceType => manifestSource(sourceType, 2))
    };
    const runtime = runtimeHarness([first, empty, second]).runtime;
    await runtime.refresh(false);
    assert.equal(SOURCE_TYPES.every(sourceType => runtime.isAvailable(sourceType)), true);
    const oldLease = runtime.activationLease('douyin.user');
    assert.equal(oldLease.isCurrent(), true);

    await runtime.refresh(false);
    assert.equal(SOURCE_TYPES.some(sourceType => runtime.isAvailable(sourceType)), false);
    assert.equal(oldLease.isCurrent(), false);
    assert.throws(() => oldLease.assertCurrent(), /stale/i);

    await runtime.refresh(false);
    assert.equal(SOURCE_TYPES.every(sourceType => runtime.isAvailable(sourceType)), true);
    assert.notEqual(runtime.activationToken('douyin.user'), oldLease.activationToken);
});

test('合集、音乐与账号自建收藏夹在 series 模式中精确匹配且拒绝其它作品类型', () => {
    const h = harness();
    const collection = h.contributions.get('douyin.collection');
    const music = h.contributions.get('douyin.music');
    const favoriteFolder = h.contributions.get('douyin.account.favorite-folder');
    h.seriesState.seriesId = 'collection-1';
    assert.equal(collection.matches({mode: 'series'}), true);
    assert.equal(music.matches({mode: 'series'}), false);
    assert.equal(favoriteFolder.matches({mode: 'series'}), false);
    h.seriesState.seriesId = 'music:music-1';
    assert.equal(collection.matches({mode: 'series'}), false);
    assert.equal(music.matches({mode: 'series'}), true);
    assert.equal(favoriteFolder.matches({mode: 'series'}), false);
    h.seriesState.seriesId = 'favorite-folder:folder-1';
    assert.equal(collection.matches({mode: 'series'}), false);
    assert.equal(music.matches({mode: 'series'}), false);
    assert.equal(favoriteFolder.matches({mode: 'series'}), true);
});

test('九类来源编辑回灌保持 canonical 字段并拒绝畸形定义', () => {
    const h = harness();
    const cases = new Map([
        ['douyin.user', {userId: 'user-1'}],
        ['douyin.search', {keyword: 'keyword-1'}],
        ['douyin.collection', {collectionId: 'collection-1'}],
        ['douyin.music', {musicId: 'music-1'}],
        ['douyin.account.own-works', {}],
        ['douyin.account.liked-works', {}],
        ['douyin.account.favorite-works', {}],
        ['douyin.account.favorite-folder', {folderId: 'folder-1'}],
        ['douyin.account.favorite-collection', {collectionId: 'favorite-1'}]
    ]);
    for (const [sourceType, source] of cases) {
        const restored = h.contributions.get(sourceType).restore({
            sourceType,
            paramsJson: definition(source, 37)
        });
        assert.equal(restored.params.fetchLimit, 37);
        assert.deepEqual(JSON.parse(JSON.stringify(restored.params.source)), source);
        assert.equal(restored.kind, 'douyin');
        if (sourceType === 'douyin.account.favorite-folder') {
            assert.equal(restored.mode, 'series');
            assert.equal(restored.quickSource, null);
            assert.equal(h.seriesState.seriesId, 'favorite-folder:folder-1');
            assert.equal(h.elements.get('series-input-url').value, 'favorite-folder:folder-1');
        } else if (sourceType.startsWith('douyin.account.')) {
            assert.equal(restored.mode, 'quick-fetch');
            assert.equal(restored.quickSource.sourceType, sourceType);
            assert.deepEqual(JSON.parse(JSON.stringify(restored.quickSource.source)), source);
        }
        const summary = h.contributions.get(sourceType).summary({
            sourceType,
            paramsJson: definition(source, 37)
        });
        assert.equal(summary.kind, 'douyin');
        assert.equal(summary.sections.length, 1);
        if (sourceType === 'douyin.account.favorite-folder') {
            assert.deepEqual(JSON.parse(JSON.stringify(summary.sections[0].rows[0])),
                ['Favorite folder ID', 'folder-1']);
        }
    }
    assert.deepEqual(h.selectedSeriesSources, ['douyin', 'douyin', 'douyin']);

    assert.throws(() => h.contributions.get('douyin.user').restore({
        paramsJson: JSON.stringify({source: {userId: 'u', transientUrl: 'https://signed.invalid'}, fetchLimit: 1})
    }), /invalid/i);
    assert.throws(() => h.contributions.get('douyin.search').restore({
        paramsJson: definition({keyword: 'k'}, 5001)
    }), /invalid/i);
    assert.throws(() => h.contributions.get('douyin.account.own-works').restore({
        paramsJson: definition({accountKey: 'secret-account'}, 1)
    }), /invalid/i);
});

test('Douyin 来源实现全部固定凭证方法并由 lease 拒绝过期调用', async () => {
    const h = harness();
    const contribution = h.contributions.get('douyin.user');
    const leaseState = credentialLease();
    const fixedMethods = [
        'credentialContribution',
        'validateCredential',
        'bindCredential',
        'bindSavedCredential',
        'revokeCredential',
        'credentialTaskPresentation'
    ];
    fixedMethods.forEach(name => assert.equal(typeof contribution[name], 'function'));
    assert.equal(contribution.credentialActions, undefined);
    assert.doesNotMatch(moduleSource,
        /cookieMode|cookieBound|accountId|ackWarningTime|sourceOwnerPluginId|task\.lastStatus/);

    const metadata = contribution.credentialContribution();
    assert.equal(metadata.supportsCredential, true);
    assert.equal(metadata.supportsProxy, true);
    assert.equal(metadata.supportsCookie, undefined);
    assert.equal(metadata.savedCookie, undefined);
    assert.equal(contribution.validateCredential(
        'ttwid=tt; passport_csrf_token=csrf; sid_tt=sid', null, leaseState.lease), null);
    assert.match(contribution.validateCredential('ttwid=tt', null, leaseState.lease), /missing/i);

    const bound = await contribution.bindCredential(43, SAVED_COOKIE, null, leaseState.lease);
    assert.deepEqual(JSON.parse(JSON.stringify(bound)), {ok: true, status: 'bound'});
    const revoked = await contribution.revokeCredential(43, null, leaseState.lease);
    assert.deepEqual(JSON.parse(JSON.stringify(revoked)), {ok: true, status: 'revoked'});
    assert.equal(h.requests[0].url, '/api/schedule/tasks/43/credential');
    assert.equal(h.requests[0].init.method, 'POST');
    assert.equal(h.requests[1].url, '/api/schedule/tasks/43/credential');
    assert.equal(h.requests[1].init.method, 'DELETE');

    const presentation = contribution.credentialTaskPresentation({
        credentialPolicy: {
            ownerPluginId: 'douyin',
            policyId: 'douyin.cookie',
            publicationId: 10,
            statusCode: 'AUTH_EXPIRED'
        }
    }, null, leaseState.lease);
    assert.equal(presentation.lightTone, 'red');
    assert.equal(presentation.suspended, true);
    assert.equal(presentation.manualRecoveryRequired, true);
    assert.equal(contribution.credentialTaskPresentation({
        sourceOwnerPluginId: 'douyin',
        lastStatus: 'AUTH_EXPIRED'
    }, null, leaseState.lease), null);

    leaseState.expire();
    assert.throws(() => contribution.validateCredential('', null, leaseState.lease), /stale/i);
    assert.throws(() => contribution.credentialTaskPresentation({}, null, leaseState.lease), /stale/i);
});

test('宿主 runtime 仅暴露固定凭证 surface 并过滤旧任意动作袋', async () => {
    const manifest = {
        epoch: 'douyin-credential-epoch',
        revision: 1,
        sources: SOURCE_TYPES.map(sourceType => manifestSource(sourceType, 1))
    };
    const h = runtimeHarness([manifest]);
    const runtime = h.runtime;
    await runtime.refresh(false);

    [
        'credentialContribution',
        'validateCredential',
        'bindCredential',
        'bindSavedCredential',
        'revokeCredential',
        'credentialTaskPresentation'
    ].forEach(name => assert.equal(typeof runtime[name], 'function'));
    assert.equal(runtime.credentialActions, undefined);
    assert.equal(runtime.invokeCredentialAction, undefined);

    const metadata = runtime.credentialContribution('douyin.user', {});
    assert.equal(metadata.supportsCredential, true);
    assert.equal(metadata.supportsCookie, undefined);
    assert.equal(metadata.savedCookie, undefined);

    const result = await runtime.bindSavedCredential('douyin.user', 44, {});
    assert.deepEqual(JSON.parse(JSON.stringify(result)), {
        ok: true,
        status: 'bound',
        error: null
    });
    assert.equal(h.savedCookieReads(), 1);
    const request = h.requests.find(item =>
        String(item.url).endsWith('/api/schedule/tasks/44/credential'));
    assert.ok(request);
    assert.equal(request.init.headers['X-Acquisition-Credential'], SAVED_COOKIE);
    assert.equal(JSON.stringify(result).includes('passport_csrf_token'), false);
    assert.doesNotMatch(moduleSource, /authorize-cookie|revoke-cookie/);
});
