import assert from 'node:assert/strict';
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
