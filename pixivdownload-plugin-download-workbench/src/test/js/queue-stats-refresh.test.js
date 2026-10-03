'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const {MiniElement, MiniEventTarget} = require('./pixiv-layout-feedback-test-dom');

class Element extends MiniElement {
    get lastElementChild() { return this.children.at(-1) || null; }
    replaceChildren(...children) {
        for (const child of [...this.children]) this.removeChild(child);
        children.forEach(child => this.appendChild(child));
    }
}

function harness(alt, vue) {
    const nodes = new Map();
    const add = id => { const node = new Element('span'); nodes.set(id, node); return node; };
    const ids = alt
        ? ['abStatPending', 'abStatSuccess', 'abStatFailed', 'abStatActive', 'abStatSkipped',
            'abDockBadge', 'abBtnStart', 'abBtnPause', 'abBtnPack', 'abBtnRetry']
        : ['stat-count-pending', 'stat-count-success', 'stat-count-failed', 'stat-count-active',
            'stat-count-skipped', 'stats-bar'];
    ids.forEach(add);
    const syncs = [];
    const context = vm.createContext({console,
        state: {queue: [], stats: {success: 9, failed: 9, active: 9, skipped: 9, extra: 'retained'}, isRunning: false, isPaused: false},
        isAdmin: true,
        document: Object.assign(new MiniEventTarget(), {
            getElementById: id => nodes.get(id) || null, createElement: tag => new Element(tag)
        }),
        PixivBatch: {queueVue: {isDownloadActive: () => vue, syncDownloadStats: stats => syncs.push(stats)}},
        formatStatsText: (...counts) => counts.join('|')
    });
    context.window = context;
    const run = code => vm.runInContext(code, context);
    const load = file => run(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static', file), 'utf8'));
    if (alt) {
        load('pixiv-batch-alt/alt-core.js');
        load('pixiv-batch-alt/alt-queue.js');
        context.PixivBatchAlt.queueVue = {isActive: () => vue, syncStats: stats => syncs.push(stats)};
        run("pageI18n = {t: key => 'English ' + key};");
    } else {
        load('pixiv-batch/batch-queue-view.js');
    }
    return {context, nodes, add, syncs, run};
}

for (const alt of [false, true]) {
    for (const vue of [false, true]) {
        test(`${alt ? '新版' : '经典'}统计在 ${vue ? 'Vue' : '回退'} 模式正确分类，状态变化与清空不残留计数`, () => {
            const h = harness(alt, vue), state = h.context.state;
            state.queue = ['completed', 'failed', 'downloading', 'skipped', 'idle', 'pending', 'paused',
                'completed', 'downloading', 'unknown', undefined].map(status => Object.freeze({status}));
            const originalStats = state.stats;
            const check = expected => {
                h.context.updateStats();
                assert.equal(state.stats, originalStats);
                assert.equal(state.stats.extra, 'retained');
                for (const key of ['success', 'failed', 'active', 'skipped']) assert.equal(state.stats[key], expected[key]);
                if (vue) assert.deepEqual({...h.syncs.at(-1)}, expected);
                else {
                    const keys = ['pending', 'success', 'failed', 'active', 'skipped'];
                    const ids = alt ? ['abStatPending', 'abStatSuccess', 'abStatFailed', 'abStatActive', 'abStatSkipped']
                        : keys.map(key => 'stat-count-' + key);
                    ids.forEach((id, i) => assert.equal(String(h.nodes.get(id).textContent), String(expected[keys[i]])));
                }
                if (alt) {
                    assert.equal(h.nodes.get('abDockBadge').hidden, expected.pending === 0);
                    assert.equal(h.nodes.get('abDockBadge').textContent, String(expected.pending));
                    assert.equal(h.nodes.get('abBtnPack').disabled, expected.success === 0);
                    assert.equal(h.nodes.get('abBtnRetry').disabled, expected.failed === 0);
                } else {
                    assert.equal(h.nodes.get('stats-bar').textContent,
                        [expected.pending, expected.success, expected.failed, expected.active, expected.skipped].join('|'));
                }
            };
            check({pending: 3, success: 2, failed: 1, active: 2, skipped: 1});
            state.queue = [{status: 'failed'}, {status: 'paused'}];
            check({pending: 1, success: 0, failed: 1, active: 0, skipped: 0});
            state.queue = [{status: 'cancelled', taskObserved: true, taskPhase: 'CANCELLED'}];
            check({pending: 0, success: 0, failed: 0, active: 0, skipped: 0});
            assert.equal(h.context.currentFrontItem(state.queue, false), null);
            assert.deepEqual({...h.context.currentRemainingCounts(state.queue, null)}, {downloading: 0, queued: 0});
            state.queue = [];
            check({pending: 0, success: 0, failed: 0, active: 0, skipped: 0});
        });
    }
    test(`${alt ? '新版' : '经典'}的 500 项统计刷新只读取一轮队列状态`, () => {
        const h = harness(alt, true);
        let reads = 0;
        h.context.state.queue = Array.from({length: 500}, () => ({get status() { reads++; return 'pending'; }}));
        h.context.updateStats();
        assert.equal(h.syncs.at(-1).pending, 500);
        assert.equal(reads, 500);
    });
}

test('新版按钮保留未变子节点，语言、暂停状态和重新创建的按钮使用当前显示内容', () => {
    const h = harness(true, true), state = h.context.state;
    const button = h.nodes.get('abBtnPause');
    state.isRunning = true;
    h.context.updateStats();
    const [icon, label] = button.children;
    for (let i = 0; i < 100; i++) h.context.updateStats();
    assert.equal(button.children[0], icon);
    assert.equal(button.children[1], label);
    assert.equal(button.disabled, false);
    assert.equal(h.nodes.get('abBtnStart').disabled, true);
    assert.equal(h.nodes.get('abBtnStart').classList.contains('is-loading'), true);
    assert.equal(icon.innerHTML, h.context.abIcon('pause'));
    assert.equal(label.textContent, 'English batch-alt:button.pause');

    h.run("pageI18n = {t: key => 'Translated ' + key};");
    h.context.updateButtonsState();
    assert.equal(button.children[0], icon);
    assert.equal(button.children[1], label);
    assert.equal(label.textContent, 'Translated batch-alt:button.pause');
    state.isPaused = true;
    h.context.updateButtonsState();
    assert.equal(button.children[0].innerHTML, h.context.abIcon('play'));
    assert.equal(button.lastElementChild.textContent, 'Translated batch-alt:button.resume');
    const replacement = h.add('abBtnPause');
    h.context.updateButtonsState();
    assert.equal(replacement.children[0].innerHTML, h.context.abIcon('play'));
    assert.equal(replacement.lastElementChild.textContent, 'Translated batch-alt:button.resume');
    state.isRunning = false;
    h.context.updateButtonsState();
    assert.equal(replacement.disabled, true);
    assert.equal(h.nodes.get('abBtnStart').disabled, false);
    assert.equal(h.nodes.get('abBtnStart').classList.contains('is-loading'), false);
});

test('新版按钮独立刷新读取最新队列，打包权限仍随身份更新', () => {
    const h = harness(true, false), state = h.context.state;
    h.context.updateStats();
    state.queue = [{status: 'completed'}, {status: 'failed'}];
    h.context.updateButtonsState();
    assert.equal(h.nodes.get('abBtnPack').disabled, false);
    assert.equal(h.nodes.get('abBtnRetry').disabled, false);
    h.context.isAdmin = false;
    h.context.updateButtonsState();
    assert.equal(h.nodes.get('abBtnPack').hidden, true);
    h.context.isAdmin = true;
    state.queue = [];
    h.context.updateButtonsState();
    assert.equal(h.nodes.get('abBtnPack').hidden, false);
    assert.equal(h.nodes.get('abBtnPack').disabled, true);
    assert.equal(h.nodes.get('abBtnRetry').disabled, true);
});
