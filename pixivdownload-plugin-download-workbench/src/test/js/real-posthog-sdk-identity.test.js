'use strict';
/*
 * 真实 vendored posthog-js 1.409.5 身份初始化验证（方案一：Node 最小 DOM + 真实 SDK）。
 *
 * 加载 static/vendor/posthog-js/1.409.5/array.full.js 的完整 bundle，在 Node 最小
 * DOM shim 环境中真实执行 posthog.init：
 *   1. bootstrap: {distinctID: scopedId, isIdentifiedID: false} 初始化后
 *      posthog.get_distinct_id() === scopedId（匿名 register 分支，不触发 identify）；
 *   2. 旧写法 sdkConfig.distinct_id 不参与初始化：get_distinct_id() 不会等于该值
 *      （证明 sdkConfig.distinct_id 在 1.409.5 中无效）；
 *   3. capture 结果 properties.distinct_id 来自当前 SDK distinct ID；
 *   4. 所有外部网络请求被拦截（fetch / XHR 都被记录，不连接真实 PostHog）。
 *
 * 运行：node pixivdownload-plugin-download-workbench/src/test/js/real-posthog-sdk-identity.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const VENDOR_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'vendor', 'posthog-js', '1.409.5', 'array.full.js');
const SDK = fs.readFileSync(VENDOR_PATH, 'utf8');

let passed = 0;
function ok(label, condition) {
    if (!condition) throw new Error('FAIL: ' + label);
    passed++;
}

/* ============================================================
   最小 DOM / 浏览器 shim（全部网络请求都被记录，不真正联网）
============================================================ */

const netCalls = [];

function makeDocument() {
    const element = {
        tagName: 'DIV',
        style: {},
        setAttribute() {}, getAttribute() { return null; },
        addEventListener() {}, removeEventListener() {},
        appendChild() {}, removeChild() {}, insertBefore() {},
        getContext() { return {canvas: null, measureText() { return {width: 0}; }, fillText() {}}; },
        getBoundingClientRect() { return {width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0}; },
        cloneNode() { return this; },
        parentNode: null,
        children: [],
        textContent: ''
    };
    return {
        documentElement: element,
        head: element,
        body: element,
        cookie: '',
        title: '',
        referrer: '',
        hidden: false,
        visibilityState: 'visible',
        location: {href: 'http://localhost/', pathname: '/', host: 'localhost',
            hostname: 'localhost', protocol: 'http:', search: '', hash: ''},
        createElement(tag) {
            const el = {
                tagName: String(tag).toUpperCase(),
                style: {},
                setAttribute() {}, getAttribute() { return null; },
                addEventListener() {}, removeEventListener() {},
                appendChild() {}, removeChild() {}, insertBefore() {},
                getContext() { return {canvas: null, measureText() { return {width: 0}; }, fillText() {}}; },
                getBoundingClientRect() { return {width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0}; },
                cloneNode() { return this; },
                parentNode: null,
                children: [],
                textContent: ''
            };
            return el;
        },
        addEventListener() {}, removeEventListener() {},
        querySelector() { return null; }, querySelectorAll() { return []; },
        getElementById() { return null; }
    };
}

function makeXhr() {
    function XHR() {
        this.readyState = 0;
    }
    XHR.prototype.open = function (method, url) {
        netCalls.push({kind: 'xhr', method, url});
        this._url = url;
        this.readyState = 1;
    };
    XHR.prototype.setRequestHeader = function () {};
    XHR.prototype.send = function () {
        const self = this;
        setTimeout(() => {
            self.readyState = 4;
            self.status = 200;
            self.responseText = JSON.stringify({
                featureFlags: {'survey-not-used': true},
                surveys: true,
                autocaptureExceptions: false,
                siteApps: [],
                toolbarParams: {},
                sessionRecording: false,
                isAuthenticated: false,
                supportedCompression: ['gzip', 'lz64'],
                captureDeadClicks: false,
                capturePerformance: false,
                captureConsoleLogs: false,
                heatmaps: false,
                rageclick: false,
                webVitals: false,
                scrollDepth: false
            });
            if (self.onreadystatechange) self.onreadystatechange();
        }, 0);
    };
    XHR.prototype.abort = function () { this.readyState = 0; };
    XHR.prototype.getResponseHeader = function (name) {
        return name.toLowerCase() === 'content-type' ? 'application/json' : null;
    };
    XHR.prototype.getAllResponseHeaders = function () {
        return 'content-type: application/json\r\n';
    };
    XHR.DONE = 4;
    return XHR;
}

