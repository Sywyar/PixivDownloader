'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement, MiniStorage} = require('./pixiv-layout-feedback-test-dom');

function setup() {
    const document = {addEventListener() {}};
    const root = new MiniElement('body', document);
    document.getElementById = id => root.querySelector('#' + id);
    document.createElement = tag => {
        const node = new MiniElement(tag, document);
        node.append = (...children) => children.forEach(child => node.appendChild(child));
        node.replaceChildren = (...children) => {
            [...node.children].forEach(child => node.removeChild(child));
            node.append(...children);
        };
        const query = node.querySelectorAll.bind(node);
        node.querySelectorAll = selector => selector.split(',').flatMap(part => query(part.trim()));
        Object.defineProperty(node, 'isConnected', {get: () => root.contains(node)});
        return node;
    };
    const append = (tag, id, parent = root) => {
        const node = document.createElement(tag);
        node.id = id;
        parent.appendChild(node);
        return node;
    };
    const modal = append('dialog', 'abModalRoot');
    modal.showModal = () => { modal.open = true; };
    modal.close = () => { modal.open = false; };
    const chip = append('button', 'abCookieChip');
    const label = append('span', '', chip);
    label.className = 'ab-chip-label';
    label.setAttribute('data-i18n', 'cookie.status.missing');
    const requests = [], toasts = [], renders = [];
    const context = vm.createContext({document, console, localStorage: new MiniStorage(),
        setTimeout: () => 1, clearTimeout() {}, addEventListener() {},
        fetch: async (url, options) => { requests.push(JSON.parse(options.body)); return {ok: true}; },
    });
    context.window = context;
    for (const name of ['alt-core.js', '../pixiv-batch/batch-download-defaults.js', '../pixiv-batch/batch-pagination.js', 'alt-state.js', 'alt-cookie.js', 'alt-chrome.js', 'alt-mode-capture.js']) {
        vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch-alt', name), 'utf8'), context);
    }
    Object.assign(context, {
        abIconEl: () => document.createElement('i'), hydrateIcons() {}, refreshAltSlots() {},
        appendExtensionCookieEditors() {}, abConfirm: async () => true,
        abToast: (...args) => toasts.push(args), renderStage: () => renders.push(context.hasPixivCookie()),
    });
    const run = code => vm.runInContext(code, context);
    const click = async node => {
        for (const listener of node.listeners.get('click') || []) await listener({target: node});
    };
    const open = () => {
        context.openCookieModal();
        return {body: modal.querySelector('.ab-cookie'), input: document.getElementById('abCookieInput'),
            save: modal.querySelector('.ab-btn--primary'), clear: modal.querySelector('.ab-btn--danger-ghost')};
    };
    return {context, run, click, open, modal, label, requests, renders, toasts, document};
}

test('Cookie 保存等待持久化后关闭弹窗，翻译重绘保留新状态，账号和搜索提示同步更新', async () => {
    const h = setup();
    h.run("state.mode = 'search'; quickState.uid = 'old-account';");
    const editor = h.open();
    await h.click(editor.body.querySelector('[data-value="json"]'));
    assert.equal(h.context.getCookieFmt(), 'header', '格式切换只是草稿');
    editor.input.value = '{"PHPSESSID":"test-only","other":"value"}';
    let complete;
    h.context.fetch = (url, options) => {
        h.requests.push(JSON.parse(options.body));
        return new Promise(resolve => { complete = resolve; });
    };
    const saving = h.click(editor.save);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(editor.save.disabled, true);
    assert.equal(h.context.hasPixivCookie(), false);
    await h.click(editor.save);
    assert.equal(h.requests.length, 1, '阻止重复保存');
    await h.modal._abRequestClose();
    assert.equal(h.modal.open, true, '保存期间不关闭弹窗');
    complete({ok: true});
    await saving;
    assert.equal(h.modal.open, false);
    assert.equal(h.context.getCookieFmt(), 'json');
    assert.equal(h.context.getCookie(), 'PHPSESSID=test-only; other=value');
    assert.equal(h.label.getAttribute('data-i18n'), 'cookie.status.saved');
    assert.equal(h.label.textContent, 'Cookie 已保存');
    assert.equal(h.run('quickState.uid'), null);
    assert.deepEqual(h.renders, [true]);
    assert.equal(h.toasts[0][0], 'success');
    assert.equal(h.requests[0].state.pixiv_cookie_fmt, 'json');
});

test('无效输入和持久化失败保留弹窗与已保存 Cookie，重试成功后才更新状态', async () => {
    const h = setup();
    h.run("serverState = {pixiv_cookie:'PHPSESSID=previous', pixiv_cookie_fmt:'header'}");
    const editor = h.open();
    editor.input.value = 'invalid';
    await h.click(editor.save);
    assert.equal(h.requests.length, 0);
    await h.click(editor.body.querySelector('[data-value="json"]'));
    editor.input.value = '{"PHPSESSID":"test-only-new"}';
    h.context.fetch = async () => ({ok: false, status: 500});
    await h.click(editor.save);
    assert.equal(h.modal.open, true);
    assert.equal(editor.input.value, '{"PHPSESSID":"test-only-new"}');
    assert.equal(h.context.getCookie(), 'PHPSESSID=previous');
    assert.equal(h.context.getCookieFmt(), 'header');
    assert.equal(editor.save.disabled, false);
    assert.match(h.document.getElementById('abCookieParseArea').textContent, /保存失败/);
    h.context.fetch = async () => ({ok: true});
    await h.click(editor.save);
    assert.equal(h.modal.open, false);
    assert.equal(h.context.getCookie(), 'PHPSESSID=test-only-new');
});

