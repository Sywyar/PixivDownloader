'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {Element, installVue, all} = require('../../../../../pixivdownload-app/src/test/js/vue-render-fixture');

test('示例槽位在严格 CSP 下真实挂载、响应交互并清理监听器', async () => {
    let initialize, cleanup, active = true, actions = 0, mounts = 0, unmounts = 0;
    const listeners = new Set(), host = new Element('section');
    const sandbox = {window: {
        addEventListener: (_, fn) => listeners.add(fn), removeEventListener: (_, fn) => listeners.delete(fn),
        PixivBatch: {queueTypes: {registerUiModule(fn) { initialize = fn; }}}
    }};
    const Vue = installVue(sandbox);
    sandbox.window.PixivVue = {async mountUiSlot(_, component) {
        const app = Vue.createApp(component);
        app.mount(host); mounts++;
        return {app: {unmount() { app.unmount(); unmounts++; }}};
    }};
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/example-download/example-download-ui-slot.js'), 'utf8'), sandbox);
    initialize({slots: [{target: 'quick-actions-mine'}], isActive: () => active, supports: () => true,
        dispatchQuickAction: () => actions++, onCleanup(fn) { cleanup = fn; }});
    await Promise.resolve();
    const button = all(host, n => n.tag === 'button')[0], ready = all(host, n => n.tag === 'span')[0];
    assert.ok(button);
    assert.equal(ready.props.hidden, true);
    assert.equal(ready.props['data-i18n'], 'example-download:slot.quick.ready');
    button.props.onClick();
    await Vue.nextTick();
    assert.equal(actions, 1);
    assert.equal(all(host, n => n.tag === 'span')[0], ready);
    assert.equal(ready.props.hidden, false);
    for (let i = 0; i < 20; i++) listeners.forEach(fn => fn());
    assert.equal(mounts, 1);
    active = false; cleanup();
    assert.equal(unmounts, 1);
    assert.equal(listeners.size, 0);
    assert.equal(host.children.length, 0);
});
