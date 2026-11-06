const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/userscripts/pixiv-batch-downloader-import.user.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve(); };

class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.listeners = {}; this.disabled = false; this.textContent = ''; this.files = []; this.style = {}; }
    append(...nodes) { for (const node of nodes) node.parent = this; this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    attachShadow() { this.shadow = new Element('shadow'); return this.shadow; }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(name, callback) { this.listeners[name] = callback; }
    showModal() { this.open = true; }
    close() { this.open = false; }
    remove() { this.removed = true; if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this); }
    focus() {}
    click() { if (!this.disabled) return this.listeners.click?.(); }
    all(tag) { return [...this.children, ...(this.shadow ? [this.shadow] : [])].flatMap(node => [...(node.tag === tag ? [node] : []), ...node.all(tag)]); }
}
function historyDatabase(options) {
    const reads = [], state = {closed: false, abortedCreation: false, probes: 0, opens: 0};
    const db = {objectStoreNames: {contains: () => !options.missingStore}, close: () => state.closed = true,
        transaction(name, mode) {
            assert.equal(mode, 'readonly'); assert.equal(state.closed, false); reads.push(name);
            if (Array.isArray(name)) {
                let pending = name.length, aborted = false;
                const tx = {abort() { aborted = true; queueMicrotask(() => tx.onabort?.()); },
                    objectStore: store => ({keyPath: options.badKey ? 'other' : 'id', openKeyCursor() {
                        state.probes++;
                        const request = {};
                        const complete = () => queueMicrotask(() => {
                            if (aborted) return;
                            request.result = store === (options.populatedStore || 'record1') && options.rows?.length !== 0 ? {key: '42_p0'} : null;
                            request.onsuccess();
                            if (--pending === 0) tx.oncomplete?.();
                        });
                        if (options.deferProbe) (state.pendingProbes ||= []).push(complete); else complete();
                        return request;
                    }})};
                return tx;
            }
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
        state.opens++;
        state.closed = false;
        const request = {result: db, transaction: {abort: () => state.abortedCreation = true}};
        const complete = () => queueMicrotask(() => options.absent ? request.onupgradeneeded() : options.blocked ? request.onblocked() : request.onsuccess());
        if (options.deferOpen) state.releaseOpen = complete; else complete();
        return request;
    }}};
}
function browser(options = {}) {
    const database = historyDatabase(options);
    const failCancellation = options.cancelFails;
    const body = new Element('body'), menus = new Map(), events = {}, calls = [], fetches = [], prompts = [], timers = new Map(), storage = options.storage instanceof Map ? options.storage : new Map(options.storage), logs = [];
    const document = {body, readyState: 'loading', visibilityState: options.hidden ? 'hidden' : 'visible',
        createElement: tag => new Element(tag), addEventListener: (name, fn) => events[name] = fn};
    const window = {location: {origin: options.origin || 'https://www.pixiv.net'}, addEventListener: (name, fn) => events[name] = fn};
    window.self = window; window.top = options.frame ? {} : window;
    let nextTimer = 0, phase = 'UPLOADING', deferredImport, rejectCreate;
    const view = () => ({session: 'job', phase, total: 1, processed: phase === 'COMPLETED' ? 1 : 0,
        imported: phase === 'COMPLETED' ? 1 : 0, skipped: 0, failed: 0, problems: [],
        preview: ['READY', 'RUNNING', 'COMPLETED'].includes(phase) ? (options.preview ?? {records: 2, works: 1, candidates: 1, skipped: 0, unavailable: 0}) : null,
        ...options.execution?.[phase]});
    const context = vm.createContext({indexedDB: database.indexedDB, IDBKeyRange: {lowerBound: after => ({after})}, URL, URLSearchParams, TextEncoder, Blob, AbortController, navigator: {language: options.language || 'en-US', locks: options.locks},
        crypto: {randomUUID: () => 'tab'}, document, window,
        console: {info: (...args) => logs.push(['info', ...args]), warn: (...args) => logs.push(['warn', ...args])},
        GM_info: options.info, GM_registerMenuCommand: (name, fn) => menus.set(name, fn),
        GM_getValue: (key, fallback) => storage.get(key) ?? fallback, GM_setValue: (key, value) => storage.set(key, value),
        GM_deleteValue: key => storage.delete(key), GM_listValues: () => [...storage.keys()], alert() {},
        prompt: (message, value) => { prompts.push({message, value}); return options.promptValue ?? 'http://127.0.0.1:7999'; },
        setTimeout: (fn, ms) => { const id = ++nextTimer; timers.set(id, {fn, ms}); return id; }, clearTimeout: id => timers.delete(id),
        fetch: (url, options) => {
            fetches.push({url, options});
            throw new Error('History import must not access Pixiv');
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
                    options.onload({status: 409, finalUrl: options.url, responseText: JSON.stringify({error: 'Directory unavailable\nChoose another folder.'})});
                    return {abort: () => options.onabort()};
                }
                if (action === 'inspect') phase = 'READY';
                if (action === 'start') phase = deferredImport ? 'RUNNING' : 'COMPLETED';
                if (action === 'cancel') phase = 'CANCELLED';
                result = view();
            }
            options.onload({status: 200, finalUrl: options.url, responseText: JSON.stringify(result)});
            return {abort: () => options.onabort()};
        }
    });
    vm.runInContext(source, context);
    if (!options.auto) menus.get('Read browser download history')();
    return {database, body, get buttons() { return body.all('button'); }, calls, fetches, prompts, timers, storage, events, menus, document, logs,
        ready: async () => { document.readyState = 'complete'; events.DOMContentLoaded(); await flush(); },
        deferImport: () => deferredImport = true,
        releaseImport: () => {
            if (phase === 'RUNNING') phase = 'COMPLETED';
            const entry = [...timers.entries()].filter(([, timer]) => timer.ms === 1000).at(-1);
            if (entry) { timers.delete(entry[0]); entry[1].fn(); }
        },
        rejectCreate: () => rejectCreate = true,
        tick: async () => { const entry = [...timers.entries()].find(([, timer]) => timer.ms === 1000); timers.delete(entry[0]); entry[1].fn(); await flush(); }};
}
const actions = b => b.calls.filter(call => call.method === 'POST').map(call => new URL(call.url).searchParams.get('action'));
const statusText = b => b.body.all('p').find(node => node.role === 'status').textContent;
const recoveryHint = b => b.body.all('p').find(node => node.textContent.includes('userscript menu') && node.textContent.includes('Read browser download history'));

