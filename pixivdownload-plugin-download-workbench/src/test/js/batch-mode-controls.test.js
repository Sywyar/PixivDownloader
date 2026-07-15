'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const SOURCE = fs.readFileSync(path.join(STATIC_ROOT, 'pixiv-batch', 'batch-mode-controls.js'), 'utf8');
const HTML = fs.readFileSync(path.join(STATIC_ROOT, 'pixiv-batch.html'), 'utf8');
const SOURCE_CONTROL_MODES = ['user', 'search'];

class ClassList {
    constructor() {
        this.values = new Set();
    }

    toggle(name, force) {
        const enabled = force === undefined ? !this.values.has(name) : !!force;
        if (enabled) this.values.add(name);
        else this.values.delete(name);
        return enabled;
    }
}

class El {
    constructor(tag, id = '') {
        this.tag = String(tag).toLowerCase();
        this.id = id;
        this.dataset = {};
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        this.classList = new ClassList();
        this.style = {};
        this.hidden = false;
        this.type = '';
        this.name = '';
        this.value = '';
        this.checked = false;
        this._textContent = '';
    }

    get textContent() {
        if (this.tag === '#text') return this._textContent;
        return this._textContent + this.children.map(child => child.textContent).join('');
    }

    set textContent(value) {
        this._textContent = String(value == null ? '' : value);
        if (this.tag !== '#text') this.replaceChildren();
    }

    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        return child;
    }

    replaceChildren(...children) {
        this.children.forEach(child => { child.parentNode = null; });
        this.children = [];
        children.forEach(child => this.appendChild(child));
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name)
            ? this.attributes[name] : null;
    }
}

function descendants(root, predicate) {
    const found = [];
    const visit = node => {
        if (predicate(node)) found.push(node);
        node.children.forEach(visit);
    };
    root.children.forEach(visit);
    return found;
}

function type(typeId) {
    return Object.freeze({type: typeId});
}

function source(id, order, types) {
    return Object.freeze({
        id,
        displayNamespace: 'sources',
        displayI18nKey: `source.${id}`,
        order,
        types: Object.freeze(types.map(type))
    });
}

