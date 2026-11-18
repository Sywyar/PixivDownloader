// 单次测量：真实浏览器 + 指定版本的页面原始文件 + 模拟后端（合成下载事件）。
// 用法：由 matrix.mjs 调用，参数为 JSON 字符串；也可传 @<json 文件路径>。
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {chromium, browserLaunch, browserProcesses, cpuSnapshot, cpuDelta} from './lib/platform.mjs';

const opt = Object.assign({
    page: 'classic', port: 17209, label: 'cand', items: 3000, concurrent: 1, interval: 2, pages: 2,
    imageMs: 800, eventMs: 40, duration: 90, counters: false, trace: 0, invalidation: false, out: null, idBase: 140000000
}, JSON.parse((process.argv[2] || '{}').startsWith('@')
    ? fs.readFileSync(process.argv[2].slice(1), 'utf8') : (process.argv[2] || '{}')));
if (!opt.out) throw new Error('missing out');
fs.mkdirSync(path.dirname(opt.out), {recursive: true});
const BASE = `http://127.0.0.1:${opt.port}`;
const OUT = opt.out;
// 每次运行使用独立的临时 profile；目录名中的唯一片段用于找到本次的浏览器进程。
const processToken = `dqbp${Date.now()}x${process.pid}`;
const userDataDir = path.join(os.tmpdir(), processToken);

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function post(p, body) {
    return (await fetch(BASE + p, {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body || {})})).json();
}

const result = {opt, startedAt: new Date().toISOString()};
await post('/__reset');
await post('/__config', {pages: opt.pages, imageMs: opt.imageMs, eventMs: opt.eventMs});
// 浏览器语言与页面源语言一致，取自模拟后端读取的 locales.json。
const {locale} = await (await fetch(BASE + '/__info')).json();

const context = await chromium.launchPersistentContext(userDataDir, browserLaunch({
    locale, viewport: {width: 1440, height: 820},
    args: [`--lang=${locale}`, '--window-size=1440,900', '--window-position=0,0', '--no-first-run',
        '--disable-background-networking', '--disable-component-update']
}));
result.browserVersion = context.browser()?.version() || null;
const page = context.pages()[0] || await context.newPage();
// 只允许访问本机模拟后端，其它请求一律拦截。
await context.route(url => !String(url).startsWith(BASE), route => route.abort());
const log = {console: [], pageerror: [], requestfailed: [], csp: 0};
page.on('console', m => {
    if (['error', 'warning'].includes(m.type())) log.console.push(`${m.type()}: ${m.text().slice(0, 240)}`);
    if (m.text().startsWith('CSP violation')) log.csp++;
});
page.on('pageerror', e => log.pageerror.push(String(e.stack || e).slice(0, 400)));
page.on('requestfailed', r => { if (r.url().startsWith(BASE)) log.requestfailed.push(`${r.url()} ${r.failure()?.errorText}`); });

