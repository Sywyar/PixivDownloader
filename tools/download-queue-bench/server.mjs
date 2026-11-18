// 下载工作台页面的模拟后端（长队列压测用）。原样提供一个导出版本的静态文件并附带生产 CSP 响应头，
// 应答经典页与新版页实际调用的接口。下载为合成事件，不访问 Pixiv；进度事件字段与真实后端的 SSE 载荷一致。
// 用法：由 matrix.mjs / visual-pair.mjs 启动：node server.mjs <导出目录> <端口>
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {URL} from 'node:url';
import crypto from 'node:crypto';

const [, , treeRoot, portArg] = process.argv;
const PORT = Number(portArg || 17209);
const PLUGIN = path.join(treeRoot, 'pixivdownload-plugin-download-workbench/src/main/resources');
const HOST = path.join(treeRoot, 'pixivdownload-app/src/main/resources');
const STATIC_ROOTS = [path.join(PLUGIN, 'static'), path.join(HOST, 'static')];
const I18N_ROOTS = [path.join(PLUGIN, 'i18n/web'), path.join(HOST, 'i18n/web')];

const CSP = "default-src 'self'; base-uri 'self'; object-src 'none'; script-src 'self'; script-src-attr 'none';"
    + " style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;"
    + " font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob:;"
    + " media-src 'self' blob:; connect-src 'self' https://layout-survey.sywyar.top"
    + " https://us.i.posthog.com https://eu.i.posthog.com"
    + " https://fonts.googleapis.com https://fonts.gstatic.com; frame-src 'self';"
    + " worker-src 'none'; manifest-src 'self'; form-action 'self'; frame-ancestors 'none'";

const MIME = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
    '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.webp': 'image/webp'};

// ---- 模拟参数（通过 POST /__config 修改）----
const sim = {
    pages: 2,            // 每个作品的图片数
    imageMs: 800,        // 每张图片的传输时长
    eventMs: 40,         // 字节进度事件间隔（真实后端按每个百分点、512 KB 或 1 秒发送）
    imageBytes: 2 * 1024 * 1024
};
const counters = {stateSaves: 0, stateBytes: 0, maxStateBytes: 0, sseEvents: 0, downloads: 0, completed: 0,
    statusPolls: 0, unknown: {}};
let serverState = {};
const statuses = new Map();   // 作品 ID → 最新状态载荷
const sseClients = new Set();
const tasks = new Map();      // 任务 ID → {attempt:{attemptId}, phase, updatedAt, finishedAt}
let taskRevision = 1;
function setTask(id, phase) {
    const t = tasks.get(id) || {attempt: {attemptId: id}};
    t.phase = phase; t.updatedAt = new Date().toISOString();
    if (phase === 'COMPLETED') t.finishedAt = Date.now();
    tasks.set(id, t); taskRevision++;
}
let connSeq = 0;

function parseProperties(text) {
    const out = {};
    const lines = text.replace(/^﻿/, '').split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
        let line = lines[i].replace(/^\s+/, '');
        if (!line || line[0] === '#' || line[0] === '!') continue;
        while (/(^|[^\\])(\\\\)*\\$/.test(line) && i + 1 < lines.length) {
            line = line.slice(0, -1) + lines[++i].replace(/^\s+/, '');
        }
        const m = line.match(/^((?:\\.|[^=:\s\\])+)\s*[=:\s]\s*(.*)$/);
        if (!m) { out[unescape(line)] = ''; continue; }
        out[unescape(m[1])] = unescape(m[2]);
    }
    return out;
    function unescape(s) {
        return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, c) => c[0] === 'u' && c.length === 5
            ? String.fromCharCode(parseInt(c.slice(1), 16))
            : ({n: '\n', t: '\t', r: '\r', f: '\f'}[c] ?? c));
    }
}

// 语言标签与资源文件后缀取自被测版本的 locales.json，页面使用源语言。
const LOCALES = JSON.parse(fs.readFileSync(path.join(HOST, 'i18n', 'locales.json'), 'utf8'));
const RESOURCE_SUFFIX = new Map(LOCALES.locales.map(l => [l.tag, l.resourceSuffix ? '_' + l.resourceSuffix : '']));

const bundleCache = new Map();
function bundle(ns, lang) {
    const key = ns + '|' + lang;
    if (bundleCache.has(key)) return bundleCache.get(key);
    const suffix = RESOURCE_SUFFIX.get(lang) ?? '';
    let messages = null;
    for (const root of I18N_ROOTS) {
        const base = path.join(root, ns + '.properties');
        if (!fs.existsSync(base)) continue;
        messages = parseProperties(fs.readFileSync(base, 'utf8'));
        const localized = path.join(root, ns + suffix + '.properties');
        if (suffix && fs.existsSync(localized)) Object.assign(messages, parseProperties(fs.readFileSync(localized, 'utf8')));
        break;
    }
    const result = messages ? {namespace: ns, lang, messages} : null;
    bundleCache.set(key, result);
    return result;
}

