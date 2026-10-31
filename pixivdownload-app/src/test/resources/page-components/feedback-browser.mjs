import assert from 'node:assert/strict';

export async function checkToastPosition(page) {
    const box = await page.locator('.pixiv-feedback-toast-host').boundingBox();
    const viewport = await page.evaluate(() => ({width: document.documentElement.clientWidth, height: innerHeight}));
    assert.ok(Math.abs(viewport.width - box.x - box.width - 16) < 2, 'notifications align to the viewport right inset');
    assert.ok(Math.abs(viewport.height - box.y - box.height - 16) < 2, 'notifications align to the viewport bottom inset');
    const close = page.locator('.pixiv-feedback-toast-close:visible');
    if (!(await page.locator('.pixiv-feedback-toast--progress').count())) assert.deepEqual(await close.evaluate(el => {
        const style = getComputedStyle(el);
        return [style.width, style.height, style.padding];
    }), ['32px', '32px', '0px'], 'page styles cannot change notification controls');
}

export async function checkToastCountdown(page) {
    const toast = page.locator('.pixiv-feedback-toast');
    const border = toast.locator('.pixiv-feedback-toast-countdown');
    const scale = () => border.evaluate(el => new DOMMatrixReadOnly(getComputedStyle(el).transform).a);
    assert.equal(await border.textContent(), '', 'remaining time is conveyed by the border');
    assert.equal(await border.getAttribute('aria-hidden'), 'true');
    const inset = await toast.evaluate(el => el.getBoundingClientRect().bottom - el.querySelector('.pixiv-feedback-toast-countdown').getBoundingClientRect().bottom);
    assert.ok(Math.abs(inset - 1) < 1, 'countdown sits on the bottom border: ' + inset);
    await page.waitForTimeout(600);
    await toast.hover();
    const paused = await scale();
    assert.ok(paused > 0 && paused < 1, 'border shrinks continuously');
    await page.waitForTimeout(600);
    assert.ok(Math.abs(await scale() - paused) < .01, 'hover pauses the visual countdown');
    await toast.locator('button').focus();
    await page.mouse.move(0, 0);
    await page.waitForTimeout(600);
    assert.ok(Math.abs(await scale() - paused) < .01, 'focus keeps the border paused after pointer departure');
    await page.locator('header a').first().focus();
    await page.waitForTimeout(400);
    assert.ok(await scale() < paused - .03, 'resume continues from the remaining length');
    await checkToastPosition(page);
    await toast.waitFor({state: 'detached', timeout: 5500});
}

export async function checkLibraryFeedback(page) {
    await page.waitForFunction(() => document.querySelector('#custom-message').dataset.state === 'idle');
    await page.route('**/api/page-editor/components', route => route.fulfill({
        status: 503, contentType: 'application/json', body: JSON.stringify({code: 'PAGE_OPERATION_FAILED'})
    }), {times: 1});
    await page.locator('#component-refresh').click();
    await page.locator('.pixiv-feedback-toast--error').waitFor();
    await checkToastPosition(page);
    const header = await page.locator('.editor-header').boundingBox();
    await page.locator('#component-refresh').click();
    await page.locator('.pixiv-feedback-toast').waitFor({state: 'detached'});
    assert.deepEqual(await page.locator('.editor-header').boundingBox(), header, 'library recovery does not shift its toolbar');
}

