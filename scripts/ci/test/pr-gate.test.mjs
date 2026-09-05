import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import { prepare } from '../prepare-pr-gate.mjs';
import { completePages, publishChecks, inspectRun, inspectFullRun, checkEvent, assertCurrentEvidence } from '../gate-checks.mjs';
import YAML from 'yaml';
const SOURCE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const SOURCE_POLICY = JSON.parse(fs.readFileSync(path.join(SOURCE_ROOT, 'scripts/ci/release-gate-policy.json'), 'utf8'));
const CORE_DIRECTORY = SOURCE_POLICY.gateEpoch === 5 ? 'scripts/ci/gate-admission' : 'scripts/ci';
const core = await import(new URL(SOURCE_POLICY.gateEpoch === 5
    ? '../gate-admission/release-gate-verifier.mjs' : '../release-gate-verifier.mjs', import.meta.url));
const { parseExecutionProof, verifyRunIdentity, verifyRunResults,
    verifyIntegratedTree, verifyAppChecks, verifyCandidate } = core;
const { resolveTrustedBase } = await import(new URL(SOURCE_POLICY.gateEpoch === 5
    ? '../gate-admission/resolve-trusted-base.mjs' : '../resolve-trusted-base.mjs', import.meta.url));

const B = 'b'.repeat(40), H = 'a'.repeat(40), M = 'c'.repeat(40), T = 'd'.repeat(40);
const roles = ['java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract',
    'i18n-check', 'check-shared-snippets'];

function fixture() {
    const caller = { name: 'Pull Request Quality Gate',
        on: { pull_request: { branches: ['master'], types: ['opened', 'reopened', 'synchronize', 'edited'] } },
        permissions: { contents: 'read' },
        jobs: { 'quality-gate': {
            if: "github.event.action != 'edited' || github.event.changes.base != null",
            uses: 'Sywyar/PixivDownloader/.github/workflows/quality-gate.yml@master',
        } } };
    const run = { id: 123, run_attempt: 2, run_started_at: '2026-09-05T08:02:00Z', head_sha: H, name: caller.name,
        repository: { id: 1089943605, full_name: 'Sywyar/PixivDownloader' },
        event: 'pull_request', path: '.github/workflows/pr-quality-gate.yml',
        status: 'completed', conclusion: 'success', referenced_workflows: [{
            path: caller.jobs['quality-gate'].uses, sha: B, ref: 'refs/heads/master',
        }] };
    const proof = { schemaVersion: 1, repository: 'Sywyar/PixivDownloader', repositoryId: '1089943605',
        event: 'pull_request', baseRef: 'master', base: B, head: H, merge: M,
        pullRequest: '42', runId: '123', attempt: '1' };
    const merge = { sha: M, tree: { sha: T }, parents: [{ sha: B }, { sha: H }] };
    const jobs = roles.map((name, i) => ({ id: 20 + i, name: `quality-gate / ${name}`,
        run_id: 123, head_sha: H, status: 'completed', conclusion: 'success',
        started_at: '2026-09-05T08:00:10Z', completed_at: '2026-09-05T08:01:00Z',
        steps: [{ number: 1, name: 'Execute check', status: 'completed', conclusion: 'success',
            started_at: '2026-09-05T08:00:10Z', completed_at: '2026-09-05T08:01:00Z' }] }));
    return { run, proof, merge, jobs, caller, protectedBase: B };
}

test('protected job evidence binds head, base, tested merge, integrated tree and App source', () => {
    const f = fixture();
    f.proof = parseExecutionProof(`2026-09-05T08:00:00.1234567Z GATE_EXECUTION ${JSON.stringify(f.proof)}\n`);
    const evidence = verifyRunIdentity(f);
    verifyRunResults(f);
    const integrated = { ...f.merge, sha: 'e'.repeat(40) };
    assert.equal(verifyIntegratedTree(evidence, integrated), integrated.sha);
    const checks = roles.map((name) => ({ name, app: { id: 4837005 }, head_sha: M,
        status: 'completed', conclusion: 'success',
        details_url: 'https://github.com/Sywyar/PixivDownloader/actions/runs/123/attempts/2' }));
    verifyAppChecks({ checks, evidence });
    for (const mutate of [
        (check) => { check.app.id = 15368; },
        (check) => { check.head_sha = H; },
        (check) => { check.conclusion = 'neutral'; },
        (check) => { check.details_url = check.details_url.replace('attempts/2', 'attempts/1'); },
    ]) {
        const changed = structuredClone(checks);
        mutate(changed[0]);
        assert.throws(() => verifyAppChecks({ checks: changed, evidence }));
    }
});

