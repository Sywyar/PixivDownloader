import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import { inspectSdkVersion, parseSdkVersion, SDK_ARTIFACTS } from '../sdk-version.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const IDENTITY = inspectSdkVersion(ROOT);
const workflow = YAML.parse(fs.readFileSync(path.join(ROOT, '.github/workflows/publish-sdk.yml'), 'utf8'));
const publication = YAML.parse(fs.readFileSync(path.join(ROOT,
    workflow.jobs.publish.steps.find(step => step.uses === './.github/actions/publish-sdk').uses, 'action.yml'), 'utf8'));
const script = publication.runs.steps.find(step => step.name === 'Check immutable publication state').run;

test('发行调用复用完整的公共 SDK，首次发布与显式恢复继续进入门禁，查询失败不能当作尚未发布', () => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-publication-plan-'));
    try {
        const entry = path.join(work, 'plan.sh');
        const planAction = workflow.jobs['release-plan'].steps.find(step => step.id === 'plan');
        const plan = YAML.parse(fs.readFileSync(path.join(ROOT, planAction.uses, 'action.yml'), 'utf8'))
            .runs.steps.find(step => step.id === 'plan').run;
        fs.writeFileSync(entry, [
            'git() {',
            '  case "$1" in',
            '    fetch) return ;;',
            '    rev-parse) printf "%040d\\n" 1 ;;',
            '    merge-base) test "$SDK_TEST_PROTECTED" = true ;;',
            '    *) return 99 ;;',
            '  esac',
            '}',
            'node() {',
            '  if [ "$1" = scripts/ci/sdk-version.mjs ]; then printf "%s" "$SDK_TEST_IDENTITY"; return; fi',
            '  if [ "$1" = scripts/ci/sdk-published-base.mjs ]; then',
            '    test "$2" = if-present || return 99',
            '    test "$SDK_TEST_PUBLISHED" != error || return 1',
            '    printf "%s" "$SDK_TEST_PUBLISHED"',
            '  else command node "$@"; fi',
            '}',
            plan,
        ].join('\n'), 'utf8');
        const stable = parseSdkVersion(`${IDENTITY.major}.${IDENTITY.minor}.${IDENTITY.patch}`);
        for (const scenario of [
            { expected: true },
            { published: 'a'.repeat(40), expected: false },
            { mode: 'recover-release', published: 'error', expected: true },
            { published: 'error', expected: null },
            { protectedCommit: false, expected: null },
            { ref: 'refs/tags/v2.3.4-beta.1', expected: null },
            { ref: 'refs/tags/v2.3.4-beta.1', published: 'a'.repeat(40), expected: false },
            { event: 'workflow_dispatch', ref: 'refs/heads/master', expected: null },
            { event: 'workflow_dispatch', ref: 'refs/heads/master', args: '-f', expected: true },
            { event: 'workflow_dispatch', ref: 'refs/heads/master', args: '--force', expected: null },
            { event: 'push', ref: 'refs/heads/master', args: '-f', expected: null },
            { workflow: 'publish-sdk.yml', expected: null },
            { ref: 'refs/tags/v2.3.4-beta.1', identity: parseSdkVersion(stable.version + '-beta.1'), expected: true },
            { event: 'workflow_dispatch', identity: parseSdkVersion(stable.version + '-rc.1'), expected: true },
        ]) {
            const { mode = 'publish', published = '', protectedCommit = true, expected,
                event = 'push', ref = 'refs/tags/v2.3.4', args = '',
                workflow: caller = 'release.yml', identity = stable } = scenario;
            const output = path.join(work, 'output.txt');
            fs.writeFileSync(output, '', 'utf8');
            const result = spawnSync('bash', [entry.replaceAll('\\', '/')], {
                cwd: ROOT, encoding: 'utf8',
                env: { ...process.env, REQUESTED_MODE: mode, DEFAULT_BRANCH: 'master',
                    REQUESTED_ARGS: args, GITHUB_EVENT_NAME: event, GITHUB_REF: ref,
                    GITHUB_REPOSITORY: 'fixture/app',
                    GITHUB_WORKFLOW_REF: `fixture/app/.github/workflows/${caller}@${ref}`,
                    SDK_TEST_IDENTITY: JSON.stringify(identity),
                    GITHUB_SHA: 'b'.repeat(40), GITHUB_OUTPUT: output.replaceAll('\\', '/'),
                    GITHUB_STEP_SUMMARY: path.join(work, 'summary.txt').replaceAll('\\', '/'),
                    SDK_TEST_PUBLISHED: published, SDK_TEST_PROTECTED: String(protectedCommit) },
            });
            assert.equal(result.status === 0, expected !== null, JSON.stringify(scenario) + ': ' + (result.error ?? result.stderr));
            const outputs = fs.readFileSync(output, 'utf8');
            if (expected === null) assert.equal(outputs, '');
            else assert.deepEqual(Object.fromEntries(outputs.trim().split('\n').map(line => line.split('='))), {
                publish: String(expected), mode, trusted_base_sha: '0'.repeat(39) + '1',
                sdk_version: identity.version, release_id: identity.releaseId,
                prerelease: String(identity.prerelease),
            });
        }
    } finally {
        fs.rmSync(work, { recursive: true, force: true });
    }
});

