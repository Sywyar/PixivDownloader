'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {harness, deferred} = require('./schedule-submit-test-support');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function listHarness(options = {}) {
    const cards = [];
    const list = {
        querySelector: () => null,
        querySelectorAll: () => cards.slice(),
        get firstElementChild() { return cards[0] || null; },
        insertAdjacentElement(position, card) {
            assert.equal(position, 'afterbegin');
            cards.unshift(card);
        },
        get innerHTML() { return this.placeholder || cards.map(card => card.html).join(''); },
        set innerHTML(value) { this.placeholder = value; cards.length = 0; }
    };
    const h = harness({...options, createElement() {
        return {
            set innerHTML(html) {
                const card = {
                    html, dataset: {taskId: html.match(/data-task-id="(\d+)"/)[1]},
                    querySelector: () => null,
                    replaceWith(replacement) { cards.splice(cards.indexOf(this), 1, replacement); },
                    remove() { cards.splice(cards.indexOf(this), 1); },
                    get nextElementSibling() { return cards[cards.indexOf(this) + 1] || null; }
                };
                this.firstElementChild = card;
            }
        };
    }});
    h.addElement('schedule-list', list);
    h.state.mode = 'schedule';
    return Object.assign(h, {list: h.element('schedule-list'), cards});
}

for (const action of ['collapse', 'leave', 'next-run']) {
    test(`队列读取在 ${action} 后不保存旧快照或重新订阅`, async () => {
        const old = deferred();
        const body = {hidden: false, querySelector: () => null};
        const wrap = {querySelector: selector => selector === '.schedule-queue-body' ? body : null};
        const h = harness({fetch: async () => ({ok: true, json: () => old.promise}),
            querySelector: () => wrap,
            queueVue: {ensureScheduleQueue: () => true, syncScheduleQueue() {}, unmountScheduleQueue() {}}});
        h.state.mode = 'schedule';
        h.replaceTasks([{...task, runState: 'RUNNING', runStartedTime: 100}]);
        h.setExpanded(7, true);
        const pending = h.fetchQueue(7);
        await new Promise(resolve => setImmediate(resolve));
        if (action === 'collapse') h.toggleQueue(7);
        if (action === 'leave') { h.stopPolling(); h.state.mode = 'user'; }
        if (action === 'next-run') h.replaceTasks([{...task, runState: 'RUNNING', runStartedTime: 200}]);
        old.resolve({startedTime: 100, items: [{workType: 'example', workId: 'old', status: 'downloading'}]});
        await pending;
        assert.equal(h.getQueueModel(7).length, 0);
        assert.equal(h.storageValue('pixiv_schedule_queue_7'), undefined);
        assert.equal(h.queueSseListenerCount('old'), 0);
        if (action !== 'next-run') assert.equal(h.requests[0].init.signal.aborted, true);
    });
}

const task = {id: 7, name: 'Current task', sourceType: 'source-a', enabled: true,
    triggerKind: 'interval', intervalMinutes: 30};
const response = tasks => ({ok: true, json: async () => tasks});

test('运行结束时旧队列读取仍在途，结算后继续取得终态快照', async () => {
    const old = deferred();
    let reads = 0;
    const body = {hidden: false};
    const h = harness({fetch: async () => ({ok: true, json: () => ++reads === 1
        ? old.promise : Promise.resolve({startedTime: 100, items: []})}),
    querySelector: () => ({querySelector: () => body}),
    queueVue: {ensureScheduleQueue: () => true, syncScheduleQueue() {}, unmountScheduleQueue() {}}});
    h.state.mode = 'schedule';
    h.replaceTasks([{...task, runState: 'RUNNING', runStartedTime: 100}]);
    h.setExpanded(7, true);
    h.refreshQueues();
    await new Promise(resolve => setImmediate(resolve));
    h.replaceTasks([{...task, runState: null, lastRunTime: 200}]);
    h.refreshQueues();
    old.resolve({startedTime: 100, items: []});
    await new Promise(resolve => setImmediate(resolve));
    h.refreshQueues();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.requests.length, 2);
    assert.equal(JSON.parse(h.storageValue('pixiv_schedule_queue_7')).lastRunTime, 200);
});

