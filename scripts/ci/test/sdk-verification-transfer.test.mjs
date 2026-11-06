import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import vm from 'node:vm';
import YAML from 'yaml';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const action = YAML.parse(fs.readFileSync(path.join(root, '.github/actions/restore-sdk-verification/action.yml'), 'utf8'));
const steps = action.runs.steps;

test('SDK 冻结归档向独立消费者交接签名工具，缺失时构建失败', () => {
    const workflow = YAML.parse(fs.readFileSync(path.join(root, '.github/workflows/quality-gate.yml'), 'utf8'));
    const archive = workflow.jobs['sdk-build'].steps.find(step => step.id === 'sdk-archive');
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-archive-'));
    const signature = 'pixivdownload-plugin-signature/target/pixivdownload-plugin-signature-fixture.jar';
    const files = ['target/sdk-publication-inputs/inputs.json', 'target/sdk-release/sdk-release.json',
        'build/sdk-development-runtime/ci-repository.yaml', 'target/sbt-launch.jar', signature];
    try {
        for (const file of files) {
            fs.mkdirSync(path.dirname(path.join(directory, file)), {recursive: true});
            fs.writeFileSync(path.join(directory, file), `frozen ${file}`, 'utf8');
        }
        const run = () => spawnSync('bash', ['-e', '-o', 'pipefail', '-s'], {
            cwd: directory, input: archive.run, encoding: 'utf8', env: {...process.env, GITHUB_OUTPUT: 'outputs.txt'},
        });
        const result = run();
        assert.equal(result.status, 0, result.stderr);
        const restored = path.join(directory, 'restored');
        fs.mkdirSync(restored);
        execFileSync('tar', ['-xf', path.join(directory, 'target/sdk-verification.tar'), '-C', restored]);
        for (const file of files) assert.equal(fs.readFileSync(path.join(restored, file), 'utf8'), `frozen ${file}`);
        const digest = createHash('sha256').update(fs.readFileSync(path.join(directory, 'target/sdk-verification.tar'))).digest('hex');
        assert.equal(fs.readFileSync(path.join(directory, 'outputs.txt'), 'utf8').trim(), `sha256=${digest}`);
        fs.unlinkSync(path.join(directory, signature));
        assert.notEqual(run().status, 0, 'Missing signature tool must reject the producer');
    } finally {
        fs.rmSync(directory, {recursive: true, force: true});
    }
});

test('页面验收的冻结产物参数保留完整、分组、发布恢复及验证码入口', () => {
    const entry = path.join(root, 'scripts/ci/page-editor-acceptance.mjs');
    const source = fs.readFileSync(entry, 'utf8').replace(/^import .*;\r?$/gm, '')
        .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(entry).href));
    for (const mode of [[], ['--publication-recovery'], ['fixture-captcha.jar'],
        ...['all', 'operations', 'components', 'custom'].map(group => ['--group', group])]) {
        for (const inputs of [[], ['--frozen-inputs', path.join(root, 'target/frozen-inputs')]]) {
            const copied = [], launched = [], stop = new Error('Java acceptance boundary reached');
            const plugins = ['page-editor', 'database-sqlite', 'download-workbench', 'ai', 'tts', 'novel-translation-contract', 'novel', 'translation'];
            const fixtureFs = {
                existsSync: () => false, mkdirSync() {}, writeFileSync() {},
                copyFileSync: (from, to) => copied.push([from, to]),
                readFileSync: () => Buffer.from('fixture bytes'),
                readdirSync: directory => path.basename(directory) === 'plugins'
                    ? plugins.map(id => `pixivdownload-plugin-${id}-0.0.0-fixture.jar`)
                    : [path.basename(path.dirname(directory)) === 'pixivdownload-app'
                        ? 'PixivDownload-fixture-boot.jar' : `${path.basename(path.dirname(directory))}-0.0.0-fixture.jar`],
            };
            assert.throws(() => vm.runInNewContext(source, {
                path, fs: fixtureFs, fileURLToPath, parseArgs, createHash, assert,
                process: {...process, argv: [process.execPath, entry, path.join(root, 'target/page-fixture'), ...mode, ...inputs]},
                createRequire: () => ({resolve: () => 'typescript/bin/tsc'}),
                spawnSync(command, args) {
                    if (command === 'java') { launched.push(...args); throw stop; }
                    return {status: 0};
                },
            }), error => error === stop);
            assert.equal(copied[0][0], inputs.length ? path.join(inputs[1], 'host.jar')
                : path.join(root, 'pixivdownload-app/target/PixivDownload-fixture-boot.jar'));
            assert.equal(copied.find(([, to]) => to.endsWith(path.sep + 'novel.jar'))[0], inputs.length
                ? path.join(inputs[1], 'plugins/pixivdownload-plugin-novel-0.0.0-fixture.jar')
                : path.join(root, 'pixivdownload-plugin-novel/target/pixivdownload-plugin-novel-0.0.0-fixture.jar'));
            assert.equal(launched.at(-1), mode[0] === '--publication-recovery' ? 'publication-recovery'
                : mode[0] === '--group' ? mode[1] : mode.length ? 'captcha'
                    : path.join(root, 'pixivdownload-app/src/test/resources/page-components/browser.mjs'));
            assert.equal(copied.some(([from]) => from === path.resolve('fixture-captcha.jar')), mode[0] === 'fixture-captcha.jar');
        }
    }
});

