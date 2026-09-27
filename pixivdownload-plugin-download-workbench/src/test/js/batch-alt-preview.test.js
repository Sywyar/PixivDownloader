'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function previewContext(localStorage) {
    const root = new MiniElement('body');
    root.id = 'abModePanel';
    const selectionBar = new MiniElement('div');
    selectionBar.id = 'abSelectionBar';
    selectionBar.hidden = true;
    selectionBar.append = (...children) => children.forEach(child => selectionBar.appendChild(child));
    Object.defineProperty(selectionBar, 'firstChild', {get: () => selectionBar.children[0]});
    root.appendChild(selectionBar);
    const document = {
        addEventListener() {},
        getElementById: id => root.querySelector('#' + id),
        querySelectorAll: selector => root.querySelectorAll(selector),
        createElement(tag) {
            const node = new MiniElement(tag);
            const closest = node.closest.bind(node);
            node.closest = selector => selector.split(',').map(part => closest(part.trim())).find(Boolean) || null;
            node.append = (...children) => children.forEach(child => node.appendChild(child));
            node.style.setProperty = () => {};
            return node;
        }
    };
    const context = vm.createContext({document, console, localStorage, state: {mode: 'search'},
        QUICK_FETCH_MODE: 'quick-fetch', SINGLE_IMPORT_MODE: 'single-import',
        applyThumbHue() {}, chromeState: {}, addEventListener() {},
        searchState: {kind: 'illust', word: 'sample'}, extraFilters: {}, dockState: {},
        workTypeMeta: () => ({cls: 'illust', label: 'Illustration'}),
        getSearchBookmarkCount: item => item.bookmarkCount ?? null,
        summaryJoin: values => values.join(' · '),
        queueHas: () => false
    });
    context.window = context;
    for (const name of ['alt-core.js', 'alt-selection.js', 'alt-modes.js']) {
        vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch-alt', name), 'utf8'), context);
    }
    return {context, root};
}

test('预览收起跨重绘保留，分页一起隐藏，各预览独立', () => {
    const {context, root} = previewContext();
    const options = {previewKey: 'search', page: 1, totalPages: 2, total: 4, onPage() {}, onEnqueueAll() {}};
    const bar = context.enqueueBar(options);
    const grid = context.worksGrid([{id: '1', title: 'Test'}], options);
    const pages = context.paginationBar(options);
    root.appendChild(bar); root.appendChild(grid); root.appendChild(pages);
    const collapse = bar.querySelector('.ab-preview-toggle');
    collapse.dispatchEvent({type: 'click'});
    assert.equal(grid.hidden, true);
    assert.equal(pages.hidden, true);
    assert.equal(collapse.getAttribute('aria-expanded'), 'false');
    const replacement = context.worksGrid([{id: '2'}], options);
    assert.equal(replacement.hidden, true);
    assert.equal(context.worksGrid([], {previewKey: 'user'}).hidden, false);
    root.appendChild(replacement);
    collapse.dispatchEvent({type: 'click'});
    assert.equal(replacement.hidden, false);
    assert.equal(pages.hidden, false);
});

test('入队提示跟随真实队列状态，小说卡片保留元数据和可展开详情', () => {
    const {context, root} = previewContext();
    const card = context.workCard({id: 'n12', kind: 'novel', title: 'Example', userName: 'Author',
        wordCount: 1200, bookmarkCount: 4, isOriginal: true, uploadTimestamp: Date.UTC(2026, 8, 27),
        readingTimeSeconds: 300, tags: ['tag-a']});
    root.appendChild(card);
    const enqueue = card.querySelector('.ab-work-enqueue');
    const enqueueLabel = enqueue.title;
    assert.ok(enqueueLabel);
    assert.equal(enqueue.parentNode, card.querySelector('.ab-work-info'));
    assert.match(card.querySelector('.ab-work-meta').textContent, /1,200/);
    const details = card.querySelector('.ab-work-details').querySelector('p').textContent;
    assert.match(details, /2026/);
    assert.match(details, /tag-a/);
    context.queueHas = () => true;
    context.syncAllResultsQueueState();
    assert.notEqual(enqueue.title, enqueueLabel);
    assert.equal(enqueue.title, enqueue.getAttribute('aria-label'));
    assert.equal(enqueue.getAttribute('aria-pressed'), 'true');
    context.queueHas = () => false;
    context.syncAllResultsQueueState();
    assert.equal(enqueue.title, enqueueLabel);
});

