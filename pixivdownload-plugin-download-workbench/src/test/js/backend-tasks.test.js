'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function fixture(admin = true) {
    const document = new MiniElement('document');
    const window = new MiniElement('window');
    class Element extends MiniElement {
        append(...children) { children.forEach(child => this.appendChild(child)); }
        replaceChildren(...children) {
            this.children.slice().forEach(child => this.removeChild(child));
            this.append(...children);
        }
        remove() { if (this.parentNode) this.parentNode.removeChild(this); }
    }
    document.createElement = tag => new Element(tag, document);
    const host = document.createElement('section');
    const timers = new Map();
    const calls = [];
    let sequence = 0;
    let value = {epoch: 'one', revision: 1, tasks: []};
    let fail = false;
    const context = vm.createContext({
        document, window, isAdmin: admin, AbortController,
        bt: (key, fallback, vars) => key + (vars ? JSON.stringify(vars) : ''),
        setTimeout: fn => { timers.set(++sequence, fn); return sequence; },
        clearTimeout: id => timers.delete(id),
        fetch: async (url, options) => {
            calls.push({url, options});
            return {ok: !fail, json: async () => value};
        }
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-batch/backend-tasks.js'), 'utf8'), context);
    context.mountBackendTasks(host);
    return {context, host, document, window, calls, timers,
        setValue(next) { value = next; },
        fail() { fail = true; },
        async poll() {
            await flush();
            const fn = Array.from(timers.values())[0];
            assert.ok(fn);
            fn();
            await flush();
        }};
}
async function flush() { for (let i = 0; i < 8; i++) await Promise.resolve(); }
function task(id, phase = 'STARTED', title = 'Work') {
    return {attempt: {attemptId: id, workType: 'example-download', workId: '1'}, title, phase};
}

test('服务端任务按精确身份取消，进度和语言更新保留按钮及焦点', async () => {
    const h = fixture();
    h.setValue({epoch: 'one', revision: 2, tasks: [task('attempt-one')]});
    await h.poll();
    const row = h.host.querySelector('li');
    const button = row.querySelector('button');
    button.focus();
    h.setValue({epoch: 'one', revision: 3, tasks: [task('attempt-one', 'STARTED', '<script>')]});
    await h.poll();
    assert.equal(h.host.querySelector('li'), row);
    assert.equal(h.document.activeElement, button);
    assert.equal(row.children[0].textContent, '<script>');
    button.dispatchEvent({type: 'click'});
    await flush();
    assert.equal(h.calls.at(-1).url, '/api/download/tasks/attempt-one/cancel');
    assert.equal(h.calls.at(-1).options.method, 'POST');
    assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 1);
    h.setValue({epoch: 'one', revision: 4, tasks: [task('attempt-one', 'CANCELLED')]});
    await h.poll();
    assert.equal(button.hidden, true);
    h.context.bt = key => 'translated:' + key;
    vm.runInContext('backendTasksPanel.render()', h.context);
    assert.equal(row.children[1].textContent, 'translated:batch:backend-tasks.phase.CANCELLED');
});

test('断线保留过期提示，重启快照替换旧身份，分页限制活动 DOM', async () => {
    const h = fixture();
    h.setValue({epoch: 'two', revision: 1, tasks: Array.from({length: 51}, (_, i) => task(String(i)))});
    await h.poll();
    assert.equal(h.host.querySelectorAll('li').length, 50);
    h.host.children.at(-1).children[2].dispatchEvent({type: 'click'});
    assert.equal(h.host.querySelectorAll('li').length, 1);
    h.setValue({epoch: 'three', revision: 1, tasks: [task('new')]});
    await h.poll();
    assert.equal(h.host.querySelectorAll('li').length, 1);
    h.fail();
    await h.poll();
    assert.equal(h.host.children[2].textContent, 'batch:backend-tasks.unavailable');
    assert.equal(h.host.querySelectorAll('li').length, 1);
});

test('隐藏与退出停止轮询，恢复重取快照，重挂载不叠加监听，非管理员不请求', async () => {
    const h = fixture();
    await flush();
    h.document.hidden = true;
    h.document.dispatchEvent({type: 'visibilitychange'});
    assert.equal(h.timers.size, 0);
    h.document.hidden = false;
    h.document.dispatchEvent({type: 'visibilitychange'});
    await flush();
    h.window.dispatchEvent({type: 'pagehide'});
    assert.equal(h.timers.size, 0);
    h.window.dispatchEvent({type: 'pageshow'});
    await flush();
    h.context.mountBackendTasks(h.host);
    await flush();
    assert.equal(h.window.listenerCount('pagehide'), 1);
    assert.equal(h.document.listenerCount('visibilitychange'), 1);
    vm.runInContext('backendTasksPanel.dispose()', h.context);
    assert.equal(h.timers.size, 0);
    assert.equal(h.window.listenerCount('pagehide'), 0);
    const guest = fixture(false);
    assert.equal(guest.host.hidden, true);
    assert.equal(guest.calls.length, 0);
});
