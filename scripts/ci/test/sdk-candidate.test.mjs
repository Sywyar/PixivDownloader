import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

test('源码候选归档执行真实工作流脚本，仅首次写入并拒绝包变化及非默认分支', async t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-candidate-archive-'));
    t.after(() => fs.rmSync(root, { recursive: true }));
    const workflow = YAML.parse(fs.readFileSync(fileURLToPath(new URL('../../../plugin-templates/sdk-package/.github/workflows/candidate.yml', import.meta.url)), 'utf8'));
    const script = workflow.jobs.archive.steps.find(step => step.with?.script).with.script;
    const execute = new (Object.getPrototypeOf(async function () {}).constructor)('require', 'github', 'context', 'core', script);
    const require = createRequire(import.meta.url);
    const localRequire = name => name === 'node:fs' ? Object.fromEntries(['lstatSync', 'readdirSync', 'readFileSync'].map(method =>
        [method, (file, ...args) => fs[method](path.join(root, file), ...args)])) : require(name);
    const bytes = Buffer.from('frozen package bytes');
    const hash = data => crypto.createHash('sha256').update(data).digest('hex');
    const directory = path.join(root, 'candidates/source-candidate-1-project'); fs.mkdirSync(directory, { recursive: true });
    const candidate = { schemaVersion: 1, repositoryId: '1234', repository: 'owner/source', sourceCommit: 'a'.repeat(40), runId: '5678', runAttempt: 1,
        pluginId: 'example', version: '7.8.9-rc.12', buildProfile: { id: 'maven-java17-v1', projectDir: '.', artifactPath: 'target/plugin.jar' },
        artifact: { file: 'pixivdownload-plugin-example-7.8.9-rc.12.jar', size: bytes.length, sha256: hash(bytes) } };
    fs.writeFileSync(path.join(directory, 'source-candidate.json'), JSON.stringify(candidate));
    fs.writeFileSync(path.join(directory, candidate.artifact.file), bytes);
    const repository = { id: 1234, full_name: 'owner/source', default_branch: 'main', owner: { login: 'owner' } };
    const run = { repository, head_sha: candidate.sourceCommit, head_branch: 'main', event: 'push' };
    const context = { repo: { owner: 'owner', repo: 'source' }, payload: { repository }, sha: candidate.sourceCommit, runId: 5678 };
    const releases = []; const assets = []; const mutations = [];
    let tagCommit = candidate.sourceCommit;
    const api = {
        getCommit: async () => ({ data: { sha: tagCommit } }),
        get: async () => ({ data: repository }), listReleases: () => {}, listReleaseAssets: () => {},
        createRelease: async args => { mutations.push(args); const release = { ...args, id: 9 }; releases.push(release); return { data: release }; },
        uploadReleaseAsset: async args => { mutations.push(args); const asset = { id: assets.length + 1, name: args.name, size: args.data.length,
            digest: 'sha256:' + hash(args.data), bytes: Buffer.from(args.data) }; assets.push(asset); return { data: asset }; },
    };
    const github = { rest: { repos: api, actions: { getWorkflowRun: async () => ({ data: run }) } },
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
    await archive(); assert.equal(mutations.length, 3);
    assert.equal(JSON.parse(assets.find(asset => asset.name === 'source-candidate.json').bytes).runAttempt, 1);
    tagCommit = 'b'.repeat(40); await assert.rejects(archive(), /CANDIDATE_TAG_CHANGED/u);
    tagCommit = candidate.sourceCommit;
    fs.writeFileSync(path.join(directory, candidate.artifact.file), 'changed');
    await assert.rejects(archive(), /CANDIDATE_BYTES_CHANGED/u); assert.equal(mutations.length, 3);
    fs.writeFileSync(path.join(directory, candidate.artifact.file), bytes);
    run.head_branch = 'feature'; await assert.rejects(archive(), /CANDIDATE_SOURCE_MISMATCH/u);
    run.head_branch = 'main'; run.event = 'pull_request'; await assert.rejects(archive(), /CANDIDATE_SOURCE_MISMATCH/u);
    assert.equal(mutations.length, 3);
});
