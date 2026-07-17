'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const RESOURCES = path.resolve(__dirname, '../../main/resources/static');

function run(relativePath, windowOverrides) {
    const source = fs.readFileSync(path.join(RESOURCES, relativePath), 'utf8');
    const elements = new Map();
    function createElement(tagName) {
        const children = [];
        let elementId = '';
        let className = '';
        let textContent = '';
        const element = {
            nodeType: 1,
            tagName: String(tagName || 'div').toUpperCase(),
            dataset: {},
            children,
            parentNode: null,
            appendChild(child) {
                children.push(child);
                child.parentNode = element;
                return child;
            },
            removeChild(child) {
                const index = children.indexOf(child);
                if (index >= 0) children.splice(index, 1);
                if (child.id) elements.delete(child.id);
                child.parentNode = null;
                return child;
            },
            remove() {
                if (element.parentNode) element.parentNode.removeChild(element);
                if (elementId) elements.delete(elementId);
            }
        };
        element.classList = {
            toggle(token, force) {
                const tokens = new Set(className.split(/\s+/).filter(Boolean));
                const enabled = force === undefined ? !tokens.has(token) : !!force;
                if (enabled) tokens.add(token);
                else tokens.delete(token);
                className = Array.from(tokens).join(' ');
                return enabled;
            },
            contains(token) {
                return className.split(/\s+/).filter(Boolean).includes(token);
            }
        };
        Object.defineProperties(element, {
            id: {
                get() { return elementId; },
                set(value) {
                    if (elementId) elements.delete(elementId);
                    elementId = String(value || '');
                    if (elementId) elements.set(elementId, element);
                }
            },
            className: {
                get() { return className; },
                set(value) { className = String(value || ''); }
            },
            textContent: {
                get() { return textContent; },
                set(value) { textContent = String(value == null ? '' : value); }
            },
            firstChild: {
                get() { return children.length ? children[0] : null; }
            }
        });
        return element;
    }
    const document = {
        getElementById(id) { return elements.get(String(id)) || null; },
        createElement,
        mount(id) {
            const element = createElement('div');
            element.id = id;
            return element;
        }
    };
    const window = Object.assign({document}, windowOverrides || {});
    const context = vm.createContext({
        window,
        document,
        console,
        URL,
        URLSearchParams,
        AbortController,
        setTimeout,
        clearTimeout,
        fetch: window.fetch,
        CustomEvent: class CustomEvent {}
    });
    vm.runInContext(source, context, {filename: relativePath});
    return {window, document};
}

