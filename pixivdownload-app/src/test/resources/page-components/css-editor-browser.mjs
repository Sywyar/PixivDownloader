import assert from 'node:assert/strict';
import path from 'node:path';

export async function checkCssEditor(page, work, zoom) {
    const input = page.locator('#css-code .cm-content'), apply = page.locator('#css-apply');
    const frame = page.frameLocator('iframe.gjs-frame');
    const write = async css => {
        await input.focus(); await input.press('ControlOrMeta+a'); await page.keyboard.insertText(css);
    };
    const open = async () => {
        if (await page.locator('#style-toggle').getAttribute('aria-expanded') !== 'true') await page.locator('#style-toggle').click();
        if (await page.locator('#edit-css').getAttribute('aria-expanded') !== 'true') await page.locator('#edit-css').click();
        await input.waitFor();
    };
    const applied = async css => {
        await open(); await write(css); await apply.click();
        await page.waitForFunction(() => document.getElementById('css-panel').dataset.state === 'success');
    };
    assert.equal(await page.evaluate(() => performance.getEntriesByType('resource').some(entry => entry.name.endsWith('/codemirror.mjs'))), false, 'lazy code editor');
    const styles = page.locator('#style-toggle');
    await styles.focus(); await styles.press('Enter');
    await page.locator('#style-content').waitFor({state: 'hidden'});
    assert.equal(await page.locator('#styles').isVisible(), false, 'collapsing Style hides layout and appearance');
    assert.equal(await page.locator('#edit-css').isVisible(), false, 'CSS belongs to the same Style group');
    await styles.press('Space');
    await page.locator('#styles').waitFor();
    await open();
    assert.equal(await page.locator('dialog[open]').count(), 0, 'CSS opens inline');
    assert.ok(await page.locator('#css-code .cm-lineNumbers').isVisible());
    assert.equal(await page.locator('#css-title').isVisible(), false, 'inline toolbar uses component context without a duplicate heading');
    assert.equal(await page.locator('#css-message').isVisible(), false, 'idle editor has no instruction paragraph');
    assert.equal(await page.locator('#css-help').isVisible(), false);
    await page.locator('#css-help-toggle').focus(); await page.keyboard.press('Enter');
    assert.equal(await page.locator('#css-help-toggle').getAttribute('aria-expanded'), 'true');
    assert.ok(await page.locator('#css-help').isVisible());
    await page.keyboard.press('Escape');
    await page.locator('#css-help').waitFor({state: 'hidden'});
    assert.equal(await page.locator('#edit-css').getAttribute('aria-expanded'), 'true', 'Escape closes help before the editor');
    await page.screenshot({path: path.join(work, 'css-compact.png'), fullPage: true});
    await write('pad'); await input.press('Control+Space');
    await page.locator('.cm-tooltip-autocomplete').waitFor();
    assert.ok((await page.locator('.cm-completionLabel').allTextContents()).includes('padding'));
    assert.equal((await page.locator('.cm-completionLabel').allTextContents()).includes('position'), false);
    await input.press('Escape');
    assert.equal(await page.locator('#edit-css').getAttribute('aria-expanded'), 'true', 'Escape closes completion first');
    for (const invalid of ['color: url(https://example.test/a);', 'padding: banana;', 'body {color:red}']) {
        await write(invalid); await apply.click();
        await page.waitForFunction(() => document.getElementById('css-panel').dataset.state === 'error');
        assert.equal(await input.innerText(), invalid);
        assert.ok(await page.locator('.cm-lintRange-error').count());
    }
    await write('padding: 18px;\nborder-radius: 8px;');
    assert.equal(await page.locator('.cm-lintRange-error').count(), 0, 'editing clears obsolete diagnostics');
    await input.press('End'); await input.press('Enter'); await input.press('x');
    await page.evaluate(() => { window.cssViewElement = document.querySelector('#css-code .cm-editor'); });
    await page.locator('#css-expand').click();
    await page.waitForFunction(() => document.querySelector('.css-fullscreen')?.getBoundingClientRect().width === innerWidth);
    assert.equal(await page.locator('.editor-header').isVisible(), false);
    assert.equal(await page.evaluate(() => window.cssViewElement === document.querySelector('#css-code .cm-editor')), true);
    await input.press('Control+z');
    assert.equal(/^\s*x\s*$/m.test(await input.innerText()), false, 'editor undo survives full screen');
    await page.screenshot({path: path.join(work, 'css-fullscreen.png')});
    await input.press('Escape');
    assert.equal(await page.locator('.css-fullscreen').count(), 0);
    assert.equal(await page.evaluate(() => window.cssViewElement === document.querySelector('#css-code .cm-editor')), true);
    await styles.click();
    await page.locator('#style-content').waitFor({state: 'hidden'});
    await page.locator('#save').click();
    await input.waitFor();
    assert.equal(await styles.getAttribute('aria-expanded'), 'true', 'pending CSS reopens its parent before focusing the draft');
    assert.equal(await input.evaluate(node => node === document.activeElement), true);
    assert.equal(await apply.isDisabled(), false, 'saving requires explicit CSS application');
    await page.locator('#structure-tab').click();
    const original = await page.locator('#selection').inputValue();
    const other = await page.locator('#selection option').nth(1).getAttribute('value');
    await page.locator('#selection').selectOption(other);
    assert.equal(await apply.isDisabled(), true, 'pending buffer cannot target another component');
    assert.ok((await input.innerText()).includes('padding: 18px'));
    await page.locator('#selection').selectOption(original);
    await write('padding: 99px;');
    const parseRoute = '**/api/page-editor/styles/parse';
    for (const close of [() => input.press('Escape'), () => styles.click()]) {
        let held;
        const waiting = new Promise(resolve => page.route(parseRoute, route => { held = route; resolve(); }));
        await apply.click(); await waiting;
        assert.equal(await apply.isDisabled(), true);
        await close();
        await held.abort().catch(() => {}); await page.unroute(parseRoute);
        assert.notEqual(await frame.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node).paddingTop), '99px');
        await open();
        assert.equal(await page.locator('#css-panel').getAttribute('aria-busy'), 'false');
    }
    await page.route(parseRoute, route => route.fulfill({status: 500, contentType: 'application/json', body: '{"code":"TEST_FAILURE"}'}));
    await write('padding: 18px; border-radius: 8px;'); await apply.click();
    await page.waitForFunction(() => document.getElementById('css-panel').dataset.state === 'error');
    assert.equal(await input.innerText(), 'padding: 18px; border-radius: 8px;', 'server failure preserves input');
    await page.unroute(parseRoute);
    await applied('padding: 18px; border-radius: 8px;');
    assert.equal(await frame.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node).paddingTop), '18px');
    await page.locator('#undo').click();
    assert.notEqual(await frame.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node).paddingTop), '18px');
    await page.locator('#redo').click();
    await page.locator('#viewport').selectOption('Mobile'); await applied('padding: 6px;');
    await page.waitForFunction(() => {
        const doc = document.querySelector('iframe.gjs-frame').contentDocument;
        const text = [...doc.querySelectorAll('p')].find(node => node.textContent === 'Layout note');
        return text && doc.defaultView.getComputedStyle(text).paddingTop === '6px';
    });
    assert.equal(await frame.getByText('Layout note', {exact: true}).evaluate(node => getComputedStyle(node).paddingTop), '6px');
    await page.screenshot({path: path.join(work, 'component-css.png'), fullPage: true});
    await page.locator('#css-expand').click();
    for (const viewport of [{width: 390, height: 700}, {width: 900, height: 400}]) {
        await page.setViewportSize(viewport);
        const box = await page.locator('#css-panel').boundingBox();
        assert.equal(Math.round(box.width), viewport.width); assert.equal(Math.round(box.height), viewport.height);
        assert.ok(await apply.isVisible());
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    }
    await page.screenshot({path: path.join(work, 'css-short.png')});
    await page.setViewportSize({width: 900, height: 600});
    await page.evaluate(() => document.documentElement.style.fontSize = '200%');
    assert.ok(await apply.evaluate(node => node.getBoundingClientRect().bottom <= innerHeight), 'large text keeps CSS actions reachable');
    assert.ok(await input.evaluate(node => node.getBoundingClientRect().height >= 48), 'large text keeps an editable area');
    await page.evaluate(() => document.documentElement.style.fontSize = '');
    await page.evaluate(() => document.documentElement.dataset.theme = 'dark');
    await page.screenshot({path: path.join(work, 'css-dark.png')});
    await page.emulateMedia({forcedColors: 'active'});
    assert.ok(await page.locator('#css-expand').isVisible());
    await page.emulateMedia({forcedColors: 'none'});
    await page.evaluate(() => document.documentElement.dataset.theme = 'light');
    if (zoom) {
        await page.setViewportSize({width: 1440, height: 720});
        assert.equal(await zoom(2), 2);
        await page.waitForFunction(() => innerWidth <= 720);
        assert.ok(await apply.evaluate(node => node.getBoundingClientRect().bottom <= innerHeight), 'browser zoom keeps CSS actions reachable');
        assert.ok(await input.isVisible());
        await page.screenshot({path: path.join(work, 'css-browser-zoom.png')});
        assert.equal(await zoom(1), 1);
        await page.waitForFunction(() => innerWidth >= 1440);
    }
    await page.setViewportSize({width: 1440, height: 1000});
    await input.press('Escape'); await input.focus(); await input.press('Tab');
    assert.equal(await input.evaluate(node => node === document.activeElement), false, 'Tab leaves code editing');
    await page.emulateMedia({reducedMotion: 'reduce'});
    await page.evaluate(() => { document.getElementById('edit-css').click(); document.getElementById('edit-css').click(); });
    assert.equal(await page.evaluate(() => document.getElementById('css-panel').getAnimations().length), 0);
    await page.emulateMedia({reducedMotion: 'no-preference'});
    await page.evaluate(() => { document.getElementById('edit-css').click(); document.getElementById('edit-css').click(); });
    await input.waitFor();
    await input.press('Escape');
    await page.locator('#viewport').selectOption('Desktop');
    await page.waitForFunction(() => {
        const doc = document.querySelector('iframe.gjs-frame').contentDocument;
        const text = [...doc.querySelectorAll('p')].find(node => node.textContent === 'Layout note');
        return text && doc.defaultView.getComputedStyle(text).paddingTop === '18px';
    });
    await checkMotion(page);
}