test('首次打开异步探测九个表的键，有记录才提醒且不传输数据，稍后仍可从菜单进入', async () => {
    const b = browser({auto: true, populatedStore: 'record9'});
    assert.equal(b.database.state.opens, 0);
    await b.ready();
    assert.equal(b.database.state.probes, 9);
    assert.equal(b.database.state.closed, true);
    assert.equal(b.database.reads.length, 1);
    assert.equal(b.calls.length, 0);
    assert.equal(b.fetches.length, 0);
    assert.equal(b.body.all('dialog').filter(node => node.open).length, 1);
    assert.equal(b.buttons.filter(node => !node.hidden).length, 2);
    assert.equal(recoveryHint(b).hidden, false);
    assert.deepEqual([...b.storage.values()], [true]);
    assert.match(b.logs.at(-1).join(' '), /HISTORY_FOUND/);
    b.buttons[2].click();
    assert.equal(b.body.all('dialog').length, 0);
    const reload = browser({auto: true, storage: b.storage});
    await reload.ready();
    assert.equal(reload.database.state.opens, 0);
    assert.equal(reload.body.all('dialog').length, 0);
    reload.menus.get('Read browser download history')();
    assert.equal(reload.body.all('dialog')[0].open, true);
    assert.equal(reload.buttons[0].disabled, false);
});

test('首次提醒直接进入既有检查与导入流程，只有点击后才上传', async () => {
    const b = browser({auto: true}); await b.ready();
    const done = b.buttons[0].click(); await flush();
    assert.equal(b.buttons[1].disabled, false);
    assert.equal(recoveryHint(b).hidden, true);
    assert.ok(actions(b).includes('inspect'));
    assert.equal(b.fetches.length, 0);
    b.buttons[1].click(); await flush(); await done;
    assert.ok(actions(b).includes('start'));
    assert.equal(recoveryHint(b).hidden, false);
});

test('没有库或没有记录时不弹窗，缺库不创建，标记按 Pixiv origin 隔离', async () => {
    for (const options of [{absent: true}, {rows: []}]) {
        const b = browser({auto: true, ...options}); await b.ready();
        assert.equal(b.body.all('dialog').length, 0);
        assert.equal(b.calls.length, 0);
        assert.deepEqual([...b.storage.values()], [true]);
        if (options.absent) assert.equal(b.database.state.abortedCreation, true);
        else assert.equal(b.database.state.closed, true);
        const originalSite = browser({auto: true, storage: b.storage, origin: 'https://pixiv.net'});
        await originalSite.ready();
        assert.equal(originalSite.body.all('dialog')[0].open, true);
    }
});

