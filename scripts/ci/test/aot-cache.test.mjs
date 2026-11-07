import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { isolatedEnvironment, main, prepareCache, validateCache } from '../../aot-cache.mjs';

test('训练与验收共用隔离环境，保留系统路径并排除 JVM、应用配置与凭据', () => {
    assert.deepEqual(isolatedEnvironment({
        Path: '/tools', SYSTEMROOT: 'C:/Windows', TEMP: '/temp', LANG: 'en_US.UTF-8',
        JAVA_TOOL_OPTIONS: '-Dpixivdownload.instance-dir=real-instance', NODE_OPTIONS: '--inspect',
        SPRING_CONFIG_LOCATION: 'real-config.yaml', GITHUB_TOKEN: 'test-token',
    }), { Path: '/tools', SYSTEMROOT: 'C:/Windows', TEMP: '/temp', LANG: 'en_US.UTF-8' });
});

function fixture(t) {
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-aot-test-')));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const javaHome = path.join(root, 'jdk');
    const cacheDir = path.join(root, 'cache');
    const jar = path.join(root, 'host.jar');
    const executable = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    const vm = process.platform === 'win32' ? 'bin/server/jvm.dll'
        : process.platform === 'darwin' ? 'lib/server/libjvm.dylib' : 'lib/server/libjvm.so';
    const contents = { release: 'JAVA_VERSION="25-test"\n', 'lib/modules': 'jdk modules',
        [path.relative(javaHome, executable)]: 'java launcher', [vm]: 'virtual machine' };
    const hash = text => createHash('sha256').update(text).digest('hex');
    for (const [file, text] of Object.entries(contents)) {
        fs.mkdirSync(path.dirname(path.join(javaHome, file)), { recursive: true });
        fs.writeFileSync(path.join(javaHome, file), text);
    }
    fs.mkdirSync(path.join(cacheDir, 'application/lib'), { recursive: true });
    fs.writeFileSync(jar, 'boot jar');
    fs.writeFileSync(path.join(cacheDir, 'application/host.jar'), 'extracted jar');
    fs.writeFileSync(path.join(cacheDir, 'application/lib/dependency.jar'), 'dependency');
    fs.writeFileSync(path.join(cacheDir, 'application.aot'), 'cache');
    const manifest = { schemaVersion: 1, root: fs.realpathSync(cacheDir), sourceSha256: hash('boot jar'),
        java: { home: fs.realpathSync(javaHome), executable, platform: process.platform, arch: process.arch,
            files: Object.fromEntries(Object.entries(contents).map(([name, value]) => [name, hash(value)])) },
        entry: 'host.jar', files: { 'host.jar': hash('extracted jar'), 'lib/dependency.jar': hash('dependency') }, cacheSha256: hash('cache') };
    const manifestFile = path.join(cacheDir, 'manifest.json');
    fs.writeFileSync(manifestFile, JSON.stringify(manifest));
    return { root, jar, javaHome, cacheDir, manifestFile, manifest };
}

test('缓存校验接受相同内容，拒绝保留大小和时间的应用、JDK 与缓存变更', t => {
    const options = fixture(t);
    assert.equal(validateCache(options).jar, path.join(options.cacheDir, 'application/host.jar'));
    const files = [options.jar, path.join(options.javaHome, 'lib/modules'),
        path.join(options.cacheDir, 'application/lib/dependency.jar'), path.join(options.cacheDir, 'application.aot')];
    for (const file of files) {
        const original = fs.readFileSync(file);
        const stat = fs.statSync(file);
        fs.writeFileSync(file, Buffer.alloc(original.length, 88));
        fs.utimesSync(file, stat.atime, stat.mtime);
        assert.throws(() => validateCache(options), /changed/);
        fs.writeFileSync(file, original);
    }
    assert.doesNotThrow(() => validateCache(options));
});

test('缓存校验拒绝缺失、额外依赖和目录迁移', t => {
    const options = fixture(t);
    const extra = path.join(options.cacheDir, 'application/lib/extra.jar');
    fs.writeFileSync(extra, 'extra');
    assert.throws(() => validateCache(options), /extracted files changed/);
    fs.unlinkSync(extra);
    const special = path.join(options.cacheDir, 'application/__proto__');
    fs.writeFileSync(special, 'extra');
    assert.throws(() => validateCache(options), /extracted files changed/);
    fs.unlinkSync(special);
    const dependency = path.join(options.cacheDir, 'application/lib/dependency.jar');
    fs.unlinkSync(dependency);
    assert.throws(() => validateCache(options), /extracted files changed/);
    fs.writeFileSync(dependency, 'dependency');
    const moved = `${options.cacheDir}-moved`;
    fs.renameSync(options.cacheDir, moved);
    assert.throws(() => validateCache({ ...options, cacheDir: moved }), /layout changed/);
});

test('缓存清单拒绝路径越界、损坏和超出读取预算', t => {
    const options = fixture(t);
    fs.writeFileSync(options.manifestFile, JSON.stringify({ ...options.manifest, entry: '../host.jar' }));
    assert.throws(() => validateCache(options), /layout changed/);
    fs.writeFileSync(options.manifestFile, '{');
    assert.throws(() => validateCache(options), SyntaxError);
    fs.writeFileSync(options.manifestFile, Buffer.alloc(1024 * 1024 + 1, 32));
    assert.throws(() => validateCache(options), /exceeds/);
});

