'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {initHarness, createHarness, createFakeAdapter, defaultSurvey, selectChoice,
    waitForFlush, captureEvents, EMBED_SOURCE} = require('./pixiv-layout-feedback-test-support');
const root = resolve(__dirname, '../../../..');
const catalog = JSON.parse(readFileSync(resolve(root,
    'pixivdownload-app/src/main/resources/i18n/locales.json'), 'utf8'));
const i18nSource = readFileSync(resolve(root,
    'pixivdownload-app/src/main/resources/static/js/pixiv-i18n.js'), 'utf8');

async function translatedClient(h, locale) {
    const {parse} = await import('../../../../scripts/i18n/lib/properties-parser.mjs');
    const suffix = locale.resourceSuffix ? '_' + locale.resourceSuffix : '';
    const parsed = parse(readFileSync(resolve(__dirname,
        '../../main/resources/i18n/web/layout-feedback' + suffix + '.properties'), 'utf8'));
    assert.deepEqual(parsed.errors, []);
    assert.deepEqual(parsed.duplicateKeys, []);
    const messages = Object.fromEntries(parsed.entries.map(entry => [entry.key, entry.value]));
    const originalFetch = h.sandbox.fetch;
    h.sandbox.navigator = {language: locale.tag};
    h.sandbox.fetch = async url => ({ok: true, json: async () => url.startsWith('/api/i18n/meta')
        ? {currentLang: locale.tag, defaultLang: catalog.defaultLocale, supportedLocales: catalog.locales}
        : {messages}});
    vm.runInContext(i18nSource, h.sandbox);
    try {
        const client = await h.sandbox.PixivI18n.create({lang: locale.tag, namespaces: ['layout-feedback']});
        return {client, messages};
    } finally {
        h.sandbox.fetch = originalFetch;
    }
}

test('布局调查读取真实词典，切换语言保留草稿并翻译提交错误', async () => {
    const h = initHarness({adapter: createFakeAdapter({surveys: [defaultSurvey()], ackOk: false})});
    await h.api.open();
    await waitForFlush();
    const dialog = h.dialog();
    selectChoice(h, 'pixiv-batch-portrait');
    h.textarea().value = '<img>';
    h.textarea().dispatchEvent({type: 'input'});
    for (const locale of catalog.locales.filter(item => ['source', 'supported'].includes(item.status))) {
        const {client, messages} = await translatedClient(h, locale);
        try {
            h.api.refreshLanguage(client);
            assert.equal(h.dialog(), dialog);
            assert.equal(captureEvents(h).filter(event => event === 'survey shown').length, 1);
            assert.equal(h.radios().find(radio => radio.checked).value, 'pixiv-batch-portrait');
            assert.equal(h.textarea().value, '<img>');
            assert.equal(h.document.getElementById('plf-title').textContent, messages['layout-feedback.title']);
            assert.equal(h.submitButton().textContent, messages['layout-feedback.submit']);
            assert.deepEqual(h.counter().textContent.match(/\d+/g), ['5', '1000']);
            assert.doesNotMatch(h.counter().textContent, /\{(?:count|max)\}/);
            for (const [binding, value] of [['data-i18n', node => node.textContent],
                ['data-i18n-placeholder', node => node.getAttribute('placeholder')],
                ['data-i18n-aria-label', node => node.getAttribute('aria-label')],
                ['data-i18n-title', node => node.getAttribute('title')]]) {
                for (const node of dialog.querySelectorAll('[' + binding + ']')) {
                    const key = node.getAttribute(binding).split(':')[1];
                    assert.ok(messages[key], '词典缺少绑定 ' + key);
                    assert.equal(value(node), messages[key]);
                }
            }
            h.submitButton().click();
            assert.equal(dialog.getAttribute('aria-busy'), 'true');
            await waitForFlush();
            assert.equal(dialog.getAttribute('aria-busy'), 'false');
            assert.equal(dialog.querySelector('img'), null);
            assert.equal(h.error().hidden, false);
            assert.equal(h.error().textContent, messages['layout-feedback.submit-failed']);
            assert.equal(h.textarea().value, '<img>');
        } finally {
            client.dispose();
        }
    }
    h.api.destroy();
});

test('嵌入调查按实际词典显示结果，仅回报变化后的内容高度', async () => {
    const locale = catalog.locales.find(item => item.tag === catalog.defaultLocale);
    for (const [status, key] of [['removed', 'embed-unavailable'], ['blocked', 'embed-completed'],
        ['ineligible', 'embed-completed'], ['unavailable', 'embed-temporarily-unavailable'],
        ['failure', 'embed-temporarily-unavailable'], ['opened', 'embed-completed']]) {
        const h = createHarness({});
        const {client, messages} = await translatedClient(h, locale);
        const element = h.document.createElement('p');
        element.id = 'layoutSurveyEmbedStatus';
        element.scrollHeight = 60;
        element.getBoundingClientRect = () => ({height: 50});
        h.body.appendChild(element);
        const posts = [];
        let changed;
        h.sandbox.URLSearchParams = URLSearchParams;
        h.sandbox.location.search = '?notificationId=survey-test';
        h.sandbox.PixivI18n.create = async () => client;
        h.sandbox.PixivPostHog = {showSurveyLoading: async () => {}};
        h.sandbox.PixivSurveyFrameBridge = {ready: async () => ({storage: h.storage}), post: value => posts.push(value)};
        h.sandbox.MutationObserver = class {constructor(callback) { changed = callback; } observe() {}};
        h.sandbox.PixivLayoutFeedback = {init() {}, async openEmbedded() {
            if (status === 'failure') throw Error('unavailable');
            return {status};
        }};
        try {
            vm.runInContext(EMBED_SOURCE, h.sandbox);
            await h.document._events.get('DOMContentLoaded')[0]();
            if (status === 'opened') assert.equal(element.hidden, true);
            changed();
            changed();
            assert.equal(element.hidden, false);
            assert.equal(element.textContent, messages['layout-feedback.' + key]);
            assert.equal(element.getAttribute('aria-busy'), 'false');
            assert.deepEqual(posts.map(value => JSON.parse(JSON.stringify(value))), [
                ...(status === 'removed' ? [{type: 'pixiv-survey-unavailable', notificationId: 'survey-test'}] : []),
                {type: 'pixiv-content-height', height: 60}
            ]);
        } finally {
            client.dispose();
        }
    }
});
