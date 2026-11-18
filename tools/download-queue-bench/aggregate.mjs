// 汇总一次矩阵运行的结果 JSON，输出 Markdown 表格（每格为均值，多次运行时括号内为最小–最大值）。
// 用法：node aggregate.mjs <结果目录>
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const COLUMNS = [
    ['完成/分', r => r.server.completed / r.elapsed * 60],
    ['渲染主线程 %', r => renderer(r)?.topThread?.cpuPct ?? NaN],
    ['渲染进程 %', r => renderer(r)?.cpuPct ?? NaN],
    ['浏览器主线程 %', r => r.cpu.find(p => p.type === 'browser')?.topThread?.cpuPct ?? NaN],
    ['浏览器进程 %', r => r.cpu.find(p => p.type === 'browser')?.cpuPct ?? NaN],
    ['GPU 进程 %', r => r.cpu.find(p => p.type === 'gpu-process')?.cpuPct ?? NaN],
    ['样式 s/分', r => r.cdp.RecalcStyleDuration / r.elapsed * 60],
    ['布局 s/分', r => r.cdp.LayoutDuration / r.elapsed * 60],
    ['长任务 s/分', r => r.page.longtasks.totalMs / 1000 / r.elapsed * 60],
    ['最长长任务 ms', r => r.page.longtasks.maxMs],
    ['最大交互 ms', r => r.page.events.maxMs],
    ['>100ms 交互/分', r => r.page.events.over100 / r.elapsed * 60],
    ['fps', r => r.page.frames.count / r.elapsed],
    ['输入往返 p95 ms', r => r.inputRoundTrip.p95Ms],
    ['行渲染/分', r => callsOf(r, 'queueItemModel', 'queueItemDisplayTitle').calls / r.elapsed * 60],
    ['队列扫描/分', r => callsOf(r, 'currentCardSummary', 'currentFrontItem').calls / r.elapsed * 60],
    ['保存 MB/分', r => r.server.stateMB / r.elapsed * 60]
];

function renderer(r) {
    return r.cpu.filter(p => p.type === 'renderer').sort((a, b) => b.cpuPct - a.cpuPct)[0];
}

function callsOf(r, ...names) {
    const calls = r.page.calls || {};
    for (const name of names) if (calls[name]) return calls[name];
    return {calls: 0};
}

function format(value) {
    if (!Number.isFinite(value)) return '-';
    return Math.abs(value) >= 100 ? value.toFixed(0) : value.toFixed(1);
}

export function aggregate(dir, versions = {}) {
    const groups = new Map();
    for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.json') && !f.startsWith('summary'))) {
        const r = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
        if (!r.opt || !r.cpu) continue;
        const key = `${r.opt.page}|并发${r.opt.concurrent} 间隔${r.opt.interval}s|${r.opt.label}`;
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(r);
    }
    const lines = [];
    const legend = Object.entries(versions).map(([name, v]) => `- ${name}：${v.rev}（${v.sha.slice(0, 10)}）${v.subject}`);
    if (legend.length) lines.push(...legend, '');
    lines.push(`| 页面 | 设置 | 版本 | 次数 | ${COLUMNS.map(c => c[0]).join(' | ')} | 异常 |`);
    lines.push(`|${'---|'.repeat(COLUMNS.length + 5)}`);
    const order = [...groups.keys()].sort();
    for (const key of order) {
        const runs = groups.get(key);
        const [page, setting, label] = key.split('|');
        const cells = COLUMNS.map(([, pick]) => {
            const values = runs.map(pick).filter(Number.isFinite);
            if (!values.length) return '-';
            const mean = values.reduce((s, v) => s + v, 0) / values.length;
            return values.length > 1
                ? `${format(mean)} (${format(Math.min(...values))}–${format(Math.max(...values))})`
                : format(mean);
        });
        const errors = runs.reduce((s, r) => s + r.log.pageerror.length + r.log.csp, 0);
        lines.push(`| ${page === 'alt' ? '新版' : '经典'} | ${setting} | ${label} | ${runs.length} | ${cells.join(' | ')} | ${errors} |`);
    }
    lines.push('', '说明：CPU 以单核 100% 计；“主线程”取该进程内 CPU 最高的线程。“异常”为页面未捕获错误与 CSP 违规次数之和。');
    return lines.join('\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const dir = process.argv[2];
    if (!dir) throw new Error('用法：node aggregate.mjs <结果目录>');
    const manifestFile = path.join(dir, 'versions.json');
    const versions = fs.existsSync(manifestFile) ? JSON.parse(fs.readFileSync(manifestFile, 'utf8')) : {};
    console.log(aggregate(dir, versions));
}
