'use strict';
/* Reuse the production extension registries without importing the legacy page DOM. */
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.queue = Object.assign(window.PixivBatch.queue || {}, {
    commitQueueItemPatch,
    addItemsToQueue,
    removeFromQueue,
    renderQueue,
    updateStats,
    syncAllResultsQueueState
});
window.PixivBatch.cookie = Object.assign(window.PixivBatch.cookie || {}, {
    getCookie,
    getCookieFmt,
    getStoredCookie,
    setStoredCookie,
    removeStoredCookie,
    parseCookieToHeaderString,
    getCookieHeaderStringFor
});

// The built-in Pixiv descriptor names legacy render callbacks while registering.
// Alt renders every contribution through its own cards, so these callbacks stay inert.
var QUICK_PAGE_SIZE_ILLUST = 30;
var QUICK_PAGE_SIZE_NOVEL = 24;
var SINGLE_IMPORT_NOVEL_SOURCE = 'single-import-novel';
function renderPixivUserResults() {}
function renderPixivSearchResults() {}
function renderPixivSeriesResults() {}
function renderQuickIllustGrid() {}
function pixivQuickInnerCard() { return ''; }
function setCurrent(item) { renderCurrent(item); }
function setStatus(message, tone) { setDockStatus(message, tone); }
function syncSettings() { saveSettings(); }
function getSearchFiltersFromUI() { return normalizeSearchFilters(extraFilters); }
function defaultNovelTranslateLang() { return state.settings.novelTranslateLang || 'zh-CN'; }

function altQueueTypes() {
    return window.PixivBatch && window.PixivBatch.queueTypes;
}

function altScheduleSources() {
    return window.PixivBatch && window.PixivBatch.scheduleSources;
}

async function altI18nNamespaces() {
    const queue = altQueueTypes();
    const schedule = altScheduleSources();
    return ['batch-alt', 'common', 'tour']
        .concat(queue ? await queue.i18nNamespaces() : [])
        .concat(schedule ? await schedule.i18nNamespaces() : [])
        .filter((value, index, all) => value && all.indexOf(value) === index);
}

async function refreshAltI18n() {
    if (!window.PixivI18n) return;
    pageI18n = await PixivI18n.create({namespaces: await altI18nNamespaces()});
    pageI18n.apply();
    document.title = bt('page.title', '下载工作台 · Pixiv 下载助手');
}

async function bootstrapAltExtensions() {
    const queue = altQueueTypes();
    if (queue) await queue.bootstrap();
    const schedule = altScheduleSources();
    if (schedule) await schedule.refresh();
    if (queue) {
        extensionManifest = {
            downloadTypes: queue.downloadTypes(),
            uiSlots: queue.uiSlots()
        };
    }
    await refreshAltI18n();
}

function altSourceLabel(source) {
    const key = source && source.displayI18nKey;
    const namespace = source && source.displayNamespace;
    return key ? bt((namespace ? namespace + ':' : '') + key, source.id) : source.id;
}

function altSourcesForMode(mode) {
    const runtime = altQueueTypes();
    if (!runtime) return acquisitionSources(mode);
    return runtime.dataSourcesForMode(mode).map(source => ({
        id: source.id,
        label: altSourceLabel(source),
        types: source.types
    }));
}

function altTypesForSource(mode, sourceId) {
    const runtime = altQueueTypes();
    return runtime ? Array.from(runtime.typesForDataSource(mode, sourceId)) : [];
}

function altTypeLabel(type) {
    const runtime = altQueueTypes();
    const descriptor = runtime && runtime.manifestDescriptor(type);
    if (!descriptor) return type;
    const key = descriptor.displayI18nKey;
    const namespace = descriptor.displayNamespace;
    return key ? bt((namespace ? namespace + ':' : '') + key, type) : type;
}

