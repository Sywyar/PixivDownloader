(function (global) {
    'use strict';
    const modules = new Map(), pages = new Map();
    const copy = value => {
        const text = JSON.stringify(value ?? Object.create(null), function (key, current) {
            const original = this[key];
            if (original === undefined || typeof original === 'function' || typeof original === 'symbol'
                    || typeof original === 'bigint' || typeof original === 'number' && !Number.isFinite(original)
                    || original && typeof original === 'object' && !Array.isArray(original)
                        && ![Object.prototype, null].includes(Object.getPrototypeOf(original))) throw new Error('UI_VALUE_INVALID');
            return current;
        });
        if (text.length > 1024 * 1024) throw new Error('UI_VALUE_BUDGET');
        return JSON.parse(text);
    };
    const moduleKey = (url, publication) => new URL(url, location.origin).pathname + '?' + publication;
    function localUrl(path, publication) {
        const url = new URL(path, location.origin);
        if (url.origin !== location.origin) throw new Error('UI_MODULE_ORIGIN');
        url.searchParams.set('uiPublication', publication);
        return url;
    }
    function registerModule(url, mount) {
        const script = document.currentScript;
        const publication = script && new URL(script.src, location.origin).searchParams.get('uiPublication');
        if (!publication || typeof mount !== 'function') throw new Error('UI_MODULE_REGISTRATION');
        const key = moduleKey(url, publication);
        const module = modules.get(key);
        if (!module || module.script !== script) throw new Error('UI_MODULE_REGISTRATION');
        if (module.mount) throw new Error('UI_MODULE_DUPLICATE');
        module.mount = mount;
    }
    function loadModule(slot, signal, onCleanup) {
        const key = moduleKey(slot.moduleUrl, slot.publication);
        let module = modules.get(key);
        if (!module) {
            const script = document.createElement('script');
            script.src = localUrl(slot.moduleUrl, slot.publication).href;
            script.async = false;
            module = {script, users: 0};
            modules.set(key, module);
            module.ready = new Promise((resolve, reject) => {
                const close = () => { script.remove(); clearTimeout(timer); };
                module.cancel = () => { close(); reject(new DOMException('Disposed', 'AbortError')); };
                const timer = setTimeout(() => { close(); reject(new Error('UI_MODULE_TIMEOUT')); }, 10000);
                script.onload = () => {
                    close();
                    if (module.mount) resolve(module.mount); else reject(new Error('UI_MODULE_REGISTRATION'));
                };
                script.onerror = () => { close(); reject(new Error('UI_MODULE_UNAVAILABLE')); };
            });
            document.head.append(script);
        }
        module.users++;
        onCleanup(() => {
            if (--module.users === 0) {
                if (modules.get(key) === module) modules.delete(key);
                module.cancel();
            }
        });
        return new Promise((resolve, reject) => {
            const cancel = () => reject(new DOMException('Disposed', 'AbortError'));
            signal.addEventListener('abort', cancel, {once: true});
            module.ready.then(value => { signal.removeEventListener('abort', cancel); resolve(value); },
                error => { signal.removeEventListener('abort', cancel); reject(error); });
            if (signal.aborted) cancel();
        });
    }
    function anchor(target, root) {
        global.PixivVue?.prepareSlotHosts(root);
        return [...root.querySelectorAll('[data-vue-slot], [data-ui-slot]')]
            .find(element => (element.getAttribute('data-vue-slot') || element.getAttribute('data-ui-slot')) === target);
    }
    function moveHost(host, next) {
        const container = host.parentNode;
        if (host.nextSibling === next) return;
        const focused = document.activeElement;
        const restoreFocus = focused && host.contains(focused);
        const selection = restoreFocus && typeof focused.selectionStart === 'number'
            ? [focused.selectionStart, focused.selectionEnd, focused.selectionDirection] : null;
        const selected = document.getSelection?.();
        const range = !selection && selected && host.contains(selected.anchorNode)
            ? [selected.anchorNode, selected.anchorOffset, selected.focusNode, selected.focusOffset] : null;
        if (typeof container.moveBefore === 'function') container.moveBefore(host, next);
        else container.insertBefore(host, next);
        // DOM 移动可能重置选区；旧浏览器重新插入时还需恢复焦点。
        if (restoreFocus && document.activeElement !== focused) focused.focus({preventScroll: true});
        if (selection) focused.setSelectionRange(...selection);
        if (range) selected.setBaseAndExtent(...range);
    }
    function connect(options = {}) {
        const root = options.root || document;
        const abort = new AbortController(), instances = new Map(), failures = new Map();
        let revision = 0, current = [], pending, closing, timer;
        let properties = copy(options.properties);
        const report = code => {
            try {
                if (options.onError) options.onError(new Error(code));
                else global.PixivFeedback?.toast({kind: 'error', message: options.i18n?.()?.t(
                    'common:error.feature-unavailable', 'This feature is temporarily unavailable. Refresh the page and try again.')});
            } catch (_) {}
        };
        const destroyEntry = entry => {
            if (entry.closing) return entry.closing;
            entry.abort.abort(); entry.host.remove();
            entry.closing = Promise.resolve().then(async () => {
                for (const cleanup of entry.cleanups.splice(0).reverse()) {
                    try { await cleanup(); } catch (_) { report('UI_CLEANUP_FAILED'); }
                }
                try { await entry.instance?.destroy?.(); } catch (_) { report('UI_CLEANUP_FAILED'); }
                entry.host.replaceChildren();
            });
            return entry.closing;
        };
        async function mount(slot, next) {
            const container = anchor(slot.target, root);
            if (!container || !slot.moduleUrl) return;
            const entry = {slot, key: moduleKey(slot.moduleUrl, slot.publication),
                host: document.createElement('div'), abort: new AbortController(), cleanups: []};
            entry.host.dataset.uiInstance = slot.slotId;
            instances.set(slot.slotId, entry);
            container.insertBefore(entry.host, next?.parentNode === container ? next : null);
            const active = () => {
                if (entry.abort.signal.aborted) throw new DOMException('Disposed', 'AbortError');
            };
            const onCleanup = fn => {
                if (typeof fn !== 'function') throw new TypeError('cleanup');
                if (entry.abort.signal.aborted) Promise.resolve().then(fn).catch(() => report('UI_CLEANUP_FAILED'));
                else entry.cleanups.push(fn);
            };
            const context = Object.freeze({
                slotId: slot.slotId, target: slot.target, publication: slot.publication, signal: entry.abort.signal,
                get properties() { return copy(properties); },
                get i18n() { return options.i18n?.(); },
                region: name => options.regions?.[name] || null,
                emit(name, value) { active(); return options.onEvent?.(name, copy(value)); },
                onCleanup,
                loadScript(path) {
                    active();
                    return new Promise((resolve, reject) => {
                        const script = document.createElement('script');
                        script.src = localUrl(path, slot.publication).href; script.async = false;
                        const clear = () => { script.remove(); clearTimeout(timeout); };
                        const timeout = setTimeout(() => { clear(); reject(new Error('UI_MODULE_TIMEOUT')); }, 10000);
                        script.onload = () => { clear(); entry.abort.signal.aborted ? reject(new DOMException('Disposed', 'AbortError')) : resolve(true); };
                        script.onerror = () => { clear(); reject(new Error('UI_MODULE_UNAVAILABLE')); };
                        onCleanup(() => { clear(); reject(new DOMException('Disposed', 'AbortError')); });
                        document.head.append(script);
                    });
                },
                loadStyle(path) {
                    active(); const link = document.createElement('link'); link.rel = 'stylesheet';
                    link.href = localUrl(path, slot.publication).href; document.head.append(link);
                    onCleanup(() => link.remove());
                },
                on(target, event, handler) { active(); target.addEventListener(event, handler, {signal: entry.abort.signal}); },
                interval(handler, ms) {
                    if (!Number.isFinite(ms) || ms > 2147483647) throw new Error('UI_INTERVAL_INVALID');
                    active(); const id = setInterval(() => { if (!entry.abort.signal.aborted) handler(); }, Math.max(100, ms));
                    onCleanup(() => clearInterval(id));
                },
                async fetch(input, init = {}) {
                    active();
                    const request = input instanceof Request ? new Request(input, init) : new Request(new URL(input, location.origin), init);
                    if (new URL(request.url).origin !== location.origin) throw new Error('UI_REQUEST_ORIGIN');
                    const headers = new Headers(request.headers); headers.set('X-Pixiv-Ui-Publication', slot.publication);
                    const response = await fetch(request, {headers, credentials: 'same-origin', redirect: 'error',
                        signal: AbortSignal.any([entry.abort.signal, request.signal])});
                    active();
                    if (response.status === 409 && (await response.clone().json().catch(() => ({}))).code === 'UI_PUBLICATION_STALE') {
                        instances.delete(slot.slotId); await destroyEntry(entry); throw new Error('UI_PUBLICATION_STALE');
                    }
                    return response;
                }
            });
            try {
                const factory = await loadModule(slot, entry.abort.signal, onCleanup); active();
                const instance = await factory(entry.host, context);
                if (entry.abort.signal.aborted) { await instance?.destroy?.(); return; }
                entry.instance = instance;
                options.i18n?.()?.apply(entry.host);
            } catch (_) {
                const cancelled = entry.abort.signal.aborted;
                if (instances.get(slot.slotId) === entry) instances.delete(slot.slotId);
                await destroyEntry(entry);
                if (!abort.signal.aborted && !cancelled) report('UI_MOUNT_FAILED');
                if (!abort.signal.aborted && current.some(item => item.slotId === slot.slotId && item.publication === slot.publication))
                    failures.set(slot.slotId, slot.publication);
            }
        }
        async function reconcile(slots, ticket) {
            if (abort.signal.aborted || ticket !== revision) return;
            if (!Array.isArray(slots) || slots.length > 1024 || new Set(slots.map(slot => slot.slotId)).size !== slots.length)
                throw new Error('UI_MANIFEST_INVALID');
            const wanted = new Map(slots.map(slot => [slot.slotId, slot]));
            for (const [id, publication] of failures) if (wanted.get(id)?.publication !== publication) failures.delete(id);
            current = slots;
            const closing = [];
            for (const [id, entry] of instances) {
                const slot = wanted.get(id);
                if (!slot || slot.publication !== entry.slot.publication || slot.moduleUrl !== entry.slot.moduleUrl || slot.target !== entry.slot.target) {
                    instances.delete(id); closing.push(destroyEntry(entry));
                }
            }
            await Promise.allSettled(closing);
            const nextHosts = new Map(), stableByTarget = new Map();
            for (let index = slots.length - 1; index >= 0; index--) {
                const slot = slots[index];
                nextHosts.set(slot.slotId, stableByTarget.get(slot.target));
                const host = instances.get(slot.slotId)?.host;
                if (host) stableByTarget.set(slot.target, host);
            }
            for (const slot of slots) {
                if (abort.signal.aborted || ticket !== revision) return;
                if (!instances.has(slot.slotId) && failures.get(slot.slotId) !== slot.publication) await mount(slot, nextHosts.get(slot.slotId));
            }
            if (abort.signal.aborted || ticket !== revision) return;
            const nextByTarget = new Map();
            for (const slot of [...slots].reverse()) {
                const host = instances.get(slot.slotId)?.host;
                if (!host?.parentNode) continue;
                const next = nextByTarget.get(host.parentNode) || null;
                moveHost(host, next);
                nextByTarget.set(host.parentNode, host);
            }
        }
        const refresh = () => {
            if (abort.signal.aborted) return Promise.resolve([]);
            if (pending) return pending;
            const ticket = ++revision;
            const prefix = options.targetPrefix || '';
            const url = new URL(options.manifestUrl || (global.BASE || '') + '/api/web/ui-slots', location.origin);
            if (url.origin !== location.origin) return Promise.reject(new Error('UI_MANIFEST_ORIGIN'));
            if (prefix) url.searchParams.set('targetPrefix', prefix);
            const request = Promise.resolve().then(async () => {
                try {
                    const response = await fetch(url, {cache: 'no-store', credentials: 'same-origin', signal: abort.signal});
                    if (!response.ok) throw new Error('UI_MANIFEST_UNAVAILABLE');
                    const slots = await response.json();
                    if (pending === request) pending = null;
                    await reconcile(slots, ticket);
                } catch (_) {
                    if (!abort.signal.aborted && ticket === revision) { await reconcile([], ticket); report('UI_MANIFEST_UNAVAILABLE'); }
                } finally { if (pending === request) pending = null; }
                return current;
            });
            pending = request;
            return request;
        };
        const destroy = () => {
            if (closing) return closing;
            abort.abort(); ++revision; clearInterval(timer);
            const entries = [...instances.values()]; instances.clear(); failures.clear(); current = [];
            closing = Promise.allSettled(entries.map(destroyEntry)).then(() => {
                for (const [key, page] of pages) if (page.destroy === destroy) pages.delete(key);
            });
            return closing;
        };
        const update = async value => {
            if (abort.signal.aborted) return;
            properties = copy(value);
            await Promise.allSettled([...instances.values()].map(async entry => {
                try { await entry.instance?.update?.(copy(properties)); options.i18n?.()?.apply(entry.host); }
                catch (_) { report('UI_UPDATE_FAILED'); }
            }));
        };
        global.addEventListener('pagehide', destroy, {once: true, signal: abort.signal});
        timer = setInterval(() => { if (!document.hidden) void refresh(); }, 2000);
        return {refresh, update, destroy};
    }
    async function bootstrap(options = {}) {
        const key = options.targetPrefix || '';
        if (!pages.has(key)) pages.set(key, connect(options));
        return pages.get(key).refresh();
    }
    global.PixivUiSlots = Object.freeze({registerModule, connect, bootstrap,
        update: (prefix, value) => pages.get(prefix)?.update(value)});
})(window);
