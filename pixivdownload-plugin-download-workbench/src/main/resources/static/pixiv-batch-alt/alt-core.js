'use strict';
/* ============================================================
   alt-core — PixivBatchAlt 命名空间引导 + 基础工具
   与 pixiv-batch/batch-core.js 同源的格式化 / 请求 / 文本工具（逐字移植，
   去掉旧 DOM 耦合），另加：SVG 图标注册表、CSS 变量缩略图、计数动画、
   PixivFeedback 封装、抽屉 / 弹窗原语。
   本文件不触碰页面 DOM（除抽屉 / 弹窗根节点），顶层无立即执行的 DOM 查询。
   ============================================================ */
window.PixivBatchAlt = window.PixivBatchAlt || {};
['core', 'state', 'data', 'cookie', 'filters', 'settings', 'chrome', 'modes',
    'schedule', 'queue', 'engine'].forEach(function (k) {
    window.PixivBatchAlt[k] = window.PixivBatchAlt[k] || {};
});

let pageI18n = null;

function interpolate(template, vars) {
    if (!vars) {
        return String(template);
    }
    return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
        return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
    });
}

function bt(key, fallback, vars) {
    if (pageI18n) {
        return pageI18n.t(key.includes(':') ? key : 'batch-alt:' + key, fallback, vars);
    }
    return interpolate(fallback != null ? fallback : key, vars);
}

function uiLang() {
    // pageI18n.lang 已由 meta 归一化为正式 tag；无 meta 时不写死语言
    return pageI18n ? pageI18n.lang : '';
}

// 语言化标点：值来自 common bundle 的 punctuation.* key，不写死语言判断。
function punct(key) {
    const fallbacks = { colon: '：', comma: '，', enum: '、', semicolon: '；' };
    const fallback = Object.prototype.hasOwnProperty.call(fallbacks, key) ? fallbacks[key] : '';
    if (pageI18n && typeof pageI18n.tns === 'function') {
        return pageI18n.tns('common', 'punctuation.' + key, fallback);
    }
    return fallback;
}

const BASE = '';  // 使用相对路径，自动适配访问地址

function formatSeconds(s) {
    s = Math.max(0, Math.round(s));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm ' + String(sec).padStart(2, '0') + 's';
    return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
}

function formatBytes(bytes) {
    const n = Number(bytes);
    if (!Number.isFinite(n) || n < 0) return '';
    const units = ['B', 'KB', 'MB', 'GB'];
    let value = n;
    let idx = 0;
    while (value >= 1024 && idx < units.length - 1) {
        value /= 1024;
        idx++;
    }
    const digits = idx === 0 || value >= 10 ? 0 : 1;
    return `${value.toFixed(digits)} ${units[idx]}`;
}

