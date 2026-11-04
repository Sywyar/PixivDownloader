import {createOperationTarget} from './pixiv-pages.mjs';

const scopes = new Set();
let installed;
let projections = 0;
const failure = code => new DOMException(code, 'OperationHookError');
function routePattern(value) {
    // 先屏蔽模板再规范化 URL；Java 正则留给 MVC 判断，不在浏览器执行。
    let marker = 'pixivHookPath';
    while (value.includes(marker)) marker += '_';
    const parts = []; let masked = '';
    for (let i = 0; i < value.length;) {
        let pattern, rest = false;
        if (value[i] === '{') {
            rest = value[i + 1] === '*'; i++;
            let depth = 1, characterClass = 0;
            for (; i < value.length && depth; i++) {
                const char = value[i];
                if (char === '\\') { i++; continue; }
                if (char === '[') characterClass++;
                else if (char === ']' && characterClass) characterClass--;
                else if (!characterClass && char === '{') depth++;
                else if (!characterClass && char === '}') depth--;
            }
            if (depth) throw failure('HOOK_TARGET_INVALID');
            pattern = rest ? '.*' : '[^/]*';
        } else if (value[i] === '*') {
            rest = value[i + 1] === '*'; i += rest ? 2 : 1; pattern = rest ? '.*' : '[^/]*';
        } else if (value[i] === '?') { i++; pattern = '[^/]'; }
        else { masked += value[i++]; continue; }
        if (rest && masked.endsWith('/')) { masked = masked.slice(0, -1); pattern = '(?:/.*)?'; }
        masked += marker + parts.length + '_'; parts.push(pattern);
    }
    const path = new URL(masked, location.href).pathname;
    return new RegExp('^' + path.split(new RegExp('(' + marker + '\\d+_)')).map(part =>
        part.startsWith(marker) ? parts[Number(part.slice(marker.length, -1))]
            : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('') + '$');
}

/** 只有当前受管页面配置的同源目标进入适配；服务端仍以实际 handler 强制检查。 */
export function createHttpTargets(entries, {revision, signal, onChange = () => {}}) {
    if (!Array.isArray(entries) || entries.length > 128) throw failure('HOOK_TARGET_CAPACITY');
    const abort = new AbortController(), targets = {}, actions = [];
    const scope = {actions, close};
    for (const entry of entries) {
        if (!entry.target || targets[entry.target] || !entry.operation || !entry.requests?.length || entry.requests.length > 64)
            throw failure('HOOK_TARGET_INVALID');
        const candidates = entry.requests.map(({parameters, ...request}) => ({...request, parameterConditions: parameters || [],
            pattern: routePattern(request.url)}));
        const preferred = entry.selected || (candidates.length === 1 && !/[{*?]/.test(candidates[0].url) ? candidates[0] : undefined);
        const state = {candidates, formId: entry.formId || '', actionId: entry.actionId || '', selected: preferred, key: '', busy: false, armed: null, handoffs: 0, abort};
        state.target = createOperationTarget(entry.operation, onChange, async requestSignal => {
            if (!state.selected) throw failure('HOOK_ACTION_SELECTION_REQUIRED');
            state.key = requestKey(state, state.selected);
            return prepare(state.selected, '', {}, false, AbortSignal.any([requestSignal, abort.signal]));
        });
        state.target.configure(revision); targets[entry.target] = state.target; actions.push(state);
    }
    if (!actions.length) return {targets, destroy: close};
    if (!installed) installed = install();
    scopes.add(scope);
    signal?.addEventListener('abort', close, {once: true});
    if (signal?.aborted) close();
    function close() {
        if (abort.signal.aborted) return;
        abort.abort(); actions.forEach(action => action.target.destroy()); actions.length = 0;
        scopes.delete(scope); signal?.removeEventListener('abort', close);
        if (!scopes.size) { installed?.close(); installed = undefined; }
    }
    return {targets, destroy: close};
}

async function prepare(request, token, proofs, nativeSubmit, signal, resolveOnly = false) {
    const headers = {};
    for (const [name, value] of new Headers(request.headers)) {
        if (['accept', 'content-type'].includes(name) || name.startsWith('x-') && !/token|key|auth|cookie|^x-(forwarded|pixiv|real)-/.test(name)) headers[name] = value;
    }
    const url = new URL(request.url, location.href);
    const response = await installed.fetch('/api/operation-hooks/prepare', {
        method: 'POST', credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(10000)]),
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({method: request.method, url: url.pathname + url.search, headers, token, proofs, nativeSubmit, resolveOnly,
            json: request.fieldValues || {}, parameters: request.parameters || {}})
    });
    const value = await response.json();
    if (!(resolveOnly ? value.code === 'HOOK_RESOLVED' && typeof value.endpoint === 'string'
            : ['HOOK_APPROVED', 'HOOK_CHALLENGE', 'HOOK_READY'].includes(value.code)) || value.invoked !== false) {
        const error = failure(value.code || 'HOOK_UNAVAILABLE');
        if (value.invoked === false) { error.payload = value; error.status = response.status; }
        throw error;
    }
    return value;
}

