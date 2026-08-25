'use strict';
(function (global) {
    global.PixivBatch = global.PixivBatch || {};
    const modules = global.PixivBatch.queueTypeRuntimeModules
        || (global.PixivBatch.queueTypeRuntimeModules = {});
    modules.normalize = Object.freeze({
        install(ctx) {
            const {
                CONTRACT_VERSION, KNOWN_MODES, QUEUE_TAG_ID_PATTERN, MAX_QUEUE_TAGS,
                MAX_QUEUE_TAG_LABEL_LENGTH, QUEUE_LIVE_STATUS_TONES,
                MAX_QUEUE_LIVE_STATUS_LABEL_LENGTH, MAX_QUEUE_LIVE_STATUS_MESSAGE_LENGTH,
                MAX_CANCEL_WORK_KEY_LENGTH, EMPTY_QUEUE_TAGS, SLOT_MODE
            } = ctx;
    function text(value) {
        return value == null ? '' : String(value).trim();
    }

    function opaqueText(value) {
        return value == null ? '' : String(value);
    }

    // 作品身份是 workType + 不透明 workId。每个 UTF-16 code unit 固定编码为 4 位十六进制，
    // 既不会因分隔符产生碰撞，也不会把引号、斜杠或 HTML 片段带进 DOM attribute / selector。
    function encodedQueueIdentityPart(value) {
        const raw = opaqueText(value);
        let encoded = '';
        for (let index = 0; index < raw.length; index++) {
            encoded += raw.charCodeAt(index).toString(16).padStart(4, '0');
        }
        return encoded;
    }

    function queueKey(itemOrType, workId) {
        const item = itemOrType && typeof itemOrType === 'object' ? itemOrType : null;
        const type = item
            ? text(item.workType != null ? item.workType : item.kind)
            : text(itemOrType);
        const id = item
            ? opaqueText(item.workId != null ? item.workId : item.id)
            : opaqueText(workId);
        return `q:${encodedQueueIdentityPart(type)}.${encodedQueueIdentityPart(id)}`;
    }

    function normalizedCancelWorkKey(value) {
        if (typeof value !== 'string' || value.length === 0
            || value.length > MAX_CANCEL_WORK_KEY_LENGTH || value.trim() === '') {
            return null;
        }
        return value;
    }

    function isPlainObject(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
        const proto = Object.getPrototypeOf(value);
        return proto === Object.prototype || proto === null;
    }

    function freezeJsonValue(value) {
        if (!value || typeof value !== 'object') return value;
        if (Array.isArray(value)) {
            value.forEach(freezeJsonValue);
            return Object.freeze(value);
        }
        Object.keys(value).forEach(key => freezeJsonValue(value[key]));
        return Object.freeze(value);
    }

    // 队列展示钩子只读取一个与持久化模型断开的中性快照，不能借渲染修改宿主队列；
    // 不复制消息、下载明细等高频大字段，避免大队列每次进度刷新都克隆完整行模型。
    function queueItemSnapshot(value) {
        const raw = value && typeof value === 'object' ? value : {};
        const workType = text(raw.workType != null ? raw.workType : raw.kind);
        const workId = opaqueText(raw.workId != null ? raw.workId : raw.id);
        try {
            const json = JSON.stringify({
                id: workId,
                kind: workType,
                workId,
                workType,
                queueKey: queueKey(workType, workId),
                source: raw.source,
                typeData: raw.typeData || raw.pluginData || null,
                xRestrict: raw.xRestrict,
                isAi: raw.isAi === true,
                ugoiraProgress: raw.ugoiraProgress || null,
                liveStatus: raw.liveStatus || null
            });
            if (json && json.length <= 131072) {
                return freezeJsonValue(JSON.parse(json));
            }
        } catch (e) {
            // 非 JSON 值或循环引用降级为只含中性身份的快照。
        }
        return Object.freeze({
            id: workId,
            kind: workType,
            workId,
            workType,
            queueKey: queueKey(workType, workId),
            source: text(raw.source),
            typeData: null,
            liveStatus: null
        });
    }

    function normalizedQueueTags(value) {
        if (!Array.isArray(value)) return EMPTY_QUEUE_TAGS;
        const seen = new Set();
        const tags = [];
        value.some(candidate => {
            if (!isPlainObject(candidate)) return false;
            const id = text(candidate.id).toLowerCase();
            const label = text(candidate.label);
            if (!QUEUE_TAG_ID_PATTERN.test(id) || !label
                || label.length > MAX_QUEUE_TAG_LABEL_LENGTH || seen.has(id)) {
                return false;
            }
            seen.add(id);
            tags.push(Object.freeze({id, label}));
            return tags.length >= MAX_QUEUE_TAGS;
        });
        return tags.length ? Object.freeze(tags) : EMPTY_QUEUE_TAGS;
    }

    function normalizedQueueLiveStatus(value) {
        if (!isPlainObject(value)) return null;
        const label = text(value.label);
        const message = text(value.message);
        const tone = text(value.tone).toLowerCase();
        if (!label || label.length > MAX_QUEUE_LIVE_STATUS_LABEL_LENGTH
            || !message || message.length > MAX_QUEUE_LIVE_STATUS_MESSAGE_LENGTH
            || !QUEUE_LIVE_STATUS_TONES.has(tone)) {
            return null;
        }
        return Object.freeze({label, message, tone});
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
        const ownerPluginId = text(owner.pluginId);
        const packageId = text(owner.packageId);
        const moduleUrl = normalizedModuleUrl(raw.moduleUrl);
        const contractVersion = Number(raw.contractVersion);
        const pluginGeneration = Number(owner.generation);
        const publicationId = Number(owner.publicationId);
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
        const descriptor = {
            contractVersion,
            type,
            displayNamespace: text(raw.displayNamespace),
            displayI18nKey: text(raw.displayI18nKey),
            order: Number.isFinite(order) ? order : 0,
            iconKey: text(raw.iconKey),
            colorToken: text(raw.colorToken),
            moduleUrl,
            acquisitionModes: Object.freeze(acquisitionModes),
            cancelSupported: raw.cancelSupported === true,
            filters: Object.freeze(Array.isArray(raw.filters) ? raw.filters.map(text).filter(Boolean) : []),
            settings: Object.freeze(Array.isArray(raw.settings) ? raw.settings.map(text).filter(Boolean) : []),
            i18nNamespace: text(raw.i18nNamespace),
            ownerPluginId,
            packageId,
            pluginGeneration,
            publicationId
        };
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
        return {epoch, revision, identity, manifest, orderedTypes, uiSlots};
    }


            Object.assign(ctx, {
                text, opaqueText, encodedQueueIdentityPart, queueKey, normalizedCancelWorkKey,
                isPlainObject, freezeJsonValue, queueItemSnapshot, normalizedQueueTags,
                normalizedQueueLiveStatus, normalizedModuleUrl, normalizedEndpoint,
                sanitizedRequestHeaders, normalizeType, normalizeUiSlot, normalizeManifest
            });
        }
    });
})(window);