test('queue module exposes only the declared contract-version-1 behavior', async () => {
    let initializer;
    let failQueue = false;
    const requests = [];
    const {window, document} = run('example-download/example-download-type.js', {
        fetch: async (url, init) => {
            requests.push({url: String(url), init: init || {}});
            let body = {code: 'example.completed', item: {id: '123'}};
            let ok = true;
            let status = 200;
            if (String(url).endsWith('/queue') && failQueue) {
                ok = false;
                status = 503;
                body = {
                    code: 'example.queue-failed',
                    messageKey: 'example-download:error.queue'
                };
            } else if (String(url).includes('/user/')) {
                body = {
                    items: [{id: '201', title: 'User item'}],
                    total: 48,
                    page: 2,
                    lastPage: false,
                    hasMore: true,
                    nextCursor: '3'
                };
            } else if (String(url).includes('/quick')) {
                body = {items: [{id: '301', title: 'Quick item'}], total: 1};
            }
            return {ok, status, async json() { return body; }};
        },
        PixivBatch: {
            queueTypes: {
                registerModule(value) {
                    initializer = value;
                    return true;
                }
            }
        }
    });
    assert.equal(typeof initializer, 'function');
    const result = initializer({
        signal: new AbortController().signal,
        assertActive() {},
        onCleanup() {}
    });
    const descriptor = result.descriptor;
    assert.equal(descriptor.contractVersion, 1);
    assert.equal(typeof descriptor.process, 'function');
    assert.equal(descriptor.type, undefined);
    assert.deepEqual(
        Object.keys(descriptor.acquisition).sort(),
        ['quick', 'search', 'series', 'user']);
    assert.equal(descriptor.acquisition.series.pageSize, 12);
    assert.equal(descriptor.acquisition.search.pageSize, 12);
    assert.equal(typeof descriptor.import.matchUrl, 'function');
    assert.equal(descriptor.import.matchUrl('https://example.invalid/work/123').id, '123');
    assert.equal(
        descriptor.import.buildItem({id: '123'}, 'Title', '').id,
        'example-download:123');
    const imported = descriptor.import.buildItem({id: '123'}, 'Title', '');
    assert.equal(imported.id, 'example-download:123');
    assert.equal(imported.cancelWorkKey, '123');
    assert.notEqual(imported.id, imported.cancelWorkKey);
    assert.equal(imported.canonicalUrl, 'https://example.invalid/work/123');
    assert.equal(imported.url, undefined);
    const readyFilter = descriptor.filters['example-ready-filter'];
    assert.equal(typeof readyFilter.matchExtra, 'function');
    assert.equal(typeof readyFilter.evaluateSkip, 'function');
    assert.equal(readyFilter.matchExtra({status: 'waiting'}), true);
    assert.equal(readyFilter.matchExtra({status: 'failed'}), false);
    assert.equal(readyFilter.evaluateSkip({rawStatus: 'failed'}), 'The example task failed');
    assert.equal(descriptor.settings['example-output-setting'].cardId, 'example-download-settings-card');
    assert.match(descriptor.slots['settings-card'], /id="example-download-settings-card"/);

    const item = {id: '123', title: 'Example 123'};
    const updates = [];
    await descriptor.process(item, {
        signal: new AbortController().signal,
        assertActive() {},
        updateItem(patch) { updates.push({...patch}); }
    });
    assert.deepEqual(updates.map(update => update.status), ['downloading', 'completed']);
    assert.equal(updates[1].rawStatus, 'example.completed');
    assert.equal(item.status, undefined);
    const queueRequest = requests.find(request => request.url.endsWith('/queue'));
    assert.deepEqual(JSON.parse(queueRequest.init.body), {id: '123', title: 'Example 123'});

    failQueue = true;
    updates.length = 0;
    await assert.rejects(descriptor.process(item, {
        signal: new AbortController().signal,
        assertActive() {},
        updateItem(patch) { updates.push({...patch}); }
    }), /example\.queue-failed/);
    assert.deepEqual(updates.map(update => update.status), ['downloading', 'failed']);
    assert.equal(updates[1].failureCode, 'example.queue-failed');
    assert.equal(updates[1].statusMessageKey, 'example-download:error.queue');
    assert.equal(updates[1].lastMessage, undefined);
    failQueue = false;

    const user = descriptor.acquisition.user;
    assert.equal(user.parseInput(' author '), 'author');
    assert.equal(await user.fetchMeta('author'), 'author');
    const userPage = await user.fetchPage('author', {
        page: 2,
        signal: new AbortController().signal
    });
    assert.equal(userPage.hasMore, true);
    assert.equal(userPage.nextCursor, '3');
    assert.match(requests.at(-1).url, /\/user\/author\/works\?page=2&pageSize=12$/);
    const userArea = document.mount('user-area');
    user.render(userArea, {
        items: userPage.items,
        inQueue: new Set(['example-download:201'])
    });
    const userCard = document.getElementById('example-user-0');
    assert.equal(userCard.dataset.exampleId, '201');
    assert.equal(userCard.classList.contains('in-queue'), true);

    const search = descriptor.acquisition.search;
    const searchRequest = search.buildRequest({word: 'tag', page: 2});
    assert.equal(searchRequest.endpoint, '/api/example-download/search');
    assert.equal(searchRequest.params.pageSize, 12);
    const rangeRequest = search.buildRangeRequest({word: 'tag', startPage: 2, endPage: 4});
    assert.equal(rangeRequest.endpoint, '/api/example-download/search/range');
    assert.equal(rangeRequest.params.startPage, 2);
    assert.equal(rangeRequest.params.endPage, 4);
    assert.equal(typeof search.syncQueueState, 'function');
    const searchItems = [
        {id: '501', title: 'First search item'},
        {id: '502', title: 'Second search item'}
    ];
    const searchArea = document.mount('search-area');
    search.render(searchArea, {items: [searchItems[1]], base: 1});
    const searchCard = document.getElementById('example-search-1');
    assert.equal(searchCard.classList.contains('in-queue'), false);
    search.syncQueueState(searchItems, new Set(['example-download:502']));
    assert.equal(searchCard.classList.contains('in-queue'), true);
    assert.equal(
        search.buildQueueMeta(searchItems[1], {word: 'tag'}).canonicalUrl,
        'https://example.invalid/work/502');
    assert.equal(search.buildQueueMeta(searchItems[1], {word: 'tag'}).cancelWorkKey, '502');

    const series = descriptor.acquisition.series;
    assert.equal(series.parseUrl('https://example.invalid/series/collection').seriesId, 'collection');
    assert.match(series.apiPath('collection', 2), /\/series\/collection\?page=2&pageSize=12$/);
    const seriesItem = {id: '401', title: 'Series item'};
    const seriesArea = document.mount('series-area');
    series.render(seriesArea, {
        items: [seriesItem],
        inQueue: new Set(['example-download:401'])
    });
    assert.equal(document.getElementById('example-series-0').classList.contains('in-queue'), true);
    const seriesMeta = series.buildQueueMeta(seriesItem, 7, {
        seriesId: 'collection',
        seriesTitle: 'Collection'
    });
    assert.equal(seriesMeta.seriesId, undefined);
    assert.equal(seriesMeta.typeData.seriesId, 'collection');
    assert.equal(seriesMeta.seriesOrder, 7);
    assert.equal(seriesMeta.cancelWorkKey, '401');

    const quick = descriptor.acquisition.quick;
    document.mount('quick-preview-area');
    quick.render([{id: '301', title: 'Quick item'}], 'quick', '',
        new Set(['example-download:301']));
    assert.equal(quick.gridCardId('quick', 0), 'quick-example-0');
    assert.equal(document.getElementById('quick-example-0').classList.contains('in-queue'), true);
    assert.equal(quick.buildQueueMeta({id: '301'}, {}).cancelWorkKey, '301');
    let quickPayload;
    let assertCurrentCount = 0;
    await quick.actions['example-featured'].load(null, {
        signal: new AbortController().signal,
        assertCurrent() { assertCurrentCount += 1; },
        publishWorks(payload) { quickPayload = payload; }
    });
    assert.match(requests.at(-1).url, /\/quick\?pageSize=12$/);
    assert.equal(assertCurrentCount, 1);
    assert.equal(quickPayload.items[0].id, '301');
    assert.equal(quickPayload.total, 1);
    assert.equal(quickPayload.title, 'Example source');
    assert.equal(quickPayload.toolbar.showAdd, true);
    assert.equal(window.PixivBatch.queueTypes != null, true);
});

