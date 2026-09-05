#!/usr/bin/env node
'use strict';

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

export function prepare(repo) {
    const policyFile = path.join(repo, 'scripts/ci/release-gate-policy.json');
    const policy = JSON.parse(fs.readFileSync(policyFile, 'utf8'));
    if (policy.gateEpoch !== 5) throw new Error('preparation requires the protected admission bundle');
    const sources = policy.protectedCore.map((rel) => ({ rel,
        bytes: fs.readFileSync(path.join(repo, 'scripts/ci/gate-admission', path.basename(rel))) }));
    const qualityFile = path.join(repo, '.github/workflows/quality-gate.yml');
    const quality = YAML.parseDocument(fs.readFileSync(qualityFile, 'utf8'));
    const publisherFile = path.join(repo, '.github/workflows/gate-checks.yml');
    const publisher = YAML.parseDocument(fs.readFileSync(publisherFile, 'utf8'));
    if (quality.errors.length || publisher.errors.length) throw new Error('invalid workflow YAML');
    publisher.setIn(['on', 'workflow_run', 'types'], ['in_progress', 'completed']);
    publisher.setIn(['on', 'workflow_run', 'workflows'], ['Pull Request Quality Gate', 'Quality Gate']);
    publisher.setIn(['on', 'push'], { branches: ['master'] });
    publisher.deleteIn(['jobs', 'protected-base']);
    publisher.deleteIn(['jobs', 'quality-gate']);
    publisher.deleteIn(['jobs', 'checks', 'needs']);
    publisher.setIn(['jobs', 'checks', 'if'], "github.event_name == 'push' || github.event.workflow_run.event == 'pull_request' || (github.event.workflow_run.event == 'workflow_dispatch' && github.event.workflow_run.head_branch == 'master' && github.event.action == 'completed')");
    quality.set('on', { workflow_dispatch: { inputs: {
        trusted_base_sha: { description: 'Protected predecessor commit (optional)', required: false, type: 'string' },
    } }, workflow_call: { inputs: { trusted_base_sha: { required: false, type: 'string' } } } });
    // 可复用调用共享调用者的 github.workflow；仅由 PR 入口取消旧运行，避免取消自身。
    quality.delete('concurrency');
    for (const { value: job } of quality.get('jobs').items) {
        for (const step of job.get('steps')?.items || []) {
            if (step.has('env')) {
                step.deleteIn(['env', 'INPUT_ROOT_ADMISSION']);
                step.deleteIn(['env', 'INPUT_ROOT_CANDIDATE_SHA']);
            }
            const run = step.get('run', true);
            if (run) run.value = run.value.replace(
                ' --root-admission "$INPUT_ROOT_ADMISSION" --root-candidate-sha "$INPUT_ROOT_CANDIDATE_SHA"', '');
        }
    }
    const caller = {
        name: 'Pull Request Quality Gate',
        on: { pull_request: { branches: ['master'], types: ['opened', 'reopened', 'synchronize', 'edited'] } },
        permissions: { contents: 'read' },
        concurrency: { group: "pr-quality-${{ github.event.pull_request.number }}-${{ github.event.action == 'edited' && github.event.changes.base == null }}", 'cancel-in-progress': true },
        jobs: { 'quality-gate': {
            if: "github.event.action != 'edited' || github.event.changes.base != null",
            uses: 'Sywyar/PixivDownloader/.github/workflows/quality-gate.yml@master',
        } },
    };
    policy.gateEpoch = 8;
    policy.contractVersion = 9;
    policy.rootTag = 'refs/tags/release-gate-epoch-8-root';
    policy.qualityGate.requiredJobs = [...policy.ruleset.requiredChecks];
    policy.qualityGate.requiredTriggers = ['workflow_call', 'workflow_dispatch'];
    delete policy.qualityGate.allowedPushExclusions;
    delete policy.workflows['.github/workflows/shared-snippets-check.yml'];
    policy.ruleset.requiredCheckSources = Object.fromEntries(policy.ruleset.requiredChecks.map((name) => [name, 4837005]));
    policy.ruleset.roots[policy.rootTag] = { allowDeletion: false, allowNonFastForward: false, allowBypass: false };
    for (const { rel, bytes } of sources) fs.writeFileSync(path.join(repo, rel), bytes);
    fs.writeFileSync(policyFile, JSON.stringify(policy, null, 2) + '\n', 'utf8');
    fs.writeFileSync(qualityFile, quality.toString(), 'utf8');
    fs.writeFileSync(publisherFile, publisher.toString(), 'utf8');
    fs.writeFileSync(path.join(repo, '.github/workflows/pr-quality-gate.yml'), YAML.stringify(caller), 'utf8');
    const shared = path.join(repo, '.github/workflows/shared-snippets-check.yml');
    if (fs.existsSync(shared)) fs.unlinkSync(shared);
    for (const { rel } of sources) fs.unlinkSync(path.join(repo, 'scripts/ci/gate-admission', path.basename(rel)));
    fs.rmdirSync(path.join(repo, 'scripts/ci/gate-admission'));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        const repo = process.cwd();
        const branch = execFileSync('git', ['-C', repo, 'branch', '--show-current'], { encoding: 'utf8' }).trim();
        if (!branch || branch === 'master') throw new Error('prepare in a standard development worktree');
        if (execFileSync('git', ['-C', repo, 'status', '--porcelain'], { encoding: 'utf8' }).trim()) {
            throw new Error('preparation requires a clean worktree');
        }
        prepare(repo);
        console.log('Prepared PR gate candidate; review and verify before creating its protected root.');
    } catch (error) {
        console.error(`prepare-pr-gate: ${error.message}`);
        process.exitCode = 1;
    }
}
