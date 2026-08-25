'use strict';
    function reconcileSeriesTypeAvailability(ready = true) {
        const registry = window.PixivBatch.queueTypes;
        const sourceChanged = renderSeriesDataSourceSwitcher(ready === false);
        const activeKind = seriesState.kind;
        const kindStale = !!activeKind && (!registry.supports(activeKind, 'series')
            || seriesDataSourceIdForOwnerType(activeKind) !== seriesState.dataSourceId
            || seriesState.ownerIdentity !== seriesOwnerIdentity(activeKind));
        if (!kindStale && !sourceChanged) return false;
        const message = ready !== false && kindStale
            ? bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停')
            : bt('status.series-empty', '粘贴当前数据来源支持的系列、合集或关联作品链接');
        clearSeriesPreview(message, true);
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.series = window.PixivBatch.modes.series || {};
window.PixivBatch.modes.series = Object.assign(window.PixivBatch.modes.series, {
    loadSeriesPreview, addCurrentSeriesPageToQueue, addAllSeriesResultsToQueue,
    renderSeriesDataSourceSwitcher, selectSeriesDataSource, reconcileSeriesTypeAvailability
});
