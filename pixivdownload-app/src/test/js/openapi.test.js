const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/openapi/ui.js'), 'utf8');
const catalogText = '{"openapi":"3.1.0"}';

async function mount(origin, lang = 'en-US', catalog = () => new Response(catalogText), rendererLoads = true, admin = false, savedMode = null) {
    let options, open;
    const requests = [];
    const guidance = [];
    const scripts = [];
    const page = {guidanceRemoved: false};
    const client = {lang, bundleMap: {openapi: {'scalar.operation.testRequest': '試行'}}, t: key => key};
    const access = {dataset: {admin: String(admin)}, remove() { page.guidanceRemoved = true; }};
    const status = {attributes: {}, setAttribute(name, value) {
        this.attributes[name] = value;
        if (value === 'alert') guidance.push(this);
    }};
    const submit = {disabled: false};
    const form = {hidden: true, querySelector: () => submit};
    const key = {value: '', focus() { page.keyFocused = true; }};
    const elements = {'reference-key-form': form, 'reference-key': key, 'reference-access': access, 'reference-status': status};
    const element = id => elements[id] ?? {append() {}};
    const order = [];
    const context = {
        localStorage: {getItem: key => key === 'colorMode' ? savedMode : null},
        location: new URL(origin + '/openapi'), URL, Request,
        Node: {ELEMENT_NODE: 1},
        MutationObserver: class {
            constructor(notify) { this.notify = notify; }
            observe(root, options) {
                page.root = root;
                if (options.childList) page.notifyMount = this.notify;
                else page.notify = this.notify;
            }
        },
        document: {
            documentElement: {style: {removeProperty() { delete this.colorScheme; }}},
            addEventListener(event, fn) { if (event === 'DOMContentLoaded') open = fn; },
            getElementById: element, createTextNode: value => value,
            createElement: tag => ({
                tag, attributes: {}, children: [],
                setAttribute(name, value) { this.attributes[name] = value; },
                append(...children) { this.children.push(...children); },
                get childElementCount() { return this.children.length; },
                get classList() { return {contains: name => this.className === name}; }
            }),
            head: {append: script => {
                order.push('renderer');
                scripts.push(script.src);
                queueMicrotask(() => rendererLoads ? script.onload() : script.onerror(new Error('blocked')));
            }}
        },
        PixivI18n: {create: async () => client}, PixivLangSwitcher: {mount() {}},
        Scalar: {createApiReference: (_, config) => { options = config; config.onLoaded(); }},
        fetch: async (input, init) => {
            const request = new Request(input, init);
            order.push('request');
            requests.push(request);
            return new URL(request.url).pathname === '/api/openapi.json' ? catalog(request) : new Response();
        }
    };
    vm.runInNewContext(source, context);
    page.initialScheme = context.document.documentElement.style.colorScheme;
    await open();
    page.finalScheme = context.document.documentElement.style.colorScheme;
    return {get options() { return options; }, requests, guidance, scripts, page, order, access, form, key};
}

test('loads the localized catalog once with the page session and sends only same-origin program requests without it', async () => {
    const {options, requests, guidance, scripts, page} = await mount('https://reference.example', 'ja-JP');
    assert.equal(options.localization.locale, 'ja-JP');
    assert.equal(options.localization.translations.operation.testRequest, '試行');
    assert.equal(new URL(requests[0].url).searchParams.get('lang'), 'ja-JP');
    assert.equal(requests[0].credentials, 'same-origin');
    assert.equal(requests[0].redirect, 'error');
    assert.equal(JSON.stringify(options.content), catalogText);
    assert.equal(options.url, undefined);
    assert.equal(guidance.length, 0);
    assert.deepEqual(scripts, ['/openapi/scalar.js']);
    assert.equal(page.guidanceRemoved, true);
    const headers = {Authorization: 'Bearer fixture'};
    await options.customFetch(new Request('https://reference.example/api/settings', {headers, credentials: 'include'}));
    assert.equal(requests[1].credentials, 'omit');
    assert.equal(requests[1].redirect, 'error');
    assert.equal(requests[1].headers.get('Authorization'), headers.Authorization);
    await assert.rejects(options.customFetch('/api/settings'));
    for (const credential of ['X-Session-Token', 'X-GUI-Token', 'Cookie']) {
        await assert.rejects(options.customFetch('/api/settings', {headers: {...headers, [credential]: 'fixture'}}));
    }
    await assert.rejects(options.customFetch('https://other.example/api/settings', {headers}));
    await assert.rejects(options.customFetch('/login.html', {headers}));
    assert.equal(requests.length, 2);
    assert.equal(options.persistAuth, false);
});

