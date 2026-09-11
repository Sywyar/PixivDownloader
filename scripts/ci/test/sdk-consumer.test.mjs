import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { inspectSdkVersion } from '../sdk-version.mjs';

import { assertSdkResolution, parsePluginIdentity, stageSdkArtifacts } from '../sdk-consumer.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const VERSION = inspectSdkVersion(ROOT).version;
const DESCRIPTOR = fs.readFileSync(path.join(ROOT, 'pixivdownload-plugin-douyin/src/main/resources/plugin.properties'), 'utf8');
const PLUGIN_VERSION = /^plugin\.version=(.+)$/mu.exec(DESCRIPTOR)[1].trim();
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
