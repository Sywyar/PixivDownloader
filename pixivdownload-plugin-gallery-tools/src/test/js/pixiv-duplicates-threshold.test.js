const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
    '../../main/resources/static/pixiv-duplicates/pixiv-duplicates.js'), 'utf8');
const storeKeys = ['pixiv_duplicates_dhash_threshold', 'pixiv_duplicates_ahash_threshold'];

async function openPage(raw, storageFails = false) {
    const controls = Object.fromEntries(['thresholdRange', 'thresholdInput', 'ahashRange', 'ahashInput'].map(id =>
        [id, { value: '', listeners: {}, addEventListener(event, callback) { this.listeners[event] = callback; } }]));
    const requests = [];
    const writes = [];
    let ready;
    vm.runInNewContext(source, {
        URLSearchParams,
        document: {
            getElementById: id => controls[id] || null,
            querySelectorAll: () => [],
            addEventListener(event, callback) { if (event === 'DOMContentLoaded') ready = callback; }
        },
        window: { localStorage: {
            getItem(key) {
                assert.ok(storeKeys.includes(key));
                if (storageFails) throw new Error('storage unavailable');
                return raw;
            },
            setItem(key, value) {
                if (storageFails) throw new Error('storage unavailable');
                writes.push([key, value]);
            }
        } },
        PixivI18n: { create: async () => ({ t: key => key, apply() {} }) },
        PixivLangSwitcher: { mount: async () => {} },
        PixivTheme: { mount() {} },
        fetch: async url => {
            requests.push(new URL(url, 'http://test.invalid'));
            return { ok: true, status: 200, json: async () => ({ page: 0, totalGroups: 0, scan: { state: 'IDLE' } }) };
        }
    });
    await ready();
    return { controls, requests, writes };
}

test('页面恢复两种阈值时保留空值回退、数值舍入与边界', async () => {
    for (const [raw, expected] of [[null, 0], ['', 0], ['bad', 0], ['Infinity', 0], ['-1', 0],
        ['0', 0], ['3.49', 3], ['3.5', 4], ['32', 32], ['99', 32]]) {
        const { controls, requests, writes } = await openPage(raw);
        assert.equal(requests.length, 1);
        for (const key of ['threshold', 'ahashThreshold']) {
            assert.equal(requests[0].searchParams.get(key), String(expected), String(raw));
        }
        for (const control of Object.values(controls)) assert.equal(control.value, String(expected));
        assert.deepEqual(writes, []);
    }
});

test('滑动预览不保存或查询，确认后保存并查询规范化值', async () => {
    const { controls, requests, writes } = await openPage('7');
    controls.thresholdRange.value = '3.5';
    controls.thresholdRange.listeners.input();
    assert.equal(controls.thresholdInput.value, '4');
    assert.equal(requests.length, 1);
    assert.deepEqual(writes, []);
    controls.thresholdRange.listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests.at(-1).searchParams.get('threshold'), '4');
    controls.ahashInput.value = 'invalid';
    controls.ahashInput.listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(controls.ahashInput.value, '7');
    assert.equal(controls.ahashRange.value, '7');
    assert.equal(requests.at(-1).searchParams.get('ahashThreshold'), '7');
    assert.deepEqual(writes, [[storeKeys[0], '4'], [storeKeys[1], '7']]);
});

test('存储不可用时仍以回退阈值启动并允许本次页面调整', async () => {
    const { controls, requests, writes } = await openPage('9', true);
    assert.equal(requests[0].searchParams.get('threshold'), '0');
    assert.equal(requests[0].searchParams.get('ahashThreshold'), '0');
    controls.thresholdInput.value = '40';
    controls.thresholdInput.listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(controls.thresholdInput.value, '32');
    assert.equal(controls.thresholdRange.value, '32');
    assert.equal(requests.at(-1).searchParams.get('threshold'), '32');
    assert.deepEqual(writes, []);
});
