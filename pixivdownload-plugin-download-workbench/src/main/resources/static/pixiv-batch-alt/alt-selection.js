'use strict';

// 选择随获取上下文保存；分页不清空，来源、账号或查询变化不能沿用旧作品。
const workSelections = new Map();
const workSelectionItems = new WeakMap();
const WORK_INTERACTION_MODE_KEY = 'pixiv_batch_alt_work_interaction_mode';
let workInteractionMode = loadWorkInteractionMode();

function loadWorkInteractionMode() {
    try {
        return localStorage.getItem(WORK_INTERACTION_MODE_KEY) === 'direct' ? 'direct' : 'select';
    } catch {
        return 'select';
    }
}

function workInteractionModeControl() {
    const group = el('div', 'ab-seg ab-seg--sm ab-work-mode');
    group.setAttribute('role', 'group');
    group.setAttribute('aria-label', bt('selection.mode', '作品操作模式'));
    for (const [mode, key, fallback] of [
        ['select', 'selection.mode.select', '批量选择'],
        ['direct', 'card.enqueue', '直接加入队列']
    ]) {
        const button = el('button', 'ab-seg-item', bt(key, fallback));
        button.type = 'button';
        button.dataset.workMode = mode;
        button.classList.toggle('is-active', mode === workInteractionMode);
        button.setAttribute('aria-pressed', String(mode === workInteractionMode));
        button.addEventListener('click', () => {
            workInteractionMode = mode;
            try {
                localStorage.setItem(WORK_INTERACTION_MODE_KEY, mode);
            } catch {
                // 浏览器禁用存储时，模式仍在本页生效。
            }
            syncAllResultsQueueState();
        });
        group.appendChild(button);
    }
    return group;
}

function workSelectionContext() {
    if (state.mode === QUICK_FETCH_MODE) return [quickState.source, quickState.kind,
        quickState.action, quickState.uid, quickState.drill];
    if (state.mode === 'user') return [userState.source, userState.kind, userState.userId];
    if (state.mode === 'search') return [searchState.source, searchState.kind, searchState.word,
        searchState.sMode, searchState.order, searchState.submode];
    if (state.mode === 'series') return [seriesState.source, seriesState.kind, seriesState.url];
    return [];
}

function currentWorkSelection() {
    const context = JSON.stringify([workSelectionContext(), extraFilters]);
    let selection = workSelections.get(state.mode);
    if (!selection || selection.context !== context) {
        selection = {context, items: new Map()};
        workSelections.set(state.mode, selection);
    }
    return selection.items;
}

function bindWorkSelection(card, item, kind, options) {
    const id = String(item.id);
    const data = {item, kind, options};
    workSelectionItems.set(card, data);
    const select = el('input', 'ab-work-select');
    select.type = 'checkbox';
    select.setAttribute('aria-label', bt('selection.work', '选择 {title}', {title: item.title || id}));
    const update = () => {
        if (workInteractionMode === 'direct') {
            toggleWorkInQueue(item, kind, options);
            return;
        }
        const selection = currentWorkSelection();
        if (select.checked) selection.set(id, data);
        else selection.delete(id);
        syncWorkSelection();
    };
    select.addEventListener('click', event => event.stopPropagation());
    select.addEventListener('change', update);
    card.addEventListener('click', event => {
        if (select.disabled || event.target.closest('button, a, input, label, details')) return;
        select.checked = !select.checked;
        update();
    });
    card.appendChild(select);
}

function selectVisibleWorks() {
    const selection = currentWorkSelection();
    document.querySelectorAll('#abModePanel .ab-work[data-work-id]').forEach(card => {
        const data = workSelectionItems.get(card);
        if (data && !queueHas(card.dataset.workId)) selection.set(card.dataset.workId, data);
    });
    syncWorkSelection();
}

function enqueueSelectedWorks() {
    const selection = currentWorkSelection();
    const groups = new Map();
    for (const [id, data] of selection) {
        const opts = data.options;
        const args = [opts.source || state.mode, opts.username || '', opts.authorId, opts.authorName];
        const key = JSON.stringify(args);
        if (!groups.has(key)) groups.set(key, {ids: [], meta: [], args});
        const group = groups.get(key);
        group.ids.push(id);
        group.meta.push(buildQueueMeta(data.item, data.kind, opts));
    }
    selection.clear();
    let added = 0;
    for (const group of groups.values()) added += addItemsToQueue(group.ids, group.meta, ...group.args);
    syncAllResultsQueueState();
    abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
}

window.addEventListener('pixivbatch:queuetypeschanged', () => {
    workSelections.clear();
    syncWorkSelection();
});

function syncWorkSelection() {
    const bar = document.getElementById('abSelectionBar');
    if (!bar) return;
    const direct = workInteractionMode === 'direct';
    document.querySelectorAll('[data-work-mode]').forEach(button => {
        const active = button.dataset.workMode === workInteractionMode;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', String(active));
    });
    document.querySelectorAll('.ab-select-page').forEach(button => { button.hidden = direct; });
    const selected = currentWorkSelection();
    for (const id of selected.keys()) if (queueHas(id)) selected.delete(id);
    document.querySelectorAll('.ab-work[data-work-id]').forEach(card => {
        const check = card.querySelector('.ab-work-select');
        const queued = queueHas(card.dataset.workId);
        const active = !direct && selected.has(card.dataset.workId);
        card.classList.toggle('is-selected', active);
        if (check) {
            check.checked = direct ? queued : active;
            check.disabled = !direct && queued;
            const title = workSelectionItems.get(card)?.item.title || card.dataset.workId;
            const label = direct
                ? bt(queued ? 'queue.remove' : 'card.enqueue', queued ? '从队列移除' : '直接加入队列') + ': ' + title
                : bt('selection.work', '选择 {title}', {title});
            check.setAttribute('aria-label', label);
            check.title = label;
        }
    });
    const show = !direct && selected.size > 0 && !dockState.open && state.mode !== 'schedule';
    if (!bar.firstChild) {
        const count = el('strong', 'ab-selection-count');
        count.setAttribute('role', 'status');
        const clear = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
        clear.type = 'button';
        clear.addEventListener('click', () => {
            currentWorkSelection().clear();
            syncWorkSelection();
            document.querySelector('#abModePanel .ab-work-select')?.focus({preventScroll: true});
        });
        const enqueue = el('button', 'ab-btn ab-btn--primary');
        enqueue.type = 'button';
        enqueue.addEventListener('click', () => {
            enqueueSelectedWorks();
            document.getElementById('abDockToggle')?.focus({preventScroll: true});
        });
        bar.append(count, clear, enqueue);
    }
    bar.children[0].textContent = bt('selection.count', '已选择 {count} 件作品', {count: selected.size});
    bar.children[1].textContent = bt('selection.clear', '取消选择');
    bar.children[2].textContent = bt('selection.enqueue', '加入下载列表');
    const entering = bar.hidden && show;
    bar.hidden = !show;
    if (entering) animateWorkspace(bar);
}

function animateWorkspace(node) {
    if (!node || !node.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    node.getAnimations().forEach(animation => animation.cancel());
    node.animate([{opacity: .35, transform: 'translateY(5px)'}, {opacity: 1, transform: 'translateY(0)'}],
        {duration: 200, easing: 'cubic-bezier(.2,.7,.2,1)'});
}
