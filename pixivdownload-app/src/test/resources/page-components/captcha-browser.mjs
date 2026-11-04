import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [method, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const pow = method.startsWith('altcha'), localImage = ['text', 'slider'].includes(method);
const offline = pow || localImage;
const remoteOrigin = method === 'recaptcha' ? 'https://www.google.com' : 'https://challenges.cloudflare.com';
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
let page;
try {
    const context = await browser.newContext({locale: 'en-US', viewport: {width: 1920, height: 1080}}), blocked = [], errors = [], violations = [];
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
    const labels = await page.evaluate(async () => {
        const i18n = await window.PixivI18n.create({namespaces: ['page-editor']});
        return {target: i18n.t('page-editor:binding-target'), self: i18n.t('page-editor:publication-self')};
    });
    if (await page.locator('#page').inputValue() !== 'core:login') {
        const loaded = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/core:login/preview'));
        await page.locator('#page').selectOption('core:login'); await loaded;
    }
    const frame = page.frameLocator('iframe.gjs-frame');
    await frame.locator('[data-page-region="admin-card"]').waitFor();
    await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').forEach(model => model.remove()));
    assert.equal(await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').length), 0);
    await page.locator('#add-tab').click();
    const captchaGroup = page.locator('#blocks .gjs-block-category').filter({
        has: page.locator('[data-component-block="captcha:altcha"]')
    });
    assert.equal((await captchaGroup.locator('.gjs-title').innerText()).trim(), 'CAPTCHA');
    assert.equal(await captchaGroup.locator('.gjs-block').count(), 6);
    assert.equal(await page.locator('[data-component-block^="captcha-example:"]').count(), 0);
    const block = page.locator('[data-component-block="captcha:' + method + '"]');
    assert.ok((await block.innerText()).trim(), 'the component has a visible localized label');
    await block.scrollIntoViewIfNeeded();
    const source = await block.boundingBox(), destination = await frame.locator('[data-page-region="admin-card"]').boundingBox();
    assert.ok(source && destination);
    await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2); await page.mouse.down();
    await page.mouse.move(destination.x + 30, destination.y + destination.height / 2, {steps: 25});
    await page.mouse.move(destination.x + 35, destination.y + destination.height / 2, {steps: 5});
    await page.waitForFunction(() => window.grapesjs.editors[0].Canvas.getSpots({type: 'target'}).some(spot => spot.get('component')?.get('region') === 'admin-card'));
    await page.mouse.up();
    await page.waitForFunction(() => window.grapesjs.editors[0].getWrapper().findType('page-component').length === 1);
    assert.equal(await page.getByLabel(labels.target, {exact: true}).inputValue(), 'login.verification');
    await frame.locator('.captcha-button').waitFor();
    assert.equal(await frame.locator('.captcha-status').innerText(), '', 'editor preview has no repeated instructions');
    assert.equal(await frame.locator('.captcha-button').isDisabled(), true);
    await page.evaluate(selected => {
        const editor = window.grapesjs.editors[0];
        const region = editor.getWrapper().findType('page-region').find(model => model.get('region') === 'admin-card');
        for (const method of ['altcha', 'altcha-pbkdf2', 'turnstile']) {
            if (method !== selected) region.append(editor.BlockManager.get('captcha:' + method).get('content'));
        }
    }, method);
    await frame.locator('.captcha-button').nth(2).waitFor();
    assert.ok((await frame.locator('.captcha-status').allTextContents()).every(value => value === ''));
    await page.screenshot({path: path.join(work, 'captcha-editor-' + method + '.png'), fullPage: true, animations: 'disabled'});
    await page.evaluate(selected => window.grapesjs.editors[0].getWrapper().findType('page-component')
        .filter(model => model.get('componentType') !== 'captcha:' + selected).forEach(model => model.remove()), method);
    const published = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/core:login/publish'));
    await page.locator('#publish').click();
    const dialog = page.locator('.pixiv-feedback-backdrop[open]');
    await dialog.waitFor();
    const approval = await dialog.innerText();
    if (localImage) assert.ok(!approval.includes('https://'), 'local images require no third-party resource approval');
    else assert.ok(approval.includes(pow ? labels.self : remoteOrigin), 'publisher sees the exact resource approval');
    await dialog.locator('.pixiv-feedback-button--primary').click();
    assert.equal((await published).status(), 200);
    const anonymous = await browser.newContext({locale: 'en-US', viewport: {width: 390, height: 844}, colorScheme: 'dark'});
    await anonymous.addCookies([{name: 'pixiv_lang', value: 'en-US', url: origin}]);
    await anonymous.addInitScript(() => localStorage.setItem('pixiv_theme', 'dark'));
    await track(anonymous);
    const deniedScripts = remoteOrigin + '/**';
    if (!offline) await anonymous.route(deniedScripts, route => route.abort());
    page = await anonymous.newPage();
    const loginRequests = [], preparations = [];
    page.on('request', request => {
        const path = new URL(request.url()).pathname;
        if (path === '/api/auth/login') loginRequests.push(request);
        if (path === '/api/operation-hooks/prepare') preparations.push(request.postDataJSON());
    });
    page.on('pageerror', error => errors.push(error.message));
    await page.exposeFunction('recordCsp', value => violations.push(value));
    await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event =>
        window.recordCsp({directive: event.violatedDirective, blocked: event.blockedURI})));
    const document = await page.goto(origin + '/login.html?redirect=/api/auth/check');
    const csp = document.headers()['content-security-policy'];
    if (localImage) {
        assert.ok(csp.includes("img-src 'self'"));
        assert.ok(!csp.includes('https://www.google.com') && !csp.includes('https://challenges.cloudflare.com'));
    } else assert.ok(csp.includes(pow ? "worker-src 'self'" : remoteOrigin));
    await page.locator('#username').fill('acceptance');
    await page.locator('#password').fill('Isolated-page-password-2026');
    await page.locator('.captcha-button').waitFor();
    assert.equal(await page.locator('.captcha-status').innerText(), '', 'idle verification has no extra instructions');
    const layout = await page.locator('.captcha-button').evaluate(button => {
        const css = getComputedStyle(button), bounds = button.getBoundingClientRect();
        return {height: bounds.height, radius: parseFloat(css.borderRadius), display: css.display,
            background: css.backgroundColor, pageBackground: getComputedStyle(document.documentElement).getPropertyValue('--control-bg').trim()};
    });
    assert.ok(layout.height >= 44 && layout.radius > 0 && layout.display === 'inline-flex', 'owned stylesheet styles the real page control');
    assert.equal(await page.evaluate(() => window.PixivTheme.current()), 'dark');
    await page.screenshot({path: path.join(work, 'captcha-' + method + '-idle-dark.png'), fullPage: true, animations: 'disabled'});
    await page.setViewportSize({width: 1280, height: 720});
    await page.evaluate(() => window.PixivTheme.apply('light'));
    await page.screenshot({path: path.join(work, 'captcha-' + method + '-idle-light.png'), fullPage: true, animations: 'disabled'});
    await page.setViewportSize({width: 390, height: 844});
    await page.evaluate(() => window.PixivTheme.apply('dark'));
    if (method === 'altcha') {
        await page.setViewportSize({width: 320, height: 568});
        await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '200% text fits the narrow page');
        await page.screenshot({path: path.join(work, 'captcha-large-text.png'), fullPage: true, animations: 'disabled'});
        await page.evaluate(() => { document.documentElement.style.removeProperty('font-size'); });
        await page.emulateMedia({forcedColors: 'active', reducedMotion: 'reduce'});
        await page.locator('.captcha-button').focus();
        assert.equal(await page.locator('.captcha-button').evaluate(button => button.matches(':focus-visible')), true);
        await page.screenshot({path: path.join(work, 'captcha-high-contrast.png'), fullPage: true, animations: 'disabled'});
        await page.emulateMedia({forcedColors: 'none', reducedMotion: 'no-preference'});
        await page.setViewportSize({width: 390, height: 844});
    }
    const challenge = page.waitForResponse(response => response.url().endsWith('/api/operation-hooks/prepare') && response.status() === 409);
    await page.getByRole('button', {name: 'Start verification', exact: true}).press('Enter');
    const preparation = (await challenge).request().postDataJSON();
    assert.equal(preparation.url, '/api/auth/login');
    assert.deepEqual(preparation.json, {});
    assert.deepEqual(preparation.proofs, {});
    assert.ok(!JSON.stringify(preparation).includes('Isolated-page-password-2026'));
    assert.equal(loginRequests.length, 0, 'verification starts without submitting login');
    assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, false);
    if (!offline) {
        await page.locator('.captcha-control[data-state="failed"]').waitFor();
        assert.ok((await page.locator('.captcha-status').innerText()).trim(), 'failure remains visible');
        assert.equal(loginRequests.length, 0);
        assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, false);
        await anonymous.unroute(deniedScripts);
        await page.reload();
        await page.locator('#username').fill('acceptance');
        await page.locator('#password').fill('Isolated-page-password-2026');
        const retry = page.waitForResponse(response => response.url().endsWith('/api/operation-hooks/prepare') && response.status() === 409);
        await page.getByRole('button', {name: 'Start verification', exact: true}).click(); await retry;
    }
    if (localImage) {
        const input = page.locator(method === 'text' ? '.captcha-input' : '.captcha-range');
        await input.waitFor();
        await page.waitForFunction(() => { const input = document.querySelector('.captcha-answer input'); return input && !input.disabled; });
        assert.equal(loginRequests.length, 0);
        await page.locator('#login-btn').click();
        assert.equal(loginRequests.length, 0, 'an unanswered image prevents business submission');
        await page.locator('.captcha-refresh').click();
        await page.waitForFunction(() => { const input = document.querySelector('.captcha-answer input'); return input && !input.disabled; });
        const source = await page.locator(method === 'text' ? '.captcha-picture img' : '.captcha-picture canvas').evaluate(node =>
            node.tagName === 'IMG' ? node.src : performance.getEntriesByType('resource').filter(entry =>
                entry.name.includes('/api/captcha/images/') && entry.name.endsWith('/0')).at(-1).name);
        const id = new URL(source).pathname.split('/').at(-2);
        let answer;
        for (let retry = 0; retry < 100 && answer === undefined; retry++) {
            try { answer = JSON.parse(fs.readFileSync(path.join(work, 'captcha-test-answers.json'), 'utf8'))[id]; } catch {}
            if (answer === undefined) await new Promise(resolve => setTimeout(resolve, 50));
        }
        assert.ok(answer, 'test process supplies the real server answer without exposing it through a product API');
        if (method === 'text') await input.fill(answer.toLowerCase());
        else {
            await input.focus();
            for (let x = 0; x < Number(answer); x++) await input.press('ArrowRight');
        }
        await page.screenshot({path: path.join(work, 'captcha-' + method + '-input-dark.png'), fullPage: true, animations: 'disabled'});
        await page.setViewportSize({width: 1280, height: 900}); await page.evaluate(() => window.PixivTheme.apply('light'));
        await page.screenshot({path: path.join(work, 'captcha-' + method + '-input-light.png'), fullPage: true, animations: 'disabled'});
        await input.press('Enter');
    }
    if (method === 'recaptcha') {
        const frame = page.frameLocator('iframe[title="reCAPTCHA"]');
        await frame.locator('#recaptcha-anchor').click();
    }
    await page.locator('.captcha-control[data-state="ready"]').waitFor({timeout: 30000});
    if (offline) {
        assert.equal(await page.locator('.captcha-control > .captcha-button').isDisabled(), true);
        assert.equal(await page.locator('.captcha-status--quiet').count(), 1, 'completion is announced without a duplicate paragraph');
    }
    assert.equal(loginRequests.length, 0, 'proof completion does not submit the form');
    if (pow) assert.deepEqual(await page.evaluate(() => window.captchaWorkers), {created: 1, terminated: 1});
    await page.screenshot({path: path.join(work, 'captcha-' + method + '.png'), fullPage: true});
    const success = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 200);
    await page.locator('#login-btn').click();
    const submitted = (await success).request().postDataJSON();
    assert.equal(loginRequests.length, 1, 'only the verified business submission reaches login');
    assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, true);
    for (const endpoint of ['/api/web/page-publications/core:login', '/api/page-editor/catalog', '/api/page-editor/documents/core:login']) {
        const response = await context.request.get(origin + endpoint);
        assert.equal(response.status(), 200);
        const body = await response.text();
        const staged = preparations.findLast(value => value.token);
        assert.deepEqual(Object.keys(submitted).sort(), ['password', 'rememberMe', 'username']);
        for (const secret of [submitted.password, staged.token, ...Object.values(staged.proofs)]) {
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
        // 延迟真实 Worker 的结果交付，复现发布发生时仍在等待的实例。
        await stalePage.evaluate(() => { window.deferCaptchaResult = true; });
        const challengeResponse = stalePage.waitForResponse(response => response.url().endsWith('/api/operation-hooks/prepare') && response.status() === 409);
        await stalePage.getByRole('button', {name: 'Start verification', exact: true}).click();
        const oldChallenge = await (await challengeResponse).json();
        await stalePage.waitForFunction(() => window.deferredCaptchaSolution);
        const solution = await stalePage.evaluate(() => window.deferredCaptchaSolution);
        assert.deepEqual(await stalePage.evaluate(() => window.captchaWorkers), {created: 1, terminated: 0});
        const editorPage = context.pages()[0];
        await editorPage.evaluate(() => {
            const editor = window.grapesjs.editors[0], region = editor.getWrapper().findType('page-region').find(model => model.get('region') === 'admin-card');
            editor.getWrapper().findType('page-component').forEach(model => model.remove());
            region.append(editor.BlockManager.get('captcha:turnstile').get('content'));
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
        for (const policy of ['captcha-altcha', 'captcha-turnstile']) {
            const stale = await stalePage.request.post(origin + '/api/operation-hooks/prepare', {headers: {Origin: origin}, data: {
                method: 'POST', url: '/api/auth/login', headers: {'Content-Type': 'application/json'}, token: oldChallenge.token,
                proofs: {[policy]: proof}, json: {}, parameters: {}
            }});
            assert.equal(stale.status(), 409, 'old token cannot cross the formal revision with either old or current policy keys');
            assert.ok(!(stale.headers()['set-cookie'] || '').includes('pixiv_session='));
        }
        const replay = await stalePage.request.post(origin + '/api/auth/login', {headers: {Origin: origin, 'X-Pixiv-Hook-Ticket': oldChallenge.token},
            data: {username: 'acceptance', password: 'Isolated-page-password-2026'}});
        assert.equal(replay.status(), 409);
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
    console.error(error);
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
