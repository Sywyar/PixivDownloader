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

test('页面验收的冻结产物参数保留默认、发布恢复及验证码入口', () => {
    const entry = path.join(root, 'scripts/ci/page-editor-acceptance.mjs');
    const source = fs.readFileSync(entry, 'utf8').replace(/^import .*;\r?$/gm, '')
        .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(entry).href));
    for (const mode of [[], ['--publication-recovery'], ['fixture-captcha.jar']]) {
        for (const inputs of [[], ['--frozen-inputs', path.join(root, 'target/frozen-inputs')]]) {
            const copied = [], launched = [], stop = new Error('Java acceptance boundary reached');
            const plugins = ['page-editor', 'database-sqlite', 'download-workbench', 'ai', 'tts'];
            const fixtureFs = {
                existsSync: () => false, mkdirSync() {}, writeFileSync() {},
                copyFileSync: (from, to) => copied.push([from, to]),
                readFileSync: () => Buffer.from('fixture bytes'),
                readdirSync: directory => path.basename(directory) === 'plugins'
                    ? plugins.map(id => `pixivdownload-plugin-${id}-fixture.jar`)
                    : [path.basename(path.dirname(directory)) === 'pixivdownload-app'
                        ? 'PixivDownload-fixture-boot.jar' : `${path.basename(path.dirname(directory))}-fixture.jar`],
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
            assert.equal(launched.at(-1), mode[0] === '--publication-recovery' ? 'publication-recovery'
                : mode.length ? 'captcha' : path.join(root, 'pixivdownload-app/src/test/resources/page-components/browser.mjs'));
            assert.equal(copied.some(([from]) => from === path.resolve('fixture-captcha.jar')), mode[0] === 'fixture-captcha.jar');
        }
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
