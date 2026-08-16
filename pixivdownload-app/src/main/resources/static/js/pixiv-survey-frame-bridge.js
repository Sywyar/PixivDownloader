(function (global) {
    'use strict';

    var INIT_TYPE = 'pixiv-survey-bridge-init';
    var FETCH_TYPE = 'pixiv-survey-bridge-fetch';
    var FETCH_CANCEL_TYPE = 'pixiv-survey-bridge-fetch-cancel';
    var FETCH_RESPONSE_TYPE = 'pixiv-survey-bridge-fetch-response';
    var STORAGE_SET_TYPE = 'pixiv-survey-bridge-storage-set';
    var STORAGE_UPDATE_TYPE = 'pixiv-survey-bridge-storage-update';
    var UI_MESSAGE_TYPES = new Set(['pixiv-content-height', 'pixiv-survey-unavailable']);
    var MAX_URL_LENGTH = 4096;
    var MAX_BODY_BYTES = 64 * 1024;
    var MAX_RESPONSE_BYTES = 1024 * 1024;
    var MAX_MESSAGE_BYTES = 8192;
    var MAX_CAPABILITIES = 32;
    var MAX_STORAGE_KEY_LENGTH = 256;
    var nativeFetch = typeof global.fetch === 'function' ? global.fetch.bind(global) : null;
    var childPort = null;
    var childStorage = null;
    var childPending = new Map();
    var nextRequestId = 0;
    var resolveChildReady = null;
    var childReady = null;

    function byteLength(value) {
        return new TextEncoder().encode(String(value || '')).length;
    }

    function safePost(port, data, transfers) {
        try {
            port.postMessage(data, transfers || []);
            return true;
        } catch (_) {
            return false;
        }
    }

    function messageWithinLimit(data) {
        try {
            return byteLength(JSON.stringify(data)) <= MAX_MESSAGE_BYTES;
        } catch (_) {
            return false;
        }
    }

    function createStorage(values, readKeys, writeKeys) {
        var data = new Map();
        var readable = new Set(readKeys);
        var writable = new Set(writeKeys);
        readKeys.forEach(function (key) {
            var value = Object.prototype.hasOwnProperty.call(values, key) ? values[key] : null;
            data.set(key, typeof value === 'string' ? value : null);
        });

        function keysWithValues() {
            return readKeys.filter(function (key) { return data.get(key) !== null; });
        }

        return Object.freeze({
            get length() { return keysWithValues().length; },
            key: function (index) { return keysWithValues()[index] || null; },
            getItem: function (key) {
                key = String(key);
                return readable.has(key) && data.has(key) ? data.get(key) : null;
            },
            setItem: function (key, value) {
                key = String(key);
                value = String(value);
                if (!writable.has(key) || byteLength(value) > MAX_BODY_BYTES) {
                    throw new Error('storage capability denied');
                }
                if (readable.has(key)) data.set(key, value);
                safePost(childPort, {type: STORAGE_SET_TYPE, key: key, value: value});
            },
            removeItem: function (key) {
                key = String(key);
                if (!writable.has(key)) throw new Error('storage capability denied');
                if (readable.has(key)) data.set(key, null);
                safePost(childPort, {type: STORAGE_SET_TYPE, key: key, value: null});
            },
            clear: function () {
                writeKeys.forEach(function (key) {
                    if (readable.has(key)) data.set(key, null);
                    safePost(childPort, {type: STORAGE_SET_TYPE, key: key, value: null});
                });
            },
            _update: function (key, value) {
                if (readable.has(key)) data.set(key, value);
            }
        });
    }

    function dispatchStorageUpdate(key, value) {
        if (!childStorage) return;
        childStorage._update(key, value);
        try {
            global.dispatchEvent(new StorageEvent('storage', {key: key, newValue: value}));
        } catch (_) {
            try {
                var event = new Event('storage');
                event.key = key;
                event.newValue = value;
                global.dispatchEvent(event);
            } catch (_) { /* older embedded browsers receive the updated adapter on the next read */ }
        }
    }

    function handleChildMessage(event) {
        var data = event && event.data;
        if (!data || typeof data !== 'object') return;
        if (data.type === STORAGE_UPDATE_TYPE && typeof data.key === 'string'
                && (typeof data.value === 'string' || data.value === null)
                && (data.value === null || byteLength(data.value) <= MAX_BODY_BYTES)) {
            dispatchStorageUpdate(data.key, data.value);
            return;
        }
        if (data.type !== FETCH_RESPONSE_TYPE || typeof data.id !== 'string') return;
        var pending = childPending.get(data.id);
        if (!pending) return;
        childPending.delete(data.id);
        if (pending.cleanup) pending.cleanup();
        if (!data.ok) {
            pending.reject(new Error(typeof data.error === 'string' ? data.error : 'request denied'));
            return;
        }
        try {
            var body = data.body || new ArrayBuffer(0);
            if ((data.status === 204 || data.status === 205 || data.status === 304)
                    && body.byteLength === 0) body = null;
            pending.resolve(new Response(body, {
                status: data.status,
                statusText: typeof data.statusText === 'string' ? data.statusText : '',
                headers: data.headers && typeof data.headers === 'object' ? data.headers : {}
            }));
        } catch (error) {
            pending.reject(error);
        }
    }

    function acceptParent(event) {
        var data = event && event.data;
        if (childPort || event.source !== global.parent || event.origin !== global.location.origin
                || !data || data.type !== INIT_TYPE || !event.ports || event.ports.length !== 1
                || !Array.isArray(data.readKeys) || !Array.isArray(data.writeKeys)
                || data.readKeys.length > MAX_CAPABILITIES || data.writeKeys.length > MAX_CAPABILITIES
                || !data.storage || typeof data.storage !== 'object') return;
        childPort = event.ports[0];
        childPort.onmessage = handleChildMessage;
        if (typeof childPort.start === 'function') childPort.start();
        childStorage = createStorage(data.storage, data.readKeys.slice(), data.writeKeys.slice());
        global.removeEventListener('message', acceptParent);
        resolveChildReady(Object.freeze({storage: childStorage}));
    }

    function serializeHeaders(headers) {
        var result = {};
        if (!headers) return result;
        var parsed = new Headers(headers);
        parsed.forEach(function (value, name) {
            var normalized = name.toLowerCase();
            if (normalized !== 'accept' && normalized !== 'content-type') {
                throw new TypeError('request header denied');
            }
            result[normalized] = value;
        });
        return result;
    }

    function bridgedFetch(input, init) {
        var rawUrl = typeof input === 'string' || input instanceof URL
            ? String(input) : input && typeof input.url === 'string' ? input.url : '';
        var url;
        try {
            url = new URL(rawUrl, global.location.href);
        } catch (_) {
            return Promise.reject(new TypeError('invalid request URL'));
        }
        if (url.origin !== global.location.origin) {
            return nativeFetch ? nativeFetch(input, init) : Promise.reject(new TypeError('fetch unavailable'));
        }
        if (!(typeof input === 'string' || input instanceof URL)) {
            return Promise.reject(new TypeError('same-origin Request objects are not supported'));
        }
        init = init || {};
        var method = String(init.method || 'GET').toUpperCase();
        var body = init.body == null ? null : init.body;
        if ((method !== 'GET' && method !== 'POST') || (method === 'GET' && body !== null)
                || (body !== null && typeof body !== 'string')
                || url.href.length > MAX_URL_LENGTH
                || (body !== null && byteLength(body) > MAX_BODY_BYTES)) {
            return Promise.reject(new TypeError('request capability denied'));
        }
        var headers;
        try {
            headers = serializeHeaders(init.headers);
        } catch (error) {
            return Promise.reject(error);
        }
        return childReady.then(function () {
            if (init.signal && init.signal.aborted) throw new DOMException('Aborted', 'AbortError');
            var id = String(++nextRequestId);
            return new Promise(function (resolve, reject) {
                var onAbort = null;
                if (init.signal) {
                    onAbort = function () {
                        childPending.delete(id);
                        safePost(childPort, {type: FETCH_CANCEL_TYPE, id: id});
                        reject(new DOMException('Aborted', 'AbortError'));
                    };
                    init.signal.addEventListener('abort', onAbort, {once: true});
                }
                childPending.set(id, {
                    resolve: resolve,
                    reject: reject,
                    cleanup: onAbort ? function () { init.signal.removeEventListener('abort', onAbort); } : null
                });
                if (!safePost(childPort, {
                    type: FETCH_TYPE,
                    id: id,
                    url: url.pathname + url.search,
                    method: method,
                    headers: headers,
                    body: body
                })) {
                    childPending.delete(id);
                    if (onAbort) init.signal.removeEventListener('abort', onAbort);
                    reject(new Error('request bridge unavailable'));
                }
            });
        });
    }

    function parseCapabilities(source) {
        if (typeof source !== 'string' || source.length > 8192) return null;
        var url;
        try {
            url = new URL(source, global.location.origin);
        } catch (_) {
            return null;
        }
        if (url.origin !== global.location.origin) return null;

        function paths(name) {
            var result = new Set();
            var values = url.searchParams.getAll(name);
            if (values.length > MAX_CAPABILITIES) return null;
            for (var i = 0; i < values.length; i++) {
                var value = values[i];
                if (!value || value.length > 256 || value.charAt(0) !== '/') return null;
                var parsed = new URL(value, global.location.origin);
                if (parsed.origin !== global.location.origin || parsed.pathname !== value
                        || parsed.search || parsed.hash) return null;
                result.add(value);
            }
            return result;
        }

        function storageKeys(name) {
            var result = new Set();
            var values = url.searchParams.getAll(name);
            if (values.length > MAX_CAPABILITIES) return null;
            for (var i = 0; i < values.length; i++) {
                var value = values[i];
                if (!value || value.length > 256 || /[\u0000-\u001f\u007f]/.test(value)) return null;
                result.add(value);
            }
            return result;
        }

        var getPaths = paths('pixivBridgeGet');
        var postPaths = paths('pixivBridgePost');
        var readKeys = storageKeys('pixivBridgeRead');
        var writeKeys = storageKeys('pixivBridgeWrite');
        return getPaths && postPaths && readKeys && writeKeys
            ? {getPaths: getPaths, postPaths: postPaths, readKeys: readKeys, writeKeys: writeKeys}
            : null;
    }

    function createHost(options) {
        options = options || {};
        var records = new Map();

        function active(record) {
            return records.get(record.frame) === record
                && typeof options.isActive === 'function' && options.isActive(record.frame) === true;
        }

        function send(record, data, transfers) {
            return record.port ? safePost(record.port, data, transfers) : false;
        }

        function rejectRequest(record, id, error) {
            send(record, {type: FETCH_RESPONSE_TYPE, id: id, ok: false, error: error});
        }

        function cancelRequest(request) {
            var reader = request.reader;
            request.reader = null;
            request.controller.abort();
            if (!reader) return;
            try {
                Promise.resolve(reader.cancel()).catch(function () {});
            } catch (_) { /* the body is already closed */ }
        }

        function cancelBody(body) {
            if (!body || typeof body.cancel !== 'function') return;
            try {
                Promise.resolve(body.cancel()).catch(function () {});
            } catch (_) { /* the body is already closed */ }
        }

        function readResponseBody(response, request) {
            var declaredLength = response.headers.get('content-length');
            if (declaredLength !== null && /^[0-9]+$/.test(declaredLength)
                    && Number(declaredLength) > MAX_RESPONSE_BYTES) {
                cancelBody(response.body);
                return Promise.reject(new Error('response exceeds size limit'));
            }
            if (!response.body) return Promise.resolve(new ArrayBuffer(0));
            if (typeof response.body.getReader !== 'function') {
                cancelBody(response.body);
                return Promise.reject(new Error('response stream unavailable'));
            }

            var reader = response.body.getReader();
            var chunks = [];
            var total = 0;
            request.reader = reader;

            function readNext() {
                return reader.read().then(function (result) {
                    if (result.done) {
                        var buffer = new ArrayBuffer(total);
                        var target = new Uint8Array(buffer);
                        var offset = 0;
                        chunks.forEach(function (chunk) {
                            target.set(chunk, offset);
                            offset += chunk.byteLength;
                        });
                        return buffer;
                    }
                    if (!(result.value instanceof Uint8Array)) {
                        throw new Error('invalid response stream');
                    }
                    total += result.value.byteLength;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new Error('response exceeds size limit');
                    }
                    chunks.push(result.value);
                    return readNext();
                });
            }

            return readNext().catch(function (error) {
                cancelRequest(request);
                throw error;
            }).finally(function () {
                if (request.reader === reader) request.reader = null;
                try { reader.releaseLock(); } catch (_) { /* the body is already closed */ }
            });
        }

        function handleStorageSet(record, data) {
            if (typeof data.key !== 'string' || data.key.length > MAX_STORAGE_KEY_LENGTH
                    || !record.capabilities.writeKeys.has(data.key)
                    || !(typeof data.value === 'string' || data.value === null)
                    || (data.value !== null && byteLength(data.value) > MAX_BODY_BYTES)) return;
            try {
                if (data.value === null) global.localStorage.removeItem(data.key);
                else global.localStorage.setItem(data.key, data.value);
            } catch (_) {
                return;
            }
            publishStorage(data.key, data.value);
        }

        function requestHeaders(value) {
            if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
            var result = {};
            var names = Object.keys(value);
            if (names.length > 2) return null;
            for (var i = 0; i < names.length; i++) {
                if (names[i].length > 32) return null;
                var name = names[i].toLowerCase();
                if ((name !== 'accept' && name !== 'content-type') || typeof value[names[i]] !== 'string'
                        || value[names[i]].length > 256) return null;
                result[name] = value[names[i]];
            }
            return result;
        }

        function handleFetch(record, data) {
            var id = typeof data.id === 'string' ? data.id : '';
            if (!id || id.length > 64 || record.inflight.has(id) || record.inflight.size >= 8) {
                if (id) rejectRequest(record, id, 'request limit exceeded');
                return;
            }
            var rawMethod = typeof data.method === 'string' ? data.method : '';
            var rawUrl = typeof data.url === 'string' ? data.url : '';
            var body = data.body == null ? null : data.body;
            if (rawMethod.length > 8 || rawUrl.length > MAX_URL_LENGTH
                    || (body !== null && (typeof body !== 'string' || body.length > MAX_BODY_BYTES))) {
                rejectRequest(record, id, 'request capability denied');
                return;
            }
            var method = rawMethod.toUpperCase();
            var headers = requestHeaders(data.headers);
            var url;
            try {
                url = new URL(rawUrl, global.location.origin);
            } catch (_) {
                rejectRequest(record, id, 'invalid request URL');
                return;
            }
            var allowed = method === 'GET' ? record.capabilities.getPaths
                : method === 'POST' ? record.capabilities.postPaths : null;
            if (!allowed || !allowed.has(url.pathname) || url.origin !== global.location.origin
                    || url.hash || !headers
                    || (method === 'GET' && body !== null)
                    || (method === 'POST' && (typeof body !== 'string'
                        || byteLength(body) > MAX_BODY_BYTES
                        || headers['content-type'] !== 'application/json'))) {
                rejectRequest(record, id, 'request capability denied');
                return;
            }
            var request = {controller: new AbortController(), reader: null};
            record.inflight.set(id, request);
            var init = {
                method: method,
                headers: headers,
                credentials: 'same-origin',
                cache: 'no-store',
                redirect: 'error',
                signal: request.controller.signal
            };
            if (body !== null) init.body = body;
            global.fetch(url.pathname + url.search, init).then(function (response) {
                return readResponseBody(response, request).then(function (buffer) {
                    var responseHeaders = {};
                    var contentType = response.headers.get('content-type');
                    if (contentType) responseHeaders['content-type'] = contentType;
                    send(record, {
                        type: FETCH_RESPONSE_TYPE,
                        id: id,
                        ok: true,
                        status: response.status,
                        statusText: response.statusText,
                        headers: responseHeaders,
                        body: buffer
                    }, [buffer]);
                });
            }).catch(function (error) {
                rejectRequest(record, id, error && error.name === 'AbortError'
                    ? 'request aborted' : 'request failed');
            }).finally(function () {
                record.inflight.delete(id);
            });
        }

        function handlePortMessage(record, event) {
            var data = event && event.data;
            if (!active(record) || !data || typeof data !== 'object') return;
            if (data.type === FETCH_CANCEL_TYPE && typeof data.id === 'string' && data.id.length <= 64) {
                var request = record.inflight.get(data.id);
                if (request) cancelRequest(request);
                return;
            }
            if (data.type === FETCH_TYPE) {
                handleFetch(record, data);
                return;
            }
            if (data.type === STORAGE_SET_TYPE) {
                handleStorageSet(record, data);
                return;
            }
            if (!UI_MESSAGE_TYPES.has(data.type) || !messageWithinLimit(data)
                    || typeof options.onMessage !== 'function') return;
            Promise.resolve(options.onMessage(record.frame, data)).catch(function () {});
        }

        function closeConnection(record) {
            record.inflight.forEach(cancelRequest);
            record.inflight.clear();
            if (record.port) {
                try { record.port.close(); } catch (_) { /* already closed */ }
                record.port = null;
            }
        }

        function attach(frame, source) {
            var capabilities = parseCapabilities(source);
            if (!frame || !capabilities || typeof MessageChannel !== 'function') return false;
            detach(frame);
            var record = {
                frame: frame,
                capabilities: capabilities,
                inflight: new Map(),
                port: null,
                load: null
            };
            record.load = function () {
                if (records.get(frame) !== record || !frame.contentWindow) return;
                closeConnection(record);
                var channel = new MessageChannel();
                record.port = channel.port1;
                record.port.onmessage = function (event) { handlePortMessage(record, event); };
                if (typeof record.port.start === 'function') record.port.start();
                var storage = {};
                capabilities.readKeys.forEach(function (key) {
                    try { storage[key] = global.localStorage.getItem(key); } catch (_) { storage[key] = null; }
                });
                try {
                    frame.contentWindow.postMessage({
                        type: INIT_TYPE,
                        readKeys: Array.from(capabilities.readKeys),
                        writeKeys: Array.from(capabilities.writeKeys),
                        storage: storage
                    }, '*', [channel.port2]);
                } catch (_) {
                    closeConnection(record);
                }
            };
            records.set(frame, record);
            frame.addEventListener('load', record.load);
            return true;
        }

        function detach(frame) {
            var record = records.get(frame);
            if (!record) return;
            records.delete(frame);
            frame.removeEventListener('load', record.load);
            closeConnection(record);
        }

        function publishStorage(key, value) {
            if (typeof key !== 'string' || !(typeof value === 'string' || value === null)) return;
            records.forEach(function (record) {
                if (active(record) && record.capabilities.readKeys.has(key)) {
                    send(record, {type: STORAGE_UPDATE_TYPE, key: key, value: value});
                }
            });
        }

        function handleStorageEvent(event) {
            if (event && typeof event.key === 'string') publishStorage(event.key, event.newValue);
        }

        function destroy() {
            Array.from(records.keys()).forEach(detach);
        }

        return Object.freeze({
            attach: attach,
            detach: detach,
            publishStorage: publishStorage,
            handleStorageEvent: handleStorageEvent,
            destroy: destroy
        });
    }

    if (global.parent && global.parent !== global) {
        childReady = new Promise(function (resolve) { resolveChildReady = resolve; });
        global.addEventListener('message', acceptParent);
        if (nativeFetch) global.fetch = bridgedFetch;
    }

    global.PixivSurveyFrameBridge = Object.freeze({
        createHost: createHost,
        ready: function () {
            return childReady || Promise.reject(new Error('survey frame bridge requires an iframe'));
        },
        post: function (data) {
            return childPort && data && typeof data === 'object' && UI_MESSAGE_TYPES.has(data.type)
                && messageWithinLimit(data) && safePost(childPort, data);
        }
    });
})(window);
