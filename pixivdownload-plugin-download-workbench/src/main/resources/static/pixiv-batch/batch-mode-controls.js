'use strict';

// User / Search 原有作品类型切换仍由各模式的 kind switcher 与插件 slot 负责；
// 本控制器只补齐中立的数据来源选择，并维护来源与当前 owner type 的对应关系。
const BATCH_ACQUISITION_SOURCE_MODES = Object.freeze(['user', 'search']);
const batchModeControlSelections = new Map();
const batchModeControlListeners = new Map();
let batchModeControlsBound = false;

function batchModeControlState(mode) {
    const key = String(mode || '');
    if (!batchModeControlSelections.has(key)) {
        batchModeControlSelections.set(key, {sourceId: null, type: null});
    }
    return batchModeControlSelections.get(key);
}

function batchModeControlSources(mode) {
    const registry = window.PixivBatch && window.PixivBatch.queueTypes;
    return registry && typeof registry.dataSourcesForMode === 'function'
        ? registry.dataSourcesForMode(mode) : Object.freeze([]);
}

function batchModeControlSnapshot(mode) {
    const state = batchModeControlState(mode);
    return Object.freeze({sourceId: state.sourceId, type: state.type});
}

function batchModeControlApplyI18n(root) {
    if (root && typeof pageI18n !== 'undefined' && pageI18n
        && typeof pageI18n.apply === 'function') {
        pageI18n.apply(root);
    }
}

function batchModeControlRenderSources(root, sources, mode, selected) {
    if (!root) return;
    root.replaceChildren();
    sources.forEach((source, index) => {
        const label = document.createElement('label');
        label.classList.toggle('active', source.id === selected);
        label.dataset.dataSource = source.id;

        const input = document.createElement('input');
        input.type = 'radio';
        input.name = `${mode}-data-source`;
        input.value = source.id;
        input.checked = source.id === selected;
        input.id = `${mode}-data-source-${index}`;
        input.dataset.acquisitionSourceMode = mode;

        const text = document.createElement('span');
        if (source.displayNamespace && source.displayI18nKey) {
            text.setAttribute('data-i18n', `${source.displayNamespace}:${source.displayI18nKey}`);
        }
        text.textContent = source.id;

        label.appendChild(input);
        label.appendChild(text);
        root.appendChild(label);
    });
    batchModeControlApplyI18n(root);
}

function batchModeControlNormalize(mode, sources, preserveSelection) {
    const state = batchModeControlState(mode);
    const previous = batchModeControlSnapshot(mode);
    if (!sources.length && preserveSelection && state.sourceId != null) {
        return {previous, preserved: true, source: null};
    }
    if (!sources.some(source => source.id === state.sourceId)) {
        state.sourceId = sources.length ? sources[0].id : null;
    }
    const source = sources.find(candidate => candidate.id === state.sourceId) || null;
    const types = source ? source.types : Object.freeze([]);
    if (!types.some(candidate => candidate.type === state.type)) {
        state.type = types.length ? types[0].type : null;
    }
    return {previous, preserved: false, source};
}

function renderBatchModeControl(mode, preserveSelection = false) {
    const key = String(mode || '');
    if (!BATCH_ACQUISITION_SOURCE_MODES.includes(key)) return null;
    const sources = batchModeControlSources(key);
    const normalized = batchModeControlNormalize(key, sources, !!preserveSelection);
    if (normalized.preserved) {
        return Object.freeze({
            mode: key,
            sourceChanged: false,
            typeChanged: false,
            selection: batchModeControlSnapshot(key),
            preserved: true
        });
    }

    const state = batchModeControlState(key);
    const sourceControl = document.getElementById(`${key}-data-source-control`);
    const sourceSwitcher = document.getElementById(`${key}-data-source-switcher`);
    if (sourceControl) {
        sourceControl.dataset.acquisitionSourceMode = key;
        sourceControl.hidden = false;
        if (sourceControl.style) sourceControl.style.display = '';
    }
    if (sourceSwitcher) {
        sourceSwitcher.dataset.acquisitionSourceMode = key;
        sourceSwitcher.setAttribute('aria-disabled', sources.length ? 'false' : 'true');
    }
    batchModeControlRenderSources(sourceSwitcher, sources, key, state.sourceId);

    const selection = batchModeControlSnapshot(key);
    return Object.freeze({
        mode: key,
        sourceChanged: normalized.previous.sourceId !== selection.sourceId,
        typeChanged: normalized.previous.type !== selection.type,
        selection,
        preserved: false
    });
}