test('后台标签页等待可见，子框架不弹窗，手动入口不被自动提醒覆盖', async () => {
    const hidden = browser({auto: true, hidden: true}); await hidden.ready();
    assert.equal(hidden.database.state.opens, 0);
    hidden.document.visibilityState = 'visible'; await hidden.events.visibilitychange();
    assert.equal(hidden.body.all('dialog')[0].open, true);
    const frame = browser({auto: true, frame: true}); await frame.ready();
    assert.equal(frame.database.state.opens, 0);
    const manual = browser({auto: true, deferOpen: true});
    const pending = manual.ready();
    manual.menus.get('Read browser download history')();
    manual.database.state.releaseOpen(); await pending;
    assert.equal(manual.body.all('dialog').length, 1);
    assert.equal(manual.database.state.closed, true);
    assert.equal(manual.database.state.probes, 0);
});

test('探测失败不标记完成且本页不忙重试，重新打开页面可再检查', async () => {
    for (const failure of [{blocked: true}, {badKey: true}, {missingStore: true}]) {
        const b = browser({auto: true, ...failure}); await b.ready();
        assert.equal(b.body.all('dialog').length, 0);
        assert.equal(b.storage.size, 0);
        assert.ok(b.logs.some(log => log[0] === 'warn'));
        await b.events.visibilitychange();
        assert.equal(b.database.state.opens, 1);
        const retry = browser({auto: true, storage: b.storage}); await retry.ready();
        assert.equal(retry.body.all('dialog')[0].open, true);
    }
});

test('探测超时、离页和升级均释放资源，不弹迟到提醒或消耗首次标记', async () => {
    for (const action of ['timeout', 'pagehide', 'upgrade']) {
        const b = browser({auto: true, deferProbe: true}); await b.ready();
        if (action === 'timeout') {
            const [id, timer] = [...b.timers].find(([, timer]) => timer.ms === 10000);
            b.timers.delete(id); timer.fn();
        } else if (action === 'pagehide') b.events.pagehide();
        else b.database.db.onversionchange();
        await flush();
        for (const complete of b.database.state.pendingProbes) complete();
        await flush();
        assert.equal(b.body.all('dialog').length, 0);
        assert.equal(b.storage.size, 0);
        assert.equal(b.database.state.closed, true);
        assert.equal([...b.timers.values()].filter(timer => timer.ms === 10000).length, 0);
    }
});

test('同源并行探测在提醒前重新检查标记，只展示一次', async () => {
    const storage = new Map(), waiting = [];
    const locks = {request: (name, options, callback) => new Promise(resolve => waiting.push(() => resolve(callback({name}))))};
    const a = browser({auto: true, storage, locks}), b = browser({auto: true, storage, locks});
    await Promise.all([a.ready(), b.ready()]);
    assert.equal(waiting.length, 2);
    waiting[0](); await flush(); waiting[1](); await flush();
    assert.equal(a.body.all('dialog').length + b.body.all('dialog').length, 1);
});

test('五种语言的首次提醒与恢复提示使用实际菜单名称', async () => {
    for (const language of ['zh-CN', 'zh-TW', 'en-US', 'ja-JP', 'ko-KR']) {
        const b = browser({auto: true, language}); await b.ready();
        const menu = [...b.menus.keys()][0];
        const hint = b.body.all('p').find(node => node.textContent.includes(menu));
        assert.ok(hint && !hint.hidden);
        assert.ok(b.buttons[2].textContent);
        assert.equal(b.body.all('h2')[0].textContent.includes('undefined'), false);
        assert.equal(b.logs.every(log => !log.join(' ').includes(menu)), true);
    }
});

test('进度圆环使用处理数，终态切换结果标记，预览不伪造百分比', async () => {
    const b = browser({execution: {
        RUNNING: {total: 3, processed: 2, imported: 1, failed: 1},
        COMPLETED: {total: 3, processed: 3, imported: 2, failed: 1}
    }});
    const ring = b.body.all('div').find(node => node.role === 'progressbar');
    assert.equal(ring.hidden, true);
    b.deferImport();
    const done = b.buttons[0].click(); await flush();
    assert.equal(ring.hidden, true);
    b.buttons[1].click(); await flush();
    assert.equal(ring.hidden, false);
    assert.equal(ring['aria-valuenow'], '66');
    assert.equal(ring['aria-valuetext'], '2 / 3');
    assert.equal(ring.all('span')[0].textContent, '66%');
    b.releaseImport(); await flush(); await done;
    assert.equal(ring.hidden, true);
    assert.match(statusText(b), /3 \/ 3/);
    assert.deepEqual(b.body.all('dd').slice(0, 3).map(node => node.textContent), ['2', '0', '1']);
});