function makeStorage() {
    const data = {};
    return {
        getItem(k) { return Object.prototype.hasOwnProperty.call(data, k) ? data[k] : null; },
        setItem(k, v) { data[k] = String(v); },
        removeItem(k) { delete data[k]; },
        key(i) { return Object.keys(data)[i] || null; },
        get length() { return Object.keys(data).length; }
    };
}

function buildSandbox() {
    const sandbox = {
        console,
        setTimeout, clearTimeout, setInterval, clearInterval,
        Date, Math, JSON, Promise, Array, Object, String, Number, Boolean,
        Symbol, Map, Set, WeakMap, WeakSet, Error, TypeError, RangeError, SyntaxError,
        RegExp, encodeURIComponent, decodeURIComponent, parseInt, parseFloat, isNaN, isFinite,
        Uint8Array, Uint16Array, Uint32Array, Int8Array, Int16Array, Int32Array,
        Float32Array, Float64Array, ArrayBuffer, DataView, TextEncoder, TextDecoder,
        location: {href: 'http://localhost:6999/pixiv-batch.html',
            pathname: '/pixiv-batch.html', host: 'localhost:6999', hostname: 'localhost',
            protocol: 'http:', search: '', hash: ''},
        navigator: {userAgent: 'real-sdk-test', platform: 'node', language: 'zh-CN',
            languages: ['zh-CN'], onLine: true, webdriver: false, maxTouchPoints: 0,
            hardwareConcurrency: 4, deviceMemory: 8, vendor: '', productSub: '20030107',
            cookieEnabled: true, sendBeacon() { return true; }},
        history: {pushState() {}, replaceState() {}, back() {}, forward() {}},
        screen: {width: 1920, height: 1080, colorDepth: 24, pixelDepth: 24},
        document: makeDocument(),
        localStorage: makeStorage(),
        sessionStorage: makeStorage(),
        XMLHttpRequest: makeXhr(),
        fetch(url) {
            netCalls.push({kind: 'fetch', url});
            return Promise.reject(new Error('network blocked by test harness'));
        },
        URL, URLSearchParams, Blob, FormData, Headers, Request, Response,
        requestAnimationFrame(cb) { return setTimeout(cb, 16); },
        cancelAnimationFrame(id) { clearTimeout(id); },
        matchMedia() { return {matches: false, addListener() {}, removeListener() {},
            addEventListener() {}, removeEventListener() {}}; },
        getComputedStyle() { return {getPropertyValue() { return ''; }}; },
        crypto: require('crypto').webcrypto,
        performance: {now() { return Date.now(); }, timeOrigin: Date.now()},
        MessageChannel, MessagePort, postMessage() {},
        Event, CustomEvent, EventTarget,
        addEventListener() {}, removeEventListener() {},
        isSecureContext: false,
        origin: 'http://localhost:6999',
        Blob, File, FormData, Headers, Request, Response, AbortController, AbortSignal
    };
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    sandbox.globalThis = sandbox;
    sandbox.top = sandbox;
    sandbox.parent = sandbox;
    return sandbox;
}

/* ============================================================
   真实 SDK 验证
============================================================ */