function candidates(url, method, form, submitter) {
    const target = new URL(url, location.href);
    if (target.origin !== location.origin) return [];
    let found = [...scopes].flatMap(scope => scope.actions).filter(action => action.candidates.some(candidate =>
        candidate.method === method.toUpperCase() && candidate.pattern.test(target.pathname)));
    if (form) found = found.filter(action => !action.formId || action.formId === form.id
        && (!action.actionId || action.actionId === submitter?.id));
    return found;
}

async function select(request, body, signal, form, submitter) {
    let found = candidates(request.url, request.method, form, submitter);
    if (found.length > 1) {
        // 条件及优先级由 MVC 实际解析；浏览器仅以返回的接口身份关联页面动作。
        const routing = {candidates: found.flatMap(action => action.candidates.map(candidate => ({...candidate, fields: []}))),
            abort: {signal}};
        const matched = await prepare(await project(routing, request, body, signal), '', {}, false, signal, true);
        found = found.filter(action => action.candidates.some(candidate => candidate.endpoint === matched.endpoint));
        if (!found.length) throw failure('HOOK_TARGET_STALE');
    }
    if (!form && found.length > 1) {
        const armed = found.filter(action => action.armed);
        if (armed.length === 1) found = armed;
    }
    if (found.length > 1) throw failure('HOOK_TARGET_AMBIGUOUS');
    return found[0];
}

function holdArmed(request) {
    const held = candidates(request.url, request.method).filter(action => action.armed).map(action => {
        action.handoffs++; return {action, token: action.armed};
    });
    return () => held.forEach(({action, token}) => {
        if (--action.handoffs === 0 && action.armed === token) { action.armed = null; action.target.reset(); }
    });
}

async function authorize(action, request, nativeSubmit, signal) {
    if (action.busy) throw failure('HOOK_REQUEST_IN_PROGRESS');
    const combined = AbortSignal.any([signal || new AbortController().signal, action.abort.signal]);
    if (combined.aborted) { action.armed = null; action.target.reset(); combined.throwIfAborted(); }
    const key = requestKey(action, request);
    if (action.armed && action.key === key) { const ticket = action.armed; action.armed = null; return ticket; }
    if (action.key && action.key !== key) { action.armed = null; action.target.reset(); }
    action.key = key; action.selected = request; action.busy = true;
    const cancel = () => { action.armed = null; action.target.reset(); };
    combined.addEventListener('abort', cancel, {once: true});
    try {
        await action.target.prepare(); combined.throwIfAborted();
        if (!action.target.ready()) throw failure('HOOK_VERIFICATION_REQUIRED');
        const {token, proofs} = action.target.request();
        if (!token) return '';
        const staged = await prepare(request, token, proofs, nativeSubmit, combined);
        combined.throwIfAborted(); return staged.token;
    } catch (error) {
        if (error.message !== 'HOOK_VERIFICATION_REQUIRED') { action.armed = null; action.target.reset(); }
        throw error;
    } finally { combined.removeEventListener('abort', cancel); action.busy = false; }
}

