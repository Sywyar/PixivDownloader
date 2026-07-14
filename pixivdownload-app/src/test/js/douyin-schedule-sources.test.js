'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const moduleSource = fs.readFileSync(path.join(
    __dirname,
    '../../../../pixivdownload-plugin-douyin/src/main/resources/static/pixiv-douyin-download/douyin-schedule-sources.js'
), 'utf8');
const runtimeSource = fs.readFileSync(path.join(
    __dirname,
    '../../../../pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch/batch-schedule-sources.js'
), 'utf8');

const SOURCE_TYPES = [
    'douyin.user',
    'douyin.search',
    'douyin.collection',
    'douyin.music',
    'douyin.account.own-works',
    'douyin.account.liked-works',
    'douyin.account.favorite-works',
    'douyin.account.favorite-collection'
];

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
    let active = true;
    let assertActiveCalls = 0;
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
                        return 'ttwid=tt; passport_csrf_token=csrf; sessionid=sid';
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
        fetch: async () => ({ok: true}),
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
        assertActive() {
            assertActiveCalls++;
            if (!active) throw new Error('stale Douyin schedule source activation');
        },
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
        deactivate() { active = false; },
        assertActiveCalls() { return assertActiveCalls; }
    };
}

function manifestSource(sourceType, generation) {
    const mode = sourceType === 'douyin.user' ? 'user'
        : sourceType === 'douyin.search' ? 'search'
            : sourceType === 'douyin.collection' || sourceType === 'douyin.music' ? 'series' : 'quick';
    return {
        sourceType,
        legacyAliases: [],
        ownerPluginId: 'douyin',
        packageId: 'douyin',
        pluginGeneration: generation,
        publicationId: generation * 10,
        activationToken: `douyin-${generation}-${sourceType}`,
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
        fetch: async () => ({ok: true, status: 200, json: async () => responses.shift()}),
        document,
        window: {
            location: {origin: 'http://localhost'},
            PixivBatch: {},
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
    return context.window.PixivBatch.scheduleSources;
}

test('模块只注册八类稳定 Douyin 周期来源并统一生成字符串作品定义', () => {
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
    const user = h.contributions.get('douyin.user').capture({mode: 'user', workTypes: ['douyin']});
    assert.deepEqual(JSON.parse(JSON.stringify(user.params)), {
        source: {userId: 'sec-user-1'},
        fetchLimit: 25
    });
    assert.equal(user.workType, 'douyin');
    assert.equal(user.fetchLimitMode, 'per-run');

    h.elements.get('search-word').value = '猫咪';
    const search = h.contributions.get('douyin.search').capture({mode: 'search', workTypes: ['douyin']});
    assert.deepEqual(JSON.parse(JSON.stringify(search.params)), {
        source: {keyword: '猫咪'},
        fetchLimit: 25
    });

    h.seriesState.seriesId = 'mix-9';
    const collection = h.contributions.get('douyin.collection')
        .capture({mode: 'series', workTypes: ['douyin']});
    assert.deepEqual(JSON.parse(JSON.stringify(collection.params.source)), {collectionId: 'mix-9'});
    h.seriesState.seriesId = 'music:music-9';
    const music = h.contributions.get('douyin.music')
        .capture({mode: 'series', workTypes: ['douyin']});
    assert.deepEqual(JSON.parse(JSON.stringify(music.params.source)), {musicId: 'music-9'});

    const quickDefinitions = new Map([
        ['douyin.account.own-works', {}],
        ['douyin.account.liked-works', {}],
        ['douyin.account.favorite-works', {}],
        ['douyin.account.favorite-collection', {collectionId: 'favorite-7'}]
    ]);
    for (const [sourceType, source] of quickDefinitions) {
        const captured = h.contributions.get(sourceType).capture({
            mode: 'quick-fetch',
            workTypes: ['douyin'],
            quickSource: {sourceType, source, kind: 'douyin', workTypes: ['douyin']}
        });
        assert.deepEqual(JSON.parse(JSON.stringify(captured.params)), {source, fetchLimit: 25});
        assert.equal(JSON.stringify(captured.params).includes('Cookie'), false);
        assert.equal(JSON.stringify(captured.params).includes('http'), false);
    }
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
    const runtime = runtimeHarness([first, empty, second]);
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

test('合集与音乐在同一 series 模式中精确匹配且拒绝其它作品类型', () => {
    const h = harness();
    const collection = h.contributions.get('douyin.collection');
    const music = h.contributions.get('douyin.music');
    h.seriesState.seriesId = 'collection-1';
    assert.equal(collection.matches({mode: 'series', workTypes: ['douyin']}), true);
    assert.equal(music.matches({mode: 'series', workTypes: ['douyin']}), false);
    h.seriesState.seriesId = 'music:music-1';
    assert.equal(collection.matches({mode: 'series', workTypes: ['douyin']}), false);
    assert.equal(music.matches({mode: 'series', workTypes: ['douyin']}), true);
    assert.equal(music.matches({mode: 'series', workTypes: ['illust']}), false);
});

test('八类来源编辑回灌保持 canonical 字段并拒绝畸形定义', () => {
    const h = harness();
    const cases = new Map([
        ['douyin.user', {userId: 'user-1'}],
        ['douyin.search', {keyword: 'keyword-1'}],
        ['douyin.collection', {collectionId: 'collection-1'}],
        ['douyin.music', {musicId: 'music-1'}],
        ['douyin.account.own-works', {}],
        ['douyin.account.liked-works', {}],
        ['douyin.account.favorite-works', {}],
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
        if (sourceType.startsWith('douyin.account.')) {
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
    }

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

test('凭证动作读取 Douyin Cookie、复用队列校验并在 activation 失效后拒绝调用', () => {
    const h = harness();
    const actions = h.contributions.get('douyin.user').credentialActions();
    assert.equal(actions.supportsCookie, true);
    assert.equal(actions.supportsProxy, true);
    assert.match(actions.savedCookie(), /passport_csrf_token=csrf/);
    assert.equal(actions.validateCookie('ttwid=tt; passport_csrf_token=csrf; sid_tt=sid'), null);
    assert.match(actions.validateCookie('ttwid=tt'), /missing/i);
    assert.ok(h.assertActiveCalls() >= 3);
    h.deactivate();
    assert.throws(() => actions.savedCookie(), /stale/i);
    assert.throws(() => actions.validateCookie(''), /stale/i);
});
