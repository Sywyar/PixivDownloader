import {checkCssEditor} from './css-editor-browser.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT
    ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [phase, work] = process.argv.slice(2);
const origin = process.env.PAGE_ORIGIN;
const extension = path.join(work, 'zoom-extension'); fs.mkdirSync(extension, {recursive: true});
fs.writeFileSync(path.join(extension, 'manifest.json'), JSON.stringify({manifest_version: 3, name: 'Isolated zoom check',
    version: '1.0', permissions: ['tabs'], background: {service_worker: 'worker.js'}}), 'utf8');
fs.writeFileSync(path.join(extension, 'worker.js'), 'chrome.runtime.onInstalled.addListener(() => {});', 'utf8');
const context = await chromium.launchPersistentContext(path.join(work, 'browser-' + phase), {
    channel: 'chromium', headless: true, viewport: {width: 1440, height: 1000}, locale: 'en-US',
    args: ['--disable-extensions-except=' + extension, '--load-extension=' + extension],
    ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const cookie = process.env.PAGE_COOKIE.split('=');
await context.addCookies([{name: cookie[0], value: cookie.slice(1).join('='), url: origin},
    {name: 'pixiv_lang', value: 'en-US', url: origin}]);
const page = await context.newPage();
const violations = [], errors = [], requests = [];
const networkErrors = [], consoleErrors = [];
page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
page.on('requestfailed', request => networkErrors.push({url: request.url(), error: request.failure()?.errorText}));
page.on('response', response => { if (response.status() >= 400) networkErrors.push({url: response.url(), status: response.status()}); });
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => { if (!request.url().startsWith(origin) && !request.url().startsWith('data:')) requests.push(request.url()); });
await page.exposeFunction('recordCsp', value => violations.push(value));
await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event =>
    window.recordCsp({directive: event.violatedDirective, blocked: event.blockedURI})));