test('foreign, stale, skipped and fabricated jobs cannot substitute for the protected call', () => {
    for (const mutate of [
        (f) => { f.run.repository.id++; },
        (f) => { f.run.event = 'push'; },
        (f) => { f.run.referenced_workflows[0].sha = H; },
        (f) => { f.proof.merge = H; },
        (f) => { f.proof.base = H; },
        (f) => { f.proof.runId = '124'; },
        (f) => { f.proof.attempt = '3'; },
        (f) => { f.caller.jobs['quality-gate'].if = 'false'; },
        (f) => { f.caller.jobs.fake = { 'runs-on': 'ubuntu-latest', steps: [{ run: 'true' }] }; },
        (f) => { f.jobs[0].conclusion = 'skipped'; },
        (f) => { f.jobs[0].conclusion = 'neutral'; },
        (f) => { f.jobs[0].head_sha = M; },
        (f) => { f.jobs.pop(); },
        (f) => { f.jobs.push({ ...f.jobs[0], id: 99, name: 'fake proof' }); },
        (f) => { f.jobs[0].id = f.jobs[1].id; },
        (f) => { f.jobs[0].name = f.jobs[1].name; },
        (f) => { f.run.conclusion = 'cancelled'; },
    ]) {
        const f = fixture();
        mutate(f);
        assert.throws(() => { verifyRunIdentity(f); verifyRunResults(f); });
    }
});

test('matching head or tree alone cannot authorize a different merge', () => {
    const f = fixture();
    const evidence = verifyRunIdentity(f);
    for (const mutate of [
        (merge) => { merge.tree.sha = H; },
        (merge) => { merge.parents.reverse(); },
        (merge) => { merge.parents.pop(); },
        (merge) => { merge.parents[0].sha = H; },
    ]) {
        const integrated = structuredClone(f.merge);
        mutate(integrated);
        assert.throws(() => verifyIntegratedTree(evidence, integrated));
    }
});

test('execution records fail closed when missing, duplicated, malformed or from a different event', () => {
    const proof = fixture().proof;
    const line = `GATE_EXECUTION ${JSON.stringify(proof)}`;
    for (const log of ['', `${line}\n${line}`, 'GATE_EXECUTION {}',
        line.replace('pull_request', 'pull_request_target'), line.replace(M, 'not-a-sha')]) {
        assert.throws(() => parseExecutionProof(log));
    }
    assert.deepEqual(parseExecutionProof(`runner setup\n${line}\nrunner cleanup`), proof);
});

test('API pagination and check publication cannot accept a partial response or another App', () => {
    assert.deepEqual(completePages([{ total_count: 2, jobs: [1] }, { total_count: 2, jobs: [2] }], 'jobs'), [1, 2]);
    for (const pages of [[], [{ jobs: [] }], [{ total_count: 2, jobs: [1] }],
        [{ total_count: 2, jobs: [1] }, { total_count: 3, jobs: [2] }]]) {
        assert.throws(() => completePages(pages, 'jobs'));
    }
    const f = fixture();
    const evidence = { ...verifyRunIdentity(f), checks: roles, status: 'completed', conclusion: 'success' };
    const writes = [];
    const api = (endpoint, options) => {
        if (options.pages) return [{ total_count: 0, check_runs: [] }];
        writes.push({ endpoint, ...options });
        return { app: { id: 4837005 }, head_sha: options.body.head_sha, name: options.body.name };
    };
    publishChecks(evidence, 'test-only-token', api);
    assert.deepEqual(writes.map((write) => write.body.name), roles);
    assert.ok(writes.every((write) => write.method === 'POST' && write.body.head_sha === M));
    assert.throws(() => publishChecks(evidence, '', api));
    assert.throws(() => publishChecks(evidence, 'test-only-token', (endpoint, options) => options.pages
        ? [{ total_count: 0, check_runs: [] }] : { app: { id: 15368 }, head_sha: M, name: roles[0] }));
});

