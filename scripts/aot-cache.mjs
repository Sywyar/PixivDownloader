#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { parseArgs, isDeepStrictEqual } from 'node:util';
import { sha256 } from './ci/sdk-release.mjs';

const MANIFEST = 'manifest.json';

function regularFile(file) {
    if (!fs.lstatSync(file).isFile()) throw new Error(`Expected a regular file: ${file}`);
    return file;
}

function digestTree(directory) {
    if (!fs.lstatSync(directory).isDirectory()) throw new Error(`Expected a directory: ${directory}`);
    const files = new Map();
    function visit(current, relative = '') {
        for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
            const name = relative ? `${relative}/${entry.name}` : entry.name;
            const file = path.join(current, entry.name);
            if (entry.isDirectory()) visit(file, name);
            else files.set(name, sha256(regularFile(file)));
        }
    }
    visit(directory);
    return Object.fromEntries(files);
}

function javaIdentity(javaHome) {
    const home = fs.realpathSync(javaHome);
    const release = fs.readFileSync(path.join(home, 'release'), 'utf8');
    if (!/^JAVA_VERSION="25(?:[.+"-])/m.test(release)) throw new Error('AOT cache requires an existing JDK 25');
    const executable = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    const vm = process.platform === 'win32' ? 'bin/server/jvm.dll'
        : process.platform === 'darwin' ? 'lib/server/libjvm.dylib' : 'lib/server/libjvm.so';
    return {
        home, executable, platform: process.platform, arch: process.arch,
        files: Object.fromEntries(['release', 'lib/modules', path.relative(home, executable), vm]
            .map(name => [name, sha256(regularFile(path.join(home, name)))])),
    };
}

function runJava(executable, args, { cwd, env = process.env, training = false } = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(executable, args, { cwd, env, stdio: 'inherit', windowsHide: true,
            detached: process.platform !== 'win32' });
        let cancelled = false;
        function stop() {
            cancelled = true;
            if (!child.pid) return;
            try {
                // AOT 组装会创建第二个 JVM；取消时一起结束本次拥有的进程树。
                if (process.platform !== 'win32') process.kill(-child.pid, 'SIGTERM');
                else if (training) execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
                // Windows 正常运行接收同一控制台的 Ctrl+C，等待 Java 关闭钩子，不能强杀数据库进程。
            } catch (error) { if (error.code !== 'ESRCH' && child.exitCode === null) child.kill(); }
        }
        function cleanup() {
            process.off('SIGINT', stop);
            process.off('SIGTERM', stop);
        }
        process.on('SIGINT', stop);
        process.on('SIGTERM', stop);
        child.once('error', error => { cleanup(); reject(error); });
        child.once('close', (code, signal) => {
            cleanup();
            if (cancelled || signal || code !== 0) reject(new Error(`Java failed: ${cancelled ? 'cancelled' : signal ?? code}`));
            else resolve();
        });
    });
}

export function isolatedEnvironment(environment) {
    // 训练不继承应用配置或 JVM 注入参数，避免写到调用者的真实实例。
    return Object.fromEntries(Object.entries(environment).filter(([key]) =>
        /^(PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|LANG|LC_ALL)$/i.test(key)));
}

