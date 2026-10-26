'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const resources = path.resolve(__dirname, '../../main/resources/static');
const flush = () => new Promise(resolve => setImmediate(resolve));

function runtime(variant, loadCore = false) {
    let now = 0, sequence = 0;
    const timers = new Map(), requests = [], listeners = new Map(), roots = [];
    function node(tag = 'div', className = '', text = '') {
        return {tag, className, textContent: text, style: {}, dataset: {}, children: [], hidden: false,
            addEventListener() {},
            appendChild(child) { this.children.push(child); return child; },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            get firstElementChild() { return this.children[0] || null; },
            querySelector(selector) {
                return descendants(this.children).find(child => selector.startsWith('.')
                    ? child.className.split(' ').includes(selector.slice(1)) : child.tag === selector) || null;
            },
            setAttribute(name, value) { this[name] = String(value); },
            set innerHTML(value) { this.children = []; this.textContent = value; },
            removeAttribute(name) { delete this[name]; }};
    }
    function descendants(nodes) { return nodes.flatMap(item => [item, ...descendants(item.children)]); }
    function get(id) { return descendants(roots).find(item => item.id === id) || null; }
    for (const id of ['archive-card', 'ac-title', 'ac-expired-area', 'ac-dl-area', 'ac-status',
        'ac-dl-btn', 'ac-countdown', 'abDockBody', 'admin-pack-btn']) {
        roots.push(Object.assign(node(), {id}));
    }
    for (const id of ['abArchiveBox', 'abQuotaBox']) {
        get('abDockBody').appendChild(Object.assign(node(), {id}));
    }
    function timer(callback, delay, repeat) {
        timers.set(++sequence, {callback, delay, repeat, at: now + delay});
        return sequence;
    }
    const context = vm.createContext({console, AbortController,
        Date: class extends Date { static now() { return now; } },
        document: {getElementById: get},
        setTimeout: (callback, delay) => timer(callback, delay, false),
        setInterval: (callback, delay) => timer(callback, delay, true),
        clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
        addEventListener(type, callback) {
            if (!listeners.has(type)) listeners.set(type, []);
            listeners.get(type).push(callback);
        },
        fetch(url, options = {}) {
            return new Promise((resolve, reject) => requests.push({url, options, resolve, reject}));
        },
        bt: (key, fallback, args) => args?.time ?? key, formatSeconds: String,
        el: node, abIconEl: () => node('i'), novelTranslateMessage() {},
        handleStart() {}, handlePause() {}, handleRetry() {}, handleClear() {},
        applyCookieHint() {}, syncCookieToggleLabel() {},
        setStatus() {}, updateAdminPackButton() {}, isAdmin: true, updateAuthButtons() {}, renderAuthButton() {},
        PixivBatch: {}, PixivBatchAlt: {state: {}, queue: {}}
    });
    context.window = context;
    const run = code => vm.runInContext(code, context);
    run("let state = {queue: []};");
    if (!loadCore) run("const BASE = '';");
    const html = fs.readFileSync(path.join(resources, variant === 'alt' ? 'pixiv-batch-alt.html' : 'pixiv-batch.html'), 'utf8');
    const names = ['batch-archive.js', 'queue-display.js', 'alt-queue-model.js', 'alt-queue.js', 'alt-engine.js', 'batch-download-quota.js'];
    if (loadCore) names.push('batch-core.js');
    for (const [, id, key] of html.matchAll(/id="(ac-[^"]+)" data-i18n="([^"]+)"/g)) {
        if (get(id)) get(id).dataset.i18n = key;
    }
    run("const dockState = {archive:null, quota:{enabled:false}};");
    for (const [, file] of html.matchAll(/<script src="\/([^"]+)"/g)) {
        if (names.includes(path.basename(file))) run(fs.readFileSync(path.join(resources, file), 'utf8'));
    }
    for (const name of ['renderDockStatus', 'renderCurrent', 'renderQueue', 'updateStats', 'updateButtonsState', 'ensureDockVue']) {
        context[name] = () => {};
    }
    return {context, requests, timers, run, get,
        show: (...args) => context.showArchiveCard(...args),
        link: () => variant === 'alt'
            ? descendants([get('abArchiveBox')]).find(item => item.tag === 'a')?.href
            : get('ac-dl-area').style.display === 'block' ? get('ac-dl-btn').href : undefined,
        expired: () => variant === 'alt'
            ? descendants([get('abArchiveBox')]).some(item => item.textContent === 'status.archive-expired')
            : get('ac-expired-area').style.display === 'block',
        text: () => variant === 'alt'
            ? descendants([get('abArchiveBox')]).map(item => item.textContent).join(' ')
            : get('ac-status').textContent,
        event(type) { for (const callback of listeners.get(type) || []) callback({persisted:true}); },
        async respond(index, data, ok = true) {
            requests[index].resolve({ok, status: ok ? 200 : 500, json: async () => {
                if (data instanceof Error) throw data;
                return data;
            }});
            await flush();
        },
        async tick(milliseconds) {
            const end = now + milliseconds;
            for (;;) {
                const due = [...timers].filter(([, value]) => value.at <= end).sort((a,b) => a[1].at - b[1].at)[0];
                if (!due) break;
                now = due[1].at;
                if (due[1].repeat) due[1].at += due[1].delay;
                else timers.delete(due[0]);
                due[1].callback();
                await flush();
            }
            now = end;
        }
    };
}

