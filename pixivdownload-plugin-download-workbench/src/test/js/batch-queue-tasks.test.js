'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

const first = '11111111-1111-4111-8111-111111111111';
const second = '22222222-2222-4222-8222-222222222222';
function task(id, phase = 'STARTED') {
    return {attempt: {attemptId: id, workType: 'example', workId: '42'}, queueType: 'example',
        title: '<作品>', phase, updatedAt: '2026-01-01T00:00:00Z'};
}
let revision = 0;
function snapshot(tasks, epoch = 'one') { return {epoch, revision: ++revision, tasks}; }
function manual(taskId) {
    return {id: taskId, taskId, status: 'paused', recoveryState: 'unknown'};
}
function fixture(admin = true, storage = new Map()) {
    const document = new MiniElement('document');
    const window = new MiniElement('window');
    window.PixivBatch = {};
    const state = {queue: []};
    const timers = new Map(), calls = [], changes = [], results = new Map();
    let sequence = 0, value = snapshot([]), fail = false, wait = null;
    const ctx = vm.createContext({window, document, state, isAdmin: admin, appMode: 'solo', BASE: '', AbortController, AbortSignal,
        setTimeout(fn, ms) { timers.set(++sequence, {fn, ms}); return sequence; },
        clearTimeout(id) { timers.delete(id); },
        storeSet: (key, v) => storage.set(key, v), storeGet: key => storage.get(key),
        storeRemove: key => storage.delete(key),
        updateStats() {}, renderQueue: item => changes.push(item), syncAllResultsQueueState() {},
        saveQueue() { storage.set('queue', JSON.stringify(state.queue)); },
        fetch: async (url, options) => {
            calls.push({url, options});
            if (wait) await wait;
            if (!fail && /^\/api\/download\/tasks\/[\w-]+$/.test(url)) {
                const result = results.get(url.split('/').pop());
                if (typeof result === 'function') return result();
                return {ok: !!result, status: result ? 200 : 404, json: async () => result};
            }
            return {ok: !fail, status: fail ? 503 : 200, json: async () => value};
        }
    });
    for (const file of ['batch-queue-recovery.js', 'batch-queue-tasks.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/pixiv-batch', file), 'utf8'), ctx);
    }
    return {api: window.PixivBatch.queueTasks, state, ctx, storage, document, window, timers, calls, changes, results,
        setValue: v => value = v, fail: () => fail = true, wait: promise => wait = promise};
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }

test('已缓存终态但本页 worker 尚未完成的记录重开后仍按原执行查询持久结果', async () => {
    for (const phase of ['COMPLETED', 'FAILED', 'CANCELLED']) {
        const previous = fixture();
        previous.state.queue.push({id: '42', taskId: first, status: 'downloading'});
        previous.api.apply(snapshot([task(first, phase)]));
        const saved = JSON.parse(previous.storage.get('queue'));
        assert.equal(saved[0].status, 'downloading');
        assert.equal(saved[0].taskPhase, phase);
        const reopened = fixture(true, previous.storage);
        reopened.state.queue = saved;
        const item = saved[0];
        assert.equal(reopened.ctx.restoreInterruptedQueueItem(item), true);
        assert.equal(item.status, 'paused');
        assert.equal(item.recoveryState, 'unknown');
        reopened.setValue(snapshot([task(second, 'COMPLETED')], 'restarted'));
        reopened.results.set(first, task(first, phase));
        await reopened.api.refresh();
        assert.equal(item.status, {COMPLETED: 'completed', FAILED: 'failed', CANCELLED: 'paused'}[phase]);
        assert.equal(item.recoveryState, undefined);
        assert.equal(item.taskMissing, undefined);
        assert.equal(JSON.parse(reopened.storage.get('queue'))[0].status, item.status);
        assert.deepEqual(reopened.calls.map(c => c.url), ['/api/download/tasks', '/api/download/tasks/' + first]);
        assert.equal(reopened.state.queue.length, 1);
        await reopened.api.refresh();
        assert.equal(reopened.calls.filter(c => c.url.endsWith(first)).length, 1);
    }
});

