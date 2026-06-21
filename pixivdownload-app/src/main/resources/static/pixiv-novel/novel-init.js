'use strict';
// ---------- 听书（TTS） ----------
function setupTts() {
    if (!window.PixivNovelTts) return;
    const contentEl = document.getElementById('content-card');
    const hasReadable = contentEl && contentEl.querySelector('p, h2.novel-chapter');
    if (!hasReadable) return; // 正文加载失败 / 无可朗读内容时不显示听书入口
    document.getElementById('ttsToggle').style.display = '';
    const data = rerenderPayload ? rerenderPayload.data : null;
    // 听书语言：显示译文时用所选内容语言，否则用作品原始语言
    const language = activeContentLang || (data ? (data.language || data.xLanguage || '') : '');
    // 「富感情朗读（多角色）」作为听书引擎之一并入同一控制条；narrationLang 用所选内容语言（空=原文），
    // 与脚本 / 渲染同源。引擎可用性 + 管理员可见由 TTS 控制器自行探测，未配置时该引擎选项禁用。
    PixivNovelTts.attach({ i18n: pageI18n, contentEl, toast, language, novelId, narrationLang: activeContentLang });
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
window.PixivNovel.init = Object.assign(window.PixivNovel.init, { setupTts });