export async function checkEditorFeedback(page, clickConfirm) {
    await page.waitForFunction(() => document.getAnimations().every(animation => animation.effect?.getTiming().iterations === Infinity || animation.playState === 'finished'));
    const title = page.locator('#page-title'), original = await title.inputValue();
    const feedback = page.locator('.pixiv-feedback-toast');
    const layout = () => page.evaluate(() => ['.editor-header', '.document-buttons', '#canvas-area', '#preferences'].map(selector => {
        const rect = document.querySelector(selector).getBoundingClientRect();
        return [rect.x, rect.y, rect.width, rect.height];
    }));
    const initialLayout = await layout();
    const aligned = async () => {
        assert.deepEqual(await layout(), initialLayout, 'feedback must never change toolbar or canvas layout');
        await checkToastPosition(page);
    };
    const state = kind => page.waitForFunction(kind => document.querySelector('#document-feedback').dataset.state === kind, kind);
    const saveUrl = '**/api/page-editor/documents/*/save';
    let release;
    const held = new Promise(resolve => { release = resolve; });
    const entered = page.waitForRequest(request => request.url().includes('/api/page-editor/documents/') && request.url().endsWith('/save'));
    await page.route(saveUrl, async route => { await held; await route.continue(); }, {times: 1});
    await feedback.waitFor({state: 'detached', timeout: 7000});
    await title.fill('Feedback browser draft');
    assert.equal(await page.locator('#document-dirty').getAttribute('aria-hidden'), 'false');
    assert.equal(await feedback.count(), 0, 'editing marks the document without a notification');
    await page.locator('#save').click();
    try {
        await entered;
        await state('busy');
        await aligned();
        assert.equal(await page.locator('#save').isDisabled(), true);
        assert.equal(await page.locator('#publish').isDisabled(), true);
        assert.equal(await feedback.evaluate(element => element.closest('[aria-busy="true"]') === null), true, 'progress announcements are not deferred by an aria-busy ancestor');
        await title.fill('Edit while saving');
        await state('busy');
    } finally { release(); }
    await state('idle');
    assert.equal(await title.inputValue(), 'Edit while saving');
    assert.equal(await page.locator('#document-dirty').getAttribute('aria-hidden'), 'false', 'edits made during save remain unsaved');
    await page.locator('#save').click(); await state('success');
    assert.equal(await page.locator('#document-dirty').getAttribute('aria-hidden'), 'true');
    assert.equal(await page.locator('.pixiv-feedback-toast-detail').isVisible(), true);
    await aligned();
    const saved = await feedback.textContent();
    let releasePublish;
    const heldPublish = new Promise(resolve => { releasePublish = resolve; });
    const publishing = page.waitForRequest(request => request.url().includes('/api/page-editor/documents/') && request.url().endsWith('/publish'));
    await page.route('**/api/page-editor/documents/*/publish', async route => { await heldPublish; await route.continue(); }, {times: 1});
    await page.locator('#publish').click(); await clickConfirm();
    try {
        await publishing; await state('busy');
        assert.equal(await page.locator('#publish').isDisabled(), true);
    } finally { releasePublish(); }
    await state('success');
    await aligned();
    assert.notEqual(await feedback.textContent(), saved, 'draft save and publication communicate different outcomes');
    const published = await feedback.textContent();
    await title.fill('Unsaved after publishing'); await state('idle');
    assert.equal(await feedback.textContent(), published, 'editing does not replace completion feedback with an unsaved notification');
    assert.equal(await page.locator('#document-dirty').getAttribute('aria-hidden'), 'false');
    await page.route(saveUrl, route => route.fulfill({status: 409, contentType: 'application/json', body: JSON.stringify({code: 'PAGE_REVISION_CONFLICT'})}), {times: 1});
    await page.locator('#save').click(); await state('error');
    assert.equal(await feedback.locator('[role=alert]').count(), 1);
    assert.equal(await title.inputValue(), 'Unsaved after publishing');
    assert.equal(await page.locator('#document-dirty').getAttribute('aria-hidden'), 'false', 'failed saves keep the document dirty');
    assert.equal(await page.locator('#save').isDisabled(), false);
    await page.waitForTimeout(3800);
    await state('error');
    assert.equal(await feedback.isVisible(), true, 'errors remain available beyond a transient notification');
    await aligned();
    assert.equal(await feedback.locator('.pixiv-feedback-toast-countdown').isVisible(), true);
    await page.mouse.move(0, 0);
    await feedback.waitFor({state: 'detached', timeout: 6000});
    assert.equal(await title.inputValue(), 'Unsaved after publishing', 'automatic dismissal preserves the draft');
    await page.locator('#save').click(); await state('success');
    await feedback.locator('button').focus();
    await page.keyboard.press('Enter');
    await feedback.waitFor({state: 'detached'});
    await page.locator('#save').click(); await state('success');
    await aligned();
    await page.mouse.move(0, 0);
    await feedback.waitFor({state: 'detached', timeout: 6000});
    assert.deepEqual(await layout(), initialLayout, 'automatic dismissal leaves the workspace in place');
    await title.fill(original);
    await page.locator('#save').click(); await state('success');
    await checkToastCountdown(page);
}
