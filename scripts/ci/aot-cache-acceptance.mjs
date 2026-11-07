import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { isolatedEnvironment, validateCache } from '../aot-cache.mjs';

const [jarArgument, javaHomeArgument, workArgument] = process.argv.slice(2);
assert.ok(jarArgument && javaHomeArgument && workArgument, 'Expected boot JAR, JDK 25 and new work directory');
const jar = path.resolve(jarArgument), javaHome = path.resolve(javaHomeArgument), work = path.resolve(workArgument);
const tool = fileURLToPath(new URL('../aot-cache.mjs', import.meta.url));
fs.mkdirSync(work);
const cacheDir = path.join(work, 'cache');
const options = { jar, javaHome, cacheDir };
const common = ['--jar', jar, '--java-home', javaHome, '--cache-dir', cacheDir];
// 隔离真实实例和 JVM 注入参数；验收日志直接写文件，不缓存整个 JVM 输出。
const env = isolatedEnvironment(process.env);
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
        const input = fs.openSync(log, 'r');
        try {
            const size = fs.fstatSync(input).size;
            const tail = Buffer.alloc(Math.min(size, 8192));
            fs.readSync(input, tail, 0, tail.length, Math.max(0, size - tail.length));
            console.error(tail.toString('utf8'));
        } finally { fs.closeSync(input); }
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
    const log = path.join(work, `${name}.log`);
    assert.ok(fs.statSync(log).size <= 1024 * 1024, `${name} startup log exceeds inspection budget`);
    const output = fs.readFileSync(log, 'utf8');
    assert.ok(/Root WebApplicationContext: initialization completed/.test(output), `${name} did not initialize the context`);
    if (name === 'cached') assert.ok(/Mapped static\s+region/.test(output), 'JVM did not map the AOT cache');
}
validateCache(options);
const report = { sourceSha256: JSON.parse(fs.readFileSync(path.join(cacheDir, 'manifest.json'), 'utf8')).sourceSha256,
    prepareMs, normalMs, cachedMs, cacheBytes: fs.statSync(validated.cache).size };
fs.writeFileSync(path.join(work, 'result.json'), JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(`AOT_CACHE_ACCEPTANCE_PASSED ${JSON.stringify(report)}`);