test('alt：配额刷新和归档状态切换复用控件，失效时撤销下载链接', () => {
    const r = runtime('alt');
    r.run('Object.assign(dockState.quota, {enabled:true, artworksUsed:1, maxArtworks:10, resetSeconds:0})');
    r.context.renderQuotaBar();
    const quota = r.get('abQuotaBox'), bar = quota.querySelector('.ab-mini-prog-bar');
    const fill = quota.querySelector('.ab-mini-prog-fill');
    for (const used of [7, 9, 2]) {
        r.run(`Object.assign(dockState.quota, {artworksUsed:${used}, resetSeconds:10})`);
        r.context.renderQuotaBar();
        assert.equal(quota.querySelector('.ab-mini-prog-bar'), bar);
        assert.equal(quota.querySelector('.ab-mini-prog-fill'), fill);
        assert.equal(bar['aria-valuenow'], String(used * 10));
    }
    assert.equal(fill.className, 'ab-mini-prog-fill');
    r.show('token', 60, false);
    const archive = r.get('abArchiveBox'), link = archive.querySelector('a');
    const note = archive.querySelector('.ab-archive-note');
    for (const status of ['ready', 'expired', 'empty', 'packing', 'ready']) {
        r.run(`dockState.archive.status = '${status}'`);
        r.context.renderArchiveCard();
        assert.equal(archive.querySelector('a'), link);
        assert.equal(archive.querySelector('.ab-archive-note'), note);
        assert.equal(link.href, status === 'ready' ? '/api/archive/download/token' : undefined);
        assert.equal(archive.querySelector('.ab-archive-row').hidden, status !== 'ready');
    }
    r.event('pagehide');
});

