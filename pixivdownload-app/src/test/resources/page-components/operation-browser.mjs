import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [phase, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
let currentPage;
try {
    const context = await browser.newContext({locale: 'en-US'}), page = await context.newPage(), errors = [], violations = [];
    currentPage = page;
    const [name, ...value] = process.env.PAGE_COOKIE.split('=');
    await context.addCookies([{name, value: value.join('='), url: origin}, {name: 'pixiv_lang', value: 'en-US', url: origin}]);
    page.on('pageerror', error => errors.push(error.message));
    await page.exposeFunction('recordViolation', value => violations.push(value));
    await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event =>
        window.recordViolation({directive: event.violatedDirective, blocked: event.blockedURI})));
    const models = () => page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component')
        .map(model => ({id: model.get('instanceId'), region: model.parent().get('region')})));
    const ready = () => page.waitForFunction(() => window.pageEditorReady);
    await page.goto(origin + '/page-editor/index.html'); await ready();
    const cleanupDiagnostics = await page.evaluate(async () => {
        const {mountComponent} = await import('/js/pixiv-pages.mjs');
        const catalog = await (await fetch('/api/page-editor/catalog')).json();
        const definition = catalog.components.find(entry => entry.definition.type === 'operation-proof:verify');
        const root = document.createElement('div'), failures = [];
        const instance = await mountComponent(root, {...definition.definition, publication: definition.publication,
            moduleUrl: '/operation-proof/cleanup-probe.mjs'}, {}, {onError: error => failures.push(error.message)});
        await instance.destroy(); await instance.destroy();
        return {failures, released: root.dataset.released};
    });
    assert.deepEqual(cleanupDiagnostics, {failures: ['PAGE_COMPONENT_CLEANUP_FAILED'], released: 'true'});
    await page.locator('#page').selectOption('operation-form:page');
    const frame = page.frameLocator('iframe.gjs-frame');
    await frame.locator('[data-page-region="alpha-fields"]').waitFor();
    const block = page.getByRole('button', {name: 'Neutral verification', exact: true});
    async function drag(region, targetRegion) {
        await page.locator('#add-tab').click();
        await block.scrollIntoViewIfNeeded();
        const source = await block.boundingBox(), destination = await frame.locator('[data-page-region="' + region + '"]').boundingBox();
        assert.ok(source && destination);
        await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2); await page.mouse.down();
        await page.mouse.move(destination.x + 30, destination.y + destination.height / 2, {steps: 25});
        await page.mouse.move(destination.x + 35, destination.y + destination.height / 2, {steps: 5});
        await page.waitForFunction(region => window.grapesjs.editors[0].Canvas.getSpots({type: 'target'})
            .some(spot => spot.get('component')?.get('region') === region), targetRegion);
        await page.mouse.up();
    }
    await drag('alpha-fields', 'alpha');
    await frame.getByRole('button', {name: 'Respond to challenge', exact: true}).waitFor();
    assert.equal(await page.getByLabel('Protected operation', {exact: true}).inputValue(), 'alpha');
    assert.equal(await frame.getByRole('button', {name: 'Respond to challenge', exact: true}).isDisabled(), true, 'preview does not issue a challenge');
    await page.evaluate(() => {
        const editor = window.grapesjs.editors[0]; editor.select(editor.getWrapper().findType('page-component')[0]); editor.runCommand('tlb-clone');
    });
    let instances = await models();
    assert.equal(instances.length, 2); assert.equal(new Set(instances.map(item => item.id)).size, 2);
    await page.evaluate(() => {
        const editor = window.grapesjs.editors[0], duplicate = editor.getWrapper().findType('page-component')[1];
        editor.select(duplicate);
        duplicate.move(editor.getWrapper().findType('page-region').find(region => region.get('region') === 'beta'));
    });
    await page.waitForFunction(() => window.grapesjs.editors[0].getWrapper().findType('page-component').some(model => model.parent().get('region') === 'beta'));
    instances = await models(); assert.deepEqual(instances.map(item => item.region).sort(), ['alpha', 'beta']);
    assert.equal(await page.getByLabel('Protected operation', {exact: true}).inputValue(), 'beta');
    await page.locator('#undo').click();
    assert.deepEqual((await models()).map(item => item.region), ['alpha', 'alpha']);
    assert.equal(await page.getByLabel('Protected operation', {exact: true}).inputValue(), 'alpha');
    await page.locator('#redo').click();
    assert.deepEqual((await models()).map(item => item.region).sort(), ['alpha', 'beta']);
    assert.equal(await page.getByLabel('Protected operation', {exact: true}).inputValue(), 'beta');
    await page.locator('#delete').click();
    assert.equal((await models()).length, 1);
    await page.locator('#undo').click();
    assert.deepEqual((await models()).sort((a,b) => a.id.localeCompare(b.id)), instances.sort((a,b) => a.id.localeCompare(b.id)),
        'undo deletion restores the exact instance and target');
    await page.evaluate(() => {
        const editor = window.grapesjs.editors[0], model = editor.getWrapper().findType('page-component')[1];
        editor.select(model); model.move(editor.getWrapper());
    });
    assert.equal(await page.getByLabel('Protected operation', {exact: true}).inputValue(), '');
    const unmatchedSave = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/save'));
    await page.locator('#save').click();
    assert.equal((await unmatchedSave).status(), 200, 'unmatched participant can remain in a draft');
    const unmatchedPublish = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/prepare'));
    await page.locator('#publish').click();
    assert.equal((await unmatchedPublish).status(), 422, 'unmatched participant cannot publish protection');
    await page.evaluate(() => {
        const editor = window.grapesjs.editors[0], model = editor.getWrapper().findType('page-component').find(item => item.parent() === editor.getWrapper());
        editor.select(model); model.move(editor.getWrapper().findType('page-region').find(region => region.get('region') === 'beta'));
    });
    await page.locator('#save').click();
    await page.waitForFunction(() => document.querySelector('#status')?.textContent === 'Draft saved');
    await page.reload(); await ready(); await page.locator('#page').selectOption('operation-form:page');
    await page.waitForFunction(() => window.grapesjs.editors[0].getWrapper().findType('page-component').length === 2);
    assert.deepEqual((await models()).sort((a,b) => a.id.localeCompare(b.id)), instances.sort((a,b) => a.id.localeCompare(b.id)), 'saved instances retain their distinct targets');
    assert.deepEqual(await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').map(model => model.get('target')).sort()),
        ['alpha', 'beta'], 'server persists both explicit bindings');
    const publish = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/publish'));
    await page.locator('#publish').click();
    await page.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
    assert.equal((await publish).status(), 200);
    await page.goto(origin + '/operation-form/index.html');
    const alpha = page.locator('form[data-target="alpha"]'), beta = page.locator('form[data-target="beta"]');
    const alphaProof = page.locator('[data-page-region="alpha"]').getByRole('button', {name: 'Respond to challenge'});
    const betaProof = page.locator('[data-page-region="beta"]').getByRole('button', {name: 'Respond to challenge'});
    await alpha.locator('button').click();
    await alpha.locator('output[data-status="CHALLENGE"]').waitFor();
    assert.equal(await alphaProof.isEnabled(), true); assert.equal(await betaProof.isDisabled(), true);
    await alphaProof.click(); await alpha.locator('button').click(); await alpha.locator('output[data-status="APPROVED"]').waitFor();
    assert.equal(await betaProof.isDisabled(), true);
    await beta.locator('button').click(); await beta.locator('output[data-status="CHALLENGE"]').waitFor();
    await betaProof.click(); await beta.locator('input').fill('changed');
    await beta.locator('button').click(); await beta.locator('output[data-status="CHALLENGE"]').waitFor();
    await betaProof.click(); await beta.locator('button').click(); await beta.locator('output[data-status="APPROVED"]').waitFor();
    await page.goto(origin + '/page-editor/index.html'); await ready();
    await page.locator('#page').selectOption('operation-form:ambiguous');
    await frame.locator('[data-page-region="shared"]').waitFor();
    await drag('shared', 'shared');
    const selection = page.getByLabel('Protected operation', {exact: true});
    await selection.waitFor();
    assert.equal(await selection.inputValue(), '', 'multiple compatible targets require an explicit choice');
    assert.deepEqual(await selection.locator('option').evaluateAll(options => options.map(option => option.value)), ['', 'alpha', 'beta']);
    await selection.selectOption('beta');
    await page.locator('#save').click();
    await page.waitForFunction(() => document.querySelector('#status')?.textContent === 'Draft saved');
    await page.reload(); await ready(); await page.locator('#page').selectOption('operation-form:ambiguous');
    await page.waitForFunction(() => window.grapesjs.editors[0].getWrapper().findType('page-component')[0]?.get('target') === 'beta');
    assert.deepEqual(errors, []);
    assert.deepEqual(violations.filter(value => !value.blocked.includes('.kaspersky-labs.com/')), [], 'product CSP violations');
    fs.writeFileSync(path.join(work, 'operation-browser.json'), JSON.stringify({phase, instances, errors, violations}, null, 2));
    console.log('PASS independent forms: actual drag, unique and ambiguous binding, clone/move/delete/undo/redo/save/reload, unmatched draft rejection, isolated challenges and changed-input reset');
} catch (error) {
    if (currentPage) {
        await currentPage.screenshot({path: path.join(work, 'operation-failure.png'), fullPage: true});
        console.error('OPERATION_BROWSER_FAILURE', await currentPage.evaluate(() => ({
            status: document.querySelector('#status')?.textContent,
            spots: window.grapesjs?.editors[0]?.Canvas.getSpots().map(spot => ({
                type: spot.get('type'), region: spot.get('component')?.get('region'),
                componentType: spot.get('component')?.get('type')
            }))
        })));
    }
    throw error;
} finally { await browser.close(); }
