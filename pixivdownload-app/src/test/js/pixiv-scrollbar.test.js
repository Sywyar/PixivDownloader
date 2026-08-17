'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const repoRoot = path.resolve(__dirname, '../../../..');
const sharedStylesheet = '<link rel="stylesheet" href="/css/pixiv-scrollbar.css">';

function staticRoots() {
    const roots = fs.readdirSync(repoRoot, {withFileTypes: true})
        .filter(entry => entry.isDirectory() && /^pixivdownload-(app|plugin-)/.test(entry.name))
        .map(entry => path.join(repoRoot, entry.name, 'src', 'main', 'resources', 'static'));
    const templatesRoot = path.join(repoRoot, 'plugin-templates');
    fs.readdirSync(templatesRoot, {withFileTypes: true})
        .filter(entry => entry.isDirectory())
        .forEach(entry => roots.push(path.join(templatesRoot, entry.name, 'src', 'main', 'resources', 'static')));
    return roots.filter(fs.existsSync);
}

function filesUnder(root) {
    return fs.readdirSync(root, {withFileTypes: true}).flatMap(entry => {
        const file = path.join(root, entry.name);
        return entry.isDirectory() ? filesUnder(file) : [file];
    });
}

test('共享滚动条样式覆盖所有 Web 页面并保持单一来源', function () {
    const sharedCss = fs.readFileSync(path.join(repoRoot,
        'pixivdownload-app', 'src', 'main', 'resources', 'static', 'css', 'pixiv-scrollbar.css'), 'utf8');
    assert.match(sharedCss, /scrollbar-width:\s*thin/);
    assert.match(sharedCss, /scrollbar-color:\s*var\(--scrollbar-thumb\)/);
    assert.match(sharedCss, /\*::-webkit-scrollbar/);

    const files = staticRoots().flatMap(filesUnder);
    const missing = files
        .filter(file => file.endsWith('.html') && path.basename(file) !== 'index.html')
        .filter(file => !fs.readFileSync(file, 'utf8').includes(sharedStylesheet))
        .map(file => path.relative(repoRoot, file));
    assert.deepEqual(missing, []);

    const duplicateOwners = files
        .filter(file => file.endsWith('.css') && !file.endsWith('pixiv-scrollbar.css'))
        .filter(file => fs.readFileSync(file, 'utf8').includes('--scrollbar-thumb:'))
        .map(file => path.relative(repoRoot, file));
    assert.deepEqual(duplicateOwners, []);
});
