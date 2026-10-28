'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const flush = () => new Promise(resolve => setImmediate(resolve));
const response = value => ({ok: true, json: async () => value});

function page() {
    const requests = [], streams = [], timers = new Map(), events = new Map(), elements = new Map();
    const errors = [], alerts = [], shown = [];
    let timerId = 0;
    function element(id) {
        if (!elements.has(id)) {
            const classes = new Set(), listeners = new Map();
            elements.set(id, {textContent: '', innerHTML: '', style: {}, src: '',
                classList: {add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x)},
                addEventListener: (type, fn) => listeners.set(type, fn),
                emit: type => listeners.get(type)?.(),
                removeAttribute(name) { this[name] = ''; }
            });
        }
        return elements.get(id);
    }
    const context = vm.createContext({
        AbortController, console: {error(...args) { errors.push(args); }}, alert: message => alerts.push(message),
        setTimeout(fn, delay) { timers.set(++timerId, {fn, delay}); return timerId; },
        clearTimeout(id) { timers.delete(id); },
        setInterval(fn, delay) { timers.set(++timerId, {fn, delay, interval: true}); return timerId; },
        clearInterval(id) { timers.delete(id); },
        document: {
            hidden: false,
            body: element('body'), querySelectorAll: () => [],
            addEventListener(type, fn) { events.set(type, fn); },
            getElementById: element
        },
        window: {addEventListener(type, fn) { events.set(type, fn); }},
        bootstrap: {Modal: class {
            constructor(node) { this.node = node; }
            show() { shown.push(this.node); }
        }},
        fetch(url, options = {}) {
            return new Promise((resolve, reject) => requests.push({url, options, resolve, reject}));
        },
        EventSource: class {
            constructor(url) { this.url = url; this.closed = false; streams.push(this); }
            addEventListener(type, fn) { this[type] = fn; }
            close() { this.closed = true; }
            send(data) { this['download-status']({data: JSON.stringify(data)}); }
        }
    });
    for (const file of ['monitor-core.js', 'monitor-data.js', 'monitor-detail.js', 'monitor-sse.js', 'monitor-init.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/monitor', file), 'utf8'), context);
    }
    vm.runInContext(`
        function updateChart() {} function updateAuthorFilterIcon() {} function renderArtworkTable() {}
        function renderPagination() {} function renderThumbnailPage() {} function bindDeclarativeActions() {}
        function toggleFormatFilter() {} function toggleR18Filter() {} function toggleAiFilter() {}
        function togglePageGrid() {} function initPageI18n() { return Promise.resolve(); }
        artworkModal = new bootstrap.Modal(document.getElementById('artworkModal'));
        imageModal = new bootstrap.Modal(document.getElementById('imageModal'));
    `, context);
    return {context, requests, streams, timers, events, elements, errors, alerts, shown, element,
        run: code => vm.runInContext(code, context),
        sync: () => vm.runInContext('syncActiveIds()', context),
        count: () => elements.get('activeCount')?.textContent,
        async discover(id) {
            const started = requests.length, pending = this.sync();
            requests[started].resolve(response({artworkIds: [id]}));
            await flush();
            requests[started + 1].resolve(response({success: true, artworkId: id, title: 'sample', totalImages: 2}));
            await pending;
        }
    };
}

test('活跃查询 HTTP 失败仍安排下一次轮询', async () => {
    const p = page(), pending = p.sync();
    p.requests[0].resolve({ok: false});
    await pending;
    assert.equal(p.timers.size, 1);
    [...p.timers.values()][0].fn();
    assert.equal(p.requests.length, 2);
    p.requests[1].resolve(response({artworkIds: []}));
    await flush();
});

test('下载状态能力缺席时清除旧活动与连接，停止轮询，页面恢复后重新发现', async () => {
    const p = page();
    await p.discover(42);
    const pending = p.sync();
    p.requests.at(-1).resolve({ok: false, status: 404});
    await pending;
    assert.equal(p.count(), '—');
    assert.equal(p.streams[0].closed, true);
    assert.equal(p.timers.size, 0);
    assert.equal(p.run('activeDownloads.length'), 0);
    assert.notEqual(p.element('emptyActiveTitle').textContent, '');
    p.events.get('visibilitychange')();
    assert.equal(p.timers.size, 0);
    const count = p.requests.length;
    p.events.get('pagehide')();
    p.events.get('pageshow')({persisted: true});
    assert.ok(p.requests.length > count);
    p.requests.find((r, i) => i >= count && r.url === '/api/download/status/active')
        .resolve(response({artworkIds: []}));
    await flush();
    assert.equal(p.count(), 0);
});

