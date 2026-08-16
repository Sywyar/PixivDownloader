'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const {webcrypto} = require('crypto');

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
        trustedApiOrigins: ['https://layout-survey.sywyar.top'],
        distinctId: 'retry_' + 'c'.repeat(64),
        beforeSend: event => event
    };
    assert.strictEqual(await sandbox.PixivPostHog.createSurveyClient(options), null);
    assert.ok(await sandbox.PixivPostHog.createSurveyClient(options));
    assert.strictEqual(attempts, 2);
}

async function main() {
    const initCalls = [];
    const fetchCalls = [];
    const storage = new Map();
    const clients = {};
    let optedOut = false;
    let capturing = true;
    let timeoutCallback = null;
    let fetchImpl = () => Promise.resolve({ok: true, status: 200});
    const sdk = {
        init(token, config, name) {
            initCalls.push({token, config, name});
            clients[name] = {
                name,
                has_opted_out_capturing() { return optedOut; },
                is_capturing() { return capturing; }
            };
            return clients[name];
        }
    };
    const sandbox = {
        window: null, console, Promise, Object, URL, Uint8Array, AbortController,
        setTimeout(callback) {
            timeoutCallback = callback;
            return 1;
        },
        clearTimeout() { timeoutCallback = null; },
        posthog: sdk, crypto: webcrypto,
        localStorage: {
            getItem(key) { return storage.has(key) ? storage.get(key) : null; },
            setItem(key, value) { storage.set(key, value); }
        },
        fetch(url, options) {
            fetchCalls.push({url, options});
            return fetchImpl(url, options);
        }
    };
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
        trustedApiOrigins: [posthog.apiHost],
        distinctId: 'plf_' + 'a'.repeat(64),
        beforeSend: filter
    });
    const reused = await api.createSurveyClient({
        ownerKey: 'download-workbench.layout-feedback',
        posthog,
        trustedApiOrigins: [posthog.apiHost],
        distinctId: 'plf_' + 'a'.repeat(64),
        beforeSend: filter
    });

    assert.strictEqual(first, reused);
    assert.strictEqual(initCalls.length, 1);
    assert.strictEqual(initCalls[0].config.bootstrap.distinctID, 'plf_' + 'a'.repeat(64));
    assert.strictEqual(initCalls[0].config.autocapture, false);
    assert.strictEqual(initCalls[0].config.before_send, filter);
    assert.strictEqual(initCalls[0].config.persistence, 'memory');
    assert.strictEqual(initCalls[0].config.disable_persistence, true);
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
    assert.strictEqual(await api.createSurveyClient({
        ownerKey: 'invalid.untrusted-custom-host',
        posthog,
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
    assert.match(initCalls[1].config.bootstrap.distinctID, /^ps_[0-9a-f]{64}$/);
    const third = await api.createSurveyClient({
        ownerKey: 'another-plugin.second-survey',
        posthog: {...posthog, surveyId: 'survey-three'},
        trustedApiOrigins: [posthog.apiHost],
        beforeSend: filter
    });
    assert.ok(third);
    assert.strictEqual(initCalls.length, 3);
    assert.match(initCalls[2].config.bootstrap.distinctID, /^ps_[0-9a-f]{64}$/);
    assert.notStrictEqual(initCalls[1].config.bootstrap.distinctID,
        initCalls[2].config.bootstrap.distinctID);
    const fourth = await api.createSurveyClient({
        ownerKey: 'another-plugin',
        posthog: {
            projectToken: 'phc_owner_four',
            surveyId: 'own-survey.survey-two',
            apiHost: 'https://eu.i.posthog.com',
            uiHost: 'https://eu.posthog.com'
        },
        beforeSend: filter
    });
    assert.ok(fourth);
    assert.strictEqual(initCalls.length, 4);
    assert.notStrictEqual(initCalls[1].config.bootstrap.distinctID,
        initCalls[3].config.bootstrap.distinctID);
    const customValues = new Map();
    const customOwner = 'embedded-survey';
    const customSurveyId = 'embedded-survey-id';
    const customKey = 'pixivdownload.posthog.survey-id.'
        + JSON.stringify([customOwner, customSurveyId]);
    const customId = 'ps_' + 'd'.repeat(64);
    customValues.set(customKey, customId);
    const customStorage = {
        getItem(key) { return customValues.has(key) ? customValues.get(key) : null; },
        setItem(key, value) { customValues.set(key, value); }
    };
    assert.ok(await api.createSurveyClient({
        ownerKey: customOwner,
        posthog: {...posthog, surveyId: customSurveyId},
        trustedApiOrigins: [posthog.apiHost],
        storage: customStorage,
        beforeSend: filter
    }));
    assert.strictEqual(initCalls.length, 5);
    assert.strictEqual(initCalls[4].config.bootstrap.distinctID, customId);
    assert.strictEqual(storage.has(customKey), false);
    const response = {
        '$survey_id': posthog.surveyId,
        '$survey_response_q1': 'Yes'
    };
    const submissionId = '018f35a1-7c40-8abc-8def-0123456789ab';
    await api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', {
            ...response,
            '$survey_response_q1': 'Changed answer'
        }, submissionId);
    assert.strictEqual(fetchCalls.length, 1);
    assert.strictEqual(fetchCalls[0].url, posthog.apiHost + '/e/');
    assert.strictEqual(fetchCalls[0].options.method, 'POST');
    assert.strictEqual(fetchCalls[0].options.credentials, 'omit');
    assert.ok(fetchCalls[0].options.signal);
    const firstPayload = JSON.parse(fetchCalls[0].options.body);
    assert.strictEqual(firstPayload.properties.distinct_id, 'plf_' + 'a'.repeat(64));
    assert.strictEqual(firstPayload.uuid, submissionId);
    assert.strictEqual(timeoutCallback, null);

    await api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, submissionId);
    assert.strictEqual(JSON.parse(fetchCalls[1].options.body).uuid, submissionId);

    const beforeInvalidSubmission = fetchCalls.length;
    await assert.rejects(api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response), /id is invalid/);
    await assert.rejects(api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, 'not-a-uuid'), /id is invalid/);
    assert.strictEqual(fetchCalls.length, beforeInvalidSubmission);

    fetchImpl = () => Promise.resolve({ok: false, status: 429});
    await assert.rejects(api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, submissionId), /not acknowledged/);
    fetchImpl = () => Promise.reject(new Error('network unavailable'));
    await assert.rejects(api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, submissionId), /network unavailable/);

    fetchImpl = () => Promise.resolve({ok: true, status: 200});
    optedOut = true;
    const fetchCount = fetchCalls.length;
    await assert.rejects(api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, submissionId), /disabled/);
    assert.strictEqual(fetchCalls.length, fetchCount);
    optedOut = false;
    capturing = false;
    await assert.rejects(api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, submissionId), /disabled/);
    assert.strictEqual(fetchCalls.length, fetchCount);
    capturing = true;

    fetchImpl = (url, options) => new Promise((resolve, reject) => {
        if (options.signal.aborted) {
            reject(new Error('request aborted'));
            return;
        }
        options.signal.addEventListener('abort', () => reject(new Error('request aborted')), {once: true});
    });
    const timedOut = api.captureSurveyWithAck(
        'download-workbench.layout-feedback', 'survey sent', response, submissionId);
    await Promise.resolve();
    assert.ok(timeoutCallback);
    timeoutCallback();
    await assert.rejects(timedOut, /request aborted/);
    assert.strictEqual(timeoutCallback, null);
    assert.deepStrictEqual(Object.keys(api), ['createSurveyClient', 'captureSurveyWithAck']);
    await testSynchronousLoadFailureCanRetry();
    console.log('pixiv-posthog.test.js: passed');
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
