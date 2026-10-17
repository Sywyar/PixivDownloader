'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {
    pixivModuleSource
} = require('./schedule-sources-test-support');

test('两版页面的来源凭证贡献读取已保存值，文案由来源命名空间解析', async () => {
    for (const core of ['pixiv-batch/batch-core.js', 'pixiv-batch-alt/alt-core.js']) {
        const storage = new Map([['pixiv_cookie_fmt', 'json']]);
        const requests = [];
        const handlers = new Map();
        const context = vm.createContext({AbortController, document: {addEventListener() {}},
            storeGet: key => storage.get(key),
            getCookieInputHeaderString: () => { throw new Error('未保存的页面草稿不得用于绑定'); },
            fetch: async (url, init) => { requests.push({url, init}); return {ok: true}; }
        });
        context.window = context;
        context.PixivBatch = {scheduleSources: {registerModule(_url, init) {
            init({descriptors: [{sourceType: 'search'}], registerSource: (type, value) => handlers.set(type, value)});
        }}};
        for (const file of ['pixiv-batch/queue-display.js', core, 'pixiv-batch/cookie-storage.js', 'pixiv-batch/pixiv-schedule-sources.js']) {
            vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static', file), 'utf8'), context);
        }
        vm.runInContext("pageI18n={t:(key,fallback)=>key==='batch:schedule.override.title'?'Source title':key==='batch-alt:schedule.override.title'?'Wrong {name}':fallback}", context);
        const contribution = handlers.get('search');
        const lease = {activationToken: 'fixture-activation', signal: new AbortController().signal, assertCurrent() {}};
        assert.equal(contribution.credentialContribution({}, lease).presentation.modalTitle, 'Source title');
        const missing = await contribution.bindSavedCredential(42, {}, lease);
        assert.equal(missing.status, 'missing');
        assert.equal(requests.length, 0);
        storage.set('pixiv_cookie', JSON.stringify({PHPSESSID: 'fixture-saved'}));
        const bound = await contribution.bindSavedCredential(42, {}, lease);
        assert.equal(bound.ok, true);
        assert.equal(requests[0].init.headers['X-Acquisition-Credential'], 'PHPSESSID=fixture-saved');
        assert.equal(requests[0].init.signal, lease.signal);
        assert.doesNotMatch(JSON.stringify(bound), /fixture-saved/);
    }
});

test('Pixiv 来源模块只经固定凭证贡献面注册并在插件内部读取已保存凭证', async () => {
    let initializer = null;
    const requests = [];
    const context = vm.createContext({
        BASE: '',
        AbortController,
        bt: (_key, fallback, vars) => Object.entries(vars || {}).reduce(
            (text, [key, value]) => text.replace(`{${key}}`, String(value)), fallback),
        getCookie: () => 'PHPSESSID=42_secret',
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
    const searchSummary = contributions.get('search').summary({paramsJson: JSON.stringify({
        kind: 'illust', source: {word: '机械师'}, download: {}, filters: {}
    })});
    assert.match(searchSummary.description, /搜索关键词.*机械师/);
    assert.equal(searchSummary.kind, 'illust');
    assert.equal(searchSummary.sections.length, 3, '简述不丢失完整快照');
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
    const overusePresentation = contribution.credentialTaskPresentation({
        credentialPolicy: {
            ownerPluginId: 'pixivdownload.plugin.download-workbench',
            policyId: 'pixiv-cookie',
            publicationId: 11,
            statusCode: 'OVERUSE_PAUSED'
        },
        suspendDetailJson: JSON.stringify({excerpt: 'Pixiv asked this account to slow down'})
    }, {}, credentialLease);
    assert.match(overusePresentation.lightText, /Pixiv asked this account to slow down/);
    assert.match(overusePresentation.statusLabel, /Pixiv asked this account to slow down/);
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
