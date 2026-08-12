'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-posthog', 'pixiv-posthog.js'), 'utf8');

async function main() {
    const initCalls = [];
    const clients = {};
    const sdk = {
        init(token, config, name) {
            initCalls.push({token, config, name});
            clients[name] = {name};
            return clients[name];
        }
    };
    const sandbox = {window: null, console, Promise, Object, URL, setTimeout, clearTimeout, posthog: sdk};
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox, {filename: 'pixiv-posthog.js'});

    const api = sandbox.PixivPostHog;
    const filter = event => event;
    const posthog = {
        projectToken: 'phc_owner_one',
        surveyId: 'survey-one',
        apiHost: 'https://one.example.test',
        uiHost: 'https://ui-one.example.test'
    };
    const first = await api.createSurveyClient({
        ownerKey: 'download-workbench.layout-feedback',
        posthog,
        distinctId: 'plf_' + 'a'.repeat(64),
        beforeSend: filter
    });
    const reused = await api.createSurveyClient({
        ownerKey: 'download-workbench.layout-feedback',
        posthog,
        distinctId: 'plf_' + 'a'.repeat(64),
        beforeSend: filter
    });

    assert.strictEqual(first, reused);
    assert.strictEqual(initCalls.length, 1);
    assert.strictEqual(initCalls[0].config.bootstrap.distinctID, 'plf_' + 'a'.repeat(64));
    assert.strictEqual(initCalls[0].config.autocapture, false);
    assert.strictEqual(initCalls[0].config.before_send, filter);
    assert.strictEqual(initCalls[0].token, posthog.projectToken);
    assert.strictEqual(initCalls[0].config.api_host, posthog.apiHost);
    assert.strictEqual(initCalls[0].config.ui_host, posthog.uiHost);
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: '', posthog, beforeSend: filter
    }), null);
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: 'invalid.missing-survey',
        posthog: {...posthog, surveyId: ''},
        beforeSend: filter
    }), null);
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: 'invalid.insecure-host',
        posthog: {...posthog, apiHost: 'http://one.example.test'},
        beforeSend: filter
    }), null);
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: 'download-workbench.layout-feedback',
        posthog,
        distinctId: 'plf_' + 'b'.repeat(64),
        beforeSend: filter
    }), null);
    const second = await api.createSurveyClient({
        ownerKey: 'another-plugin.own-survey',
        posthog: {
            projectToken: 'phc_owner_two',
            surveyId: 'survey-two',
            apiHost: 'https://two.example.test',
            uiHost: 'https://ui-two.example.test'
        },
        beforeSend: filter
    });
    assert.ok(second);
    assert.strictEqual(initCalls.length, 2);
    assert.strictEqual(initCalls[1].token, 'phc_owner_two');
    assert.deepStrictEqual(Object.keys(api), ['createSurveyClient']);
    console.log('pixiv-posthog.test.js: passed');
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
