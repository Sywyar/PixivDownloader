'use strict';
    let batchCollectionsRefreshPromise = null;
    const scopedBatchCollectionsRefreshPromises = new WeakMap();
    const AUTO_SAVE_SETTING_IDS = new Set([
        's-interval', 's-image-delay', 's-concurrent', 's-skip', 's-verify-files',
        's-redownload-deleted', 's-bookmark', 's-collection', 's-file-name-template',
        's-novel-format', 's-novel-merge', 's-novel-merge-format',
        's-novel-auto-translate', 's-novel-translate-lang', 's-novel-translate-seg'
    ]);

    function bindDelegatedSettingAutosave(root, onSave) {
        if (!root || typeof root.addEventListener !== 'function') return;
        const save = typeof onSave === 'function' ? onSave : syncSettings;
        const delegatedSave = event => {
            const el = event.target;
            if (!el || !AUTO_SAVE_SETTING_IDS.has(el.id)) return;
            if (event.type === 'input' && el.type !== 'number' && el.type !== 'text') return;
            save();
        };
        root.addEventListener('change', delegatedSave);
        root.addEventListener('input', delegatedSave);
    }

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

    // 当前模式下，作品类型 `type` 是否在取得范围内（决定其设置卡 / 专属筛选是否显示）。
    // 有 kind 单选的模式（user / search / quick）按选中 kind 判定；无 kind 单选、可产出任意类型的模式
    // （单作品导入：文本可含任意链接；系列：kind 由 URL 决定）一律视为「可能适用」→ 显示。
    function typeApplicableInCurrentMode(type) {
        const mode = state.mode;
        if (mode === SINGLE_IMPORT_MODE || mode === 'series') return true;
        if (mode === 'user') return state.settings.userKind === type;
        if (mode === 'search') return state.settings.searchKind === type;
        if (mode === QUICK_FETCH_MODE) {
            // 快捷获取在展示该类型网格（含珍藏集内混合作品）或编辑该类型 / 混合类 quick 任务时显示其设置，
            // 供其格式 / 合订等设置生效并被计划任务快照采用。
            const qk = scheduleEditingQuickSource ? scheduleEditingQuickSource.kind : quickCurrentKind();
            return qk === type || qk === 'mixed';
        }
        return false;
    }

    // 各作品类型贡献的设置卡（如小说的「小说设置」）显隐：类型不可用（其插件禁用 / 未加载）时其设置卡
    // 根本未注入宿主页（slot 缺席），contributionsOf 不含它、无需处理；可用类型按当前模式 + 选中 kind
    // 显隐（与 kind 单选 / 专属筛选同经 queueTypes 可用性单一来源对齐）。插画为内置类型、无独立设置卡。
    // 函数名沿用历史（被多处直接调用 + facade 暴露），其行为已通用、不再写死小说。
    function applyNovelSettingsVisibility() {
        window.PixivBatch.queueTypes.contributionsOf('settings').forEach(s => {
            const card = s.cardId ? document.getElementById(s.cardId) : null;
            if (!card) return;
            card.style.display = typeApplicableInCurrentMode(s.type) ? '' : 'none';
        });
        updateNovelTranslateVisibility();
    }

    function saveSettings() {
        // 「收藏到」（收藏夹选择）不持久化：每次加载都默认为「不加入收藏夹」，仅在当前会话内生效。
        const {collectionId, ...persisted} = state.settings;
        storeSet('pixiv_batch_settings', JSON.stringify(persisted));
    }

    function loadSettings() {
        try {
            const raw = storeGet('pixiv_batch_settings');
            if (raw) {
                const parsed = JSON.parse(raw);
                // 「收藏到」不从存储恢复：忽略历史遗留的持久化值，始终回到默认。
                delete parsed.collectionId;
                Object.assign(state.settings, parsed);
            }
        } catch {
        }
        document.getElementById('s-interval').value = state.settings.interval;
        document.getElementById('s-concurrent').value = state.settings.concurrent;
        document.getElementById('s-skip').checked = state.settings.skipHistory;
        document.getElementById('s-verify-files').checked = state.settings.verifyHistoryFiles ?? false;
        document.getElementById('s-redownload-deleted').checked = state.settings.redownloadDeleted ?? false;
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
        // 持久化的 kind 可能是可见的作品类型，也可能只是来源内部 owner（来源不重复贡献单项切换 UI）。
        // 此时扩展点与 acquisition 均已加载，先按活动 owner 解析；仅 owner 已失效时才据实际 DOM 回退。
        reconcileQueueTypeSettings();
        toggleSkipHistoryOptions();
    }

    function reconcileQueueTypeSettings() {
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        const registry = window.PixivBatch && window.PixivBatch.queueTypes;
        if (controls && registry) {
            let userType = registry.resolveSelectionForMode(state.settings.userKind, 'user');
            let searchType = registry.resolveSelectionForMode(state.settings.searchKind, 'search');
            if (!userType) {
                state.settings.userKind = normalizeKindSetting('user-kind-switcher', state.settings.userKind);
                userType = registry.resolveSelectionForMode(state.settings.userKind, 'user');
            }
            if (!searchType) {
                state.settings.searchKind = normalizeKindSetting('search-kind-switcher', state.settings.searchKind);
                searchType = registry.resolveSelectionForMode(state.settings.searchKind, 'search');
            }
            if (userType) controls.syncType('user', userType);
            if (searchType) controls.syncType('search', searchType);
            const modes = window.PixivBatch.modes || {};
            if (modes.user && typeof modes.user.applyUserSourceKindAvailability === 'function') {
                modes.user.applyUserSourceKindAvailability();
            }
            if (modes.search && typeof modes.search.applySearchSourceKindAvailability === 'function') {
                modes.search.applySearchSourceKindAvailability();
            }
        } else {
            state.settings.userKind = normalizeKindSetting('user-kind-switcher', state.settings.userKind);
            state.settings.searchKind = normalizeKindSetting('search-kind-switcher', state.settings.searchKind);
        }
        applyKindSwitcherUI('user-kind-switcher', state.settings.userKind);
        applyKindSwitcherUI('search-kind-switcher', state.settings.searchKind);
        applySearchKindUI();
        applyNovelSettingsVisibility();
        updateExtraFiltersCardVisibility();
    }

    // kind 单选当前实际可选的 kind 值集合：直接读注入后的单选选项（label[data-kind]）。
    // 取得侧控件据扩展点动态注入（禁用类型的选项缺席），故此集合天然只含可用 kind；宿主内置项
    // （插画、user 模式的约稿）始终在 HTML 里。
    function validKindTokens(switcherId) {
        const root = document.getElementById(switcherId);
        if (!root) return ['illust'];
        const tokens = Array.from(root.querySelectorAll('label[data-kind]'))
            .map(l => l.dataset.kind)
            .filter(Boolean);
        return tokens.length ? tokens : ['illust'];
    }

    // 把一个持久化的 kind 值收敛为「当前单选里实际存在」的值：在则保留，不在（其类型不可用 / 不存在）→ illust。
    function normalizeKindSetting(switcherId, value) {
        return validKindTokens(switcherId).indexOf(value) !== -1 ? value : 'illust';
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
        if (!root || root.dataset.kindSwitcherBound === 'true') return;
        root.dataset.kindSwitcherBound = 'true';
        root.addEventListener('click', event => {
            let label = event.target;
            while (label && label !== root
                    && !(label.tagName && label.tagName.toLowerCase() === 'label')) {
                label = label.parentNode;
            }
            if (!label || label === root || !label.dataset) return;
            // 标签声明什么 kind 就用什么（User 模式新增 'request' = 约稿，发现走约稿接口、渲染按插画）；
            // 切换器的可选 kind 由当前启用的作品类型动态注入（插画为内置项），此处按标签取值即可、不写死具体类型。
            const next = label.dataset.kind || 'illust';
            if (state.settings[settingKey] === next) return;
            state.settings[settingKey] = next;
            applyKindSwitcherUI(switcherId, next);
            saveSettings();
            if (typeof onChange === 'function') onChange(next);
        });
    }

    // 当前模式对应的作品类型——共享「附加筛选」里页数/字数等专属字段的显隐据此切换。选择值可由
    // acquisition owner 的 accepts hook 映射到真实作品类型，宿主不认识来源专属变体。
    function currentModeKind() {
        const qt = window.PixivBatch.queueTypes;
        if (state.mode === 'user') {
            return qt.resolveSelectionForMode(state.settings.userKind, 'user');
        }
        if (state.mode === 'search') {
            return qt.resolveSelectionForMode(state.settings.searchKind, 'search');
        }
        if (state.mode === 'series') {
            return qt.resolveSelectionForMode(seriesState.kind, 'series');
        }
        return 'illust';
    }

    function syncSettings() {
        state.settings.interval = Math.max(0, parseFloat(document.getElementById('s-interval').value) || 0);
        state.settings.imageDelay = Math.max(0, parseFloat(document.getElementById('s-image-delay').value) || 0);
        state.settings.concurrent = Math.max(1, parseInt(document.getElementById('s-concurrent').value) || 1);
        state.settings.skipHistory = document.getElementById('s-skip').checked;
        state.settings.verifyHistoryFiles = document.getElementById('s-verify-files').checked;
        state.settings.redownloadDeleted = document.getElementById('s-redownload-deleted').checked;
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
        const novelCard = document.getElementById('novel-settings-card');
        const novelVisible = !!(novelCard && novelCard.style.display !== 'none');
        const row = document.getElementById('s-novel-auto-translate-row');
        if (row) row.style.display = (isAdmin && novelVisible) ? '' : 'none';
        const on = isAdmin && novelVisible && !!(document.getElementById('s-novel-auto-translate') || {}).checked;
        ['s-novel-translate-lang-row', 's-novel-translate-seg-row', 's-novel-translate-hint'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = on ? '' : 'none';
        });
    }

    function toggleSkipHistoryOptions() {
        const visible = document.getElementById('s-skip').checked ? '' : 'none';
        ['s-verify-files-wrap', 's-redownload-deleted-wrap'].forEach(id => {
            const wrap = document.getElementById(id);
            if (wrap) wrap.style.display = visible;
        });
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

    async function refreshBatchCollections(invocation) {
        const scoped = invocation && typeof invocation.assertActive === 'function'
            ? invocation : null;
        if (!scoped && batchCollectionsRefreshPromise) {
            return batchCollectionsRefreshPromise;
        }
        if (scoped && scoped.signal && scopedBatchCollectionsRefreshPromises.has(scoped.signal)) {
            return scopedBatchCollectionsRefreshPromises.get(scoped.signal);
        }
        const refresh = Promise.resolve().then(async () => {
            if (scoped) scoped.assertActive();
            const canUseCollections = appMode === 'solo' || isAdmin;
            if (!canUseCollections) {
                if (scoped) scoped.assertActive();
                setBatchCollectionSetting(null);
                resetBatchCollectionSelect();
                return {collectionId: null, collections: []};
            }
            try {
                const res = await fetch(BASE + '/api/collections', {
                    credentials: 'same-origin',
                    signal: scoped ? scoped.signal : undefined
                });
                if (scoped) scoped.assertActive();
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
                if (scoped) scoped.assertActive();
                const collections = Array.isArray(data.collections) ? data.collections : [];
                const current = normalizeBatchCollectionId(state.settings.collectionId);
                const validIds = new Set(collections
                    .map(c => normalizeBatchCollectionId(c.id))
                    .filter(id => id !== null));
                const selectedId = current !== null && validIds.has(current) ? current : null;
                setBatchCollectionSetting(selectedId);
                renderBatchCollections(collections, selectedId);
                return {collectionId: selectedId, collections};
            } catch (error) {
                if (scoped) scoped.assertActive();
                setBatchCollectionSetting(null);
                resetBatchCollectionSelect();
                return {collectionId: null, collections: []};
            } finally {
                if (scoped) {
                    if (scoped.signal) scopedBatchCollectionsRefreshPromises.delete(scoped.signal);
                } else {
                    batchCollectionsRefreshPromise = null;
                }
            }
        });
        if (scoped) {
            if (scoped.signal) scopedBatchCollectionsRefreshPromises.set(scoped.signal, refresh);
            return refresh;
        }
        batchCollectionsRefreshPromise = refresh;
        return batchCollectionsRefreshPromise;
    }

    async function resolveBatchCollectionIdForDownload(invocation) {
        const result = await refreshBatchCollections(invocation);
        if (invocation) invocation.assertActive();
        return result.collectionId;
    }


// ---- PixivBatch facade ----
window.PixivBatch.settings = window.PixivBatch.settings || {};
window.PixivBatch.settings = Object.assign(window.PixivBatch.settings, { syncSettings, bindDelegatedSettingAutosave, getIntervalMs, getImageDelayMs, toggleIntervalUnit, toggleImageDelayUnit, applyNovelSettingsVisibility, reconcileQueueTypeSettings, updateNovelTranslateVisibility, refreshNovelTranslateLangDefault, refreshBatchCollections, updateBatchLimitNote, loadSettings, saveSettings });
