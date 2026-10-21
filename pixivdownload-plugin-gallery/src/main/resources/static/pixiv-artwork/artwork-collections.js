'use strict';

    let collectionPending = false;
    let collectionReadRevision = 0;
    let collectionModalRevision = 0;

    function syncCollectionControls() {
        document.getElementById('quickCreateBtn').disabled = collectionPending;
        document.getElementById('addToCollectionList').querySelectorAll('.collection-row').forEach(row => {
            const checkbox = row.querySelector('input[type="checkbox"]');
            checkbox.disabled = collectionPending;
            if (!collectionPending) checkbox.checked = state.collectionMembership.has(Number(row.dataset.id));
        });
    }

    function setCollectionPending(pending) {
        collectionPending = pending;
        ++collectionReadRevision;
        ++collectionLoadRevision;
        syncCollectionControls();
    }

    // ---------- Collections ----------
    let collectionLoadRevision = 0;
    async function loadCollections() {
        const revision = ++collectionLoadRevision;
        try {
            const resp = await api('/api/collections');
            if (revision !== collectionLoadRevision || collectionPending) return;
            state.collections = resp.collections || [];
        } catch (e) {
            if (revision === collectionLoadRevision && !collectionPending) state.collections = [];
        }
    }

    async function loadMembership() {
        const revision = ++collectionReadRevision;
        const artworkId = state.artworkId;
        try {
            const resp = await api(`/api/collections/of/${artworkId}`);
            if (revision !== collectionReadRevision || collectionPending || artworkId !== state.artworkId) return;
            state.collectionMembership = new Set(resp.collectionIds || []);
            syncCollectionControls();
            const btn = document.getElementById('heartBtn');
            if (btn) btn.style.display = '';
            updateHeart();
        } catch (e) {
            // Ignore — heart stays hidden when collections aren't accessible
        }
    }

    function updateHeart() {
        const btn = document.getElementById('heartBtn');
        const label = document.getElementById('heartLabel');
        if (!btn || !label) return;
        const liked = state.collectionMembership.size > 0;
        btn.classList.toggle('liked', liked);
        label.textContent = liked
            ? wt('button.favorite-active', 'Favorited ({count})', {count: state.collectionMembership.size})
            : wt('button.favorite', 'Favorite');
    }

    const heartBtn = document.getElementById('heartBtn');
    if (heartBtn) heartBtn.addEventListener('click', openAddToCollectionModal);

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${collection.createdTime}" alt="">`;
        }
        return HEART_SVG;
    }

    async function openAddToCollectionModal() {
        const revision = ++collectionModalRevision;
        await loadCollections();
        if (revision !== collectionModalRevision) return;
        await loadMembership();
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
        if (!state.collections.length) {
            list.innerHTML = '<div style="padding:24px; text-align:center; color:var(--muted); font-size:13px">' + escapeHtml(wt('status.no-collections', 'No collections yet. Create one first.')) + '</div>';
            return;
        }
        list.innerHTML = state.collections.map(c => `
            <label class="collection-row" data-id="${c.id}">
                <input type="checkbox" ${state.collectionMembership.has(c.id) ? 'checked' : ''}>
                <div class="collection-row-icon">${iconHtml(c)}</div>
                <div class="collection-row-name">${escapeHtml(c.name)}</div>
                <span class="collection-count">${c.artworkCount}</span>
            </label>
        `).join('');
        list.querySelectorAll('.collection-row').forEach(row => {
            const id = Number(row.dataset.id);
            const cb = row.querySelector('input[type="checkbox"]');
            cb.addEventListener('change', async () => {
                if (collectionPending) return;
                const artworkId = state.artworkId;
                if (artworkId == null) return;
                const membership = state.collectionMembership;
                const selected = cb.checked;
                setCollectionPending(true);
                try {
                    await api(`/api/collections/${id}/artworks/${artworkId}`, {method: selected ? 'POST' : 'DELETE'});
                    if (selected) membership.add(id);
                    else membership.delete(id);
                } catch (e) {
                    toast(e.message || wt('toast.action-failed', 'Action failed'), 'error');
                } finally {
                    setCollectionPending(false);
                    await loadMembership();
                    updateHeart();
                }
            });
        });
        syncCollectionControls();
    }

    document.getElementById('quickCreateBtn').addEventListener('click', async () => {
        const input = document.getElementById('quickCreateName');
        const name = input.value.trim();
        if (!name) return;
        if (collectionPending) return;
        const artworkId = state.artworkId;
        const membership = state.collectionMembership;
        const revision = collectionModalRevision;
        setCollectionPending(true);
        try {
            const c = await api('/api/collections', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name}),
            });
            if (revision === collectionModalRevision) input.value = '';
            state.collections.push(c);
            state.collections.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
            await api(`/api/collections/${c.id}/artworks/${artworkId}`, {method: 'POST'});
            membership.add(c.id);
            toast(wt('toast.created', 'Created'), 'success');
        } catch (e) {
            toast(e.message || wt('toast.create-failed', 'Creation failed'), 'error');
        } finally {
            setCollectionPending(false);
            await loadMembership();
            await loadCollections();
            renderAddToCollectionList();
            updateHeart();
        }
    });

    document.addEventListener('click', e => {
        const backdrop = e.target.classList && e.target.classList.contains('modal-backdrop');
        if (backdrop) {
            if (e.target.id === 'modalAddToCollection') closeAddToCollectionModal();
            else e.target.classList.remove('open');
        }
    });


// ---- PixivArtwork facade ----
window.PixivArtwork.collections = window.PixivArtwork.collections || {};
window.PixivArtwork.collections = Object.assign(window.PixivArtwork.collections, { loadCollections, loadMembership, updateHeart, iconHtml, openAddToCollectionModal, closeAddToCollectionModal, renderAddToCollectionList });
