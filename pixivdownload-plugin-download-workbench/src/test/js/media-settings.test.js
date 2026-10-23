'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement, createFakeI18n} = require('./pixiv-layout-feedback-test-dom');

function mountSettings(classic = false) {
    const document = {};
    class Element extends MiniElement {
        append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
        replaceChildren() { this.children.slice().forEach(node => this.removeChild(node)); }
        get isConnected() { return document.body.contains(this); }
        get valueAsNumber() { return Number(this.value); }
        reportValidity() { return this.type === 'checkbox' || this.value !== ''
            && Number.isInteger(this.valueAsNumber) && this.valueAsNumber >= Number(this.min)
            && this.valueAsNumber <= Number(this.max); }
    }
    document.createElement = tag => new Element(tag, document);
    document.createTextNode = text => Object.assign(new Element('#text', document), {textContent: text});
    document.body = document.createElement('body');
    const dialog = document.createElement('dialog');
    if (classic) dialog.id = 'download-settings-card';
    document.body.appendChild(dialog);
    const settings = {imageFormats: 'original', ugoiraFormats: 'webp'};
    let changes = 0;
    const context = vm.createContext({document, fetch: () => { throw new Error('Settings must stay browser-local'); }});
    context.window = context;
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch/media-settings.js'), 'utf8'), context);
    context.PixivMediaSettings.mount(dialog, settings, () => changes++, key => key, true);
    return {document, settings, changes: () => changes, media: context.PixivMediaSettings};
}

for (const classic of [false, true]) {
    test(`${classic ? '经典页' : '设置抽屉'}媒体文案随页面翻译更新，保留控件、草稿与展开状态`, () => {
        const {document, settings, changes} = mountSettings(classic);
        const dropdown = document.body.querySelector('details');
        const summary = dropdown.querySelector('summary');
        const png = dropdown.querySelector('input[value="png"]');
        png.checked = true;
        png.dispatchEvent({type: 'change'});
        dropdown.open = true;
        const quality = document.body.querySelector('#media-setting-mediaQuality');
        quality.value = '61';
        quality.focus();
        const client = createFakeI18n();
        client.t = key => 'translated:' + key;
        client.apply(document.body);
        assert.equal(summary.getAttribute('aria-label'), 'translated:batch:media.image-formats.label');
        assert.deepEqual(summary.children.map(node => node.textContent),
            ['translated:batch:media.format.original', ', ', 'translated:batch:media.format.png']);
        const labels = document.body.querySelectorAll('[data-i18n]');
        for (const name of ['image-formats', 'ugoira-formats', 'quality', 'webp-lossless', 'maximum-edge']) {
            assert.ok(labels.some(node => node.getAttribute('data-i18n') === `batch:media.${name}.label`));
        }
        assert.ok(labels.every(node => node.textContent.startsWith('translated:batch:media.')));
        assert.equal(document.body.querySelector('#media-setting-mediaQuality'), quality);
        assert.equal(document.activeElement, quality);
        assert.equal(quality.value, '61');
        assert.equal(dropdown.open, true);
        assert.equal(png.checked, true);
        assert.equal(settings.imageFormats, 'original,png');
        assert.equal(changes(), 1);
    });
}

test('编码控件验证范围并统一捕获独立的任务快照', () => {
    const {document, settings, changes, media} = mountSettings();
    for (const [key, value] of [['mediaQuality', '72'], ['mediaMaximumEdge', '1600']]) {
        const input = document.body.querySelector('#media-setting-' + key);
        input.value = value;
        input.dispatchEvent({type: 'change'});
        assert.equal(settings[key], Number(value));
        for (const invalid of ['', '-1', '1.5', '999999']) {
            input.value = invalid;
            input.dispatchEvent({type: 'change'});
            assert.equal(settings[key], Number(value));
        }
    }
    const lossless = document.body.querySelector('#media-setting-mediaWebpLossless');
    lossless.checked = true;
    lossless.dispatchEvent({type: 'change'});
    const snapshot = media.snapshot(settings);
    settings.mediaQuality = 30;
    assert.equal(snapshot.mediaQuality, 72);
    assert.equal(snapshot.mediaMaximumEdge, 1600);
    assert.equal(snapshot.mediaWebpLossless, true);
    assert.equal(changes(), 3);
});

for (const [index, key, format] of [[0, 'imageFormats', 'png'], [1, 'ugoiraFormats', 'gif']]) {
    test(`${key} 文字激活前的暂时失焦保持展开，选择和键盘退出仍有效`, () => {
        const {document, settings, changes} = mountSettings();
        const dropdown = document.body.querySelectorAll('details')[index];
        const summary = dropdown.querySelector('summary');
        const checkbox = dropdown.querySelector(`input[value="${format}"]`);
        dropdown.open = true;

        // 点击 label 文字时，浏览器先报告空目标失焦，再将焦点交给关联控件。
        summary.dispatchEvent({type: 'focusout', relatedTarget: null});
        assert.equal(dropdown.open, true);
        summary.dispatchEvent({type: 'focusout', relatedTarget: dropdown.closest('dialog')});
        assert.equal(dropdown.open, true);
        assert.equal(changes(), 0);
        summary.dispatchEvent({type: 'focusout', relatedTarget: checkbox});
        assert.equal(dropdown.open, true);
        checkbox.checked = true;
        checkbox.dispatchEvent({type: 'change'});
        assert.ok(settings[key].split(',').includes(format));
        assert.equal(changes(), 1);

        let dialogKeyEvents = 0;
        dropdown.closest('dialog').addEventListener('keydown', () => dialogKeyEvents++);
        const escape = {type: 'keydown', key: 'Escape'};
        checkbox.dispatchEvent(escape);
        assert.equal(dropdown.open, false);
        assert.equal(document.activeElement, summary);
        assert.equal(escape.defaultPrevented, true);
        assert.equal(dialogKeyEvents, 0);
        summary.dispatchEvent({type: 'keydown', key: 'Escape'});
        assert.equal(dialogKeyEvents, 1);
        dropdown.open = true;
        checkbox.dispatchEvent({type: 'focusout', relatedTarget: document.createElement('button')});
        assert.equal(dropdown.open, false);
        assert.equal(changes(), 1);
    });
}
