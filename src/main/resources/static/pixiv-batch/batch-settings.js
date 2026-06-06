'use strict';
    let batchCollectionsRefreshPromise = null;

    function getIntervalMs() {
        const {interval, intervalUnit} = state.settings;
        return intervalUnit === 's' ? Math.round(interval * 1000) : Math.round(interval);
    }

    function getImageDelayMs() {
        const {imageDelay, imageDelayUnit} = state.settings;
        return imageDelayUnit === 's' ? Math.round(imageDelay * 1000) : Math.round(imageDelay);
    }

    function toggleImageDelayUnit() {
        const btn = document.getElementById('s-image-delay-unit');
        const input = document.getElementById('s-image-delay');
        const cur = parseFloat(input.value) || 0;
        if (state.settings.imageDelayUnit === 's') {
            state.settings.imageDelayUnit = 'ms';
            input.value = Math.round(cur * 1000);
            btn.textContent = 'ms';
        } else {
            state.settings.imageDelayUnit = 's';
            input.value = +(cur / 1000).toFixed(3);
            btn.textContent = 's';
        }
        state.settings.imageDelay = parseFloat(input.value) || 0;
        saveSettings();
    }

    function toggleIntervalUnit() {
        const btn = document.getElementById('s-interval-unit');
        const input = document.getElementById('s-interval');
        const cur = parseFloat(input.value) || 0;
        if (state.settings.intervalUnit === 's') {
            state.settings.intervalUnit = 'ms';
            input.value = Math.round(cur * 1000);
            btn.textContent = 'ms';
        } else {
            state.settings.intervalUnit = 's';
            input.value = +(cur / 1000).toFixed(3);
            btn.textContent = 's';
        }
        state.settings.interval = parseFloat(input.value) || 0;
        saveSettings();
    }

    function applyNovelSettingsVisibility() {
        const card = document.getElementById('novel-settings-card');
        if (!card) return;
        const mode = state.mode;
        let visible;
        if (mode === SINGLE_IMPORT_MODE) {
            visible = true;
        } else if (mode === 'user') {
            visible = state.settings.userKind === 'novel';
        } else if (mode === 'search') {
            visible = state.settings.searchKind === 'novel';
        } else if (mode === 'series') {
            visible = true;
        } else if (mode === QUICK_FETCH_MODE) {
            // 快捷获取在展示小说网格（含珍藏集内混合作品）或编辑小说/混合类 quick 任务时显示小说设置，
            // 供其格式 / 合订设置生效并被计划任务快照采用。
            const qk = scheduleEditingQuickSource ? scheduleEditingQuickSource.kind : quickCurrentKind();
            visible = qk === 'novel' || qk === 'mixed';
        } else {
            visible = false;
        }
        card.style.display = visible ? '' : 'none';
        updateNovelTranslateVisibility();
    }

    function saveSettings() {
        storeSet('pixiv_batch_settings', JSON.stringify(state.settings));
    }

    function loadSettings() {
        try {
            const raw = storeGet('pixiv_batch_settings');
            if (raw) Object.assign(state.settings, JSON.parse(raw));
        } catch {
        }
        document.getElementById('s-interval').value = state.settings.interval;
        document.getElementById('s-concurrent').value = state.settings.concurrent;
        document.getElementById('s-skip').checked = state.settings.skipHistory;
        document.getElementById('s-verify-files').checked = state.settings.verifyHistoryFiles ?? false;
        document.getElementById('s-bookmark').checked = state.settings.bookmark ?? false;
        document.getElementById('s-image-delay').value = state.settings.imageDelay ?? 0;
        state.settings.fileNameTemplate = normalizeFileNameTemplate(state.settings.fileNameTemplate);
        document.getElementById('s-file-name-template').value = state.settings.fileNameTemplate;
        const unit = state.settings.intervalUnit || 's';
        state.settings.intervalUnit = unit;
        document.getElementById('s-interval-unit').textContent = unit;
        const imgUnit = state.settings.imageDelayUnit || 'ms';
        state.settings.imageDelayUnit = imgUnit;
        document.getElementById('s-image-delay-unit').textContent = imgUnit;
        if (state.settings.collectionId) {
            const sel = document.getElementById('s-collection');
            sel.value = String(state.settings.collectionId);
        }
        const fmtEl = document.getElementById('s-novel-format');
        if (fmtEl) fmtEl.value = (state.settings.novelFormat || 'txt');
        const mergeEl = document.getElementById('s-novel-merge');
        if (mergeEl) mergeEl.checked = !!state.settings.mergeNovelSeries;
        const mergeFmtEl = document.getElementById('s-novel-merge-format');
        if (mergeFmtEl) mergeFmtEl.value = (state.settings.mergeNovelFormat || 'epub');
        const autoTrEl = document.getElementById('s-novel-auto-translate');
        if (autoTrEl) autoTrEl.checked = !!state.settings.novelAutoTranslate;
        const trLangEl = document.getElementById('s-novel-translate-lang');
        if (trLangEl) trLangEl.value = state.settings.novelTranslateLang || defaultNovelTranslateLang();
        const trSegEl = document.getElementById('s-novel-translate-seg');
        if (trSegEl) trSegEl.value = state.settings.novelTranslateSeg ?? 0;
        updateMergeFormatVisibility();
        updateNovelTranslateVisibility();
        state.settings.userKind = state.settings.userKind === 'novel' ? 'novel' : 'illust';
        state.settings.searchKind = state.settings.searchKind === 'novel' ? 'novel' : 'illust';
        applyKindSwitcherUI('user-kind-switcher', state.settings.userKind);
        applyKindSwitcherUI('search-kind-switcher', state.settings.searchKind);
        applySearchKindUI();
        applyNovelSettingsVisibility();
        toggleSkipHistoryOptions();
    }

    function applyKindSwitcherUI(switcherId, value) {
        const root = document.getElementById(switcherId);
        if (!root) return;
        root.querySelectorAll('label').forEach(lbl => {
            const matches = lbl.dataset.kind === value;
            lbl.classList.toggle('active', matches);
            const input = lbl.querySelector('input[type=radio]');
            if (input) input.checked = matches;
        });
    }

    function bindKindSwitcher(switcherId, settingKey, onChange) {
        const root = document.getElementById(switcherId);
        if (!root) return;
        root.querySelectorAll('label').forEach(lbl => {
            lbl.addEventListener('click', () => {
                const next = lbl.dataset.kind === 'novel' ? 'novel' : 'illust';
                if (state.settings[settingKey] === next) return;
                state.settings[settingKey] = next;
                applyKindSwitcherUI(switcherId, next);
                saveSettings();
                if (typeof onChange === 'function') onChange(next);
            });
        });
    }

    // 当前模式对应的作品类型（插画/小说）——共享「附加筛选」里页数/字数字段的显隐据此切换
    function currentModeKind() {
        if (state.mode === 'user') return state.settings.userKind === 'novel' ? 'novel' : 'illust';
        if (state.mode === 'search') return state.settings.searchKind === 'novel' ? 'novel' : 'illust';
        if (state.mode === 'series') return seriesState.kind === 'novel' ? 'novel' : 'illust';
        return 'illust';
    }

    function syncSettings() {
        state.settings.interval = Math.max(0, parseFloat(document.getElementById('s-interval').value) || 0);
        state.settings.imageDelay = Math.max(0, parseFloat(document.getElementById('s-image-delay').value) || 0);
        state.settings.concurrent = Math.max(1, parseInt(document.getElementById('s-concurrent').value) || 1);
        state.settings.skipHistory = document.getElementById('s-skip').checked;
        state.settings.verifyHistoryFiles = document.getElementById('s-verify-files').checked;
        state.settings.bookmark = document.getElementById('s-bookmark').checked;
        state.settings.fileNameTemplate = normalizeFileNameTemplate(document.getElementById('s-file-name-template').value);
        const sel = document.getElementById('s-collection');
        state.settings.collectionId = sel.value ? Number(sel.value) : null;
        const fmtEl = document.getElementById('s-novel-format');
        if (fmtEl) state.settings.novelFormat = (fmtEl.value || 'txt').toLowerCase();
        const mergeEl = document.getElementById('s-novel-merge');
        if (mergeEl) state.settings.mergeNovelSeries = !!mergeEl.checked;
        const mergeFmtEl = document.getElementById('s-novel-merge-format');
        if (mergeFmtEl) state.settings.mergeNovelFormat = (mergeFmtEl.value || 'epub').toLowerCase();
        const autoTrEl = document.getElementById('s-novel-auto-translate');
        if (autoTrEl) state.settings.novelAutoTranslate = !!autoTrEl.checked;
        const trLangEl = document.getElementById('s-novel-translate-lang');
        if (trLangEl) {
            // 与当前页面语言的默认目标语言相同（或留空）时存空 = 「跟随页面语言」，不把默认译名烤进模型；
            // 仅当用户填入不同文本时才记为自定义值。
            const v = (trLangEl.value || '').trim();
            state.settings.novelTranslateLang = (v && v !== defaultNovelTranslateLang()) ? v : '';
        }
        const trSegEl = document.getElementById('s-novel-translate-seg');
        if (trSegEl) state.settings.novelTranslateSeg = Math.max(0, parseInt(trSegEl.value, 10) || 0);
        updateMergeFormatVisibility();
        updateNovelTranslateVisibility();
        toggleSkipHistoryOptions();
        saveSettings();
        // 下载设置实时生效：运行中调高并发数时立即补足 worker（调低由 workerLoop 自行收敛）。
        if (state.isRunning) ensureWorkers();
    }

    // 合订本格式选择仅在勾选「生成合订本」时可见
    function updateMergeFormatVisibility() {
        const on = !!(document.getElementById('s-novel-merge') || {}).checked;
        ['s-novel-merge-format-row', 's-novel-merge-format-hint'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = on ? '' : 'none';
        });
    }

    // 语言切换 / 初次加载：用户未自定义目标语言（模型为空）时，让输入框跟随当前页面语言显示默认目标语言。
    // 用户已自定义（模型非空）则保留其填写值，不被语言切换覆盖。
    function refreshNovelTranslateLangDefault() {
        if (state.settings.novelTranslateLang) return;
        const el = document.getElementById('s-novel-translate-lang');
        if (el) el.value = defaultNovelTranslateLang();
    }

    // 「新下载小说自动翻译」：整组仅管理员可见（翻译 admin-only + 需 AI）；目标语言 / 分段字数 / 提示
    // 仅在管理员勾选后展开。
    function updateNovelTranslateVisibility() {
        const row = document.getElementById('s-novel-auto-translate-row');
        if (row) row.style.display = isAdmin ? '' : 'none';
        const on = isAdmin && !!(document.getElementById('s-novel-auto-translate') || {}).checked;
        ['s-novel-translate-lang-row', 's-novel-translate-seg-row', 's-novel-translate-hint'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = on ? '' : 'none';
        });
    }

    function toggleSkipHistoryOptions() {
        const wrap = document.getElementById('s-verify-files-wrap');
        if (!wrap) return;
        wrap.style.display = document.getElementById('s-skip').checked ? '' : 'none';
    }

    function updateBatchLimitNote() {
        const note = document.getElementById('batch-limit-note');
        if (!note) return;
        const limit = batchPageLimit();
        if (limit > 0) {
            note.textContent = bt('search.batch.note.multi-limited',
                'multi 模式：每次最多 {limit} 页', {limit});
        } else if (appMode === 'solo') {
            note.textContent = bt('search.batch.note.solo', 'solo 模式：不限制');
        } else {
            note.textContent = bt('search.batch.note.multi-unlimited', 'multi 模式：不限制');
        }
    }

    /* ============================================================
       收藏夹选择器（solo / multi 管理员）
    ============================================================ */
    function normalizeBatchCollectionId(value) {
        if (value === null || value === undefined || value === '') return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
    }

    function setBatchCollectionSetting(value) {
        const next = normalizeBatchCollectionId(value);
        const current = normalizeBatchCollectionId(state.settings.collectionId);
        state.settings.collectionId = next;
        if (current === next) return false;
        saveSettings();
        return true;
    }

    function resetBatchCollectionSelect() {
        const sel = document.getElementById('s-collection');
        sel.innerHTML = '';
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = bt('option.collection.none', '（不加入收藏夹）');
        sel.appendChild(opt);
        sel.value = '';
        document.getElementById('s-collection-wrap').style.display = 'none';
    }

    function renderBatchCollections(collections, selectedId) {
        const sel = document.getElementById('s-collection');
        resetBatchCollectionSelect();
        collections.forEach(c => {
            const opt = document.createElement('option');
            opt.value = String(c.id);
            opt.textContent = c.name;
            sel.appendChild(opt);
        });
        if (collections.length > 0) {
            document.getElementById('s-collection-wrap').style.display = '';
        }
        sel.value = selectedId === null ? '' : String(selectedId);
    }

    async function refreshBatchCollections() {
        if (batchCollectionsRefreshPromise) {
            return batchCollectionsRefreshPromise;
        }
        batchCollectionsRefreshPromise = (async () => {
            const canUseCollections = appMode === 'solo' || isAdmin;
            if (!canUseCollections) {
                setBatchCollectionSetting(null);
                resetBatchCollectionSelect();
                return {collectionId: null, collections: []};
            }
            try {
                const res = await fetch(BASE + '/api/collections', {credentials: 'same-origin'});
                if (!res.ok) {
                    if (res.status === 401 || res.status === 403) {
                        isAdmin = false;
                        updateAuthButtons();
                    }
                    setBatchCollectionSetting(null);
                    resetBatchCollectionSelect();
                    return {collectionId: null, collections: []};
                }
                const data = await res.json();
                const collections = Array.isArray(data.collections) ? data.collections : [];
                const current = normalizeBatchCollectionId(state.settings.collectionId);
                const validIds = new Set(collections
                    .map(c => normalizeBatchCollectionId(c.id))
                    .filter(id => id !== null));
                const selectedId = current !== null && validIds.has(current) ? current : null;
                setBatchCollectionSetting(selectedId);
                renderBatchCollections(collections, selectedId);
                return {collectionId: selectedId, collections};
            } catch {
                setBatchCollectionSetting(null);
                resetBatchCollectionSelect();
                return {collectionId: null, collections: []};
            } finally {
                batchCollectionsRefreshPromise = null;
            }
        })();
        return batchCollectionsRefreshPromise;
    }

    async function resolveBatchCollectionIdForDownload() {
        const result = await refreshBatchCollections();
        return result.collectionId;
    }


// ---- PixivBatch facade ----
window.PixivBatch.settings = window.PixivBatch.settings || {};
window.PixivBatch.settings = Object.assign(window.PixivBatch.settings, { syncSettings, getIntervalMs, getImageDelayMs, toggleIntervalUnit, toggleImageDelayUnit, applyNovelSettingsVisibility, updateNovelTranslateVisibility, refreshNovelTranslateLangDefault, refreshBatchCollections, updateBatchLimitNote, loadSettings, saveSettings });
