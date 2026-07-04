'use strict';
// ---------- Admin only (delete) ----------
async function setupAdminMode() {
    try {
        const res = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
        if (!res.ok) return;
        document.body.classList.add('admin-mode');
        const btn = document.getElementById('deleteNovelBtn');
        if (btn) btn.style.display = '';
    } catch (_) { /* not admin */ }
}

function openDeleteNovelModal() {
    document.getElementById('modalDeleteNovel').classList.add('open');
}

function closeDeleteNovelModal() {
    document.getElementById('modalDeleteNovel').classList.remove('open');
}

async function confirmDeleteNovel() {
    if (!novelId) return;
    const btn = document.getElementById('deleteNovelConfirm');
    btn.disabled = true;
    try {
        const r = await fetch(`/api/gallery/novel/${encodeURIComponent(novelId)}`, { method: 'DELETE', credentials: 'same-origin' });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        toast(pageI18n.t('delete.success', '已删除'), 'success');
        setTimeout(() => { window.location.href = '/pixiv-novel-gallery.html?view=all'; }, 600);
    } catch (e) {
        btn.disabled = false;
        closeDeleteNovelModal();
        toast(pageI18n.t('delete.failed', '删除失败'), 'error');
    }
}


// ---- PixivNovel facade ----
window.PixivNovel.admin = window.PixivNovel.admin || {};
window.PixivNovel.admin = Object.assign(window.PixivNovel.admin, { setupAdminMode, openDeleteNovelModal, closeDeleteNovelModal, confirmDeleteNovel });
