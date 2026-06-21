'use strict';
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

// ---- PixivInviteDetail facade ----
window.PixivInviteDetail.chart = Object.assign(window.PixivInviteDetail.chart || {}, { loadStats, getCss, colorMix, drawChart, drawHoverDot, onChartHover });
