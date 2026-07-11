'use strict';

const BATCH_LAYOUT_STORAGE_KEY = 'pixiv:batch-layout:v1';
const BATCH_LAYOUT_TOKEN_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const BATCH_LAYOUT_ACTION_ID_PATTERN = /^[A-Za-z][A-Za-z0-9_.:-]*$/;
const BATCH_LAYOUT_ACTION_HOST_SELECTOR = '[data-batch-layout-action-host]';
const BATCH_LAYOUT_ACTION_ORIGIN_SELECTOR = '[data-batch-layout-action-origin]';
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

function batchLayoutActionElements(selector) {
    return typeof document.querySelectorAll === 'function'
        ? Array.prototype.slice.call(document.querySelectorAll(selector))
        : [];
}

function batchLayoutActionOrder(host) {
    const raw = host && host.getAttribute('data-batch-layout-action-order');
    if (typeof raw !== 'string' || !raw.trim()) return null;
    const ids = raw.trim().split(/\s+/);
    const seen = new Set();
    for (const id of ids) {
        if (!BATCH_LAYOUT_ACTION_ID_PATTERN.test(id) || seen.has(id)) return null;
        seen.add(id);
    }
    return ids;
}

function buildBatchLayoutActionProjection(layout) {
    const hosts = batchLayoutActionElements(BATCH_LAYOUT_ACTION_HOST_SELECTOR);
    const origins = batchLayoutActionElements(BATCH_LAYOUT_ACTION_ORIGIN_SELECTOR);
    if (!hosts.length && !origins.length) {
        return {hosts: [], actions: [], target: null, targetOrder: []};
    }
    if (!hosts.length || !origins.length) return null;

    const originsById = new Map();
    for (const origin of origins) {
        const id = origin.getAttribute('data-batch-layout-action-origin');
        if (typeof id !== 'string' || !BATCH_LAYOUT_ACTION_ID_PATTERN.test(id)
                || originsById.has(id) || !origin.parentNode) {
            return null;
        }
        originsById.set(id, origin);
    }

    const idCounts = new Map();
    for (const element of batchLayoutActionElements('[id]')) {
        const id = element.getAttribute('id');
        if (id) idCounts.set(id, (idCounts.get(id) || 0) + 1);
    }

    const actionsById = new Map();
    for (const [id, origin] of originsById) {
        const action = document.getElementById(id);
        if (!action || idCounts.get(id) !== 1 || !action.parentNode || action === origin) return null;
        actionsById.set(id, {id, node: action, origin});
    }

    const hostTokens = new Set();
    const hostPlans = [];
    let target = null;
    for (const host of hosts) {
        const token = host.getAttribute('data-batch-layout-action-host');
        const order = batchLayoutActionOrder(host);
        if (typeof token !== 'string' || !BATCH_LAYOUT_TOKEN_PATTERN.test(token)
                || hostTokens.has(token) || !order || !host.parentNode
                || order.length !== actionsById.size
                || order.some(id => !actionsById.has(id))) {
            return null;
        }
        hostTokens.add(token);
        const plan = {element: host, token, order};
        hostPlans.push(plan);
        if (token === layout) target = plan;
    }

    return {
        hosts: hostPlans,
        actions: Array.from(actionsById.values()),
        actionsById,
        target,
        targetOrder: target ? target.order.map(id => actionsById.get(id)) : []
    };
}

function syncBatchLayoutActionProjection(layout) {
    const plan = buildBatchLayoutActionProjection(layout);
    if (!plan) return false;
    if (!plan.actions.length) return true;

    const snapshots = plan.actions.map(action => ({
        node: action.node,
        parent: action.node.parentNode,
        nextSibling: action.node.nextSibling
    }));
    const hostStates = plan.hosts.map(host => ({element: host.element, hidden: host.element.hidden}));
    const focused = plan.actions.some(action => action.node === document.activeElement)
        ? document.activeElement
        : null;

    function restoreFocus() {
        if (!focused || document.activeElement === focused || typeof focused.focus !== 'function') return;
        try {
            focused.focus({preventScroll: true});
        } catch (_) {
            // 布局已同步；浏览器拒绝恢复焦点时不回滚节点投影。
        }
    }

    try {
        if (plan.target) {
            const host = plan.target.element;
            const actionNodes = new Set(plan.actions.map(action => action.node));
            const current = Array.prototype.filter.call(host.children || [], child => actionNodes.has(child));
            const alreadyProjected = current.length === plan.targetOrder.length
                && plan.targetOrder.every((action, index) => action.node.parentNode === host
                    && current[index] === action.node);
            if (!alreadyProjected) {
                plan.targetOrder.forEach(action => host.appendChild(action.node));
            }
        } else {
            plan.actions.forEach(action => {
                const parent = action.origin.parentNode;
                const reference = action.origin.nextElementSibling;
                if (action.node.parentNode !== parent || reference !== action.node) {
                    parent.insertBefore(action.node, reference);
                }
            });
        }
        plan.hosts.forEach(host => { host.element.hidden = host !== plan.target; });
        restoreFocus();
        return true;
    } catch (_) {
        for (let index = snapshots.length - 1; index >= 0; index--) {
            const snapshot = snapshots[index];
            try {
                const reference = snapshot.nextSibling && snapshot.nextSibling.parentNode === snapshot.parent
                    ? snapshot.nextSibling
                    : null;
                snapshot.parent.insertBefore(snapshot.node, reference);
            } catch (_) {
                // 已完成完整预检；这里只对意外的浏览器 DOM 异常做尽力回滚。
            }
        }
        hostStates.forEach(state => { state.element.hidden = state.hidden; });
        restoreFocus();
        return false;
    }
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
    const previousLayout = root && root.getAttribute('data-batch-layout');
    const focusedBeforeProjection = document.activeElement;
    if (!root || !syncBatchLayoutActionProjection(normalized)) {
        refreshBatchLayoutToggle();
        return null;
    }
    try {
        root.setAttribute('data-batch-layout', normalized);
    } catch (_) {
        syncBatchLayoutActionProjection(previousLayout);
        refreshBatchLayoutToggle();
        return null;
    }
    if (focusedBeforeProjection && document.activeElement !== focusedBeforeProjection
            && typeof focusedBeforeProjection.focus === 'function') {
        try {
            focusedBeforeProjection.focus({preventScroll: true});
        } catch (_) {
            // 根布局已经生效；浏览器拒绝恢复焦点时不回滚布局。
        }
    }
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