async function checkMotion(page) {
    const settle = locator => locator.evaluate(async node => {
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        await Promise.allSettled(node.getAnimations().map(animation => animation.finished));
    });
    const details = page.locator('#page-settings');
    await details.locator('summary').click();
    await page.emulateMedia({reducedMotion: 'reduce'});
    assert.equal(await details.evaluate(node => node.open && node.style.overflow === ''), true, 'changing motion preference settles disclosure');
    await page.emulateMedia({reducedMotion: 'no-preference'});
    await details.locator('summary').click(); await details.locator('summary').click();
    await page.waitForFunction(() => document.getElementById('page-settings').getAnimations().length === 0);
    assert.equal(await details.evaluate(node => node.open), true, 'rapid disclosure follows the last action');
    const sector = page.locator('#styles .gjs-sm-sector').first();
    const sectorToggle = sector.locator('.gjs-sm-sector-title');
    await sectorToggle.focus(); await sectorToggle.press('Enter');
    await settle(sector.locator(':scope > .gjs-sm-properties'));
    assert.equal(await sector.locator(':scope > .gjs-sm-properties').isVisible(), false);
    assert.equal(await sectorToggle.getAttribute('aria-expanded'), 'false');
    assert.equal(await sector.locator(':scope > .gjs-sm-properties').evaluate(node => node.inert), true);
    await sectorToggle.press('Space');
    await page.waitForFunction(() => document.querySelector('#styles .gjs-sm-sector > .gjs-sm-properties').getAnimations().length > 0);
    const layer = page.locator('#layers .gjs-layer').first();
    const layerToggle = layer.locator(':scope > .gjs-layer-item [data-toggle-open]');
    await layerToggle.focus(); await layerToggle.press('Enter');
    await settle(layer.locator(':scope > .gjs-layer-children'));
    assert.equal(await layer.locator(':scope > .gjs-layer-children').isVisible(), false);
    assert.equal(await layerToggle.getAttribute('aria-expanded'), 'false');
    await layerToggle.press('Space');
    await page.waitForFunction(() => document.querySelector('#layers .gjs-layer > .gjs-layer-children').getAnimations().length > 0);
    const moved = await page.evaluate(async () => {
        const e = grapesjs.editors[0], root = e.getWrapper();
        const models = root.append([{type:'page-text', content:'Motion A'}, {type:'page-text', content:'Motion B'}]);
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        await Promise.allSettled(models.flatMap(model => model.getEl().getAnimations().map(animation => animation.finished)));
        models[1].move(root, {at: models[0].index()});
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const animated = models.some(model => model.getEl().getAnimations().some(animation => animation.playState === 'running'));
        models[0].move(root, {at: models[1].index()});
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const styles = models.map(model => model.getStyle());
        models.forEach(model => model.remove());
        return {animated, styles};
    });
    assert.equal(moved.animated, true, 'component reordering animates in the real canvas');
    assert.ok(moved.styles.every(style => !style.transform && !style.transition), 'animation is not page data');
}
