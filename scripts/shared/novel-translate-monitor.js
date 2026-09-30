// 翻译由服务端执行，状态观察独立于下载 worker；只保留原始状态，不展示上游错误正文。
const UserscriptNovelTranslation = (() => {
    const pending = new Map();
    let timer = null;
    let stopped = false;
    let polling = false;
    const phases = ['QUEUED', 'WAITING_SERIES', 'RESOLVING', 'TRANSLATING', 'MERGING',
        'DONE', 'SAME_LANGUAGE', 'FAILED', 'UNAVAILABLE', 'STOPPED'];
    function label(item) {
        if (!item.translatePhase) return '';
        let phase = phases.includes(item.translatePhase) ? item.translatePhase : 'UNAVAILABLE';
        if (!pending.has(item) && ['QUEUED', 'WAITING_SERIES', 'RESOLVING', 'TRANSLATING', 'MERGING'].includes(phase)) phase = 'STOPPED';
        return UserscriptDownloadOptions.text('translate.' + phase, {
            seconds: item.translateElapsed || 0, pending: item.translateSeriesPending || 0
        });
    }
    function finish(item, watch, phase) {
        pending.delete(item);
        if (!stopped && watch.base === serverBase && watch.uuid === userUUID && watch.current()) { item.translatePhase = phase; watch.update(); }
    }
    async function poll(item, watch) {
        if (!watch.current() || watch.base !== serverBase || watch.uuid !== userUUID || stopped) { pending.delete(item); return; }
        if (Date.now() - watch.started > 30 * 60 * 1000) { finish(item, watch, 'STOPPED'); return; }
        try {
            const path = watch.waitDownload ? '/api/novel/status/' : '/api/novel/translate-status/';
            const result = await UserscriptDownloadOptions.request(path + encodeURIComponent(watch.id), watch.base);
            if (!watch.current() || watch.base !== serverBase || watch.uuid !== userUUID || stopped || pending.get(item) !== watch) return;
            if ([401, 403, 404].includes(result.status)) { finish(item, watch, 'UNAVAILABLE'); return; }
            const status = result.data;
            if (result.status !== 200 || !status) {
                if (++watch.missing >= 10) finish(item, watch, 'UNAVAILABLE');
                return;
            }
            if (watch.waitDownload) {
                if (status.failed) { finish(item, watch, 'UNAVAILABLE'); return; }
                if (status.completed) watch.waitDownload = false;
                return;
            }
            if (!phases.includes(status.phase)) {
                if (++watch.missing >= 10) finish(item, watch, 'UNAVAILABLE');
                return;
            }
            watch.missing = 0;
            item.translatePhase = status.failed ? 'FAILED' : status.done && status.phase !== 'SAME_LANGUAGE' ? 'DONE' : status.phase;
            item.translateElapsed = Math.max(0, Number(status.elapsedSeconds) || 0);
            item.translateSeriesPending = Math.max(0, Number(status.seriesPending) || 0);
            watch.update();
            if (status.done || status.failed || ['DONE', 'FAILED', 'SAME_LANGUAGE'].includes(status.phase)) pending.delete(item);
        } catch (_) {
            if (pending.get(item) === watch && ++watch.missing >= 10) finish(item, watch, 'UNAVAILABLE');
        }
    }
    async function tick() {
        timer = null;
        if (stopped) return;
        polling = true;
        const batch = [...pending.entries()].slice(0, 4);
        // 轮转观察，避免较早的长篇翻译独占状态请求。
        for (const [item, watch] of batch) { pending.delete(item); pending.set(item, watch); }
        await Promise.all(batch.map(([item, watch]) => poll(item, watch)));
        polling = false;
        if (pending.size && !stopped) timer = setTimeout(tick, 1500);
    }
    function watch(item, id, current, update, waitDownload = false) {
        if (stopped) return;
        const entry = {id, current, update, waitDownload, base: serverBase, uuid: userUUID, started: Date.now(), missing: 0};
        // ponytail: 每脚本最多观察 128 项；更大批次的完整观察交给后端工作台。
        if (pending.size >= 128 && !pending.has(item)) { finish(item, entry, 'STOPPED'); return; }
        item.translatePhase = 'QUEUED';
        pending.set(item, entry);
        update();
        if (timer === null && !polling && !stopped) timer = setTimeout(tick, 1500);
    }
    window.addEventListener('pagehide', () => {
        for (const [item, entry] of pending) finish(item, entry, 'STOPPED');
        stopped = true;
        clearTimeout(timer);
        timer = null;
        pending.clear();
    });
    window.addEventListener('pageshow', () => { stopped = false; });
    return {watch, label};
})();
