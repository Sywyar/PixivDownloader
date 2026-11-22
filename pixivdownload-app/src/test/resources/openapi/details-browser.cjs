const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

async function verifyLoadingTheme(browser, origin, cookies) {
    const page = await browser.newPage({ locale: 'zh-CN', colorScheme: 'light' });
    let resume;
    const catalog = new Promise(resolve => { resume = resolve; });
    try {
        await page.context().addCookies(cookies);
        await page.addInitScript(() => localStorage.setItem('colorMode', 'dark'));
        await page.route('**/api/openapi.json?*', async route => { await catalog; await route.continue(); });
        await page.goto(origin + '/openapi?lang=zh-CN', { waitUntil: 'domcontentloaded' });
        const indicator = page.getByRole('progressbar');
        await indicator.waitFor();
        const scheme = () => page.locator('body').evaluate(el => getComputedStyle(el).colorScheme);
        assert.equal(await scheme(), 'dark');
        await page.emulateMedia({ reducedMotion: 'reduce' });
        assert.equal(await indicator.isVisible(), false);
        assert.ok(await page.getByRole('status').isVisible());
        resume();
        await page.locator('#reference-access').waitFor({ state: 'detached' });
        await page.locator('.t-doc__sidebar').waitFor();
        assert.equal(await scheme(), 'dark');
        console.log('OpenAPI saved loading theme and reduced motion passed');
    } finally {
        resume();
        await page.close();
    }
}

async function main() {
    const [origin, cookieName] = process.argv.slice(2);
    const browser = await chromium.launch();
    try {
        const page = await browser.newPage({ locale: 'zh-CN', colorScheme: 'dark' });
        await page.context().addCookies([{ name: cookieName, value: fs.readFileSync(0, 'utf8'), url: origin, httpOnly: true }]);
        await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin });
        await verifyLoadingTheme(browser, origin, await page.context().cookies(origin));
        const operations = [
            ['core-downloads', '/api/download/tasks', 'task.outputPath'],
            ['core-batch', '/api/download/batches', 'batch.work.capacity-exceeded'],
            ['download-workbench-pixiv-art', '/api/pixiv/user/{userId}/downloads', 'schedule.pixiv.discovery-not-found']
        ];
        for (const [tag, path, term] of operations) {
            await page.goto(origin + '/openapi?lang=zh-CN#tag/' + tag + '/POST' + path);
            const operation = page.locator('section[id$="/POST' + path + '"]');
            const panel = operation.locator('details.reference-disclosure');
            const toggle = panel.locator('summary', { hasText: '详细信息' });
            await toggle.waitFor();
            assert.equal(await panel.count(), 1);
            assert.equal(await panel.evaluate(el => el.open), false);
            const body = panel.locator(':scope > div');
            assert.equal(await body.isVisible(), false);
            assert.ok(await operation.locator('.responses-list--tree').isVisible());
            if (path === '/api/download/tasks') {
                const list = page.locator('section[id$="/GET/api/download/tasks"]');
                await list.locator('.operation-description').waitFor();
                assert.equal(await list.locator('.reference-disclosure').count(), 0);
                await operation.getByRole('button', { name: '复制为 Markdown' }).click();
                const markdown = await page.evaluate(() => navigator.clipboard.readText());
                assert.ok(markdown.includes('### 详细信息') && markdown.includes(term));
            }
            await toggle.press('Enter');
            await body.waitFor();
            assert.ok((await body.innerText()).includes(term), 'Missing chapter text on ' + path);
            if (path === '/api/download/tasks') {
                for (const name of ['artwork', 'novel'])
                    assert.ok(await body.getByRole('heading', { name, exact: true }).isVisible());
            }
            await toggle.press('Space');
            await body.waitFor({ state: 'hidden' });
            assert.ok(await toggle.evaluate(el => el === document.activeElement));
            console.log('OpenAPI details disclosure passed at ' + new URL(page.url()).hash);
        }
        await page.setViewportSize({ width: 390, height: 844 });
        await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
        await page.goto(origin + '/openapi?lang=en-US#tag/core-downloads/POST/api/download/tasks');
        const panel = page.locator('section[id$="/POST/api/download/tasks"] details.reference-disclosure');
        const toggle = panel.locator('summary', { hasText: 'Details' });
        await toggle.waitFor();
        const box = await toggle.boundingBox();
        assert.ok(box.height >= 44 && box.x >= 0 && box.x + box.width <= 391);
        assert.equal(await panel.evaluate(el => el.open), false);
        await toggle.click({ position: { x: box.width - 12, y: box.height / 2 } });
        await panel.locator('div').first().waitFor();
        assert.ok((await panel.innerText()).includes('task.outputPath'));
        console.log('OpenAPI narrow-screen English disclosure passed');
    } finally {
        await browser.close();
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
