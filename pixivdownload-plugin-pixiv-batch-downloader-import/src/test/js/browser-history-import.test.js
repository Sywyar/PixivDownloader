const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/userscripts/pixiv-batch-downloader-import.user.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve(); };

class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.listeners = {}; this.disabled = false; this.textContent = ''; this.files = []; }
    append(...nodes) { this.children.push(...nodes); }
    attachShadow() { this.shadow = new Element('shadow'); return this.shadow; }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(name, callback) { this.listeners[name] = callback; }
    showModal() { this.open = true; }
    close() { this.open = false; }
    remove() { this.removed = true; }
    focus() {}
    click() { if (!this.disabled) return this.listeners.click?.(); }
    all(tag) { return [...this.children, ...(this.shadow ? [this.shadow] : [])].flatMap(node => [...(node.tag === tag ? [node] : []), ...node.all(tag)]); }
}
function historyDatabase(options) {
    const reads = [], state = {closed: false, abortedCreation: false};
    const db = {objectStoreNames: {contains: () => !options.missingStore}, close: () => state.closed = true,
        transaction(name, mode) {
            assert.equal(mode, 'readonly'); assert.equal(state.closed, false); reads.push(name);
            let aborted = false;
            const tx = {abort() { aborted = true; queueMicrotask(() => tx.onabort?.()); },
                objectStore: () => ({keyPath: options.badKey ? 'other' : 'id',
                    openCursor(range) {
                        const request = {}, rows = (options.rows || [{id: '42_p0', n: '0.png'}, {id: '42_p1', n: '1.png'}]);
                        let index = name === 'record1' ? rows.findIndex(row => !range || row.id > range.after) : -1;
                        const next = () => queueMicrotask(() => {
                            if (aborted) return;
                            let continued = false;
                            request.result = index < 0 || index >= rows.length ? null : {value: rows[index], key: rows[index].id,
                                continue() { continued = true; index++; next(); }};
                            request.onsuccess();
                            if (!continued) queueMicrotask(() => { if (!aborted) tx.oncomplete?.(); });
                        });
                        next(); return request;
                    }})};
            return tx;
        }};
    return {reads, state, db, indexedDB: {open(name, version) {
        assert.equal(name, 'DLRecord'); assert.equal(version, undefined);
        state.closed = false;
        const request = {result: db, transaction: {abort: () => state.abortedCreation = true}};
        queueMicrotask(() => options.absent ? request.onupgradeneeded() : request.onsuccess());
        return request;
    }}};
}
function browser(options = {}) {
    const database = historyDatabase(options);
    const failCancellation = options.cancelFails;
    const body = new Element('body'), menus = new Map(), events = {}, calls = [], fetches = [], prompts = [], timers = new Map(), storage = new Map(options.storage);
    let nextTimer = 0, phase = 'UPLOADING', deferredFetch, fetchResponse, responseFactory, rejectCreate;
    const view = () => ({session: 'job', phase, total: 1, processed: phase === 'COMPLETED' ? 1 : 0,
        imported: phase === 'COMPLETED' ? 1 : 0, skipped: 0, failed: 0, problems: [],
        preview: ['READY', 'RUNNING', 'COMPLETED'].includes(phase) ? (options.preview ?? {records: 2, works: 1, candidates: 1, skipped: 0, unavailable: 0}) : null,
        metadata: phase === 'RUNNING' ? {id: 'metadata-1', kind: 'illust', workId: '42'} : null});
    const context = vm.createContext({indexedDB: database.indexedDB, IDBKeyRange: {lowerBound: after => ({after})}, URL, URLSearchParams, TextEncoder, Blob, AbortController, navigator: {language: 'en-US'},
        crypto: {randomUUID: () => 'tab'}, document: {body, createElement: tag => new Element(tag)},
        window: {location: {origin: 'https://www.pixiv.net'}, addEventListener: (name, fn) => events[name] = fn},
        GM_info: options.info, GM_registerMenuCommand: (name, fn) => menus.set(name, fn),
        GM_getValue: (key, fallback) => storage.get(key) ?? fallback, GM_setValue: (key, value) => storage.set(key, value),
        GM_deleteValue: key => storage.delete(key), GM_listValues: () => [...storage.keys()], alert() {},
        prompt: (message, value) => { prompts.push({message, value}); return options.promptValue ?? 'http://127.0.0.1:7999'; },
        setTimeout: (fn, ms) => { const id = ++nextTimer; timers.set(id, {fn, ms}); return id; }, clearTimeout: id => timers.delete(id),
        fetch: (url, options) => {
            fetches.push({url, options});
            if (deferredFetch) return new Promise((resolve, reject) => {
                fetchResponse = resolve;
                options.signal.addEventListener('abort', () => reject(new Error('aborted')));
            });
            return Promise.resolve(responseFactory ? responseFactory() : new Response('{"error":false,"body":{"id":"42"}}', {status: 200}));
        },
        GM_xmlhttpRequest: options => {
            calls.push(options);
            let result;
            if (options.method === 'GET') result = {token: 'once'};
            else {
                const action = new URL(options.url).searchParams.get('action');
                if (action === 'create') phase = 'UPLOADING';
                if (action === 'cancel' && failCancellation) {
                    options.onerror();
                    return {abort: () => options.onabort()};
                }
                if (action === 'create' && rejectCreate) {
                    options.onload({status: 409, finalUrl: options.url, responseText: JSON.stringify({error: 'Save the original files folder first.'})});
                    return {abort: () => options.onabort()};
                }
                if (action === 'inspect') phase = 'READY';
                if (action === 'start') phase = 'RUNNING';
                if (action === 'reply') phase = new URL(options.url).searchParams.get('status') === '200' ? 'COMPLETED' : 'FAILED';
                if (action === 'cancel') phase = 'CANCELLED';
                result = view();
            }
            options.onload({status: 200, finalUrl: options.url, responseText: JSON.stringify(result)});
            return {abort: () => options.onabort()};
        }
    });
    vm.runInContext(source, context);
    menus.get('Read browser download history')();
    const buttons = body.all('button'), input = body.all('input')[0];
    return {database, body, buttons, input, calls, fetches, prompts, timers, storage, events, menus,
        deferFetch: () => deferredFetch = true, releaseFetch: () => fetchResponse?.(new Response('{}')),
        response: factory => responseFactory = factory,
        rejectCreate: () => rejectCreate = true,
        tick: async () => { const entry = [...timers.entries()].find(([, timer]) => timer.ms === 1000); timers.delete(entry[0]); entry[1].fn(); await flush(); }};
}
const actions = b => b.calls.filter(call => call.method === 'POST').map(call => new URL(call.url).searchParams.get('action'));

