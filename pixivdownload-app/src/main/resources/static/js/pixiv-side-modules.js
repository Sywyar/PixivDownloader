/* global window, document */
/**
 * 侧边模块：注册式的侧边栏内容模块框架 + 外凸切换按钮。
 * 把页面左侧边栏（#sidebar）的内容抽象成可注册的模块，点击外凸按钮后
 * 直接把侧边栏内容替换为对应模块。
 *
 * 再次点击当前模块按钮时：
 *   - nav 模块：切换侧边栏收起 / 展开（移动端为抽屉开合）。
 *   - 声明了 compact 的模块（如 tasks）：侧边栏收起为窄条并显示该模块的
 *     简要信息（紧凑态），鼠标悬浮简要信息时浮层显示详细信息；再次点击
 *     回到展开的完整视图。
 *   - 其它模块：返回 nav。
 *
 * 内置模块：
 *   - nav：页面自身的侧边栏内容（导航），默认激活，不额外渲染。
 *   - tasks：管理员的后台压缩任务队列（批量导出 / 打包），含作品级
 *     打包进度，完成后提供下载按钮，过期任务由服务端自动清理。
 *     仅管理员可见（body.admin-mode 出现后才显示按钮；接口本身也是 admin-only）。
 *
 * 由 pixiv-gallery / pixiv-novel-gallery / pixiv-series / pixiv-stats 复用。
 * 页面需提供 #sidebar 以及主题变量（--sidebar-width / --surface / --line / --text / --brand）。
 *
 * 对页面暴露 window.PixivSideModules：
 *   - registerModule(def)：注册侧边栏内容模块。
 *       def = {
 *           id,              // 唯一标识
 *           order,           // 按钮排序，小的在上
 *           label(),         // 外凸按钮文字
 *           title(),         // 模块面板标题（默认同 label）
 *           visible(),       // 按钮是否可见（默认 true）
 *           mount(body),     // 首次激活时渲染模块内容到 body 容器
 *           onShow(),        // 模块被激活
 *           onHide(),        // 模块被切走
 *           renderTabExtras(tab), // 在按钮上追加元素（如徽标）
 *           compact: {       // 可选：紧凑态支持
 *               mount(body),   // 渲染窄条上的简要信息
 *               flyout(body),  // 渲染悬浮详细信息
 *           },
 *       }
 *   - activateModule(id) / activeModuleId()
 *   - openTasks() / closeTasks() / toggleTasks()：tasks 模块快捷方式
 *   - trackArchive(token, { onReady, onFailed })：跟踪一个刚触发的压缩任务，
 *     就绪后自动触发一次下载并回调；失败回调收到 status（empty / error / expired）。
 */
