import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { isolatedEnvironment, validateCache } from '../aot-cache.mjs';

const [jarArgument, javaHomeArgument, workArgument] = process.argv.slice(2);
assert.ok(jarArgument && javaHomeArgument && workArgument, 'Expected boot JAR, JDK 25+ and new work directory');
const jar = path.resolve(jarArgument), javaHome = path.resolve(javaHomeArgument), work = path.resolve(workArgument);
const tool = fileURLToPath(new URL('../aot-cache.mjs', import.meta.url));
fs.mkdirSync(work);
const cacheDir = path.join(work, 'cache');
const options = { jar, javaHome, cacheDir };
const common = ['--jar', jar, '--java-home', javaHome, '--cache-dir', cacheDir];
// 隔离真实实例和 JVM 注入参数；验收日志直接写文件，不缓存整个 JVM 输出。
const env = isolatedEnvironment(process.env);
function output(name) {
    const log = path.join(work, name + '.log');
    assert.ok(fs.statSync(log).size <= 1024 * 1024, name + ' startup log exceeds inspection budget');
    return fs.readFileSync(log, 'utf8');
}
function tail(name) {
    const file = fs.openSync(path.join(work, name + '.log'), 'r');
    try {
        const size = fs.fstatSync(file).size, bytes = Buffer.alloc(Math.min(size, 8192));
        fs.readSync(file, bytes, 0, bytes.length, Math.max(0, size - bytes.length));
        return bytes.toString('utf8');
    } finally { fs.closeSync(file); }
}
function invoke(name, command, args, extraEnvironment = {}) {
    const log = path.join(work, `${name}.log`);
    const descriptor = fs.openSync(log, 'wx');
    const start = performance.now();
    console.log(`AOT acceptance: ${name}`);
    let result;
    try { result = spawnSync(command, args, { cwd: work, env: { ...env, ...extraEnvironment },
        stdio: ['ignore', descriptor, descriptor], windowsHide: true }); }
    finally { fs.closeSync(descriptor); }
    if (result.status !== 0) {
        console.error(tail(name));
    }
    assert.equal(result.status, 0, `${name} failed; inspect ${log}: ${result.error?.message ?? result.signal ?? result.status}`);
    return Math.round(performance.now() - start);
}
const prepareMs = invoke('prepare', process.execPath, [tool, 'prepare', ...common]);
const validated = validateCache(options);
const flags = ['-Djava.awt.headless=true', '-Dpixivdownload.instance-dir=instance'];
invoke('setup', validated.java, [...flags, '-jar', validated.jar, '--setup', '--username=aot-acceptance',
    `--password=${randomUUID()}`, '--mode=solo', '--proxy-enabled=false']);
const normalMs = invoke('normal', validated.java, [...flags, '-Dspring.context.exit=onRefresh',
    '-jar', validated.jar, '--no-gui', '--server.port=0']);
const cachedMs = invoke('cached', process.execPath, [tool, 'run', ...common, '--strict', '--', '--no-gui', '--server.port=0'],
    { JAVA_TOOL_OPTIONS: [...flags, '-Dspring.context.exit=onRefresh'].join(' ') });
// onRefresh 在常规 Started 日志之前退出；同时核对上下文初始化与 JVM 映射。
for (const name of ['normal', 'cached']) {
    const text = output(name);
    assert.ok(/Root WebApplicationContext: initialization completed/.test(text), `${name} did not initialize the context`);
    if (name === 'cached') assert.ok(/Mapped static\s+region/.test(text), 'JVM did not map the AOT cache');
}
validateCache(options);
const launcher = 'top.sywyar.pixivdownload.startup.DistributionLauncher';
const distributedFlags = [...flags, '-Dspring.context.exit=onRefresh', '-Xlog:aot=info'];
const distributed = [...distributedFlags, '-cp', validated.jar, launcher, 'run', '--no-gui', '--server.port=0'];
invoke('distribution-record', validated.java, distributed);
const distributedCache = path.join(work, 'state/aot/application.aot');
assert.ok(fs.statSync(distributedCache).size > 0, 'Java distribution must prepare its cache');
const distributionCachedMs = invoke('distribution-cached', validated.java, distributed);
const distributedOutput = output('distribution-cached');
assert.match(distributedOutput, /AOT distribution: using verified cache/);
assert.match(distributedOutput, /Mapped static\s+region/);
invoke('distribution-strict', validated.java, [...distributedFlags, '-XX:AOTMode=on', `-XX:AOTCache=${distributedCache}`,
    '-cp', validated.jar, 'top.sywyar.pixivdownload.gui.GuiLauncher', '--no-gui', '--server.port=0']);