test('无候选预览显示逐作品原因，释放会话并允许重新检查', async () => {
    const options = {preview: {records: 10, works: 8, candidates: 0, skipped: 1, unavailable: 7,
        samples: [
            {workKey: 'artwork:42', files: 2, disposition: 'ALREADY_PRESENT'},
            {workKey: 'artwork:43', files: 1, disposition: 'MISSING_FILES'},
            {workKey: 'artwork:44', files: 1, disposition: 'CONFLICT'}
        ]}};
    const b = browser(options); await flush();
    const idleTimers = [...b.timers];
    const done = b.buttons[0].click(); await flush();
    assert.equal(b.buttons[0].disabled, false);
    await done;
    assert.equal(b.buttons[1].disabled, true);
    assert.equal(b.buttons[2].textContent, 'Close');
    assert.deepEqual(actions(b), ['create', 'upload', 'upload', 'inspect', 'cancel']);
    assert.equal(b.fetches.length, 0);
    assert.deepEqual([...b.timers], idleTimers);
    const details = b.body.all('pre')[0].textContent;
    assert.match(details, /artwork:42: Already registered/);
    assert.match(details, /artwork:43: Original files unavailable or pages incomplete/);
    assert.match(details, /artwork:44: Path or record conflict/);
    assert.match(details, /3 \/ 8/);
    assert.match(b.body.all('p').at(-1).textContent, /Check complete/);
    options.preview = {records: 2, works: 1, candidates: 1, skipped: 0, unavailable: 0, samples: []};
    const retry = b.buttons[0].click(); await flush();
    assert.equal(b.buttons[1].disabled, false);
    b.buttons[1].click(); await flush(); await retry;
    assert.equal(actions(b).filter(action => action === 'start').length, 1);
    assert.equal(b.fetches.length, 1);
});

