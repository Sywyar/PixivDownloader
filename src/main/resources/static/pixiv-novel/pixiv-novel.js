const params = new URLSearchParams(location.search);
const novelId = params.get('id');
let pageI18n;
let rerenderPayload = null;
let cachedSeriesNav = null;

const HEART_DEFAULT_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>';

const collectionState = {
    list: [],
    membership: new Set()
};

async function loadAll() {
    pageI18n = await PixivI18n.create({ namespaces: ['novel', 'common'] });
    pageI18n.apply();
    await PixivLangSwitcher.mount({
        mountPoint: document.getElementById('langSwitcherAnchor'),
        i18n: pageI18n,
        onChange: (next) => {
            pageI18n = next;
            pageI18n.apply();
            rerenderDynamic();
        }
    });
    PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') });
    if (!novelId) {
        document.getElementById('loading').textContent = pageI18n.t('status.missing-id');
        return;
    }
    document.getElementById('btn-pixiv').href = `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(novelId)}`;
    try {
        await loadNovel();
        await loadSeriesNav();
        document.getElementById('loading').style.display = 'none';
        document.getElementById('root').style.display = 'block';
        loadCollectionState();
    } catch (e) {
        document.getElementById('loading').textContent = pageI18n.t('status.novel-load-failed', null, { message: String(e && e.message ? e.message : e) });
    }
}

async function loadNovel() {
    const r = await fetch(`/api/gallery/novel/${encodeURIComponent(novelId)}`);
    if (!r.ok) {
        // Fallback: try fetching from Pixiv proxy
        const remote = await fetch(`/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`);
        if (!remote.ok) throw new Error(`HTTP ${r.status}`);
        const meta = await remote.json();
        renderRemote(meta);
        return;
    }
    const view = await r.json();
    await renderLocal(view);
}

function showCover() {
    const wrap = document.getElementById('novel-cover');
    const img = document.getElementById('novel-cover-img');
    img.src = `/api/gallery/novel/${encodeURIComponent(novelId)}/cover`;
    wrap.style.display = 'block';
}

function rerenderDynamic() {
    if (!rerenderPayload) return;
    const d = rerenderPayload.data;
    if (rerenderPayload.kind === 'remote') {
        renderBadges({ xRestrict: d.xRestrict, isAi: d.isAi, isOriginal: d.isOriginal });
        renderMetaRow({ wordCount: d.wordCount, textLength: d.textLength, readingTimeSeconds: d.readingTimeSeconds, language: d.language, uploadTimestamp: d.uploadTimestamp });
        renderDescription(d.description);
        renderTags(d.tags || []);
    } else {
        renderBadges({ xRestrict: d.xRestrict, isAi: d.isAi, isOriginal: d.isOriginal });
        renderMetaRow({ wordCount: d.wordCount, textLength: d.textLength, readingTimeSeconds: d.readingTimeSeconds, language: d.xLanguage, uploadTimestamp: d.time });
        renderDescription(d.description);
        renderTags(d.tags || []);
    }
    if (cachedSeriesNav) {
        renderSeriesNavSet(cachedSeriesNav, { wrap: 'series-nav', prev: 'series-prev', index: 'series-index', next: 'series-next' });
        renderSeriesNavSet(cachedSeriesNav, { wrap: 'series-nav-bottom', prev: 'series-prev-bottom', index: 'series-index-bottom', next: 'series-next-bottom' });
    }
    updateHeart();
}

function renderRemote(meta) {
    rerenderPayload = { kind: 'remote', data: meta };
    if (meta.coverUrl) showCover();
    document.getElementById('novel-title').textContent = meta.title || '';
    document.getElementById('novel-author').innerHTML = meta.authorId
        ? `<a href="https://www.pixiv.net/users/${meta.authorId}" target="_blank">${escapeHtml(meta.authorName || '')}</a>`
        : escapeHtml(meta.authorName || pageI18n.t('status.unknown-author'));
    renderBadges({ xRestrict: meta.xRestrict, isAi: meta.isAi, isOriginal: meta.isOriginal });
    renderMetaRow({
        wordCount: meta.wordCount,
        textLength: meta.textLength,
        readingTimeSeconds: meta.readingTimeSeconds,
        language: meta.language,
        uploadTimestamp: meta.uploadTimestamp
    });
    renderDescription(meta.description);
    renderTags(meta.tags || []);
    renderContent(meta.content || '', buildImageResolver({ remote: meta.textEmbeddedImages || {} }));
}

