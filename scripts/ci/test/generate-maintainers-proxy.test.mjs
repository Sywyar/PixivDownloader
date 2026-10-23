import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createServer as createSecureServer } from 'node:https';
import { connect } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
let directory;
let certificate;
let pfx;
let sequence = 0;
before(async () => {
  directory = await mkdtemp(join(tmpdir(), 'maintainer-proxy-'));
  certificate = join(directory, 'certificate.pem');
  const store = join(directory, 'server.p12');
  for (const args of [
    ['-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
      '-dname', 'CN=api.github.com', '-ext', 'SAN=dns:api.github.com,dns:avatars.githubusercontent.com,dns:localhost',
      '-keystore', store, '-storetype', 'PKCS12', '-storepass', 'fixture-password'],
    ['-exportcert', '-rfc', '-alias', 'fixture', '-keystore', store, '-storepass', 'fixture-password', '-file', certificate],
  ]) {
    const result = spawnSync('keytool', args, { encoding: 'utf8' });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr);
  }
  pfx = await readFile(store);
});
after(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });

async function fixture(t, { respond, rejectProxy = false } = {}) {
  const requests = [];
  const tunnels = [];
  const sockets = new Set();
  const server = createSecureServer({ pfx, passphrase: 'fixture-password' }, (request, response) => {
    requests.push({ url: request.url, headers: request.headers });
    if (respond) return respond(request, response);
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify({ ok: true }));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const proxy = createServer();
  proxy.on('connect', (request, client, head) => {
    tunnels.push({ target: request.url, headers: request.headers });
    if (rejectProxy) {
      client.end('HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    const upstream = connect(server.address().port, '127.0.0.1', () => {
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head);
      client.pipe(upstream).pipe(client);
    });
    sockets.add(upstream);
    upstream.on('close', () => sockets.delete(upstream));
    upstream.on('error', () => client.destroy());
    client.on('error', () => upstream.destroy());
    client.on('close', () => upstream.destroy());
  });
  for (const service of [server, proxy]) service.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
  });
  proxy.listen(0, '127.0.0.1');
  await once(proxy, 'listening');
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await Promise.all([server, proxy].map((service) => new Promise((resolve) => service.close(resolve))));
  });
  return { requests, tunnels, proxyUrl: `http://127.0.0.1:${proxy.address().port}`, directUrl: `https://localhost:${server.address().port}` };
}

async function run(options = {}) {
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (/^(?:https?_proxy|all_proxy|no_proxy|node_options|node_use_env_proxy|node_extra_ca_certs|github_token|gh_token)$/i.test(key)) delete env[key];
  }
  Object.assign(env, { NODE_EXTRA_CA_CERTS: certificate, GITHUB_TOKEN: 'fixture-token' }, options.env);
  const source = `
import dns from 'node:dns';
import { syncBuiltinESMExports } from 'node:module';
const lookup = dns.lookup;
dns.lookup = (host, options, callback) => {
  if (host === 'api.github.com' || host === 'avatars.githubusercontent.com') {
    const done = typeof options === 'function' ? options : callback;
    return queueMicrotask(() => done(Object.assign(new Error('direct route disabled'), { code: 'ENOTFOUND' })));
  }
  return lookup(host, options, callback);
};
syncBuiltinESMExports();
${options.script ?? `const { fetchGitHub } = await import(${JSON.stringify(pathToFileURL(join(repository, 'scripts/generate-maintainers.mjs')).href)});
try {
  const response = await fetchGitHub('/repos/fixture/project', { wait: async () => {}, warn: () => {} });
  console.log(JSON.stringify(await response.json()));
} catch (error) { console.error(error.message); process.exitCode = 1; }`}
`;
  const script = join(directory, `request-${++sequence}.mjs`);
  await writeFile(script, source, 'utf8');
  const command = options.shell ?? process.execPath;
  const args = options.shell
    ? ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', '& $env:MAINTAINER_TEST_NODE $env:MAINTAINER_TEST_SCRIPT; exit $LASTEXITCODE']
    : [script];
  const child = spawn(command, args, {
    env: { ...env, MAINTAINER_TEST_NODE: process.execPath, MAINTAINER_TEST_SCRIPT: script },
    stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
  });
  let stdout = '', stderr = '';
  child.stdout.setEncoding('utf8').on('data', (value) => { stdout += value; });
  child.stderr.setEncoding('utf8').on('data', (value) => { stderr += value; });
  const [status] = await once(child, 'exit');
  return { status, stdout, stderr };
}

test('维护者 API 在直连不可用时经明确代理建立 CONNECT 并验证来源 TLS', async (t) => {
  const network = await fixture(t);
  const result = await run({ env: { HTTPS_PROXY: network.proxyUrl } });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), { ok: true });
  assert.equal(network.requests.length, 1);
  assert.equal(network.requests[0].headers.authorization, 'Bearer fixture-token');
  assert.equal(network.tunnels.length, 1);
  assert.equal(network.tunnels[0].target, 'api.github.com:443');
  assert.equal(network.tunnels[0].headers.authorization, undefined);
});

