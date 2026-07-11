'use strict';

const GALLERY_FRONTEND_DESCRIPTOR_URL = '/api/gallery/unified/descriptors';
const GALLERY_FRONTEND_MEDIA_KINDS = new Set([
    'IMAGE', 'VIDEO', 'LIVE_PHOTO_VIDEO', 'TEXT', 'COVER', 'UGOIRA', 'UNKNOWN'
]);
const GALLERY_FRONTEND_HOOK_METHODS = Object.freeze({
    FILTER_EXTENSION: 'registerFilterExtension',
    CARD_EXTENSION: 'registerCardExtension',
    MEDIA_RENDERER: 'registerMediaRenderer',
    DETAIL_ACTION: 'registerDetailAction'
});
const GALLERY_FRONTEND_FAILURE_TYPES = new Set([
    'Error', 'TypeError', 'RangeError', 'SyntaxError', 'ReferenceError', 'URIError',
    'EvalError', 'AbortError', 'NetworkError', 'GalleryHttpError'
]);

let galleryFrontendSnapshot = {
    generation: -1,
    projections: [],
    works: [],
    frontends: [],
    diagnostics: []
};
let galleryFrontendDiagnostics = [];
let galleryFrontendI18n = null;
let galleryFrontendLanguage = null;
let galleryFrontendActivation = 0;
let galleryFrontendPendingModuleUrl = null;
let galleryFrontendRefreshPromise = null;
const galleryFrontendModuleDefinitions = new Map();
const galleryFrontendHandlers = {
    FILTER_EXTENSION: new Map(),
    CARD_EXTENSION: new Map(),
    MEDIA_RENDERER: new Map(),
    DETAIL_ACTION: new Map()
};

function galleryFrontendDiagnostic(code, detail) {
    const item = Object.assign({
        code: String(code),
        generation: galleryFrontendSnapshot.generation
    }, detail || {});
    galleryFrontendDiagnostics.push(item);
    if (window.console && typeof window.console.warn === 'function') {
        window.console.warn('[gallery frontend] ' + item.code, item);
    }
    return item;
}

function galleryFrontendFailureDetails(failure) {
    let candidate = '';
    try {
        candidate = failure && typeof failure.name === 'string' ? failure.name : '';
    } catch (_) {
        candidate = '';
    }
    const detail = {
        failureType: GALLERY_FRONTEND_FAILURE_TYPES.has(candidate) ? candidate : 'UnknownFailure'
    };
    let status = Number.NaN;
    try {
        status = failure && Number(failure.httpStatus);
    } catch (_) {
        status = Number.NaN;
    }
    if (Number.isInteger(status) && status >= 100 && status <= 599) detail.httpStatus = status;
    return detail;
}

