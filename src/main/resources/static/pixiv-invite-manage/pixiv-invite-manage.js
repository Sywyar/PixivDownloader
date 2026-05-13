'use strict';

let pageI18n = null;
function tr(key, fallback, vars) {
    if (pageI18n && pageI18n.t) return pageI18n.t(key, fallback, vars);
    return interpolate(fallback || key, vars);
}
function interpolate(template, vars) {
    if (!vars) return template;
    return String(template).replace(/\{([a-zA-Z0-9_-]+)\}/g, (m, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : m);
}

const state = { invites: [], filter: 'all', keyword: '' };

async function api(url, options) {
    const res = await fetch(url, {
        credentials: 'same-origin',
        ...options,
        headers: { 'Accept': 'application/json', ...((options || {}).headers || {}) }
    });
    if (res.status === 401) {
        window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname);
        throw new Error('Unauthorized');
    }
    if (!res.ok) {
        let msg = 'HTTP ' + res.status;
        try { const j = await res.json(); if (j && j.error) msg = j.error; } catch (_) {}
        throw new Error(msg);
    }
    return res.headers.get('content-type')?.includes('json') ? res.json() : res.text();
}

function fmtTime(ms) {
    if (ms == null) return '-';
    const d = new Date(ms);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-'
        + String(d.getDate()).padStart(2, '0') + ' '
        + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function expireLabel(item) {
    if (item.expireTime == null) return tr('invite:expire.permanent', '永久');
    const remaining = item.expireTime - Date.now();
    if (remaining <= 0) return tr('invite:expire.expired', '已过期');
    const days = Math.floor(remaining / 86400000);
    if (days >= 1) return tr('invite:expire.days', '剩 {days} 天', { days });
    const hours = Math.floor(remaining / 3600000);
    return tr('invite:expire.hours', '剩 {hours} 小时', { hours });
}
function statusOf(item) {
    if (item.expireTime != null && Date.now() > item.expireTime) return 'expired';
    if (item.paused) return 'paused';
    return 'active';
}

function renderTable() {
    const container = document.getElementById('tableContainer');
    let list = state.invites.slice();
    if (state.filter === 'active') list = list.filter(it => statusOf(it) === 'active');
    else if (state.filter === 'paused') list = list.filter(it => statusOf(it) === 'paused');
    else if (state.filter === 'expired') list = list.filter(it => statusOf(it) === 'expired');
    else if (state.filter === 'unused') list = list.filter(it => !it.used);
    if (state.keyword) {
        const k = state.keyword.toLowerCase();
        list = list.filter(it => (it.name || '').toLowerCase().includes(k)
            || (it.code || '').toLowerCase().includes(k));
    }
    if (list.length === 0) {
        container.innerHTML = '<div class="empty">' + escapeHtml(tr('invite:table.empty', '无匹配的邀请记录')) + '</div>';
        return;
    }
    const rows = list.map(it => {
        const status = statusOf(it);
        const statusBadge = status === 'active'
            ? `<span class="badge badge-active">${escapeHtml(tr('invite:status.active', '启用'))}</span>`
            : status === 'paused'
                ? `<span class="badge badge-paused">${escapeHtml(tr('invite:status.paused', '暂停'))}</span>`
                : `<span class="badge badge-expired">${escapeHtml(tr('invite:status.expired', '已过期'))}</span>`;
        const usedBadge = it.used
            ? `<span class="badge badge-used" title="${fmtTime(it.firstUsedTime)}">${escapeHtml(tr('invite:used.yes', '已使用'))}</span>`
            : `<span class="badge badge-unused">${escapeHtml(tr('invite:used.no', '未使用'))}</span>`;
        return `
        <tr>
            <td>${escapeHtml(it.name)}</td>
            <td class="code-cell" title="${escapeHtml(tr('invite:copy', '复制'))}" data-copy="${escapeHtml(it.code)}">${escapeHtml(it.code)}</td>
            <td>${fmtTime(it.createdTime)}</td>
            <td>${escapeHtml(expireLabel(it))}</td>
            <td>${statusBadge}</td>
            <td>${usedBadge}</td>
            <td>${it.totalRequestCount}</td>
            <td><a class="action-btn" href="/pixiv-invite-detail.html?id=${it.id}">${escapeHtml(tr('invite:table.action.detail', '详情'))}</a></td>
        </tr>`;
    }).join('');
    container.innerHTML = `
    <table>
        <thead>
            <tr>
                <th>${escapeHtml(tr('invite:column.name', '名称'))}</th>
                <th>${escapeHtml(tr('invite:column.code', '邀请码'))}</th>
                <th>${escapeHtml(tr('invite:column.created', '创建时间'))}</th>
                <th>${escapeHtml(tr('invite:column.expire', '过期'))}</th>
                <th>${escapeHtml(tr('invite:column.status', '状态'))}</th>
                <th>${escapeHtml(tr('invite:column.used', '使用情况'))}</th>
                <th>${escapeHtml(tr('invite:column.total', '累计'))}</th>
                <th>${escapeHtml(tr('invite:column.actions', '操作'))}</th>
            </tr>
        </thead>
        <tbody>${rows}</tbody>
    </table>`;
    container.querySelectorAll('.code-cell').forEach(cell => {
        cell.addEventListener('click', async () => {
            const ok = await window.InviteModals.copyText(cell.dataset.copy);
            cell.textContent = ok ? tr('invite:copy.copied', '已复制') : tr('invite:copy.failed', '复制失败');
            setTimeout(() => { cell.textContent = cell.dataset.copy; }, 1500);
        });
    });
}

function escapeHtml(str) {
    return String(str || '').replace(/[&<>"']/g, c =>
        ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}

async function loadInvites() {
    try {
        const data = await api('/api/admin/invites');
        state.invites = data.invites || [];
        renderTable();
    } catch (e) {
        document.getElementById('tableContainer').innerHTML =
            '<div class="empty">' + escapeHtml(tr('invite:load.failed', '加载失败：{0}', { 0: e.message })) + '</div>';
    }
}

async function fetchTagsForPicker() {
    const data = await api('/api/gallery/tags?limit=2000');
    return (data.tags || []).map(t => ({
        id: t.tagId,
        name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name
    }));
}
async function fetchAuthorsForPicker() {
    const all = []; let page = 0; let totalPages = 1;
    while (page < totalPages && page < 50) {
        const data = await api(`/api/authors/paged?page=${page}&size=200&sort=name`);
        const list = data.content || [];
        for (const a of list) all.push({ id: a.authorId, name: a.name || ('#' + a.authorId) });
        totalPages = data.totalPages || 1;
        page++;
        if (list.length === 0) break;
    }
    return all;
}
async function fetchNovelTagsForPicker() {
    const data = await api('/api/gallery/novels/tags?limit=2000');
    return (data.tags || []).map(t => ({
        id: t.tagId,
        name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name
    }));
}
async function fetchNovelAuthorsForPicker() {
    const all = []; let page = 0; let totalPages = 1;
    while (page < totalPages && page < 50) {
        const data = await api(`/api/gallery/novels/authors?page=${page}&size=200&sort=name`);
        const list = data.content || [];
        for (const a of list) all.push({ id: a.authorId, name: a.name || ('#' + a.authorId) });
        totalPages = data.totalPages || 1;
        page++;
        if (list.length === 0) break;
    }
    return all;
}

document.querySelectorAll('.filter-chip').forEach(chip => {
    chip.addEventListener('click', () => {
        document.querySelectorAll('.filter-chip').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        state.filter = chip.dataset.filter;
        renderTable();
    });
});
document.getElementById('searchBox').addEventListener('input', e => {
    state.keyword = e.target.value || '';
    renderTable();
});

document.getElementById('btnNewInvite').addEventListener('click', () => {
    window.InviteModals.openInviteFormModal({
        title: tr('invite:modal.create.title', '邀请访客'),
        submitText: tr('invite:modal.create.submit', '创建邀请链接'),
        fetchTags: fetchTagsForPicker,
        fetchAuthors: fetchAuthorsForPicker,
        fetchNovelTags: fetchNovelTagsForPicker,
        fetchNovelAuthors: fetchNovelAuthorsForPicker,
        onSubmit: async (payload) => {
            const result = await api('/api/admin/invites', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            window.InviteModals.openInviteResultModal({ code: result.code, url: result.url });
            loadInvites();
        }
    });
});

(async () => {
    try {
        pageI18n = await PixivI18n.create({ namespaces: ['invite', 'common'] });
        window.PixivInvitesI18n = pageI18n;
        if (pageI18n.apply) pageI18n.apply();
    } catch (_) { /* fallback to defaults */ }
    if (window.PixivLangSwitcher && window.PixivLangSwitcher.mount && pageI18n) {
        try {
            await window.PixivLangSwitcher.mount({
                mountPoint: document.getElementById('langSwitcherAnchor'),
                i18n: pageI18n,
                onChange: client => { pageI18n = client; window.PixivInvitesI18n = client; renderTable(); }
            });
        } catch (_) {}
    }
    if (window.PixivTheme && window.PixivTheme.mount) {
        try { window.PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') }); } catch (_) {}
    }
    loadInvites();
})();
