import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';

const run = promisify(execFile);

test('POSIX Maven Wrapper 固定 ZIP、校验后解压并保留可执行入口与缓存', {
    skip: process.platform === 'win32' ? 'POSIX 引导回归在 Linux / macOS 执行' : false,
}, async t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'maven-wrapper-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const command = name => execFileSync('/bin/sh', ['-c', 'command -v "$1"', 'tool', name], { encoding: 'utf8' }).trim();
    const jar = command('jar');
    const unzip = command('unzip');
    const archive = path.join(root, 'maven.zip');
    // 使用带 Unix 权限的真实 ZIP，入口只回显参数，避免网络依赖与 Maven 版本耦合。
    execFileSync('python3', ['-c', `
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as archive:
    for name in ['mvn', 'mvnDebug']:
        entry = zipfile.ZipInfo('maven-fixture/bin/' + name)
        entry.create_system = 3
        entry.external_attr = 0o100755 << 16
        archive.writestr(entry, '#!/bin/sh\\nprintf "%s\\\\n" "$@"\\n')
`, archive]);
    const original = fs.readFileSync(archive);
    let payload = original;
    const digest = bytes => createHash('sha256').update(bytes).digest('hex');
    const requests = [];
    const server = http.createServer((request, response) => {
        requests.push(request.url);
        response.end(payload);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise(resolve => server.close(resolve)));
    const url = `http://127.0.0.1:${server.address().port}/maven-fixture-bin.zip`;
    const tools = new Map(['uname', 'dirname', 'basename', 'tr', 'mktemp', 'mkdir', 'rm', 'mv', 'chmod', 'sha256sum', 'curl']
        .map(name => [name, command(name)]));
    const fixture = (name, extractor, checksum = digest(original)) => {
        const project = path.join(root, name);
        const bin = path.join(project, 'bin');
        fs.mkdirSync(bin, { recursive: true });
        for (const [tool, source] of tools) fs.symlinkSync(source, path.join(bin, tool));
        const javaHome = path.join(project, 'jdk with spaces');
        if (extractor === 'unzip') fs.symlinkSync(unzip, path.join(bin, 'unzip'));
        if (extractor === 'path') fs.symlinkSync(jar, path.join(bin, 'jar'));
        if (extractor === 'home') {
            fs.mkdirSync(path.join(javaHome, 'bin'), { recursive: true });
            fs.symlinkSync(jar, path.join(javaHome, 'bin/jar'));
        }
        fs.mkdirSync(path.join(project, '.mvn/wrapper'), { recursive: true });
        fs.copyFileSync(new URL('../../../mvnw', import.meta.url), path.join(project, 'mvnw'));
        fs.writeFileSync(path.join(project, '.mvn/wrapper/maven-wrapper.properties'),
            `distributionUrl=${url}\ndistributionSha256Sum=${checksum}\n`, 'utf8');
        const temporary = path.join(project, 'temporary');
        fs.mkdirSync(temporary);
        const cache = path.join(project, 'cache');
        const env = { ...process.env, PATH: bin, JAVA_HOME: extractor === 'home' ? javaHome : '',
            MAVEN_USER_HOME: cache, TMPDIR: temporary, MVNW_REPOURL: '', MVNW_VERBOSE: '', MVNW_USERNAME: '', MVNW_PASSWORD: '' };
        return { bin, cache, temporary, invoke: (...args) => run('/bin/sh', [path.join(project, 'mvnw'), ...args], { cwd: project, env }) };
    };
    for (const extractor of ['unzip', 'path', 'home']) await t.test(`使用 ${extractor} 解压固定 ZIP，缓存命中不再下载`, async () => {
        payload = original;
        requests.length = 0;
        const item = fixture(extractor, extractor);
        const args = ['--version', '-Dvalue=contains spaces 中文'];
        assert.equal((await item.invoke(...args)).stdout, args.join('\n') + '\n');
        assert.deepEqual(requests, ['/maven-fixture-bin.zip']);
        const dist = path.join(item.cache, 'wrapper/dists/maven-fixture');
        const installed = path.join(dist, fs.readdirSync(dist)[0]);
        assert.equal(fs.readFileSync(path.join(installed, 'mvnw.url'), 'utf8').trim(), url);
        for (const name of ['mvn', 'mvnDebug']) assert.ok(fs.statSync(path.join(installed, 'bin', name)).mode & 0o111);
        if (extractor !== 'home') fs.unlinkSync(path.join(item.bin, extractor === 'path' ? 'jar' : 'unzip'));
        assert.equal((await item.invoke('cached')).stdout, 'cached\n');
        assert.deepEqual(requests, ['/maven-fixture-bin.zip']);
        assert.deepEqual(fs.readdirSync(item.temporary), []);
    });
    for (const failure of ['checksum', 'archive', 'extractor']) await t.test(`${failure} 失败不安装 Maven 或留下下载临时目录`, async () => {
        payload = failure === 'archive' ? Buffer.from('invalid ZIP') : original;
        requests.length = 0;
        const checksum = failure === 'checksum' ? '0'.repeat(64) : digest(payload);
        const item = fixture(failure, failure === 'extractor' ? null : 'path', checksum);
        await assert.rejects(item.invoke('--version'), error => {
            assert.match(error.stderr, failure === 'checksum' ? /Failed to validate Maven distribution SHA-256/u
                : failure === 'archive' ? /failed to extract Maven ZIP/u : /requires unzip or/u);
            return true;
        });
        assert.deepEqual(requests, failure === 'extractor' ? [] : ['/maven-fixture-bin.zip']);
        assert.deepEqual(fs.readdirSync(path.join(item.cache, 'wrapper/dists/maven-fixture')), []);
        assert.deepEqual(fs.readdirSync(item.temporary), []);
    });
});
