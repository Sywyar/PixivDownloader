'use strict';
async function load() {
    try {
        detail = await api('/api/admin/invites/' + encodeURIComponent(inviteId));
        render();
    } catch (e) {
        document.getElementById('wrap').innerHTML =
            '<div class="card"><div class="error">' + escapeHtml(tr('invite:load.failed', '加载失败：{0}', { 0: e.message })) + '</div></div>';
    }
}

async function togglePause() {
    if (!detail) return;
    const wasPaused = detail.paused;
    const path = wasPaused ? '/resume' : '/pause';
    try {
        await api('/api/admin/invites/' + detail.id + path, { method: 'POST' });
        window.InviteModals.showToast(
            tr(wasPaused ? 'invite:toast.resumed' : 'invite:toast.paused',
               wasPaused ? '邀请码已恢复' : '邀请码已暂停'),
            'success');
        load();
    } catch (e) {
        window.InviteModals.showToast(tr('invite:toast.failed', '{0}', { 0: e.message }), 'error');
    }
}

async function deleteInvite() {
    if (!detail) return;
    if (!confirm(tr('invite:detail.op.delete.confirm',
        '确认删除邀请「{name}」？该操作不可恢复。', { name: detail.name }))) return;
    try {
        await api('/api/admin/invites/' + detail.id, { method: 'DELETE' });
        window.InviteModals.showToast(tr('invite:toast.deleted', '已删除'), 'success');
        setTimeout(() => { location.href = '/pixiv-invite-manage.html'; }, 600);
    } catch (e) {
        window.InviteModals.showToast(tr('invite:toast.failed', '{0}', { 0: e.message }), 'error');
    }
}

async function fetchTagsForPicker() {
    if (cachedTags) return cachedTags;
    const data = await api('/api/gallery/tags?limit=2000');
    cachedTags = (data.tags || []).map(t => ({
        id: t.tagId,
        name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name
    }));
    return cachedTags;
}
async function fetchAuthorsForPicker() {
    if (cachedAuthors) return cachedAuthors;
    const all = []; let page = 0; let totalPages = 1;
    while (page < totalPages && page < 50) {
        const data = await api(`/api/authors/paged?page=${page}&size=200&sort=name`);
        const list = data.content || [];
        for (const a of list) all.push({ id: a.authorId, name: a.name || ('#' + a.authorId) });
        totalPages = data.totalPages || 1;
        page++;
        if (list.length === 0) break;
    }
    cachedAuthors = all;
    return cachedAuthors;
}
async function fetchNovelTagsForPicker() {
    if (cachedNovelTags) return cachedNovelTags;
    const data = await api('/api/gallery/novels/tags?limit=2000');
    cachedNovelTags = (data.tags || []).map(t => ({
        id: t.tagId,
        name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name
    }));
    return cachedNovelTags;
}
async function fetchNovelAuthorsForPicker() {
    if (cachedNovelAuthors) return cachedNovelAuthors;
    const all = []; let page = 0; let totalPages = 1;
    while (page < totalPages && page < 50) {
        const data = await api(`/api/gallery/novels/authors?page=${page}&size=200&sort=name`);
        const list = data.content || [];
        for (const a of list) all.push({ id: a.authorId, name: a.name || ('#' + a.authorId) });
        totalPages = data.totalPages || 1;
        page++;
        if (list.length === 0) break;
    }
    cachedNovelAuthors = all;
    return cachedNovelAuthors;
}

/**
 * 把单维度的"查看详细"打开为可编辑 picker，提交时只更新对应维度，其它三维度保持原值。
 */
