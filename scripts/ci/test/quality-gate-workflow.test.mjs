'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const require = createRequire(path.join(ROOT, 'package.json'));
const YAML = require('yaml');
const POLICY = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/ci/release-gate-policy.json'), 'utf8'));

function load(rel) {
    return YAML.parse(fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8'));
}

function executionSteps(job) {
    return (job.steps || []).flatMap((step) => step.uses?.startsWith('./.github/actions/')
        ? [step, ...executionSteps({ steps: load(`${step.uses}/action.yml`).runs.steps })]
        : [step]);
}

function triggers(doc) {
    return Object.keys(doc.on ?? doc.true ?? {});
}

function secretNames(job) {
    return [...new Set([...JSON.stringify(job).matchAll(/secrets\.([A-Z0-9_]+)/g)]
        .map((match) => match[1]))];
}

function assertActionPins(text) {
    const doc = YAML.parseDocument(text);
    assert.deepEqual(doc.errors, []);
    const jobs = doc.get('jobs')?.items.map(({ value }) => value) || [doc.get('runs')];
    let count = 0;
    for (const job of jobs) {
        for (const step of job?.get('steps')?.items || []) {
            const uses = step.get('uses', true);
            if (!uses || uses.value.startsWith('./')) continue;
            assert.match(uses.value, /@[0-9a-f]{40}$/u, 'external Action must use a full commit SHA');
            assert.match(uses.comment?.trim().split(/\s+/u)[0] || '',
                /^v[1-9][0-9]*(?:\.[0-9]+\.[0-9]+)?$/u, 'external Action must keep a readable version comment');
            count++;
        }
    }
    return count;
}

test('Action 引用按 YAML step 校验完整 SHA 与版本注释，reusable 调用由可信合同校验', () => {
    const action = `actions/checkout@${'a'.repeat(40)}`;
    const workflow = `jobs:\n  reusable:\n    uses: owner/repo/.github/workflows/check.yml@master\n  direct:\n    steps:\n      - uses: ${action} # v8\n`;
    assert.equal(assertActionPins(workflow), 1);
    assert.equal(assertActionPins(workflow.replace('# v8', '# v8.0.1')), 1);
    assert.equal(assertActionPins(`runs:\n  using: composite\n  steps:\n    - uses: ${action} # v8\n`), 1);
    assert.throws(() => assertActionPins(workflow.replace(action, 'actions/checkout@master')), /full commit SHA/u);
    for (const comment of ['', '# v8.0']) {
        assert.throws(() => assertActionPins(workflow.replace('# v8', comment)), /version comment/u);
    }
    const githubRoot = path.join(ROOT, '.github');
    let count = 0;
    for (const rel of fs.readdirSync(githubRoot, { recursive: true })) {
        if (!(path.dirname(rel) === 'workflows' && /\.ya?ml$/u.test(rel))
            && !/^action\.ya?ml$/u.test(path.basename(rel))) continue;
        count += assertActionPins(fs.readFileSync(path.join(githubRoot, rel), 'utf8'));
    }
    assert.ok(count > 0);
    const dependabot = load('.github/dependabot.yml');
    assert.equal(dependabot.version, 2);
    assert.ok(dependabot.updates.some((entry) => entry['package-ecosystem'] === 'github-actions'
        && entry.directory === '/' && entry.schedule?.interval === 'weekly'));
});

test('PR concurrency cancels only earlier validation of the same PR and workflow', () => {
    const evaluate = (text, github) => text.replace(/\$\{\{(.*?)\}\}/g,
        (_, expression) => String(vm.runInNewContext(expression, { github })));
    if (POLICY.gateEpoch >= 8) {
        const caller = load('.github/workflows/pr-quality-gate.yml');
        assert.deepEqual(triggers(caller), ['pull_request']);
        assert.equal(caller.concurrency['cancel-in-progress'], true);
        const first = { event: { pull_request: { number: 1 } } };
        const second = { event: { pull_request: { number: 2 } } };
        assert.equal(evaluate(caller['run-name'], first), 'PR #1');
        assert.equal(evaluate(caller['run-name'], second), 'PR #2');
        assert.notEqual(evaluate(caller.concurrency.group, first), evaluate(caller.concurrency.group, second));
        const textEdit = { event: { ...first.event, action: 'edited', changes: {} } };
        const baseEdit = { event: { ...textEdit.event, changes: { base: {} } } };
        assert.notEqual(evaluate(caller.concurrency.group, first), evaluate(caller.concurrency.group, textEdit));
        assert.equal(evaluate(caller.concurrency.group, first), evaluate(caller.concurrency.group, baseEdit));
        assert.equal(evaluate(caller.concurrency.group, first), evaluate(caller.concurrency.group,
            { event: { ...first.event, action: 'synchronize' } }));
        assert.equal(load('.github/workflows/quality-gate.yml').concurrency, undefined);
        return;
    }
    for (const rel of ['.github/workflows/quality-gate.yml', '.github/workflows/shared-snippets-check.yml']) {
        const { concurrency } = load(rel);
        const run = { workflow: rel, event_name: 'pull_request', event: { pull_request: { number: 1 } }, run_id: 10 };
        const group = (context) => evaluate(concurrency.group, context);
        assert.equal(group(run), group({ ...run, run_id: 11 }));
        assert.notEqual(group(run), group({ ...run, event: { pull_request: { number: 2 } } }));
        assert.notEqual(group(run), group({ ...run, workflow: 'another workflow' }));
        assert.equal(evaluate(concurrency['cancel-in-progress'], run), 'true');
        for (const event_name of ['push', 'workflow_dispatch', 'schedule']) {
            const other = { ...run, event_name, event: { pull_request: {} } };
            assert.notEqual(group(other), group({ ...other, run_id: 11 }));
            assert.equal(evaluate(concurrency['cancel-in-progress'], other), 'false');
        }
    }
});

test('check publication serializes writers for the same head without canceling active publication', () => {
    const { concurrency } = load('.github/workflows/gate-checks.yml');
    const group = (github) => concurrency.group.replace(/\$\{\{(.*?)\}\}/g,
        (_, expression) => String(vm.runInNewContext(expression, { github })));
    const upstream = { event: { workflow_run: { id: 10, head_sha: 'same-head' } }, sha: 'protected-base' };
    assert.equal(concurrency['cancel-in-progress'], false);
    assert.equal(group(upstream), group({ ...upstream, event: { workflow_run: { ...upstream.event.workflow_run, run_attempt: 2 } } }));
    const sameHead = { ...upstream, event: { workflow_run: { id: 11, head_sha: 'same-head' } } };
    assert.equal(group(upstream), group(sameHead));
    assert.equal(concurrency.queue, 'max');
    assert.notEqual(group(upstream), group({ ...upstream, event: { workflow_run: { id: 12, head_sha: 'other-head' } } }));
    const master = { event: { workflow_run: {} }, sha: 'integrated-commit' };
    assert.notEqual(group(upstream), group(master));
    assert.notEqual(group(master), group({ ...master, sha: 'next-integrated-commit' }));
});

test('主线手动全量验证只在完成后核对证据，PR 进行中事件仍撤销旧成功检查', () => {
    if (POLICY.gateEpoch === 5) return;
    const condition = load('.github/workflows/gate-checks.yml').jobs.checks.if;
    for (const [event_name, event, head_branch, action, expected] of [
        ['push', '', '', '', true],
        ['workflow_run', 'pull_request', 'feature', 'in_progress', true],
        ['workflow_run', 'pull_request', 'feature', 'completed', true],
        ['workflow_run', 'workflow_dispatch', 'master', 'in_progress', false],
        ['workflow_run', 'workflow_dispatch', 'master', 'completed', true],
        ['workflow_run', 'workflow_dispatch', 'feature', 'completed', true],
    ]) {
        assert.equal(vm.runInNewContext(condition, { github: {
            event_name, event: { action, workflow_run: { event, head_branch } },
        } }), expected);
    }
});

test('Quality Gate preserves required roles and the active event contract', () => {
    const doc = load('.github/workflows/quality-gate.yml');
    assert.equal(doc.name, 'Quality Gate');
    assert.deepEqual(doc.permissions, { contents: 'read' });
    for (const id of [
        'java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract', 'i18n-check',
    ]) assert.ok(doc.jobs[id], `required job ${id}`);
    for (const event of POLICY.qualityGate.requiredTriggers) {
        assert.ok(triggers(doc).includes(event), `required event ${event}`);
    }
    if (POLICY.gateEpoch === 5) assert.deepEqual(doc.on.push['branches-ignore'], ['gh-pages']);
    else {
        assert.ok(doc.jobs['check-shared-snippets']);
        assert.deepEqual(triggers(doc).sort(), ['workflow_call', 'workflow_dispatch']);
    }
    const javaSteps = Object.values(doc.jobs).flatMap(executionSteps);
    const sdkResolve = javaSteps.find((step) => step.env?.INPUT_TRUSTED_BASE_SHA !== undefined);
    const releaseBuild = javaSteps.find((step) => /\b(?:verify|install)\b.*-Pofficial-surveys/.test(step.run || ''));
    const releaseBoundary = javaSteps.find((step) => /DistributionPackagingBoundaryTest/.test(step.run || ''));
    const sdkPackage = javaSteps.find((step) => /-DaltDeploymentRepository=/.test(step.run || ''));
    const sdkContract = javaSteps.find((step) => /sdk-contract\.mjs/.test(step.run || ''));
    assert.equal(sdkResolve.env.INPUT_TRUSTED_BASE_SHA, '${{ inputs.trusted_base_sha }}');
    assert.match(sdkResolve.run, /resolve-trusted-base\.mjs/u);
    assert.match(releaseBuild.run, /\bmvn(?:w)?\b/u);
    assert.match(releaseBuild.run, /-P\s*(?:[^\s,]+,)*official-surveys(?:,|\s|$)/u);
    assert.doesNotMatch(releaseBuild.run, /-Dexec\.skip(?:=true)?(?:\s|$)/u);
    assert.match(releaseBoundary.run, /DistributionPackagingBoundaryTest/u);
    assert.match(releaseBoundary.run, /distribution\.packaging\.require-artifacts=true/u);
    assert.match(releaseBoundary.run, /Failures: 0, Errors: 0, Skipped: 0/u);
    assert.ok(sdkPackage, 'SDK consumer artifacts must be built');
    const sdkArguments = execFileSync('bash', ['-e', '-o', 'pipefail', '-c',
        `mvn() { printf 'MAVEN_ARGUMENT:%s\\n' "$@"; }\n${sdkPackage.run}`], {
        cwd: ROOT, encoding: 'utf8',
        env: { ...process.env, GITHUB_WORKSPACE: ROOT.replaceAll('\\', '/'), SOURCE_SHA: 'a'.repeat(40) },
    }).trim().split('\n').filter(line => line.startsWith('MAVEN_ARGUMENT:'))
        .map(line => line.slice('MAVEN_ARGUMENT:'.length));
    assert.deepEqual(sdkArguments[sdkArguments.indexOf('-pl') + 1].split(','), [
        'pixivdownload-sdk-info', 'pixivdownload-plugin-api', 'pixivdownload-core-api',
        'pixivdownload-sdk-bom', 'pixivdownload-sdk',
    ], 'Maven receives every public SDK module as one argument');
    assert.ok(sdkArguments.includes('deploy'), 'SDK artifacts must reach the staging repository');
    assert.match(sdkContract.run, /git archive "\$SDK_BASE_SHA"/u);
    assert.match(sdkContract.run, /sdk-api-surface\.mjs/u);
    assert.match(sdkContract.run, /sdk-contract\.mjs/u);
    assert.match(sdkContract.run, /--base-sdk-root\b/u);
    assert.match(sdkContract.run, /--candidate-sdk-root\b/u);
    assert.doesNotMatch(sdkContract.run, /continue-on-error|always\(\)|failure\(\)|cancelled\(\)/u);
    for (const id of ['signature-guard', 'trusted-gate-contract']) {
        const resolve = doc.jobs[id].steps.find((step) => step.env?.EVENT_PR_BASE_REF !== undefined);
        const scripts = doc.jobs[id].steps.map((step) => step.run || '').join('\n');
        assert.doesNotMatch(resolve.run, /\$\{\{/);
        assert.equal(resolve.env.EVENT_PR_BASE_REF, '${{ github.event.pull_request.base.ref }}');
        assert.equal(resolve.env.INPUT_TRUSTED_BASE_SHA, '${{ inputs.trusted_base_sha }}');
        assert.match(scripts, /resolve-trusted-base\.mjs/);
        assert.match(scripts, /git show "\$BASE_SHA:\$rel"/);
    }
});

test('Java tests and ProGuard run independently and both gate the required Java result', () => {
    const { jobs } = load('.github/workflows/quality-gate.yml');
    const owner = (pattern) => {
        const matches = Object.keys(jobs).filter((id) => executionSteps(jobs[id]).some((step) => pattern.test(step.run || '')));
        assert.equal(matches.length, 1, `one execution owner for ${pattern}`);
        return matches[0];
    };
    const ancestors = (id, visited = new Set()) => {
        assert.ok(jobs[id], `dependency ${id} exists`);
        if (visited.has(id)) return visited;
        visited.add(id);
        for (const dependency of [jobs[id].needs || []].flat()) ancestors(dependency, visited);
        return visited;
    };
    const tests = owner(/\bmvn\b[^\n]*\btest\b[^\n]*-Duser\.language=/u);
    const build = owner(/\bmvn\b[^\n]*\b(?:verify|install)\b[^\n]*-Pofficial-surveys/u);
    assert.notEqual(tests, build);
    assert.equal(ancestors(tests).has(build), false);
    assert.equal(ancestors(build).has(tests), false);
    const sdk = owner(/sdk-contract\.mjs/u);
    assert.notEqual(sdk, tests);
    for (const [a, b] of [[tests, sdk], [build, sdk]]) {
        assert.equal(ancestors(a).has(b), false);
        assert.equal(ancestors(b).has(a), false);
    }
    const required = ancestors('java-tests');
    for (const id of [tests, build, owner(/DistributionPackagingBoundaryTest/u), owner(/sdk-contract\.mjs/u)]) {
        assert.ok(required.has(id), `java-tests must require ${id}`);
    }
    for (const id of required) {
        assert.equal(jobs[id].if, undefined, `${id} must preserve dependency success`);
        assert.ok(Number.isInteger(jobs[id]['timeout-minutes']) && jobs[id]['timeout-minutes'] > 0);
        assert.ok(jobs[id]['continue-on-error'] === undefined || jobs[id]['continue-on-error'] === false);
    }
});

test('QG 覆盖 Compose、SDK 消费者和两种 PowerShell，并顺序复用构建产物', () => {
    const { jobs } = load('.github/workflows/quality-gate.yml');
    const artifacts = executionSteps(jobs['release-artifacts']);
    const build = artifacts.findIndex(step => /mvn.*(?:verify|install).*-Pofficial-surveys/u.test(step.run || ''));
    const compose = artifacts.findIndex(step => /gradlew(?:\.bat)?.*mavenTest/u.test(step.run || ''));
    const boundaries = artifacts.findIndex(step => /DistributionPackagingBoundaryTest/u.test(step.run || ''));
    assert.ok(build >= 0 && compose > build && boundaries > build);
    assert.equal(artifacts[compose]['working-directory'], 'pixivdownload-plugin-gui-compose');
    assert.doesNotMatch(artifacts[compose].run, /-Pmaven(?:SkipTests|TestSkip)=true|(?:^|\s)-x(?:\s|$)/u);
    assert.equal(jobs['release-artifacts']['runs-on'], 'windows-latest');
    assert.ok(artifacts.some(step => step.shell === 'pwsh'
        && /scripts\/release-e2e\/runtime\.test\.ps1/u.test(step.run || '')));
    const boundaryCall = artifacts.find(step => step.uses === './.github/actions/verify-release-boundaries');
    assert.deepEqual(boundaryCall.with.additional_tests.split(',').sort(),
        ['DeleteStagingManifestTest#rejectsWindowsJunctionParentDuringRecovery',
            'PluginReleaseScriptsTest#releaseArtifactCleanupHandlesSealedPermissions+releaseArtifactFailurePrintsDiagnosticTail',
            'StagedFileDeletionTest#rejectsWindowsJunctionBeforeStaging',
            'WorkDeletionFileRollbackTest#novelJunctionAbortsFilesAndSoftDelete']);
    const sources = executionSteps(jobs['sdk-tests']);
    const stage = sources.findIndex(step => /altDeploymentRepository=sdk-staging/u.test(step.run || ''));
    const templates = sources.findIndex(step => /plugin-templates\/pom.xml/u.test(step.run || ''));
    const consumer = sources.findIndex(step => /sdk-consumer\.mjs/u.test(step.run || ''));
    const contract = sources.findIndex(step => /sdk-contract\.mjs/u.test(step.run || ''));
    assert.ok(stage >= 0 && templates > stage && consumer > stage && contract > stage);
    assert.doesNotMatch(sources[templates].run, /-D(?:skipTests|maven\.test\.skip)(?:=true)?(?:\s|$)/u);
    assert.match(sources[consumer].run, /sdk-release\.mjs/u);
    const scripts = Object.values(jobs).flatMap(executionSteps)
        .filter(step => /check-powershell\.ps1/u.test(step.run || ''));
    assert.deepEqual(scripts.map(step => step.shell).sort(), ['powershell', 'pwsh']);
    const release = load('.github/actions/build-release-java/action.yml').runs.steps
        .find(step => step.uses === './.github/actions/verify-release-boundaries');
    assert.ok(release);
    assert.equal(release.with.require_production_credential_key, 'true');
});

test('SDK 发布串行消费本次完整 QG 验证的候选，恢复继续使用原始冻结附件', () => {
    const qg = load('.github/workflows/quality-gate.yml');
    const sdk = load('.github/workflows/publish-sdk.yml');
    const producer = qg.jobs['sdk-tests'];
    const upload = producer.steps.find(step => step.id === 'sdk-candidate');
    assert.equal(qg.on.workflow_call.inputs.export_sdk_candidates.default, false);
    assert.equal(sdk.jobs['quality-gate'].with.export_sdk_candidates,
        "${{ needs.release-plan.outputs.mode == 'publish' }}");
    assert.match(upload.uses, /^actions\/upload-artifact@[a-f0-9]{40}$/u);
    assert.equal(upload.if.replace(/^\$\{\{\s*|\s*\}\}$/gu, ''), 'inputs.export_sdk_candidates == true');
    assert.equal(upload.with.path, 'target/sdk-release/');
    assert.equal(upload.with['if-no-files-found'], 'error');
    assert.equal(producer.outputs.candidate_id, '${{ steps.sdk-candidate.outputs.artifact-id }}');
    assert.equal(qg.on.workflow_call.outputs.sdk_candidate_id.value,
        '${{ jobs.sdk-tests.outputs.candidate_id }}');
    const steps = sdk.jobs.publish.steps;
    const download = steps.find(step => step.uses?.startsWith('actions/download-artifact@'));
    assert.deepEqual(download.with, {
        'artifact-ids': '${{ needs.quality-gate.outputs.sdk_candidate_id }}',
        path: 'target/sdk-release', 'merge-multiple': true,
    });
    assert.equal(download.if, "${{ steps.state.outputs.reuse_release == 'false' }}");
    assert.ok(!steps.some(step => step.uses === './.github/actions/verify-sdk'));
    const freeze = steps.findIndex(step => step.name === 'Freeze signed SDK assets before Central publication');
    assert.ok(steps.indexOf(download) < freeze);
    assert.match(steps[freeze].run, /--verify-directory target\/sdk-release --source-sha/u);
    assert.ok(steps.findIndex(step => /pixivdownload-plugin-signature package/u.test(step.run || '')) < freeze);
});

test('应用资源构建与 SDK 宿主消费者显式取得内置 GitHub 读取令牌', () => {
    const workflows = ['quality-gate', 'publish-sdk', 'nightly', 'release'];
    let builds = 0;
    for (const workflow of workflows) {
        const doc = load(`.github/workflows/${workflow}.yml`);
        for (const step of Object.values(doc.jobs).flatMap(executionSteps)) {
            const command = step.run || '';
            const appBuild = command.split('\n').some(line => /\bmvn\b.*\b(?:compile|test|verify|install)\b/u.test(line)
                && !line.includes('surefire:test')
                && (!line.includes('-pl') || /-pl\s+pixivdownload-(?:app|official-plugins)\b/u.test(line)));
            if (!appBuild && !command.includes('scripts/ci/sdk-consumer.mjs')) continue;
            assert.equal(step.env?.GITHUB_TOKEN, '${{ github.token }}', `${workflow}: ${step.name}`);
            builds++;
        }
    }
    assert.ok(builds > 0);
});

test('发布链：所有凭据与写权限只在 release Environment 的门禁后使用', () => {
    const publish = load('.github/workflows/publish-plugins.yml');
    const publishAction = load('.github/actions/publish-official-plugins/action.yml');
    const javaAction = load('.github/actions/build-release-java/action.yml');
    const pluginInputs = load('.github/actions/stage-release-plugins/action.yml');
    const windowsAction = load('.github/actions/package-windows-installer/action.yml');
    const updateSigningAction = load('.github/actions/sign-update-manifest/action.yml');
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
    const manifestCommit = publishAction.runs.steps.find((step) => step.id === 'commit-manifest');
    assert.ok(manifestCommit);
    assert.match(manifestCommit.run, /\$nightlyTag = "\$pluginId-nightly"/);
    assert.match(manifestCommit.run, /git tag -f \$nightlyTag HEAD/);
    assert.match(manifestCommit.run,
        /\+refs\/tags\/\$\{nightlyTag\}:refs\/tags\/\$\{nightlyTag\}/);
    assert.match(manifestCommit.run, /git push --atomic origin @nightlyTagRefspecs/);
    assert.equal(publishAction.inputs.plugins_repo_token, undefined);
    assert.deepEqual(secretNames(publishAction), []);
    for (const action of [javaAction, windowsAction, updateSigningAction]) {
        assert.equal(action.runs.using, 'composite');
        assert.deepEqual(secretNames(action), []);
    }
    const directPublish = publish.jobs.publish.steps
        .find((step) => step.uses === './.github/actions/publish-official-plugins');
    assert.equal(directPublish.if, 'inputs.publish_in_caller != true');
    assert.equal(publish.jobs.publish.steps.find((step) => step.name === 'Complete delegated publication gate')?.if,
        'inputs.publish_in_caller == true');

    const release = load('.github/workflows/release.yml');
    const nightly = load('.github/workflows/nightly.yml');
    assert.deepEqual(publish.permissions, { contents: 'read' });
    assert.deepEqual(release.permissions, { contents: 'read' });
    assert.deepEqual(nightly.permissions, { contents: 'read' });
    const sharedProvider = load(POLICY.gateEpoch >= 8
        ? '.github/workflows/quality-gate.yml' : '.github/workflows/shared-snippets-check.yml');
    assert.deepEqual(sharedProvider.permissions, { contents: 'read' });
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
    assert.deepEqual(release.jobs['build-jar'].needs, ['validate-release-tag', 'publish-plugins']);
    assert.deepEqual(nightly.jobs['build-jar'].needs, ['resolve-version', 'publish-plugins']);
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
    const releaseJava = release.jobs['build-jar'].steps
        .find((step) => step.uses === './.github/actions/build-release-java');
    const nightlyJava = nightly.jobs['build-jar'].steps
        .find((step) => step.uses === './.github/actions/build-release-java');
    assert.equal(releaseJava.with.release_version, '${{ needs.validate-release-tag.outputs.version }}');
    assert.equal(releaseJava.with.distribution_version, '${{ github.ref_name }}');
    assert.equal(releaseJava.with.plugin_manifest_commit, undefined);
    assert.equal(nightlyJava.with.release_version, '${{ needs.resolve-version.outputs.version }}');
    assert.equal(nightlyJava.with.distribution_version, '${{ needs.resolve-version.outputs.version }}');
    assert.equal(nightly.jobs['publish-plugin-artifacts'].steps
        .find(step => step.uses === './.github/actions/stage-release-plugins').with.plugin_manifest_commit,
        '${{ steps.publish.outputs.manifest_commit }}');
    assert.match(pluginInputs.runs.steps
        .find((step) => step.name === 'Stage official plugin inputs from signed catalog').run,
        /PLUGIN_MANIFEST_COMMIT\/nightly-manifest\.json/);
    for (const doc of [release, nightly]) {
        assert.ok(doc.jobs['build-windows-installer'].steps
            .some((step) => step.uses === './.github/actions/package-windows-installer'));
        const sign = doc.jobs[doc === release ? 'release' : 'release-nightly'].steps
            .find((step) => step.uses === './.github/actions/sign-update-manifest');
        assert.equal(sign.with.trusted_base_sha, '${{ needs.publish-plugins.outputs.trusted_base_sha }}');
        assert.equal(sign.with.update_signing_private_key_pem_base64,
            '${{ secrets.UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64 }}');
    }
});

test('SDK 发布链只在身份变化或显式恢复时通过同 SHA 门禁写入公共仓库', () => {
    const sdk = load('.github/workflows/publish-sdk.yml');
    const policy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts', 'ci',
        'release-gate-policy.json'), 'utf8'));
    assert.equal(sdk.name, 'Publish plugin SDK');
    assert.deepEqual(triggers(sdk), ['push', 'workflow_dispatch']);
    assert.deepEqual(sdk.permissions, { contents: 'read' });
    assert.deepEqual(sdk.on.push.branches, ['master']);
    assert.deepEqual(sdk.on.workflow_dispatch.inputs.mode.options, ['publish', 'recover-release']);
    assert.equal(sdk.jobs['quality-gate'].uses, './.github/workflows/quality-gate.yml');
    assert.equal(sdk.jobs['quality-gate'].with.trusted_base_sha,
        '${{ needs.release-plan.outputs.trusted_base_sha }}');
    assert.deepEqual(sdk.jobs.publish.needs, ['release-plan', 'quality-gate']);
    assert.equal(sdk.jobs.publish.environment, 'release');
    assert.equal(sdk.jobs.publish.steps.find((step) => step.name === 'Checkout release source').with.ref,
        '${{ github.sha }}');
    assert.deepEqual(secretNames(sdk.jobs.publish).sort(), [
        'CENTRAL_PASSWORD', 'CENTRAL_USERNAME', 'CROSS_REPO_RELEASE_TOKEN',
        'MAVEN_GPG_PASSPHRASE', 'MAVEN_GPG_PRIVATE_KEY',
    ]);
    const serialized = JSON.stringify(sdk.jobs.publish);
    assert.doesNotMatch(serialized, /continue-on-error|always\(\)|failure\(\)|cancelled\(\)/u);
    const state = sdk.jobs.publish.steps.find((step) => step.name === 'Check immutable publication state');
    const central = sdk.jobs.publish.steps.find((step) => step.name === 'Publish SDK artifacts to Maven Central');
    const remote = sdk.jobs.publish.steps.find((step) => step.name === 'Verify public SDK Release and clean consumer');
    assert.match(state.run, /central_count.*tag_exists.*reuse_release/su);
    const freeze = sdk.jobs.publish.steps.findIndex(step => step.name === 'Freeze signed SDK assets before Central publication');
    assert.ok(freeze > -1 && freeze < sdk.jobs.publish.steps.indexOf(central));
    assert.match(sdk.jobs.publish.steps[freeze].run, /--draft/u);
    assert.doesNotMatch(serialized, /--clobber/u);
    const restore = sdk.jobs.publish.steps.find(step => step.name === 'Restore original frozen SDK Release');
    assert.match(restore.run, /gpg --batch --verify/u);
    assert.match(restore.run, /--verify-directory/u);
    assert.equal(central.if, "${{ steps.state.outputs.publish_central == 'true' }}");
    assert.match(remote.run, /gh release download/u);
    assert.match(remote.run, /sdk-consumer\.mjs/u);
    assert.doesNotMatch(serialized, /PixivDownloader-Plugin-SDK-Javadocs/u);
    assert.deepEqual(policy.workflows['.github/workflows/publish-sdk.yml'], {
        workflowName: 'Publish plugin SDK',
        requiredJobs: ['release-plan', 'quality-gate', 'publish'],
        requiredTriggers: ['push', 'workflow_dispatch'],
    });
});

