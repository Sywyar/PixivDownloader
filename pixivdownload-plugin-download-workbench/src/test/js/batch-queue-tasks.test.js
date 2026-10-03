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
function fixture(admin = true, storage = new Map()) {
    const document = new MiniElement('document');
    const window = new MiniElement('window');
    window.PixivBatch = {};
    const state = {queue: []};
    const timers = new Map(), calls = [], changes = [];
    let sequence = 0, value = snapshot([]), fail = false, wait = null;
    const ctx = vm.createContext({window, document, state, isAdmin: admin, BASE: '', AbortController, AbortSignal,
        setTimeout(fn, ms) { timers.set(++sequence, {fn, ms}); return sequence; },
        clearTimeout(id) { timers.delete(id); },
        storeSet: (key, v) => storage.set(key, v), storeGet: key => storage.get(key),
        updateStats() {}, renderQueue: item => changes.push(item), syncAllResultsQueueState() {},
        saveQueue() { storage.set('queue', JSON.stringify(state.queue)); },
        setQueueRecoveryState(item, recoveryState) {
            item.recoveryState = recoveryState; item.status = 'paused';
            item.statusMessageKey = 'batch:queue.recovery.' + recoveryState;
        },
        fetch: async (url, options) => {
            calls.push({url, options});
            if (wait) await wait;
            return {ok: !fail, status: fail ? 503 : 200, json: async () => value};
        }
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-batch/batch-queue-tasks.js'), 'utf8'), ctx);
    return {api: window.PixivBatch.queueTasks, state, ctx, storage, document, window, timers, calls, changes,
        setValue: v => value = v, fail: () => fail = true, wait: promise => wait = promise};
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }

test('回执和快照任意先后顺序都合并同一执行，保留原队列进度和元数据', () => {
    for (const earlySnapshot of [true, false]) {
        const h = fixture();
        const item = {id: '42', title: '原始标题', status: 'downloading', downloadedCount: 3};
        h.state.queue.push(item);
        if (earlySnapshot) h.api.apply(snapshot([task(first)]));
        assert.equal(h.api.bind(item, first), true);
        h.api.apply(snapshot([task(first)]));
        assert.equal(h.state.queue.length, 1);
        assert.equal(h.state.queue[0], item);
        assert.equal(item.downloadedCount, 3);
        assert.equal(item.title, '原始标题');
    }
});

test('相同作品的不同执行分别显示；取消只携带执行编号', async () => {
    const h = fixture();
    h.api.apply(snapshot([task(first), task(second)]));
    assert.equal(h.state.queue.length, 2);
    assert.notEqual(h.state.queue[0].id, h.state.queue[1].id);
    await h.api.cancel(h.state.queue[1]);
    assert.equal(h.calls[0].url, '/api/download/tasks/' + second + '/cancel');
    assert.equal(h.calls[0].options.body, undefined);
    h.api.apply(snapshot([task(first), task(second, 'CANCELLED')]));
    assert.equal(h.api.canCancel(h.state.queue[1]), false);
    assert.equal(h.state.queue[1].status, 'cancelled');
    assert.equal(h.state.queue[1].statusMessageKey, 'batch:queue.task.phase.CANCELLED');
});

test('终态快照过期和服务重启都保留已保存记录；未完成项失联后待确认', () => {
    const h = fixture();
    h.api.apply(snapshot([task(first, 'COMPLETED'), task(second)]));
    h.api.apply(snapshot([]));
    assert.equal(h.state.queue.length, 2);
    assert.equal(h.state.queue[0].status, 'completed');
    assert.equal(h.state.queue[1].recoveryState, 'unknown');
    h.api.apply(snapshot([], 'restarted'));
    assert.equal(h.state.queue[0].status, 'completed');
    assert.equal(JSON.parse(h.storage.get('queue')).length, 2);
});

test('移除和清空后的执行不会被轮询或页面刷新重新加入；新执行仍出现', async () => {
    const h = fixture();
    h.api.apply(snapshot([task(first, 'COMPLETED')]));
    h.api.dismiss(h.state.queue);
    h.state.queue.length = 0;
    h.api.apply(snapshot([task(first, 'COMPLETED')]));
    assert.equal(h.state.queue.length, 0);
    const reopened = fixture(true, h.storage);
    reopened.setValue(snapshot([task(first, 'COMPLETED'), task(second)]));
    reopened.api.start();
    await flush();
    assert.equal(reopened.state.queue.length, 1);
    assert.equal(reopened.state.queue[0].taskId, second);
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
    assert.equal(h.state.queue.length, 0);
    h.window.dispatchEvent({type: 'pageshow'});
    await flush();
    assert.equal(h.state.queue.length, 1);
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
