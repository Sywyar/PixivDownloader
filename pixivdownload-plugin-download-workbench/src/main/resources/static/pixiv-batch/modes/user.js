'use strict';
    function handleUserModeControlChange(detail) {
        const previousSourceId = selectedUserSourceId(detail && detail.previous);
        const currentSourceId = selectedUserSourceId(detail && detail.selection);
        if (previousSourceId !== currentSourceId) {
            const input = document.getElementById('user-id-input');
            const draftSourceId = previousSourceId || userInputDraftSourceId;
            if (input) saveUserInputDraft(draftSourceId, input.value);
            if (currentSourceId) {
                saveUserDataSourceSelection(currentSourceId);
                restoreUserInputDraft(currentSourceId);
            }
        }
        const kindChanged = applyUserSourceKindAvailability();
        if (kindChanged) saveSettings();
        clearUserPreview();
        applyNovelSettingsVisibility();
        applySearchKindUI();
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
    }

    function reconcileUserTypeAvailability(ready = true) {
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        const result = controls ? controls.render('user', ready === false) : null;
        const kindChanged = ready === false ? false : applyUserSourceKindAvailability();
        const allowed = new Set(selectedUserSourceTypes());
        const kindStale = !!userState.kind && (!window.PixivBatch.queueTypes.supports(userState.kind, 'user')
            || (allowed.size && !allowed.has(userState.kind)));
        const selectionChanged = !!result && (result.sourceChanged || result.typeChanged);
        if (!kindChanged && !kindStale && !selectionChanged) return false;
        if (kindChanged) saveSettings();
        clearUserPreview();
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.user = window.PixivBatch.modes.user || {};
window.PixivBatch.modes.user = Object.assign(window.PixivBatch.modes.user, {
    loadUserPreview, addCurrentUserPageToQueue, addAllUserResultsToQueue,
    applyUserSourceKindAvailability, handleUserModeControlChange, reconcileUserTypeAvailability,
    initUserInputDraftPersistence, saveUserInputDraft, restoreUserInputDraft
});