test('publication rechecks the current PR and newest run even when fork run associations are empty', () => {
    const f = fixture();
    f.run.head_branch = 'feature';
    f.run.head_repository = { id: 12345 };
    f.run.pull_requests = [];
    const evidence = { ...verifyRunIdentity(f), pullRequest: 42, conclusion: 'success' };
    const pr = { state: 'open', base: { sha: B, ref: 'master', repo: { id: 1089943605 } },
        head: { sha: H }, merge_commit_sha: M };
    const api = (endpoint, options) => endpoint.includes('/jobs?') ? [{ total_count: f.jobs.length, jobs: f.jobs }] : options?.pages
        ? [{ total_count: 1, workflow_runs: [f.run] }]
        : endpoint.endsWith('/pulls/42') ? pr : f.run;
    assertCurrentEvidence(evidence, api);
    for (const mutate of [
        () => { f.run.run_attempt++; },
        () => { pr.base.sha = H; },
        () => { pr.merge_commit_sha = H; },
    ]) {
        const savedRun = structuredClone(f.run), savedPr = structuredClone(pr);
        mutate();
        assert.throws(() => assertCurrentEvidence(evidence, api));
        Object.assign(f.run, savedRun); Object.assign(pr, savedPr);
    }
    assert.throws(() => assertCurrentEvidence(evidence, (endpoint, options) => endpoint.includes('/workflows/')
        ? [{ total_count: 2, workflow_runs: [f.run, { ...f.run, id: 124 }] }] : api(endpoint, options)), /newer PR/u);
    assert.throws(() => assertCurrentEvidence(evidence, () => { throw new Error('API unavailable'); }), /API unavailable/u);
});

test('text-only PR edits do not publish success or supersede verified execution of the same merge', async () => {
    const f = fixture();
    const skipped = { ...f.run, id: 124, pull_requests: [{ number: 42 }] };
    const evidence = { ...verifyRunIdentity(f), pullRequest: 42, conclusion: 'success' };
    f.run.pull_requests = [{ number: 42 }];
    const api = (endpoint) => {
        if (endpoint.endsWith('/actions/runs/123')) return f.run;
        if (endpoint.endsWith('/actions/runs/124')) return skipped;
        if (endpoint.includes('/runs/124/jobs')) return [{ total_count: 1, jobs: [{ id: 99,
            name: 'quality-gate', run_id: 124, head_sha: H, status: 'completed', conclusion: 'skipped' }] }];
        if (endpoint.includes('/runs/123/jobs')) return [{ total_count: 6, jobs: f.jobs }];
        if (endpoint.includes('/workflows/')) return [{ total_count: 2, workflow_runs: [skipped, f.run] }];
        if (endpoint.endsWith('/pulls/42')) return { state: 'open', base: { sha: B, ref: 'master', repo: { id: 1089943605 } },
            head: { sha: H }, merge_commit_sha: M };
        throw new Error(`unexpected edit API request: ${endpoint}`);
    };
    assertCurrentEvidence(evidence, api);
    const result = await checkEvent({ repo: SOURCE_ROOT, eventName: 'workflow_run',
        event: { workflow_run: { id: 124 } }, core, api });
    assert.equal(result.ignored, true);
    assert.equal(result.merge, undefined);
});

