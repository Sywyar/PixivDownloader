import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { runReleaseChecks } from '../parallel-release-checks.mjs';

test('独立验收同时开始，全部结束后才汇总任一失败', async t => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'parallel-release-test-'));
    t.after(() => fs.rmSync(work, { recursive: true, force: true }));
    const fixture = path.join(work, 'check.mjs');
    fs.writeFileSync(fixture, `
        import fs from 'node:fs';
        import {setTimeout} from 'node:timers/promises';
        const [name, other, code] = process.argv.slice(2);
        fs.writeFileSync(name + '.started', '');
        const deadline = Date.now() + 10000;
        while (!fs.existsSync(other + '.started')) {
            if (Date.now() > deadline) throw new Error('Checks did not overlap');
            await setTimeout(10);
        }
        if (code === '0') await setTimeout(100);
        fs.writeFileSync(name + '.finished', '');
        process.exitCode = Number(code);
    `, 'utf8');
    for (const codes of [[0, 0], [7, 0], [0, 9], [7, 9]]) {
        const root = path.join(work, codes.join('-'));
        fs.mkdirSync(root);
        const checks = ['boundary', 'aot'].map((name, index, names) => ({ name, command: process.execPath,
            args: [fixture, name, names[1 - index], String(codes[index])], cwd: root }));
        if (codes.some(Boolean)) {
            await assert.rejects(runReleaseChecks(checks), error => error instanceof AggregateError
                && error.errors.length === codes.filter(Boolean).length);
        } else await runReleaseChecks(checks);
        for (const name of ['boundary', 'aot']) assert.ok(fs.existsSync(path.join(root, name + '.finished')));
    }
    const marker = path.join(work, 'surviving-check');
    await assert.rejects(runReleaseChecks([
        { name: 'missing tool', command: path.join(work, 'missing-executable'), args: [] },
        { name: 'other check', command: process.execPath, args: ['-e', 'require("fs").writeFileSync(process.argv[1], "done")', marker] },
    ]), AggregateError);
    assert.equal(fs.readFileSync(marker, 'utf8'), 'done');
});

test('取消并行验收会终止所属子进程树并返回失败', { timeout: 30000 }, async t => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'parallel-release-cancel-'));
    t.after(() => fs.rmSync(work, { recursive: true, force: true }));
    const fixture = path.join(work, 'runner.mjs');
    fs.writeFileSync(fixture, `
        import {runReleaseChecks} from ${JSON.stringify(new URL('../parallel-release-checks.mjs', import.meta.url).href)};
        process.on('message', () => process.emit('SIGTERM'));
        const command = 'const {spawn}=require("child_process"); const child=spawn(process.execPath,["-e","setInterval(()=>{},1000)"],{stdio:"ignore"}); require("fs").writeFileSync(process.argv[1],JSON.stringify([process.pid,child.pid])); setInterval(()=>{},1000)';
        try {
            await runReleaseChecks(['first', 'second'].map(name => ({name,command:process.execPath,args:['-e',command,name+'.json']})));
            process.exit(0);
        } catch { process.exit(130); }
    `, 'utf8');
    const runner = spawn(process.execPath, [fixture], { cwd: work, windowsHide: true, stdio: ['ignore', 'inherit', 'inherit', 'ipc'] });
    const exited = new Promise((resolve, reject) => { runner.once('error', reject); runner.once('close', resolve); });
    t.after(() => { if (runner.exitCode === null) runner.kill(); });
    const waitUntil = async predicate => {
        const deadline = Date.now() + 10000;
        while (!predicate()) {
            assert.ok(Date.now() < deadline, 'Owned processes did not reach the expected state');
            await new Promise(resolve => setTimeout(resolve, 20));
        }
    };
    await waitUntil(() => ['first', 'second'].every(name => fs.existsSync(path.join(work, name + '.json'))));
    const pids = ['first', 'second'].flatMap(name => JSON.parse(fs.readFileSync(path.join(work, name + '.json'), 'utf8')));
    runner.send('cancel');
    assert.equal(await exited, 130);
    await waitUntil(() => pids.every(pid => { try { process.kill(pid, 0); return false; } catch (error) { return error.code === 'ESRCH'; } }));
});
