import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const fixture = JSON.parse(fs.readFileSync(path.join(work, 'access-fixture.json'), 'utf8'));
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const errors = [];
let page;
try {
    const context = await browser.newContext({locale: 'en-US', viewport: {width: 1920, height: 1080}});
    const [name, ...value] = process.env.PAGE_COOKIE.split('=');
    await context.addCookies([{name, value: value.join('='), url: origin}]);
    page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin + '/page-editor/index.html');
    await page.waitForFunction(() => window.pageEditorReady);
    const targetLabel = await page.evaluate(async () =>
        (await window.PixivI18n.create({namespaces: ['page-editor']})).t('page-editor:binding-target'));
    await page.locator('#page').selectOption('core:login');
    const frame = page.frameLocator('iframe.gjs-frame');
    await frame.locator('[data-page-region="admin-card"]').waitFor();
    await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').forEach(model => model.remove()));
    await page.locator('#show-hidden').check();
    for (const [region, target] of [['admin-card', 'login.verification'], ['invite-card', 'invite.verification']]) {
        await page.locator('#add-tab').click();
        const block = page.locator('[data-component-block="operation-proof:verify"]');
        await block.scrollIntoViewIfNeeded();
        await frame.locator('[data-page-region="' + region + '"]').scrollIntoViewIfNeeded();
        const source = await block.boundingBox(), destination = await frame.locator('[data-page-region="' + region + '"]').boundingBox();
        assert.ok(source && destination);
        await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2); await page.mouse.down();
        await page.mouse.move(destination.x + 30, destination.y + destination.height / 2, {steps: 25});
        await page.mouse.move(destination.x + 35, destination.y + destination.height / 2, {steps: 5});
        await page.waitForFunction(region => window.grapesjs.editors[0].Canvas.getSpots({type: 'target'})
            .some(spot => spot.get('component')?.get('region') === region), region);
        await page.mouse.up();
        assert.equal(await page.getByLabel(targetLabel, {exact: true}).inputValue(), target);
    }
    await page.locator('#preview').click();
    await page.frameLocator('#preview-root').getByRole('button', {name: 'Respond to challenge'}).first().waitFor();
    await page.locator('#close-preview').click();
    const published = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/core:login/publish'));
    await page.locator('#publish').click();
    const dialog = page.locator('.pixiv-feedback-backdrop[open]');
    await dialog.waitFor();
    await dialog.locator('details > summary').click();
    assert.match(await dialog.innerText(), /core:invite-redeem/);
    assert.match(await dialog.innerText(), /core:login/);
    await dialog.locator('.pixiv-feedback-button--primary').click();
    assert.equal((await published).status(), 200);

    const guest = await browser.newContext({locale: 'en-US'});
    await guest.addCookies([{name: 'pixiv_lang', value: 'en-US', url: origin}]);
    page = await guest.newPage(); page.on('pageerror', error => errors.push(error.message));
    const submitted = [];
    page.on('request', request => { if (new URL(request.url()).pathname === '/api/auth/invite-redeem') submitted.push(request); });
    await page.goto(origin + '/invite?code=' + fixture.code);
    await page.locator('#invite-btn:enabled').waitFor();
    assert.equal(await page.locator('#inviteCodeInput').inputValue(), fixture.code);
    assert.ok(!(await guest.cookies()).some(cookie => cookie.name === 'pixiv_invite_token'), 'GET does not establish a guest session');
    await page.locator('#invite-btn').click();
    const proof = page.locator('#inviteCard').getByRole('button', {name: 'Respond to challenge'});
    await proof.waitFor();
    await page.waitForFunction(() => { const button = document.querySelector('#inviteCard [data-page-component] button'); return button && !button.disabled; });
    assert.equal(submitted.length, 0, 'unverified invitation never reaches the business endpoint');
    await proof.click();
    const redeemed = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/invite-redeem');
    await page.locator('#invite-btn').click();
    assert.equal((await redeemed).status(), 200);
    const credential = (await guest.cookies()).find(cookie => cookie.name === 'pixiv_invite_token');
    assert.ok(credential?.httpOnly && credential.sameSite === 'Strict' && credential.value !== fixture.code);
    fs.writeFileSync(path.join(work, 'access-session.json'), JSON.stringify({token: credential.value}));

    const anonymous = await browser.newContext({locale: 'en-US'});
    await anonymous.addCookies([{name: 'pixiv_lang', value: 'en-US', url: origin}]);
    page = await anonymous.newPage(); page.on('pageerror', error => errors.push(error.message));
    const logins = [];
    page.on('request', request => { if (new URL(request.url()).pathname === '/api/auth/login') logins.push(request); });
    await page.goto(origin + '/login.html?redirect=/api/auth/check');
    await page.locator('#username').fill('acceptance'); await page.locator('#password').fill('Isolated-page-password-2026');
    await page.locator('#login-btn:enabled').click();
    const loginProof = page.locator('#loginCard').getByRole('button', {name: 'Respond to challenge'});
    await loginProof.waitFor(); assert.equal(logins.length, 0, 'unverified login does not submit credentials');
    await loginProof.click();
    const loggedIn = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login');
    await page.locator('#login-btn').click(); assert.equal((await loggedIn).status(), 200);
    assert.deepEqual(errors, []);
    console.log('PASS real login and invite: drag, target resolution, preview, approval, frontend proof gating and distinct HttpOnly guest credential');
} catch (error) {
    if (page) await page.screenshot({path: path.join(work, 'access-failure.png'), fullPage: true});
    throw error;
} finally { await browser.close(); }