await page.addInitScript(() => {
    try {
        localStorage.setItem('pixiv_onboarding_v1', JSON.stringify({status: 'completed', name: '测试用户'}));
    } catch {}
    document.addEventListener('securitypolicyviolation', e => console.error('CSP violation', e.violatedDirective, e.blockedURI));
    const m = window.__dqb = {longtasks: [], events: [], frames: 0, gaps: [], measuring: false};
    try {
        new PerformanceObserver(list => {
            if (!m.measuring) return;
            for (const e of list.getEntries()) { m.longtasks.push(e.duration); (m.ltAt = m.ltAt || []).push([Math.round(e.startTime - (m.t0 || 0)), Math.round(e.duration)]); }
        }).observe({type: 'longtask', buffered: false});
        new PerformanceObserver(list => {
            if (!m.measuring) return;
            for (const e of list.getEntries()) m.events.push({name: e.name, duration: e.duration,
                delay: e.processingStart - e.startTime, id: e.interactionId || 0});
        }).observe({type: 'event', durationThreshold: 16, buffered: false});
    } catch (e) { console.warn('observer unavailable', e); }
    let last = 0;
    const tick = t => {
        if (m.measuring) {
            m.frames++;
            if (last) m.gaps.push(t - last);
        }
        last = t;
        requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
});

const pagePath = opt.page === 'alt' ? '/pixiv-batch-alt.html' : '/pixiv-batch.html';
await page.goto(BASE + pagePath, {waitUntil: 'load'});
await page.waitForFunction(() => typeof state !== 'undefined' && typeof addItemsToQueue === 'function'
    && window.PixivBatch?.queueTypes?.get?.('illust'), null, {timeout: 30000});
await sleep(2500);

// ---- 通过页面自己的批量导入入口入队 ----
const ids = Array.from({length: opt.items}, (_, i) => String(opt.idBase + i));
const enqueueStart = Date.now();
if (opt.page === 'alt') {
    await page.evaluate(() => switchMode('single-import'));
    await page.waitForSelector('#abImportInput', {timeout: 10000});
    await page.evaluate(text => {
        document.getElementById('abImportInput').value = text;
        runImportParse(false);
    }, ids.join('\n'));
    await page.evaluate(() => { if (typeof toggleDock === 'function') toggleDock(true); });
} else {
    await page.evaluate(() => switchMode('single-import'));
    await page.evaluate(text => {
        document.getElementById('single-import-textarea').value = text;
        parseSingleImport();
    }, ids.join('\n'));
}
const rowSel = opt.page === 'alt' ? '#abQueueList .ab-queue-item' : '#queue-list .queue-item';
await page.waitForFunction(([sel, n]) => document.querySelectorAll(sel).length >= n, [rowSel, opt.items], {timeout: 60000});
result.enqueueMs = Date.now() - enqueueStart;
await sleep(1500);

// ---- 通过页面自己的设置模型修改并发与间隔 ----
await page.evaluate(({concurrent, interval}) => {
    state.settings.concurrent = concurrent;
    state.settings.interval = interval;
    state.settings.intervalUnit = 's';
    state.settings.imageDelay = 0;
    state.settings.skipHistory = false;
    const c = document.getElementById('s-concurrent'); if (c) c.value = String(concurrent);
    const i = document.getElementById('s-interval'); if (i) i.value = String(interval);
    if (typeof saveSettings === 'function') saveSettings();
}, {concurrent: opt.concurrent, interval: opt.interval});

// ---- 可选的调用计数：包装页面全局函数，两个版本使用相同的包装 ----
if (opt.counters) {
    await page.evaluate(() => {
        const c = window.__dqb.calls = {};
        const wrap = (name, measure) => {
            const fn = window[name];
            if (typeof fn !== 'function') return;
            c[name] = {calls: 0, scanned: 0};
            window[name] = function (...args) {
                c[name].calls++;
                if (measure) c[name].scanned += measure(args);
                return fn.apply(this, args);
            };
        };
        wrap('queueItemModel');                                       // 经典页行模型（Vue 与命令式回退）
        wrap('queueItemDisplayTitle');                                // 行标题（两版页面）
        wrap('queueProgressSnapshot');                                // 新版页行快照
        wrap('buildQueueItemHtml');                                   // 命令式回退的行 HTML
        wrap('currentFrontItem', a => (a[0] && a[0].length) || 0);    // 当前卡扫描队列
        wrap('currentRemainingCounts', a => (a[0] && a[0].length) || 0);
        wrap('currentCardSummary', a => (a[0] && a[0].length) || 0);  // 仅较新的版本存在
    });
}

// 记录几行不会被触及的待下载行，结束时检查其 DOM 节点是否保留。
await page.evaluate(([sel, alt]) => {
    const rows = Array.from(document.querySelectorAll(sel));
    const pick = [...new Set([0.45, 0.7, 0.95].map(f => Math.floor(rows.length * f)))].filter(i => i > 0);
    window.__dqb.kept = pick.map(i => {
        const el = rows[i];
        const id = alt ? el.dataset.queueId : el.querySelector('[data-queue-remove-id]')?.dataset.queueRemoveId;
        return {id, el};
    });
}, [rowSel, opt.page === 'alt']);

const cdp = await context.newCDPSession(page);
await cdp.send('Performance.enable', {timeDomain: 'threadTicks'});
const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(m => [m.name, m.value]));

const procs = browserProcesses(processToken);
result.processes = procs;
const listBox = await page.locator(opt.page === 'alt' ? '#abQueueList' : '#queue-list').boundingBox();
result.listBox = listBox;

const m0 = await metrics();
const cpu0 = cpuSnapshot(procs);
const srv0 = await (await fetch(BASE + '/__counters')).json();
await page.evaluate(() => { window.__dqb.measuring = true; window.__dqb.t0 = performance.now(); });
const t0 = Date.now();
await page.evaluate(() => handleStart());

