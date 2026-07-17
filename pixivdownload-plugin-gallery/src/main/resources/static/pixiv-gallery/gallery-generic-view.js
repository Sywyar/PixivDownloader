'use strict';

const GALLERY_GENERIC_KINDS = new Set(['IMAGE', 'VIDEO', 'NOVEL']);
const GALLERY_GENERIC_SORT_FIELDS = new Set(['CREATED_AT', 'DOWNLOADED_AT', 'UPDATED_AT', 'TITLE']);
const GALLERY_GENERIC_SORT_DIRECTIONS = new Set(['ASC', 'DESC']);
const GALLERY_GENERIC_FILTER_FIELDS = Object.freeze(['author', 'tag', 'ai', 'rating', 'media']);
let galleryGenericRevision = 0;
let galleryGenericState = {
    active: false,
    hosts: null,
    galleryKind: null,
    sourceId: null,
    cursor: null,
    nextCursor: null,
    hasMore: false,
    cards: [],
    detailWork: null,
    detailHint: null,
    filters: null,
    loading: false,
    revision: 0
};

function galleryGenericIsRequest(search) {
    const params = new URLSearchParams(search == null ? window.location.search : String(search));
    return params.has('galleryKind') || params.has('sourceId');
}

function galleryGenericElement(id) {
    return document && typeof document.getElementById === 'function' ? document.getElementById(id) : null;
}

function galleryGenericResolveHosts(options) {
    const config = options || {};
    return {
        grid: config.grid || galleryGenericElement('galleryGrid'),
        status: config.status || galleryGenericElement('galleryStatus'),
        pagination: config.pagination || galleryGenericElement('pagination'),
        detail: config.detail || galleryGenericElement('galleryGenericDetail'),
        filters: config.filters || galleryGenericElement('galleryGenericFilters')
    };
}

function galleryGenericSelection(search) {
    const params = new URLSearchParams(search == null ? window.location.search : String(search));
    const requestedKind = String(params.get('galleryKind') || '').toUpperCase();
    const sourceId = params.get('sourceId') || null;
    const projections = window.PixivGalleryFrontend.snapshot().projections || [];
    let galleryKind = GALLERY_GENERIC_KINDS.has(requestedKind) ? requestedKind : null;
    if (!galleryKind) {
        const first = projections.find(item => !sourceId || item.sourceId === sourceId);
        galleryKind = first && GALLERY_GENERIC_KINDS.has(String(first.kind)) ? String(first.kind) : null;
    }
    return {galleryKind, sourceId};
}

async function galleryGenericRequestJson(url) {
    const response = await fetch(url, {
        credentials: 'same-origin',
        headers: {'Accept': 'application/json'}
    });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    return response.json();
}

function galleryGenericText(key, fallback, params) {
    return window.PixivGalleryFrontend.t('gallery:' + key, params, fallback);
}

function galleryGenericSetStatus(hosts, key, fallback) {
    if (!hosts || !hosts.status) return;
    hosts.status.hidden = false;
    hosts.status.textContent = galleryGenericText(key, fallback);
}

function galleryGenericSafeLocalUrl(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
        || /[\u0000-\u0020\\]/.test(value)) return null;
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
        return resolved.href;
    } catch (_) {
        return null;
    }
}

function galleryGenericWorkKey(value) {
    if (!value || typeof value !== 'object') return null;
    if (value.key && value.key.workKey) return value.key.workKey;
    if (value.key && value.key.sourceId) return value.key;
    return value.workKey || null;
}

function galleryGenericMediaLabel(kind) {
    const normalized = String(kind || 'UNKNOWN').toLowerCase().replaceAll('_', '-');
    return galleryGenericText('frontend.media.' + normalized, String(kind || 'UNKNOWN'));
}