test('发行构建与插件发布并行，分发产物依赖完整且全部 E2E 成功后才能发布', () => {
    for (const file of ['release', 'nightly']) {
        const { jobs } = load(`.github/workflows/${file}.yml`);
        const ancestors = (id, visited = new Set()) => {
            assert.ok(jobs[id], `dependency ${id} exists`);
            if (visited.has(id)) return visited;
            visited.add(id);
            for (const parent of [jobs[id].needs || []].flat()) ancestors(parent, visited);
            return visited;
        };
        for (const [a, b] of [['build-jar', 'publish-plugin-artifacts'], ['package-java', 'build-windows-installer']]) {
            assert.equal(ancestors(a).has(b), false);
            assert.equal(ancestors(b).has(a), false);
        }
        const sharedArtifacts = ['app-shell-jar', 'plugin-inputs', 'release-signature-tool',
            'java-distributions', 'windows-installer'];
        for (const artifact of sharedArtifacts) {
            const producers = Object.keys(jobs).filter(id => executionSteps(jobs[id]).some(step =>
                step.uses?.startsWith('actions/upload-artifact@') && step.with?.name === artifact));
            assert.equal(producers.length, 1, `one producer of ${artifact}`);
            for (const id of Object.keys(jobs)) {
                if (executionSteps(jobs[id]).some(step => step.uses?.startsWith('actions/download-artifact@')
                    && step.with?.name === artifact)) {
                    assert.ok(ancestors(id).has(producers[0]), `${id} waits for ${artifact}`);
                }
            }
        }
        const e2e = jobs['release-artifact-e2e'];
        assert.deepEqual(e2e.strategy.matrix.include.map(({distribution, scenario_group}) =>
            `${distribution}/${scenario_group}`).sort(), [
            'full-offline/all', 'java-standard/all', 'windows-installer/failures',
            'windows-installer/recovery', 'windows-installer/startup'
        ]);
        assert.equal(e2e.strategy['fail-fast'], false);
        const acceptance = e2e.steps.find(step => step.uses === './.github/actions/test-release-artifacts');
        assert.equal(acceptance.with.distribution, '${{ matrix.distribution }}');
        assert.equal(acceptance.with.scenario_group, '${{ matrix.scenario_group }}');
        const publication = file === 'nightly' ? 'release-nightly' : 'release';
        const required = ancestors(publication);
        for (const id of ['publish-plugins', 'publish-plugin-artifacts', 'build-jar', 'package-java',
            'build-windows-installer', 'release-artifact-e2e']) {
            assert.ok(required.has(id));
            assert.ok(jobs[id]['continue-on-error'] === undefined || jobs[id]['continue-on-error'] === false);
            assert.doesNotMatch(jobs[id].if || '', /always\(|failure\(|cancelled\(/u);
        }
        assert.ok(ancestors('build-jar').has('publish-plugins'));
    }
    const evidence = load('.github/actions/test-release-artifacts/action.yml').runs.steps
        .find(step => step.uses?.startsWith('actions/upload-artifact@'));
    assert.equal(evidence.if, 'always()');
    assert.equal(evidence.with.name, 'release-e2e-evidence-${{ inputs.distribution }}-${{ inputs.scenario_group }}');
    assert.equal(evidence.with['if-no-files-found'], 'error');
});

test('发行候选只来自同次完整 QG，生产应用继续重建并执行完整发行边界', () => {
    const qg = load('.github/workflows/quality-gate.yml');
    const steps = qg.jobs['release-artifacts'].steps;
    const upload = steps.find(step => step.with?.name === 'release-candidates-${{ github.sha }}');
    assert.equal(upload.if, 'inputs.export_release_candidates == true');
    assert.equal(upload.with['if-no-files-found'], 'error');
    assert.equal(upload.with.overwrite, true);
    assert.ok(steps.indexOf(upload) > steps.findIndex(step => step.uses === './.github/actions/verify-release-boundaries'));
    assert.equal(load('.github/workflows/publish-plugins.yml').jobs['quality-gate'].with.export_release_candidates, true);
    const restore = load('.github/actions/restore-release-candidates/action.yml').runs.steps;
    const download = restore.find(step => step.uses?.startsWith('actions/download-artifact@'));
    assert.equal(download.with.name, 'release-candidates-${{ github.sha }}');
    assert.equal(download.with['run-id'], undefined);
    assert.equal(download.with['github-token'], undefined);
    assert.match(restore.at(-1).run, /release-build-candidates\.ps1 -Mode Import/u);
    const app = load('.github/actions/build-release-java/action.yml').runs.steps;
    assert.ok(app.findIndex(step => step.uses === './.github/actions/restore-release-candidates') <
        app.findIndex(step => step.name === 'Build JAR'));
    assert.match(app.find(step => step.name === 'Build JAR').run, /-pl pixivdownload-app -am (?:verify|install)/u);
    const boundary = app.find(step => step.uses === './.github/actions/verify-release-boundaries');
    assert.equal(boundary.with.require_production_credential_key, 'true');
});

test('SDK 接收仓库 workflow 由主仓库只读编排且不持有跨仓库凭据', () => {
    const ci = load('.github/workflows/sdk-repository-ci.yml');
    const pages = load('.github/workflows/sdk-pages.yml');
    const policy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts', 'ci',
        'release-gate-policy.json'), 'utf8'));

    assert.equal(ci.name, 'SDK repository CI');
    assert.deepEqual(triggers(ci), ['workflow_call']);
    assert.deepEqual(ci.permissions, { contents: 'read' });
    assert.deepEqual(Object.keys(ci.jobs), ['verify']);
    assert.equal(ci.jobs.verify.steps.find((step) => step.name === 'Checkout SDK repository candidate')
        .with.ref, '${{ github.sha }}');
    assert.equal(ci.jobs.verify.steps.find((step) => step.name === 'Verify SDK repository source').run,
        'npm test');

    assert.equal(pages.name, 'Build SDK Pages');
    assert.deepEqual(triggers(pages), ['workflow_call']);
    assert.deepEqual(pages.permissions, { contents: 'read' });
    assert.deepEqual(Object.keys(pages.jobs), ['build']);
    const download = pages.jobs.build.steps
        .find((step) => step.name === 'Download every immutable SDK Release');
    const upload = pages.jobs.build.steps.find((step) => step.name === 'Upload Pages artifact');
    assert.equal(download.env, undefined);
    assert.match(download.run, /https:\/\/api\.github\.com\/repos\/\$GITHUB_REPOSITORY\/releases/u);
    assert.match(download.run, /https:\/\/github\.com\/\$GITHUB_REPOSITORY\/releases\/download/u);
    assert.match(download.run, /\.artifacts\[\]\.file/u);
    assert.equal(upload.uses, 'actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9');
    assert.doesNotMatch(JSON.stringify([ci, pages]),
        /secrets\.|github\.token|CROSS_REPO_RELEASE_TOKEN|"pages":"write"|"id-token":"write"/u);
    assert.doesNotMatch(JSON.stringify(pages), /actions\/deploy-pages/u);

    assert.deepEqual(policy.workflows['.github/workflows/sdk-repository-ci.yml'], {
        workflowName: 'SDK repository CI',
        requiredJobs: ['verify'],
        requiredTriggers: ['workflow_call'],
    });
    assert.deepEqual(policy.workflows['.github/workflows/sdk-pages.yml'], {
        workflowName: 'Build SDK Pages',
        requiredJobs: ['build'],
        requiredTriggers: ['workflow_call'],
    });
});