test('matches the saved Scalar theme before startup and hands it back after mounting', async () => {
    for (const mode of ['dark', 'light', 'system', null]) {
        const {page} = await mount('https://reference.example', 'en-US', undefined, true, true, mode);
        assert.equal(page.initialScheme, ['dark', 'light'].includes(mode) ? mode : undefined);
        assert.equal(page.finalScheme, undefined);
    }
});

test('signed-in page requests Scalar before the catalog completes and restores guidance on a stale session', async () => {
    const signedIn = await mount('https://reference.example', 'en-US', undefined, true, true);
    assert.deepEqual(signedIn.order, ['renderer', 'request']);
    assert.equal(signedIn.page.guidanceRemoved, true);
    const expired = await mount('https://reference.example', 'en-US', () => new Response('', {status: 401}), true, true);
    assert.equal(expired.options, undefined);
    assert.equal(expired.access.dataset.admin, 'false');
    assert.equal(expired.guidance[0].textContent, 'page.denied');
});

test('places related links directly below responses and falls back to details when responses are absent', async () => {
    const catalog = {paths: {
        '/api/a': {get: {'x-related-operations': ['GET /gone', 'POST /api/b/{id}']}},
        '/api/b/{id}': {post: {tags: ['Other_group'], summary: 'Run <job>'}}
    }};
    const {page} = await mount('https://reference.example', 'en-US', () => Response.json(catalog));
    const children = [{classList: {contains: () => false}}];
    const responseBlock = {
        get lastElementChild() { return children.at(-1); },
        append: child => children.push(child)
    };
    const details = {
        nodeType: 1, matches: selector => selector.split(', ').includes('.operation-layout > .operation-details'),
        querySelector: selector => selector === ':scope > div > .responses-list--tree' ? {parentElement: responseBlock} : null,
        closest: () => ({id: 'api-1/tag/group/GET/api/a'})
    };
    const added = [{target: {}, addedNodes: [details]}];
    page.notifyMount(added);
    page.notifyMount(added);
    assert.equal(children.length, 2);
    const footer = children.at(-1);
    const [heading, list] = footer.children;
    assert.equal(footer.tag, 'footer');
    assert.equal(heading.textContent, 'related.operations');
    assert.equal(list.children.length, 1);
    const link = list.children[0].children[0];
    assert.equal(link.href, '#tag/other-group/POST/api/b/{id}');
    assert.equal(link.children[0], 'Run <job> ');
    assert.equal(link.children[1].textContent, 'POST /api/b/{id}');

    const withoutResponses = {...details, querySelector: () => null, append: child => { withoutResponses.lastElementChild = child; }};
    page.notifyMount([{target: {}, addedNodes: [withoutResponses]}]);
    assert.equal(withoutResponses.lastElementChild.className, 'reference-related');
});

test('remote HTTP can read the catalog but cannot send a key', async () => {
    const {options, requests} = await mount('http://reference.example', 'zh-Hant');
    assert.equal(options.localization.locale, 'zh-TW');
    assert.equal(options.hideTestRequestButton, true);
    await assert.rejects(options.customFetch('/api/settings', {headers: {Authorization: 'Bearer fixture'}}));
    assert.equal(requests.length, 1);
    assert.equal((await mount('http://127.0.0.1')).options.hideTestRequestButton, false);
    const denied = await mount('http://reference.example', 'en-US', () => new Response(null, {status: 401}));
    assert.equal(denied.form.hidden, true);
    assert.equal(denied.guidance[0].textContent, 'request.blocked');
});