function altSelectionForMode(mode, sourceId, selection) {
    const runtime = altQueueTypes();
    if (!runtime) return selection;
    const allowed = new Set(altTypesForSource(mode, sourceId).map(item => item.type));
    const direct = allowed.has(selection) ? selection : null;
    if (direct) return direct;
    const owner = runtime.resolveSelectionForMode(selection, mode, null);
    if (owner && allowed.has(owner)) return selection;
    return allowed.values().next().value || null;
}

function altAcquisition(mode, sourceId, selection) {
    const runtime = altQueueTypes();
    if (!runtime) return null;
    const type = runtime.resolveSelectionForMode(selection, mode, null);
    const allowed = new Set(altTypesForSource(mode, sourceId).map(item => item.type));
    return type && allowed.has(type) ? Object.assign({}, runtime.acquisition(type, mode), {type}) : null;
}

function altRequestUrl(spec) {
    if (typeof spec === 'string') return spec;
    if (!spec || typeof spec !== 'object' || !spec.endpoint) {
        throw new Error(bt('acquisition.error.request-unavailable', '当前来源无法构建请求'));
    }
    const params = new URLSearchParams();
    Object.entries(spec.params || {}).forEach(([key, value]) => {
        if (Array.isArray(value)) value.forEach(item => params.append(key, item));
        else if (value != null) params.append(key, value);
    });
    const endpoint = String(spec.endpoint);
    return endpoint + (params.toString() ? (endpoint.includes('?') ? '&' : '?') + params : '');
}

async function altAcquisitionJson(type, mode, spec, operation, context) {
    const runtime = altQueueTypes();
    const request = runtime.prepareAcquisitionRequest(
        type, mode, altRequestUrl(spec), operation, context || {});
    const response = await fetch(request.url, request.init);
    const data = await response.json().catch(() => ({}));
    request.assertCurrent();
    if (!response.ok || data.error) throw new Error(data.error || data.message || `HTTP ${response.status}`);
    return data;
}

function altNextCursor(data, current, hasMore) {
    if (!hasMore) return null;
    const next = data && data.nextCursor != null ? String(data.nextCursor) : '';
    if (!next || (current != null && next === String(current))) {
        throw new Error(bt('pagination.error.cursor-stalled', '分页游标未推进，已停止继续加载'));
    }
    return next;
}

function altCompatibilityInput(id, value) {
    let input = document.getElementById(id);
    if (!input) {
        input = document.createElement('input');
        input.type = 'hidden';
        input.id = id;
        document.body.appendChild(input);
    }
    input.value = value == null ? '' : String(value);
    return input;
}

function altCompatibilityRadio(name, value) {
    const wanted = String(value == null ? '' : value);
    let input = Array.from(document.querySelectorAll(`input[name="${name}"]`))
        .find(candidate => candidate.value === wanted);
    if (!input) {
        input = document.createElement('input');
        input.type = 'radio';
        input.name = name;
        input.hidden = true;
        input.value = wanted;
        document.body.appendChild(input);
    }
    input.checked = true;
    return input;
}

function altQuickScheduleSource() {
    const runtime = altQueueTypes();
    if (!runtime || !quickState.action) return null;
    const acquisition = runtime.acquisitionList('quick').find(item =>
        item.actions && item.actions[quickState.action]);
    const action = acquisition && acquisition.actions[quickState.action];
    if (!action || typeof action.scheduleSource !== 'function') return null;
    const inner = quickState.drill && quickState.drill.type === 'user'
        ? {type: 'following-user', userId: quickState.drill.id, name: quickState.drill.name}
        : quickState.drill && quickState.drill.type === 'collection'
            ? {type: 'collection', id: quickState.drill.id, name: quickState.drill.name}
            : null;
    return action.scheduleSource({
        uid: quickState.uid,
        rest: quickState.action.endsWith('-hide') ? 'hide' : 'show',
        action: quickState.action,
        inner,
        accountId: quickState.uid,
        accountOwner: quickState.source
    });
}

