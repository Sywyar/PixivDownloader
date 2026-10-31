import assert from 'node:assert/strict';

/** 全新匿名浏览器延迟发布请求，观察初始 HTML、翻译和回退期间的实际标题。 */
export async function checkPublishedTitle(browser, origin, expected) {
    for (const outcome of ['mounted', 'unavailable', 'empty-title', 'withdrawn']) {
        const context = await browser.newContext({locale: 'en-US'});
        await context.addCookies([{name: 'pixiv_lang', value: 'en-US', url: origin}]);
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        let release;
        const ready = new Promise(resolve => { release = resolve; });
        await page.addInitScript(() => {
            window.observedTitles = [];
            new MutationObserver(() => {
                if (document.title && window.observedTitles.at(-1) !== document.title) {
                    window.observedTitles.push(document.title);
                }
            }).observe(document, {subtree: true, childList: true, characterData: true});
        });
        await page.route('**/api/web/page-publications/**', async route => {
            if (route.request().method() === 'HEAD') {
                return outcome === 'withdrawn' ? route.fulfill({status: 404}) : route.continue();
            }
            await ready;
            if (outcome === 'unavailable') return route.fulfill({status: 503});
            if (outcome === 'empty-title') {
                const response = await route.fetch();
                const view = await response.json();
                return route.fulfill({response, json: {...view, title: ''}});
            }
            return route.continue();
        });
        try {
            const publication = page.waitForRequest(request => request.method() === 'GET'
                && request.url().includes('/api/web/page-publications/'));
            await page.goto(origin + '/login.html', {waitUntil: 'domcontentloaded'});
            await publication;
            await page.locator('.pixiv-lang-switcher__select').waitFor();
            assert.equal(await page.title(), expected, 'published title is present while the layout request is held');
            assert.equal(await page.locator('title[data-pixiv-page-title]').count(), 1);
            await page.locator('.pixiv-lang-switcher__select').selectOption('ja-JP');
            await page.waitForFunction(() => document.documentElement.lang.startsWith('ja'));
            assert.deepEqual(await page.evaluate(() => window.observedTitles), [expected], 'no default-title flash during initialization or language change');
            release();
            assert.equal(await page.locator('#default-page').count(), 0, 'visitors cannot switch away from the managed layout');
            if (outcome === 'mounted' || outcome === 'withdrawn') {
                await page.waitForFunction(() => document.getElementById('native-page').hidden);
                assert.equal(await page.title(), expected);
                if (outcome === 'mounted') {
                    await page.locator('.pixiv-lang-switcher__select').selectOption('en-US');
                    await page.waitForFunction(() => document.documentElement.lang.startsWith('en'));
                    assert.equal(await page.title(), expected);
                    assert.deepEqual(errors, []);
                    continue;
                }
                await page.waitForFunction(() => !document.getElementById('native-page').hidden);
            } else if (outcome === 'empty-title') {
                await page.waitForFunction(() => document.getElementById('native-page').hidden);
            }
            await page.waitForFunction(title => document.title !== title, expected);
            assert.equal(await page.title(), await page.evaluate(() => lt('page.title')));
            await page.locator('.pixiv-lang-switcher__select').selectOption('en-US');
            await page.waitForFunction(() => document.documentElement.lang.startsWith('en'));
            assert.equal(await page.title(), await page.evaluate(() => lt('page.title')));
            assert.deepEqual(errors, []);
        } finally {
            release();
            await context.close();
        }
    }
}
