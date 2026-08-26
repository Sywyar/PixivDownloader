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

test('Pixiv 来源模块只经固定凭证贡献面注册并在插件内部读取已保存凭证', async () => {
    let initializer = null;
    const requests = [];
    const context = vm.createContext({
        BASE: '',
        AbortController,
        bt: (_key, fallback, vars) => Object.entries(vars || {}).reduce(
            (text, [key, value]) => text.replace(`{${key}}`, String(value)), fallback),
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
