import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';

const repo = new URL('../../', import.meta.url);
const staticRoot = new URL('pixivdownload-app/src/main/resources/static/', repo);
const proofModule = new URL('pixivdownload-app/src/test/resources/page-components/operation-proof.mjs', repo);
const protocol = {stateSchema: '{"type":"string"}', responseSchema: '{"type":"string"}'};
let revision, documents, projections, preparations, submissions, accepted, challenge;
const slowPaths = {auth: '/api/auth/check', 'page-i18n': '/fixture-i18n/1', 'login-i18n': '/fixture-i18n/2'};
let timing, delayFinished, delayed;
const html = () => `<!doctype html>
<html lang="en-US"><head><meta charset="utf-8">
<meta name="pixiv-page-revision" content="${revision}"><title>Login fixture</title></head><body>
<div id="native-page"><div data-page-region="form" id="loginCard">
<input id="username" aria-label="Username"><input id="password" type="password" aria-label="Password">
<input id="remember" type="checkbox" aria-label="Remember"><div data-ui-slot="login.verification"></div>
<button id="login-btn" data-pixiv-click="doLogin()" disabled>Login</button><div id="error-msg"></div>
<button id="useInviteCode">Invite</button></div></div><div id="custom-page"></div>
<div id="inviteCard" hidden><input id="inviteCodeInput"><button id="invite-btn" disabled>Redeem</button>
<button id="backToLogin">Back</button><div id="invite-error"></div></div>
<script src="/fixture.js"></script><script src="/js/pixiv-actions.js"></script>
<script src="/login/login.js"></script>
<script type="module" src="/login/login-page.mjs"></script></body></html>`;
const fixture = `
let dictionaries = 0;
window.PixivI18n = {create: async () => {
    await fetch('/fixture-i18n/' + ++dictionaries);
    return {t: (key, fallback) => fallback || key, apply() {}, dispose() {}};
}};
window.PixivUiSlots = {connect: () => ({async refresh() {}, async destroy() {}})};
addEventListener('pageshow', event => {
    const events = JSON.parse(sessionStorage.getItem('shows') || '[]');
    events.push({persisted: event.persisted, revision: document.querySelector('meta[name="pixiv-page-revision"]').content,
        proofReady: document.querySelector('[data-page-component="proof"] button')?.disabled === true});
    sessionStorage.setItem('shows', JSON.stringify(events));
});`;
const server = createServer(async (request, response) => {
    try {
        const url = new URL(request.url, 'http://localhost');
        const send = (status, type, body) => {
            response.writeHead(status, {'Content-Type': type});
            response.end(request.method === 'HEAD' ? undefined : body);
        };
        const json = (status, value) => send(status, 'application/json', JSON.stringify(value));
        if (!delayed && url.pathname === slowPaths[timing]) {
            delayed = true;
            delayFinished = new Promise(resolve => setTimeout(resolve, 2500));
            await delayFinished;
        }
        if (url.pathname === '/login') {
            documents.push(revision);
            return send(200, 'text/html; charset=utf-8', html());
        }
        if (url.pathname === '/away' || url.pathname === '/redirect') return send(200, 'text/html', '<!doctype html><title>Away</title><p>Away</p>');
        if (url.pathname === '/fixture.js') return send(200, 'text/javascript', fixture);
        if (url.pathname.startsWith('/fixture-i18n/')) return json(200, {});
        if (url.pathname === '/api/auth/check') return json(200, {valid: false});
        if (url.pathname === '/api/operation-hooks/prepare') {
            let body = '';
            for await (const part of request) body += part;
            const data = JSON.parse(body);
            assert.equal(data.url, '/api/auth/login');
            assert.equal(data.method, 'POST');
            assert.equal(body.includes('fixture-password'), false);
            if (data.token) {
                assert.equal(data.token, challenge);
                assert.deepEqual(data.proofs, {proof: 'fixture:' + revision});
                return json(200, {code: 'HOOK_READY', invoked: false, token: challenge, revision, challenges: {}});
            }
            preparations.push(data);
            challenge = 'challenge-' + revision + '-' + preparations.length;
            return json(409, {code: 'HOOK_CHALLENGE', invoked: false, token: challenge, revision, challenges: {proof: revision}});
        }
        if (url.pathname === '/api/auth/login') {
            let body = '';
            for await (const part of request) body += part;
            const data = JSON.parse(body);
            assert.deepEqual(Object.keys(data).sort(), ['password', 'rememberMe', 'username']);
            const token = request.headers['x-pixiv-hook-ticket'];
            submissions.push(token);
            if (token === challenge) { accepted++; return json(200, {}); }
            return json(409, {code: 'HOOK_STALE', invoked: false});
        }
        if (url.pathname.startsWith('/api/web/page-publications/')) {
            response.setHeader('X-Pixiv-Page-Revision', revision);
            if (!url.pathname.endsWith('/targets')) projections.push({method: request.method, revision: url.searchParams.get('revision')});
            if (url.searchParams.get('revision') !== revision) return json(409, {});
            if (url.pathname.endsWith('/targets')) return json(200, {revision, targets: [{target: 'login.verification', operation: 'core:login', requests: [{method: 'POST', url: '/api/auth/login', headers: {'Content-Type': 'application/json'}, parameters: [], headerConditions: [], fields: []}]}]});
            return json(200, {title: 'Published ' + revision, body: {type: 'wrapper', children: [
                {type: 'page-region', region: 'form'},
                {type: 'page-component', instanceId: 'proof', target: 'login.verification', operation: 'core:login',
                    policy: 'proof', interaction: protocol, namespace: 'proof', moduleUrl: '/proof.mjs',
                    publication: revision, configuration: {}, inputs: [], outputs: []}
            ]}});
        }
        const sources = {
            '/js/pixiv-http-hooks.mjs': new URL('js/pixiv-http-hooks.mjs', staticRoot),
            '/js/pixiv-pages.mjs': new URL('js/pixiv-pages.mjs', staticRoot),
            '/js/pixiv-actions.js': new URL('js/pixiv-actions.js', staticRoot),
            '/login/login.js': new URL('login/login.js', staticRoot),
            '/login/login-page.mjs': new URL('login/login-page.mjs', staticRoot),
            '/sdk/values/value-codecs.mjs': new URL('pixivdownload-plugin-api/src/main/resources/META-INF/pixivdownload/web/value-codecs.mjs', repo),
            '/proof.mjs': proofModule
        };
        if (sources[url.pathname]) return send(200, 'text/javascript', await readFile(sources[url.pathname], 'utf8'));
        send(404, 'text/plain', 'Not found');
    } catch (error) {
        console.error(error);
        response.writeHead(500).end();
    }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + server.address().port;
let browser;
try {
    // Playwright 默认禁用 BFCache，需开启后验证真实的历史页面恢复。
    browser = await chromium.launch({channel: 'chromium', ignoreDefaultArgs: ['--disable-back-forward-cache']});
    console.log('Browser:', browser.version(), 'Module:', fileURLToPath(new URL('js/pixiv-pages.mjs', staticRoot)));
    for (timing of ['ready', 'auth', 'page-i18n', 'login-i18n'])
    for (const responseBeforeReturn of timing === 'ready' ? [false] : [false, true])
    for (const direction of ['back', 'forward']) for (const changed of [false, true]) {
        revision = 'revision-a'; documents = []; projections = []; preparations = []; submissions = []; accepted = 0; challenge = '';
        delayed = false; delayFinished = undefined;
        const context = await browser.newContext();
        const page = await context.newPage();
        page.setDefaultTimeout(8000);
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        const proof = page.locator('[data-page-component="proof"] button');
        const ready = () => page.waitForFunction(expected => document.title === 'Published ' + expected
            && document.querySelector('#login-btn')?.checkVisibility()
            && !document.querySelector('#login-btn').disabled
            && document.querySelector('[data-page-component="proof"] button'), revision);
        const prepareProof = async () => {
            await page.locator('#username').fill('fixture-user');
            await page.locator('#password').fill('fixture-password');
            const prepared = page.waitForResponse(response => response.url().endsWith('/api/operation-hooks/prepare'));
            await proof.click();
            const result = await (await prepared).json();
            await page.waitForFunction(() => document.querySelector('[data-page-component="proof"] button')?.disabled === true);
            return result.token;
        };
        try {
            if (direction === 'forward') await page.goto(origin + '/away');
            const initializing = timing === 'ready' ? undefined : page.waitForRequest(origin + slowPaths[timing]);
            await page.goto(origin + '/login');
            let old;
            if (timing === 'ready') {
                await ready();
                old = await prepareProof();
            } else {
                await initializing;
                assert.equal(await page.locator('#login-btn').isDisabled(), true, 'Leave before initialization completes');
                assert.equal(await proof.count(), 0);
            }
            assert.deepEqual(documents, ['revision-a'], 'Initial pageshow must not reload');
            if (direction === 'back') await page.goto(origin + '/away');
            else await page.goBack({waitUntil: 'commit'});
            if (changed) revision = 'revision-b';
            if (responseBeforeReturn) await delayFinished;
            if (direction === 'back') await page.goBack({waitUntil: 'commit'});
            else await page.goForward({waitUntil: 'commit'});
            await page.waitForFunction(() => JSON.parse(sessionStorage.getItem('shows') || '[]').some(event => event.persisted));
            await ready();
            const shows = await page.evaluate(() => JSON.parse(sessionStorage.getItem('shows')));
            const restored = shows.filter(event => event.persisted);
            assert.equal(restored.length, 1, 'History navigation must use BFCache');
            assert.equal(restored[0].revision, 'revision-a');
            assert.equal(restored[0].proofReady, false, 'Leaving must clear the old proof');
            assert.deepEqual(documents, ['revision-a', revision], 'Restore must load exactly one fresh document');
            assert.deepEqual(projections.filter(item => item.method === 'GET').map(item => item.revision),
                timing === 'ready' ? ['revision-a', revision] : [revision]);
            assert.equal(await proof.isDisabled(), false, 'A fresh verification must be available');
            const fresh = await prepareProof();
            if (old) assert.notEqual(fresh, old);
            assert.equal(submissions.length, 0, 'Verification must not submit credentials');
            await page.locator('#login-btn').click();
            await page.waitForURL(origin + '/redirect');
            assert.deepEqual(submissions, [fresh], 'Only the fresh proof may be submitted');
            assert.equal(accepted, 1, 'Restored login must complete challenge and response');
            assert.deepEqual(errors, []);
            console.log(JSON.stringify({timing, responseBeforeReturn, direction, changed, persisted: true, documents, accepted, pageErrors: errors.length, result: 'PASS'}));
        } catch (error) {
            console.error(JSON.stringify({timing, responseBeforeReturn, direction, changed, documents, projections, preparations, submissions, errors,
                state: await page.evaluate(() => ({shows: JSON.parse(sessionStorage.getItem('shows') || '[]'),
                    loginError: document.querySelector('#error-msg')?.textContent,
                    nativeHidden: document.querySelector('#native-page')?.hidden,
                    customChildren: document.querySelector('#custom-page')?.childElementCount,
                    notRestoredReasons: performance.getEntriesByType('navigation')[0]?.notRestoredReasons?.toJSON()}))}));
            throw error;
        } finally { await context.close(); await delayFinished; }
    }
} finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
}