test('准备缓存拒绝覆盖现存目录，失败不改已有文件', async t => {
    const options = fixture(t);
    const before = fs.readFileSync(options.manifestFile, 'utf8');
    await assert.rejects(prepareCache(options), { code: 'EEXIST' });
    assert.equal(fs.readFileSync(options.manifestFile, 'utf8'), before);
    await assert.rejects(main(['prepare', '--jar', options.jar, '--java-home', options.javaHome,
        '--cache-dir', options.cacheDir, '--', '--setup']), /Invalid arguments/);
});

test('启动校验在 Java 执行之前拒绝已失效的缓存', async t => {
    const options = fixture(t);
    fs.writeFileSync(options.jar, 'different application');
    await assert.rejects(main(['run', '--jar', options.jar, '--java-home', options.javaHome,
        '--cache-dir', options.cacheDir]), /application changed/);
});

test('CLI 复用所选 JAVA_HOME，显式路径优先，缺少 JDK 时给出配置指引', t => {
    const options = fixture(t);
    fs.writeFileSync(options.jar, 'different application');
    const script = fileURLToPath(new URL('../../aot-cache.mjs', import.meta.url));
    const args = [script, 'run', '--jar', options.jar, '--cache-dir', options.cacheDir];
    const inherited = spawnSync(process.execPath, args, {
        encoding: 'utf8', env: { ...process.env, JAVA_HOME: options.javaHome },
    });
    assert.equal(inherited.status, 1);
    assert.match(inherited.stderr, /application changed/);
    const explicit = spawnSync(process.execPath, [...args, '--java-home', options.javaHome], {
        encoding: 'utf8', env: { ...process.env, JAVA_HOME: path.join(options.root, 'wrong jdk') },
    });
    assert.equal(explicit.status, 1);
    assert.match(explicit.stderr, /application changed/);
    const missing = spawnSync(process.execPath, args, { encoding: 'utf8', env: { ...process.env, JAVA_HOME: '' } });
    assert.equal(missing.status, 1);
    assert.match(missing.stderr, /toolchains\.xml/);
});

test('未准备缓存时 CLI 给出准备指引并保持目录缺失', t => {
    const options = fixture(t);
    const missing = path.join(options.root, 'unprepared cache');
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('../../aot-cache.mjs', import.meta.url)),
        'run', '--jar', options.jar, '--java-home', options.javaHome, '--cache-dir', missing], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.ok(result.stderr.includes(missing));
    assert.match(result.stderr, /AOT Prepare/);
    assert.doesNotMatch(result.stderr, /ENOENT/);
    assert.equal(fs.existsSync(missing), false);
});

test('未完成训练的缓存提示换用新目录并保留现有文件', t => {
    const options = fixture(t);
    fs.unlinkSync(options.manifestFile);
    assert.throws(() => validateCache(options), error => {
        assert.match(error.message, /AOT Prepare/);
        assert.match(error.message, /new cache directory/);
        assert.equal(error.cause.code, 'ENOENT');
        return true;
    });
    assert.equal(fs.readFileSync(path.join(options.cacheDir, 'application.aot'), 'utf8'), 'cache');
    assert.equal(fs.existsSync(options.manifestFile), false);
});

test('准备和运行都明确提示缺失的打包 JAR 且不改缓存', async t => {
    const options = fixture(t);
    fs.unlinkSync(options.jar);
    const before = fs.readFileSync(options.manifestFile, 'utf8');
    for (const command of ['prepare', 'run']) {
        await assert.rejects(main([command, '--jar', options.jar, '--java-home', options.javaHome,
            '--cache-dir', options.cacheDir]), error => {
            assert.ok(error.message.includes(options.jar));
            assert.match(error.message, /Build the boot JAR/);
            assert.equal(error.cause.code, 'ENOENT');
            return true;
        });
    }
    assert.equal(fs.readFileSync(options.manifestFile, 'utf8'), before);
});

test('准备失败不发布清单并清理隔离训练目录', async t => {
    const options = fixture(t);
    const failed = path.join(options.root, 'missing parent', 'failed');
    await assert.rejects(prepareCache({ ...options, cacheDir: failed }));
    assert.equal(fs.statSync(failed).isDirectory(), true);
    assert.equal(fs.existsSync(path.join(failed, 'manifest.json')), false);
    assert.equal(fs.existsSync(path.join(failed, 'training')), false);
    assert.equal(fs.readFileSync(options.jar, 'utf8'), 'boot jar');
});

test('缓存目录中的链接不能引入外部依赖', t => {
    const options = fixture(t);
    const outside = path.join(options.root, 'outside');
    fs.mkdirSync(outside);
    fs.writeFileSync(path.join(outside, 'external.jar'), 'external');
    const link = path.join(options.cacheDir, 'application/linked');
    fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
    assert.throws(() => validateCache(options), /regular file/);
    fs.unlinkSync(link);
    assert.equal(fs.readFileSync(path.join(outside, 'external.jar'), 'utf8'), 'external');
});
