'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement, MiniEventTarget} = require('./pixiv-layout-feedback-test-dom');
const repository = resolve(__dirname, '../../../..');

function harness() {
    const events = new MiniEventTarget();
    const document = {addEventListener() {}};
    class Element extends MiniElement {
        get firstChild() { return this.children[0] || null; }
        get parentElement() { return this.parentNode; }
        get isConnected() { return document.body.contains(this); }
        remove() { if (this.parentNode) this.parentNode.removeChild(this); }
        append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
        replaceChildren(...nodes) {
            this.children.slice().forEach(child => this.removeChild(child));
            this.append(...nodes);
        }
        matches(selector) { return selector.split(',').some(part => super.matches(part.trim())); }
        set innerHTML(html) {
            this.children.slice().forEach(child => this.removeChild(child));
            const stack = [this];
            for (const match of html.matchAll(/<\/?([a-z][\w-]*)([^>]*)>/gi)) {
                if (match[0].startsWith('</')) { stack.pop(); continue; }
                const element = document.createElement(match[1]);
                for (const attribute of match[2].matchAll(/([\w-]+)="([^"]*)"/g)) {
                    element.setAttribute(attribute[1], attribute[2]);
                }
                stack.at(-1).appendChild(element);
                if (!['input', 'br', 'img', 'hr'].includes(match[1])) stack.push(element);
            }
        }
    }
    document.createElement = tag => new Element(tag, document);
    document.body = document.createElement('body');
    document.getElementById = id => document.body.querySelector('#' + id);
    document.querySelectorAll = selector => document.body.querySelectorAll(selector);
    const writes = [];
    const context = vm.createContext({
        document, console, cookieHasPhpsessid: () => true,
        addEventListener: events.addEventListener.bind(events),
        removeEventListener: events.removeEventListener.bind(events),
        altQueueTypes: () => ({
            resolveSelectionForMode: kind => kind,
            contributionsOf: () => [{type: 'novel', cardId: 'novel-settings-card'}]
        })
    });
    context.window = context;
    const load = path => vm.runInContext(readFileSync(resolve(repository, path), 'utf8'), context, {filename: path});
    const alt = 'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch-alt/';
    ['alt-core.js', 'alt-state.js', 'alt-settings.js'].forEach(name => load(alt + name));
    context.storeSet = (_, value) => writes.push(JSON.parse(value));
    vm.runInContext('isAdmin = true', context);
    const dispatchSlots = () => events.dispatchEvent({type: 'pixivbatch:slotsrendered'});
    const activation = () => {
        let active = true;
        const cleanup = [];
        return {isActive: () => active, onCleanup: fn => cleanup.push(fn),
            revoke() { active = false; cleanup.reverse().forEach(fn => fn()); }};
    };
    const mountNovel = () => {
        const owner = activation();
        const shared = {context: owner};
        context.PixivBatch.queueTypes = {registerSubmodule: initializer => initializer(shared)};
        load('pixivdownload-plugin-novel/src/main/resources/static/pixiv-novel-download/novel-queue-view.js');
        const slot = document.createElement('div');
        slot.innerHTML = shared.NOVEL_SLOTS['settings-card'];
        document.body.appendChild(slot);
        dispatchSlots();
        return {revoke() { owner.revoke(); slot.remove(); }};
    };
    const mountAi = () => {
        const owner = activation();
        context.PixivBatch.queueTypes.registerUiModule = initializer => initializer(owner);
        load('pixivdownload-plugin-ai/src/main/resources/static/pixiv-ai/download-novel-ai-settings-slot.js');
        return owner;
    };
    return {context, document, writes, load, dispatchSlots, mountNovel, mountAi,
        input: id => document.getElementById(id)};
}

