// 对已导出的版本运行 visual.mjs：两个版本时比较几何、滚动高度与交互结果，单版本时输出该版本的检查报告。
// 截图留在各版本目录供人工查看。用法：node visual-pair.mjs [--items 3000] [--ports 17208,17209] [--out <目录>]
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {DATA_DIR, HARNESS_DIR, list, parseArgs, readManifest, startServer, stopServers} from './lib/servers.mjs';

const args = parseArgs(process.argv.slice(2), {});
const ports = list(args.ports ?? '17208,17209').map(Number);
const items = Number(args.items ?? 3000);
if (!Number.isInteger(items) || items < 20) { console.error('--items 需要不小于 20 的整数'); process.exit(1); }
const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const out = path.resolve(args.out ?? path.join(DATA_DIR, 'results', `visual-${stamp}`));
const manifest = readManifest();
const names = Object.keys(manifest.versions);
const servers = [];
const cleanup = () => stopServers(servers);
process.on('SIGINT', () => { cleanup(); process.exit(130); });
process.on('exit', cleanup);

const versionPorts = Object.fromEntries(names.map((name, i) => [name, ports[i] ?? ports[0] + i]));
for (const name of names) servers.push(await startServer(manifest.versions[name].dir, versionPorts[name]));
for (const name of names) {
    console.log(`运行 ${name}（${manifest.versions[name].sha.slice(0, 10)}），约 4～6 分钟……`);
    const run = spawnSync(process.execPath, [path.join(HARNESS_DIR, 'visual.mjs'), String(versionPorts[name]), path.join(out, name), String(items)],
        {stdio: ['ignore', 'ignore', 'inherit']});
    if (run.status !== 0) { cleanup(); throw new Error(`${name} 运行失败，退出码 ${run.status}`); }
}
cleanup();

const results = Object.fromEntries(names.map(name =>
    [name, JSON.parse(fs.readFileSync(path.join(out, name, 'results.json'), 'utf8'))]));
const describe = name => `- ${name}：${manifest.versions[name].rev}（${manifest.versions[name].sha.slice(0, 10)}）${manifest.versions[name].subject}`;
const lines = [];

if (names.length === 2) {
    const [b, c] = names;
    const B = results[b], C = results[c];
    const nums = box => box.replace(/^[A-Za-z0-9-]+:/, '').split(',').map(Number);
    lines.push('# 界面与交互对比', '', describe(b), describe(c), '',
        `| 条件 | 列表高度 ${b} / ${c} | 滚动总高 ${b} / ${c} | 队首几何差异 | 滚到底几何差异 | 页面异常 ${b} / ${c} |`, '|---|---|---|---|---|---|');
    for (const key of Object.keys(B)) {
        const x = B[key], y = C[key];
        const geo = g => {
            if (x[g].boxes.length !== y[g].boxes.length) return `元素数不同 ${x[g].boxes.length}/${y[g].boxes.length}`;
            const pairs = x[g].boxes.map((box, i) => [box, y[g].boxes[i]]);
            const differing = pairs.filter(([p, q]) => p !== q);
            const maxDelta = Math.max(0, ...differing.flatMap(([p, q]) => nums(p).map((v, i) => Math.abs(v - nums(q)[i]))));
            return differing.length ? `${differing.length}/${pairs.length} 个，最大 ${maxDelta.toFixed(1)} px` : `一致（${pairs.length} 个）`;
        };
        lines.push(`| ${key} | ${x.geoTop.list.split(',')[3]} / ${y.geoTop.list.split(',')[3]} | ${x.scroll.after} / ${y.scroll.after} | ${geo('geoTop')} | ${geo('geoBottom')} | ${x.errors.length} / ${y.errors.length} |`);
    }
    lines.push('', `| 条件 | 检查 | ${b} | ${c} |`, '|---|---|---|---|');
    for (const key of Object.keys(B).filter(k => B[k].focus)) {
        const x = B[key], y = C[key];
        lines.push(`| ${key} | 聚焦第 ${x.targets.focusRow} 行按钮 | ${JSON.stringify(x.focus)} | ${JSON.stringify(y.focus)} |`);
        lines.push(`| ${key} | Tab 经过的行 | ${x.tab.rows.join(',')} | ${y.tab.rows.join(',')} |`);
        lines.push(`| ${key} | window.find 第 ${x.targets.findRow} 行 | ${JSON.stringify(x.find)} | ${JSON.stringify(y.find)} |`);
        lines.push(`| ${key} | 逐屏滚动 | ${JSON.stringify(x.scrollSteps)} | ${JSON.stringify(y.scrollSteps)} |`);
        if (x.hintMargins) lines.push(`| ${key} | 提示弹层子元素 margin-top | ${x.hintMargins.join(' ')} | ${y.hintMargins.join(' ')} |`);
    }
} else {
    const [only] = names;
    const R = results[only];
    lines.push('# 界面与交互检查（单版本）', '', describe(only), '',
        '| 条件 | 列表高度 | 滚动总高（滚到底前 / 后） | 队首可见行 | 滚到底可见行 | 最后一行在视口内 | 页面异常 |', '|---|---|---|---|---|---|---|');
    for (const [key, r] of Object.entries(R)) {
        lines.push(`| ${key} | ${r.geoTop.list.split(',')[3]} | ${r.scroll.before} / ${r.scroll.after} | ${r.geoTop.rows} | ${r.geoBottom.rows} | ${r.lastVisible.inViewport ? '是' : '否'} | ${r.errors.length} |`);
    }
    lines.push('', '| 条件 | 检查 | 结果 | 期望 |', '|---|---|---|---|');
    for (const [key, r] of Object.entries(R).filter(([, v]) => v.focus)) {
        lines.push(`| ${key} | 聚焦第 ${r.targets.focusRow} 行按钮 | ${JSON.stringify(r.focus)} | focused、renderedAfter、inView 均为 true |`);
        lines.push(`| ${key} | Tab 经过的行 | ${r.tab.rows.join(',')} | 行号依次递增，allInView 为 true（实际 ${r.tab.allInView}） |`);
        lines.push(`| ${key} | window.find 第 ${r.targets.findRow} 行 | ${JSON.stringify(r.find)} | found 为 true、rowIndex 为 ${r.targets.findRow} |`);
        lines.push(`| ${key} | 逐屏滚动 | ${JSON.stringify(r.scrollSteps)} | 能滚到底；distinctHeights 越接近 1，滚动条越稳定 |`);
        if (r.hintMargins) lines.push(`| ${key} | 提示弹层子元素 margin-top | ${r.hintMargins.join(' ')} | 0px 8px 0px 0px 8px |`);
    }
}
lines.push('', `截图：${names.map(name => path.join(out, name)).join(' 与 ')} 下的 PNG。`,
    '说明：条件中 wide / narrow / short 为宽、窄、矮窗口，dark 为深色主题，bigtext 为 200% 字号。'
    + 'focus.skippedBefore 为 true 表示聚焦前该行内容处于 content-visibility 跳过状态；'
    + '新版页列表高度在行渲染前按占位估算，可能有亚像素级累计差异。');
const reportName = names.length === 2 ? 'compare.md' : 'report.md';
fs.writeFileSync(path.join(out, reportName), lines.join('\n') + '\n', 'utf8');
console.log(lines.join('\n'));
console.log(`\n${names.length === 2 ? '对比' : '报告'}已写入 ${path.join(out, reportName)}`);
