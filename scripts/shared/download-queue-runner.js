// 领取前读取当前并发；降低并发不取消已开始的任务，冷却时间跟随当前设置。
async function runUserscriptDownloadQueue(manager, getConcurrent) {
    const active = new Set();
    try {
        while (manager.isRunning && !manager.stopRequested) {
            const configured = Number(getConcurrent());
            const limit = Number.isFinite(configured) ? Math.max(1, Math.floor(configured)) : 1;
            if (!manager.isPaused && active.size < limit) {
                const next = manager._getNextPending();
                if (next) {
                    const work = Promise.resolve().then(() => manager._processSingle(next))
                        .catch(error => console.error(error))
                        .then(async () => {
                            const completedAt = Date.now();
                            while (manager.isRunning && !manager.stopRequested
                                && Date.now() - completedAt < manager.getIntervalMs()) await manager._sleep(100);
                        }).finally(() => {
                            active.delete(work);
                            manager.activeWorkers = active.size;
                        });
                    active.add(work);
                    manager.activeWorkers = active.size;
                    continue;
                }
            }
            if (!manager.isPaused && !active.size && !manager.queue.some(item => item.status === 'pending')) break;
            await manager._sleep(100);
        }
    } finally {
        await Promise.allSettled(active);
        manager.activeWorkers = 0;
    }
}