test('缓存终态的待恢复项仍隔离删除、重绑和已确认结果之后到达的回执', async () => {
    for (const change of ['remove', 'replace', 'resolved', 'wrong-id']) {
        const h = fixture();
        const item = {...manual(first), taskPhase: 'COMPLETED'};
        h.state.queue.push(item);
        let release;
        h.results.set(first, () => new Promise(resolve => release = () => resolve({ok: true,
            json: async () => task(change === 'wrong-id' ? second : first, 'COMPLETED')})));
        const query = h.api.refresh(); await flush();
        assert.equal(typeof release, 'function');
        if (change === 'remove') h.state.queue.length = 0;
        if (change === 'replace') h.api.bind(item, second);
        if (change === 'resolved') {
            h.api.apply(snapshot([task(first, 'FAILED')]));
            assert.equal(item.status, 'failed');
        }
        release(); await query;
        assert.notEqual(item.status, 'completed');
        assert.equal(h.state.queue.length, change === 'remove' ? 0 : 1);
        assert.equal(h.calls.some(c => c.options.method === 'POST'), false);
    }
});

test('新页面按执行编号找回快照已过期的终态，并保存核对结果', async () => {
    for (const phase of ['COMPLETED', 'FAILED', 'CANCELLED']) {
        const previous = fixture();
        previous.state.queue.push(manual(first));
        previous.api.apply(snapshot([task(first)]));
        const reopened = fixture(true, previous.storage);
        reopened.state.queue = JSON.parse(previous.storage.get('queue'));
        reopened.results.set(first, task(first, phase));
        await reopened.api.refresh();
        assert.equal(reopened.state.queue.length, 1);
        assert.equal(reopened.state.queue[0].taskPhase, phase);
        assert.equal(reopened.state.queue[0].recoveryState, undefined);
        assert.equal(JSON.parse(reopened.storage.get('queue'))[0].taskPhase, phase);
        assert.deepEqual(reopened.calls.map(c => c.url), ['/api/download/tasks', '/api/download/tasks/' + first]);
    }
});

test('核对无回执时保持待确认，自动轮询不重复查询，手动重新核对可恢复', async () => {
    const h = fixture();
    h.state.queue.push(manual(first));
    h.api.apply(snapshot([task(first)]));
    await h.api.refresh();
    assert.equal(h.state.queue[0].recoveryState, 'unknown');
    h.results.set(first, task(first, 'COMPLETED'));
    const poll = [...h.timers.values()].find(timer => timer.ms === 2000);
    await poll.fn();
    assert.equal(h.calls.filter(c => c.url.endsWith(first)).length, 1);
    assert.equal(h.state.queue[0].recoveryState, 'unknown');
    await h.api.refresh();
    assert.equal(h.state.queue[0].status, 'completed');
    assert.equal(h.calls.filter(c => c.url.endsWith(first)).length, 2);
});

test('只按旧执行查回终态，不能借用同一作品另一执行或重新导入已删除项', async () => {
    for (const change of ['remove', 'replace', 'hide', 'wrong-id']) {
        const h = fixture();
        h.api.start(); await flush();
        h.state.queue.push(manual(first));
        h.api.apply(snapshot([task(first)]));
        let release;
        h.results.set(first, () => new Promise(resolve => release = () => resolve({ok: true,
            json: async () => task(change === 'wrong-id' ? second : first, 'COMPLETED')})));
        const query = h.api.refresh(); await flush();
        const item = h.state.queue[0];
        if (change === 'remove') h.state.queue.length = 0;
        if (change === 'replace') h.api.bind(item, second);
        if (change === 'hide') h.window.dispatchEvent({type: 'pagehide'});
        assert.equal(typeof release, 'function');
        release(); await query;
        assert.notEqual(item.status, 'completed');
        assert.equal(h.state.queue.length, change === 'remove' ? 0 : 1);
        assert.equal(h.calls.some(c => c.options.method === 'POST'), false);
    }
});

test('缺失执行串行核对，单条查询失败不影响其它回执且下次轮询重试', async () => {
    const h = fixture();
    h.state.queue.push(manual(first), manual(second));
    h.api.apply(snapshot([task(first), task(second)]));
    let release;
    h.results.set(first, () => new Promise(resolve => release = () => resolve({ok: false, status: 503})));
    h.results.set(second, task(second, 'COMPLETED'));
    const query = h.api.refresh(); await flush();
    assert.equal(h.calls.some(call => call.url.endsWith(second)), false);
    release(); await query;
    assert.equal(h.state.queue[0].recoveryState, 'unknown');
    assert.equal(h.state.queue[1].status, 'completed');
    h.results.set(first, task(first, 'FAILED'));
    await [...h.timers.values()].find(timer => timer.ms === 2000).fn();
    assert.equal(h.state.queue[0].status, 'failed');
});

