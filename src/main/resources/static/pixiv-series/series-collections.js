'use strict';

    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
            renderCollections();
        } catch (e) {
            console.warn(t('log.collections-failed', '加载收藏夹失败'), e);
        }
    }

    function renderCollections() {
        const list = document.getElementById('collectionList');
        if (!list) return;
        if (!state.collections.length) {
            list.innerHTML = '<div class="collection-empty">' +
                escapeHtml(pageText('gallery:status.no-collections', 'No collections')) +
                '</div>';
            return;
        }
        list.innerHTML = state.collections.map(collection => {
            const count = isNovelMode()
                ? (collection.novelCount ?? 0)
                : (collection.artworkCount ?? 0);
            return `
                <a class="collection-item" href="${escapeHtml(buildCollectionHref(collection.id))}">
                    <div class="collection-icon">${iconHtml(collection)}</div>
                    <span class="collection-label">${escapeHtml(collection.name)}</span>
                    <span class="collection-count">${escapeHtml(count)}</span>
                </a>
            `;
        }).join('');
    }

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${encodeURIComponent(collection.createdTime || '')}" alt="">`;
        }
        return HEART_SVG;
    }

    function buildCollectionHref(collectionId) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        params.set('collectionIds', String(collectionId));
        return (isNovelMode() ? '/pixiv-novel-gallery.html' : '/pixiv-gallery.html') + '?' + params.toString();
    }