async function renderLocal(view) {
    rerenderPayload = { kind: 'local', data: view };
    if (view.coverExt) showCover();
    document.getElementById('novel-title').textContent = view.title || '';
    document.getElementById('novel-author').innerHTML = view.authorId
        ? `<a href="https://www.pixiv.net/users/${view.authorId}" target="_blank">${escapeHtml(view.authorName || '')}</a>`
        : escapeHtml(view.authorName || pageI18n.t('status.unknown-author'));
    renderBadges({ xRestrict: view.xRestrict, isAi: view.isAi, isOriginal: view.isOriginal });
    renderMetaRow({
        wordCount: view.wordCount,
        textLength: view.textLength,
        readingTimeSeconds: view.readingTimeSeconds,
        language: view.xLanguage,
        uploadTimestamp: view.time
    });
    renderDescription(view.description);
    renderTags(view.tags || []);
    // Local view doesn't include content; need a fresh fetch
    const localIds = new Set(view.embeddedImageIds || []);
    try {
        const remote = await fetch(`/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`);
        if (remote.ok) {
            const meta = await remote.json();
            renderContent(meta.content || '', buildImageResolver({
                local: localIds,
                remote: meta.textEmbeddedImages || {}
            }));
            return;
        }
    } catch {}
    document.getElementById('content-card').innerHTML = `<div class="empty">${pageI18n.t('status.load-failed')}</div>`;
}

function renderBadges(o) {
    const parts = [];
    if (o.xRestrict === 1) parts.push('<span class="badge r18">R-18</span>');
    if (o.xRestrict === 2) parts.push('<span class="badge r18g">R-18G</span>');
    if (o.isAi) parts.push('<span class="badge ai">AI</span>');
    if (o.isOriginal) parts.push(`<span class="badge original">${escapeHtml(pageI18n.t('meta.original'))}</span>`);
    document.getElementById('novel-badges').innerHTML = parts.join('');
}

function formatReadingDuration(seconds) {
    const total = Math.floor(Number(seconds || 0));
    if (!Number.isFinite(total) || total <= 0) return '';
    const hour = Math.floor(total / 3600);
    const minute = Math.floor((total % 3600) / 60);
    const second = total % 60;
    const parts = [];
    if (hour > 0) parts.push(pageI18n.t('duration.hour', '{count} 小时', { count: hour }));
    if (minute > 0) parts.push(pageI18n.t('duration.minute', '{count} 分钟', { count: minute }));
    if (second > 0 && hour === 0) parts.push(pageI18n.t('duration.second', '{count} 秒', { count: second }));
    if (!parts.length) parts.push(pageI18n.t('duration.second', '{count} 秒', { count: total }));
    return parts.join(' ');
}

function formatReadingTime(seconds) {
    const text = formatReadingDuration(seconds);
    return text ? pageI18n.t('meta.reading-time', '预计阅读：{time}', { time: text }) : '';
}

function renderMetaRow(o) {
    const parts = [];
    if (o.wordCount != null && o.wordCount > 0) {
        parts.push(`<span>${escapeHtml(pageI18n.t('meta.word-count', null, { count: o.wordCount }))}</span>`);
    }
    if (o.textLength != null && o.textLength > 0 && o.textLength !== o.wordCount) {
        parts.push(`<span>${escapeHtml(pageI18n.t('meta.text-length', null, { count: o.textLength }))}</span>`);
    }
    const readingText = formatReadingTime(o.readingTimeSeconds);
    if (readingText) parts.push(`<span>${escapeHtml(readingText)}</span>`);
    if (o.language) {
        parts.push(`<span>${escapeHtml(pageI18n.t('meta.language', null, { lang: o.language }))}</span>`);
    }
    if (o.uploadTimestamp) {
        const d = new Date(o.uploadTimestamp);
        if (!isNaN(d.getTime())) {
            parts.push(`<span>${escapeHtml(pageI18n.t('meta.upload', null, { time: d.toISOString().slice(0, 16).replace('T', ' ') }))}</span>`);
        }
    }
    document.getElementById('novel-meta-row').innerHTML = parts.join('');
}

function renderDescription(text) {
    const el = document.getElementById('novel-description');
    if (text && text.trim()) {
        // Pixiv 简介本身是受限 HTML（<br>, <a>, <strong> 等），与 pixiv-artwork.html 保持一致
        // 直接用 innerHTML 让 <br>/<br/> 等渲染为真实换行；并把链接强制在新标签页打开。
        el.innerHTML = text;
        el.querySelectorAll('a').forEach(a => {
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
        });
    } else {
        el.textContent = pageI18n.t('status.no-description');
    }
}

function renderTags(tags) {
    if (!tags || tags.length === 0) {
        document.getElementById('novel-tags').innerHTML = `<span class="tag">${escapeHtml(pageI18n.t('status.no-tags'))}</span>`;
        return;
    }
    document.getElementById('novel-tags').innerHTML = tags.map(t => {
        const name = t.name || '';
        const translated = t.translatedName
            ? `<span class="translated">${escapeHtml(t.translatedName)}</span>`
            : '';
        const tagId = Number(t.tagId);
        const canFilter = (Number.isFinite(tagId) && tagId > 0) || name;
        if (!canFilter) {
            return `<span class="tag">${escapeHtml(name)}${translated}</span>`;
        }
        return `<a class="tag tag-link" href="${escapeHtml(buildNovelGalleryTagFilterHref(t))}">${escapeHtml(name || ('#' + tagId))}${translated}</a>`;
    }).join('');
}

