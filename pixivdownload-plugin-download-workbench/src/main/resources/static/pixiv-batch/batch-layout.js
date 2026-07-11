'use strict';

const BATCH_LAYOUT_STORAGE_KEY = 'pixiv:batch-layout:v1';
const BATCH_LAYOUT_TOKEN_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
let batchLayoutBoundButton = null;
let batchLayoutClickBound = false;
let batchLayoutStorageBound = false;
let batchLayoutUiReady = false;

function availableBatchLayouts() {
    const links = typeof document.querySelectorAll === 'function'
        ? document.querySelectorAll('link[data-batch-layout-style]')
        : [];
    const seen = new Set();
    const layouts = [];
    Array.prototype.forEach.call(links, link => {
        const token = link && typeof link.getAttribute === 'function'
            ? link.getAttribute('data-batch-layout-style')
            : null;
        if (typeof token !== 'string' || !BATCH_LAYOUT_TOKEN_PATTERN.test(token) || seen.has(token)) return;
        seen.add(token);
        layouts.push(token);
    });
    return Object.freeze(layouts);
}

function defaultBatchLayoutFor(layouts) {
    if (!layouts.length) return null;
    const root = document.documentElement;
    const declared = root && root.getAttribute('data-batch-layout-default');
    return typeof declared === 'string' && layouts.includes(declared) ? declared : layouts[0];
}

function normalizeBatchLayoutFor(value, layouts) {
    if (!layouts.length) return null;
    return typeof value === 'string' && layouts.includes(value)
        ? value
        : defaultBatchLayoutFor(layouts);
}

function defaultBatchLayout() {
    return defaultBatchLayoutFor(availableBatchLayouts());
}

function normalizeBatchLayout(value) {
    return normalizeBatchLayoutFor(value, availableBatchLayouts());
}

function readStoredBatchLayout() {
    const layouts = availableBatchLayouts();
    const fallback = defaultBatchLayoutFor(layouts);
    if (fallback === null) return null;

    let stored;
    try {
        stored = window.localStorage.getItem(BATCH_LAYOUT_STORAGE_KEY);
    } catch (_) {
        return fallback;
    }
    if (stored === null) return fallback;
    if (typeof stored === 'string' && layouts.includes(stored)) return stored;

    try {
        window.localStorage.removeItem(BATCH_LAYOUT_STORAGE_KEY);
    } catch (_) {
        // 失效偏好无法清理时仍使用当前可用的默认布局。
    }
    return fallback;
}

function currentBatchLayout() {
    const layouts = availableBatchLayouts();
    const root = document.documentElement;
    return normalizeBatchLayoutFor(root && root.getAttribute('data-batch-layout'), layouts);
}

function nextBatchLayout(current, layouts) {
    if (!layouts.length) return null;
    if (layouts.length === 1) return layouts[0];
    const normalized = normalizeBatchLayoutFor(current, layouts);
    const index = layouts.indexOf(normalized);
    return layouts[(index + 1) % layouts.length];
}

function batchLayoutText(key) {
    return typeof bt === 'function' ? bt(key, key) : key;
}

function clearBatchLayoutToggle(button) {
    if (!button) return;
    button.hidden = true;
    button.disabled = true;
    ['data-layout', 'data-layout-target', 'data-i18n-title', 'data-i18n-aria-label', 'title', 'aria-label']
        .forEach(name => button.removeAttribute(name));
    const label = button.querySelector('.batch-layout-toggle-label');
    if (label) {
        label.removeAttribute('data-i18n');
        label.textContent = '';
    }
}

function syncBatchLayoutClickBinding(button, enabled) {
    if (batchLayoutBoundButton !== button) {
        if (batchLayoutBoundButton && batchLayoutClickBound) {
            batchLayoutBoundButton.removeEventListener('click', toggleBatchLayout);
        }
        batchLayoutBoundButton = button;
        batchLayoutClickBound = false;
    }
    if (!button) return;
    if (enabled && !batchLayoutClickBound) {
        button.addEventListener('click', toggleBatchLayout);
        batchLayoutClickBound = true;
    } else if (!enabled && batchLayoutClickBound) {
        button.removeEventListener('click', toggleBatchLayout);
        batchLayoutClickBound = false;
    }
}