export async function prepareCache({ jar, javaHome, cacheDir }) {
    const source = fs.realpathSync(regularFile(path.resolve(jar)));
    const java = javaIdentity(javaHome);
    const sourceSha256 = sha256(source);
    fs.mkdirSync(cacheDir); // 仅创建新目录；不得覆盖已有缓存或用户文件。
    const root = fs.realpathSync(cacheDir);
    const application = path.join(root, 'application');
    const training = path.join(root, 'training');
    const cache = path.join(root, 'application.aot');
    const env = isolatedEnvironment(process.env);
    fs.mkdirSync(training);
    const started = performance.now();
    let manifest;
    try {
        console.log('AOT prepare: extracting application');
        await runJava(java.executable, ['-Djarmode=tools', '-jar', source, 'extract', '--destination', application], { cwd: training, env, training: true });
        const entry = path.basename(source);
        const extracted = regularFile(path.join(application, entry));
        const isolated = ['-Djava.awt.headless=true', '-Dpixivdownload.instance-dir=instance'];
        console.log('AOT prepare: initializing isolated training instance');
        await runJava(java.executable, [...isolated, '-jar', extracted, '--setup', '--username=aot-training',
            `--password=${randomUUID()}`, '--mode=solo', '--proxy-enabled=false'], { cwd: training, env, training: true });
        console.log('AOT prepare: training and assembling cache');
        await runJava(java.executable, [...isolated, `-XX:AOTCacheOutput=${cache}`, '-Dspring.context.exit=onRefresh',
            '-jar', extracted, '--no-gui', '--server.port=0'], { cwd: training, env, training: true });
        if (sha256(source) !== sourceSha256 || !isDeepStrictEqual(javaIdentity(javaHome), java)) {
            throw new Error('Application or JDK changed during training');
        }
        manifest = { schemaVersion: 1, root, sourceSha256, java, entry,
            files: digestTree(application), cacheSha256: sha256(regularFile(cache)) };
    } finally {
        // 只清理本次新建目录中的训练数据；失败产物保留，但没有清单就不能复用。
        fs.rmSync(training, { recursive: true, force: true });
    }
    fs.writeFileSync(path.join(root, MANIFEST), `${JSON.stringify(manifest, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
    console.log(`AOT prepare: ready, elapsedMs=${Math.round(performance.now() - started)}, cacheBytes=${fs.statSync(cache).size}`);
    return manifest;
}

export function validateCache({ jar, javaHome, cacheDir }) {
    const root = fs.realpathSync(cacheDir);
    const file = regularFile(path.join(root, MANIFEST));
    if (fs.statSync(file).size > 1024 * 1024) throw new Error('AOT manifest exceeds 1 MiB');
    const manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (manifest.schemaVersion !== 1 || manifest.root !== root
        || typeof manifest.entry !== 'string' || manifest.entry !== path.basename(manifest.entry)) {
        throw new Error('AOT cache layout changed; prepare a new cache');
    }
    const java = javaIdentity(javaHome);
    if (!isDeepStrictEqual(manifest.java, java)) throw new Error('AOT cache JDK changed; prepare a new cache');
    if (sha256(regularFile(jar)) !== manifest.sourceSha256) throw new Error('AOT cache application changed; prepare a new cache');
    const application = path.join(root, 'application');
    if (!isDeepStrictEqual(digestTree(application), manifest.files)) throw new Error('AOT extracted files changed; prepare a new cache');
    const cache = regularFile(path.join(root, 'application.aot'));
    if (sha256(cache) !== manifest.cacheSha256) throw new Error('AOT cache content changed; prepare a new cache');
    return { java: java.executable, jar: regularFile(path.join(application, manifest.entry)), cache };
}

export async function main(args = process.argv.slice(2)) {
    const command = args.shift();
    if (!command || command === '--help') {
        console.log('Usage: node scripts/aot-cache.mjs <prepare|run> --jar <boot.jar> --java-home <JDK25> --cache-dir <directory> [--strict] [-- <application args>]');
        console.log('Prepare requires a new directory. Run uses the current instance directory and configuration. --strict rejects JVM cache fallback; use it for verification.');
        return;
    }
    const { values, positionals } = parseArgs({ args, allowPositionals: true, options: {
        jar: { type: 'string' }, 'java-home': { type: 'string' }, 'cache-dir': { type: 'string' }, strict: { type: 'boolean' },
    } });
    if (!['prepare', 'run'].includes(command) || !values.jar || !values['java-home'] || !values['cache-dir']
        || (command === 'prepare' && (values.strict || positionals.length))) throw new Error('Invalid arguments; use --help');
    const options = { jar: path.resolve(values.jar), javaHome: path.resolve(values['java-home']), cacheDir: path.resolve(values['cache-dir']) };
    if (command === 'prepare') return prepareCache(options);
    const validated = validateCache(options);
    console.log('AOT run: content verified; JVM cache diagnostics follow');
    await runJava(validated.java, [`-XX:AOTMode=${values.strict ? 'on' : 'auto'}`, `-XX:AOTCache=${validated.cache}`,
        '-Xlog:aot=info', '-jar', validated.jar, ...positionals]);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    main().catch(error => { console.error(`AOT failed: ${error.message}`); process.exitCode = 1; });
}
