'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function editorDocument() {
    const document = {};
    const root = new MiniElement('main', document);
    document.body = root;
    document.addEventListener = root.addEventListener.bind(root);
    document.removeEventListener = root.removeEventListener.bind(root);
    document.dispatchEvent = root.dispatchEvent.bind(root);
    document.documentElement = {clientWidth: 800};
    document.getElementById = id => root.querySelector('#' + id);
    document.createElement = tag => {
        const element = new MiniElement(tag, document);
        element.append = (...children) => children.forEach(child => element.appendChild(child));
        element.replaceChildren = (...children) => {
            while (element.firstChild) element.removeChild(element.firstChild);
            element.append(...children);
        };
        element.remove = () => element.parentNode?.removeChild(element);
        element.getBoundingClientRect = () => ({top: 20, bottom: 60, left: 20, right: 240, width: 220, height: 40});
        element.offsetWidth = 220;
        element.offsetHeight = 40;
        element.showModal = () => { element.open = true; };
        element.close = () => { element.open = false; element.dispatchEvent({type: 'close'}); };
        element.showPopover = () => {
            element.dispatchEvent({type: 'beforetoggle', newState: 'open'});
            element.popoverOpen = true;
        };
        element.hidePopover = () => {
            if (!element.popoverOpen) return;
            element.dispatchEvent({type: 'beforetoggle', newState: 'closed'});
            element.popoverOpen = false;
        };
        Object.defineProperty(element, 'isConnected', {get: () => root.contains(element)});
        return element;
    };
    const mount = document.createElement('section');
    root.appendChild(mount);
    return {document, mount};
}

function harness(options = {}) {
    const values = {pixiv_cookie: 'PHPSESSID=1_fixture', ...(options.values || {})};
    let catalog = {revision: '0', entries: options.entries || []};
    const requests = [];
    const writes = [];
    const dom = options.dom ? editorDocument() : null;
    const context = vm.createContext({setTimeout, clearTimeout, window: {innerHeight: 600,
        addEventListener() {}, removeEventListener() {}, matchMedia: () => ({matches: true}),
        PixivBatchAlt: {cookie: {}}, PixivFeedback: {confirm: async request => {
        assert.ok(request.message && request.confirmLabel && request.cancelLabel);
        return options.confirmed !== false;
    }}},
        document: dom?.document || {getElementById: () => null},
        BASE: '', isAdmin: options.admin !== false, AbortSignal, crypto: require('node:crypto').webcrypto, bt: key => key,
        storeGet: key => values[key], getCookie: () => values.pixiv_cookie || '',
        validateAndParseCookie: value => ({ok: value.includes('PHPSESSID=')}),
        parseCookieToHeaderString: value => value,
        persistStoreEntries: async changes => {
            writes.push({...changes});
            if (options.failState) throw new Error('state unavailable');
            Object.assign(values, changes);
        },
        fetch: async (_url, init) => {
            if (init.method === 'PUT') {
                if (options.beforeSave) await options.beforeSave();
                const body = JSON.parse(init.body);
                requests.push(body);
                if (options.failSave) return {ok: false, status: options.failStatus || 500};
                catalog = {revision: '1', entries: body.entries.map(({secret, ...entry}) => entry)};
            }
            return {ok: true, json: async () => structuredClone(catalog)};
        }
    });
    if (dom) {
        for (const file of ['pixiv-batch/cookie-storage.js', 'pixiv-batch-alt/alt-cookie.js']) {
            vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static', file), 'utf8'), context);
        }
    }
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-batch/credential-library.js'), 'utf8'), context);
    return {api: context.window.PixivCredentials, values, requests, writes, context, ...dom};
}

