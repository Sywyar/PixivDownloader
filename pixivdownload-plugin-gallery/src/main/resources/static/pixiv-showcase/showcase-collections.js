'use strict';

    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
        } catch (e) {
            state.collections = [];
        }
    }

    async function loadMembership() {
        try {
            const resp = await api(`/api/collections/of/${state.artworkId}`);
            state.collectionMembership = new Set(resp.collectionIds || []);
            updateHeartBtn();
        } catch (e) {}
    }

    function updateHeartBtn() {
        const btn = document.getElementById('heartBtnTop');
        if (!btn) return;
        const liked = state.collectionMembership.size > 0;
        if (liked) {
            btn.style.borderColor = 'var(--danger)';
            btn.style.color = 'var(--danger)';
            btn.style.background = 'rgba(220, 38, 38, 0.1)';
        } else {
            btn.style.borderColor = '';
            btn.style.color = '';
            btn.style.background = '';
        }
    }

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${collection.createdTime}" alt="">`;
        }
        return HEART_SVG;
    }

    async function openAddToCollectionModal() {
        await loadCollections();
        await loadMembership();
        renderAddToCollectionList();
        document.getElementById('modalAddToCollection').classList.add('open');
    }

    function closeAddToCollectionModal() {
        document.getElementById('modalAddToCollection').classList.remove('open');
        updateHeartBtn();
    }

    function renderAddToCollectionList() {
        const list = document.getElementById('addToCollectionList');
        if (!state.collections.length) {
            list.innerHTML = `<div class="collection-empty">${escapeHtml(wt('status.no-collections', 'No collections yet. Create one first.'))}</div>`;
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
                try {
                    if (cb.checked) {
                        await api(`/api/collections/${id}/artworks/${state.artworkId}`, { method: 'POST' });
                        state.collectionMembership.add(id);
                    } else {
                        await api(`/api/collections/${id}/artworks/${state.artworkId}`, { method: 'DELETE' });
                        state.collectionMembership.delete(id);
                    }
                    updateHeartBtn();
                } catch (e) {
                    toast(e.message || wt('toast.action-failed', 'Action failed'), 'error');
                    cb.checked = !cb.checked;
                }
            });
        });
    }

    document.getElementById('quickCreateBtn').addEventListener('click', async () => {
        const input = document.getElementById('quickCreateName');
        const name = input.value.trim();
        if (!name) return;
        const btn = document.getElementById('quickCreateBtn');
        btn.disabled = true;
        try {
            const c = await api('/api/collections', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name }),
            });
            input.value = '';
            state.collections.push(c);
            state.collections.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
            await api(`/api/collections/${c.id}/artworks/${state.artworkId}`, { method: 'POST' });
            state.collectionMembership.add(c.id);
            renderAddToCollectionList();
            updateHeartBtn();
            toast(wt('toast.created', 'Created'), 'success');
        } catch (e) {
            toast(e.message || wt('toast.create-failed', 'Creation failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

    document.addEventListener('click', e => {
        const backdrop = e.target.classList && e.target.classList.contains('modal-backdrop');
        if (backdrop) e.target.classList.remove('open');
    });
