import assert from 'node:assert/strict';

export async function checkComponentMotion(page) {
    const result = await page.evaluate(async () => {
        const $ = id => document.getElementById(id);
        const running = element => element.getAnimations().some(animation => animation.playState === 'running');
        document.getAnimations().filter(animation => Number.isFinite(animation.effect.getComputedTiming().endTime))
            .forEach(animation => animation.finish());
        const source = document.querySelector('#custom-code .cm-content').textContent;
        $('section-properties').click();
        const panel = $('component-properties-panel'), animation = panel.getAnimations()[0];
        const switches = !!animation;
        animation.currentTime = animation.effect.getTiming().duration / 2;
        const intermediate = Number(getComputedStyle(panel).opacity);
        $('section-code').click(); $('section-properties').click();
        const latest = !panel.hidden && $('component-code-panel').hidden && running(panel);
        $('property-add').click();
        const row = document.querySelector('.property-row:last-child');
        const added = running(row);
        row.querySelector('button').click();
        const removed = row.inert && row.getAttribute('aria-hidden') === 'true' && running(row);
        row.getAnimations().forEach(animation => animation.finish());
        await new Promise(requestAnimationFrame);
        await new Promise(requestAnimationFrame);
        const cleaned = !row.isConnected;
        $('section-code').click();
        const summary = document.querySelector('.source-help summary');
        summary.click(); summary.click(); summary.click();
        const details = summary.parentElement;
        details.getAnimations().forEach(animation => animation.finish());
        await new Promise(requestAnimationFrame);
        await new Promise(requestAnimationFrame);
        const reopened = details.open && summary.getAttribute('aria-expanded') === 'true' && !details.lastElementChild.inert;
        summary.click();
        const preserved = document.querySelector('#custom-code .cm-content').textContent === source;
        const abort = new AbortController();
        const {createViewMotion} = await import('/page-editor/view-motion.mjs');
        const motion = createViewMotion(abort.signal), probe = document.createElement('div');
        probe.textContent = 'Motion cleanup'; document.body.append(probe);
        motion.remove(probe); abort.abort();
        const disposed = !probe.isConnected && probe.getAnimations().length === 0;
        return {switches, intermediate, latest, added, removed, cleaned, reopened, preserved, disposed};
    });
    assert.ok(result.switches && result.intermediate > 0 && result.intermediate < 1, 'section transition renders intermediate frames');
    for (const key of ['latest', 'added', 'removed', 'cleaned', 'reopened', 'preserved', 'disposed']) assert.equal(result[key], true, key);
    await page.emulateMedia({reducedMotion: 'reduce'});
    await page.waitForFunction(() => !document.querySelector('.source-help').open);
    assert.deepEqual(await page.evaluate(() => {
        document.querySelector('#section-properties').click();
        const panel = document.querySelector('#component-properties-panel');
        return {hidden: panel.hidden, animations: panel.getAnimations().length, opacity: getComputedStyle(panel).opacity,
            disclosureOpen: document.querySelector('.source-help').open};
    }), {hidden: false, animations: 0, opacity: '1', disclosureOpen: false});
    await page.locator('#section-code').click();
    await page.emulateMedia({reducedMotion: 'no-preference'});
}
