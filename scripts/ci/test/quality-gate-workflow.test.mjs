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
    assert.equal(publishAction.inputs.cross_repo_release_token.required, true);
    assert.equal(publishAction.inputs.nightly_build_version.default, '');
    assert.equal(publishAction.outputs.manifest_commit.value,
        '${{ steps.commit-manifest.outputs.manifest_commit }}');
    assert.equal(publishAction.inputs.plugins_repo_token, undefined);
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
        'CROSS_REPO_RELEASE_TOKEN', 'PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64',
    ]);
    for (const doc of [release, nightly]) {
        const job = doc.jobs['publish-plugin-artifacts'];
        assert.ok((Array.isArray(job.needs) ? job.needs : [job.needs]).includes('publish-plugins'));
        assert.deepEqual(secretNames(job).sort(), [
            'CROSS_REPO_RELEASE_TOKEN', 'PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64',
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

    const releasePluginPublication = release.jobs['publish-plugin-artifacts'].steps
        .find((step) => step.uses === './.github/actions/publish-official-plugins');
    const nightlyPluginPublication = nightly.jobs['publish-plugin-artifacts'].steps
        .find((step) => step.uses === './.github/actions/publish-official-plugins');
    assert.equal(releasePluginPublication.with.nightly_build_version, undefined);
    assert.equal(nightlyPluginPublication.with.nightly_build_version,
        '${{ needs.resolve-version.outputs.version }}');
    assert.equal(nightly.jobs['publish-plugin-artifacts'].outputs.manifest_commit,
        '${{ steps.publish.outputs.manifest_commit }}');
    assert.doesNotMatch(release.jobs['build-jar'].steps
        .find((step) => step.name === 'Stage official plugin inputs from signed catalog').run,
        /nightly-manifest\.json/);
    assert.match(nightly.jobs['build-jar'].steps
        .find((step) => step.name === 'Stage official plugin inputs from signed catalog').run,
        /PLUGIN_MANIFEST_COMMIT\/nightly-manifest\.json/);
});

test('FFmpeg：手动流程从官方稳定源码构建并在门禁后发布五个平台资产', () => {
    const ffmpeg = load('.github/workflows/build-stable-ffmpeg.yml');
    const policy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts', 'ci', 'release-gate-policy.json'), 'utf8'));
    assert.equal(ffmpeg.name, 'Build stable FFmpeg');
    assert.deepEqual(triggers(ffmpeg), ['workflow_dispatch']);
    assert.deepEqual(ffmpeg.permissions, { contents: 'read' });
    assert.deepEqual(ffmpeg.jobs.build.strategy.matrix.include.map((item) => item.asset), [
        'windows-x64', 'linux-x64', 'linux-arm64', 'macos-x64', 'macos-arm64',
    ]);
    assert.equal(ffmpeg.jobs.publish.environment, 'release');
    assert.ok(ffmpeg.jobs.publish.needs.includes('quality-gate'));
    assert.equal(ffmpeg.jobs.publish.env.GH_TOKEN, '${{ secrets.CROSS_REPO_RELEASE_TOKEN }}');
    assert.equal(ffmpeg.jobs.publish.steps.find((step) => step.name === 'Checkout').with.ref,
        '${{ github.sha }}');
    assert.equal(ffmpeg.jobs.publish.steps.find((step) => step.name === 'Download platform archives')
        .with.pattern, 'ffmpeg-*-*');
    const generateManifest = ffmpeg.jobs.publish.steps
        .find((step) => step.name === 'Generate checksums and manifest');
    const signManifest = ffmpeg.jobs.publish.steps
        .find((step) => step.name === 'Sign FFmpeg release manifest');
    const publishRelease = ffmpeg.jobs.publish.steps
        .find((step) => step.name === 'Publish Remote Content release');
    assert.match(generateManifest.run, /expectedSizeBytes: fs\.statSync\(file\)\.size/);
    assert.equal(signManifest.env.PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64,
        '${{ secrets.PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64 }}');
    assert.match(signManifest.run, /manifest --manifest assets\/ffmpeg-release\.json --repository-id ffmpeg-stable/);
    assert.match(signManifest.run, /pixivdownloader-official-root-2026-07/);
    assert.match(publishRelease.run, /assets\/ffmpeg-release\.json\.sig/);
    assert.equal(ffmpeg.env.REMOTE_CONTENT_REPO, 'Sywyar/PixivDownloader-Remote-Content');
    assert.equal(ffmpeg.env.RELEASE_TAG, 'ffmpeg-stable');
    assert.equal(ffmpeg.env.FFMPEG_SIGNING_KEY_FINGERPRINT,
        'FCF986EA15E6E293A5644F10B4322F04D67658D8');
    assert.equal(ffmpeg.env.LIBWEBP_COMMIT, '4fa21912338357f89e4fd51cf2368325b59e9bd9');
    assert.deepEqual(policy.workflows['.github/workflows/build-stable-ffmpeg.yml'].requiredTriggers,
        ['workflow_dispatch']);
    const sourceVerification = ffmpeg.jobs['resolve-source'].steps
        .find((step) => step.name === 'Resolve and verify official stable source');
    const dependencyInstall = ffmpeg.jobs.build.steps
        .find((step) => step.name === 'Install build dependencies');
    const platformBuild = ffmpeg.jobs.build.steps
        .find((step) => step.name === 'Build FFmpeg and libwebp');
    const packageBinaries = ffmpeg.jobs.build.steps
        .find((step) => step.name === 'Verify and package binaries');
    assert.ok(sourceVerification);
    assert.ok(dependencyInstall);
    assert.ok(platformBuild);
    assert.ok(packageBinaries);
    assert.match(sourceVerification.run, /gpg --batch --verify/);
    assert.match(dependencyInstall.run, /if \[\[ "\$ASSET_ID" == "macos-x64" \]\]; then\s+brew install nasm/);
    assert.match(platformBuild.run, /--pkg-config=pkg-config/);
    assert.match(platformBuild.run, /tail -n 200 ffbuild\/config\.log/);
    assert.match(platformBuild.run, /CONFIG_LIBWEBP_ENCODER 1\$' config_components\.h/);
    assert.match(packageBinaries.run, /ffmpeg-LGPLv2\.1\.txt/);
    assert.match(packageBinaries.run, /libwebp-COPYING\.txt/);
    assert.match(packageBinaries.run, /libwebp-PATENTS\.txt/);
    assert.doesNotMatch(JSON.stringify(ffmpeg), /BtbN|ffmpeg-master-latest/);
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
        '.github/workflows/publish-plugins.yml', '.github/workflows/build-stable-ffmpeg.yml',
        '.github/actions/publish-official-plugins/action.yml']) {
        const text = fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8');
        assert.doesNotMatch(text, /always\(\)|!cancelled\(\)|continue-on-error/);
        assert.doesNotMatch(text, /PLUGIN_SIGNING_PRIVATE_KEY_PEM(?:\s|:|\})/);
        assert.doesNotMatch(text, /PLUGINS_REPO_TOKEN|plugins_repo_token/);
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
