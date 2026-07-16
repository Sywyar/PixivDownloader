'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const toolboxPath = path.resolve(
    __dirname, '../../../../Pixiv 体验增强工具箱(Toolbox).user.js');
const toolboxSource = fs.readFileSync(toolboxPath, 'utf8');

function loadDownloadedBorderFeature(environment = {}) {
    const marker = toolboxSource.indexOf('功能：已下载作品加边框');
    const start = toolboxSource.indexOf('FeatureRegistry.register({', marker);
    const end = toolboxSource.indexOf('/* ========== 功能：一键获取并保存 Cookie', start);
    assert.ok(marker >= 0 && start >= 0 && end > start, '应能定位已下载边框功能源码');

    let feature;
    vm.runInNewContext(toolboxSource.slice(start, end), {
        ...environment,
        FeatureRegistry: { register(definition) { feature = definition; } },
        console,
        t: (_key, fallback) => fallback,
        setTimeout,
        clearTimeout,
        setInterval,
        clearInterval
    });
    assert.ok(feature, '应注册已下载边框功能');
    return feature;
}

function apiWith(settings, gmRequest) {
    return {
        serverBase: 'http://localhost:6999',
        getSetting: key => settings[key],
        gmRequest,
        handleUnauthorized() {}
    };
}

class FakeElement {
    constructor(tagName) {
        this.tagName = tagName;
        this.style = {};
        this.children = [];
        this.listeners = new Map();
        this.dataset = {};
        this.value = '';
        this.checked = false;
        this.disabled = false;
    }

    get firstChild() {
        return this.children[0] || null;
    }

    appendChild(child) {
        this.children.push(child);
        return child;
    }

