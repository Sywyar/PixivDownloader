'use strict';
// ---------- Admin only (delete + AI translate) ----------
async function setupAdminMode() {
    try {
        const res = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
        if (!res.ok) return;
        document.body.classList.add('admin-mode');
        const btn = document.getElementById('deleteNovelBtn');
        if (btn) btn.style.display = '';
        // 「AI 翻译」入口仅在后端已配置文本模型时展示：未配置时翻译无法工作，隐藏入口。
        const translateBtn = document.getElementById('aiTranslateBtn');
        if (translateBtn && window.PixivTranslate && PixivTranslate.isAiConfigured) {
            const aiConfigured = await PixivTranslate.isAiConfigured();
            if (aiConfigured) {
                translateBtn.style.display = '';
                translateBtn.addEventListener('click', openTranslateDialog);
            }
        }
    } catch (_) { /* not admin */ }
}

// ---------- AI translate (admin only) ----------
async function openTranslateDialog() {
    if (!window.PixivTranslate || !novelId) return;
    // 已有进行中的翻译：直接重新弹出当前进度，不再发新请求
    if (PixivTranslate.hasActiveJob()) {
        PixivTranslate.showActiveJob();
        return;
    }
    const choice = await PixivTranslate.openDialog({
        i18n: pageI18n, series: false, novelId: novelId, onToast: toast
    });
    if (!choice) return;
    const seriesIdForMerge = cachedSeriesNav && cachedSeriesNav.seriesId
        ? cachedSeriesNav.seriesId : null;
    const outcome = await PixivTranslate.runSingleNovel({
        i18n: pageI18n, novelId: novelId, choice: choice,
        seriesId: seriesIdForMerge
    });
    if (!outcome || outcome.cancelled) return;
    if (outcome.error) {
        toast(pageI18n.t('translate:toast.failed', '翻译失败：{message}',
            { message: String(outcome.error.message || outcome.error) }), 'error');
        return;
    }
    if (outcome.mergeFailed) {
        // 翻译已成功落库，仅合订本生成失败：提示后仍走后续刷新逻辑
        toast(pageI18n.t('translate:toast.merge-failed', '合订本生成失败：{message}',
            { message: String(outcome.mergeFailed.message || outcome.mergeFailed) }), 'error');
    }
    const resp = outcome.result;
    if (resp.status === PixivTranslate.STATUS_INVALID_LANGUAGE) {
        toast(resp.message || pageI18n.t('translate:toast.invalid-language', '该语言不存在或无法识别'), 'error');
    } else if (resp.status === PixivTranslate.STATUS_SAME_LANGUAGE) {
        // 原文已是目标语言：无译文变体，提示后不切换内容语言、不跳转。
        toast(resp.message || pageI18n.t('translate:toast.same-language',
            '原文已是目标语言，已跳过翻译'), 'success');
    } else if (resp.status === PixivTranslate.STATUS_OK || resp.status === PixivTranslate.STATUS_SKIPPED) {
        toast(resp.message || pageI18n.t('translate:toast.success', '翻译完成'), 'success');
        if (resp.langCode) {
            if (window.PixivContentLang) PixivContentLang.setStored(resp.langCode);
            const p = new URLSearchParams(location.search);
            p.set('lang', resp.langCode);
            location.search = p.toString();
        }
    } else {
        toast(resp.message || pageI18n.t('translate:toast.failed', '翻译失败：{message}', { message: '' }), 'error');
    }
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
window.PixivNovel.admin = Object.assign(window.PixivNovel.admin, { setupAdminMode, openTranslateDialog, openDeleteNovelModal, closeDeleteNovelModal, confirmDeleteNovel });