test('清除更新弹窗状态和页面凭据门槛，一键导入只关闭发起导入的弹窗', async () => {
    const h = setup();
    h.run("state.mode = QUICK_FETCH_MODE; serverState = {pixiv_cookie:'PHPSESSID=old'}");
    const old = h.open();
    await h.click(old.clear);
    assert.equal(h.modal.open, true);
    assert.equal(old.input.value, '');
    assert.equal(h.document.getElementById('abCookieStatus').textContent, '未保存 Cookie');
    assert.deepEqual(h.renders, [false]);
    h.context.closeModal();
    h.open();
    h.context.applyImportedCookie({cookie:'PHPSESSID=imported', fmt:'header', syncAt:'1'}, old.body);
    assert.equal(h.modal.open, true, '旧异步请求不得关闭后来打开的弹窗');
    h.context.applyImportedCookie({cookie:'PHPSESSID=imported', fmt:'header', syncAt:'2'}, h.modal.querySelector('.ab-cookie'));
    assert.equal(h.modal.open, false);
    assert.equal(h.label.getAttribute('data-i18n'), 'cookie.status.saved');
    assert.equal(h.renders.at(-1), true);
});

test('自动保存与 Cookie 保存顺序执行，后续快照保留最新凭据和其他设置', async () => {
    const h = setup();
    const pending = [];
    h.context.fetch = (url, options) => {
        h.requests.push(JSON.parse(options.body));
        return new Promise(resolve => pending.push(resolve));
    };
    const first = h.context.persistStoreEntries({});
    const cookie = h.context.persistStoreEntries({pixiv_cookie: 'PHPSESSID=new'});
    h.context.storeSet('pixiv_mode', 'search');
    const later = h.context.persistStoreEntries({});
    for (let i = 0; i < 3; i++) {
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(h.requests.length, i + 1);
        pending.shift()({ok: true});
    }
    await Promise.all([first, cookie, later]);
    assert.equal(h.requests[2].state.pixiv_cookie, 'PHPSESSID=new');
    assert.equal(h.requests[2].state.pixiv_mode, 'search');
});

test('浏览器存储拒绝写入时不关闭编辑器，成功保存缺少登录字段时保留警告', async () => {
    const h = setup();
    h.run("appMode = 'multi'");
    const editor = h.open();
    editor.input.value = 'other=test-only';
    h.context.localStorage.throwOnSet = true;
    await h.click(editor.save);
    assert.equal(h.modal.open, true);
    assert.equal(h.context.hasPixivCookie(), false);
    h.context.localStorage.throwOnSet = false;
    await h.click(editor.save);
    assert.equal(h.modal.open, false);
    assert.equal(h.toasts.at(-1)[0], 'warning');
    assert.equal(h.label.getAttribute('data-i18n'), 'cookie.status.no-phpsessid');
});

test('Cookie 更换后迟到的账号响应不能覆盖新账号', async () => {
    const h = setup();
    const pending = [];
    h.context.quickAcquisition = () => ({type:'test-source', account:{buildRequest:()=>({}), readId:data=>data.id}});
    h.context.altAcquisitionJson = () => new Promise(resolve => pending.push(resolve));
    const old = h.context.loadQuickUid();
    h.context.refreshCookieViews();
    const current = h.context.loadQuickUid();
    pending[1]({id: 'new-account'});
    await current;
    pending[0]({id: 'old-account'});
    await old;
    assert.equal(h.run('quickState.uid'), 'new-account');
});

test('贡献的通用凭据编辑器复用保存完成与失败反馈', async () => {
    const h = setup();
    for (const name of ['commitQueueItemPatch', 'addItemsToQueue', 'removeFromQueue', 'renderQueue',
        'updateStats', 'syncAllResultsQueueState']) h.context[name] = () => {};
    vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch-alt/alt-extensions.js'), 'utf8'), h.context);
    h.context.PixivBatch.queueTypes = {
        contributionsOf: () => [{type:'test-source', validate:raw=>({ok:!!raw})}],
        get: () => null, manifestDescriptor: () => null,
    };
    const {body} = h.open();
    const section = body.querySelector('section');
    const input = section.querySelector('textarea');
    input.value = 'test-only-credential';
    h.context.fetch = async () => ({ok:false,status:403});
    await h.click(section.querySelector('.ab-btn--primary'));
    assert.equal(h.modal.open, true);
    assert.match(section.querySelector('[role="status"]').textContent, /保存失败/);
    h.context.fetch = async () => ({ok:true});
    await h.click(section.querySelector('.ab-btn--primary'));
    assert.equal(h.modal.open, false);
    assert.equal(h.context.getStoredCookie('test-source'), 'test-only-credential');
});
