'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'js', 'pixiv-survey-frame-bridge.js'), 'utf8');

class FakePort {
    constructor() {
        this.peer = null;
        this.onmessage = null;
        this.closed = false;
        this.started = false;
    }

    postMessage(data) {
        if (!this.closed && this.peer && typeof this.peer.onmessage === 'function') {
            this.peer.onmessage({data});
        }
    }

    start() {
        this.started = true;
    }

    close() {
        this.closed = true;
    }
}

class FakeMessageChannel {
    constructor() {
        this.port1 = new FakePort();
        this.port2 = new FakePort();
        this.port1.peer = this.port2;
        this.port2.peer = this.port1;
    }
}

function baseSandbox() {
    return {
        console,
        Promise,
        Object,
        Array,
        Map,
        Set,
        URL,
        Headers,
        Response,
        AbortController,
        DOMException,
        TextEncoder,
        ArrayBuffer,
        Uint8Array,
        MessageChannel: FakeMessageChannel,
        location: {origin: 'http://127.0.0.1:48731', href: 'http://127.0.0.1:48731/page'}
    };
}

async function tick() {
    await new Promise(resolve => setImmediate(resolve));
}

async function testHostCapabilities() {
    const stored = new Map([['allowed-key', 'initial'], ['private-key', 'secret']]);
    const fetchCalls = [];
    let declaredBodyReads = 0;
    let declaredBodyCancels = 0;
    let chunkedBodyReads = 0;
    let chunkedBodyCancels = 0;
    let slowBodyReads = 0;
    let slowBodyCancels = 0;
    let resolveSlowRead;
    const sandbox = baseSandbox();
    sandbox.localStorage = {
        getItem(key) { return stored.has(key) ? stored.get(key) : null; },
        setItem(key, value) { stored.set(key, value); },
        removeItem(key) { stored.delete(key); }
    };
    sandbox.fetch = async (url, init) => {
        fetchCalls.push({url, init});
        if (url === '/api/declared-large') {
            return {
                status: 200,
                statusText: 'OK',
                headers: new Headers({'Content-Length': String(1024 * 1024 + 1)}),
                body: {
                    cancel() { declaredBodyCancels++; return Promise.resolve(); },
                    getReader() { declaredBodyReads++; throw new Error('body must not be read'); }
                }
            };
        }
        if (url === '/api/chunked-large') {
            const chunks = [new Uint8Array(1024 * 1024), new Uint8Array(1)];
            return {
                status: 200,
                statusText: 'OK',
                headers: new Headers(),
                body: {
                    getReader() {
                        return {
                            read() {
                                const value = chunks[chunkedBodyReads++];
                                return Promise.resolve(value ? {done: false, value} : {done: true});
                            },
                            cancel() { chunkedBodyCancels++; return Promise.resolve(); },
                            releaseLock() {}
                        };
                    }
                }
            };
        }
        if (url === '/api/slow') {
            return {
                status: 200,
                statusText: 'OK',
                headers: new Headers(),
                body: {
                    getReader() {
                        return {
                            read() {
                                slowBodyReads++;
                                return new Promise(resolve => { resolveSlowRead = resolve; });
                            },
                            cancel() {
                                slowBodyCancels++;
                                if (resolveSlowRead) resolveSlowRead({done: true});
                                return Promise.resolve();
                            },
                            releaseLock() {}
                        };
                    }
                }
            };
        }
        return new Response(JSON.stringify({ok: true}), {
            status: 200,
            headers: {'Content-Type': 'application/json'}
        });
    };
    sandbox.window = sandbox;
    sandbox.parent = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox, {filename: 'pixiv-survey-frame-bridge-host.js'});

    let active = true;
    let initMessage = null;
    let childPort = null;
    const listeners = {};
    const frame = {
        contentWindow: {
            postMessage(data, targetOrigin, ports) {
                assert.strictEqual(targetOrigin, '*');
                initMessage = data;
                childPort = ports[0];
            }
        },
        addEventListener(type, listener) { listeners[type] = listener; },
        removeEventListener(type, listener) {
            if (listeners[type] === listener) delete listeners[type];
        }
    };
    const uiMessages = [];
    const host = sandbox.PixivSurveyFrameBridge.createHost({
        isActive() { return active; },
        onMessage(_frame, data) { uiMessages.push(data); }
    });
    assert.strictEqual(host.attach(frame,
        '/survey.html?pixivBridgeGet=/api/allowed'
        + '&pixivBridgeGet=/api/declared-large'
        + '&pixivBridgeGet=/api/chunked-large'
        + '&pixivBridgeGet=/api/slow'
        + '&pixivBridgePost=/api/write'
        + '&pixivBridgeRead=allowed-key'
        + '&pixivBridgeWrite=allowed-key'), true);
    listeners.load();

    assert.deepStrictEqual(JSON.parse(JSON.stringify(initMessage.storage)), {'allowed-key': 'initial'});
    assert.deepStrictEqual(Array.from(initMessage.readKeys), ['allowed-key']);
    const responses = [];
    childPort.onmessage = event => responses.push(event.data);

    childPort.postMessage({
        type: 'pixiv-survey-bridge-fetch', id: 'allowed', url: '/api/allowed?x=1',
        method: 'GET', headers: {accept: 'application/json'}, body: null
    });
    await tick();
    assert.strictEqual(fetchCalls.length, 1);
    assert.strictEqual(fetchCalls[0].url, '/api/allowed?x=1');
    assert.strictEqual(fetchCalls[0].init.credentials, 'same-origin');
    assert.strictEqual(fetchCalls[0].init.redirect, 'error');
    assert.strictEqual(responses.find(item => item.id === 'allowed').ok, true);

    childPort.postMessage({
        type: 'pixiv-survey-bridge-fetch', id: 'denied', url: '/api/private',
        method: 'GET', headers: {}, body: null
    });
    assert.strictEqual(responses.find(item => item.id === 'denied').ok, false);
    assert.strictEqual(fetchCalls.length, 1);

    [
        {type: 'pixiv-survey-bridge-fetch', id: 'long-method', url: '/api/allowed',
            method: 'G'.repeat(100), headers: {}, body: null},
        {type: 'pixiv-survey-bridge-fetch', id: 'long-url', url: '/' + 'a'.repeat(5000),
            method: 'GET', headers: {}, body: null},
        {type: 'pixiv-survey-bridge-fetch', id: 'many-headers', url: '/api/allowed',
            method: 'GET', headers: {accept: 'a', Accept: 'b', ACCEPT: 'c'}, body: null}
    ].forEach(message => childPort.postMessage(message));
    assert.ok(['long-method', 'long-url', 'many-headers'].every(id =>
        responses.some(item => item.id === id && item.ok === false)));
    assert.strictEqual(fetchCalls.length, 1);

    childPort.postMessage({type: 'pixiv-survey-bridge-storage-set', key: 'private-key', value: 'changed'});
    assert.strictEqual(stored.get('private-key'), 'secret');
    childPort.postMessage({type: 'pixiv-survey-bridge-storage-set', key: 'allowed-key', value: 'changed'});
    assert.strictEqual(stored.get('allowed-key'), 'changed');
    assert.ok(responses.some(item => item.type === 'pixiv-survey-bridge-storage-update'
        && item.key === 'allowed-key' && item.value === 'changed'));

    childPort.postMessage({
        type: 'pixiv-survey-bridge-fetch', id: 'declared-large', url: '/api/declared-large',
        method: 'GET', headers: {}, body: null
    });
    childPort.postMessage({
        type: 'pixiv-survey-bridge-fetch', id: 'chunked-large', url: '/api/chunked-large',
        method: 'GET', headers: {}, body: null
    });
    await tick();
    assert.strictEqual(responses.find(item => item.id === 'declared-large').ok, false);
    assert.strictEqual(declaredBodyReads, 0);
    assert.strictEqual(declaredBodyCancels, 1);
    assert.strictEqual(responses.find(item => item.id === 'chunked-large').ok, false);
    assert.strictEqual(chunkedBodyReads, 2);
    assert.strictEqual(chunkedBodyCancels, 1);

    childPort.postMessage({type: 'pixiv-content-height', height: 320});
    await tick();
    assert.deepStrictEqual(uiMessages, [{type: 'pixiv-content-height', height: 320}]);

    active = false;
    childPort.postMessage({
        type: 'pixiv-survey-bridge-fetch', id: 'inactive', url: '/api/allowed',
        method: 'GET', headers: {}, body: null
    });
    assert.strictEqual(fetchCalls.length, 3);
    active = true;
    childPort.postMessage({
        type: 'pixiv-survey-bridge-fetch', id: 'slow', url: '/api/slow',
        method: 'GET', headers: {}, body: null
    });
    await tick();
    assert.strictEqual(slowBodyReads, 1);
    host.detach(frame);
    await tick();
    assert.strictEqual(slowBodyCancels, 1);
}

