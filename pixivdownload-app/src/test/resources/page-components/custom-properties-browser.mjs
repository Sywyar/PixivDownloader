import assert from 'node:assert/strict';

export async function checkCustomProperties({page, id, version, first}) {
    const title = page.getByLabel('Title', {exact: true}), count = page.getByLabel('Count', {exact: true});
    const configuration = () => page.evaluate(id => window.grapesjs.editors[0].getWrapper().findType('page-component')
        .filter(model => model.get('configuration').componentId === id)[1].get('configuration'), id);
    const select = index => page.evaluate(({id, index}) => {
        const editor = window.grapesjs.editors[0];
        editor.select(editor.getWrapper().findType('page-component').filter(model => model.get('configuration').componentId === id)[index]);
    }, {id, index});
    const edit = async (field, value) => { await field.fill(value); await field.press('Tab'); };
    const undo = () => page.locator('#undo').click();
    const redo = () => page.locator('#redo').click();
    const initial = await configuration();
    await page.evaluate(() => { window.grapesjs.editors[0].UndoManager.clear(); });
    await edit(title, 'Changed title');
    assert.equal((await configuration()).properties.title, 'Changed title');
    await undo(); assert.equal(await title.inputValue(), 'Second instance');
    await redo(); assert.equal(await title.inputValue(), 'Changed title');
    await undo();
    await edit(count, '2');
    assert.deepEqual((await configuration()).properties, {...initial.properties, count: '2'},
        'editing another property must not resurrect the undone title');
    await title.focus(); await title.evaluate(input => input.setSelectionRange(2, 5));
    const focused = await title.elementHandle();
    await undo();
    assert.equal(await focused.evaluate(input => input.isConnected && input.selectionStart === 2 && input.selectionEnd === 5), true,
        'property refresh preserves the field and text selection');
    const preview = page.frameLocator('iframe.gjs-frame').frameLocator('iframe[sandbox]').nth(1);
    for (const [input, encoded] of [['.5', '0.5'], ['0005', '5'], ['1e2', '100'], ['0', '0'], ['-2', '-2'], ['-0.125', '-0.125']]) {
        await edit(count, input);
        assert.equal((await configuration()).properties.count, encoded);
        await preview.getByText('number:' + encoded, {exact: true}).waitFor();
        const saved = page.waitForResponse(response => /\/documents\/[^/]+\/save$/.test(response.url()));
        await page.locator('#save').click(); assert.equal((await saved).status(), 200);
        await page.waitForFunction(() => document.querySelector('#status')?.dataset.state === 'success'
            && !document.querySelector('#save').disabled);
        await page.reload(); await page.waitForFunction(() => window.pageEditorReady);
        await select(1);
        await count.waitFor();
        assert.equal(await count.inputValue(), encoded, 'canonical number survives save and reload');
        await preview.getByText('number:' + encoded, {exact: true}).waitFor();
    }
    const valid = await configuration();
    for (const input of ['', 'NaN', 'Infinity', '1e309']) {
        await count.evaluate((field, value) => { field.value = value; field.dispatchEvent(new Event('change', {bubbles: true})); }, input);
        assert.deepEqual(await configuration(), valid, 'invalid number must not change the draft');
    }
    await edit(count, '3');
    await page.getByLabel('Enabled', {exact: true}).uncheck();
    assert.equal((await configuration()).properties.enabled, 'false');
    await undo(); assert.equal(await page.getByLabel('Enabled', {exact: true}).isChecked(), true);
    const versions = page.locator('#properties select'), apply = page.locator('#properties button');
    const oldInput = await title.elementHandle();
    await versions.selectOption(first); await apply.click();
    await page.waitForFunction(first => window.grapesjs.editors[0].getSelected().get('configuration').version === first, first);
    const oldVersion = await configuration();
    await oldInput.evaluate(input => { input.value = 'Stale field'; input.dispatchEvent(new Event('change', {bubbles: true})); });
    assert.deepEqual(await configuration(), oldVersion, 'detached version fields cannot overwrite the current configuration');
    await undo(); await title.waitFor(); assert.equal((await configuration()).version, version);
    await redo(); assert.equal((await configuration()).version, first);
    await undo(); await title.waitFor();
    for (const action of ['undo', 'switch']) {
        await edit(count, action === 'undo' ? '4' : '5');
        let arrived, release;
        const started = new Promise(resolve => arrived = resolve), resume = new Promise(resolve => release = resolve);
        const pattern = '**/api/page-editor/components/' + id + '/versions/' + first;
        let routeDone;
        await page.route(pattern, route => routeDone = (async () => {
            const response = await route.fetch(); arrived(); await resume;
            await route.fulfill({response}).catch(() => {});
        })());
        try {
            await versions.selectOption(first); await apply.click(); await started;
            if (action === 'undo') await undo();
            else await select(0);
            const expected = await configuration();
            release(); await routeDone;
            await page.waitForFunction(() => !document.querySelector('#properties button')?.disabled);
            assert.deepEqual(await configuration(), expected, 'pending version request is obsolete after ' + action);
        } finally { release(); await routeDone; await page.unroute(pattern); }
        await select(1);
        await title.waitFor();
    }
    await edit(title, 'Second instance');
    console.log('PASS custom properties: real undo/redo, focus, stale fields and requests, finite JSON numbers through preview/save/reload');
}