test('恢复查询共享超时，中止后不应用迟到回执也不继续查询其它条目', async () => {
    const h = fixture();
    h.state.queue.push({id: 'manual', taskId: first, status: 'paused', recoveryState: 'unknown'},
        {id: 'other', taskId: second, status: 'paused', recoveryState: 'unknown'});
    let release;
    h.results.set(first, () => new Promise(resolve => release = () => resolve({ok: true,
        json: async () => task(first, 'COMPLETED')})));
    const query = h.api.refresh(); await flush();
    [...h.timers.values()].find(timer => timer.ms === 10000).fn();
    assert.equal(h.calls.at(-1).options.signal.aborted, true);
    release(); await query;
    assert.equal(h.calls.some(call => call.url.endsWith(second)), false);
    assert.equal(h.state.queue[0].recoveryState, 'unknown');
    h.results.set(first, task(first, 'COMPLETED'));
    await h.api.refresh();
    assert.equal(h.state.queue[0].status, 'completed');
});

test('快照早于回执也不导入任务，绑定后保留手动队列进度和元数据', () => {
    for (const earlySnapshot of [true, false]) {
        const h = fixture();
        const item = {id: '42', title: '原始标题', status: 'downloading', downloadedCount: 3};
        h.state.queue.push(item);
        const value = snapshot([task(first)]);
        if (earlySnapshot) h.api.apply(value);
        assert.equal(h.state.queue.length, 1);
        assert.equal(item.taskId, undefined);
        assert.equal(h.api.bind(item, first), true);
        h.api.apply(value);
        assert.equal(h.state.queue.length, 1);
        assert.equal(h.state.queue[0], item);
        assert.equal(item.downloadedCount, 3);
        assert.equal(item.title, '原始标题');
        assert.equal(item.taskPhase, 'STARTED');
    }
});

test('同作品的后台执行不进入手动队列，取消只携带绑定的执行编号', async () => {
    const h = fixture();
    h.state.queue.push({...manual(second), id: '42'});
    h.api.apply(snapshot([task(first), task(second)]));
    assert.equal(h.state.queue.length, 1);
    await h.api.cancel(h.state.queue[0]);
    assert.equal(h.calls[0].url, '/api/download/tasks/' + second + '/cancel');
    assert.equal(h.calls[0].options.body, undefined);
    h.api.apply(snapshot([task(first), task(second, 'CANCELLED')]));
    assert.equal(h.api.canCancel(h.state.queue[0]), false);
    assert.equal(h.state.queue[0].status, 'paused');
    assert.equal(h.state.queue[0].statusMessageKey, 'batch:queue.task.phase.CANCELLED');
});

test('终态快照过期和服务重启都保留已保存记录；未完成项失联后待确认', () => {
    const h = fixture();
    h.state.queue.push(manual(first), manual(second));
    h.api.apply(snapshot([task(first, 'COMPLETED'), task(second)]));
    h.api.apply(snapshot([]));
    assert.equal(h.state.queue.length, 2);
    assert.equal(h.state.queue[0].status, 'completed');
    assert.equal(h.state.queue[1].recoveryState, 'unknown');
    h.api.apply(snapshot([], 'restarted'));
    assert.equal(h.state.queue[0].status, 'completed');
    assert.equal(JSON.parse(h.storage.get('queue')).length, 2);
});

test('移除和清空后轮询或重开页面不导入任何后台执行，并清理旧移除缓存', async () => {
    const h = fixture();
    h.state.queue.push(manual(first));
    h.api.apply(snapshot([task(first, 'COMPLETED')]));
    h.state.queue.length = 0;
    h.api.apply(snapshot([task(first, 'COMPLETED')]));
    assert.equal(h.state.queue.length, 0);
    h.storage.set('pixiv_batch_task_dismissed', JSON.stringify({epoch: 'one', ids: [first]}));
    const reopened = fixture(true, h.storage);
    reopened.setValue(snapshot([task(first, 'COMPLETED'), task(second)]));
    reopened.api.start();
    await flush();
    assert.equal(reopened.state.queue.length, 0);
    assert.equal(reopened.storage.has('pixiv_batch_task_dismissed'), false);
    assert.equal(reopened.calls.some(c => c.options.method === 'POST'), false);
});

