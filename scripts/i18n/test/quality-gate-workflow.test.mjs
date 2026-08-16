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
