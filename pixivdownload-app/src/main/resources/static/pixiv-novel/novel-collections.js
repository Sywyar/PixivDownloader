'use strict';
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


// ---- PixivNovel facade ----
window.PixivNovel.collections = window.PixivNovel.collections || {};
window.PixivNovel.collections = Object.assign(window.PixivNovel.collections, { loadCollectionState, updateHeart, iconHtml, refreshMembership, refreshCollections, openAddToCollectionModal, closeAddToCollectionModal, renderAddToCollectionList });
