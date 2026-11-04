import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const [phase, work] = process.argv.slice(2), origin = process.env.PAGE_ORIGIN;
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
let currentPage;
const errors = [], violations = [];
try {
    const context = await browser.newContext({locale: 'en-US'}), page = await context.newPage();
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
    let preparations = 0;
    page.on('request', request => { if (new URL(request.url()).pathname === '/api/operation-hooks/prepare') preparations++; });
    await page.goto(origin + '/page-editor/index.html'); await ready();
    const labels = await page.evaluate(async () => {
        const i18n = await window.PixivI18n.create({namespaces: ['page-editor', 'operation-form', 'operation-proof']});
        return Object.fromEntries(['page-editor:binding-target', 'page-editor:hook.fields', 'operation-proof:name', 'operation-form:alpha']
            .map(key => [key, i18n.t(key)]));
    });
    const targetLabel = labels['page-editor:binding-target'];
    if (phase === 'configured-actions') {
        const id = 'operation-form:configured';
        await page.locator('#page').selectOption(id);
        const frame = page.frameLocator('iframe.gjs-frame');
        await frame.locator('#alpha-form').waitFor();
        await page.locator('#hooks-section').evaluate(node => { node.open = true; });
        for (const [index, region] of ['alpha', 'beta'].entries()) {
            const relations = page.locator('#hooks > details');
            await relations.evaluate(node => { node.open = true; });
            await relations.locator('select').nth(0).selectOption(String(index));
            await relations.locator('select').nth(1).selectOption(region);
            const endpoint = relations.locator('select').nth(2);
            assert.equal(await endpoint.locator('option').count(), 2, 'native action resolves to one actual mapping');
            await endpoint.selectOption({index: 1});
            await relations.locator('button[type="submit"]').click();
            await page.waitForFunction(count => document.querySelectorAll('#hooks > details > p').length === count, index + 1);
        }
        await page.locator('#hooks-section').evaluate(node => { node.open = false; });
        await page.evaluate(() => {
            const editor = window.grapesjs.editors[0];
            for (const region of ['alpha', 'beta']) editor.getWrapper().findType('page-region')
                .find(model => model.get('region') === region).append(editor.BlockManager.get('operation-proof:verify').get('content'));
        });
        await page.locator('#save').click();
        await page.waitForFunction(() => document.querySelector('#status').textContent === 'Draft saved');
        const document = await page.evaluate(async id => (await fetch('/api/page-editor/documents/' + id)).json(), id);
        assert.equal(document.draft.actions.length, 2);
        assert.ok(document.draft.actions.every(action => action.endpoint && !action.operation));
        const published = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/' + id + '/publish'));
        await page.locator('#publish').click();
        await page.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
        assert.equal((await published).status(), 200);
        await page.goto(origin + '/operation-form/configured.html');
        await page.locator('#native').waitFor({state: 'hidden'});
        const requests = [];
        page.on('request', request => { if (/\/api\/operation-form\/(alpha|beta)$/.test(request.url())) requests.push(request); });
        for (const region of ['alpha', 'beta']) {
            const form = page.locator('#' + region + '-form');
            const proof = page.locator('[data-page-region="' + region + '"]').getByRole('button', {name: 'Respond to challenge'});
            await form.locator('button').click();
            await proof.click();
            const response = page.waitForResponse(response => response.url().endsWith('/api/operation-form/' + region));
            await form.locator('button').click();
            assert.equal((await response).status(), 200);
        }
        assert.equal(requests.length, 2, 'each unchanged AJAX handler executes only after verification');
        assert.ok(requests.every(request => Object.keys(request.postDataJSON()).join() === 'value'), 'business DTO stays unchanged');
        await page.goto(origin + '/page-editor/index.html'); await ready();
        await page.locator('#page').selectOption(id);
        await page.locator('#document-more summary').click(); await page.locator('#defaults').click();
        await page.waitForFunction(() => window.grapesjs.editors[0].getWrapper().findType('page-component').length === 0);
        const cleared = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/' + id + '/publish'));
        await page.locator('#publish').click();
        await page.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
        assert.equal((await cleared).status(), 200);
        console.log('CONFIGURED_FORM_ACTIONS_REAL_EDITOR_AUTOMATIC_ENDPOINTS_UNCHANGED_AJAX_DTO_PASSED');
    } else if (phase === 'operation-hooks') {
        for (const id of ['operation-form:page', 'operation-form:other']) {
            await page.locator('#page').selectOption(id);
            await page.waitForFunction(() => !document.querySelector('#publish').disabled);
            await page.locator('#hooks-section').evaluate(element => { element.open = true; });
            const form = page.locator('#hooks > form'); await form.waitFor();
            const selections = form.locator('select');
            await selections.nth(0).selectOption('alpha');
            await page.waitForFunction(() => document.querySelectorAll('#hooks > form select')[1]?.options.length === 3);
            assert.match(await selections.nth(1).innerText(), /alpha-alias/);
            assert.match(await selections.nth(1).innerText(), /alpha-secondary/);
            await selections.nth(2).selectOption('fixture-generic');
            for (const stage of ['BEFORE_INVOKE', 'AFTER_COMPLETION']) {
                await selections.nth(3).selectOption(stage);
                await form.locator('input[type="number"]').fill('10');
                await form.locator('button[type="submit"]').click();
            }
            await page.locator('#save').click();
            await page.waitForFunction(() => document.querySelector('#status').textContent === 'Draft saved');
            const state = await page.evaluate(async id => (await fetch('/api/page-editor/documents/' + id)).json(), id);
            assert.equal(state.draft.hooks.filter(binding => binding.hook === 'fixture-generic').length, 2);
            assert.ok(state.draft.hooks.filter(binding => binding.hook === 'fixture-generic').every(binding => binding.operation === 'operation-form:alpha' && binding.endpoint === ''));
            await page.locator('#hooks-section').evaluate(element => { element.open = false; });
        }
        await page.reload(); await ready();
        await page.locator('#page').selectOption('operation-form:page');
        await page.locator('#hooks-section').evaluate(element => { element.open = true; });
        await page.locator('#hooks > form').waitFor();
        assert.equal(await page.locator('#hooks > div > p').filter({hasText: 'fixture-generic'}).count(), 2, 'saved generic bindings survive reload');
        assert.deepEqual(errors, []);
        console.log('PASS generic Hook editor: explicit action, all endpoint aliases, both phases, saved configuration and reload');
    } else if (phase === 'operation-draft-batch') {
        const read = id => page.evaluate(async id => (await fetch('/api/page-editor/documents/' + id)).json(), id);
        const before = await read('operation-form:page');
        const unselected = await read('operation-form:ambiguous');
        for (const id of ['operation-form:page', 'operation-form:other']) {
            await page.locator('#page').selectOption(id);
            await page.waitForFunction(id => window.grapesjs.editors[0].getWrapper().findType('page-component').length === 2
                && document.querySelector('#page').value === id && !document.querySelector('#publish').disabled, id);
            await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-component').forEach(model => model.remove()));
            await page.locator('#hooks-section').evaluate(element => { element.open = true; });
            await page.locator('#hooks > form').waitFor();
            const proofBindings = page.locator('#hooks > div > p').filter({hasText: labels['operation-proof:name']});
            while (await proofBindings.count()) await proofBindings.first().getByRole('button').click();
            await page.locator('#hooks-section').evaluate(element => { element.open = false; });
            await page.locator('#save').click();
            await page.waitForFunction(() => document.querySelector('#status').textContent === 'Draft saved');
            const conflict = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/' + id + '/prepare'));
            await page.locator('#publish').click();
            assert.equal((await conflict).status(), 422, 'one draft cannot remove shared protection');
        }
        await page.locator('#page').selectOption('operation-form:page');
        await page.waitForFunction(() => !document.querySelector('#publish').disabled);
        const openPicker = async () => {
            await page.locator('#document-more summary').click(); await page.locator('#publish-batch').click();
        };
        await openPicker();
        const picker = page.locator('.pixiv-feedback-backdrop[open]:has(.pixiv-feedback-choices)');
        await picker.waitFor();
        assert.equal(await picker.locator('input:checked').count(), 1, 'other drafts are opt-in');
        for (const [theme, width, height, font] of [['light', 1280, 800, 16], ['dark', 600, 420, 32]]) {
            await page.setViewportSize({width, height});
            await page.evaluate(({theme, font}) => {
                document.documentElement.dataset.theme = theme;
                document.documentElement.style.fontSize = font + 'px';
            }, {theme, font});
            await picker.locator('[data-feedback-action="accept"]').scrollIntoViewIfNeeded();
            assert.ok(await picker.evaluate(element => {
                const panel = element.querySelector('.pixiv-feedback-dialog'), rect = panel.getBoundingClientRect();
                return rect.left >= 0 && rect.right <= innerWidth && panel.scrollWidth <= panel.clientWidth + 1;
            }), 'picker remains inside the viewport without horizontal clipping');
            await page.screenshot({path: path.join(work, 'draft-picker-' + theme + '.png'), fullPage: true});
        }
        await page.setViewportSize({width: 1280, height: 800});
        await page.evaluate(() => { document.documentElement.style.fontSize = ''; });
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#document-more summary').evaluate(element => element === document.activeElement), true, 'cancel restores focus');
        assert.equal((await read('operation-form:page')).publicationRevision, before.publicationRevision);
        await openPicker();
        const other = picker.locator('input[value="operation-form:other"]');
        await other.locator('..').click(); assert.equal(await other.isChecked(), true, 'caption toggles once');
        await other.focus(); await page.keyboard.press('Space'); assert.equal(await other.isChecked(), false);
        await page.keyboard.press('Space'); assert.equal(await other.isChecked(), true);
        const prepared = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/prepare'));
        await picker.locator('[data-feedback-action="accept"]').click();
        const preparedResponse = await prepared;
        assert.equal(preparedResponse.status(), 200, await preparedResponse.text());
        const plan = await preparedResponse.json();
        assert.deepEqual(plan.pages, ['operation-form:page', 'operation-form:other']);
        assert.equal(plan.drafts.length, 2);
        assert.ok(plan.impacts.every(impact => impact.before.some(value => value.startsWith('fixture-proof@')) && impact.after.length === 0));
        const confirm = page.locator('.pixiv-feedback-backdrop[open]');
        await confirm.locator('.pixiv-feedback-button--secondary').click();
        assert.equal((await read('operation-form:page')).publicationRevision, before.publicationRevision, 'cancel has no formal writes');
        await openPicker(); await picker.locator('input[value="operation-form:other"]').check();
        await picker.locator('[data-feedback-action="accept"]').click();
        const published = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/publish'));
        await confirm.locator('.pixiv-feedback-button--primary').click(); assert.equal((await published).status(), 200);
        const primary = await read('operation-form:page'), secondary = await read('operation-form:other');
        assert.equal(primary.publicationRevision, before.publicationRevision + 1);
        assert.equal(secondary.publicationRevision, primary.publicationRevision);
        assert.deepEqual(await read('operation-form:ambiguous'), {...unselected, publicationRevision: primary.publicationRevision});
        assert.deepEqual(errors, []);
        assert.deepEqual(violations.filter(value => !value.blocked.includes('.kaspersky-labs.com/')), [], 'product CSP violations');
        console.log('PASS explicit draft batch: both single-page conflicts, opt-in selection, keyboard, cancel, atomic revision and unselected draft');
    } else if (phase === 'operation-history-batch') {
        const read = id => page.evaluate(async id => (await fetch('/api/page-editor/documents/' + id)).json(), id);
        const ids = ['operation-form:page', 'operation-form:other'];
        const before = await Promise.all(ids.map(read)), unselected = await read('operation-form:ambiguous');
        assert.ok(before.every(state => state.previous), 'history exists independently of restoration');
        await page.locator('#page').selectOption(ids[0]);
        await page.waitForFunction(() => !document.querySelector('#restore-batch').disabled);
        const openPicker = async () => {
            await page.locator('#document-more summary').click(); await page.locator('#restore-batch').click();
        };
        const picker = page.locator('.pixiv-feedback-backdrop[open]:has(.pixiv-feedback-choices)');
        await openPicker(); await picker.waitFor();
        assert.equal(await picker.locator('input:checked').count(), 1, 'history restoration is opt-in');
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#document-more summary').evaluate(element => element === document.activeElement), true);
        assert.deepEqual(await Promise.all(ids.map(read)), before, 'picker cancellation preserves drafts and publications');
        const choose = async () => {
            await openPicker(); await picker.locator('input[value="operation-form:other"]').check();
            const prepared = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/prepare'));
            await picker.locator('[data-feedback-action="accept"]').click();
            const response = await prepared; assert.equal(response.status(), 200, await response.text());
            const plan = await response.json();
            assert.equal(response.request().postDataJSON().restoring, true);
            assert.deepEqual(plan.pages, ids);
            assert.deepEqual(plan.drafts.map(value => value.page).sort(), [...ids].sort());
            return plan;
        };
        await choose();
        const confirmation = page.locator('.pixiv-feedback-backdrop[open]');
        for (const id of ids) await confirmation.getByText('(' + id + ')').first().waitFor();
        await page.screenshot({path: path.join(work, 'history-confirmation.png'), fullPage: true, animations: 'disabled'});
        await confirmation.locator('.pixiv-feedback-button--secondary').click();
        assert.deepEqual(await Promise.all(ids.map(read)), before, 'confirmation cancellation has no writes');
        const plan = await choose();
        const completed = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/restore'));
        await confirmation.locator('.pixiv-feedback-button--primary').click();
        const response = await completed; assert.equal(response.status(), 200, await response.text());
        assert.equal(response.request().postDataJSON().decision.fingerprint, plan.fingerprint);
        const restored = await Promise.all(ids.map(read));
        restored.forEach((state, index) => {
            assert.deepEqual(state.published, before[index].previous, 'each page uses its own history');
            assert.deepEqual(state.draft, state.published, 'selected draft reflects the restored publication');
            assert.deepEqual(state.previous, before[index].published, 'the publication replaced by restore remains recoverable');
            assert.equal(state.revision, before[index].revision + 1);
            assert.equal(state.publicationRevision, before[0].publicationRevision + 1);
        });
        assert.deepEqual(await read('operation-form:ambiguous'), {...unselected, publicationRevision: restored[0].publicationRevision});
        await page.waitForFunction(title => document.querySelector('#page-title').value === title && !document.querySelector('#restore-batch').disabled,
            restored[0].draft.title || '');
        await page.locator('#page').selectOption(ids[1]);
        await page.waitForFunction(title => document.querySelector('#page-title').value === title && !document.querySelector('#restore-batch').disabled,
            restored[1].draft.title || '');
        assert.deepEqual(errors, []);
        assert.deepEqual(violations.filter(value => !value.blocked.includes('.kaspersky-labs.com/')), []);
        console.log('PASS explicit history batch: source identity, opt-in, cancellation, atomic recovery, per-page draft synchronization and retained rollback');
    } else if (phase === 'operation-policy-update') {
        await page.locator('#page').selectOption('operation-form:page');
        await page.frameLocator('iframe.gjs-frame').locator('[data-page-region="alpha-fields"]').waitFor();
        const dialog = page.locator('.pixiv-feedback-backdrop[open]');
        const prepare = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/prepare'));
        await page.locator('#publish').click();
        const plan = await (await prepare).json();
        assert.deepEqual(plan.pages, ['operation-form:page', 'operation-form:other']);
        for (const id of plan.pages) await dialog.getByText('(' + id + ')').first().waitFor();
        await dialog.evaluate(async element => Promise.all(element.getAnimations({subtree: true})
            .filter(animation => Number.isFinite(animation.effect.getComputedTiming().endTime))
            .map(animation => animation.finished.catch(() => {}))));
        await page.screenshot({path: path.join(work, 'shared-policy-confirmation.png'), fullPage: true});
        await dialog.locator('.pixiv-feedback-button--secondary').click();
        const result = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/publish'));
        await page.locator('#publish').click();
        await dialog.locator('.pixiv-feedback-button--primary').click();
        assert.equal((await result).status(), 200);
        await page.evaluate(() => {
            const editor = window.grapesjs.editors[0];
            editor.getWrapper().findType('page-component').find(model => model.get('target') === 'alpha').remove();
        });
        await page.locator('#hooks-section').evaluate(element => { element.open = true; });
        await page.locator('#hooks > form').waitFor();
        await page.locator('#hooks > div > p').filter({hasText: labels['operation-proof:name']})
            .filter({hasText: labels['operation-form:alpha']}).getByRole('button').click();
        await page.locator('#save').click();
        await page.waitForFunction(() => document.querySelector('#status')?.textContent === 'Draft saved');
        const conflict = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/prepare'));
        await page.locator('#publish').click();
        const rejected = await conflict;
        assert.equal(rejected.status(), 422);
        assert.match(await rejected.text(), /PAGE_OPERATION_CONFLICT/);
        await page.locator('#undo').click();
        const hooks = page.locator('#hooks > form');
        await hooks.locator('select').nth(0).selectOption('alpha');
        await hooks.locator('select').nth(1).locator('option').first().waitFor({state: 'attached'});
        await hooks.locator('select').nth(2).selectOption('fixture-proof');
        await hooks.getByLabel(labels['page-editor:hook.fields']).fill('[{"source":"JSON","path":"/value","name":"value"}]');
        await hooks.locator('button[type="submit"]').click();
        await page.locator('#save').click();
        await page.waitForFunction(() => document.querySelector('#status')?.textContent === 'Draft saved');
        assert.deepEqual(errors, []);
        console.log('PASS shared-operation policy update through editor confirmation');
    } else {
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
    const block = page.locator('[data-component-block="operation-proof:verify"]');
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
    assert.equal(await page.getByLabel(targetLabel, {exact: true}).inputValue(), 'alpha');
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
    assert.equal(await page.getByLabel(targetLabel, {exact: true}).inputValue(), 'beta');
    await page.locator('#undo').click();
    assert.deepEqual((await models()).map(item => item.region), ['alpha', 'alpha']);
    assert.equal(await page.getByLabel(targetLabel, {exact: true}).inputValue(), 'alpha');
    await page.locator('#redo').click();
    assert.deepEqual((await models()).map(item => item.region).sort(), ['alpha', 'beta']);
    assert.equal(await page.getByLabel(targetLabel, {exact: true}).inputValue(), 'beta');
    await page.locator('#delete').click();
    assert.equal((await models()).length, 1);
    await page.locator('#undo').click();
    assert.deepEqual((await models()).sort((a,b) => a.id.localeCompare(b.id)), instances.sort((a,b) => a.id.localeCompare(b.id)),
        'undo deletion restores the exact instance and target');
    await page.evaluate(() => {
        const editor = window.grapesjs.editors[0], model = editor.getWrapper().findType('page-component')[1];
        editor.select(model); model.move(editor.getWrapper());
    });
    await page.waitForFunction(label => [...document.querySelectorAll('#properties select')]
        .some(select => select.getAttribute('aria-label') === label && select.value === ''), targetLabel);
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
    await page.locator('#hooks-section').evaluate(element => { element.open = true; });
    const hooks = page.locator('#hooks > form'); await hooks.waitFor();
    for (const action of ['alpha', 'beta']) {
        await hooks.locator('select').nth(0).selectOption(action);
        await hooks.locator('select').nth(1).locator('option').first().waitFor({state: 'attached'});
        await hooks.locator('select').nth(2).selectOption('fixture-proof');
        await hooks.getByLabel(labels['page-editor:hook.fields']).fill('[{"source":"JSON","path":"/value","name":"value"}]');
        await hooks.locator('button[type="submit"]').click();
    }
    await page.locator('#save').click();
    await page.waitForFunction(() => document.querySelector('#status')?.textContent === 'Draft saved');
    const publish = page.waitForResponse(response => decodeURIComponent(response.url()).endsWith('/operation-form:page/publish'));
    await page.locator('#publish').click();
    await page.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
    assert.equal((await publish).status(), 200);
    assert.equal(preparations, 0, 'editor preview and publication do not prepare challenges');
    await page.goto(origin + '/operation-form/index.html');
    await page.locator('#native').waitFor({state: 'hidden'});
    const businessRequests = [];
    page.on('request', request => {
        const path = new URL(request.url()).pathname;
        if (/^\/api\/operation-form\/(alpha|beta)$/.test(path)) businessRequests.push(path);
    });
    const alpha = page.locator('form[data-target="alpha"]'), beta = page.locator('form[data-target="beta"]');
    const alphaProof = page.locator('[data-page-region="alpha"]').getByRole('button', {name: 'Respond to challenge'});
    const betaProof = page.locator('[data-page-region="beta"]').getByRole('button', {name: 'Respond to challenge'});
    await alpha.locator('button').click();
    await alpha.locator('output[data-status="ERROR"]').waitFor();
    assert.equal(await alphaProof.isEnabled(), true); assert.equal(await betaProof.isEnabled(), true);
    assert.deepEqual(businessRequests, [], 'preparation must not submit either business form');
    await alphaProof.click(); await alpha.locator('button').click(); await alpha.locator('output[data-status="APPROVED"]').waitFor();
    assert.deepEqual(businessRequests, ['/api/operation-form/alpha']);
    await beta.locator('button').click(); await beta.locator('output[data-status="ERROR"]').waitFor();
    assert.deepEqual(businessRequests, ['/api/operation-form/alpha'], 'changed inputs require fresh proof before submission');
    await betaProof.click(); await beta.locator('input').fill('changed');
    await beta.locator('button').click(); await beta.locator('output[data-status="ERROR"]').waitFor();
    await betaProof.click(); await beta.locator('button').click(); await beta.locator('output[data-status="APPROVED"]').waitFor();
    assert.deepEqual(businessRequests, ['/api/operation-form/alpha', '/api/operation-form/beta']);
    await page.goto(origin + '/page-editor/index.html'); await ready();
    await page.locator('#page').selectOption('operation-form:ambiguous');
    await frame.locator('[data-page-region="shared"]').waitFor();
    await drag('shared', 'shared');
    const selection = page.getByLabel(targetLabel, {exact: true});
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
    }
} catch (error) {
    if (currentPage) {
        await currentPage.screenshot({path: path.join(work, 'operation-failure.png'), fullPage: true});
        console.error('OPERATION_BROWSER_FAILURE', {errors, violations}, await currentPage.evaluate(() => ({
            url: location.href,
            forms: [...document.querySelectorAll('form')].map(form => ({target: form.dataset.target, status: form.querySelector('output')?.dataset.status})),
            status: document.querySelector('#status')?.textContent,
            spots: window.grapesjs?.editors[0]?.Canvas.getSpots().map(spot => ({
                type: spot.get('type'), region: spot.get('component')?.get('region'),
                componentType: spot.get('component')?.get('type')
            }))
        })));
    }
    throw error;
} finally { await browser.close(); }
