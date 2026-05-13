'use strict';

const inviteId = new URLSearchParams(location.search).get('id');
let detail = null;
let chartDays = 7;
let pageI18n = null;
let cachedTags = null;
let cachedAuthors = null;
let cachedNovelTags = null;
let cachedNovelAuthors = null;
let chartContext = null;
let lastBuckets = [];
let lastHoverPoint = null;

function tr(key, fallback, vars) {
    if (pageI18n && pageI18n.t) return pageI18n.t(key, fallback, vars);
    return interpolate(fallback || key, vars);
}
function interpolate(template, vars) {
    if (!vars) return template;
    return String(template).replace(/\{([a-zA-Z0-9_-]+)\}/g, (m, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : m);
}

async function api(url, options) {
    const res = await fetch(url, {
        credentials: 'same-origin',
        ...options,
        headers: { 'Accept': 'application/json', ...((options || {}).headers || {}) }
    });
    if (res.status === 401) {
        window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
        throw new Error('Unauthorized');
    }
    if (!res.ok) {
        let msg = 'HTTP ' + res.status;
        try { const j = await res.json(); if (j && j.error) msg = j.error; } catch (_) {}
        throw new Error(msg);
    }
    return res.headers.get('content-type')?.includes('json') ? res.json() : res.text();
}

function escapeHtml(str) {
    return String(str || '').replace(/[&<>"']/g, c =>
        ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}
function fmtTime(ms) {
    if (ms == null) return '-';
    const d = new Date(ms);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-'
        + String(d.getDate()).padStart(2, '0') + ' '
        + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function fmtTimeShort(ms) {
    const d = new Date(ms);
    return (d.getMonth() + 1) + '/' + d.getDate() + ' '
        + String(d.getHours()).padStart(2, '0') + ':00';
}
/**
 * 小时桶覆盖 [ms, ms + 3_600_000)，tooltip 显示这段时间范围。
 * 例如 5/3 10:00–11:00；跨日时 23:00–00:00。
 */
function fmtBucketRange(ms) {
    const start = new Date(ms);
    const end = new Date(ms + 3600000);
    const dateStr = (start.getMonth() + 1) + '/' + start.getDate();
    const startHH = String(start.getHours()).padStart(2, '0') + ':00';
    const endHH = String(end.getHours()).padStart(2, '0') + ':00';
    return dateStr + ' ' + startHH + '–' + endHH;
}

function ageRatingLabel(d) {
    const parts = [];
    if (d.allowSfw) parts.push(tr('invite:age.sfw', 'SFW'));
    if (d.allowR18) parts.push(tr('invite:age.r18', 'R18'));
    if (d.allowR18g) parts.push(tr('invite:age.r18g', 'R18G'));
    return parts.length ? parts.join(', ') : '(-)';
}

/**
 * 渲染"可见标签"或"可见作者"维度的摘要单元格。
 * 三种状态：全部可见 / 全部不可见 / 部分可见；查看详细按钮始终可点击。
 *
 * {@code kind} 决定数据源与文案：tag / author / novel-tag / novel-author。
 */
function renderVisibilityCell(unrestricted, entries, kind) {
    const isTagSide = kind === 'tag' || kind === 'novel-tag';
    let summaryText;
    if (unrestricted) {
        summaryText = isTagSide
            ? tr('invite:detail.value.tags-all', '全部标签')
            : tr('invite:detail.value.authors-all', '全部作者');
    } else if (!entries || entries.length === 0) {
        summaryText = isTagSide
            ? tr('invite:detail.value.tags-none', '全部不可见')
            : tr('invite:detail.value.authors-none', '全部不可见');
    } else {
        const summaryKey = isTagSide
            ? 'invite:detail.value.summary.tags'
            : 'invite:detail.value.summary.authors';
        const summaryFallback = isTagSide ? '可见 {visible} 个标签' : '可见 {visible} 个作者';
        summaryText = tr(summaryKey, summaryFallback, { visible: entries.length });
    }
    return `
        <div class="summary-row">
            <span class="summary-text">${escapeHtml(summaryText)}</span>
            <button class="view-detail-btn" data-kind="${kind}">${escapeHtml(tr('invite:detail.action.view-detail', '查看详细'))}</button>
        </div>`;
}

function render() {
    const wrap = document.getElementById('wrap');
    const d = detail;
    if (!d) return;

    wrap.innerHTML = `
    <div class="card">
        <div class="card-title">${escapeHtml(tr('invite:detail.section.basic', '基础信息'))}</div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.name', '名称'))}</div><div class="val">${escapeHtml(d.name)}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.created', '创建时间'))}</div><div class="val">${fmtTime(d.createdTime)}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.expire', '过期时间'))}</div><div class="val">${d.expireTime == null ? escapeHtml(tr('invite:expire.permanent', '永久')) : fmtTime(d.expireTime)}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.status', '状态'))}</div><div class="val">${escapeHtml(d.paused ? tr('invite:detail.value.paused', '已暂停') : tr('invite:detail.value.active', '启用中'))}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.used', '是否被使用'))}</div><div class="val">${d.used
            ? escapeHtml(tr('invite:detail.value.used.has', '已使用（首次：{first}，最近：{last}）',
                { first: fmtTime(d.firstUsedTime), last: fmtTime(d.lastUsedTime) }))
            : escapeHtml(tr('invite:detail.value.used.none', '未使用'))}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.total', '累计访问'))}</div><div class="val">${d.totalRequestCount}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.age', '年龄分级'))}</div><div class="val">${ageRatingLabel(d)}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.tags.illust', '漫画可见标签'))}</div><div class="val">${renderVisibilityCell(d.tagUnrestricted, d.tags, 'tag')}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.authors.illust', '漫画可见作者'))}</div><div class="val">${renderVisibilityCell(d.authorUnrestricted, d.authors, 'author')}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.tags.novel', '小说可见标签'))}</div><div class="val">${renderVisibilityCell(d.novelTagUnrestricted, d.novelTags, 'novel-tag')}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.authors.novel', '小说可见作者'))}</div><div class="val">${renderVisibilityCell(d.novelAuthorUnrestricted, d.novelAuthors, 'novel-author')}</div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.url', '邀请链接'))}</div>
            <div class="val"><div class="code-block"><span class="v">${escapeHtml(d.url)}</span>
                <button class="copy-btn" data-copy="${escapeHtml(d.url)}">${escapeHtml(tr('invite:copy', '复制'))}</button></div></div></div>
        <div class="info-row"><div class="lbl">${escapeHtml(tr('invite:detail.field.code', '邀请码'))}</div>
            <div class="val"><div class="code-block"><span class="v">${escapeHtml(d.code)}</span>
                <button class="copy-btn" data-copy="${escapeHtml(d.code)}">${escapeHtml(tr('invite:copy', '复制'))}</button></div></div></div>
    </div>

    <div class="card">
        <div class="card-title">${escapeHtml(tr('invite:detail.section.ops', '操作'))}</div>
        <div class="ops">
            <button class="op-btn primary" id="btnEdit">${escapeHtml(tr('invite:detail.op.edit', '编辑'))}</button>
            <button class="op-btn" id="btnTogglePause">${escapeHtml(d.paused ? tr('invite:detail.op.resume', '恢复') : tr('invite:detail.op.pause', '暂停'))}</button>
            <button class="op-btn danger" id="btnDelete">${escapeHtml(tr('invite:detail.op.delete', '删除'))}</button>
        </div>
    </div>

    <div class="card">
        <div class="card-title">${escapeHtml(tr('invite:detail.section.stats', '访问统计'))}</div>
        <div class="chart-toolbar">
            <button class="filter-chip ${chartDays === 1 ? 'active' : ''}" data-days="1">${escapeHtml(tr('invite:detail.stats.24h', '最近 24 小时'))}</button>
            <button class="filter-chip ${chartDays === 7 ? 'active' : ''}" data-days="7">${escapeHtml(tr('invite:detail.stats.7d', '最近 7 天'))}</button>
            <button class="filter-chip ${chartDays === 30 ? 'active' : ''}" data-days="30">${escapeHtml(tr('invite:detail.stats.30d', '最近 30 天'))}</button>
        </div>
        <div class="chart-wrap">
            <canvas id="statsCanvas"></canvas>
            <div class="chart-tooltip" id="chartTooltip"></div>
        </div>
    </div>
    `;

    wrap.querySelectorAll('.copy-btn').forEach(btn => {
        btn.addEventListener('click', async () => {
            const ok = await window.InviteModals.copyText(btn.dataset.copy);
            const orig = btn.textContent;
            btn.textContent = ok ? tr('invite:copy.copied', '已复制') : tr('invite:copy.failed', '复制失败');
            setTimeout(() => { btn.textContent = orig; }, 1500);
        });
    });
    wrap.querySelectorAll('.view-detail-btn').forEach(btn => {
        btn.addEventListener('click', () => openViewDetailPicker(btn.dataset.kind));
    });
    document.getElementById('btnEdit').addEventListener('click', openEditModal);
    document.getElementById('btnTogglePause').addEventListener('click', togglePause);
    document.getElementById('btnDelete').addEventListener('click', deleteInvite);
    wrap.querySelectorAll('.chart-toolbar .filter-chip').forEach(chip => {
        chip.addEventListener('click', () => {
            chartDays = parseInt(chip.dataset.days, 10);
            wrap.querySelectorAll('.chart-toolbar .filter-chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            loadStats();
        });
    });

    setupChartListeners();
    loadStats();
}

function setupChartListeners() {
    const canvas = document.getElementById('statsCanvas');
    if (!canvas) return;
    canvas.addEventListener('mousemove', onChartHover);
    canvas.addEventListener('mouseleave', () => {
        const tip = document.getElementById('chartTooltip');
        if (tip) tip.classList.remove('show');
        lastHoverPoint = null;
        if (lastBuckets.length) drawChart(lastBuckets);
    });
}

async function load() {
    try {
        detail = await api('/api/admin/invites/' + encodeURIComponent(inviteId));
        render();
    } catch (e) {
        document.getElementById('wrap').innerHTML =
            '<div class="card"><div class="error">' + escapeHtml(tr('invite:load.failed', '加载失败：{0}', { 0: e.message })) + '</div></div>';
    }
}

async function togglePause() {
    if (!detail) return;
    const wasPaused = detail.paused;
    const path = wasPaused ? '/resume' : '/pause';
    try {
        await api('/api/admin/invites/' + detail.id + path, { method: 'POST' });
        window.InviteModals.showToast(
            tr(wasPaused ? 'invite:toast.resumed' : 'invite:toast.paused',
               wasPaused ? '邀请码已恢复' : '邀请码已暂停'),
            'success');
        load();
    } catch (e) {
        window.InviteModals.showToast(tr('invite:toast.failed', '{0}', { 0: e.message }), 'error');
    }
}

async function deleteInvite() {
    if (!detail) return;
    if (!confirm(tr('invite:detail.op.delete.confirm',
        '确认删除邀请「{name}」？该操作不可恢复。', { name: detail.name }))) return;
    try {
        await api('/api/admin/invites/' + detail.id, { method: 'DELETE' });
        window.InviteModals.showToast(tr('invite:toast.deleted', '已删除'), 'success');
        setTimeout(() => { location.href = '/pixiv-invite-manage.html'; }, 600);
    } catch (e) {
        window.InviteModals.showToast(tr('invite:toast.failed', '{0}', { 0: e.message }), 'error');
    }
}

async function fetchTagsForPicker() {
    if (cachedTags) return cachedTags;
    const data = await api('/api/gallery/tags?limit=2000');
    cachedTags = (data.tags || []).map(t => ({
        id: t.tagId,
        name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name
    }));
    return cachedTags;
}
async function fetchAuthorsForPicker() {
    if (cachedAuthors) return cachedAuthors;
    const all = []; let page = 0; let totalPages = 1;
    while (page < totalPages && page < 50) {
        const data = await api(`/api/authors/paged?page=${page}&size=200&sort=name`);
        const list = data.content || [];
        for (const a of list) all.push({ id: a.authorId, name: a.name || ('#' + a.authorId) });
        totalPages = data.totalPages || 1;
        page++;
        if (list.length === 0) break;
    }
    cachedAuthors = all;
    return cachedAuthors;
}
async function fetchNovelTagsForPicker() {
    if (cachedNovelTags) return cachedNovelTags;
    const data = await api('/api/gallery/novels/tags?limit=2000');
    cachedNovelTags = (data.tags || []).map(t => ({
        id: t.tagId,
        name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name
    }));
    return cachedNovelTags;
}
async function fetchNovelAuthorsForPicker() {
    if (cachedNovelAuthors) return cachedNovelAuthors;
    const all = []; let page = 0; let totalPages = 1;
    while (page < totalPages && page < 50) {
        const data = await api(`/api/gallery/novels/authors?page=${page}&size=200&sort=name`);
        const list = data.content || [];
        for (const a of list) all.push({ id: a.authorId, name: a.name || ('#' + a.authorId) });
        totalPages = data.totalPages || 1;
        page++;
        if (list.length === 0) break;
    }
    cachedNovelAuthors = all;
    return cachedNovelAuthors;
}

/**
 * 把单维度的"查看详细"打开为可编辑 picker，提交时只更新对应维度，其它三维度保持原值。
 */
function openViewDetailPicker(kind) {
    const fetched = (() => {
        switch (kind) {
            case 'tag': return fetchTagsForPicker();
            case 'author': return fetchAuthorsForPicker();
            case 'novel-tag': return fetchNovelTagsForPicker();
            case 'novel-author': return fetchNovelAuthorsForPicker();
            default: return Promise.resolve([]);
        }
    })();
    Promise.resolve(fetched).then(list => {
        const initialUnrestricted = ({
            'tag': detail.tagUnrestricted,
            'author': detail.authorUnrestricted,
            'novel-tag': detail.novelTagUnrestricted,
            'novel-author': detail.novelAuthorUnrestricted,
        })[kind];
        const initialIds = (() => {
            switch (kind) {
                case 'tag': return (detail.tags || []).map(t => t.tagId);
                case 'author': return (detail.authors || []).map(a => a.authorId);
                case 'novel-tag': return (detail.novelTags || []).map(t => t.tagId);
                case 'novel-author': return (detail.novelAuthors || []).map(a => a.authorId);
                default: return [];
            }
        })();
        window.InviteModals.openVisibilityPicker({
            kind,
            items: list,
            unrestricted: initialUnrestricted,
            selectedIds: initialIds,
            onSubmit: async ({ unrestricted, ids }) => {
                const expireDays = detail.expireTime == null
                    ? null
                    : Math.max(1, Math.ceil((detail.expireTime - Date.now()) / 86400000));
                const payload = {
                    name: detail.name,
                    expireDays,
                    allowSfw: detail.allowSfw,
                    allowR18: detail.allowR18,
                    allowR18g: detail.allowR18g,
                    tagUnrestricted: detail.tagUnrestricted,
                    tagIds: (detail.tags || []).map(t => t.tagId),
                    authorUnrestricted: detail.authorUnrestricted,
                    authorIds: (detail.authors || []).map(a => a.authorId),
                    novelTagUnrestricted: detail.novelTagUnrestricted,
                    novelTagIds: (detail.novelTags || []).map(t => t.tagId),
                    novelAuthorUnrestricted: detail.novelAuthorUnrestricted,
                    novelAuthorIds: (detail.novelAuthors || []).map(a => a.authorId),
                };
                // 仅覆盖被编辑的那一维
                switch (kind) {
                    case 'tag':
                        payload.tagUnrestricted = unrestricted;
                        payload.tagIds = unrestricted ? [] : ids;
                        break;
                    case 'author':
                        payload.authorUnrestricted = unrestricted;
                        payload.authorIds = unrestricted ? [] : ids;
                        break;
                    case 'novel-tag':
                        payload.novelTagUnrestricted = unrestricted;
                        payload.novelTagIds = unrestricted ? [] : ids;
                        break;
                    case 'novel-author':
                        payload.novelAuthorUnrestricted = unrestricted;
                        payload.novelAuthorIds = unrestricted ? [] : ids;
                        break;
                }
                try {
                    await api('/api/admin/invites/' + detail.id, {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    });
                    window.InviteModals.showToast(tr('invite:toast.saved', '已保存'), 'success');
                    load();
                } catch (e) {
                    window.InviteModals.showToast(tr('invite:toast.failed', '{0}', { 0: e.message }), 'error');
                }
            }
        });
    });
}

function openEditModal() {
    if (!detail) return;
    const expireDays = detail.expireTime == null
        ? null
        : Math.max(1, Math.ceil((detail.expireTime - Date.now()) / 86400000));
    const prefill = {
        id: detail.id,
        name: detail.name,
        expireDays,
        permanent: detail.expireTime == null,
        allowSfw: detail.allowSfw,
        allowR18: detail.allowR18,
        allowR18g: detail.allowR18g,
        tagUnrestricted: detail.tagUnrestricted,
        authorUnrestricted: detail.authorUnrestricted,
        tagIds: (detail.tags || []).map(t => t.tagId),
        authorIds: (detail.authors || []).map(a => a.authorId),
        novelTagUnrestricted: detail.novelTagUnrestricted,
        novelAuthorUnrestricted: detail.novelAuthorUnrestricted,
        novelTagIds: (detail.novelTags || []).map(t => t.tagId),
        novelAuthorIds: (detail.novelAuthors || []).map(a => a.authorId),
    };
    window.InviteModals.openInviteFormModal({
        title: tr('invite:modal.edit.title', '编辑邀请'),
        submitText: tr('invite:modal.edit.submit', '保存'),
        prefill,
        editing: true,
        fetchTags: fetchTagsForPicker,
        fetchAuthors: fetchAuthorsForPicker,
        fetchNovelTags: fetchNovelTagsForPicker,
        fetchNovelAuthors: fetchNovelAuthorsForPicker,
        onSubmit: async (payload) => {
            await api('/api/admin/invites/' + detail.id, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            window.InviteModals.showToast(tr('invite:toast.saved', '已保存'), 'success');
            load();
        }
    });
}

// ---------- chart ----------

async function loadStats() {
    if (!detail) return;
    try {
        const data = await api(`/api/admin/invites/${detail.id}/stats?days=${chartDays}`);
        drawChart(data.buckets || []);
    } catch (e) {
        const c = document.getElementById('statsCanvas');
        if (c) {
            const ctx = c.getContext('2d');
            ctx.clearRect(0, 0, c.width, c.height);
            ctx.fillStyle = '#dc3545';
            ctx.fillText(tr('invite:detail.stats.failed', '加载统计失败：{0}', { 0: e.message }), 10, 20);
        }
    }
}

function getCss(name, fallback) {
    const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
}
function colorMix(color, alpha) {
    if (color.startsWith('#')) {
        const h = color.slice(1);
        const r = parseInt(h.length === 3 ? h[0] + h[0] : h.slice(0, 2), 16);
        const g = parseInt(h.length === 3 ? h[1] + h[1] : h.slice(2, 4), 16);
        const b = parseInt(h.length === 3 ? h[2] + h[2] : h.slice(4, 6), 16);
        return `rgba(${r},${g},${b},${alpha})`;
    }
    return color;
}

function drawChart(buckets) {
    lastBuckets = buckets || [];
    const c = document.getElementById('statsCanvas');
    if (!c) return;
    const dpr = window.devicePixelRatio || 1;
    const cssW = c.clientWidth || 600;
    const cssH = c.clientHeight || 280;
    c.width = cssW * dpr; c.height = cssH * dpr;
    const ctx = c.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);

    const padL = 44, padR = 14, padT = 14, padB = 30;
    const w = cssW - padL - padR, h = cssH - padT - padB;
    const colorAxis = getCss('--muted', '#888');
    const colorBorder = getCss('--line', '#ddd');
    const colorBrand = getCss('--brand', '#0096fa');

    chartContext = null;
    if (!buckets.length) {
        ctx.fillStyle = colorAxis;
        ctx.font = '13px sans-serif';
        ctx.fillText(tr('invite:detail.stats.empty', '暂无数据'), cssW / 2 - 30, cssH / 2);
        return;
    }
    const maxVal = Math.max(1, ...buckets.map(b => b.count));
    const stepX = w / Math.max(1, buckets.length - 1);

    // grid
    ctx.strokeStyle = colorBorder; ctx.lineWidth = 1;
    ctx.fillStyle = colorAxis; ctx.font = '11px sans-serif';
    for (let i = 0; i <= 4; i++) {
        const y = padT + (h * i) / 4;
        const v = Math.round((maxVal * (4 - i)) / 4);
        ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(cssW - padR, y); ctx.stroke();
        ctx.fillText(String(v), 6, y + 3);
    }

    // x labels
    if (chartDays === 1) {
        const tickStep = Math.max(1, Math.floor(buckets.length / 8));
        for (let i = 0; i < buckets.length; i += tickStep) {
            const x = padL + i * stepX;
            const date = new Date(buckets[i].hourEpochMillis);
            ctx.fillText(String(date.getHours()).padStart(2, '0') + ':00', x - 14, cssH - 10);
        }
    } else {
        const totalDays = buckets.length / 24;
        const tickInterval = chartDays === 7 ? 1 : 5;
        for (let d = 0; d <= totalDays; d += tickInterval) {
            const idx = Math.min(buckets.length - 1, Math.round(d * 24));
            const ts = buckets[idx]?.hourEpochMillis || Date.now();
            const x = padL + idx * stepX;
            const date = new Date(ts);
            ctx.fillText((date.getMonth() + 1) + '/' + date.getDate(), x - 12, cssH - 10);
        }
    }

    // line
    ctx.strokeStyle = colorBrand; ctx.lineWidth = 2; ctx.beginPath();
    const points = [];
    buckets.forEach((b, i) => {
        const x = padL + i * stepX;
        const y = padT + h - (b.count / maxVal) * h;
        points.push({ x, y, bucket: b });
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();

    // fill
    ctx.lineTo(padL + (buckets.length - 1) * stepX, padT + h);
    ctx.lineTo(padL, padT + h);
    ctx.closePath();
    ctx.fillStyle = colorMix(colorBrand, 0.16);
    ctx.fill();

    chartContext = { points, padL, padR, padT, padB, cssW, cssH, colorBrand, stepX };

    // 重绘当前 hover 点（如果窗口大小变化等）
    if (lastHoverPoint) {
        const same = points.find(p => p.bucket.hourEpochMillis === lastHoverPoint.bucket.hourEpochMillis);
        if (same) drawHoverDot(same);
    }
}

function drawHoverDot(pt) {
    const c = document.getElementById('statsCanvas');
    if (!c || !chartContext) return;
    const ctx = c.getContext('2d');
    ctx.fillStyle = chartContext.colorBrand;
    ctx.beginPath();
    ctx.arc(pt.x, pt.y, 4.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1.5;
    ctx.stroke();
}

function onChartHover(e) {
    if (!chartContext) return;
    const canvas = e.currentTarget;
    const rect = canvas.getBoundingClientRect();
    const cx = e.clientX - rect.left;
    const cy = e.clientY - rect.top;
    let nearest = null; let nearestDist = Infinity;
    const maxDist = Math.max(24, chartContext.stepX * 2);
    for (const p of chartContext.points) {
        const dx = p.x - cx;
        const dy = p.y - cy;
        const dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < maxDist && dist < nearestDist) {
            nearest = p; nearestDist = dist;
        }
    }
    const tip = document.getElementById('chartTooltip');
    if (!nearest) {
        if (lastHoverPoint) {
            lastHoverPoint = null;
            if (tip) tip.classList.remove('show');
            if (lastBuckets.length) drawChart(lastBuckets);
        }
        return;
    }
    if (nearest === lastHoverPoint) return;
    lastHoverPoint = nearest;
    if (lastBuckets.length) drawChart(lastBuckets); // 重绘清除旧实心圆
    drawHoverDot(nearest);

    if (tip) {
        const ts = nearest.bucket.hourEpochMillis;
        tip.innerHTML = '<div>' + escapeHtml(tr('invite:detail.tooltip.time',
                '{time}', { time: fmtBucketRange(ts) })) + '</div>'
            + '<div>' + escapeHtml(tr('invite:detail.tooltip.count',
                '请求 {count} 次', { count: nearest.bucket.count })) + '</div>';
        const wrap = document.querySelector('.chart-wrap');
        const tipW = tip.offsetWidth;
        const tipH = tip.offsetHeight;
        let left = nearest.x;
        const half = tipW / 2;
        if (left + half > wrap.clientWidth - 4) left = wrap.clientWidth - half - 4;
        if (left - half < 4) left = half + 4;
        if (nearest.y - tipH - 12 < 0) {
            tip.style.transform = 'translate(-50%, 12px)';
        } else {
            tip.style.transform = 'translate(-50%, calc(-100% - 12px))';
        }
        tip.style.left = left + 'px';
        tip.style.top = nearest.y + 'px';
        tip.classList.add('show');
    }
}

(async () => {
    try {
        pageI18n = await PixivI18n.create({ namespaces: ['invite', 'common'] });
        window.PixivInvitesI18n = pageI18n;
        if (pageI18n.apply) pageI18n.apply();
    } catch (_) {}
    if (!inviteId) {
        document.getElementById('wrap').innerHTML =
            '<div class="card"><div class="error">'
            + escapeHtml(tr('invite:detail.missing-id', '缺少 ?id 参数'))
            + '</div></div>';
        return;
    }
    if (window.PixivLangSwitcher && window.PixivLangSwitcher.mount && pageI18n) {
        try {
            await window.PixivLangSwitcher.mount({
                mountPoint: document.getElementById('langSwitcherAnchor'),
                i18n: pageI18n,
                onChange: client => { pageI18n = client; window.PixivInvitesI18n = client; render(); }
            });
        } catch (_) {}
    }
    if (window.PixivTheme && window.PixivTheme.mount) {
        try { window.PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') }); } catch (_) {}
    }
    load();
})();