function send(res, status, body, type = 'application/json; charset=utf-8', extra = {}) {
    const data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
    res.writeHead(status, {'Content-Type': type, 'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff',
        'Referrer-Policy': 'no-referrer', 'Cache-Control': 'no-store', ...extra});
    res.end(data);
}

function readBody(req) {
    return new Promise(resolve => {
        const chunks = [];
        req.on('data', c => chunks.push(c));
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    });
}

function broadcast(event, data) {
    const frame = `id: ${Date.now()}\nevent: ${event}\ndata: ${typeof data === 'string' ? data : JSON.stringify(data)}\n\n`;
    for (const client of sseClients) client.write(frame);
    if (event === 'download-status') counters.sseEvents++;
}

function payload(artworkId, title, total, fields) {
    const p = Object.assign({artworkId: Number(artworkId), status: '进度更新', message: '下载进度已更新', success: true,
        title, presentationAttributes: {}, currentImageIndex: 0, totalImages: total, downloadedCount: 0,
        completed: false, failed: false, cancelled: false, folderName: null, bookmarkResult: null,
        collectionResult: null, ugoiraProgress: null, imageProgress: null}, fields);
    p.progress = total > 0 ? Math.floor(p.downloadedCount / total * 100) : 0;
    statuses.set(String(artworkId), p);
    return p;
}

function simulateDownload(artworkId, title, total, taskId) {
    counters.downloads++;
    const perImage = Math.max(1, Math.round(sim.imageMs / sim.eventMs));
    let image = 1, step = 0;
    const timer = setInterval(() => {
        if (image > total) {
            clearInterval(timer);
            counters.completed++;
            setTask(taskId, 'COMPLETED');
            broadcast('download-status', payload(artworkId, title, total, {currentImageIndex: total - 1,
                downloadedCount: total, completed: true, message: '下载完成', folderName: String(artworkId),
                imageProgress: {status: 'completed', imageNumber: total, totalImages: total,
                    downloadedBytes: sim.imageBytes, totalBytes: sim.imageBytes, progress: 100}}));
            return;
        }
        step++;
        const bytes = Math.min(sim.imageBytes, Math.round(sim.imageBytes * step / perImage));
        const done = step >= perImage;
        broadcast('download-status', payload(artworkId, title, total, {currentImageIndex: image - 1,
            downloadedCount: done ? image : image - 1,
            imageProgress: {status: 'running', imageNumber: image, totalImages: total, downloadedBytes: bytes,
                totalBytes: sim.imageBytes, progress: Math.min(99, Math.floor(bytes * 100 / sim.imageBytes))}}));
        if (done) { image++; step = 0; }
    }, sim.eventMs);
}

const EXTENSIONS = {epoch: 'dqb-epoch', revision: 1, uiSlots: [], downloadTypes: [{
    contractVersion: 1, type: 'illust', displayNamespace: 'batch', displayI18nKey: 'batch.user.kind-illust',
    order: 10, iconKey: 'image', colorToken: 'pixiv', moduleUrl: '/pixiv-batch/pixiv-queue-type.js',
    acquisitionModes: ['single-import', 'user', 'series', 'search', 'quick'], cancelSupported: true,
    filters: ['illust-extra'], settings: ['media-output'], i18nNamespace: 'batch',
    owner: {pluginId: 'download-workbench', packageId: 'download-workbench', generation: 1, publicationId: 1}}]};

