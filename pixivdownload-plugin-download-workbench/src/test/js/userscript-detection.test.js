const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');

const resources = path.resolve(__dirname, '../../main/resources');
const bootstrap = fs.readFileSync(path.join(resources, 'userscript-presence-bootstrap.js'), 'utf8');
const client = fs.readFileSync(path.join(resources, 'static/pixiv-batch/userscript-detection.js'), 'utf8');

function browser() {
    const listeners = new Map(), timers = new Map(), sent = [];
    let clock = 0, sequence = 0;
    const origin = 'http://localhost:6999';
    const win = {
        addEventListener(type, fn) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(fn);
        },
        removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
        postMessage(data, target) {
            assert.equal(target, origin);
            sent.push(data);
            emit('message', {data, source: win, origin});
        }
    };
    win.top = win;
    function emit(type, event) { [...(listeners.get(type) || [])].forEach(fn => fn(event)); }
    const context = vm.createContext({
        window: win, document: {defaultView: win}, location: {origin, pathname: '/pixiv-batch.html'}, crypto: webcrypto,
        setTimeout(fn, delay) { const id = ++sequence; timers.set(id, {fn, at: clock + delay}); return id; },
        clearTimeout(id) { timers.delete(id); },
        localStorage: {getItem() { return '{"all-in-one":true}'; }}
    });
    vm.runInContext(bootstrap + '\n' + client, context);
    const api = vm.runInContext('PixivUserscriptDetection', context);
    function advance(delay) {
        const until = clock + delay;
        while (timers.size) {
            const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
            if (next[1].at > until) break;
            clock = next[1].at;
            timers.delete(next[0]);
            next[1].fn();
        }
        clock = until;
    }
    function install(id, version = '7.8.9-test') {
        const sandboxWindow = {...win};
        sandboxWindow.top = sandboxWindow;
        const sandbox = vm.createContext({
            window: sandboxWindow, document: context.document, location: context.location,
            GM_info: {script: {version}}, presenceConfig: {id, origin, page: '/pixiv-batch'}
        });
        return vm.runInContext(bootstrap + '\nregisterUserscriptPresence(presenceConfig)', sandbox);
    }
    return {api, context, install, emit, advance, sent, win, listeners, timers, origin};
}

test('沙箱窗口代理下响应实际版本，多合一只覆盖其包含的模块', async () => {
    const h = browser();
    assert.equal(h.install('all-in-one'), true);
    h.install('artwork-java', '3.4.5-fixture');
    const result = h.api.probe(['all-in-one', 'artwork-java']);
    h.advance(1800);
    const found = await result;
    assert.deepEqual([...found.get('all-in-one')], ['7.8.9-test']);
    assert.deepEqual([...found.get('artwork-java')], ['3.4.5-fixture']);
    assert.equal(h.api.has('experience-toolbox'), true);
    assert.equal(h.api.has('artwork-local'), false);
    const text = h.api.describe('artwork-java', found, (key, _, vars) => key + ':' + vars.version);
    assert.match(text, /userscripts.detected:3.4.5-fixture/);
    assert.match(text, /userscripts.covered:7.8.9-test/);
    assert.equal(h.timers.size, 0);
    assert.equal(h.listeners.get('pagehide').size, 0);
});

test('安装点击的旧记录不构成检测结果，超时后释放监听器', async () => {
    const h = browser();
    const pending = h.api.probe(['all-in-one']);
    h.advance(1800);
    assert.equal((await pending).size, 0);
    assert.equal(h.api.has('experience-toolbox'), false);
    assert.equal(h.sent.length, 3);
    assert.equal(h.listeners.get('message').size, 0);
    assert.equal(h.timers.size, 0);
});

test('拒绝跨源、其它窗口、陈旧查询、未知脚本及超长版本响应', async () => {
    const h = browser();
    const pending = h.api.probe(['artwork-java']);
    const data = {type: 'pixivdownloader:userscript-presence', protocol: 1,
        requestId: h.sent[0].requestId, id: 'artwork-java', version: '2-fixture'};
    for (const change of [
        {origin: 'https://attacker.example'}, {source: {}},
        {data: {...data, requestId: 'expired'}}, {data: {...data, id: 'unknown'}},
        {data: {...data, version: 'x'.repeat(129)}}, {data: {...data, protocol: 2}}
    ]) h.emit('message', {data, source: h.win, origin: h.origin, ...change});
    h.advance(1800);
    assert.equal((await pending).size, 0);
});

test('查询换代和页面离开清空旧结果，迟到响应不能恢复状态', async () => {
    const h = browser();
    h.install('artwork-java');
    const old = h.api.probe(['artwork-java']);
    const latest = h.api.probe(['all-in-one']);
    assert.equal((await old).size, 0);
    h.emit('pagehide', {});
    assert.equal((await latest).size, 0);
    h.advance(10000);
    assert.equal(h.api.has('artwork-java'), false);
    assert.equal(h.timers.size, 0);
});

test('检测引导只在安装站点的两套下载页面运行，回复不含存储或凭据', () => {
    const h = browser();
    for (const [origin, pathname] of [
        ['https://www.pixiv.net', '/artworks/123'],
        ['http://localhost:7000', '/pixiv-batch.html'],
        [h.origin, '/other.html']
    ]) {
        Object.assign(h.context.location, {origin, pathname});
        assert.equal(h.install('artwork-java'), false);
    }
    Object.assign(h.context.location, {origin: h.origin, pathname: '/pixiv-batch-alt.html'});
    h.install('artwork-java');
    h.emit('message', {source: h.win, origin: h.origin,
        data: {type: 'pixivdownloader:userscript-probe', protocol: 1, requestId: 'valid'}});
    assert.deepEqual(Object.keys(h.sent[0]).sort(), ['id', 'protocol', 'requestId', 'type', 'version']);
    h.emit('message', {source: {}, origin: h.origin,
        data: {type: 'pixivdownloader:userscript-probe', protocol: 1, requestId: 'valid'}});
    assert.equal(h.sent.length, 1);
});