const settle = () => new Promise(resolve => setImmediate(resolve));
test('我的 Pixiv 默认第一个 Cookie，切换账号独立于手动候选列表', async () => {
    const h = harness({dom: true, entries: [
        {id: 'a', name: 'Account A', enabled: true}, {id: 'b', name: 'Account B', enabled: true}],
        values: {pixiv_cookie: '', pixiv_credential_selection: '["b","a"]'}});
    await h.api.initialize();
    assert.equal(h.api.accountHeaders()['X-Acquisition-Credentials'], 'a');
    let changed = 0;
    h.api.mountAccount(h.mount, {onChange: () => changed++});
    const select = h.mount.querySelector('select');
    assert.equal(select.value, 'a');
    assert.equal(select.querySelectorAll('option').length, 2);
    assert.equal(h.writes.length, 0);
    select.value = 'b';
    select.dispatchEvent({type: 'change'});
    await settle();
    assert.equal(h.values.pixiv_account_credential, 'b');
    assert.equal(h.values.pixiv_credential_selection, '["b","a"]');
    assert.equal(h.api.accountHeaders()['X-Acquisition-Credentials'], 'b');
    assert.equal(changed, 1);
    h.values.pixiv_account_credential = 'missing';
    h.api.refresh();
    assert.equal(h.api.accountHeaders()['X-Acquisition-Credentials'], 'a');
    assert.equal(select.value, 'a');
    assert.equal(changed, 2);
});

test('我的 Pixiv 保留有效选择，缺省或失效时选择第一个启用的 Cookie，无可用项才空选', async () => {
    for (const [saved, entries, expected] of [
        ['b', [{id: 'a', enabled: true}, {id: 'b', enabled: true}], 'b'],
        ['', [{id: 'a', enabled: false}, {id: 'b', enabled: true}], 'b'],
        ['a', [{id: 'a', enabled: false}, {id: 'b', enabled: true}], 'b'],
        ['', [], ''],
        ['a', [{id: 'a', enabled: false}], '']
    ]) {
        const h = harness({dom: true, entries, values: {pixiv_cookie: '', pixiv_account_credential: saved}});
        await h.api.initialize();
        h.api.mountAccount(h.mount);
        const select = h.mount.querySelector('select');
        assert.equal(select.value, expected);
        assert.equal(select.disabled, !expected);
        assert.equal(h.writes.length, 0);
        if (expected) assert.equal(h.api.accountHeaders()['X-Acquisition-Credentials'], expected);
        else {
            assert.equal(h.api.accountSelection(), null);
            assert.throws(() => h.api.accountHeaders(), /account-required/);
        }
    }
});

test('个人计划的 Cookie 选择只返回一个候选，单个可用项直接选择', async () => {
    const one = harness({entries: [{id: 'a', name: 'A', enabled: true}]});
    assert.deepEqual(Array.from((await one.api.choose({single: true})).ids), ['a']);
    const h = harness({dom: true, entries: [
        {id: 'a', name: 'A', enabled: true}, {id: 'b', name: 'B', enabled: true}]});
    let spec;
    const choosing = h.api.choose({single: true, openDialog: value => {
        spec = value;
        value.root.append(value.body, value.footer);
        return {close: () => value.root.close()};
    }});
    await settle();
    const radios = spec.body.querySelectorAll('input');
    assert.ok(radios.every(input => input.type === 'radio'));
    radios[0].checked = true;
    radios[0].dispatchEvent({type: 'change'});
    radios[1].checked = true;
    radios[1].dispatchEvent({type: 'change'});
    spec.footer.querySelector('.btn-blue').click();
    assert.deepEqual(Array.from((await choosing).ids), ['b']);
    assert.equal(h.writes.length, 0);
});
const control = (root, key) => root.querySelector('[data-i18n="batch:credentials.' + key + '"]');
async function mounted(options = {}) {
    const h = harness({...options, dom: true, values: {pixiv_cookie: '', ...options.values}});
    await h.api.initialize();
    h.api.mount(h.mount);
    await settle();
    return h;
}

