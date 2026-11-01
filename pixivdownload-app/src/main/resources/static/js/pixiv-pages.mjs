/* 插件只接收纯值与可取消上下文，不接收编辑器对象。 */
import {validateWireValue} from '/sdk/values/value-codecs.mjs';
let pageRecoveryInstalled = false;
function ensurePageRecovery() {
    if (pageRecoveryInstalled || typeof window === 'undefined') return;
    pageRecoveryInstalled = true;
    window.addEventListener('pagehide', event => {
        // 初始化也可能被缓存；恢复监听独立于实例清理，每份文档只重载一次。
        if (event.persisted) window.addEventListener('pageshow', () => location.reload(), {once: true});
    }, {once: true});
}
const publications = new Map();
function watchPublication(token, url, withdrawn) {
    let entry = publications.get(token);
    if (!entry) {
        const abort = new AbortController();
        entry = {callbacks: new Set(), abort, pending: false};
        const check = async () => {
            if (entry.pending || abort.signal.aborted) return;
            entry.pending = true;
            try {
                const response = await fetch(url, {method: 'HEAD', cache: 'no-store',
                    signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10000)]), redirect: 'error'});
                if (!response.ok) throw new Error('PAGE_COMPONENT_UNAVAILABLE');
            } catch (_) {
                if (!abort.signal.aborted) for (const callback of [...entry.callbacks]) callback();
            } finally { entry.pending = false; }
        };
        entry.timer = setInterval(check, 2000);
        publications.set(token, entry);
    }
    entry.callbacks.add(withdrawn);
    return () => {
        entry.callbacks.delete(withdrawn);
        if (!entry.callbacks.size) {
            clearInterval(entry.timer); entry.abort.abort(); publications.delete(token);
        }
    };
}
export function pureValue(value) {
    const serialized = JSON.stringify(value, function (key, current) {
        const original = this[key];
        if (original === undefined || typeof original === 'function' || typeof original === 'symbol'
                || typeof original === 'bigint' || typeof original === 'number' && !Number.isFinite(original)
                || original && typeof original === 'object' && !Array.isArray(original)
                    && ![Object.prototype, null].includes(Object.getPrototypeOf(original))) throw new Error('PAGE_VALUE_INVALID');
        return current;
    });
    if (serialized === undefined || serialized.length > 1024 * 1024) throw new Error('PAGE_VALUE_INVALID');
    return JSON.parse(serialized);
}

/** 值校验与 SDK JSON Schema 子集一致；配置和业务载荷不执行自定义代码。 */
export function portValue(port, input) {
    if (!port) throw new Error('PAGE_PORT_INVALID');
    const value = pureValue(input), root = JSON.parse(port.schema);
    let nodes = 0;
    function check(schema, data, depth) {
        if (++nodes > 16384 || depth > 32) throw new Error('PAGE_VALUE_INVALID');
        if (schema.anyOf) { if (data === null) return; return check(schema.anyOf[0], data, depth + 1); }
        if (schema.$ref) {
            if (!schema.$ref.startsWith('#/$defs/')) throw new Error('PAGE_PORT_INVALID');
            return check(root.$defs[schema.$ref.slice(8)], data, depth + 1);
        }
        let valid = true;
        switch (schema.type) {
            case 'boolean': valid = typeof data === 'boolean'; break;
            case 'integer': valid = Number.isSafeInteger(data) && data >= schema.minimum && data <= schema.maximum; break;
            case 'string': valid = typeof data === 'string' && !/[\uD800-\uDFFF]/u.test(data)
                && [...data].length >= (schema.minLength ?? 0) && [...data].length <= (schema.maxLength ?? Infinity)
                && (!schema.enum || schema.enum.includes(data));
                if (valid && schema['x-encoding']) {
                    try { validateWireValue(schema, data); } catch (_) { valid = false; }
                }
                break;
            case 'array':
                valid = Array.isArray(data) && data.length >= (schema.minItems ?? 0) && data.length <= (schema.maxItems ?? Infinity);
                if (valid) data.forEach(item => check(schema.items, item, depth + 1));
                break;
            case 'object':
                valid = data !== null && typeof data === 'object' && !Array.isArray(data);
                if (valid) {
                    valid = (schema.required || []).every(key => Object.hasOwn(data, key));
                    for (const [key, item] of Object.entries(data)) {
                        const property = schema.properties ? schema.properties[key] : schema.additionalProperties;
                        if (!property || typeof property !== 'object') throw new Error('PAGE_VALUE_INVALID');
                        check(property, item, depth + 1);
                    }
                }
                break;
            default: valid = false;
        }
        if (!valid) throw new Error('PAGE_VALUE_INVALID');
    }
    check(root, value, 0);
    if (new TextEncoder().encode(JSON.stringify(value)).length > 1024 * 1024) throw new Error('PAGE_VALUE_INVALID');
    return value;
}

