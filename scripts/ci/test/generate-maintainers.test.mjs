import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { attributionCommits, fetchGitHub, readBounded } from '../../generate-maintainers.mjs';

test('queries only representative commits for each author and co-author email', async () => {
  const [first, repeated, different, promoted, anonymous] = ['a', 'b', 'c', 'd', 'e'].map((hex) => hex.repeat(40));
  const result = await attributionCommits([
    first + '\0OWNER@example.test\0Display Name <helper@example.test>',
    repeated + '\0owner@example.test\0Changed Name <HELPER@example.test>',
    different + '\0owner@example.test\0Same Name <another@example.test>',
    promoted + '\0helper@example.test',
    anonymous + '\0\0Malformed trailer',
  ]);
  assert.deepEqual(result, [first, different, promoted]);
});

test('有界读取在声明超限或累计超限时取消正文并释放读取锁', async () => {
  for (const headers of [{ 'content-length': '5' }, {}]) {
    let cancelled = 0;
    const response = new Response(new ReadableStream({
      start(controller) { controller.enqueue(new Uint8Array(5)); },
      cancel() { cancelled++; },
    }), { headers });
    await assert.rejects(readBounded(response, 4), /too large/);
    assert.equal(cancelled, 1);
    assert.equal(response.body.locked, false);
  }
});

test('有界读取在成功和正文失败后释放读取锁并保留原结果', async () => {
  const response = new Response(new Uint8Array([1, 2, 3]));
  assert.deepEqual(await readBounded(response, 3), Buffer.from([1, 2, 3]));
  assert.equal(response.body.locked, false);
  const failure = new Error('body failed');
  const broken = new Response(new ReadableStream({ start(controller) { controller.error(failure); } }));
  await assert.rejects(readBounded(broken, 3), (error) => error === failure);
  assert.equal(broken.body.locked, false);
});

test('正文取消失败不能覆盖原始大小拒绝', async () => {
  const response = new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(5)); },
    cancel() { throw new Error('cancel failed'); },
  }));
  await assert.rejects(readBounded(response, 4), /too large/);
  assert.equal(response.body.locked, false);
});