test('Cookie 编辑器在独立原生弹窗打开，三种格式经同一保存入口转换且完成后清除明文', async () => {
    for (const [format, raw] of [
        ['header', 'PHPSESSID=fixture'], ['json', '{"PHPSESSID":"fixture"}'],
        ['netscape', '#HttpOnly_.pixiv.net\tTRUE\t/\tTRUE\t0\tPHPSESSID\tfixture']
    ]) {
        const h = await mounted();
        const form = h.mount.querySelector('form');
        assert.equal(form.hidden, true);
        control(h.mount, 'add').click();
        assert.equal(form.closest('dialog').open, true);
        assert.equal(form.hidden, false);
        const name = form.querySelector('input');
        assert.equal(h.document.activeElement, name);
        name.value = 'Account';
        form.querySelector('select').value = format;
        const secret = form.querySelector('textarea');
        secret.value = raw;
        form.dispatchEvent({type: 'submit'});
        await settle();
        assert.equal(h.requests.at(-1).entries[0].secret, 'PHPSESSID=fixture');
        assert.equal(form.hidden, true);
        assert.equal(form.closest('dialog').open, false);
        assert.equal(secret.value, '');
        assert.equal(JSON.parse(h.values.pixiv_credential_selection).length, 1);
        assert.equal(h.document.activeElement, control(h.mount, 'add'));
    }
});

test('勾选与排序保留列表节点和展开状态，每次操作只更新一次页面选择', async () => {
    const h = await mounted({entries: [{id: 'a', name: 'A', enabled: true}, {id: 'b', name: 'B', enabled: true}],
        values: {pixiv_credential_selection: '["a"]'}});
    const rows = h.mount.querySelectorAll('.credential-entry');
    assert.equal(rows[0].querySelector('.credential-rank').hidden, true);
    assert.equal(rows[0].querySelector('.credential-move').hidden, true);
    assert.equal(rows[0].querySelector('.credential-state').textContent, 'batch:credentials.preferred');
    assert.equal(rows[0].querySelector('.credential-more').getAttribute('aria-label'), 'batch:credentials.more');
    const checkbox = rows[1].querySelector('input');
    checkbox.checked = true;
    checkbox.dispatchEvent({type: 'change'});
    await settle();
    assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), ['a', 'b']);
    assert.equal(h.writes.length, 1);
    assert.equal(rows[0].querySelector('.credential-rank').hidden, false);
    assert.equal(rows[1].querySelector('.credential-move').hidden, false);
    rows[1].querySelector('.credential-more').click();
    rows[1].querySelector('.credential-move').focus();
    rows[1].querySelector('.credential-move').click();
    await settle();
    assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), ['b', 'a']);
    assert.equal(h.writes.length, 2);
    assert.equal(h.mount.querySelector('.credential-entry'), rows[1]);
    assert.equal(rows[1].dataset.preferred, 'true');
    assert.equal(rows[0].dataset.preferred, 'false');
    assert.equal(h.document.activeElement, rows[1].querySelector('input'));
    h.api.refresh();
    assert.equal(rows[1].querySelector('[role="menu"]').popoverOpen, true);
    const summary = rows[1].querySelector('.credential-more');
    const escape = {type: 'keydown', key: 'Escape'};
    summary.dispatchEvent(escape);
    assert.equal(escape.defaultPrevented, true);
    assert.equal(escape.propagationStopped, true);
    assert.equal(h.document.activeElement, summary);
    assert.equal(rows[1].querySelector('[role="menu"]').popoverOpen, false);
});

test('保存失败保留编辑草稿，刷新不覆盖输入，取消清空输入并还原焦点', async () => {
    const options = {dom: true, values: {pixiv_cookie: ''}, failSave: true};
    const h = harness(options);
    await h.api.initialize();
    h.api.mount(h.mount);
    await settle();
    const add = control(h.mount, 'add');
    add.click();
    const form = h.mount.querySelector('form');
    form.querySelector('input').value = 'My account';
    form.querySelector('textarea').value = 'PHPSESSID=fixture';
    form.dispatchEvent({type: 'submit'});
    await settle();
    assert.equal(form.hidden, false);
    assert.equal(form.querySelector('textarea').value, 'PHPSESSID=fixture');
    assert.equal(h.mount.querySelector('[role="status"]').textContent, 'batch:credentials.failed');
    control(h.mount, 'reload').click();
    await settle();
    assert.equal(form.querySelector('input').value, 'My account');
    control(form, 'cancel').click();
    assert.equal(form.hidden, true);
    assert.equal(form.querySelector('textarea').value, '');
    assert.equal(h.document.activeElement, add);
});