test('明细默认收起，导入结果逐项更新且保留展开状态和节点', async () => {
    const samples = ['42', '43', '44', '45', '46'].map(id => ({workKey: 'artwork:' + id, files: 1, disposition: 'CANDIDATE'}));
    const b = browser({preview: {records: 5, works: 5, candidates: 5, skipped: 0, unavailable: 0, samples},
        execution: {
            RUNNING: {total: 5, results: samples.map(item => ({workKey: item.workKey, state: 'WAITING'}))},
            COMPLETED: {total: 5, processed: 5, imported: 3, skipped: 1, failed: 1, results: samples.map((item, index) => ({
                workKey: item.workKey, state: ['IMPORTED', 'SKIPPED', 'FAILED', 'IMPORTED', 'IMPORTED'][index],
                error: index === 2 ? 'File validation failed' : index === 3 ? 'Statistics unavailable' : null
            }))}
        }});
    b.deferImport();
    const done = b.buttons[0].click(); await flush();
    const details = b.body.all('details')[1], rows = b.body.all('li');
    assert.equal(details.open, false);
    details.open = true;
    b.buttons[1].click(); await flush();
    assert.equal(b.buttons[0].hidden, true);
    assert.equal(b.buttons[1].hidden, true);
    b.releaseImport(); await flush(); await done;
    assert.equal(details.open, true);
    assert.deepEqual(b.body.all('li'), rows);
    assert.deepEqual(rows.map(row => row.all('span')[1]['data-state']), ['IMPORTED', 'SKIPPED', 'FAILED', 'IMPORTED', 'IMPORTED']);
    assert.equal(rows[2].all('p')[0].textContent, 'File validation failed');
    assert.equal(rows[3].all('p')[0].textContent, 'Statistics unavailable');
    assert.equal(b.body.all('div').find(node => node.className === 'notice').hidden, false);
    assert.equal(b.buttons[2].className, 'primary');
});

test('取消后的待处理作品不显示为成功，未知结果也不猜测登记状态', async () => {
    const preview = {records: 1, works: 1, candidates: 1, skipped: 0, unavailable: 0,
        samples: [{workKey: 'artwork:42', files: 1, disposition: 'CANDIDATE'}]};
    const b = browser({preview, execution: {CANCELLED: {preview, results: [{workKey: 'artwork:42', state: 'WAITING'}]}}});
    b.deferImport();
    const done = b.buttons[0].click(); await flush(); b.buttons[1].click(); await flush();
    const row = b.body.all('li')[0];
    assert.equal(row.all('span')[1]['data-state'], 'CANDIDATE');
    b.buttons[2].click(); await flush(); await done;
    assert.equal(row.all('span')[1]['data-state'], 'WAITING');
    assert.equal(recoveryHint(b).hidden, false);
    assert.equal(b.body.all('div').find(node => node.className === 'badge warning').hidden, false);
    assert.equal(b.fetches.length, 0);
});

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
    const details = b.body.all('li');
    assert.deepEqual(details.map(row => row['data-work']), ['artwork:42', 'artwork:43', 'artwork:44']);
    assert.deepEqual(details.map(row => row.all('span')[1]['data-state']), ['ALREADY_PRESENT', 'MISSING_FILES', 'CONFLICT']);
    assert.ok(b.body.all('summary').some(node => node.textContent.includes('3 / 8')));
    assert.match(statusText(b), /Check complete/);
    assert.equal(recoveryHint(b).hidden, false);
    options.preview = {records: 2, works: 1, candidates: 1, skipped: 0, unavailable: 0, samples: []};
    const retry = b.buttons[0].click(); await flush();
    assert.equal(b.buttons[1].disabled, false);
    b.buttons[1].click(); await flush(); await retry;
    assert.equal(actions(b).filter(action => action === 'start').length, 1);
    assert.equal(b.fetches.length, 0);
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
    assert.match(statusText(b), /unconfirmed/);
    assert.equal(recoveryHint(b).hidden, false);
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