test('页面验收拒绝未知分组与冲突模式且不创建工作目录', () => {
    const entry = path.join(root, 'scripts/ci/page-editor-acceptance.mjs');
    const directory = path.join(root, 'target/page-invalid-group-' + process.pid);
    for (const args of [['--group', 'missing'], ['--group', ''], ['--group', 'custom', '--publication-recovery'],
        ['--group', 'operations', 'fixture-captcha.jar']]) {
        const result = spawnSync(process.execPath, [entry, directory, ...args], {encoding: 'utf8'});
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /Unknown page acceptance group|cannot be combined/);
        assert.equal(fs.existsSync(directory), false);
    }
});

test('页面恢复比较接受无序端点集合，仍拒绝成员、字段及有序列表变化', () => {
    const entry = path.join(root, 'scripts/ci/page-editor-acceptance.mjs');
    const source = fs.readFileSync(entry, 'utf8').replace(/^import .*;\r?$/gm, '')
        .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(entry).href));
    const anchor = {owner: 'fixture', handler: 'Controller#first', routes: ['POST:/a', 'GET:/b'],
        paths: ['/a', '/b'], methods: ['POST', 'GET'], alternatives: ['b', 'a'],
        parameters: ['first', 'second'], headers: [], consumes: [], produces: [], bodyRequired: true};
    const hook = {definition: {phases: ['BEFORE_INVOKE', 'AFTER_COMPLETION']}, targetContract: 'contract',
        targetAnchors: [anchor, {...anchor, handler: 'Controller#second'}]};
    const before = {epoch: 'old', pages: {fixture: {document: 'document', hooks: [hook]}},
        configuration: {requirements: [hook]}};
    for (const change of [null, value => value.targetAnchors[0].routes.pop(),
        value => { value.targetAnchors[0].bodyRequired = false; },
        value => value.targetAnchors[0].parameters.reverse(),
        value => { value.targetContract = 'changed'; }]) {
        const after = JSON.parse(JSON.stringify(before)); after.epoch = 'new';
        for (const value of [after.pages.fixture.hooks[0], after.configuration.requirements[0]]) {
            value.definition.phases.reverse(); value.targetAnchors.reverse();
            for (const target of value.targetAnchors)
                for (const field of ['routes', 'paths', 'methods', 'alternatives']) target[field].reverse();
        }
        if (change) change(after.configuration.requirements[0]);
        let current = JSON.stringify({file: 'before.json', digest: 'valid'}), invocations = 0, recovery = false;
        const stop = new Error('External acceptance boundary reached');
        const context = vm.createContext({path, fileURLToPath, parseArgs, createHash, assert,
            process: {...process, argv: [process.execPath, entry, path.join(root, 'target/page-recovery-fixture')]},
            console: {log() {}}, fs: {existsSync: () => false, mkdirSync() {}, copyFileSync() {}, appendFileSync() {},
                readdirSync: () => { if (!recovery) throw stop; return ['before.json']; },
                writeFileSync(file, value) { if (file.endsWith('current.json')) current = value; },
                readFileSync(file) {
                    if (file.endsWith('current.json')) return current;
                    if (file.endsWith('before.json')) return JSON.stringify(before);
                    if (file.endsWith('after.json')) return JSON.stringify(after);
                    throw new Error('Unexpected fixture read: ' + file);
                }},
            spawnSync(command, args) {
                if (command !== 'java') throw stop;
                invocations++;
                if (args.includes('--publication-sha256=' + '0'.repeat(64))) return {status: 1, stdout: '', stderr: ''};
                if (args.includes('--publication-sha256=valid')) current = JSON.stringify({file: 'after.json'});
                return {status: 0, stdout: current.includes('corrupt') ? 'unavailable' : 'Current publication:', stderr: ''};
            }});
        assert.throws(() => vm.runInContext(source, context), error => error === stop);
        recovery = true;
        const recover = () => vm.runInContext('verifyRecoveryCli()', context);
        if (change) assert.throws(recover, /recovery preserves standalone Hook bindings/);
        else { recover(); assert.equal(invocations, 5); }
    }
});