for (const version of [IDENTITY.version, ...['alpha', 'beta', 'rc']
    .map(channel => `${IDENTITY.major}.${IDENTITY.minor}.${IDENTITY.patch + 1}-${channel}.12`)]) {
test(`SDK 发布与恢复按精确身份判断 Central 和冻结附件：${version}`, () => {
    const identity = parseSdkVersion(version);
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-publication-'));
    try {
        const entry = path.join(work, 'check.sh');
        fs.writeFileSync(entry, [
            'curl() {',
            '  case "$SDK_TEST_CENTRAL" in',
            '    complete) printf 200 ;;',
            '    empty) printf 404 ;;',
            '    partial) case "$*" in *"/$SDK_TEST_FIRST/"*) printf 200 ;; *) printf 404 ;; esac ;;',
            '    unavailable) printf 503 ;;',
            '  esac',
            '}',
            'gh() {',
            '  if [[ "$*" == *"/git/ref/tags/"* ]]; then',
            '    if [[ "$SDK_TEST_TAG" == true ]]; then printf "{}"; return; fi',
            '    printf "HTTP 404" >&2; return 1',
            '  fi',
            '  if [[ "$*" == *"/releases?per_page=100"* ]]; then printf "%s" "$SDK_TEST_RELEASES"; return; fi',
            '  printf "Unexpected external request" >&2; return 1',
            '}',
            script,
        ].join('\n'), 'utf8');
        for (const [mode, central, release, tag, accept, published] of [
            ['publish', 'empty', 'absent', false, true, false],
            ['publish', 'empty', 'draft', false, false],
            ['publish', 'empty', 'draft', true, false],
            ['publish', 'empty', 'published', true, false],
            ['publish', 'complete', 'draft', false, true, false],
            ['publish', 'complete', 'draft', true, true, false],
            ['publish', 'complete', 'published', true, true, true],
            ['publish', 'complete', 'absent', false, false],
            ['publish', 'complete', 'duplicate', true, false],
            ['publish', 'partial', 'draft', true, false],
            ['publish', 'unavailable', 'absent', false, false],
            ['publish', 'empty', 'absent', true, false],
            ['recover-release', 'complete', 'draft', true, true, false],
            ['recover-release', 'complete', 'published', true, true, true],
            ['recover-release', 'complete', 'absent', false, false],
            ['recover-release', 'empty', 'absent', false, false],
            ['recover-release', 'empty', 'draft', false, false],
            ['recover-release', 'partial', 'draft', true, false],
            ['recover-release', 'complete', 'duplicate', true, false],
            ['invalid-mode', 'empty', 'absent', false, false],
        ]) {
            const output = path.join(work, 'output.txt');
            fs.writeFileSync(output, '', 'utf8');
            const frozen = { tag_name: identity.releaseId, draft: release !== 'published' };
            const releases = release === 'absent' ? [] : [frozen];
            if (release === 'duplicate') releases.push(frozen);
            const result = spawnSync('bash', [entry.replaceAll('\\', '/')], {
                cwd: ROOT, encoding: 'utf8',
                env: { ...process.env, PUBLICATION_MODE: mode, SDK_VERSION: identity.version,
                    RELEASE_ID: identity.releaseId, SDK_REPOSITORY: 'fixture/sdk',
                    CENTRAL_BASE_URL: 'https://invalid.example/maven', GITHUB_OUTPUT: output.replaceAll('\\', '/'),
                    SDK_TEST_CENTRAL: central, SDK_TEST_TAG: String(tag),
                    SDK_TEST_FIRST: SDK_ARTIFACTS[0][0], SDK_TEST_RELEASES: JSON.stringify([[], releases]) },
            });
            const label = [mode, central, release, tag].join('/');
            assert.equal(result.status === 0, accept, label + ': ' + (result.error ?? result.stderr));
            const outputs = fs.readFileSync(output, 'utf8');
            const expected = 'reuse_release=' + (release !== 'absent') + '\npublished=' + published
                + '\npublish_central=' + (central === 'empty') + '\n';
            assert.equal(outputs, accept ? expected : '', label);
        }
    } finally {
        fs.rmSync(work, { recursive: true, force: true });
    }
});
}
