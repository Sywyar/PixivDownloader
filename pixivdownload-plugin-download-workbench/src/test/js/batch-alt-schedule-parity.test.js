'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');
const rootPath = resolve(__dirname, '../../main/resources/static/pixiv-batch-alt');
const escape = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

class Element extends MiniElement {
    set href(value) { this.setAttribute('href', value); }
    get href() { return this.getAttribute('href'); }
    replaceChildren(...children) {
        this.children.slice().forEach(child => this.removeChild(child));
        this._html = '';
        children.forEach(child => this.appendChild(child));
    }
    append(...children) { children.forEach(child => this.appendChild(child)); }
    prepend(child) { this.insertBefore(child, this.children[0]); }
    get innerHTML() { return this._html || this.children.map(child => child.outerHTML || escape(child.textContent)).join(''); }
    set innerHTML(value) { this.replaceChildren(); this._html = value; }
    get outerHTML() {
        const attrs = Object.entries(this.attributes).map(([k, v]) => ` ${k}="${escape(v)}"`).join('');
        return `<${this.tagName.toLowerCase()}${attrs}>${this.innerHTML || escape(this.textContent)}</${this.tagName.toLowerCase()}>`;
    }
}

function setup() {
    const nodes = new Map(), storage = new Map(), listeners = new Map(), frames = new Map();
    let snapshot = {items: []}, frameId = 0;
    const document = {
        getElementById: id => nodes.get(id) || null, addEventListener() {},
        createElement: tag => new Element(tag),
        createTextNode: text => ({textContent: String(text), outerHTML: escape(text), children: [], matches: () => false}),
        querySelectorAll: () => []
    };
    const runtime = {
        get: () => null,
        queueKey: (type, id) => JSON.stringify([type, id]),
        scheduledQueueItem: (type, item) => ({rawTitle: item.title || item.presentation?.title,
            canonicalUrl: 'https://example.test/' + encodeURIComponent(item.workId),
            source: 'schedule', typeData: {owner: type}}),
        supportsScheduledSse: () => true,
        queueTags: () => [], dataSourceForItem: () => ({label: 'Fixture'}), canCancel: () => true,
        queueLiveStatus: item => item.liveStatus
            ? {label: 'Owner phase', message: item.liveStatus.phase, tone: 'info'} : null,
        manifestDescriptor: () => ({i18nNamespace: 'fixture'})
    };
    const c = vm.createContext({console, document, Map, Set, Promise, URLSearchParams,
        setTimeout, clearTimeout, setInterval: () => 1, clearInterval() {},
        requestAnimationFrame: fn => { const id = ++frameId; frames.set(id, fn); return id; },
        cancelAnimationFrame: id => frames.delete(id)});
    c.window = c;
    c.PixivBatchAlt = {queue: {}, schedule: {}};
    c.PixivBatch = {queueTypes: runtime};
    for (const file of ['alt-core.js', 'alt-state.js', 'alt-queue.js', 'alt-schedule.js', 'alt-schedule-actions.js', 'alt-schedule-editor.js']) {
        vm.runInContext(readFileSync(resolve(rootPath, file), 'utf8'), c, {filename: file});
    }
    Object.assign(c, {
        altQueueTypes: () => runtime,
        bt: (key, fallback, vars) => Object.entries(vars || {}).reduce((str, [k, v]) =>
            str.replaceAll('{' + k + '}', String(v)), fallback || key),
        storeGet: key => storage.get(key), storeSet: (key, value) => storage.set(key, value),
        storeRemove: key => storage.delete(key),
        fetch: async () => ({ok: true, json: async () => snapshot}),
        fmtScheduleTime: () => '12:00', formatBytes: bytes => String(bytes) + ' B',
        formatDurationMs: ms => String(ms), computeCurrentCardHtml: () => '<div>Current work</div>',
        abIconEl: () => new Element('span'), errorBox: message => {
            const node = new Element('aside'); node.textContent = message; return node;
        },
        queueDataSourceText: () => 'Fixture',
        ensureSharedSSE() {}, mergeUgoiraProgress: (old, next) => ({...old, ...next}),
        addSSEListener(id, fn) { const set = listeners.get(id) || new Set(); set.add(fn); listeners.set(id, set); },
        removeSSEListener(id, fn) { listeners.get(id)?.delete(fn); }
    });
    const task = {id: 1, runState: 'RUNNING', lastRunTime: 42, sourceType: 'source'};
    nodes.set('abScheduleQueue-1', new Element('div'));
    c.run = code => vm.runInContext(code, c);
    c.task = task;
    c.run("state.mode='schedule'; scheduleState.tasks=[task]; scheduleState.expandedQueues.add(1)");
    return {c, task, nodes, storage, listeners, frames, runtime,
        setSnapshot: value => { snapshot = value; },
        flush: () => { const pending = Array.from(frames.values()); frames.clear(); pending.forEach(fn => fn()); }};
}

