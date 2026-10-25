'use strict';
let collectionPending = false;
let collectionReadRevision = 0;
let collectionModalRevision = 0;

function syncCollectionControls() {
    document.getElementById('quickCreateBtn').disabled = collectionPending;
    document.getElementById('addToCollectionList').querySelectorAll('.collection-row').forEach(row => {
        const checkbox = row.querySelector('input[type="checkbox"]');
        checkbox.disabled = collectionPending;
        if (!collectionPending) checkbox.checked = collectionState.membership.has(Number(row.dataset.id));
    });
}

function setCollectionPending(pending) {
    collectionPending = pending;
    ++collectionReadRevision;
    ++collectionLoadRevision;
    syncCollectionControls();
}

// ---------- Collections ----------
async function loadCollectionState() {
    await Promise.all([refreshCollections(), refreshMembership()]);
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
    const revision = ++collectionReadRevision;
    try {
        const r = await fetch(`/api/collections/novels/of/${encodeURIComponent(novelId)}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        if (revision !== collectionReadRevision || collectionPending) return;
        collectionState.membership = new Set(data.collectionIds || []);
        document.getElementById('heartBtn').style.display = '';
        syncCollectionControls();
        updateHeart();
    } catch (e) {}
}

let collectionLoadRevision = 0;
async function refreshCollections() {
    const revision = ++collectionLoadRevision;
    try {
        const r = await fetch('/api/collections', { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        if (revision !== collectionLoadRevision || collectionPending) return;
        collectionState.list = data.collections || [];
    } catch (e) {}
}

async function openAddToCollectionModal() {
    const revision = ++collectionModalRevision;
    await refreshCollections();
    if (revision !== collectionModalRevision) return;
    await refreshMembership();
    if (revision !== collectionModalRevision) return;
    renderAddToCollectionList();
    document.getElementById('modalAddToCollection').classList.add('open');
}

function closeAddToCollectionModal() {
    ++collectionModalRevision;
    ++collectionReadRevision;
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
            if (collectionPending) return;
            const targetNovelId = novelId;
            const membership = collectionState.membership;
            const selected = cb.checked;
            setCollectionPending(true);
            try {
                const r = await fetch(`/api/collections/${id}/novels/${encodeURIComponent(targetNovelId)}`, {
                    method: selected ? 'POST' : 'DELETE', credentials: 'same-origin'
                });
                if (!r.ok) throw new Error('HTTP ' + r.status);
                if (selected) membership.add(id);
                else membership.delete(id);
            } catch (e) {
                toast(pageI18n.t('toast.action-failed', '操作失败'), 'error');
            } finally {
                setCollectionPending(false);
                await refreshMembership();
                updateHeart();
            }
        });
    });
    syncCollectionControls();
}


// ---- PixivNovel facade ----
window.PixivNovel.collections = window.PixivNovel.collections || {};
window.PixivNovel.collections = Object.assign(window.PixivNovel.collections, { loadCollectionState, updateHeart, iconHtml, refreshMembership, refreshCollections, openAddToCollectionModal, closeAddToCollectionModal, renderAddToCollectionList });
