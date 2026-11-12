import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {fileURLToPath, pathToFileURL} from 'node:url';

const {chromium} = await import(process.env.PAGE_EDITOR_PLAYWRIGHT ? pathToFileURL(process.env.PAGE_EDITOR_PLAYWRIGHT).href : 'playwright');
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../..');
const root = path.resolve(process.argv[2] || path.join(repository, 'target/cookie-isolation-browser'));
const classpath = (await fs.readFile(process.argv[3] || path.join(repository, 'target/cookie-isolation-classpath.txt'), 'utf8')).trim();
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const live = new Set(), results = [], errors = [], launches = [];
const browser = await chromium.launch({headless: true, ...(process.env.PAGE_EDITOR_CHROMIUM ? {executablePath: process.env.PAGE_EDITOR_CHROMIUM} : {})});
const argument = value => '"' + value.replaceAll('\\', '\\\\').replaceAll('"', '\\"') + '"';

async function start(name, mode, port = 0, applicationMode = 'solo') {
    const directory = path.join(root, name);
    await fs.mkdir(directory, {recursive: true});
    await fs.rm(path.join(directory, 'port.txt'), {force: true});
    const args = ['-Djava.awt.headless=true', ...(mode === null ? [] : ['-Dpixivdownload.plugin-dev.enabled=' + mode]),
        '-cp', classpath, 'top.sywyar.pixivdownload.plugin.security.CookieIsolationBrowserServer', directory, String(port), applicationMode];
    const argsPath = path.join(directory, 'java.args');
    await fs.writeFile(argsPath, args.map(argument).join('\n'), 'utf8');
    const log = await fs.open(path.join(directory, 'server.log'), 'w');
    const process = spawn(java, ['@' + argsPath], {cwd: repository, stdio: ['ignore', log.fd, log.fd], windowsHide: true});
    await log.close(); live.add(process);
    process.once('exit', () => live.delete(process));
    const deadline = Date.now() + 120_000;
    while (true) {
        if (process.exitCode !== null) throw new Error(`Fixture ${name} exited: ${await fs.readFile(path.join(directory, 'server.log'), 'utf8')}`);
        try {
            port = Number(await fs.readFile(path.join(directory, 'port.txt'), 'utf8'));
            if (port > 0) break;
        } catch (error) { if (error.code !== 'ENOENT') throw error; }
        assert.ok(Date.now() < deadline, `Fixture readiness: ${name}`);
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    launches.push({name, developerMode: mode ?? 'unset', applicationMode, port, pid: process.pid});
    return {name, mode, port, applicationMode, process, origin: 'http://localhost:' + port,
        cookie: original => mode === 'true' ? `pixiv_dev_${port}_${original}` : original};
}

async function stop(instance) {
    if (instance.process.exitCode !== null) return;
    const exited = new Promise(resolve => instance.process.once('exit', resolve));
    instance.process.kill(); await exited;
}

async function restart(instance, mode = instance.mode) {
    await stop(instance);
    return start(instance.name, mode, instance.port, instance.applicationMode);
}

async function pageAt(context, instance) {
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
    await page.goto(instance.origin + '/cookie-fixture.html');
    await page.waitForFunction(() => window.cookieReady);
    return page;
}

async function call(page, url, data, headers = {}) {
    return page.evaluate(async ({url, data, headers}) => {
        const response = await fetch(url, {method: data === undefined ? 'GET' : 'POST', headers: {'Content-Type': 'application/json', ...headers},
            ...(data === undefined ? {} : {body: JSON.stringify(data)})});
        const text = await response.text();
        return {status: response.status, body: text && response.headers.get('Content-Type')?.includes('json') ? JSON.parse(text) : null};
    }, {url, data, headers});
}

async function login(page, rememberMe = true) {
    assert.equal((await call(page, '/api/auth/login', {username: 'cookie-fixture', password: 'cookie-fixture-password', rememberMe})).status, 200);
}
async function valid(page, expected) { assert.deepEqual((await call(page, '/api/auth/check')).body, {valid: expected}); }
async function logout(page) { assert.equal((await call(page, '/api/auth/logout', {})).status, 200); }
async function invite(page) { assert.equal((await call(page, '/api/auth/invite-redeem', {code: 'A'.repeat(24)})).status, 200); }
async function prepare(page) { return (await call(page, '/api/cookie-fixture/prepare', {})).body.token; }
async function stage(page, token, nativeTicket = false) {
    return (await call(page, '/api/cookie-fixture/stage?token=' + token + '&nativeTicket=' + nativeTicket, {})).status;
}
async function consume(page, token) { return (await call(page, '/api/cookie-fixture/consume', {}, token ? {'X-Pixiv-Hook-Ticket': token} : {})).status; }
async function nativeSubmit(page) {
    const response = page.waitForResponse(response => response.url().endsWith('/api/cookie-fixture/consume'));
    await page.evaluate(() => {
        const iframe = document.createElement('iframe'); iframe.name = 'native-proof'; iframe.hidden = true; document.body.append(iframe);
        const form = document.createElement('form'); form.method = 'POST'; form.action = '/api/cookie-fixture/consume';
        form.target = iframe.name; document.body.append(form); form.requestSubmit(); form.remove();
    });
    return (await response).status();
}
async function language(page, value) {
    await page.locator('#language select').selectOption(value);
    await page.waitForFunction(value => window.cookieClient.lang === value, value);
    assert.equal((await call(page, '/api/cookie-fixture/locale')).body.lang, value);
}

async function isolatedPair(label, firstMode) {
    let a = await start(label + '/a', firstMode), b = await start(label + '/b', 'true');
    const context = await browser.newContext({locale: 'en-US'});
    try {
        const pa = await pageAt(context, a), pb = await pageAt(context, b);
        await login(pa, false); await login(pb, true); await valid(pa, true); await valid(pb, true);
        for (const page of [pa, pb]) assert.equal((await call(page, '/api/cookie-fixture/admin')).status, 200);
        let cookies = await context.cookies();
        for (const instance of [a, b]) {
            const cookie = cookies.find(cookie => cookie.name === instance.cookie('pixiv_session'));
            assert.ok(cookie.httpOnly); assert.equal(cookie.sameSite, 'Strict'); assert.equal(cookie.path, '/'); assert.equal(cookie.secure, false);
        }
        assert.equal(cookies.find(cookie => cookie.name === a.cookie('pixiv_session')).expires, -1);
        assert.ok(cookies.find(cookie => cookie.name === b.cookie('pixiv_session')).expires > Date.now() / 1000 + 29 * 86400);
        await logout(pa); await valid(pa, false); await valid(pb, true);
        await login(pa); await logout(pb); await valid(pa, true); await valid(pb, false); await login(pb);

        await invite(pa); await valid(pa, false); await valid(pb, true);
        assert.equal((await call(pa, '/api/cookie-fixture/guest')).status, 200);
        assert.equal((await call(pa, '/api/cookie-fixture/admin')).status, 403);
        await logout(pb); assert.equal((await call(pa, '/api/cookie-fixture/guest')).status, 200); await login(pb);
        await invite(pb); await call(pa, '/api/cookie-fixture/invalidate', {});
        assert.equal((await call(pa, '/api/cookie-fixture/guest')).status, 403);
        assert.equal((await call(pa, '/api/cookie-fixture/admin')).status, 403);
        await valid(pa, false); assert.equal((await call(pb, '/api/cookie-fixture/guest')).status, 200);
        await login(pa); assert.equal((await call(pb, '/api/cookie-fixture/guest')).status, 200);
        await logout(pa); assert.equal((await call(pb, '/api/cookie-fixture/guest')).status, 200); await login(pa); await login(pb);

        const ta = await prepare(pa), tb = await prepare(pb);
        assert.equal(await stage(pb, tb), 200); assert.equal(await consume(pb, tb), 200);
        assert.equal(await stage(pa, ta), 200); assert.equal(await consume(pa, ta), 200);
        assert.equal(await consume(pa, ta), 409); assert.equal(await consume(pb, tb), 409);
        const aTokens = [await prepare(pa), await prepare(pa)];
        for (const token of aTokens) assert.equal(await stage(pa, token, true), 200);
        const bTokens = [await prepare(pb), await prepare(pb)];
        for (const token of bTokens) assert.equal(await stage(pb, token, true), 200);
        const afterOtherInstance = await prepare(pa);
        aTokens.push(afterOtherInstance);
        assert.equal(await stage(pa, afterOtherInstance, true), 200);
        cookies = await context.cookies();
        for (const [instance, tokens] of [[a, aTokens], [b, bTokens]]) {
            for (const token of tokens) assert.ok(cookies.some(cookie => cookie.name === instance.cookie('pixiv_hook_ticket_') + token));
        }
        assert.equal(await nativeSubmit(pa), 200); assert.equal(await nativeSubmit(pb), 200);
        assert.equal(await nativeSubmit(pa), 200); assert.equal(await nativeSubmit(pb), 200);
        assert.equal(await nativeSubmit(pa), 200);
        assert.equal(await nativeSubmit(pa), 409); assert.equal(await nativeSubmit(pb), 409);
        assert.ok(!(await context.cookies()).some(cookie => cookie.name.includes('pixiv_hook_ticket_')));

        await language(pa, 'zh-CN'); await language(pb, 'zh-CN'); await language(pb, 'en-US');
        assert.equal((await call(pa, '/api/cookie-fixture/locale')).body.lang, 'zh-CN');
        await pb.route('**/api/i18n/meta?*', route => route.abort());
        await pb.reload(); await pb.waitForFunction(() => window.cookieReady);
        assert.equal(await pb.evaluate(() => window.cookieClient.cookieName), b.cookie('pixiv_lang'));
        await language(pb, 'zh-CN'); await language(pb, 'en-US');
        assert.equal((await call(pa, '/api/cookie-fixture/locale')).body.lang, 'zh-CN');
        assert.equal(await pa.evaluate(() => localStorage.getItem('pixiv.lang')), 'zh-CN');
        assert.equal(await pb.evaluate(() => localStorage.getItem('pixiv.lang')), 'en-US');

        for (const instance of [a, b]) {
            assert.equal((await context.request.post(instance.origin + '/api/cookie-fixture/invalidate')).status(), 403);
            assert.equal((await context.request.post(instance.origin + '/api/cookie-fixture/invalidate',
                {headers: {Origin: instance === a ? b.origin : a.origin}})).status(), 403);
            assert.equal((await context.request.post(instance.origin + '/api/cookie-fixture/invalidate', {headers: {Origin: instance.origin}})).status(), 200);
        }
        const credential = (await context.cookies()).find(cookie => cookie.name === a.cookie('pixiv_session'));
        await context.clearCookies({name: credential.name});
        assert.equal((await call(pa, '/api/auth/check', undefined, {'X-Session-Token': credential.value})).body.valid, true);
        await context.addCookies([{...credential, value: 'invalid-current'}]);
        assert.equal((await call(pa, '/api/auth/check', undefined, {'X-Session-Token': credential.value})).body.valid, false);
        await context.addCookies([credential]);
        await login(pa, true); await login(pb, false);
        a = await restart(a); b = await restart(b);
        await valid(pa, true); await valid(pb, false);
        results.push({scenario: label, passed: ['login', 'cookie-attributes', 'logout-both-directions', 'invite-priority-and-revocation',
            'challenge-interleaving', 'header-ticket-replay', 'concurrent-native-forms', 'language-api-and-static-fallback',
            'csrf-missing-and-cross-port-origin', 'header-priority', 'restart-long-and-short-sessions']});
    } finally { await context.close(); await stop(a); await stop(b); }
}

async function normalPair() {
    let a = await start('normal/a', null), b = await start('normal/b', 'false');
    const context = await browser.newContext({locale: 'en-US'});
    try {
        const pa = await pageAt(context, a), pb = await pageAt(context, b);
        await login(pa); await login(pb); await valid(pa, false); await valid(pb, true);
        await logout(pa); await valid(pb, false);
        const token = await prepare(pa); assert.equal(await stage(pa, token, true), 200);
        const other = await prepare(pb); assert.equal(await stage(pb, other, true), 200);
        assert.ok(!(await context.cookies()).some(cookie => cookie.name === 'pixiv_hook_ticket_' + token));
        await language(pa, 'zh-CN'); assert.equal((await call(pb, '/api/cookie-fixture/locale')).body.lang, 'zh-CN');
        assert.ok((await context.cookies()).every(cookie => !cookie.name.startsWith('pixiv_dev_')));
        results.push({scenario: 'normal-unset-and-false', passed: ['original-login-overwrite', 'original-logout-clearing', 'original-ticket-cleanup', 'original-language-sharing', 'original-names']});
    } finally { await context.close(); await stop(a); await stop(b); }
}

async function toggleMode() {
    let instance = await start('toggle', null);
    const context = await browser.newContext({locale: 'en-US'});
    try {
        const page = await pageAt(context, instance); await login(page);
        const legacy = (await context.cookies()).find(cookie => cookie.name === 'pixiv_session');
        instance = await restart(instance, 'true'); await valid(page, false); await login(page);
        assert.equal((await context.cookies()).find(cookie => cookie.name === 'pixiv_session').value, legacy.value);
        await logout(page);
        assert.equal((await context.cookies()).find(cookie => cookie.name === 'pixiv_session').value, legacy.value);
        instance = await restart(instance, 'false'); await valid(page, true);
        results.push({scenario: 'mode-toggle', passed: ['no-legacy-adoption', 'no-legacy-migration-or-deletion', 'legacy-session-restored']});
    } finally { await context.close(); await stop(instance); }
}

async function visitors() {
    const a = await start('visitors/a', 'true', 0, 'multi'), b = await start('visitors/b', 'false', 0, 'multi');
    const context = await browser.newContext({locale: 'en-US'});
    try {
        const pa = await pageAt(context, a), pb = await pageAt(context, b);
        const first = (await call(pa, '/api/cookie-fixture/visitor')).body.uuid;
        const second = (await call(pb, '/api/cookie-fixture/visitor')).body.uuid;
        assert.notEqual(first, second);
        assert.equal((await call(pa, '/api/cookie-fixture/visitor')).body.uuid, first);
        assert.equal((await call(pb, '/api/cookie-fixture/visitor')).body.uuid, second);
        results.push({scenario: 'visitor-mixed', passed: ['independent-uuid', 'stable-uuid']});
    } finally { await context.close(); await stop(a); await stop(b); }
}

try {
    await isolatedPair('development-pair', 'true');
    await isolatedPair('mixed-pair', 'false');
    await normalPair(); await toggleMode(); await visitors();
    assert.deepEqual(errors, []);
    const report = {browser: await browser.version(), fixture: 'production servlet components with controlled invite store and proof provider', launches, results, pageErrors: errors};
    await fs.writeFile(path.join(root, 'result.json'), JSON.stringify(report, null, 2), 'utf8');
    console.log(JSON.stringify(report, null, 2));
} finally {
    await browser.close();
    for (const process of live) process.kill();
}
