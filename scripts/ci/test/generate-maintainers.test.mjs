import assert from 'node:assert/strict';
import test from 'node:test';

import { gitContributorIdentities, readBounded, selectMaintainers } from '../../generate-maintainers.mjs';

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