test('前台事件与轮询共用一个在途查询，不重复添加活跃作品', async () => {
    const p = page(), pending = p.sync();
    p.events.get('visibilitychange')();
    p.events.get('visibilitychange')();
    assert.equal(p.requests.length, 1);
    p.requests[0].resolve(response({artworkIds: [42]}));
    await flush();
    p.requests[1].resolve(response({success: true, artworkId: 42}));
    await pending;
    assert.equal(p.count(), 1);
    assert.equal(p.streams.length, 1);
    assert.equal(p.timers.size, 1);
});

test('详情查询失败会取消同轮其余请求，下一轮可以继续同步', async () => {
    const p = page(), pending = p.sync();
    p.requests[0].resolve(response({artworkIds: [42, 43]}));
    await flush();
    p.requests[1].reject(new Error('network failed'));
    await pending;
    assert.equal(p.requests[2].options.signal.aborted, true);
    assert.equal(p.timers.size, 1);
    p.requests[2].resolve(response({artworkId: 43}));
    await flush();
    assert.equal(p.streams.length, 0);
    const recovered = p.sync();
    p.requests[3].resolve(response({artworkIds: []}));
    await recovered;
    assert.equal(p.count(), 0);
});

test('离页取消在途查询，迟到响应不重开连接；返回页面恢复查询', async () => {
    const p = page();
    await p.discover(42);
    const pending = p.sync();
    p.events.get('pagehide')({persisted: true});
    assert.equal(p.requests[2].options.signal.aborted, true);
    assert.equal(p.streams[0].closed, true);
    assert.equal(p.timers.size, 0);
    p.requests[2].resolve(response({artworkIds: [43]}));
    await pending;
    assert.equal(p.requests.length, 3);
    assert.equal(p.timers.size, 0);
    p.events.get('pageshow')({persisted: true});
    const resumed = p.requests.slice(3).find(r => r.url === '/api/download/status/active');
    assert.ok(resumed);
    resumed.resolve(response({artworkIds: []}));
    await flush();
    assert.equal(p.count(), 0);
    assert.equal(p.streams.at(-1).closed, true);
    p.events.get('pagehide')({persisted: true});
    assert.equal(p.timers.size, 0);
});

test('统计只发布最新请求，旧失败不记录错误且当前失败允许重试', async () => {
    const p = page(), first = p.run('loadStatistics()'), second = p.run('loadStatistics()');
    assert.equal(p.requests[0].options.signal?.aborted, true);
    p.requests[1].resolve(response({totalArtworks: 20, totalImages: 30, totalMoved: 4}));
    await second;
    p.requests[0].reject(new Error('obsolete'));
    await first;
    assert.equal(p.element('totalArtworks').textContent, 20);
    assert.equal(p.element('totalImages').textContent, 30);
    assert.equal(p.errors.length, 0);
    const failed = p.run('loadStatistics()');
    p.requests[2].reject(new Error('current'));
    await failed;
    assert.equal(p.errors.length, 1);
    const retry = p.run('loadStatistics()');
    p.requests[3].resolve(response({totalArtworks: 21}));
    await retry;
    assert.equal(p.element('totalArtworks').textContent, 21);
});

test('统计正文解析期间被刷新或离页后不再修改页面', async () => {
    const p = page();
    let parsed;
    const old = p.run('loadStatistics()');
    p.requests[0].resolve({ok: true, json: () => new Promise(resolve => { parsed = resolve; })});
    await flush();
    const current = p.run('loadStatistics()');
    p.requests[1].resolve(response({totalArtworks: 20})); await current;
    parsed({totalArtworks: 10}); await old;
    assert.equal(p.element('totalArtworks').textContent, 20);
    const leaving = p.run('loadStatistics()');
    p.events.get('pagehide')({persisted: true});
    assert.equal(p.requests[2].options.signal.aborted, true);
    p.requests[2].resolve(response({totalArtworks: 99})); await leaving;
    assert.equal(p.element('totalArtworks').textContent, 20);
    await p.run('loadStatistics()');
    assert.equal(p.requests.length, 3);
});

test('定时统计刷新不会不断取消同一个慢请求', async () => {
    const p = page(), pending = p.run('loadStatistics()');
    p.run('updateDashboard(); updateDashboard();');
    assert.equal(p.requests.length, 1);
    p.requests[0].resolve(response({totalArtworks: 1})); await pending;
    p.run('updateDashboard()');
    assert.equal(p.requests.length, 2);
});