test('separate UI module mounts and cleans up the declared Vue slot', async () => {
    let initializer;
    let mountedSlot;
    let mountedOptions;
    let cleanup;
    let quickAction;
    let mountCount = 0;
    let unmountCount = 0;
    run('example-download/example-download-ui-slot.js', {
        addEventListener() {},
        removeEventListener() {},
        Vue: {ref(value) { return {value}; }},
        PixivVue: {
            mountUiSlot(slot, options) {
                mountCount += 1;
                mountedSlot = slot;
                mountedOptions = options;
                return Promise.resolve({app: {unmount() { unmountCount += 1; }}});
            }
        },
        PixivBatch: {
            queueTypes: {
                registerUiModule(value) {
                    initializer = value;
                    return true;
                }
            }
        }
    });
    assert.equal(typeof initializer, 'function');
    initializer({
        slots: [{target: 'quick-actions-mine'}],
        isActive() { return true; },
        supports(type, mode) {
            assert.equal(type, 'example-download');
            assert.equal(mode, 'quick');
            return true;
        },
        dispatchQuickAction(action) { quickAction = action; },
        onCleanup(callback) { cleanup = callback; }
    });
    await Promise.resolve();
    assert.equal(mountedSlot.target, 'quick-actions-mine');
    const bindings = mountedOptions.setup();
    assert.equal(bindings.showReady(), false);
    bindings.activate();
    assert.equal(bindings.showReady(), true);
    assert.equal(quickAction, 'example-featured');
    cleanup();
    assert.equal(unmountCount, 1);

    initializer({
        slots: [{target: 'quick-actions-mine'}],
        isActive() { return true; },
        supports() { return false; },
        dispatchQuickAction() { throw new Error('unsupported action must not dispatch'); },
        onCleanup() { throw new Error('unsupported UI must not register cleanup'); }
    });
    await Promise.resolve();
    assert.equal(mountCount, 1);
});

test('schedule module registers the declared source and captures an opaque id', () => {
    let moduleUrl;
    let initializer;
    let registered;
    let restored = null;
    const source = fs.readFileSync(
        path.join(RESOURCES, 'example-download/example-download-schedule-sources.js'), 'utf8');
    const window = {
        PixivBatch: {
            scheduleSources: {
                registerModule(url, value) {
                    moduleUrl = url;
                    initializer = value;
                    return true;
                }
            }
        }
    };
    vm.runInNewContext(source, {window, console, JSON}, {
        filename: 'example-download-schedule-sources.js'
    });
    assert.equal(moduleUrl, '/example-download/example-download-schedule-sources.js');
    initializer({
        descriptors: [{sourceType: 'example-download.ids'}],
        registerSource(type, behavior) { registered = {type, behavior}; }
    });
    assert.equal(registered.type, 'example-download.ids');
    const context = {
        mode: 'single-import',
        workType: 'example-download',
        acquisitionInput(mode) {
            assert.equal(mode, 'single-import');
            return 'https://example.invalid/work/456 | title';
        },
        restoreAcquisition(mode, value) {
            restored = {mode, value};
            return true;
        }
    };
    assert.equal(registered.behavior.matches(context), true);
    assert.equal(registered.behavior.capture(context).params.id, '456');
    assert.deepEqual(
        JSON.parse(JSON.stringify(registered.behavior.restore({params: {id: '789'}}, context))),
        {mode: 'single-import', params: {id: '789'}, kind: 'example-download'}
    );
    assert.deepEqual(restored, {
        mode: 'single-import', value: 'https://example.invalid/work/789'
    });
    assert.doesNotMatch(source, /\bdocument\b/);
    assert.doesNotMatch(source, /switchMode/);
});
