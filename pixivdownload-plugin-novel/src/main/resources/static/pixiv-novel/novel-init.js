'use strict';

function hideNovelCover() {
    document.getElementById('novel-cover').style.display = 'none';
}

PixivActions.bind(document, { error: {hideNovelCover} });

const readerMore = document.getElementById('readerMore');
readerMore.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || event.defaultPrevented || !readerMore.open) return;
    event.preventDefault();
    event.stopPropagation();
    readerMore.open = false;
    readerMore.querySelector('summary').focus();
});
readerMore.addEventListener('focusout', event => {
    if (event.relatedTarget && !readerMore.contains(event.relatedTarget)) readerMore.open = false;
});
readerMore.addEventListener('click', event => {
    if (event.target.closest('[data-reader-action]')) readerMore.open = false;
});
document.addEventListener('pointerdown', event => {
    if (!readerMore.contains(event.target)) readerMore.open = false;
});

bindNovelGalleryReturn();
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
    if (collectionPending) return;
    const targetNovelId = novelId;
    const membership = collectionState.membership;
    const revision = collectionModalRevision;
    setCollectionPending(true);
    try {
        const r = await fetch('/api/collections', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        if (revision === collectionModalRevision) input.value = '';
        collectionState.list.push(c);
        collectionState.list.sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id));
        const link = await fetch(`/api/collections/${c.id}/novels/${encodeURIComponent(targetNovelId)}`, { method: 'POST', credentials: 'same-origin' });
        if (!link.ok) throw new Error('HTTP ' + link.status);
        membership.add(c.id);
        toast(pageI18n.t('toast.created', '已创建'), 'success');
    } catch (e) {
        toast(pageI18n.t('toast.create-failed', '创建失败'), 'error');
    } finally {
        setCollectionPending(false);
        await refreshMembership();
        await refreshCollections();
        renderAddToCollectionList();
        updateHeart();
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