test('历史和作者属于同轮快照，旧结果与旧失败不能覆盖新列表', async () => {
    const p = page(), old = p.run('loadAllDataForChart()'), current = p.run('loadAllDataForChart()');
    assert.equal(p.requests[0].options.signal?.aborted, true);
    assert.equal(p.requests[1].options.signal, p.requests[0].options.signal);
    p.requests[2].resolve(response({content: [{artworkId: 20}]}));
    p.requests[3].resolve(response([{authorId: 2, name: 'new'}])); await current;
    p.requests[0].resolve(response({content: [{artworkId: 10}]}));
    p.requests[1].reject(new Error('obsolete')); await old;
    assert.equal(p.run('allArtworksCache[0].artworkId'), 20);
    assert.equal(p.run('authorMap.get(2)'), 'new');
    assert.equal(p.errors.length, 0);
    assert.equal(p.element('historyTableBody').innerHTML, '');
});

test('历史的作者解析迟到时保留筛选状态和当前快照', async () => {
    const p = page(); let parsed;
    const old = p.run('loadAllDataForChart()');
    p.requests[0].resolve(response({content: [{artworkId: 10}]}));
    p.requests[1].resolve({ok: true, json: () => new Promise(resolve => { parsed = resolve; })});
    await flush();
    p.run('searchQuery = "new"; sortKey = "title"; sortDir = "asc"; authorFilter.add(2);');
    const current = p.run('loadAllDataForChart()');
    p.requests[2].resolve(response({content: [{artworkId: 20, title: 'new', authorId: 2}]}));
    p.requests[3].resolve({ok: false}); await current;
    parsed([{authorId: 1, name: 'old'}]); await old;
    assert.equal(p.run('allArtworksCache[0].artworkId'), 20);
    assert.equal(p.run('authorMap.size'), 0);
    assert.equal(p.run('searchQuery + ":" + sortKey + ":" + sortDir'), 'new:title:asc');
    assert.equal(p.run('authorFilter.has(2)'), true);
});

test('历史失败取消同轮请求并显示错误，随后仍能加载', async () => {
    const p = page(), failed = p.run('loadAllDataForChart()');
    p.requests[0].reject(new Error('current')); await failed;
    assert.equal(p.requests[1].options.signal?.aborted, true);
    assert.match(p.element('historyTableBody').innerHTML, /LOAD FAILED/);
    const retry = p.run('loadAllDataForChart()');
    p.requests[2].resolve(response({content: []})); p.requests[3].resolve(response([])); await retry;
    assert.equal(p.run('allArtworksCache.length'), 0);
});

test('详情与缩略图替换共用弹窗请求归属，迟到结果不重开旧作品', async () => {
    const p = page(), old = p.run('viewArtworkDetails(10)'), current = p.run('viewThumbnails(20)');
    assert.equal(p.requests[0].options.signal?.aborted, true);
    p.requests[1].resolve(response({artworkId: 20, count: 7})); await current;
    p.requests[0].resolve(response({artworkId: 10, title: 'obsolete-title', time: 1})); await old;
    assert.equal(p.run('thumbArtworkId'), 20);
    assert.match(p.element('artworkModalBody').innerHTML, /7 images/);
    assert.doesNotMatch(p.element('artworkModalBody').innerHTML, /obsolete-title/);
    assert.equal(p.shown.length, 1);
});

test('关闭弹窗即取消未完成详情，旧错误不弹提示', async () => {
    const p = page(); p.events.get('DOMContentLoaded')(); await flush();
    const index = p.requests.length, pending = p.run('viewArtworkDetails(10)');
    p.element('artworkModal').emit('hide.bs.modal');
    assert.equal(p.requests[index].options.signal?.aborted, true);
    p.requests[index].reject(new Error('obsolete')); await pending;
    assert.equal(p.alerts.length, 0);
    assert.equal(p.shown.length, 0);
});

test('详情正文解析迟到不能覆盖新选择，当前错误仍提示并可重试', async () => {
    const p = page(); let parsed;
    const old = p.run('viewThumbnails(10)');
    p.requests[0].resolve({ok: true, json: () => new Promise(resolve => { parsed = resolve; })});
    await flush();
    const current = p.run('viewArtworkDetails(20)');
    p.requests[1].resolve(response({artworkId: 20, title: 'latest', time: 1})); await current;
    parsed({artworkId: 10, count: 7}); await old;
    assert.match(p.element('artworkModalBody').innerHTML, /latest/);
    assert.equal(p.shown.length, 1);
    const failure = p.run('viewThumbnails(30)');
    p.requests[2].resolve({ok: false}); await failure;
    assert.equal(p.alerts.length, 1);
    const retry = p.run('viewThumbnails(30)');
    p.requests[3].resolve(response({artworkId: 30, count: 3})); await retry;
    assert.equal(p.run('thumbArtworkId'), 30);
    assert.equal(p.shown.length, 2);
});

