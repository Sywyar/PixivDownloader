'use strict';

    /* ============================================================
       User 模式预览（对齐 Search / 系列：先渲染预览网格 + 分页，
       再由「将此页加入队列」「全部加入队列」入队；附加筛选实时过滤当前页）
    ============================================================ */
    const USER_PAGE_SIZE = 30;
    const USER_INPUT_DRAFT_STORAGE_PREFIX = 'pixiv_batch_user_input:';
    const USER_DATA_SOURCE_STORAGE_KEY = 'pixiv_batch_user_data_source';
    const LEGACY_USER_INPUT_STORAGE_KEYS = Object.freeze([
        'pixiv_batch_last_user_id',
        'pixiv_batch_last_username'
    ]);
    let userInputDraftBoundElement = null;
    let userInputDraftSourceId = '';
    let userRequestController = null;
    let userOperationController = null;

    let userState = {
        kind: null,
        variant: null,
        userId: '',
        username: '',
        allIds: [],
        total: 0,
        pagedAcquisition: false,
        pageCache: new Map(),
        pageCursors: new Map(),
        currentPage: 1,
        totalPages: 1,
        rawItems: [],   // 当前页未过滤的卡片
        items: [],      // 当前页经附加筛选后的卡片（渲染 / 「加入此页」据此）
        cardCache: new Map(), // kind+id -> 卡片元数据，翻页 / 改筛选时复用避免重复请求
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        renderToken: 0,
        activeBlobUrls: [],
        filterSeq: 0,
        requestSeq: 0
    };

    function abortUserRequests() {
        const controllers = [userOperationController, userRequestController];
        userOperationController = null;
        userRequestController = null;
        controllers.forEach(controller => {
            if (!controller || controller.signal.aborted) return;
            try { controller.abort(); } catch (e) { /* best effort */ }
        });
    }
    function beginUserRequestGeneration() {
        abortUserRequests();
        userRequestController = new AbortController();
    }

    function beginUserOperation() {
        const previous = userOperationController;
        userOperationController = new AbortController();
        if (!previous || previous.signal.aborted) return;
        try { previous.abort(); } catch (e) { /* best effort */ }
    }

    function linkedUserRequestSignal(...signals) {
        const sources = [
            userRequestController && userRequestController.signal,
            userOperationController && userOperationController.signal,
            ...signals
        ]
            .filter((signal, index, all) => signal && all.indexOf(signal) === index);
        if (!sources.length) return {signal: null, dispose() {}};
        if (sources.length === 1) return {signal: sources[0], dispose() {}};

        const controller = new AbortController();
        const listeners = [];
        const abort = source => {
            if (controller.signal.aborted) return;
            try { controller.abort(source && source.reason); } catch (e) { controller.abort(); }
        };
        sources.forEach(signal => {
            if (signal.aborted) {
                abort(signal);
                return;
            }
            const listener = () => abort(signal);
            signal.addEventListener('abort', listener, {once: true});
            listeners.push([signal, listener]);
        });
        return {
            signal: controller.signal,
            dispose() {
                listeners.forEach(([signal, listener]) => signal.removeEventListener('abort', listener));
            }
        };
    }

    function userCardCacheKey(id) {
        return String(userState.kind || '') + ':' + String(id);
    }

    // 当前 user 模式作品类型的取得钩子；类型不可用时返回 null，由调用方停止该模式请求。
    function userAcq() {
        return window.PixivBatch.queueTypes.acquisition(userState.kind, 'user');
    }
    function userAcquisitionContext() {
        return {
            userId: userState.userId,
            username: userState.username,
            variant: userState.variant
        };
    }
    function userEmptyMessage() {
        const fallback = bt('status.user-no-artworks', '该用户暂无作品');
        const acq = userAcq();
        if (!acq || typeof acq.emptyMessage !== 'function') return fallback;
        try {
            const message = acq.emptyMessage(userAcquisitionContext());
            return typeof message === 'string' && message.trim() ? message : fallback;
        } catch (e) {
            console.warn('[user] 获取空态文案失败：', e);
            return fallback;
        }
    }
    function userQueueId(item) {
        const acq = userAcq();
        return acq.queueId(item);
    }
    // 队列预览卡片元素 id（小说卡 / 插画缩略图 id 前缀不同，由该类型 user 钩子贡献）。
    function userCardElementId(idx) {
        const acq = userAcq();
        return acq.cardId(idx);
    }

    function selectedUserSourceTypes() {
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        const selected = controls ? controls.selection('user') : null;
        if (!selected || !selected.sourceId) return [];
        return window.PixivBatch.queueTypes.typesForDataSource('user', selected.sourceId)
            .map(candidate => candidate.type);
    }

    function selectedUserSourceId(selection) {
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        const current = selection || (controls ? controls.selection('user') : null);
        return current && current.sourceId != null ? String(current.sourceId).trim() : '';
    }

    function userInputDraftStorageKey(sourceId) {
        const normalized = sourceId == null ? '' : String(sourceId).trim();
        return normalized ? USER_INPUT_DRAFT_STORAGE_PREFIX + normalized : null;
    }

    function saveUserDataSourceSelection(sourceId) {
        const normalized = sourceId == null ? '' : String(sourceId).trim();
        if (!normalized) return false;
        try {
            storeSet(USER_DATA_SOURCE_STORAGE_KEY, normalized);
            return true;
        } catch (e) {
            console.warn('[user] 保存数据来源选择失败：', e);
            return false;
        }
    }

    function restoreUserDataSourceSelection() {
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        if (!controls) return '';
        let savedSourceId = '';
        try {
            const saved = storeGet(USER_DATA_SOURCE_STORAGE_KEY);
            savedSourceId = saved == null ? '' : String(saved).trim();
            if (savedSourceId
                && typeof controls.selectSource === 'function') {
                controls.selectSource('user', savedSourceId, false);
            }
        } catch (e) {
            console.warn('[user] 读取数据来源选择失败：', e);
        }
        const current = selectedUserSourceId();
        if (current) saveUserDataSourceSelection(current);
        return current || savedSourceId;
    }

    function saveUserInputDraft(sourceId, value) {
        const key = userInputDraftStorageKey(sourceId);
        if (!key) return false;
        try {
            storeSet(key, String(value == null ? '' : value));
            return true;
        } catch (e) {
            console.warn('[user] 保存分来源输入草稿失败：', e);
            return false;
        }
    }

    function loadUserInputDraft(sourceId) {
        const key = userInputDraftStorageKey(sourceId);
        if (!key) return '';
        try {
            const value = storeGet(key);
            return value == null ? '' : String(value);
        } catch (e) {
            console.warn('[user] 读取分来源输入草稿失败：', e);
            return '';
        }
    }

    function restoreUserInputDraft(sourceId = selectedUserSourceId()) {
        const input = document.getElementById('user-id-input');
        if (!input) return '';
        const normalized = sourceId == null ? '' : String(sourceId).trim();
        if (!normalized) return input.value;
        const value = loadUserInputDraft(normalized);
        input.value = value;
        userInputDraftSourceId = normalized;
        return value;
    }

    function discardLegacyUserInputState() {
        LEGACY_USER_INPUT_STORAGE_KEYS.forEach(key => {
            try {
                if (storeGet(key) != null) storeRemove(key);
            } catch (e) {
                console.warn('[user] 清理旧 User 输入状态失败：', e);
            }
        });
    }

    function initUserInputDraftPersistence() {
        const input = document.getElementById('user-id-input');
        if (!input) return '';
        const sourceId = restoreUserDataSourceSelection();
        const kindChanged = applyUserSourceKindAvailability();
        if (kindChanged) saveSettings();
        if (userInputDraftBoundElement !== input) {
            userInputDraftBoundElement = input;
            input.addEventListener('input', () => {
                saveUserInputDraft(selectedUserSourceId() || userInputDraftSourceId, input.value);
            });
        }
        // 旧键没有来源维度，不能安全迁移；清除后仅恢复当前插件来源自己的原始输入。
        discardLegacyUserInputState();
        return restoreUserInputDraft(sourceId);
    }

    function userKindOwner(kind) {
        const allowed = new Set(selectedUserSourceTypes());
        const acquisitions = window.PixivBatch.queueTypes.acquisitionList('user')
            .filter(candidate => !allowed.size || allowed.has(candidate.type));
        const direct = acquisitions.find(candidate => candidate.type === kind);
        if (direct) return direct.type;
        const matches = acquisitions.filter(candidate => {
            if (typeof candidate.accepts !== 'function') return false;
            try {
                return candidate.accepts(kind);
            } catch (e) {
                console.warn('[batch] user kind ownership check failed:', candidate.type, e);
                return false;
            }
        });
        return matches.length === 1 ? matches[0].type : null;
    }

    // 数据来源只约束旧 kind switcher 中哪些插件项可用；来源本身不重复渲染成作品类型。
    // 当前来源不足两个可见选项时隐藏切换器，但仍把内部 setting 收敛到该来源 owner。
    function applyUserSourceKindAvailability() {
        const root = document.getElementById('user-kind-switcher');
        const allowed = new Set(selectedUserSourceTypes());
        if (!root) return false;
        if (!allowed.size) {
            root.hidden = true;
            if (root.style) root.style.display = 'none';
            return false;
        }
        const labels = Array.from(root.querySelectorAll('label[data-kind]'));
        const visibleLabels = [];
        labels.forEach(label => {
            const visible = allowed.has(userKindOwner(label.dataset.kind));
            label.hidden = !visible;
            if (label.style) label.style.display = visible ? '' : 'none';
            const input = label.querySelector('input[type=radio]');
            if (input) input.disabled = !visible;
            if (visible) visibleLabels.push(label);
        });
        root.hidden = visibleLabels.length < 2;
        if (root.style) root.style.display = root.hidden ? 'none' : '';

        const currentOwner = userKindOwner(state.settings.userKind);
        if (allowed.has(currentOwner)) {
            applyKindSwitcherUI('user-kind-switcher', state.settings.userKind);
            return false;
        }
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        const preferredType = controls ? controls.selection('user').type : null;
        const fallback = visibleLabels.find(label =>
            userKindOwner(label.dataset.kind) === preferredType)
            || visibleLabels[0];
        const nextKind = fallback ? fallback.dataset.kind
            : (allowed.has(preferredType) ? preferredType : null);
        if (!nextKind || state.settings.userKind === nextKind) return false;
        state.settings.userKind = nextKind;
        applyKindSwitcherUI('user-kind-switcher', state.settings.userKind);
        return true;
    }

    function resolveUserSelection(selection, rawInput) {
        const sourceTypes = selectedUserSourceTypes();
        const entries = window.PixivBatch.queueTypes.acquisitionList('user')
            .filter(candidate => !sourceTypes.length || sourceTypes.includes(candidate.type));
        let variant = selection;
        let entry = null;
        for (const candidate of entries) {
            try {
                if (typeof candidate.accepts === 'function'
                    ? candidate.accepts(selection) : candidate.type === selection) {
                    entry = candidate;
                    break;
                }
            } catch (e) {
                console.warn('[user] 用户类型选择钩子失败：', candidate.type, e);
            }
        }
        for (const candidate of entries) {
            if (typeof candidate.detectVariant !== 'function') continue;
            try {
                const detected = candidate.detectVariant(rawInput, selection);
                if (detected && (typeof candidate.accepts !== 'function' || candidate.accepts(detected))) {
                    entry = candidate;
                    variant = detected;
                    break;
                }
            } catch (e) {
                console.warn('[user] 用户类型变体钩子失败：', candidate.type, e);
            }
        }
        if (!entry) {
            const type = window.PixivBatch.queueTypes.resolveTypeForMode(selection, 'user');
            entry = entries.find(candidate => candidate.type === type) || null;
        }
        return entry ? {type: entry.type, variant, acquisition: entry} : null;
    }
