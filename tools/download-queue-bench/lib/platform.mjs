// 浏览器启动参数与浏览器进程 CPU 采样：Windows 通过 PowerShell 读取进程 / 线程累计 CPU，Linux 读取 /proc。
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';

const require = createRequire(import.meta.url);
// 使用仓库根目录开发依赖中的 playwright（在仓库根目录执行 npm ci 安装），不另建依赖。
export const {chromium} = require('playwright');
export const isWindows = process.platform === 'win32';

// 默认使用本机安装的 Edge（Windows）或 Chrome；BROWSER_PATH / BROWSER_CHANNEL 可覆盖。
export function browserLaunch(extra = {}) {
    const options = {headless: false, ...extra};
    if (process.env.BROWSER_PATH) options.executablePath = process.env.BROWSER_PATH;
    else options.channel = process.env.BROWSER_CHANNEL || (isWindows ? 'msedge' : 'chrome');
    return options;
}

function powershell(script) {
    // 中文系统控制台默认输出编码不是 UTF-8，先切换再输出 JSON。
    const encoded = Buffer.from('[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ' + script, 'utf16le').toString('base64');
    const output = execFileSync('powershell.exe',
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded],
        {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true}).trim();
    if (!output) return [];
    const value = JSON.parse(output);
    return Array.isArray(value) ? value : [value];
}

function classify(pid, args) {
    const type = (args.match(/--type=([a-z-]+)/) || [, 'browser'])[1];
    const sub = (args.match(/--utility-sub-type=([\w.]+)/) || [, ''])[1];
    return {pid: Number(pid), type: sub ? `${type}:${sub.split('.').pop()}` : type};
}

// token 是本次独立 profile 目录名中的唯一片段，只匹配本次启动的浏览器进程。
export function browserProcesses(token) {
    if (!/^[A-Za-z0-9_-]+$/.test(token)) throw new Error('unsafe process token: ' + token);
    if (isWindows) {
        // 先按 token 找到本次的浏览器进程，再沿父进程链补齐命令行里不带 profile 路径的子进程。
        return powershell(
            `$all = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, CommandLine);`
            + ` $ids = New-Object 'System.Collections.Generic.HashSet[int]';`
            + ` foreach ($p in $all) { if ($p.CommandLine -and $p.CommandLine.Contains('${token}')) { [void]$ids.Add([int]$p.ProcessId) } };`
            + ` do { $added = $false; foreach ($p in $all) { if (-not $ids.Contains([int]$p.ProcessId) -and $ids.Contains([int]$p.ParentProcessId)) { [void]$ids.Add([int]$p.ProcessId); $added = $true } } } while ($added);`
            + ` $r = @($all | Where-Object { $ids.Contains([int]$_.ProcessId) } | ForEach-Object { [pscustomobject]@{pid = $_.ProcessId; args = [string]$_.CommandLine} });`
            + ` ConvertTo-Json -InputObject $r -Compress`)
            .map(p => classify(p.pid, p.args));
    }
    return execFileSync('ps', ['-eo', 'pid=,args='], {encoding: 'utf8'}).split('\n')
        .map(line => line.trim().match(/^(\d+)\s+(.*)$/))
        .filter(m => m && m[2].includes(token))
        .map(m => classify(m[1], m[2]));
}

let clockTicks = null;
function linuxTicksToMs(ticks) {
    if (clockTicks === null) clockTicks = Number(execFileSync('getconf', ['CLK_TCK'], {encoding: 'utf8'}).trim()) || 100;
    return ticks * 1000 / clockTicks;
}

function linuxCpu(pid) {
    const parse = text => {
        const fields = text.slice(text.lastIndexOf(')') + 2).split(' ');
        return linuxTicksToMs(Number(fields[11]) + Number(fields[12]));
    };
    try {
        const threads = {};
        for (const tid of fs.readdirSync(`/proc/${pid}/task`)) {
            try {
                const text = fs.readFileSync(`/proc/${pid}/task/${tid}/stat`, 'utf8');
                threads[tid] = {name: text.slice(text.indexOf('(') + 1, text.lastIndexOf(')')), ms: parse(text)};
            } catch {}
        }
        return {ms: parse(fs.readFileSync(`/proc/${pid}/stat`, 'utf8')), threads};
    } catch {
        return null;
    }
}

// 返回各进程与各线程的累计 CPU 毫秒数；at 取采样开始与结束的中点。
export function cpuSnapshot(procs) {
    const started = Date.now();
    let rows;
    if (isWindows) {
        const ids = procs.map(p => p.pid).filter(Number.isSafeInteger);
        const raw = ids.length ? powershell(
            `$out = foreach ($id in @(${ids.join(',')})) {`
            + ` $p = Get-Process -Id $id -ErrorAction SilentlyContinue; if ($p) {`
            + ` $t = @{}; foreach ($th in $p.Threads) { try { $t[[string]$th.Id] = $th.TotalProcessorTime.TotalMilliseconds } catch {} };`
            + ` [pscustomobject]@{pid = $id; ms = $p.TotalProcessorTime.TotalMilliseconds; threads = $t} } };`
            + ` ConvertTo-Json -InputObject @($out) -Compress -Depth 4`) : [];
        rows = raw.filter(Boolean).map(r => ({pid: r.pid, type: procs.find(p => p.pid === r.pid)?.type || '?', ms: r.ms,
            threads: Object.fromEntries(Object.entries(r.threads || {}).map(([tid, ms]) => [tid, {name: '', ms}]))}));
    } else {
        rows = procs.map(p => ({...p, ...linuxCpu(p.pid)})).filter(r => r.ms != null);
    }
    return {at: (started + Date.now()) / 2, rows};
}

// 两次采样之间各进程 CPU 占用（单核 100%）与最忙线程占用。
export function cpuDelta(before, after) {
    const seconds = (after.at - before.at) / 1000;
    const pct = ms => +(ms / 10 / seconds).toFixed(1);
    const rows = [];
    for (const p of after.rows) {
        const q = before.rows.find(x => x.pid === p.pid);
        if (!q) continue;
        let top = null;
        for (const [tid, t] of Object.entries(p.threads)) {
            const prev = q.threads[tid];
            if (!prev) continue;
            const d = t.ms - prev.ms;
            if (!top || d > top.ms) top = {name: t.name, ms: d};
        }
        rows.push({pid: p.pid, type: p.type, cpuPct: pct(p.ms - q.ms),
            topThread: top ? {name: top.name, cpuPct: pct(top.ms)} : null});
    }
    return {seconds: +seconds.toFixed(2), rows};
}
