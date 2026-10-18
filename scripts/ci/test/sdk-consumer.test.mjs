import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { inspectSdkVersion } from '../sdk-version.mjs';

import { assertSdkResolution, parsePluginIdentity, prepareDouyinSource, stageSdkArtifacts, verifyConsumer } from '../sdk-consumer.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const VERSION = inspectSdkVersion(ROOT).version;
const PLUGIN_VERSION = '9.8.7-test.5';
const GROUP_PATH = path.join('io', 'github', 'sywyar', 'pixivdownloader');
const ARTIFACTS = [
    ['pixivdownload-sdk-bom', ['pom']],
    ['pixivdownload-sdk-info', ['pom', 'jar']],
    ['pixivdownload-plugin-api', ['pom', 'jar']],
    ['pixivdownload-core-api', ['pom', 'jar']],
    ['pixivdownload-sdk', ['pom', 'jar']],
];

function writeRepository(root) {
    for (const [artifact, extensions] of ARTIFACTS) {
        const directory = path.join(root, GROUP_PATH, artifact, VERSION);
        fs.mkdirSync(directory, { recursive: true });
        for (const extension of extensions) {
            fs.writeFileSync(path.join(directory, `${artifact}-${VERSION}.${extension}`),
                    `${artifact}:${extension}:public\n`, 'utf8');
        }
    }
}

test('第三方验收签名身份从插件描述符读取', () => {
    assert.deepEqual(parsePluginIdentity(`
        # fixture
        plugin.id=douyin
        plugin.version=${PLUGIN_VERSION}
    `), { id: 'douyin', version: PLUGIN_VERSION });
    assert.throws(() => parsePluginIdentity('plugin.id=douyin\n'), /must declare/u);
    assert.throws(() => parsePluginIdentity(
            `plugin.id=douyin\nplugin.id=other\nplugin.version=${PLUGIN_VERSION}\n`), /more than once/u);
});

