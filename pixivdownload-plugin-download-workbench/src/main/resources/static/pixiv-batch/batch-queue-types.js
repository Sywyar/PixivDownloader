'use strict';
// 下载类型前端运行时：后端 manifest 是类型、owner、代际与取得模式的唯一事实源。
// 动态模块只能在其真实 <script> 求值期间登记 initializer，不能自报或覆盖 type/owner。
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.queueTypes = (function () {
    const CONTRACT_VERSION = 1;
    const ENDPOINT = '/api/download/extensions';
    const INITIALIZER_TIMEOUT_MS = 5000;
    const SCRIPT_LOAD_TIMEOUT_MS = 5000;
    const KNOWN_MODES = new Set(['single-import', 'user', 'search', 'series', 'quick']);
    const QUEUE_TAG_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;
    const MAX_QUEUE_TAGS = 8;
    const MAX_QUEUE_TAG_LABEL_LENGTH = 48;
    const QUEUE_LIVE_STATUS_TONES = new Set(['info', 'success', 'warning', 'error']);
    const MAX_QUEUE_LIVE_STATUS_LABEL_LENGTH = 48;
    const MAX_QUEUE_LIVE_STATUS_MESSAGE_LENGTH = 256;
    const MAX_CANCEL_WORK_KEY_LENGTH = 4096;
    const EMPTY_QUEUE_TAGS = Object.freeze([]);
    const SLOT_MODE = Object.freeze({
        'kind-option-user': 'user',
        'kind-option-search': 'search',
        'kind-option-quick': 'quick',
        'quick-actions-bookmarks': 'quick',
        'quick-actions-mine': 'quick',
        'import-hint': 'single-import'
    });
    const EMPTY = Object.freeze({
        epoch: '',
        revision: -1,
        identity: '',
        manifest: new Map(),
        orderedTypes: [],
        activations: new Map(),
        uiActivations: new Set(),
        uiSlots: [],
        controller: new AbortController(),
        disposers: []
    });

    let current = EMPTY;
    let slotsBootstrapped = false;
    let slotRenderSequence = 0;
    let slotRenderTail = Promise.resolve();
    const slotMounts = new Map();
    const modules = window.PixivBatch.queueTypeRuntimeModules || {};
    if (!modules.normalize || !modules.runtime) {
        throw new Error('queue type runtime modules are unavailable');
    }
    const moduleContext = {
        CONTRACT_VERSION, ENDPOINT, INITIALIZER_TIMEOUT_MS, SCRIPT_LOAD_TIMEOUT_MS,
        KNOWN_MODES, QUEUE_TAG_ID_PATTERN, MAX_QUEUE_TAGS, MAX_QUEUE_TAG_LABEL_LENGTH,
        QUEUE_LIVE_STATUS_TONES, MAX_QUEUE_LIVE_STATUS_LABEL_LENGTH,
        MAX_QUEUE_LIVE_STATUS_MESSAGE_LENGTH, MAX_CANCEL_WORK_KEY_LENGTH,
        EMPTY_QUEUE_TAGS, SLOT_MODE, EMPTY,
        publish(snapshot) { current = snapshot; },
        hooks: Object.freeze({
            get(type) { return get(type); },
            supports(type, mode) { return supports(type, mode); },
            quickActionsFor(type) { return quickActionsFor(type); },
            slotsBootstrapped() { return slotsBootstrapped; },
            clearRenderedSlots() { clearRenderedSlots(); },
            renderSlots() { return renderSlots(); }
        })
    };
    modules.normalize.install(moduleContext);
    modules.runtime.install(moduleContext);
    delete window.PixivBatch.queueTypeRuntimeModules;
    const {
        text, opaqueText, queueKey, normalizedCancelWorkKey, isPlainObject,
        queueItemSnapshot, normalizedQueueTags, normalizedQueueLiveStatus,
        normalizedEndpoint, sanitizedRequestHeaders, isActivationLive,
        staleQueueTypeError, registerModule, registerUiModule, registerSubmodule, refresh,
        prefetchExtensions
    } = moduleContext;
    const disposeRuntime = moduleContext.dispose;
    function activeEntry(type) {
        const entry = current.activations.get(text(type));
        return entry && entry.activation === current.activation && isActivationLive(current.activation)
            ? entry : null;
    }

    function activeAcquisitionEntry(type, mode) {
        const entry = activeEntry(type);
        const normalizedMode = text(mode);
        if (!entry || !entry.descriptor.acquisitionModes.includes(normalizedMode)) return null;
        const behavior = entry.behavior;
        const contribution = normalizedMode === 'single-import'
            ? behavior.import
            : behavior.acquisition && behavior.acquisition[normalizedMode];
        return contribution ? {entry, contribution, mode: normalizedMode} : null;
    }

    function isAcquisitionEntryCurrent(value) {
        if (!value || !value.entry) return false;
        const entry = value.entry;
        return activeEntry(entry.descriptor.type) === entry
            && entry.descriptor.acquisitionModes.includes(value.mode);
    }

    function acquisitionLease(type, mode) {
        const value = activeAcquisitionEntry(type, mode);
        if (!value) throw new Error('acquisition contribution is unavailable');
        return Object.freeze({
            type: value.entry.descriptor.type,
            mode: value.mode,
            signal: value.entry.activation.controller.signal,
            isCurrent() { return isAcquisitionEntryCurrent(value); },
            assertCurrent() {
                if (!isAcquisitionEntryCurrent(value)) {
                    const error = new Error('acquisition request activation is stale');
                    error.code = 'STALE_ACQUISITION';
                    throw error;
                }
            }
        });
    }

    function prepareAcquisitionRequest(type, mode, endpoint, operation, context) {
        const value = activeAcquisitionEntry(type, mode);
        if (!value) throw new Error('acquisition contribution is unavailable');
        const activationLease = acquisitionLease(value.entry.descriptor.type, value.mode);
        const initValue = typeof value.contribution.requestInit === 'function'
            ? value.contribution.requestInit(Object.assign({operation: text(operation)}, context || {}))
            : {};
        if (initValue != null && typeof initValue !== 'object') {
            throw new Error('acquisition requestInit must return an object');
        }
        const request = Object.freeze({
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: sanitizedRequestHeaders(initValue),
            signal: activationLease.signal
        });
        const lease = {
            type: value.entry.descriptor.type,
            mode: value.mode,
            url: normalizedEndpoint(endpoint),
            init: request,
            signal: request.signal,
            isCurrent: activationLease.isCurrent,
            assertCurrent: activationLease.assertCurrent
        };
        return Object.freeze(lease);
    }

    function get(type) {
        const entry = activeEntry(type);
        return entry ? entry.behavior : null;
    }

    // 类型模块可在渲染期贡献纯文本标签。宿主统一限制数量/长度、按稳定 id 去重并在最终 HTML 中转义；
    // 插件不能返回 HTML，也不能用异步结果跨 publication 回写旧队列。
    function queueTags(item) {
        const type = text(item && item.kind);
        const behavior = get(type);
        if (!behavior || typeof behavior.queueTags !== 'function') return EMPTY_QUEUE_TAGS;
        try {
            const value = behavior.queueTags(queueItemSnapshot(item));
            if (value && typeof value.then === 'function') {
                // 同步 hook 意外返回 rejected Promise 时仍要吸收 rejection，避免把插件错误
                // 泄漏为页面级 unhandledrejection；该 publication 的标签本轮直接降级为空。
                Promise.resolve(value).catch(() => undefined);
                throw new Error('queueTags must return a synchronous array');
            }
            return normalizedQueueTags(value);
        } catch (e) {
            console.warn('[queueTypes] 队列类型标签贡献失败：', type, e);
            return EMPTY_QUEUE_TAGS;
        }
    }

    // 类型模块可把自己的 raw 实时状态解释成一行纯文本。宿主只接受同步、有界、固定 tone 的结果，
    // 最终 HTML 仍由共享渲染器统一转义；插件卸载或 publication 过期时安全降级为不显示。
    function queueLiveStatus(item) {
        const type = text(item && (item.workType != null ? item.workType : item.kind));
        const behavior = get(type);
        if (!behavior || typeof behavior.queueLiveStatus !== 'function') return null;
        try {
            const value = behavior.queueLiveStatus(queueItemSnapshot(item));
            if (value && typeof value.then === 'function') {
                Promise.resolve(value).catch(() => undefined);
                throw new Error('queueLiveStatus must return a synchronous object');
            }
            return normalizedQueueLiveStatus(value);
        } catch (e) {
            // 插件异常对象可能夹带私有运行态；只记录 owner 类型，不把异常内容泄漏到浏览器控制台。
            console.warn('[queueTypes] 队列类型实时状态贡献失败：', type);
            return null;
        }
    }

    function has(type) {
        return !!activeEntry(type);
    }

    function isEnabled(type) {
        return current.manifest.has(text(type));
    }

    function isTypeAvailable(type) {
        return has(type) && isEnabled(type);
    }

    function resolveType(type, fallback) {
        const requested = text(type);
        if (isTypeAvailable(requested)) return requested;
        const preferred = text(fallback);
        if (preferred && isTypeAvailable(preferred)) return preferred;
        return current.orderedTypes.find(isTypeAvailable) || null;
    }

    function normalizeSelectedType(type, allowed) {
        const allow = Array.isArray(allowed) && allowed.length ? allowed.map(text) : current.orderedTypes;
        const requested = text(type);
        if (allow.includes(requested) && isTypeAvailable(requested)) return requested;
        return allow.find(isTypeAvailable) || current.orderedTypes.find(isTypeAvailable) || null;
    }

    function descriptor(type) {
        return get(type);
    }

    function backendDescriptor(type) {
        return current.manifest.get(text(type)) || null;
    }

    function manifestDescriptor(type) {
        const item = backendDescriptor(type);
        if (!item) return null;
        return Object.freeze({
            contractVersion: item.contractVersion,
            type: item.type,
            displayNamespace: text(item.displayNamespace),
            displayI18nKey: text(item.displayI18nKey),
            order: item.order,
            iconKey: text(item.iconKey),
            colorToken: text(item.colorToken),
            moduleUrl: item.moduleUrl,
            i18nNamespace: text(item.i18nNamespace),
            acquisitionModes: Object.freeze(item.acquisitionModes.slice()),
            cancelSupported: item.cancelSupported,
            owner: Object.freeze({
                pluginId: item.ownerPluginId,
                packageId: item.packageId,
                generation: item.pluginGeneration,
                publicationId: item.publicationId
            })
        });
    }

    function declaredForMode(type, mode) {
        const backend = backendDescriptor(type);
        return !!backend && backend.acquisitionModes.includes(text(mode));
    }

    function acquisition(type, mode) {
        const value = activeAcquisitionEntry(type, mode);
        return value ? value.contribution : null;
    }

    function supports(type, mode) {
        return !!acquisition(type, mode);
    }

    function typesForMode(mode) {
        return current.orderedTypes.filter(type => supports(type, mode));
    }

    function resolveTypeForMode(type, mode, fallback) {
        const requested = text(type);
        if (supports(requested, mode)) return requested;
        const preferred = text(fallback);
        if (preferred && supports(preferred, mode)) return preferred;
        return typesForMode(mode)[0] || null;
    }

    // 取得模式可声明不等同于作品类型 id 的选择变体（例如 user 模式的 request）。先保留直接类型，
    // 再由 owner 的 accepts hook 唯一解析；没有 owner 接受时才走普通类型回退，避免排序靠前的无关类型抢占。
    function resolveSelectionForMode(selection, mode, fallback) {
        const requested = text(selection);
        if (supports(requested, mode)) return requested;
        const matches = [];
        acquisitionList(mode).forEach(candidate => {
            if (typeof candidate.accepts !== 'function') return;
            try {
                if (candidate.accepts(requested)) matches.push(candidate.type);
            } catch (e) {
                console.warn('[queue-types] 取得模式选择钩子失败：', candidate.type, e);
            }
        });
        if (matches.length === 1) return matches[0];
        if (matches.length > 1) return null;
        return resolveTypeForMode(requested, mode, fallback);
    }

    function acquisitionList(mode) {
        return typesForMode(mode).map(type => Object.assign({}, acquisition(type, mode), {type}));
    }

    function dataSourceDescriptor(acquisitionContribution) {
        const type = text(acquisitionContribution && acquisitionContribution.type);
        if (!type) return null;
        const manifest = manifestDescriptor(type) || {};
        const metadata = acquisitionContribution && isPlainObject(acquisitionContribution.dataSource)
            ? acquisitionContribution.dataSource : {};
        const id = text(metadata.id || type);
        if (!id) return null;
        const rawOrder = metadata.order == null ? manifest.order : metadata.order;
        const order = Number(rawOrder);
        return {
            id,
            displayNamespace: text(metadata.displayNamespace || manifest.displayNamespace),
            displayI18nKey: text(metadata.displayI18nKey || manifest.displayI18nKey),
            order: Number.isFinite(order) ? order : 0,
            type
        };
    }

    function frozenDataSourceDescriptor(value) {
        return value ? Object.freeze({
            id: value.id,
            displayNamespace: value.displayNamespace,
            displayI18nKey: value.displayI18nKey,
            order: value.order,
            type: value.type
        }) : null;
    }

    // 队列项按实际取得模式优先解析来源；计划队列等没有手动模式的场景，则从该类型全部活动
    // acquisition 中选确定性的首个来源。旧模块未声明 dataSource 时沿用类型展示元数据作为中性回退。
    function dataSourceForType(type, mode) {
        const normalizedType = text(type);
        if (!normalizedType || !has(normalizedType)) return null;
        const normalizedMode = text(mode);
        if (KNOWN_MODES.has(normalizedMode)) {
            const contribution = acquisition(normalizedType, normalizedMode);
            if (contribution) {
                return frozenDataSourceDescriptor(dataSourceDescriptor(
                    Object.assign({}, contribution, {type: normalizedType})));
            }
        }
        const explicitCandidates = [];
        const fallbackCandidates = [];
        const explicitSeen = new Set();
        const fallbackSeen = new Set();
        KNOWN_MODES.forEach(candidateMode => {
            const contribution = acquisition(normalizedType, candidateMode);
            if (!contribution) return;
            const candidate = dataSourceDescriptor(Object.assign({}, contribution, {type: normalizedType}));
            if (!candidate) return;
            const explicit = isPlainObject(contribution.dataSource);
            const seen = explicit ? explicitSeen : fallbackSeen;
            if (seen.has(candidate.id)) return;
            seen.add(candidate.id);
            (explicit ? explicitCandidates : fallbackCandidates).push(candidate);
        });
        const candidates = explicitCandidates.length ? explicitCandidates : fallbackCandidates;
        if (!candidates.length) {
            return frozenDataSourceDescriptor(dataSourceDescriptor({type: normalizedType}));
        }
        candidates.sort((left, right) => (left.order - right.order) || left.id.localeCompare(right.id));
        return frozenDataSourceDescriptor(candidates[0]);
    }

    function dataSourceTypeDescriptor(type) {
        const manifest = manifestDescriptor(type) || {};
        const rawOrder = Number(manifest.order);
        return Object.freeze({
            type: text(type),
            displayNamespace: text(manifest.displayNamespace),
            displayI18nKey: text(manifest.displayI18nKey),
            order: Number.isFinite(rawOrder) ? rawOrder : 0,
            iconKey: text(manifest.iconKey),
            colorToken: text(manifest.colorToken)
        });
    }

    function cancelWorkKey(item) {
        const value = item && typeof item === 'object' ? item : {};
        if (Object.prototype.hasOwnProperty.call(value, 'cancelWorkKey')) {
            return normalizedCancelWorkKey(value.cancelWorkKey);
        }
        return null;
    }

    function canCancel(item) {
        const type = text(item && item.kind);
        const entry = activeEntry(type);
        return !!entry && entry.descriptor.cancelSupported === true && cancelWorkKey(item) !== null;
    }

    async function cancel(item) {
        const type = text(item && item.kind);
        const entry = activeEntry(type);
        const workKey = cancelWorkKey(item);
        if (!entry || entry.descriptor.cancelSupported !== true || workKey === null) {
            const error = new Error('queue item cancellation is unavailable');
            error.code = 'QUEUE_CANCEL_UNAVAILABLE';
            throw error;
        }
        const response = await fetch(BASE + '/api/download/queue/' + encodeURIComponent(type) + '/cancel', {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                workKey,
                owner: {
                    pluginId: entry.descriptor.ownerPluginId,
                    packageId: entry.descriptor.packageId,
                    generation: entry.descriptor.pluginGeneration,
                    publicationId: entry.descriptor.publicationId
                }
            }),
            signal: entry.activation.controller.signal
        });
        if (activeEntry(type) !== entry) throw staleQueueTypeError();
        let payload = null;
        try {
            payload = await response.json();
        } catch (e) {
            payload = null;
        }
        if (activeEntry(type) !== entry) throw staleQueueTypeError();
        if (!response.ok) {
            const error = new Error('queue cancellation HTTP ' + response.status);
            error.code = payload && typeof payload.code === 'string' && payload.code
                ? payload.code : 'QUEUE_CANCEL_FAILED';
            error.status = response.status;
            throw error;
        }
        return payload;
    }

    // 每个取得模式都投影成中立的「来源 -> 活动作品类型」只读快照。来源元数据属于
    // acquisition contribution；旧模块未声明时按自身 type / display token 退化为独立来源，
    // 宿主无需认识任何具体平台 id。多个类型共享 source id 时，以确定性贡献顺序的首项为准。
    function dataSourcesForMode(mode) {
        const byId = new Map();
        acquisitionList(mode).forEach(contribution => {
            const candidate = dataSourceDescriptor(contribution);
            if (!candidate) return;
            const existing = byId.get(candidate.id);
            if (!existing) {
                byId.set(candidate.id, {
                    id: candidate.id,
                    displayNamespace: candidate.displayNamespace,
                    displayI18nKey: candidate.displayI18nKey,
                    order: candidate.order,
                    types: [candidate.type]
                });
                return;
            }
            if (!existing.types.includes(candidate.type)) existing.types.push(candidate.type);
            if (existing.displayNamespace !== candidate.displayNamespace
                || existing.displayI18nKey !== candidate.displayI18nKey
                || existing.order !== candidate.order) {
                console.warn('[queueTypes] 同一数据来源的展示元数据不一致，保留先声明的元数据：', candidate.id);
            }
        });
        const sources = Array.from(byId.values()).map(source => {
            const types = source.types.map(dataSourceTypeDescriptor)
                .sort((left, right) => (left.order - right.order) || left.type.localeCompare(right.type));
            return Object.freeze({
                id: source.id,
                displayNamespace: source.displayNamespace,
                displayI18nKey: source.displayI18nKey,
                order: source.order,
                types: Object.freeze(types)
            });
        }).sort((left, right) => (left.order - right.order) || left.id.localeCompare(right.id));
        return Object.freeze(sources);
    }

    function typesForDataSource(mode, sourceId) {
        const requested = text(sourceId);
        const source = dataSourcesForMode(mode).find(candidate => candidate.id === requested);
        return source ? source.types : Object.freeze([]);
    }

    function filtersFor(type) {
        return mergedDeclaredContributions(type, 'filters');
    }

    function settingsFor(type) {
        return mergedDeclaredContributions(type, 'settings');
    }

    function mergedDeclaredContributions(type, key) {
        const behavior = get(type);
        const groups = behavior && isPlainObject(behavior[key]) ? behavior[key] : {};
        const values = Object.keys(groups).filter(name => isPlainObject(groups[name]));
        if (!values.length) return null;
        const merged = {};
        const conflicted = new Set();
        values.forEach(name => {
            Object.keys(groups[name]).forEach(field => {
                if (field === 'type' || field === 'contributionKey') return;
                if (conflicted.has(field)) return;
                if (Object.prototype.hasOwnProperty.call(merged, field)) {
                    delete merged[field];
                    conflicted.add(field);
                    return;
                }
                merged[field] = groups[name][field];
            });
        });
        merged.type = text(type);
        return Object.freeze(merged);
    }

    function quickActionsFor(type) {
        const quick = acquisition(type, 'quick');
        return quick && quick.actions ? quick.actions : {};
    }

    // 把后端计划队列项投影为工作区队列项的类型自有部分。context 可提供 source；状态、进度等
    // 跨类型字段由调用方在结果上合并。类型缺席时仍返回可渲染、可保留的中性项。
    function scheduledQueueItem(type, item, context) {
        const raw = item && typeof item === 'object' ? item : {};
        const ctx = context && typeof context === 'object' ? context : {};
        const presentation = isPlainObject(raw.presentation) ? raw.presentation : {};
        const presentationAttributes = isPlainObject(raw.presentationAttributes)
            ? raw.presentationAttributes
            : (isPlainObject(presentation.attributes) ? presentation.attributes : {});
        const result = isPlainObject(raw.result) ? raw.result : {};
        const resultAttributes = isPlainObject(raw.resultAttributes)
            ? raw.resultAttributes
            : (isPlainObject(result.attributes) ? result.attributes : {});
        const normalizedType = text(type) || text(raw.kind) || text(raw.workType) || 'unknown';
        const rawId = opaqueText(raw.workId != null ? raw.workId : raw.id);
        const rawLiveStatus = isPlainObject(raw.liveStatus) ? Object.assign({}, raw.liveStatus) : null;
        const fallback = {
            id: rawId,
            kind: normalizedType,
            workId: rawId,
            workType: normalizedType,
            queueKey: queueKey(normalizedType, rawId),
            rawTitle: text(raw.title) || text(presentation.title) || null,
            author: text(raw.author) || text(presentation.author) || null,
            thumbnailReference: text(raw.thumbnailReference)
                || text(presentation.thumbnailReference) || null,
            presentationAttributes: Object.assign({}, presentationAttributes),
            resultAttributes: Object.assign({}, resultAttributes),
            liveStatus: rawLiveStatus,
            source: text(ctx.source) || text(raw.source) || 'schedule'
        };
        const behavior = get(normalizedType);
        if (!behavior || typeof behavior.scheduledQueueItem !== 'function') return fallback;
        try {
            const owned = behavior.scheduledQueueItem(raw, ctx);
            if (!isPlainObject(owned)) return fallback;
            return Object.assign(fallback, owned, {
                id: rawId,
                kind: normalizedType,
                workId: rawId,
                workType: normalizedType,
                queueKey: queueKey(normalizedType, rawId),
                liveStatus: rawLiveStatus
            });
        } catch (e) {
            console.warn('[queueTypes] 计划队列项类型映射失败：', normalizedType, e);
            return fallback;
        }
    }

    function supportsScheduledSse(type) {
        const behavior = get(type);
        return !!behavior && behavior.scheduledSse === true;
    }

    function contributionsOf(key) {
        if (key === 'import') {
            return typesForMode('single-import')
                .map(type => Object.assign({}, acquisition(type, 'single-import'), {type}));
        }
        if (key === 'filters' || key === 'settings') {
            const out = [];
            current.orderedTypes.forEach(type => {
                const behavior = get(type);
                const groups = behavior && isPlainObject(behavior[key]) ? behavior[key] : {};
                Object.keys(groups).forEach(contributionKey => {
                    const contribution = groups[contributionKey];
                    if (!isPlainObject(contribution)) return;
                    out.push(Object.assign({}, contribution, {type, contributionKey}));
                });
            });
            return out;
        }
        return current.orderedTypes
            .map(type => ({type, behavior: get(type)}))
            .filter(entry => entry.behavior && entry.behavior[key])
            .map(entry => Object.assign({}, entry.behavior[key], {type: entry.type}));
    }

    function uiSlots() {
        return current.uiSlots.map(slot => Object.assign({}, slot));
    }

    function downloadTypes() {
        return current.orderedTypes.map(type => {
            const item = current.manifest.get(type);
            return Object.assign({}, item, {acquisitionModes: item.acquisitionModes.slice()});
        });
    }

    function addNamespace(out, seen, value) {
        const namespace = text(value);
        if (!namespace || seen.has(namespace)) return;
        seen.add(namespace);
        out.push(namespace);
    }

    async function i18nNamespaces() {
        const out = [];
        const seen = new Set();
        const downloadTypes = current.identity
            ? current.orderedTypes.map(type => current.manifest.get(type))
            : (((await prefetchExtensions()) || {}).downloadTypes || []);
        downloadTypes.forEach(item => {
            addNamespace(out, seen, item && item.displayNamespace);
            addNamespace(out, seen, item && item.i18nNamespace);
        });
        if (current.identity) {
            current.orderedTypes.forEach(type => {
                ['single-import', 'user', 'search', 'series', 'quick'].forEach(mode => {
                    const contribution = acquisition(type, mode);
                    addNamespace(out, seen,
                        contribution && contribution.dataSource && contribution.dataSource.displayNamespace);
                });
            });
        }
        return out;
    }

    // 类型的 typed settings 声明（{cardId}）中已有任一 cardId 被宿主页面原生渲染时，
    // 该类型的 settings-card 槽位片段不再注入——同 id 卡片只保留宿主原生那一份，
    // 避免新旧布局并存或宿主内建同 id 区块时出现重复 id 与双份设置卡。
    function hasNativeSettingsCard(behavior) {
        const groups = behavior && isPlainObject(behavior.settings) ? behavior.settings : {};
        return Object.keys(groups).some(key => {
            const cardId = text(groups[key] && groups[key].cardId);
            return !!cardId && !!document.getElementById(cardId);
        });
    }

    function collectSlotFragments() {
        const byTarget = new Map();
        current.orderedTypes.forEach(type => {
            const behavior = get(type);
            const slots = behavior && behavior.slots ? behavior.slots : {};
            Object.keys(slots).forEach(target => {
                if (target === 'settings-card' && hasNativeSettingsCard(behavior)) return;
                try {
                    const raw = slots[target];
                    const contribution = typeof raw === 'function' ? raw() : raw;
                    if (contribution == null) return;
                    if (!byTarget.has(target)) byTarget.set(target, []);
                    byTarget.get(target).push(contribution);
                } catch (e) {
                    console.warn('[queueTypes] 下载页槽位贡献失败：', type, target, e);
                }
            });
        });
        return byTarget;
    }

    function templatesForTarget(target) {
        const out = [];
        document.querySelectorAll('template[data-qt-slot]').forEach(marker => {
            if (marker.getAttribute('data-qt-slot') === target) out.push(marker);
        });
        return out;
    }

    function directSlotHost(marker, target) {
        const parent = marker && marker.parentNode;
        if (!parent) return null;
        const children = parent.children || parent.childNodes || [];
        for (let i = 0; i < children.length; i++) {
            const child = children[i];
            if (child && typeof child.getAttribute === 'function'
                && child.getAttribute('data-vue-slot') === target) {
                return child;
            }
        }
        const host = document.createElement('div');
        host.setAttribute('data-vue-slot', target);
        parent.insertBefore(host, marker);
        return host;
    }

    function slotAnchors(target) {
        return templatesForTarget(target)
            .map(marker => ({marker, host: directSlotHost(marker, target)}))
            .filter(anchor => !!anchor.host);
    }

    function clearSlotHost(host) {
        if (!host) return;
        if (typeof host.replaceChildren === 'function') {
            host.replaceChildren();
            return;
        }
        try { host.innerHTML = ''; } catch (e) { /* detached test DOM */ }
    }

    function cleanupSlotRecord(record) {
        if (!record) return;
        record.apps.splice(0).reverse().forEach(app => {
            try { app.unmount(); } catch (e) { console.warn('[queueTypes] Vue 槽位卸载失败：', e); }
        });
        record.cleanups.splice(0).reverse().forEach(cleanup => {
            try { cleanup(); } catch (e) { console.warn('[queueTypes] 命令式槽位清理失败：', e); }
        });
        record.anchors.forEach(anchor => clearSlotHost(anchor.host));
    }

    function clearRenderedSlots() {
        ++slotRenderSequence;
        Array.from(slotMounts.values()).reverse().forEach(cleanupSlotRecord);
        slotMounts.clear();
    }

    async function mountSlot(target, contributions, anchors, record) {
        if (!window.PixivVue || contributions.some(value => typeof value !== 'string')) return false;
        const helper = window.PixivVue;
        if (typeof helper.mountOn !== 'function' && typeof helper.mount !== 'function') return false;
        const component = {template: contributions.join('')};
        try {
            const handles = [];
            if (typeof helper.mountOn === 'function') {
                for (const anchor of anchors) handles.push(await helper.mountOn(anchor.host, component));
            } else {
                handles.push(await helper.mount(target, component));
            }
            if (!handles.length || handles.some(handle => !(handle && handle.app))) {
                handles.forEach(handle => {
                    if (handle && handle.app) {
                        try { handle.app.unmount(); } catch (e) { /* fallback path owns cleanup */ }
                    }
                });
                return false;
            }
            handles.forEach(handle => record.apps.push(handle.app));
            return true;
        } catch (e) {
            return false;
        }
    }

    function contributionCleanup(result, contribution, host, marker) {
        if (typeof result === 'function') return result;
        const owner = result && typeof result === 'object' ? result : contribution;
        if (owner && typeof owner.unmount === 'function') return () => owner.unmount(host, marker);
        if (owner && typeof owner.dispose === 'function') return () => owner.dispose(host, marker);
        if (owner && typeof owner.destroy === 'function') return () => owner.destroy(host, marker);
        return null;
    }

    function mountNodeContribution(anchor, contribution, record) {
        if (!contribution || typeof contribution !== 'object') return false;
        try {
            if (typeof contribution.mount === 'function') {
                const result = contribution.mount(anchor.host, anchor.marker);
                const cleanup = contributionCleanup(result, contribution, anchor.host, anchor.marker);
                if (cleanup) record.cleanups.push(cleanup);
                return true;
            }
            if (typeof Node !== 'undefined' && contribution instanceof Node) {
                anchor.host.appendChild(contribution.cloneNode(true));
                return true;
            }
        } catch (e) {
            console.warn('[queueTypes] 命令式槽位贡献挂载失败：', e);
        }
        return false;
    }

    function injectSlotFallback(contributions, record) {
        const strings = contributions.filter(value => typeof value === 'string');
        const html = strings.join('');
        record.anchors.forEach(anchor => {
            if (html) anchor.host.insertAdjacentHTML('beforeend', html);
            contributions.filter(value => typeof value !== 'string')
                .forEach(value => mountNodeContribution(anchor, value, record));
        });
    }

    async function renderSlotsNow(snapshot, sequence) {
        if (sequence !== slotRenderSequence || snapshot !== current) return;
        const byTarget = collectSlotFragments();
        if (window.PixivVue && typeof window.PixivVue.prepareSlotHosts === 'function') {
            window.PixivVue.prepareSlotHosts(document);
        }
        for (const [target, contributions] of byTarget) {
            if (sequence !== slotRenderSequence || snapshot !== current) return;
            const record = {identity: snapshot.identity, anchors: slotAnchors(target), apps: [], cleanups: []};
            slotMounts.set(target, record);
            if (!await mountSlot(target, contributions, record.anchors, record)) {
                injectSlotFallback(contributions, record);
            }
            if (sequence !== slotRenderSequence || snapshot !== current) {
                // clearRenderedSlots 可能已把 record 从 map 移除，但 mountOn 可能在此后才返回 app。
                // 无条件清理这些迟到句柄；串行尾队列保证新 record 尚未共用该 host。
                cleanupSlotRecord(record);
                if (slotMounts.get(target) === record) {
                    slotMounts.delete(target);
                }
                return;
            }
        }
        if (typeof pageI18n !== 'undefined' && pageI18n) pageI18n.apply(document.body);
        try {
            window.dispatchEvent(new CustomEvent('pixivbatch:slotsrendered', {
                detail: {identity: snapshot.identity, targets: Array.from(byTarget.keys())}
            }));
        } catch (e) {
            // 旧环境缺少 CustomEvent 构造器不影响槽位。
        }
    }

    function renderSlots() {
        clearRenderedSlots();
        const sequence = slotRenderSequence;
        const snapshot = current;
        const queued = slotRenderTail.catch(() => undefined)
            .then(() => renderSlotsNow(snapshot, sequence));
        // 后续渲染必须等前一次迟到 mount 完成清理，避免旧 app 卸载清空新 publication 的共享 host。
        slotRenderTail = queued.catch(error => {
            console.warn('[queueTypes] 下载页槽位渲染失败：', error);
        });
        return queued;
    }

    async function bootstrap() {
        slotsBootstrapped = true;
        await refresh(false, true);
        return current;
    }

    function dispose() {
        disposeRuntime();
        slotsBootstrapped = false;
    }
    return Object.freeze({
        registerModule,
        registerUiModule,
        registerSubmodule,
        bootstrap,
        refresh(force) { return refresh(!!force, false); },
        get,
        queueKey,
        queueTags,
        queueLiveStatus,
        canCancel,
        cancel,
        has,
        isEnabled,
        isTypeAvailable,
        resolveType,
        normalizeSelectedType,
        descriptor,
        manifestDescriptor,
        acquisition,
        acquisitionList,
        dataSourceForType,
        dataSourcesForMode,
        typesForDataSource,
        supports,
        typesForMode,
        resolveTypeForMode,
        resolveSelectionForMode,
        filtersFor,
        settingsFor,
        quickActionsFor,
        scheduledQueueItem,
        supportsScheduledSse,
        prepareAcquisitionRequest,
        acquisitionLease,
        contributionsOf,
        uiSlots,
        downloadTypes,
        i18nNamespaces,
        // 幂等可重入的槽位重渲染：动态重建视图的宿主页（如 alt 布局在 renderStage / 抽屉 / 弹窗
        // 重建后锚点随之重建）在视图渲染完成后调用它重挂槽位；无锚点的 target 自动空转。
        renderSlots,
        dispose,
        contractVersion: CONTRACT_VERSION
    });
})();
