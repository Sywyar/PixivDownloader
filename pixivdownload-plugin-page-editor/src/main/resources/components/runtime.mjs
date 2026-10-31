const watches = new Map();

function watch(url, dispose) {
    let entry = watches.get(url);
    if (!entry) {
        entry = {listeners: new Set(), busy: false, abort: new AbortController()};
        entry.timer = setInterval(async () => {
            if (entry.busy) return;
            entry.busy = true;
            try {
                const response = await fetch(url, {method: 'HEAD', cache: 'no-store', credentials: 'omit', signal: entry.abort.signal});
                if (!response.ok) for (const listener of [...entry.listeners]) listener();
            } catch (_) { if (!entry.abort.signal.aborted) for (const listener of [...entry.listeners]) listener(); }
            finally { entry.busy = false; }
        }, 2000);
        watches.set(url, entry);
    }
    entry.listeners.add(dispose);
    return () => {
        entry.listeners.delete(dispose);
        if (!entry.listeners.size) { clearInterval(entry.timer); entry.abort.abort(); watches.delete(url); }
    };
}

/** 只有纯值属性经过 MessagePort；宿主上下文与凭证不传入沙箱。 */
export async function mountFrame(root, url, properties, {signal, mode = 'preview', title, onError = () => {}}) {
    const frame = root.ownerDocument.createElement('iframe');
    frame.title = title; frame.setAttribute('sandbox', 'allow-scripts'); frame.referrerPolicy = 'no-referrer';
    frame.style.cssText = 'display:block;width:100%;height:100px;border:0;background:transparent;visibility:hidden';
    const placeholder = root.ownerDocument.createElement('div');
    placeholder.dataset.componentPlaceholder = '';
    placeholder.textContent = title;
    placeholder.hidden = true;
    placeholder.style.cssText = 'min-height:24px;box-sizing:border-box;border:1px dashed currentColor;opacity:.65;font:inherit';
    const parentWindow = root.ownerDocument.defaultView;
    let port, closed = false, settled = false, ready, fail, timer, unwatch;
    function appearance() {
        const style = parentWindow.getComputedStyle(root);
        frame.style.colorScheme = style.colorScheme;
        return {colorScheme: style.colorScheme, color: style.color};
    }
    const syncAppearance = () => {
        if (!closed && port) port.postMessage({type: 'appearance', appearance: appearance()});
    };
    const onTransition = event => {
        if (event.propertyName === 'color' && event.target.contains(root)) syncAppearance();
    };
    const theme = new MutationObserver(syncAppearance);
    theme.observe(root.ownerDocument.documentElement, {attributes: true, attributeFilter: ['class', 'style', 'data-theme']});
    root.ownerDocument.addEventListener('transitionend', onTransition);
    root.ownerDocument.addEventListener('transitioncancel', onTransition);
    const loaded = new Promise((resolve, reject) => { ready = resolve; fail = reject; });
    const destroy = () => {
        if (closed) return;
        closed = true; clearTimeout(timer); unwatch?.(); theme.disconnect(); port?.close(); frame.remove(); placeholder.remove();
        root.ownerDocument.removeEventListener('transitionend', onTransition);
        root.ownerDocument.removeEventListener('transitioncancel', onTransition);
        parentWindow.removeEventListener('message', handshake); signal?.removeEventListener('abort', destroy);
        if (!settled) { settled = true; fail(new DOMException('Disposed', 'AbortError')); }
    };
    const error = code => {
        if (closed) return;
        if (!settled) { settled = true; fail(new Error(code)); }
        destroy(); onError(code);
    };
    function handshake(event) {
        if (closed || event.source !== frame.contentWindow || event.origin !== 'null' || event.data !== 'component-ready' || port) return;
        const channel = new MessageChannel(); port = channel.port1;
        port.onmessage = ({data}) => {
            if (closed) return;
            if (data?.type === 'mounted') { clearTimeout(timer); frame.style.visibility = ''; settled = true; ready(); }
            else if (data?.type === 'height' && Number.isFinite(data.value)) {
                const height = Math.max(0, Math.min(4096, data.value));
                frame.style.height = height + 'px';
                placeholder.hidden = mode !== 'edit' || height !== 0;
            }
            else if (data?.type === 'error') error(['PAGE_COMPONENT_HTML_INVALID', 'PAGE_COMPONENT_PROPERTIES_INVALID'].includes(data.code)
                ? data.code : 'PAGE_COMPONENT_SCRIPT_FAILED');
        };
        frame.contentWindow.postMessage({type: 'component-connect', properties, mode, appearance: appearance()}, '*', [channel.port2]);
    }
    parentWindow.addEventListener('message', handshake);
    signal?.addEventListener('abort', destroy, {once: true});
    if (signal?.aborted) destroy();
    if (!closed) {
        timer = setTimeout(() => error('PAGE_COMPONENT_START_TIMEOUT'), 10000);
        frame.src = url; root.replaceChildren(frame, placeholder);
    }
    await loaded;
    unwatch = watch(url.replace(/frame$/, 'status'), () => error('PAGE_COMPONENT_UNAVAILABLE'));
    return {update(next) { properties = next; if (!closed) port.postMessage({type: 'update', properties}); }, destroy};
}

export async function mount(root, context) {
    const {componentId, version, properties} = context.properties;
    if (!/^[0-9a-f-]{36}$/.test(componentId) || !/^[0-9a-f]{64}$/.test(version)) throw new Error('PAGE_COMPONENT_UNAVAILABLE');
    const base = '/page-components/published/' + componentId + '/' + version + '/';
    const unavailable = () => { instance?.destroy(); root.textContent = context.t('unavailable'); };
    let instance;
    instance = await mountFrame(root, base + 'frame', properties, {
        signal: context.signal, mode: context.mode, title: context.t('custom.component'), onError: unavailable
    });
    return {update(next) {
        if (next.componentId !== componentId || next.version !== version) throw new Error('PAGE_COMPONENT_VERSION_CHANGED');
        instance.update(next.properties);
    }, destroy() { instance.destroy(); }};
}
