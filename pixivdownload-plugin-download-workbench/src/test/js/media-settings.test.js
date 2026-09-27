'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function mountSettings() {
    const document = {};
    class Element extends MiniElement {
        append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
        replaceChildren() { this.children.slice().forEach(node => this.removeChild(node)); }
        get isConnected() { return document.body.contains(this); }
    }
    document.createElement = tag => new Element(tag, document);
    document.body = document.createElement('body');
    const dialog = document.createElement('dialog');
    document.body.appendChild(dialog);
    const settings = {imageFormats: 'original', ugoiraFormats: 'webp'};
    let changes = 0;
    const context = vm.createContext({document, fetch: async () => ({ok: false})});
    context.window = context;
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch/media-settings.js'), 'utf8'), context);
    context.PixivMediaSettings.mount(dialog, settings, () => changes++, key => key, true);
    return {document, settings, changes: () => changes};
}

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
