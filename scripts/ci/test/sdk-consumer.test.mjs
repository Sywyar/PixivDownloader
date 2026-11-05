import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { inspectSdkVersion } from '../sdk-version.mjs';

import { assertSdkResolution, stageSdkArtifacts, verifyTypedServiceArtifacts } from '../sdk-consumer.mjs';
import { createHash } from 'node:crypto';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const VERSION = inspectSdkVersion(ROOT).version;
const GROUP_PATH = path.join('io', 'github', 'sywyar', 'pixivdownloader');

test('独立服务产物必须携带原 API 字节，消费与提供包不得重复契约或宿主类', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-typed-artifacts-'));
    const pack = (module, entries) => {
        const target = path.join(root, module, 'target');
        const content = path.join(target, 'content');
        fs.rmSync(content, { recursive: true, force: true });
        fs.mkdirSync(content, { recursive: true });
        for (const [name, value] of Object.entries(entries)) {
            fs.mkdirSync(path.dirname(path.join(content, name)), { recursive: true });
            fs.writeFileSync(path.join(content, name), value);
        }
        const jar = path.join(target, 'fixture.jar');
        execFileSync('jar', ['--create', '--file', jar, '-C', content, '.']);
        return jar;
    };
    const ordinary = module => ({ 'plugin.properties': `plugin.id=${module}\n`,
        [`com/example/textmetrics/${module}/Entry.class`]: 'class fixture' });
    try {
        const api = fs.readFileSync(pack('api', { 'com/example/textmetrics/api/Api.class': 'API class fixture' }));
        const digest = createHash('sha256').update(api).digest('hex');
        const contract = { ...ordinary('contract'), 'lib/text-metrics-api.jar': api,
            'plugin.properties': `pixiv.contract-api=lib/text-metrics-api.jar\npixiv.contract-sha256=${digest}\n` };
        pack('contract', contract);
        pack('provider', ordinary('provider'));
        pack('consumer', ordinary('consumer'));
        assert.deepEqual(verifyTypedServiceArtifacts(root), { apiDigest: digest });
        for (const [module, entry] of [['provider', 'com/example/textmetrics/api/Api.class'],
            ['consumer', 'org/springframework/Context.class'], ['consumer', 'hidden.jar']]) {
            pack(module, { ...ordinary(module), [entry]: 'unexpected' });
            assert.throws(() => verifyTypedServiceArtifacts(root), /unexpected|duplicated/u);
            pack(module, ordinary(module));
        }
        pack('contract', { ...contract, 'lib/text-metrics-api.jar': Buffer.from('different bytes') });
        assert.throws(() => verifyTypedServiceArtifacts(root), /original API JAR bytes/u);
        pack('contract', { ...contract, 'plugin.properties': contract['plugin.properties'].replace(digest, '0'.repeat(64)) });
        assert.throws(() => verifyTypedServiceArtifacts(root), /original API JAR bytes/u);
        assert.deepEqual(fs.readdirSync(path.join(root, 'target')), []);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
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
