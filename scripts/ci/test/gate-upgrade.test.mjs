import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import { historicalGateFile, historicalWorkflows } from './lib/historical-gate.mjs';
import { createApproval, verifyUpgrade, materializeGate, selectGateFiles, POLICY_PATH, APPROVAL_PATH,
    UPGRADE_DIRECTORY } from '../gate-upgrade.mjs';
import { verifyProtectedCandidate } from '../trusted-gate-runner.mjs';
import { credentialSources, effectivePermissions, privilegedCredentials, workflowCredentialBindings } from '../gate-credentials.mjs';

const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
function fixture(t) {
    const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-upgrade-test-'));
    t.after(() => fs.rmSync(repo, { recursive: true, force: true }));
    const git = (...args) => execFileSync('git', ['-C', repo, ...args], {
        encoding: 'utf8', windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    const write = (file, value) => {
        fs.mkdirSync(path.dirname(path.join(repo, file)), { recursive: true });
        fs.writeFileSync(path.join(repo, file), typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n');
    };
    const json = (file) => JSON.parse(fs.readFileSync(path.join(repo, file), 'utf8'));
    const commit = (message) => { git('add', '.'); git('-c', 'commit.gpgsign=false', 'commit', '-qm', message); return git('rev-parse', 'HEAD'); };
    git('init', '-q', '-b', 'master');
    git('config', 'user.name', 'Gate test'); git('config', 'user.email', 'gate@example.invalid');
    git('config', 'commit.gpgsign', 'false'); git('config', 'tag.gpgsign', 'false');
    git('config', 'core.autocrlf', 'false'); git('config', 'core.hooksPath', path.join(repo, 'no-hooks'));
    for (const file of ['.github', 'package.json', 'package-lock.json', 'scripts/ci']) {
        fs.cpSync(path.join(ROOT, file), path.join(repo, file), { recursive: true });
    }
    const previous = JSON.parse(historicalGateFile(ROOT, POLICY_PATH, 8));
    for (const file of [...previous.protectedCore, POLICY_PATH]) write(file, historicalGateFile(ROOT, file, 8).toString('utf8'));
    fs.rmSync(path.join(repo, '.github/workflows'), { recursive: true });
    for (const file of historicalWorkflows(ROOT, 8)) write(file, historicalGateFile(ROOT, file, 8).toString('utf8'));
    // 固定的旧核心验证准备提交；新协议的 epoch 由测试数据选择。
    const initial = commit('existing protected gate');
    git('-c', 'tag.gpgsign=false', 'tag', '-a', 'release-gate-epoch-8-root', '-m', 'fixture root', initial);
    git('update-ref', 'refs/remotes/origin/master', initial);
    function prepare(nextEpoch) {
        const before = json(POLICY_PATH), next = json(UPGRADE_DIRECTORY + 'release-gate-policy.json');
        next.gateEpoch = nextEpoch; next.rootTag = `refs/tags/release-gate-epoch-${nextEpoch}-root`;
        next.ruleset.roots = { ...before.ruleset.roots,
            [next.rootTag]: { allowDeletion: false, allowNonFastForward: false, allowBypass: false } };
        write(UPGRADE_DIRECTORY + 'release-gate-policy.json', next);
        write(APPROVAL_PATH, createApproval({ repo, trusted: 'HEAD', changes: ['Reviewed credential semantics and upgrade contract'] }));
        const base = commit('approve inactive core');
        git('update-ref', 'refs/remotes/origin/master', base);
        return base;
    }
    function activate(base) {
        const record = json(APPROVAL_PATH);
        execFileSync(process.execPath, [path.join(ROOT, 'scripts/ci/gate-upgrade.mjs'),
            '--activate', '--repo-root', repo, '--trusted-ref', base], { cwd: repo, windowsHide: true });
        fs.cpSync(path.join(ROOT, '.github/workflows'), path.join(repo, '.github/workflows'), { recursive: true });
        const root = commit('activate approved core');
        git('-c', 'tag.gpgsign=false', 'tag', '-a', record.to.root.slice('refs/tags/'.length), '-m', 'fixture sealed root', root);
        const merge = git('commit-tree', `${root}^{tree}`, '-p', base, '-p', root, '-m', 'fixture integration');
        return { root, merge };
    }
    return { repo, git, write, json, commit, initial, prepare, activate };
}

test('升级复用同一协议，旧核心批准准备提交，普通更新继续通过保护', (t) => {
    const f = fixture(t), base = f.prepare(17);
    assert.equal(verifyProtectedCandidate({ repo: f.repo, trusted: f.initial, candidate: base }).gateEpoch, 8);
    const activated = f.activate(base);
    assert.equal(verifyUpgrade({ repo: f.repo, trusted: base, candidate: activated.merge }).to.epoch, 17);
    assert.equal(verifyProtectedCandidate({ repo: f.repo, trusted: base, candidate: activated.merge }).gateEpoch, 17);
    const workflow = YAML.parse(fs.readFileSync(path.join(ROOT, '.github/workflows/quality-gate.yml'), 'utf8'));
    const step = workflow.jobs['trusted-gate-contract'].steps.find((step) => step.name === 'Materialize protected verifier');
    const runner = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-upgrade-runner-'));
    t.after(() => fs.rmSync(runner, { recursive: true, force: true }));
    execFileSync('bash', ['-e', '-o', 'pipefail', '-c', step.run], { cwd: f.repo, windowsHide: true,
        env: { ...process.env, BASE_SHA: base, CANDIDATE_SHA: activated.merge, GITHUB_WORKSPACE: f.repo,
            RUNNER_TEMP: runner, GITHUB_ENV: path.join(runner, 'github-env') } });
    assert.equal(JSON.parse(fs.readFileSync(path.join(runner, 'trusted-gate', POLICY_PATH), 'utf8')).gateEpoch, 17);
    f.git('reset', '--hard', activated.merge);
    f.git('update-ref', 'refs/remotes/origin/master', activated.merge);
    f.git('config', 'pixiv.release.trustedGateEpoch', '8');
    f.git('config', 'pixiv.release.trustedGateRef', base);
    const local = { ...process.env }; delete local.CI;
    execFileSync(process.execPath, [path.join(ROOT, 'scripts/ci/trust-gate.mjs'), '--adopt-root', '--ref', activated.merge],
        { cwd: f.repo, env: local, windowsHide: true });
    assert.equal(f.git('config', '--get', 'pixiv.release.trustedGateEpoch'), '17');
    assert.equal(f.git('config', '--get', 'pixiv.release.trustedGateRef'), activated.merge);
    const secondBase = f.prepare(18);
    assert.equal(verifyProtectedCandidate({ repo: f.repo, trusted: activated.merge, candidate: secondBase }).gateEpoch, 17);
    const second = f.activate(secondBase);
    assert.equal(verifyProtectedCandidate({ repo: f.repo, trusted: secondBase, candidate: second.merge }).gateEpoch, 18);
});

test('候选声明、来源、核心字节、依赖、文件模式和 root 不能替代前驱授权', (t) => {
    const f = fixture(t), base = f.prepare(17), { root, merge } = f.activate(base);
    const options = { repo: f.repo, trusted: base, candidate: merge };
    assert.doesNotThrow(() => verifyUpgrade(options));
    assert.throws(() => selectGateFiles({ repo: f.repo, trusted: root, candidate: merge }), /protected predecessor/);
    assert.throws(() => verifyUpgrade({ ...options, trusted: f.initial }), /approval|source|match/);
    for (const file of ['scripts/ci/release-gate-verifier.mjs', 'scripts/ci/gate-credentials.mjs', 'package-lock.json']) {
        f.git('reset', '--hard', root);
        f.write(file, fs.readFileSync(path.join(f.repo, file), 'utf8') + '\n');
        const tampered = f.commit('tampered candidate');
        assert.throws(() => verifyUpgrade({ ...options, candidate: tampered }), /approved bytes/);
    }
    f.git('reset', '--hard', root);
    f.git('update-index', '--chmod=+x', 'scripts/ci/release-gate-verifier.mjs');
    f.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'tampered executable mode');
    assert.throws(() => verifyUpgrade({ ...options, candidate: 'HEAD' }), /approved bytes/);
    f.git('tag', '-d', 'release-gate-epoch-17-root');
    f.git('-c', 'tag.gpgsign=false', 'tag', 'release-gate-epoch-17-root', root);
    assert.throws(() => verifyUpgrade(options), /annotated/);
});

test('物化内容来自受保护前驱，启用前仅允许明确的本地反馈', (t) => {
    const oldCI = process.env.CI;
    t.after(() => { if (oldCI === undefined) delete process.env.CI; else process.env.CI = oldCI; });
    delete process.env.CI;
    const f = fixture(t), base = f.prepare(17);
    for (const file of f.json(APPROVAL_PATH).files) f.write(file.path, fs.readFileSync(path.join(f.repo, file.source), 'utf8'));
    const candidate = f.commit('unsealed activation'), opts = { repo: f.repo, trusted: base, candidate };
    assert.throws(() => verifyUpgrade(opts));
    assert.doesNotThrow(() => verifyUpgrade({ ...opts, localFeedback: true }));
    process.env.CI = 'true';
    assert.throws(() => verifyUpgrade({ ...opts, localFeedback: true }), /forbidden in CI/);
    delete process.env.CI;
    const directory = path.join(f.repo, 'materialized');
    materializeGate({ ...opts, directory, localFeedback: true });
    assert.equal(f.git('hash-object', path.join(directory, 'scripts/ci/release-gate-verifier.mjs')),
        f.json(APPROVAL_PATH).files.find((file) => file.path === 'scripts/ci/release-gate-verifier.mjs').blob);
});

test('内置令牌别名、混合秘密、字面量、OIDC 与运行时令牌按来源分类', () => {
    for (const value of ['${{ github.token }}', '${{ secrets.GITHUB_TOKEN }}', "${{ GiThUb['ToKeN'] }}", "${{ secrets['GITHUB_TOKEN'] }}"]) {
        assert.deepEqual([...credentialSources(value)], ['github']);
        assert.equal(privilegedCredentials(value, { contents: 'read' }), false);
    }
    for (const value of ["${{ github.token || secrets.PAT }}", "${{ secrets['PAT'] }}", '${{ toJSON(secrets) }}', '${{ secrets[inputs.name] }}']) {
        assert.equal(privilegedCredentials(value, { contents: 'read' }), true);
    }
    assert.deepEqual([...credentialSources("${{ format('secrets github.token {0}', github.repository) }} GITHUB_TOKEN")], []);
    assert.equal(privilegedCredentials('${{ github.token }}', 'read-all', { contents: 'write' }), true);
    assert.equal(privilegedCredentials('${{ github.token }}', { contents: 'write' }, { contents: 'read' }), false);
    assert.equal(privilegedCredentials({}, { 'id-token': 'write' }), true);
    assert.equal(privilegedCredentials('${{ env.ACTIONS_ID_TOKEN_REQUEST_TOKEN }}', {}), true);
    assert.equal(privilegedCredentials('${{ env.ACTIONS_RUNTIME_TOKEN }}', {}), true);
    assert.deepEqual(effectivePermissions({ contents: 'write' }, {}, { contents: 'read' }), { contents: 'none', 'id-token': 'none' });
    assert.equal(effectivePermissions('write-all', undefined, { contents: 'read' }).contents, 'read');
    const doc = { jobs: {
        mint: { steps: [{ id: 'app', uses: 'actions/create-github-app-token@' + 'a'.repeat(40) }],
            outputs: { credential: '${{ steps.app.outputs.token }}', ordinary: '${{ steps.app.outputs.app-slug }}' } },
        consume: { env: { TOKEN: "${{ needs['mint'].outputs.credential }}" } },
    } };
    const bindings = workflowCredentialBindings(doc).get('consume');
    assert.equal(privilegedCredentials(doc.jobs.consume, { contents: 'read' }, undefined, bindings), true);
    assert.equal(privilegedCredentials('${{ needs.mint.outputs.ordinary }}', {}, undefined, bindings), false);
    assert.equal(privilegedCredentials('${{ steps.unknown.outputs.token }}', {}), false);
});

test('真实 workflow 审查允许只读内置令牌，并拒绝 composite 隐藏秘密和未门控写权限', (t) => {
    const f = fixture(t), base = f.prepare(17), { root, merge } = f.activate(base);
    f.git('reset', '--hard', merge); f.git('update-ref', 'refs/remotes/origin/master', merge);
    const action = '.github/actions/gate-credential-fixture/action.yml';
    const workflow = '.github/workflows/gate-credential-fixture.yml';
    f.write(action, "name: fixture\nruns:\n  using: composite\n  steps:\n    - shell: bash\n      run: echo ok\n      env:\n        GH_TOKEN: ${{ secrets['GITHUB_TOKEN'] }}\n");
    f.write(workflow, "name: fixture\non: workflow_dispatch\npermissions:\n  contents: read\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: ./.github/actions/gate-credential-fixture\n");
    const allowed = f.commit('read-only composite');
    assert.equal(verifyProtectedCandidate({ repo: f.repo, trusted: merge, candidate: allowed }).gateEpoch, 17);
    f.write(action, fs.readFileSync(path.join(f.repo, action), 'utf8').replace("secrets['GITHUB_TOKEN']", 'secrets.PAT'));
    const denied = f.commit('secret composite');
    assert.throws(() => verifyProtectedCandidate({ repo: f.repo, trusted: merge, candidate: denied }), /privileged capabilities/);
    f.git('reset', '--hard', allowed);
    f.write(workflow, fs.readFileSync(path.join(f.repo, workflow), 'utf8').replace('contents: read', 'contents: write'));
    assert.throws(() => verifyProtectedCandidate({ repo: f.repo, trusted: merge, candidate: f.commit('write token') }), /privileged capabilities/);
    assert.ok(root);
});
