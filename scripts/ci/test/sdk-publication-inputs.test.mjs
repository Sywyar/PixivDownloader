import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { verifyInputs, transferInputs } from '../sdk-publication-inputs.mjs';
import { sha256 } from '../sdk-release.mjs';
import { inspectSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from '../sdk-version.mjs';

test('SDK 发布输入绑定同一源码与原始字节，拒绝越界路径、重复项及篡改后恢复', t => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-publication-inputs-'));
    t.after(() => fs.rmSync(work, { recursive: true, force: true }));
    const bundle = path.join(work, 'bundle');
    const destination = path.join(work, 'repo');
    const relative = 'target/sdk-runtime-inputs/host.jar';
    const file = path.join(bundle, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, 'tested bytes');
    const sourceSha = 'a'.repeat(40);
    const valid = { schemaVersion: 1, sourceSha, hostVersion: '7.8.9',
        files: [{ file: relative, size: fs.statSync(file).size, sha256: sha256(file) }] };
    const write = value => fs.writeFileSync(path.join(bundle, 'inputs.json'), JSON.stringify(value));
    write(valid);
    assert.deepEqual(verifyInputs(bundle, sourceSha), valid);
    transferInputs(destination, bundle, sourceSha, true);
    assert.deepEqual(fs.readFileSync(path.join(destination, relative)), fs.readFileSync(file));
    for (const candidate of [
        { ...valid, sourceSha: 'b'.repeat(40) },
        { ...valid, files: [] },
        { ...valid, files: [valid.files[0], valid.files[0]] },
        ...['../escape.jar', 'target/sdk-runtime-inputs/../outside.jar', '.github/workflows/publish-sdk.yml',
            'target/sdk-runtime-inputs/plugins/secret.pem', 'target/sdk-runtime-inputs/plugins/c:escape.jar',
            'target/sdk-runtime-inputs/plugins/..\\..\\escape.jar',
            'pixivdownload-plugin-api/target/..\\..\\escape.jar']
            .map(name => ({ ...valid, files: [{ ...valid.files[0], file: name }] })),
        { ...valid, files: [{ ...valid.files[0], size: 256 * 1024 ** 2 + 1 }] },
        { ...valid, files: [{ ...valid.files[0], sha256: '0'.repeat(64) }] },
    ]) {
        write(candidate);
        const rejected = path.join(work, 'rejected');
        assert.throws(() => transferInputs(rejected, bundle, sourceSha, true));
        assert.equal(fs.existsSync(rejected), false);
    }
    write(valid);
    fs.appendFileSync(file, 'modified');
    assert.throws(() => verifyInputs(bundle, sourceSha), /bytes mismatch/u);
});

test('发布恢复入口为全部 Maven POM 和主 JAR 生成匹配原始字节的校验和', t => {
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-publication-restore-'));
    t.after(() => fs.rmSync(work, { recursive: true, force: true }));
    const version = inspectSdkVersion(root).version;
    const bundle = path.join(work, 'target/sdk-publication-inputs');
    for (const relative of [
        'pom.xml', 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties',
        ...SDK_ARTIFACTS.map(([name]) => `${name}/pom.xml`),
        ...['minimal-feature-plugin', 'download-type-plugin'].flatMap(name => [
            `plugin-templates/${name}/pom.xml`, `plugin-templates/${name}/src/main/resources/plugin.properties`,
        ]),
    ]) {
        fs.mkdirSync(path.dirname(path.join(work, relative)), { recursive: true });
        fs.copyFileSync(path.join(root, relative), path.join(work, relative));
    }
    const files = SDK_ARTIFACTS.flatMap(([name, packaging]) => [
        `${name}/target/flattened-pom.xml`,
        ...(packaging === 'jar' ? [`${name}/target/${name}-${version}.jar`] : []),
    ]).map(file => {
        const source = path.join(bundle, file);
        fs.mkdirSync(path.dirname(source), { recursive: true });
        fs.writeFileSync(source, `verified bytes: ${file}\n`, 'utf8');
        return { file, size: fs.statSync(source).size, sha256: sha256(source) };
    });
    const sourceSha = 'c'.repeat(40);
    fs.writeFileSync(path.join(bundle, 'inputs.json'), JSON.stringify({
        schemaVersion: 1, sourceSha, hostVersion: '7.8.9', files,
    }), 'utf8');
    execFileSync(process.execPath, [path.join(root, 'scripts/ci/sdk-publication-inputs.mjs'), 'restore', sourceSha],
        { cwd: work, stdio: 'pipe' });
    for (const [name, packaging] of SDK_ARTIFACTS) {
        for (const extension of packaging === 'jar' ? ['pom', 'jar'] : ['pom']) {
            const artifact = `${name}-${version}.${extension}`;
            const staged = path.join(work, 'target/sdk-staging', SDK_GROUP_ID.replaceAll('.', '/'), name, version, artifact);
            const source = path.join(bundle, name, 'target', extension === 'pom' ? 'flattened-pom.xml' : artifact);
            const bytes = fs.readFileSync(source);
            assert.deepEqual(fs.readFileSync(staged), bytes);
            assert.equal(fs.readFileSync(`${staged}.sha1`, 'utf8').trim(), createHash('sha1').update(bytes).digest('hex'));
        }
    }
});
