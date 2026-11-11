import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('Compose 验收真实传递 Gradle 参数并拒绝旧报告、失败、零测试和跳过', { skip: process.platform !== 'win32' }, t => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'compose-verification-'));
    t.after(() => fs.rmSync(work, { recursive: true, force: true }));
    const scripts = path.join(work, 'scripts/ci'), plugin = path.join(work, 'pixivdownload-plugin-gui-compose');
    const reports = path.join(plugin, 'build/test-results/test');
    fs.mkdirSync(scripts, { recursive: true });
    fs.mkdirSync(reports, { recursive: true });
    const script = path.join(scripts, 'verify-compose-tests.ps1');
    fs.copyFileSync(fileURLToPath(new URL('../verify-compose-tests.ps1', import.meta.url)), script);
    fs.writeFileSync(path.join(plugin, 'gradlew.bat'), '@echo off\r\nnode "%~dp0gradle-fixture.mjs" %*\r\nexit /b %ERRORLEVEL%\r\n', 'utf8');
    fs.writeFileSync(path.join(plugin, 'gradle-fixture.mjs'), `
        import fs from 'node:fs';
        fs.writeFileSync('arguments.json', JSON.stringify(process.argv.slice(2)));
        const mode = process.env.COMPOSE_FIXTURE_MODE;
        if (mode === 'exit') process.exit(17);
        if (mode !== 'missing') fs.writeFileSync('build/test-results/test/TEST-fixture.xml',
            '<testsuite tests="' + (mode === 'zero' ? 0 : 3) + '" failures="' + (mode === 'failure' ? 1 : 0)
            + '" errors="' + (mode === 'error' ? 1 : 0) + '" skipped="' + (mode === 'skip' ? 1 : 0) + '"/>');
    `, 'utf8');
    for (const shell of ['powershell', 'pwsh']) {
        for (const mode of ['success', 'exit', 'missing', 'zero', 'failure', 'error', 'skip']) {
            fs.writeFileSync(path.join(reports, 'TEST-stale.xml'), '<testsuite tests="100" failures="0" errors="0" skipped="0"/>', 'utf8');
            const result = spawnSync(shell, ['-NoProfile', '-File', script], { cwd: work, encoding: 'utf8',
                env: { ...process.env, COMPOSE_FIXTURE_MODE: mode }, windowsHide: true });
            assert.ifError(result.error);
            assert.equal(result.status === 0, mode === 'success', `${shell}: ${mode}\n${result.stderr}`);
            if (mode === 'exit') assert.equal(result.status, 17);
            assert.deepEqual(JSON.parse(fs.readFileSync(path.join(plugin, 'arguments.json'), 'utf8')), ['--daemon', 'mavenTest']);
            assert.ok(!fs.existsSync(path.join(reports, 'TEST-stale.xml')));
        }
    }
});
