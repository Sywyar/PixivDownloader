import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';

test('签名 CLI 的加密私钥与 OpenSSL 及各指定 JDK 双向互通，错误密码仍拒绝', t => {
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'signing-key-interop-')));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const source = fileURLToPath(new URL('../../../pixivdownload-plugin-signature/src/main/java/', import.meta.url));
    const sources = fs.readdirSync(source, { recursive: true }).filter(name => name.endsWith('.java')).map(name => path.join(source, name));
    const classes = path.join(root, 'classes'); fs.mkdirSync(classes);
    execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', classes, ...sources], { windowsHide: true, stdio: 'pipe' });
    // 默认验证当前 JDK；本地或 CI 可传入多个 JAVA_HOME，在同一次测试中覆盖全部读写组合。
    const homes = (process.env.PIXIV_TEST_JAVA_HOMES ?? process.env.JAVA_HOME ?? '').split(path.delimiter).filter(Boolean);
    const runtimes = homes.length ? [...new Set(homes)].map(home => path.join(home, 'bin', 'java')) : ['java'];
    const password = Buffer.from('互操作-é-🔑', 'utf8');
    t.after(() => password.fill(0));
    const run = (java, args, input = password) => spawnSync(java, ['-cp', classes,
        'top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool', ...args],
    { input, encoding: 'utf8', windowsHide: true, timeout: 120000 });
    const publicDer = key => crypto.createPublicKey(key).export({ format: 'der', type: 'spki' });
    const check = (java, directory, expected = 0, input = password) => {
        const result = run(java, ['check-key', '--private-key', path.join(directory, 'private-key.pem'),
            '--public-key', path.join(directory, 'public-key.pem'), '--password-stdin', 'true'], input);
        assert.equal(result.status, expected, result.stderr);
        if (expected) assert.match(result.stderr, /KEY_PASSWORD_INVALID/u);
        assert(!result.stdout.includes(password.toString('utf8')));
    };
    for (const [index, java] of runtimes.entries()) {
        const directory = path.join(root, 'jdk-' + index);
        const generated = run(java, ['keygen', '--directory', directory, '--password-stdin', 'true']);
        assert.equal(generated.status, 0, generated.stderr);
        const pem = fs.readFileSync(path.join(directory, 'private-key.pem'));
        const decoded = crypto.createPrivateKey({ key: pem, passphrase: password });
        assert.equal(decoded.asymmetricKeyType, 'ed25519');
        assert.deepEqual(publicDer(decoded), publicDer(fs.readFileSync(path.join(directory, 'public-key.pem'))));
        for (const reader of runtimes) check(reader, directory);
        check(java, directory, 1, Buffer.from('wrong'));
        assert.deepEqual(fs.readFileSync(path.join(directory, 'private-key.pem')), pem);
    }
    const independent = path.join(root, 'openssl'); fs.mkdirSync(independent);
    const pair = crypto.generateKeyPairSync('ed25519');
    fs.writeFileSync(path.join(independent, 'private-key.pem'), pair.privateKey.export({
        type: 'pkcs8', format: 'pem', cipher: 'aes-256-cbc', passphrase: password }));
    fs.writeFileSync(path.join(independent, 'public-key.pem'), pair.publicKey.export({ type: 'spki', format: 'pem' }));
    for (const java of runtimes) check(java, independent);
    const invalid = path.join(root, 'invalid'); fs.mkdirSync(invalid);
    fs.copyFileSync(path.join(independent, 'public-key.pem'), path.join(invalid, 'public-key.pem'));
    for (const [pem, code] of [
        [pair.privateKey.export({ type: 'pkcs8', format: 'pem', cipher: 'aes-128-cbc', passphrase: password }), 'KEY_ENCRYPTION_UNSUPPORTED'],
        ['-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n', 'KEY_FORMAT_INVALID'],
        [Buffer.alloc(16385), 'INPUT_LIMIT_EXCEEDED'],
    ]) {
        fs.writeFileSync(path.join(invalid, 'private-key.pem'), pem);
        for (const java of runtimes) {
            const result = run(java, ['check-key', '--private-key', path.join(invalid, 'private-key.pem'),
                '--public-key', path.join(invalid, 'public-key.pem')], Buffer.alloc(0));
            assert.equal(result.status, 1);
            assert(result.stderr.includes(code), result.stderr);
        }
    }
    t.diagnostic(`JDK writers/readers: ${runtimes.length}; cross-JDK checks: ${runtimes.length ** 2}; OpenSSL imports: ${runtimes.length}`);
});
