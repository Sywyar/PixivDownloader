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
    const saved = new Map();
    let catalog = {revision: '0', profiles: []};
    const context = vm.createContext({
        document, console, AbortController, AbortSignal, crypto: require('node:crypto').webcrypto,
        Event: class { constructor(type) { this.type = type; } },
        fetch: async (_url, options) => {
            if (options.method === 'PUT') catalog = JSON.parse(options.body);
            return {ok: true, json: async () => catalog};
        },
        cookieHasPhpsessid: () => true,
        altAcquisition: (_mode, _source, type) => ({type}),
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
    load('pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch/filename-template-presets.js');
    ['../pixiv-batch/queue-display.js', 'alt-core.js', '../pixiv-batch/batch-download-defaults.js', '../pixiv-batch/batch-pagination.js', '../pixiv-batch/state-storage.js', 'alt-state.js', '../pixiv-batch/filename-template.js', 'alt-settings.js', '../pixiv-batch/download-profiles.js', 'alt-profiles.js'].forEach(name => load(alt + name));
    context.PixivBatch.queueTypes = context.altQueueTypes();
    context.storeSet = (key, value) => {
        saved.set(key, value);
        if (key !== 'pixiv_download_profile_id') writes.push(JSON.parse(value));
    };
    context.storeGet = key => saved.get(key);
    context.flushServerState = async () => {};
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
        context.PixivBatch.queueTypes = {registerSubmodule: initializer => initializer(shared),
            contributionsOf: () => [{type: 'illust', extraSelector: '.search-illust-only'},
                {type: 'novel', extraSelector: '.search-novel-only'}]};
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
        load('pixivdownload-plugin-translation/src/main/resources/static/pixiv-translation/download-novel-translate-settings-slot.js');
        return owner;
    };
    return {context, document, events, writes, saved, catalog: () => catalog, load, dispatchSlots, mountNovel, mountAi,
        input: id => document.getElementById(id)};
}

test('共享变量生成的按钮插入可执行模板并保存，不显示导入专用变量', () => {
    const h = harness();
    const body = h.context.buildSettingsDrawerBody();
    h.document.body.appendChild(body);
    const chips = body.querySelectorAll('.ab-var-chip');
    const tokens = chips.map(chip => chip.title);
    assert.ok(tokens.includes('{artwork_id}') && tokens.includes('{ai+}') && tokens.includes('{R18+}'));
    assert.ok(tokens.includes('{collection_name}') && tokens.includes('{collection_id}'));
    for (const token of ['series_id', 'series_name', 'series_title', 'series_order', 'username']) {
        assert.ok(tokens.includes('{' + token + '}'));
    }
    assert.ok(!tokens.includes('{extension}') && !tokens.includes('{name}'));
    const input = body.querySelector('.filename-template-controls').querySelector('input');
    input.value = 'selected_';
    chips.find(chip => chip.title === '{artwork_id}').dispatchEvent({type: 'click'});
    assert.equal(input.value, 'selected_{artwork_id}');
    assert.equal(h.writes.at(-1).fileNameTemplate, 'selected_{artwork_id}');
    assert.equal(h.context.formatFileNameBase(input.value, {artworkId: '42'}, 0, 1), 'selected_42');
});

test('更换或清空收藏夹立即刷新文件名预览，不把收藏夹写入保存的设置', () => {
    const h = harness();
    vm.runInContext("batchCollections = [{id: 7, name: '收藏/one'}, {id: 8, name: 'Two'}]; state.settings.collectionId = 7; state.settings.fileNameTemplate = '{collection_name}_{collection_id}_{artwork_id}'", h.context);
    const body = h.context.buildSettingsDrawerBody();
    h.document.body.appendChild(body);
    const preview = body.querySelector('.ab-name-preview');
    const select = body.querySelectorAll('select').find(node => node.children.some(option => option.value === '7'));
    assert.match(preview.textContent, /收藏_one_7_12345678/);
    select.value = '8';
    select.dispatchEvent({type: 'change'});
    assert.match(preview.textContent, /Two_8_12345678/);
    select.value = '';
    select.dispatchEvent({type: 'change'});
    assert.match(preview.textContent, /__12345678/);
    assert.ok(h.writes.every(value => !Object.hasOwn(value, 'collectionId')));
});

