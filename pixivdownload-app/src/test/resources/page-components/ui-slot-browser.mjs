import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
import {verifySlotOrder} from './ui-slot-order-browser.mjs';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const context = await browser.newContext({viewport: {width: 390, height: 844}, locale: 'en-US'});
const cookie = process.env.PAGE_COOKIE.split('=');
await context.addCookies([{name: cookie[0], value: cookie.slice(1).join('='), url: origin},
    {name: 'pixiv_lang', value: 'en-US', url: origin}]);
const page = await context.newPage(), errors = [], violations = [];
const consoleErrors = [];
page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
page.on('pageerror', error => errors.push(error.message));
await page.exposeFunction('slotCsp', value => violations.push(value));
await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event => window.slotCsp(event.violatedDirective)));
let delayed;
await page.route('**/api/web/ui-slots?fixture-delay=1', route => { delayed = route; });
try {
    await page.goto(origin + '/page-checklist/render/slots.html');
    await page.waitForFunction(() => window.slotReady && window.slotFixture.mounts === 2);
    await page.evaluate(() => window.slotPages[0].update({label: 'Updated first'}));
    await page.getByRole('button', {name: 'Updated first'}).focus();
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => window.slotFixture.events === 1);
    assert.ok(delayed, 'real request is in flight');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: path.join(work, 'ordinary-slots-mobile.png'), fullPage: true});
    const firstPublication = await page.evaluate(() => window.slotFixture.scopes[0].publication);
    fs.writeFileSync(path.join(work, 'slots-ready'), 'ready');
    await page.waitForFunction(() => window.slotFixture.destroys === 2, undefined, {timeout: 30000});
    assert.equal(await page.locator('[data-ui-instance]').count(), 0);
    assert.equal(await page.evaluate(() => window.slotFixture.cleanups), 2);
    await page.waitForFunction(() => window.slotFixture.aborted === 1);
    await delayed.abort().catch(() => {});
    const stopped = await page.evaluate(async () => {
        const before = {ticks: slotFixture.ticks, events: slotFixture.events};
        dispatchEvent(new Event('fixture-signal'));
        await new Promise(resolve => setTimeout(resolve, 350));
        let code = '';
        try { await slotFixture.scopes[0].fetch('/api/web/ui-slots'); } catch(error) { code = error.name; }
        const raw = await fetch('/api/web/ui-slots', {headers: {'X-Pixiv-Ui-Publication': slotFixture.scopes[0].publication}});
        return {before, ticks: slotFixture.ticks, events: slotFixture.events, late: slotFixture.late, code,
            status: raw.status, body: await raw.json()};
    });
    assert.deepEqual(stopped.before, {ticks: stopped.ticks, events: stopped.events});
    assert.equal(stopped.late, 0); assert.equal(stopped.code, 'AbortError');
    assert.equal(stopped.status, 409); assert.equal(stopped.body.code, 'UI_PUBLICATION_STALE');
    fs.writeFileSync(path.join(work, 'slots-withdrawn'), 'withdrawn');
    await page.waitForFunction(() => window.slotFixture.mounts === 4, undefined, {timeout: 30000});
    assert.notEqual(await page.evaluate(() => slotFixture.scopes[2].publication), firstPublication);
    assert.equal(await page.locator('[data-ui-instance]').count(), 2);
    await page.evaluate(() => slotPages[0].destroy());
    assert.equal(await page.locator('[data-ui-instance]').count(), 1);
    await page.evaluate(() => slotPages[1].destroy());
    assert.equal(await page.locator('[data-ui-instance]').count(), 0);
    assert.equal(await page.evaluate(() => slotFixture.destroys), 4);
    const contribution = await page.evaluate(async () => (await (await fetch('/api/web/ui-slots?targetPrefix=acceptance.')).json())[0]);
    await verifySlotOrder(page, contribution);
    await page.goto(origin + '/plugin-manage.html');
    const details = page.locator('[data-pm-details="page-checklist"]');
    await details.click();
    const relationship = page.locator('details[data-pm-section="services-0"]');
    await relationship.locator('summary').waitFor();
    assert.equal(await relationship.evaluate(node => node.open), false);
    await relationship.locator('summary').focus();
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelector('details[data-pm-section="services-0"]')?.open);
    assert.match(await relationship.textContent(), /example\.pages\.contract\.Checklist/);
    assert.match(await relationship.textContent(), /Service relationships/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: path.join(work, 'service-relations-mobile.png'), fullPage: true});
    await page.keyboard.press('Escape');
    await details.waitFor({state: 'visible'});
    assert.deepEqual(errors, []); assert.deepEqual(violations, []);
    console.log('ORDINARY_UI_SLOT_BROWSER_LIFECYCLE_PASSED');
} catch (error) {
    console.error('UI_SLOT_BROWSER_FAILURE', {errors, violations, consoleErrors, url: page.url(),
        state: await page.evaluate(() => ({ready: window.slotReady, mounts: window.slotFixture?.mounts}))});
    await page.screenshot({path: path.join(work, 'ordinary-slot-failure.png'), fullPage: true});
    throw error;
} finally { await browser.close(); }
