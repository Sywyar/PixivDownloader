import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {checkComponentFrameLayout} from './component-frame-browser.mjs';
import {checkComponentMotion} from './motion-browser.mjs';
import {checkPageControlsAndPalette} from './palette-browser.mjs';
import {checkEditorFeedback, checkLibraryFeedback, checkToastPosition} from './feedback-browser.mjs';
import {checkCustomProperties} from './custom-properties-browser.mjs';

export async function checkCustomComponents({page, context, origin, phase, work, clickConfirm}) {
    const api = (route, body) => page.evaluate(async ({route, body}) => {
        const response = await fetch('/api/page-editor/' + route, {method: body === undefined ? 'GET' : 'POST',
            headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
        return {status: response.status, body: await response.json()};
    }, {route, body});
    const fixture = path.join(work, 'custom-components.json');
    if (phase === 'custom-restarted') {
        const {id, version} = JSON.parse(fs.readFileSync(fixture, 'utf8'));
        const state = await api('components/' + id);
        assert.equal(state.status, 200); assert.ok(state.body.versions.includes(version));
        await context.clearCookies({name: 'pixiv_session'});
        await page.goto(origin + '/login.html');
        const frame = page.frameLocator('#custom-page iframe').first();
        await frame.locator('button').waitFor();
        assert.equal(await frame.locator('button').textContent(), '0');
        await frame.locator('button').click(); await frame.getByRole('button', {name: '1', exact: true}).waitFor();
        return;
    }
    await checkComponentFrameLayout({page, api});
    await checkPageControlsAndPalette(page);
    await checkEditorFeedback(page, clickConfirm);
    const originalTitle = await page.locator('#page-title').inputValue();
    await page.locator('#page-title').fill('Layout draft stays in its own tab');
    await page.locator('#add-tab').click();
    const group = page.getByRole('button', {name: 'Layout containers', exact: true});
    await group.focus(); await group.press('Enter');
    assert.equal(await group.getAttribute('aria-expanded'), 'false');
    await page.locator('#' + await group.getAttribute('aria-controls')).waitFor({state: 'hidden'});
    await group.press('Space');
    assert.equal(await group.getAttribute('aria-expanded'), 'true');
    assert.equal(await page.locator('#' + await group.getAttribute('aria-controls')).isVisible(), true);
    await page.locator('#block-search').fill('no matching component');
    await page.locator('#blocks-no-match').waitFor();
    assert.equal(await page.locator('#blocks .gjs-block:visible').count(), 0);
    await page.locator('#block-search').fill('Text');
    const textCount = await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-text').length);
    const textBlock = page.locator('[data-component-block="text"]');
    const canvasState = () => page.evaluate(() => {
        const editor = window.grapesjs.editors[0];
        return {project: editor.getProjectData(), selected: editor.getSelected()?.getId(),
            status: document.querySelector('#status').textContent, undo: editor.UndoManager.getStack().length};
    });
    const beforeDetails = await canvasState();
    await textBlock.click();
    await page.locator('#component-details').waitFor();
    assert.equal(await page.locator('#block-search').isVisible(), true);
    assert.equal(await textBlock.isVisible(), true);
    assert.equal(await textBlock.getAttribute('aria-expanded'), 'true');
    const textBox = await textBlock.boundingBox(), popupBox = await page.locator('#component-details').boundingBox();
    assert.ok(popupBox.x >= textBox.x + textBox.width, 'wide-view details appear next to the component');
    assert.equal(await page.locator('#component-detail-name').textContent(), (await textBlock.textContent()).trim());
    assert.deepEqual(await canvasState(), beforeDetails, 'viewing details leaves document, selection, and undo unchanged');
    await page.locator('#component-detail-name').press('Escape');
    assert.equal(await textBlock.evaluate(element => element === document.activeElement), true);
    assert.equal(await page.locator('#block-search').inputValue(), 'Text');
    await textBlock.press('Space');
    await page.locator('#component-details').waitFor();
    assert.deepEqual(await canvasState(), beforeDetails);
    await page.locator('#component-close').click();
    assert.equal(await textBlock.evaluate(element => element === document.activeElement), true);
    assert.equal(await textBlock.getAttribute('aria-expanded'), 'false');
    await textBlock.click();
    await page.locator('#block-search').click();
    assert.equal(await page.locator('#component-details').evaluate(element => !element.matches(':popover-open') && element.inert), true, 'closing disables the popup immediately');
    await page.locator('#component-details').waitFor({state: 'hidden'});
    assert.equal(await page.locator('#block-search').evaluate(element => element === document.activeElement), true);
    assert.deepEqual(await canvasState(), beforeDetails);
    await page.locator('#block-search').fill('');
    await textBlock.click();
    await page.locator('[data-component-block="link"]').click();
    assert.equal(await page.locator('#component-detail-name').textContent(), (await page.locator('[data-component-block="link"]').textContent()).trim());
    assert.equal(await textBlock.getAttribute('aria-expanded'), 'false');
    assert.equal(await page.locator('[data-component-block="link"]').getAttribute('aria-expanded'), 'true');
    assert.deepEqual(await canvasState(), beforeDetails, 'switching details leaves the page unchanged');
    await page.locator('#component-close').click();
    await page.locator('#block-search').fill('Text');
    await textBlock.press('Enter');
    await page.locator('#component-details').waitFor();
    assert.deepEqual(await canvasState(), beforeDetails);
    const viewport = page.viewportSize();
    await page.setViewportSize({width: 390, height: 600});
    await page.locator('#component-add').scrollIntoViewIfNeeded();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.waitForFunction(() => {
        const box = document.querySelector('#component-details').getBoundingClientRect();
        return box.left >= 0 && box.right <= innerWidth && box.top >= 0 && box.bottom <= innerHeight;
    });
    await page.screenshot({path: path.join(work, 'component-details-narrow.png'), fullPage: true});
    await page.setViewportSize(viewport);
    await page.locator('#component-add').click();
    assert.equal(await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-text').length), textCount + 1);
    assert.equal(await textBlock.evaluate(element => element === document.activeElement), true);
    await page.locator('#undo').click();
    assert.equal(await page.evaluate(() => window.grapesjs.editors[0].getWrapper().findType('page-text').length), textCount);
    await textBlock.click();
    await page.frameLocator('iframe.gjs-frame').locator('[data-page-region="password"]').click();
    await page.waitForFunction(() => !document.querySelector('#component-details').matches(':popover-open')
        && window.grapesjs.editors[0].getSelected()?.get('region') === 'password');
    await page.locator('#block-search').fill('');
    const popup = page.waitForEvent('popup'); await page.locator('#custom-open').click();
    const creator = await popup;
    const creatorErrors = []; creator.on('pageerror', error => creatorErrors.push(error.message));
    const creatorRequests = [];
    creator.on('request', request => { if (!request.url().startsWith(origin) && !request.url().startsWith('data:')) creatorRequests.push(request.url()); });
    if (!(await api('components')).body.length) await creator.locator('#component-empty').waitFor();
    else await creator.locator('#component-list a').first().waitFor();
    await checkLibraryFeedback(creator);
    await creator.locator('#custom-new').click();
    await creator.locator('#custom-name').waitFor();
    await creator.waitForFunction(() => !document.querySelector('#component-fields').inert);
    const failedMessage = await creator.evaluate(async () =>
        (await window.PixivI18n.create({namespaces: ['page-editor']})).t('page-editor:failed'));
    await checkComponentMotion(creator);
    const editSource = async (tab, value) => {
        await creator.locator('#custom-tab-' + tab).click();
        const input = creator.locator('#custom-code .cm-content');
        await input.focus(); await input.press('ControlOrMeta+a');
        if (value) await creator.keyboard.insertText(value); else await input.press('Backspace');
    };
    await creator.locator('#custom-name').fill('Reusable footer');
    await editSource('html', '<footer><span>Reusable footer</span></footer>');
    await creator.locator('#custom-tab-css').click();
    await creator.locator('#custom-tab-html').click();
    await creator.locator('#custom-code .cm-content').press('ControlOrMeta+z');
    assert.notEqual(await creator.locator('#custom-code .cm-content').innerText(), '<footer><span>Reusable footer</span></footer>');
    await creator.locator('#custom-code .cm-content').press('ControlOrMeta+y');
    assert.equal(await creator.locator('#custom-code .cm-content').innerText(), '<footer><span>Reusable footer</span></footer>');
    await creator.locator('#section-properties').click();
    await creator.locator('#property-add').click();
    const property = creator.locator('.property-row').last();
    await property.locator('input').nth(0).fill('step-size');
    await property.locator('input').nth(1).fill('Step');
    await property.locator('select').selectOption('number');
    await property.locator('input').nth(2).fill('2');
    await creator.locator('#section-code').click();
    await editSource('javascript', '');
    await creator.locator('#custom-run').click();
    await creator.frameLocator('#custom-preview iframe').getByText('Reusable footer').waitFor();
    await creator.locator('#custom-save').click();
    await creator.waitForURL(/component.html\?id=/);
    await creator.locator('.pixiv-feedback-toast--success').waitFor();
    await checkToastPosition(creator);
    const id = new URL(creator.url()).searchParams.get('id');
    assert.deepEqual((await api('components/' + id)).body.draft.properties['step-size'], {type: 'number', label: 'Step', default: 2});
    await creator.screenshot({path: path.join(work, 'component-authoring.png'), fullPage: true});
    for (const [width, height] of [[390, 600], [1000, 480], [2560, 1440]]) {
        await creator.setViewportSize({width, height});
        assert.ok(await creator.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'component page must reflow');
        await creator.locator('#custom-save').scrollIntoViewIfNeeded();
        await creator.locator('#custom-run').scrollIntoViewIfNeeded();
        await creator.frameLocator('#custom-preview iframe').locator('footer').waitFor();
        await creator.screenshot({path: path.join(work, 'component-' + width + '.png'), fullPage: true});
    }
    await creator.setViewportSize({width: 1440, height: 1000});
    await creator.evaluate(() => document.documentElement.style.fontSize = '200%');
    assert.ok(await creator.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'component text zoom');
    await creator.locator('#section-properties').click();
    assert.ok(await creator.locator('.property-row').first().isVisible());
    await creator.evaluate(() => document.documentElement.style.fontSize = '');
    await creator.locator('#section-code').click();
    const worker = context.serviceWorkers()[0];
    if (worker) {
        const zoom = factor => worker.evaluate(async ({url, factor}) => {
            const tab = (await chrome.tabs.query({})).find(tab => tab.url === url);
            await chrome.tabs.setZoom(tab.id, factor); return chrome.tabs.getZoom(tab.id);
        }, {url: creator.url(), factor});
        assert.equal(await zoom(2), 2);
        await creator.waitForFunction(() => innerWidth <= 720);
        assert.ok(await creator.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'component browser zoom');
        await creator.locator('#custom-save').scrollIntoViewIfNeeded();
        await zoom(1);
        await creator.waitForFunction(() => innerWidth === 1440);
    }
    await creator.evaluate(() => window.PixivTheme.apply('dark', false, false));
    await creator.screenshot({path: path.join(work, 'component-dark.png'), fullPage: true});
    await creator.emulateMedia({forcedColors: 'active', reducedMotion: 'reduce'});
    await creator.locator('#section-code').focus(); await creator.locator('#section-code').press('ArrowDown');
    assert.equal(await creator.locator('#section-properties').getAttribute('aria-selected'), 'true');
    await creator.locator('#section-properties').press('ArrowUp');
    await creator.emulateMedia({forcedColors: 'none', reducedMotion: 'no-preference'});
    await creator.evaluate(() => window.PixivTheme.apply('light', false, false));
    await editSource('javascript', "throw new Error('test failure');");
    await creator.locator('#custom-run').click();
    await creator.waitForFunction(expected => {
        const message = document.querySelector('#custom-message');
        return message.dataset.state === 'error' && message.textContent === expected;
    }, failedMessage);
    await creator.frameLocator('#custom-preview iframe').locator('footer').waitFor();
    await editSource('javascript', "root.querySelector('span').textContent = context.properties.title;");
    await editSource('html', '<p onclick="alert(1)">invalid</p>');
    await creator.locator('#custom-run').click();
    await creator.waitForFunction(expected => {
        const message = document.querySelector('#custom-message');
        return message.dataset.state === 'error' && message.textContent === expected;
    }, failedMessage);
    await creator.frameLocator('#custom-preview iframe').locator('footer').waitFor();
    await editSource('html', '<footer><span></span></footer>');
    await creator.locator('#custom-publish').click(); await creator.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
    await creator.waitForFunction(() => document.querySelector('#custom-message').textContent === 'Published');
    let state = (await api('components/' + id)).body;
    assert.match(state.latest, /^[0-9a-f]{64}$/);
    const first = state.latest;
    const source = {name: 'Counter', html: '<button type="button">0</button><output></output><span data-number></span>',
        css: 'button { color: rgb(125, 30, 90); } body { background: transparent; }',
        javascript: `let n = 0;
const button = root.querySelector('button');
button.addEventListener('click', () => button.textContent = String(++n));
root.querySelector('output').textContent = context.properties.title;
root.querySelector('[data-number]').textContent = typeof context.properties.count + ':' + context.properties.count;
try { parent.document.body.dataset.customEscape = 'bad'; } catch (_) {}
try { document.cookie = 'escape=bad'; } catch (_) {}`,
        properties: {title: {type: 'string', label: 'Title', default: 'Instance'},
            count: {type: 'number', label: 'Count', default: 1}, enabled: {type: 'boolean', label: 'Enabled', default: true}}};
    state = (await api('components/save', {id, revision: state.revision, source})).body;
    assert.equal((await api('components/save', {id, revision: state.revision - 1, source})).status, 409);
    state = (await api('components/' + id + '/publish', {revision: state.revision})).body;
    const version = state.latest; assert.notEqual(version, first);
    assert.equal((await api('components/' + id + '/versions/' + first)).body.name, 'Reusable footer');
    await creator.reload(); await creator.waitForFunction(() => !document.querySelector('#component-fields').inert);
    await creator.locator('#section-versions').click();
    await creator.locator('#custom-versions li').last().getByRole('button').click();
    await creator.waitForFunction(() => document.querySelector('#custom-name').value === 'Reusable footer');
    assert.equal((await api('components/' + id)).body.draft.name, 'Counter', 'loading history must not save');
    await creator.locator('#custom-back').click();
    await creator.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--secondary').click();
    assert.equal(await creator.locator('#custom-name').inputValue(), 'Reusable footer');
    await creator.locator('#custom-back').click();
    await creator.locator('.pixiv-feedback-backdrop[open] .pixiv-feedback-button--primary').click();
    await creator.locator('#component-list a').first().waitFor();
    await creator.locator('#component-search').fill('no matching component');
    await creator.locator('#component-no-match').waitFor();
    await creator.locator('#component-search').fill('Counter');
    assert.ok((await creator.locator('#component-list strong').allTextContents()).every(name => name === 'Counter'));
    await creator.locator('a[href="/page-editor/component.html?id=' + id + '"]').waitFor();
    for (const name of ['Footer with a long name for a shared page layout', 'A compact signature', 'Draft announcement']) {
        assert.equal((await api('components/save', {id: '', revision: 0, source: {...source, name}})).status, 200);
    }
    await creator.locator('#component-search').fill(''); await creator.locator('#component-refresh').click();
    await creator.waitForFunction(() => document.querySelectorAll('#component-list li').length >= 4);
    await creator.locator('[data-filter="draft"]').click();
    assert.equal(await creator.locator('#component-list li').count(), 3);
    assert.equal(await creator.locator('[data-filter="draft"]').getAttribute('aria-pressed'), 'true');
    await creator.locator('[data-filter="published"]').click();
    assert.equal(await creator.locator('#component-list li').count(), 1);
    await creator.locator('#component-search').fill('missing');
    await creator.locator('#component-clear').click();
    assert.equal(await creator.locator('#component-list li').count(), 4);
    assert.equal(await creator.locator('#component-search').inputValue(), '');
    await creator.screenshot({path: path.join(work, 'component-library.png'), fullPage: true});
    await creator.setViewportSize({width: 390, height: 600});
    assert.ok(await creator.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    assert.ok(await creator.locator('.component-row').evaluateAll(rows => rows.every(row =>
        row.querySelector('.component-identity').getBoundingClientRect().width >= row.getBoundingClientRect().width / 2)),
        'narrow rows must leave room for names instead of squeezing them beside status labels');
    await creator.screenshot({path: path.join(work, 'component-library-narrow.png'), fullPage: true});
    await creator.setViewportSize({width: 1440, height: 1000});
    await creator.close(); await page.bringToFront(); assert.deepEqual(creatorErrors, []);
    assert.deepEqual(creatorRequests.filter(url => !url.includes('.kaspersky-labs.com/')), []);
    assert.equal(await page.locator('#page-title').inputValue(), 'Layout draft stays in its own tab');
    await page.locator('#page-title').fill(originalTitle);
    await page.reload(); await page.waitForFunction(() => window.pageEditorReady);
    await page.locator('#add-tab').click();
    const block = page.locator('[data-component-block="custom:' + id + '"]');
    await block.focus(); await block.press('Enter');
    assert.equal(await page.locator('#component-detail-version').textContent(), version.slice(0, 12));
    assert.equal(new URL(await page.locator('#component-detail-edit').getAttribute('href'), origin).searchParams.get('id'), id);
    await page.locator('#component-add').click();
    await block.press('Enter'); await page.locator('#component-add').click();
    const canvas = page.frameLocator('iframe.gjs-frame');
    const firstFrame = canvas.frameLocator('iframe[sandbox]').first();
    const secondFrame = canvas.frameLocator('iframe[sandbox]').nth(1);
    await firstFrame.locator('button').waitFor(); await secondFrame.locator('button').waitFor();
    assert.equal(await firstFrame.locator('output').textContent(), 'Instance');
    await page.getByLabel('Title', {exact: true}).fill('Second instance');
    await page.getByLabel('Title', {exact: true}).press('Tab');
    await secondFrame.getByText('Second instance', {exact: true}).waitFor();
    await checkCustomProperties({page, id, version, first});
    assert.equal(await firstFrame.locator('output').textContent(), 'Instance');
    assert.equal(await firstFrame.locator('button').evaluate(button => getComputedStyle(button).color), 'rgb(125, 30, 90)');
    await page.evaluate(async () => {
        const canvas = document.querySelector('iframe.gjs-frame').contentDocument;
        await Promise.all([...document.getAnimations(), ...canvas.getAnimations()]
            .filter(animation => Number.isFinite(animation.effect.getComputedTiming().endTime))
            .map(animation => animation.finished.catch(() => {})));
    });
    // 先滚动外层画布；屏外嵌套 frame 的动画帧可能被浏览器暂停。
    await canvas.locator('iframe[sandbox]').first().scrollIntoViewIfNeeded();
    await firstFrame.locator('button').scrollIntoViewIfNeeded();
    await firstFrame.locator('button').click();
    await firstFrame.getByRole('button', {name: '1', exact: true}).waitFor();
    assert.equal(await firstFrame.locator('button').textContent(), '1');
    await firstFrame.locator('button').click();
    await firstFrame.getByRole('button', {name: '2', exact: true}).waitFor();
    assert.equal(await firstFrame.locator('button').textContent(), '2');
    assert.equal(await secondFrame.locator('button').textContent(), '0');
    await firstFrame.locator('#root').evaluate(root => { root.hidden = true; });
    const emptyComponent = canvas.locator('[data-component-placeholder]').first();
    await emptyComponent.waitFor({state: 'visible'}); await emptyComponent.click();
    assert.equal(await page.evaluate(() => window.grapesjs.editors[0].getSelected().get('configuration').componentId), id);
    await page.screenshot({path: path.join(work, 'custom-empty-editor.png'), fullPage: true});
    await firstFrame.locator('#root').evaluate(root => { root.hidden = false; });
    await firstFrame.locator('button').waitFor({state: 'visible'});
    assert.equal(await firstFrame.locator('button').textContent(), '2');
    assert.equal(await page.evaluate(() => document.body.dataset.customEscape), undefined);
    assert.equal((await context.cookies()).some(cookie => cookie.name === 'escape'), false);
    await page.screenshot({path: path.join(work, 'custom-component-editor.png'), fullPage: true});
    await page.locator('#save').click();
    await page.waitForFunction(() => document.querySelector('#status').dataset.state === 'success');
    let document = (await api('documents/core:login')).body;
    const all = node => [node, ...(node.components || []).flatMap(all)];
    const nodes = all(document.draft.project.pages[0].frames[0].component).filter(node => node.componentType === 'page-editor:custom');
    assert.equal(nodes.length, 2); assert.equal(new Set(nodes.map(node => node.instanceId)).size, 2);
    assert.ok(nodes.every(node => node.configuration.version === version));
    state = (await api('components/save', {id, revision: state.revision, source: {...source, name: 'Unpublished source'}})).body;
    assert.equal((await api('documents/core:login')).body.draft.project.pages[0].frames[0].component.components.length,
        document.draft.project.pages[0].frames[0].component.components.length);
    const preview = await api('documents/core:login/prepare', {revision: document.revision, restoring: false});
    assert.equal(preview.status, 200);
    assert.equal((await api('documents/core:login/publish', {revision: document.revision, decision: {
        revision: preview.body.revision, fingerprint: preview.body.fingerprint, approvals: preview.body.permissions
    }})).status, 200);
    const publicContext = await context.browser().newContext();
    const published = await publicContext.newPage();
    await published.goto(origin + '/login.html');
    await published.frameLocator('#custom-page iframe').first().locator('button').waitFor();
    await checkComponentFrameLayout({page: published, api});
    await page.bringToFront(); await page.locator('#add-tab').click(); await block.press('Enter');
    state = (await api('components/' + id + '/availability', {revision: state.revision, enabled: false})).body;
    await published.waitForFunction(() => !document.querySelector('#custom-page iframe'), undefined, {timeout: 10000});
    await page.waitForFunction(() => document.querySelector('#component-add').disabled);
    await page.locator('#component-detail-unavailable').waitFor();
    assert.equal(await page.locator('#component-detail-edit').isVisible(), false);
    assert.equal((await context.request.get(origin + '/page-components/published/' + id + '/' + version + '/frame')).status(), 404);
    state = (await api('components/' + id + '/availability', {revision: state.revision, enabled: true})).body;
    await page.waitForFunction(() => !document.querySelector('#component-add').disabled);
    assert.equal(await page.locator('#component-detail-version').textContent(), version.slice(0, 12));
    await page.locator('#component-close').click();
    await published.reload(); await published.frameLocator('#custom-page iframe').first().locator('button').waitFor();
    await published.close();
    assert.equal((await publicContext.request.get(origin + '/api/page-editor/components/' + id)).status(), 401);
    assert.equal((await publicContext.request.get(origin + '/page-components/published/' + id + '/' + version + '/frame')).status(), 200);
    assert.equal((await context.request.post(origin + '/api/page-editor/components/save', {
        headers: {Origin: 'https://example.test'}, data: {id, revision: state.revision, source}
    })).status(), 403);
    const snapshot = await api('components/preview', {source: {...source, html: '<p></p>', properties: {},
        javascript: "fetch('/api/auth/logout', {method:'POST',credentials:'include'}).then(() => root.textContent='unexpected').catch(() => root.textContent='network-blocked');"}});
    assert.equal(snapshot.status, 200);
    const probe = await publicContext.newPage(), businessRequests = [], blocked = [];
    probe.on('requestfinished', request => { if (request.url().includes('/api/auth/logout')) businessRequests.push(request.url()); });
    await probe.exposeFunction('recordSandboxViolation', value => blocked.push(value));
    await probe.addInitScript(() => addEventListener('securitypolicyviolation', event =>
        window.recordSandboxViolation({directive: event.effectiveDirective, uri: event.blockedURI})));
    await probe.goto(origin + '/login.html');
    await probe.evaluate(async url => {
        const {mountFrame} = await import('/page-components/runtime.mjs');
        const root = document.createElement('div'); root.id = 'network-probe'; document.body.append(root);
        await mountFrame(root, url, {}, {title: 'Network probe'});
    }, snapshot.body.url);
    await probe.frameLocator('#network-probe iframe').getByText('network-blocked').waitFor();
    assert.ok(blocked.some(value => value.directive === 'connect-src' && value.uri === origin + '/api/auth/logout'));
    assert.deepEqual(businessRequests, [], 'sandbox never submits the attempted business write');
    assert.equal((await api('components/previews/' + snapshot.body.token + '/close', {})).status, 200);
    await probe.locator('#network-probe iframe').waitFor({state: 'detached'});
    assert.equal((await publicContext.request.get(origin + snapshot.body.url)).status(), 404);
    await publicContext.close();
    fs.writeFileSync(fixture, JSON.stringify({id, version}), 'utf8');
}
