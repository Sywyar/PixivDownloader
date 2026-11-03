import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath, pathToFileURL} from 'node:url';
import path from 'node:path';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const root = process.env.HOOK_STATIC_ROOT || fileURLToPath(new URL('../../../main/resources/static/', import.meta.url));
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const row = {id: 'declaration-one', provider: 'unfamiliar', hook: 'observe-status', callback: 'fixture.Observer#completed',
    target: {owner: 'core', method: 'GET', path: '/api/download/status', params: [], headers: [], consumes: [], produces: []},
    phase: 'AFTER_COMPLETION', fields: [], endpoint: 'core:endpoint', status: 'PENDING_APPROVAL', approved: false, fingerprint: 'fixture'};
let revision = 0, published = 0, stale = false, selected;
const permission = {owner: 'unfamiliar', policy: 'observe-status', profile: {id: 'hook-binding', revision: 'fixture', sources: []}};
try {
    const page = await browser.newPage({viewport: {width: 1100, height: 780}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        const json = (body, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(body)});
        if (url.pathname === '/api/plugins/status') return json({plugins: [{id: 'unfamiliar', status: 'STARTED', displayName: 'Neutral observer'}]});
        if (url.pathname === '/api/web/operation-hooks/declarations') return json({revision, declarations: [row]});
        if (url.pathname.endsWith('/declarations/prepare')) {
            selected = route.request().postDataJSON();
            return json({revision, fingerprint: 'plan', settings: {groups: {}, bindings: selected.remove ? [] : [{id: row.id}]},
                permissions: selected.remove ? [] : [permission], pages: ['core:form']});
        }
        if (url.pathname === '/api/web/operation-hooks/publish') {
            if (stale) return json({code: 'PAGE_PUBLICATION_STALE'}, 409);
            const value = route.request().postDataJSON();
            assert.deepEqual(value.approvals, value.plan.permissions);
            published++; revision++;
            row.approved = !selected.remove; row.status = row.approved ? 'ACTIVE' : 'PENDING_APPROVAL';
            return json(revision);
        }
        if (url.pathname.startsWith('/api/i18n')) return json({}, 404);
        if (url.pathname.startsWith('/api/')) return json({});
        const file = path.resolve(root, '.' + url.pathname);
        if (!file.startsWith(root)) return route.abort();
        try {
            const type = {'.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.css': 'text/css'}[path.extname(file)];
            await route.fulfill({body: await readFile(file), contentType: type || 'application/octet-stream'});
        } catch { await route.fulfill({status: 404, body: ''}); }
    });
    await page.goto('http://fixture.test/plugin-manage.html?lang=en-US');
    await page.locator('[data-pm-details="unfamiliar"]').click();
    const panel = page.locator('[data-pm-hooks]');
    await panel.locator('[data-hook-action="prepare"]').click();
    await panel.locator('[data-hook-action="publish"]').waitFor();
    assert.match(await panel.innerText(), /GET \/api\/download\/status/);
    assert.match(await panel.innerText(), /fixture.Observer#completed/);
    await panel.locator('[data-hook-action="cancel"]').click();
    assert.equal(published, 0);
    await panel.locator('[data-hook-action="prepare"]').click();
    await panel.locator('[data-hook-action="publish"]').click();
    await panel.locator('[data-hook-action="revoke"]').waitFor();
    assert.equal(published, 1);
    stale = true;
    await panel.locator('[data-hook-action="revoke"]').click();
    await panel.locator('[data-hook-action="publish"]').click();
    await page.waitForFunction(() => document.querySelector('[data-pm-hooks] [role="status"]').textContent === PixivPluginManage.t('hooks.error'));
    assert.equal(published, 1);
    stale = false;
    await panel.locator('[data-hook-action="refresh"]').click();
    await panel.locator('[data-hook-action="revoke"]').click();
    await panel.locator('[data-hook-action="publish"]').click();
    await panel.locator('[data-hook-action="prepare"]').waitFor();
    assert.equal(published, 2);
    await page.setViewportSize({width: 390, height: 760});
    await panel.locator('[data-hook-action="prepare"]').click();
    await panel.locator('[data-hook-action="publish"]').scrollIntoViewIfNeeded();
    const bounds = await panel.locator('[data-hook-action="publish"]').boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
    if (process.env.HOOK_BROWSER_SCREENSHOT) await page.screenshot({path: process.env.HOOK_BROWSER_SCREENSHOT});
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#pm-detail-modal').evaluate(node => node.open), false);
    await page.locator('[data-pm-details="unfamiliar"]').click();
    await panel.locator('[data-hook-action="prepare"]').waitFor();
    await page.evaluate(() => { dispatchEvent(new PageTransitionEvent('pagehide', {persisted: true})); dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true})); });
    await panel.locator('[data-hook-action="prepare"]').waitFor();
    assert.deepEqual(errors, []);
    console.log('DECLARED_HOOKS_BROWSER_PASSED approval cancellation stale revoke narrow-dialog reopen bfcache (mock API)');
} finally { await browser.close(); }