function buildNovelGalleryTagFilterHref(tag) {
    const params = new URLSearchParams();
    params.set('view', 'all');
    const tagId = Number(tag.tagId);
    if (Number.isFinite(tagId) && tagId > 0) params.set('tagIds', String(tagId));
    if (tag.name) params.set('tagName', tag.name);
    if (tag.translatedName) params.set('tagTranslatedName', tag.translatedName);
    return `/pixiv-novel-gallery.html?${params.toString()}`;
}

function renderContent(raw, resolver) {
    document.getElementById('content-card').innerHTML = PixivNovelRender.render(raw, resolver);
}

/**
 * 构造内嵌图片解析器：
 * - opts.local: Set<string>，已落盘到本地的 [uploadedimage:id] ID 集合，命中时走本地接口；
 * - opts.remote: { id: pximgUrl }，未在本地的 ID 走 thumbnail-proxy 透传 pximg。
 */
function buildImageResolver(opts) {
    const local = opts && opts.local ? opts.local : null;
    const remote = opts && opts.remote ? opts.remote : {};
    return {
        labels: {
            uploadedImage(id) {
                return pageI18n.t('render.uploaded-image', null, { id });
            },
            pixivImage(id) {
                return pageI18n.t('render.pixiv-image', null, { id });
            }
        },
        uploadedImage(id) {
            if (local && local.has(String(id))) {
                return `/api/gallery/novel/${encodeURIComponent(novelId)}/embed/${encodeURIComponent(id)}`;
            }
            const pximg = remote[id];
            if (pximg) {
                return `/api/pixiv/thumbnail-proxy?url=${encodeURIComponent(pximg)}`;
            }
            return null;
        },
        pixivImage() { return null; }
    };
}

async function loadSeriesNav() {
    try {
        const r = await fetch(`/api/gallery/novel/${encodeURIComponent(novelId)}/series`);
        if (!r.ok) return;
        const nav = await r.json();
        if (!nav.seriesId) return;
        cachedSeriesNav = nav;
        renderSeriesNavSet(nav, {
            wrap: 'series-nav',
            prev: 'series-prev',
            index: 'series-index',
            next: 'series-next'
        });
        renderSeriesNavSet(nav, {
            wrap: 'series-nav-bottom',
            prev: 'series-prev-bottom',
            index: 'series-index-bottom',
            next: 'series-next-bottom'
        });
    } catch {}
}

