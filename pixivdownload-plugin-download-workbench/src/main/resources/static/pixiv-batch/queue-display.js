'use strict';

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

function queueStatusText(status) {
    return {
        idle: bt('queue.status.waiting', '等待中'),
        pending: bt('queue.status.waiting', '等待中'),
        downloading: bt('queue.status.downloading', '下载中'),
        completed: bt('queue.status.completed', '已完成'),
        failed: bt('queue.status.failed', '失败'),
        paused: bt('queue.status.paused', '已暂停'),
        skipped: bt('queue.status.skipped', '已跳过')
    }[status] || status;
}

function queueAcquisitionMode(source) {
    const normalizedSource = String(source || '').trim();
    if (normalizedSource === QUICK_FETCH_MODE
        || normalizedSource.startsWith(QUICK_FETCH_MODE + '-')) return 'quick';
    if (normalizedSource === SINGLE_IMPORT_MODE
        || normalizedSource.startsWith(SINGLE_IMPORT_MODE + '-')) return 'single-import';
    if (normalizedSource === 'user' || normalizedSource.startsWith('user-')) return 'user';
    if (normalizedSource === 'search' || normalizedSource.startsWith('search-')) return 'search';
    if (normalizedSource === 'series' || normalizedSource.startsWith('series-')) return 'series';
    if (normalizedSource === 'schedule' || normalizedSource.startsWith('schedule-')) return 'schedule';
    return 'single-import';
}

function queueSourceText(source) {
    if (source === 'task') return bt('batch:queue.task.external');
    return {
        user: bt('queue.source.user', 'User'),
        search: bt('queue.source.search', 'Search'),
        series: bt('queue.source.series', 'Series'),
        quick: bt('queue.source.quick-fetch', '快捷'),
        'single-import': bt('queue.source.import', '导入'),
        schedule: bt('queue.source.schedule', '计划')
    }[queueAcquisitionMode(source)] || bt('queue.source.import', '导入');
}

// 渲染时派生队列项标题：模型里 title 只存原始字符串（可为空），此处补 i18n fallback。
function queueItemDisplayTitle(q) {
    if (q && q.title) return q.title;
    if (q && q.kind === 'novel') {
        const id = q.novelId || (q.id != null ? String(q.id).replace(/^n/, '') : '');
        return bt('queue.novel-fallback', '小说 {id}', {id});
    }
    return bt('queue.artwork-fallback', '作品 {id}', {id: q && q.id != null ? q.id : ''});
}

// 队首未完成项 = 队列镜像中第一个 status 属于 downloading/pending/paused 的项（completed/failed/skipped/idle
// 视为已结束）。含 pending/paused 是为了避免并发=1 时「上一项完成 → 下一项被认领」的间隙短暂闪「无」。暂停
//（停止接受新任务）期间仍展示正在收尾下载的作品（drain）：有 downloading 项时照常展示队首；仅当没有任何
// downloading 项时才回退 idle「无」。queue 参数传 reactive 队列镜像（Vue 路径）或 state.queue（命令式路径），
// 两路共用同一口径。
function currentFrontItem(queue, isPaused) {
    for (let i = 0; i < queue.length; i++) {
        const s = queue[i].status;
        if (s === 'downloading') return queue[i];
    }
    if (isPaused) return null;
    for (let i = 0; i < queue.length; i++) {
        const s = queue[i].status;
        if (s === 'pending' || s === 'paused') return queue[i];
    }
    return null;
}

// 剩余计数原始数据：{downloading, queued}。展示的队首不计入「还有」；文案渲染期经 bt 派生（模型不 bake 翻译）。
function currentRemainingCounts(queue, front) {
    let downloading = 0, queued = 0;
    for (let i = 0; i < queue.length; i++) {
        const s = queue[i].status;
        if (s === 'downloading') downloading++;
        else if (s === 'pending' || s === 'paused') queued++;
    }
    if (front) {
        if (front.status === 'downloading') downloading--;
        else queued--;
    }
    return {downloading, queued};
}

// 剩余计数行文案（命令式回退与 Vue 路径共用；两项都为 0 时返回空串）。
function currentRemainingLineText(downloading, queued) {
    if (downloading > 0 && queued > 0) {
        return bt('status.current-remaining.both', '还有 {downloading} 个正在下载、{queued} 个排队中…',
            {downloading: downloading, queued: queued});
    }
    if (downloading > 0) {
        return bt('status.current-remaining.downloading', '还有 {count} 个正在下载…', {count: downloading});
    }
    if (queued > 0) {
        return bt('status.current-remaining.queued', '还有 {count} 个排队中…', {count: queued});
    }
    return '';
}

function pct(q) {
    if (!q.totalImages) return 0;
    return Math.min(100, Math.round((q.downloadedCount || 0) / q.totalImages * 100));
}
