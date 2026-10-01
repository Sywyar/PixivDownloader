'use strict';

let backendTasksPanel = null;

// 服务端快照不写入浏览器待下载草稿，刷新或重连不会触发重复提交。
function mountBackendTasks(host) {
    if (backendTasksPanel) backendTasksPanel.dispose();
    backendTasksPanel = null;
    if (!host) return;
    host.hidden = !isAdmin;
    if (!isAdmin) return;
    backendTasksPanel = createBackendTasksPanel(host);
}

function createBackendTasksPanel(host) {
    const pageSize = 50;
    const rows = new Map();
    const cancellations = new Map();
    let snapshot = null;
    let page = 0;
    let timer;
    let request = null;
    let suspended = false;
    let disposed = false;
    const heading = document.createElement('h3');
    const description = document.createElement('p');
    const status = document.createElement('p');
    status.setAttribute('role', 'status');
    const list = document.createElement('ul');
    const navigation = document.createElement('div');
    const previous = document.createElement('button');
    const next = document.createElement('button');
    const count = document.createElement('span');
    host.classList.add('backend-tasks');
    list.className = 'backend-tasks-list';
    navigation.className = 'backend-tasks-navigation';
    for (const button of [previous, next]) {
        button.type = 'button';
        button.className = 'btn btn-gray ab-btn ab-btn--ghost ab-btn--sm';
    }
    navigation.append(previous, count, next);
    host.replaceChildren(heading, description, status, list, navigation);
    previous.addEventListener('click', () => { page--; render(); });
    next.addEventListener('click', () => { page++; render(); });

    function label(key, vars) { return bt('batch:backend-tasks.' + key, undefined, vars); }
    function render() {
        heading.textContent = label('title');
        description.textContent = label('description');
        previous.textContent = label('previous');
        next.textContent = label('next');
        if (!snapshot) return;
        const tasks = snapshot.tasks;
        const totalPages = Math.max(1, Math.ceil(tasks.length / pageSize));
        page = Math.max(0, Math.min(page, totalPages - 1));
        count.textContent = label('page', {page: page + 1, pages: totalPages, count: tasks.length});
        previous.disabled = page === 0;
        next.disabled = page + 1 === totalPages;
        navigation.hidden = tasks.length <= pageSize;
        const visible = tasks.slice(Math.max(0, tasks.length - (page + 1) * pageSize),
            tasks.length - page * pageSize).reverse();
        const ids = new Set(visible.map(task => task.attempt.attemptId));
        for (const [id, row] of rows) {
            if (!ids.has(id)) { row.root.remove(); rows.delete(id); }
        }
        visible.forEach((task, index) => {
            const id = task.attempt.attemptId;
            let row = rows.get(id);
            if (!row) {
                const root = document.createElement('li');
                const title = document.createElement('span');
                const phase = document.createElement('span');
                const cancel = document.createElement('button');
                cancel.type = 'button';
                cancel.className = 'btn btn-gray ab-btn ab-btn--ghost ab-btn--sm';
                title.className = 'backend-task-title';
                root.append(title, phase, cancel);
                cancel.addEventListener('click', () => cancelTask(id));
                row = {root, title, phase, cancel};
                rows.set(id, row);
            }
            row.title.textContent = task.title || task.attempt.workId;
            row.title.title = task.attempt.workType + ' · ' + task.attempt.workId;
            row.phase.textContent = label('phase.' + task.phase);
            row.cancel.textContent = label(cancellations.has(id) ? 'cancelling' : 'cancel');
            row.cancel.setAttribute('aria-label', label('cancel-work', {title: row.title.textContent}));
            row.cancel.hidden = !['QUEUED', 'STARTED'].includes(task.phase);
            row.cancel.disabled = cancellations.has(id);
            if (list.children[index] !== row.root) list.insertBefore(row.root, list.children[index] || null);
        });
    }

    async function refresh() {
        clearTimeout(timer);
        if (disposed || suspended || document.hidden || request) return;
        const controller = new AbortController();
        request = controller;
        try {
            const response = await fetch('/api/download/tasks', {cache: 'no-store', signal: controller.signal});
            if (!response.ok) throw new Error('task snapshot unavailable');
            const value = await response.json();
            if (disposed || controller.signal.aborted) return;
            if (!snapshot || snapshot.epoch !== value.epoch || snapshot.revision !== value.revision) {
                snapshot = value;
                render();
            }
            status.textContent = value.tasks.length ? '' : label('empty');
        } catch (error) {
            if (!disposed && !controller.signal.aborted) status.textContent = label('unavailable');
        } finally {
            if (request === controller) request = null;
            if (!disposed && !suspended && !document.hidden) timer = setTimeout(refresh, 2000);
        }
    }

    async function cancelTask(id) {
        if (disposed || suspended || cancellations.has(id)) return;
        const controller = new AbortController();
        cancellations.set(id, controller);
        render();
        try {
            const response = await fetch('/api/download/tasks/' + encodeURIComponent(id) + '/cancel',
                {method: 'POST', signal: controller.signal});
            if (!response.ok) throw new Error('task cancellation unavailable');
            if (!disposed && !controller.signal.aborted) status.textContent = label('cancel-requested');
        } catch (error) {
            if (!disposed && !controller.signal.aborted) status.textContent = label('cancel-failed');
        } finally {
            cancellations.delete(id);
            if (!disposed) render();
        }
    }

    function pause() {
        clearTimeout(timer);
        if (request) request.abort();
        for (const controller of cancellations.values()) controller.abort();
    }
    function visibility() { if (document.hidden) pause(); else void refresh(); }
    function hide() { suspended = true; pause(); }
    function show() { suspended = false; void refresh(); }
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('pagehide', hide);
    window.addEventListener('pageshow', show);
    render();
    status.textContent = label('loading');
    void refresh();
    return {
        render,
        dispose() {
            disposed = true;
            pause();
            document.removeEventListener('visibilitychange', visibility);
            window.removeEventListener('pagehide', hide);
            window.removeEventListener('pageshow', show);
            rows.clear();
            cancellations.clear();
            snapshot = null;
        }
    };
}