test('获取与下载列表共用筛选及设置抽屉，切换分区保留草稿并共享下载偏好', async () => {
    const h = harness();
    const {context, document} = h;
    const alt = 'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch-alt/';
    for (const name of ['alt-filters.js', 'alt-modes.js', 'alt-mode-discovery.js', 'alt-chrome.js']) h.load(alt + name);
    context.abIconEl = () => document.createElement('span');
    context.applyFiltersToCurrentMode = async () => {};
    let mounts = 0;
    context.refreshAltSlots = () => { mounts++; };
    for (const id of ['abDownloadOptions', 'abDrawerRoot']) {
        const node = document.createElement(id === 'abDrawerRoot' ? 'dialog' : 'div');
        node.id = id;
        node.showModal = () => { node.open = true; };
        node.close = () => { node.open = false; };
        document.body.appendChild(node);
    }
    const click = node => node.dispatchEvent({type: 'click'});
    const drawer = document.getElementById('abDrawerRoot');
    vm.runInContext("state.mode = 'search'; searchState.kind = 'illust'; dockState.open = true", context);
    context.bindDockToggle();
    click(document.getElementById('abQueueSettingsBtn'));
    assert.equal(drawer.open, true);
    assert.equal(drawer.querySelector('.ab-settings').hidden, false);
    assert.equal(drawer.querySelector('.ab-filters').hidden, true);
    const interval = drawer.querySelector('.ab-settings').querySelector('input');
    interval.value = '7';
    interval.dispatchEvent({type: 'change'});
    assert.equal(h.writes.at(-1).interval, 7);
    const sections = drawer.querySelector('.ab-download-options').children[0].querySelectorAll('button');
    click(sections[0]);
    const tags = drawer.querySelector('[data-filter-field="tagsExact"]');
    tags.value = 'cat';
    click(sections[1]);
    click(sections[0]);
    assert.equal(drawer.querySelector('[data-filter-field="tagsExact"]'), tags);
    assert.equal(tags.value, 'cat');
    assert.equal(mounts, 1, '切换分区不重建贡献控件或其监听器');
    click(drawer.querySelector('.ab-drawer-actions').children[1]);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(drawer.open, false);
    assert.equal(context.evaluateDownloadFilterSkip({tags: ['dog']}, 'illust') !== null, true);
    const queueBadge = document.getElementById('abQueueFilterBtn').querySelector('[data-filter-badge]');
    assert.equal(queueBadge.textContent, '1');
    assert.equal(queueBadge.hidden, false);
    vm.runInContext('dockState.open = false', context);
    click(context.filterButton());
    assert.equal(drawer.querySelector('[data-filter-field="tagsExact"]').value, 'cat');
    assert.equal(drawer.querySelector('.ab-settings').querySelector('input').value, '7');
    const card = document.createElement('div');
    card.id = 'novel-settings-card';
    document.body.appendChild(card);
    context.refreshContributedSettingsVisibility();
    assert.equal(card.style.display, 'none');
    vm.runInContext('dockState.open = true', context);
    context.refreshContributedSettingsVisibility();
    assert.equal(card.style.display, '', '下载列表可设置所有活动作品类型，不受上次获取方式限制');
});

test('设置抽屉仅保留贡献锚点，缺席小说与 AI 时不伪造设置', () => {
    const h = harness();
    const body = h.context.buildSettingsDrawerBody();
    assert.equal(body.querySelector('#novel-settings-card'), null);
    assert.equal(body.querySelector('#s-novel-auto-translate'), null);
    assert.ok(body.querySelector('template[data-qt-slot="settings-card"]'));
});

test('真实小说片段绑定同一设置模型，重开与换代恢复值且旧监听失效', () => {
    const h = harness();
    const owner = h.mountNovel();
    const format = h.input('s-novel-format');
    assert.equal(format.value, 'txt');
    format.value = 'html';
    format.dispatchEvent({type: 'change'});
    const merge = h.input('s-novel-merge');
    assert.equal(h.input('s-novel-merge-format-row').style.display, 'none');
    merge.checked = true;
    merge.dispatchEvent({type: 'change'});
    assert.equal(h.input('s-novel-merge-format-row').style.display, '');
    assert.equal(h.writes.at(-1).mergeNovelSeries, true);
    assert.equal(h.writes.at(-1).novelFormat, 'html');
    h.dispatchSlots();
    const count = h.writes.length;
    format.dispatchEvent({type: 'change'});
    assert.equal(h.writes.length, count + 1, '重复挂载不叠加监听');
    owner.revoke();
    format.value = 'epub';
    format.dispatchEvent({type: 'change'});
    assert.equal(h.writes.length, count + 1);
    h.mountNovel();
    assert.equal(h.input('s-novel-format').value, 'html');
    vm.runInContext("state.mode = 'search'; searchState.kind = 'illust'", h.context);
    h.dispatchSlots();
    assert.equal(h.input('novel-settings-card').style.display, 'none');
    vm.runInContext("searchState.kind = 'novel'", h.context);
    h.dispatchSlots();
    assert.equal(h.input('novel-settings-card').style.display, '');
});

test('AI 贡献自行挂载并持久化，仅管理员可改，撤回不清空偏好或保留旧处理器', () => {
    const h = harness();
    h.mountNovel();
    assert.equal(h.input('s-novel-auto-translate'), null);
    const owner = h.mountAi();
    const toggle = h.input('s-novel-auto-translate');
    toggle.checked = true;
    toggle.dispatchEvent({type: 'change'});
    assert.equal(h.input('s-novel-translate-lang-row').style.display, '');
    const lang = h.input('s-novel-translate-lang');
    lang.value = 'english';
    lang.dispatchEvent({type: 'change'});
    assert.equal(h.writes.at(-1).novelTranslateLang, '');
    lang.value = 'custom-language';
    lang.dispatchEvent({type: 'input'});
    assert.equal(h.writes.at(-1).novelTranslateLang, 'custom-language');
    const count = h.writes.length;
    owner.revoke();
    assert.equal(h.input('s-novel-auto-translate'), null);
    toggle.checked = false;
    toggle.dispatchEvent({type: 'change'});
    lang.value = 'stale-language';
    lang.dispatchEvent({type: 'input'});
    assert.equal(h.writes.length, count);
    vm.runInContext('isAdmin = false', h.context);
    const replacement = h.mountAi();
    assert.equal(h.input('s-novel-auto-translate-row').style.display, 'none');
    assert.equal(h.input('s-novel-auto-translate').checked, true);
    assert.equal(h.input('s-novel-translate-lang').value, 'custom-language');
    h.input('s-novel-auto-translate').dispatchEvent({type: 'change'});
    assert.equal(h.writes.length, count);
    replacement.revoke();
});