test('代理失败不得静默改为直连，且继续服从原有重试预算', async (t) => {
  const network = await fixture(t, { rejectProxy: true });
  const result = await run({ env: { HTTPS_PROXY: network.proxyUrl } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /transport failure.*attempts=3/);
  assert.equal(network.tunnels.length, 3);
  assert.equal(network.requests.length, 0);
});

test('经代理下载仍拒绝不受信任的源站证书', async (t) => {
  const network = await fixture(t);
  const result = await run({ env: { HTTPS_PROXY: network.proxyUrl, NODE_EXTRA_CA_CERTS: '' } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /transport failure/);
  assert.equal(network.tunnels.length, 3);
  assert.equal(network.requests.length, 0);
});

test('代理凭据与源站凭据分别发送到各自目标', async (t) => {
  const network = await fixture(t);
  const proxy = new URL(network.proxyUrl);
  proxy.username = 'proxy-user';
  proxy.password = 'proxy-password';
  const result = await run({ env: { https_proxy: proxy.href } });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(network.tunnels[0].headers['proxy-authorization'], `Basic ${Buffer.from('proxy-user:proxy-password').toString('base64')}`);
  assert.equal(network.tunnels[0].headers.authorization, undefined);
  assert.equal(network.requests[0].headers.authorization, 'Bearer fixture-token');
  assert.equal(network.requests[0].headers['proxy-authorization'], undefined);
});

test('明确绕过规则使用真实直连且不接触配置的代理', async (t) => {
  const network = await fixture(t);
  const result = await run({
    env: { HTTPS_PROXY: network.proxyUrl, NO_PROXY: 'localhost' },
    script: `const { maintainerRequest } = await import(${JSON.stringify(pathToFileURL(join(repository, 'scripts/maintainer-request.mjs')).href)});
console.log(JSON.stringify(await (await maintainerRequest(${JSON.stringify(network.directUrl)})).json()));`,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), { ok: true });
  assert.equal(network.requests.length, 1);
  assert.equal(network.tunnels.length, 0);
});

test('拒绝重定向后不把 API 凭据发送到第二个来源', async (t) => {
  const network = await fixture(t, { respond(_request, response) {
    response.writeHead(302, { location: 'https://avatars.githubusercontent.com/redirected' });
    response.end();
  } });
  const result = await run({ env: { HTTPS_PROXY: network.proxyUrl } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /ERR_REDIRECT/);
  assert.equal(network.requests.length, 3);
  assert.ok(network.requests.every((request) => request.headers.host === 'api.github.com'));
});

test('真实生成入口通过代理获取 API 和头像并保留双方认证边界', async (t) => {
  const { maintainers } = JSON.parse(await readFile(join(repository, 'scripts/maintainers-allowlist.json'), 'utf8'));
  const users = maintainers.map((entry) => ({ ...entry, type: 'User', avatar_url: `https://avatars.githubusercontent.com/u/${entry.id}`, html_url: `https://github.com/${entry.login}` }));
  const avatar = Buffer.from([137, 80, 78, 71]);
  const network = await fixture(t, { respond(request, response) {
    if (request.headers.host === 'avatars.githubusercontent.com') {
      response.writeHead(200, { 'content-type': 'image/png', 'content-length': avatar.length });
      response.end(avatar);
    } else {
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify(request.url.includes('/contributors') ? users : { owner: users[0] }));
    }
  } });
  const output = join(directory, 'catalog.json');
  const generator = join(repository, 'scripts/generate-maintainers.mjs');
  const result = await run({ env: { HTTPS_PROXY: network.proxyUrl }, script: `
process.argv = [process.execPath, ${JSON.stringify(generator)}, '--repo-root', ${JSON.stringify(repository)}, '--output', ${JSON.stringify(output)}];
await import(${JSON.stringify(pathToFileURL(generator).href)});
` });
  assert.equal(result.status, 0, result.stderr);
  const catalog = JSON.parse(await readFile(output, 'utf8'));
  assert.deepEqual(catalog.maintainers.map((entry) => entry.id), maintainers.map((entry) => entry.id));
  assert.ok(catalog.maintainers.every((entry) => entry.avatarBase64 === avatar.toString('base64')));
  const avatarRequests = network.requests.filter((request) => request.headers.host === 'avatars.githubusercontent.com');
  assert.equal(avatarRequests.length, maintainers.length);
  assert.ok(avatarRequests.every((request) => request.headers.authorization === undefined));
  assert.ok(network.requests.filter((request) => request.headers.host === 'api.github.com').every((request) => request.headers.authorization === 'Bearer fixture-token'));
  assert.equal(network.tunnels.length, network.requests.length);
});

test('PowerShell 支持入口将明确代理传递到真实 Node 子进程', { skip: process.platform !== 'win32' }, async (t) => {
  const network = await fixture(t);
  for (const shell of ['powershell.exe', 'pwsh.exe']) {
    const result = await run({ shell, env: { HTTPS_PROXY: network.proxyUrl } });
    assert.equal(result.status, 0, `${shell}: ${result.stderr}`);
    assert.deepEqual(JSON.parse(result.stdout), { ok: true });
  }
  assert.equal(network.tunnels.length, 2);
  assert.equal(network.requests.length, 2);
});

test('正文等待被取消时释放实际代理连接', async (t) => {
  const network = await fixture(t, { respond(_request, response) {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.write('{');
  } });
  const result = await run({ env: { HTTPS_PROXY: network.proxyUrl }, script: `
import assert from 'node:assert/strict';
const { maintainerRequest } = await import(${JSON.stringify(pathToFileURL(join(repository, 'scripts/maintainer-request.mjs')).href)});
const controller = new AbortController();
const response = await maintainerRequest('https://api.github.com/body', { signal: controller.signal });
controller.abort();
await assert.rejects(response.text());
console.log('cancelled');
` });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), 'cancelled');
  assert.equal(network.tunnels.length, 1);
});

test('无效代理配置拒绝请求且不在诊断中泄露代理凭据', async (t) => {
  const network = await fixture(t);
  const result = await run({ env: { HTTPS_PROXY: 'socks5://proxy-user:proxy-password@127.0.0.1:1' } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /ERR_MAINTAINER_PROXY_CONFIG/);
  assert.doesNotMatch(result.stderr, /attempts=/);
  assert.doesNotMatch(result.stderr, /proxy-user|proxy-password/);
  assert.equal(network.tunnels.length, 0);
  assert.equal(network.requests.length, 0);
});