async function api(req, res, url) {
    const p = url.pathname;
    const m = (re) => p.match(re);
    let r;
    if (p === '/__config' && req.method === 'POST') { Object.assign(sim, JSON.parse(await readBody(req))); return send(res, 200, sim); }
    if (p === '/__counters') return send(res, 200, counters);
    if (p === '/__info') return send(res, 200, {locale: LOCALES.sourceLocale});
    if (p === '/__reset' && req.method === 'POST') {
        serverState = {}; statuses.clear(); tasks.clear(); taskRevision++;
        Object.assign(counters, {stateSaves: 0, stateBytes: 0, maxStateBytes: 0, sseEvents: 0, downloads: 0,
            completed: 0, statusPolls: 0, unknown: {}});
        return send(res, 200, {ok: true});
    }
    if (p === '/api/setup/status') return send(res, 200, {setupComplete: true, mode: 'solo', multiModeLimitPage: 0});
    if (p === '/api/auth/check') return send(res, 200, {valid: true});
    if (p === '/api/i18n/meta') {
        const meta = JSON.parse(fs.readFileSync(path.join(HOST, 'static/i18n-static/meta.json'), 'utf8'));
        meta.currentLang = LOCALES.sourceLocale;
        return send(res, 200, meta);
    }
    if ((r = m(/^\/api\/i18n\/messages\/([^/]+)$/))) {
        const b = bundle(decodeURIComponent(r[1]), url.searchParams.get('lang') || LOCALES.sourceLocale);
        return b ? send(res, 200, b) : send(res, 404, {error: 'not found'});
    }
    if (p === '/api/download/extensions') return send(res, 200, EXTENSIONS);
    if (p === '/api/batch/state') {
        if (req.method === 'POST') {
            const body = await readBody(req);
            counters.stateSaves++; counters.stateBytes += body.length;
            counters.maxStateBytes = Math.max(counters.maxStateBytes, body.length);
            serverState = JSON.parse(body).state || {};
            return send(res, 200, {success: true});
        }
        return send(res, 200, {state: serverState});
    }
    if (p === '/api/download/tasks') {
        const now = Date.now();
        for (const [id, t] of tasks) if (t.finishedAt && now - t.finishedAt > 60000) { tasks.delete(id); taskRevision++; }
        return send(res, 200, {epoch: 'dqb-tasks', revision: taskRevision, tasks: Array.from(tasks.values(),
            t => ({attempt: t.attempt, phase: t.phase, updatedAt: t.updatedAt}))});
    }
    if (p === '/api/schedule/sources') return send(res, 200, {epoch: 'dqb-schedule', revision: 1, sources: []});
    if (p === '/api/onboarding/profile') return send(res, 200, {displayName: '测试用户'});
    if (p === '/api/collections') return send(res, 200, {collections: []});
    if (p === '/api/download/status') return send(res, 200, {success: true});
    if ((r = m(/^\/api\/download\/status\/(\d+)$/))) {
        counters.statusPolls++;
        const s = statuses.get(r[1]);
        return send(res, 200, s || {success: false});
    }
    if ((r = m(/^\/api\/downloaded\/(\d+)$/))) return send(res, 400, {error: 'not downloaded'});
    if ((r = m(/^\/api\/pixiv\/artwork\/(\d+)\/meta$/))) {
        return send(res, 200, {illustId: r[1], illustTitle: '测试作品 ' + r[1], userId: '1960050', userName: '测试作者',
            authorId: 1960050, authorName: '测试作者', xRestrict: 0, aiType: 1, illustType: 0, pageCount: sim.pages,
            description: '', tags: ['オリジナル', 'test']});
    }
    if ((r = m(/^\/api\/pixiv\/artwork\/(\d+)\/pages$/))) {
        return send(res, 200, {urls: Array.from({length: sim.pages}, (_, i) =>
            `https://i.pximg.net/img-original/img/2026/11/18/00/00/00/${r[1]}_p${i}.png`)});
    }
    if (p === '/api/download/pixiv' && req.method === 'POST') {
        const body = JSON.parse(await readBody(req));
        const id = String(body.artworkId);
        const total = body.imageUrls.length;
        payload(id, body.title, total, {});
        const taskId = crypto.randomUUID();
        setTask(taskId, 'RUNNING');
        setTimeout(() => simulateDownload(id, body.title, total, taskId), 30);
        return send(res, 200, {success: true, message: '下载已开始', taskId, artworkId: Number(id)});
    }
    if (p === '/api/sse/download') {
        res.writeHead(200, {'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache',
            'Connection': 'keep-alive', 'Content-Security-Policy': CSP});
        const id = 'conn-' + (++connSeq);
        res.write(`event: aggregated-ready\ndata: ${id}\n\n`);
        sseClients.add(res);
        req.on('close', () => sseClients.delete(res));
        return;
    }
    if ((r = m(/^\/api\/sse\/close\/aggregated\/(.+)$/))) return send(res, 200, {success: true});
    counters.unknown[req.method + ' ' + p] = (counters.unknown[req.method + ' ' + p] || 0) + 1;
    return send(res, 404, {error: 'not mocked: ' + p});
}

function serveStatic(req, res, url) {
    let rel = decodeURIComponent(url.pathname);
    if (rel === '/') rel = '/pixiv-batch.html';
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, rel);
        if (!file.startsWith(root)) break;
        if (fs.existsSync(file) && fs.statSync(file).isFile()) {
            return send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || 'application/octet-stream');
        }
    }
    counters.unknown['STATIC ' + rel] = (counters.unknown['STATIC ' + rel] || 0) + 1;
    send(res, 404, 'not found', 'text/plain; charset=utf-8');
}

http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/__')) {
        api(req, res, url).catch(e => send(res, 500, {error: String(e && e.stack || e)}));
    } else serveStatic(req, res, url);
}).listen(PORT, '127.0.0.1', () => console.log(`download-queue-bench server ${treeRoot} on ${PORT}`));