test('已知总页数可跳页，非法页码不请求，游标分页只允许逐页浏览', () => {
    const {context} = previewContext();
    const calls = [];
    const bar = context.paginationBar({page: 2, totalPages: 8, total: 80, onPage: p => calls.push(p)});
    const jump = bar.querySelector('.ab-page-jump');
    const page = jump.querySelector('input');
    for (const value of ['', '0', '9', '1.5', 'no', '2']) {
        page.value = value;
        jump.dispatchEvent({type: 'submit', preventDefault() {}});
    }
    assert.deepEqual(calls, []);
    page.value = '6';
    jump.dispatchEvent({type: 'submit', preventDefault() {}});
    assert.deepEqual(calls, [6]);
    for (const label of ['第一页', '最后一页']) {
        bar.querySelectorAll('button').find(node => node.textContent === label).dispatchEvent({type: 'click'});
    }
    assert.deepEqual(calls, [6, 1, 8]);
    assert.equal(context.paginationBar({page: 2, hasNext: true, onPage() {}}).querySelector('.ab-page-jump'), null);
});

test('操作模式切换不提交选择，直接模式的复选框与卡片均切换入队且跟随真实结果', () => {
    const {context, root} = previewContext();
    const queued = new Set();
    const added = [];
    const notices = [];
    let accept = true;
    let removable = true;
    context.queueHas = id => queued.has(id);
    context.normalizeAuthorId = id => id || null;
    context.abToast = (level, message) => notices.push({level, message});
    context.removeFromQueue = id => removable && queued.delete(id);
    context.addItemsToQueue = (ids, metadata) => {
        if (!accept) return 0;
        ids.forEach(id => queued.add(id));
        added.push(...metadata);
        return ids.length;
    };
    const bar = context.enqueueBar({pageEnabled: true, onEnqueueAll() {}});
    const grid = context.worksGrid([
        {id: '1', title: 'Draft'}, {id: '2', title: 'Image'},
        {id: 'n3', kind: 'novel', title: 'Book', seriesId: 7, seriesOrder: 3, tags: ['tag']}
    ]);
    root.appendChild(bar); root.appendChild(grid);
    const cards = grid.querySelectorAll('.ab-work');
    const click = node => node.dispatchEvent({type: 'click', stopPropagation() {}});
    const choose = card => {
        const checkbox = card.querySelector('.ab-work-select');
        checkbox.checked = !checkbox.checked;
        checkbox.dispatchEvent({type: 'change'});
    };
    context.syncAllResultsQueueState();
    click(cards[0]);
    assert.equal(context.currentWorkSelection().size, 1);
    assert.equal(queued.size, 0);
    const modes = bar.querySelectorAll('[data-work-mode]');
    assert.equal(bar.children[bar.children.indexOf(bar.querySelector('.ab-preview-toggle')) - 1],
        modes[0].parentNode, '切换位于预览收起按钮左侧');
    click(modes[1]);
    assert.equal(modes[1].getAttribute('aria-pressed'), 'true');
    assert.equal(context.currentWorkSelection().size, 1);
    assert.equal(queued.size, 0, '切换不会提交原有批量草稿');
    assert.equal(root.querySelector('#abSelectionBar').hidden, true);
    assert.equal(bar.querySelector('.ab-select-page').hidden, true);
    assert.ok(cards.every(card => card.querySelector('.ab-work-enqueue').hidden));
    choose(cards[1]);
    click(cards[2]);
    assert.deepEqual([...queued], ['2', 'n3']);
    assert.equal(added[1].kind, 'novel');
    assert.equal(added[1].seriesOrder, 3);
    assert.equal(cards[2].querySelector('.ab-work-select').checked, true);
    assert.equal(cards[2].querySelector('.ab-work-select').disabled, false);
    assert.match(cards[2].querySelector('.ab-work-select').title, /从队列移除/);
    click(cards[2]);
    assert.deepEqual([...queued], ['2'], '再次点击卡片移出队列');
    assert.equal(cards[2].querySelector('.ab-work-select').checked, false);
    assert.match(cards[2].querySelector('.ab-work-select').title, /直接加入队列/);
    choose(cards[2]);
    assert.equal(queued.has('n3'), true);
    choose(cards[2]);
    assert.equal(queued.has('n3'), false, '再次点击复选框移出队列');
    assert.equal(cards[2].querySelector('.ab-work-select').checked, false);
    removable = false;
    click(cards[1]);
    assert.equal(queued.has('2'), true);
    assert.equal(cards[1].querySelector('.ab-work-select').checked, true, '拒绝移除时恢复勾选');
    choose(cards[1]);
    assert.equal(queued.has('2'), true);
    assert.equal(cards[1].querySelector('.ab-work-select').checked, true);
    assert.equal(notices.at(-1).level, 'warning');
    cards[2].dispatchEvent({type: 'click', target: cards[2].querySelector('summary')});
    assert.equal(queued.has('n3'), false, '展开作品详情不触发入队');
    accept = false;
    choose(cards[2]);
    assert.equal(cards[2].querySelector('.ab-work-select').checked, false, '入队失败不假报勾选成功');
    const nextPage = context.workCard({id: '4'});
    assert.equal(nextPage.querySelector('.ab-work-enqueue').hidden, true);
    click(modes[0]);
    assert.equal(root.querySelector('#abSelectionBar').hidden, false);
    assert.equal(cards[0].querySelector('.ab-work-select').checked, true);
    assert.equal(cards[1].querySelector('.ab-work-select').disabled, true, '批量模式仍禁止重复选择已入队作品');
    assert.equal(cards[1].querySelector('.ab-work-enqueue').hidden, false);
});

