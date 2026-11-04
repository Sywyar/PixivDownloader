import assert from 'node:assert/strict';

export async function checkComponentFrameLayout({page, api}) {
    const source = {name: 'Collapsing footer', html: '<footer>ICP example</footer>',
        css: 'footer { padding: 12px 16px; line-height: 20px; box-sizing: content-box; }',
        properties: {size: {type: 'number', label: 'Height', default: 20},
            hide: {type: 'boolean', label: 'Hide', default: false}, restore: {type: 'boolean', label: 'Restore', default: false}},
        javascript: `const footer = root.querySelector('footer'); let timer;
            function update({properties}) {
                clearTimeout(timer); footer.hidden = false; footer.style.height = properties.size + 'px';
                if (properties.hide) timer = setTimeout(() => {
                    footer.hidden = true;
                    if (properties.restore) timer = setTimeout(() => { footer.hidden = false; }, 1000);
                }, 150);
            }
            update(context); return {update, destroy() { clearTimeout(timer); }};`};
    const snapshot = await api('components/preview', {source});
    assert.equal(snapshot.status, 200);
    const originalTheme = await page.evaluate(() => window.PixivTheme.current());
    try {
        await page.evaluate(async url => {
            const {mountFrame} = await import('/page-components/runtime.mjs');
            const probe = document.createElement('section'); probe.id = 'frame-layout-probe';
            probe.style.cssText = 'position:fixed;top:0;left:0;width:320px;z-index:2147483647';
            document.body.append(probe); window.frameLayoutInstances = [];
            for (const mode of ['preview', 'edit', 'published']) {
                const root = document.createElement('div'); root.dataset.mode = mode; probe.append(root);
                window.frameLayoutInstances.push(await mountFrame(root, url, {}, {mode, title: 'Empty component'}));
            }
        }, snapshot.body.url);
        const probe = page.locator('#frame-layout-probe');
        for (const theme of ['dark', 'light', 'dark']) {
            await page.evaluate(theme => {
                window.PixivTheme.apply(theme, false, false);
                for (const root of document.querySelectorAll('#frame-layout-probe > div')) {
                    root.style.background = theme === 'dark' ? 'rgb(24, 32, 43)' : 'rgb(240, 243, 246)';
                }
            }, theme);
            await page.waitForFunction(() => [...document.querySelectorAll('#frame-layout-probe iframe')]
                .every(frame => frame.getBoundingClientRect().height === 44));
            const child = await (await probe.locator('iframe').first().elementHandle()).contentFrame();
            await child.waitForFunction(theme => getComputedStyle(document.documentElement).colorScheme === theme, theme);
            await page.evaluate(async () => {
                const root = document.querySelector('#frame-layout-probe');
                await Promise.allSettled(document.getAnimations().filter(animation => animation.transitionProperty === 'color'
                    && animation.effect.target.contains(root)).map(animation => animation.finished));
            });
            const color = await probe.locator('div[data-mode]').first().evaluate(node => getComputedStyle(node).color);
            await child.waitForFunction(color => getComputedStyle(document.querySelector('footer')).color === color, color, {timeout: 5000});
            // 实际像素能发现 color-scheme 不匹配产生的白底，透明 CSS 声明本身不能。
            const screenshot = await probe.locator('iframe').first().screenshot();
            const pixel = await page.evaluate(async base64 => {
                const image = new Image(); image.src = 'data:image/png;base64,' + base64; await image.decode();
                const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
                const ctx = canvas.getContext('2d'); ctx.drawImage(image, 0, 0); return [...ctx.getImageData(2, 2, 1, 1).data];
            }, screenshot.toString('base64'));
            assert.deepEqual(pixel, theme === 'dark' ? [24, 32, 43, 255] : [240, 243, 246, 255]);
        }
        const update = values => page.evaluate(values => window.frameLayoutInstances.forEach(instance => instance.update(values)), values);
        const height = value => page.waitForFunction(value => [...document.querySelectorAll('#frame-layout-probe iframe')]
            .every(frame => frame.getBoundingClientRect().height === value), value);
        for (const hidden of [true, false]) {
            for (const element of await probe.locator('iframe').elementHandles()) {
                const child = await element.contentFrame();
                await child.locator('#root').evaluate((root, hidden) => { root.hidden = hidden; }, hidden);
            }
            await height(hidden ? 0 : 44);
        }
        await update({size: '180'}); await height(204);
        await update({size: '20'}); await height(44);
        await update({hide: 'true'}); await height(0);
        assert.equal(await probe.locator('[data-mode="preview"]').evaluate(node => node.getBoundingClientRect().height), 0);
        assert.equal(await probe.locator('[data-mode="published"]').evaluate(node => node.getBoundingClientRect().height), 0);
        await probe.locator('[data-mode="edit"] [data-component-placeholder]').waitFor({state: 'visible'});
        await update({}); await height(44);
        await update({hide: 'true', restore: 'true'}); await height(0); await height(44);
        await update({size: '6000'}); await height(4096);
        await update({}); await height(44);
    } finally {
        await page.evaluate(theme => {
            window.frameLayoutInstances?.forEach(instance => instance.destroy()); delete window.frameLayoutInstances;
            document.querySelector('#frame-layout-probe')?.remove();
            window.PixivTheme.apply(theme, false, false);
        }, originalTheme);
        await api('components/previews/' + snapshot.body.token + '/close', {});
    }
}
