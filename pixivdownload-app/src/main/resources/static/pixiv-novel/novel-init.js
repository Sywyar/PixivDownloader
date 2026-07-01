'use strict';
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

document.getElementById('deleteNovelBtn').addEventListener('click', openDeleteNovelModal);
document.getElementById('deleteNovelClose').addEventListener('click', closeDeleteNovelModal);
document.getElementById('deleteNovelCancel').addEventListener('click', closeDeleteNovelModal);
document.getElementById('deleteNovelConfirm').addEventListener('click', confirmDeleteNovel);
document.getElementById('modalDeleteNovel').addEventListener('click', e => {
    if (e.target.id === 'modalDeleteNovel') closeDeleteNovelModal();
});

setupAdminMode();
loadAll();

// ---- PixivNovel facade ----
window.PixivNovel.init = window.PixivNovel.init || {};
