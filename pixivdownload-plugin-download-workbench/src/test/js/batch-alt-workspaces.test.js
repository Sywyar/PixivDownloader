'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {execFileSync} = require('node:child_process');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

test('Cupertino 控件使用原生开关状态并保留业务回调和禁用属性', () => {
    const document = {
        addEventListener() {},
        createElement(tag) {
            const node = new MiniElement(tag);
            node.append = (...children) => children.forEach(child => node.appendChild(child));
            return node;
        },
    };
    const context = vm.createContext({document});
    context.window = context;
    for (const name of ['alt-core.js', 'alt-settings.js']) {
        vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch-alt', name), 'utf8'), context);
    }
    const changes = [];
    const control = context.switchControl(true, value => changes.push(value), false);
    const input = control.querySelector('input');
    assert.equal(input.type, 'checkbox');
    assert.equal(input.checked, true);
    assert.equal(input.getAttribute('role'), 'switch');
    input.checked = false;
    input.dispatchEvent({type: 'change'});
    assert.deepEqual(changes, [false]);
    assert.equal(context.switchControl(false, () => {}, true).querySelector('input').disabled, true);
    const action = context.el('button', 'ab-btn ab-btn--primary', '操作');
    assert.ok(action.classList.contains('button-fill'));
    assert.equal(action.textContent, '操作');
});

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
        scrollY: 320, addEventListener() {}, PixivBatchAlt: {chrome: {}},
    });
    context.window = context;
    context.scrollTo = ({top}) => { context.scrollY = top; };
    const source = resolve(__dirname, '../../main/resources/static/pixiv-batch-alt');
    for (const name of ['alt-selection.js', 'alt-modes.js', 'alt-chrome.js']) {
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

test('作品选择跨页保留，按查询和插件换代清理，批量入队保留作品元数据', () => {
    const handlers = {};
    const batches = [];
    const context = vm.createContext({
        state: {mode: 'search'}, QUICK_FETCH_MODE: 'quick-fetch', extraFilters: {},
        searchState: {source: 'demo', kind: 'novel', word: 'cat', page: 1},
        document: {getElementById: () => null},
        addEventListener: (name, handler) => { handlers[name] = handler; },
        buildQueueMeta: (item, kind, options) => ({...item, kind, seriesId: options.seriesId}),
        addItemsToQueue: (ids, meta, ...options) => { batches.push({ids, meta, options}); return ids.length; },
        syncAllResultsQueueState() {}, abToast() {}, bt: (_, fallback) => fallback,
    });
    context.window = context;
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch-alt/alt-selection.js'), 'utf8'), context);
    const selection = context.currentWorkSelection();
    const options = {source: 'search', username: 'writer', seriesId: 42};
    for (const id of ['n1', 'n2']) selection.set(id, {item: {id, title: id}, kind: 'novel', options});
    context.searchState.page = 2;
    assert.equal(context.currentWorkSelection(), selection);
    context.enqueueSelectedWorks();
    assert.equal(batches.length, 1, '一批选择只触发一次队列持久化和重绘');
    assert.deepEqual(Array.from(batches[0].ids), ['n1', 'n2']);
    assert.equal(batches[0].meta[1].seriesId, 42);
    assert.equal(batches[0].meta[1].kind, 'novel');
    assert.equal(batches[0].options[0], 'search');
    assert.equal(context.currentWorkSelection().size, 0);
    selection.set('n3', {});
    context.searchState.word = 'dog';
    assert.equal(context.currentWorkSelection().size, 0);
    context.currentWorkSelection().set('n4', {});
    context.extraFilters = {content: 'safe'};
    assert.equal(context.currentWorkSelection().size, 0);
    context.currentWorkSelection().set('n5', {});
    handlers['pixivbatch:queuetypeschanged']();
    assert.equal(context.currentWorkSelection().size, 0);
});

test('计划列表直接提供管理动作，并按名称、来源与状态筛选', () => {
    const calls = [];
    const document = {
        addEventListener() {},
        createElement(tag) {
            const node = new MiniElement(tag);
            node.append = (...children) => children.forEach(child => node.appendChild(child));
            return node;
        },
    };
    const context = vm.createContext({document, pageI18n: null,
        scheduleState: {expandedQueues: new Set()},
        altScheduleSources: () => ({isAvailable: () => true, descriptor: () => null}),
        summaryJoin: items => items.filter(Boolean).join(' · '),
        scheduleVerb: (task, verb) => calls.push([task.id, verb]),
        scheduleSetEnabled: (task, enabled) => calls.push([task.id, enabled]),
        openScheduleEditor: task => calls.push([task.id, 'edit']),
    });
    context.window = context;
    for (const name of ['alt-core.js', 'alt-schedule.js']) {
        vm.runInContext(readFileSync(resolve(__dirname,
            '../../main/resources/static/pixiv-batch-alt', name), 'utf8'), context);
    }
    const plan = (id, extra = {}) => ({id, name: id, sourceType: 'demo', enabled: true, ...extra});
    const tasks = [plan('Favorite'), plan('running', {runState: 'RUNNING'}),
        plan('disabled', {enabled: false}), plan('paused', {suspendReason: 'MANUAL', lastStatus: 'PAUSED'}),
        plan('unavailable', {suspendReason: 'SOURCE_UNAVAILABLE'}),
        plan('input-needed', {suspendReason: 'USER_ACTION_REQUIRED'})];
    const ids = (query, filter) => Array.from(context.scheduleVisibleTasks(tasks, query, filter), task => task.id);
    assert.deepEqual(ids(' FAV ', 'all'), ['Favorite']);
    assert.equal(ids('demo', 'all').length, tasks.length);
    assert.deepEqual(ids('', 'running'), ['running']);
    assert.deepEqual(ids('', 'paused'), ['disabled', 'paused']);
    assert.deepEqual(ids('', 'attention'), ['unavailable', 'input-needed']);
    const action = (row, key) => row.querySelectorAll('button').find(button => button.dataset.action === 'schedule.actions.' + key);
    const row = context.scheduleTaskCard(tasks[0]);
    for (const key of ['run', 'pause', 'disable', 'edit']) {
        const button = action(row, key);
        assert.equal(button.disabled, false);
        assert.equal(button.closest('details'), null, '常用动作无需展开');
        button.dispatchEvent({type: 'click'});
    }
    assert.deepEqual(calls, [['Favorite', 'run'], ['Favorite', 'pause'], ['Favorite', false], ['Favorite', 'edit']]);
    assert.ok(action(row, 'delete').closest('details'), '破坏性动作收纳到更多');
    assert.equal(action(context.scheduleTaskCard(tasks[1]), 'run').disabled, true);
    const paused = context.scheduleTaskCard(tasks[3]);
    action(paused, 'resume').dispatchEvent({type: 'click'});
    assert.deepEqual(calls.at(-1), ['paused', 'resume']);
    assert.equal(paused.querySelector('.ab-schedule-next').querySelector('.ab-schedule-meta-value').textContent, '—');
});


test('计划队列的过期响应不得更新或卸载重新展开的容器', async () => {
    let box = {};
    let settle;
    const renders = [];
    const unmounts = [];
    const context = vm.createContext({
        BASE: '', document: {getElementById: () => box},
        fetch: () => new Promise(resolve => { settle = resolve; }),
        PixivBatchAlt: {queueVue: {unmountScheduleQueue: id => unmounts.push(id)}},
    });
    context.window = context;
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch-alt/alt-schedule-actions.js'), 'utf8'), context);
    context.renderScheduleQueue = (target, task, data) => renders.push({target, data});
    for (const ok of [true, false]) {
        const pending = context.loadScheduleQueue({id: 7}, true);
        box = {};
        settle({ok, json: async () => ({error: 'test-error', items: []})});
        await pending;
    }
    assert.equal(renders.length, 0);
    assert.equal(unmounts.length, 0);
    const current = context.loadScheduleQueue({id: 7});
    settle({ok: true, json: async () => ({items: []})});
    await current;
    assert.equal(renders.length, 1);
    assert.equal(renders[0].target, box);
});

test('计划代理与凭证弹窗可打开，字段有标签且不回显已绑定凭证', () => {
    const context = vm.createContext({
        document: {createElement: tag => new MiniElement(tag), addEventListener() {}},
        altScheduleSources: () => ({isAvailable: () => true,
            credentialActions: () => ({supportsCookie: true, supportsProxy: true, presentation: {}})}),
    });
    context.window = context;
    for (const name of ['alt-core.js', 'alt-schedule.js', 'alt-schedule-actions.js', 'alt-schedule-editor.js']) {
        vm.runInContext(readFileSync(resolve(__dirname,
            '../../main/resources/static/pixiv-batch-alt', name), 'utf8'), context);
    }
    let modal;
    context.openModal = options => { modal = options; };
    context.openScheduleOverride({id: 7, sourceType: 'demo', proxy: 'localhost:8080', cookieBound: true});
    assert.equal(modal.id, 'schedule-override');
    const proxy = modal.body.querySelector('input');
    const credential = modal.body.querySelector('textarea');
    assert.equal(proxy.value, 'localhost:8080');
    assert.ok(proxy.getAttribute('aria-label'));
    assert.ok(credential.getAttribute('aria-label'));
    assert.equal(credential.value, '');
});

test('侧栏关闭保护未保存内容，重新打开可打断关闭且保留新内容', async () => {
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

    const animations = [];
    root.getAnimations = () => animations.filter(animation => !animation.cancelled);
    root.animate = frames => {
        let finish;
        const animation = {frames, finished: new Promise(resolve => { finish = resolve; }),
            cancel() { this.cancelled = true; }, finish: () => finish()};
        animations.push(animation);
        return animation;
    };
    context.matchMedia = () => ({matches: false});
    context.getComputedStyle = () => ({opacity: '1', transform: 'matrix(1, 0, 0, 1, 200, 0)'});
    context.openDrawer({body: new MiniElement('input')});
    context.closeDrawer();
    const closingAnimation = animations.at(-1);
    assert.equal(closingAnimation.frames[0].transform, 'matrix(1, 0, 0, 1, 200, 0)',
        '从屏幕上的当前位置收起，避免中途跳回起点');
    const replacement = new MiniElement('input');
    context.openDrawer({body: replacement});
    closingAnimation.finish();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(root.open, true);
    assert.equal(root.querySelector('input'), replacement, '过期关闭动画不能清除新侧栏');
    context.matchMedia = () => ({matches: true});
    const count = animations.length;
    context.closeDrawer();
    assert.equal(root.open, false);
    assert.equal(animations.length, count, '减少动态效果时直接关闭');
});
