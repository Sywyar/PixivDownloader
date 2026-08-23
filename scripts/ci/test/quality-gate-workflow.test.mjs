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
    assert.deepEqual(doc.permissions, { contents: 'read' });
    assert.deepEqual(Object.keys(doc.jobs), [
        'java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract', 'i18n-check',
    ]);
    assert.deepEqual(triggers(doc), [
        'push', 'pull_request', 'merge_group', 'workflow_dispatch', 'workflow_call',
    ]);
    assert.deepEqual(doc.on.push['branches-ignore'], ['gh-pages']);
    for (const id of ['signature-guard', 'trusted-gate-contract']) {
        const resolve = doc.jobs[id].steps.find((step) => step.name === 'Resolve protected predecessor');
        const scripts = doc.jobs[id].steps.map((step) => step.run || '').join('\n');
        assert.doesNotMatch(resolve.run, /\$\{\{/);
        assert.equal(resolve.env.EVENT_PR_BASE_REF, '${{ github.event.pull_request.base.ref }}');
        assert.equal(resolve.env.INPUT_TRUSTED_BASE_SHA, '${{ inputs.trusted_base_sha }}');
        assert.match(resolve.run, /GITHUB_EVENT_NAME" = "workflow_dispatch"/);
        assert.match(resolve.run, /EVENT_PR_BASE_REF" != "\$DEFAULT_BRANCH"/);
        assert.match(resolve.run, /git merge-base "\$GITHUB_SHA" "\$PROTECTED_TIP"/);
        assert.match(resolve.run, /--input-base "\$TRUSTED_BASE_SHA"/);
        assert.match(scripts, /resolve-trusted-base\.mjs/);
        assert.match(scripts, /git show "\$BASE_SHA:\$rel"/);
        assert.match(scripts, /gate-parity\.mjs/);
    }
});

test('发布链：所有凭据与写权限只在 release Environment 的门禁后使用', () => {
    const publish = load('.github/workflows/publish-plugins.yml');
    const publishAction = load('.github/actions/publish-official-plugins/action.yml');
    assert.equal(publish.jobs['quality-gate'].uses, './.github/workflows/quality-gate.yml');
    assert.equal(publish.jobs.publish.environment, 'release');
    assert.deepEqual(publish.jobs.publish.needs, 'quality-gate');
    assert.equal(publish.on.workflow_call.inputs.publish_in_caller.required, true);
    assert.equal(publishAction.runs.using, 'composite');
    assert.equal(publishAction.inputs.plugin_signing_private_key_pem_base64.required, true);
    assert.equal(publishAction.inputs.plugins_repo_token.required, true);
    assert.deepEqual(secretNames(publishAction), []);
    const directPublish = publish.jobs.publish.steps
        .find((step) => step.uses === './.github/actions/publish-official-plugins');
    assert.equal(directPublish.if, 'inputs.publish_in_caller != true');
    assert.equal(publish.jobs.publish.steps.find((step) => step.name === 'Complete delegated publication gate')?.if,
        'inputs.publish_in_caller == true');

    const release = load('.github/workflows/release.yml');
    const nightly = load('.github/workflows/nightly.yml');
    const sharedSnippets = load('.github/workflows/shared-snippets-check.yml');
    assert.deepEqual(publish.permissions, { contents: 'read' });
    assert.deepEqual(release.permissions, { contents: 'read' });
    assert.deepEqual(nightly.permissions, { contents: 'read' });
    assert.deepEqual(sharedSnippets.permissions, { contents: 'read' });
    assert.equal(release.jobs['publish-plugins'].uses, './.github/workflows/publish-plugins.yml');
    assert.equal(nightly.jobs['publish-plugins'].uses, './.github/workflows/publish-plugins.yml');
    assert.equal(release.jobs['publish-plugins'].with.publish_in_caller, true);
    assert.equal(nightly.jobs['publish-plugins'].with.publish_in_caller, true);
    for (const [doc, ids] of [[release, ['publish-plugin-artifacts', 'build-jar', 'build-windows-installer',
        'release', 'create-draft-release']],
        [nightly, ['publish-plugin-artifacts', 'build-jar', 'build-windows-installer', 'release-nightly']]]) {
        for (const id of ids) assert.equal(doc.jobs[id].environment, 'release');
    }

    for (const doc of [publish, release, nightly]) {
        for (const [id, job] of Object.entries(doc.jobs)) {
            if (secretNames(job).length || job.permissions?.contents === 'write') {
                assert.equal(job.environment, 'release', `${id} must isolate credentials and write permission`);
            }
        }
    }
    const writeJobs = (doc) => Object.entries(doc.jobs)
        .filter(([, job]) => job.permissions?.contents === 'write')
        .map(([id]) => id);
    assert.deepEqual(writeJobs(publish), []);
    assert.deepEqual(writeJobs(release), ['release', 'create-draft-release']);
    assert.deepEqual(writeJobs(nightly), ['release-nightly']);

    assert.deepEqual(secretNames(publish.jobs.publish).sort(), [
        'PLUGINS_REPO_TOKEN', 'PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64',
    ]);
    for (const doc of [release, nightly]) {
        const job = doc.jobs['publish-plugin-artifacts'];
        assert.ok((Array.isArray(job.needs) ? job.needs : [job.needs]).includes('publish-plugins'));
        assert.deepEqual(secretNames(job).sort(), [
            'PLUGINS_REPO_TOKEN', 'PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64',
        ]);
        assert.equal(job.steps.find((step) => step.uses === './.github/actions/publish-official-plugins')
            ?.name, 'Publish official plugins');
    }
    assert.deepEqual(release.jobs['build-jar'].needs, ['validate-release-tag', 'publish-plugin-artifacts']);
    assert.deepEqual(nightly.jobs['build-jar'].needs, ['resolve-version', 'publish-plugin-artifacts']);
    assert.ok(release.jobs.release.needs.includes('publish-plugin-artifacts'));
    assert.ok(nightly.jobs['release-nightly'].needs.includes('publish-plugin-artifacts'));
    for (const doc of [release, nightly]) {
        for (const [id, job] of Object.entries(doc.jobs)) {
            assert.equal(secretNames(job).includes('PIXIVDOWNLOAD_PLUGIN_CREDENTIAL_MASTER_KEY_BASE64'),
                id === 'build-jar');
        }
    }
    assert.deepEqual(secretNames(release.jobs.release), ['UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64']);
    assert.deepEqual(secretNames(nightly.jobs['release-nightly']), ['UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64']);
});

test('发布链：外部 ref 与输入先校验，再通过环境变量进入 shell', () => {
    const release = load('.github/workflows/release.yml');
    const nightly = load('.github/workflows/nightly.yml');
    const publish = load('.github/workflows/publish-plugins.yml');
    const releaseTagPattern = String.raw`^v(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})(-beta\.[1-9][0-9]{0,8})?$`;
    const workflowDir = path.join(ROOT, '.github', 'workflows');
    for (const file of fs.readdirSync(workflowDir).filter((name) => name.endsWith('.yml'))) {
        const doc = load(`.github/workflows/${file}`);
        for (const [jobId, job] of Object.entries(doc.jobs)) {
            for (const step of job.steps || []) {
                assert.doesNotMatch(step.run || '', /\$\{\{/, `${file}/${jobId}/${step.name}`);
            }
        }
    }
    const publishAction = load('.github/actions/publish-official-plugins/action.yml');
    for (const step of publishAction.runs.steps) {
        assert.doesNotMatch(step.run || '', /\$\{\{/, `publish action/${step.name}`);
    }

    const releaseValidation = release.jobs['validate-release-tag'];
    const releaseVersion = releaseValidation.steps.find((step) => step.name === 'Validate release tag');
    assert.ok(releaseVersion.run.includes(releaseTagPattern));
    assert.match(releaseVersion.run, /unsupported release tag/);
    assert.equal(releaseVersion.env.RELEASE_TAG, '${{ github.ref_name }}');
    assert.equal(releaseValidation.outputs.version, '${{ steps.vars.outputs.version }}');
    assert.equal(release.jobs['publish-plugins'].needs, 'validate-release-tag');
    assert.equal(release.jobs['build-jar'].outputs.version,
        '${{ needs.validate-release-tag.outputs.version }}');
    assert.equal(release.jobs['build-jar'].env.RELEASE_VERSION,
        '${{ needs.validate-release-tag.outputs.version }}');
    assert.equal(release.jobs['build-jar'].steps
        .find((step) => step.name === 'Resolve version'), undefined);
    assert.equal(release.jobs['build-windows-installer'].env.RELEASE_VERSION,
        '${{ needs.build-jar.outputs.version }}');
    assert.equal(release.jobs['build-windows-installer'].steps
        .find((step) => step.name === 'Resolve version'), undefined);
    const draftTag = release.jobs['create-draft-release'].steps
        .find((step) => step.name === 'Verify draft tag targets the tested commit');
    assert.ok(draftTag.run.includes(releaseTagPattern));
    assert.match(draftTag.run, /unsupported draft release tag/);

    const nightlyVersion = nightly.jobs['resolve-version'].steps
        .find((step) => step.name === 'Resolve next version');
    assert.ok(nightlyVersion.run.includes(releaseTagPattern));
    assert.match(nightlyVersion.run, /while IFS= read -r tag/);
    assert.match(nightlyVersion.run, /LATEST_TAG="\$tag"/);
    assert.doesNotMatch(nightlyVersion.run, /unsupported release tag/);
    for (const id of ['build-jar', 'build-windows-installer', 'release-nightly']) {
        assert.equal(nightly.jobs[id].env.RELEASE_VERSION,
            '${{ needs.resolve-version.outputs.version }}');
    }
});

test('发布链：仅接受 Base64 私钥且不存在失败绕过', () => {
    for (const rel of ['.github/workflows/release.yml', '.github/workflows/nightly.yml',
        '.github/workflows/publish-plugins.yml', '.github/actions/publish-official-plugins/action.yml']) {
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
    for (const id of ['publish-plugins', 'publish-plugin-artifacts', 'build-jar', 'build-windows-installer',
        'release-nightly']) {
        assert.equal(nightly.jobs[id].if, "needs.resolve-version.outputs.has_changes == 'true'");
    }
    const resolveScripts = nightly.jobs['resolve-version'].steps.map((step) => step.run || '').join('\n');
    assert.match(resolveScripts, /nightly-changelog-gate\.sh\s+CHANGELOG\.md\s+nightly/);
});
