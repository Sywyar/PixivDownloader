'use strict';

async function douyinDetailInitI18n() {
    let pageI18n = null;
    if (window.PixivI18n && typeof window.PixivI18n.create === 'function') {
        try {
            pageI18n = await window.PixivI18n.create({namespaces: ['douyin', 'common']});
            PixivDouyinDetailCore.setI18n(pageI18n);
            if (typeof pageI18n.apply === 'function') pageI18n.apply(document);
            if (window.PixivLangSwitcher && typeof window.PixivLangSwitcher.mount === 'function') {
                await window.PixivLangSwitcher.mount({
                    mountPoint: document.getElementById('langSwitcherAnchor'),
                    i18n: pageI18n,
                    onChange(next) {
                        PixivDouyinDetailCore.setI18n(next);
                        if (next && typeof next.apply === 'function') next.apply(document);
                        PixivDouyinDetailRender.rerender();
                    }
                });
            }
        } catch (_) {
            PixivDouyinDetailCore.setI18n(null);
        }
    }
    if (window.PixivTheme && typeof window.PixivTheme.mount === 'function') {
        window.PixivTheme.mount({mountPoint: document.getElementById('langSwitcherAnchor')});
    }
    return pageI18n;
}

function douyinDetailBindNavigation(state) {
    const galleryLink = document.getElementById('galleryLink');
    const backButton = document.getElementById('backButton');
    galleryLink.href = state.returnTo;
    backButton.addEventListener('click', () => window.location.assign(state.returnTo));
}

function douyinDetailFailureStatus(failure) {
    const status = failure && Number(failure.httpStatus);
    if (status === 401 || status === 403) {
        return ['detail.status.forbidden', 'Administrator access is required'];
    }
    if (status === 404) {
        return ['detail.status.not-found', 'This work is unavailable'];
    }
    return ['detail.status.failed', 'Unable to load work details'];
}

async function douyinDetailBoot() {
    const state = PixivDouyinDetailCore.parseParams(window.location.search);
    douyinDetailBindNavigation(state);
    await douyinDetailInitI18n();
    if (!state.id) {
        PixivDouyinDetailRender.showStatus(
            'detail.status.missing-id', 'A work ID is required', 'error');
        return;
    }
    PixivDouyinDetailRender.showStatus(
        'detail.status.loading', 'Loading work details...', 'loading');
    try {
        PixivDouyinDetailRender.renderWork(await PixivDouyinDetailCore.requestWork(state.id));
    } catch (failure) {
        const status = douyinDetailFailureStatus(failure);
        PixivDouyinDetailRender.showStatus(status[0], status[1], 'error');
    }
}

void douyinDetailBoot();
