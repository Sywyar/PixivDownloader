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
        tabs: [],
        uiSlots: [],
        controller: new AbortController(),
        disposers: []
    });

    let current = EMPTY;
    let activationSequence = 0;
    let loadSequence = 0;
    let refreshPromise = null;
    let refreshQueued = false;
    let refreshQueuedForce = false;
    let prefetchedData = null;
    let prefetchPromise = null;
    let slotsBootstrapped = false;
    let slotRenderSequence = 0;
    let slotRenderTail = Promise.resolve();
    const pendingLoads = new Map();
    const slotMounts = new Map();

    function text(value) {
        return value == null ? '' : String(value).trim();
    }

    function isPlainObject(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
        const proto = Object.getPrototypeOf(value);
        return proto === Object.prototype || proto === null;
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

    function normalizedEndpoint(value) {
        const raw = text(value);
        if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.indexOf('\\') >= 0
            || /[\u0000-\u001f\u007f]/.test(raw)) {
            throw new Error('acquisition endpoint must be a same-origin absolute path');
        }
        const rawPath = raw.split('?', 1)[0];
        const pathSegments = rawPath.split('/');
        if (rawPath.includes('%') || pathSegments.some(segment => segment === '.' || segment === '..')) {
            throw new Error('acquisition endpoint path must not contain encoded or dot-segment traversal');
        }
        let parsed;
        try {
            parsed = new URL(raw, window.location.origin);
        } catch (e) {
            throw new Error('acquisition endpoint is invalid');
        }
        if (parsed.origin !== window.location.origin || parsed.hash || !parsed.pathname.startsWith('/')) {
            throw new Error('acquisition endpoint must be same-origin');
        }
        return parsed.pathname + parsed.search;
    }

    function sanitizedRequestHeaders(value) {
        const raw = value && typeof value === 'object' ? value.headers : null;
        const out = {};
        if (!raw) return Object.freeze(out);
        const allowed = Object.freeze({
            accept: {name: 'Accept', maxLength: 256},
            'x-acquisition-credential': {name: 'X-Acquisition-Credential', maxLength: 16384}
        });
        const append = (name, headerValue) => {
            const key = text(name);
            const rule = allowed[key.toLowerCase()];
            if (!key || !rule || /[\r\n]/.test(key)) return;
            const normalized = headerValue == null ? '' : String(headerValue);
            if (/\r|\n/.test(normalized) || normalized.length > rule.maxLength) return;
            out[rule.name] = normalized;
        };
        if (typeof Headers !== 'undefined' && raw instanceof Headers) {
            raw.forEach((headerValue, name) => append(name, headerValue));
        } else if (Array.isArray(raw)) {
            raw.forEach(pair => {
                if (Array.isArray(pair) && pair.length >= 2) append(pair[0], pair[1]);
            });
        } else if (typeof raw === 'object') {
            Object.keys(raw).forEach(name => append(name, raw[name]));
        }
        return Object.freeze(out);
    }

    function normalizeType(raw) {
        if (!raw || typeof raw !== 'object') return null;
        const owner = raw.owner && typeof raw.owner === 'object' ? raw.owner : {};
        const type = text(raw.type);
        const ownerPluginId = text(owner.pluginId || raw.ownerPluginId);
        const packageId = text(owner.packageId || raw.packageId);
        const moduleUrl = normalizedModuleUrl(raw.moduleUrl);
        const contractVersion = Number(raw.contractVersion);
        const pluginGeneration = Number(owner.generation != null ? owner.generation : raw.pluginGeneration);
        const publicationId = Number(owner.publicationId != null ? owner.publicationId : raw.publicationId);
        const legacyContract = raw.legacyContract === true;
        const order = Number(raw.order);
        if (!type || !ownerPluginId || !packageId || !moduleUrl
            || contractVersion !== CONTRACT_VERSION
            || !Number.isSafeInteger(pluginGeneration) || pluginGeneration < 0
            || !Number.isSafeInteger(publicationId) || publicationId <= 0) {
            return null;
        }
        const acquisitionModes = Array.isArray(raw.acquisitionModes)
            ? Array.from(new Set(raw.acquisitionModes.map(text).filter(mode => KNOWN_MODES.has(mode))))
            : [];
        const declaredSlots = Array.isArray(raw.uiSlots)
            ? Array.from(new Set(raw.uiSlots.map(text).filter(Boolean))) : [];
        const descriptor = Object.assign({}, raw, {
            contractVersion,
            type,
            ownerPluginId,
            packageId,
            pluginGeneration,
            publicationId,
            legacyContract,
            moduleUrl,
            order: Number.isFinite(order) ? order : 0,
            acquisitionModes: Object.freeze(acquisitionModes),
            uiSlots: Object.freeze(declaredSlots),
            filters: Object.freeze(Array.isArray(raw.filters) ? raw.filters.map(text).filter(Boolean) : []),
            settings: Object.freeze(Array.isArray(raw.settings) ? raw.settings.map(text).filter(Boolean) : [])
        });
        descriptor.identity = [ownerPluginId, packageId, pluginGeneration, publicationId, type, moduleUrl].join(':');
        return Object.freeze(descriptor);
    }

    function normalizeUiSlot(raw) {
        if (!raw || typeof raw !== 'object') return null;
        const owner = raw.owner && typeof raw.owner === 'object' ? raw.owner : {};
        const slotId = text(raw.slotId);
        const target = text(raw.target);
        const moduleUrl = raw.moduleUrl == null ? null : normalizedModuleUrl(raw.moduleUrl);
        const ownerPluginId = text(owner.pluginId);
        const packageId = text(owner.packageId);
        const pluginGeneration = Number(owner.generation);
        const publicationId = Number(owner.publicationId);
        const order = Number(raw.order);
        if (!slotId || !target || !ownerPluginId || !packageId
            || (raw.moduleUrl != null && !moduleUrl)
            || !Number.isSafeInteger(pluginGeneration) || pluginGeneration < 0
            || !Number.isSafeInteger(publicationId) || publicationId <= 0) {
            return null;
        }
        const descriptor = {
            slotId,
            target,
            moduleUrl,
            order: Number.isFinite(order) ? order : 0,
            metadata: Object.freeze(isPlainObject(raw.metadata) ? Object.assign({}, raw.metadata) : {}),
            ownerPluginId,
            packageId,
            pluginGeneration,
            publicationId
        };
        descriptor.identity = [
            ownerPluginId, packageId, pluginGeneration, publicationId, slotId, target, moduleUrl || ''
        ].join(':');
        return Object.freeze(descriptor);
    }

    function normalizeManifest(raw) {
        const epoch = raw && typeof raw.epoch === 'string' ? raw.epoch.trim() : '';
        const revision = Number(raw && raw.revision);
        if (!epoch) {
            throw new Error('invalid download extension epoch');
        }
        if (!Number.isSafeInteger(revision) || revision < 0) {
            throw new Error('invalid download extension revision');
        }
        const manifest = new Map();
        const list = Array.isArray(raw.downloadTypes) ? raw.downloadTypes : [];
        list.forEach(item => {
            const descriptor = normalizeType(item);
            if (!descriptor || manifest.has(descriptor.type)) return;
            manifest.set(descriptor.type, descriptor);
        });
        const orderedTypes = Array.from(manifest.values())
            .sort((a, b) => (a.order - b.order) || a.type.localeCompare(b.type))
            .map(item => item.type);
        const tabs = Array.isArray(raw.tabs) ? raw.tabs.slice() : [];
        const slotIds = new Set();
        const uiSlots = [];
        (Array.isArray(raw.uiSlots) ? raw.uiSlots : []).forEach(item => {
            const slot = normalizeUiSlot(item);
            if (!slot || slotIds.has(slot.slotId)) return;
            slotIds.add(slot.slotId);
            uiSlots.push(slot);
        });
        uiSlots.sort((a, b) => (a.order - b.order) || a.slotId.localeCompare(b.slotId));
        const identity = [epoch, revision]
            .concat(orderedTypes.map(type => manifest.get(type).identity))
            .concat(uiSlots.map(slot => slot.identity))
            .join('|');
        return {epoch, revision, identity, manifest, orderedTypes, tabs, uiSlots};
    }

    function appendCachebuster(moduleUrl, load) {
        return moduleUrl + '?__queue_type=' + encodeURIComponent([
            load.epoch,
            load.revision,
            load.descriptor.pluginGeneration,
            load.descriptor.publicationId,
            load.token
        ].join('-'));
    }

    function isActivationLive(activation) {
        return !!activation && activation.valid !== false && !activation.controller.signal.aborted;
    }

    function isCandidateCurrent(activation) {
        return isActivationLive(activation) && activation.sequence === activationSequence;
    }

    function isContextActive(activation) {
        return isActivationLive(activation) && (activation.installed || isCandidateCurrent(activation));
    }

    function staleQueueTypeError() {
        const error = new Error('queue type activation is stale');
        error.code = 'STALE_QUEUE_TYPE';
        return error;
    }

    function assertActivationLive(activation) {
        if (!isActivationLive(activation)) {
            throw staleQueueTypeError();
        }
    }

    function guardedFunction(fn, activation) {
        return function () {
            assertActivationLive(activation);
            const result = fn.apply(this, arguments);
            if (!result || typeof result.then !== 'function') {
                assertActivationLive(activation);
                return result;
            }
            return Promise.resolve(result).then(value => {
                assertActivationLive(activation);
                return value;
            }, error => {
                assertActivationLive(activation);
                throw error;
            });
        };
    }

    function guardValue(value, activation, seen) {
        if (typeof value === 'function') return guardedFunction(value, activation);
        if (!value || typeof value !== 'object') return value;
        if (seen.has(value)) return seen.get(value);
        if (Array.isArray(value)) {
            const out = [];
            seen.set(value, out);
            value.forEach(item => out.push(guardValue(item, activation, seen)));
            return Object.freeze(out);
        }
        if (!isPlainObject(value)) return value;
        const out = {};
        seen.set(value, out);
        Object.keys(value).forEach(key => {
            out[key] = guardValue(value[key], activation, seen);
        });
        return Object.freeze(out);
    }

    function sanitizeBehavior(descriptor, backend, activation, moduleScope, publishedSlotTargets) {
        if (!isPlainObject(descriptor) || typeof descriptor.process !== 'function') {
            throw new Error('queue type module misses process(item)');
        }
        const ownedFields = [
            'type', 'pluginId', 'ownerPluginId', 'packageId', 'pluginGeneration',
            'publicationId', 'moduleUrl', 'acquisitionModes'
        ];
        if (ownedFields.some(name => Object.prototype.hasOwnProperty.call(descriptor, name))) {
            throw new Error('queue type module attempted to self-report owner identity');
        }
        if (descriptor.contractVersion != null && Number(descriptor.contractVersion) !== CONTRACT_VERSION) {
            throw new Error('unsupported queue type module contractVersion');
        }
        const legacyContract = backend.legacyContract === true;
        const declared = new Set(backend.acquisitionModes);
        const allowedModes = legacyContract ? KNOWN_MODES : declared;
        const behavior = {};
        Object.keys(descriptor).forEach(key => {
            if (!['process', 'import', 'acquisition', 'contractVersion', 'slots', 'uiSlots', 'filters', 'settings']
                .includes(key)) {
                behavior[key] = descriptor[key];
            }
        });
        const processContext = processInvocationContext(backend, activation, moduleScope);
        behavior.process = function (item) {
            processContext.assertActive();
            return descriptor.process.call(this, item, processContext);
        };
        behavior.contractVersion = CONTRACT_VERSION;
        behavior.type = backend.type;
        behavior.owner = Object.freeze({
            ownerPluginId: backend.ownerPluginId,
            packageId: backend.packageId,
            pluginGeneration: backend.pluginGeneration,
            publicationId: backend.publicationId
        });
        if (allowedModes.has('single-import') && isPlainObject(descriptor.import)) {
            if (typeof descriptor.import.matchUrl === 'function'
                && typeof descriptor.import.buildItem === 'function') {
                behavior.import = descriptor.import;
            }
        }
        const acquisition = {};
        const rawAcquisition = isPlainObject(descriptor.acquisition) ? descriptor.acquisition : {};
        ['user', 'search', 'series', 'quick'].forEach(mode => {
            if (allowedModes.has(mode) && isPlainObject(rawAcquisition[mode])
                && validAcquisitionHooks(mode, rawAcquisition[mode])) {
                acquisition[mode] = rawAcquisition[mode];
            }
        });
        behavior.acquisition = acquisition;
        const rawSlots = isPlainObject(descriptor.slots)
            ? descriptor.slots
            : (isPlainObject(descriptor.uiSlots) ? descriptor.uiSlots : {});
        const declaredSlots = legacyContract
            ? new Set(Object.keys(rawSlots).map(text).filter(Boolean))
            : new Set((backend.uiSlots || [])
                .filter(target => publishedSlotTargets && publishedSlotTargets.has(target)));
        behavior.slots = {};
        Object.keys(rawSlots).forEach(target => {
            const requiredMode = SLOT_MODE[target];
            if (!declaredSlots.has(target) || (requiredMode && !allowedModes.has(requiredMode))) return;
            behavior.slots[target] = rawSlots[target];
        });
        behavior.filters = declaredContributionMap(descriptor.filters, backend.filters, legacyContract);
        behavior.settings = declaredContributionMap(descriptor.settings, backend.settings, legacyContract);
        return guardValue(behavior, activation, new Map());
    }

    function processInvocationContext(backend, activation, moduleScope) {
        const active = () => moduleScope.valid && isContextActive(activation);
        return Object.freeze({
            type: backend.type,
            signal: moduleScope.controller.signal,
            isActive() { return active(); },
            assertActive() {
                if (!active()) throw staleQueueTypeError();
            }
        });
    }

    function effectiveDescriptor(backend, behavior) {
        if (!backend.legacyContract) return backend;
        const modes = [];
        if (behavior.import) modes.push('single-import');
        ['user', 'search', 'series', 'quick'].forEach(mode => {
            if (behavior.acquisition && behavior.acquisition[mode]) modes.push(mode);
        });
        return Object.freeze(Object.assign({}, backend, {
            acquisitionModes: Object.freeze(modes)
        }));
    }

    function publishedTypeSlotTargets(manifest, backend) {
        return new Set(manifest.uiSlots.filter(slot => slot.moduleUrl === backend.moduleUrl
                && slot.ownerPluginId === backend.ownerPluginId
                && slot.packageId === backend.packageId
                && slot.pluginGeneration === backend.pluginGeneration
                && slot.publicationId === backend.publicationId)
            .map(slot => slot.target));
    }

    function validAcquisitionHooks(mode, value) {
        const required = {
            user: [
                'parseInput', 'fetchMeta', 'fetchIds', 'cardsEndpoint', 'queueId', 'cardId',
                'render', 'buildQueueMeta', 'buildQueueMetaFromId'
            ],
            search: ['buildRequest', 'buildRangeRequest', 'queueId', 'render', 'buildQueueMeta'],
            series: [
                'apiPath', 'parseUrl', 'typeLabel', 'queueId', 'cardId',
                'render', 'buildQueueMeta'
            ],
            quick: ['queueId', 'gridCardId', 'innerCardHtml', 'render', 'buildQueueMeta']
        }[mode] || [];
        return required.every(name => typeof value[name] === 'function');
    }

    function declaredContributionMap(value, declaredKeys, acceptLegacyKeys) {
        const out = {};
        if (!isPlainObject(value)) return out;
        const keys = acceptLegacyKeys ? Object.keys(value) : (declaredKeys || []);
        keys.forEach(key => {
            if (Object.prototype.hasOwnProperty.call(value, key)) out[key] = value[key];
        });
        return out;
    }

    function activationContext(load, activation, disposers, moduleScope) {
        const backend = load.descriptor;
        const active = () => moduleScope.valid && isContextActive(activation);
        return Object.freeze({
            type: backend.type,
            manifest: backend,
            signal: moduleScope.controller.signal,
            isActive() { return active(); },
            assertActive() {
                if (!active()) throw staleQueueTypeError();
            },
            onCleanup(callback) {
                registerScopedCleanup(active, disposers, callback,
                    'queue type activation is stale', '[queueTypes] 过期作品类型清理失败：');
            }
        });
    }

    function registerModule(initializer) {
        const script = document.currentScript;
        const token = script && script.dataset ? text(script.dataset.queueTypeToken) : '';
        const load = token ? pendingLoads.get(token) : null;
        if (!load || load.kind !== 'queue-type' || load.script !== script
            || load.initializer || typeof initializer !== 'function') {
            return false;
        }
        load.initializer = initializer;
        return true;
    }

    // 1.0 前端模块兼容入口。仅后端明确标记为旧构造器的当前 script 可登记；类型与 owner
    // 由 publication 盖章，模块自报字段只用于一致性校验，随后移除并进入同一 sanitizer。
    function register(type, descriptor) {
        const script = document.currentScript;
        const token = script && script.dataset ? text(script.dataset.queueTypeToken) : '';
        const load = token ? pendingLoads.get(token) : null;
        const normalizedType = text(type);
        if (!load || load.kind !== 'queue-type' || load.script !== script || load.initializer
            || !load.descriptor.legacyContract || normalizedType !== load.descriptor.type
            || !isPlainObject(descriptor)) {
            return false;
        }
        const reportedType = text(descriptor.type);
        const reportedOwner = text(descriptor.ownerPluginId || descriptor.pluginId);
        if ((reportedType && reportedType !== load.descriptor.type)
            || (reportedOwner && reportedOwner !== load.descriptor.ownerPluginId)) {
            return false;
        }
        const normalized = Object.assign({}, descriptor);
        [
            'type', 'pluginId', 'ownerPluginId', 'packageId', 'pluginGeneration',
            'publicationId', 'moduleUrl', 'acquisitionModes'
        ].forEach(name => { delete normalized[name]; });
        load.initializer = function () {
            return {descriptor: normalized};
        };
        return true;
    }

    function registerUiModule(initializer) {
        const script = document.currentScript;
        const token = script && script.dataset ? text(script.dataset.downloadUiToken) : '';
        const load = token ? pendingLoads.get(token) : null;
        if (!load || load.kind !== 'ui-slot' || load.script !== script
            || load.initializer || typeof initializer !== 'function') {
            return false;
        }
        load.initializer = initializer;
        return true;
    }

    function createScopedModule(activation, publicationDisposers, cleanupLabel) {
        const scope = {valid: true, controller: new AbortController()};
        const moduleDisposers = [];
        let disposed = false;
        const abortModule = () => {
            try { scope.controller.abort(); } catch (e) { /* best effort */ }
        };
        activation.controller.signal.addEventListener('abort', abortModule, {once: true});
        const dispose = () => {
            if (disposed) return;
            disposed = true;
            scope.valid = false;
            activation.controller.signal.removeEventListener('abort', abortModule);
            abortModule();
            moduleDisposers.splice(0).reverse().forEach(callback => {
                try { callback(); } catch (e) { console.warn(cleanupLabel, e); }
            });
        };
        publicationDisposers.push(dispose);
        return {
            scope,
            disposers: moduleDisposers,
            dispose,
            fail() {
                const index = publicationDisposers.indexOf(dispose);
                if (index >= 0) publicationDisposers.splice(index, 1);
                dispose();
            }
        };
    }

    function registerScopedCleanup(active, disposers, callback, staleMessage, cleanupLabel) {
        if (typeof callback !== 'function') throw new Error('cleanup callback must be a function');
        if (active()) {
            disposers.push(callback);
            return;
        }
        try { callback(); } catch (e) { console.warn(cleanupLabel, e); }
        throw new Error(staleMessage);
    }

    function cleanupInitializerResult(result, label) {
        try {
            if (typeof result === 'function') result();
            else if (result && typeof result.dispose === 'function') result.dispose();
        } catch (e) {
            console.warn('[queueTypes] 过期 initializer 返回值清理失败：', label, e);
        }
    }

    function runInitializer(initializer, context, label) {
        let timeoutId;
        let acceptingResult = true;
        let abortListener = null;
        const execution = Promise.resolve().then(() => initializer(context));
        const observed = execution.then(result => {
            if (acceptingResult) return result;
            cleanupInitializerResult(result, label);
            return undefined;
        }, error => {
            if (acceptingResult) throw error;
            return undefined;
        });
        const timeout = new Promise((_resolve, reject) => {
            timeoutId = setTimeout(() => {
                acceptingResult = false;
                reject(new Error(label + ' initializer timed out'));
            }, INITIALIZER_TIMEOUT_MS);
        });
        const aborted = new Promise((_resolve, reject) => {
            abortListener = () => {
                acceptingResult = false;
                reject(new Error(label + ' initializer aborted'));
            };
            if (context.signal.aborted) abortListener();
            else context.signal.addEventListener('abort', abortListener, {once: true});
        });
        return Promise.race([
            observed,
            timeout,
            aborted
        ]).finally(() => {
            clearTimeout(timeoutId);
            if (abortListener) context.signal.removeEventListener('abort', abortListener);
        });
    }

    function loadTypeModule(descriptor, manifest, activation, candidate, disposers) {
        return new Promise(resolve => {
            const token = 'queue-type-' + (++loadSequence) + '-' + activation.sequence;
            const load = {
                kind: 'queue-type',
                token,
                epoch: manifest.epoch,
                revision: manifest.revision,
                descriptor,
                activation,
                initializer: null,
                script: null
            };
            const script = document.createElement('script');
            load.script = script;
            script.async = true;
            script.dataset.queueTypeToken = token;
            script.dataset.queueType = descriptor.type;
            script.dataset.manifestEpoch = manifest.epoch;
            script.dataset.ownerPluginId = descriptor.ownerPluginId;
            script.dataset.packageId = descriptor.packageId;
            script.dataset.pluginGeneration = String(descriptor.pluginGeneration);
            script.dataset.publicationId = String(descriptor.publicationId);
            script.src = appendCachebuster(descriptor.moduleUrl, load);
            pendingLoads.set(token, load);

            let settled = false;
            let loadTimer = setTimeout(() => {
                console.warn('[queueTypes] 作品类型行为模块加载超时：', descriptor.moduleUrl);
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
                console.warn('[queueTypes] 作品类型行为模块加载失败：', descriptor.moduleUrl);
                finish();
            };
            script.onload = async () => {
                if (loadTimer != null) clearTimeout(loadTimer);
                loadTimer = null;
                if (settled) return;
                if (!isCandidateCurrent(activation)) {
                    finish();
                    return;
                }
                if (typeof load.initializer !== 'function') {
                    console.warn('[queueTypes] 作品类型行为模块未登记 initializer：', descriptor.type, descriptor.moduleUrl);
                    finish();
                    return;
                }
                const module = createScopedModule(
                    activation, disposers, '[queueTypes] 作品类型模块清理失败：');
                try {
                    const result = await runInitializer(
                        load.initializer,
                        activationContext(load, activation, module.disposers, module.scope),
                        'queue type ' + descriptor.type);
                    if (!isCandidateCurrent(activation)) throw new Error('queue type activation is stale');
                    const moduleResult = isPlainObject(result) && Object.prototype.hasOwnProperty.call(result, 'descriptor')
                        ? result : {descriptor: result};
                    const behavior = sanitizeBehavior(
                        moduleResult.descriptor,
                        descriptor,
                        activation,
                        module.scope,
                        publishedTypeSlotTargets(manifest, descriptor));
                    const activeDescriptor = effectiveDescriptor(descriptor, behavior);
                    candidate.set(descriptor.type, Object.freeze({
                        descriptor: activeDescriptor,
                        behavior,
                        activation
                    }));
                    if (typeof moduleResult.dispose === 'function') module.disposers.push(moduleResult.dispose);
                } catch (e) {
                    candidate.delete(descriptor.type);
                    module.fail();
                    console.warn('[queueTypes] 作品类型行为模块初始化失败：', descriptor.type, e);
                }
                finish();
            };
            (document.head || document.documentElement).appendChild(script);
        });
    }

    function deactivate(snapshot) {
        if (!snapshot || snapshot === EMPTY) return;
        if (snapshot.activation) snapshot.activation.valid = false;
        try { snapshot.controller.abort(); } catch (e) { /* best effort */ }
        snapshot.disposers.splice(0).reverse().forEach(dispose => {
            try { dispose(); } catch (e) { console.warn('[queueTypes] 作品类型模块清理失败：', e); }
        });
    }

    function announceChange(snapshot, ready) {
        try {
            window.dispatchEvent(new CustomEvent('pixivbatch:queuetypeschanged', {
                detail: {
                    epoch: snapshot.epoch,
                    revision: snapshot.revision,
                    ready: ready !== false,
                    types: snapshot.orderedTypes.slice()
                }
            }));
        } catch (e) {
            // 旧环境缺少 CustomEvent 构造器不影响类型调用。
        }
    }

    function uiModules(manifest) {
        const modules = new Map();
        manifest.uiSlots.forEach(slot => {
            if (!slot.moduleUrl) return;
            const key = [
                slot.moduleUrl, slot.ownerPluginId, slot.packageId,
                slot.pluginGeneration, slot.publicationId
            ].join(':');
            if (!modules.has(key)) {
                modules.set(key, {
                    identity: key,
                    moduleUrl: slot.moduleUrl,
                    ownerPluginId: slot.ownerPluginId,
                    packageId: slot.packageId,
                    pluginGeneration: slot.pluginGeneration,
                    publicationId: slot.publicationId,
                    slots: []
                });
            }
            modules.get(key).slots.push(slot);
        });
        return Array.from(modules.values()).map(module => Object.freeze(Object.assign({}, module, {
            slots: Object.freeze(module.slots.slice())
        })));
    }

    function uiModuleContext(load, activation, module) {
        const descriptor = load.descriptor;
        const active = () => module.scope.valid && isContextActive(activation);
        return Object.freeze({
            epoch: load.epoch,
            revision: load.revision,
            owner: Object.freeze({
                pluginId: descriptor.ownerPluginId,
                packageId: descriptor.packageId,
                generation: descriptor.pluginGeneration,
                publicationId: descriptor.publicationId
            }),
            slots: descriptor.slots,
            signal: module.scope.controller.signal,
            isActive() { return active(); },
            assertActive() {
                if (!active()) throw new Error('download UI module activation is stale');
            },
            onCleanup(callback) {
                registerScopedCleanup(active, module.disposers, callback,
                    'download UI module activation is stale', '[queueTypes] 过期 UI 模块清理失败：');
            }
        });
    }

    function loadUiModule(descriptor, manifest, activation, disposers, activated) {
        return new Promise(resolve => {
            const token = 'download-ui-' + (++loadSequence) + '-' + activation.sequence;
            const load = {
                kind: 'ui-slot',
                token,
                epoch: manifest.epoch,
                revision: manifest.revision,
                descriptor,
                activation,
                initializer: null,
                script: null
            };
            const script = document.createElement('script');
            load.script = script;
            script.async = true;
            script.dataset.downloadUiToken = token;
            script.dataset.manifestEpoch = manifest.epoch;
            script.dataset.ownerPluginId = descriptor.ownerPluginId;
            script.dataset.packageId = descriptor.packageId;
            script.dataset.pluginGeneration = String(descriptor.pluginGeneration);
            script.dataset.publicationId = String(descriptor.publicationId);
            script.src = descriptor.moduleUrl + '?__download_ui=' + encodeURIComponent([
                manifest.epoch, manifest.revision, descriptor.pluginGeneration,
                descriptor.publicationId, token
            ].join('-'));
            pendingLoads.set(token, load);
            let settled = false;
            let loadTimer = setTimeout(() => {
                console.warn('[queueTypes] 下载页 UI 模块加载超时：', descriptor.moduleUrl);
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
                console.warn('[queueTypes] 下载页 UI 模块加载失败：', descriptor.moduleUrl);
                finish();
            };
            script.onload = async () => {
                if (loadTimer != null) clearTimeout(loadTimer);
                loadTimer = null;
                if (settled) return;
                if (!isCandidateCurrent(activation)) {
                    finish();
                    return;
                }
                if (typeof load.initializer !== 'function') {
                    console.warn('[queueTypes] 下载页 UI 模块未登记 initializer：', descriptor.moduleUrl);
                    finish();
                    return;
                }
                const module = createScopedModule(
                    activation, disposers, '[queueTypes] 下载页 UI 模块清理失败：');
                try {
                    const result = await runInitializer(
                        load.initializer,
                        uiModuleContext(load, activation, module),
                        'download UI ' + descriptor.moduleUrl);
                    if (!isCandidateCurrent(activation)) throw new Error('download UI module activation is stale');
                    if (typeof result === 'function') module.disposers.push(result);
                    else if (result && typeof result.dispose === 'function') module.disposers.push(result.dispose);
                    activated.add(descriptor.identity);
                } catch (e) {
                    activated.delete(descriptor.identity);
                    module.fail();
                    console.warn('[queueTypes] 下载页 UI 模块初始化失败：', descriptor.moduleUrl, e);
                }
                finish();
            };
            (document.head || document.documentElement).appendChild(script);
        });
    }

    async function install(manifest) {
        const previous = current;
        const sequence = ++activationSequence;
        const controller = new AbortController();
        const activation = {sequence, controller, valid: true, installed: false};
        const candidate = new Map();
        const uiActivations = new Set();
        const disposers = [];
        current = {
            epoch: manifest.epoch,
            revision: manifest.revision,
            identity: manifest.identity,
            manifest: manifest.manifest,
            orderedTypes: manifest.orderedTypes,
            activations: new Map(),
            uiActivations,
            tabs: manifest.tabs,
            uiSlots: manifest.uiSlots,
            controller,
            activation,
            disposers
        };
        activation.installed = true;
        deactivate(previous);
        if (slotsBootstrapped) clearRenderedSlots();
        announceChange(current, false);
        await Promise.all(manifest.orderedTypes.map(type =>
            loadTypeModule(manifest.manifest.get(type), manifest, activation, candidate, disposers)));
        if (!isCandidateCurrent(activation)) {
            activation.valid = false;
            try { controller.abort(); } catch (e) { /* best effort */ }
            disposers.slice().reverse().forEach(dispose => {
                try { dispose(); } catch (e) { /* stale cleanup */ }
            });
            return current;
        }
        current = {
            epoch: manifest.epoch,
            revision: manifest.revision,
            identity: manifest.identity,
            manifest: manifest.manifest,
            orderedTypes: manifest.orderedTypes,
            activations: candidate,
            uiActivations,
            tabs: manifest.tabs,
            uiSlots: manifest.uiSlots,
            controller,
            activation,
            disposers
        };
        const typeModuleUrls = new Set(manifest.orderedTypes.map(type => manifest.manifest.get(type).moduleUrl));
        const uiDescriptors = uiModules(manifest).filter(module => !typeModuleUrls.has(module.moduleUrl));
        await Promise.all(uiDescriptors.map(module =>
            loadUiModule(module, manifest, activation, disposers, uiActivations)));
        if (slotsBootstrapped && isCandidateCurrent(activation)) await renderSlots();
        if (isCandidateCurrent(activation)) announceChange(current, true);
        return current;
    }

    async function fetchData(usePrefetch) {
        if (usePrefetch && prefetchedData) {
            const data = prefetchedData;
            prefetchedData = null;
            return data;
        }
        if (usePrefetch && prefetchPromise) {
            const data = await prefetchPromise;
            prefetchedData = null;
            return data;
        }
        const response = await fetch(BASE + ENDPOINT, {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: {'Accept': 'application/json'}
        });
        if (!response.ok) throw new Error('download extension manifest HTTP ' + response.status);
        return response.json();
    }

    function needsRetry(manifest) {
        if (manifest.orderedTypes.some(type => !current.activations.has(type))) return true;
        const typeModuleUrls = new Set(manifest.orderedTypes.map(type => manifest.manifest.get(type).moduleUrl));
        return uiModules(manifest)
            .filter(module => !typeModuleUrls.has(module.moduleUrl))
            .some(module => !current.uiActivations.has(module.identity));
    }

    async function refresh(force, usePrefetch) {
        if (refreshPromise) {
            refreshQueued = true;
            refreshQueuedForce = refreshQueuedForce || !!force;
            return refreshPromise;
        }
        refreshPromise = (async () => {
            let first = true;
            let forceNext = !!force;
            do {
                refreshQueued = false;
                const manifest = normalizeManifest(await fetchData(first && !!usePrefetch));
                if (forceNext || manifest.identity !== current.identity || needsRetry(manifest)) {
                    await install(manifest);
                }
                forceNext = refreshQueuedForce;
                refreshQueuedForce = false;
                first = false;
            } while (refreshQueued);
            return current;
        })();
        try {
            return await refreshPromise;
        } catch (e) {
            console.warn('[queueTypes] 拉取下载页扩展点失败：', e);
            return current;
        } finally {
            refreshPromise = null;
            refreshQueued = false;
            refreshQueuedForce = false;
        }
    }

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
        const normalizedType = text(type) || text(raw.kind) || text(raw.workType) || 'unknown';
        const rawId = text(raw.workId != null ? raw.workId : raw.id);
        const fallback = {
            id: rawId,
            kind: normalizedType,
            rawTitle: text(raw.title) || null,
            source: text(ctx.source) || text(raw.source) || 'schedule',
            xRestrict: raw.xRestrict == null ? null : raw.xRestrict,
            isAi: raw.ai === true || raw.isAi === true
        };
        const behavior = get(normalizedType);
        if (!behavior || typeof behavior.scheduledQueueItem !== 'function') return fallback;
        try {
            const owned = behavior.scheduledQueueItem(raw, ctx);
            return isPlainObject(owned) ? Object.assign(fallback, owned) : fallback;
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

    async function prefetchExtensions() {
        if (prefetchedData) return prefetchedData;
        if (!prefetchPromise) {
            prefetchPromise = (async () => {
                try {
                    const data = await fetchData(false);
                    prefetchedData = data;
                    return data;
                } catch (e) {
                    console.warn('[queueTypes] 预取下载页扩展点失败：', e);
                    return null;
                } finally {
                    prefetchPromise = null;
                }
            })();
        }
        return prefetchPromise;
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
            addNamespace(out, seen, item && item.gallery && item.gallery.reasonNamespace);
        });
        return out;
    }

    function collectSlotFragments() {
        const byTarget = new Map();
        current.orderedTypes.forEach(type => {
            const behavior = get(type);
            const slots = behavior && behavior.slots ? behavior.slots : {};
            Object.keys(slots).forEach(target => {
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
        ++activationSequence;
        const previous = current;
        current = EMPTY;
        deactivate(previous);
        if (slotsBootstrapped) clearRenderedSlots();
        slotsBootstrapped = false;
    }

    return Object.freeze({
        register,
        registerModule,
        registerUiModule,
        bootstrap,
        refresh(force) { return refresh(!!force, false); },
        get,
        has,
        isEnabled,
        isTypeAvailable,
        resolveType,
        normalizeSelectedType,
        descriptor,
        manifestDescriptor,
        acquisition,
        acquisitionList,
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
        dispose,
        contractVersion: CONTRACT_VERSION
    });
})();
