import assert from 'node:assert/strict';
import test from 'node:test';

import { gitContributorIdentities, readBounded, selectMaintainers } from '../../generate-maintainers.mjs';

test('维护者为仓库所有者和提交协作者与真人白名单的交集', async () => {
  const identities = gitContributorIdentities(`
Sywyar <83223374+Sywyar@users.noreply.github.com>
gdrfgdrf <gdrfgdrfgdrfgdrfgdrfgdrf@hotmail.com>
`);
  const selected = await selectMaintainers({
    owner: { id: 83223374, login: 'Sywyar', type: 'User' },
    contributors: [
      { id: 49699333, login: 'dependabot[bot]', type: 'Bot' },
      { id: 7, login: 'not-approved', type: 'User' },
    ],
    identities,
    allowlist: [
      { id: 83223374, login: 'Sywyar', role: 'author-core' },
      { id: 65430754, login: 'gdrfgdrf', role: 'commit-collaborator' },
      { id: 49699333, login: 'dependabot[bot]', role: 'bot' },
    ],
    loadUser: async (login) => ({ id: 65430754, login, type: 'User' }),
  });

  assert.deepEqual(selected.map(({ id, login, role }) => ({ id, login, role })), [
    { id: 83223374, login: 'Sywyar', role: 'author-core' },
    { id: 65430754, login: 'gdrfgdrf', role: 'commit-collaborator' },
  ]);
});

test('头像响应超过上限时立即拒绝', async () => {
  await assert.rejects(readBounded(new Response(new Uint8Array(5)), 4), /too large/);
});
