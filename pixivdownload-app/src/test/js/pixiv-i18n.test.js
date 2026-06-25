'use strict';
/*
 * 共享 i18n 客户端（pixiv-i18n.js, window.PixivI18n）的「显式 namespace 解析」契约测试。
 *
 * 无浏览器 / 无 jsdom：在 Node 的 vm 沙箱里加载**真实**的 static/js/pixiv-i18n.js（不手写 tns 桩替代），
 * 用 fetch 桩返回 meta 与多个 namespace bundle，经真实 PixivI18n.create({ namespaces }) 构造客户端，
 * 验证 tns(namespace, key, fallback, vars)「namespace 与纯 key 分离」契约的不变量：
 *   a) tns("gallery", "nav.label") 命中**指定** namespace（gallery），不落到默认 / 首个 namespace（common）。
 *   b) tns("missing", "x", "fallback") → 未知 namespace 无 bundle、返回 fallback；present namespace 缺 key 亦回 fallback；
 *      无 fallback 时回 key 本身。
 *   c) tns(null/""/纯空白, key) → 退化为既有 t() 行为（裸 key 在**首个** namespace 内解析）；非空 namespace 先 trim 再查。
 *   d) vars 插值在 tns 命中 / tns 回退（含空白 namespace 回退）/ t() 三条路径上都生效，fallback 串本身也参与插值。
 *
 * 运行： node src/test/js/pixiv-i18n.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC = fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'pixiv-i18n.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

// 按 URL 路由的 fetch 桩：/api/i18n/meta → meta；/api/i18n/messages/<ns> → { messages: bundles[ns] || {} }。
// 后端按 namespace 分别返回 bundle，正是本契约「每个 namespace 一份纯 key→文案表」的来源。
function makeFetch(meta, bundles) {
    return function (url) {
        let body;
        if (url.indexOf('/api/i18n/meta') === 0) {
            body = meta;
        } else {
            const m = url.match(/\/api\/i18n\/messages\/([^?]+)/);
            const ns = m ? decodeURIComponent(m[1]) : '';
            body = { messages: bundles[ns] || {} };
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
    };
}

// 在沙箱里加载真实 pixiv-i18n.js，按给定 namespaces 构造真实客户端（不桩 tns / t / interpolate）。
async function createClient(namespaces, meta, bundles) {
    const sandbox = {
        console: { warn() {}, log() {}, error() {} },
        Promise,
        navigator: { language: 'en-US' },        // create 优先用显式 lang，此处仅兜底、不参与断言
        fetch: makeFetch(meta, bundles)
    };
    sandbox.window = sandbox;                     // 模块 IIFE 以 window 作 global
    vm.createContext(sandbox);
    vm.runInContext(SRC, sandbox);
    return sandbox.PixivI18n.create({ namespaces: namespaces, lang: 'en-US' });
}

async function main() {
    const META = {
        currentLang: 'en-US', defaultLang: 'en-US', cookieName: 'pixiv_lang', parameterName: 'lang',
        supportedLocales: [{ tag: 'en-US', displayName: 'English' }, { tag: 'zh-CN', displayName: '简体中文' }]
    };
    // 同名 key 'nav.label' 在 common 与 gallery 内文案**不同**：用于证明 tns 命中的是指定 namespace 而非默认。
    const BUNDLES = {
        common: { 'nav.label': 'Common Nav', 'greeting': 'Hi {name}!' },
        gallery: { 'nav.label': 'Gallery Nav', 'welcome': 'Welcome {who}' }
    };
    // 首个 namespace 为 common（裸 key / 空 namespace 的回退落点），gallery 为第二 namespace。
    const i18n = await createClient(['common', 'gallery'], META, BUNDLES);

    // ===== a) tns 命中指定 namespace，不落到默认 / 首个 namespace =====
    ok('a: tns("gallery","nav.label") 命中 gallery（"Gallery Nav"）', i18n.tns('gallery', 'nav.label') === 'Gallery Nav');
    ok('a: 不落到默认 namespace（common 的 "Common Nav"）', i18n.tns('gallery', 'nav.label') !== 'Common Nav');
    ok('a: tns("common","nav.label") 命中 common（"Common Nav"）', i18n.tns('common', 'nav.label') === 'Common Nav');
    // 对照：裸 key t() 解析首个 namespace（common），与 tns("gallery",...) 区分开，进一步证明 tns 按 namespace 定向。
    ok('a: 对照 t("nav.label") 解析首个 namespace（common）', i18n.t('nav.label') === 'Common Nav');

    // ===== b) 未知 namespace / 缺 key → fallback；无 fallback → key 本身 =====
    ok('b: tns("missing","x","fallback") 未知 namespace 返回 fallback', i18n.tns('missing', 'x', 'fallback') === 'fallback');
    ok('b: tns("gallery","no.such.key","fb2") present namespace 缺 key 返回 fallback', i18n.tns('gallery', 'no.such.key', 'fb2') === 'fb2');
    ok('b: tns("missing","x") 无 fallback 时返回 key 本身', i18n.tns('missing', 'x') === 'x');

    // ===== c) tns(null/""/纯空白) 退化为 t()：裸 key 在首个 namespace 内解析；非空 namespace 先 trim =====
    ok('c: tns(null,"nav.label") 退化为 t()（首个 namespace common）', i18n.tns(null, 'nav.label') === 'Common Nav');
    ok('c: tns("","nav.label") 退化为 t()（首个 namespace common）', i18n.tns('', 'nav.label') === 'Common Nav');
    ok('c: tns(null,key) 与 t(key) 等价', i18n.tns(null, 'nav.label') === i18n.t('nav.label'));
    // 纯空白 namespace（"  " / 含制表换行）同样退化为 t()：与裸 key t() 等价、绝不落到「空白 namespace」误查。
    ok('c: tns("  ","nav.label") 退化为 t()（首个 namespace common）', i18n.tns('  ', 'nav.label') === 'Common Nav');
    ok('c: tns("  ","nav.label") 与 t("nav.label") 等价', i18n.tns('  ', 'nav.label') === i18n.t('nav.label'));
    ok('c: tns("\\t\\n ",key) 含制表/换行的纯空白亦与 t(key) 等价', i18n.tns('\t\n ', 'nav.label') === i18n.t('nav.label'));
    // 非空 namespace 先 trim 再查：含首尾空白的 "  gallery  " 命中 gallery（"Gallery Nav"），不被当作空白回退。
    ok('c: tns("  gallery  ",key) trim 后命中 gallery（"Gallery Nav"）', i18n.tns('  gallery  ', 'nav.label') === 'Gallery Nav');

    // ===== d) vars 插值在 tns 命中 / tns 回退 / t() 三路径都生效，fallback 串本身也插值 =====
    ok('d: tns 命中路径插值（common.greeting）', i18n.tns('common', 'greeting', null, { name: 'World' }) === 'Hi World!');
    ok('d: tns 命中路径插值（gallery.welcome）', i18n.tns('gallery', 'welcome', 'fb', { who: 'You' }) === 'Welcome You');
    ok('d: t() 裸 key 插值仍生效', i18n.t('greeting', null, { name: 'X' }) === 'Hi X!');
    ok('d: tns(null,...) 回退路径插值生效', i18n.tns(null, 'greeting', null, { name: 'Z' }) === 'Hi Z!');
    ok('d: 缺命中时 fallback 串本身参与插值', i18n.tns('missing', 'x', 'Hello {n}', { n: 'Y' }) === 'Hello Y');
    // 纯空白 namespace 走 t() 回退路径时，vars 插值与裸 key t() 完全一致（既命中 bundle 也插值、缺命中也插值 fallback 串）。
    ok('d: tns("  ",...) 空白 namespace 回退路径命中 bundle 并插值', i18n.tns('  ', 'greeting', null, { name: 'Z' }) === 'Hi Z!');
    ok('d: tns("  ",...) 空白 namespace 回退路径插值与 t() 等价', i18n.tns('  ', 'greeting', null, { name: 'Z' }) === i18n.t('greeting', null, { name: 'Z' }));
    ok('d: tns("   ",...) 空白 namespace 回退时 fallback 串本身参与插值', i18n.tns('   ', 'no.key', 'Hello {n}', { n: 'Y' }) === 'Hello Y');

    console.log(`\npixiv-i18n.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