function galleryGenericRenderStandardCard(context) {
    const input = context || {};
    const card = input.card || input.work;
    const host = input.host;
    const documentRef = host && host.ownerDocument ? host.ownerDocument : document;
    if (!card || !documentRef || typeof documentRef.createElement !== 'function') return null;

    const article = documentRef.createElement('article');
    article.className = 'work-card gallery-generic-card';
    const key = galleryGenericWorkKey(card);
    if (key) {
        article.dataset.sourceId = String(key.sourceId || '');
        article.dataset.workNamespace = String(key.sourceWorkNamespace || '');
        article.dataset.workId = String(key.sourceWorkId || '');
    }

    const button = documentRef.createElement('button');
    button.type = 'button';
    button.className = 'gallery-generic-card-open';
    button.setAttribute('aria-label', galleryGenericText('frontend.action.open-detail', 'Open details'));
    const thumbnail = documentRef.createElement('span');
    thumbnail.className = 'work-thumb gallery-generic-thumb';
    const thumbnailUrl = galleryGenericSafeLocalUrl(card.thumbnailUrl);
    if (thumbnailUrl) {
        const image = documentRef.createElement('img');
        image.loading = 'lazy';
        image.src = thumbnailUrl;
        image.alt = card.title == null ? '' : String(card.title);
        thumbnail.appendChild(image);
    } else {
        const placeholder = documentRef.createElement('span');
        placeholder.className = 'gallery-generic-thumb-placeholder';
        placeholder.textContent = galleryGenericMediaLabel(
            card.key && card.key.kind ? card.key.kind : 'UNKNOWN');
        thumbnail.appendChild(placeholder);
    }
    const body = documentRef.createElement('span');
    body.className = 'gallery-generic-card-body';
    const title = documentRef.createElement('strong');
    title.className = 'gallery-generic-card-title';
    title.textContent = card.title == null
        ? galleryGenericText('frontend.card.untitled', 'Untitled') : String(card.title);
    body.appendChild(title);
    const author = card.author || card.actor;
    if (author && author.name) {
        const authorName = documentRef.createElement('span');
        authorName.className = 'gallery-generic-card-author';
        authorName.textContent = String(author.name);
        body.appendChild(authorName);
    }
    const kinds = Array.isArray(card.containedMediaKinds) ? card.containedMediaKinds : [];
    if (kinds.length) {
        const badges = documentRef.createElement('span');
        badges.className = 'gallery-generic-card-kinds';
        kinds.forEach(kind => {
            const badge = documentRef.createElement('span');
            badge.className = 'gallery-generic-kind-badge';
            badge.textContent = galleryGenericMediaLabel(kind);
            badges.appendChild(badge);
        });
        body.appendChild(badges);
    }
    button.appendChild(thumbnail);
    button.appendChild(body);
    button.addEventListener('click', () => {
        if (typeof input.openDetail === 'function') input.openDetail(card);
    });
    article.appendChild(button);

    const extensionHost = documentRef.createElement('div');
    extensionHost.className = 'gallery-generic-card-extensions';
    window.PixivGalleryFrontend.renderCardExtensions({
        work: card,
        card,
        host: extensionHost,
        galleryKind: card.key && card.key.kind,
        openDetail: input.openDetail
    });
    if (extensionHost.children.length) article.appendChild(extensionHost);
    return article;
}

function galleryGenericNormalizeFilterValues(value) {
    if (!Array.isArray(value)) return [];
    return Array.from(new Set(value.map(item => String(item).trim()).filter(Boolean))).slice(0, 100);
}

function galleryGenericDefaultFilters() {
    return {
        sort: 'DOWNLOADED_AT',
        direction: 'DESC',
        author: [], tag: [], ai: [], rating: [], media: []
    };
}

function galleryGenericFilterSnapshot() {
    const current = galleryGenericState.filters || {};
    const snapshot = {
        galleryKind: galleryGenericState.galleryKind,
        sourceId: galleryGenericState.sourceId,
        sort: current.sort || 'DOWNLOADED_AT',
        direction: current.direction || 'DESC'
    };
    GALLERY_GENERIC_FILTER_FIELDS.forEach(field => {
        snapshot[field] = Object.freeze(galleryGenericNormalizeFilterValues(current[field]));
    });
    return Object.freeze(snapshot);
}

function galleryGenericUpdateFilters(update, value) {
    const patch = typeof update === 'string' ? {[update]: value} : update;
    if (!patch || typeof patch !== 'object') return Promise.resolve(null);
    const current = galleryGenericFilterSnapshot();
    const next = {
        sort: GALLERY_GENERIC_SORT_FIELDS.has(String(patch.sort)) ? String(patch.sort) : current.sort,
        direction: GALLERY_GENERIC_SORT_DIRECTIONS.has(String(patch.direction))
            ? String(patch.direction) : current.direction
    };
    GALLERY_GENERIC_FILTER_FIELDS.forEach(field => {
        next[field] = Object.prototype.hasOwnProperty.call(patch, field)
            ? galleryGenericNormalizeFilterValues(patch[field]) : Array.from(current[field]);
    });
    galleryGenericState.filters = next;
    galleryGenericState.nextCursor = null;
    galleryGenericState.hasMore = false;
    return galleryGenericLoadPage(null, false);
}