for (const variant of ['classic', 'alt']) {
    test(variant + '：管理员打包只提交已完成插画，忽略同 ID 的其他类型', async () => {
        const r = runtime(variant);
        r.run(`state.queue = [
            {id:'31',kind:'illust',status:'completed'},
            {id:'31',kind:'novel',status:'completed'},
            {id:'32',kind:'novel',status:'completed'},
            {id:'33',kind:'plugin-work',status:'completed'},
            {id:'34',kind:'illust',status:'pending'},
            {id:'35',kind:'illust',status:'skipped'}
        ];`);
        const packing = r.context.triggerAdminPack();
        assert.equal(r.requests.length, 1);
        assert.equal(r.requests[0].url, '/api/archive/pack-artworks');
        assert.equal(r.requests[0].options.method, 'POST');
        assert.equal(r.requests[0].options.credentials, 'same-origin');
        await r.respond(0, {archiveToken:'selected-artwork', archiveExpireSeconds:60});
        await packing;
        assert.deepEqual(JSON.parse(r.requests[0].options.body), {artworkIds: [31]});
        await r.tick(2000);
        await r.respond(1, {status:'ready', expireSeconds:60});
        assert.equal(r.link(), '/api/archive/download/selected-artwork');
        r.event('pagehide');
    });

    test(variant + '：没有已完成插画时不提交管理员打包请求', async () => {
        const r = runtime(variant);
        const messages = [];
        r.context.setStatus = r.context.setDockStatus = message => messages.push(message);
        r.run("state.queue = [{id:'31',kind:'novel',status:'completed'}," +
            "{id:'32',kind:'plugin-work',status:'completed'}, {id:'33',kind:'illust',status:'failed'}];");
        const packing = r.context.triggerAdminPack();
        if (r.requests.length) r.requests[0].resolve({status:204});
        await packing;
        assert.equal(r.requests.length, 0);
        assert.equal(messages[0].key, 'status.no-completed-to-pack');
    });

    for (const failure of ['unauthorized', 'missing', 'server', 'json', 'network']) {
        test(variant + '：管理员打包失败仍反馈原因并刷新按钮：' + failure, async () => {
            const r = runtime(variant), messages = [];
            let refreshed = 0;
            r.context.setStatus = r.context.setDockStatus = message => messages.push(message);
            r.context.updateAdminPackButton = r.context.updateButtonsState = () => refreshed++;
            r.run("state.queue = [{id:'31',kind:'illust',status:'completed'}];");
            const packing = r.context.triggerAdminPack();
            if (failure === 'network') r.requests[0].reject(new Error('offline'));
            else r.requests[0].resolve({
                status: failure === 'unauthorized' ? 401 : failure === 'missing' ? 204 : 500,
                ok: false,
                json: async () => {
                    if (failure === 'json') throw new Error('invalid JSON');
                    return {error:'archive unavailable'};
                }
            });
            await packing;
            const expected = {unauthorized:'status.login-expired', missing:'status.pack-folder-missing',
                server:'archive unavailable', json:'status.pack-failed-http', network:'status.pack-request-failed'};
            assert.equal(messages.at(-1).key || messages.at(-1), expected[failure]);
            assert.equal(r.context.isAdmin, failure !== 'unauthorized');
            assert.ok(refreshed > 0);
            assert.equal(r.timers.size, 0);
        });
    }

    test(variant + '：迟到的旧归档结果不能替换当前下载链接', async () => {
        const r = runtime(variant);
        r.show('old', 60, false);
        await r.tick(2000);
        r.show('new', 60, true);
        await r.respond(0, {status:'ready', expireSeconds:60});
        assert.equal(r.link(), '/api/archive/download/new');
    });

    test(variant + '：离页后首次收到归档回执，返回前不开始观察', async () => {
        const r = runtime(variant);
        r.event('pagehide');
        r.show('late-receipt', 60, false);
        await r.tick(6000);
        assert.equal(r.requests.length, 0);
        assert.equal(r.timers.size, 0);
        r.event('pageshow');
        await r.tick(2000);
        assert.equal(r.requests.length, 1);
    });

    test(variant + '：慢查询不重叠，离页中止且返回继续观察', async () => {
        const r = runtime(variant);
        r.show('token', 60, false);
        await r.tick(6000);
        assert.equal(r.requests.length, 1);
        r.event('pagehide');
        assert.equal(r.requests[0].options.signal.aborted, true);
        await r.respond(0, {status:'ready', expireSeconds:60});
        assert.equal(r.link(), undefined);
        assert.equal(r.timers.size, 0);
        r.event('pageshow');
        await r.tick(2000);
        assert.equal(r.requests.length, 2);
    });

    test(variant + '：同一归档重建与离页不延长下载期限', async () => {
        const r = runtime(variant);
        r.show('token', 10, true);
        await r.tick(6000);
        r.show('token', 10, true);
        r.event('pagehide');
        await r.tick(4000);
        r.event('pageshow');
        assert.equal(r.expired(), true);
        assert.equal(r.link(), undefined);
        assert.equal(r.timers.size, 0);
    });

    test(variant + '：错误响应与超时后继续查询，零期限立即失效', async () => {
        const r = runtime(variant);
        r.show('token', 60, false);
        await r.tick(2000);
        await r.respond(0, {status:'ready', expireSeconds:60}, false);
        assert.equal(r.link(), undefined);
        await r.tick(2000);
        await r.respond(1, new Error('invalid json'));
        await r.tick(2000);
        await r.tick(10000);
        assert.equal(r.requests[2].options.signal.aborted, true);
        await r.tick(1999);
        assert.equal(r.requests.length, 3);
        await r.tick(1);
        await r.respond(2, {status:'expired'});
        assert.equal(r.expired(), false);
        await r.respond(3, {status:'ready', expireSeconds:0});
        assert.equal(r.expired(), true);
        assert.equal(r.link(), undefined);
        assert.equal(r.timers.size, 0);
    });

    test(variant + '：空归档停止查询，重建和返回保留终态', async () => {
        const r = runtime(variant);
        r.show('empty', 60, false);
        await r.tick(2000);
        await r.respond(0, {status:'empty'});
        assert.match(r.text(), /status.archive-empty/);
        r.show('empty', 60, false);
        if (variant === 'alt') r.context.renderArchiveCard();
        r.event('pagehide');
        await r.tick(5000);
        r.event('pageshow');
        assert.match(r.text(), /status.archive-empty/);
        assert.equal(r.timers.size, 0);
        assert.equal(r.requests.length, 1);
    });

    test(variant + '：响应体解析期间替换卡片仍隔离旧结果', async () => {
        const r = runtime(variant);
        r.show('old', 60, false);
        await r.tick(2000);
        let body;
        r.requests[0].resolve({ok:true, json:() => new Promise(resolve => { body = resolve; })});
        await flush();
        r.show('new', 60, false);
        await r.tick(2000);
        body({status:'ready', expireSeconds:60});
        await flush();
        assert.equal(r.link(), undefined);
        await r.respond(1, {status:'ready', expireSeconds:5});
        assert.equal(r.link(), '/api/archive/download/new');
        await r.tick(5000);
        assert.equal(r.expired(), true);
    });

    test(variant + '：异步归档状态使用当前语言，不保存已翻译标题', async () => {
        const r = runtime(variant);
        let lang = 'first';
        r.context.bt = (key, fallback, args) => `${lang}:${key}:${args?.count ?? ''}`;
        r.show('token', 60, false, {key:'status.admin-packing', fallback:'', args:{count:3}});
        await r.tick(2000);
        lang = 'second';
        await r.respond(0, {status:'empty'});
        const title = variant === 'alt' ? r.text() : r.context.document.getElementById('ac-title').textContent;
        assert.match(title, /second:status.admin-packing:3/);
        assert.doesNotMatch(title, /first:/);
    });
}

