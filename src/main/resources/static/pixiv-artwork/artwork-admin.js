'use strict';
    // ---------- Delete (admin only) ----------
    async function setupAdminMode() {
        try {
            const res = await fetch('/api/admin/invites/access-check', {credentials: 'same-origin'});
            if (!res.ok) return;
            document.body.classList.add('admin-mode');
            const btn = document.getElementById('deleteArtworkBtn');
            if (btn) btn.style.display = '';
        } catch (_) { /* not admin */ }
    }

    function openDeleteModal() {
        document.getElementById('modalDeleteArtwork').classList.add('open');
    }

    function closeDeleteModal() {
        document.getElementById('modalDeleteArtwork').classList.remove('open');
    }

    async function confirmDelete() {
        const btn = document.getElementById('deleteArtworkConfirm');
        if (state.artworkId == null) return;
        btn.disabled = true;
        try {
            await api(`/api/gallery/artwork/${state.artworkId}`, {method: 'DELETE'});
            toast(wt('delete.success', 'Deleted'), 'success');
            setTimeout(() => {
                window.location.href = '/pixiv-gallery.html?view=all';
            }, 600);
        } catch (e) {
            btn.disabled = false;
            closeDeleteModal();
            toast(e.message || wt('delete.failed', 'Delete failed'), 'error');
        }
    }

    (function wireDelete() {
        const btn = document.getElementById('deleteArtworkBtn');
        if (btn) btn.addEventListener('click', openDeleteModal);
        document.getElementById('deleteArtworkClose').addEventListener('click', closeDeleteModal);
        document.getElementById('deleteArtworkCancel').addEventListener('click', closeDeleteModal);
        document.getElementById('deleteArtworkConfirm').addEventListener('click', confirmDelete);
    })();


// ---- PixivArtwork facade ----
window.PixivArtwork.admin = window.PixivArtwork.admin || {};
window.PixivArtwork.admin = Object.assign(window.PixivArtwork.admin, { setupAdminMode, openDeleteModal, closeDeleteModal, confirmDelete });
