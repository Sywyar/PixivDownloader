'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

test('监控数据先于语言资源返回时仍显示时间和作者，并在语言就绪后使用所选格式', async () => {
    const instant = new Date('2024-06-15T13:24:00Z');
    const lastUpdated = {textContent: ''};
    let resolveI18n;
    const pendingI18n = new Promise(resolve => { resolveI18n = resolve; });
    const context = vm.createContext({
        Date: class extends Date {
            constructor(...args) { super(...(args.length ? args : [instant.getTime()])); }
        },
        document: {
            addEventListener() {},
            getElementById: id => id === 'lastUpdatedTime' ? lastUpdated : null
        },
        window: {addEventListener() {}},
        PixivI18n: {create: () => pendingI18n},
        PixivLangSwitcher: {mount: async () => {}},
        PixivTheme: {mount() {}}
    });
    for (const file of ['monitor-core.js', 'monitor-init.js']) {
        const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/monitor', file), 'utf8');
        vm.runInContext(source, context, {filename: file});
    }
    vm.runInContext("authorMap = new Map([[2, 'Bob'], [1, 'Alice']]);", context);
    const initialized = vm.runInContext('initPageI18n()', context);

    function assertFormatted(locale) {
        const formatted = vm.runInContext(`formatDateTime(${instant.getTime()})`, context);
        assert.equal(formatted.date, instant.toLocaleDateString(locale));
        assert.equal(formatted.time, instant.toLocaleTimeString(locale, {hour: '2-digit', minute: '2-digit'}));
        assert.equal(formatted.full, instant.toLocaleString(locale));
        vm.runInContext('updateLastUpdatedTime()', context);
        assert.equal(lastUpdated.textContent, instant.toLocaleTimeString(locale));
        assert.equal(vm.runInContext('getAuthorOptions().map(author => author.id).join(",")', context), '1,2');
    }

    assertFormatted(undefined);
    resolveI18n({lang: 'en-US', t: (_key, fallback) => fallback, apply() {}});
    await initialized;
    assertFormatted('en-US');
});