test('菜单支持方向键、Escape 与离焦关闭，关闭后恢复触发按钮', async () => {
    const h = await mounted({entries: [{id: 'a', name: 'A', enabled: true}]});
    const trigger = h.mount.querySelector('.credential-more');
    const menu = h.mount.querySelector('[role="menu"]');
    trigger.dispatchEvent({type: 'keydown', key: 'ArrowUp'});
    assert.equal(trigger.getAttribute('aria-expanded'), 'true');
    assert.equal(h.document.activeElement, control(menu, 'delete'));
    const escape = {type: 'keydown', key: 'Escape'};
    menu.dispatchEvent(escape);
    assert.equal(escape.propagationStopped, true);
    assert.equal(menu.popoverOpen, false);
    assert.equal(h.document.activeElement, trigger);
    trigger.click();
    menu.dispatchEvent({type: 'focusout', relatedTarget: control(h.mount, 'add')});
    assert.equal(trigger.getAttribute('aria-expanded'), 'false');
});

test('编辑弹窗保存期间拒绝 Escape，保存成功后关闭；取消清理明文且不关闭外层', async () => {
    let complete;
    const h = await mounted({beforeSave: () => new Promise(resolve => { complete = resolve; })});
    const add = control(h.mount, 'add');
    add.click();
    const dialog = h.mount.querySelector('dialog');
    const form = dialog.querySelector('form');
    form.querySelector('input').value = 'A';
    form.querySelector('textarea').value = 'PHPSESSID=fixture';
    form.dispatchEvent({type: 'submit'});
    const escape = {type: 'keydown', key: 'Escape'};
    dialog.dispatchEvent(escape);
    assert.equal(escape.propagationStopped, true);
    assert.equal(dialog.open, true);
    complete();
    await settle();
    assert.equal(dialog.open, false);
    add.click();
    form.querySelector('textarea').value = 'PHPSESSID=cancelled';
    dialog.dispatchEvent({type: 'keydown', key: 'Escape'});
    assert.equal(form.querySelector('textarea').value, '');
    assert.equal(h.document.activeElement, add);
});

test('快速获取默认收起，展开保留草稿且 Esc 先收起再关闭编辑弹窗', async () => {
    const h = harness({dom: true, values: {pixiv_cookie: ''}});
    h.context.appMode = 'solo';
    await h.api.initialize();
    const importButton = h.document.createElement('button');
    let imports = 0;
    importButton.addEventListener('click', () => imports++);
    const dispose = h.api.mount(h.mount, {importButton});
    assert.equal(importButton.type, 'button');
    control(h.mount, 'add').click();
    const dialog = h.mount.querySelector('dialog');
    assert.equal(importButton.closest('dialog'), dialog);
    assert.equal(control(h.mount, 'scope').closest('dialog'), null);
    assert.equal(importButton.getAttribute('aria-describedby'), control(dialog, 'import-help').id);
    const acquisition = importButton.closest('details');
    assert.equal(!!acquisition.open, false);
    assert.equal(control(dialog, 'import-risk').closest('details'), acquisition);
    acquisition.open = true;
    const secret = dialog.querySelector('textarea');
    secret.value = 'PHPSESSID=draft';
    importButton.click();
    assert.equal(imports, 1);
    assert.equal(secret.value, 'PHPSESSID=draft');
    assert.equal(dialog.open, true);
    acquisition.dispatchEvent({type: 'keydown', key: 'Escape'});
    assert.equal(acquisition.open, false);
    assert.equal(dialog.open, true);
    assert.equal(secret.value, 'PHPSESSID=draft');
    assert.equal(h.document.activeElement, acquisition.querySelector('summary'));
    dialog.dispatchEvent({type: 'keydown', key: 'Escape'});
    assert.equal(dialog.open, false);
    dispose();
    assert.equal(secret.value, '');
    assert.equal(dialog.open, false);
});

