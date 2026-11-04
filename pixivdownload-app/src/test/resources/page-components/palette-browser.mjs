import assert from 'node:assert/strict';

export async function checkPaletteDisclosure(page) {
    await page.locator('#add-tab').click();
    const group = page.locator('#blocks .gjs-block-category').filter({has: page.locator('[data-component-block="box"]')});
    const toggle = group.locator('.gjs-title'), content = group.locator('.gjs-blocks-c');
    const settled = async open => {
        await page.waitForFunction(open => {
            const group = document.querySelector('[data-component-block="box"]').closest('.gjs-block-category');
            const content = group.querySelector('.gjs-blocks-c');
            return content.getAnimations().length === 0 && (getComputedStyle(content).display !== 'none') === open
                && group.querySelector('.gjs-title').getAttribute('aria-expanded') === String(open) && content.inert === !open;
        }, open);
        assert.equal(await page.evaluate(() => window.grapesjs.editors[0].BlockManager.getCategories().get('layout').get('open')), open);
    };
    // 真实指针事件会在捕获和冒泡之间执行微任务，element.click() 无法复现这条路径。
    await toggle.click(); await settled(false);
    await toggle.press('Enter'); await settled(true);
    await toggle.press('Space'); await settled(false);
    await toggle.click();
    await page.waitForFunction(() => document.querySelector('[data-component-block="box"]').parentElement.getAnimations().length > 0);
    await toggle.click({force: true}); await settled(false);
    await toggle.click(); await settled(true);
    await toggle.click(); await settled(false);
    const catalogUrl = '**/api/page-editor/catalog';
    await page.route(catalogUrl, async route => {
        const response = await route.fetch(), body = await response.json();
        body.customComponents = [...(body.customComponents || []), {id: 'disclosure-refresh-fixture', name: 'Refresh fixture', enabled: false}];
        await route.fulfill({response, json: body});
    });
    const oldToggle = await toggle.elementHandle();
    try {
        await page.waitForFunction(element => !element.isConnected, oldToggle);
        await settled(false);
    } finally { await page.unroute(catalogUrl); await oldToggle.dispose(); }
    await page.emulateMedia({reducedMotion: 'reduce'});
    await toggle.click(); await settled(true);
    await toggle.click(); await settled(false);
    assert.equal(await content.evaluate(element => element.getAnimations().length), 0);
    await toggle.click(); await settled(true);
    await page.emulateMedia({reducedMotion: 'no-preference'});
}

export async function checkPageControlsAndPalette(page) {
    await checkPaletteDisclosure(page);
    const title = page.locator('#page-title'), settings = page.locator('#page-settings');
    const original = await title.inputValue();
    const footer = await page.evaluate(() => {
        const editor = window.grapesjs.editors[0];
        const model = editor.getWrapper().findType('page-box').find(item => item.get('role') === 'footer');
        editor.select(model);
        return {id: model.getId(), name: model.getName(), children: model.components().length};
    });
    await page.waitForFunction(name => document.querySelector('#inspector-selection').textContent === name, footer.name);
    assert.equal(await title.isVisible(), true, 'selecting a component preserves page settings');
    await settings.locator('summary').click();
    await page.waitForFunction(() => !document.querySelector('#page-settings').open);
    const before = await page.evaluate(() => {
        const editor = window.grapesjs.editors[0];
        return {project: editor.getProjectData(), undo: editor.UndoManager.getStack().length, selected: editor.getSelected().getId()};
    });
    await settings.locator('summary').focus();
    await settings.locator('summary').press('Enter');
    await page.waitForFunction(() => document.querySelector('#page-settings').open);
    await settings.locator('summary').press('Tab');
    assert.equal(await title.evaluate(element => element === document.activeElement && !element.inert), true);
    assert.deepEqual(await page.evaluate(() => {
        const editor = window.grapesjs.editors[0];
        return {project: editor.getProjectData(), undo: editor.UndoManager.getStack().length, selected: editor.getSelected().getId()};
    }), before, 'opening page settings keeps the canvas and selection');
    await title.fill('Title retained while selecting content');
    await page.evaluate(() => { const editor = window.grapesjs.editors[0]; editor.select(editor.getWrapper()); });
    await page.waitForFunction(() => window.grapesjs.editors[0].getSelected().get('type') === 'wrapper');
    assert.equal(await title.isVisible(), true);
    assert.equal(await title.inputValue(), 'Title retained while selecting content');
    await title.fill(original);
    await page.locator('#structure-tab').click();
    await page.locator('#selection').selectOption(footer.id);
    await page.locator('#add-tab').click();
    const groups = await page.locator('#blocks .gjs-block-category').evaluateAll(elements => elements.map(group =>
        [...group.querySelectorAll('[data-component-block]')].map(block => block.dataset.componentBlock)));
    assert.deepEqual(groups.find(group => group.includes('footer')), ['box', 'header', 'main', 'footer']);
    assert.deepEqual(groups.find(group => group.includes('text')), ['text', 'link']);
    const beforeDetails = await page.evaluate(() => {
        const editor = window.grapesjs.editors[0];
        return {project: editor.getProjectData(), undo: editor.UndoManager.getStack().length};
    });
    await page.locator('[data-component-block="text"]').click();
    assert.deepEqual(await page.evaluate(() => {
        const editor = window.grapesjs.editors[0];
        return {project: editor.getProjectData(), undo: editor.UndoManager.getStack().length};
    }), beforeDetails, 'viewing details does not create a temporary component or undo entry');
    assert.equal(await page.evaluate(() => window.grapesjs.editors[0].getSelected()?.getId()), footer.id, 'viewing details preserves the insertion target');
    assert.ok((await page.locator('#component-insertion-path').textContent()).endsWith(' › ' + footer.name));
    await page.locator('#component-add').click();
    const inserted = await page.evaluate(() => {
        const model = window.grapesjs.editors[0].getSelected();
        return {id: model.getId(), parent: model.parent().getId(), index: model.index(), droppable: model.get('droppable')};
    });
    assert.equal(inserted.parent, footer.id); assert.equal(inserted.index, footer.children); assert.equal(inserted.droppable, false);
    await page.locator('[data-component-block="link"]').click();
    assert.ok((await page.locator('#component-insertion-path').textContent()).endsWith(' › ' + footer.name));
    assert.ok((await page.locator('#component-insertion-order').textContent()).includes(await page.evaluate(() => window.grapesjs.editors[0].getSelected().getName())));
    assert.equal((await page.locator('#component-insertion-order').textContent()).includes('{name}'), false);
    await page.locator('#component-add').click();
    assert.deepEqual(await page.evaluate(() => {
        const model = window.grapesjs.editors[0].getSelected();
        return {parent: model.parent().getId(), previous: model.parent().components().at(model.index() - 1).getId()};
    }), {parent: footer.id, previous: inserted.id}, 'content is added after a leaf, not inside it');
    await page.locator('#undo').click(); await page.locator('#undo').click();
    assert.equal(await page.evaluate(id => window.grapesjs.editors[0].getWrapper().findType('page-box').find(model => model.getId() === id).components().length, footer.id), footer.children);
    await page.evaluate(() => { window.grapesjs.editors[0].select(); });
}