function renderSupportedImportDataSources(preserveSelection = false) {
    const root = document.getElementById('single-import-data-sources');
    if (!root) return Object.freeze([]);
    const sources = batchModeControlSources('single-import');
    if (preserveSelection && !sources.length && root.childNodes && root.childNodes.length) return sources;
    root.replaceChildren();
    const separator = typeof uiLang === 'function' && uiLang() === 'en-US' ? ', ' : '、';
    sources.forEach((source, index) => {
        if (index > 0) root.appendChild(document.createTextNode(separator));
        const label = document.createElement('span');
        if (source.displayNamespace && source.displayI18nKey) {
            label.setAttribute('data-i18n', `${source.displayNamespace}:${source.displayI18nKey}`);
        }
        label.textContent = source.id;
        root.appendChild(label);
    });
    if (!sources.length) root.textContent = '-';
    batchModeControlApplyI18n(root);
    return sources;
}

function renderAllBatchModeControls(preserveSelection = false) {
    const results = {};
    BATCH_ACQUISITION_SOURCE_MODES.forEach(mode => {
        results[mode] = renderBatchModeControl(mode, preserveSelection);
    });
    renderSupportedImportDataSources(preserveSelection);
    return Object.freeze(results);
}

function notifyBatchModeControl(mode, previous, reason) {
    const listeners = batchModeControlListeners.get(mode);
    if (!listeners || !listeners.size) return;
    const detail = Object.freeze({
        mode,
        reason,
        previous,
        selection: batchModeControlSnapshot(mode)
    });
    Array.from(listeners).forEach(listener => listener(detail));
}

function selectBatchModeSource(mode, sourceId, notify = true) {
    const key = String(mode || '');
    if (!BATCH_ACQUISITION_SOURCE_MODES.includes(key)) return false;
    const sources = batchModeControlSources(key);
    const requested = String(sourceId == null ? '' : sourceId);
    if (!sources.some(source => source.id === requested)) return false;
    const previous = batchModeControlSnapshot(key);
    batchModeControlState(key).sourceId = requested;
    renderBatchModeControl(key, false);
    const selection = batchModeControlSnapshot(key);
    const changed = previous.sourceId !== selection.sourceId || previous.type !== selection.type;
    if (changed && notify) notifyBatchModeControl(key, previous, 'source');
    return changed;
}

function selectBatchModeType(mode, type, notify = true) {
    const key = String(mode || '');
    if (!BATCH_ACQUISITION_SOURCE_MODES.includes(key)) return false;
    const requested = String(type == null ? '' : type);
    const state = batchModeControlState(key);
    const source = batchModeControlSources(key).find(candidate => candidate.id === state.sourceId
        && candidate.types.some(entry => entry.type === requested));
    if (!source) return false;
    const previous = batchModeControlSnapshot(key);
    state.type = requested;
    const changed = previous.type !== state.type;
    if (changed && notify) notifyBatchModeControl(key, previous, 'type');
    return changed;
}

function syncBatchModeType(mode, type) {
    const key = String(mode || '');
    if (!BATCH_ACQUISITION_SOURCE_MODES.includes(key)) return false;
    const requested = String(type == null ? '' : type);
    const ownerSource = batchModeControlSources(key)
        .find(source => source.types.some(candidate => candidate.type === requested));
    if (!ownerSource) return false;
    const state = batchModeControlState(key);
    state.sourceId = ownerSource.id;
    state.type = requested;
    renderBatchModeControl(key, false);
    return true;
}

function onBatchModeControlChange(mode, listener) {
    const key = String(mode || '');
    if (typeof listener !== 'function') return () => undefined;
    if (!batchModeControlListeners.has(key)) batchModeControlListeners.set(key, new Set());
    batchModeControlListeners.get(key).add(listener);
    return () => batchModeControlListeners.get(key).delete(listener);
}

function reconcileBatchModeControls(ready = true) {
    const results = {};
    BATCH_ACQUISITION_SOURCE_MODES.forEach(mode => {
        const previous = batchModeControlSnapshot(mode);
        const result = renderBatchModeControl(mode, ready === false);
        results[mode] = result;
        if (ready !== false && result && (result.sourceChanged || result.typeChanged)) {
            notifyBatchModeControl(mode, previous, 'reconcile');
        }
    });
    renderSupportedImportDataSources(ready === false);
    return Object.freeze(results);
}

function bindBatchModeControls() {
    if (batchModeControlsBound) return;
    batchModeControlsBound = true;
    document.addEventListener('change', event => {
        const target = event && event.target;
        if (!target || !target.dataset || !target.dataset.acquisitionSourceMode) return;
        selectBatchModeSource(target.dataset.acquisitionSourceMode, target.value);
    });
}

window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.modeControls = window.PixivBatch.modeControls || {};
window.PixivBatch.modeControls = Object.assign(window.PixivBatch.modeControls, {
    bind: bindBatchModeControls,
    render: renderBatchModeControl,
    renderAll: renderAllBatchModeControls,
    reconcile: reconcileBatchModeControls,
    selectSource: selectBatchModeSource,
    selectType: selectBatchModeType,
    syncType: syncBatchModeType,
    selection: batchModeControlSnapshot,
    sources: batchModeControlSources,
    onChange: onBatchModeControlChange,
    renderSupportedImportSources: renderSupportedImportDataSources
});