test('编辑保存不回显 Cookie，空内容保留原值且不改变已有选择', async () => {
    const h = await mounted({entries: [{id: 'a', name: 'A', enabled: true}], values: {pixiv_credential_selection: '[]'}});
    const edit = control(h.mount, 'replace');
    edit.click();
    const form = h.mount.querySelector('form');
    assert.equal(form.querySelector('textarea').value, '');
    form.querySelector('input').value = 'Renamed';
    form.dispatchEvent({type: 'submit'});
    await settle();
    assert.deepEqual(h.requests.at(-1).entries, [{id: 'a', name: 'Renamed', enabled: true, secret: null}]);
    assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), []);
    assert.equal(h.document.activeElement, edit);
});

test('语言绑定只替换标签文字，保留格式选择与编辑草稿', async () => {
    const h = await mounted();
    control(h.mount, 'add').click();
    const form = h.mount.querySelector('form');
    const name = form.querySelector('input');
    const secret = form.querySelector('textarea');
    const format = form.querySelector('select');
    name.value = 'Draft';
    secret.value = '{"PHPSESSID":"fixture"}';
    format.value = 'json';
    for (const label of h.mount.querySelectorAll('[data-i18n]')) label.textContent = 'Translated';
    h.api.refresh();
    assert.equal(form.querySelector('input'), name);
    assert.equal(form.querySelector('textarea'), secret);
    assert.equal(form.querySelector('select'), format);
    assert.equal(name.value, 'Draft');
    assert.equal(secret.value, '{"PHPSESSID":"fixture"}');
    assert.equal(format.value, 'json');
});

test('取消后迟到的粘贴结果不能填回明文或抢走焦点', async () => {
    const h = await mounted();
    let finishPaste;
    h.context.navigator = {clipboard: {readText: () => new Promise(resolve => { finishPaste = resolve; })}};
    const add = control(h.mount, 'add');
    add.click();
    const form = h.mount.querySelector('form');
    control(form, 'paste').click();
    control(form, 'cancel').click();
    finishPaste('PHPSESSID=fixture');
    await settle();
    assert.equal(form.querySelector('textarea').value, '');
    assert.equal(h.document.activeElement, add);
});

test('删除 Cookie 提供完整确认文案，取消不写入，确认后清理选择并恢复焦点', async () => {
    for (const confirmed of [false, true]) {
        const h = await mounted({confirmed, entries: [{id: 'a', name: 'A', enabled: true}],
            values: {pixiv_credential_selection: '["a"]'}});
        const remove = control(h.mount, 'delete');
        remove.focus();
        remove.click();
        await settle();
        assert.equal(h.requests.length, confirmed ? 1 : 0);
        assert.equal(h.mount.querySelectorAll('.credential-entry').length, confirmed ? 0 : 1);
        assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), confirmed ? [] : ['a']);
        assert.equal(h.document.activeElement, confirmed ? control(h.mount, 'add') : remove);
    }
});

test('先验证凭据库保存成功，再将旧 Cookie 替换为独立选择引用', async () => {
    const h = harness();
    await h.api.initialize();
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].entries[0].secret, 'PHPSESSID=1_fixture');
    assert.equal(h.values.pixiv_cookie, null);
    assert.equal(h.writes.length, 1);
    assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), ['legacy']);
    assert.deepEqual({...h.api.headers()}, {'X-Acquisition-Credentials': 'legacy'});
    await h.api.initialize();
    assert.equal(h.requests.length, 1);
});

test('凭据库或页面状态保存失败均保留旧 Cookie，不伪造迁移成功', async () => {
    for (const option of [{failSave: true}, {failState: true}]) {
        const h = harness(option);
        await assert.rejects(h.api.initialize());
        assert.equal(h.values.pixiv_cookie, 'PHPSESSID=1_fixture');
        assert.equal(h.api.headers(), null);
    }
});