test('后台执行无论来源和状态都不新增队列项，也不按作品编号覆盖手动项', () => {
    const h = fixture();
    const item = {id: '42', kind: 'example', title: '手动作品', status: 'idle'};
    h.state.queue.push(item);
    for (const phase of ['ACCEPTED', 'QUEUED', 'STARTED', 'COMPLETED', 'FAILED', 'CANCELLED']) {
        h.api.apply(snapshot([task(first, phase)], phase));
        assert.deepEqual(h.state.queue, [item]);
        assert.deepEqual(item, {id: '42', kind: 'example', title: '手动作品', status: 'idle'});
    }
    assert.equal(h.storage.size, 0);
    assert.equal(h.changes.length, 0);
});

test('旧移除缓存清理失败不阻断手动执行的状态查询', async () => {
    const h = fixture();
    h.state.queue.push(manual(first));
    h.storage.set('pixiv_batch_task_dismissed', '{}');
    h.ctx.storeRemove = () => { throw new Error('storage unavailable'); };
    h.setValue(snapshot([task(first, 'COMPLETED')]));
    h.api.start();
    await h.api.refresh();
    assert.equal(h.state.queue[0].status, 'completed');
});

test('执行恢复不覆盖终态；重复快照不重绘现有行', () => {
    const h = fixture();
    const item = {id: '42', taskId: first, status: 'paused', recoveryState: 'unknown'};
    h.state.queue.push(item);
    h.api.apply(snapshot([task(first)]));
    assert.equal(item.status, 'downloading');
    const count = h.changes.length;
    h.api.apply(snapshot([task(first)]));
    assert.equal(h.changes.length, count);
    h.api.apply(snapshot([task(first, 'FAILED')]));
    assert.equal(item.status, 'failed');
    assert.equal(item.recoveryState, undefined);
});

test('重复查询共享请求，隐藏与离开取消请求，恢复重查，非管理员不请求', async () => {
    const h = fixture();
    h.state.queue.push(manual(first));
    let release;
    h.wait(new Promise(resolve => release = resolve));
    h.api.start();
    h.api.start();
    const pending = h.api.refresh();
    assert.equal(h.calls.length, 1);
    h.window.dispatchEvent({type: 'pagehide'});
    assert.equal(h.calls[0].options.signal.aborted, true);
    h.setValue(snapshot([task(first)]));
    release();
    await pending;
    assert.equal(h.state.queue[0].recoveryState, 'unknown');
    h.window.dispatchEvent({type: 'pageshow'});
    await flush();
    assert.equal(h.state.queue.length, 1);
    assert.equal(h.state.queue[0].status, 'downloading');
    h.document.hidden = true;
    h.document.dispatchEvent({type: 'visibilitychange'});
    assert.equal(h.timers.size, 0);
    h.document.hidden = false;
    h.document.dispatchEvent({type: 'visibilitychange'});
    await flush();
    assert.equal(h.window.listenerCount('pagehide'), 1);
    const guest = fixture(false);
    guest.api.start(); await guest.api.refresh();
    assert.equal(guest.calls.length, 0);
});

test('断线只标记待确认并保留记录，重连后继续同一次执行', async () => {
    const h = fixture();
    h.state.queue.push(manual(first));
    h.api.apply(snapshot([task(first)]));
    h.fail();
    await h.api.refresh();
    assert.equal(h.state.queue[0].recoveryState, 'unknown');
    assert.equal(h.api.canCancel(h.state.queue[0]), false);
    h.api.apply(snapshot([task(first)]));
    assert.equal(h.state.queue[0].status, 'downloading');
    assert.equal(h.state.queue.length, 1);
});

test('重复取消合并为一个请求，离页中止请求并释放计时器', async () => {
    const h = fixture();
    h.api.start(); await flush();
    h.state.queue.push(manual(first));
    h.api.apply(snapshot([task(first)]));
    let release;
    h.wait(new Promise(resolve => release = resolve));
    const a = h.api.cancel(h.state.queue[0]);
    const b = h.api.cancel(h.state.queue[0]);
    const posts = h.calls.filter(call => call.options.method === 'POST');
    assert.equal(posts.length, 1);
    h.window.dispatchEvent({type: 'pagehide'});
    assert.equal(posts[0].options.signal.aborted, true);
    release(); await Promise.all([a, b]);
    assert.equal(h.timers.size, 0);
});