/** 表单 owner 持有目标，组件只取得自己实例的有界挑战通道。 */
export function createOperationTarget(operation, onChange = () => {}, prepareRequest) {
    ensurePageRecovery();
    const participants = new Map();
    let revision = '', token = '', challenges = {}, proofs = {}, closed = false, prepared = false, preparation;
    const ready = () => !closed && prepared && Object.keys(challenges).every(policy => Object.hasOwn(proofs, policy));
    const changed = () => onChange({token, revision, challenges: pureValue(challenges), ready: ready(), preparing: !!preparation});
    const notify = () => {
        for (const entry of participants.values()) {
            const value = token && Object.hasOwn(challenges, entry.policy)
                ? {id: token, revision, value: portValue({schema: entry.protocol.stateSchema}, challenges[entry.policy])} : null;
            entry.state = value;
            for (const listener of entry.listeners) {
                try { listener(value === null ? null : pureValue(value)); }
                catch (_) { console.error('PAGE_INTERACTION_LISTENER_FAILED'); }
            }
        }
        changed();
    };
    const reset = () => {
        preparation?.controller.abort(); preparation = undefined;
        prepared = false; token = ''; challenges = {}; proofs = {}; notify();
    };
    const target = {
        operation,
        configure(next) { if (closed) throw new Error('PAGE_INTERACTION_CLOSED'); if (revision !== next) { revision = next; reset(); } },
        challenge(next) {
            if (next.revision !== revision) { reset(); throw new Error('PAGE_REVISION_CHANGED'); }
            if (closed || typeof next.token !== 'string' || next.token.length > 128
                    || !next.challenges || Array.isArray(next.challenges) || typeof next.challenges !== 'object'
                    || Object.keys(next.challenges).length > 16
                    || (!next.token && Object.keys(next.challenges).length)) throw new Error('PAGE_INTERACTION_INVALID');
            const checked = pureValue(next.challenges);
            for (const [policy, value] of Object.entries(checked)) {
                if (!/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/.test(policy) || policy.length > 96 || typeof value !== 'string'
                        || new TextEncoder().encode(value).length > 16384) throw new Error('PAGE_INTERACTION_INVALID');
            }
            if (new TextEncoder().encode(JSON.stringify(checked)).length > 65536) throw new Error('PAGE_INTERACTION_INVALID');
            token = next.token; challenges = checked; proofs = {}; prepared = true; notify();
        },
        prepare() {
            if (closed) return Promise.reject(new Error('PAGE_INTERACTION_CLOSED'));
            if (prepared) return Promise.resolve();
            if (preparation) return preparation.promise;
            if (typeof prepareRequest !== 'function') return Promise.reject(new Error('OPERATION_PREPARATION_UNAVAILABLE'));
            const current = {controller: new AbortController()};
            preparation = current;
            current.promise = Promise.resolve().then(() => prepareRequest(current.controller.signal)).then(value => {
                if (closed || preparation !== current || current.controller.signal.aborted)
                    throw new DOMException('Preparation cancelled', 'AbortError');
                target.challenge(value);
            }).finally(() => {
                if (preparation === current) { preparation = undefined; changed(); }
            });
            changed();
            return current.promise;
        },
        ready,
        request() {
            if (!ready()) throw new Error('OPERATION_VERIFICATION_REQUIRED');
            return {token, proofs: pureValue(proofs)};
        },
        reset,
        connect({instanceId, policy, protocol, operation: declared}, signal) {
            if (closed || declared !== operation || !instanceId || participants.has(instanceId) || participants.size >= 16)
                throw new Error('PAGE_INTERACTION_INVALID');
            if ([...participants.values()].some(entry => entry.policy === policy)) throw new Error('PAGE_INTERACTION_DUPLICATE');
            const entry = {policy, protocol: pureValue(protocol), listeners: new Set(), state: null};
            participants.set(instanceId, entry);
            let disposed = false;
            const dispose = () => {
                if (disposed) return;
                disposed = true; participants.delete(instanceId); entry.listeners.clear();
                signal?.removeEventListener('abort', dispose); reset();
            };
            entry.dispose = dispose;
            signal?.addEventListener('abort', dispose, {once: true});
            if (signal?.aborted) dispose(); else notify();
            return Object.freeze({
                prepare() {
                    if (disposed || closed) return Promise.reject(new Error('PAGE_INTERACTION_CLOSED'));
                    return target.prepare();
                },
                invalidate(id) {
                    if (disposed || closed || !token || id !== token) return false;
                    reset(); return true;
                },
                onState(listener) {
                    if (disposed || typeof listener !== 'function' || entry.listeners.size >= 16) throw new Error('PAGE_INTERACTION_INVALID');
                    entry.listeners.add(listener); listener(entry.state === null ? null : pureValue(entry.state));
                    return () => entry.listeners.delete(listener);
                },
                respond(id, value) {
                    if (disposed || closed || !token || id !== token || entry.state?.revision !== revision
                            || !Object.hasOwn(challenges, policy) || Object.hasOwn(proofs, policy)) return false;
                    const checked = portValue({schema: protocol.responseSchema}, value);
                    if (typeof checked !== 'string' || new TextEncoder().encode(checked).length > 16384) throw new Error('PAGE_INTERACTION_INVALID');
                    const next = {...proofs, [policy]: checked};
                    if (new TextEncoder().encode(JSON.stringify(next)).length > 65536) throw new Error('PAGE_INTERACTION_INVALID');
                    proofs = next; changed(); return true;
                },
                destroy: dispose
            });
        },
        destroy() {
            if (closed) return; closed = true; reset();
            for (const entry of [...participants.values()]) entry.dispose();
            participants.clear();
        }
    };
    return Object.freeze(target);
}