test('操作模式在重新打开后恢复，未知值和存储不可用时仍可正常使用', () => {
    const values = new Map();
    const storage = {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value)
    };
    const open = localStorage => {
        const {context, root} = previewContext(localStorage);
        const controls = context.workInteractionModeControl();
        root.appendChild(controls);
        const buttons = controls.querySelectorAll('button');
        const active = () => buttons.find(button => button.getAttribute('aria-pressed') === 'true').dataset.workMode;
        const select = mode => buttons.find(button => button.dataset.workMode === mode).dispatchEvent({type: 'click'});
        return {context, active, select};
    };
    const first = open(storage);
    assert.equal(first.active(), 'select');
    first.select('direct');
    const restored = open(storage);
    assert.equal(restored.active(), 'direct');
    assert.equal(restored.context.workCard({id: '1'}).querySelector('.ab-work-enqueue').hidden, true);
    assert.equal(restored.context.currentWorkSelection().size, 0, '恢复模式不恢复或提交作品选择');
    restored.select('select');
    assert.equal(open(storage).active(), 'select');
    for (const value of ['', 'unknown', '{"mode":"direct"}']) {
        const invalid = open({getItem: () => value});
        assert.equal(invalid.active(), 'select');
    }
    const unavailable = open({
        getItem() { throw new Error('Storage blocked'); },
        setItem() { throw new Error('Storage blocked'); }
    });
    assert.equal(unavailable.active(), 'select');
    unavailable.select('direct');
    assert.equal(unavailable.active(), 'direct');
    const readOnly = open({getItem: () => 'direct', setItem() { throw new Error('Quota exceeded'); }});
    assert.equal(readOnly.active(), 'direct');
    readOnly.select('select');
    assert.equal(readOnly.active(), 'select');
});