function openViewDetailPicker(kind) {
    const fetched = (() => {
        switch (kind) {
            case 'tag': return fetchTagsForPicker();
            case 'author': return fetchAuthorsForPicker();
            case 'novel-tag': return fetchNovelTagsForPicker();
            case 'novel-author': return fetchNovelAuthorsForPicker();
            default: return Promise.resolve([]);
        }
    })();
    Promise.resolve(fetched).then(list => {
        const initialUnrestricted = ({
            'tag': detail.tagUnrestricted,
            'author': detail.authorUnrestricted,
            'novel-tag': detail.novelTagUnrestricted,
            'novel-author': detail.novelAuthorUnrestricted,
        })[kind];
        const initialIds = (() => {
            switch (kind) {
                case 'tag': return (detail.tags || []).map(t => t.tagId);
                case 'author': return (detail.authors || []).map(a => a.authorId);
                case 'novel-tag': return (detail.novelTags || []).map(t => t.tagId);
                case 'novel-author': return (detail.novelAuthors || []).map(a => a.authorId);
                default: return [];
            }
        })();
        window.InviteModals.openVisibilityPicker({
            kind,
            items: list,
            unrestricted: initialUnrestricted,
            selectedIds: initialIds,
            onSubmit: async ({ unrestricted, ids }) => {
                const expireDays = detail.expireTime == null
                    ? null
                    : Math.max(1, Math.ceil((detail.expireTime - Date.now()) / 86400000));
                const payload = {
                    name: detail.name,
                    expireDays,
                    allowSfw: detail.allowSfw,
                    allowR18: detail.allowR18,
                    allowR18g: detail.allowR18g,
                    tagUnrestricted: detail.tagUnrestricted,
                    tagIds: (detail.tags || []).map(t => t.tagId),
                    authorUnrestricted: detail.authorUnrestricted,
                    authorIds: (detail.authors || []).map(a => a.authorId),
                    novelTagUnrestricted: detail.novelTagUnrestricted,
                    novelTagIds: (detail.novelTags || []).map(t => t.tagId),
                    novelAuthorUnrestricted: detail.novelAuthorUnrestricted,
                    novelAuthorIds: (detail.novelAuthors || []).map(a => a.authorId),
                };
                // 仅覆盖被编辑的那一维
                switch (kind) {
                    case 'tag':
                        payload.tagUnrestricted = unrestricted;
                        payload.tagIds = unrestricted ? [] : ids;
                        break;
                    case 'author':
                        payload.authorUnrestricted = unrestricted;
                        payload.authorIds = unrestricted ? [] : ids;
                        break;
                    case 'novel-tag':
                        payload.novelTagUnrestricted = unrestricted;
                        payload.novelTagIds = unrestricted ? [] : ids;
                        break;
                    case 'novel-author':
                        payload.novelAuthorUnrestricted = unrestricted;
                        payload.novelAuthorIds = unrestricted ? [] : ids;
                        break;
                }
                try {
                    await api('/api/admin/invites/' + detail.id, {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    });
                    window.InviteModals.showToast(tr('invite:toast.saved', '已保存'), 'success');
                    load();
                } catch (e) {
                    window.InviteModals.showToast(tr('invite:toast.failed', '{0}', { 0: e.message }), 'error');
                }
            }
        });
    });
}

function openEditModal() {
    if (!detail) return;
    const expireDays = detail.expireTime == null
        ? null
        : Math.max(1, Math.ceil((detail.expireTime - Date.now()) / 86400000));
    const prefill = {
        id: detail.id,
        name: detail.name,
        expireDays,
        permanent: detail.expireTime == null,
        allowSfw: detail.allowSfw,
        allowR18: detail.allowR18,
        allowR18g: detail.allowR18g,
        tagUnrestricted: detail.tagUnrestricted,
        authorUnrestricted: detail.authorUnrestricted,
        tagIds: (detail.tags || []).map(t => t.tagId),
        authorIds: (detail.authors || []).map(a => a.authorId),
        novelTagUnrestricted: detail.novelTagUnrestricted,
        novelAuthorUnrestricted: detail.novelAuthorUnrestricted,
        novelTagIds: (detail.novelTags || []).map(t => t.tagId),
        novelAuthorIds: (detail.novelAuthors || []).map(a => a.authorId),
    };
    window.InviteModals.openInviteFormModal({
        title: tr('invite:modal.edit.title', '编辑邀请'),
        submitText: tr('invite:modal.edit.submit', '保存'),
        prefill,
        editing: true,
        fetchTags: fetchTagsForPicker,
        fetchAuthors: fetchAuthorsForPicker,
        fetchNovelTags: fetchNovelTagsForPicker,
        fetchNovelAuthors: fetchNovelAuthorsForPicker,
        onSubmit: async (payload) => {
            await api('/api/admin/invites/' + detail.id, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            window.InviteModals.showToast(tr('invite:toast.saved', '已保存'), 'success');
            load();
        }
    });
}

// ---- PixivInviteDetail facade ----
window.PixivInviteDetail.actions = Object.assign(window.PixivInviteDetail.actions || {}, { load, togglePause, deleteInvite, fetchTagsForPicker, fetchAuthorsForPicker, fetchNovelTagsForPicker, fetchNovelAuthorsForPicker, openViewDetailPicker, openEditModal });