function syncBatchLayoutStorageBinding(enabled) {
    if (enabled && !batchLayoutStorageBound) {
        window.addEventListener('storage', onBatchLayoutStorage);
        batchLayoutStorageBound = true;
    } else if (!enabled && batchLayoutStorageBound) {
        window.removeEventListener('storage', onBatchLayoutStorage);
        batchLayoutStorageBound = false;
    }
}

function refreshBatchLayoutToggle() {
    const button = document.getElementById('batch-layout-toggle');
    const layouts = availableBatchLayouts();
    const interactive = batchLayoutUiReady && layouts.length > 1;
    syncBatchLayoutClickBinding(button, interactive);
    syncBatchLayoutStorageBinding(batchLayoutUiReady);
    if (!button || !interactive) {
        clearBatchLayoutToggle(button);
        return;
    }

    const layout = currentBatchLayout();
    const target = nextBatchLayout(layout, layouts);
    if (layout === null || target === null || target === layout) {
        clearBatchLayoutToggle(button);
        return;
    }

    const key = 'layout.switch-to-' + target;
    const text = batchLayoutText(key);
    const label = button.querySelector('.batch-layout-toggle-label');
    button.setAttribute('data-layout', layout);
    button.setAttribute('data-layout-target', target);
    button.setAttribute('data-i18n-title', key);
    button.setAttribute('data-i18n-aria-label', key);
    button.setAttribute('title', text);
    button.setAttribute('aria-label', text);
    if (label) {
        label.setAttribute('data-i18n', key);
        label.textContent = text;
    }
    button.disabled = false;
    button.hidden = false;
}

function applyBatchLayout(layout, options) {
    const layouts = availableBatchLayouts();
    const normalized = normalizeBatchLayoutFor(layout, layouts);
    if (normalized === null) {
        refreshBatchLayoutToggle();
        return null;
    }

    const root = document.documentElement;
    if (root) root.setAttribute('data-batch-layout', normalized);
    refreshBatchLayoutToggle();
    if (options && options.persist) {
        try {
            window.localStorage.setItem(BATCH_LAYOUT_STORAGE_KEY, normalized);
        } catch (_) {
            // 浏览器拒绝持久化时仍保留当前页面的即时布局。
        }
    }
    return normalized;
}

function applyStoredBatchLayout() {
    const layout = readStoredBatchLayout();
    if (layout === null) {
        refreshBatchLayoutToggle();
        return null;
    }
    return applyBatchLayout(layout, {persist: false});
}

function toggleBatchLayout() {
    const layouts = availableBatchLayouts();
    if (!layouts.length) {
        refreshBatchLayoutToggle();
        return null;
    }
    if (layouts.length === 1) {
        refreshBatchLayoutToggle();
        return layouts[0];
    }
    return applyBatchLayout(nextBatchLayout(currentBatchLayout(), layouts), {persist: true});
}

function onBatchLayoutStorage(event) {
    if (!event || event.key !== BATCH_LAYOUT_STORAGE_KEY) return;
    if (!availableBatchLayouts().length) {
        refreshBatchLayoutToggle();
        return;
    }
    applyBatchLayout(event.newValue, {persist: false});
}

function bindBatchLayoutToggle() {
    batchLayoutUiReady = true;
    refreshBatchLayoutToggle();
}

window.PixivBatch.layout = Object.assign(window.PixivBatch.layout || {}, {
    availableLayouts: availableBatchLayouts,
    defaultLayout: defaultBatchLayout,
    normalizeLayout: normalizeBatchLayout,
    readStoredLayout: readStoredBatchLayout,
    applyLayout: applyBatchLayout,
    applyStoredLayout: applyStoredBatchLayout,
    toggleLayout: toggleBatchLayout,
    bindLayoutToggle: bindBatchLayoutToggle,
    refreshLayoutToggle: refreshBatchLayoutToggle,
    currentLayout: currentBatchLayout
});