test('系列与来源变量在文件名设置中预览实际示例值', () => {
    const h = harness();
    vm.runInContext("state.settings.fileNameTemplate = '{series_id}_{series_name}_{series_title}_{series_order}_{username}'", h.context);
    const body = h.context.buildSettingsDrawerBody();
    const preview = body.querySelector('.ab-name-preview').textContent;
    assert.match(preview, /24680_.+_.+_1_.+/);
    assert.doesNotMatch(preview, /\{(?:series_id|series_name|series_title|series_order|username)\}/);
});

test('统一设置保留分区输入，筛选即时生效且与下载参数一起保存和恢复方案', async () => {
    const h = harness();
    const {context, document} = h;
    const alt = 'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch-alt/';
    for (const name of ['../pixiv-batch/search-filters.js', 'alt-filters.js', 'alt-modes.js', 'alt-mode-discovery.js', 'alt-chrome.js']) h.load(alt + name);
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
    assert.equal(drawer.querySelector('.ab-settings').hidden, true);
    assert.equal(drawer.querySelector('.ab-filters').hidden, false);
    assert.equal(document.getElementById('abQueueFilterBtn'), null);
    const profiles = drawer.querySelector('.download-profiles');
    assert.ok(profiles && !profiles.closest('.ab-settings') && !profiles.closest('.ab-filters'));
    const sections = drawer.querySelector('.ab-download-sections').querySelectorAll('button');
    click(sections[1]);
    const interval = drawer.querySelector('.ab-settings').querySelectorAll('input').find(input => input.type === 'number');
    interval.value = '7';
    interval.dispatchEvent({type: 'change'});
    assert.equal(h.writes.at(-1).interval, 7);
    click(sections[0]);
    const tags = drawer.querySelector('[data-filter-field="tagsExact"]');
    tags.value = 'cat';
    tags.dispatchEvent({type: 'change'});
    assert.equal(h.writes.at(-1).tagsExact[0], 'cat');
    assert.equal(drawer.open, true, '修改筛选不关闭设置');
    click(sections[1]);
    click(sections[0]);
    assert.equal(drawer.querySelector('[data-filter-field="tagsExact"]'), tags);
    assert.equal(tags.value, 'cat');
    assert.equal(mounts, 1, '切换分区不重建贡献控件或其监听器');
    context.abPrompt = async () => 'Daily';
    context.abConfirm = async () => true;
    await new Promise(resolve => setImmediate(resolve));
    click(profiles.querySelector('[data-i18n="batch:profiles.new"]'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.catalog().profiles[0].filters.tagsExact[0], 'cat');
    assert.equal(h.catalog().profiles[0].settings.interval, 7);
    tags.value = 'dog';
    tags.dispatchEvent({type: 'change'});
    assert.equal(profiles.querySelector('.download-profiles-modified').hidden, false);
    click(profiles.querySelector('[data-i18n="batch:profiles.save"]'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.catalog().profiles[0].filters.tagsExact[0], 'dog');
    assert.equal(profiles.querySelector('.download-profiles-modified').hidden, true);
    click(drawer.querySelector('.ab-filter-heading').querySelector('.ab-btn'));
    assert.equal(tags.value, '');
    assert.equal(context.activeFilterCount(), 0);
    const select = profiles.querySelector('select');
    select.value = h.catalog().profiles[0].id;
    select.focus();
    select.dispatchEvent({type: 'change'});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(drawer.querySelector('[data-filter-field="tagsExact"]').value, 'dog');
    assert.equal(drawer.querySelector('.ab-filters').hidden, false, '应用方案保留当前分区');
    assert.equal(drawer.querySelector('.download-profiles'), profiles, '应用方案不重建方案入口');
    assert.equal(document.activeElement, select);
    click(drawer.querySelector('.ab-drawer-actions').children[0]);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(drawer.open, false);
    assert.equal(context.evaluateDownloadFilterSkip({tags: ['cat']}, 'illust') !== null, true);
    const queueBadge = document.getElementById('abQueueSettingsBtn').querySelector('[data-filter-badge]');
    assert.equal(queueBadge.textContent, '1');
    assert.equal(queueBadge.hidden, false);
    vm.runInContext('dockState.open = false', context);
    click(context.settingsButton());
    assert.equal(drawer.querySelector('[data-filter-field="tagsExact"]').value, 'dog');
    assert.equal(drawer.querySelector('.ab-settings').querySelectorAll('input').find(input => input.type === 'number').value, '7');
    const card = document.createElement('div');
    card.id = 'novel-settings-card';
    document.body.appendChild(card);
    context.refreshContributedSettingsVisibility();
    assert.equal(card.style.display, 'none');
    vm.runInContext('dockState.open = true', context);
    context.refreshContributedSettingsVisibility();
    assert.equal(card.style.display, '', '下载列表可设置所有活动作品类型，不受上次获取方式限制');
});

test('计划草稿即时修改和重置筛选不改手动持久值或运行队列使用的筛选', () => {
    const h = harness();
    const {context, document} = h;
    const alt = 'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch-alt/';
    for (const name of ['../pixiv-batch/search-filters.js', 'alt-filters.js']) h.load(alt + name);
    context.applyFiltersToCurrentMode = async () => {};
    context.PixivBatch.queueTypes.filtersFor = () => null;
    vm.runInContext(`
        extraFilters.tagsExact = ['manual'];
        saveSearchFilterPrefs(extraFilters);
        scheduleState.editing = {settings: {...state.settings}, original: {filters: extraFilters}};
        extraFilters = defaultSearchFilters();
    `, context);
    const before = h.saved.get('pixiv_search_filters');
    const body = context.buildFiltersDrawerBody();
    document.body.appendChild(body);
    const tags = body.querySelector('[data-filter-field="tagsExact"]');
    tags.value = 'draft';
    tags.dispatchEvent({type: 'change'});
    assert.equal(vm.runInContext('extraFilters.tagsExact[0]', context), 'draft');
    assert.equal(h.saved.get('pixiv_search_filters'), before);
    assert.equal(context.evaluateDownloadFilterSkip({tags: ['manual']}, 'illust'), null);
    assert.notEqual(context.evaluateDownloadFilterSkip({tags: ['draft']}, 'illust'), null);
    body.querySelector('.ab-filter-heading').querySelector('.ab-btn').dispatchEvent({type: 'click'});
    assert.equal(tags.value, '');
    assert.equal(context.activeFilterCount(), 0);
    assert.equal(h.saved.get('pixiv_search_filters'), before);
    assert.notEqual(context.evaluateDownloadFilterSkip({tags: ['draft']}, 'illust'), null);
});

test('文件名预览跟随输入，变量按需展开，插入和键盘收起保留模板与焦点', () => {
    const h = harness();
    const body = h.context.buildSettingsDrawerBody();
    h.document.body.appendChild(body);
    const input = body.querySelector('.filename-template-controls').querySelector('input');
    const preview = body.querySelector('.ab-name-preview');
    const variables = body.querySelector('.ab-template-variables');
    assert.equal(!!variables.open, false);
    const label = body.querySelectorAll('label').find(node => node.htmlFor === input.id);
    assert.ok(label, '模板标签关联可编辑输入框');
    assert.ok(input.getAttribute('aria-describedby').split(' ').includes(preview.id));
    const hint = label.parentElement.querySelector('[role="tooltip"]');
    const helpButton = label.parentElement.querySelector('button');
    assert.equal(hint.getAttribute('popover'), 'manual');
    assert.equal(helpButton.getAttribute('aria-describedby'), hint.id);
    assert.equal(label.contains(helpButton), false, '说明按钮不参与标签激活');
    assert.ok(input.getAttribute('aria-describedby').split(' ').includes(hint.firstChild.id));
    const pathAction = body.querySelector('#s-path-overflow-action');
    assert.ok(h.document.getElementById(pathAction.getAttribute('aria-describedby')));
    const before = h.writes.length;
    input.value = 'draft_{artwork_id}';
    input.dispatchEvent({type: 'input'});
    assert.ok(preview.textContent.includes('draft_12345678'));
    assert.equal(h.writes.length, before, '预览不提前持久化输入');
    variables.open = true;
    variables.querySelectorAll('button').find(node => node.title === '{author_id}').dispatchEvent({type: 'click'});
    assert.equal(h.writes.at(-1).fileNameTemplate, 'draft_{artwork_id}{author_id}');
    assert.equal(h.document.activeElement, input);
    let stopped = false;
    variables.dispatchEvent({type: 'keydown', key: 'Escape', preventDefault() {}, stopPropagation() { stopped = true; }});
    assert.equal(variables.open, false);
    assert.equal(stopped, true);
    assert.equal(h.document.activeElement, variables.querySelector('summary'));
});

test('历史检查子项随开关展开且收起时保留已选参数', () => {
    const h = harness();
    const body = h.context.buildSettingsDrawerBody();
    h.document.body.appendChild(body);
    const section = body.querySelectorAll('.ab-settings-section')[1];
    const dependent = section.querySelector('.ab-settings-dependent');
    const toggle = section.querySelector('input');
    toggle.checked = true;
    toggle.dispatchEvent({type: 'change'});
    assert.equal(dependent.hidden, false);
    const verify = dependent.querySelector('input');
    verify.checked = true;
    verify.dispatchEvent({type: 'change'});
    toggle.checked = false;
    toggle.dispatchEvent({type: 'change'});
    assert.equal(dependent.hidden, true);
    assert.equal(h.writes.at(-1).verifyHistoryFiles, true);
});

test('自动开始下载开关默认关闭，保存后重开恢复，计划编辑和非管理员不显示', () => {
    const h = harness();
    let body = h.context.buildSettingsDrawerBody();
    let input = body.querySelector('#s-auto-start');
    assert.equal(input.checked, false);
    input.checked = true;
    input.dispatchEvent({type: 'change'});
    assert.equal(h.writes.at(-1).autoStartOnEnqueue, true);
    h.context.storeGet = () => JSON.stringify(h.writes.at(-1));
    vm.runInContext('state.settings.autoStartOnEnqueue = false', h.context);
    h.context.loadSettings();
    body = h.context.buildSettingsDrawerBody();
    assert.equal(body.querySelector('#s-auto-start').checked, true);
    vm.runInContext('isAdmin = false', h.context);
    assert.equal(h.context.buildSettingsDrawerBody().querySelector('#s-auto-start'), null);
    vm.runInContext('isAdmin = true; scheduleState.editing = {task: {name: "demo"}, settings: {...state.settings}}', h.context);
    h.context.withScheduleEditSettings = action => action();
    assert.equal(h.context.buildSettingsDrawerBody().querySelector('#s-auto-start'), null);
});

test('设置抽屉仅保留贡献锚点，缺席小说与 AI 时不伪造设置', () => {
    const h = harness();
    const body = h.context.buildSettingsDrawerBody();
    assert.equal(body.querySelector('#novel-settings-card'), null);
    assert.equal(body.querySelector('#s-novel-auto-translate'), null);
    assert.ok(body.querySelector('template[data-qt-slot="settings-card"]'));
});

test('设置抽屉通过当前队列运行时挂载格式选项，缺席时仍可打开', () => {
    const h = harness();
    let mounted;
    h.context.altQueueTypes = () => ({contributionsOf: () => [{mount: root => { mounted = root; }}]});
    const body = h.context.buildSettingsDrawerBody();
    assert.equal(mounted, body.querySelector('#type-output-settings'));
    assert.ok(mounted);
    h.context.altQueueTypes = () => null;
    assert.ok(h.context.buildSettingsDrawerBody());
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
    const listeners = h.events.listenerCount('pixivbatch:slotsrendered');
    const owner = h.mountAi();
    assert.ok(h.input('s-novel-merge-format-hint').closest('[role="tooltip"]'));
    assert.ok(h.input('s-novel-translate-hint').closest('[role="tooltip"]'));
    assert.equal(h.events.listenerCount('pixivbatch:slotsrendered'), listeners + 1);
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
    assert.equal(h.events.listenerCount('pixivbatch:slotsrendered'), listeners);
    h.dispatchSlots();
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
    assert.equal(h.events.listenerCount('pixivbatch:slotsrendered'), listeners);
});