// 可选的 trace：开始后 traceAt（默认 5）秒采集，持续 opt.trace 秒。
let traceState = opt.trace ? 'pending' : 'off';
const traceChunks = [];
cdp.on('Tracing.dataCollected', e => traceChunks.push(...e.value));
const traceDone = new Promise(resolve => cdp.on('Tracing.tracingComplete', resolve));
// 运行期间每秒在队列区域移动鼠标并滚动一次，测量输入响应。
const inputLatencies = [];
let wheelDir = 1;
while (Date.now() - t0 < opt.duration * 1000) {
    if (traceState === 'pending' && Date.now() - t0 > (opt.traceAt ?? 5) * 1000) {
        await cdp.send('Tracing.start', {transferMode: 'ReportEvents', traceConfig: {recordMode: 'recordAsMuchAsPossible',
            includedCategories: ['toplevel', 'devtools.timeline', 'disabled-by-default-devtools.timeline', 'v8.execute',
                'v8', 'blink', 'cc', 'disabled-by-default-v8.gc', 'loading', 'netlog'].concat(opt.invalidation ? ['disabled-by-default-devtools.timeline.invalidationTracking', 'disabled-by-default-devtools.timeline.stack'] : [])}});
        traceState = Date.now();
    } else if (typeof traceState === 'number' && Date.now() - traceState > opt.trace * 1000) {
        await cdp.send('Tracing.end');
        await traceDone;
        fs.writeFileSync(OUT.replace(/\.json$/, '.trace.json'), JSON.stringify({traceEvents: traceChunks}));
        traceState = 'done';
    }
    await sleep(1000);
    const x = listBox ? listBox.x + listBox.width / 2 : 1200, y = listBox ? listBox.y + Math.min(200, listBox.height / 2) : 400;
    const s = Date.now();
    await page.mouse.move(x, y);
    await page.mouse.wheel(0, 240 * wheelDir);
    wheelDir = -wheelDir;
    // 经过渲染主线程的往返：下一帧之后才返回
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
    inputLatencies.push(Date.now() - s);
}
if (typeof traceState === 'number') {
    await cdp.send('Tracing.end');
    await traceDone;
    fs.writeFileSync(OUT.replace(/\.json$/, '.trace.json'), JSON.stringify({traceEvents: traceChunks}));
    traceState = 'done';
}
const elapsed = (Date.now() - t0) / 1000;
await page.evaluate(() => { window.__dqb.measuring = false; });
const cpu1 = cpuSnapshot(procs);
const m1 = await metrics();
const srv1 = await (await fetch(BASE + '/__counters')).json();

const page1 = await page.evaluate(([sel, alt]) => {
    const m = window.__dqb;
    const sorted = a => a.slice().sort((x, y) => x - y);
    const pct = (a, p) => a.length ? sorted(a)[Math.min(a.length - 1, Math.floor(a.length * p))] : 0;
    const rows = document.querySelectorAll(sel);
    const keptOk = (m.kept || []).map(k => {
        const now = alt ? document.querySelector(`${sel}[data-queue-id="${k.id}"]`)
            : document.querySelector(`[data-queue-remove-id="${k.id}"]`)?.closest('.queue-item');
        return {id: k.id, connected: k.el.isConnected, same: now === k.el};
    });
    const statusCounts = {};
    state.queue.forEach(q => { statusCounts[q.status] = (statusCounts[q.status] || 0) + 1; });
    return {
        longtasks: {count: m.longtasks.length, totalMs: Math.round(m.longtasks.reduce((s, x) => s + x, 0)),
            maxMs: Math.round(Math.max(0, ...m.longtasks)), p95Ms: Math.round(pct(m.longtasks, 0.95))},
        events: {count: m.events.length, maxMs: Math.round(Math.max(0, ...m.events.map(e => e.duration))),
            p95Ms: Math.round(pct(m.events.map(e => e.duration), 0.95)),
            over100: m.events.filter(e => e.duration > 100).length,
            maxDelayMs: Math.round(Math.max(0, ...m.events.map(e => e.delay)))},
        frames: {count: m.frames, gapsOver50: m.gaps.filter(g => g > 50).length,
            gapsOver100: m.gaps.filter(g => g > 100).length, maxGapMs: Math.round(Math.max(0, ...m.gaps)),
            p99GapMs: Math.round(pct(m.gaps, 0.99))},
        calls: m.calls || null,
        topLongTasks: (m.ltAt || []).slice().sort((a, b) => b[1] - a[1]).slice(0, 6),
        rowsInDom: rows.length,
        domNodes: document.getElementsByTagName('*').length,
        keptRows: keptOk,
        statusCounts,
        currentCard: (document.getElementById(alt ? 'abCurrentCard' : 'current-card')?.textContent || '').replace(/\s+/g, ' ').slice(0, 160),
        heapMB: performance.memory ? +(performance.memory.usedJSHeapSize / 1048576).toFixed(1) : null
    };
}, [rowSel, opt.page === 'alt']);