test('空历史和全部已存在均结束检查，不等待导入或请求元数据', async () => {
    for (const count of [0, 8]) {
        const b = browser({preview: {records: count, works: count, candidates: 0, skipped: count, unavailable: 0, samples: []}});
        await flush();
        const idleTimers = [...b.timers];
        const done = b.buttons[0].click(); await flush();
        assert.equal(b.buttons[0].disabled, false);
        await done;
        assert.equal(b.buttons[1].disabled, true);
        assert.deepEqual([...b.timers], idleTimers);
        assert.equal(b.fetches.length, 0);
        assert.equal(actions(b).includes('start'), false);
    }
});

test('无候选会话释放失败时保留未确认提示，不能直接重试', async () => {
    const b = browser({cancelFails: true, preview: {records: 1, works: 1, candidates: 0, skipped: 0, unavailable: 1, samples: []}});
    await flush();
    const idleTimers = [...b.timers];
    b.buttons[0].click(); await flush();
    assert.equal(actions(b).at(-1), 'cancel');
    assert.equal(b.buttons[0].disabled, true);
    assert.equal(b.buttons[1].disabled, true);
    assert.match(b.body.all('p').at(-1).textContent, /unconfirmed/);
    assert.deepEqual([...b.timers], idleTimers);
});

test('历史预览及导入全过程使用安装来源，地址菜单使用相同默认值', async () => {
    for (const origin of ['http://localhost:17319', 'http://127.0.0.1:18427', 'http://[::1]:19531', 'https://localhost:16443']) {
        const url = origin + '/api/scripts/pixiv-batch-downloader-import.user.js';
        for (const info of [{script: {downloadURL: url}}, {script: {updateURL: url}}, {scriptUpdateURL: url}]) {
            const b = browser({info, promptValue: origin}); await flush();
            const done = b.buttons[0].click(); await flush();
            assert.deepEqual(actions(b), ['create', 'upload', 'upload', 'inspect']);
            assert.equal(b.buttons[1].disabled, false);
            b.buttons[1].click(); await flush(); await done;
            assert.ok(b.calls.length > 0);
            assert.ok(b.calls.every(call => new URL(call.url).origin === origin));
            b.menus.get('Set local server address')();
            assert.equal(b.prompts[0].value, origin);
            assert.ok(b.prompts[0].message.includes(origin));
        }
    }
});

test('已保存的服务地址覆盖安装默认值，非法修改恢复当前地址', async () => {
    const installed = 'http://localhost:17319', saved = 'http://127.0.0.1:18427';
    const b = browser({info: {scriptUpdateURL: installed + '/api/scripts/pixiv-batch-downloader-import/install'},
        storage: [['pixiv-batch-downloader-import.server', saved]], promptValue: 'https://remote.example'});
    await flush();
    b.menus.get('Set local server address')(); await flush();
    assert.equal(b.prompts[0].value, saved);
    assert.ok(b.prompts[0].message.includes(installed));
    assert.equal(b.storage.get('pixiv-batch-downloader-import.server'), saved);
    b.buttons[0].click(); await flush();
    assert.deepEqual(actions(b), ['create', 'upload', 'upload', 'inspect']);
    assert.ok(b.calls.every(call => new URL(call.url).origin === saved));
});

