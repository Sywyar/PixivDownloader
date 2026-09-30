'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { MiniElement } = require('../../../../pixivdownload-plugin-download-workbench/src/test/js/pixiv-layout-feedback-test-dom');

test('side module labels follow the latest language even when translations arrive out of order', async () => {
    const sidebar = new MiniElement('aside');
    let tabs;
    sidebar.insertAdjacentElement = (_, element) => { tabs = element; };
    const pending = [];
    let onLanguageChange;
    const window = {
        PixivI18n: {
            create(options) { return new Promise(resolve => pending.push({ options, resolve })); },
            onLanguageChange(callback) { onLanguageChange = callback; }
        }
    };
    const document = {
        readyState: 'complete', body: new MiniElement('body'),
        getElementById: id => id === 'sidebar' ? sidebar : null,
        createElement: tag => {
            const element = new MiniElement(tag);
            Object.defineProperty(element, 'innerHTML', { set() { element.children = []; } });
            return element;
        }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/pixiv-side-modules.js'), 'utf8'), {
        window, document, MutationObserver: class { observe() {} }
    });
    const initial = pending.shift();
    onLanguageChange({ lang: 'en-US' });
    pending.shift().resolve({ t: key => 'en:' + key });
    await new Promise(setImmediate);
    const labels = () => tabs.children.map(tab => tab.children[0].textContent);
    assert.deepEqual(labels(), ['en:common:side-modules.nav', 'en:common:side-modules.tasks']);
    initial.resolve({ t: key => 'old:' + key });
    await new Promise(setImmediate);
    assert.deepEqual(labels(), ['en:common:side-modules.nav', 'en:common:side-modules.tasks']);
});
