'use strict';

function mountAltDownloadProfiles(root) {
    const draft = scheduleState.editing;
    const target = () => scheduleState.editing?.settings || state.settings;
    const contributions = () => altQueueTypes()?.contributionsOf('settings') || [];
    window.PixivDownloadProfiles.mount(root, {
        draft: !!draft,
        context: target,
        capture: () => window.PixivDownloadProfiles.capture(target(), extraFilters, contributions()),
        selected: () => draft ? draft.profileId : storeGet('pixiv_download_profile_id'),
        select: id => {
            if (draft) draft.profileId = id;
            else storeSet('pixiv_download_profile_id', id);
        },
        apply: async profile => {
            const values = window.PixivDownloadProfiles.prepare(profile, target(), contributions());
            Object.assign(target(), values.settings);
            extraFilters = values.filters;
            saveSettings();
            saveSearchFilterPrefs(extraFilters);
            if (!draft && state.isRunning) ensureWorkers();
            await applyFiltersToCurrentMode();
            syncFilterButtonBadge();
            if (!draft) await flushServerState();
        },
        applied: () => openDownloadOptionsDrawer('settings'),
        translate: key => bt('batch:' + key),
        prompt: (message, value) => abPrompt('batch:profiles.name', message, null, {value}),
        confirm: key => abConfirm('batch:profiles.' + key),
        buttonClass: altComponentClasses('button', 'ab-btn ab-btn--ghost ab-btn--sm'),
        primaryButtonClass: altComponentClasses('button', 'ab-btn ab-btn--primary ab-btn--sm'),
        inputClass: 'ab-input'
    });
}