test('远端安装来源不会被当成本机服务，也不会退回默认端口发送历史', async () => {
    const b = browser({info: {scriptUpdateURL: 'https://remote.example/api/scripts/pixiv-batch-downloader-import.user.js'}});
    await flush(); await b.buttons[0].click(); await flush();
    assert.equal(b.calls.length, 0);
    assert.equal(b.database.reads.length, 0);
});

test('直接安装源文件时使用本机缺省地址', async () => {
    const b = browser({info: {script: {downloadURL: 'https://raw.githubusercontent.com/example/script.user.js'}}});
    await flush(); b.buttons[0].click(); await flush();
    assert.deepEqual(actions(b), ['create', 'upload', 'upload', 'inspect']);
    assert.ok(b.calls.every(call => new URL(call.url).origin === 'http://localhost:6999'));
});

test('直接只读扫描浏览器历史，确认前不请求元数据，登录 Cookie 不发送本机', async () => {
    const b = browser(); await flush();

    const done = b.buttons[0].click(); await flush();
    assert.deepEqual(actions(b), ['create', 'upload', 'upload', 'inspect']);
    assert.equal(b.fetches.length, 0); assert.equal(b.buttons[1].disabled, false);
    assert.equal(b.storage.size, 0);
    const chunks = b.calls.filter(call => new URL(call.url).searchParams.get('action') === 'upload');
    assert.equal(b.input, undefined);
    assert.deepEqual(JSON.parse(await new Blob(chunks.map(call => call.data)).text()), [{id: '42_p0', n: '0.png'}, {id: '42_p1', n: '1.png'}]);
    assert.equal(b.database.reads.length, 9); assert.equal(b.database.state.closed, true);
    assert.equal(new URL(chunks[1].url).searchParams.get('offset'), String(chunks[0].data.size));
    b.buttons[1].click(); await flush(); await done;
    assert.equal(b.fetches.length, 1); assert.equal(b.fetches[0].url, '/ajax/illust/42');
    assert.equal(b.fetches[0].options.credentials, 'same-origin');
    assert.equal(b.fetches[0].options.redirect, 'error');
    assert.ok(b.calls.every(call => call.anonymous && !Object.keys(call.headers).some(key => key.toLowerCase() === 'cookie')));
    const reply = b.calls.find(call => new URL(call.url).searchParams.get('action') === 'reply');
    assert.equal(new URL(reply.url).searchParams.get('request'), 'metadata-1');
    assert.match(await reply.data.text(), /"id":"42"/);
    assert.match(b.body.all('p').at(-1).textContent, /Processing finished/);
    assert.equal(b.buttons[2].textContent, 'Close');
});

test('取消浏览器元数据等待后不发送迟到响应，后端确认后才显示取消', async () => {
    const b = browser(); await flush(); b.deferFetch();

    const done = b.buttons[0].click(); await flush();
    b.buttons[1].click(); await flush();
    assert.equal(b.fetches.length, 1);
    b.buttons[2].click(); await flush(); await done;
    assert.equal(b.fetches[0].options.signal.aborted, true);
    assert.ok(actions(b).includes('cancel')); assert.ok(!actions(b).includes('reply'));
    assert.match(b.body.all('p').at(-1).textContent, /Cancelled/);
    b.releaseFetch(); await flush(); assert.ok(!actions(b).includes('reply'));
});

test('切换服务地址只向原地址取消历史会话，新地址不接收旧数据', async () => {
    const b = browser(); await flush(); b.deferFetch();

    const done = b.buttons[0].click(); await flush(); b.buttons[1].click(); await flush();
    b.menus.get('Set local server address')(); await flush(); await done;
    assert.equal(b.fetches[0].options.signal.aborted, true);
    assert.ok(b.calls.every(call => new URL(call.url).origin === 'http://localhost:6999'));
    assert.ok(actions(b).includes('cancel')); assert.ok(!actions(b).includes('reply'));
});

