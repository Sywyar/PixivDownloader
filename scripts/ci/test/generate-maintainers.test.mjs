import assert from 'node:assert/strict';
import test from 'node:test';

import { fetchGitHub, gitContributorIdentities, readBounded, selectMaintainers } from '../../generate-maintainers.mjs';

test('维护者为仓库所有者和提交协作者与真人白名单的交集', async () => {
  const identities = gitContributorIdentities(
    'Repo Owner <101+repo-owner@users.noreply.github.com>\0'
      + 'Commit Helper <102+commit-helper@users.noreply.github.com>\x1e',
  );
  const selected = await selectMaintainers({
    owner: { id: 101, login: 'repo-owner', type: 'User' },
    contributors: [
      { id: 103, login: 'automation[bot]', type: 'Bot' },
      { id: 104, login: 'not-approved', type: 'User' },
    ],
    identities,
    allowlist: [
      { id: 101, login: 'repo-owner' },
      { id: 102, login: 'commit-helper' },
      { id: 103, login: 'automation[bot]' },
    ],
    loadUser: async (login) => ({ id: 102, login, type: 'User' }),
  });

  assert.deepEqual(selected.map(({ id, login, role }) => ({ id, login, role })), [
    { id: 101, login: 'repo-owner', role: 'author-core' },
    { id: 102, login: 'commit-helper', role: 'commit-collaborator' },
  ]);
});

test('提交协作者产生独立提交后自动成为提交贡献者', async () => {
  const identities = gitContributorIdentities(
    'Repo Owner <101+repo-owner@users.noreply.github.com>\0'
      + 'Commit Helper <102+commit-helper@users.noreply.github.com>\x1e'
      + 'Commit Helper <102+commit-helper@users.noreply.github.com>\x1e',
  );
  const selected = await selectMaintainers({
    owner: { id: 101, login: 'repo-owner', type: 'User' },
    contributors: [{ id: 102, login: 'commit-helper', type: 'User' }],
    identities,
    allowlist: [{ id: 102, login: 'commit-helper' }],
    loadUser: async () => assert.fail('独立提交者不应再次查询用户'),
  });

  assert.deepEqual(selected.map(({ id, login, role }) => ({ id, login, role })), [
    { id: 102, login: 'commit-helper', role: 'commit-contributor' },
  ]);
});

test('头像响应超过上限时立即拒绝', async () => {
  await assert.rejects(readBounded(new Response(new Uint8Array(5)), 4), /too large/);
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

test('短暂服务和网络故障有限重试，重试用尽仍拒绝构建', async () => {
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