test('独立插件源码输入只复制构建与测试文件，缺失输入或移动 ref 拒绝执行', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-douyin-source-'));
    const source = path.join(root, 'source');
    const work = path.join(root, 'work');
    const descriptor = path.join(source, 'src/main/resources/plugin.properties');
    try {
        fs.mkdirSync(path.dirname(descriptor), { recursive: true });
        fs.mkdirSync(work);
        fs.writeFileSync(descriptor, `plugin.id=douyin\nplugin.version=${PLUGIN_VERSION}\n`, 'utf8');
        fs.writeFileSync(path.join(source, 'pom.xml'), '<project/>', 'utf8');
        fs.writeFileSync(path.join(source, 'local-only.txt'), 'not a build input', 'utf8');
        const staged = prepareDouyinSource(ROOT, work, source);
        assert.equal(fs.readFileSync(path.join(staged, 'src/main/resources/plugin.properties'), 'utf8'),
            fs.readFileSync(descriptor, 'utf8'));
        assert.equal(fs.existsSync(path.join(staged, 'local-only.txt')), false);
        fs.mkdirSync(path.join(root, 'missing'));
        assert.throws(() => prepareDouyinSource(ROOT, path.join(root, 'missing'), path.join(root, 'absent')));
        fs.mkdirSync(path.join(root, 'scripts/ci'), { recursive: true });
        fs.writeFileSync(path.join(root, 'scripts/ci/douyin-source.json'), '{"revision":"main"}', 'utf8');
        assert.throws(() => prepareDouyinSource(root, work), /exact commit/u);
        const consumer = path.join(ROOT, 'target/source-boundary-check');
        assert.throws(() => verifyConsumer({ repoRoot: ROOT, sdkZip: '', sdkRepository: '',
            workDirectory: consumer, douyinSource: path.join(consumer, 'source') }), /outside/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('固定源码检出保留长路径测试文件并拒绝不完整的工作树', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-douyin-checkout-'));
    const source = path.join(root, 'source');
    const config = path.join(root, 'gitconfig');
    const env = { ...process.env, GIT_CONFIG_GLOBAL: config, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_COUNT: '0' };
    const git = args => execFileSync('git', ['-C', source, ...args], { env, encoding: 'utf8' });
    const relative = `src/test/java/${'package/'.repeat(12)}LongPathTest.java`;
    const completeDirectory = `complete-${'nested-'.repeat(10)}`;
    const content = 'class LongPathTest {}\n';
    const checkout = name => spawnSync(process.execPath, ['--input-type=module', '-e', `
        import { prepareDouyinSource } from ${JSON.stringify(new URL('../sdk-consumer.mjs', import.meta.url).href)};
        prepareDouyinSource(${JSON.stringify(root)}, ${JSON.stringify(path.join(root, name))});
    `], { env, encoding: 'utf8' });
    try {
        fs.mkdirSync(source);
        fs.writeFileSync(config, '[core]\n\tautocrlf = false\n', 'utf8');
        git(['init', '--quiet']);
        for (const [name, contents] of [
            ['pom.xml', '<project/>\n'],
            ['src/main/resources/plugin.properties', `plugin.id=douyin\nplugin.version=${PLUGIN_VERSION}\n`],
            [relative, content],
        ]) {
            const file = path.join(source, name);
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, contents, 'utf8');
        }
        git(['add', '.']);
        git(['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'fixture']);
        const revision = git(['rev-parse', 'HEAD']).trim();
        fs.mkdirSync(path.join(root, 'scripts/ci'), { recursive: true });
        fs.writeFileSync(path.join(root, 'scripts/ci/douyin-source.json'), JSON.stringify({ revision }), 'utf8');
        fs.appendFileSync(config, `\n[url "${source.replaceAll('\\', '/')}"]\n`
                + '\tinsteadOf = https://github.com/Sywyar/PixivDownloader-Plugin-Douyin.git\n', 'utf8');

        const originalConfig = fs.readFileSync(config, 'utf8');
        const result = checkout(completeDirectory);
        assert.equal(result.status, 0, result.stderr);
        assert.equal(fs.readFileSync(path.join(root, completeDirectory, 'douyin-source', relative), 'utf8'), content);
        assert.equal(fs.readFileSync(config, 'utf8'), originalConfig);

        // 模拟检出命令成功、工作树却遗漏文件，验证构建前的完整性检查。
        const template = path.join(root, 'template');
        fs.mkdirSync(path.join(template, 'hooks'), { recursive: true });
        fs.writeFileSync(path.join(template, 'hooks/post-checkout'), '#!/bin/sh\nrm -f pom.xml\n', { mode: 0o755 });
        fs.appendFileSync(config, `\n[init]\n\ttemplateDir = "${template.replaceAll('\\', '/')}"\n`, 'utf8');
        const incomplete = checkout('incomplete');
        assert.notEqual(incomplete.status, 0, incomplete.stdout);
        assert.match(incomplete.stderr, /git exited with/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('隔离消费者按指定仓库字节离线验证 SDK，不依赖 Maven 来源 marker', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-consumer-'));
    const supplied = path.join(root, 'supplied');
    const local = path.join(root, 'local');
    try {
        writeRepository(supplied);
        const stale = path.join(local, GROUP_PATH, 'pixivdownload-sdk-bom', VERSION);
        fs.mkdirSync(stale, { recursive: true });
        fs.writeFileSync(path.join(stale, '_remote.repositories'),
                `pixivdownload-sdk-bom-${VERSION}.pom>central=\n`, 'utf8');
        fs.writeFileSync(path.join(stale, `pixivdownload-sdk-bom-${VERSION}.pom`), 'stale', 'utf8');

        stageSdkArtifacts(local, supplied, VERSION);
        assert.equal(fs.existsSync(path.join(stale, '_remote.repositories')), false);
        assert.doesNotThrow(() => assertSdkResolution(local, supplied, VERSION));

        fs.writeFileSync(path.join(local, GROUP_PATH, 'pixivdownload-core-api', VERSION,
                `pixivdownload-core-api-${VERSION}.jar`), 'tampered', 'utf8');
        assert.throws(() => assertSdkResolution(local, supplied, VERSION), /does not match/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
