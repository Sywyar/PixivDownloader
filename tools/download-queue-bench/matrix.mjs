// download-queue-bench：下载页长队列压测工具（本文件为测试矩阵入口）。
//
// 用一套合成的长下载队列（默认 3000 项，可用 --items 指定）测试经典下载页与新版下载页在下载过程中的流畅度，
// 可以对比两个 Git 版本，也可以只测一个版本。页面文件从指定提交原样导出；后端是 server.mjs
// 模拟服务，下载为合成进度事件，不访问 Pixiv，浏览器内所有非 127.0.0.1 请求都会被拦截。
// Windows 默认使用本机 Edge，每次运行使用临时独立配置；Linux 默认使用 Chrome，
// 可用环境变量 BROWSER_PATH 指定浏览器程序，没有图形界面时用 xvfb-run 启动。
//
// 准备：在仓库根目录执行 npm ci（使用根目录开发依赖中的 playwright），然后在本目录执行
//   node check.mjs                                    环境自检：Edge 启动、进程识别与 CPU 采样
// 选择版本（版本可写分支、标签、提交号或 origin/master~1；建议先 git fetch origin）：
//   node prepare.mjs --base origin/master --cand <分支>   对比两个版本（base 为对照）
//   node prepare.mjs --only origin/master                只测一个版本
//   两边写同一版本可得到测量本身的波动范围。工具不在仓库内时加 --repo <仓库路径>。
// 运行：
//   node matrix.mjs --quick    试跑：每种组合 1 次、每次 30 秒
//   node matrix.mjs            正式：每种组合 2 次、每次 60 秒，两版本交替先后
//   node visual-pair.mjs       界面检查：多种窗口、主题与字号下的几何、聚焦、Tab、页内查找（同样支持 --items）
// 导出的版本与结果写入 <仓库>/target/download-queue-bench/（已被忽略），汇总表为
// results/<时间>/summary.md；原始 JSON 的 page.topLongTasks 记录最长的长任务及其发生时间。
//
// 注意：运行期间不要遮挡、最小化或切走测试窗口，浏览器会降低后台窗口的渲染频率；关闭睡眠，
// 尽量关闭其它占用 CPU 的程序。数字适合比较版本之间的差异，不代表真实下载的绝对值。
// 模拟后端按 5ce999e2 的页面接口编写，版本差距较大时可能需要补充 server.mjs 中的接口。
//
// 参数：node matrix.mjs [--quick] [--repeats 2] [--duration 60] [--items 3000]
//       [--pages classic,alt] [--settings default,fast] [--ports 17208,17209] [--trace 8 [--invalidation]] [--out <目录>]
//   default 设置为并发 1、作品间隔 2 秒（页面默认值），fast 为并发 3、无间隔；
//   --trace 每次运行开始 5 秒后采集指定秒数的 trace，可在 DevTools 的 Performance 面板加载，采集有额外开销；
//   同时加 --invalidation 会记录样式失效来源，用于定位整列表样式重算等问题，开销更大。
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {DATA_DIR, HARNESS_DIR, list, parseArgs, readManifest, startServer, stopServers} from './lib/servers.mjs';
import {aggregate} from './aggregate.mjs';

const SETTINGS = {
    default: {concurrent: 1, interval: 2},   // 页面默认：并发 1、作品间隔 2 秒
    fast: {concurrent: 3, interval: 0}       // 高吞吐：并发 3、无间隔
};
const args = parseArgs(process.argv.slice(2), {});
const quick = args.quick === true;
const repeats = Number(args.repeats ?? (quick ? 1 : 2));
const duration = Number(args.duration ?? (quick ? 30 : 60));
const items = Number(args.items ?? 3000);
if (!Number.isInteger(items) || items < 20) { console.error('--items 需要不小于 20 的整数'); process.exit(1); }
const pages = list(args.pages ?? 'classic,alt');
const settings = list(args.settings ?? 'default,fast');
const ports = list(args.ports ?? '17208,17209').map(Number);
const trace = Number(args.trace ?? 0);
const invalidation = args.invalidation === true;
for (const s of settings) if (!SETTINGS[s]) throw new Error('未知设置：' + s + '（可用：default、fast）');
for (const p of pages) if (!['classic', 'alt'].includes(p)) throw new Error('未知页面：' + p + '（可用：classic、alt）');

const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const out = path.resolve(args.out ?? path.join(DATA_DIR, 'results', stamp));
fs.mkdirSync(out, {recursive: true});
const manifest = readManifest();
fs.writeFileSync(path.join(out, 'versions.json'), JSON.stringify(manifest.versions, null, 1), 'utf8');

const servers = [];
const cleanup = () => stopServers(servers);
process.on('SIGINT', () => { cleanup(); process.exit(130); });
process.on('exit', cleanup);

const names = Object.keys(manifest.versions);
const versionPorts = Object.fromEntries(names.map((name, i) => [name, ports[i] ?? ports[0] + i]));
for (const name of names) servers.push(await startServer(manifest.versions[name].dir, versionPorts[name]));
console.log(names.map(name => `${name} = ${manifest.versions[name].rev}（${manifest.versions[name].sha.slice(0, 10)}）`).join('，'));
console.log(`共 ${repeats * pages.length * settings.length * names.length} 次，每次约 ${duration + 45} 秒；结果目录 ${out}`);

const failures = [];
let index = 0;
for (let rep = 1; rep <= repeats; rep++) {
    for (const page of pages) {
        for (const setting of settings) {
            // 两个版本交替先后，减少机器状态随时间漂移带来的偏差。
            const order = rep % 2 === 1 ? names : [...names].reverse();
            for (const label of order) {
                index++;
                const options = {page, port: versionPorts[label], label, items, duration, counters: true, trace, invalidation,
                    ...SETTINGS[setting], out: path.join(out, `${page}-${setting}-${label}-r${rep}.json`)};
                process.stdout.write(`[${index}] `);
                const run = spawnSync(process.execPath, [path.join(HARNESS_DIR, 'run.mjs'), JSON.stringify(options)],
                    {stdio: 'inherit', windowsHide: false});
                if (run.status !== 0) failures.push(`${page}-${setting}-${label}-r${rep}（退出码 ${run.status}）`);
            }
        }
    }
}
cleanup();

const summary = aggregate(out, manifest.versions);
fs.writeFileSync(path.join(out, 'summary.md'), summary + '\n', 'utf8');
console.log('\n' + summary);
if (failures.length) console.log('\n失败的运行：\n- ' + failures.join('\n- '));
console.log(`\n汇总已写入 ${path.join(out, 'summary.md')}`);