test('拒绝超大声明后关闭仍在发送正文的真实连接', { timeout: 5000 }, async (t) => {
  let disconnected;
  const closed = new Promise((resolve) => { disconnected = resolve; });
  const server = createServer((_request, response) => {
    response.on('close', disconnected);
    response.writeHead(200, { 'content-length': '100' });
    response.write('first');
  });
  t.after(() => {
    server.closeAllConnections();
    return new Promise((resolve) => server.close(resolve));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const response = await fetch(`http://127.0.0.1:${server.address().port}/`);
  await assert.rejects(readBounded(response, 4), /too large/);
  await closed;
});

test('真实生成入口在获取失败或离线时写入不可用状态并释放正文', async (t) => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const fixture = await mkdtemp(join(tmpdir(), 'maintainer-response-'));
  t.after(() => rm(fixture, { recursive: true, force: true }));
  const preload = join(fixture, 'fetch.mjs');
  const output = join(fixture, 'maintainers.json');
  await writeFile(preload, `
import https from 'node:https';
import { EventEmitter } from 'node:events';
import { syncBuiltinESMExports } from 'node:module';
import { Readable } from 'node:stream';
const owner = { id: 101, login: 'repo-owner', type: 'User', avatar_url: 'https://avatars.githubusercontent.com/u/101' };
const authors = { nodes: [{ user: { databaseId: owner.id, login: owner.login } }], pageInfo: { hasNextPage: false } };
https.request = (input, options, callback) => {
  const url = new URL(input);
  const outgoing = new EventEmitter();
  let incoming;
  if (url.hostname === 'api.github.com') {
    const mode = process.env.FIXTURE_MODE;
    if (['offline', 'no-token'].includes(mode)) throw new Error('Unavailable generation must not request the API');
    let payload = url.pathname === '/graphql' ? { data: { repository: { object: { authors } } } } : { owner };
    if (url.pathname === '/graphql' && mode === 'graphql') {
      payload = { ...payload, errors: [{ type: 'FORBIDDEN' }] };
    }
    if (url.pathname === '/graphql' && mode === 'graphql-body') payload = { data: 'a'.repeat(65 * 1024) };
    incoming = Readable.from([Buffer.from(JSON.stringify(payload))]);
    incoming.statusCode = ['api', 'rate'].includes(process.env.FIXTURE_MODE) ? 403 : 200;
    incoming.headers = { 'content-type': 'application/json' };
    if (process.env.FIXTURE_MODE === 'rate') {
      incoming.headers['x-ratelimit-remaining'] = '0';
      incoming.headers['x-ratelimit-reset'] = String(Math.ceil(Date.now() / 1000) + 3600);
    }
  } else {
    const mode = process.env.FIXTURE_MODE;
    incoming = new Readable({
      read() {},
      destroy(error, done) {
        process.stdout.write('BODY_CANCELLED\\n');
        done(mode.endsWith('-cancel-fails') ? new Error('cleanup failure') : error);
      },
    });
    incoming.statusCode = mode.startsWith('status') ? 502 : 200;
    incoming.headers = { 'content-type': 'text/plain' };
  }
  outgoing.end = () => queueMicrotask(() => callback(incoming));
  return outgoing;
};
syncBuiltinESMExports();
`, 'utf8');
  for (const mode of ['api', 'rate', 'graphql', 'graphql-body', 'offline', 'no-token', 'status', 'type', 'status-cancel-fails', 'type-cancel-fails']) {
    await t.test(mode, async () => {
      await writeFile(output, 'existing catalog', 'utf8');
      const env = { ...process.env, FIXTURE_MODE: mode, NO_PROXY: '*' };
      delete env.GITHUB_TOKEN;
      delete env.GH_TOKEN;
      if (mode !== 'no-token') env.GH_TOKEN = 'fixture-token';
      const child = spawnSync(process.execPath, ['--import', pathToFileURL(preload).href, join(root, 'scripts/generate-maintainers.mjs'),
        '--repo-root', root, '--output', output, `--offline=${mode === 'offline'}`], { env, encoding: 'utf8', timeout: 30_000 });
      assert.ifError(child.error);
      assert.equal(child.status, 0, child.stderr);
      const expected = {
        api: /GitHub API 403/,
        rate: /GitHub API 403/,
        graphql: /GitHub GraphQL author lookup failed.*FORBIDDEN/,
        'graphql-body': /Response is too large/,
        offline: /offline mode/,
        'no-token': /requires GITHUB_TOKEN or GH_TOKEN/,
      }[mode] ?? (mode.startsWith('status') ? /Avatar download 502/ : /Avatar is not an image/);
      assert.match(child.stderr, expected);
      assert.doesNotMatch(child.stderr, /must not request/);
      assert.equal((child.stdout.match(/BODY_CANCELLED/g) ?? []).length,
        /^(status|type)/.test(mode) ? 1 : 0, mode);
      assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), { maintainers: [], unavailable: true });
    });
  }
});

test('不可用状态也必须成功写入，输出路径错误仍使生成失败', async (t) => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const fixture = await mkdtemp(join(tmpdir(), 'maintainer-output-'));
  t.after(() => rm(fixture, { recursive: true, force: true }));
  const child = spawnSync(process.execPath, [join(root, 'scripts/generate-maintainers.mjs'),
    '--repo-root', root, '--offline=true', '--output', fixture], { encoding: 'utf8', timeout: 30_000 });
  assert.ifError(child.error);
  assert.equal(child.status, 1);
});

function requests(...responses) {
  const calls = [];
  const delays = [];
  const warnings = [];
  return {
    calls, delays, warnings,
    options: {
      fetchImpl: async (url, options) => {
        calls.push({ url, options });
        const response = responses.shift();
        if (response instanceof Error) throw response;
        assert.ok(response, '不能超出预定请求次数');
        return response;
      },
      wait: async (delay) => { delays.push(delay); },
      now: () => 1_000_000,
      warn: (message) => { warnings.push(message); },
    },
  };
}

test('GitHub API 使用现有令牌且拒绝把认证发送到其它主机', async (t) => {
  const previous = process.env.GITHUB_TOKEN;
  process.env.GITHUB_TOKEN = 'test-built-in-token';
  t.after(() => {
    if (previous === undefined) delete process.env.GITHUB_TOKEN;
    else process.env.GITHUB_TOKEN = previous;
  });
  const request = requests(Response.json({ id: 101 }));
  assert.deepEqual(await (await fetchGitHub('/repos/example/project', request.options)).json(), { id: 101 });
  assert.equal(request.calls[0].options.headers.Authorization, 'Bearer test-built-in-token');
  assert.equal(request.calls[0].options.redirect, 'error');
  assert.ok(request.calls[0].options.signal instanceof AbortSignal);
  await assert.rejects(fetchGitHub('https://example.com/stolen', request.options), /left api.github.com/);
  await assert.rejects(fetchGitHub('https://user@api.github.com/repos/example/project', request.options), /left api.github.com/);
  assert.equal(request.calls.length, 1);
});

