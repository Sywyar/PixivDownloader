'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-posthog', 'pixiv-posthog.js'), 'utf8');

async function testSynchronousLoadFailureCanRetry() {
    let attempts = 0;
    const sdk = {init() { return {}; }};
    const sandbox = {window: null, console, Promise, Object, URL, setTimeout, clearTimeout};
    const head = {
        appendChild(script) {
            attempts++;
            if (attempts === 1) throw new Error('append failed');
            script.parentNode = head;
            sandbox.posthog = sdk;
            Promise.resolve().then(() => script.listeners.load());
        },
        removeChild(script) { script.parentNode = null; }
    };
    sandbox.document = {
        head,
        documentElement: head,
        querySelector() { return null; },
        createElement() {
            return {
                listeners: {}, parentNode: null,
                addEventListener(type, listener) { this.listeners[type] = listener; },
                removeEventListener(type) { delete this.listeners[type]; }
            };
        }
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox, {filename: 'pixiv-posthog-retry.js'});
    const options = {
        ownerKey: 'retry.owner',
        posthog: {
            projectToken: 'phc_retry', surveyId: 'survey-retry',
            apiHost: 'https://layout-survey.sywyar.top', uiHost: 'https://us.posthog.com'
        },
        distinctId: 'retry_' + 'c'.repeat(64),
        beforeSend: event => event
    };
    assert.strictEqual(await sandbox.PixivPostHog.createSurveyClient(options), null);
    assert.ok(await sandbox.PixivPostHog.createSurveyClient(options));
    assert.strictEqual(attempts, 2);
}

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
        apiHost: 'https://layout-survey.sywyar.top',
        uiHost: 'https://us.posthog.com'
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
        posthog: {...posthog, apiHost: 'https://attacker.example'},
        beforeSend: filter
    }), null);
    for (const [ownerKey, apiHost] of [
        ['invalid.http-host', 'http://layout-survey.sywyar.top'],
        ['invalid.custom-port', 'https://layout-survey.sywyar.top:444'],
        ['invalid.path', 'https://layout-survey.sywyar.top/capture'],
        ['invalid.credentials', 'https://user@layout-survey.sywyar.top']
    ]) {
        assert.strictEqual(await api.createSurveyClient({
            ownerKey, posthog: {...posthog, apiHost}, beforeSend: filter
        }), null);
    }
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: 'invalid.ui-host',
        posthog: {...posthog, uiHost: 'https://attacker.example'},
        beforeSend: filter
    }), null);
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: 'invalid.ui-path',
        posthog: {...posthog, uiHost: 'https://us.posthog.com/project'},
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
            apiHost: 'https://eu.i.posthog.com',
            uiHost: 'https://eu.posthog.com'
        },
        beforeSend: filter
    });
    assert.ok(second);
    assert.strictEqual(initCalls.length, 2);
    assert.strictEqual(initCalls[1].token, 'phc_owner_two');
    assert.deepStrictEqual(Object.keys(api), ['createSurveyClient']);
    await testSynchronousLoadFailureCanRetry();
    console.log('pixiv-posthog.test.js: passed');
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
