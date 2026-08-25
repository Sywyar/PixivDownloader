'use strict';
// 计划任务来源前端运行时：宿主只消费后端盖章的来源 manifest，并为同一 owner publication
// 创建受控注册作用域。来源模块不能自报 owner、代际或可注册的 sourceType；manifest 变化时旧
// handler 会先失活并收到 AbortSignal，再由新 publication 原子替换。
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.scheduleSources = (function () {
    const CONTRACT_VERSION = 1;
    const ENDPOINT = '/api/schedule/sources';
    const MODULE_INITIALIZER_TIMEOUT_MS = 5000;
    const SCRIPT_LOAD_TIMEOUT_MS = 5000;
    const EMPTY = Object.freeze({
        epoch: '',
        revision: -1,
        identity: '',
        descriptors: new Map(),
        aliases: new Map(),
        handlers: new Map(),
        controller: new AbortController(),
        disposers: []
    });

    let current = EMPTY;
    const modules = window.PixivBatch.scheduleSourceRuntimeModules || {};
    if (!modules.normalize || !modules.runtime) {
        throw new Error('schedule source runtime modules are unavailable');
    }
    const moduleContext = {
        CONTRACT_VERSION, ENDPOINT, MODULE_INITIALIZER_TIMEOUT_MS,
        SCRIPT_LOAD_TIMEOUT_MS, EMPTY,
        publish(snapshot) { current = snapshot; }
    };
    modules.normalize.install(moduleContext);
    modules.runtime.install(moduleContext);
    delete window.PixivBatch.scheduleSourceRuntimeModules;
    const {
        text, sourceEditorError, ownerIdentity, normalizeFetchLimitPresentation,
        normalizeFetchLimitMode, normalizeCredentialContribution,
        normalizeCredentialTaskPresentation, normalizeCredentialPolicyGroup,
        normalizeCredentialOperationResult, normalizeCredentialValidation,
        normalizedOperationPromise, registerModule, refresh, activationIsCurrent
    } = moduleContext;
    const disposeRuntime = moduleContext.dispose;
    function canonicalSourceType(sourceType) {
        const normalized = text(sourceType);
        return current.descriptors.has(normalized)
            ? normalized : (current.aliases.get(normalized) || normalized);
    }

    function descriptor(sourceType) {
        return current.descriptors.get(canonicalSourceType(sourceType)) || null;
    }

    function handler(sourceType) {
        const entry = current.handlers.get(canonicalSourceType(sourceType));
        return entry && activationIsCurrent(entry.activation) && !current.controller.signal.aborted
            ? entry : null;
    }

    function isEntryCurrent(entry) {
        return !!entry && handler(entry.descriptor.sourceType) === entry;
    }

    function assertEntryCurrent(entry) {
        if (!isEntryCurrent(entry)) {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                'schedule source handler became stale');
        }
    }

    function freezeJsonValue(value) {
        if (!value || typeof value !== 'object') return value;
        if (Array.isArray(value)) {
            value.forEach(freezeJsonValue);
            return Object.freeze(value);
        }
        Object.keys(value).forEach(key => freezeJsonValue(value[key]));
        return Object.freeze(value);
    }

    function quickSourceSnapshot(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
        try {
            const json = JSON.stringify(value);
            if (!json || json.length > 131072) return null;
            const copy = JSON.parse(json);
            return copy && typeof copy === 'object' && !Array.isArray(copy)
                ? freezeJsonValue(copy) : null;
        } catch (e) {
            return null;
        }
    }

    function scopedSourceContext(entry, context) {
        const raw = context && typeof context === 'object' ? context : {};
        const host = raw.__scheduleAcquisitionHost;
        const out = {
            mode: text(raw.mode),
            quickSource: quickSourceSnapshot(raw.quickSource)
        };
        const acquisitionMode = value => {
            assertEntryCurrent(entry);
            const mode = normalizeAcquisitionMode(text(value) || text(raw.mode));
            if (!mode || !entry.descriptor.acquisitionModes.includes(mode)) {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule source acquisition mode is unavailable');
            }
            return mode;
        };
        out.acquisitionInput = modeValue => {
            const mode = acquisitionMode(modeValue);
            if (!host || typeof host.input !== 'function') {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule acquisition input is unavailable');
            }
            const value = host.input(mode);
            if (value && typeof value.then === 'function') {
                Promise.resolve(value).catch(() => {});
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_DEFINITION_INVALID',
                    'schedule acquisition input must be read synchronously');
            }
            assertEntryCurrent(entry);
            return value == null ? '' : String(value);
        };
        out.restoreAcquisition = (modeValue, value) => {
            const mode = acquisitionMode(modeValue);
            if (!host || typeof host.restore !== 'function') {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule acquisition restore is unavailable');
            }
            const restored = host.restore(mode, value == null ? '' : String(value));
            if (restored && typeof restored.then === 'function') {
                Promise.resolve(restored).catch(() => {});
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_DEFINITION_INVALID',
                    'schedule acquisition restore must complete synchronously');
            }
            assertEntryCurrent(entry);
            return restored !== false;
        };
        return Object.freeze(out);
    }

    function guardReturnedValue(value, entry, seen) {
        if (typeof value === 'function') {
            return function () {
                assertEntryCurrent(entry);
                let result;
                try {
                    result = value.apply(this, arguments);
                } catch (e) {
                    assertEntryCurrent(entry);
                    throw e;
                }
                if (!result || typeof result.then !== 'function') {
                    assertEntryCurrent(entry);
                    return result;
                }
                return Promise.resolve(result).then(resolved => {
                    assertEntryCurrent(entry);
                    return guardReturnedValue(resolved, entry, new Map());
                }, error => {
                    assertEntryCurrent(entry);
                    throw error;
                });
            };
        }
        if (!value || typeof value !== 'object') return value;
        if (seen.has(value)) return seen.get(value);
        if (Array.isArray(value)) {
            const out = [];
            seen.set(value, out);
            value.forEach(item => out.push(guardReturnedValue(item, entry, seen)));
            return Object.freeze(out);
        }
        const proto = Object.getPrototypeOf(value);
        if (proto !== Object.prototype && proto !== null) return value;
        const out = {};
        seen.set(value, out);
        Object.keys(value).forEach(key => {
            out[key] = guardReturnedValue(value[key], entry, seen);
        });
        return Object.freeze(out);
    }

    function invoke(sourceType, method, args, fallback) {
        const entry = handler(sourceType);
        const fn = entry && entry.methods[method];
        if (typeof fn !== 'function') return fallback;
        const activation = entry.activation;
        const result = fn.apply(null, args || []);
        if (!result || typeof result.then !== 'function') {
            if (!activationIsCurrent(activation) || current.controller.signal.aborted) {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule source handler became stale');
            }
            return guardReturnedValue(result, entry, new Map());
        }
        return Promise.resolve(result).then(value => {
            if (!activationIsCurrent(activation) || current.controller.signal.aborted) {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule source handler became stale');
            }
            return guardReturnedValue(value, entry, new Map());
        }, error => {
            if (!activationIsCurrent(activation) || current.controller.signal.aborted) {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule source handler became stale');
            }
            throw error;
        });
    }

    function invokeSync(sourceType, method, args, fallback) {
        const result = invoke(sourceType, method, args, fallback);
        if (result && typeof result.then === 'function') {
            // 宿主以同步值消费这些编辑器 hook；主动吸收晚 rejection，但立即拒绝该贡献。
            Promise.resolve(result).catch(() => {});
            throw sourceEditorError(
                'SCHEDULE_SOURCE_DEFINITION_INVALID',
                'schedule source ' + method + ' hook must return synchronously');
        }
        return result;
    }

    function requestedWorkTypes(context) {
        const values = context && Array.isArray(context.workTypes)
            ? context.workTypes : [context && context.workType];
        return Array.from(new Set(values.map(text).filter(Boolean)));
    }

    function sourceSupportsContext(source, context) {
        const requested = requestedWorkTypes(context);
        return !requested.length || requested.every(type => source.possibleWorkTypes.includes(type));
    }

    function normalizeAcquisitionMode(mode) {
        const normalized = text(mode);
        // 页面状态沿用成熟工作台的 tab id；插件契约只消费稳定的中性取得模式码。
        return normalized === 'quick-fetch' ? 'quick' : normalized;
    }

    function exactContextEntry(mode, context) {
        const normalizedMode = normalizeAcquisitionMode(mode);
        const requestedType = text(context && context.editingSourceType)
            || (normalizedMode === 'quick' ? text(context && context.quickSource
                && (context.quickSource.sourceType || context.quickSource.type)) : '');
        if (!requestedType) return undefined;
        const entry = handler(requestedType);
        if (!entry || !entry.descriptor.acquisitionModes.includes(normalizedMode)
            || !sourceSupportsContext(entry.descriptor, context)) {
            return null;
        }
        return entry;
    }

    function matchingEntry(mode, context) {
        const normalizedMode = normalizeAcquisitionMode(mode);
        const exact = exactContextEntry(normalizedMode, context);
        if (exact !== undefined) return exact;
        const matchesFound = [];
        for (const source of current.descriptors.values()) {
            if (!source.acquisitionModes.includes(normalizedMode)) continue;
            if (!sourceSupportsContext(source, context)) continue;
            const entry = handler(source.sourceType);
            if (!entry) continue;
            const matches = entry.methods.matches;
            try {
                if (typeof matches !== 'function') {
                    if (!isEntryCurrent(entry)) return null;
                    matchesFound.push(entry);
                    continue;
                }
                const matched = matches(scopedSourceContext(entry, context));
                if (matched && typeof matched.then === 'function') {
                    Promise.resolve(matched).catch(() => {});
                    throw new Error('schedule source matches hook must return synchronously');
                }
                // matches 可同步触发 unload/reload；一旦失活就终止本次选择，不得继续误选其它来源。
                if (!isEntryCurrent(entry)) return null;
                if (matched === true) matchesFound.push(entry);
            } catch (e) {
                if (!isEntryCurrent(entry)) return null;
                console.warn('[scheduleSources] 来源 matches 钩子失败：', source.sourceType, e);
            }
        }
        if (matchesFound.length > 1) {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_EDITOR_AMBIGUOUS',
                'schedule source editor selection is ambiguous');
        }
        return matchesFound[0] || null;
    }

    function previewForMode(mode, context) {
        const entry = matchingEntry(mode, context);
        if (!entry) return null;
        const preview = typeof entry.methods.preview === 'function'
            ? invokeSync(entry.descriptor.sourceType, 'preview',
                [scopedSourceContext(entry, context)], null)
            : null;
        const previewValue = preview && typeof preview === 'object' ? preview : null;
        return Object.freeze({
            sourceType: entry.descriptor.sourceType,
            descriptor: entry.descriptor,
            activationToken: entry.descriptor.activationToken,
            fetchLimitMode: normalizeFetchLimitMode(previewValue && previewValue.fetchLimitMode),
            fetchLimitPresentation: normalizeFetchLimitPresentation(
                previewValue && previewValue.fetchLimitPresentation,
                entry.descriptor.presentation.displayNamespace),
            preview
        });
    }

    function captureForMode(mode, context) {
        const entry = matchingEntry(mode, context);
        if (!entry) {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                'schedule source editor is unavailable');
        }
        const captured = invokeSync(entry.descriptor.sourceType, 'capture',
            [scopedSourceContext(entry, context)], null);
        if (!captured || typeof captured !== 'object') {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_DEFINITION_INVALID',
                'schedule source editor returned an invalid definition');
        }
        const params = Object.prototype.hasOwnProperty.call(captured, 'params')
            ? captured.params : captured.definition;
        return Object.freeze({
            sourceType: entry.descriptor.sourceType,
            activationToken: entry.descriptor.activationToken,
            params,
            fetchLimitMode: normalizeFetchLimitMode(captured.fetchLimitMode),
            fetchLimitPresentation: normalizeFetchLimitPresentation(
                captured.fetchLimitPresentation,
                entry.descriptor.presentation.displayNamespace),
            quickLabel: captured.quickLabel || null,
            workType: captured.workType || null
        });
    }

    function restoreTask(task, context) {
        const sourceType = text(task && (task.sourceType || task.type));
        const entry = handler(sourceType);
        return invokeSync(sourceType, 'restore',
            [task, entry ? scopedSourceContext(entry, context) : context], null);
    }

    function summary(task, context) {
        const sourceType = text(task && (task.sourceType || task.type));
        const entry = handler(sourceType);
        return invokeSync(sourceType, 'summary',
            [task, entry ? scopedSourceContext(entry, context) : context], null);
    }

    function fetchLimitMode(sourceType, definition, context) {
        const entry = handler(sourceType);
        return normalizeFetchLimitMode(invokeSync(sourceType, 'fetchLimitMode',
            [definition, entry ? scopedSourceContext(entry, context) : context], null));
    }

    function quickSourceNote(sourceType, context) {
        const entry = handler(sourceType);
        return invokeSync(sourceType, 'quickSourceNote',
            [entry ? scopedSourceContext(entry, context) : context], null);
    }

    function credentialLease(entry) {
        if (!entry) {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                'schedule source credential contribution is unavailable');
        }
        return Object.freeze({
            sourceType: entry.descriptor.sourceType,
            ownerPluginId: entry.descriptor.ownerPluginId,
            packageId: entry.descriptor.packageId,
            pluginGeneration: entry.descriptor.pluginGeneration,
            publicationId: entry.descriptor.publicationId,
            activationToken: entry.descriptor.activationToken,
            signal: current.controller.signal,
            isCurrent() { return isEntryCurrent(entry); },
            assertCurrent() { assertEntryCurrent(entry); }
        });
    }

    function credentialContribution(sourceType, context) {
        const entry = handler(sourceType);
        if (!entry) return null;
        try {
            const raw = invokeSync(sourceType, 'credentialContribution',
                [scopedSourceContext(entry, context), credentialLease(entry)], null);
            return normalizeCredentialContribution(raw, entry.descriptor);
        } catch (e) {
            return null;
        }
    }

    function invokeCredentialWrite(sourceType, method, args, context) {
        const entry = handler(sourceType);
        if (!entry || typeof entry.methods[method] !== 'function') {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                'schedule source credential contribution is unavailable');
        }
        return invoke(sourceType, method, (Array.isArray(args) ? args : []).concat([
            scopedSourceContext(entry, context), credentialLease(entry)
        ]), null);
    }

    function validateCredential(sourceType, credential, context) {
        const value = invokeCredentialWrite(
            sourceType, 'validateCredential', [String(credential || '')], context);
        return normalizedOperationPromise(value, normalizeCredentialValidation);
    }

    function bindCredential(sourceType, taskId, credential, context) {
        const value = invokeCredentialWrite(sourceType, 'bindCredential',
            [taskId, String(credential || '')], context);
        return normalizedOperationPromise(value, normalizeCredentialOperationResult);
    }

    function bindSavedCredential(sourceType, taskId, context) {
        const value = invokeCredentialWrite(
            sourceType, 'bindSavedCredential', [taskId], context);
        return normalizedOperationPromise(value, normalizeCredentialOperationResult);
    }

    function revokeCredential(sourceType, taskId, context) {
        const value = invokeCredentialWrite(
            sourceType, 'revokeCredential', [taskId], context);
        return normalizedOperationPromise(value, normalizeCredentialOperationResult);
    }

    function jsonSnapshot(value, maxLength) {
        try {
            const json = JSON.stringify(value);
            if (!json || json.length > maxLength) return null;
            return freezeJsonValue(JSON.parse(json));
        } catch (e) {
            return null;
        }
    }

    function credentialTaskPresentation(sourceType, task, context) {
        const entry = handler(sourceType);
        if (!entry) return null;
        const taskSnapshot = jsonSnapshot(task, 262144);
        if (!taskSnapshot) return null;
        const raw = invokeSync(sourceType, 'credentialTaskPresentation', [
            taskSnapshot, scopedSourceContext(entry, context), credentialLease(entry)
        ], null);
        return normalizeCredentialTaskPresentation(raw);
    }

    function credentialPolicyGroups(tasks, context) {
        const taskSnapshots = Array.isArray(tasks)
            ? tasks.slice(0, 512).map(task => jsonSnapshot(task, 262144)).filter(Boolean)
            : [];
        if (!taskSnapshots.length) return Object.freeze([]);
        const seenOwners = new Set();
        const groups = new Map();
        current.handlers.forEach(entry => {
            if (!isEntryCurrent(entry) || typeof entry.methods.credentialPolicyGroups !== 'function') return;
            const ownerKey = ownerIdentity(current.epoch, entry.descriptor)
                + ':' + (entry.descriptor.frontend ? entry.descriptor.frontend.moduleUrl : '');
            if (seenOwners.has(ownerKey)) return;
            seenOwners.add(ownerKey);
            let raw;
            try {
                raw = invokeSync(entry.descriptor.sourceType, 'credentialPolicyGroups', [
                    Object.freeze(taskSnapshots.slice()),
                    scopedSourceContext(entry, context),
                    credentialLease(entry)
                ], []);
            } catch (e) {
                return;
            }
            (Array.isArray(raw) ? raw : []).forEach(value => {
                const group = normalizeCredentialPolicyGroup(value, entry);
                if (group && !groups.has(group.identityKey)) groups.set(group.identityKey, group);
            });
        });
        return Object.freeze(Array.from(groups.values()));
    }

    function applyCredentialPolicyAction(sourceType, request, context) {
        const snapshot = jsonSnapshot(request, 65536);
        if (!snapshot) {
            return Object.freeze({ok: false, status: 'failed', error: 'invalid credential action'});
        }
        const value = invokeCredentialWrite(
            sourceType, 'applyCredentialPolicyAction', [snapshot], context);
        return normalizedOperationPromise(value, normalizeCredentialOperationResult);
    }

    function isAvailable(sourceType) {
        return !!handler(sourceType);
    }

    function activationToken(sourceType) {
        const value = descriptor(sourceType);
        return value ? value.activationToken : null;
    }

    function activationLease(sourceType) {
        const entry = handler(sourceType);
        if (!entry) {
            throw sourceEditorError(
                'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                'schedule source handler is unavailable');
        }
        return Object.freeze({
            sourceType: entry.descriptor.sourceType,
            activationToken: entry.descriptor.activationToken,
            signal: current.controller.signal,
            isCurrent() { return isEntryCurrent(entry); },
            assertCurrent() { assertEntryCurrent(entry); }
        });
    }

    function i18nNamespaces() {
        return Array.from(new Set(Array.from(current.descriptors.values())
            .map(source => source.presentation.displayNamespace).filter(Boolean)));
    }

    function dispose() {
        disposeRuntime();
    }
    return Object.freeze({
        registerModule,
        refresh,
        descriptor,
        previewForMode,
        captureForMode,
        restoreTask,
        summary,
        fetchLimitMode,
        quickSourceNote,
        credentialContribution,
        validateCredential,
        bindCredential,
        bindSavedCredential,
        revokeCredential,
        credentialTaskPresentation,
        credentialPolicyGroups,
        applyCredentialPolicyAction,
        isAvailable,
        activationToken,
        activationLease,
        i18nNamespaces,
        dispose,
        contractVersion: CONTRACT_VERSION
    });
})();