function galleryGenericFilterContext() {
    const filters = galleryGenericFilterSnapshot();
    return Object.freeze({
        filter: filters,
        filters,
        setFilter(update, value) {
            return galleryGenericUpdateFilters(update, value);
        },
        reload() { return galleryGenericLoadPage(null, false); }
    });
}

function galleryGenericRenderFilters() {
    const hosts = galleryGenericState.hosts;
    if (!hosts || !hosts.filters) return;
    hosts.filters.replaceChildren();
    hosts.filters.hidden = false;
    const filterContext = galleryGenericFilterContext();
    window.PixivGalleryFrontend.renderFilterExtensions({
        work: null,
        host: hosts.filters,
        filter: filterContext.filter,
        filters: filterContext.filters,
        setFilter: filterContext.setFilter,
        reload: filterContext.reload,
        galleryKind: galleryGenericState.galleryKind,
        sourceId: galleryGenericState.sourceId,
        openDetail: galleryGenericOpenDetail
    });
    hosts.filters.hidden = hosts.filters.children.length === 0;
}

function galleryGenericProjectionUrl(cursor) {
    const filters = galleryGenericFilterSnapshot();
    const params = new URLSearchParams();
    params.set('kind', galleryGenericState.galleryKind);
    if (galleryGenericState.sourceId) params.set('sourceId', galleryGenericState.sourceId);
    GALLERY_GENERIC_FILTER_FIELDS.forEach(field => {
        filters[field].forEach(value => params.append(field, value));
    });
    params.set('sort', filters.sort);
    params.set('direction', filters.direction);
    params.set('limit', '50');
    if (cursor) params.set('cursor', cursor);
    return '/api/gallery/unified/projections?' + params.toString();
}

function galleryGenericRenderCards() {
    const hosts = galleryGenericState.hosts;
    if (!hosts || !hosts.grid) return;
    hosts.grid.replaceChildren();
    galleryGenericState.cards.forEach(card => {
        const node = galleryGenericRenderStandardCard({
            work: card,
            card,
            host: hosts.grid,
            openDetail: galleryGenericOpenDetail
        });
        if (node) hosts.grid.appendChild(node);
    });
    hosts.grid.hidden = false;
    if (hosts.status) {
        hosts.status.hidden = galleryGenericState.cards.length > 0;
        if (!galleryGenericState.cards.length) {
            hosts.status.textContent = galleryGenericText('frontend.status.empty', 'No works found');
        }
    }
    galleryGenericRenderPagination();
}

function galleryGenericRenderPagination() {
    const hosts = galleryGenericState.hosts;
    if (!hosts || !hosts.pagination) return;
    hosts.pagination.replaceChildren();
    if (!galleryGenericState.hasMore || !galleryGenericState.nextCursor) return;
    const button = hosts.pagination.ownerDocument.createElement('button');
    button.type = 'button';
    button.className = 'btn gallery-generic-load-more';
    button.textContent = galleryGenericText('frontend.action.load-more', 'Load more');
    button.addEventListener('click', () => galleryGenericLoadPage(galleryGenericState.nextCursor, true));
    hosts.pagination.appendChild(button);
}

async function galleryGenericLoadPage(cursor, append) {
    if (galleryGenericState.loading || !galleryGenericState.galleryKind) return null;
    const revision = galleryGenericState.revision;
    galleryGenericState.loading = true;
    galleryGenericSetStatus(galleryGenericState.hosts, 'frontend.status.loading', 'Loading…');
    try {
        const page = await galleryGenericRequestJson(galleryGenericProjectionUrl(cursor));
        if (revision !== galleryGenericState.revision) return null;
        const cards = Array.isArray(page && page.projections) ? page.projections : [];
        galleryGenericState.cards = append ? galleryGenericState.cards.concat(cards) : cards.slice();
        galleryGenericState.nextCursor = page && page.nextCursor || null;
        galleryGenericState.hasMore = !!(page && page.hasMore);
        galleryGenericRenderCards();
        return page;
    } catch (failure) {
        if (revision === galleryGenericState.revision) {
            galleryGenericSetStatus(galleryGenericState.hosts, 'frontend.status.failed', 'Unable to load gallery');
        }
        return null;
    } finally {
        if (revision === galleryGenericState.revision) galleryGenericState.loading = false;
    }
}