test('历史导入全程仅访问本机服务，不请求 Pixiv 或发送登录 Cookie', async () => {
    const b = browser(); await flush();

    const done = b.buttons[0].click(); await flush();
    assert.deepEqual(actions(b), ['create', 'upload', 'upload', 'inspect']);
    assert.equal(b.fetches.length, 0); assert.equal(b.buttons[1].disabled, false);
    assert.deepEqual([...b.storage.values()], [true]);
    const chunks = b.calls.filter(call => new URL(call.url).searchParams.get('action') === 'upload');
    assert.equal(b.input, undefined);
    assert.deepEqual(JSON.parse(await new Blob(chunks.map(call => call.data)).text()), [{id: '42_p0', n: '0.png'}, {id: '42_p1', n: '1.png'}]);
    assert.equal(b.database.reads.length, 9); assert.equal(b.database.state.closed, true);
    assert.equal(new URL(chunks[1].url).searchParams.get('offset'), String(chunks[0].data.size));
    b.buttons[1].click(); await flush(); await done;
    assert.equal(b.fetches.length, 0);
    assert.ok(!actions(b).includes('reply'));
    assert.ok(b.calls.every(call => call.anonymous && !Object.keys(call.headers).some(key => key.toLowerCase() === 'cookie')));
    assert.match(statusText(b), /Import complete/);
    assert.equal(b.buttons[2].textContent, 'Close');
    assert.equal(recoveryHint(b).hidden, false);
});

test('取消正在导入的本机任务，后端确认后才显示取消', async () => {
    const b = browser(); await flush(); b.deferImport();

    const done = b.buttons[0].click(); await flush();
    b.buttons[1].click(); await flush();
    assert.equal(b.fetches.length, 0);
    b.buttons[2].click(); await flush(); await done;
    assert.equal(b.fetches.length, 0);
    assert.ok(actions(b).includes('cancel')); assert.ok(!actions(b).includes('reply'));
    assert.match(statusText(b), /Cancelled/);
    b.releaseImport(); await flush(); assert.ok(!actions(b).includes('reply'));
});

test('切换服务地址只向原地址取消历史会话，新地址不接收旧数据', async () => {
    const b = browser(); await flush(); b.deferImport();

    const done = b.buttons[0].click(); await flush(); b.buttons[1].click(); await flush();
    b.menus.get('Set local server address')(); await flush(); await done;
    assert.equal(b.fetches.length, 0);
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

test('本机明确拒绝创建时显示设置提示，不误报为未知结果或继续上传', async () => {
    const b = browser(); await flush(); b.rejectCreate();

    await b.buttons[0].click();
    assert.deepEqual(actions(b), ['create']);
    const notice = b.body.all('div').find(node => node.className === 'notice');
    assert.equal(notice.all('strong')[0].textContent, 'Directory unavailable');
    assert.equal(notice.all('p')[0].textContent, 'Choose another folder.');
    assert.equal(b.body.all('p').find(node => node.className === 'intro').hidden, true);
    assert.equal(b.body.all('p').find(node => node.className === 'caution').hidden, true);
    assert.equal(b.body.all('details')[0].hidden, true);
    assert.equal(b.buttons[2].textContent, 'Close');
});

test('引导随读取检查导入阶段更新，结束后仅保留结果', async () => {
    const b = browser(); await flush(); b.deferImport();
    const intro = b.body.all('p').find(node => node.className === 'intro');
    const caution = b.body.all('p').find(node => node.className === 'caution');
    const help = b.body.all('details')[0];
    assert.equal(intro.hidden, false);
    assert.equal(caution.hidden, false);
    assert.equal(help.hidden, false);
    assert.notEqual(help.open, true);
    const initialWarning = caution.textContent;
    const done = b.buttons[0].click();
    assert.equal(intro.hidden, true);
    assert.equal(help.hidden, true);
    assert.equal(caution.textContent, initialWarning);
    await flush();
    const readyWarning = caution.textContent;
    assert.notEqual(readyWarning, initialWarning);
    assert.equal(caution.hidden, false);
    b.buttons[1].click(); await flush();
    assert.notEqual(caution.textContent, readyWarning);
    assert.ok(caution.textContent);
    b.releaseImport(); await flush(); await done;
    assert.equal(intro.hidden, false);
    assert.ok(intro.textContent.includes('desktop app'));
    assert.equal(help.hidden, true);
    assert.equal(caution.hidden, true);
    assert.equal(caution.textContent, '');
});

for (const options of [{absent: true}, {missingStore: true}, {badKey: true}, {rows: [{id: '42_p0', n: 'x', unexpected: 'field'}]}]) {
    test('历史库缺失或格式不符时停止，不创建或改写上游数据 ' + JSON.stringify(options), async () => {
        const b = browser(options); await flush(); await b.buttons[0].click();
        assert.ok(!actions(b).includes('inspect')); assert.ok(!actions(b).includes('start'));
        assert.equal(b.fetches.length, 0);
        assert.equal(recoveryHint(b).hidden, false);
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
    assert.ok(b.database.reads.length > 9); assert.deepEqual([...b.storage.values()], [true]);
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
