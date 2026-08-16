'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/pixiv-actions.js'), 'utf8');

function loadActions(root) {
    const window = {};
    vm.runInNewContext(source, {window, document: root});
    return window.PixivActions;
}

test('声明式动作只调用白名单函数并解析受控参数', function () {
    let listener;
    const root = {
        addEventListener(type, callback) {
            assert.equal(type, 'click');
            listener = callback;
        }
    };
    let rawAction = "run('a\\'b', 2, true, null, this, event)";
    const element = {
        dataset: {pixivPrevent: 'true', pixivStop: 'true'},
        closest() { return this; },
        getAttribute() { return rawAction; }
    };
    const event = {
        target: element,
        preventDefaultCalled: false,
        stopPropagationCalled: false,
        stopImmediatePropagationCalled: false,
        preventDefault() { this.preventDefaultCalled = true; },
        stopPropagation() { this.stopPropagationCalled = true; },
        stopImmediatePropagation() { this.stopImmediatePropagationCalled = true; }
    };
    let received = null;

    loadActions(root).bind(root, {
        click: {
            run() { received = {receiver: this, args: Array.from(arguments)}; }
        }
    });
    listener(event);

    assert.equal(received.receiver, element);
    assert.deepEqual(received.args.slice(0, 4), ["a'b", 2, true, null]);
    assert.equal(received.args[4], element);
    assert.equal(received.args[5], event);
    assert.equal(event.preventDefaultCalled, true);
    assert.equal(event.stopImmediatePropagationCalled, true);
    assert.equal(event.stopPropagationCalled, false);

    received = null;
    rawAction = 'run(window.location)';
    listener(event);
    assert.equal(received, null);
});
