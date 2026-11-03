import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
try {
    const page = await browser.newPage();
    await page.goto(process.env.PAGE_ORIGIN + '/automatic/page');
    await page.evaluate(async ({revision, endpoints}) => {
        window.originalFetch = window.fetch; window.originalXHR = window.XMLHttpRequest;
        const {createHttpTargets} = await import('/js/pixiv-http-hooks.mjs');
        window.scope = new AbortController();
        const form = document.querySelector('form'); form.id = 'native-form'; form.querySelector('button').id = 'native-submit';
        const second = form.cloneNode(true); second.id = 'second-form'; second.querySelector('button').id = 'second-submit'; form.after(second);
        window.runtime = createHttpTargets(['save', 'native', 'other-native', 'upload'].map(id => ({target: id, operation: 'fixture:' + id,
            formId: id === 'native' ? 'native-form' : id === 'other-native' ? 'second-form' : '',
            actionId: id === 'native' ? 'native-submit' : id === 'other-native' ? 'second-submit' : '',
            requests: [{endpoint: endpoints[id.includes('native') ? 'nativeForm' : id], method: 'POST', url: '/automatic/' + (id === 'other-native' ? 'native' : id), parameters: id.includes('native') ? ['mode=chosen'] : [], headers: {'Content-Type': id.includes('native')
                ? 'application/x-www-form-urlencoded' : id === 'save' ? 'application/json' : 'application/octet-stream'}}]})),
        {revision, signal: scope.signal});
        window.states = {}; window.channels = {};
        for (const [id, target] of Object.entries(runtime.targets)) {
            channels[id] = target.connect({instanceId: id, policy: 'unknown-callback', operation: target.operation,
                protocol: {id: 'operation-verification', version: 1, stateSchema: '{"type":"string"}', responseSchema: '{"type":"string"}'}}, scope.signal);
            channels[id].onState(state => { states[id] = state; });
        }
        window.solve = async id => { await runtime.targets[id].prepare(); if (states[id]) channels[id].respond(states[id].id, 'valid'); };
    }, {revision: process.env.PAGE_REVISION, endpoints: JSON.parse(process.env.PAGE_ENDPOINTS)});
    const blocked = await page.evaluate(async () => {
        const response = await fetch('/automatic/save', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{"value":"unchanged"}'});
        const result = await response.json();
        return response.status === 409 && result.code === 'HOOK_VERIFICATION_REQUIRED' && result.invoked === false && !!states.save;
    });
    assert.equal(blocked, true);
    const saved = await page.evaluate(async () => {
        await solve('save');
        const request = new Request(location.origin + '/automatic/save', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{"value":"unchanged"}'});
        const response = await fetch(request);
        return {status: response.status, body: await response.json()};
    });
    assert.equal(saved.status, 200); assert.equal(saved.body.value, 'unchanged');
    await page.locator('#native-submit').click();
    await page.waitForFunction(() => !!states.native);
    await page.evaluate(() => solve('native'));
    const nativeResponse = page.waitForResponse(response => response.url().endsWith('/automatic/native') && response.request().method() === 'POST');
    await page.locator('#native-submit').click();
    const native = await nativeResponse;
    assert.equal(native.status(), 200); assert.equal((await native.json()).value, 'native-value');
    await page.locator('#second-submit').click();
    await page.waitForFunction(() => !!states['other-native']);
    await page.evaluate(() => solve('other-native'));
    const secondResponse = page.waitForResponse(response => response.url().endsWith('/automatic/native') && response.request().method() === 'POST');
    await page.locator('#second-submit').click(); assert.equal((await secondResponse).status(), 200);
    const upload = await page.evaluate(async () => {
        await solve('upload');
        return await new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest(); xhr.open('POST', '/automatic/upload'); xhr.setRequestHeader('Content-Type', 'application/octet-stream');
            xhr.responseType = 'json'; xhr.onload = () => resolve({status: xhr.status, body: xhr.response}); xhr.onerror = () => reject(new Error('upload failed'));
            xhr.send(new Blob([new Uint8Array(17000)]));
        });
    });
    assert.equal(upload.status, 200); assert.equal(upload.body.bytes, 17000);
    // 挂起真实原生请求，使另一个标签动作在第一张票据仍有效时进入宿主管线。
    await page.evaluate(() => {
        const frame = document.createElement('iframe'); frame.name = 'second-result'; document.body.append(frame);
        document.querySelector('#second-form').target = frame.name;
    });
    for (const mode of ['native', 'fetch']) {
        const held = [];
        await page.route('**/automatic/native', route => { held.push(route); });
        await page.locator('#native-submit').click();
        await page.waitForFunction(() => !!states.native); await page.evaluate(() => solve('native'));
        await page.locator('#native-submit').click();
        for (let i = 0; held.length < 1 && i < 100; i++) await page.waitForTimeout(20);
        assert.equal(held.length, 1);
        const cookies = (await page.context().cookies()).filter(cookie => cookie.name.startsWith('pixiv_hook_ticket'));
        assert.equal(cookies.length, 1);
        assert.equal(await page.evaluate(async () => (await originalFetch('/automatic/observe', {method: 'POST'})).status), 200);
        assert.deepEqual((await page.context().cookies()).filter(cookie => cookie.name.startsWith('pixiv_hook_ticket')), cookies);
        if (mode === 'native') {
            await page.locator('#second-submit').click();
            await page.waitForFunction(() => !!states['other-native']); await page.evaluate(() => solve('other-native'));
            await page.locator('#second-submit').click();
            for (let i = 0; held.length < 2 && i < 100; i++) await page.waitForTimeout(20);
            assert.equal(held.length, 2);
            assert.equal((await page.context().cookies()).filter(cookie => cookie.name.startsWith('pixiv_hook_ticket')).length, 2);
        } else {
            await page.evaluate(() => solve('save'));
            assert.equal(await page.evaluate(async () => (await fetch('/automatic/save', {method: 'POST', headers: {'Content-Type': 'application/json'},
                body: '{"value":"concurrent"}'})).status), 200);
        }
        const responses = held.map(route => page.waitForResponse(response => response.request() === route.request()));
        await Promise.all(held.map(route => route.continue()));
        assert.deepEqual(await Promise.all(responses.map(async response => (await response).status())), held.map(() => 200));
        await page.unroute('**/automatic/native');
        assert.equal((await page.context().cookies()).filter(cookie => cookie.name.startsWith('pixiv_hook_ticket')).length, 0);
    }
    await page.locator('#native-submit').click(); await page.waitForFunction(() => !!states.native);
    await page.evaluate(async () => {
        await solve('native'); window.cancelledNative = false;
        document.querySelector('#native-form').addEventListener('submit', event => { event.preventDefault(); cancelledNative = true; }, {once: true});
    });
    await page.locator('#native-submit').click(); await page.waitForFunction(() => cancelledNative && !runtime.targets.native.ready());
    assert.equal(await page.evaluate(async () => (await originalFetch('/automatic/observe', {method: 'POST'})).status), 200);
    await page.locator('#native-submit').click(); await page.waitForFunction(() => !!states.native); await page.evaluate(() => solve('native'));
    const resumed = page.waitForResponse(response => response.url().endsWith('/automatic/native') && response.request().method() === 'POST');
    await page.locator('#native-submit').click(); assert.equal((await resumed).status(), 200);
    console.log('NATIVE_TICKET_ISOLATION_PASSED native-native native-fetch after-only cancelled-retry');
    // 故障注入只替换准备响应；生产运行时和原生表单事件照常执行。
    for (const transport of ['fetch', 'native']) for (const reason of ['HOOK_STALE', 'HOOK_TARGET_STALE', 'network', 'cancel']) {
        const envelopes = []; let ticket = 0;
        await page.route('**/api/operation-hooks/prepare', async route => {
            const envelope = route.request().postDataJSON(); envelopes.push(envelope);
            if (!envelope.token) return route.fulfill({status: 409, contentType: 'application/json', body: JSON.stringify({
                code: 'HOOK_CHALLENGE', invoked: false, revision: process.env.PAGE_REVISION,
                token: 'fixture-ticket-' + ++ticket, challenges: {'unknown-callback': 'question'}})});
            if (reason === 'network') return route.abort('failed');
            if (reason === 'cancel') {
                await page.evaluate(transport => transport === 'fetch' ? window.stageAbort.abort() : window.runtime.targets.native.reset(), transport);
                return route.abort('aborted');
            }
            return route.fulfill({status: 409, contentType: 'application/json', body: JSON.stringify({code: reason, invoked: false})});
        });
        const id = transport === 'fetch' ? 'save' : 'native';
        await page.evaluate(async id => { runtime.targets[id].reset(); await solve(id); window.stageAbort = new AbortController(); }, id);
        if (transport === 'fetch') await page.evaluate(async () => {
            try { await fetch('/automatic/save', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{"value":"unchanged"}', signal: stageAbort.signal}); }
            catch (error) { if (!['AbortError', 'TypeError'].includes(error.name)) throw error; }
        });
        else await page.locator('#native-submit').click();
        await page.waitForFunction(id => !runtime.targets[id].ready() && states[id] === null, id);
        if (transport === 'fetch') assert.equal(await page.evaluate(async () => (await fetch('/automatic/save', {
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{"value":"unchanged"}'})).status), 409);
        else await page.locator('#native-submit').click();
        await page.waitForFunction(id => !!states[id], id);
        assert.deepEqual(envelopes.map(value => value.token), ['', 'fixture-ticket-1', '']);
        assert.equal(await page.evaluate(id => runtime.targets[id].ready(), id), false);
        await page.unroute('**/api/operation-hooks/prepare');
    }
    await page.evaluate(async ({revision, endpoints}) => {
        const {createHttpTargets} = await import('/js/pixiv-http-hooks.mjs');
        const routes = {item: '/automatic/items/{id}', code: '/automatic/codes/{id:[A-Z]{2}\\d{2}}.json', files: '/automatic/files/{*path}'};
        window.dynamic = createHttpTargets(Object.entries(routes).map(([id, url]) => ({target: id, operation: 'fixture:' + id,
            requests: [{endpoint: endpoints[id], method: 'POST', url}]})), {revision, signal: scope.signal});
        for (const [id, target] of Object.entries(dynamic.targets)) {
            channels[id] = target.connect({instanceId: id, policy: 'unknown-callback', operation: target.operation,
                protocol: {id: 'operation-verification', version: 1, stateSchema: '{"type":"string"}', responseSchema: '{"type":"string"}'}}, scope.signal);
            channels[id].onState(state => { states[id] = state; });
        }
        const form = document.createElement('form'); form.id = 'dynamic-form'; form.method = 'post'; form.target = 'result';
        const button = document.createElement('button'); button.id = 'dynamic-submit'; button.textContent = 'Save';
        form.append(button); document.body.append(form);
    }, {revision: process.env.PAGE_REVISION, endpoints: JSON.parse(process.env.PAGE_ENDPOINTS)});
    let dynamicCalls = 0;
    for (const [id, path, expected] of [['item', '/automatic/items/42', '42'], ['code', '/automatic/codes/AB42.json', 'AB42'], ['files', '/automatic/files/a/b', '/a/b']]) {
        for (const transport of ['fetch', 'xhr']) {
            const result = await page.evaluate(async ({id, path, transport}) => {
                const send = () => transport === 'fetch' ? fetch(path, {method: 'POST'}) : new Promise(resolve => {
                    const xhr = new XMLHttpRequest(); xhr.open('POST', path);
                    xhr.onload = () => resolve({status: xhr.status, json: async () => JSON.parse(xhr.responseText)});
                    xhr.onerror = () => resolve({status: 409}); xhr.send();
                });
                const blocked = await send();
                if (blocked.status !== 409 || !states[id]) throw new Error('Missing dynamic challenge: ' + id + '/' + transport);
                channels[id].respond(states[id].id, 'valid');
                const response = await send(); return {status: response.status, body: await response.json()};
            }, {id, path, transport});
            assert.equal(result.status, 200); assert.equal(result.body.value, expected); assert.equal(result.body.calls, ++dynamicCalls);
        }
        await page.evaluate(path => { document.querySelector('#dynamic-form').action = path; }, path);
        await page.locator('#dynamic-submit').click(); await page.waitForFunction(id => !!states[id], id);
        await page.evaluate(id => channels[id].respond(states[id].id, 'valid'), id);
        const response = page.waitForResponse(value => value.url().endsWith(path) && value.request().method() === 'POST');
        await page.locator('#dynamic-submit').click(); const submitted = await response;
        assert.equal(submitted.status(), 200); const body = await submitted.json();
        assert.equal(body.value, expected); assert.equal(body.calls, ++dynamicCalls);
    }
    const emptyRest = await page.evaluate(async () => {
        const blocked = await fetch('/automatic/files', {method: 'POST'});
        if (blocked.status !== 409 || !states.files) throw new Error('Missing empty catch-all challenge');
        channels.files.respond(states.files.id, 'valid');
        return (await fetch('/automatic/files', {method: 'POST'})).json();
    });
    assert.equal(emptyRest.value, ''); assert.equal(emptyRest.calls, ++dynamicCalls);
    assert.equal(await page.evaluate(async () => (await fetch('/automatic/codes/invalid.json', {method: 'POST'})).status), 400);
    console.log('DYNAMIC_HTTP_HOOK_ROUTES_PASSED fetch xhr native regex catch-all empty-rest');
    await new Promise(resolve => setTimeout(resolve, 61000 - Date.now() % 60000));
    await page.evaluate(async ({revision, endpoints}) => {
        const {createHttpTargets} = await import('/js/pixiv-http-hooks.mjs');
        const entries = ['draft', 'publishForm', 'headerDraft', 'headerPublish', 'json', 'text'].map(id => ({
            target: id, operation: 'fixture:' + id, requests: [{endpoint: endpoints[id], method: 'POST',
                url: '/automatic/' + (id.startsWith('header') ? 'header' : ['json', 'text'].includes(id) ? 'media' : 'conditional'),
                parameters: id === 'draft' || id === 'publishForm' ? ['mode=' + (id === 'draft' ? 'draft' : 'publish')] : [],
                headerConditions: id.startsWith('header') ? ['X-Mode=' + (id === 'headerDraft' ? 'draft' : 'publish')] : [],
                consumes: ['json', 'text'].includes(id) ? [id === 'json' ? 'application/json' : 'text/plain'] : [],
                produces: ['json', 'text'].includes(id) ? ['application/json'] : []}]}));
        window.conditional = createHttpTargets(entries, {revision, signal: scope.signal});
        for (const [id, target] of Object.entries(conditional.targets)) {
            channels[id] = target.connect({instanceId: id, policy: 'unknown-callback', operation: target.operation,
                protocol: {id: 'operation-verification', version: 1, stateSchema: '{"type":"string"}', responseSchema: '{"type":"string"}'}}, scope.signal);
            channels[id].onState(state => { states[id] = state; });
        }
        window.conditionalEntries = entries;
    }, {revision: process.env.PAGE_REVISION, endpoints: JSON.parse(process.env.PAGE_ENDPOINTS)});
    for (const transport of ['fetch', 'xhr']) for (const id of ['draft', 'publishForm', 'headerDraft', 'headerPublish', 'json', 'text']) {
        const result = await page.evaluate(async ({transport, id}) => {
            const entry = conditionalEntries.find(value => value.target === id).requests[0];
            const headers = id.startsWith('header') ? {'X-Mode': id === 'headerDraft' ? 'draft' : 'publish'}
                : {Accept: 'application/json', 'Content-Type': id === 'json' ? 'application/json' : id === 'text' ? 'text/plain' : 'application/x-www-form-urlencoded'};
            const body = id === 'json' ? '{}' : id === 'text' ? 'plain' : 'mode=' + (id === 'draft' ? 'draft' : 'publish');
            const send = async () => transport === 'fetch' ? fetch(entry.url, {method: 'POST', headers, body}) : new Promise(resolve => {
                const xhr = new XMLHttpRequest(); xhr.open('POST', entry.url);
                for (const [name, value] of Object.entries(headers)) xhr.setRequestHeader(name, value);
                xhr.onload = () => resolve({status: xhr.status, json: async () => JSON.parse(xhr.responseText)});
                xhr.onerror = () => resolve({status: 409}); xhr.send(body);
            });
            const blocked = await send();
            if (blocked.status !== 409 || !states[id]) throw new Error('Wrong conditional target: ' + id);
            channels[id].respond(states[id].id, 'valid');
            const response = await send(); return {status: response.status, value: await response.json()};
        }, {transport, id});
        assert.equal(result.status, 200); assert.equal(result.value.selected, id);
    }
    await page.evaluate(() => {
        const form = document.createElement('form'); form.id = 'conditional-form'; form.method = 'post'; form.action = '/automatic/conditional'; form.target = 'result';
        const input = document.createElement('input'); input.name = 'mode'; input.value = 'publish';
        const button = document.createElement('button'); button.id = 'conditional-submit'; button.textContent = 'Publish'; form.append(input, button); document.body.append(form);
    });
    await page.locator('#conditional-submit').click(); await page.waitForFunction(() => !!states.publishForm);
    await page.evaluate(() => channels.publishForm.respond(states.publishForm.id, 'valid'));
    const conditionalNative = page.waitForResponse(response => response.url().endsWith('/automatic/conditional') && response.request().method() === 'POST');
    await page.locator('#conditional-submit').click(); assert.equal((await (await conditionalNative).json()).selected, 'publishForm');
    await page.evaluate(() => {
        document.querySelector('#conditional-form').addEventListener('submit', event => {
            event.preventDefault();
            window.ajaxResult = fetch(event.target.action, {method: 'POST',
                body: new URLSearchParams(new FormData(event.target))}).then(async response => ({status: response.status, value: await response.json()}));
        });
    });
    await page.locator('#conditional-submit').click(); await page.waitForFunction(() => !!states.publishForm);
    await page.evaluate(() => channels.publishForm.respond(states.publishForm.id, 'valid'));
    await page.locator('#conditional-submit').click(); await page.waitForFunction(() => !!window.ajaxResult);
    const ajaxHandoff = await page.evaluate(() => window.ajaxResult);
    assert.equal(ajaxHandoff.status, 200); assert.equal(ajaxHandoff.value.selected, 'publishForm');
    const ambiguous = await page.evaluate(async revision => {
        const {createHttpTargets} = await import('/js/pixiv-http-hooks.mjs');
        const duplicate = createHttpTargets([{...conditionalEntries[0], target: 'duplicate'}], {revision, signal: scope.signal});
        try {
            const response = await fetch('/automatic/conditional?mode=draft', {method: 'POST'});
            return response.status === 409 && (await response.json()).code === 'HOOK_TARGET_AMBIGUOUS';
        }
        finally { duplicate.destroy(); }
    }, process.env.PAGE_REVISION);
    assert.equal(ambiguous, true);
    console.log('HOOK_STALE_RECOVERY_FETCH_NATIVE_AND_MVC_CONDITIONS_FETCH_XHR_FORM_PASSED');
    const cancelled = await page.evaluate(async () => {
        const abort = new AbortController(); abort.abort();
        let fetchCancelled = false, synchronousRejected = false;
        try { await fetch('/automatic/save', {method: 'POST', signal: abort.signal}); } catch (error) { fetchCancelled = error.name === 'AbortError'; }
        try { new XMLHttpRequest().open('POST', '/automatic/upload', false); } catch (error) { synchronousRejected = error.name === 'NotSupportedError'; }
        const xhrCancelled = await new Promise(resolve => {
            const xhr = new XMLHttpRequest(); xhr.open('POST', '/automatic/upload'); xhr.onabort = () => resolve(true); xhr.send(new Blob(['cancel'])); xhr.abort();
        });
        scope.abort();
        return {fetchCancelled, synchronousRejected, xhrCancelled, restored: fetch === originalFetch && XMLHttpRequest === originalXHR};
    });
    assert.deepEqual(cancelled, {fetchCancelled: true, synchronousRejected: true, xhrCancelled: true, restored: true});
    console.log('AUTOMATIC_HTTP_HOOK_BROWSER_PASSED native-submit fetch-Request xhr-upload cancellation disposal');
} finally { await browser.close(); }
