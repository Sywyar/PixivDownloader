// 环境自检：确认 Node、Git、浏览器启动、浏览器进程识别与 CPU 采样都可用。正式测试前先运行一次。
// 用法：node check.mjs
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {chromium, browserLaunch, browserProcesses, cpuSnapshot, cpuDelta, isWindows} from './lib/platform.mjs';
import {DATA_DIR} from './lib/servers.mjs';

const ok = (name, detail) => console.log(`[通过] ${name}${detail ? '：' + detail : ''}`);
const fail = (name, error) => { console.log(`[失败] ${name}：${error && error.message || error}`); process.exitCode = 1; };

const major = Number(process.versions.node.split('.')[0]);
major >= 18 ? ok('Node 版本', process.versions.node) : fail('Node 版本', `${process.versions.node}，需要 18 或更高`);
try { ok('Git', execFileSync('git', ['--version'], {encoding: 'utf8'}).trim()); } catch (e) { fail('Git', e); }
const manifest = path.join(DATA_DIR, 'trees', 'manifest.json');
fs.existsSync(manifest) ? ok('版本导出', manifest) : console.log(`[提示] 尚未导出版本，正式测试前运行 node prepare.mjs（数据目录 ${DATA_DIR}）`);

const token = `dqbcheck${Date.now()}x${process.pid}`;
const profile = path.join(os.tmpdir(), token);
let context;
try {
    context = await chromium.launchPersistentContext(profile, browserLaunch({args: ['--no-first-run']}));
    ok('浏览器启动', `${isWindows ? 'Edge' : '浏览器'} ${context.browser()?.version() ?? ''}`.trim());
    const page = context.pages()[0] || await context.newPage();
    await page.setContent('<p>download-queue-bench check</p>');
    // 让页面跑一段计算，确认 CPU 采样能看到渲染进程的占用。
    const procs = browserProcesses(token);
    procs.length ? ok('浏览器进程识别', procs.map(p => p.type).join('、')) : fail('浏览器进程识别', '没有找到本次启动的浏览器进程');
    const before = cpuSnapshot(procs);
    await page.evaluate(() => { const end = performance.now() + 1500; let x = 0; while (performance.now() < end) x += Math.sqrt(x + 1); return x; });
    const after = cpuSnapshot(procs);
    const delta = cpuDelta(before, after);
    const renderer = delta.rows.filter(r => r.type === 'renderer').sort((a, b) => b.cpuPct - a.cpuPct)[0];
    if (renderer && renderer.cpuPct > 20 && renderer.topThread) {
        ok('CPU 采样', `窗口 ${delta.seconds}s，渲染进程 ${renderer.cpuPct}%，最忙线程 ${renderer.topThread.cpuPct}%`);
    } else {
        fail('CPU 采样', `未得到有效的渲染进程占用：${JSON.stringify(delta.rows)}`);
    }
} catch (error) {
    fail('浏览器自检', error);
} finally {
    if (context) await context.close();
    try { fs.rmSync(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 500}); } catch {}
}
console.log(process.exitCode ? '\n自检未通过，请把上面的输出发给我。' : '\n自检通过，可以开始测试。');