const clickConfirm = async () => page.locator('[data-pixiv-feedback-action="accept"],.pixiv-feedback-button--primary').click();
const waitStatus = async text => page.waitForFunction(value => document.getElementById('status')?.textContent === value, text);
async function editor() {
    await context.addCookies([{name: cookie[0], value: cookie.slice(1).join('='), url: origin}]);
    await page.goto(origin + '/page-editor/index.html');
    await page.waitForFunction(() => window.pageEditorReady, undefined, {timeout: 20000});
}
async function login() {
    await context.clearCookies({name: cookie[0]});
    await page.goto(origin + '/login.html');
    await page.locator('#login-btn').waitFor({state: 'visible'});
}
async function documentState() {
    return page.evaluate(async () => (await fetch('/api/page-editor/documents/core:login')).json());
}
async function selectLayer(type, identity) {
    await page.locator('#structure-tab').click();
    const id = await page.evaluate(({type, identity}) => {
        const all = model => [model, ...model.components().models.flatMap(all)];
        return all(window.grapesjs.editors[0].getWrapper()).find(model =>
            model.get('type') === type && (!identity || model.get('region') === identity || model.get('role') === identity)).getId();
    }, {type, identity});
    await page.locator('#selection').selectOption(id);
}
async function restore() {
    await page.locator('#document-more > summary').click();
    await page.locator('#restore').click(); await clickConfirm();
    await waitStatus('Previous publication and draft restored');
}
async function checkEditorLayout() {
    await editor();
    assert.equal(await page.locator('#undo').isDisabled(), true);
    assert.equal(await page.locator('#redo').isDisabled(), true);
    const more = page.locator('#document-more > summary');
    await more.focus(); await more.press('Enter');
    assert.equal(await page.locator('#restore').isDisabled(), true);
    await more.press('Escape');
    assert.equal(await page.locator('#document-more').evaluate(node => node.open), false);
    assert.equal(await more.evaluate(node => node === document.activeElement), true);
    const canvas = page.frameLocator('iframe.gjs-frame');
    await canvas.locator('input[type=password]').waitFor();
    await selectLayer('page-region', 'admin-title');
    await page.locator('.gjs-sm-property__padding-top input').waitFor();
    assert.equal(await page.locator('#delete').isDisabled(), true);
    assert.equal(await page.locator('#up').isDisabled(), true);
    assert.equal(await page.locator('.gjs-sm-property__flex-direction').isVisible(), false);
    await selectLayer('page-region', 'username');
    await page.locator('#down').click();
    await canvas.locator('[data-page-region="username"] input').waitFor();
    assert.ok((await canvas.locator('[data-page-region="username"]').boundingBox()).y >
        (await canvas.locator('[data-page-region="password"]').boundingBox()).y, 'required controls can move within their parent');
    await page.locator('#up').click();
    await canvas.locator('[data-page-region="username"] input').waitFor();
    assert.ok((await canvas.locator('[data-page-region="username"]').boundingBox()).y <
        (await canvas.locator('[data-page-region="password"]').boundingBox()).y);
    const checkContrast = async () => {
      for (const selector of ['.gjs-sm-property__gap input', '.gjs-block-label', '.pixiv-lang-switcher__select']) {
        const result = await page.locator(selector).first().evaluate(node => {
            const css = getComputedStyle(node);
            let surface = node;
            while (surface.parentElement && getComputedStyle(surface).backgroundColor === 'rgba(0, 0, 0, 0)') surface = surface.parentElement;
            const background = getComputedStyle(surface).backgroundColor;
            const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => {
                v /= 255; return v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4;
            }).reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0);
            const a = luminance(css.color), b = luminance(background);
            return {ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05), size: parseFloat(css.fontSize),
                enabled: !node.disabled, opacity: css.opacity};
        });
        assert.ok(result.ratio >= 4.5, JSON.stringify(result));
        assert.ok(result.size >= 14); assert.ok(result.enabled); assert.equal(result.opacity, '1');
      }
    };
    await checkContrast();
    await page.screenshot({path: path.join(work, 'editor-design-light.png'), fullPage: true});
    await page.locator('.pixiv-theme-toggle').click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    await checkContrast();
    await page.screenshot({path: path.join(work, 'editor-design-dark.png'), fullPage: true});
    await page.locator('.pixiv-theme-toggle').click();
    await page.locator('#add-tab').click();
    const container = page.getByRole('button', {name: 'Container', exact: true});
    await container.press('Enter');
    await page.locator('.gjs-sm-property__flex-direction').waitFor();
    await page.getByRole('button', {name: 'Text', exact: true}).press('Enter');
    await page.waitForFunction(() => !document.getElementById('outdent').disabled);
    const layerAlignment = await page.locator('#layers .gjs-layer-item').evaluateAll(rows => rows.every(row => {
        const elements = [...row.querySelectorAll('.gjs-layer-name,.gjs-layer-caret')].filter(node => node.getBoundingClientRect().height);
        return elements.every(node => {
            const box = node.getBoundingClientRect(), parent = row.getBoundingClientRect();
            return box.top >= parent.top - 1 && box.bottom <= parent.bottom + 1;
        });
    }));
    assert.ok(layerAlignment, 'layer names and carets stay inside their own row');
    assert.equal(await page.locator('#delete').isDisabled(), false);
    await page.locator('#delete').click(); await page.locator('#undo').click();
    assert.equal(await page.locator('#redo').isDisabled(), false);
    for (const viewport of [{width: 390, height: 844}, {width: 900, height: 500}, {width: 1280, height: 720}, {width: 1920, height: 500}]) {
        await page.setViewportSize(viewport);
        await more.click();
        const menu = await page.locator('.action-menu-content').boundingBox();
        assert.ok(menu.x >= 0 && menu.x + menu.width <= viewport.width + 2, 'menu fits ' + JSON.stringify(viewport));
        await more.press('Escape');
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), JSON.stringify(viewport));
    }
    await page.setViewportSize({width: 1440, height: 1000});
    await page.locator('#document-more > summary').click();
    await page.locator('#reload').click(); await clickConfirm(); await waitStatus('Draft loaded');
    const metadata = await page.evaluate(async () => (await fetch('/api/i18n/meta')).json());
    for (const locale of metadata.supportedLocales) {
        await Promise.all([
            page.waitForEvent('framenavigated', {predicate: frame => frame === page.mainFrame()}),
            page.locator('.pixiv-lang-switcher__select').selectOption(locale.tag)
        ]);
        await page.waitForFunction(() => window.pageEditorReady);
        assert.ok(!(await page.locator('#status').textContent()).includes('(PAGE_'), 'loading an intact draft succeeds');
        assert.equal(await page.locator('html').getAttribute('lang'), locale.tag);
        await page.locator('#selection').selectOption({index: 2});
        const labels = await page.locator('#styles .gjs-sm-property__padding-top .gjs-sm-label').textContent();
        assert.ok(labels.trim() && !labels.includes('Padding top'), locale.tag + ': translated composite property');
        if (locale.tag === 'zh-CN') await page.screenshot({path: path.join(work, 'editor-design-zh.png'), fullPage: true});
    }
    await Promise.all([
        page.waitForEvent('framenavigated', {predicate: frame => frame === page.mainFrame()}),
        page.locator('.pixiv-lang-switcher__select').selectOption('en-US')
    ]);
    await page.waitForFunction(() => window.pageEditorReady);
}
try {
    if (phase === 'unchanged') {
        await checkEditorLayout();
        const defaultLogin = async () => {
            const projection = page.waitForResponse(response => response.request().method() === 'GET'
                && response.url().includes('/api/web/page-publications/'));
            await login();
            assert.equal((await projection).status(), 404);
            assert.equal(await page.locator('#default-page').isVisible(), false);
            assert.equal(await page.locator('#custom-page > *').count(), 0);
            assert.equal(await page.locator('.center').evaluate(node => node.parentElement.id === 'native-page'), true);
        };
        await defaultLogin();
        await editor();
        await page.locator('#save').click(); await waitStatus('Draft saved');
        await defaultLogin();
        await editor();
        await page.locator('#publish').click(); await clickConfirm(); await waitStatus('Page published');
        const source = (await documentState()).published;
        assert.ok(source.project.pages[0].frames[0].component.attributes.id, 'actual engine-generated IDs');
        await defaultLogin();
        await defaultLogin();
        await page.screenshot({path: path.join(work, 'login-unchanged-wide.png'), fullPage: true});
        await page.setViewportSize({width: 390, height: 844});
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
        assert.equal(await page.locator('#default-page').isVisible(), false);
        await page.screenshot({path: path.join(work, 'login-unchanged-mobile.png'), fullPage: true});
        await page.setViewportSize({width: 1440, height: 1000});
        await editor();
        assert.deepEqual((await documentState()).published, source, 'default source is preserved');
        await page.locator('#page-title').fill('Custom login title');
        await page.locator('#publish').click(); await clickConfirm(); await waitStatus('Page published');
        await login();
        await page.locator('#default-page').waitFor({state: 'visible'});
        assert.equal(await page.title(), 'Custom login title');
        await page.locator('#default-page').click();
        await page.locator('#default-page').waitFor({state: 'hidden'});
        assert.equal(await page.locator('.center').evaluate(node => node.parentElement.id === 'native-page'), true);
        await editor();
        await restore();
        assert.deepEqual((await documentState()).published, source, 'restore keeps the original default document');
        await defaultLogin();
    } else if (phase === 'incomplete') {
        const submitted = [];
        page.on('request', request => { if (request.method() === 'POST' && request.url().includes('/api/auth/')) submitted.push(request.url()); });
        await editor();
        const before = await documentState();
        await page.locator('#recovery').waitFor();
        assert.equal(await page.locator('#save').isDisabled(), true);
        assert.equal(await page.locator('#publish').isDisabled(), true);
        const preview = page.frameLocator('#preview-root');
        await preview.locator('input[type=password]').waitFor();
        assert.equal(await preview.locator('input[type=password]').evaluate(node => Boolean(node.closest('[inert]'))), true);
        await page.screenshot({path: path.join(work, 'editor-recovery.png'), fullPage: true});
        await page.locator('#repair').click();
        await page.frameLocator('iframe.gjs-frame').locator('input[type=password]').waitFor();
        await waitStatus('Unsaved changes');
        assert.equal(await page.locator('#recovery').isVisible(), false);
        assert.equal(await page.locator('#save').isEnabled(), true);
        assert.deepEqual(await documentState(), before, 'adopting the default only changes the unsaved canvas');
        let pending;
        const interception = '**/api/page-editor/documents/*/preview';
        const waiting = new Promise(resolve => page.route(interception, route => { pending = route; resolve(); }));
        await page.locator('#preview').click();
        await waiting;
        await page.locator('#close-preview').click();
        await page.waitForFunction(() => !document.getElementById('preview').disabled);
        await pending.abort().catch(() => {});
        await page.unroute(interception);
        assert.equal(await page.locator('#preview-panel').isVisible(), false, 'an in-flight preview can be cancelled');
        await page.locator('#preview').click();
        await preview.locator('input[type=password]').waitFor();
        await page.locator('#close-preview').press('Escape');
        await page.waitForFunction(() => document.activeElement.id === 'preview');
        assert.equal(await page.locator('#preview-panel').isVisible(), false);
        assert.deepEqual(submitted, []);
    } else if (phase === 'live') {
        await editor();
        assert.equal(await page.locator('[data-component-block="page-checklist:list"]').count(), 0);
        fs.writeFileSync(path.join(work, 'live-ready'), 'ready');
        await page.locator('#add-tab').click();
        const block = page.getByRole('button', {name: 'Checklist', exact: true});
        await block.waitFor({timeout: 30000}); await block.press('Enter');
        await page.getByLabel('Checklist title', {exact: true}).waitFor();
        const frame = page.frameLocator('iframe.gjs-frame');
        const mounted = await frame.locator('[data-tick]').elementHandle();
        fs.writeFileSync(path.join(work, 'live-discovered'), 'discovered');
        await block.waitFor({state: 'detached', timeout: 15000});
        await page.waitForFunction(element => element.dataset.disposed === 'true', mounted);
        const ticks = await mounted.evaluate(element => element.dataset.tick);
        await page.waitForTimeout(160);
        assert.equal(await mounted.evaluate(element => element.dataset.tick), ticks, 'withdrawal stops timers');
        fs.writeFileSync(path.join(work, 'live-withdrawn'), 'withdrawn');
        await block.waitFor({timeout: 15000});
        await page.getByLabel('Checklist title', {exact: true}).waitFor();
        await frame.getByRole('heading', {name: 'Checklist', exact: true}).waitFor();
    } else if (phase === 'active') {
        await editor();
        await page.locator('#add-tab').click();
        const checklist = page.getByRole('button', {name: 'Checklist', exact: true});
        await checklist.waitFor();
        const frame = page.frameLocator('iframe.gjs-frame');
        await frame.locator('[data-page-region="password"]').waitFor();
        const blockBox = await checklist.boundingBox();
        const fieldBox = await frame.locator('[data-page-region="password"]').boundingBox();
        await page.mouse.move(blockBox.x + blockBox.width / 2, blockBox.y + blockBox.height / 2);
        await page.mouse.down();
        await page.mouse.move(fieldBox.x + 30, fieldBox.y + fieldBox.height - 2, {steps: 20});
        await page.mouse.move(fieldBox.x + 35, fieldBox.y + fieldBox.height - 1, {steps: 5});
        await page.mouse.up();
        await frame.getByRole('heading', {name: 'Checklist', exact: true}).waitFor({timeout: 5000});
        await page.getByLabel('Checklist title', {exact: true}).fill('First list');
        await page.getByLabel('Checklist title', {exact: true}).press('Tab');
        await page.getByRole('button', {name: 'Add item', exact: true}).click();
        assert.equal(await page.locator('#properties').getByRole('textbox').count(), 3);
        await checklist.focus(); await checklist.press('Enter');
        await page.getByLabel('Checklist title', {exact: true}).fill('Second list');
        await page.getByLabel('Checklist title', {exact: true}).press('Tab');
        const instanceLabels = (await page.locator('#selection option').allTextContents()).filter(text => text.includes('Checklist'));
        assert.equal(instanceLabels.length, 2);
        assert.equal(new Set(instanceLabels).size, 2, 'component instances have distinct selection labels');
        await page.locator('#properties input[type="checkbox"]').first().check();
        await checklist.focus(); await checklist.press('Enter');
        await page.getByLabel('Checklist title', {exact: true}).fill('Third list');
        await page.getByLabel('Checklist title', {exact: true}).press('Tab');
        const containerBlock = page.getByRole('button', {name: 'Container', exact: true});
        await containerBlock.press('Enter');
        await page.waitForFunction(() => document.querySelector('.gjs-sm-property__gap input')?.value === '16');
        await page.locator('.gjs-sm-property__gap input').fill('12');
        await page.locator('.gjs-sm-property__gap input').press('Tab');
        await page.locator('#viewport').selectOption('Mobile');
        await page.locator('.gjs-sm-property__gap input').fill('28');
        await page.locator('.gjs-sm-property__gap input').press('Tab');
        await page.locator('#viewport').selectOption('Desktop');
        await page.getByRole('button', {name: 'Text', exact: true}).press('Enter');
        await page.locator('#properties textarea').fill('Layout note');
        await page.locator('#properties textarea').press('Tab');
        await page.locator('#outdent').click();
        await page.locator('#nest').click();
        await page.locator('#delete').click();
        assert.equal(await frame.getByText('Layout note', {exact: true}).count(), 0);
        await page.locator('#undo').click();
        await frame.getByText('Layout note', {exact: true}).waitFor();
        await checkCssEditor(page, work, async factor => {
            const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
            return worker.evaluate(async ({factor, origin}) => {
                const tab = (await chrome.tabs.query({})).find(tab => tab.url.startsWith(origin));
                await chrome.tabs.setZoom(tab.id, factor); return chrome.tabs.getZoom(tab.id);
            }, {factor, origin});
        });
        await page.locator('.connections-section > summary').click();
        await page.locator('#to').selectOption(await page.locator('#to option').nth(1).getAttribute('value'));
        await page.locator('#connect button').click();
        await page.locator('#from').selectOption(await page.locator('#from option').nth(1).getAttribute('value'));
        await page.locator('#to').selectOption(await page.locator('#to option').nth(2).getAttribute('value'));
        await page.locator('#connect button').click();
        await selectLayer('page-box', 'header');
        await page.locator('.gjs-sm-property__padding-top input').fill('20');
        await page.locator('.gjs-sm-property__padding-top input').press('Tab');
        await selectLayer('page-box', 'footer');
        await page.locator('#add-tab').click();
        await page.getByRole('button', {name: 'Link', exact: true}).press('Enter');
        await page.locator('#properties textarea').fill('Registration information');
        await page.locator('#properties textarea').press('Tab');
        await page.getByLabel('Link URL', {exact: true}).fill('https://example.org/registration');
        await page.getByLabel('Link URL', {exact: true}).press('Tab');
        await page.locator('#page-settings > summary').click();
        await page.locator('#show-hidden').check();
        await frame.locator('[data-page-region="invite-card"]').waitFor({state: 'visible'});
        await page.locator('#show-hidden').uncheck();
        await frame.locator('[data-page-region="invite-card"]').waitFor({state: 'hidden'});
        await page.locator('#page-title').fill('Published example');
        await page.locator('#save').click(); await waitStatus('Draft saved');
        let state = await documentState();
        const serialized = JSON.stringify(state.draft);
        assert.ok(serialized.includes('First list') && serialized.includes('Second list'));
        const find = node => node && typeof node === 'object' ? [node, ...Object.values(node).flatMap(find)] : [];
        const instances = find(state.draft).filter(node => node.type === 'page-component');
        assert.equal(instances.length, 3); assert.equal(new Set(instances.map(instance => instance.instanceId)).size, 3);
        assert.ok(find(state.draft).some(node => node.type === 'page-box' && node.components?.[0]?.content === 'Layout note'));
        assert.equal(find(state.draft).find(node => node.region === 'admin-card').components.filter(node => node.type === 'page-component').length, 3,
            'both independent components are inside the original login card');
        assert.ok(state.draft.project.styles.some(rule => rule.mediaText.includes('640') && rule.style.gap === '28px'));
        await page.reload(); await page.waitForFunction(() => window.pageEditorReady);
        assert.deepEqual((await documentState()).draft, state.draft);
        await page.locator('#preview').click();
        await page.locator('#preview-panel').waitFor();
        await page.locator('#preview-loading').waitFor({state: 'hidden'});
        assert.equal(await page.locator('#catalog').isVisible(), false);
        assert.equal(await page.locator('#inspector').isVisible(), false);
        await page.frameLocator('#preview-root').locator('button:disabled').nth(1).waitFor();
        assert.equal(await page.frameLocator('#preview-root').locator('button:disabled').count(), 3);
        await page.frameLocator('#preview-root').getByRole('link', {name: 'Registration information'}).waitFor();
        await page.frameLocator('#preview-root').locator('input[type=password]').waitFor();
        assert.equal(await page.frameLocator('#preview-root').getByText('Layout note', {exact: true})
            .evaluate(node => getComputedStyle(node).paddingTop), '18px');
        const appearance = node => ({background: getComputedStyle(node).backgroundColor, color: getComputedStyle(node).color, font: getComputedStyle(node).fontFamily});
        assert.deepEqual(await frame.locator('body').evaluate(appearance), await page.frameLocator('#preview-root').locator('body').evaluate(appearance));
        const previewInstance = await page.frameLocator('#preview-root').locator('[data-tick]').first().elementHandle();
        await page.screenshot({path: path.join(work, 'editor-inline-preview.png'), fullPage: true});
        await page.locator('#viewport').selectOption('Mobile');
        assert.equal(Math.round((await page.locator('#preview-root').boundingBox()).width), 375);
        await page.locator('#viewport').selectOption('Desktop');
        await page.locator('#close-preview').click();
        await page.waitForFunction(node => node.dataset.disposed === 'true', previewInstance);
        const previewTick = await previewInstance.evaluate(node => node.dataset.tick);
        await page.waitForTimeout(160);
        assert.equal(await previewInstance.evaluate(node => node.dataset.tick), previewTick, 'closing preview stops its timers');
        assert.equal(await page.locator('#catalog').isVisible(), true);
        await page.locator('#publish').click(); await clickConfirm(); await waitStatus('Page published');
        const saved = await documentState();
        await page.locator('#document-more > summary').click();
        await page.locator('#defaults').click(); await waitStatus('Unsaved changes');
        assert.equal(await frame.getByRole('heading', {name: 'First list', exact: true}).count(), 0);
        assert.deepEqual(await documentState(), saved, 'resetting the canvas neither saves nor publishes');
        await page.locator('#document-more > summary').click();
        await page.locator('#reload').click(); await clickConfirm(); await waitStatus('Draft loaded');
        await login();
        await page.getByRole('heading', {name: 'First list', exact: true}).waitFor();
        assert.equal(await page.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node).paddingTop), '18px');
        const controls = await page.locator('#default-page').evaluate(node => {
            const language = document.querySelector('.pixiv-lang-switcher__select');
            return {font: getComputedStyle(node).fontSize, weight: getComputedStyle(node).fontWeight,
                languageFont: getComputedStyle(language).fontSize, height: node.getBoundingClientRect().height,
                languageHeight: language.getBoundingClientRect().height};
        });
        assert.equal(controls.font, controls.languageFont);
        assert.equal(controls.weight, '500');
        assert.ok(Math.abs(controls.height - controls.languageHeight) <= 2, JSON.stringify(controls));
        assert.equal(await page.title(), 'Published example');
        assert.equal(await page.locator('#custom-page header').evaluate(node => getComputedStyle(node).paddingTop), '20px');
        await page.locator('#loginCard').getByRole('heading', {name: 'First list', exact: true}).waitFor();
        const footer = page.locator('#custom-page footer');
        await footer.getByRole('link', {name: 'Registration information'}).waitFor();
        assert.equal(await footer.locator('a').getAttribute('href'), 'https://example.org/registration');
        assert.ok(await footer.evaluate(node => {
            const box = node.getBoundingClientRect(), main = document.querySelector('#custom-page main').getBoundingClientRect();
            return box.top >= main.bottom - 2 && box.bottom >= innerHeight - 2;
        }), 'footer follows content and reaches the bottom on short pages');
        const verify = page.getByRole('button', {name: 'Verify checklist', exact: true});
        await verify.nth(0).click();
        await page.waitForFunction(() => document.querySelector('#custom-page output')?.textContent.length > 0);
        assert.notEqual(await page.locator('#custom-page output').first().textContent(), '0 / 2');
        await context.addCookies([{name: cookie[0], value: cookie.slice(1).join('='), url: origin}]);
        await verify.nth(0).click();
        await page.waitForFunction(() => document.querySelectorAll('#custom-page output')[1]?.textContent === '{"checked":0,"total":2}');
        await page.waitForFunction(() => document.querySelectorAll('#custom-page output')[2]?.textContent === '{"checked":0,"total":2}');
        await verify.nth(1).click();
        await page.waitForFunction(() => [...document.querySelectorAll('#custom-page output')].map(node => node.textContent).join(',') === '0 / 2,1 / 1,{"checked":1,"total":1}');
        await page.locator('#useInviteCode').click();
        await page.locator('#invite-btn').waitFor({state: 'visible'});
        await page.locator('#backToLogin').click();
        await page.locator('#login-btn').waitFor({state: 'visible'});
        await page.setViewportSize({width: 390, height: 844});
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
        await page.waitForFunction(() => getComputedStyle([...document.querySelectorAll('#custom-page p')]
            .find(node => node.textContent === 'Layout note')).paddingTop === '6px');
        assert.equal(await page.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node).paddingTop), '6px');
        await page.waitForFunction(() => getComputedStyle([...document.querySelectorAll('#custom-page p')]
            .find(node => node.textContent === 'Layout note').parentElement).gap === '28px');
        assert.equal(await page.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node.parentElement).gap), '28px');
        await page.screenshot({path: path.join(work, 'login-mobile.png'), fullPage: true});
        await page.locator('#default-page').click();
        assert.equal(await page.locator('#custom-page').textContent(), '');
        await page.locator('#login-btn').waitFor({state: 'visible'});
        assert.equal(await page.locator('#loginCard input').count(), 3, 'fallback restores original controls');
        assert.equal(await page.locator('#loginCard').getByRole('heading', {name: 'First list', exact: true}).count(), 0);
        await page.setViewportSize({width: 1440, height: 1000});
        await editor();
        await page.locator('#add-tab').click();
        await page.getByRole('button', {name: 'Text', exact: true}).focus();
        await page.getByRole('button', {name: 'Text', exact: true}).press('Enter');
        await page.locator('#properties textarea').fill('A changed publication');
        await page.locator('#properties textarea').press('Tab');
        await page.locator('#publish').click(); await clickConfirm(); await waitStatus('Page published');
        await restore();
        assert.ok(!JSON.stringify((await documentState()).draft).includes('A changed publication'));
        await page.screenshot({path: path.join(work, 'editor-wide.png'), fullPage: true});
        await page.setViewportSize({width: 390, height: 844});
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
        await page.screenshot({path: path.join(work, 'editor-mobile.png'), fullPage: true});
        await page.setViewportSize({width: 1440, height: 720});
        await page.evaluate(() => document.documentElement.style.fontSize = '200%');
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
        assert.ok(await page.locator('#canvas-area').evaluate(node =>
            node.getBoundingClientRect().width >= Math.min(innerWidth - 64, parseFloat(getComputedStyle(document.documentElement).fontSize) * 16)),
        'larger text must leave a usable canvas width');
        await page.screenshot({path: path.join(work, 'editor-font-200.png'), fullPage: true});
        await page.evaluate(() => document.documentElement.style.fontSize = '');
        const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
        const zoom = async factor => worker.evaluate(async ({factor, origin}) => {
            const tab = (await chrome.tabs.query({})).find(tab => tab.url.startsWith(origin));
            await chrome.tabs.setZoom(tab.id, factor); return chrome.tabs.getZoom(tab.id);
        }, {factor, origin});
        assert.equal(await zoom(2), 2);
        await page.waitForFunction(() => innerWidth <= 720);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
        const capture = await (await context.newCDPSession(page)).send('Page.captureScreenshot', {format: 'png', fromSurface: true});
        fs.writeFileSync(path.join(work, 'editor-zoom-200.png'), Buffer.from(capture.data, 'base64'));
        console.log('PAGE_EDITOR_ZOOM', await page.evaluate(() => ({width: innerWidth, height: innerHeight, dpr: devicePixelRatio})), 2);
        await zoom(1);
        await page.locator('.pixiv-theme-toggle').click();
        await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
        const canvasColors = await frame.locator('body').evaluate(node => ({text: getComputedStyle(node).color, background: getComputedStyle(node).backgroundColor}));
        assert.notEqual(canvasColors.text, 'rgb(0, 0, 0)', 'canvas inherits dark theme');
        await page.screenshot({path: path.join(work, 'editor-dark.png'), fullPage: true});
        await page.emulateMedia({forcedColors: 'active', reducedMotion: 'reduce'});
        await page.locator('#save').focus(); await page.locator('#save').press('Tab');
        assert.equal(await page.evaluate(() => document.activeElement.id), 'preview');
        await page.screenshot({path: path.join(work, 'editor-contrast.png'), fullPage: true});
    } else if (phase === 'missing') {
        await editor();
        const before = (await documentState()).draft;
        await page.locator('#page-title').fill('Missing component kept');
        await page.locator('#save').click(); await waitStatus('Draft saved');
        assert.deepEqual((await documentState()).draft.project, before.project);
        await login();
        assert.equal(await page.locator('#custom-page').textContent(), '');
    } else if (phase === 'restored') {
        await login();
        await page.getByRole('heading', {name: 'First list', exact: true}).waitFor();
        await context.addCookies([{name: cookie[0], value: cookie.slice(1).join('='), url: origin}]);
        await page.getByRole('button', {name: 'Verify checklist', exact: true}).first().click();
        await page.waitForFunction(() => document.querySelector('#custom-page output')?.textContent === '0 / 2');
    } else if (phase === 'default' || phase === 'corrupt') {
        await login();
        assert.equal(await page.locator('#custom-page').textContent(), '');
        await page.locator('#username').fill('acceptance');
        await page.locator('#password').fill('wrong');
        await page.locator('#login-btn').click();
        await page.waitForFunction(() => document.getElementById('error-msg').textContent.length > 0);
    } else throw new Error('unknown phase');
    assert.deepEqual(violations.filter(value => !value.blocked.includes('.kaspersky-labs.com/')), [], 'product CSP violations');
    assert.deepEqual(errors, [], 'browser errors');
    const productRequests = requests.filter(url => !url.includes('.kaspersky-labs.com/'));
    assert.deepEqual(productRequests, [], 'product must not use CDN or telemetry');
    fs.writeFileSync(path.join(work, 'browser-' + phase + '.json'), JSON.stringify({phase, violations, errors, externalRequests: requests}, null, 2), 'utf8');
    console.log('PAGE_EDITOR_BROWSER_' + phase.toUpperCase() + '_PASSED');
} catch (failure) {
    fs.writeFileSync(path.join(work, 'failure-' + phase + '.json'), JSON.stringify(await page.evaluate(() => {
        const editor = window.grapesjs?.editors[0];
        return {selected: editor?.getSelected()?.toJSON(), styles: editor?.getProjectData().styles,
            gap: document.querySelector('.gjs-sm-property__gap input')?.value};
    }), null, 2), 'utf8');
    fs.writeFileSync(path.join(work, 'failure-' + phase + '.html'), await page.content(), 'utf8');
    for (let index = 0; index < page.frames().length; index++) fs.writeFileSync(path.join(work, 'failure-' + phase + '-frame-' + index + '.html'), await page.frames()[index].content(), 'utf8');
    await page.screenshot({path: path.join(work, 'failure-' + phase + '.png'), fullPage: true}).catch(() => {});
    console.error('PAGE_BROWSER_FAILURE', failure, {violations, errors, networkErrors, consoleErrors, url: page.url()});
    throw failure;
} finally { await context.close(); }