test('SDK 输入拒绝缺失身份和损坏归档，通过摘要后才解包并执行源码校验', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-verification-'));
    const source = path.join(directory, 'source');
    const workspace = path.join(directory, 'workspace');
    const temporary = path.join(directory, 'temporary');
    const archive = path.join(temporary, 'sdk-verification/sdk-verification.tar');
    for (const folder of [source, workspace, path.dirname(archive)]) fs.mkdirSync(folder, { recursive: true });
    fs.writeFileSync(path.join(source, 'input.txt'), 'frozen SDK bytes', 'utf8');
    execFileSync('tar', ['-cf', archive, '-C', source, 'input.txt']);
    const digest = createHash('sha256').update(fs.readFileSync(archive)).digest('hex');
    const bashPath = file => file.replaceAll('\\', '/').replace(/^([A-Za-z]):/u, (_, drive) => `/${drive.toLowerCase()}`);
    const env = {
        ...process.env, ARTIFACT_ID: '42', INPUT_SHA256: digest, SOURCE_SHA: 'a'.repeat(40),
        GITHUB_WORKSPACE: bashPath(workspace), RUNNER_TEMP: bashPath(temporary),
    };
    const run = (script, overrides = {}) => spawnSync('bash', ['-e', '-o', 'pipefail', '-s'], {
        cwd: workspace, env: { ...env, ...overrides }, input: script, encoding: 'utf8',
    });
    try {
        assert.equal(run(steps[0].run).status, 0);
        for (const invalid of [{ ARTIFACT_ID: '' }, { ARTIFACT_ID: '0' }, { ARTIFACT_ID: '42 43' },
            { INPUT_SHA256: '' }, { INPUT_SHA256: digest.slice(1) }]) {
            assert.notEqual(run(steps[0].run, invalid).status, 0);
        }
        const download = steps.find(step => step.uses?.startsWith('actions/download-artifact@'));
        assert.equal(download.with['artifact-ids'], '${{ inputs.artifact_id }}');
        assert.equal(download.with['run-id'], undefined);
        // 这里验证传输顺序；实际 manifest/source/逐文件摘要由 sdk-publication-inputs.test.mjs 覆盖。
        const script = 'node() { printf "%s\\n" "$*" >> validators.log; }\n' + steps.at(-1).run;
        const rejected = run(script, { INPUT_SHA256: '0'.repeat(64) });
        assert.notEqual(rejected.status, 0);
        assert.equal(fs.existsSync(path.join(workspace, 'input.txt')), false);
        assert.equal(fs.existsSync(path.join(workspace, 'validators.log')), false);
        const accepted = run(script);
        assert.equal(accepted.status, 0, accepted.stderr);
        assert.equal(fs.readFileSync(path.join(workspace, 'input.txt'), 'utf8'), 'frozen SDK bytes');
        const calls = fs.readFileSync(path.join(workspace, 'validators.log'), 'utf8');
        assert.match(calls, new RegExp(`sdk-publication-inputs.mjs restore ${env.SOURCE_SHA}`));
        assert.match(calls, new RegExp(`sdk-release.mjs .*--verify-directory target/sdk-release --source-sha ${env.SOURCE_SHA}`));
        const invalidManifest = run('node() { return 1; }\n' + steps.at(-1).run);
        assert.notEqual(invalidManifest.status, 0);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});
