import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import YAML from 'yaml';

test('源码候选容器在 checkout 前安装 Git，构建前提供归档工具', t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-candidate-prerequisites-'));
    t.after(() => fs.rmSync(root, { recursive: true }));
    const workflow = YAML.parse(fs.readFileSync(fileURLToPath(new URL('../../../plugin-templates/sdk-package/.github/workflows/candidate.yml', import.meta.url)), 'utf8'));
    for (const name of ['projects', 'build']) {
        const job = workflow.jobs[name];
        const checkout = job.steps.findIndex(step => step.uses?.startsWith('actions/checkout@'));
        assert(checkout > 0);
        const script = job.steps.slice(0, checkout).filter(step => step.run).map(step => step.run).join('\n');
        // 运行实际安装命令，只替换包管理器，避免在测试机安装软件。
        const wrapper = 'set -eu\napt-get() { printf "%s\\n" "$*"; }\n' + script;
        const file = path.join(root, name + '.sh'); fs.writeFileSync(file, wrapper, 'utf8');
        const output = execFileSync('bash', [file], { encoding: 'utf8', windowsHide: true });
        const commands = output.trim().split(/\r?\n/u).map(line => line.split(/\s+/u));
        const update = commands.findIndex(args => args[0] === 'update');
        const install = commands.findIndex(args => args[0] === 'install');
        assert(update >= 0 && install > update);
        assert(commands[install].includes('git'));
        if (name === 'build') assert(commands[install].includes('unzip'));
    }
});

test('源码候选容器的后续 Git 进程只信任本次检出的工程', t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-candidate-git-'));
    t.after(() => fs.rmSync(root, { recursive: true }));
    const workflow = YAML.parse(fs.readFileSync(fileURLToPath(new URL('../../../plugin-templates/sdk-package/.github/workflows/candidate.yml', import.meta.url)), 'utf8'));
    for (const name of ['projects', 'build']) {
        const workspace = path.join(root, name + ' workspace');
        const other = path.join(root, name + '-other');
        const env = { ...process.env, HOME: root, GIT_CONFIG_GLOBAL: path.join(root, name + '.gitconfig'),
            GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_COUNT: '0', GIT_TEST_ASSUME_DIFFERENT_OWNER: '1',
            GITHUB_WORKSPACE: workspace.replaceAll('\\', '/') };
        for (const directory of [workspace, other]) execFileSync('git', ['init', directory], { env, stdio: 'pipe' });
        const inspect = directory => spawnSync('git', ['-C', directory, 'ls-files'], { env, encoding: 'utf8', windowsHide: true });
        assert.match(inspect(workspace).stderr, /dubious ownership/u);
        const steps = workflow.jobs[name].steps;
        const checkout = steps.findIndex(step => step.uses?.startsWith('actions/checkout@'));
        const candidate = steps.findIndex(step => step.run?.includes('node tools/candidate.mjs'));
        const script = steps.slice(checkout + 1, candidate).filter(step => step.run).map(step => step.run).join('\n');
        const file = path.join(root, name + '.sh');
        fs.writeFileSync(file, 'set -eu\n' + script, 'utf8');
        execFileSync('bash', [file], { env, stdio: 'pipe', windowsHide: true });
        assert.equal(inspect(workspace).status, 0);
        assert.match(inspect(other).stderr, /dubious ownership/u);
    }
});

