import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT
    ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const browser = await chromium.launch({headless: true,
    ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const page = await browser.newPage({viewport: {width: 390, height: 844}, locale: 'en-US'});
const origin = process.env.PAGE_ORIGIN;
const errors = [], violations = [];
const requests = [], consoleErrors = [];
page.on('response', response => requests.push({url: response.url(), status: response.status()}));
page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
page.on('pageerror', error => errors.push(error.message));
await page.exposeFunction('recordCsp', event => violations.push(event));
await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event =>
    window.recordCsp({directive: event.violatedDirective, blocked: event.blockedURI})));
try {
    await page.goto(origin + '/login.html?redirect=/api/auth/check');
    await page.locator('#username').fill('acceptance');
    await page.locator('#password').fill('Isolated-acceptance-password-2026');
    await page.locator('#login-btn').click();
    await page.locator('#neutral-proof').waitFor({state: 'visible'});
    assert.equal((await page.request.get(origin + '/api/auth/check')).status(), 200);
    assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, false);
    assert.deepEqual(Object.keys(await page.evaluate(() => window.neutralProofProperties)).sort(), ['challenges', 'verificationToken']);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    if (process.env.PAGE_SCREENSHOT) await page.screenshot({path: process.env.PAGE_SCREENSHOT, fullPage: true});
    await page.locator('#neutral-proof').focus(); await page.locator('#neutral-proof').press('Enter');
    const success = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 200);
    await page.locator('#login-btn').click(); await success;
    assert.equal((await (await page.request.get(origin + '/api/auth/check')).json()).valid, true);
    assert.deepEqual(errors, []);
    assert.deepEqual(violations.filter(event => !event.blocked.includes('.kaspersky-labs.com/')), []);
    console.log('LOGIN_VERIFICATION_BROWSER_PASSED');
} catch (error) {
    console.error('LOGIN_BROWSER_FAILURE', {errors, violations, requests, consoleErrors,
        page: await page.evaluate(() => ({url: location.href, error: document.getElementById('error-msg')?.textContent,
            properties: window.neutralProofProperties, buttons: document.querySelectorAll('#neutral-proof').length}))});
    throw error;
} finally { await browser.close(); }