async function testChildHandshakeAndFetch() {
    const listeners = {};
    const nativeFetchCalls = [];
    const parent = {};
    const sandbox = baseSandbox();
    sandbox.parent = parent;
    sandbox.window = sandbox;
    sandbox.addEventListener = (type, listener) => { listeners[type] = listener; };
    sandbox.removeEventListener = (type, listener) => {
        if (listeners[type] === listener) delete listeners[type];
    };
    sandbox.dispatchEvent = () => true;
    sandbox.fetch = async url => {
        nativeFetchCalls.push(url);
        return new Response('external');
    };
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox, {filename: 'pixiv-survey-frame-bridge-child.js'});

    const wrong = new FakeMessageChannel();
    listeners.message({
        source: {},
        origin: sandbox.location.origin,
        data: {type: 'pixiv-survey-bridge-init', readKeys: [], writeKeys: [], storage: {}},
        ports: [wrong.port2]
    });
    assert.strictEqual(wrong.port2.started, false);

    const wrongOrigin = new FakeMessageChannel();
    listeners.message({
        source: parent,
        origin: 'http://attacker.example',
        data: {type: 'pixiv-survey-bridge-init', readKeys: [], writeKeys: [], storage: {}},
        ports: [wrongOrigin.port2]
    });
    assert.strictEqual(wrongOrigin.port2.started, false);

    const channel = new FakeMessageChannel();
    const parentMessages = [];
    channel.port1.onmessage = event => {
        parentMessages.push(event.data);
        if (event.data.type === 'pixiv-survey-bridge-fetch') {
            const body = new TextEncoder().encode('bridged').buffer;
            channel.port1.postMessage({
                type: 'pixiv-survey-bridge-fetch-response',
                id: event.data.id,
                ok: true,
                status: 200,
                statusText: 'OK',
                headers: {'content-type': 'text/plain'},
                body
            });
        }
    };
    listeners.message({
        source: parent,
        origin: sandbox.location.origin,
        data: {
            type: 'pixiv-survey-bridge-init',
            readKeys: ['state-key'],
            writeKeys: ['state-key'],
            storage: {'state-key': 'initial'}
        },
        ports: [channel.port2]
    });
    const bridge = await sandbox.PixivSurveyFrameBridge.ready();
    assert.strictEqual(bridge.storage.getItem('state-key'), 'initial');
    assert.strictEqual(bridge.storage.getItem('private-key'), null);
    bridge.storage.setItem('state-key', 'updated');
    assert.ok(parentMessages.some(item => item.type === 'pixiv-survey-bridge-storage-set'
        && item.key === 'state-key' && item.value === 'updated'));
    assert.throws(() => bridge.storage.setItem('private-key', 'denied'), /capability denied/);

    const response = await sandbox.fetch('/api/allowed', {headers: {Accept: 'text/plain'}});
    assert.strictEqual(await response.text(), 'bridged');
    assert.ok(parentMessages.some(item => item.type === 'pixiv-survey-bridge-fetch'
        && item.url === '/api/allowed'));
    assert.strictEqual(await (await sandbox.fetch('https://example.com/data')).text(), 'external');
    assert.deepStrictEqual(nativeFetchCalls, ['https://example.com/data']);
}

(async function main() {
    await testHostCapabilities();
    await testChildHandshakeAndFetch();
    console.log('pixiv-survey-frame-bridge.test.js: sandbox capability bridge passed ✓');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