test('离页取消预览，不自动启动', async () => {
    const b = browser(); await flush();


    const done = b.buttons[0].click(); await flush();
    b.events.pagehide(); await flush(); await done;
    assert.ok(actions(b).includes('cancel')); assert.ok(!actions(b).includes('start'));
    assert.equal(b.fetches.length, 0);
});

for (const declared of [true, false]) {
    test(`元数据${declared ? '声明长度' : '实际响应流'}超过 4 MiB 时不转发正文`, async () => {
        const b = browser(); await flush();
        b.response(() => declared ? new Response('{}', {headers: {'Content-Length': String(4 * 1024 * 1024 + 1)}})
            : new Response(new Uint8Array(4 * 1024 * 1024 + 1)));

        const done = b.buttons[0].click(); await flush(); b.buttons[1].click(); await done;
        const reply = b.calls.find(call => new URL(call.url).searchParams.get('action') === 'reply');
        assert.equal(new URL(reply.url).searchParams.get('status'), '0');
        assert.equal(reply.data.size, 0);
        assert.match(b.body.all('p').at(-1).textContent, /Import incomplete/);
        assert.ok(b.calls.every(call => call.redirect === 'error'));
    });
}

test('本机明确拒绝创建时显示设置提示，不误报为未知结果或继续上传', async () => {
    const b = browser(); await flush(); b.rejectCreate();

    await b.buttons[0].click();
    assert.deepEqual(actions(b), ['create']);
    const message = b.body.all('p').at(-1).textContent;
    assert.match(message, /Save the original files folder first/);
    assert.doesNotMatch(message, /unconfirmed/);
    assert.equal(b.buttons[2].textContent, 'Close');
});

for (const options of [{absent: true}, {missingStore: true}, {badKey: true}, {rows: [{id: '42_p0', n: 'x', unexpected: 'field'}]}]) {
    test('历史库缺失或格式不符时停止，不创建或改写上游数据 ' + JSON.stringify(options), async () => {
        const b = browser(options); await flush(); await b.buttons[0].click();
        assert.ok(!actions(b).includes('inspect')); assert.ok(!actions(b).includes('start'));
        assert.equal(b.fetches.length, 0);
        if (options.absent) {
            assert.equal(b.database.state.abortedCreation, true); assert.equal(b.calls.length, 0);
        } else assert.equal(b.database.state.closed, true);
    });
}
test('多批读取完整保留 Unicode 和分表记录，每次上传有界且不保留 GM 副本', async () => {
    const rows = Array.from({length: 2301}, (_, index) => ({id: String(10000+index)+'_p0', n: '作品/'.repeat(120)+'原图.png'}));
    const b = browser({rows}); await flush();
    const done = b.buttons[0].click();
    for (let i = 0; i < 300 && !actions(b).includes('inspect'); i++) await flush();
    assert.ok(actions(b).includes('inspect'));
    const chunks = b.calls.filter(call => new URL(call.url).searchParams.get('action') === 'upload');
    assert.ok(chunks.length > 3); assert.ok(chunks.every(call => call.data.size <= 1024 * 1024));
    assert.deepEqual(JSON.parse(await new Blob(chunks.map(call => call.data)).text()), rows);
    assert.ok(b.database.reads.length > 9); assert.equal(b.storage.size, 0);
    b.buttons[2].click(); await flush(); await done;
});
test('读取时页面触发数据库升级会关闭连接并取消会话，不继续检查或导入', async () => {
    const b = browser({rows: Array.from({length: 1200}, (_, index) => ({id: String(10000+index)+'_p0', n: 'a.png'}))});
    const done = b.buttons[0].click();
    while (!b.database.db.onversionchange) await Promise.resolve();
    b.database.db.onversionchange();
    for (let i = 0; i < 100; i++) await flush();
    await done;
    assert.equal(b.database.state.closed, true);
    assert.ok(!actions(b).includes('inspect')); assert.ok(!actions(b).includes('start'));
});