function altScheduleSourceContext(fetchLimit) {
    state.settings.userKind = userState.kind;
    state.settings.searchKind = searchState.kind;
    if (seriesState.info && seriesState.info.seriesId != null) {
        seriesState.seriesId = seriesState.info.seriesId;
    }
    altCompatibilityInput('user-id-input', userState.input || userState.userId);
    altCompatibilityInput('search-word', searchState.word);
    altCompatibilityInput('search-content-filter', searchApiMode());
    altCompatibilityRadio('search-smode', searchState.sMode);
    altCompatibilityRadio('search-order', searchState.order);
    altCompatibilityRadio('search-submode', searchState.submode);
    altCompatibilityInput('batch-end-page', searchState.endPage);
    altCompatibilityInput('series-input-url', seriesState.url);
    altCompatibilityInput('sch-fetch-limit', fetchLimit || 0);

    const mode = state.mode;
    const quickSource = mode === QUICK_FETCH_MODE ? altQuickScheduleSource() : null;
    let workType = null;
    if (mode === 'user') {
        const acquisition = altAcquisition('user', userState.source, userState.kind);
        workType = acquisition && acquisition.type;
    } else if (mode === 'search') {
        const acquisition = altAcquisition('search', searchState.source, searchState.kind);
        workType = acquisition && acquisition.type;
    } else if (mode === 'series') {
        const acquisition = altAcquisition('series', seriesState.source, seriesState.kind);
        workType = acquisition && acquisition.type;
    }
    const workTypes = quickSource && Array.isArray(quickSource.workTypes)
        ? quickSource.workTypes.slice()
        : workType ? [workType] : [];
    return Object.freeze({
        mode,
        quickSource,
        workType: workTypes[0] || null,
        workTypes: Object.freeze(workTypes)
    });
}