function renderSeriesNavSet(nav, ids) {
    const wrap = document.getElementById(ids.wrap);
    const prev = document.getElementById(ids.prev);
    const next = document.getElementById(ids.next);
    const index = document.getElementById(ids.index);
    if (!wrap || !prev || !next || !index) return;

    wrap.style.display = 'grid';
    if (nav.prev) {
        prev.href = `/pixiv-novel.html?id=${nav.prev.novelId}`;
        prev.classList.remove('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: nav.prev.seriesOrder, title: nav.prev.title });
    } else {
        prev.classList.add('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: '-', title: '' });
    }
    if (nav.next) {
        next.href = `/pixiv-novel.html?id=${nav.next.novelId}`;
        next.classList.remove('disabled-look');
        next.textContent = pageI18n.t('series.next', null, { order: nav.next.seriesOrder, title: nav.next.title });
    } else {
        next.classList.add('disabled-look');
        next.textContent = pageI18n.t('series.next', null, { order: '-', title: '' });
    }
    const indexParams = new URLSearchParams({
        type: 'novel',
        seriesId: String(nav.seriesId),
        currentId: String(novelId)
    });
    if (nav.seriesTitle) indexParams.set('seriesTitle', nav.seriesTitle);
    if (nav.currentOrder != null) indexParams.set('order', String(nav.currentOrder));
    index.href = `/pixiv-series.html?${indexParams.toString()}`;
    index.removeAttribute('target');
    index.removeAttribute('rel');
    index.textContent = pageI18n.t('series.index') + (nav.seriesTitle ? ` · ${nav.seriesTitle}` : '');
}

function escapeHtml(s) {
    return PixivNovelRender.escapeHtml(s);
}

// ---------- Collections ----------
async function loadCollectionState() {
    try {
        const [listRes, ofRes] = await Promise.all([
            fetch('/api/collections', { credentials: 'same-origin' }),
            fetch(`/api/collections/novels/of/${encodeURIComponent(novelId)}`, { credentials: 'same-origin' })
        ]);
        if (listRes.ok) {
            const data = await listRes.json();
            collectionState.list = data.collections || [];
        }
        if (ofRes.ok) {
            const data = await ofRes.json();
            collectionState.membership = new Set(data.collectionIds || []);
        }
        document.getElementById('heartBtn').style.display = '';
        updateHeart();
    } catch (e) {
        // silent — heart stays hidden
    }
}

function updateHeart() {
    const btn = document.getElementById('heartBtn');
    const liked = collectionState.membership.size > 0;
    btn.classList.toggle('liked', liked);
    document.getElementById('heartLabel').textContent = liked
        ? pageI18n.t('button.favorite-active', '已收藏 ({count})', { count: collectionState.membership.size })
        : pageI18n.t('button.favorite', '收藏');
}

function iconHtml(c) {
    return c.iconExt
        ? `<img src="/api/collections/${c.id}/icon?v=${c.createdTime}" alt="">`
        : HEART_DEFAULT_SVG;
}

async function refreshMembership() {
    try {
        const r = await fetch(`/api/collections/novels/of/${encodeURIComponent(novelId)}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        collectionState.membership = new Set(data.collectionIds || []);
        updateHeart();
    } catch (e) {}
}

async function refreshCollections() {
    try {
        const r = await fetch('/api/collections', { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        collectionState.list = data.collections || [];
    } catch (e) {}
}

async function openAddToCollectionModal() {
    await refreshCollections();
    await refreshMembership();
    renderAddToCollectionList();
    document.getElementById('modalAddToCollection').classList.add('open');
}

function closeAddToCollectionModal() {
    document.getElementById('modalAddToCollection').classList.remove('open');
    updateHeart();
}

function renderAddToCollectionList() {
    const list = document.getElementById('addToCollectionList');
    if (!collectionState.list.length) {
        list.innerHTML = `<div style="padding:24px; text-align:center; color:var(--muted); font-size:13px">${escapeHtml(pageI18n.t('status.no-collections', '暂无收藏夹，请先新建'))}</div>`;
        return;
    }
    list.innerHTML = collectionState.list.map(c => {
        const checked = collectionState.membership.has(c.id) ? 'checked' : '';
        return `<label class="collection-row" data-id="${c.id}">
            <input type="checkbox" ${checked}>
            <div class="collection-row-icon">${iconHtml(c)}</div>
            <div class="collection-row-name">${escapeHtml(c.name)}</div>
            <span class="collection-count">${c.novelCount ?? 0}</span>
        </label>`;
    }).join('');
    list.querySelectorAll('.collection-row').forEach(row => {
        const id = Number(row.dataset.id);
        const cb = row.querySelector('input[type="checkbox"]');
        cb.addEventListener('change', async () => {
            try {
                if (cb.checked) {
                    const r = await fetch(`/api/collections/${id}/novels/${encodeURIComponent(novelId)}`, { method: 'POST', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.add(id);
                } else {
                    const r = await fetch(`/api/collections/${id}/novels/${encodeURIComponent(novelId)}`, { method: 'DELETE', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.delete(id);
                }
                updateHeart();
            } catch (e) {
                cb.checked = !cb.checked;
                toast(pageI18n.t('toast.action-failed', '操作失败'), 'error');
            }
        });
    });
}

document.getElementById('heartBtn').addEventListener('click', openAddToCollectionModal);
document.getElementById('addToCollectionClose').addEventListener('click', closeAddToCollectionModal);
document.getElementById('addToCollectionDone').addEventListener('click', closeAddToCollectionModal);
document.getElementById('modalAddToCollection').addEventListener('click', e => {
    if (e.target.id === 'modalAddToCollection') closeAddToCollectionModal();
});

document.getElementById('quickCreateBtn').addEventListener('click', async () => {
    const input = document.getElementById('quickCreateName');
    const name = input.value.trim();
    if (!name) return;
    const btn = document.getElementById('quickCreateBtn');
    btn.disabled = true;
    try {
        const r = await fetch('/api/collections', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        input.value = '';
        collectionState.list.push(c);
        collectionState.list.sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id));
        const link = await fetch(`/api/collections/${c.id}/novels/${encodeURIComponent(novelId)}`, { method: 'POST', credentials: 'same-origin' });
        if (link.ok) collectionState.membership.add(c.id);
        renderAddToCollectionList();
        toast(pageI18n.t('toast.created', '已创建'), 'success');
    } catch (e) {
        toast(pageI18n.t('toast.create-failed', '创建失败'), 'error');
    } finally {
        btn.disabled = false;
    }
});

function toast(msg, type) {
    const c = document.getElementById('toastContainer');
    if (!c) return;
    const el = document.createElement('div');
    el.className = 'toast' + (type ? ' ' + type : '');
    el.textContent = msg;
    c.appendChild(el);
    setTimeout(() => el.remove(), 2800);
}

loadAll();