/** 串行转发保留中间组件的输出；队列仅属于当前页面实例。 */
export function connectionDispatcher(connections, receive, onError = () => {}) {
    if (connections.length > 256) throw new Error('PAGE_CONNECTION_INVALID');
    const edges = new Map();
    for (const link of connections) {
        if (!edges.has(link.from)) edges.set(link.from, new Set());
        edges.get(link.from).add(link.to);
    }
    for (const start of edges.keys()) {
        const pending = [...edges.get(start)], visited = new Set();
        while (pending.length) {
            const next = pending.pop();
            if (next === start) throw new Error('PAGE_CONNECTION_CYCLE');
            if (!visited.has(next)) { visited.add(next); pending.push(...(edges.get(next) || [])); }
        }
    }
    const queue = [];
    let active = false, ready = false, closed = false, bytes = 0;
    const failure = code => { try { onError(new Error(code)); } catch (_) {} };
    const drain = async () => {
        if (active || !ready || closed) return;
        active = true;
        let delivered = 0;
        try {
            while (queue.length && !closed) {
                const item = queue.shift(); bytes -= item.bytes;
                if (++delivered > 4096) { queue.length = 0; bytes = 0; failure('PAGE_CONNECTION_BUDGET'); break; }
                try { await receive(item.link.to, item.link.input, item.value); }
                catch (_) { if (!closed) failure('PAGE_CONNECTION_FAILED'); }
            }
        } finally { active = false; }
    };
    return {
        emit(from, output, value) {
            if (closed) return;
            const copy = pureValue(value), size = new TextEncoder().encode(JSON.stringify(copy)).length;
            const links = connections.filter(link => link.from === from && link.output === output);
            if (queue.length + links.length > 256 || bytes + size * links.length > 16 * 1024 * 1024)
                throw new Error('PAGE_CONNECTION_BUDGET');
            for (const link of links) { queue.push({link, value: pureValue(copy), bytes: size}); bytes += size; }
            void drain();
        },
        start() { ready = true; void drain(); },
        destroy() { closed = true; queue.length = 0; bytes = 0; }
    };
}