test('权限错误立即失败并保留脱敏诊断，不输出响应中的令牌或换行', async (t) => {
  const previous = process.env.GITHUB_TOKEN;
  process.env.GITHUB_TOKEN = 'secret-fixture-token';
  t.after(() => {
    if (previous === undefined) delete process.env.GITHUB_TOKEN;
    else process.env.GITHUB_TOKEN = previous;
  });
  for (const status of [401, 403, 404]) {
    const request = requests(Response.json({ message: 'Permission denied: secret-fixture-token\n::error::injected' }, {
      status, headers: { 'x-github-request-id': 'request-fixture', 'x-ratelimit-remaining': '42' },
    }));
    await assert.rejects(fetchGitHub('/repos/example/project', request.options), (error) => {
      assert.match(error.message, new RegExp(`GitHub API ${status}`));
      assert.match(error.message, /authenticated=true.*request-fixture.*remaining=42/);
      assert.match(error.message, /Permission denied/);
      assert.doesNotMatch(error.message, /secret-fixture-token|[\r\n]/);
      return true;
    });
    assert.equal(request.calls.length, 1);
    assert.deepEqual(request.delays, []);
  }
});

test('明确限流遵守 Retry-After 与重置时刻，未提供时等待至少一分钟', async () => {
  for (const [status, headers, message, expected] of [
    [403, { 'retry-after': '2' }, 'slow down', 2000],
    [429, { 'retry-after': new Date(1_005_000).toUTCString() }, 'slow down', 5000],
    [403, { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': '1090' }, 'API rate limit exceeded', 91_000],
    [403, {}, 'You have exceeded a secondary rate limit', 60_000],
    [429, {}, 'slow down', 60_000],
  ]) {
    const request = requests(Response.json({ message }, { status, headers }), Response.json({ ok: true }));
    assert.equal((await fetchGitHub('/repos/example/project', request.options)).status, 200);
    assert.deepEqual(request.delays, [expected]);
    assert.equal(request.warnings.length, 1);
    assert.equal(request.calls.length, 2);
  }
});

test('短暂服务和网络故障有限重试，重试用尽仍拒绝请求', async () => {
  const request = requests(
    new TypeError('fetch failed'),
    Response.json({ message: 'unavailable' }, { status: 503 }),
    Response.json({ ok: true }),
  );
  assert.equal((await fetchGitHub('/users/example', request.options)).status, 200);
  assert.deepEqual(request.delays, [1000, 2000]);
  const exhausted = requests(...Array.from({ length: 3 }, () => new DOMException('timeout', 'TimeoutError')));
  await assert.rejects(fetchGitHub('/users/example', exhausted.options), /transport failure.*attempts=3/);
  assert.equal(exhausted.calls.length, 3);
  assert.deepEqual(exhausted.delays, [1000, 2000]);
});

test('限流等待超出累计预算时失败，不提前请求也不无限重试', async () => {
  const tooLong = requests(Response.json({ message: 'slow down' }, { status: 429, headers: { 'retry-after': '3600' } }));
  await assert.rejects(fetchGitHub('/users/example', tooLong.options), /retry wait budget/);
  assert.deepEqual(tooLong.delays, []);
  assert.equal(tooLong.calls.length, 1);
  const repeated = requests(...Array.from({ length: 3 }, () => Response.json({ message: 'slow down' }, { status: 429 })));
  await assert.rejects(fetchGitHub('/users/example', repeated.options), /attempts=3/);
  assert.deepEqual(repeated.delays, [60_000, 120_000]);
});

test('诊断正文过大或不是 JSON 仍报告 HTTP 状态和请求编号', async () => {
  for (const body of ['gateway error', 'x'.repeat(64 * 1024 + 1)]) {
    const request = requests(new Response(body, { status: 403, headers: { 'x-github-request-id': 'error-body-fixture' } }));
    await assert.rejects(fetchGitHub('/users/example', request.options), (error) => {
      assert.match(error.message, /GitHub API 403.*error-body-fixture/);
      assert.ok(error.message.length < 2000);
      return true;
    });
  }
});
