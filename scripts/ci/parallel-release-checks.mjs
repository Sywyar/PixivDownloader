import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export async function runReleaseChecks(checks) {
    const children = new Set();
    let cancelled = false;
    function stop() {
        cancelled = true;
        for (const child of children) {
            if (!child.pid) continue;
            try {
                if (process.platform === 'win32') {
                    execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
                } else process.kill(-child.pid, 'SIGTERM');
            } catch (error) {
                if (error.code !== 'ESRCH' && child.exitCode === null) child.kill();
            }
        }
    }
    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
    try {
        const results = await Promise.allSettled(checks.map(({ name, command, args, cwd }) => new Promise((resolve, reject) => {
            const started = performance.now();
            console.log(`Release verification started: ${name}`);
            const child = spawn(command, args, { cwd, stdio: 'inherit', windowsHide: true,
                detached: process.platform !== 'win32' });
            children.add(child);
            child.once('error', reject);
            child.once('close', (code, signal) => {
                children.delete(child);
                console.log(`Release verification finished: ${name}; exit=${code}; signal=${signal}; elapsedMs=${Math.round(performance.now() - started)}`);
                if (code === 0 && !signal) resolve();
                else reject(new Error(`${name} failed: ${signal ?? code}`));
            });
        })));
        const failures = results.filter(result => result.status === 'rejected').map(result => result.reason);
        if (cancelled) failures.push(new Error('Release verification cancelled'));
        if (failures.length) throw new AggregateError(failures, 'Release verification failed');
    } finally {
        process.off('SIGINT', stop);
        process.off('SIGTERM', stop);
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    assert.ok(['true', 'false'].includes(process.env.VERIFY_COMPOSE_TESTS ?? 'false'), 'Invalid Compose verification mode');
    const hosts = fs.readdirSync('pixivdownload-app/target').filter(name => /^PixivDownload-.*-boot\.jar$/u.test(name));
    assert.equal(hosts.length, 1, 'Expected exactly one boot JAR');
    assert.ok(process.env.JAVA_HOME, 'JAVA_HOME is required');
    const work = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP || os.tmpdir(), 'release-checks-'));
    await runReleaseChecks([
        { name: 'packaged boundaries', command: 'bash', args: ['scripts/ci/verify-release-boundaries.sh'] },
        { name: 'JDK AOT cache', command: process.execPath, args: ['scripts/ci/aot-cache-acceptance.mjs',
            path.resolve('pixivdownload-app/target', hosts[0]), process.env.JAVA_HOME, path.join(work, 'runtime')] },
    ]);
}
