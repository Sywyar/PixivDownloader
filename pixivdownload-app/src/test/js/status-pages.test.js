'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const errorRoot = path.resolve(__dirname, '../../main/resources/static/error');
const statuses = ['400', '403', '404', '429', '4xx', '500', '502', '503', '5xx'];
const sharedResources = [
    '/js/pixiv-i18n.js',
    '/js/pixiv-lang-switcher.js',
    '/js/pixiv-theme.js',
    '/vendor/fonts/fonts.css',
    '/css/pixiv-scrollbar.css',
    '/error/error.css',
    '/error/error.js'
];

test('自定义状态码页覆盖专用状态与 4xx/5xx 兜底并复用共享资源', function () {
    const pages = fs.readdirSync(errorRoot)
        .filter(file => file.endsWith('.html'))
        .sort();
    assert.deepEqual(pages, statuses.map(status => `${status}.html`).sort());

    statuses.forEach(status => {
        const html = fs.readFileSync(path.join(errorRoot, `${status}.html`), 'utf8');
        assert.match(html, new RegExp(`<body data-status="${status}">`));
        sharedResources.forEach(resource => assert.ok(html.includes(resource),
            `${status}.html 缺少 ${resource}`));
        if (status.endsWith('xx')) assert.match(html, /data-error-status-text/);
    });
});

test('4xx/5xx 兜底页投影真实导航状态码', function () {
    assert.equal(projectedStatus('4xx', 418), '418');
    assert.equal(projectedStatus('5xx', 501), '501');
    assert.equal(projectedStatus('4xx', 200), '4xx');
    assert.equal(projectedStatus('5xx', 404), '5xx');
});

function projectedStatus(declaredStatus, responseStatus) {
    const markers = [{ textContent: declaredStatus }, { textContent: declaredStatus }];
    const descriptions = [{ textContent: `This is a ${declaredStatus} error` }];
    const attributes = {};
    const document = {
        title: `${declaredStatus} · Error`,
        readyState: 'complete',
        body: {
            getAttribute: name => name === 'data-status' ? declaredStatus : null,
            setAttribute: (name, value) => { attributes[name] = value; }
        },
        querySelectorAll: selector => selector === '[data-error-status-code]' ? markers
            : selector === '[data-error-status-text]' ? descriptions : [],
        getElementById: () => null,
        addEventListener: () => {}
    };
    vm.runInNewContext(fs.readFileSync(path.join(errorRoot, 'error.js'), 'utf8'), {
        document,
        location: { reload() {} },
        window: {
            performance: {
                getEntriesByType: () => [{ responseStatus }]
            }
        }
    });
    assert.ok(markers.every(marker => marker.textContent === attributes['data-actual-status']));
    assert.equal(descriptions[0].textContent, `This is a ${attributes['data-actual-status']} error`);
    return attributes['data-actual-status'];
}
