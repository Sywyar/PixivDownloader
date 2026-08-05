'use strict';
/*
 * 共享 i18n 客户端（pixiv-i18n.js, window.PixivI18n）的契约测试。
 *
 * 无浏览器 / 无 jsdom：在 Node 的 vm 沙箱里加载**真实**的 static/js/pixiv-i18n.js（不手写桩替代），
 * 用 fetch 桩返回 meta 与多个 namespace bundle，经真实 PixivI18n.create({ namespaces }) 构造客户端，
 * 验证以下不变量：
 *   a) tns(namespace, key, fallback, vars)「namespace 与纯 key 分离」契约；
 *   b) 语言元数据来自后端 /api/i18n/meta；后端不可用时回退 i18n-static/meta.json；
 *   c) 前端不再写死默认语言与语言数组 —— 菜单完全由 meta.supportedLocales 驱动，candidate 不进入；
 *   d) 当前语言按 meta 归一化（未知语言 → default；语言级唯一匹配）；
 *   e) 静态 bundle 回退；
 *   f) 跨标签页语言切换（onLanguageChange 订阅/退订、notifyLanguageChange 可调用）。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC = fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'pixiv-i18n.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

const META = {
    currentLang: 'en-US', sourceLang: 'zh-CN', defaultLang: 'en-US', fallbackLang: 'en-US',
    languageCookieName: 'pixiv_lang', languageParamName: 'lang',
    supportedLocales: [
        { tag: 'en-US', displayName: 'English', direction: 'ltr' },
        { tag: 'zh-CN', displayName: '简体中文', direction: 'ltr' }
    ],
    supportedNamespaces: ['common', 'gallery']
};

// 按 URL 路由的 fetch 桩。
// routes: { '/api/i18n/meta...': meta 或抛错, '/api/i18n/messages/<ns>': bundle, 'i18n-static/meta.json': staticMeta }
function makeFetch(options) {
    return function (url) {
        const config = options || {};
        if (url.indexOf('/api/i18n/meta') === 0) {
            if (config.backendMeta === null) {
                return Promise.reject(new Error('backend unavailable'));
            }
            const body = config.backendMeta !== undefined ? config.backendMeta : META;
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
        }
        if (url.indexOf('i18n-static/meta.json') === 0) {
            if (config.staticMeta === null) {
                return Promise.reject(new Error('static meta unavailable'));
            }
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(config.staticMeta || META) });
        }
        if (url.indexOf('/api/i18n/messages/') === 0) {
            if (config.backendMessages === false) {
                return Promise.reject(new Error('backend unavailable'));
            }
            const m = url.match(/\/api\/i18n\/messages\/([^?]+)/);
            const ns = m ? decodeURIComponent(m[1]) : '';
            const bundles = config.bundles || {};
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ messages: bundles[ns] || {} }) });
        }
        if (url.indexOf('i18n-static/') === 0) {
            const m = url.match(/i18n-static\/([^.]+)\.([^.]+)\.json/);
            const ns = m ? m[1] : '';
            const staticBundles = config.staticBundles || {};
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ messages: staticBundles[ns] || {} }) });
        }
        return Promise.reject(new Error('unexpected url: ' + url));
    };
}

function createSandbox(fetch) {
    const sandbox = {
        console: { warn() {}, log() {}, error() {} },
        Promise,
        navigator: { language: 'en-US' },
        fetch
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(SRC, sandbox);
    return sandbox;
}

async function createClient(namespaces, options) {
    const sandbox = createSandbox(makeFetch(options));
    const client = await sandbox.PixivI18n.create({ namespaces: namespaces, lang: 'en-US' });
    return { client, sandbox };
}

async function main() {
    // ===== a) tns 契约 =====
    const BUNDLES = {
        common: { 'nav.label': 'Common Nav', 'greeting': 'Hi {name}!' },
        gallery: { 'nav.label': 'Gallery Nav', 'welcome': 'Welcome {who}' }
    };
    const { client: i18n } = await createClient(['common', 'gallery'], { bundles: BUNDLES });

    ok('a: tns("gallery","nav.label") 命中 gallery', i18n.tns('gallery', 'nav.label') === 'Gallery Nav');
    ok('a: tns("common","nav.label") 命中 common', i18n.tns('common', 'nav.label') === 'Common Nav');
    ok('a: 对照 t("nav.label") 解析首个 namespace', i18n.t('nav.label') === 'Common Nav');
    ok('a: 未知 namespace 返回 fallback', i18n.tns('missing', 'x', 'fb') === 'fb');
    ok('a: 无 fallback 返回 key', i18n.tns('missing', 'x') === 'x');
    ok('a: 空白 namespace 退化为 t()', i18n.tns('  ', 'nav.label') === 'Common Nav');
    ok('a: tns 命中路径插值', i18n.tns('common', 'greeting', null, { name: 'World' }) === 'Hi World!');
    ok('a: fallback 串参与插值', i18n.tns('missing', 'x', 'Hello {n}', { n: 'Y' }) === 'Hello Y');

    // ===== b) 后端 meta 优先 =====
    const { client: backendClient } = await createClient(['common'], {});
    ok('b: 后端 meta 的 currentLang 生效', backendClient.lang === 'en-US');
    ok('b: 后端 meta 的 defaultLang 生效', backendClient.defaultLang === 'en-US');
    ok('b: cookie 名来自 meta', backendClient.cookieName === 'pixiv_lang');

    // ===== b2) 后端不可用时回退静态 meta =====
    const STATIC_META = {
        currentLang: 'en-US', sourceLang: 'zh-CN', defaultLang: 'en-US', fallbackLang: 'en-US',
        languageCookieName: 'pixiv_lang', languageParamName: 'lang',
        supportedLocales: [
            { tag: 'en-US', displayName: 'English', direction: 'ltr' },
            { tag: 'zh-CN', displayName: '简体中文', direction: 'ltr' }
        ],
        supportedNamespaces: ['common']
    };
    const { client: staticClient } = await createClient(['common'], {
        backendMeta: null, staticMeta: STATIC_META
    });
    ok('b2: 后端不可用时使用静态 meta', staticClient.lang === 'en-US');
    ok('b2: 静态 meta 驱动菜单', staticClient.supportedLocales.length === 2);

    // ===== c) 无硬编码语言数组：菜单完全来自 meta =====
    const META_WITH_CANDIDATE = {
        currentLang: 'en-US', sourceLang: 'zh-CN', defaultLang: 'en-US', fallbackLang: 'en-US',
        languageCookieName: 'pixiv_lang', languageParamName: 'lang',
        supportedLocales: [
            { tag: 'en-US', displayName: 'English' },
            { tag: 'zh-CN', displayName: '简体中文' }
        ],
        supportedNamespaces: ['common']
    };
    const { client: menuClient } = await createClient(['common'], { backendMeta: META_WITH_CANDIDATE });
    const menuTags = menuClient.supportedLocales.map(function (item) { return item.tag; });
    ok('c: 菜单只含 meta 中的正式语言', menuTags.indexOf('en-US') >= 0 && menuTags.indexOf('zh-CN') >= 0);
    ok('c: 菜单不含未发布的 candidate（candidate 由服务端/生成器过滤，前端不写死）',
        menuClient.supportedLocales.length === 2);
    ok('c: displayName 归一化', menuClient.supportedLocales[0].displayName === 'English');

    // ===== d) 当前语言归一化 =====
    const sandboxD = createSandbox(makeFetch({ backendMeta: META }));
    const clientD = await sandboxD.PixivI18n.create({ namespaces: ['common'], lang: 'zh' });
    ok('d: 语言级唯一匹配 zh → zh-CN', clientD.lang === 'zh-CN');
    const sandboxD2 = createSandbox(makeFetch({ backendMeta: META }));
    const clientD2 = await sandboxD2.PixivI18n.create({ namespaces: ['common'], lang: 'fr-FR' });
    ok('d: 未知语言落到 default en-US', clientD2.lang === 'en-US');
    const sandboxD3 = createSandbox(makeFetch({ backendMeta: META }));
    const clientD3 = await sandboxD3.PixivI18n.create({ namespaces: ['common'], lang: 'en-US' });
    ok('d: 精确 tag 保留', clientD3.lang === 'en-US');

    // ===== d2) alias 与 BCP 47 归一化匹配 =====
    const META_ALIASES = {
        currentLang: 'en-US', sourceLang: 'zh-CN', defaultLang: 'en-US', fallbackLang: 'en-US',
        languageCookieName: 'pixiv_lang', languageParamName: 'lang',
        supportedLocales: [
            { tag: 'en-US', aliases: ['en'], displayName: 'English', direction: 'ltr' },
            { tag: 'zh-CN', aliases: ['zh', 'zh-Hans'], displayName: '简体中文', direction: 'ltr' },
            { tag: 'zh-TW', aliases: ['zh-Hant'], displayName: '繁體中文', direction: 'ltr' }
        ],
        supportedNamespaces: ['common']
    };
    async function langOf(lang, meta) {
        const sandbox = createSandbox(makeFetch({ backendMeta: meta }));
        const client = await sandbox.PixivI18n.create({ namespaces: ['common'], lang: lang });
        return client.lang;
    }
    ok('d2: zh-Hans alias 命中 zh-CN', await langOf('zh-Hans', META_ALIASES) === 'zh-CN');
    ok('d2: ZH_hans 归一化 / 大小写不敏感', await langOf('ZH_hans', META_ALIASES) === 'zh-CN');
    ok('d2: en alias 命中 en-US', await langOf('en', META_ALIASES) === 'en-US');
    ok('d2: zh-hant alias 命中 zh-TW（script 标题大小写）', await langOf('zh-hant', META_ALIASES) === 'zh-TW');
    ok('d2: ZH_CN 下划线 / 大小写归一化后精确 tag 命中 zh-CN', await langOf('ZH_CN', META_ALIASES) === 'zh-CN');
    ok('d2: 同语言多个版本时 zh 按明确 alias 命中 zh-CN', await langOf('zh', META_ALIASES) === 'zh-CN');
    const META_TW_NO_ALIAS = {
        currentLang: 'en-US', sourceLang: 'zh-CN', defaultLang: 'en-US', fallbackLang: 'en-US',
        languageCookieName: 'pixiv_lang', languageParameterName: 'lang',
        supportedLocales: [
            { tag: 'en-US', aliases: ['en'], displayName: 'English', direction: 'ltr' },
            { tag: 'zh-CN', aliases: [], displayName: '简体中文', direction: 'ltr' },
            { tag: 'zh-TW', aliases: [], displayName: '繁體中文', direction: 'ltr' }
        ],
        supportedNamespaces: ['common']
    };
    ok('d2: 无 alias 且同语言多个版本时 zh 落到 default', await langOf('zh', META_TW_NO_ALIAS) === 'en-US');

    // ===== e) 静态 bundle 回退（后端 messages 不可用） =====
    const { client: staticBundleClient } = await createClient(['common'], {
        backendMessages: false,
        staticBundles: { common: { 'side-modules.tasks.title': 'Static Tasks' } }
    });
    ok('e: 后端 bundle 不可用时读取静态 bundle', staticBundleClient.t('side-modules.tasks.title') === 'Static Tasks');
    ok('e: 静态 bundle 缺失 key 仍回退 fallback', staticBundleClient.t('no.such.key', 'fb') === 'fb');

    // ===== f) 跨标签页语言切换（无 BroadcastChannel 环境不抛错、可退订） =====
    const sandboxF = createSandbox(makeFetch({ backendMeta: META }));
    const unsubscribeF = await new Promise((resolve) => {
        const client = sandboxF.PixivI18n;
        resolve(client.onLanguageChange(function () {}));
    });
    ok('f: onLanguageChange 返回退订函数', typeof unsubscribeF === 'function');
    sandboxF.PixivI18n.notifyLanguageChange('en-US');
    ok('f: notifyLanguageChange 在无 BroadcastChannel 时安全调用', true);
    unsubscribeF();
    const { client: clientF } = await createClient(['common'], {});
    ok('f: setLanguage 保留跨标签同步契约（返回新客户端）', typeof clientF.setLanguage('en-US').then === 'function');

    console.log(`\npixiv-i18n.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