test('旧 Cookie 迁移不会覆盖凭据库中未选中的同名历史条目', async () => {
    const h = harness({entries: [{id: 'legacy', name: 'Saved account', enabled: true}]});
    await h.api.initialize();
    const entries = h.requests[0].entries;
    assert.equal(entries.length, 2);
    assert.deepEqual(entries[0], {id: 'legacy', name: 'Saved account', enabled: true});
    assert.notEqual(entries[1].id, 'legacy');
    assert.equal(entries[1].secret, 'PHPSESSID=1_fixture');
    assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), [entries[1].id]);
});

test('清空页面选择不删除凭据库，替换首选保持其余顺序', async () => {
    const h = harness({entries: [{id: 'backup', name: 'B', enabled: true}, {id: 'primary', name: 'A', enabled: true}],
        values: {pixiv_cookie: '', pixiv_credential_selection: '["backup","primary"]'}});
    await h.api.initialize();
    await h.api.savePrimary('PHPSESSID=2_fixture', 'header');
    assert.deepEqual(JSON.parse(h.values.pixiv_credential_selection), ['backup', 'primary']);
    const writes = h.requests.length;
    await h.api.savePrimary('', 'header');
    assert.equal(h.requests.length, writes);
    assert.equal(h.api.headers(), null);
});

test('非管理员不能迁移或调用多 Cookie，非法选择不生成请求头', async () => {
    const guest = harness({admin: false, values: {pixiv_credential_selection: '["legacy"]'}});
    await guest.api.initialize();
    assert.equal(guest.requests.length, 0);
    assert.equal(guest.api.headers(), null);
    await assert.rejects(guest.api.savePrimary('PHPSESSID=1_fixture', 'header'));
    for (const selection of ['["a","a"]', '["a\r\nvalue"]', '{}']) {
        const h = harness({values: {pixiv_credential_selection: selection}});
        assert.equal(h.api.headers(), null);
    }
});

test('计划选择只读取可用 Cookie，唯一项直接采用且不修改手动下载选择', async () => {
    const h = harness({dom: true, entries: [{id: 'a', name: 'Account A', enabled: true},
        {id: 'b', name: 'Account B', enabled: false}], values: {pixiv_cookie: '', pixiv_credential_selection: '["b"]'}});
    const chosen = await h.api.choose();
    assert.deepEqual(JSON.parse(JSON.stringify(chosen)), {ids: ['a'], label: 'Account A'});
    assert.equal(h.values.pixiv_credential_selection, '["b"]');
    assert.equal(h.writes.length, 0);
    assert.equal(h.requests.length, 0);
    assert.equal(h.document.body.querySelector('dialog'), null);
    const empty = harness({entries: [{id: 'b', enabled: false}]});
    await assert.rejects(empty.api.choose(), /no-credential/);
});

test('计划多 Cookie 选择按勾选顺序返回，取消与来源失效均不修改保存值', async () => {
    const h = harness({dom: true, entries: [{id: 'a', name: 'A', enabled: true}, {id: 'b', name: 'B', enabled: true}]});
    const controller = new AbortController();
    const pending = h.api.choose({signal: controller.signal});
    await settle();
    let dialog = h.document.body.querySelector('dialog');
    const checkboxes = dialog.querySelectorAll('input');
    const toggle = (index, checked) => {
        checkboxes[index].checked = checked;
        checkboxes[index].dispatchEvent({type: 'change'});
    };
    toggle(1, true);
    toggle(0, true);
    dialog.querySelector('.btn-blue').dispatchEvent({type: 'click'});
    assert.deepEqual(JSON.parse(JSON.stringify(await pending)), {ids: ['b', 'a'], label: 'B、A'});
    assert.equal(h.document.body.querySelector('dialog'), null);
    for (const cancel of ['escape', 'abort']) {
        const abort = new AbortController();
        const choice = h.api.choose({signal: abort.signal});
        await settle();
        dialog = h.document.body.querySelector('dialog');
        if (cancel === 'abort') abort.abort();
        else dialog.dispatchEvent({type: 'keydown', key: 'Escape', preventDefault() {}, stopPropagation() {}});
        assert.equal(await choice, null);
        assert.equal(h.document.body.querySelector('dialog'), null);
    }
    assert.equal(h.writes.length, 0);
    assert.equal(h.requests.length, 0);
});
