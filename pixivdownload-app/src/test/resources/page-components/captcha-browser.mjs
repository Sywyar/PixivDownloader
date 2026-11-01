import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [method, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const offline = method.startsWith('altcha');
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
let page;
try {
    const context = await browser.newContext({locale: 'en-US'}), blocked = [], errors = [], violations = [];
    const [name, ...value] = process.env.PAGE_COOKIE.split('=');
    await context.addCookies([{name, value: value.join('='), url: origin}, {name: 'pixiv_lang', value: 'en-US', url: origin}]);
    const track = async context => {
        if (offline) await context.route('**/*', route => {
            if (new URL(route.request().url()).origin !== origin) { blocked.push(new URL(route.request().url()).origin); return route.abort(); }
            return route.continue();
        });
        await context.addInitScript(() => {
            const OriginalWorker = window.Worker;
            window.captchaWorkers = {created: 0, terminated: 0};
            window.Worker = class extends OriginalWorker {
                constructor(...args) { super(...args); window.captchaWorkers.created++; }
                set onmessage(callback) {
                    super.onmessage = event => {
                        if (window.deferCaptchaResult) window.deferredCaptchaSolution = event.data;
                        else callback(event);
                    };
                }
                terminate() {
                    if (!this.finished) { this.finished = true; window.captchaWorkers.terminated++; window.recordWorkerTermination?.(); }
                    super.terminate();
                }
            };
        });
    };
    await track(context);
    page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin + '/page-editor/index.html');
    await page.waitForFunction(() => window.pageEditorReady);
    if (await page.locator('#page').inputValue() !== 'core:login') {
        const loaded = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/core:login/preview'));
        await page.locator('#page').selectOption('core:login'); await loaded;
    }
    const frame = page.frameLocator('iframe.gjs-frame');
    await frame.locator('[data-page-region="admin-card"]').waitFor();
    await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').forEach(model => model.remove()));
    assert.equal(await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').length), 0);
    await page.locator('#add-tab').click();
    const block = page.getByRole('button', {name: {altcha: 'ALTCHA offline verification',
        'altcha-pbkdf2': 'ALTCHA PBKDF2 offline verification', turnstile: 'Turnstile online verification'}[method], exact: true});
    await block.scrollIntoViewIfNeeded();
    const source = await block.boundingBox(), destination = await frame.locator('[data-page-region="admin-card"]').boundingBox();
    assert.ok(source && destination);
    await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2); await page.mouse.down();
    await page.mouse.move(destination.x + 30, destination.y + destination.height / 2, {steps: 25});
    await page.mouse.move(destination.x + 35, destination.y + destination.height / 2, {steps: 5});
    await page.waitForFunction(() => window.grapesjs.editors[0].Canvas.getSpots({type: 'target'}).some(spot => spot.get('component')?.get('region') === 'admin-card'));
    await page.mouse.up();
    await page.waitForFunction(() => window.grapesjs.editors[0].getWrapper().findType('page-component').length === 1);
    assert.equal(await page.getByLabel('Protected operation', {exact: true}).inputValue(), 'login.verification');
    const published = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/core:login/publish'));
    await page.locator('#publish').click();
    const dialog = page.locator('.pixiv-feedback-backdrop[open]');
    await dialog.waitFor();
    assert.ok((await dialog.innerText()).includes(offline ? 'self' : 'https://challenges.cloudflare.com'), 'publisher sees the exact resource approval');
    await dialog.locator('.pixiv-feedback-button--primary').click();
    assert.equal((await published).status(), 200);
    const anonymous = await browser.newContext({locale: 'en-US', viewport: {width: 390, height: 844}, colorScheme: 'dark'});
    await anonymous.addCookies([{name: 'pixiv_lang', value: 'en-US', url: origin}]);
    await track(anonymous);
    const deniedScripts = 'https://challenges.cloudflare.com/**';
    if (!offline) await anonymous.route(deniedScripts, route => route.abort());
    page = await anonymous.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.exposeFunction('recordCsp', value => violations.push(value));
    await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event =>
        window.recordCsp({directive: event.violatedDirective, blocked: event.blockedURI})));
    const document = await page.goto(origin + '/login.html?redirect=/api/auth/check');
    const csp = document.headers()['content-security-policy'];
    assert.ok(csp.includes(offline ? "worker-src 'self'" : 'https://challenges.cloudflare.com'));
    await page.locator('#username').fill('acceptance');
    await page.locator('#password').fill('Isolated-page-password-2026');
    const challenge = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 409);
    await page.locator('#login-btn').click(); await challenge;
    assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, false);
    if (!offline) {
        await page.getByText('Verification failed. Submit again for a new challenge.', {exact: true}).waitFor();
        assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, false);
        await anonymous.unroute(deniedScripts);
        await page.reload();
        await page.locator('#username').fill('acceptance');
        await page.locator('#password').fill('Isolated-page-password-2026');
        const retry = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 409);
        await page.locator('#login-btn').click(); await retry;
    }
    if (offline) await page.getByRole('button', {name: 'Start verification', exact: true}).press('Enter');
    await page.getByText('Verification complete. Submit again to continue.', {exact: true}).waitFor({timeout: 30000});
    if (offline) assert.deepEqual(await page.evaluate(() => window.captchaWorkers), {created: 1, terminated: 1});
    await page.screenshot({path: path.join(work, 'captcha-' + method + '.png'), fullPage: true});
    const success = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 200);
    await page.locator('#login-btn').click();
    const submitted = (await success).request().postDataJSON();
    assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, true);
    for (const endpoint of ['/api/web/page-publications/core:login', '/api/page-editor/catalog', '/api/page-editor/documents/core:login']) {
        const response = await context.request.get(origin + endpoint);
        assert.equal(response.status(), 200);
        const body = await response.text();
        for (const secret of [submitted.password, submitted.operationToken, ...Object.values(submitted.operationProofs)]) {
            assert.ok(secret && !body.includes(secret), 'public projection and editor values omit actual challenge tokens and proofs');
        }
    }
    if (method === 'altcha') {
        const staleContext = await browser.newContext({locale: 'en-US'});
        await staleContext.addCookies([{name: 'pixiv_lang', value: 'en-US', url: origin}]);
        await track(staleContext);
        const stalePage = await staleContext.newPage();
        let terminated = 0;
        await stalePage.exposeFunction('recordWorkerTermination', () => terminated++);
        const oldDocument = await stalePage.goto(origin + '/login.html');
        await stalePage.locator('#username').fill('acceptance');
        await stalePage.locator('#password').fill('Isolated-page-password-2026');
        const challengeResponse = stalePage.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 409);
        await stalePage.locator('#login-btn').click();
        const oldChallenge = await (await challengeResponse).json();
        // 延迟真实 Worker 的结果交付，复现发布发生时仍在等待的实例。
        await stalePage.evaluate(() => { window.deferCaptchaResult = true; });
        await stalePage.getByRole('button', {name: 'Start verification', exact: true}).click();
        await stalePage.waitForFunction(() => window.deferredCaptchaSolution);
        const solution = await stalePage.evaluate(() => window.deferredCaptchaSolution);
        assert.deepEqual(await stalePage.evaluate(() => window.captchaWorkers), {created: 1, terminated: 0});
        const editorPage = context.pages()[0];
        await editorPage.evaluate(() => {
            const editor = window.grapesjs.editors[0], region = editor.getWrapper().findType('page-region').find(model => model.get('region') === 'admin-card');
            editor.getWrapper().findType('page-component').forEach(model => model.remove());
            region.append(editor.BlockManager.get('captcha-example:turnstile').get('content'));
        });
        const navigation = stalePage.waitForNavigation({waitUntil: 'domcontentloaded'});
        const replacement = editorPage.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/core:login/publish'));
        await editorPage.locator('#publish').click();
        await editorPage.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
        assert.equal((await replacement).status(), 200);
        const newDocument = await navigation;
        assert.equal(terminated, 1, 'revision replacement terminates the actual Worker before navigating');
        assert.notEqual(newDocument.headers().etag, oldDocument.headers().etag);
        assert.ok(newDocument.headers()['content-security-policy'].includes('https://challenges.cloudflare.com'));
        assert.notEqual(newDocument.headers()['x-pixiv-page-revision'], oldDocument.headers()['x-pixiv-page-revision']);
        const proof = Buffer.from(JSON.stringify({challenge: JSON.parse(oldChallenge.challenges['captcha-altcha']), solution})).toString('base64');
        const rejected = await stalePage.request.post(origin + '/api/auth/login', {data: {
            username: 'acceptance', password: 'Isolated-page-password-2026', operationToken: oldChallenge.token,
            operationProofs: {'captcha-altcha': proof}
        }});
        assert.equal(rejected.status(), 400, 'withdrawn policy is not accepted by the current request contract');
        const stale = await stalePage.request.post(origin + '/api/auth/login', {data: {
            username: 'acceptance', password: 'Isolated-page-password-2026', operationToken: oldChallenge.token,
            operationProofs: {'captcha-turnstile': proof}
        }});
        assert.equal(stale.status(), 409, 'old token cannot cross the formal revision even with current policy keys');
        assert.equal(stale.headers()['set-cookie'], undefined);
        assert.equal((await (await stalePage.request.get(origin + '/api/auth/check')).json()).valid, false);
        await staleContext.close();
        console.log('CAPTCHA_REVISION_CSP_NAVIGATION_AND_WORKER_CLEANUP_PASSED');
    }
    assert.deepEqual(errors, []);
    assert.deepEqual(violations.filter(value => !value.blocked.includes('.kaspersky-labs.com/')), []);
    assert.deepEqual(blocked.filter(value => !value.includes('.kaspersky-labs.com')), [], 'offline mode attempts no third-party browser requests');
    fs.writeFileSync(path.join(work, 'captcha-' + method + '.json'), JSON.stringify({method, csp, blocked, violations, errors, login: true}, null, 2));
    console.log('CAPTCHA_BROWSER_PASSED ' + method);
    await anonymous.close(); await context.close();
} catch (error) {
    if (page) {
        await page.screenshot({path: path.join(work, 'captcha-failure.png'), fullPage: true});
        console.error('CAPTCHA_BROWSER_FAILURE', await page.evaluate(() => ({
            url: location.pathname, status: document.querySelector('#status')?.textContent,
            error: document.querySelector('#error-msg')?.textContent,
            messages: [...document.querySelectorAll('[role="status"]')].map(node => node.textContent)
        })));
    }
    throw error;
} finally { await browser.close(); }