test('FFmpeg：手动流程从官方稳定源码构建并在门禁后发布五个平台资产', () => {
    const ffmpeg = load('.github/workflows/build-stable-ffmpeg.yml');
    const policy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts', 'ci', 'release-gate-policy.json'), 'utf8'));
    assert.match(execFileSync('git', ['ls-files', '--stage', '--', 'mvnw'], {
        cwd: ROOT,
        encoding: 'utf8',
    }), /^100755 /);
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
    assert.equal(signManifest.env.FFMPEG_SIGNING_PRIVATE_KEY_PEM_BASE64,
        '${{ secrets.FFMPEG_SIGNING_PRIVATE_KEY_PEM_BASE64 }}');
    assert.match(signManifest.run, /manifest --manifest assets\/ffmpeg-release\.json --repository-id ffmpeg-stable/);
    assert.match(signManifest.run, /pixivdownloader-ffmpeg-root-2026-08/);
    assert.doesNotMatch(JSON.stringify(signManifest), /PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64/);
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
    const actionDir = path.join(ROOT, '.github', 'actions');
    for (const name of fs.readdirSync(actionDir)) {
        const action = load(`.github/actions/${name}/action.yml`);
        for (const step of action.runs.steps) {
            assert.doesNotMatch(step.run || '', /\$\{\{/, `${name} action/${step.name}`);
        }
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
        '.github/actions/publish-official-plugins/action.yml',
        '.github/actions/package-release-java/action.yml',
        '.github/actions/build-release-java/action.yml',
        '.github/actions/stage-release-plugins/action.yml',
        '.github/actions/package-windows-installer/action.yml',
        '.github/actions/sign-update-manifest/action.yml']) {
        const text = fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8');
        assert.doesNotMatch(text, /always\(\)|!cancelled\(\)|continue-on-error/);
        assert.doesNotMatch(text, /PLUGIN_SIGNING_PRIVATE_KEY_PEM(?:\s|:|\})/);
        assert.doesNotMatch(text, /PLUGINS_REPO_TOKEN|plugins_repo_token/);
    }
    for (const rel of ['.github/workflows/release.yml', '.github/workflows/nightly.yml']) {
        const text = fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8');
        assert.match(text, /UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64/);
    }
    const ffmpeg = fs.readFileSync(path.join(ROOT, '.github', 'workflows', 'build-stable-ffmpeg.yml'), 'utf8');
    assert.match(ffmpeg, /FFMPEG_SIGNING_PRIVATE_KEY_PEM_BASE64/);
    assert.doesNotMatch(ffmpeg, /PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64/);
    assert.match(fs.readFileSync(path.join(ROOT, '.github', 'actions', 'sign-update-manifest', 'action.yml'),
        'utf8'), /pixivdownloader-update-root-2026-08/);
});

test('Nightly：共享变更门禁以语义输出控制全部昂贵任务', () => {
    const nightly = load('.github/workflows/nightly.yml');
    for (const id of ['publish-plugins', 'publish-plugin-artifacts', 'build-jar', 'package-java', 'build-windows-installer',
        'release-artifact-e2e', 'release-nightly']) {
        assert.equal(nightly.jobs[id].if, "needs.resolve-version.outputs.has_changes == 'true'");
    }
    const resolveScripts = nightly.jobs['resolve-version'].steps.map((step) => step.run || '').join('\n');
    assert.match(resolveScripts, /nightly-changelog-gate\.sh\s+CHANGELOG\.md\s+nightly/);
});