function galleryGenericDetailUrl(key) {
    return '/api/gallery/unified/works/' + encodeURIComponent(String(key.sourceId)) + '/'
        + encodeURIComponent(String(key.sourceWorkNamespace)) + '/'
        + encodeURIComponent(String(key.sourceWorkId));
}

function galleryGenericShowList() {
    const hosts = galleryGenericState.hosts;
    galleryGenericState.detailWork = null;
    galleryGenericState.detailHint = null;
    if (hosts.detail) {
        hosts.detail.hidden = true;
        hosts.detail.replaceChildren();
    }
    if (hosts.grid) hosts.grid.hidden = false;
    if (hosts.filters) hosts.filters.hidden = hosts.filters.children.length === 0;
    galleryGenericRenderCards();
}

function galleryGenericRenderDetail(work) {
    const hosts = galleryGenericState.hosts;
    if (!hosts || !hosts.detail) return;
    const root = hosts.detail;
    const documentRef = root.ownerDocument;
    root.replaceChildren();
    root.hidden = false;
    if (hosts.grid) hosts.grid.hidden = true;
    if (hosts.status) hosts.status.hidden = true;
    if (hosts.pagination) hosts.pagination.replaceChildren();
    if (hosts.filters) hosts.filters.hidden = true;

    const toolbar = documentRef.createElement('div');
    toolbar.className = 'gallery-generic-detail-toolbar';
    const back = documentRef.createElement('button');
    back.type = 'button';
    back.className = 'btn gallery-generic-back';
    back.textContent = galleryGenericText('frontend.action.back', 'Back to gallery');
    back.addEventListener('click', galleryGenericShowList);
    toolbar.appendChild(back);
    root.appendChild(toolbar);

    const header = documentRef.createElement('header');
    header.className = 'gallery-generic-detail-header';
    const title = documentRef.createElement('h1');
    title.textContent = work.title == null
        ? galleryGenericText('frontend.card.untitled', 'Untitled') : String(work.title);
    header.appendChild(title);
    if (work.author && work.author.name) {
        const author = documentRef.createElement('p');
        author.className = 'gallery-generic-detail-author';
        author.textContent = String(work.author.name);
        header.appendChild(author);
    }
    if (work.description) {
        const description = documentRef.createElement('p');
        description.className = 'gallery-generic-detail-description';
        description.textContent = String(work.description);
        header.appendChild(description);
    }
    root.appendChild(header);

    const actionHost = documentRef.createElement('div');
    actionHost.className = 'gallery-generic-detail-actions';
    window.PixivGalleryFrontend.renderDetailActions({
        work,
        host: actionHost,
        detailRoot: root,
        galleryKind: galleryGenericState.galleryKind,
        openDetail: galleryGenericOpenDetail
    });
    if (actionHost.children.length) root.appendChild(actionHost);

    const mediaRoot = documentRef.createElement('section');
    mediaRoot.className = 'gallery-generic-detail-media';
    const detailHint = galleryGenericState.detailHint || {};
    mediaRoot.dataset.galleryKind = String(detailHint.galleryKind || galleryGenericState.galleryKind || '');
    const mediaItems = (Array.isArray(work.media) ? work.media : []).map((media, index) => ({media, index}));
    mediaItems.sort((left, right) => {
        const leftPreferred = left.media && left.media.key
            && left.media.key.mediaId === detailHint.preferredMediaId;
        const rightPreferred = right.media && right.media.key
            && right.media.key.mediaId === detailHint.preferredMediaId;
        return Number(rightPreferred) - Number(leftPreferred) || left.index - right.index;
    }).forEach(item => {
        const media = item.media;
        const host = documentRef.createElement('div');
        host.className = 'gallery-generic-media-host';
        const mediaId = media && media.key && media.key.mediaId;
        if (mediaId != null) host.dataset.mediaId = String(mediaId);
        if (mediaId != null && String(mediaId) === String(detailHint.preferredMediaId || '')) {
            host.classList.add('gallery-generic-media-preferred');
            host.setAttribute('tabindex', '-1');
        }
        const node = window.PixivGalleryFrontend.renderMedia({
            work,
            media,
            host,
            detailRoot: root,
            galleryKind: galleryGenericState.galleryKind,
            openDetail: galleryGenericOpenDetail
        });
        if (node) host.appendChild(node);
        mediaRoot.appendChild(host);
    });
    root.appendChild(mediaRoot);
}