export async function mountComponent(root, definition, configuration, options = {}) {
    const abort = new AbortController();
    const cleanups = [];
    const inputs = new Map();
    let mounted;
    let destroyed = false;
    let disposal;
    let cleanupFailed = false;
    const reportCleanupFailure = () => {
        if (cleanupFailed) return;
        cleanupFailed = true;
        const error = new Error('PAGE_COMPONENT_CLEANUP_FAILED');
        console.error(error.message);
        try { options.onError?.(error); }
        catch (_) { console.error('PAGE_COMPONENT_ERROR_HANDLER_FAILED'); }
    };
    let props = pureValue(configuration);
    const mode = options.mode || 'display';
    const url = new URL(options.properties ? definition.propertiesModuleUrl : definition.moduleUrl, location.origin);
    if (url.origin !== location.origin || !url.pathname.startsWith('/')) throw new Error('PAGE_MODULE_INVALID');
    url.searchParams.set('pagePublication', definition.publication);
    const assertActive = () => { if (destroyed) throw new DOMException('Page component disposed', 'AbortError'); };
    const onCleanup = fn => {
        if (typeof fn !== 'function') throw new TypeError('cleanup');
        if (destroyed) Promise.resolve().then(fn).catch(reportCleanupFailure);
        else cleanups.push(fn);
    };
    const dispose = () => {
        if (destroyed) return disposal;
        destroyed = true;
        abort.abort();
        disposal = (async () => {
        for (const fn of cleanups.splice(0).reverse()) {
            try { await fn(); } catch (_) { reportCleanupFailure(); }
        }
        try { await mounted?.destroy?.(); } catch (_) { reportCleanupFailure(); }
        finally { root.replaceChildren(); inputs.clear(); }
        })();
        return disposal;
    };
    const fetchBound = async (input, init = {}) => {
        assertActive();
        if (mode !== 'display') throw new Error('PAGE_PREVIEW_OPERATION_DENIED');
        const request = input instanceof Request ? new Request(input, init) : new Request(new URL(input, location.origin), init);
        if (new URL(request.url).origin !== location.origin) throw new Error('PAGE_ORIGIN_INVALID');
        const headers = new Headers(request.headers);
        headers.set('X-Pixiv-Page-Publication', definition.publication);
        const response = await fetch(request, {headers, credentials: 'same-origin', redirect: 'error',
            signal: AbortSignal.any([abort.signal, request.signal])});
        assertActive();
        if (response.status === 409 && (await response.clone().json().catch(() => ({}))).code === 'PAGE_PUBLICATION_STALE') {
            await dispose(); options.onUnavailable?.();
            throw new Error('PAGE_COMPONENT_UNAVAILABLE');
        }
        return response;
    };
    const context = Object.freeze({
        instanceId: options.instanceId, mode, signal: abort.signal,
        get properties() { return pureValue(props); },
        t: options.t || ((key) => key), fetch: fetchBound, onCleanup,
        interaction: options.interaction,
        async importModule(path) {
            assertActive();
            const dependency = new URL(path, url);
            if (dependency.origin !== location.origin) throw new Error('PAGE_ORIGIN_INVALID');
            dependency.searchParams.set('pagePublication', definition.publication);
            const response = await fetch(dependency, {method: 'HEAD', cache: 'no-store', signal: abort.signal, redirect: 'error'});
            if (!response.ok) throw new Error('PAGE_COMPONENT_UNAVAILABLE');
            const module = await import(dependency.href);
            assertActive(); return module;
        },
        on: (target, event, handler) => {
            assertActive();
            target.addEventListener(event, handler, {signal: abort.signal});
        },
        interval: (handler, milliseconds) => {
            if (!Number.isFinite(milliseconds) || milliseconds > 2147483647) throw new Error('PAGE_INTERVAL_INVALID');
            assertActive(); const timer = setInterval(() => { if (!destroyed) handler(); }, Math.max(100, milliseconds));
            onCleanup(() => clearInterval(timer));
        },
        emit: (port, value) => {
            assertActive();
            const output = definition.outputs.find(item => item.name === port);
            const checked = portValue(output, value);
            options.emit?.(port, checked);
        },
        onInput: (port, handler) => {
            assertActive();
            if (!definition.inputs.some(item => item.name === port) || typeof handler !== 'function') throw new Error('PAGE_PORT_INVALID');
            inputs.set(port, handler); onCleanup(() => inputs.delete(port));
        },
        change: value => { assertActive(); options.onChange?.(pureValue(value)); }
    });
    onCleanup(() => options.interaction?.destroy());
    options.signal?.addEventListener('abort', dispose, {once: true});
    onCleanup(() => options.signal?.removeEventListener('abort', dispose));
    if (options.signal?.aborted) await dispose();
    try {
        const module = await import(url.href);
        assertActive();
        const admission = await fetch(url, {method: 'HEAD', cache: 'no-store', signal: abort.signal, redirect: 'error'});
        if (!admission.ok) throw new Error('PAGE_COMPONENT_UNAVAILABLE');
        const factory = options.properties ? module.mountProperties : module.mount;
        if (typeof factory !== 'function') throw new Error('PAGE_MODULE_INVALID');
        const pending = await factory(root, context);
        if (destroyed) {
            try { await pending?.destroy?.(); } catch (_) { reportCleanupFailure(); }
            throw new DOMException('Disposed', 'AbortError');
        }
        mounted = pending;
        // 服务端立即关闭准入；同一 publication 共用轮询以释放闲置实例。
        onCleanup(watchPublication(definition.publication, url, async () => {
            await dispose(); options.onUnavailable?.();
        }));
        if (options.signal?.aborted) await dispose();
        return {
            async update(next) { assertActive(); props = pureValue(next); await mounted?.update?.(pureValue(props)); assertActive(); },
            input(port, value) {
                assertActive();
                const checked = portValue(definition.inputs.find(item => item.name === port), value);
                return inputs.get(port)?.(checked);
            },
            destroy: dispose
        };
    } catch (error) { await dispose(); throw error; }
}