function formatDurationMs(ms) {
    const n = Number(ms);
    if (!Number.isFinite(n) || n < 0) return '';
    const totalSeconds = Math.round(n / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return minutes > 0 ? `${minutes}:${String(seconds).padStart(2, '0')}` : `${seconds}s`;
}

// 按速度大小自适应单位：B/s · KB/s · MB/s · GB/s（与 batch-queue.js 同口径）。
function formatSpeed(bytesPerSec) {
    const b = Number(bytesPerSec);
    if (!Number.isFinite(b) || b < 1) return {value: '0', unit: 'B/s'};
    const units = ['B/s', 'KB/s', 'MB/s', 'GB/s'];
    let v = b, i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    let value;
    if (i === 0 || v >= 100) value = String(Math.round(v));
    else if (v >= 10) value = v.toFixed(1);
    else value = v.toFixed(2);
    return {value, unit: units[i]};
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function esc(s) {
    return String(s).replace(/[&<>"']/g, c =>
        ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'})[c]);
}

function downloadTxt(content, filename) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([content], {type: 'text/plain'}));
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

// Pixiv 代理请求：统一附带已保存 Cookie（alt-cookie.js 提供 pixivHeader）。
async function apiGet(path, requestInit) {
    const init = Object.assign({}, requestInit || {});
    init.headers = Object.assign({}, pixivHeader(), init.headers || {});
    const res = await fetch(BASE + path, init);
    return res.json();
}

async function apiJson(path, requestInit) {
    const init = Object.assign({credentials: 'same-origin'}, requestInit || {});
    init.headers = Object.assign({}, pixivHeader(), init.headers || {});
    return fetch(BASE + path, init);
}

async function checkBackend() {
    try {
        const res = await fetch(BASE + '/api/download/status',
            {signal: AbortSignal.timeout(2000)});
        return res.status === 200;
    } catch {
        return false;
    }
}

function summaryJoin(parts) {
    const sep = ' · ';
    return parts.filter(p => p !== null && p !== undefined && p !== '').join(sep);
}

/* ============================================================
   SVG 图标注册表（Feather 线性风格，stroke=currentColor）
   ============================================================ */
const AB_ICONS = {
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
    zap: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
    clipboard: '<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/>',
    user: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
    search: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
    layers: '<polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    sliders: '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>',
    filter: '<polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>',
    key: '<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',
    puzzle: '<path d="M14 3v3a2 2 0 1 0 4 0V3h3v4a2 2 0 1 0 0 4h3v10h-7v-3a2 2 0 1 0-4 0v3H3v-7h3a2 2 0 1 0 0-4H3V3h7v3a2 2 0 1 0 4 0z"/>',
    help: '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
    x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
    plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
    check: '<polyline points="20 6 9 17 4 12"/>',
    external: '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>',
    refresh: '<polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>',
    trash: '<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
    edit: '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>',
    eye: '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>',
    'eye-off': '<path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    alert: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
    info: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/>',
    'chevron-down': '<polyline points="6 9 12 15 18 9"/>',
    'chevron-right': '<polyline points="9 18 15 12 9 6"/>',
    folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
    box: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/>',
    gauge: '<path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
    'file-text': '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
    globe: '<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
    bookmark: '<path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/>',
    star: '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
    film: '<rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/>',
    book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>',
    play: '<polygon points="5 3 19 12 5 21 5 3"/>',
    pause: '<rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>',
    stop: '<rect x="4" y="4" width="16" height="16" rx="2"/>',
    cpu: '<rect x="4" y="4" width="16" height="16" rx="2" ry="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/>',
    link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
    heart: '<path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>',
    users: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    grid: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>',
    send: '<line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>',
    paste: '<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/><line x1="9" y1="12" x2="15" y2="12"/>'
};

function abIcon(name) {
    const body = AB_ICONS[name] || AB_ICONS.info;
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
        + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + body + '</svg>';
}

// 把容器内 <span class="ab-icon" data-icon="name"> 占位替换为真实 SVG（幂等）。
function hydrateIcons(root) {
    (root || document).querySelectorAll('.ab-icon[data-icon]:not([data-hydrated])').forEach(el => {
        el.innerHTML = abIcon(el.getAttribute('data-icon'));
        el.setAttribute('data-hydrated', '1');
    });
}

function abIconEl(name, cls) {
    const span = document.createElement('span');
    span.className = 'ab-icon' + (cls ? ' ' + cls : '');
    span.innerHTML = abIcon(name);
    span.setAttribute('aria-hidden', 'true');
    return span;
}

/* ============================================================
   CSS 变量缩略图 / 头像占位（无外部资源、无颜色字面量：
   仅写入数值 hue，实际渐变在样式表的自定义属性定义行）
   ============================================================ */
function abHueSeed(value) {
    const s = String(value == null ? '' : value);
    let h = 0;
    for (let i = 0; i < s.length; i++) {
        h = ((h << 5) - h + s.charCodeAt(i)) | 0;
    }
    return Math.abs(h) % 360;
}

function applyThumbHue(el, seed) {
    el.style.setProperty('--thumb-h', String(abHueSeed(seed)));
}

/* ============================================================
   统计计数直接显示已确认的当前值
   ============================================================ */
function animateCount(el, next, options) {
    const value = String(Number(next) || 0);
    el.dataset.countValue = value;
    if (el.textContent !== value) el.textContent = value;
}

/* ============================================================
   PixivFeedback 封装（文案经 bt() 解析）
   ============================================================ */
function abToast(kind, message, durationMs) {
    if (window.PixivFeedback) {
        window.PixivFeedback.toast({kind: kind || 'info', message, durationMs});
    }
}

function abAlert(key, fallback, vars) {
    const message = bt(key, fallback, vars);
    if (window.PixivFeedback) {
        return window.PixivFeedback.alert({
            title: bt('dialog.title.notice', '提示'),
            message,
            confirmLabel: bt('common.ok', '知道了')
        });
    }
    return Promise.resolve(true);
}

function abConfirm(key, fallback, vars, options) {
    const opts = options || {};
    const message = bt(key, fallback, vars);
    if (window.PixivFeedback) {
        return window.PixivFeedback.confirm({
            title: opts.title || bt('dialog.title.confirm', '确认操作'),
            message,
            confirmLabel: opts.confirmLabel || bt('common.confirm', '确认'),
            cancelLabel: bt('common.cancel', '取消'),
            danger: !!opts.danger
        });
    }
    return Promise.resolve(false);
}

function abPrompt(key, fallback, vars, options) {
    const opts = options || {};
    const message = bt(key, fallback, vars);
    if (window.PixivFeedback) {
        return window.PixivFeedback.prompt({
            title: opts.title || bt('dialog.title.input', '输入'),
            message,
            value: opts.value,
            inputType: opts.inputType || 'text',
            min: opts.min,
            max: opts.max,
            confirmLabel: opts.confirmLabel || bt('common.confirm', '确认'),
            cancelLabel: bt('common.cancel', '取消')
        });
    }
    return Promise.resolve(null);
}

/* ============================================================
   抽屉 / 弹窗原语（共享 #abDrawerRoot / #abModalRoot）
   ============================================================ */
let abDrawerOpen = null;
let abModalOpen = null;

function closeDrawer() {
    closeAltDialog('abDrawerRoot');
    abDrawerOpen = null;
}

function closeModal() {
    closeAltDialog('abModalRoot');
    abModalOpen = null;
}

function closeAltDialog(id) {
    const root = document.getElementById(id);
    if (!root) return;
    const finish = () => {
        if (root.open) root.close();
        root.replaceChildren();
        root._abRequestClose = null;
        root.onkeydown = null;
    };
    if (root._abClosing) return;
    if (!root.open || !root.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        finish();
        return;
    }
    const style = window.getComputedStyle(root);
    const from = {opacity: style.opacity, transform: style.transform};
    const drawer = id === 'abDrawerRoot';
    root.getAnimations().forEach(animation => animation.cancel());
    const animation = root.animate([from,
        {opacity: drawer ? 1 : 0, transform: drawer ? 'translateX(100%)' : 'translateY(8px)'}],
        {duration: drawer ? 220 : 140, easing: 'cubic-bezier(.4,0,1,1)', fill: 'forwards'});
    root._abClosing = animation;
    animation.finished.then(() => {
        if (root._abClosing !== animation) return;
        finish();
        animation.cancel();
        root._abClosing = null;
    }, () => {});
}

// 原生对话框负责模态、焦点约束、Escape 与关闭后的焦点恢复。
function openAltDialog(spec, drawer) {
    const root = document.getElementById(drawer ? 'abDrawerRoot' : 'abModalRoot');
    if (!root) return null;
    const style = root.open && root.animate ? window.getComputedStyle(root) : null;
    const from = style ? {opacity: style.opacity, transform: style.transform}
        : {opacity: drawer ? 1 : 0, transform: drawer ? 'translateX(100%)' : 'translateY(8px)'};
    root._abClosing?.cancel();
    root._abClosing = null;
    root.getAnimations?.().forEach(animation => animation.cancel());
    const close = drawer ? closeDrawer : closeModal;
    let confirming = false;
    root._abRequestClose = async () => {
        if (confirming) return;
        confirming = true;
        try { if (!spec.beforeClose || await spec.beforeClose()) close(); }
        finally { confirming = false; }
    };
    // 浏览器的 cancel 事件可能无法取消，草稿确认需要先拦截 Escape。
    root.onkeydown = event => {
        if (event.key !== 'Escape' || !spec.beforeClose) return;
        event.preventDefault();
        event.stopPropagation();
        root._abRequestClose?.();
    };
    const prefix = drawer ? 'ab-drawer' : 'ab-modal';
    const box = el('div', prefix + (spec.widthClass ? ' ' + spec.widthClass : ''));
    const head = el('div', prefix + '-head');
    const title = el('h2', prefix + '-title');
    title.id = root.id + '-title';
    if (spec.icon) title.appendChild(abIconEl(spec.icon));
    title.appendChild(el('span', '', spec.title || ''));
    const closeBtn = el('button', 'ab-iconbtn');
    closeBtn.type = 'button';
    closeBtn.setAttribute('aria-label', bt('common.close', '关闭'));
    closeBtn.appendChild(abIconEl('x'));
    closeBtn.addEventListener('click', () => root._abRequestClose?.());
    head.append(title, closeBtn);
    const body = el('div', prefix + '-body');
    if (spec.body) body.appendChild(spec.body);
    box.append(head, body);
    if (spec.footer) {
        const foot = el('div', prefix + '-foot');
        foot.appendChild(spec.footer);
        box.appendChild(foot);
    }
    root.replaceChildren(box);
    root.setAttribute('aria-labelledby', title.id);
    if (!root.open) root.showModal();
    if (root.animate && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        root.animate([from, {opacity: 1, transform: 'none'}],
            {duration: drawer ? 260 : 180, easing: 'cubic-bezier(.2,.7,.2,1)'});
    }
    return {panel: box, box, body, close};
}

function openDrawer(spec) {
    abDrawerOpen = spec.id || true;
    return openAltDialog(spec, true);
}

function openModal(spec) {
    abModalOpen = spec.id || true;
    return openAltDialog(spec, false);
}

function closeTargetDialog(event) {
    if (event.target.id !== 'abDrawerRoot' && event.target.id !== 'abModalRoot') return;
    event.preventDefault();
    event.target._abRequestClose?.();
}

document.addEventListener('cancel', closeTargetDialog, true);
document.addEventListener('click', event => {
    const root = event.target;
    if (root.id !== 'abDrawerRoot' && root.id !== 'abModalRoot') return;
    const rect = root.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right
        || event.clientY < rect.top || event.clientY > rect.bottom) closeTargetDialog(event);
});

/* ---- 小构件 ---- */
// Framework7 的 CSS 组件由现有渲染入口统一创建，行为仍由原生控件和业务事件负责。
function altComponentClasses(tag, classes) {
    const tokens = classes.split(/\s+/);
    const button = tag === 'button' || (tag === 'a' && tokens.includes('ab-iconbtn'));
    if (button) {
        classes += ' button';
        if (tokens.includes('ab-btn--primary') || tokens.includes('ab-btn--danger')) classes += ' button-fill';
        else if (tokens.includes('ab-btn--ghost')) classes += ' button-tonal';
    }
    if (tokens.includes('ab-seg')) classes += ' segmented';
    return classes.trim();
}

function el(tag, className, text) {
    const node = document.createElement(tag);
    node.className = altComponentClasses(tag, className || '');
    if (text !== undefined && text !== null) node.textContent = text;
    if ((className || '').split(/\s+/).includes('ab-loading-line')) {
        const spinner = document.createElement('span');
        spinner.className = 'preloader';
        spinner.setAttribute('aria-hidden', 'true');
        const inner = document.createElement('span');
        inner.className = 'preloader-inner';
        for (let i = 0; i < 8; i++) {
            const line = document.createElement('span');
            line.className = 'preloader-inner-line';
            inner.appendChild(line);
        }
        spinner.appendChild(inner);
        node.prepend(spinner);
        node.setAttribute('role', 'status');
    }
    return node;
}

function debounce(fn, ms) {
    let timer = null;
    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), ms);
    };
}

window.PixivBatchAlt.core = Object.assign(window.PixivBatchAlt.core, {
    bt, uiLang, esc, sleep, downloadTxt, apiGet, apiJson, checkBackend,
    formatSeconds, formatBytes, formatDurationMs, formatSpeed, summaryJoin,
    abIcon, abIconEl, hydrateIcons, applyThumbHue, abHueSeed, animateCount,
    abToast, abAlert, abConfirm, abPrompt,
    openDrawer, closeDrawer, openModal, closeModal,
    el, debounce
});
