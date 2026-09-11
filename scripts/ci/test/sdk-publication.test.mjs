import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import { inspectSdkVersion, SDK_ARTIFACTS } from '../sdk-version.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const IDENTITY = inspectSdkVersion(ROOT);
const workflow = YAML.parse(fs.readFileSync(path.join(ROOT, '.github/workflows/publish-sdk.yml'), 'utf8'));
const script = workflow.jobs.publish.steps.find(step => step.name === 'Check immutable publication state').run;

test('SDK 发布状态实际拒绝部分 Central、冲突公开身份和缺少冻结附件的恢复', () => {
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
            ['publish', 'empty', 'draft', false, true, false],
            ['publish', 'empty', 'draft', true, true, false],
            ['publish', 'empty', 'published', true, false],
            ['publish', 'complete', 'draft', true, false],
            ['publish', 'partial', 'draft', true, false],
            ['publish', 'unavailable', 'absent', false, false],
            ['publish', 'empty', 'absent', true, false],
            ['recover-release', 'complete', 'draft', true, true, false],
            ['recover-release', 'complete', 'published', true, true, true],
            ['recover-release', 'complete', 'absent', false, false],
            ['recover-release', 'empty', 'draft', false, false],
            ['recover-release', 'partial', 'draft', true, false],
            ['recover-release', 'complete', 'duplicate', true, false],
        ]) {
            const output = path.join(work, 'output.txt');
            fs.writeFileSync(output, '', 'utf8');
            const frozen = { tag_name: IDENTITY.releaseId, draft: release !== 'published' };
            const releases = release === 'absent' ? [] : [frozen];
            if (release === 'duplicate') releases.push(frozen);
            const result = spawnSync('bash', [entry.replaceAll('\\', '/')], {
                cwd: ROOT, encoding: 'utf8',
                env: { ...process.env, PUBLICATION_MODE: mode, SDK_VERSION: IDENTITY.version,
                    RELEASE_ID: IDENTITY.releaseId, SDK_REPOSITORY: 'fixture/sdk',
                    CENTRAL_BASE_URL: 'https://invalid.example/maven', GITHUB_OUTPUT: output.replaceAll('\\', '/'),
                    SDK_TEST_CENTRAL: central, SDK_TEST_TAG: String(tag),
                    SDK_TEST_FIRST: SDK_ARTIFACTS[0][0], SDK_TEST_RELEASES: JSON.stringify([[], releases]) },
            });
            const label = [mode, central, release, tag].join('/');
            assert.equal(result.status === 0, accept, label + ': ' + (result.error ?? result.stderr));
            const outputs = fs.readFileSync(output, 'utf8');
            const expected = 'reuse_release=' + (release !== 'absent') + '\npublished=' + published + '\n';
            assert.equal(outputs, accept ? expected : '', label);
        }
    } finally {
        fs.rmSync(work, { recursive: true, force: true });
    }
});