    insertBefore(child, before) {
        const index = before == null ? -1 : this.children.indexOf(before);
        if (index < 0) this.children.push(child);
        else this.children.splice(index, 0, child);
        return child;
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    emit(type) {
        for (const listener of this.listeners.get(type) || []) listener({ target: this });
    }
}

function loadPanelClass(featureRegistry) {
    const start = toolboxSource.indexOf('class Panel {');
    const endMarker = toolboxSource.indexOf('/* ========== 一键导入 Cookie', start);
    assert.ok(start >= 0 && endMarker > start, '应能定位设置面板类');
    const document = {
        activeElement: null,
        createElement: tag => new FakeElement(tag)
    };
    const createElement = (tag, props = {}, children = []) => {
        const element = new FakeElement(tag);
        for (const [key, value] of Object.entries(props)) {
            if (key === 'style') Object.assign(element.style, value);
            else element[key] = value;
        }
        for (const child of Array.isArray(children) ? children : [children]) {
            if (child && typeof child !== 'string') element.appendChild(child);
        }
        return element;
    };
    const context = {
        document,
        FeatureRegistry: featureRegistry,
        $el: createElement,
        t: (_key, fallback) => fallback,
        BRAND: '#6f42c1'
    };
    vm.runInNewContext(
        toolboxSource.slice(start, endMarker) + '\nthis.Panel = Panel;',
        context);
    return { Panel: context.Panel, document };
}

test('已删除边框默认继承基础样式，并可逐项覆盖', () => {
    const feature = loadDownloadedBorderFeature();
    const settings = {
        width: 5,
        color: '#123456',
        style: 'double',
        showDeleted: false,
        deletedWidth: null,
        deletedColor: null,
        deletedStyle: null
    };
    const api = apiWith(settings, async () => { throw new Error('unused'); });

    assert.match(feature._frameCss(api, 'deleted'), /border:5px double #123456/);
    settings.deletedWidth = 2;
    settings.deletedColor = '#abcdef';
    settings.deletedStyle = 'dashed';
    assert.match(feature._frameCss(api, 'deleted'), /border:2px dashed #abcdef/);

    const showDeleted = feature.settingDefs.find(def => def.key === 'showDeleted');
    assert.equal(showDeleted.type, 'checkbox');
    for (const key of ['deletedWidth', 'deletedColor', 'deletedStyle']) {
        const definition = feature.settingDefs.find(def => def.key === key);
        assert.deepEqual(
            { ...definition.visibleWhen },
            { key: 'showDeleted', equals: true });
        assert.ok(definition.inheritFrom);
    }
});

test('批量响应只保存有效和软删除正命中，不保存未命中的 false', async () => {
    const feature = loadDownloadedBorderFeature();
    feature._collectTargets = () => new Map();
    feature._applyMarks = () => {};
    let requestBody;
    const api = apiWith({}, async request => {
        requestBody = JSON.parse(request.data);
        return {
            status: 200,
            responseText: JSON.stringify({
                artworks: [{ artworkId: 1 }],
                deletedArtworkIds: [2]
            })
        };
    });

    await feature._queryBatch(api, ['a:1', 'a:2', 'a:3'], 'a');

    assert.deepEqual({ ...requestBody }, { artworkIds: [1, 2, 3], includeDeleted: true });
    assert.equal(feature._downloaded.has('a:1'), true);
    assert.equal(feature._deleted.has('a:2'), true);
    assert.equal(feature._downloaded.has('a:3'), false);
    assert.equal(feature._deleted.has('a:3'), false);
    assert.equal(Object.hasOwn(feature, '_cache'), false);
    assert.equal(feature._lastQueryAt.has('a:3'), true, '未命中只应留下可重试的节流时间');
});

test('小说批量响应使用独立前缀缓存有效与软删除正命中', async () => {
    const feature = loadDownloadedBorderFeature();
    feature._collectTargets = () => new Map();
    feature._applyMarks = () => {};
    const api = apiWith({}, async request => {
        assert.deepEqual(
            { ...JSON.parse(request.data) },
            { novelIds: [9, 10, 11], includeDeleted: true });
        return {
            status: 200,
            responseText: '{"novelIds":[9],"deletedNovelIds":[10]}'
        };
    });

    await feature._queryBatch(api, ['n:9', 'n:10', 'n:11'], 'n');

    assert.equal(feature._downloaded.has('n:9'), true);
    assert.equal(feature._deleted.has('n:10'), true);
    assert.equal(feature._downloaded.has('n:11'), false);
    assert.equal(feature._deleted.has('n:11'), false);
});

test('未命中在冷却期后重新查询，正命中不重复查询', () => {
    const feature = loadDownloadedBorderFeature();
    const api = apiWith({ showDeleted: true }, async () => { throw new Error('unused'); });
    feature._cacheBase = api.serverBase;
    feature._downloaded.add('a:1');
    feature._deleted.add('a:2');
    feature._lastQueryAt.set('a:3', Date.now());
    feature._collectTargets = () => new Map([
        ['a:1', []],
        ['a:2', []],
        ['a:3', []]
    ]);
    feature._applyMarks = () => {};
    const calls = [];
    feature._queryBatch = (_api, keys, kind) => {
        if (keys.length) calls.push({ keys: [...keys], kind });
    };

    feature._scan(api);
    assert.deepEqual(calls, []);

    feature._lastQueryAt.set('a:3', Date.now() - feature.NEGATIVE_RETRY_MS - 1);
    feature._scan(api);
    assert.deepEqual(calls, [{ keys: ['a:3'], kind: 'a' }]);
});

test('同一作品类型的三千项查询按 200 分块串行执行', async () => {
    const feature = loadDownloadedBorderFeature();
    feature._collectTargets = () => new Map();
    feature._applyMarks = () => {};
    let active = 0;
    let maxActive = 0;
    let requests = 0;
    const chunkSizes = [];
    const api = apiWith({}, async request => {
        requests += 1;
        chunkSizes.push(JSON.parse(request.data).artworkIds.length);
        active += 1;
        maxActive = Math.max(maxActive, active);
        await new Promise(resolve => setTimeout(resolve, 1));
        active -= 1;
        return { status: 200, responseText: '{"artworks":[],"deletedArtworkIds":[]}' };
    });
    const keys = Array.from({ length: 3001 }, (_unused, index) => 'a:' + (index + 1));

    await Promise.all([
        feature._queryBatch(api, keys, 'a'),
        feature._queryBatch(api, ['a:4000'], 'a')
    ]);

    assert.equal(requests, 17);
    assert.equal(maxActive, 1);
    assert.equal(Math.max(...chunkSizes), 200);
    assert.equal(chunkSizes.reduce((sum, size) => sum + size, 0), 3002);
});

test('401 会终止整条批量链并为未知项退避，避免重复打开登录页', async () => {
    const feature = loadDownloadedBorderFeature();
    feature._collectTargets = () => new Map();
    feature._applyMarks = () => {};
    let requests = 0;
    let unauthorized = 0;
    const api = apiWith({}, async () => {
        requests += 1;
        return { status: 401, responseText: '' };
    });
    api.handleUnauthorized = () => { unauthorized += 1; };
    const keys = Array.from({ length: 3001 }, (_unused, index) => 'a:' + (index + 1));

    await feature._queryBatch(api, keys, 'a');

    assert.equal(requests, 1);
    assert.equal(unauthorized, 1);
    assert.equal(feature._lastQueryAt.size, 3001);
    assert.equal(feature._downloaded.size, 0);
    assert.equal(feature._deleted.size, 0);
});

test('跨运行代次的同类请求仍等待旧在途请求退出', async () => {
    const feature = loadDownloadedBorderFeature();
    feature._collectTargets = () => new Map();
    feature._applyMarks = () => {};
    let releaseFirst;
    let active = 0;
    let maxActive = 0;
    let requests = 0;
    const api = apiWith({}, async () => {
        requests += 1;
        active += 1;
        maxActive = Math.max(maxActive, active);
        if (requests === 1) {
            await new Promise(resolve => { releaseFirst = resolve; });
        }
        active -= 1;
        return { status: 200, responseText: '{"artworks":[],"deletedArtworkIds":[]}' };
    });

    const first = feature._queryBatch(api, ['a:1'], 'a');
    await new Promise(resolve => setTimeout(resolve, 0));
    feature._generation += 1;
    const second = feature._queryBatch(api, ['a:2'], 'a');
    await new Promise(resolve => setTimeout(resolve, 0));

    assert.equal(requests, 1, '新代次必须等待旧代次的在途网络请求');
    releaseFirst();
    await Promise.all([first, second]);
    assert.equal(requests, 2);
    assert.equal(maxActive, 1);
});

test('旧代次网络失败不会把退避状态写入新代次', async () => {
    const feature = loadDownloadedBorderFeature();
    feature._collectTargets = () => new Map();
    feature._applyMarks = () => {};
    let rejectFirst;
    let requests = 0;
    const api = apiWith({}, async () => {
        requests += 1;
        if (requests === 1) {
            return new Promise((_resolve, reject) => { rejectFirst = reject; });
        }
        return { status: 200, responseText: '{"artworks":[],"deletedArtworkIds":[]}' };
    });

    const first = feature._queryBatch(api, ['a:1'], 'a');
    await new Promise(resolve => setTimeout(resolve, 0));
    feature._generation += 1;
    feature._lastQueryAt.clear();
    const second = feature._queryBatch(api, ['a:2'], 'a');
    rejectFirst(new Error('old generation failed'));
    await Promise.all([first, second]);

    assert.equal(feature._lastQueryAt.has('a:1'), false);
    assert.equal(feature._lastQueryAt.has('a:2'), true);
});

test('虚拟列表复用容器时会重建新作品边框的定位上下文', () => {
    const classes = new Set(['pixiv-enh-host']);
    const host = {
        style: { position: 'relative' },
        dataset: { pixivEnhPos: '1' },
        children: [],
        classList: {
            add(name) { classes.add(name); },
            remove(name) { classes.delete(name); },
            contains(name) { return classes.has(name); }
        },
        appendChild(child) {
            this.children.push(child);
            child.parentElement = this;
            return child;
        },
        querySelector() {
            return this.children.find(child => child.className === 'pixiv-enh-frame') || null;
        }
    };
    const createFrame = key => ({
        className: 'pixiv-enh-frame',
        dataset: key ? { pixivEnhKey: key, pixivEnhState: 'downloaded' } : {},
        style: {},
        parentElement: null,
        remove() {
            if (!this.parentElement) return;
            const parent = this.parentElement;
            parent.children = parent.children.filter(child => child !== this);
            this.parentElement = null;
        }
    });
    const staleFrame = createFrame('a:1');
    host.appendChild(staleFrame);
    const document = {
        querySelectorAll: selector => selector === '.pixiv-enh-frame' ? [...host.children] : [],
        createElement: () => createFrame(null)
    };
    const feature = loadDownloadedBorderFeature({
        document,
        getComputedStyle: element => ({ position: element.style.position || 'static' })
    });
    feature._pickThumb = () => host;
    feature._downloaded.add('a:2');
    const api = apiWith({
        showDeleted: false,
        width: 3,
        color: '#00aaff',
        style: 'solid'
    }, async () => { throw new Error('unused'); });

    feature._applyMarks(new Map([['a:2', [{}]]]), api);

    assert.equal(host.children.length, 1);
    const currentFrame = host.children[0];
    assert.equal(currentFrame.dataset.pixivEnhKey, 'a:2');
    assert.equal(host.style.position, 'relative');
    assert.equal(host.dataset.pixivEnhPos, '1');
    assert.equal(host.classList.contains(feature.HOST_CLASS), true);

    feature._removeFrame(currentFrame);
    assert.equal(host.style.position, '');
    assert.equal(host.dataset.pixivEnhPos, undefined);
    assert.equal(host.classList.contains(feature.HOST_CLASS), false);
});

test('设置面板声明式支持复选框、条件显示和继承控制', () => {
    assert.match(toolboxSource, /sd\.type === 'checkbox'/);
    assert.match(toolboxSource, /sd\.visibleWhen/);
    assert.match(toolboxSource, /sd\.inheritFrom/);
    assert.match(toolboxSource, /downloaded-border\.setting\.show-deleted': '为删除的作品显示边框'/);
});

test('设置面板可实际切换已删除选项、显隐独立样式并恢复继承', () => {
    const feature = {
        settings: { showDeleted: false, width: 3, deletedWidth: null },
        def: {},
        running: false
    };
    const registry = {
        setSetting(target, key, value) { target.settings[key] = value; }
    };
    const { Panel } = loadPanelClass(registry);
    const panel = Object.create(Panel.prototype);
    const card = { _settingRows: [] };
    panel._featureCards = [{ feature, card }];
    const showRow = panel._buildSettingRow(feature, {
        key: 'showDeleted', type: 'checkbox', labelKey: 'show'
    });
    const deletedWidthRow = panel._buildSettingRow(feature, {
        key: 'deletedWidth', type: 'number', labelKey: 'width', min: 1, max: 12,
        visibleWhen: { key: 'showDeleted', equals: true }, inheritFrom: 'width'
    });
    card._settingRows.push(showRow, deletedWidthRow);

    const showInput = showRow.children[0];
    const widthInput = deletedWidthRow.children[1];
    const inheritInput = deletedWidthRow.children[2].children[0];
    assert.equal(deletedWidthRow.style.display, 'none');
    assert.equal(widthInput.disabled, true);
    assert.equal(widthInput.value, 3);
    assert.equal(inheritInput.checked, true);

    showInput.checked = true;
    showInput.emit('change');
    assert.equal(feature.settings.showDeleted, true);
    assert.equal(deletedWidthRow.style.display, 'flex');

    inheritInput.checked = false;
    inheritInput.emit('change');
    assert.equal(feature.settings.deletedWidth, 3);
    assert.equal(widthInput.disabled, false);

    widthInput.value = '6';
    widthInput.emit('input');
    assert.equal(feature.settings.deletedWidth, 6);

    inheritInput.checked = true;
    inheritInput.emit('change');
    assert.equal(feature.settings.deletedWidth, null);
    assert.equal(widthInput.disabled, true);
    assert.equal(widthInput.value, 3);
});
