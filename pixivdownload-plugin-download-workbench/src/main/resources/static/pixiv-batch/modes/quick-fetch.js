'use strict';
    function reconcileQuickTypeAvailability(ready = true) {
        const registry = window.PixivBatch.queueTypes;
        const sourceChanged = renderQuickDataSourceSwitcher(ready === false);
        const outerOwnerType = quickState.ownerType || quickState.kind;
        const outerStale = outerOwnerType && !registry.supports(outerOwnerType, 'quick');
        const innerStale = quickInner.kind && !registry.supports(quickInner.kind, 'quick');
        if (!outerStale && !innerStale && !sourceChanged) return false;
        quickResetView();
        quickState.accountSeq++;
        quickState.uid = null;
        quickState.accountOwner = null;
        quickRenderEmpty(ready !== false && (outerStale || innerStale)
            ? bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停')
            : bt('quick.preview.empty', '点击上方按钮加载内容'));
        const toolbar = document.getElementById('quick-preview-toolbar');
        if (toolbar) toolbar.style.display = 'none';
        ['quick-add-page', 'quick-add-all'].forEach(id => {
            const button = document.getElementById(id);
            if (!button) return;
            button.style.display = 'none';
            button.disabled = true;
        });
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.quick = window.PixivBatch.modes.quick || {};
window.PixivBatch.modes.quick = Object.assign(window.PixivBatch.modes.quick, { quickLoad, quickAddCurrentPageToQueue, quickAddAllToQueue, quickCloseInner, quickInnerAddCurrentPageToQueue, quickInnerAddAllToQueue, quickScheduleSource, quickHasWorksGrid, quickCurrentKind, quickReapplyFilters, syncQuickQueueState, renderQuickDataSourceSwitcher, selectQuickDataSource, reconcileQuickTypeAvailability, invalidateQuickAccount });
