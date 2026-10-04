import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { inspectSdkVersion, parseSdkVersion, SDK_MODULES } from '../sdk-version.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const CLI = path.join(ROOT, 'scripts/ci/sdk-baseline.mjs');
const CURRENT = inspectSdkVersion(ROOT);
const METADATA = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties';
const MODULES = ['sdk-info', 'plugin-api', 'core-api'];
const BASE_VERSION = '27.3.0';
const NEXT_VERSION = '27.4.0';
const PUBLISHED_REFS = new Map();

function write(root, file, text) {
    const destination = path.join(root, file);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, text, 'utf8');
}

function command(root, executable, args) {
    return execFileSync(executable, args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function version(root, value) {
    const identity = parseSdkVersion(value);
    for (const file of ['pom.xml', METADATA, ...SDK_MODULES.map(module => `${module}/pom.xml`),
        ...['minimal-feature-plugin', 'download-type-plugin'].map(name => `plugin-templates/${name}/pom.xml`)]) {
        write(root, file, fs.readFileSync(path.join(ROOT, file), 'utf8').replaceAll(CURRENT.version, value));
    }
    for (const name of ['minimal-feature-plugin', 'download-type-plugin']) {
        write(root, `plugin-templates/${name}/src/main/resources/plugin.properties`,
                `plugin.requires=${identity.prerelease ? '=' + value : identity.compatibilityVersion}\n`);
    }
}

function build(root, value, methods = 'public void keep() {}') {
    write(root, 'Api.java', `package fixture; public class Api { ${methods} }\n`);
    command(root, 'javac', ['--release', '17', '-d', 'classes', 'Api.java']);
    for (const name of MODULES) {
        const destination = `pixivdownload-${name}/target`;
        fs.mkdirSync(path.join(root, destination), { recursive: true });
        command(root, 'jar', ['--create', '--file', `${destination}/pixivdownload-${name}-${value}.jar`,
            '-C', 'classes', '.']);
    }
}

function expectedSurface(methods = ['keep']) {
    return MODULES.flatMap(name => [
        `${name}\tTYPE\tpublic class fixture.Api`,
        `${name}\tMEMBER\tpublic class fixture.Api\tpublic fixture.Api();\tdescriptor: ()V`,
        ...methods.map(method => `${name}\tMEMBER\tpublic class fixture.Api\tpublic void ${method}();\tdescriptor: ()V`)
    ]).sort().join('\n') + '\n';
}

function baseline(root) {
    return ['metadata.json', 'api-surface.txt'].map(file =>
        fs.readFileSync(path.join(root, 'sdk-baselines/v27', file), 'utf8'));
}

function run(root) {
    const script = `import { updateBaseline } from ${JSON.stringify(pathToFileURL(CLI).href)};
        updateBaseline(process.cwd(), process.argv[1]);`;
    return spawnSync(process.execPath, ['--input-type=module', '-e', script, PUBLISHED_REFS.get(root)],
            { cwd: root, encoding: 'utf8' });
}

function fixture(t, withBaseline = true) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-baseline-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    command(root, 'git', ['init', '-q']);
    command(root, 'git', ['config', 'user.email', 'test@example.com']);
    command(root, 'git', ['config', 'user.name', 'test']);
    version(root, BASE_VERSION);
    if (withBaseline) {
        write(root, 'sdk-baselines/v27/metadata.json', JSON.stringify({ schemaVersion: 1, sdkVersion: BASE_VERSION }) + '\n');
        write(root, 'sdk-baselines/v27/api-surface.txt', expectedSurface());
    }
    command(root, 'git', ['add', '.']);
    command(root, 'git', ['commit', '-q', '-m', 'baseline']);
    PUBLISHED_REFS.set(root, command(root, 'git', ['rev-parse', 'HEAD']).trim());
    t.after(() => PUBLISHED_REFS.delete(root));
    return root;
}

test('稳定基线命令从真实 JAR 同步版本和 API，重复执行字节不变', t => {
    const root = fixture(t);
    version(root, NEXT_VERSION);
    build(root, NEXT_VERSION, 'public void keep() {} public void added() {}');
    const result = run(root);
    assert.equal(result.status, 0, result.stderr);
    const generated = baseline(root);
    assert.deepEqual(JSON.parse(generated[0]), { schemaVersion: 1, sdkVersion: NEXT_VERSION });
    assert.equal(generated[1], expectedSurface(['keep', 'added']));
    assert.equal(run(root).status, 0);
    assert.deepEqual(baseline(root), generated);
});

test('稳定基线命令允许无 API 变化的版本更新与首次稳定基线', t => {
    for (const withBaseline of [true, false]) {
        const root = fixture(t, withBaseline);
        version(root, NEXT_VERSION);
        build(root, NEXT_VERSION);
        const result = run(root);
        assert.equal(result.status, 0, result.stderr);
        assert.equal(JSON.parse(baseline(root)[0]).sdkVersion, NEXT_VERSION);
        assert.equal(baseline(root)[1], expectedSurface());
    }
});

test('多个开发提交共用未发布版本，累计 API 变化仍对比实际发行', t => {
    const root = fixture(t);
    version(root, NEXT_VERSION);
    build(root, NEXT_VERSION, 'public void keep() {} public void draft() {}');
    assert.equal(run(root).status, 0);
    command(root, 'git', ['add', '.']);
    command(root, 'git', ['commit', '-q', '-m', 'development API']);

    build(root, NEXT_VERSION, 'public void keep() {} public void added() {}');
    const result = run(root);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(baseline(root)[1], expectedSurface(['keep', 'added']));
    assert.equal(JSON.parse(baseline(root)[0]).sdkVersion, NEXT_VERSION);

    build(root, NEXT_VERSION, 'public void added() {}');
    const before = baseline(root);
    assert.match(run(root).stderr, /removes public API/u);
    assert.deepEqual(baseline(root), before);
});

test('工作区基线被提前改写也不能掩盖已发布 API 的删除', t => {
    const root = fixture(t);
    version(root, NEXT_VERSION);
    build(root, NEXT_VERSION, 'public void added() {}');
    write(root, 'sdk-baselines/v27/api-surface.txt', expectedSurface(['added']));
    write(root, 'sdk-baselines/v27/metadata.json', JSON.stringify({ schemaVersion: 1, sdkVersion: NEXT_VERSION }));
    const before = baseline(root);
    const result = run(root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /removes public API/u);
    assert.deepEqual(baseline(root), before);
});

test('预发布、版本倒退和新增 API 未提升次版本时保留原基线', t => {
    for (const [value, methods, error] of [
        ['27.4.0-rc.1', 'public void keep() {}', /prerelease/u],
        ['27.2.0', 'public void keep() {}', /must increase/u],
        ['26.9.0', 'public void keep() {}', /must increase/u],
        ['27.3.1', 'public void keep() {} public void added() {}', /higher SDK minor/u],
        [BASE_VERSION, 'public void keep() {} public void added() {}', /without a new SDK release identity/u]
    ]) {
        const root = fixture(t);
        version(root, value);
        build(root, value, methods);
        const before = baseline(root);
        const result = run(root);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, error);
        assert.deepEqual(baseline(root), before);
        assert.equal(fs.existsSync(path.join(root, 'sdk-baselines/v26')), false);
    }
});

test('缺少当前版本 JAR 或已发布基线不完整时不写入', t => {
    const root = fixture(t);
    version(root, NEXT_VERSION);
    build(root, BASE_VERSION);
    const before = baseline(root);
    assert.notEqual(run(root).status, 0);
    assert.deepEqual(baseline(root), before);

    command(root, 'git', ['rm', 'sdk-baselines/v27/api-surface.txt']);
    command(root, 'git', ['commit', '-q', '-m', 'incomplete baseline']);
    PUBLISHED_REFS.set(root, command(root, 'git', ['rev-parse', 'HEAD']).trim());
    const result = run(root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /incomplete/u);
    assert.equal(fs.existsSync(path.join(root, 'sdk-baselines/v27/api-surface.txt')), false);
    assert.equal(fs.readFileSync(path.join(root, 'sdk-baselines/v27/metadata.json'), 'utf8'), before[0]);
});