test('计划详情复用只读队列行、类型展示、图片与动图进度和完整统计', async () => {
    const h = setup();
    h.setSnapshot({startedTime: 1, total: 2, items: [
        {workType: 'image', workId: 'same', status: 'pending', title: '<img onerror=bad>', liveStatus: {phase: 'translating'}},
        {workType: 'text', workId: 'same', status: 'downloaded', title: 'Text'}
    ]});
    await h.c.loadScheduleQueue(h.task);
    const data = h.c.run('altScheduleQueues.get(1).data');
    assert.equal(data.items[0].typeData.owner, 'image');
    const model = h.c.scheduleQueueDetailModel(data);
    assert.match(model.rows[0].html, /&lt;img onerror=bad&gt;/);
    assert.match(model.rows[0].html, /Owner phase/);
    assert.doesNotMatch(model.rows[0].html, /<button/);
    assert.match(model.rows[0].html, /https:\/\/example.test\/same/);
    assert.equal(new Set(model.rows.map(row => row.key)).size, 2);
    assert.match(model.statsText, /成功: 1/);
    const persistent = JSON.parse(h.storage.get('pixiv_schedule_queue_1'));
    assert.equal(persistent.items[0].liveStatus, null);
});

test('SSE 使用复合身份，合批刷新，并保留后续快照前的逐图进度', async () => {
    const h = setup();
    h.setSnapshot({startedTime: 1, items: [
        {workType: 'image', workId: 'same', status: 'pending', title: 'Image'},
        {workType: 'text', workId: 'same', status: 'pending', title: 'Text'}
    ]});
    await h.c.loadScheduleQueue(h.task);
    const send = event => Array.from(h.listeners.get('same')).forEach(fn => fn(event));
    send({totalImages: 5, downloadedCount: 1});
    assert.equal(h.c.run('altScheduleQueues.get(1).data.items[0].status'), 'pending', '无类型的歧义事件不串写');
    send({workType: 'image', totalImages: 5, downloadedCount: 1, imageProgress: {progress: 20},
        ugoiraProgress: {phase: 'ffmpeg', ffmpegProgress: 30}});
    send({workType: 'image', totalImages: 5, downloadedCount: 2});
    assert.equal(h.frames.size, 1);
    h.flush();
    assert.equal(h.c.run('altScheduleQueues.get(1).data.items[0].downloadedCount'), 2);
    assert.equal(h.c.run('altScheduleQueues.get(1).data.items[1].status'), 'pending');
    await h.c.loadScheduleQueue(h.task, true);
    const model = h.c.scheduleQueueDetailModel(h.c.run('altScheduleQueues.get(1).data'));
    assert.match(model.rows[0].html, /40%/);
    assert.match(model.rows[0].html, /ffmpeg/);
    assert.match(model.currentHtml, /Current work/);
    h.c.run('scheduleState.expandedQueues.clear()');
    h.c.startScheduleQueuePolling();
    assert.equal(h.listeners.get('same').size, 0);
    assert.equal(h.frames.size, 0);
});

test('同轮缓存保留、换轮失效且不会复活 liveStatus', async () => {
    const h = setup();
    h.setSnapshot({startedTime: 1, items: [{workType: 'image', workId: '1', status: 'downloaded', title: 'Cached'}]});
    await h.c.loadScheduleQueue(h.task);
    h.c.run('altScheduleQueues.clear()');
    h.setSnapshot({startedTime: null, items: []});
    await h.c.loadScheduleQueue(h.task);
    assert.equal(h.c.run('altScheduleQueues.get(1).data.items[0].rawTitle'), 'Cached');
    h.task.lastRunTime = 43;
    await h.c.loadScheduleQueue(h.task);
    assert.equal(h.c.run('altScheduleQueues.get(1).data.items.length'), 0);
});

test('普通队列保留操作，计划行禁止触发普通队列的取消和移除', () => {
    const h = setup();
    const item = {id: '42', kind: 'image', status: 'pending', title: 'Work'};
    assert.equal(h.c.queueItemRow(item).querySelectorAll('button').length, 1);
    assert.equal(h.c.queueItemRow(item, {readOnly: true}).querySelectorAll('button').length, 0);
    item.status = 'downloading';
    assert.equal(h.c.queueItemRow(item).querySelectorAll('button').length, 1);
    assert.equal(h.c.queueItemRow(item, {readOnly: true}).querySelectorAll('button').length, 0);
});

test('小说的正文、内嵌图片、封面进度和插件状态都经共享行显示', () => {
    const h = setup();
    const row = h.c.queueItemRow({id: 'n1', kind: 'novel', status: 'downloading', title: 'Novel',
        novelText: {done: 10, total: 20}, novelEmbedded: {done: 1, total: 4},
        novelCover: {done: 10, total: 40}, liveStatus: {phase: 'TRANSLATING'}}, {readOnly: true});
    assert.match(row.outerHTML, /小说正文/);
    assert.match(row.outerHTML, /内嵌图片/);
    assert.match(row.outerHTML, /封面/);
    assert.match(row.outerHTML, /TRANSLATING/);
});

