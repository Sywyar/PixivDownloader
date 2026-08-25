'use strict';

const assert = require('assert');
const test = require('node:test');
const {
    typeDescriptor,
    manifest,
    harness,
    waitUntil
} = require('./batch-queue-types-fixture');

const ENTRY = `(async function (context) {
    var shared = {context: context};
    await context.loadSubmodule('./queue-part.js', shared);
    return {descriptor: {process: function () { return shared.value; }}};
})`;

test('子模块在入口激活域内动态加载且只能登记一次', async () => {
    const h = harness([manifest(1, [typeDescriptor()])], {
        '/modules/demo.js': {initializer: ENTRY},
        '/modules/queue-part.js': {source: `(function () {
            var first = window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
                shared.value = 'loaded';
                shared.context.onCleanup(function () {
                    testState.submoduleCleanup = (testState.submoduleCleanup || 0) + 1;
                });
            });
            var second = window.PixivBatch.queueTypes.registerSubmodule(function () {});
            testState.submoduleRegistrations = [first, second];
        })()`}
    });

    await h.qt.bootstrap();

    assert.strictEqual(h.qt.get('demo').process(), 'loaded');
    assert.deepStrictEqual(Array.from(h.sandbox.testState.submoduleRegistrations), [true, false]);
    assert.ok(h.loads.some(url => url.startsWith('/modules/queue-part.js?__queue_type_submodule=')));
});

test('子模块拒绝越出入口目录并使整个候选激活失败', async () => {
    const initializer = `(async function (context) {
        await context.loadSubmodule('../foreign.js', {context: context});
        return {descriptor: {process: function () {}}};
    })`;
    const h = harness([manifest(1, [typeDescriptor()])], {
        '/modules/demo.js': {initializer},
        '/foreign.js': {initializer: `(function () {})`}
    });

    await h.qt.bootstrap();

    assert.strictEqual(h.qt.get('demo'), null);
    assert.ok(!h.loads.some(url => url.includes('/foreign.js')));
});

test('publication 撤回会取消子模块激活并执行已登记清理', async () => {
    const h = harness([
        manifest(1, [typeDescriptor()]),
        manifest(2, [])
    ], {
        '/modules/demo.js': {initializer: ENTRY},
        '/modules/queue-part.js': {initializer: `(function (shared) {
            shared.value = 'loaded';
            shared.context.onCleanup(function () {
                testState.submoduleCleanup = (testState.submoduleCleanup || 0) + 1;
            });
        })`}
    });
    await h.qt.bootstrap();

    await h.qt.refresh();

    assert.strictEqual(h.qt.get('demo'), null);
    assert.strictEqual(h.sandbox.testState.submoduleCleanup, 1);
});

test('悬挂子模块与 initializer 共用超时并被取消', async () => {
    const h = harness([manifest(1, [typeDescriptor()])], {
        '/modules/demo.js': {initializer: ENTRY},
        '/modules/queue-part.js': {never: true}
    }, {fakeTimers: true});
    const boot = h.qt.bootstrap();
    await waitUntil(() => h.loads.some(url => url.includes('/modules/queue-part.js')));

    h.advanceTimers(5000);
    await boot;

    assert.strictEqual(h.qt.get('demo'), null);
});
