// 批量选择只收集页面身份；草稿修订在预检前读取，正式写入仍需单独确认。
export async function selectPages(pages, current, label, t, signal, restoring = false) {
    const selected = await window.PixivFeedback.choose({
        title: t(restoring ? 'restore-batch' : 'publish-batch'), message: t(restoring ? 'restore-select-help' : 'publication-select-help'),
        confirmLabel: t('publication-review'), cancelLabel: t('cancel'), signal, multiple: true,
        choices: pages.map(page => ({value: page.id, label: label(page) + ' (' + page.id + ')',
            checked: page.id === current, disabled: page.id === current}))
    });
    return selected?.filter(id => id !== current) ?? null;
}
