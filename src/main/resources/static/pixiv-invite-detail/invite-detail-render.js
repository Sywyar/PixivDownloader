'use strict';
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

// ---- PixivInviteDetail facade ----
window.PixivInviteDetail.render = Object.assign(window.PixivInviteDetail.render || {}, { renderVisibilityCell, render, setupChartListeners });
