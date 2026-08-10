(function () {
    'use strict';

    var pageI18n = null;
    var state = { category: '', messages: [], unreadCount: 0, selectedId: '' };

    function el(id) { return document.getElementById(id); }
    function t(key, fallback, vars) { return pageI18n ? pageI18n.t(key, fallback, vars) : (fallback || key); }

    async function api(url, options) {
        var response = await fetch(url, Object.assign({
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }, options || {}));
        if (response.status === 401) {
            location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
            throw new Error('Unauthorized');
        }
        var payload = null;
        try { payload = await response.json(); } catch (e) { payload = null; }
        if (!response.ok) throw new Error(payload && payload.error ? payload.error : 'HTTP ' + response.status);
        return payload;
    }

    function categoryLabel(category) {
        var labels = {
            download: ['inbox.category.download', '下载通知'],
            announcement: ['inbox.category.announcement', '公告'],
            survey: ['inbox.category.survey', '调查'],
            system: ['inbox.category.system', '系统']
        };
        var label = labels[category] || ['inbox.category.system', '系统'];
        return t(label[0], label[1]);
    }

    function timeText(epochMillis) {
        if (!epochMillis) return '';
        try {
            return new Intl.DateTimeFormat(pageI18n ? pageI18n.lang : undefined, {
                dateStyle: 'medium', timeStyle: 'short'
            }).format(new Date(epochMillis));
        } catch (e) {
            return new Date(epochMillis).toLocaleString();
        }
    }

    function messageButton(message) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'notification-list-item' + (message.readTime ? '' : ' unread')
            + (message.id === state.selectedId ? ' active' : '');

        var meta = document.createElement('span');
        meta.className = 'notification-item-meta';
        var category = document.createElement('span');
        category.className = 'notification-category';
        category.textContent = categoryLabel(message.category);
        var time = document.createElement('time');
        time.className = 'notification-item-time';
        time.dateTime = new Date(message.createdTime).toISOString();
        time.textContent = timeText(message.createdTime);
        meta.append(category, time);

        var title = document.createElement('span');
        title.className = 'notification-item-title';
        title.textContent = message.title;
        var preview = document.createElement('span');
        preview.className = 'notification-item-preview';
        preview.textContent = message.body;
        button.append(meta, title, preview);
        button.addEventListener('click', function () { selectMessage(message.id, true); });
        return button;
    }

    function renderList() {
        var list = el('notificationList');
        var status = el('notificationStatus');
        list.textContent = '';
        el('notificationUnreadCount').textContent = state.unreadCount > 0 ? String(state.unreadCount) : '';
        if (!state.messages.length) {
            status.hidden = false;
            status.textContent = t('inbox.empty', '暂无消息');
            return;
        }
        status.hidden = true;
        state.messages.forEach(function (message) { list.appendChild(messageButton(message)); });
    }

    function safeActionHref(value) {
        if (!value) return null;
        try {
            var url = new URL(value, location.origin);
            return url.protocol === 'http:' || url.protocol === 'https:' ? url : null;
        } catch (e) {
            return null;
        }
    }

    function renderDetail(message) {
        var detail = el('notificationDetail');
        detail.textContent = '';

        var meta = document.createElement('div');
        meta.className = 'notification-detail-meta';
        var category = document.createElement('span');
        category.className = 'notification-category';
        category.textContent = categoryLabel(message.category);
        var time = document.createElement('time');
        time.className = 'notification-detail-time';
        time.dateTime = new Date(message.createdTime).toISOString();
        time.textContent = timeText(message.createdTime);
        meta.append(category, time);

        var title = document.createElement('h2');
        title.textContent = message.title;
        var body = document.createElement('div');
        body.className = 'notification-detail-body';
        body.textContent = message.body;
        detail.append(meta, title, body);

        var actionUrl = safeActionHref(message.actionUrl);
        if (actionUrl) {
            var action = document.createElement('a');
            action.className = 'notification-action-link';
            action.href = actionUrl.href;
            action.textContent = t('inbox.open-link', '打开相关链接');
            if (actionUrl.origin !== location.origin) {
                action.target = '_blank';
                action.rel = 'noopener noreferrer';
            }
            detail.appendChild(action);
        }
    }

    async function selectMessage(id, updateHistory) {
        state.selectedId = id;
        renderList();
        if (updateHistory) history.pushState({ id: id }, '', '?id=' + encodeURIComponent(id));
        try {
            var message = await api('/api/notifications/' + encodeURIComponent(id));
            renderDetail(message);
            if (!message.readTime) {
                var updated = await api('/api/notifications/' + encodeURIComponent(id) + '/read', { method: 'POST' });
                state.messages = state.messages.map(function (item) { return item.id === id ? updated : item; });
                state.unreadCount = Math.max(0, state.unreadCount - 1);
                renderList();
            }
        } catch (error) {
            el('notificationDetail').textContent = t('inbox.load-failed', '消息加载失败');
        }
    }

    async function loadMessages() {
        var status = el('notificationStatus');
        status.hidden = false;
        status.textContent = t('inbox.loading', '正在加载消息…');
        var query = new URLSearchParams({ limit: '100' });
        if (state.category) query.set('category', state.category);
        try {
            var snapshot = await api('/api/notifications?' + query.toString());
            state.messages = Array.isArray(snapshot.messages) ? snapshot.messages : [];
            state.unreadCount = Number(snapshot.unreadCount) || 0;
            renderList();
        } catch (error) {
            status.hidden = false;
            status.textContent = t('inbox.load-failed', '消息加载失败');
        }
    }

    function bindFilters() {
        document.querySelectorAll('.notification-filters button').forEach(function (button) {
            button.addEventListener('click', function () {
                state.category = button.dataset.category || '';
                document.querySelectorAll('.notification-filters button').forEach(function (item) {
                    item.classList.toggle('active', item === button);
                    item.setAttribute('aria-pressed', item === button ? 'true' : 'false');
                });
                loadMessages();
            });
        });
    }

    function applyTranslations() {
        document.title = t('inbox.page-title', '站内信 · Pixiv Downloader');
        if (pageI18n) pageI18n.apply(document.body);
        renderList();
        var selected = state.messages.find(function (message) { return message.id === state.selectedId; });
        if (selected) renderDetail(selected);
    }

    async function initI18n() {
        pageI18n = await PixivI18n.create({ namespaces: ['notification', 'common'] });
        await PixivLangSwitcher.mount({
            mountPoint: el('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyTranslations();
            }
        });
        PixivTheme.mount({ mountPoint: el('langSwitcherAnchor') });
        applyTranslations();
    }

    document.addEventListener('DOMContentLoaded', async function () {
        bindFilters();
        await initI18n();
        await loadMessages();
        var requestedId = new URLSearchParams(location.search).get('id');
        if (requestedId) selectMessage(requestedId, false);
        window.addEventListener('popstate', function () {
            var id = new URLSearchParams(location.search).get('id');
            if (id) selectMessage(id, false);
        });
    });
})();