fs.writeFileSync(distributedCache, 'damaged cache', 'utf8');
fs.writeFileSync(path.join(work, 'state/aot/recording.aot'), 'interrupted recording', 'utf8');
fs.writeFileSync(path.join(work, 'state/aot/recording.aot.config'), 'interrupted configuration', 'utf8');
invoke('distribution-repair', validated.java, distributed);
assert.match(tail('distribution-repair'), /AOT distribution: cache ready/);
invoke('distribution-jvm-options', validated.java, distributed, {JAVA_TOOL_OPTIONS: '-XX:AOTMode=off'});
assert.match(output('distribution-jvm-options'), /without managed cache/);
invoke('distribution-environment-record', validated.java, distributed, {JAVA_TOOL_OPTIONS: '-Daot.acceptance.environment=true'});
invoke('distribution-environment-cached', validated.java, distributed, {JAVA_TOOL_OPTIONS: '-Daot.acceptance.environment=true'});
assert.match(output('distribution-environment-cached'), /AOT distribution: using verified cache/);
assert.match(output('distribution-environment-cached'), /Mapped static\s+region/);
const cacheRoot = path.dirname(distributedCache), savedCache = path.join(work, 'saved-cache');
fs.renameSync(cacheRoot, savedCache);
try {
    fs.writeFileSync(cacheRoot, 'occupied by a file', 'utf8');
    invoke('distribution-cache-unavailable', validated.java, distributed);
    assert.match(output('distribution-cache-unavailable'), /cache unavailable/);
    assert.equal(fs.readFileSync(cacheRoot, 'utf8'), 'occupied by a file');
    fs.unlinkSync(cacheRoot);
    fs.symlinkSync(savedCache, cacheRoot, process.platform === 'win32' ? 'junction' : 'dir');
    invoke('distribution-linked-cache', validated.java, distributed);
    assert.match(output('distribution-linked-cache'), /cache unavailable/);
    assert.ok(!fs.existsSync(path.join(savedCache, 'recording.aot')));
} finally {
    if (fs.existsSync(cacheRoot)) fs.unlinkSync(cacheRoot);
    fs.renameSync(savedCache, cacheRoot);
}
const lockProbe = path.join(work, 'CacheLock.java');
fs.writeFileSync(lockProbe, 'import java.nio.channels.*;import java.nio.file.*;class CacheLock {public static void main(String[] a)throws Exception{try(var c=FileChannel.open(Path.of(a[0]),StandardOpenOption.WRITE);var l=c.lock()){System.out.println("LOCKED");System.out.flush();System.in.read();}}}', 'utf8');
const holder = spawn(validated.java, [lockProbe, path.join(cacheRoot, 'prepare.lock')], {env, windowsHide: true, stdio: ['pipe', 'pipe', 'inherit']});
const holderExit = new Promise((resolve, reject) => {holder.once('error', reject); holder.once('exit', code => code === 0 ? resolve() : reject(new Error('Cache lock fixture failed')));});
try {
    let lockOutput = '';
    await Promise.race([new Promise(resolve => holder.stdout.on('data', chunk => {lockOutput += chunk; if (lockOutput.includes('LOCKED')) resolve();})),
        holderExit.then(() => {throw new Error('Cache lock fixture exited before acquiring the lock');})]);
    invoke('distribution-concurrent', validated.java, distributed);
    assert.match(output('distribution-concurrent'), /another process is preparing/);
} finally { holder.stdin.end('\n'); await holderExit; }
const report = { sourceSha256: JSON.parse(fs.readFileSync(path.join(cacheDir, 'manifest.json'), 'utf8')).sourceSha256,
    prepareMs, normalMs, cachedMs, distributionCachedMs, cacheBytes: fs.statSync(validated.cache).size };
fs.writeFileSync(path.join(work, 'result.json'), JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(`AOT_CACHE_ACCEPTANCE_PASSED ${JSON.stringify(report)}`);