test('来源贡献凭证策略分组，确认和数值提示后按 owner 身份执行', async () => {
    const h = setup(), calls = [], toasts = [];
    const group = {sourceType: 'fixture-source', identityKey: 'fixture:account',
        identity: {owner: 'fixture-owner', accountKey: 'account'}, title: 'Policy', description: 'Description',
        actions: [{actionId: 'defer', label: 'Defer', tone: 'danger', confirmMessage: 'Confirm',
            prompt: {message: 'Minutes', inputType: 'number', parameterName: 'minutes', min: 60, defaultValue: '60'}}]};
    let groups = [group];
    h.c.altScheduleSources = () => ({
        credentialPolicyGroups: () => groups,
        applyCredentialPolicyAction: async (...args) => { calls.push(args); return {ok: true}; }
    });
    h.c.abConfirm = async () => false;
    h.c.abPrompt = async () => '2';
    h.c.abToast = (...args) => toasts.push(args);
    h.c.loadScheduleTasks = async () => {};
    const host = new Element('div');
    h.c.renderScheduleCredentialPolicyBanners(host);
    const button = host.querySelector('button');
    assert.equal(button.textContent, 'Defer');
    h.c.renderScheduleCredentialPolicyBanners(host);
    assert.equal(host.querySelector('button'), button, '刷新保留按钮与键盘焦点');
    await h.c.applyScheduleCredentialPolicyAction(group, group.actions[0], button);
    assert.equal(calls.length, 0);
    h.c.abConfirm = async () => true;
    await h.c.applyScheduleCredentialPolicyAction(group, group.actions[0], button);
    assert.equal(calls[0][0], 'fixture-source');
    assert.equal(calls[0][1].identity.owner, 'fixture-owner');
    assert.equal(calls[0][1].parameters.minutes, 60);
    assert.equal(toasts[0][0], 'success');
    groups = [];
    h.c.renderScheduleCredentialPolicyBanners(host);
    assert.equal(host.children.length, 0, '来源撤回时动作一并移除');
});

test('待重试原因按来源解析详细机器码，忙碌任务不能清除', async () => {
    const h = setup(), bodyCalls = [];
    h.c.localizeScheduleMachineCode = (code, source) => source === 'source' && code === 'fixture.failure' ? 'Localized failure' : null;
    assert.equal(h.c.schedulePendingReasonText({reasonDetailJson: '{"reason":"fixture.failure"}'}, 'source'), 'Localized failure');
    assert.equal(h.c.schedulePendingReasonText({reasonDetailJson: '<secret>', reasonCode: 'fixture.failure'}, 'source'), '失败原因不可用');
    h.c.openModal = options => bodyCalls.push(options.body);
    h.c.summaryJoin = list => list.filter(Boolean).join(' · ');
    h.c.scheduleKindLabel = kind => kind;
    let writes = 0;
    h.c.fetch = async (_url, options = {}) => {
        if (options.method === 'DELETE') writes++;
        return {ok: true, json: async () => [{workType: 'image', workId: 'x', attempts: 1,
            reasonDetailJson: '{"reason":"fixture.failure"}'}]};
    };
    await h.c.openSchedulePending(h.task);
    assert.match(bodyCalls[0].outerHTML, /Localized failure/);
    const button = bodyCalls[0].querySelector('button');
    assert.equal(button.disabled, true);
    button.click();
    await Promise.resolve();
    assert.equal(writes, 0);
});

test('小说实际贡献的翻译阶段在计划行显示，不推断新的下载状态', async () => {
    const h = setup();
    const file = resolve(__dirname, '../../../../pixivdownload-plugin-novel/src/main/resources/static/pixiv-novel-download/novel-queue-acquisition.js');
    const source = readFileSync(file, 'utf8');
    const start = source.indexOf('function novelLiveStatusCount');
    const end = source.indexOf('function novelCanonicalQueueItemUrl', start);
    vm.runInContext(source.slice(start, end), h.c);
    h.runtime.supportsScheduledSse = () => false;
    h.runtime.queueLiveStatus = h.c.novelQueueLiveStatus;
    h.setSnapshot({items: [{workType: 'text', workId: '1', status: 'downloaded', title: 'Novel',
        liveStatus: {phase: 'TRANSLATING', elapsedSeconds: 12}}]});
    await h.c.loadScheduleQueue(h.task);
    const data = h.c.run('altScheduleQueues.get(1).data');
    const model = h.c.scheduleQueueDetailModel(data);
    assert.equal(data.items[0].status, 'completed');
    assert.match(model.rows[0].html, /AI 翻译/);
    assert.match(model.rows[0].html, /12s/);
    assert.equal(h.listeners.size, 0);
});