// 结束：暂停后等待在途作品收尾，确认下载循环停止。
await page.evaluate(() => handlePause());
await page.waitForFunction(() => !state.queue.some(q => q.status === 'downloading'), null, {timeout: 60000}).catch(() => {});
await sleep(3000);
const settled = await page.evaluate(() => {
    const c = {}; state.queue.forEach(q => { c[q.status] = (c[q.status] || 0) + 1; });
    return {statusCounts: c, isRunning: state.isRunning, isPaused: state.isPaused};
});
// 暂停后的空闲 CPU（下载已停止，页面仍保留整份队列）。
const cpuIdle0 = cpuSnapshot(procs); await sleep(10000); const cpuIdle1 = cpuSnapshot(procs);

const d = (k) => +(m1[k] - m0[k]).toFixed(3);
Object.assign(result, {
    elapsed,
    cdp: {TaskDuration: d('TaskDuration'), ScriptDuration: d('ScriptDuration'), LayoutDuration: d('LayoutDuration'),
        RecalcStyleDuration: d('RecalcStyleDuration'), LayoutCount: d('LayoutCount'), RecalcStyleCount: d('RecalcStyleCount'),
        JSHeapUsedMB: +(m1.JSHeapUsedSize / 1048576).toFixed(1), Nodes: m1.Nodes, JSEventListeners: m1.JSEventListeners},
    cpu: cpuDelta(cpu0, cpu1).rows,
    cpuSeconds: cpuDelta(cpu0, cpu1).seconds,
    idleCpu: cpuDelta(cpuIdle0, cpuIdle1).rows,
    server: {completed: srv1.completed - srv0.completed, downloads: srv1.downloads - srv0.downloads,
        sseEvents: srv1.sseEvents - srv0.sseEvents, stateSaves: srv1.stateSaves - srv0.stateSaves,
        stateMB: +((srv1.stateBytes - srv0.stateBytes) / 1048576).toFixed(1), maxStateKB: Math.round(srv1.maxStateBytes / 1024),
        statusPolls: srv1.statusPolls - srv0.statusPolls, unknown: srv1.unknown},
    page: page1, settled,
    inputRoundTrip: {count: inputLatencies.length, maxMs: Math.max(...inputLatencies),
        p95Ms: inputLatencies.slice().sort((a, b) => a - b)[Math.floor(inputLatencies.length * 0.95)] || 0},
    log: {pageerror: log.pageerror, csp: log.csp, requestfailed: log.requestfailed.slice(0, 20),
        console: log.console.filter(l => !/Failed to load resource|拉取导航失败|计划来源|i18n bundle unavailable|AbortError/.test(l)).slice(0, 30),
        consoleTotal: log.console.length}
});
await page.screenshot({path: OUT.replace(/\.json$/, '.png')});
await context.close();
try { fs.rmSync(userDataDir, {recursive: true, force: true, maxRetries: 5, retryDelay: 500}); } catch {}
fs.writeFileSync(OUT, JSON.stringify(result, null, 1));
const r = result;
const renderer = r.cpu.filter(p => p.type === 'renderer').sort((a, b) => b.cpuPct - a.cpuPct)[0];
const browser = r.cpu.find(p => p.type === 'browser');
console.log(`${opt.label} ${opt.page} 并发${opt.concurrent} 间隔${opt.interval}s：完成 ${r.server.completed} 个；`
    + `渲染进程 ${renderer?.cpuPct ?? '?'}%（主线程 ${renderer?.topThread?.cpuPct ?? '?'}%），浏览器进程 ${browser?.cpuPct ?? '?'}%；`
    + `长任务 ${r.page.longtasks.count} 个共 ${r.page.longtasks.totalMs} ms；最大交互 ${r.page.events.maxMs} ms；进程数 ${r.processes.length}`);
