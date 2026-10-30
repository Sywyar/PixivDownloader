/* 插件只接收纯值与可取消上下文，不接收编辑器对象。 */
import {validateWireValue} from '/sdk/values/value-codecs.mjs';
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
                const response = await fetch(url, {method: 'HEAD', cache: 'no-store', signal: abort.signal, redirect: 'error'});
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
    let props = pureValue(configuration);
    const mode = options.mode || 'display';
    const url = new URL(options.properties ? definition.propertiesModuleUrl : definition.moduleUrl, location.origin);
    if (url.origin !== location.origin || !url.pathname.startsWith('/')) throw new Error('PAGE_MODULE_INVALID');
    url.searchParams.set('pagePublication', definition.publication);
    const assertActive = () => { if (destroyed) throw new DOMException('Page component disposed', 'AbortError'); };
    const onCleanup = fn => {
        if (typeof fn !== 'function') throw new TypeError('cleanup');
        if (destroyed) fn(); else cleanups.push(fn);
    };
    const dispose = () => {
        if (destroyed) return disposal;
        destroyed = true;
        abort.abort();
        disposal = (async () => {
        for (const fn of cleanups.splice(0).reverse()) {
            try { await fn(); } catch (error) { options.onError?.(error); }
        }
        try { await mounted?.destroy?.(); } finally { root.replaceChildren(); inputs.clear(); }
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
        if (destroyed) { await pending?.destroy?.(); throw new DOMException('Disposed', 'AbortError'); }
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
            if (node.unavailable) element.textContent = options.t('unavailable');
            else mounts.push(async () => {
                let client = namespaces.get(node.namespace);
                if (!client && window.PixivI18n) {
                    client = await window.PixivI18n.create({namespaces: [node.namespace]});
                    if (disposed) { client.dispose(); throw new DOMException('Disposed', 'AbortError'); }
                    namespaces.set(node.namespace, client); cleanups.push(() => client.dispose());
                }
                const instance = await mountComponent(element, node, node.configuration, {
                    ...options, signal: abort.signal, instanceId: node.instanceId,
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
export async function attachPage({pageId, root, regions, title, t, onMounted, onFallback, resetButton}) {
    let mounted;
    let closed = false;
    const controller = new AbortController();
    let timer;
    const fallback = async () => {
        if (closed) return; closed = true; controller.abort(); clearTimeout(timer);
        await mounted?.destroy(); root.replaceChildren(); onFallback?.();
        resetButton.hidden = true;
    };
    resetButton.addEventListener('click', fallback, {signal: controller.signal});
    window.addEventListener('pagehide', fallback, {once: true, signal: controller.signal});
    try {
        const response = await fetch('/api/web/page-publications/' + encodeURIComponent(pageId), {cache: 'no-store', signal: controller.signal});
        if (!response.ok) return fallback();
        const view = await response.json();
        const result = await renderPage(root, view, {mode: 'display', regions, t, signal: controller.signal, onUnavailable: fallback});
        if (closed) { await result.destroy(); return; }
        mounted = result;
        onMounted?.();
        if (view.title) title(view.title);
        resetButton.hidden = false;
        const verifySource = async () => {
            if (closed) return;
            try {
                const response = await fetch('/api/web/page-publications/' + encodeURIComponent(pageId), {method: 'HEAD', cache: 'no-store', signal: controller.signal});
                if (!response.ok) return fallback();
            } catch (_) { return fallback(); }
            timer = setTimeout(verifySource, 2000);
        };
        timer = setTimeout(verifySource, 2000);
    } catch (_) { await fallback(); }
    return {destroy: fallback};
}
