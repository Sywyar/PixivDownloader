'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/resources/static');

function harness(layout) {
    const saved = new Map();
    let filters = {tagsExact: ['manual']}, adapter;
    const live = {interval: 1, intervalUnit: 's', concurrent: 1, imageFormats: 'jpg', collectionId: 9};
    const context = vm.createContext({
        window: {PixivBatch: {queueTypes: {contributionsOf: () => contributions}}, PixivBatchAlt: {settings: {}}},
        document: {getElementById: () => ({replaceChildren() {}}), querySelectorAll: () => []},
        state: {settings: live, isRunning: true}, scheduleState: {editing: null}, isAdmin: true,
        extraFilters: filters, structuredClone, console,
        storeGet: key => saved.get(key), storeSet: (key, value) => saved.set(key, value),
        bt: key => key, normalizeFileNameTemplate: value => value || '',
        abConfirm: async key => { assert.ok(key.startsWith('batch:profiles.')); return false; },
        uiConfirmKey: async key => { assert.ok(key.startsWith('profiles.')); return false; },
        altComponentClasses: (_tag, classes) => classes,
        sanitizeFileNamePart() {}, buildDownloadFileNames() {}, normalizeAuthorId() {},
        getSearchFiltersFromUI: () => filters, setSearchFiltersUI: value => { filters = value; },
        handleSearchFilterChange: async () => {}, flushServerState: async () => {},
        applyFiltersToCurrentMode: async () => {}, syncFilterButtonBadge() {},
        ensureWorkers() {}, openDownloadOptionsDrawer() {},
        saveSearchFilterPrefs: () => {},
        altQueueTypes: () => ({contributionsOf: () => contributions})
    });
    const contributions = [{profileSettings: settings => ({imageFormats: settings.imageFormats})}];
    const read = file => vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context);
    read('pixiv-batch/search-filters.js');
    read('pixiv-batch/download-profiles.js');
    const api = context.window.PixivDownloadProfiles;
    context.window.PixivDownloadProfiles = {...api, mount: (_root, value) => { adapter = value; }};
    if (layout === 'classic') {
        read('pixiv-batch/settings-context.js');
        read('pixiv-batch/batch-settings.js');
        read('pixiv-batch/batch-profiles.js');
        context.renderSettings = () => {};
        context.syncSettings = () => context.saveSettings();
    } else {
        read('pixiv-batch-alt/alt-settings.js');
        read('pixiv-batch-alt/alt-profiles.js');
    }
    function mount() {
        vm.runInContext(layout === 'classic' ? 'mountDownloadProfiles()'
            : 'mountAltDownloadProfiles({querySelector: () => null})', context);
        return adapter;
    }
    function draft(value) {
        context.nextDraft = value;
        vm.runInContext(layout === 'classic' ? 'scheduleSettingsDraft = nextDraft' : 'scheduleState.editing = nextDraft', context);
    }
    return {context, live, saved, mount, draft, api};
}

for (const layout of ['classic', 'alt']) {
    test(layout + '：切换立即更新下载参数读取值并独立持久化，直接修改不覆盖方案', async () => {
        const h = harness(layout);
        assert.equal(await h.mount().confirm('overwrite'), false);
        assert.equal(await h.mount().confirm('delete-confirm'), false);
        const profile = {settings: {interval: 4, concurrent: 3, imageFormats: 'png'}, filters: {tagsExact: ['profile']}};
        await h.mount().apply(profile);
        assert.equal(h.live.imageFormats, 'png');
        assert.equal(h.live.concurrent, 3);
        assert.equal(h.context.getIntervalMs(), 4000);
        assert.equal(h.live.collectionId, 9);
        const restored = JSON.parse(h.saved.get('pixiv_batch_settings'));
        assert.equal(restored.imageFormats, 'png');
        assert.equal(restored.collectionId, undefined);
        h.live.concurrent = 7;
        h.context.saveSettings();
        assert.equal(profile.settings.concurrent, 3);
        assert.equal(JSON.parse(h.saved.get('pixiv_batch_settings')).concurrent, 7);
    });

    test(layout + '：计划草稿切换及直接编辑不改手动设置、保存值或正在运行队列，退出后恢复手动目标', async () => {
        const h = harness(layout);
        h.context.saveSettings();
        const before = h.saved.get('pixiv_batch_settings');
        const draft = {settings: {...h.live}, manualFilters: {}, profileId: ''};
        h.draft(draft);
        const adapter = h.mount();
        await adapter.apply({settings: {imageFormats: 'webp', concurrent: 5}, filters: {}});
        adapter.select('saved-profile');
        assert.equal(draft.settings.imageFormats, 'webp');
        assert.equal(draft.profileId, 'saved-profile');
        assert.equal(h.live.imageFormats, 'jpg');
        assert.equal(h.live.concurrent, 1);
        assert.equal(h.context.getIntervalMs(), 1000);
        draft.settings.interval = 9;
        h.context.saveSettings();
        assert.equal(h.saved.get('pixiv_batch_settings'), before);
        assert.equal(h.saved.has('pixiv_download_profile_id'), false);
        h.draft(null);
        assert.equal(h.mount().context(), h.live);
        assert.equal(h.live.interval, 1);
    });
}