(function () {
    'use strict';

    const FALLBACK = {
        'side-modules.nav': '导航',
        'side-modules.tasks': '任务列表',
        'side-modules.tasks.title': '压缩任务',
        'side-modules.tasks.empty': '暂无压缩任务',
        'side-modules.tasks.hint': '压缩包到期后会自动删除；服务重启后任务列表会清空。',
        'side-modules.status.pending': '排队中',
        'side-modules.status.creating': '打包中…',
        'side-modules.status.ready': '已就绪',
        'side-modules.status.empty': '无可打包文件',
        'side-modules.status.error': '打包失败',
        'side-modules.type.artworks': '插画导出',
        'side-modules.type.novels': '小说导出',
        'side-modules.type.pack': '作品打包',
        'side-modules.download': '下载',
        'side-modules.expires-in': '剩余 {time}',
        'side-modules.works-files': '{works} 个作品 · {files} 个文件',
        'side-modules.works': '{works} 个作品',
        'side-modules.progress': '{done}/{total} 个作品',
    };

    const LIST_POLL_MS = 3000;
    const TRACK_POLL_MS = 2000;
    const FLYOUT_HIDE_DELAY_MS = 200;

    let i18nClient = null;
    let sidebar = null;
    let tabsEl = null;

    function t(key, vars) {
        const fallback = FALLBACK[key] || key;
        const template = i18nClient ? i18nClient.t('common:' + key, fallback, vars) : fallback;
        return interpolate(template, vars);
    }

    function interpolate(template, vars) {
        if (!vars) return template;
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (m, name) =>
            Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m);
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function isMobile() {
        return window.matchMedia('(max-width: 768px)').matches;
    }

    function isAdmin() {
        return document.body.classList.contains('admin-mode');
    }

    // ---------- 模块注册中心 ----------

    /** id → {def, panel, bodyEl, compactEl, mounted, compactMounted} */
    const registry = new Map();
    let activeId = 'nav';
    /** 当前激活模块是否处于紧凑态（侧边栏收起为窄条 + 简要信息）。 */
    let compactActive = false;

    function registerModule(def) {
        if (!def || !def.id || registry.has(def.id)) return;
        registry.set(def.id, {def, panel: null, bodyEl: null, compactEl: null,
            mounted: false, compactMounted: false});
        if (tabsEl) renderTabs();
    }

    function moduleVisible(mod) {
        return typeof mod.def.visible === 'function' ? !!mod.def.visible() : true;
    }

    function sortedModules() {
        return [...registry.values()].sort((a, b) => (a.def.order || 0) - (b.def.order || 0));
    }

    function ensureMounted(mod) {
        if (mod.mounted || typeof mod.def.mount !== 'function') {
            mod.mounted = true;
            return;
        }
        const panel = document.createElement('div');
        panel.className = 'pixiv-side-module-panel';
        panel.dataset.moduleId = mod.def.id;
        const head = document.createElement('div');
        head.className = 'pixiv-side-module-head';
        const title = document.createElement('span');
        title.className = 'pixiv-side-module-title';
        title.textContent = moduleTitle(mod);
        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'pixiv-side-module-close';
        close.textContent = '×';
        close.addEventListener('click', () => activateModule('nav'));
        head.appendChild(title);
        head.appendChild(close);
        const body = document.createElement('div');
        body.className = 'pixiv-side-module-body';
        panel.appendChild(head);
        panel.appendChild(body);
        sidebar.appendChild(panel);
        mod.panel = panel;
        mod.bodyEl = body;
        mod.mounted = true;
        mod.def.mount(body);
    }

    function ensureCompactMounted(mod) {
        if (mod.compactMounted || !mod.def.compact) return;
        const compact = document.createElement('div');
        compact.className = 'pixiv-side-module-compact';
        compact.addEventListener('mouseenter', () => showFlyout(mod));
        compact.addEventListener('mouseleave', scheduleHideFlyout);
        mod.panel.appendChild(compact);
        mod.compactEl = compact;
        mod.compactMounted = true;
        mod.def.compact.mount(compact);
    }

    function moduleTitle(mod) {
        if (typeof mod.def.title === 'function') return mod.def.title();
        if (typeof mod.def.label === 'function') return mod.def.label();
        return mod.def.id;
    }

    function activateModule(id) {
        const next = registry.get(id);
        if (!next || !moduleVisible(next)) return;
        if (activeId === id) {
            // 已激活：紧凑态回到完整视图，并保证侧边栏可见。
            if (compactActive) exitCompact(next);
            ensureSidebarVisible();
            return;
        }
        const prev = registry.get(activeId);
        if (prev) {
            if (compactActive) exitCompact(prev);
            if (typeof prev.def.onHide === 'function') prev.def.onHide();
            if (prev.panel) prev.panel.classList.remove('active');
        }
        activeId = id;
        if (id === 'nav') {
            sidebar.classList.remove('pixiv-side-module-active');
        } else {
            ensureMounted(next);
            if (next.panel) next.panel.classList.add('active');
            sidebar.classList.add('pixiv-side-module-active');
        }
        ensureSidebarVisible();
        if (typeof next.def.onShow === 'function') next.def.onShow();
        syncTabActive();
    }

    function activeModuleId() {
        return activeId;
    }

    // ---------- 紧凑态 + 悬浮详情 ----------

    function enterCompact(mod) {
        if (!mod.def.compact || !mod.panel) return;
        ensureCompactMounted(mod);
        compactActive = true;
        mod.panel.classList.add('compact');
        // 直接收起侧边栏，不走页面 toggleSidebar，避免把紧凑态写入页面的收起状态持久化。
        sidebar.classList.add('collapsed');
        hideFlyout();
    }

    function exitCompact(mod) {
        compactActive = false;
        if (mod && mod.panel) mod.panel.classList.remove('compact');
        sidebar.classList.remove('collapsed');
        hideFlyout();
    }

    let flyoutEl = null;
    let flyoutBodyEl = null;
    let flyoutModuleId = null;
    let flyoutHideTimer = null;

    function ensureFlyout() {
        if (flyoutEl) return;
        flyoutEl = document.createElement('div');
        flyoutEl.className = 'pixiv-side-module-flyout';
        flyoutEl.addEventListener('mouseenter', cancelHideFlyout);
        flyoutEl.addEventListener('mouseleave', scheduleHideFlyout);
        const title = document.createElement('div');
        title.className = 'pixiv-side-module-flyout-title';
        flyoutBodyEl = document.createElement('div');
        flyoutBodyEl.className = 'pixiv-side-module-flyout-body';
        flyoutEl.appendChild(title);
        flyoutEl.appendChild(flyoutBodyEl);
        document.body.appendChild(flyoutEl);
    }

    function showFlyout(mod) {
        if (!mod.def.compact || typeof mod.def.compact.flyout !== 'function') return;
        cancelHideFlyout();
        ensureFlyout();
        if (flyoutModuleId !== mod.def.id) {
            flyoutModuleId = mod.def.id;
            flyoutBodyEl.innerHTML = '';
            mod.def.compact.flyout(flyoutBodyEl);
        }
        flyoutEl.querySelector('.pixiv-side-module-flyout-title').textContent = moduleTitle(mod);
        const rect = sidebar.getBoundingClientRect();
        const anchor = mod.compactEl ? mod.compactEl.getBoundingClientRect() : rect;
        flyoutEl.style.left = (rect.right + 8) + 'px';
        flyoutEl.style.top = Math.max(16, anchor.top) + 'px';
        flyoutEl.classList.add('open');
    }

    function scheduleHideFlyout() {
        cancelHideFlyout();
        flyoutHideTimer = window.setTimeout(hideFlyout, FLYOUT_HIDE_DELAY_MS);
    }

    function cancelHideFlyout() {
        if (flyoutHideTimer) {
            window.clearTimeout(flyoutHideTimer);
            flyoutHideTimer = null;
        }
    }

    function hideFlyout() {
        cancelHideFlyout();
        if (flyoutEl) flyoutEl.classList.remove('open');
    }

    // ---------- 侧边栏可见性 ----------

    /** 激活模块时保证侧边栏可见：桌面端展开收起态，移动端拉开抽屉。 */
    function ensureSidebarVisible() {
        if (isMobile()) {
            if (!sidebar.classList.contains('mobile-open')) {
                if (typeof window.openMobileSidebar === 'function') {
                    window.openMobileSidebar();
                } else {
                    sidebar.classList.add('mobile-open');
                    const overlay = document.getElementById('mobileOverlay');
                    if (overlay) overlay.classList.add('active');
                }
            }
            return;
        }
        if (sidebar.classList.contains('collapsed')) {
            // 复用页面自身的 toggleSidebar（带收起状态持久化）。
            if (typeof window.toggleSidebar === 'function') {
                window.toggleSidebar();
            } else {
                sidebar.classList.remove('collapsed');
            }
        }
    }

    /** 再次点击 nav 按钮：沿用原侧边栏开关行为。 */
    function toggleNavSidebar() {
        if (isMobile()) {
            const opened = sidebar.classList.contains('mobile-open');
            if (opened && typeof window.closeMobileSidebar === 'function') {
                window.closeMobileSidebar();
            } else if (!opened && typeof window.openMobileSidebar === 'function') {
                window.openMobileSidebar();
            } else {
                sidebar.classList.toggle('mobile-open', !opened);
                const overlay = document.getElementById('mobileOverlay');
                if (overlay) overlay.classList.toggle('active', !opened);
            }
            return;
        }
        if (typeof window.toggleSidebar === 'function') {
            window.toggleSidebar();
        } else {
            sidebar.classList.toggle('collapsed');
        }
    }

    function onTabClick(id) {
        if (id !== activeId) {
            activateModule(id);
            return;
        }
        if (id === 'nav') {
            toggleNavSidebar();
            return;
        }
        const mod = registry.get(id);
        if (isMobile()) {
            // 移动端无悬浮交互：再次点击关闭抽屉，模块保持激活。
            toggleNavSidebar();
            return;
        }
        if (mod && mod.def.compact) {
            // 再次点击：完整视图 ↔ 紧凑态（窄条简要信息）。
            if (compactActive) {
                exitCompact(mod);
            } else {
                enterCompact(mod);
            }
            return;
        }
        activateModule('nav');
    }

    // ---------- 外凸按钮 ----------

    function renderTabs() {
        if (!tabsEl) return;
        tabsEl.innerHTML = '';
        for (const mod of sortedModules()) {
            const tab = document.createElement('button');
            tab.type = 'button';
            tab.className = 'pixiv-side-tab';
            tab.dataset.moduleId = mod.def.id;
            tab.hidden = !moduleVisible(mod);
            const label = document.createElement('span');
            label.textContent = typeof mod.def.label === 'function' ? mod.def.label() : mod.def.id;
            tab.appendChild(label);
            if (typeof mod.def.renderTabExtras === 'function') {
                mod.def.renderTabExtras(tab);
            }
            tab.addEventListener('click', () => onTabClick(mod.def.id));
            tabsEl.appendChild(tab);
        }
        syncTabActive();
    }

    function syncTabActive() {
        if (!tabsEl) return;
        tabsEl.querySelectorAll('.pixiv-side-tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.moduleId === activeId && activeId !== 'nav');
        });
    }

    function refreshTabVisibility() {
        if (!tabsEl) return;
        tabsEl.querySelectorAll('.pixiv-side-tab').forEach(tab => {
            const mod = registry.get(tab.dataset.moduleId);
            if (mod) tab.hidden = !moduleVisible(mod);
        });
    }

    // ---------- tasks 模块（压缩任务队列） ----------

    let tasksBadge = null;
    let listEl = null;
    let hintEl = null;
    let compactListEl = null;
    let flyoutListEl = null;
    let listTimer = null;
    let trackTimer = null;
    let lastTasks = [];
    const tracked = new Map();

    function tasksActive() {
        return activeId === 'tasks';
    }

    function statusLabel(status) {
        switch (status) {
            case 'creating': return t('side-modules.status.creating');
            case 'ready': return t('side-modules.status.ready');
            case 'empty': return t('side-modules.status.empty');
            case 'error': return t('side-modules.status.error');
            default: return t('side-modules.status.pending');
        }
    }

    function typeLabel(exportType) {
        if (exportType === 'artworks') return t('side-modules.type.artworks');
        if (exportType === 'novels') return t('side-modules.type.novels');
        return t('side-modules.type.pack');
    }

    function formatRemaining(seconds) {
        const total = Math.max(0, Number(seconds) || 0);
        const h = Math.floor(total / 3600);
        const m = Math.floor((total % 3600) / 60);
        const s = total % 60;
        const mm = String(m).padStart(2, '0');
        const ss = String(s).padStart(2, '0');
        return h > 0 ? h + ':' + mm + ':' + ss : mm + ':' + ss;
    }

    function triggerDownload(token) {
        const link = document.createElement('a');
        link.href = '/api/archive/download/' + encodeURIComponent(token);
        link.download = '';
        document.body.appendChild(link);
        link.click();
        link.remove();
    }

    function taskProgress(task) {
        if (!(task.workCount > 0)) return null;
        const done = Math.min(task.workCount, Math.max(0, Number(task.processedWorks) || 0));
        return {done, total: task.workCount, percent: Math.round(done / task.workCount * 100)};
    }

    function taskItemHtml(task) {
        const creating = task.status === 'pending' || task.status === 'creating';
        const failed = task.status === 'error' || task.status === 'empty';
        const meta = task.fileCount > 0
            ? t('side-modules.works-files', {works: task.workCount, files: task.fileCount})
            : t('side-modules.works', {works: task.workCount});
        let progress = '';
        const p = creating ? taskProgress(task) : null;
        if (p) {
            progress = '<div class="pixiv-side-task-progress">'
                + '<div class="pixiv-side-task-progress-bar">'
                + '<div class="pixiv-side-task-progress-fill" style="width:' + p.percent + '%"></div>'
                + '</div>'
                + '<div class="pixiv-side-task-progress-text">'
                + escapeHtml(t('side-modules.progress', {done: p.done, total: p.total}))
                + '</div></div>';
        }
        const download = task.status === 'ready'
            ? '<div class="pixiv-side-task-actions">'
                + '<button type="button" class="pixiv-side-task-download" data-token="'
                + escapeHtml(task.token) + '">' + escapeHtml(t('side-modules.download')) + '</button>'
                + '<span class="pixiv-side-task-meta">'
                + escapeHtml(t('side-modules.expires-in', {time: formatRemaining(task.expireSeconds)}))
                + '</span></div>'
            : '';
        return '<div class="pixiv-side-task-item">'
            + '<div class="pixiv-side-task-title">'
            + '<span>' + escapeHtml(typeLabel(task.exportType)) + '</span>'
            + '<span class="pixiv-side-task-status' + (creating ? ' is-creating' : '')
            + (failed ? ' is-error' : '') + '">' + escapeHtml(statusLabel(task.status)) + '</span>'
            + '</div>'
            + '<div class="pixiv-side-task-meta">' + escapeHtml(meta) + '</div>'
            + progress
            + download
            + '</div>';
    }

    function compactItemHtml(task) {
        const creating = task.status === 'pending' || task.status === 'creating';
        const failed = task.status === 'error' || task.status === 'empty';
        const cls = creating ? ' is-creating' : (failed ? ' is-error' : ' is-ready');
        const p = creating ? taskProgress(task) : null;
        const text = p ? p.done + '/' + p.total : (creating ? '…' : (failed ? '!' : '✓'));
        const bar = p
            ? '<div class="pixiv-side-compact-bar">'
                + '<div class="pixiv-side-compact-fill" style="width:' + p.percent + '%"></div>'
                + '</div>'
            : '';
        return '<div class="pixiv-side-compact-task' + cls + '">'
            + '<span class="pixiv-side-compact-dot"></span>'
            + '<span class="pixiv-side-compact-text">' + escapeHtml(text) + '</span>'
            + bar
            + '</div>';
    }

    function renderTaskList(container) {
        if (!container) return;
        if (!lastTasks.length) {
            container.innerHTML = '<div class="pixiv-side-tasks-empty">'
                + escapeHtml(t('side-modules.tasks.empty')) + '</div>';
            return;
        }
        container.innerHTML = lastTasks.map(taskItemHtml).join('');
        container.querySelectorAll('.pixiv-side-task-download').forEach(btn => {
            btn.addEventListener('click', () => triggerDownload(btn.dataset.token));
        });
    }

    function renderTasks() {
        const activeCount = lastTasks.filter(task =>
            task.status === 'pending' || task.status === 'creating').length;
        if (tasksBadge) {
            tasksBadge.textContent = String(activeCount);
            tasksBadge.hidden = activeCount === 0;
        }
        renderTaskList(listEl);
        renderTaskList(flyoutListEl);
        if (compactListEl) {
            compactListEl.innerHTML = lastTasks.length
                ? lastTasks.map(compactItemHtml).join('')
                : '<div class="pixiv-side-compact-task is-ready">'
                    + '<span class="pixiv-side-compact-dot"></span>'
                    + '<span class="pixiv-side-compact-text">0</span></div>';
        }
    }

    async function fetchTasks() {
        try {
            const res = await fetch('/api/archive/list', {
                credentials: 'same-origin',
                headers: {'Accept': 'application/json'},
            });
            if (res.status === 401 || res.status === 403) {
                closeTasks();
                const tab = tabsEl && tabsEl.querySelector('[data-module-id="tasks"]');
                if (tab) tab.hidden = true;
                return;
            }
            if (!res.ok) return;
            const data = await res.json();
            lastTasks = (data && data.tasks) || [];
            renderTasks();
        } catch (_) { /* 网络异常时保留上一次渲染结果 */ }
    }

    function startListPolling() {
        if (listTimer) return;
        listTimer = window.setInterval(fetchTasks, LIST_POLL_MS);
    }

    function stopListPolling() {
        if (!listTimer) return;
        window.clearInterval(listTimer);
        listTimer = null;
    }

    function openTasks() {
        activateModule('tasks');
    }

    function closeTasks() {
        if (tasksActive()) activateModule('nav');
    }

    function toggleTasks() {
        if (tasksActive()) {
            closeTasks();
        } else {
            openTasks();
        }
    }

    // ---------- 刚触发任务的跟踪（就绪后自动下载一次） ----------

    function trackArchive(token, callbacks) {
        if (!token) return;
        tracked.set(token, callbacks || {});
        startListPolling();
        ensureTrackPolling();
    }

    function ensureTrackPolling() {
        if (trackTimer || !tracked.size) return;
        trackTimer = window.setInterval(pollTracked, TRACK_POLL_MS);
    }

    async function pollTracked() {
        for (const [token, callbacks] of [...tracked]) {
            try {
                const res = await fetch('/api/archive/status/' + encodeURIComponent(token), {
                    credentials: 'same-origin',
                    headers: {'Accept': 'application/json'},
                });
                if (!res.ok) continue;
                const data = await res.json();
                if (data.status === 'ready') {
                    tracked.delete(token);
                    triggerDownload(token);
                    if (typeof callbacks.onReady === 'function') callbacks.onReady(token);
                } else if (data.status === 'empty' || data.status === 'error' || data.status === 'expired') {
                    tracked.delete(token);
                    if (typeof callbacks.onFailed === 'function') callbacks.onFailed(data.status);
                }
            } catch (_) { /* 下一轮重试 */ }
        }
        if (!tracked.size) {
            window.clearInterval(trackTimer);
            trackTimer = null;
            if (!tasksActive()) stopListPolling();
        }
        if (tasksActive()) fetchTasks();
    }

    // ---------- 内置模块注册 ----------

    function registerBuiltinModules() {
        registerModule({
            id: 'nav',
            order: 0,
            label: () => t('side-modules.nav'),
        });
        registerModule({
            id: 'tasks',
            order: 10,
            label: () => t('side-modules.tasks'),
            title: () => t('side-modules.tasks.title'),
            visible: isAdmin,
            renderTabExtras: tab => {
                tasksBadge = document.createElement('span');
                tasksBadge.className = 'pixiv-side-tab-badge';
                tasksBadge.hidden = true;
                tab.appendChild(tasksBadge);
            },
            mount: body => {
                body.innerHTML = '<div class="pixiv-side-tasks-body" id="pixivSideTasksList"></div>'
                    + '<div class="pixiv-side-tasks-hint" id="pixivSideTasksHint"></div>';
                listEl = body.querySelector('#pixivSideTasksList');
                hintEl = body.querySelector('#pixivSideTasksHint');
                hintEl.textContent = t('side-modules.tasks.hint');
                renderTasks();
            },
            onShow: () => {
                fetchTasks();
                startListPolling();
            },
            onHide: () => {
                if (!tracked.size) stopListPolling();
            },
            compact: {
                mount: body => {
                    compactListEl = body;
                    renderTasks();
                },
                flyout: body => {
                    body.innerHTML = '<div class="pixiv-side-tasks-body"></div>'
                        + '<div class="pixiv-side-tasks-hint"></div>';
                    flyoutListEl = body.querySelector('.pixiv-side-tasks-body');
                    body.querySelector('.pixiv-side-tasks-hint').textContent =
                        t('side-modules.tasks.hint');
                    renderTasks();
                },
            },
        });
    }

    // ---------- 装配 ----------

    function buildDom() {
        tabsEl = document.createElement('div');
        tabsEl.className = 'pixiv-side-tabs';
        tabsEl.id = 'pixivSideTabs';
        sidebar.insertAdjacentElement('afterend', tabsEl);
        renderTabs();
    }

    function applyTexts() {
        renderTabs();
        for (const mod of registry.values()) {
            if (mod.panel) {
                const title = mod.panel.querySelector('.pixiv-side-module-title');
                if (title) title.textContent = moduleTitle(mod);
            }
        }
        if (hintEl) hintEl.textContent = t('side-modules.tasks.hint');
        if (flyoutEl) {
            const flyoutHint = flyoutEl.querySelector('.pixiv-side-tasks-hint');
            if (flyoutHint) flyoutHint.textContent = t('side-modules.tasks.hint');
        }
        renderTasks();
    }

    function watchAdminMode() {
        const sync = () => {
            refreshTabVisibility();
            if (isAdmin()) {
                fetchTasks();
            } else {
                closeTasks();
            }
        };
        if (isAdmin()) {
            sync();
            return;
        }
        const observer = new MutationObserver(() => {
            if (isAdmin()) {
                observer.disconnect();
                sync();
            }
        });
        observer.observe(document.body, {attributes: true, attributeFilter: ['class']});
    }

    async function init() {
        sidebar = document.getElementById('sidebar');
        if (!sidebar) return;
        registerBuiltinModules();
        buildDom();
        watchAdminMode();
        if (window.PixivI18n && typeof window.PixivI18n.create === 'function') {
            try {
                i18nClient = await window.PixivI18n.create({namespaces: ['common']});
                applyTexts();
            } catch (_) { /* 回退到内置文案 */ }
        }
    }

    window.PixivSideModules = {
        registerModule,
        activateModule,
        activeModuleId,
        openTasks,
        closeTasks,
        toggleTasks,
        trackArchive,
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
