'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const scripts = path.join(__dirname, '../../main/resources/static/pixiv-batch');

for (const mode of ['search', 'series', 'user', 'user-id']) {
    const userMode = mode.startsWith('user');
    const username = mode === 'user' ? 'Author' : '';
    for (const action of ['add', 'duplicate', 'keep', 'merge', 'remove', 'downloading', 'missing']) {
        test(`${mode} 结果卡片切换：${action}`, () => {
            const calls = [];
            const item = {id: '42', title: 'Title'};
            const meta = {kind: 'fixture', title: item.title};
            const existing = !['add', 'duplicate', 'missing'].includes(action);
            const queue = existing ? [{id: 'fixture:42'}] : [];
            const acq = {
                queueId(value) { assert.equal(value, item); return 'fixture:42'; },
                buildQueueMeta(value, order, context) {
                    assert.equal(value, item);
                    if (mode === 'series') {
                        assert.equal(order, 1);
                        assert.equal(context.seriesId, 'series-id');
                    } else if (userMode) {
                        assert.equal(order.userId, 'author-id');
                        assert.equal(order.username, username);
                        assert.equal(order.variant, 'fixture-variant');
                    }
                    return meta;
                },
                get queueSource() { calls.push('source'); return 'fixture-source'; }
            };
            const sandbox = {
                window: {PixivBatch: {queueTypes: {acquisition: () => acq}}},
                state: {queue}, defaultSearchFilters: () => ({}),
                seriesState: {items: [item], seriesId: 'series-id'},
                seriesAcq: () => acq, getSeriesFallbackOrder: index => index + 1,
                reconcileQueueItemTypeData(value, incoming, reason) {
                    assert.equal(value, queue[0]);
                    assert.equal(incoming, meta);
                    assert.equal(reason, 'toggle');
                    calls.push('merge');
                    return {keepExisting: ['keep', 'merge'].includes(action), changed: action === 'merge'};
                },
                updateStats: () => calls.push('stats'), saveQueue: () => calls.push('save'),
                renderQueue: () => calls.push('render'),
                setStatus(message, level) {
                    assert.equal(message.args.title, item.title);
                    calls.push([message.key, level]);
                }
            };
            vm.createContext(sandbox);
            const modeScripts = userMode ? ['user-core', 'user-view']
                : [mode === 'search' ? 'search' : 'series-view'];
            for (const name of ['batch-queue-actions.js', ...modeScripts.map(name => `modes/${name}.js`)]) {
                vm.runInContext(fs.readFileSync(path.join(scripts, name), 'utf8'), sandbox, {filename: name});
            }
            sandbox.addItemsToQueue = (ids, metas, source, queueUsername, authorId, authorName) => {
                assert.equal(ids[0], 'fixture:42'); assert.equal(metas[0], meta);
                assert.equal(source, userMode ? 'user' : 'fixture-source');
                assert.equal(queueUsername, userMode ? username || 'author-id' : '');
                if (userMode) {
                    assert.equal(authorId, 'author-id');
                    assert.equal(authorName, username || 'author-id');
                }
                calls.push('add'); return action === 'duplicate' ? 0 : 1;
            };
            sandbox.removeFromQueue = id => {
                assert.equal(id, 'fixture:42'); calls.push('remove'); return action !== 'downloading';
            };
            sandbox.syncSeriesResultsQueueState = () => calls.push('sync-series');
            sandbox.syncUserResultsQueueState = () => calls.push('sync-user');
            sandbox.input = item;
            if (mode === 'search') vm.runInContext('searchState.results = [input]', sandbox);
            if (userMode) {
                sandbox.username = username;
                vm.runInContext("Object.assign(userState, {items: [input], userId: 'author-id', username, variant: 'fixture-variant'})", sandbox);
            }
            const toggle = userMode ? sandbox.addUserItemToQueue
                : mode === 'search' ? sandbox.addSearchItemToQueue : sandbox.addSeriesItemToQueue;
            toggle(action === 'missing' ? 1 : 0);
            const expected = {
                add: ['source', 'add', ['status.added-to-queue', 'success']],
                duplicate: ['source', 'add', ['status.already-in-queue', 'info']],
                keep: ['merge', ['status.already-in-queue', 'info']],
                merge: ['merge', 'stats', 'save', 'render', ['status.already-in-queue', 'info']],
                remove: ['merge', 'remove', ['status.removed-from-queue', 'info']],
                downloading: ['merge', 'remove', ['status.cannot-remove-downloading', 'warning']],
                missing: []
            }[action];
            if (mode === 'series' && ['add', 'duplicate'].includes(action)) expected.push('sync-series');
            if (userMode && ['add', 'duplicate'].includes(action)) {
                expected.shift();
                expected.push('sync-user');
            }
            assert.deepEqual(calls, expected);
        });
    }
}