function createHarness() {
    const byId = new Map();
    const listeners = new Map();
    const register = (tag, id) => {
        const element = new El(tag, id);
        byId.set(id, element);
        return element;
    };
    ['quick', 'user', 'search', 'series'].forEach(mode => {
        register('div', `${mode}-data-source-control`);
        register('div', `${mode}-data-source-switcher`);
    });
    register('span', 'single-import-data-sources');

    const document = {
        getElementById(id) {
            return byId.get(id) || null;
        },
        createElement(tag) {
            return new El(tag);
        },
        createTextNode(value) {
            const node = new El('#text');
            node.textContent = value;
            return node;
        },
        addEventListener(event, listener) {
            if (!listeners.has(event)) listeners.set(event, new Set());
            listeners.get(event).add(listener);
        }
    };

    const snapshots = {
        user: [
            source('source-a', 10, ['owner-a', 'owner-b']),
            source('source-b', 20, ['owner-c'])
        ],
        search: [],
        'single-import': [
            source('source-a', 10, ['owner-a', 'owner-b']),
            source('source-b', 20, ['owner-c'])
        ]
    };
    const queueTypes = {
        dataSourcesForMode(mode) {
            return Object.freeze((snapshots[mode] || []).slice());
        }
    };
    const sandbox = {
        window: {PixivBatch: {queueTypes}},
        document,
        pageI18n: {apply() {}},
        uiLang() { return 'zh-CN'; },
        console: {warn() {}, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);
    return {
        controls: sandbox.window.PixivBatch.modeControls,
        snapshots,
        element(id) { return byId.get(id); },
        listenerCount(event) { return (listeners.get(event) || new Set()).size; },
        dispatch(event, target) {
            Array.from(listeners.get(event) || []).forEach(listener => listener({type: event, target}));
        }
    };
}

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

(function () {
    ['quick', 'user', 'search', 'series'].forEach(mode => {
        ok(`${mode} 模式静态页面提供数据来源切换器`,
            HTML.includes(`id="${mode}-data-source-switcher"`));
        ok(`${mode} 模式不新增顶部通用作品类型控件`,
            !HTML.includes(`id="${mode}-work-type-control"`)
            && !HTML.includes(`id="${mode}-work-type-switcher"`));
    });
    ok('User 与 Search 保留既有 kind switcher 及插件槽位',
        HTML.includes('id="user-kind-switcher"')
        && HTML.includes('data-qt-slot="kind-option-user"')
        && HTML.includes('id="search-kind-switcher"')
        && HTML.includes('data-qt-slot="kind-option-search"'));
    ok('Quick 与 Series 继续保留各自既有来源控件结构',
        HTML.includes('class="quick-data-source-control data-source-control"')
        && HTML.includes('class="series-data-source-control data-source-control"'));
    ok('single-import 静态页面只提供支持来源的只读展示锚点',
        HTML.includes('id="single-import-data-sources"')
        && !HTML.includes('id="single-import-data-source-switcher"'));
    const queueTypesIndex = HTML.indexOf('/pixiv-batch/batch-queue-types.js');
    const modeControlsIndex = HTML.indexOf('/pixiv-batch/batch-mode-controls.js');
    const initIndex = HTML.indexOf('/pixiv-batch/batch-init.js');
    ok('来源控件模块在 queueTypes 之后且页面初始化之前加载',
        queueTypesIndex >= 0 && modeControlsIndex > queueTypesIndex && initIndex > modeControlsIndex);

    const h = createHarness();
    const controls = h.controls;
    [
        'bind', 'render', 'renderAll', 'selectSource', 'selectType', 'syncType', 'selection',
        'sources', 'onChange', 'renderSupportedImportSources'
    ].forEach(method => ok(`modeControls 暴露 ${method} 公共方法`, typeof controls[method] === 'function'));

    const quickSentinel = new El('span');
    const seriesSentinel = new El('span');
    h.element('quick-data-source-switcher').appendChild(quickSentinel);
    h.element('series-data-source-switcher').appendChild(seriesSentinel);
    controls.renderAll();
    ok('共享来源控件不接管 Quick 与 Series 自有来源 DOM',
        h.element('quick-data-source-switcher').children[0] === quickSentinel
        && h.element('series-data-source-switcher').children[0] === seriesSentinel);

    SOURCE_CONTROL_MODES.forEach(mode => {
        const control = h.element(`${mode}-data-source-control`);
        const switcher = h.element(`${mode}-data-source-switcher`);
        ok(`${mode} 即使当前无可用来源也始终渲染数据来源控件`,
            control.hidden === false && control.style.display === ''
            && control.dataset.acquisitionSourceMode === mode
            && switcher.dataset.acquisitionSourceMode === mode);
    });
    ok('无来源模式保留可见的数据来源控件并标记为不可选择',
        h.element('search-data-source-switcher').children.length === 0
        && h.element('search-data-source-switcher').getAttribute('aria-disabled') === 'true');
    ok('User 来源选择快照只保存来源与旧 kind 对应 owner',
        Object.keys(controls.selection('user')).join(',') === 'sourceId,type'
        && controls.selection('user').sourceId === 'source-a'
        && controls.selection('user').type === 'owner-a');
    ok('内部 owner 同步只更新选择状态，不渲染顶部类型控件',
        controls.selectType('user', 'owner-b') === true
        && controls.selection('user').sourceId === 'source-a'
        && controls.selection('user').type === 'owner-b'
        && controls.selectType('user', 'owner-c') === false
        && controls.syncType('user', 'owner-c') === true
        && controls.selection('user').sourceId === 'source-b'
        && controls.selection('user').type === 'owner-c');

    controls.bind();
    controls.bind();
    h.dispatch('change', {
        dataset: {acquisitionSourceMode: 'user'}, value: 'source-a'
    });
    ok('来源控件使用幂等的稳定 document change 委托处理热重载后的选项',
        h.listenerCount('change') === 1
        && controls.selection('user').sourceId === 'source-a'
        && controls.selection('user').type === 'owner-a');

    const events = [];
    const unsubscribe = controls.onChange('user', event => events.push(event));
    ok('显式切换来源会通知订阅者',
        controls.selectSource('user', 'source-b') === true
        && events.length === 1 && events[0].reason === 'source');
    unsubscribe();
    controls.selectSource('user', 'source-a');
    ok('onChange 返回的取消函数会停止后续通知', events.length === 1);

    controls.selectSource('user', 'source-b');

    h.snapshots.user = [];
    const loading = controls.render('user', true);
    ok('queue type loading 空快照保留期望来源与旧来源选项',
        loading.preserved === true
        && controls.selection('user').sourceId === 'source-b'
        && descendants(h.element('user-data-source-switcher'), node => node.tag === 'input')
            .map(input => input.value).join(',') === 'source-a,source-b');
    h.snapshots.user = [source('source-a', 10, ['owner-a', 'owner-b'])];
    const withdrawal = controls.render('user', true);
    ok('插件热撤回当前来源后回退到仍可用来源',
        withdrawal.sourceChanged === true
        && controls.selection('user').sourceId === 'source-a');

    const importSources = controls.renderSupportedImportSources();
    const importRoot = h.element('single-import-data-sources');
    ok('single-import 只读列出所有插件贡献的支持来源',
        importSources.map(item => item.id).join(',') === 'source-a,source-b'
        && descendants(importRoot, node => node.tag === 'span').length === 2
        && importRoot.textContent.includes('source-a') && importRoot.textContent.includes('source-b'));
    ok('single-import 支持来源区域不渲染任何可选择控件',
        descendants(importRoot, node => node.tag === 'input' || node.tag === 'button').length === 0
        && importRoot.getAttribute('role') !== 'radiogroup');
    ok('single-import 不接受来源选择且不产生选择状态',
        controls.selectSource('single-import', 'source-b') === false
        && controls.selection('single-import').sourceId === null
        && controls.selection('single-import').type === null);

    console.log(`batch-mode-controls.test.js: ${passed} assertions passed ✓`);
})();
