'use strict';
/*
 * single-import.js 的运行态测试（启用 / 禁用作品类型时的批量导入解析行为）。
 *
 * 无浏览器 / 无 jsdom：在 Node 的 vm 沙箱里加载**真实**的 batch-queue-types.js + single-import.js，
 * 用最小 DOM + 宿主工具函数桩驱动**真实**的 parseSingleImport（不只测 registry），验证：
 *   a) novel 可用 → `novel:` 区段裸 ID + 显式 novel URL 正常构造 novel 队列项并入队。
 *   b) novel 不可用 → `novel:` 区段裸 ID 跳过、计入 unavailable、不入队。
 *   c) novel 不可用 → 显式 novel URL 跳过、计入 unavailable、不入队，状态 key = status.single-import-skipped-unavailable。
 *   d) novel 不可用 → 插画 URL / 裸 ID 仍按旧行为入队。
 *   e) novel 不可用 + 混合输入 → 可用插画照常入队、novel URL 跳过、不破坏旧成功路径。
 *
 * 运行： node src/test/js/single-import.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const QT_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-queue-types.js'), 'utf8');
const SI_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'single-import.js'), 'utf8');
const DOUYIN_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-douyin', 'src', 'main', 'resources', 'static',
    'pixiv-douyin-download', 'douyin-queue-type.js'), 'utf8');

// ---- 最小 DOM（够 batch-queue-types 的 bootstrap/renderSlots + single-import 读 textarea 用）----
function makeDocument(textareaValue) {
    const textarea = {value: textareaValue};
    const noopEl = () => ({
        dataset: {}, setAttribute() {}, getAttribute() {}, appendChild(child) {
            if (typeof this.onAppend === 'function') this.onAppend(child);
        }, remove() {}, addEventListener() {}, removeEventListener() {},
        insertAdjacentHTML() {}, querySelectorAll: () => [], style: {}, children: []
    });
    const head = noopEl();
    return {
        getElementById: id => (id === 'single-import-textarea' ? textarea : null),
        querySelectorAll: () => [],            // 本测试不放 slot 锚点 → renderSlots 无注入目标
        createElement: tag => Object.assign(noopEl(), {tag, src: '', onload: null, onerror: null}),
        currentScript: null,
        head,
        body: noopEl(),
        documentElement: noopEl()
    };
}

// novel 的「批量导入」钩子（镜像 novel-queue-type.js 的 import descriptor 形态：sectionType/matchUrl/buildItem/source）。
function novelImportDescriptor() {
    return {
        pluginId: 'novel', type: 'novel', display: 'd',
        process() {},
        import: {
            sectionType: 'novel',
            matchUrl(line) {
                const m = String(line).match(/https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=(\d+)/);
                return m ? m[1] : null;
            },
            buildItem(id, title) {
                return {id: 'n' + id, novelId: id, kind: 'novel', title: title || ('小说 ' + id)};
            },
            source: 'single-import-novel'
        }
    };
}

function structuredImportDescriptor() {
    return {
        pluginId: 'structured', type: 'structured', display: 'd',
        process() {},
        import: {
            sectionType: 'structured',
            matchUrl(line) {
                const m = String(line).match(/https?:\/\/example\.test\/works\/([A-Za-z0-9_-]+)/);
                return m ? {id: m[1], url: m[0]} : null;
            },
            buildItem(match, title, line) {
                return {
                    id: 's' + match.id,
                    workId: match.id,
                    url: match.url,
                    line,
                    kind: 'structured',
                    title: title || ('结构化 ' + match.id)
                };
            },
            source: 'single-import-structured'
        }
    };
}

// 在沙箱里加载真实 registry + 真实 single-import，按启用类型装配后跑 parseSingleImport。
// 返回捕获的 setStatus / addItemsToQueue 调用，供断言。
async function runParse(textareaValue, {
    novelEnabled = false,
    douyinEnabled = false,
    pixivEnabled = true,
    lowOrderEnabled = false,
    ambiguousUrlEnabled = false,
    secondBareDefaultEnabled = false
}) {
    const enqueued = [];   // [{ids, items, source}]
    let status = null;     // {message, level}
    const document = makeDocument(textareaValue);
    const downloadTypes = [];
    if (pixivEnabled) downloadTypes.push({
        contractVersion: 1, type: 'illust', ownerPluginId: 'download-workbench',
        packageId: 'download-workbench', pluginGeneration: 1, publicationId: 1,
        order: 1, moduleUrl: '/test/illust.js', acquisitionModes: ['single-import']
    });
    if (novelEnabled) {
        downloadTypes.push(
            {contractVersion: 1, type: 'novel', ownerPluginId: 'novel', packageId: 'novel',
                pluginGeneration: 1, publicationId: 2, order: 2, moduleUrl: '/test/novel.js',
                acquisitionModes: ['single-import']},
            {contractVersion: 1, type: 'structured', ownerPluginId: 'structured', packageId: 'structured',
                pluginGeneration: 1, publicationId: 3, order: 3, moduleUrl: '/test/structured.js',
                acquisitionModes: ['single-import']});
    }
    if (douyinEnabled) downloadTypes.push({
        contractVersion: 1, type: 'douyin', ownerPluginId: 'douyin', packageId: 'douyin',
        pluginGeneration: 1, publicationId: 4, order: 4,
        moduleUrl: '/pixiv-douyin-download/douyin-queue-type.js',
        acquisitionModes: ['single-import', 'series']
    });
    if (lowOrderEnabled) downloadTypes.push({
        contractVersion: 1, type: 'low-order', ownerPluginId: 'low-order', packageId: 'low-order',
        pluginGeneration: 1, publicationId: 5, order: -30,
        moduleUrl: '/test/low-order.js', acquisitionModes: ['single-import']
    });
    if (ambiguousUrlEnabled) downloadTypes.push({
        contractVersion: 1, type: 'rival-url', ownerPluginId: 'rival-url', packageId: 'rival-url',
        pluginGeneration: 1, publicationId: 6, order: -20,
        moduleUrl: '/test/rival-url.js', acquisitionModes: ['single-import']
    });
    if (secondBareDefaultEnabled) downloadTypes.push({
        contractVersion: 1, type: 'rival-default', ownerPluginId: 'rival-default',
        packageId: 'rival-default', pluginGeneration: 1, publicationId: 7, order: -10,
        moduleUrl: '/test/rival-default.js', acquisitionModes: ['single-import']
    });
    const localStorageMap = new Map();
    const sandbox = {
        window: {location: {origin: 'https://local.test'}, addEventListener() {}, removeEventListener() {}, dispatchEvent() {}},
        document,
        BASE: '',
        pageI18n: {apply() {}},
        console: {warn() {}, log() {}, error() {}},
        setTimeout, clearTimeout, Promise, URL, AbortController,
        CustomEvent: function CustomEvent(type, init) { return {type, detail: init && init.detail}; },
        localStorage: {
            getItem: key => localStorageMap.get(key) || null,
            setItem: (key, value) => localStorageMap.set(key, String(value || ''))
        },
        fetch: () => Promise.resolve({ok: true, json: () => Promise.resolve({
            epoch: 'single-import-test-epoch', revision: 1, downloadTypes, tabs: [], uiSlots: []
        })}),
        // —— 宿主工具函数桩 ——
        SINGLE_IMPORT_MODE: 'single-import',
        bt: key => key,   // 让状态 message === i18n key，便于断言所用 key
        dedupeQueueItems: items => {
            const seen = new Set();
            return items.filter(x => (seen.has(x.id) ? false : (seen.add(x.id), true)));
        },
        addItemsToQueue: (ids, items, source) => { enqueued.push({ids, items, source}); return items.length; },
        setStatus: (message, level) => { status = {message, level}; }
    };
    vm.createContext(sandbox);
    vm.runInContext(QT_SOURCE, sandbox);
    const qt = sandbox.window.PixivBatch.queueTypes;
    const initializers = {
        '/test/illust.js': `(function (context) { return {descriptor: {
            process: function () {},
            import: {bareDefault: true, sectionType: 'artwork', sectionAliases: ['illust'],
                matchUrl: function (line) { var m=String(line).match(/artworks\\/(\\d+)/); return m ? m[1] : null; },
                buildItem: function (id,title) { return {id:String(id),kind:context.type,title:title||('作品 '+id)}; },
                source: 'single-import'}
        }}; })`,
        '/test/low-order.js': `(function (context) { return {descriptor: {
            process: function () {},
            import: {sectionType: 'loworder', bareDefault: false,
                matchUrl: function (line) { var m=String(line).match(/low\\/(\\d+)/); return m ? m[1] : null; },
                buildItem: function (id) { return {id:'l'+id,kind:context.type}; },
                source: 'single-import-low-order'}
        }}; })`,
        '/test/rival-url.js': `(function (context) { return {descriptor: {
            process: function () {},
            import: {sectionType: 'rivalurl', bareDefault: false,
                matchUrl: function (line) { var m=String(line).match(/artworks\\/(\\d+)/); return m ? m[1] : null; },
                buildItem: function (id) { return {id:'r'+id,kind:context.type}; },
                source: 'single-import-rival-url'}
        }}; })`,
        '/test/rival-default.js': `(function (context) { return {descriptor: {
            process: function () {},
            import: {sectionType: 'rivaldefault', bareDefault: true,
                matchUrl: function () { return null; },
                buildItem: function (id) { return {id:'d'+id,kind:context.type}; },
                source: 'single-import-rival-default'}
        }}; })`,
        '/test/novel.js': `(function (context) { return {descriptor: {
            process: function () {},
            import: {sectionType: 'novel',
                matchUrl: function (line) { var m=String(line).match(/novel\\/show\\.php\\?[^\\s|]*?id=(\\d+)/); return m ? m[1] : null; },
                buildItem: function (id,title) { return {id:'n'+id,novelId:String(id),kind:context.type,title:title||('小说 '+id)}; },
                source: 'single-import-novel'}
        }}; })`,
        '/test/structured.js': `(function (context) { return {descriptor: {
            process: function () {},
            import: {sectionType: 'structured',
                matchUrl: function (line) { var m=String(line).match(/example\\.test\\/works\\/([A-Za-z0-9_-]+)/); return m ? {id:m[1],url:'https://example.test/works/'+m[1]} : null; },
                buildItem: function (match,title,line) { return {id:'s'+match.id,workId:match.id,url:match.url,line:line,kind:context.type,title:title||('结构化 '+match.id)}; },
                source: 'single-import-structured'}
        }}; })`
    };
    document.head.onAppend = child => setTimeout(() => {
        const pathname = new URL(child.src, sandbox.window.location.origin).pathname;
        document.currentScript = child;
        if (pathname === '/pixiv-douyin-download/douyin-queue-type.js') {
            vm.runInContext(DOUYIN_SOURCE, sandbox);
        } else {
            vm.runInContext(`window.PixivBatch.queueTypes.registerModule(${initializers[pathname]})`, sandbox);
        }
        document.currentScript = null;
        if (typeof child.onload === 'function') child.onload();
    }, 0);
    await qt.bootstrap();
    vm.runInContext(SI_SOURCE, sandbox);
    sandbox.window.PixivBatch.modes.singleImport.parseSingleImport();
    return {enqueued, status};
}

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }
const bySource = (enqueued, source) => { const b = enqueued.find(e => e.source === source); return b ? b.items : []; };
const novelItems = enqueued => bySource(enqueued, 'single-import-novel');
const illustItems = enqueued => bySource(enqueued, 'single-import');
const structuredItems = enqueued => bySource(enqueued, 'single-import-structured');
const douyinItems = enqueued => bySource(enqueued, 'single-import-douyin');

(async function () {
    // ===== a) novel 可用：`novel:` 区段裸 ID + 显式 novel URL → novel 队列项 =====
    {
        const {enqueued, status} = await runParse(
            'novel:\n123\nhttps://www.pixiv.net/novel/show.php?id=456 | 标题', {novelEnabled: true});
        const ni = novelItems(enqueued);
        const ids = ni.map(x => x.id).sort();
        ok('a: 入队 2 个 novel 项', ni.length === 2);
        ok('a: id 为 n123 / n456', ids[0] === 'n123' && ids[1] === 'n456');
        ok('a: 全部 kind=novel', ni.every(x => x.kind === 'novel'));
        ok('a: 显式 URL 标题被采用', ni.some(x => x.id === 'n456' && x.title === '标题'));
        ok('a: 无插画桶', illustItems(enqueued).length === 0);
        ok('a: 状态为成功汇总', !!status && status.message === 'status.parsed-summary' && status.level === 'success');
    }

    // ===== b) novel 不可用：`novel:` 区段裸 ID → 跳过、计 unavailable、不入队 =====
    {
        const {enqueued, status} = await runParse('novel:\n789', {novelEnabled: false});
        ok('b: 无任何入队', enqueued.length === 0);
        ok('b: 状态 key = skipped-unavailable', !!status && status.message === 'status.single-import-skipped-unavailable');
        ok('b: 状态级别 warning', !!status && status.level === 'warning');
    }

    // ===== c) novel 不可用：其 URL 无 owner hook 认领，不入队、不误判为其它类型 =====
    {
        const {enqueued, status} = await runParse(
            'https://www.pixiv.net/novel/show.php?id=456', {novelEnabled: false});
        ok('c: 无任何入队（不误判为插画、不发起小说 API）', enqueued.length === 0);
        ok('c: 状态 key = none', !!status && status.message === 'status.single-import-none');
        ok('c: 状态级别 error', !!status && status.level === 'error');
    }

    // ===== d) novel 不可用：插画 URL / 裸 ID 仍按旧行为入队 =====
    {
        const {enqueued, status} = await runParse(
            'https://www.pixiv.net/artworks/111\n222', {novelEnabled: false});
        const ii = illustItems(enqueued);
        const ids = ii.map(x => x.id).sort();
        ok('d: 入队 2 个插画项', ii.length === 2);
        ok('d: id 为 111 / 222', ids[0] === '111' && ids[1] === '222');
        ok('d: 无 novel 桶', novelItems(enqueued).length === 0);
        ok('d: 状态为成功汇总', !!status && status.message === 'status.parsed-summary' && status.level === 'success');
    }

    // ===== e) novel 不可用 + 混合输入：插画照常入队、novel URL 跳过、不破坏旧成功路径 =====
    {
        const {enqueued, status} = await runParse(
            'https://www.pixiv.net/artworks/111\nhttps://www.pixiv.net/novel/show.php?id=456', {novelEnabled: false});
        const ii = illustItems(enqueued);
        ok('e: 插画 111 仍入队', ii.length === 1 && ii[0].id === '111');
        ok('e: 无 novel 桶（novel URL 跳过）', novelItems(enqueued).length === 0);
        ok('e: 旧成功路径未被破坏', !!status && status.message === 'status.parsed-summary' && status.level === 'success');
    }

    {
        const {enqueued, status} = await runParse(
            'https://www.pixiv.net/users/123\nhttps://www.pixiv.net/novel/series/456',
            {novelEnabled: false});
        ok('f: unsupported Pixiv URLs enqueue nothing', enqueued.length === 0);
        ok('f: unsupported Pixiv URLs keep old none status', !!status && status.message === 'status.single-import-none');
        ok('f: unsupported Pixiv URLs keep old error level', !!status && status.level === 'error');
    }

    {
        const {enqueued, status} = await runParse(
            'https://example.test/works/abc_123 | 标题', {novelEnabled: true});
        const si = structuredItems(enqueued);
        ok('g: 结构化 matchUrl 结果可入队', si.length === 1 && si[0].id === 'sabc_123');
        ok('g: buildItem 收到原 URL', si[0].url === 'https://example.test/works/abc_123');
        ok('g: 结构化解析仍走成功汇总', !!status && status.message === 'status.parsed-summary' && status.level === 'success');
    }

    {
        const {enqueued, status} = await runParse(
            'https://v.douyin.com/XUyPmdu7naU/', {novelEnabled: false, douyinEnabled: true});
        const di = douyinItems(enqueued);
        ok('h: Douyin 短链可通过单作品导入入队', di.length === 1 && di[0].id === 'dshort-XUyPmdu7naU');
        ok('h: Douyin 短链保留原始作品标识', di[0].douyinId === 'XUyPmdu7naU' && di[0].kind === 'douyin');
        ok('h: Douyin 短链解析走成功汇总', !!status && status.message === 'status.parsed-summary' && status.level === 'success');
    }

    {
        const {enqueued} = await runParse('123', {lowOrderEnabled: true});
        const ii = illustItems(enqueued);
        ok('i: 更低 order 的第三方类型未声明 bareDefault 时不能抢占裸 ID',
            ii.length === 1 && ii[0].id === '123');
    }

    {
        const {enqueued, status} = await runParse(
            'https://www.pixiv.net/artworks/321', {ambiguousUrlEnabled: true});
        ok('j: 两个 URL matcher 同时认领时不按 order 偷选', enqueued.length === 0);
        ok('j: URL 归属歧义使用明确 warning 状态', !!status
            && status.message === 'status.single-import-ambiguous' && status.level === 'warning');
    }

    {
        const {enqueued, status} = await runParse('654', {secondBareDefaultEnabled: true});
        ok('k: 多个 bareDefault 时拒绝裸 ID', enqueued.length === 0);
        ok('k: 裸 ID 默认归属歧义使用明确 warning 状态', !!status
            && status.message === 'status.single-import-ambiguous' && status.level === 'warning');
    }

    {
        const unavailable = await runParse('777', {pixivEnabled: false, novelEnabled: true});
        ok('l: Pixiv 类型缺席时裸 ID 不会回退给其它类型', unavailable.enqueued.length === 0
            && unavailable.status.message === 'status.single-import-skipped-unavailable');
        const explicit = await runParse('novel:\n789', {pixivEnabled: false, novelEnabled: true});
        ok('l: Pixiv 类型缺席不影响显式 novel 区段',
            novelItems(explicit.enqueued).length === 1 && novelItems(explicit.enqueued)[0].id === 'n789');
    }

    console.log(`\nsingle-import.test.js: ${passed} assertions passed (12 scenarios) ✓`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