test('卡片刷新保留队列与待重试节点、点击监听和焦点', () => {
    class Element extends MiniElement {
        replaceWith(node) {
            const parent = this.parentNode;
            parent.insertBefore(node, this);
            parent.removeChild(this);
        }
    }
    const h = harness({createElement: () => ({firstElementChild: newCard})});
    const root = new Element('div', h.document);
    const makeCard = () => {
        const card = new Element('div', h.document);
        for (const name of ['schedule-queue-body', 'schedule-pending-panel']) {
            const body = new Element('div', h.document);
            body.className = name;
            card.appendChild(body);
        }
        return card;
    };
    const oldCard = makeCard();
    const newCard = makeCard();
    root.appendChild(oldCard);
    const queue = oldCard.querySelector('.schedule-queue-body');
    const pending = oldCard.querySelector('.schedule-pending-panel');
    const button = new Element('button', h.document);
    let clicks = 0;
    button.addEventListener('click', () => { clicks++; });
    pending.appendChild(button);
    button.focus();
    const result = h.replaceCard(oldCard, task);
    assert.equal(result.querySelector('.schedule-queue-body'), queue);
    assert.equal(result.querySelector('.schedule-pending-panel'), pending);
    assert.equal(h.document.activeElement, button);
    result.querySelector('button').click();
    assert.equal(clicks, 1);
});

for (const outcome of ['success', 'http-error', 'body-error']) {
    test(`旧任务列表 ${outcome} 不覆盖后发刷新`, async () => {
        const old = deferred();
        let reads = 0;
        const h = listHarness({fetch: async () => {
            if (++reads > 1) return response([task]);
            if (outcome === 'http-error') return old.promise;
            return {ok: true, json: () => old.promise};
        }});
        const first = h.loadTasks();
        await new Promise(resolve => setImmediate(resolve));
        await h.loadTasks();
        if (outcome === 'http-error') old.resolve({ok: false});
        else if (outcome === 'body-error') old.resolve(Promise.reject(new Error('Old read')));
        else old.resolve([{...task, name: 'Old task'}]);
        await first;
        assert.equal(h.taskById(7).name, 'Current task');
        assert.match(h.list.innerHTML, /Current task/);
    });
}

test('离开计划页取消在途读取，返回可以重新取得列表', async () => {
    const body = deferred();
    let reads = 0;
    const h = listHarness({fetch: async () => ++reads === 1
        ? {ok: true, json: () => body.promise} : response([task])});
    const first = h.loadTasks();
    await new Promise(resolve => setImmediate(resolve));
    h.stopPolling();
    h.state.mode = 'user';
    body.resolve([{...task, name: 'Old task'}]);
    await first;
    assert.equal(h.taskById(7), null);
    assert.equal(h.list.innerHTML, '');
    assert.equal(h.requests[0].init.signal.aborted, true);
    h.state.mode = 'schedule';
    h.startPolling();
    await h.loadTasks();
    assert.equal(h.taskById(7).name, 'Current task');
});

test('来源刷新迟到时不再发起旧列表请求，轮询不叠加慢读取', async () => {
    const manifest = deferred();
    let refreshes = 0;
    const h = listHarness({refresh: () => ++refreshes === 1 ? manifest.promise : Promise.resolve(),
        fetch: async () => response([task])});
    h.startPolling();
    const first = h.loadTasks();
    h.poll();
    assert.equal(refreshes, 1);
    await h.loadTasks();
    manifest.resolve();
    await first;
    assert.equal(h.requests.length, 1);
});

for (const [name, change, expected] of [
    ['interval', {intervalMinutes: 45}, '45 minutes'],
    ['cron', {triggerKind: 'cron', cronExpr: '0 0 4 * * *'}, '0 0 4 * * *'],
    ['outcome', {lastOutcome: 'INTERRUPTED'}, 'Interrupted']
]) {
    test(`卡片只改变 ${name} 时更新展示，相同快照保留节点`, async () => {
        let snapshot = task;
        const h = listHarness({fetch: async () => response([snapshot]), translations: {
            'schedule.time.minutes': '{count} minutes', 'schedule.light.interrupted': 'Interrupted'
        }});
        await h.loadTasks();
        const original = h.cards[0];
        await h.loadTasks();
        assert.equal(h.cards[0], original);
        snapshot = {...task, ...change};
        await h.loadTasks();
        assert.notEqual(h.cards[0], original);
        assert.ok(h.list.innerHTML.includes(expected));
    });
}
