'use strict';
(function (global) {
    global.PixivBatch = global.PixivBatch || {};
    const modules = global.PixivBatch.scheduleSourceRuntimeModules
        || (global.PixivBatch.scheduleSourceRuntimeModules = {});
    modules.runtime = Object.freeze({
        install(ctx) {
            const {
                ENDPOINT, MODULE_INITIALIZER_TIMEOUT_MS, SCRIPT_LOAD_TIMEOUT_MS, EMPTY,
                text, normalizedModuleUrl, ownerIdentity, normalizeManifest
            } = ctx;
            let current = EMPTY;
            let refreshPromise = null;
            let refreshQueued = false;
            let refreshQueuedForce = false;
            let activationSequence = 0;
            let loadSequence = 0;
            const pendingLoads = new Map();
            ctx.publish(current);
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
            'quickSourceNote', 'credentialContribution', 'validateCredential',
            'bindCredential', 'bindSavedCredential', 'revokeCredential',
            'credentialTaskPresentation', 'credentialPolicyGroups',
            'applyCredentialPolicyAction', 'dispose'
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
        ctx.publish(current);
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
        ctx.publish(current);
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


            function dispose() {
                ++activationSequence;
                const previous = current;
                current = EMPTY;
                ctx.publish(current);
                deactivate(previous);
            }

            Object.assign(ctx, {
                registerModule,
                refresh,
                activationIsCurrent(activation) { return activation === activationSequence; },
                dispose
            });
        }
    });
})(window);