async function galleryGenericOpenDetail(cardOrWork) {
    const key = galleryGenericWorkKey(cardOrWork);
    if (!key) return null;
    const revision = galleryGenericState.revision;
    galleryGenericState.detailHint = {
        preferredMediaId: cardOrWork && cardOrWork.preferredMediaId || null,
        galleryKind: cardOrWork && cardOrWork.key && cardOrWork.key.kind
            || galleryGenericState.galleryKind
    };
    galleryGenericSetStatus(galleryGenericState.hosts, 'frontend.status.detail-loading', 'Loading details…');
    try {
        const response = await galleryGenericRequestJson(galleryGenericDetailUrl(key));
        if (revision !== galleryGenericState.revision) return null;
        if (!response || !response.work) throw new Error('missing work');
        galleryGenericState.detailWork = response.work;
        galleryGenericRenderDetail(response.work);
        return response.work;
    } catch (failure) {
        if (revision === galleryGenericState.revision) {
            galleryGenericSetStatus(galleryGenericState.hosts, 'frontend.status.detail-failed',
                'Unable to load details');
        }
        return null;
    }
}

async function galleryGenericStart(options) {
    const config = options || {};
    const selection = galleryGenericSelection(config.search);
    galleryGenericState = {
        active: true,
        hosts: galleryGenericResolveHosts(config),
        galleryKind: selection.galleryKind,
        sourceId: selection.sourceId,
        cursor: null,
        nextCursor: null,
        hasMore: false,
        cards: [],
        detailWork: null,
        detailHint: null,
        filters: galleryGenericDefaultFilters(),
        loading: false,
        revision: ++galleryGenericRevision
    };
    if (document.body && document.body.classList) document.body.classList.add('gallery-generic-mode');
    const hosts = galleryGenericState.hosts;
    if (hosts.detail) hosts.detail.hidden = true;
    galleryGenericRenderFilters();
    if (!selection.galleryKind) {
        galleryGenericSetStatus(hosts, 'frontend.status.unavailable', 'This view is unavailable');
        return null;
    }
    return galleryGenericLoadPage(null, false);
}

function galleryGenericProjectionAvailable() {
    const snapshot = window.PixivGalleryFrontend.snapshot();
    const projections = snapshot && Array.isArray(snapshot.projections) ? snapshot.projections : [];
    return projections.some(item => item
        && String(item.kind || '') === galleryGenericState.galleryKind
        && (!galleryGenericState.sourceId || item.sourceId === galleryGenericState.sourceId));
}

async function galleryGenericRefreshSnapshot() {
    if (!galleryGenericState.active) return null;
    galleryGenericState.revision = ++galleryGenericRevision;
    galleryGenericState.loading = false;
    galleryGenericState.cards = [];
    galleryGenericState.nextCursor = null;
    galleryGenericState.hasMore = false;
    galleryGenericState.detailWork = null;
    galleryGenericState.detailHint = null;
    galleryGenericState.filters = galleryGenericDefaultFilters();

    const hosts = galleryGenericState.hosts;
    if (hosts.detail) {
        hosts.detail.hidden = true;
        hosts.detail.replaceChildren();
    }
    if (hosts.grid) {
        hosts.grid.hidden = false;
        hosts.grid.replaceChildren();
    }
    if (hosts.pagination) hosts.pagination.replaceChildren();
    galleryGenericRenderFilters();

    if (!galleryGenericProjectionAvailable()) {
        galleryGenericSetStatus(hosts, 'frontend.status.unavailable', 'This view is unavailable');
        return null;
    }
    return galleryGenericLoadPage(null, false);
}

async function galleryGenericStartDataFlow(options) {
    const config = options || {};
    if (!galleryGenericIsRequest(config.search)) {
        return typeof config.loadPrimary === 'function' ? config.loadPrimary() : null;
    }
    return galleryGenericStart(Object.assign({}, config.generic || {}, {search: config.search}));
}

function galleryGenericRerender() {
    if (!galleryGenericState.active) return;
    galleryGenericRenderFilters();
    if (galleryGenericState.detailWork) galleryGenericRenderDetail(galleryGenericState.detailWork);
    else galleryGenericRenderCards();
}

window.PixivGalleryFrontend = Object.assign(window.PixivGalleryFrontend || {}, {
    isGenericRequest: galleryGenericIsRequest,
    startDataFlow: galleryGenericStartDataFlow,
    startGeneric: galleryGenericStart,
    openDetail: galleryGenericOpenDetail,
    rerenderGeneric: galleryGenericRerender,
    refreshGeneric: galleryGenericRefreshSnapshot,
    renderStandardCard: galleryGenericRenderStandardCard
});