test('retries the catalog with an in-memory key and excludes the page session', async () => {
    const statuses = [401, 401, 403, 200];
    const mounted = await mount('https://reference.example', 'ja-JP',
        () => new Response(catalogText, {status: statuses.shift()}));
    assert.equal(mounted.form.hidden, false);
    for (const [key, error] of [['invalid', 'page.invalidKey'], ['limited', 'page.keyForbidden'], ['reader', null]]) {
        mounted.key.value = key;
        let prevented = false;
        await mounted.form.onsubmit({preventDefault() { prevented = true; }});
        assert.equal(prevented, true);
        assert.equal(mounted.key.value, '');
        const request = mounted.requests.at(-1);
        assert.equal(request.url, 'https://reference.example/api/openapi.json?lang=ja-JP');
        assert.equal(request.credentials, 'omit');
        assert.equal(request.redirect, 'error');
        assert.equal(request.headers.get('Authorization'), 'Bearer ' + key);
        if (error) {
            assert.equal(mounted.guidance.at(-1).textContent, error);
            assert.equal(mounted.form.hidden, false);
            assert.equal(mounted.page.keyFocused, true);
            assert.equal(mounted.scripts.length, 0);
        }
    }
    assert.equal(mounted.page.guidanceRemoved, true);
    assert.equal(JSON.stringify(mounted.options.content), catalogText);
    assert.equal(mounted.requests.length, 4);
    await assert.rejects(mounted.options.customFetch('/api/settings'));
});

test('keeps the selected group mounted across batched navigation and releases it on a documentation-only tag', async () => {
    const {page} = await mount('https://reference.example');
    const list = {children: []};
    function group(hash, expanded) {
        const item = {parentElement: list, matches: selector => selector === '[data-sidebar-id]'};
        const link = {hash, matches: selector => selector === 'a[aria-current="page"]', closest: () => item};
        const toggle = expanded === undefined ? null : {
            matches: selector => selector === 'button[aria-expanded="true"]' && expanded,
            closest: () => item,
            click() { expanded = false; }
        };
        item.querySelector = () => toggle;
        list.children.push(item);
        return {
            selected: {target: link, attributeName: 'aria-current'},
            opened: {target: toggle, attributeName: 'aria-expanded'},
            isExpanded: () => expanded
        };
    }
    page.root.querySelector = () => ({contains: () => true});
    const introduction = group('#description/introduction');
    const first = group('#tag/first', true);
    page.notify([introduction.selected]);
    assert.equal(first.isExpanded(), true);

    const second = group('#tag/second', true);
    page.notify([second.selected, second.opened, first.opened]);
    assert.equal(first.isExpanded(), false);
    assert.equal(second.isExpanded(), true);
    page.notify([first.selected]);
    assert.equal(second.isExpanded(), true);

    const third = group('#tag/third', true);
    page.notify([third.opened]);
    assert.equal(second.isExpanded(), false);
    assert.equal(third.isExpanded(), true);

    page.notify([group('#tag/guide').selected]);
    assert.equal(third.isExpanded(), false);
});

test('keeps the server-rendered access guidance when the catalog cannot be read', async () => {
    for (const [catalog, key] of [
        [() => new Response('{}', {status: 401}), 'page.denied'],
        [() => new Response('{}', {status: 403}), 'page.denied'],
        [() => new Response('{}', {status: 503}), 'page.failed'],
        [() => new Response('invalid JSON'), 'page.failed'],
        [() => { throw new TypeError('network'); }, 'page.failed']
    ]) {
        const {options, guidance, scripts, page} = await mount('https://reference.example', 'en-US', catalog);
        assert.equal(options, undefined);
        assert.equal(guidance.length, 1);
        assert.equal(guidance[0].textContent, key);
        assert.equal(guidance[0].attributes.role, 'alert');
        // 读不到目录时不下载渲染器，页面保留服务端说明。
        assert.deepEqual(scripts, []);
        assert.equal(page.guidanceRemoved, false);
    }
    const blocked = await mount('https://reference.example', 'en-US', undefined, false);
    assert.equal(blocked.options, undefined);
    assert.equal(blocked.guidance[0].textContent, 'page.failed');
    assert.equal(blocked.page.guidanceRemoved, false);
});
