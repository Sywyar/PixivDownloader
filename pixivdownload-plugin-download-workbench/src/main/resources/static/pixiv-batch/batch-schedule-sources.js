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
    let refreshPromise = null;
    let refreshQueued = false;
    let refreshQueuedForce = false;
    let activationSequence = 0;
    let loadSequence = 0;
    const pendingLoads = new Map();

    function text(value) {
        return value == null ? '' : String(value).trim();
    }

    function sourceEditorError(code, message) {
        const error = new Error(message);
        error.code = code;
        return error;
    }

    function normalizedModuleUrl(value) {
        const raw = text(value);
        if (!raw || raw.indexOf('\\') >= 0 || /[\u0000-\u001f\u007f]/.test(raw)) return null;
        let parsed;
        try {
            parsed = new URL(raw, window.location.origin);
        } catch (e) {
            return null;
        }
        if (parsed.origin !== window.location.origin || parsed.search || parsed.hash
            || !parsed.pathname.startsWith('/') || parsed.pathname.startsWith('//')
            || !parsed.pathname.endsWith('.js') || parsed.pathname.includes('/../')
            || parsed.pathname.includes('/./') || parsed.pathname.includes('%')) {
            return null;
        }
        return parsed.pathname;
    }

    function sourceIdentity(epoch, source) {
        return [
            epoch,
            source.ownerPluginId,
            source.packageId,
            source.pluginGeneration,
            source.publicationId,
            source.activationToken,
            source.legacyAliases.join(',')
        ].join(':');
    }

    function ownerIdentity(epoch, source) {
        return [epoch, source.ownerPluginId, source.packageId,
            source.pluginGeneration, source.publicationId, source.activationToken].join(':');
    }

    function normalizePresentation(raw) {
        const value = raw && typeof raw === 'object' ? raw : {};
        return Object.freeze({
            displayNamespace: text(value.displayNamespace),
            displayNameKey: text(value.displayNameKey),
            descriptionKey: text(value.descriptionKey),
            iconKey: text(value.iconKey),
            colorToken: text(value.colorToken)
        });
    }

    function i18nToken(value, maxLength) {
        const normalized = text(value);
        return normalized.length <= maxLength && /^[a-z0-9][a-z0-9._-]*$/i.test(normalized)
            ? normalized : '';
    }

    function normalizeFetchLimitPresentation(raw, expectedNamespace) {
        try {
            if (!raw || typeof raw !== 'object') return null;
            if (typeof raw.then === 'function') {
                Promise.resolve(raw).catch(() => {});
                return null;
            }
            const namespace = i18nToken(expectedNamespace, 64);
            const requestedNamespace = i18nToken(raw.namespace, 64);
            const watermarkHintKey = i18nToken(raw.watermarkHintKey, 192);
            const perRunHintKey = i18nToken(raw.perRunHintKey, 192);
            const fullFetchConfirmKey = i18nToken(raw.fullFetchConfirmKey, 192);
            if (!namespace || (requestedNamespace && requestedNamespace !== namespace)
                || (!watermarkHintKey && !perRunHintKey && !fullFetchConfirmKey)) {
                return null;
            }
            return Object.freeze({
                namespace,
                watermarkHintKey,
                perRunHintKey,
                fullFetchConfirmKey
            });
        } catch (e) {
            return null;
        }
    }

    function normalizeFetchLimitMode(value) {
        const mode = text(value);
        return mode === 'watermark' || mode === 'per-run' ? mode : null;
    }

    function normalizeSource(epoch, raw) {
        if (!raw || typeof raw !== 'object') return null;
        const sourceType = text(raw.sourceType);
        const ownerPluginId = text(raw.ownerPluginId);
        const packageId = text(raw.packageId);
        const activationToken = text(raw.activationToken);
        const definitionSchema = text(raw.definitionSchema);
        const definitionVersion = Number(raw.definitionVersion);
        const pluginGeneration = Number(raw.pluginGeneration);
        const publicationId = Number(raw.publicationId);
        if (!sourceType || !ownerPluginId || !packageId || !activationToken || !definitionSchema
            || !Number.isInteger(definitionVersion) || definitionVersion <= 0
            || !Number.isSafeInteger(pluginGeneration) || pluginGeneration < 0
            || !Number.isSafeInteger(publicationId) || publicationId <= 0) {
            return null;
        }
        const frontend = raw.frontend && typeof raw.frontend === 'object'
            ? {
                contractVersion: Number(raw.frontend.contractVersion),
                moduleUrl: normalizedModuleUrl(raw.frontend.moduleUrl)
            }
            : null;
        const normalizedFrontend = frontend
            && frontend.contractVersion === CONTRACT_VERSION && frontend.moduleUrl
            ? Object.freeze(frontend)
            : null;
        const acquisitionModes = Array.isArray(raw.acquisitionModes)
            ? Object.freeze(Array.from(new Set(raw.acquisitionModes.map(text).filter(Boolean))))
            : Object.freeze([]);
        const possibleWorkTypes = Array.isArray(raw.possibleWorkTypes)
            ? Object.freeze(Array.from(new Set(raw.possibleWorkTypes.map(text).filter(Boolean))))
            : Object.freeze([]);
        const legacyAliases = Array.isArray(raw.legacyAliases)
            ? Object.freeze(Array.from(new Set(raw.legacyAliases.map(text).filter(Boolean))))
            : Object.freeze([]);
        const source = {
            sourceType,
            legacyAliases,
            ownerPluginId,
            packageId,
            pluginGeneration,
            publicationId,
            activationToken,
            definitionSchema,
            definitionVersion,
            presentation: normalizePresentation(raw.presentation),
            acquisitionModes,
            possibleWorkTypes,
            frontend: normalizedFrontend
        };
        source.identity = sourceIdentity(epoch, source);
        return Object.freeze(source);
    }

    function normalizeManifest(raw) {
        const epoch = text(raw && raw.epoch);
        const revision = Number(raw && raw.revision);
        if (!epoch || !Number.isSafeInteger(revision) || revision < 0) {
            throw new Error('invalid schedule source manifest');
        }
        const descriptors = new Map();
        const sources = Array.isArray(raw.sources) ? raw.sources : [];
        sources.forEach(item => {
            const source = normalizeSource(epoch, item);
            if (!source) return;
            if (descriptors.has(source.sourceType)) {
                throw new Error('duplicate schedule source type');
            }
            descriptors.set(source.sourceType, source);
        });
        const aliases = new Map();
        descriptors.forEach(source => source.legacyAliases.forEach(alias => {
            if (alias === source.sourceType || descriptors.has(alias) || aliases.has(alias)) {
                throw new Error('conflicting schedule source alias');
            }
            aliases.set(alias, source.sourceType);
        }));
        const identity = epoch + ':' + revision + ':' + Array.from(descriptors.values())
            .map(source => source.identity).join('|');
        return {epoch, revision, identity, descriptors, aliases};
    }

    function deactivate(snapshot) {
        if (!snapshot || snapshot === EMPTY) return;
        try {
            snapshot.controller.abort();
        } catch (e) {
            // AbortController.abort 是 best-effort；disposer 仍继续执行。
        }
        snapshot.disposers.splice(0).reverse().forEach(dispose => {
            try {
                dispose();
            } catch (e) {
                console.warn('[scheduleSources] 来源模块清理失败：', e);
            }
        });
    }

    function announceChange(snapshot) {
        try {
            window.dispatchEvent(new CustomEvent('pixivbatch:schedulesourceschanged', {
                detail: {epoch: snapshot.epoch, revision: snapshot.revision}
            }));
        } catch (e) {
            // 旧浏览器缺少 CustomEvent 构造器时不影响来源调用。
        }
    }

    function moduleGroups(manifest) {
        const groups = new Map();
        manifest.descriptors.forEach(source => {
            if (!source.frontend) return;
            const key = source.frontend.moduleUrl;
            const existing = groups.get(key);
            if (existing) {
                if (existing.ownerIdentity !== ownerIdentity(manifest.epoch, source)) {
                    throw new Error('schedule source module spans multiple owner activations');
                }
                existing.sources.push(source);
                return;
            }
            groups.set(key, {
                moduleUrl: key,
                ownerIdentity: ownerIdentity(manifest.epoch, source),
                sources: [source]
            });
        });
        return Array.from(groups.values());
    }

    function contributionMethods(value) {
        const allowed = [
            'matches', 'preview', 'capture', 'restore', 'summary', 'fetchLimitMode',
            'quickSourceNote', 'credentialActions', 'dispose'
        ];
        const out = {};
        allowed.forEach(name => {
            if (typeof value[name] === 'function') out[name] = value[name];
        });
        return Object.freeze(out);
    }

    function scopedApi(load, candidate, controller, registerCleanup) {
        const allowed = new Map(load.group.sources.map(source => [source.sourceType, source]));
        const registered = new Set();
        return Object.freeze({
            signal: controller.signal,
            isActive() {
                return load.activation === activationSequence && !controller.signal.aborted;
            },
            assertActive() {
                if (load.activation !== activationSequence || controller.signal.aborted) {
                    throw new Error('schedule source module activation is stale');
                }
            },
            descriptors: Object.freeze(load.group.sources.slice()),
            registerSource(sourceType, contribution) {
                if (load.activation !== activationSequence || controller.signal.aborted) {
                    throw new Error('schedule source module activation is stale');
                }
                const normalizedType = text(sourceType);
                const descriptor = allowed.get(normalizedType);
                if (!descriptor || registered.has(normalizedType)
                    || !contribution || typeof contribution !== 'object') {
                    throw new Error('schedule source contribution is not declared by this module');
                }
                const methods = contributionMethods(contribution);
                if (typeof methods.capture !== 'function'
                    || typeof methods.restore !== 'function'
                    || typeof methods.summary !== 'function') {
                    throw new Error('schedule source contribution misses required hooks');
                }
                registered.add(normalizedType);
                candidate.set(normalizedType, Object.freeze({
                    descriptor,
                    activation: load.activation,
                    methods
                }));
                if (typeof methods.dispose === 'function') {
                    registerCleanup(() => methods.dispose());
                }
            },
            onCleanup(callback) {
                if (typeof callback !== 'function') throw new Error('cleanup callback must be a function');
                registerCleanup(callback);
            }
        });
    }

    function awaitInitializer(result, controller, cleanupLateResult) {
        return new Promise((resolve, reject) => {
            let settled = false;
            const finish = (callback, value) => {
                if (settled) return false;
                settled = true;
                clearTimeout(timer);
                controller.signal.removeEventListener('abort', onAbort);
                callback(value);
                return true;
            };
            const onAbort = () => finish(reject,
                new Error('schedule source module activation is stale'));
            const timer = setTimeout(() => finish(reject,
                new Error('schedule source module initializer timed out')),
            MODULE_INITIALIZER_TIMEOUT_MS);
            controller.signal.addEventListener('abort', onAbort, {once: true});
            Promise.resolve(result).then(value => {
                if (!finish(resolve, value)) cleanupLateResult(value);
            }, failure => {
                finish(reject, failure);
            });
        });
    }

    function registerModule(moduleUrl, initializer) {
        const script = document.currentScript;
        const token = script && script.dataset ? text(script.dataset.scheduleModuleToken) : '';
        const load = token ? pendingLoads.get(token) : null;
        if (!load || load.script !== script || load.moduleUrl !== normalizedModuleUrl(moduleUrl)
            || typeof initializer !== 'function' || load.initializer) {
            return false;
        }
        load.initializer = initializer;
        return true;
    }

    function appendCachebuster(moduleUrl, load) {
        const separator = moduleUrl.includes('?') ? '&' : '?';
        return moduleUrl + separator + '__schedule_source=' + encodeURIComponent([
            load.epoch, load.revision, load.group.sources[0].pluginGeneration,
            load.group.sources[0].publicationId, load.token
        ].join('-'));
    }

    function loadModule(group, manifest, activation, candidate, controller, disposers) {
        return new Promise(resolve => {
            const token = 'schedule-source-' + (++loadSequence) + '-' + activation;
            const load = {
                token,
                moduleUrl: group.moduleUrl,
                group,
                epoch: manifest.epoch,
                revision: manifest.revision,
                activation,
                initializer: null,
                script: null
            };
            const script = document.createElement('script');
            load.script = script;
            script.async = true;
            script.dataset.scheduleModuleToken = token;
            script.src = appendCachebuster(group.moduleUrl, load);
            pendingLoads.set(token, load);
            let settled = false;
            let loadTimer = setTimeout(() => {
                console.warn('[scheduleSources] 来源前端模块加载超时：', group.moduleUrl);
                finish();
            }, SCRIPT_LOAD_TIMEOUT_MS);
            const finish = () => {
                if (settled) return;
                settled = true;
                if (loadTimer != null) clearTimeout(loadTimer);
                loadTimer = null;
                pendingLoads.delete(token);
                script.onload = null;
                script.onerror = null;
                try { script.remove(); } catch (e) { /* detached test DOM */ }
                resolve();
            };
            script.onerror = () => {
                console.warn('[scheduleSources] 来源前端模块加载失败：', group.moduleUrl);
                finish();
            };
            script.onload = async () => {
                if (loadTimer != null) clearTimeout(loadTimer);
                loadTimer = null;
                if (settled) return;
                if (activation !== activationSequence || controller.signal.aborted) {
                    finish();
                    return;
                }
                if (typeof load.initializer !== 'function') {
                    console.warn('[scheduleSources] 来源前端模块未注册 initializer：', group.moduleUrl);
                    finish();
                    return;
                }
                const groupCandidate = new Map();
                const groupDisposers = [];
                let cleaned = false;
                const cleanupGroup = () => {
                    if (cleaned) return;
                    cleaned = true;
                    groupDisposers.splice(0).reverse().forEach(dispose => {
                        try { dispose(); } catch (e) {
                            console.warn('[scheduleSources] 来源模块清理失败：', e);
                        }
                    });
                };
                const registerCleanup = callback => {
                    if (typeof callback !== 'function') {
                        throw new Error('cleanup callback must be a function');
                    }
                    if (cleaned || activation !== activationSequence || controller.signal.aborted) {
                        try { callback(); } catch (e) {
                            console.warn('[scheduleSources] 来源模块清理失败：', e);
                        }
                        throw new Error('schedule source module activation is stale');
                    }
                    groupDisposers.push(callback);
                };
                const cleanupLateResult = result => {
                    if (typeof result !== 'function') return;
                    try { result(); } catch (e) {
                        console.warn('[scheduleSources] 来源模块清理失败：', e);
                    }
                };
                disposers.push(cleanupGroup);
                try {
                    const result = await awaitInitializer(load.initializer(
                        scopedApi(load, groupCandidate, controller, registerCleanup)),
                    controller, cleanupLateResult);
                    if (typeof result === 'function') {
                        if (cleaned) {
                            cleanupLateResult(result);
                        } else {
                            groupDisposers.push(result);
                        }
                    }
                    if (activation !== activationSequence || controller.signal.aborted) {
                        throw new Error('schedule source module activation is stale');
                    }
                    groupCandidate.forEach((entry, sourceType) => candidate.set(sourceType, entry));
                } catch (e) {
                    cleanupGroup();
                    const cleanupIndex = disposers.indexOf(cleanupGroup);
                    if (cleanupIndex >= 0) disposers.splice(cleanupIndex, 1);
                    console.warn('[scheduleSources] 来源前端模块初始化失败：', group.moduleUrl, e);
                } finally {
                    finish();
                }
            };
            (document.head || document.documentElement).appendChild(script);
        });
    }

    async function install(manifest) {
        const previous = current;
        const activation = ++activationSequence;
        const controller = new AbortController();
        const disposers = [];
        const loadingSnapshot = {
            epoch: manifest.epoch,
            revision: manifest.revision,
            identity: manifest.identity,
            descriptors: manifest.descriptors,
            aliases: manifest.aliases,
            handlers: new Map(),
            controller,
            disposers
        };
        current = loadingSnapshot;
        deactivate(previous);
        announceChange(loadingSnapshot);

        const candidate = new Map();
        let groups = [];
        try {
            groups = moduleGroups(manifest);
        } catch (e) {
            console.warn('[scheduleSources] 来源 manifest 模块归属无效：', e);
        }
        await Promise.all(groups.map(group =>
            loadModule(group, manifest, activation, candidate, controller, disposers)));
        if (activation !== activationSequence || controller.signal.aborted) {
            disposers.slice().reverse().forEach(dispose => {
                try { dispose(); } catch (e) { /* stale cleanup */ }
            });
            return current;
        }
        current = {
            epoch: manifest.epoch,
            revision: manifest.revision,
            identity: manifest.identity,
            descriptors: manifest.descriptors,
            aliases: manifest.aliases,
            handlers: candidate,
            controller,
            disposers
        };
        announceChange(current);
        return current;
    }

    function needsRetry(manifest) {
        for (const source of manifest.descriptors.values()) {
            if (source.frontend && !current.handlers.has(source.sourceType)) return true;
        }
        return false;
    }

    async function refresh(force) {
        if (refreshPromise) {
            refreshQueued = true;
            refreshQueuedForce = refreshQueuedForce || !!force;
            return refreshPromise;
        }
        refreshPromise = (async () => {
            let forceNext = !!force;
            do {
                refreshQueued = false;
                const response = await fetch(ENDPOINT, {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: {'Accept': 'application/json'}
                });
                if (!response.ok) throw new Error('schedule source manifest HTTP ' + response.status);
                const manifest = normalizeManifest(await response.json());
                if (forceNext || manifest.identity !== current.identity || needsRetry(manifest)) {
                    await install(manifest);
                }
                forceNext = refreshQueuedForce;
                refreshQueuedForce = false;
            } while (refreshQueued);
            return current;
        })();
        try {
            return await refreshPromise;
        } finally {
            refreshPromise = null;
            refreshQueued = false;
            refreshQueuedForce = false;
        }
    }

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
        return entry && entry.activation === activationSequence && !current.controller.signal.aborted
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
            if (activation !== activationSequence || current.controller.signal.aborted) {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule source handler became stale');
            }
            return guardReturnedValue(result, entry, new Map());
        }
        return Promise.resolve(result).then(value => {
            if (activation !== activationSequence || current.controller.signal.aborted) {
                throw sourceEditorError(
                    'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
                    'schedule source handler became stale');
            }
            return guardReturnedValue(value, entry, new Map());
        }, error => {
            if (activation !== activationSequence || current.controller.signal.aborted) {
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

    function credentialActions(sourceType, context) {
        const entry = handler(sourceType);
        return invoke(sourceType, 'credentialActions',
            [entry ? scopedSourceContext(entry, context) : context], null);
    }

    function invokeCredentialAction(sourceType, actionName, args, context) {
        const entry = handler(sourceType);
        if (!entry) throw new Error('schedule source credential action is unavailable');
        const actions = invoke(sourceType, 'credentialActions',
            [scopedSourceContext(entry, context)], null);
        const run = value => {
            const action = value && value[text(actionName)];
            if (typeof action !== 'function') {
                throw new Error('schedule source credential action is unavailable');
            }
            const lease = Object.freeze({
                activationToken: entry.descriptor.activationToken,
                signal: current.controller.signal,
                isCurrent() { return isEntryCurrent(entry); },
                assertCurrent() { assertEntryCurrent(entry); }
            });
            return action.apply(null, (Array.isArray(args) ? args : []).concat([lease]));
        };
        return actions && typeof actions.then === 'function' ? actions.then(run) : run(actions);
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
        ++activationSequence;
        const previous = current;
        current = EMPTY;
        deactivate(previous);
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
        credentialActions,
        invokeCredentialAction,
        isAvailable,
        activationToken,
        activationLease,
        i18nNamespaces,
        dispose,
        contractVersion: CONTRACT_VERSION
    });
})();