function altCaptureScheduleSource(fetchLimit) {
    const runtime = altScheduleSources();
    if (!runtime) throw new Error(bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
    const captured = runtime.captureForMode(state.mode, altScheduleSourceContext(fetchLimit));
    const lease = runtime.activationLease(captured.sourceType);
    if (lease.activationToken !== captured.activationToken) {
        throw new Error(bt('schedule.error.concurrent-change', '来源状态已变化，请重试'));
    }
    return Object.freeze(Object.assign({}, captured, {lease}));
}

function appendExtensionCookieEditors(host) {
    const runtime = altQueueTypes();
    if (!runtime || !host) return;
    runtime.contributionsOf('cookie').forEach(contribution => {
        const section = el('section', 'ab-cookie-formats');
        section.appendChild(el('h4', 'ab-settings-group',
            bt('cookie.extension.title', '{type} 凭证', {type: altTypeLabel(contribution.type)})));
        const input = el('textarea', 'ab-input ab-cookie-input');
        input.rows = 4;
        input.spellcheck = false;
        input.value = getStoredCookie(contribution.type);
        input.placeholder = bt('cookie.extension.placeholder', '粘贴该来源所需的 Cookie / 凭证');
        section.appendChild(input);
        const status = el('p', 'ab-field-note');
        section.appendChild(status);
        const actions = el('div', 'ab-cookie-actions');
        const clear = el('button', 'ab-btn ab-btn--danger-ghost', bt('cookie.clear', '清除'));
        clear.type = 'button';
        clear.addEventListener('click', async () => {
            if (!await abConfirm('dialog.confirm-clear-cookie', '确认清除已保存的 Cookie？')) return;
            removeStoredCookie(contribution.type);
            input.value = '';
            status.textContent = bt('status.cookie-cleared', 'Cookie 已清除');
            refreshQuickCredentialGate();
        });
        const save = el('button', 'ab-btn ab-btn--primary', bt('cookie.save', '保存'));
        save.type = 'button';
        save.addEventListener('click', () => {
            const raw = input.value.trim();
            let validation = {ok: !!raw};
            try {
                validation = typeof contribution.validate === 'function'
                    ? contribution.validate(raw) : validation;
            } catch (e) {
                validation = {ok: false, error: e.message};
            }
            if (!validation || validation.ok !== true) {
                status.textContent = validation && (validation.error || validation.message)
                    || bt('cookie.extension.invalid', '凭证无效');
                return;
            }
            setStoredCookie(contribution.type, raw);
            status.textContent = bt('status.cookie-saved-simple', '凭证已保存');
            refreshQuickCredentialGate();
        });
        actions.appendChild(clear);
        actions.appendChild(save);
        section.appendChild(actions);
        host.appendChild(section);
    });
}

function altParseImportText(text) {
    const runtime = altQueueTypes();
    if (!runtime) return null;
    const contributions = runtime.contributionsOf('import');
    const bareDefaults = contributions.filter(item => item.bareDefault === true);
    const sectionFor = token => contributions.find(item =>
        [item.sectionType].concat(item.sectionAliases || [])
            .some(name => String(name || '').toLowerCase() === token.toLowerCase())) || null;
    const normalizeMatch = match => {
        if (match && typeof match === 'object') {
            const id = match.id ?? match.workId ?? match.value;
            return id == null ? null : Object.assign({}, match, {id: String(id)});
        }
        return match == null ? null : {id: String(match)};
    };
    const buildItem = (contribution, match, title, line) => {
        const normalized = normalizeMatch(match);
        if (!normalized || typeof contribution.buildItem !== 'function') return null;
        return (match && typeof match === 'object') || contribution.buildItem.length >= 3
            ? contribution.buildItem(normalized, title, line)
            : contribution.buildItem(normalized.id, title);
    };
    const unavailable = Symbol('unavailable');
    let section = bareDefaults.length === 1 ? bareDefaults[0] : unavailable;
    let explicit = false;
    let skippedUnavailable = 0;
    const rejected = [];
    const items = [];
    const lines = String(text || '').split('\n').map(line => line.trim()).filter(Boolean);
    for (const line of lines) {
        const header = line.match(/^([A-Za-z]+)\s*[:：]\s*$/);
        if (header) {
            section = sectionFor(header[1]) || unavailable;
            explicit = true;
            continue;
        }
        const matches = [];
        contributions.forEach(contribution => {
            try {
                const match = contribution.matchUrl && contribution.matchUrl(line);
                if (match != null) matches.push({contribution, match});
            } catch (e) {
                console.warn('[batch-alt] 单作品解析钩子失败：', contribution.type, e);
            }
        });
        if (matches.length > 1) {
            rejected.push(line);
            continue;
        }
        const title = (line.split('|')[1] || '').trim();
        if (matches.length === 1) {
            const match = matches[0];
            const item = buildItem(match.contribution, match.match, title, line);
            if (item) items.push(Object.assign({source: match.contribution.source}, item));
            continue;
        }
        const bare = line.match(/^(\d+)\s*(?:\|\s*(.*))?$/);
        if (!bare) {
            if (/^https?:\/\//.test(line)) rejected.push(line);
            continue;
        }
        if (section === unavailable) {
            if (explicit || bareDefaults.length !== 1) skippedUnavailable++;
            continue;
        }
        const item = buildItem(section, bare[1], (bare[2] || '').trim(), line);
        if (item) items.push(Object.assign({source: section.source}, item));
    }
    const seen = new Set();
    return {
        items: items.filter(item => {
            const key = String(item.kind) + ':' + String(item.id);
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        }),
        skippedUnavailable,
        rejected
    };
}

window.PixivBatchAlt.extensions = Object.assign(window.PixivBatchAlt.extensions || {}, {
    bootstrapAltExtensions,
    refreshAltI18n,
    altSourcesForMode,
    altTypesForSource,
    altSelectionForMode,
    altAcquisition,
    altAcquisitionJson,
    altParseImportText,
    altScheduleSourceContext,
    altCaptureScheduleSource,
    appendExtensionCookieEditors
});
