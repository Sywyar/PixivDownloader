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
    publisher.setIn(['jobs', 'checks', 'if'], "github.event_name == 'push' || github.event.workflow_run.event == 'pull_request' || (github.event.workflow_run.event == 'workflow_dispatch' && github.event.action == 'completed')");
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
            if (!run) continue;
            if (run.value.includes('node scripts/ci/resolve-trusted-base.mjs ')) {
                run.value = run.value.slice(run.value.indexOf('node scripts/ci/resolve-trusted-base.mjs '))
                    .replace('"$TRUSTED_BASE_SHA"', '"$INPUT_TRUSTED_BASE_SHA"')
                    .replace(' --root-admission "$INPUT_ROOT_ADMISSION" --root-candidate-sha "$INPUT_ROOT_CANDIDATE_SHA"', '');
            }
            if (run.value.includes('if git cat-file -e "$BASE_SHA:scripts/ci/release-gate-policy.json"')) {
                // YAML scalar 已去掉缩进；只保留当前受保护 policy 的物化路径。
                const from = run.value.indexOf('if git cat-file');
                const end = run.value.indexOf('while IFS= read -r rel; do');
                run.value = run.value.slice(0, from) + [
                    'git show "$BASE_SHA:scripts/ci/release-gate-policy.json" > "$RUNNER_TEMP/gate-policy.json"',
                    'GATE_EPOCH=$(node -e \'console.log(require(process.argv[1]).gateEpoch)\' "$RUNNER_TEMP/gate-policy.json")',
                    'node -e \'const p=require(process.argv[1]); console.log(p.protectedCore.join("\\n"))\' "$RUNNER_TEMP/gate-policy.json" > "$RUNNER_TEMP/gate-files.txt"',
                    '',
                ].join('\n') + run.value.slice(end);
            }
            if (run.value.includes('elif [ "$GATE_MODE" = "ROOT_ADMISSION" ]')) {
                run.value = run.value.split('\n').find((line) => line.includes('node "$GATE_DIR/scripts/ci/release-gate-verifier.mjs"')).trim() + '\n';
            }
        }
    }
    // 复用同一 protected predecessor 算法；历史 master 提交仍使用自己的严格前驱。
    for (const name of ['release.yml', 'publish-plugins.yml', 'build-stable-ffmpeg.yml']) {
        const file = path.join(repo, '.github/workflows', name);
        const doc = YAML.parseDocument(fs.readFileSync(file, 'utf8'));
        if (doc.errors.length) throw new Error(`invalid workflow YAML: ${name}`);
        const step = doc.getIn(['jobs', 'trusted-base', 'steps']).items.find((entry) => entry.get('id') === 'base');
        step.commentBefore = ' 从受保护默认分支历史解析候选的严格前驱；历史提交仍使用自己的父提交。';
        step.set('run', [
            'set -euo pipefail',
            ...(name === 'publish-plugins.yml' ? [
                'if [ "$GITHUB_EVENT_NAME" = "workflow_dispatch" ] && [ "$GITHUB_REF" != "refs/heads/master" ]; then',
                '  echo "manual publication requires the protected master branch" >&2',
                '  exit 1',
                'fi',
            ] : []),
            'git fetch --no-tags origin "+refs/heads/$DEFAULT_BRANCH:refs/remotes/origin/$DEFAULT_BRANCH"',
            'tip="$(git rev-parse "refs/remotes/origin/$DEFAULT_BRANCH")"',
            'if git merge-base --is-ancestor "$GITHUB_SHA" "$tip"; then',
            '  base="$(git rev-parse "$GITHUB_SHA^1")"',
            'else',
            '  base="$(git merge-base "$GITHUB_SHA" "$tip")"',
            'fi',
            'echo "sha=$base" >> "$GITHUB_OUTPUT"',
            '',
        ].join('\n'));
        if (name === 'publish-plugins.yml') {
            doc.deleteIn(['jobs', 'trusted-base', 'if']);
            doc.deleteIn(['jobs', 'quality-gate', 'if']);
        }
        fs.writeFileSync(file, doc.toString(), 'utf8');
    }
    const caller = {
        name: 'Pull Request Quality Gate',
        'run-name': 'PR #${{ github.event.pull_request.number }}',
        on: { pull_request: { types: ['opened', 'reopened', 'synchronize', 'edited'] } },
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