test('protected predecessor admits only its approved core and exact root merge; ordinary workflow maintenance remains possible', async () => {
    const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
    const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv gate admission '));
    const git = (args, input) => execFileSync('git', ['-C', repo, ...args],
        { encoding: 'utf8', input, stdio: ['pipe', 'pipe', 'pipe'] }).trim();
    const commit = (message) => { git(['add', '-A']); git(['commit', '-qm', message]); return git(['rev-parse', 'HEAD']); };
    try {
        git(['init', '-q']);
        git(['config', 'user.name', 'Gate test']);
        git(['config', 'user.email', 'gate@example.test']);
        fs.cpSync(path.join(source, '.github/workflows'), path.join(repo, '.github/workflows'), { recursive: true });
        const policy = JSON.parse(fs.readFileSync(path.join(source, 'scripts/ci/release-gate-policy.json'), 'utf8'));
        for (const rel of [...policy.protectedCore, 'scripts/ci/release-gate-policy.json', 'package.json', 'package-lock.json']) {
            fs.mkdirSync(path.dirname(path.join(repo, rel)), { recursive: true });
            fs.copyFileSync(path.join(source, rel), path.join(repo, rel));
        }
        fs.mkdirSync(path.join(repo, 'scripts/ci/gate-admission'), { recursive: true });
        for (const rel of policy.protectedCore) fs.copyFileSync(path.join(source, CORE_DIRECTORY, path.basename(rel)),
            path.join(repo, 'scripts/ci/gate-admission', path.basename(rel)));
        policy.gateEpoch = 5;
        policy.contractVersion = 6;
        policy.rootTag = 'refs/tags/release-gate-epoch-5-root';
        delete policy.ruleset.roots['refs/tags/release-gate-epoch-7-root'];
        delete policy.ruleset.requiredCheckSources;
        fs.writeFileSync(path.join(repo, 'scripts/ci/release-gate-policy.json'), JSON.stringify(policy, null, 2));
        const base = commit('protected admission');
        git(['tag', 'release-gate-epoch-5-root', base]);
        git(['update-ref', 'refs/remotes/origin/master', base]);
        const actionComments = (file) => {
            const comments = [];
            const doc = YAML.parseDocument(fs.readFileSync(path.join(repo, file), 'utf8'));
            const retained = file.endsWith('/gate-checks.yml') ? doc.getIn(['jobs', 'checks']) : doc;
            YAML.visit(retained, {
                Pair(_, pair) {
                    if (pair.key?.value === 'uses' && pair.value?.comment) {
                        comments.push([pair.value.value, pair.value.comment]);
                    }
                },
            });
            return comments;
        };
        const workflowFiles = ['.github/workflows/quality-gate.yml', '.github/workflows/gate-checks.yml'];
        const comments = workflowFiles.map(actionComments);
        assert.ok(comments.every((entries) => entries.length > 0));
        prepare(repo);
        assert.deepEqual(workflowFiles.map(actionComments), comments, '生成器保留既有 Action 版本注释');
        const root = commit('approved root');
        git(['tag', 'release-gate-epoch-7-root', root]);
        const tree = git(['rev-parse', root + '^{tree}']);
        const merge = git(['commit-tree', tree, '-p', base, '-p', root], 'PR merge\n');
        assert.equal(verifyCandidate({ repo, trusted: base, candidate: merge }).gateEpoch, 7);
        assert.equal(verifyCandidate({ repo, trusted: base, candidate: root }).gateEpoch, 7);
        for (const request of [
            { event: 'pull_request', candidate: merge, prBase: base, prHead: root },
            { event: 'push', candidate: root, ref: 'refs/heads/feature' },
            { event: 'workflow_dispatch', candidate: root },
            { event: 'workflow_call', candidate: root, inputBase: base },
        ]) assert.equal(resolveTrustedBase({ repo, ...request }).base, base);
        assert.throws(() => resolveTrustedBase({ repo, event: 'pull_request', candidate: merge, prBase: root, prHead: base }));
        assert.throws(() => resolveTrustedBase({ repo, event: 'workflow_call', candidate: root, inputBase: root }));
        git(['remote', 'add', 'origin', repo]);
        const f = fixture();
        f.run.head_sha = root;
        f.run.referenced_workflows[0].sha = base;
        f.proof = { ...f.proof, base, head: root, merge };
        f.jobs.forEach((job) => { job.head_sha = root; });
        const originalJobs = structuredClone(f.jobs);
        f.jobs.forEach((job) => { job.id += 100; job.run_attempt = 2; });
        Object.assign(f.jobs[0], { started_at: '2026-09-05T08:02:10Z', completed_at: '2026-09-05T08:03:00Z' });
        Object.assign(f.jobs[0].steps[0], { started_at: f.jobs[0].started_at, completed_at: f.jobs[0].completed_at });
        const contractId = f.jobs.find((job) => job.name === 'quality-gate / trusted-gate-contract').id;
        const api = (endpoint, options = {}) => {
            if (endpoint.endsWith('/actions/runs/123')) return f.run;
            if (endpoint.includes(`/actions/jobs/${contractId}/logs`)) return `GATE_EXECUTION ${JSON.stringify(f.proof)}`;
            if (endpoint.endsWith(`/git/commits/${merge}`)) return { sha: merge, tree: { sha: tree }, parents: [{ sha: base }, { sha: root }] };
            if (endpoint.endsWith('/attempts/2')) return f.run;
            if (endpoint.endsWith('/attempts/1')) return { ...f.run, run_attempt: 1, run_started_at: '2026-09-05T08:00:00Z' };
            if (options.pages && endpoint.includes('/attempts/2/jobs')) return [{ total_count: 6, jobs: f.jobs }];
            if (options.pages && endpoint.includes('/attempts/1/jobs')) return [{ total_count: 6, jobs: originalJobs }];
            if (options.pages && endpoint.includes('/jobs?filter=latest')) return [{ total_count: 6, jobs: f.jobs }];
            throw new Error(`unexpected test API request: ${endpoint}`);
        };
        const evidence = await inspectRun({ repo, runId: 123, api, core });
        assert.equal(evidence.merge, merge);
        assert.equal(evidence.attempt, 2);
        await assert.rejects(inspectRun({ repo, runId: 123, core, api: (endpoint, options) => {
            const response = api(endpoint, options);
            return endpoint.endsWith('/attempts/1') ? { ...response, referenced_workflows: [] } : response;
        } }), /different workflow source/u);
        await assert.rejects(inspectRun({ repo, runId: 123, core, api: (endpoint, options) => {
            if (endpoint.includes(`/actions/jobs/${contractId}/logs`)) {
                return `GATE_EXECUTION ${JSON.stringify({ ...f.proof, attempt: '2' })}`;
            }
            return api(endpoint, options);
        } }), /different job attempt/u);
        git(['branch', '-M', 'master']);
        git(['update-ref', 'refs/heads/master', merge]);
        git(['read-tree', merge]);
        git(['update-ref', 'refs/remotes/origin/master', merge]);
        assert.equal(resolveTrustedBase({ repo, event: 'push', candidate: merge, before: base, ref: 'refs/heads/master' }).base, base);
        git(['config', '--local', 'pixiv.release.trustedGateEpoch', '5']);
        git(['config', '--local', 'pixiv.release.trustedGateRef', base]);
        const env = { ...process.env };
        delete env.CI;
        const adopted = spawnSync(process.execPath,
            [path.join(source, CORE_DIRECTORY, 'release-gate-trust.mjs'), '--adopt-root', '--ref', merge],
            { cwd: repo, env, encoding: 'utf8' });
        assert.equal(adopted.status, 0, adopted.stderr || adopted.stdout);
        assert.equal(git(['config', '--get', 'pixiv.release.trustedGateEpoch']), '7');
        assert.equal(git(['config', '--get', 'pixiv.release.trustedGateRef']), merge);
        assert.equal(fs.existsSync(path.join(repo, '.git/config.lock')), false);
        const full = { ...f.run, event: 'workflow_dispatch', head_branch: 'master', head_sha: merge,
            path: '.github/workflows/quality-gate.yml', name: 'Quality Gate', referenced_workflows: [] };
        const fullJobs = f.jobs.map((job) => ({ ...job, name: job.name.replace('quality-gate / ', ''), head_sha: merge }));
        const originalFullJobs = originalJobs.map((job) => ({ ...job, name: job.name.replace('quality-gate / ', ''), head_sha: merge }));
        const fullApi = (endpoint, options = {}) => {
            if (endpoint.includes('/workflows/pr-quality-gate.yml/runs')) return [{ total_count: 0, workflow_runs: [] }];
            if (endpoint.includes('/workflows/quality-gate.yml/runs')) return [{ total_count: 1, workflow_runs: [full] }];
            if (endpoint.endsWith('/actions/runs/123') || endpoint.endsWith('/attempts/2')) return full;
            if (endpoint.endsWith('/attempts/1')) return { ...full, run_attempt: 1, run_started_at: '2026-09-05T08:00:00Z' };
            if (endpoint.endsWith(`/git/commits/${merge}`)) return { sha: merge, tree: { sha: tree }, parents: [{ sha: base }, { sha: root }] };
            if (options.pages && endpoint.includes('/attempts/2/jobs')) return [{ total_count: 6, jobs: fullJobs }];
            if (options.pages && endpoint.includes('/attempts/1/jobs')) return [{ total_count: 6, jobs: originalFullJobs }];
            if (options.pages && endpoint.includes('/jobs?filter=latest')) return [{ total_count: 6, jobs: fullJobs }];
            throw new Error(`unexpected full-gate API request: ${endpoint}`);
        };
        assert.equal(inspectFullRun({ repo, runId: 123, candidate: merge, api: fullApi, core }).integrated, merge);
        const recovered = await checkEvent({ repo, eventName: 'push', event: { ref: 'refs/heads/master', before: base, after: merge }, api: fullApi, core });
        assert.equal(recovered.verification, 'same-commit-full-quality-gate');
        assert.throws(() => inspectFullRun({ repo, runId: 123, candidate: root, api: fullApi, core }), /same protected master commit/u);
        fullJobs[0].conclusion = 'skipped';
        assert.throws(() => inspectFullRun({ repo, runId: 123, candidate: merge, api: fullApi, core }), /unsuccessful/u);
        fullJobs[0].conclusion = 'success';
        const wrong = git(['commit-tree', tree, '-p', root, '-p', base], 'wrong parent order\n');
        assert.throws(() => verifyCandidate({ repo, trusted: base, candidate: wrong }), /two-parent merge/u);
        fs.appendFileSync(path.join(repo, 'scripts/ci/release-gate-verifier.mjs'), '\n// changed authority\n');
        const unapproved = commit('unapproved core');
        assert.throws(() => verifyCandidate({ repo, trusted: base, candidate: unapproved }), /unapproved admission core/u);
        git(['update-ref', 'refs/remotes/origin/master', unapproved]);
        const rejected = spawnSync(process.execPath,
            [path.join(source, CORE_DIRECTORY, 'release-gate-trust.mjs'), '--advance', '--ref', unapproved],
            { cwd: repo, env, encoding: 'utf8' });
        assert.notEqual(rejected.status, 0);
        assert.equal(git(['config', '--get', 'pixiv.release.trustedGateEpoch']), '7');
        assert.equal(git(['config', '--get', 'pixiv.release.trustedGateRef']), merge);
        fs.writeFileSync(path.join(repo, 'scripts/ci/release-gate-verifier.mjs'),
            fs.readFileSync(path.join(source, CORE_DIRECTORY, 'release-gate-verifier.mjs')));
        git(['update-ref', 'refs/remotes/origin/master', merge]);
        const branch = git(['symbolic-ref', 'HEAD']);
        git(['update-ref', branch, merge]);
        const workflow = path.join(repo, '.github/workflows/quality-gate.yml');
        fs.writeFileSync(workflow, fs.readFileSync(workflow, 'utf8').replace(
            'mvn -B -ntp test', 'mvn -ntp -B test'));
        const maintenance = commit('ordinary command ordering');
        assert.equal(verifyCandidate({ repo, trusted: merge, candidate: maintenance }).gateEpoch, 7);
        const extraPath = path.join(repo, '.github/workflows/diagnostics.yml');
        const extra = { name: 'Diagnostics', on: { workflow_dispatch: {} }, permissions: { contents: 'read' },
            jobs: { report: { 'runs-on': 'ubuntu-latest', if: 'always()', steps: [{ run: 'echo report' }] } } };
        fs.writeFileSync(extraPath, YAML.stringify(extra));
        assert.equal(verifyCandidate({ repo, trusted: merge, candidate: commit('read-only diagnostics') }).gateEpoch, 7);
        for (const mutate of [
            (doc) => { doc.env = { KEY: '${{ secrets.PRODUCT_KEY }}' }; },
            (doc) => { doc.jobs.report.environment = 'release'; doc.jobs.report.permissions = { contents: 'write' }; },
            (doc) => {
                doc.jobs.gate = { uses: './.github/workflows/quality-gate.yml' };
                doc.jobs.report.needs = 'gate';
                doc.jobs.publish = { needs: 'report', environment: 'release', 'runs-on': 'ubuntu-latest',
                    permissions: { contents: 'write' }, steps: [{ run: 'echo publish' }] };
            },
        ]) {
            const changed = structuredClone(extra); mutate(changed);
            fs.writeFileSync(extraPath, YAML.stringify(changed));
            assert.throws(() => verifyCandidate({ repo, trusted: merge, candidate: commit('invalid privilege path') }));
        }
        fs.unlinkSync(extraPath);
        const publisherPath = path.join(repo, '.github/workflows/gate-checks.yml');
        const publisher = fs.readFileSync(publisherPath, 'utf8');
        for (const mutate of [
            (doc) => { doc.jobs.checks.env = { KEY: '${{ secrets.PRODUCT_KEY }}' }; },
            (doc) => { doc.jobs.checks.permissions = { contents: 'write' }; },
            (doc) => { doc.jobs.checks.steps.find((step) => step.with?.['client-id']).with['permission-contents'] = 'write'; },
        ]) {
            const changed = YAML.parse(publisher); mutate(changed);
            fs.writeFileSync(publisherPath, YAML.stringify(changed));
            assert.throws(() => verifyCandidate({ repo, trusted: merge, candidate: commit('invalid publisher privilege') }), /publisher/u);
        }
        fs.writeFileSync(publisherPath, publisher);
        const quality = YAML.parse(fs.readFileSync(workflow, 'utf8'));
        quality.jobs['java-tests'].steps[0]['continue-on-error'] = '${{ true }}';
        fs.writeFileSync(workflow, YAML.stringify(quality));
        assert.throws(() => verifyCandidate({ repo, trusted: merge, candidate: commit('suppressed quality failure') }), /suppress/u);
    } finally {
        fs.rmSync(repo, { recursive: true, force: true });
    }
});