for (const flat of [false, true]) test(`源码候选${flat ? '直接展开' : '分目录'}复用草稿，拒绝过期构建并恢复中断覆盖`, async t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-candidate-archive-'));
    t.after(() => fs.rmSync(root, { recursive: true }));
    const workflow = YAML.parse(fs.readFileSync(fileURLToPath(new URL('../../../plugin-templates/sdk-package/.github/workflows/candidate.yml', import.meta.url)), 'utf8'));
    const script = workflow.jobs.archive.steps.find(step => step.with?.script).with.script;
    const execute = new (Object.getPrototypeOf(async function () {}).constructor)('require', 'github', 'context', 'core', script);
    const require = createRequire(import.meta.url);
    const localRequire = name => name === 'node:fs' ? Object.fromEntries(['existsSync', 'lstatSync', 'readdirSync', 'readFileSync'].map(method =>
        [method, (file, ...args) => fs[method](path.join(root, file), ...args)])) : require(name);
    const bytes = Buffer.from('frozen package bytes');
    const hash = data => crypto.createHash('sha256').update(data).digest('hex');
    const directory = path.join(root, flat ? 'candidates' : 'candidates/source-candidate-1-project'); fs.mkdirSync(directory, { recursive: true });
    const candidate = { schemaVersion: 1, repositoryId: '1234', repository: 'owner/source', sourceCommit: 'a'.repeat(40), runId: '5678', runAttempt: 1,
        pluginId: 'example', version: '7.8.9-rc.12', buildProfile: { id: 'maven-java17-v1', projectDir: '.', artifactPath: 'target/plugin.jar' },
        artifact: { file: 'pixivdownload-plugin-example-7.8.9-rc.12.jar', size: bytes.length, sha256: hash(bytes) } };
    fs.writeFileSync(path.join(directory, 'source-candidate.json'), JSON.stringify(candidate));
    fs.writeFileSync(path.join(directory, candidate.artifact.file), bytes);
    const repository = { id: 1234, full_name: 'owner/source', default_branch: 'main', owner: { login: 'owner' } };
    const run = { repository, head_sha: candidate.sourceCommit, head_branch: 'main', event: 'push' };
    const context = { repo: { owner: 'owner', repo: 'source' }, payload: { repository }, sha: candidate.sourceCommit, runId: 5678 };
    const releases = []; const assets = []; const mutations = [];
    let tip = candidate.sourceCommit;
    let tagExists = false; let refError; let failUpload = false; let assetId = 0;
    const api = {
        getCommit: async args => { assert.equal(args.ref, 'main'); return { data: { sha: tip } }; },
        get: async () => ({ data: repository }), listReleases: () => {}, listReleaseAssets: () => {},
        createRelease: async args => { mutations.push(args); const release = { ...args, id: 9 }; releases.push(release); return { data: release }; },
        updateRelease: async args => { mutations.push(args); Object.assign(releases[0], args); return { data: releases[0] }; },
        deleteReleaseAsset: async args => { mutations.push(args); assets.splice(assets.findIndex(a => a.id === args.asset_id), 1); },
        uploadReleaseAsset: async args => {
            if (failUpload && args.name === 'source-candidate.json') throw new Error('UPLOAD_INTERRUPTED');
            mutations.push(args); const asset = { id: ++assetId, state: 'uploaded', name: args.name, size: args.data.length,
            digest: 'sha256:' + hash(args.data), bytes: Buffer.from(args.data) }; assets.push(asset); return { data: asset }; },
    };
    const github = { rest: { repos: api, actions: { getWorkflowRun: async () => ({ data: run }) }, git: { getRef: async args => {
        assert.equal(args.ref, `tags/candidate-${candidate.pluginId}`);
        if (refError) throw refError;
        if (!tagExists) throw Object.assign(new Error('Not Found'), { status: 404 });
        return { data: { ref: 'refs/' + args.ref } };
    } } },
        paginate: async method => method === api.listReleases ? releases : assets,
        request: async (_method, args) => ({ data: assets.find(asset => asset.id === args.asset_id).bytes }) };
    const previous = process.env.GITHUB_RUN_ATTEMPT; process.env.GITHUB_RUN_ATTEMPT = '1';
    t.after(() => { if (previous === undefined) delete process.env.GITHUB_RUN_ATTEMPT; else process.env.GITHUB_RUN_ATTEMPT = previous; });
    const archive = () => execute(localRequire, github, context, { info() {} });
    await archive(); assert.equal(mutations.length, 3);
    assert.equal(releases[0].draft, true); assert.equal(releases[0].prerelease, true); assert.equal(releases[0].make_latest, 'false');
    await archive(); assert.equal(mutations.length, 3);
    candidate.runAttempt = 2; process.env.GITHUB_RUN_ATTEMPT = '2';
    fs.writeFileSync(path.join(directory, 'source-candidate.json'), JSON.stringify(candidate));
    const marker = assets.find(asset => asset.name === 'source-candidate.json').id;
    await archive(); assert.equal(mutations[3].asset_id, marker);
    assert.equal(JSON.parse(assets.find(asset => asset.name === 'source-candidate.json').bytes).runAttempt, 2);
    assert.equal(releases.length, 1);
    refError = Object.assign(new Error('Reference query failed'), { status: 422 });
    await assert.rejects(archive(), /Reference query failed/u); refError = undefined;
    releases[0].draft = false; await assert.rejects(archive(), /CANDIDATE_RELEASE_CONFLICT/u); releases[0].draft = true;
    tagExists = true;
    await assert.rejects(archive(), /CANDIDATE_TAG_CHANGED/u); tagExists = false;
    let count = mutations.length;
    fs.writeFileSync(path.join(directory, candidate.artifact.file), 'changed');
    await assert.rejects(archive(), /CANDIDATE_BYTES_CHANGED/u); assert.equal(mutations.length, count);
    fs.writeFileSync(path.join(directory, candidate.artifact.file), bytes);
    run.head_branch = 'feature'; await assert.rejects(archive(), /CANDIDATE_SOURCE_MISMATCH/u);
    run.head_branch = 'main'; run.event = 'pull_request'; await assert.rejects(archive(), /CANDIDATE_SOURCE_MISMATCH/u);
    assert.equal(mutations.length, count);
    run.event = 'push';
    tip = 'b'.repeat(40); await archive(); assert.equal(mutations.length, count);
    context.sha = tip; run.head_sha = tip; candidate.sourceCommit = tip;
    fs.writeFileSync(path.join(directory, 'source-candidate.json'), JSON.stringify(candidate));
    failUpload = true; await assert.rejects(archive(), /UPLOAD_INTERRUPTED/u);
    assert.equal(assets.length, 1); assert.notEqual(assets[0].name, 'source-candidate.json');
    failUpload = false; await archive();
    assert.equal(releases.length, 1); assert.equal(releases[0].id, 9); assert.equal(releases[0].target_commitish, tip);
    assert.equal(assets.length, 2); assert.equal(JSON.parse(assets.find(a => a.name === 'source-candidate.json').bytes).sourceCommit, tip);
    count = mutations.length;
    context.sha = 'a'.repeat(40); run.head_sha = context.sha;
    await archive(); assert.equal(mutations.length, count); assert.equal(releases[0].target_commitish, tip);
    context.sha = tip; run.head_sha = tip;
    fs.writeFileSync(path.join(root, 'candidates/unexpected.txt'), 'unexpected');
    await assert.rejects(archive(), /CANDIDATE_(?:PATH_INVALID|BYTES_CHANGED)/u);
    assert.equal(mutations.length, count);
});
