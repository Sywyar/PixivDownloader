'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const require = createRequire(path.join(ROOT, 'package.json'));
const YAML = require('yaml');

function load(rel) {
    return YAML.parse(fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8'));
}

function triggers(doc) {
    return Object.keys(doc.on ?? doc.true ?? {});
}

function secretNames(job) {
    return [...new Set([...JSON.stringify(job).matchAll(/secrets\.([A-Z0-9_]+)/g)]
        .map((match) => match[1]))];
}

test('Quality Gate：五个 required context 与完整触发面保持稳定', () => {
    const doc = load('.github/workflows/quality-gate.yml');
    assert.equal(doc.name, 'Quality Gate');
    assert.deepEqual(Object.keys(doc.jobs), [
        'java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract', 'i18n-check',
    ]);
    assert.deepEqual(triggers(doc), [
        'push', 'pull_request', 'merge_group', 'workflow_dispatch', 'workflow_call',
    ]);
    assert.deepEqual(doc.on.push['branches-ignore'], ['gh-pages']);
    for (const id of ['signature-guard', 'trusted-gate-contract']) {
        const scripts = doc.jobs[id].steps.map((step) => step.run || '').join('\n');
        assert.match(scripts, /resolve-trusted-base\.mjs/);
        assert.match(scripts, /git show "\$BASE_SHA:\$rel"/);
        assert.match(scripts, /gate-parity\.mjs/);
    }
});

test('发布链：所有凭据与写权限只在 release Environment 的门禁后使用', () => {
    const publish = load('.github/workflows/publish-plugins.yml');
    assert.equal(publish.jobs['quality-gate'].uses, './.github/workflows/quality-gate.yml');
    assert.equal(publish.jobs.publish.environment, 'release');
    assert.deepEqual(publish.jobs.publish.needs, 'quality-gate');

    const release = load('.github/workflows/release.yml');
    const nightly = load('.github/workflows/nightly.yml');
    assert.equal(release.jobs['publish-plugins'].uses, './.github/workflows/publish-plugins.yml');
    assert.equal(nightly.jobs['publish-plugins'].uses, './.github/workflows/publish-plugins.yml');
    for (const [doc, ids] of [[release, ['build-jar', 'build-windows-installer', 'release', 'create-draft-release']],
        [nightly, ['build-jar', 'build-windows-installer', 'release-nightly']]]) {
        for (const id of ids) assert.equal(doc.jobs[id].environment, 'release');
    }

    for (const doc of [publish, release, nightly]) {
        for (const [id, job] of Object.entries(doc.jobs)) {
            if (secretNames(job).length || job.permissions?.contents === 'write') {
                assert.equal(job.environment, 'release', `${id} must isolate credentials and write permission`);
            }
        }
    }

    assert.deepEqual(secretNames(publish.jobs.publish).sort(), [
        'PLUGINS_REPO_TOKEN', 'PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64',
    ]);
    for (const doc of [release, nightly]) {
        for (const [id, job] of Object.entries(doc.jobs)) {
            assert.equal(secretNames(job).includes('PIXIVDOWNLOAD_PLUGIN_CREDENTIAL_MASTER_KEY_BASE64'),
                id === 'build-jar');
        }
    }
    assert.deepEqual(secretNames(release.jobs.release), ['UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64']);
    assert.deepEqual(secretNames(nightly.jobs['release-nightly']), ['UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64']);
});

test('发布链：仅接受 Base64 私钥且不存在失败绕过', () => {
    for (const rel of ['.github/workflows/release.yml', '.github/workflows/nightly.yml',
        '.github/workflows/publish-plugins.yml']) {
        const text = fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8');
        assert.doesNotMatch(text, /always\(\)|!cancelled\(\)|continue-on-error/);
        assert.doesNotMatch(text, /PLUGIN_SIGNING_PRIVATE_KEY_PEM(?:\s|:|\})/);
    }
    for (const rel of ['.github/workflows/release.yml', '.github/workflows/nightly.yml']) {
        const text = fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8');
        assert.match(text, /UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64/);
        assert.match(text, /pixivdownloader-update-root-2026-08/);
    }
});

test('Nightly：共享变更门禁以语义输出控制全部昂贵任务', () => {
    const nightly = load('.github/workflows/nightly.yml');
    for (const id of ['publish-plugins', 'build-jar', 'build-windows-installer', 'release-nightly']) {
        assert.equal(nightly.jobs[id].if, "needs.resolve-version.outputs.has_changes == 'true'");
    }
    const resolveScripts = nightly.jobs['resolve-version'].steps.map((step) => step.run || '').join('\n');
    assert.match(resolveScripts, /nightly-changelog-gate\.sh\s+CHANGELOG\.md\s+nightly/);
});
