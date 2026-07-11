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

const PLUGIN_RESOURCES = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources');
const CORE_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch', 'batch-core.js');
const HTML_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch.html');
const BATCH_ZH_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch.properties');
const BATCH_EN_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch_en.properties');
const SOURCE = fs.readFileSync(CORE_PATH, 'utf8');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');
const BATCH_ZH = fs.readFileSync(BATCH_ZH_PATH, 'utf8');
const BATCH_EN = fs.readFileSync(BATCH_EN_PATH, 'utf8');

function propertyKeys(source) {
    return new Set(String(source).split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line && !line.startsWith('#') && line.includes('='))
        .map(line => line.slice(0, line.indexOf('=')).trim()));
}

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
                    i18nNamespaces: () => Promise.resolve(['novel', 'douyin', 'batch', '', null])
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
    const fixedNamespaces = (SOURCE.match(/const BATCH_I18N_NAMESPACES = \[([^\]]*)\]/) || [])[1] || '';
    const zhKeys = propertyKeys(BATCH_ZH);
    const enKeys = propertyKeys(BATCH_EN);
    const baseKindKeys = [
        'batch.user.kind-illust',
        'batch.user.kind-request',
        'batch.search.kind-illust'
    ];

    ok('默认 namespace 仍被加载', ['batch', 'common', 'ai', 'tour'].every(ns => namespaces.includes(ns)));
    ok('固定 namespace 不再依赖可选 novel', !fixedNamespaces.includes("'novel'"));
    ok('控制器仍从活动下载类型动态收集 namespace', SOURCE.includes('await qt.i18nNamespaces()'));
    ok('可选 novel namespace 由活动 descriptor 动态合并', namespaces.includes('novel'));
    ok('插件 descriptor namespace 被合并进页面 i18n', namespaces.includes('douyin'));
    ok('重复 namespace 去重', namespaces.filter(ns => ns === 'batch').length === 1);
    ok('空 namespace 被忽略', !namespaces.includes(''));
    ok('页面静态翻译仍执行', sandbox.document.title === 'Batch title');
    baseKindKeys.forEach(key => {
        ok('基础下载类型 HTML 使用 batch namespace: ' + key,
            HTML.includes('data-i18n="' + key + '"'));
        ok('基础下载类型 HTML 不再引用 novel namespace: ' + key,
            !HTML.includes('data-i18n="novel:' + key + '"'));
        ok('中文 batch bundle 提供基础下载类型文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供基础下载类型文案: ' + key, enKeys.has(key));
    });

    console.log(`\nbatch-core-i18n.test.js: ${passed} assertions passed`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.stack ? err.stack : err);
    process.exit(1);
});
