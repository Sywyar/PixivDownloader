'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {Element, installVue, all, textOf} = require('../../../../pixivdownload-app/src/test/js/vue-render-fixture');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-novel-download/novel-queue-search.js'), 'utf8');
function harness() {
    let initializer, active = true, mounts = 0, unmounts = 0;
    const warnings = [], clicked = [];
    const sandbox = {
        window: {PixivBatch: {queueTypes: {registerSubmodule(fn) { initializer = fn; }}}},
        document: {createElement: tag => new Element(tag)},
        console: {warn: (...args) => warnings.push(args)},
        state: {queue: []}, searchState: {submode: 'page', total: 1, currentPage: 1, pixivPageCount: 1},
        bt: (key, fallback, vars) => fallback.replace(/\{(\w+)\}/g, (_, k) => vars[k]),
        searchStatText: (key, count) => key + ':' + count, hasExtraSearchFilter: () => false,
        getSearchBookmarkCount: item => item.bookmarkCount ?? null,
        buildBookmarkTip: count => ':' + count, buildQueueToggleTip: queued => ':' + queued,
        summarySeparator: () => ' | ', esc: value => String(value),
        addSearchItemToQueue: index => clicked.push(index)
    };
    const Vue = installVue(sandbox);
    const createApp = Vue.createApp;
    Vue.createApp = component => {
        const app = createApp(component), unmount = app.unmount;
        mounts++;
        app.unmount = () => { unmounts++; unmount(); };
        return app;
    };
    sandbox.window.PixivVue = {ensure: () => Promise.resolve(Vue)};
    vm.runInContext(source, sandbox);
    const shared = {context: {isActive: () => active}};
    initializer(shared);
    return {sandbox, Vue, shared, warnings, clicked, counts: () => ({mounts, unmounts}),
        stop() { active = false; shared.disposeNovelSearch(); }};
}
const view = title => ({base: 4, items: [{id: '42', title, userName: '作者', wordCount: 123,
    isOriginal: true, aiType: 2, xRestrict: 1, bookmarkCount: 9}]});
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

test('严格 CSP 下小说卡片保持节点、文本安全与队列交互，撤回释放实例', async () => {
    const h = harness(), area = new Element('section');
    h.shared.renderNovelSearchResults(area, view('<img src=x>'));
    await settle();
    const card = all(area, n => n.props.class === 'novel-search-card')[0];
    assert.ok(card, '必须实际挂载 Vue，不能以回退 HTML 通过');
    assert.ok(textOf(card).includes('<img src=x>'));
    assert.equal(all(area, n => n.tag === 'img').length, 0);
    card.props.onClick();
    assert.deepEqual(h.clicked, [4]);
    for (let i = 0; i < 20; i++) h.shared.renderNovelSearchResults(area, view('更新标题'));
    await h.Vue.nextTick();
    assert.equal(all(area, n => n.props.class === 'novel-search-card')[0], card);
    h.shared.syncNovelSearchQueueState([], new Set(['n42']));
    await h.Vue.nextTick();
    assert.match(card.props.class, /in-queue/);
    assert.ok(textOf(card).includes('更新标题'));
    assert.deepEqual(h.counts(), {mounts: 1, unmounts: 0});
    h.stop();
    assert.deepEqual(h.counts(), {mounts: 1, unmounts: 1});
    assert.equal(all(area, n => n.props.class?.includes('novel-search-card')).length, 0);
    assert.equal(h.warnings.length, 0);
});

test('在途挂载采用最新宿主，撤回后迟到运行时不能复活旧实例', async () => {
    const h = harness();
    let release;
    h.sandbox.window.PixivVue.ensure = () => new Promise(resolve => { release = resolve; });
    const oldArea = new Element('section'), latestArea = new Element('section');
    h.shared.renderNovelSearchResults(oldArea, view('旧结果'));
    h.shared.renderNovelSearchResults(latestArea, view('最新结果'));
    release(h.Vue);
    await settle();
    assert.equal(oldArea.children.length, 0);
    assert.ok(textOf(latestArea).includes('最新结果'));
    h.stop();
    const other = harness();
    other.sandbox.window.PixivVue.ensure = () => new Promise(resolve => { release = resolve; });
    other.shared.renderNovelSearchResults(new Element('section'), view('已撤回'));
    other.stop();
    release(other.Vue);
    await settle();
    assert.deepEqual(other.counts(), {mounts: 0, unmounts: 0});
});

test('真实挂载失败仍保留首屏回退并释放部分应用', async () => {
    const h = harness(), area = new Element('section');
    let released = 0;
    h.Vue.createApp = () => ({mount() { throw Error('mount failed'); }, unmount() { released++; }});
    h.shared.renderNovelSearchResults(area, view('回退结果'));
    await settle();
    assert.match(area.innerHTML, /回退结果/);
    assert.equal(released, 1);
    assert.equal(h.warnings.length, 1);
});
