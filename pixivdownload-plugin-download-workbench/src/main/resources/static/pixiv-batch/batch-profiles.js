'use strict';

function mountDownloadProfiles() {
    const root = document.getElementById('download-profiles');
    if (!root) return;
    root.hidden = !isAdmin;
    root.replaceChildren();
    const draft = scheduleSettingsDraft;
    const contributions = () => window.PixivBatch.queueTypes.contributionsOf('settings');
    window.PixivDownloadProfiles.mount(root, {
        draft: !!draft,
        context: () => settingsTarget(),
        capture: () => window.PixivDownloadProfiles.capture(settingsTarget(), getSearchFiltersFromUI(), contributions()),
        selected: () => draft ? draft.profileId : storeGet('pixiv_download_profile_id'),
        select: id => {
            if (draft) draft.profileId = id;
            else storeSet('pixiv_download_profile_id', id);
        },
        apply: async profile => {
            const values = window.PixivDownloadProfiles.prepare(profile, settingsTarget(), contributions());
            Object.assign(settingsTarget(), values.settings);
            renderSettings();
            setSearchFiltersUI(values.filters);
            syncSettings();
            await handleSearchFilterChange();
            if (!draft) await flushServerState();
        },
        translate: key => bt('batch:' + key),
        prompt: (message, value) => uiPromptKey('profiles.name', message, value),
        confirm: key => uiConfirmKey('profiles.' + key),
        buttonClass: 'btn btn-gray',
        primaryButtonClass: 'btn btn-blue'
    });
}