test('离页取消历史与详情，恢复时重新获取统计和历史但保留筛选', async () => {
    const p = page(), history = p.run('loadAllDataForChart()'), detail = p.run('viewArtworkDetails(10)');
    p.run('searchQuery = "kept"; currentPage = 3;');
    p.events.get('pagehide')({persisted: true});
    assert.ok(p.requests.every(r => r.options.signal?.aborted));
    p.requests[0].resolve(response({content: [{artworkId: 10}]}));
    p.requests[1].resolve(response([]));
    p.requests[2].resolve(response({artworkId: 10, title: 'obsolete', time: 1}));
    await Promise.all([history, detail]);
    assert.equal(p.run('allArtworksCache'), null);
    assert.equal(p.shown.length, 0);
    p.events.get('pageshow')({persisted: true});
    const urls = p.requests.slice(3).map(r => r.url);
    assert.ok(urls.includes('/api/downloaded/statistics'));
    assert.ok(urls.includes('/api/downloaded/history/paged?page=0&size=9999'));
    assert.equal(p.run('searchQuery'), 'kept');
    assert.equal(p.run('currentPage'), 3);
});

test('原图加载被另一个作品替换时只打开新图，旧回调无效', async () => {
    const p = page(), old = p.run('viewFullImage(10, 0)'), image = p.element('modalImage');
    const oldLoad = image.onload;
    const current = p.run('viewFullImage(20, 1)');
    assert.equal(image.src, '/api/downloaded/rawfile/20/1');
    oldLoad();
    assert.equal(p.shown.length, 0);
    image.onload(); await Promise.all([old, current]);
    assert.equal(p.shown.length, 1);
    assert.equal(p.element('imgCounter').textContent, '# 2');
});

test('关闭原图或离页会释放图片来源，迟到加载不重开弹窗', async () => {
    for (const leave of [false, true]) {
        const p = page(); p.events.get('DOMContentLoaded')(); await flush();
        const pending = p.run('viewFullImage(10, 0)'), image = p.element('modalImage'), oldLoad = image.onload;
        if (leave) p.events.get('pagehide')({persisted: true});
        else p.element('imageModal').emit('hide.bs.modal');
        oldLoad(); await pending;
        assert.equal(image.src, '');
        assert.equal(p.shown.length, 0);
    }
});

test('页面恢复重取仍打开的原图，已关闭的预览不重新加载', async () => {
    for (const visible of [false, true]) {
        const p = page(), pending = p.run('viewFullImage(10, 2)'), image = p.element('modalImage');
        image.onload(); await pending;
        if (visible) p.element('imageModal').classList.add('show');
        p.events.get('pagehide')({persisted: true});
        assert.equal(image.src, '');
        p.events.get('pageshow')({persisted: true});
        assert.equal(image.src, visible ? '/api/downloaded/rawfile/10/2' : '');
        if (visible) image.onload();
        assert.equal(p.shown.length, 1);
    }
});

test('原图旧失败不提示，当前失败释放等待并允许翻页重试', async () => {
    const p = page(), old = p.run('viewFullImage(10, 0)'), image = p.element('modalImage'), oldError = image.onerror;
    const current = p.run('viewFullImage(10, 1)');
    oldError();
    assert.equal(p.alerts.length, 0);
    image.onerror(); await Promise.all([old, current]);
    assert.equal(p.alerts.length, 1);
    assert.equal(p.shown.length, 0);
    const retry = p.run('navigateFullImg(1)');
    assert.equal(image.src, '/api/downloaded/rawfile/10/2');
    image.onload(); await retry;
    assert.equal(p.element('imgCounter').textContent, '# 3');
});

test('旧连接的迟到终态不移除重开后的作品，活动连接仍更新进度并关闭空列表', async () => {
    const p = page();
    await p.discover(42);
    const first = p.streams[0];
    first.send({artworkId: 42, completed: true});
    assert.equal(p.count(), 0);
    assert.equal(first.closed, true);
    await p.discover(42);
    first.send({artworkId: 42, completed: true});
    assert.equal(p.count(), 1);
    const current = p.streams[1];
    current.send({artworkId: 99, completed: true});
    current.send({artworkId: 42, downloadedCount: 1, totalImages: 2, progress: 50});
    assert.match(p.elements.get('activeDownloadsList').innerHTML, /50\.0%/);
    current.send({artworkId: 42, cancelled: true});
    assert.equal(p.count(), 0);
    assert.equal(current.closed, true);
});