function galleryFrontendNormalizeLocalUrl(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null;
    if (/[\u0000-\u0020\\]/.test(value)) return null;
    const rawPath = value.split(/[?#]/, 1)[0].toLowerCase();
    if (/%(?:2e|2f|5c|00|25)/.test(rawPath)) return null;
    if (rawPath.split('/').some(part => part === '.' || part === '..')) return null;
    try {
        const resolved = new URL(value, window.location.origin);
        if (resolved.origin !== window.location.origin || resolved.username || resolved.password || resolved.hash) {
            return null;
        }
        if (!resolved.pathname.startsWith('/') || resolved.pathname.startsWith('//')
            || resolved.pathname.includes('//')) return null;
        const lower = resolved.pathname.toLowerCase();
        if (lower.includes('%2e') || lower.includes('%2f') || lower.includes('%5c') || lower.includes('%00')) {
            return null;
        }
        if (resolved.pathname.split('/').some(part => part === '.' || part === '..')) return null;
        return resolved.pathname + resolved.search;
    } catch (_) {
        return null;
    }
}

function galleryFrontendSafeAssetUrl(value) {
    const local = galleryFrontendNormalizeLocalUrl(value);
    return local == null ? null : new URL(local, window.location.origin).href;
}

function galleryFrontendRegisterModule(moduleUrl, initializer) {
    const normalized = galleryFrontendNormalizeLocalUrl(moduleUrl);
    if (!normalized || typeof initializer !== 'function') {
        galleryFrontendDiagnostic('invalid-module-registration', {moduleUrl: String(moduleUrl || '')});
        return false;
    }
    if (galleryFrontendPendingModuleUrl && galleryFrontendPendingModuleUrl !== normalized) {
        galleryFrontendDiagnostic('module-url-mismatch', {
            moduleUrl: normalized,
            expectedModuleUrl: galleryFrontendPendingModuleUrl
        });
        return false;
    }
    if (galleryFrontendModuleDefinitions.has(normalized)) {
        galleryFrontendDiagnostic('duplicate-module-definition', {moduleUrl: normalized});
        return false;
    }
    galleryFrontendModuleDefinitions.set(normalized, initializer);
    return true;
}

function galleryFrontendArray(value) {
    return Array.isArray(value) ? value.filter(item => item != null) : [];
}

function galleryFrontendSafeDiagnosticToken(value, fallback) {
    const token = value == null ? '' : String(value);
    return /^[a-zA-Z0-9._:-]{1,120}$/.test(token) ? token : fallback;
}

function galleryFrontendNormalizeServerDiagnostic(value) {
    const raw = value && typeof value === 'object' ? value : {};
    const diagnostic = {
        code: galleryFrontendSafeDiagnosticToken(raw.code, 'provider-diagnostic')
    };
    const providerId = galleryFrontendSafeDiagnosticToken(raw.providerId, null);
    const sourceId = galleryFrontendSafeDiagnosticToken(raw.sourceId, null);
    const kind = galleryFrontendSafeDiagnosticToken(raw.kind, null);
    if (providerId) diagnostic.providerId = providerId;
    if (sourceId) diagnostic.sourceId = sourceId;
    if (kind) diagnostic.kind = kind;
    return diagnostic;
}

function galleryFrontendNormalizeSnapshot(value) {
    const raw = value && typeof value === 'object' ? value : {};
    const generation = Number.isSafeInteger(raw.generation) ? raw.generation : 0;
    const projections = galleryFrontendArray(raw.projections).slice().sort((left, right) =>
        Number(left.order || 0) - Number(right.order || 0)
        || String(left.sourceId || '').localeCompare(String(right.sourceId || ''))
        || String(left.kind || '').localeCompare(String(right.kind || '')));
    const frontends = galleryFrontendArray(raw.frontends).slice().sort((left, right) =>
        Number(left.order || 0) - Number(right.order || 0)
        || String(left.contributionId || '').localeCompare(String(right.contributionId || '')));
    return {
        generation,
        projections,
        works: galleryFrontendArray(raw.works),
        frontends,
        diagnostics: galleryFrontendArray(raw.diagnostics).map(galleryFrontendNormalizeServerDiagnostic)
    };
}

function galleryFrontendNamespaces(snapshot) {
    const namespaces = new Set(['gallery']);
    snapshot.projections.forEach(item => {
        if (item && typeof item.displayNamespace === 'string' && item.displayNamespace.trim()) {
            namespaces.add(item.displayNamespace.trim());
        }
    });
    snapshot.frontends.forEach(item => {
        if (item && typeof item.displayNamespace === 'string' && item.displayNamespace.trim()) {
            namespaces.add(item.displayNamespace.trim());
        }
    });
    return Array.from(namespaces);
}

async function galleryFrontendLoadI18n(snapshot, language) {
    if (!window.PixivI18n || typeof window.PixivI18n.create !== 'function') {
        galleryFrontendI18n = null;
        return;
    }
    try {
        galleryFrontendI18n = await window.PixivI18n.create({
            lang: language || galleryFrontendLanguage || undefined,
            namespaces: galleryFrontendNamespaces(snapshot)
        });
        galleryFrontendLanguage = galleryFrontendI18n.lang || language || galleryFrontendLanguage;
    } catch (failure) {
        galleryFrontendI18n = null;
        galleryFrontendDiagnostic('i18n-load-failed', galleryFrontendFailureDetails(failure));
    }
}

function galleryFrontendTranslate(key, params, fallback) {
    const resolvedFallback = fallback == null ? String(key || '') : String(fallback);
    if (galleryFrontendI18n && typeof galleryFrontendI18n.t === 'function') {
        return galleryFrontendI18n.t(String(key || ''), resolvedFallback, params);
    }
    return resolvedFallback;
}

function galleryFrontendScope(item) {
    const value = item && item.scope && typeof item.scope === 'object' ? item.scope : {};
    return {
        sourceIds: galleryFrontendArray(value.sourceIds).map(String),
        sourceWorkNamespaces: galleryFrontendArray(value.sourceWorkNamespaces).map(String),
        galleryKinds: galleryFrontendArray(value.galleryKinds).map(String),
        mediaKinds: galleryFrontendArray(value.mediaKinds).map(String)
    };
}

function galleryFrontendDimensionMatches(expected, actual) {
    return expected.length === 0 || actual != null && expected.includes(String(actual));
}

function galleryFrontendIdentity(context) {
    const card = context && context.card;
    const work = context && context.work;
    const keyHolder = card && card.key ? card.key : work && work.key ? work.key : {};
    const workKey = keyHolder.workKey || keyHolder || work && work.workKey || {};
    const mediaKinds = [];
    if (context && context.media && context.media.kind) mediaKinds.push(String(context.media.kind));
    galleryFrontendArray(card && card.containedMediaKinds).forEach(kind => mediaKinds.push(String(kind)));
    galleryFrontendArray(work && work.media).forEach(media => {
        if (media && media.kind) mediaKinds.push(String(media.kind));
    });
    return {
        sourceId: workKey.sourceId || context && context.sourceId || null,
        namespace: workKey.sourceWorkNamespace || context && context.sourceWorkNamespace || null,
        galleryKind: keyHolder.kind || context && context.galleryKind || null,
        mediaKinds: Array.from(new Set(mediaKinds))
    };
}

function galleryFrontendScopeMatches(contribution, identity) {
    const expected = galleryFrontendScope(contribution);
    if (!galleryFrontendDimensionMatches(expected.sourceIds, identity.sourceId)
        || !galleryFrontendDimensionMatches(expected.sourceWorkNamespaces, identity.namespace)
        || !galleryFrontendDimensionMatches(expected.galleryKinds, identity.galleryKind)) return false;
    return expected.mediaKinds.length === 0
        || identity.mediaKinds.some(kind => expected.mediaKinds.includes(kind));
}

function galleryFrontendContributionHasHook(contribution, hook) {
    return galleryFrontendArray(contribution && contribution.hooks).map(String).includes(hook);
}

function galleryFrontendContributionForHandler(moduleUrl, hook, id) {
    return galleryFrontendSnapshot.frontends.find(item =>
        item && item.contributionId === id
        && galleryFrontendNormalizeLocalUrl(item.moduleUrl) === moduleUrl
        && galleryFrontendContributionHasHook(item, hook));
}

function galleryFrontendRegisterHandler(moduleUrl, hook, definition) {
    const id = definition && typeof definition.id === 'string' ? definition.id.trim() : '';
    if (!id || !definition || typeof definition.render !== 'function') {
        galleryFrontendDiagnostic('invalid-handler-registration', {moduleUrl, hook, contributionId: id});
        return false;
    }
    const contribution = galleryFrontendContributionForHandler(moduleUrl, hook, id);
    if (!contribution) {
        galleryFrontendDiagnostic('undeclared-handler', {moduleUrl, hook, contributionId: id});
        return false;
    }
    const registry = galleryFrontendHandlers[hook];
    if (registry.has(id)) {
        galleryFrontendDiagnostic('duplicate-handler-id', {moduleUrl, hook, contributionId: id});
        return false;
    }
    let mediaKinds = [];
    if (hook === 'MEDIA_RENDERER') {
        mediaKinds = galleryFrontendArray(definition.mediaKinds).map(String);
        if (mediaKinds.length === 0 || mediaKinds.some(kind => !GALLERY_FRONTEND_MEDIA_KINDS.has(kind))) {
            galleryFrontendDiagnostic('invalid-media-kinds', {moduleUrl, hook, contributionId: id});
            return false;
        }
        const declared = galleryFrontendScope(contribution).mediaKinds;
        if (declared.length > 0 && mediaKinds.some(kind => !declared.includes(kind))) {
            galleryFrontendDiagnostic('media-kind-outside-scope', {moduleUrl, hook, contributionId: id});
            return false;
        }
    }
    registry.set(id, {id, render: definition.render, mediaKinds, contribution});
    return true;
}

function galleryFrontendModuleApi(moduleUrl) {
    return Object.freeze({
        registerFilterExtension(definition) {
            return galleryFrontendRegisterHandler(moduleUrl, 'FILTER_EXTENSION', definition);
        },
        registerCardExtension(definition) {
            return galleryFrontendRegisterHandler(moduleUrl, 'CARD_EXTENSION', definition);
        },
        registerMediaRenderer(definition) {
            return galleryFrontendRegisterHandler(moduleUrl, 'MEDIA_RENDERER', definition);
        },
        registerDetailAction(definition) {
            return galleryFrontendRegisterHandler(moduleUrl, 'DETAIL_ACTION', definition);
        }
    });
}

function galleryFrontendHandlerSnapshot() {
    return Object.fromEntries(Object.entries(galleryFrontendHandlers)
        .map(([hook, registry]) => [hook, new Set(registry.keys())]));
}

function galleryFrontendRollbackHandlers(snapshot) {
    Object.entries(galleryFrontendHandlers).forEach(([hook, registry]) => {
        const existing = snapshot[hook] || new Set();
        Array.from(registry.keys()).forEach(id => {
            if (!existing.has(id)) registry.delete(id);
        });
    });
}

function galleryFrontendLoadModule(moduleUrl, activation) {
    return new Promise(resolve => {
        const normalized = galleryFrontendNormalizeLocalUrl(moduleUrl);
        if (!normalized) {
            galleryFrontendDiagnostic('unsafe-module-url', {moduleUrl: String(moduleUrl || '')});
            resolve(false);
            return;
        }
        const script = document.createElement('script');
        script.async = false;
        script.dataset.galleryFrontendModule = normalized;
        const separator = normalized.includes('?') ? '&' : '?';
        script.src = normalized + separator + '_galleryGeneration=' + encodeURIComponent(
            String(galleryFrontendSnapshot.generation));
        script.onload = () => {
            if (galleryFrontendPendingModuleUrl === normalized) galleryFrontendPendingModuleUrl = null;
            if (activation !== galleryFrontendActivation) {
                script.remove();
                resolve(false);
                return;
            }
            const initializer = galleryFrontendModuleDefinitions.get(normalized);
            if (!initializer) {
                galleryFrontendDiagnostic('module-registration-missing', {moduleUrl: normalized});
                script.remove();
                resolve(false);
                return;
            }
            const handlersBeforeInitializer = galleryFrontendHandlerSnapshot();
            try {
                initializer(galleryFrontendModuleApi(normalized));
                resolve(true);
            } catch (failure) {
                galleryFrontendRollbackHandlers(handlersBeforeInitializer);
                galleryFrontendDiagnostic('module-initializer-failed', Object.assign({
                    moduleUrl: normalized,
                }, galleryFrontendFailureDetails(failure)));
                resolve(false);
            } finally {
                if (galleryFrontendPendingModuleUrl === normalized) galleryFrontendPendingModuleUrl = null;
                script.remove();
            }
        };
        script.onerror = failure => {
            if (galleryFrontendPendingModuleUrl === normalized) galleryFrontendPendingModuleUrl = null;
            galleryFrontendDiagnostic('module-load-failed', Object.assign({
                moduleUrl: normalized,
            }, galleryFrontendFailureDetails(failure)));
            script.remove();
            resolve(false);
        };
        galleryFrontendPendingModuleUrl = normalized;
        try {
            document.head.appendChild(script);
        } catch (failure) {
            if (galleryFrontendPendingModuleUrl === normalized) galleryFrontendPendingModuleUrl = null;
            galleryFrontendDiagnostic('module-load-failed', Object.assign({
                moduleUrl: normalized
            }, galleryFrontendFailureDetails(failure)));
            resolve(false);
        }
    });
}

function galleryFrontendClearRuntime() {
    Object.values(galleryFrontendHandlers).forEach(registry => registry.clear());
    galleryFrontendModuleDefinitions.clear();
    galleryFrontendPendingModuleUrl = null;
}

async function galleryFrontendActivateSnapshot(rawSnapshot, options) {
    const next = galleryFrontendNormalizeSnapshot(rawSnapshot);
    const activation = ++galleryFrontendActivation;
    galleryFrontendClearRuntime();
    galleryFrontendSnapshot = next;
    galleryFrontendDiagnostics = next.diagnostics.map(item => Object.assign({}, item));
    await galleryFrontendLoadI18n(next, options && options.language);
    if (activation !== galleryFrontendActivation) return galleryFrontendSnapshot;

    const moduleUrls = [];
    next.frontends.forEach(item => {
        const hasRuntimeHook = Object.keys(GALLERY_FRONTEND_HOOK_METHODS)
            .some(hook => galleryFrontendContributionHasHook(item, hook));
        const normalized = galleryFrontendNormalizeLocalUrl(item.moduleUrl);
        if (hasRuntimeHook && normalized && !moduleUrls.includes(normalized)) moduleUrls.push(normalized);
    });
    for (const moduleUrl of moduleUrls) {
        if (activation !== galleryFrontendActivation) break;
        await galleryFrontendLoadModule(moduleUrl, activation);
    }
    return galleryFrontendSnapshot;
}

async function galleryFrontendRequestDescriptors() {
    const response = await fetch(GALLERY_FRONTEND_DESCRIPTOR_URL, {
        credentials: 'same-origin',
        headers: {'Accept': 'application/json'}
    });
    if (!response.ok) {
        const failure = new Error('descriptor request failed');
        failure.name = 'GalleryHttpError';
        failure.httpStatus = response.status;
        throw failure;
    }
    return response.json();
}

async function galleryFrontendBootstrap(options) {
    const config = options || {};
    try {
        const next = config.snapshot || await galleryFrontendRequestDescriptors();
        return await galleryFrontendActivateSnapshot(next, config);
    } catch (failure) {
        galleryFrontendDiagnostic('descriptor-load-failed', galleryFrontendFailureDetails(failure));
        return galleryFrontendSnapshot;
    }
}

async function galleryFrontendRefresh(options) {
    if (galleryFrontendRefreshPromise) return galleryFrontendRefreshPromise;
    const config = options || {};
    galleryFrontendRefreshPromise = (async () => {
        try {
            const next = galleryFrontendNormalizeSnapshot(await galleryFrontendRequestDescriptors());
            if (!config.force && next.generation === galleryFrontendSnapshot.generation) return galleryFrontendSnapshot;
            return galleryFrontendActivateSnapshot(next, config);
        } catch (failure) {
            galleryFrontendDiagnostic('descriptor-refresh-failed', galleryFrontendFailureDetails(failure));
            return galleryFrontendSnapshot;
        } finally {
            galleryFrontendRefreshPromise = null;
        }
    })();
    return galleryFrontendRefreshPromise;
}

function galleryFrontendContext(context) {
    const source = context || {};
    let work = source.work || source.card || null;
    if (work && work.author && !work.actor) work = Object.assign({}, work, {actor: work.author});
    return Object.assign({}, source, {
        work,
        t: (key, params) => galleryFrontendTranslate(key, params, key),
        openDetail: typeof source.openDetail === 'function' ? source.openDetail : function () {}
    });
}

function galleryFrontendHandlersFor(hook, context) {
    const identity = galleryFrontendIdentity(context);
    return Array.from(galleryFrontendHandlers[hook].values())
        .filter(handler => galleryFrontendScopeMatches(handler.contribution, identity))
        .sort((left, right) => Number(left.contribution.order || 0) - Number(right.contribution.order || 0)
            || left.id.localeCompare(right.id));
}

function galleryFrontendIsNode(value) {
    return value && typeof value === 'object' && typeof value.nodeType === 'number';
}

function galleryFrontendRenderExtensions(hook, context) {
    const safeContext = galleryFrontendContext(context);
    let count = 0;
    galleryFrontendHandlersFor(hook, safeContext).forEach(handler => {
        try {
            const node = handler.render(safeContext);
            if (node === false || node == null) return;
            if (!galleryFrontendIsNode(node)) {
                galleryFrontendDiagnostic('handler-return-invalid', {hook, contributionId: handler.id});
                return;
            }
            safeContext.host.appendChild(node);
            count++;
        } catch (failure) {
            galleryFrontendDiagnostic('handler-render-failed', Object.assign({
                hook,
                contributionId: handler.id
            }, galleryFrontendFailureDetails(failure)));
        }
    });
    return count;
}

function galleryFrontendRenderFilterExtensions(context) {
    return galleryFrontendRenderExtensions('FILTER_EXTENSION', context);
}

function galleryFrontendRenderCardExtensions(context) {
    return galleryFrontendRenderExtensions('CARD_EXTENSION', context);
}

function galleryFrontendRenderDetailActions(context) {
    return galleryFrontendRenderExtensions('DETAIL_ACTION', context);
}

function galleryFrontendMediaUnavailable(documentRef, key) {
    const node = documentRef.createElement('div');
    node.className = 'gallery-media-fallback gallery-media-unavailable';
    node.textContent = galleryFrontendTranslate(key || 'gallery:frontend.media.unavailable', null,
        key || 'gallery:frontend.media.unavailable');
    return node;
}

function galleryFrontendRenderStandardMedia(input) {
    const context = input || {};
    const media = context.media;
    const host = context.host;
    const documentRef = host && host.ownerDocument ? host.ownerDocument : window.document;
    if (!media || !documentRef || typeof documentRef.createElement !== 'function') return null;
    const kind = GALLERY_FRONTEND_MEDIA_KINDS.has(String(media.kind)) ? String(media.kind) : 'UNKNOWN';
    const url = galleryFrontendSafeAssetUrl(media.url);
    const thumbnail = galleryFrontendSafeAssetUrl(media.thumbnailUrl);
    if (kind === 'VIDEO' || kind === 'LIVE_PHOTO_VIDEO') {
        if (!url) return galleryFrontendMediaUnavailable(documentRef);
        const video = documentRef.createElement('video');
        video.className = 'gallery-media gallery-media-video gallery-media-' + kind.toLowerCase();
        video.controls = true;
        video.preload = 'metadata';
        video.src = url;
        if (thumbnail) video.poster = thumbnail;
        video.setAttribute('aria-label', galleryFrontendTranslate(
            'gallery:frontend.media.' + kind.toLowerCase().replaceAll('_', '-'), null, kind));
        return video;
    }
    if (kind === 'IMAGE' || kind === 'COVER' || kind === 'UGOIRA') {
        const source = url || thumbnail;
        if (!source) return galleryFrontendMediaUnavailable(documentRef);
        const image = documentRef.createElement('img');
        image.className = 'gallery-media gallery-media-image gallery-media-' + kind.toLowerCase();
        image.loading = 'lazy';
        image.src = source;
        image.alt = galleryFrontendTranslate(
            'gallery:frontend.media.' + kind.toLowerCase(), null, kind);
        return image;
    }
    if (kind === 'TEXT') {
        const article = documentRef.createElement('article');
        article.className = 'gallery-media gallery-media-text';
        const content = documentRef.createElement('pre');
        content.className = 'gallery-media-text-content';
        content.textContent = media.content == null ? galleryFrontendTranslate(
            'gallery:frontend.media.text-empty', null, 'gallery:frontend.media.text-empty') : String(media.content);
        article.appendChild(content);
        return article;
    }
    const unknown = documentRef.createElement('article');
    unknown.className = 'gallery-media gallery-media-fallback gallery-media-unknown';
    const machineKind = documentRef.createElement('code');
    machineKind.className = 'gallery-media-machine-kind';
    machineKind.textContent = 'UNKNOWN';
    unknown.appendChild(machineKind);
    const label = documentRef.createElement('p');
    label.textContent = galleryFrontendTranslate('gallery:frontend.media.unknown', null,
        'gallery:frontend.media.unknown');
    unknown.appendChild(label);
    if (media.content != null && String(media.content)) {
        const content = documentRef.createElement('pre');
        content.textContent = String(media.content);
        unknown.appendChild(content);
    }
    return unknown;
}

function galleryFrontendRenderMedia(context) {
    const safeContext = galleryFrontendContext(context);
    const mediaKind = safeContext.media && String(safeContext.media.kind || '');
    for (const handler of galleryFrontendHandlersFor('MEDIA_RENDERER', safeContext)) {
        if (!handler.mediaKinds.includes(mediaKind)) continue;
        try {
            const node = handler.render(safeContext);
            if (galleryFrontendIsNode(node)) return node;
            if (node !== false && node != null) {
                galleryFrontendDiagnostic('handler-return-invalid', {
                    hook: 'MEDIA_RENDERER', contributionId: handler.id
                });
            }
        } catch (failure) {
            galleryFrontendDiagnostic('handler-render-failed', Object.assign({
                hook: 'MEDIA_RENDERER',
                contributionId: handler.id
            }, galleryFrontendFailureDetails(failure)));
        }
    }
    return galleryFrontendRenderStandardMedia(safeContext);
}

async function galleryFrontendSetLanguage(language) {
    galleryFrontendLanguage = language || galleryFrontendLanguage;
    await galleryFrontendLoadI18n(galleryFrontendSnapshot, galleryFrontendLanguage);
    return galleryFrontendI18n;
}

window.PixivGalleryFrontend = Object.assign(window.PixivGalleryFrontend || {}, {
    registerModule: galleryFrontendRegisterModule,
    bootstrap: galleryFrontendBootstrap,
    refresh: galleryFrontendRefresh,
    setLanguage: galleryFrontendSetLanguage,
    renderFilterExtensions: galleryFrontendRenderFilterExtensions,
    renderCardExtensions: galleryFrontendRenderCardExtensions,
    renderDetailActions: galleryFrontendRenderDetailActions,
    renderMedia: galleryFrontendRenderMedia,
    renderStandardMedia: galleryFrontendRenderStandardMedia,
    diagnostics: () => galleryFrontendDiagnostics.slice(),
    generation: () => galleryFrontendSnapshot.generation,
    snapshot: () => galleryFrontendSnapshot,
    t: galleryFrontendTranslate
});
