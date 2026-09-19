'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {execFileSync} = require('node:child_process');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

test('工作区保留各自滚动位置，草稿只保存非敏感输入与光标', () => {
    const root = new MiniElement('body');
    const document = {
        activeElement: null,
        getElementById: id => root.querySelector('#' + id),
        querySelectorAll: selector => root.querySelectorAll(selector),
    };
    function element(tag, id, parent = root) {
        const node = new MiniElement(tag, document);
        node.id = id;
        parent.appendChild(node);
        return node;
    }
    ['abStage', 'abDock', 'abRail', 'abPrepareTab', 'abDockToggle', 'abScheduleTab'].forEach(id => element('div', id));
    const panel = element('div', 'abModePanel');
    panel.dataset.mode = 'single-import';
    const queryFields = panel.querySelectorAll.bind(panel);
    panel.querySelectorAll = selector => selector.split(',').flatMap(part => queryFields(part.trim()));
    const input = element('textarea', 'draft', panel);
    input.value = '长中文作品标题 / draft';
    input.selectionStart = 2;
    input.selectionEnd = 5;
    input.setSelectionRange = (start, end) => { input.selectionStart = start; input.selectionEnd = end; };
    const password = element('input', 'credential', panel);
    password.type = 'password';
    password.value = 'test-only-secret';
    const context = vm.createContext({
        document, console, QUICK_FETCH_MODE: 'quick-fetch', SINGLE_IMPORT_MODE: 'single-import',
        state: {mode: 'single-import'}, dockState: {open: false},
        scrollY: 320, PixivBatchAlt: {chrome: {}},
    });
    context.window = context;
    context.scrollTo = ({top}) => { context.scrollY = top; };
    const source = resolve(__dirname, '../../main/resources/static/pixiv-batch-alt');
    for (const name of ['alt-modes.js', 'alt-chrome.js']) {
        vm.runInContext(readFileSync(resolve(source, name), 'utf8'), context, {filename: name});
    }
    document.activeElement = input;
    context.rememberModeDraft(panel);
    context.openDock();
    assert.equal(document.getElementById('abStage').hidden, true);
    context.scrollY = 900;
    context.rememberModeDraft(panel);
    input.value = '';
    password.value = '';
    context.restoreModeDraft(panel, false);
    assert.equal(context.scrollY, 900, '后台重绘不得滚动正在查看的队列');
    assert.equal(input.value, '长中文作品标题 / draft');
    assert.equal(password.value, '', '密码不得恢复到草稿');
    assert.equal(input.selectionStart, 2);
    context.toggleDock(false);
    assert.equal(context.scrollY, 320);
    assert.equal(document.getElementById('abStage').hidden, false);
    context.openDock();
    assert.equal(context.scrollY, 900);
    assert.equal(document.getElementById('abDockToggle').getAttribute('aria-current'), 'page');
});

test('Web 与 Compose 色板来自同一输入，文本及控件对比度通过', () => {
    execFileSync(process.execPath, ['scripts/design/generate-experience-tokens.mjs', '--check'], {
        cwd: resolve(__dirname, '../../../..'), encoding: 'utf8', stdio: 'pipe',
    });
});


test('未保存弹窗在 Escape 后等待确认，取消保留内容且不会叠加确认', async () => {
    const root = new MiniElement('dialog');
    root.id = 'abDrawerRoot';
    root.showModal = () => { root.open = true; };
    root.close = () => { root.open = false; };
    const createElement = tag => {
        const node = new MiniElement(tag);
        node.append = (...children) => children.forEach(child => node.appendChild(child));
        return node;
    };
    root.replaceChildren = (...children) => { root.children.slice().forEach(child => root.removeChild(child)); children.forEach(child => root.appendChild(child)); };
    const document = {
        getElementById: () => root,
        createElement,
        addEventListener: () => {},
    };
    const context = vm.createContext({document});
    context.window = context;
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch-alt/alt-core.js'), 'utf8'), context);
    context.abIconEl = () => new MiniElement('span');
    let settle;
    let confirmations = 0;
    context.openDrawer({title: '测试草稿', body: new MiniElement('input'), beforeClose: () => {
        confirmations++;
        return new Promise(resolve => { settle = resolve; });
    }});
    let prevented = false;
    root.onkeydown({key: 'Escape', preventDefault: () => { prevented = true; }, stopPropagation: () => {}});
    await root._abRequestClose();
    assert.equal(prevented, true);
    assert.equal(confirmations, 1);
    settle(false);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(root.open, true);
    assert.ok(root.children.length);
    const closing = root._abRequestClose();
    settle(true);
    await closing;
    assert.equal(root.open, false);
    assert.equal(root.children.length, 0);
});
