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

const STATIC = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch');
const QT_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-queue-types.js'), 'utf8');
const SI_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'single-import.js'), 'utf8');
const DOUYIN_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-douyin', 'src', 'main', 'resources', 'static',
    'pixiv-douyin-download', 'douyin-queue-type.js'), 'utf8');

// ---- 最小 DOM（够 batch-queue-types 的 bootstrap/renderSlots + single-import 读 textarea 用）----
function makeDocument(textareaValue) {
    const textarea = {value: textareaValue};
    const noopEl = () => ({
        setAttribute() {}, getAttribute() {}, appendChild() {}, remove() {}, addEventListener() {},
        insertAdjacentHTML() {}, querySelectorAll: () => [], style: {}, children: []
    });
    return {
        getElementById: id => (id === 'single-import-textarea' ? textarea : null),
        querySelectorAll: () => [],            // 本测试不放 slot 锚点 → renderSlots 无注入目标
        createElement: () => noopEl(),
        head: noopEl(),
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
async function runParse(textareaValue, {novelEnabled, douyinEnabled = false}) {
    const enqueued = [];   // [{ids, items, source}]
    let status = null;     // {message, level}
    const document = makeDocument(textareaValue);
    const queueTypesData = [{type: 'illust', order: 1}];
    if (novelEnabled) {
        queueTypesData.push({type: 'novel', order: 2}, {type: 'structured', order: 3});
    }
    if (douyinEnabled) queueTypesData.push({type: 'douyin', order: 4});
    const localStorageMap = new Map();
    const sandbox = {
        window: {addEventListener() {}, dispatchEvent() {}},
        document,
        BASE: '',
        pageI18n: {apply() {}},
        console: {warn() {}, log() {}, error() {}},
        setTimeout, clearTimeout, Promise, URL,
        localStorage: {
            getItem: key => localStorageMap.get(key) || null,
            setItem: (key, value) => localStorageMap.set(key, String(value || ''))
        },
        fetch: () => Promise.resolve({ok: true, json: () => Promise.resolve({queueTypes: queueTypesData, tabs: []})}),
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
    qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});  // 内置插画：无 import 钩子
    if (novelEnabled) qt.register('novel', novelImportDescriptor());
    if (novelEnabled) qt.register('structured', structuredImportDescriptor());
    if (douyinEnabled) vm.runInContext(DOUYIN_SOURCE, sandbox);
    await qt.bootstrap();   // 据 queueTypesData 设 enabledTypes/orderedTypes/bootstrapped
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

    // ===== c) novel 不可用：显式 novel URL → 跳过、计 unavailable、不入队、不误判为插画 =====
    {
        const {enqueued, status} = await runParse(
            'https://www.pixiv.net/novel/show.php?id=456', {novelEnabled: false});
        ok('c: 无任何入队（不误判为插画、不发起小说 API）', enqueued.length === 0);
        ok('c: 状态 key = skipped-unavailable', !!status && status.message === 'status.single-import-skipped-unavailable');
        ok('c: 状态级别 warning', !!status && status.level === 'warning');
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

    console.log(`\nsingle-import.test.js: ${passed} assertions passed (8 scenarios) ✓`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