export async function renderPage(root, view, options = {}) {
    const abort = new AbortController();
    const instances = new Map();
    const cleanups = [];
    const namespaces = new Map();
    let disposed = false;
    let closing;
    const dispose = () => {
        if (closing) return closing;
        disposed = true;
        closing = Promise.resolve().then(async () => {
            abort.abort();
            await Promise.allSettled([...instances.values()].map(instance => instance.destroy()));
            for (const cleanup of cleanups.reverse()) cleanup();
            instances.clear(); namespaces.clear(); root.replaceChildren();
        });
        return closing;
    };
    options.signal?.addEventListener('abort', dispose, {once: true});
    cleanups.push(() => options.signal?.removeEventListener('abort', dispose));
    if (options.signal?.aborted) await dispose();
    const forwarding = connectionDispatcher(view.connections || [],
        (to, port, value) => instances.get(to)?.input(port, value), options.onError);
    abort.signal.addEventListener('abort', () => forwarding.destroy(), {once: true});
    cleanups.push(() => forwarding.destroy());
    const mounts = [];
    const ids = new Map();
    function build(node) {
        const document = root.ownerDocument;
        const tag = node.type === 'page-text' ? 'p' : node.type === 'page-link' ? 'a'
            : node.type === 'page-box' ? node.role : 'div';
        let element = document.createElement(tag);
        if (node.type === 'page-text' || node.type === 'page-link') element.textContent = node.content;
        if (node.type === 'page-link') {
            element.href = node.href;
            if (new URL(node.href, location.origin).origin !== location.origin) {
                element.target = '_blank'; element.rel = 'noopener noreferrer';
            }
            if (options.mode !== 'display') element.addEventListener('click', event => event.preventDefault(), {signal: abort.signal});
        }
        if (node.type === 'page-region') {
            const region = options.regions?.[node.region];
            if (!region) {
                if (options.mode === 'display') throw new Error('PAGE_REGION_UNAVAILABLE');
                element.hidden = node.previewHidden;
                if (!node.container) {
                    if (view.previewModuleUrl) mounts.push(async () => {
                        const instance = await mountComponent(element,
                            {moduleUrl: view.previewModuleUrl, publication: view.publication, inputs: [], outputs: []},
                            {region: node.region}, {...options, signal: abort.signal, instanceId: node.region,
                                t: (key, fallback) => options.pageT?.(key, fallback) || fallback || key});
                        if (disposed) await instance.destroy(); else instances.set('region:' + node.region, instance);
                    });
                    else element.textContent = options.pageT?.(node.labelKey) || node.region;
                }
            } else {
                const marker = document.createComment('page-region');
                region.before(marker);
                const originalStyle = region.style.cssText;
                const originalChildren = node.container ? [...region.childNodes] : null;
                cleanups.push(() => {
                    region.style.cssText = originalStyle;
                    if (originalChildren) region.replaceChildren(...originalChildren);
                    marker.replaceWith(region);
                });
                element = region;
            }
        }
        if (node.id) ids.set(node.id, element);
        for (const [property, value] of Object.entries(node.style || {})) element.style.setProperty(property, value);
        if (node.type === 'page-component') {
            element.dataset.pageComponent = node.instanceId;
            if (node.unavailable) element.textContent = options.t('unavailable');
            else mounts.push(async () => {
                if (options.mode === 'display' && node.target && !options.targets?.[node.target])
                    throw new Error('PAGE_TARGET_UNAVAILABLE');
                let client = namespaces.get(node.namespace);
                if (!client && window.PixivI18n) {
                    client = await window.PixivI18n.create({namespaces: [node.namespace]});
                    if (disposed) { client.dispose(); throw new DOMException('Disposed', 'AbortError'); }
                    namespaces.set(node.namespace, client); cleanups.push(() => client.dispose());
                }
                const instance = await mountComponent(element, node, node.configuration, {
                    ...options, signal: abort.signal, instanceId: node.instanceId,
                    interaction: options.mode === 'display' && node.target
                        ? options.targets?.[node.target]?.connect({...node, protocol: node.interaction}, abort.signal) : undefined,
                    t: (key, fallback) => client?.t(node.namespace + ':' + key, fallback || key) || fallback || key,
                    emit: (port, value) => forwarding.emit(node.instanceId, port, value)
                });
                if (disposed) await instance.destroy(); else instances.set(node.instanceId, instance);
            });
        }
        const children = (node.children || []).map(build);
        if (node.type === 'wrapper' || node.type === 'page-box' || node.container) element.replaceChildren(...children);
        return element;
    }
    try {
        if (disposed) throw new DOMException('Disposed', 'AbortError');
        for (const stylesheet of view.stylesheets || []) {
            const url = new URL(stylesheet, location.origin); url.searchParams.set('pagePublication', view.publication);
            const link = root.ownerDocument.createElement('link'); link.rel = 'stylesheet'; link.href = url.href;
            root.ownerDocument.head.append(link); cleanups.push(() => link.remove());
        }
        root.replaceChildren(build(view.body));
        const applyStyles = () => {
            for (const rule of view.styles || []) {
                if (rule.media && !root.ownerDocument.defaultView.matchMedia(rule.media).matches) continue;
                for (const id of rule.ids) for (const [property, value] of Object.entries(rule.style)) ids.get(id)?.style.setProperty(property, value);
            }
        };
        // 响应断点变化时只重置生成节点的样式，保留组件实例与业务区域。
        const base = new Map([...ids.values()].map(element => [element, element.style.cssText]));
        const refresh = () => { for (const [element, css] of base) element.style.cssText = css; applyStyles(); };
        for (const query of new Set((view.styles || []).map(rule => rule.media).filter(Boolean))) {
            const media = root.ownerDocument.defaultView.matchMedia(query); media.addEventListener('change', refresh, {signal: abort.signal});
        }
        applyStyles();
        for (const mount of mounts) {
            if (disposed) throw new DOMException('Disposed', 'AbortError');
            await mount();
        }
        forwarding.start();
        return {destroy: dispose};
    } catch (error) { await dispose(); throw error; }
}

