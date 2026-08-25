'use strict';
(function (global) {
    global.PixivBatch = global.PixivBatch || {};
    const modules = global.PixivBatch.queueTypeRuntimeModules
        || (global.PixivBatch.queueTypeRuntimeModules = {});
    modules.runtime = Object.freeze({
        install(ctx) {
            const {
                CONTRACT_VERSION, ENDPOINT, INITIALIZER_TIMEOUT_MS, SCRIPT_LOAD_TIMEOUT_MS,
                SLOT_MODE, EMPTY, text, isPlainObject, normalizeManifest, hooks
            } = ctx;
            let current = EMPTY;
            let activationSequence = 0;
            let loadSequence = 0;
            let refreshPromise = null;
            let refreshQueued = false;
            let refreshQueuedForce = false;
            let prefetchedData = null;
            let prefetchPromise = null;
            const pendingLoads = new Map();
            ctx.publish(current);
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
        const declared = new Set(backend.acquisitionModes);
        const allowedModes = declared;
        const behavior = {};
        Object.keys(descriptor).forEach(key => {
            if (key === 'uiSlots') return;
            if (!['process', 'import', 'acquisition', 'contractVersion', 'slots', 'filters', 'settings']
                .includes(key)) {
                behavior[key] = descriptor[key];
            }
        });
        behavior.process = function (item) {
            const processContext = processInvocationContext(backend, activation, moduleScope, item);
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
                const contribution = Object.assign({}, descriptor.import);
                const dataSource = normalizeAcquisitionDataSource(contribution.dataSource, backend);
                if (dataSource) contribution.dataSource = dataSource;
                else delete contribution.dataSource;
                behavior.import = contribution;
            }
        }
        const acquisition = {};
        const rawAcquisition = isPlainObject(descriptor.acquisition) ? descriptor.acquisition : {};
        ['user', 'search', 'series', 'quick'].forEach(mode => {
            if (allowedModes.has(mode) && isPlainObject(rawAcquisition[mode])
                && validAcquisitionHooks(mode, rawAcquisition[mode])) {
                const contribution = Object.assign({}, rawAcquisition[mode]);
                const dataSource = normalizeAcquisitionDataSource(contribution.dataSource, backend);
                if (dataSource) contribution.dataSource = dataSource;
                else delete contribution.dataSource;
                if (mode === 'series') {
                    const browser = normalizeSeriesBrowser(contribution.browser);
                    if (browser) contribution.browser = browser;
                    else delete contribution.browser;
                }
                acquisition[mode] = contribution;
            }
        });
        behavior.acquisition = acquisition;
        const rawSlots = isPlainObject(descriptor.slots) ? descriptor.slots : {};
        const declaredSlots = new Set(publishedSlotTargets || []);
        behavior.slots = {};
        Object.keys(rawSlots).forEach(target => {
            const requiredMode = SLOT_MODE[target];
            if (!declaredSlots.has(target) || (requiredMode && !allowedModes.has(requiredMode))) return;
            behavior.slots[target] = rawSlots[target];
        });
        behavior.filters = declaredContributionMap(descriptor.filters, backend.filters);
        behavior.settings = declaredContributionMap(descriptor.settings, backend.settings);
        return guardValue(behavior, activation, new Map());
    }

    function processInvocationContext(backend, activation, moduleScope, item) {
        const active = () => moduleScope.valid && isContextActive(activation);
        const i18nNamespace = text(backend.i18nNamespace || backend.displayNamespace);
        return Object.freeze({
            type: backend.type,
            signal: moduleScope.controller.signal,
            isActive() { return active(); },
            assertActive() {
                if (!active()) throw staleQueueTypeError();
            },
            updateItem(patch) {
                if (!active()) throw staleQueueTypeError();
                if (!isPlainObject(patch)) throw new Error('queue item patch must be a plain object');
                const normalized = Object.assign(Object.create(null), patch);
                if (Object.prototype.hasOwnProperty.call(normalized, 'statusMessageKey')) {
                    const key = text(normalized.statusMessageKey);
                    if (!key) {
                        normalized.statusMessageKey = null;
                    } else {
                        if (!i18nNamespace || !key.startsWith(i18nNamespace + ':')) {
                            throw new Error('statusMessageKey must belong to the queue type i18n namespace');
                        }
                        normalized.statusMessageKey = key;
                    }
                }
                const queue = window.PixivBatch && window.PixivBatch.queue;
                if (!queue || typeof queue.commitQueueItemPatch !== 'function') {
                    throw new Error('queue item patch bridge is unavailable');
                }
                return queue.commitQueueItemPatch(item, normalized);
            }
        });
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
        if (mode === 'user') {
            const common = [
                'parseInput', 'fetchMeta', 'queueId', 'cardId', 'render', 'buildQueueMeta'
            ].every(name => typeof value[name] === 'function');
            const legacy = ['fetchIds', 'cardsEndpoint', 'buildQueueMetaFromId']
                .every(name => typeof value[name] === 'function');
            return common && (legacy || typeof value.fetchPage === 'function');
        }
        const required = {
            search: ['buildRequest', 'buildRangeRequest', 'queueId', 'render', 'buildQueueMeta'],
            series: [
                'apiPath', 'parseUrl', 'typeLabel', 'queueId', 'cardId',
                'render', 'buildQueueMeta'
            ],
            quick: ['queueId', 'gridCardId', 'innerCardHtml', 'render', 'buildQueueMeta']
        }[mode] || [];
        return required.every(name => typeof value[name] === 'function');
    }

    function normalizeAcquisitionDataSource(value, backend) {
        if (!isPlainObject(value)) return null;
        const id = text(value.id);
        if (!id || id.length > 64) return null;
        const rawOrder = Number(value.order);
        return Object.freeze({
            id,
            displayNamespace: text(value.displayNamespace || backend.displayNamespace),
            displayI18nKey: text(value.displayI18nKey || backend.displayI18nKey),
            order: Number.isFinite(rawOrder) ? rawOrder : backend.order
        });
    }

    function normalizeSeriesBrowser(value) {
        if (!isPlainObject(value)) return null;
        const required = ['buildPageRequest', 'readPage', 'itemId', 'itemLabel', 'select'];
        if (!required.every(name => typeof value[name] === 'function')) return null;
        const pageSize = Number(value.pageSize);
        const out = {
            initialCursor: text(value.initialCursor || '0'),
            pageSize: Number.isSafeInteger(pageSize) && pageSize > 0 ? pageSize : 24
        };
        required.concat(['title', 'loadingLabel', 'emptyLabel']).forEach(name => {
            if (typeof value[name] === 'function') out[name] = value[name];
        });
        return Object.freeze(out);
    }

    function declaredContributionMap(value, declaredKeys) {
        const out = {};
        if (!isPlainObject(value)) return out;
        (declaredKeys || []).forEach(key => {
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
                    candidate.set(descriptor.type, Object.freeze({
                        descriptor,
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
        const ownsType = type => {
            const behavior = hooks.get(type);
            const owner = behavior && behavior.owner;
            return !!owner
                && owner.ownerPluginId === descriptor.ownerPluginId
                && owner.packageId === descriptor.packageId
                && owner.pluginGeneration === descriptor.pluginGeneration
                && owner.publicationId === descriptor.publicationId;
        };
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
            supports(type, mode) {
                return active() && ownsType(type) && hooks.supports(type, mode);
            },
            dispatchQuickAction(action) {
                if (!active()) throw new Error('download UI module activation is stale');
                const actionId = text(action);
                if (!actionId) return false;
                const actionOwnerType = current.orderedTypes.find(type =>
                    Object.prototype.hasOwnProperty.call(hooks.quickActionsFor(type), actionId));
                if (!actionOwnerType || !ownsType(actionOwnerType)) return false;
                const quick = window.PixivBatch && window.PixivBatch.modes
                    && window.PixivBatch.modes.quick;
                if (!quick || typeof quick.quickLoad !== 'function') return false;
                return quick.quickLoad(actionId);
            },
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
            uiSlots: manifest.uiSlots,
            controller,
            activation,
            disposers
        };
        ctx.publish(current);
        activation.installed = true;
        deactivate(previous);
        if (hooks.slotsBootstrapped()) hooks.clearRenderedSlots();
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
            uiSlots: manifest.uiSlots,
            controller,
            activation,
            disposers
        };
        ctx.publish(current);
        const typeModuleUrls = new Set(manifest.orderedTypes.map(type => manifest.manifest.get(type).moduleUrl));
        const uiDescriptors = uiModules(manifest).filter(module => !typeModuleUrls.has(module.moduleUrl));
        await Promise.all(uiDescriptors.map(module =>
            loadUiModule(module, manifest, activation, disposers, uiActivations)));
        if (hooks.slotsBootstrapped() && isCandidateCurrent(activation)) await hooks.renderSlots();
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

            function dispose() {
                ++activationSequence;
                const previous = current;
                current = EMPTY;
                ctx.publish(current);
                deactivate(previous);
                if (hooks.slotsBootstrapped()) hooks.clearRenderedSlots();
            }

            Object.assign(ctx, {
                isActivationLive, staleQueueTypeError, registerModule, registerUiModule,
                refresh, prefetchExtensions, dispose
            });
        }
    });
})(window);
