import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';

const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const context = await browser.newContext({viewport: {width: 390, height: 844}, locale: 'en-US'});
// 本机安全软件注入会包装事件监听并丢弃 signal；验收只运行宿主与插件自己的脚本。
await context.route(/^https?:\/\/[^/]*\.kaspersky-labs\.com\//, route => route.abort());
const [name, ...value] = process.env.PAGE_COOKIE.split('=');
await context.addCookies([{name, value: value.join('='), url: origin}]);
const page = await context.newPage(), errors = [], violations = [], requests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => requests.push(new URL(request.url()).pathname));
await page.exposeFunction('officialCsp', value => violations.push(value));
await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', event => window.officialCsp(event.violatedDirective));
    window.beforeUnloadListeners = [];
    const add = window.addEventListener.bind(window), remove = window.removeEventListener.bind(window);
    window.addEventListener = (type, listener, options) => {
        if (type === 'beforeunload') {
            const item = {listener, signal: options?.signal, active: !options?.signal?.aborted};
            beforeUnloadListeners.push(item);
            options?.signal?.addEventListener('abort', () => { item.active = false; }, {once: true});
        }
        return add(type, listener, options);
    };
    window.removeEventListener = (type, listener, options) => {
        if (type === 'beforeunload') beforeUnloadListeners.filter(item => item.listener === listener).forEach(item => { item.active = false; });
        return remove(type, listener, options);
    };
});
let mode = 'fail', delayed;
await page.route('**/pixiv-tts/tts/tts-voices.js?*', route => {
    if (mode === 'fail') return route.abort('failed');
    if (mode === 'delay') {
        delayed = route;
        return page.evaluate(() => { window.ttsScriptLoading = true; });
    }
    return route.continue();
});
try {
    const url = origin + '/page-checklist/render/official-slots.html';
    await page.goto(url);
    await page.waitForFunction(() => window.officialSlotsReady);
    assert.deepEqual(await page.evaluate(() => officialSlotErrors), ['UI_MOUNT_FAILED']);
    assert.equal(await page.locator('#ttsBar, #modalNarrationCast').count(), 0, 'failed controller load cleans external body nodes');
    assert.equal(await page.locator('.pt-lang-switch').count(), 2, 'both actual AI instances mounted');

    mode = 'delay';
    await page.reload();
    await page.waitForFunction(() => document.getElementById('ttsBar') && document.querySelectorAll('.pt-lang-switch').length === 2);
    await page.waitForFunction(() => document.getElementById('aiTranslateBtn')?.style.display === ''
        && document.getElementById('translateSeriesBtn')?.style.display === '');
    await page.waitForFunction(() => window.ttsScriptLoading);
    assert.ok(delayed, 'controller script is in flight');
    await page.evaluate(() => { window.oldAiButtons = [document.getElementById('aiTranslateBtn'), document.getElementById('translateSeriesBtn')]; });
    fs.writeFileSync(path.join(work, 'official-slots-loading'), 'loading');
    await page.waitForFunction(() => !document.querySelector('[data-ui-instance]')
        && !document.querySelector('#ttsBar, #modalNarrationCast, .pt-lang-switch'), undefined, {timeout: 30000});
    await delayed.abort().catch(() => {});
    mode = 'pass';
    const before = requests.filter(url => url.startsWith('/api/admin/')).length;
    await page.evaluate(() => oldAiButtons.forEach(button => button.click()));
    assert.equal(await page.locator('.pt-backdrop').count(), 0, 'withdrawn AI button listeners no longer open dialogs');
    assert.equal(requests.filter(url => url.startsWith('/api/admin/')).length, before);
    assert.deepEqual(await page.evaluate(() => beforeUnloadListeners.filter(item => item.active)
        .map(item => ({signal: !!item.signal, aborted: item.signal?.aborted, listener: String(item.listener).slice(0,160)}))), []);
    assert.deepEqual(await page.evaluate(() => officialSlotErrors), []);
    fs.writeFileSync(path.join(work, 'official-slots-withdrawn'), 'withdrawn');

    await page.waitForFunction(() => document.getElementById('ttsToggle')?.style.display === ''
        && document.getElementById('aiTranslateBtn')?.style.display === ''
        && document.getElementById('translateSeriesBtn')?.style.display === '', undefined, {timeout: 30000});
    assert.equal(await page.locator('#ttsBar').count(), 1);
    assert.equal(await page.locator('#modalNarrationCast').count(), 1);
    assert.equal(await page.locator('.pt-lang-switch').count(), 2);
    assert.equal(await page.evaluate(() => beforeUnloadListeners.filter(item => item.active).length), 2);
    await page.locator('#novel-language select').selectOption('ja');
    assert.deepEqual(await page.evaluate(() => officialSlotEvents), [{name: 'content-language', value: {language: 'ja'}}]);
    await page.locator('#ttsToggle').focus();
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.getElementById('ttsBar').style.display !== 'none');
    await page.locator('#ttsClose').click();
    await page.locator('#aiTranslateBtn').click();
    await page.locator('.pt-backdrop').waitFor();
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.pt-backdrop.open'));
    await page.evaluate(() => {
        document.getElementById('aiTranslateBtn').click();
        document.getElementById('translateSeriesBtn').click();
    });
    await page.waitForFunction(() => document.querySelectorAll('.pt-backdrop').length === 2);
    await page.screenshot({path: path.join(work, 'official-slots-mobile.png'), fullPage: true});
    await page.evaluate(() => Promise.all([officialPages[0].destroy(), officialPages[2].destroy()]));
    assert.equal(await page.locator('.pt-backdrop').count(), 1, 'destroying novel instance preserves series instance');
    assert.equal(await page.locator('.pt-lang-switch').count(), 1);
    assert.equal(await page.locator('#ttsBar, #modalNarrationCast').count(), 0);
    assert.deepEqual(await page.evaluate(() => beforeUnloadListeners.filter(item => item.active)
        .map(item => ({signal: !!item.signal, aborted: item.signal?.aborted, listener: String(item.listener).slice(0,160)}))), []);
    assert.equal(await page.evaluate(() => speechSynthesis.onvoiceschanged == null), true);
    await page.evaluate(() => officialPages[1].destroy());
    assert.equal(await page.locator('.pt-backdrop, .pt-lang-switch, [data-ui-instance]').count(), 0);
    assert.deepEqual(await page.evaluate(() => officialSlotErrors), []);
    assert.deepEqual(errors, []);
    assert.deepEqual(violations, []);
    console.log('OFFICIAL_AI_TTS_BROWSER_FAILURE_WITHDRAWAL_REENABLE_CLEANUP_PASSED');
} catch (error) {
    console.error('OFFICIAL_SLOT_BROWSER_FAILURE', {errors, violations, requests,
        state: await page.evaluate(() => ({ready: window.officialSlotsReady, errors: window.officialSlotErrors}))});
    await page.screenshot({path: path.join(work, 'official-slot-failure.png'), fullPage: true});
    throw error;
} finally { await browser.close(); }