/** 页面 owner 提供受控区域及默认回退；共享渲染器不认识具体页面身份。 */
export async function attachPage({pageId, root, regions, targets = {}, title, t, onMounted, onFallback}) {
    ensurePageRecovery();
    const revision = document.querySelector('meta[name="pixiv-page-revision"]')?.content || 'unavailable';
    for (const target of Object.values(targets)) target.configure(revision);
    let mounted, timer, closed = false, unavailable = false;
    const controller = new AbortController();
    const dispose = async () => {
        if (closed) return;
        closed = true; controller.abort(); clearTimeout(timer);
        for (const target of Object.values(targets)) target.reset();
        await mounted?.destroy(); root.replaceChildren();
    };
    const withdraw = async () => {
        if (unavailable) return;
        unavailable = true;
        for (const target of Object.values(targets)) target.reset();
        await mounted?.destroy(); mounted = undefined;
        root.replaceChildren(); await onFallback?.(false);
    };
    const reload = async () => {
        let deadline;
        try {
            await Promise.race([dispose(), new Promise(resolve => { deadline = setTimeout(resolve, 2000); })]);
        } finally { clearTimeout(deadline); location.reload(); }
    };
    window.addEventListener('pagehide', dispose, {once: true, signal: controller.signal});
    const endpoint = '/api/web/page-publications/' + encodeURIComponent(pageId) + '?revision=' + encodeURIComponent(revision);
    const request = method => fetch(endpoint, {method, cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)])});
    const changed = response => response.status === 409 || response.headers.get('X-Pixiv-Page-Revision') !== revision;
    try {
        const response = await request('GET');
        if (closed) return;
        if ((response.ok || response.status === 404 || response.status === 409) && changed(response)) {
            if (response.status !== 404 || response.headers.has('X-Pixiv-Page-Revision')) return reload();
        }
        if (response.ok) {
            const view = await response.json();
            if (closed) return;
            title(view.title || '');
            const result = await renderPage(root, view, {mode: 'display', regions, targets, t,
                signal: controller.signal, onUnavailable: withdraw});
            if (closed || unavailable) await result.destroy();
            else { mounted = result; await onMounted?.(view); }
        } else if (response.status === 404 && response.headers.get('X-Pixiv-Page-Revision') === revision) {
            await onFallback?.(true);
        } else await withdraw();
    } catch (_) { if (!closed) await withdraw(); }
    const verifySource = async () => {
        if (closed) return;
        try {
            const response = await request('HEAD');
            if (closed) return;
            if (response.status === 409 || (response.ok || response.status === 404) && response.headers.has('X-Pixiv-Page-Revision') && changed(response)) return reload();
            if (response.ok || response.status === 404 && response.headers.get('X-Pixiv-Page-Revision') === revision) {
                if (unavailable) return reload();
            } else await withdraw();
        } catch (_) { if (!closed) await withdraw(); }
        if (!closed) timer = setTimeout(verifySource, 2000);
    };
    if (!closed) timer = setTimeout(verifySource, 2000);
    return {destroy: dispose};
}
