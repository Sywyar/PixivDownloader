'use strict';
/*
 * 下载页 i18n 初始化 smoke。
 *
 * 验证 batch-core.js 创建页面 i18n client 前，会从下载类型 descriptor 收集动态 namespace。
 *
 * 运行： node src/test/js/batch-core-i18n.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const CORE_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch',
    'batch-core.js');
const SOURCE = fs.readFileSync(CORE_PATH, 'utf8');

let passed = 0;
function ok(label, cond) {
    assert.ok(cond, label);
    passed++;
}

(async function () {
    const created = [];
    const sandbox = {
        window: {
            PixivBatch: {
                queueTypes: {
                    i18nNamespaces: () => Promise.resolve(['douyin', 'batch', '', null])
                }
            }
        },
        document: {
            body: {},
            title: '',
            getElementById: () => null
        },
        console: {warn() {}, log() {}, error() {}},
        Promise,
        Set,
        PixivI18n: {
            create: opts => {
                created.push((opts && opts.namespaces || []).slice());
                const client = {
                    lang: (opts && opts.lang) || 'zh-CN',
                    supportedLocales: [],
                    t(key, fallback) {
                        return key === 'batch:page.title' ? 'Batch title' : (fallback || key);
                    },
                    apply() { return client; },
                    setLanguage(lang) {
                        return sandbox.PixivI18n.create({lang, namespaces: client.namespaces.slice()});
                    }
                };
                client.namespaces = (opts && opts.namespaces || []).slice();
                return Promise.resolve(client);
            }
        },
        PixivLangSwitcher: {mount: () => Promise.resolve({})},
        PixivTheme: {mount() {}},
        applyCookieHint() {},
        syncCookieToggleLabel() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);

    await sandbox.initPageI18n();
    const namespaces = created[0] || [];

    ok('默认 namespace 仍被加载', ['batch', 'common', 'novel', 'ai', 'tour'].every(ns => namespaces.includes(ns)));
    ok('插件 descriptor namespace 被合并进页面 i18n', namespaces.includes('douyin'));
    ok('重复 namespace 去重', namespaces.filter(ns => ns === 'batch').length === 1);
    ok('空 namespace 被忽略', !namespaces.includes(''));
    ok('页面静态翻译仍执行', sandbox.document.title === 'Batch title');

    console.log(`\nbatch-core-i18n.test.js: ${passed} assertions passed`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.stack ? err.stack : err);
    process.exit(1);
});