function main() {
    const sandbox = buildSandbox();
    vm.createContext(sandbox);
    vm.runInContext(SDK, sandbox, {filename: 'array.full.js'});
    ok('真实 SDK 已加载（posthog.init 可用）',
        sandbox.posthog && typeof sandbox.posthog.init === 'function');
    ok('真实 SDK 公开 get_distinct_id',
        typeof sandbox.posthog.get_distinct_id === 'function');

    const scopedId = 'plf_' + 'a'.repeat(64);
    const config = {
        api_host: 'http://localhost:6999/pg',
        ui_host: 'http://localhost:6999/pg',
        autocapture: false,
        capture_pageview: false,
        capture_pageleave: false,
        capture_performance: false,
        capture_dead_clicks: false,
        capture_exceptions: false,
        capture_heatmaps: false,
        disable_session_recording: true,
        disable_surveys: false,
        person_profiles: 'identified_only',
        persistence: 'localStorage',
        cross_subdomain_cookie: false,
        respect_dnt: true,
        save_campaign_params: false,
        save_referrer: false,
        rageclick: false,
        disable_surveys_automatic_display: true,
        advanced_only_evaluate_survey_feature_flags: true,
        disable_external_dependency_loading: true,
        feature_flag_request_timeout_ms: 5000,
        surveys_request_timeout_ms: 15000,
        mask_all_text: true,
        mask_all_element_attributes: true,
        bootstrap: {distinctID: scopedId, isIdentifiedID: false}
    };
    sandbox.posthog.init('phc_real_sdk_test_token', config);

    ok('bootstrap.distinctID 初始化后 get_distinct_id() === scopedId',
        sandbox.posthog.get_distinct_id() === scopedId);

    const result = sandbox.posthog.capture('survey sent', {'$survey_id': 's1'});
    ok('capture 返回 CaptureResult', !!result && result.event === 'survey sent');
    ok('capture 结果 distinct_id 为 scoped ID',
        result.properties.distinct_id === scopedId);

    // 旧写法证明：sdkConfig.distinct_id 不参与初始化（这就是必须改 bootstrap 的原因）。
    const fresh = buildSandbox();
    vm.createContext(fresh);
    vm.runInContext(SDK, fresh, {filename: 'array.full.js'});
    fresh.posthog.init('phc_real_sdk_test_token', {
        api_host: 'http://localhost:6999/pg',
        ui_host: 'http://localhost:6999/pg',
        persistence: 'localStorage',
        person_profiles: 'identified_only',
        respect_dnt: true,
        distinct_id: 'legacy-config-distinct-id'
    });
    ok('sdkConfig.distinct_id 不被 1.409.5 用作初始化身份（旧配置无效）',
        fresh.posthog.get_distinct_id() !== 'legacy-config-distinct-id');

    // 网络拦截：所有请求都进了 netCalls（被 stub 处理），没有任何真实外联。
    ok('所有网络请求被拦截（无真实外联）',
        netCalls.every(call => {
            const url = String(call.url);
            return url.indexOf('http://localhost:6999') === 0 || url.indexOf('localhost:6999') >= 0;
        }));
    ok('SDK 只请求了本地拦截端点',
        netCalls.length >= 1 && netCalls.every(call =>
            String(call.url).indexOf('i.posthog.com') < 0
            && String(call.url).indexOf('us.posthog.com') < 0
            && String(call.url).indexOf('eu.posthog.com') < 0));

    console.log(`\nreal-posthog-sdk-identity.test.js: ${passed} assertions passed ✓`);
    console.log(`  get_distinct_id() === scopedId: ${sandbox.posthog.get_distinct_id() === scopedId}`);
    console.log('  network intercepted: ' + netCalls.length + ' local calls (no real PostHog)');
    process.exit(0);
}

try {
    // 防止 SDK 内部残留定时器拖住进程：验证完成后 15 秒强制退出并报错。
    const guard = setTimeout(() => {
        console.error('FAIL: real SDK test hung');
        process.exit(2);
    }, 15000);
    guard.unref();
    main();
} catch (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exit(1);
}