function requestKey(action, request) {
    const url = new URL(request.url, location.href), headers = new Headers(request.headers);
    const names = new Set(action.candidates.flatMap(candidate => (candidate.headerConditions || []).map(value => value.replace(/^!/, '').split(/[!=]/)[0].toLowerCase())));
    return JSON.stringify([request.method, url.pathname + url.search, [...names].sort().map(name => [name, headers.get(name)]),
        request.fieldValues || {}, request.parameters || {}]);
}

function parameterNames(action) {
    return [...new Set(action.candidates.flatMap(candidate => candidate.parameterConditions).map(value => value.replace(/^!/, '').split(/[!=]/)[0]))].sort();
}
function formParameters(action, data) {
    const parameters = {};
    for (const name of parameterNames(action)) {
        const values = data.getAll(name);
        if (values.length > 128 || values.some(value => typeof value !== 'string' || value.length > 4096)) throw failure('HOOK_INPUT_LIMIT');
        if (values.length) parameters[name] = values;
    }
    return parameters;
}

async function project(action, request, body, signal) {
    const paths = [...new Set(action.candidates.flatMap(candidate => (candidate.fields || []).filter(field => field.source === 'JSON').map(field => field.path)))].sort();
    const form = /^(application\/x-www-form-urlencoded|multipart\/form-data)(;|$)/i.test(new Headers(request.headers).get('content-type') || '');
    const needsParameters = form && parameterNames(action).length;
    if (!paths.length && !needsParameters) return request;
    if (needsParameters && !paths.length && (body instanceof FormData || body instanceof URLSearchParams))
        return {method: request.method, url: request.url, headers: request.headers, parameters: formParameters(action, body)};
    if (needsParameters && /^multipart\//i.test(new Headers(request.headers).get('content-type'))) throw failure('HOOK_INPUT_UNSUPPORTED');
    const cancelled = AbortSignal.any([signal || request.signal || new AbortController().signal, action.abort.signal, AbortSignal.timeout(10000)]);
    cancelled.throwIfAborted();
    if (projections >= 4) throw failure('HOOK_INPUT_CAPACITY');
    projections++;
    try {
    let text;
    if (request instanceof Request) {
        const reader = request.clone().body?.getReader();
        if (!reader) throw failure('HOOK_INPUT_REQUIRED');
        const chunks = []; let length = 0, complete = false;
        const onAbort = () => { void reader.cancel().catch(() => {}); void request.body.cancel().catch(() => {}); };
        cancelled.addEventListener('abort', onAbort, {once: true});
        try {
            while (true) {
                const {done, value} = await reader.read(); cancelled.throwIfAborted(); if (done) { complete = true; break; }
                length += value.byteLength;
                if (length > 65536) throw failure('HOOK_INPUT_LIMIT');
                chunks.push(value);
            }
            const bytes = new Uint8Array(length); let offset = 0;
            for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
            text = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
        } finally {
            cancelled.removeEventListener('abort', onAbort);
            if (!complete) { void reader.cancel().catch(() => {}); void request.body.cancel().catch(() => {}); }
            reader.releaseLock();
        }
    } else if (typeof body === 'string') text = body;
    else if (body instanceof Blob && body.size <= 65536) text = await body.text();
    else throw failure('HOOK_INPUT_UNSUPPORTED');
    cancelled.throwIfAborted();
    if (new TextEncoder().encode(text).length > 65536) throw failure('HOOK_INPUT_LIMIT');
    const value = paths.length ? JSON.parse(text) : null, json = {};
    for (const path of paths) {
        let current = value;
        for (const key of path.slice(1).split('/').map(value => value.replace(/~1/g, '/').replace(/~0/g, '~'))) {
            current = current && typeof current === 'object' && Object.hasOwn(current, key) ? current[key] : undefined;
        }
        if (current !== undefined) json[path] = current;
    }
    return {method: request.method, url: request.url, headers: request.headers, fieldValues: json,
        parameters: needsParameters ? formParameters(action, new URLSearchParams(text)) : {}};
    } finally { projections--; }
}

function install() {
    const originalFetch = window.fetch, OriginalXHR = window.XMLHttpRequest, originalSubmit = HTMLFormElement.prototype.submit;
    const controller = new AbortController(), permitted = new WeakSet();
    const transport = {fetch: originalFetch.bind(window), close};
    let messages, notice;
    async function blocked(error, show) {
        if (!messages) messages = window.PixivI18n?.create({namespaces: ['common']}).catch(() => null) || Promise.resolve(null);
        const client = await messages;
        const code = error.message;
        const kind = code === 'HOOK_VERIFICATION_REQUIRED' || code === 'HOOK_CHALLENGE' ? 'required'
            : code.includes('STALE') ? 'stale' : code === 'HOOK_REJECTED' ? 'rejected' : 'unavailable';
        const payload = error.payload || {code, error: client?.t('common:hooks.' + kind, code) || code, invoked: false};
        if (controller.signal.aborted) throw new DOMException('Disposed', 'AbortError');
        document.dispatchEvent(new CustomEvent('pixiv:hook-blocked', {detail: {code: payload.code}}));
        if (show && window.PixivFeedback) {
            notice?.close();
            notice = window.PixivFeedback.toast({message: payload.error, kind: 'warning', signal: controller.signal,
                dismissLabel: client?.t('common:button.close', 'Close') || 'Close'});
        }
        return new Response(JSON.stringify(payload), {status: error.status >= 400 ? error.status : 409,
            headers: {'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Pixiv-Hook': payload.code}});
    }
    async function fetch(input, init) {
        const url = input instanceof Request ? input.url : String(input), method = init?.method || (input instanceof Request ? input.method : 'GET');
        if (!candidates(url, method).length) return originalFetch.call(window, input, init);
        const actual = new Request(input instanceof Request ? input : new URL(url, location.href), init);
        const releaseHandoff = holdArmed(actual); let action;
        let sent = false;
        try {
            action = await select(actual, init?.body, AbortSignal.any([actual.signal, controller.signal]));
            const token = await authorize(action, await project(action, actual, init?.body), false, actual.signal);
            const headers = new Headers(actual.headers); if (token) headers.set('X-Pixiv-Hook-Ticket', token);
            // 原始请求尚未发送；只在授权 JSON 字段时有界读取副本，从不重放已发送的业务。
            sent = true;
            return await originalFetch.call(window, new Request(actual, {headers}));
        } catch (error) {
            if (error.name === 'OperationHookError') return blocked(error, false);
            throw error;
        }
        finally { releaseHandoff(); if (sent) action.target.reset(); }
    }
    class ManagedXHR extends OriginalXHR {
        #managed; #request; #abort; #opened = false; #sent = false;
        open(method, url, async = true, ...credentials) {
            this.#abort?.abort(); this.#abort = undefined; this.#opened = false; this.#sent = false;
            this.#managed = candidates(url, method).length > 0;
            if (this.#managed && async === false) throw new DOMException('HOOK_SYNCHRONOUS_XHR_UNSUPPORTED', 'NotSupportedError');
            super.open(method, url, async, ...credentials);
            this.#request = {method: method.toUpperCase(), url: new URL(url, location.href).href, headers: new Headers()}; this.#opened = true;
        }
        setRequestHeader(name, value) { super.setRequestHeader(name, value); this.#request?.headers.append(name, value); }
        send(body) {
            if (!this.#managed) return super.send(body);
            if (!this.#opened || this.#abort) throw new DOMException('Request already sent', 'InvalidStateError');
            const current = this.#abort = new AbortController(); let action;
            const request = {...this.#request, headers: new Headers(this.#request.headers)};
            const releaseHandoff = holdArmed(request);
            if (!request.headers.has('content-type') && !['GET', 'HEAD'].includes(request.method)) {
                const type = body instanceof URLSearchParams ? 'application/x-www-form-urlencoded;charset=UTF-8'
                    : body instanceof FormData ? 'multipart/form-data' : body instanceof Blob ? body.type
                    : typeof body === 'string' ? 'text/plain;charset=UTF-8' : '';
                if (type) request.headers.set('content-type', type);
            }
            select(request, body, AbortSignal.any([current.signal, controller.signal])).then(selected => {
                action = selected;
                return project(action, request, body, current.signal);
            }).then(request => authorize(action, request, false, current.signal)).then(token => {
                current.signal.throwIfAborted();
                if (token) super.setRequestHeader('X-Pixiv-Hook-Ticket', token);
                this.addEventListener('loadend', () => { action.target.reset(); if (this.#abort === current) this.#abort = undefined; }, {once: true});
                this.#sent = true; super.send(body);
            }).catch(error => {
                if (this.#abort !== current) return;
                if (error.message !== 'HOOK_VERIFICATION_REQUIRED') action?.target.reset(); this.#abort = undefined;
                if (error.name !== 'AbortError') {
                    if (error.name === 'OperationHookError') void blocked(error, true).catch(() => {});
                    this.dispatchEvent(new ProgressEvent('error')); this.dispatchEvent(new ProgressEvent('loadend'));
                }
            }).finally(releaseHandoff);
        }
        abort() {
            const pending = !!this.#abort && !this.#sent; this.#abort?.abort(); this.#abort = undefined;
            super.abort();
            if (pending) {
                this.dispatchEvent(new ProgressEvent('abort')); this.dispatchEvent(new ProgressEvent('loadend'));
            }
        }
    }
    function formRequest(form, submitter) {
        const method = (submitter?.getAttribute('formmethod') || form.method || 'GET').toUpperCase();
        const url = new URL(submitter?.getAttribute('formaction') || form.action, location.href);
        const data = new FormData(form, submitter);
        if (method === 'GET') url.search = new URLSearchParams([...data].map(([name, value]) => [name, typeof value === 'string' ? value : value.name])).toString();
        return {url: url.href, method, headers: method === 'GET' ? {} : {'Content-Type': submitter?.getAttribute('formenctype') || form.enctype},
            data};
    }
    async function submit(form, submitter, direct) {
        try {
            let request = formRequest(form, submitter);
            const action = await select(request, request.data, controller.signal, form, submitter);
            if (!action) return originalSubmit.call(form);
            request = {...request, parameters: request.method === 'GET' ? {} : formParameters(action, request.data)};
            const token = await authorize(action, request, true, controller.signal);
            action.armed = token;
            if (direct) originalSubmit.call(form);
            else { permitted.add(form); form.requestSubmit(submitter); }
            queueMicrotask(() => { permitted.delete(form); if (!action.handoffs && action.armed === token) { action.armed = null; action.target.reset(); } });
        } catch (error) { if (error.name !== 'AbortError') void blocked(error.name === 'OperationHookError' ? error : failure('HOOK_UNAVAILABLE'), true).catch(() => {}); }
    }
    function nativeSubmit() {
        void submit(this, undefined, true);
    }
    document.addEventListener('submit', event => {
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || permitted.delete(form)) return;
        try {
            const request = formRequest(form, event.submitter);
            if (!candidates(request.url, request.method, form, event.submitter).length) return;
        } catch (error) {
            event.preventDefault(); event.stopImmediatePropagation();
            void blocked(error.name === 'OperationHookError' ? error : failure('HOOK_UNAVAILABLE'), true).catch(() => {});
            return;
        }
        event.preventDefault(); event.stopImmediatePropagation(); void submit(form, event.submitter, false);
    }, {capture: true, signal: controller.signal});
    window.fetch = fetch; window.XMLHttpRequest = ManagedXHR; HTMLFormElement.prototype.submit = nativeSubmit;
    function close() {
        controller.abort();
        messages?.then(client => client?.dispose());
        if (window.fetch === fetch) window.fetch = originalFetch;
        if (window.XMLHttpRequest === ManagedXHR) window.XMLHttpRequest = OriginalXHR;
        if (HTMLFormElement.prototype.submit === nativeSubmit) HTMLFormElement.prototype.submit = originalSubmit;
    }
    return transport;
}