test('两版工作台分别持有归档观察与页面生命周期', async () => {
    const classic = runtime('classic'), alt = runtime('alt');
    classic.show('first', 60, false);
    alt.show('second', 60, false);
    await classic.tick(2000);
    await alt.tick(2000);
    classic.event('pagehide');
    assert.equal(alt.requests[0].options.signal.aborted, false);
    await alt.respond(0, {status:'ready', expireSeconds:5});
    assert.equal(alt.link(), '/api/archive/download/second');
    assert.equal(classic.link(), undefined);
});

test('旧版整页语言刷新保留归档标题参数与就绪状态', () => {
    const r = runtime('classic', true);
    r.context.language = 'first';
    r.context.translator = {
        t: (key, fallback, args) => `${r.context.language}:${key}:${args?.count ?? ''}`,
        apply() {
            for (const id of ['ac-title', 'ac-status']) {
                const node = r.context.document.getElementById(id);
                if (node.dataset.i18n) node.textContent = this.t('batch:' + node.dataset.i18n);
            }
        }
    };
    r.run('pageI18n = translator');
    r.show('token', 60, true, {key:'status.admin-packing', fallback:'', args:{count:3}});
    r.context.language = 'second';
    r.context.applyStaticPageTranslations();
    assert.equal(r.context.document.getElementById('ac-title').textContent, 'second:batch:status.admin-packing:3');
    assert.equal(r.context.document.getElementById('ac-status').textContent, 'second:batch:status.archive-ready:');
    assert.equal(r.link(), '/api/archive/download/token');
});

test('新版下载坞重建后恢复归档链接和终态，倒计时不重置', async () => {
    const r = runtime('alt');
    r.show('token', 5, true);
    await r.tick(2000);
    r.context.renderDock();
    assert.equal(r.link(), '/api/archive/download/token');
    await r.tick(3000);
    assert.equal(r.expired(), true);
    r.context.renderDock();
    assert.equal(r.expired(), true);
    r.show('empty', 60, false);
    await r.tick(2000);
    await r.respond(0, {status:'empty'});
    r.context.renderDock();
    assert.match(r.text(), /status.archive-empty/);
});
