/* Captures one illust descriptor publication for the lifetime of this page.
 * A stale page must fail closed instead of rebinding cancellation to a replacement.
 */
function createIllustExactCancelClient(options) {
    const QUEUE_TYPE = 'illust';
    const MAX_WORK_KEY_LENGTH = 4096;
    const MAX_OWNER_ID_LENGTH = 512;
    const REQUEST_TIMEOUT_MS = 5000;
    const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
    const settings = options || {};
    const request = settings.request;
    if (typeof request !== 'function') {
        throw new TypeError('userscript request function is required');
    }

    const capturedServerBase = normalizeServerBase(settings.initialServerBase);
    const currentServerBase = typeof settings.currentServerBase === 'function'
        ? settings.currentServerBase
        : () => settings.initialServerBase;
    const currentOwnerUuid = typeof settings.currentOwnerUuid === 'function'
        ? settings.currentOwnerUuid
        : () => null;
    const onFailure = typeof settings.onFailure === 'function'
        ? settings.onFailure
        : () => {};
    const publicationPromise = capturePublication();

    function normalizeServerBase(value) {
        return String(value == null ? '' : value).trim().replace(/\/+$/, '');
    }

    function text(value) {
        return value == null ? '' : String(value).trim();
    }

    function error(code, status) {
        const failure = new Error(code);
        failure.code = code;
        if (Number.isFinite(status)) failure.status = status;
        return failure;
    }

    function send(init) {
        return new Promise((resolve, reject) => {
            let settled = false;
            const complete = callback => value => {
                if (settled) return;
                settled = true;
                callback(value);
            };
            const onload = complete(resolve);
            const onerror = complete(() => reject(error('QUEUE_CANCEL_NETWORK_ERROR')));
            const ontimeout = complete(() => reject(error('QUEUE_CANCEL_TIMEOUT')));
            try {
                request(Object.assign({}, init, {onload, onerror, ontimeout}));
            } catch (requestError) {
                if (!settled) {
                    settled = true;
                    reject(requestError);
                }
            }
        });
    }

    function parsePayload(response) {
        try {
            return JSON.parse(response && typeof response.responseText === 'string'
                ? response.responseText
                : '');
        } catch (parseError) {
            return null;
        }
    }

    function successfulStatus(response) {
        const status = Number(response && response.status);
        return Number.isInteger(status) && status >= 200 && status < 300;
    }

    function selectPublication(payload) {
        const downloadTypes = payload && Array.isArray(payload.downloadTypes)
            ? payload.downloadTypes
            : [];
        const descriptor = downloadTypes.find(candidate =>
            candidate && text(candidate.type) === QUEUE_TYPE && candidate.cancelSupported === true);
        const owner = descriptor && descriptor.owner;
        const pluginId = text(owner && owner.pluginId);
        const packageId = text(owner && owner.packageId);
        const generation = Number(owner && owner.generation);
        const publicationId = Number(owner && owner.publicationId);
        if (!pluginId || pluginId.length > MAX_OWNER_ID_LENGTH
            || !packageId || packageId.length > MAX_OWNER_ID_LENGTH
            || !Number.isSafeInteger(generation) || generation < 0
            || !Number.isSafeInteger(publicationId) || publicationId <= 0) {
            return null;
        }
        return Object.freeze({pluginId, packageId, generation, publicationId});
    }

    function capturePublication() {
        if (!capturedServerBase) return Promise.resolve(null);
        return send({
            method: 'GET',
            url: capturedServerBase + '/api/download/extensions',
            headers: {'Accept': 'application/json'},
            timeout: REQUEST_TIMEOUT_MS
        }).then(response => {
            if (!successfulStatus(response)) return null;
            return selectPublication(parsePayload(response));
        }, () => null);
    }

    async function cancel(workKey) {
        try {
            const normalizedWorkKey = String(workKey == null ? '' : workKey);
            if (!normalizedWorkKey.trim() || normalizedWorkKey.length > MAX_WORK_KEY_LENGTH) {
                throw error('QUEUE_CANCEL_REQUEST_INVALID');
            }
            const publication = await publicationPromise;
            if (!publication) {
                throw error('QUEUE_CANCEL_DESCRIPTOR_UNAVAILABLE');
            }
            if (normalizeServerBase(currentServerBase()) !== capturedServerBase) {
                throw error('QUEUE_CANCEL_DESCRIPTOR_STALE');
            }

            const headers = {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            };
            const ownerUuid = text(currentOwnerUuid());
            if (UUID_PATTERN.test(ownerUuid)) headers['X-User-UUID'] = ownerUuid;
            const response = await send({
                method: 'POST',
                url: capturedServerBase + '/api/download/queue/' + QUEUE_TYPE + '/cancel',
                headers,
                data: JSON.stringify({
                    workKey: normalizedWorkKey,
                    owner: publication
                }),
                timeout: REQUEST_TIMEOUT_MS
            });
            const payload = parsePayload(response);
            if (!successfulStatus(response) || !payload || payload.success !== true) {
                const responseCode = text(payload && payload.code);
                throw error(responseCode || 'QUEUE_CANCEL_FAILED', Number(response && response.status));
            }
            return true;
        } catch (failure) {
            try {
                onFailure(failure);
            } catch (callbackError) {
                // A presentation failure must not turn a rejected cancellation into success.
            }
            return false;
        }
    }

    return Object.freeze({cancel});
}
