(function () {
    'use strict';

    var pageI18n = null;
    var emptyDetail = null;
    var contentFrameHost = null;
    var contentFrames = new Map();
    var loadSequence = 0;
    var markingReadIds = new Set();
    var state = {
        category: '', unreadOnly: false, messages: [], unreadCount: 0,
        selectedId: '', selectedMessage: null
    };

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

    function isoTime(epochMillis) {
        try {
            var date = new Date(epochMillis);
            return Number.isFinite(date.getTime()) ? date.toISOString() : '';
        } catch (e) {
            return '';
        }
    }

    function matchesCategory(message) {
        return !!message && (!state.category || message.category === state.category);
    }

    function severityClass(message) {
        var severity = message && String(message.severity || '').toLowerCase();
        return severity === 'warning' || severity === 'error' ? ' severity-' + severity : '';
    }

    function resetDetail() {
        var detail = el('notificationDetail');
        Array.prototype.slice.call(detail.children).forEach(function (child) {
            if (child !== contentFrameHost) child.remove();
        });
        contentFrames.forEach(function (frame) { frame.hidden = true; });
        contentFrameHost.hidden = true;
        detail.classList.remove('severity-warning', 'severity-error');
        return detail;
    }

    function clearSelection(updateHistory) {
        state.selectedId = '';
        state.selectedMessage = null;
        var detail = resetDetail();
        if (emptyDetail) {
            detail.insertBefore(emptyDetail, contentFrameHost);
            if (pageI18n) pageI18n.apply(detail);
        }
        renderList();
        if (updateHistory) {
            var url = new URL(location.href);
            url.searchParams.delete('id');
            history.replaceState({}, '', url.pathname + url.search + url.hash);
        }
    }

    function messageButton(message) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'notification-list-item' + (message.readTime ? '' : ' unread')
            + (message.id === state.selectedId ? ' active' : '') + severityClass(message);

        var meta = document.createElement('span');
        meta.className = 'notification-item-meta';
        var category = document.createElement('span');
        category.className = 'notification-category';
        category.textContent = categoryLabel(message.category);
        var time = document.createElement('time');
        time.className = 'notification-item-time';
        time.dateTime = isoTime(message.createdTime);
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
        el('notificationUnreadOnly').checked = state.unreadOnly;
        el('notificationMarkCategoryRead').disabled = state.unreadCount === 0;
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

    function withLanguage(url) {
        if (!pageI18n || !pageI18n.lang) return url;
        var localized = new URL(url, location.origin);
        localized.searchParams.set('lang', pageI18n.lang);
        return localized.pathname + localized.search;
    }

    function contentFrameSource(message) {
        if (message.embeddedContentUrl) {
            var embeddedUrl = new URL(message.embeddedContentUrl, location.origin);
            embeddedUrl.searchParams.set('notificationId', message.id);
            if (pageI18n && pageI18n.lang) embeddedUrl.searchParams.set('lang', pageI18n.lang);
            return embeddedUrl.pathname + embeddedUrl.search;
        }
        return '/api/notifications/' + encodeURIComponent(message.id) + '/content';
    }

    function discardContentFrame(id) {
        var frame = contentFrames.get(id);
        if (frame) frame.remove();
        contentFrames.delete(id);
    }

    function contentFrame(message) {
        if (!message || !message.id || (!message.hasHtmlContent && !message.embeddedContentUrl)) return null;
        var source = contentFrameSource(message);
        var frame = contentFrames.get(message.id);
        if (frame && frame.getAttribute('data-content-source') === source) {
            frame.title = t('inbox.html-content', '消息 HTML 正文') + '：' + message.title;
            return frame;
        }
        discardContentFrame(message.id);
        frame = document.createElement('iframe');
        frame.className = 'notification-detail-content-frame';
        frame.hidden = true;
        frame.src = source;
        frame.setAttribute('data-content-source', source);
        frame.setAttribute('data-notification-id', message.id);
        if (message.embeddedContentUrl) {
            frame.setAttribute('data-embedded-survey', 'true');
            frame.setAttribute('loading', 'eager');
        } else {
            frame.setAttribute('sandbox', 'allow-scripts');
            frame.setAttribute('loading', 'lazy');
        }
        frame.title = t('inbox.html-content', '消息 HTML 正文') + '：' + message.title;
        frame.setAttribute('referrerpolicy', 'no-referrer');
        frame.setAttribute('scrolling', 'no');
        frame.setAttribute('allow', "camera 'none'; clipboard-read 'none'; clipboard-write 'none'; fullscreen 'none'; geolocation 'none'; microphone 'none'; payment 'none'; usb 'none'");
        contentFrames.set(message.id, frame);
        contentFrameHost.appendChild(frame);
        return frame;
    }

    function preloadEmbeddedFrames(messages) {
        messages.forEach(function (message) {
            if (message.embeddedContentUrl) contentFrame(message);
        });
    }

    async function handleContentMessage(event) {
        var data = event.data;
        if (!data) return;
        var frames = document.querySelectorAll('.notification-detail-content-frame');
        var frame = Array.prototype.find.call(frames, function (frame) {
            return frame.contentWindow === event.source;
        });
        if (!frame) return;
        if (data.type === 'pixiv-content-height') {
            if (typeof data.height !== 'number' || !Number.isFinite(data.height) || data.height <= 0) return;
            var frameHeight = Math.ceil(data.height + 2) + 'px';
            if (frame.style.height !== frameHeight) frame.style.height = frameHeight;
            return;
        }
        if (data.type === 'pixiv-survey-unavailable') {
            var notificationId = frame.getAttribute('data-notification-id');
            if (event.origin !== location.origin
                    || frame.getAttribute('data-embedded-survey') !== 'true'
                    || typeof data.notificationId !== 'string'
                    || data.notificationId !== notificationId) return;
            try {
                await api('/api/notifications/' + encodeURIComponent(notificationId)
                    + '/survey-unavailable', {method: 'POST'});
                discardContentFrame(notificationId);
                if (state.selectedId === notificationId) clearSelection(true);
                await loadMessages(true);
            } catch (error) {
                // 临时错误保留站内信，稍后重新验证调查发布状态。
            }
            return;
        }
        if (data.type !== 'pixiv-external-link' || typeof data.href !== 'string'
                || !data.href || data.href.length > 8192 || typeof data.newTab !== 'boolean'
                || !window.PixivFeedback) return;
        window.PixivFeedback.followLink(data.href, {newTab: data.newTab});
    }

    function renderDetail(message) {
        var detail = resetDetail();
        var autoRead = message.category === 'survey' || message.category === 'announcement';
        var severity = severityClass(message).trim();
        if (severity) detail.classList.add(severity);

        var meta = document.createElement('div');
        meta.className = 'notification-detail-meta';
        var category = document.createElement('span');
        category.className = 'notification-category';
        category.textContent = categoryLabel(message.category);
        var time = document.createElement('time');
        time.className = 'notification-detail-time';
        time.dateTime = isoTime(message.createdTime);
        time.textContent = timeText(message.createdTime);
        meta.append(category, time);

        var title = document.createElement('h2');
        title.textContent = message.title;
        var body = document.createElement('div');
        body.className = 'notification-detail-body';
        body.textContent = message.body;
        detail.insertBefore(meta, contentFrameHost);
        detail.insertBefore(title, contentFrameHost);
        detail.insertBefore(body, contentFrameHost);

        var frame = contentFrame(message);
        if (frame) {
            frame.hidden = false;
            contentFrameHost.hidden = false;
        }

        var toolbar = document.createElement('div');
        toolbar.className = 'notification-detail-actions';
        var markRead = document.createElement('button');
        markRead.type = 'button';
        markRead.className = 'notification-detail-action';
        markRead.disabled = !!message.readTime;
        markRead.setAttribute('data-i18n', message.readTime ? 'inbox.read' : 'inbox.mark-read');
        markRead.textContent = message.readTime ? t('inbox.read', '已读') : t('inbox.mark-read', '标记已读');
        if (!message.readTime) markRead.addEventListener('click', markSelectedRead);
        var remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'notification-detail-action notification-detail-action--danger';
        remove.setAttribute('data-i18n', 'inbox.delete');
        remove.textContent = t('inbox.delete', '删除');
        remove.addEventListener('click', deleteSelected);
        var actionStatus = document.createElement('span');
        actionStatus.id = 'notificationDetailActionStatus';
        actionStatus.className = 'notification-detail-action-status';
        actionStatus.setAttribute('role', 'status');
        actionStatus.setAttribute('aria-live', 'polite');
        if (!autoRead) toolbar.appendChild(markRead);
        if (message.deletable !== false) toolbar.appendChild(remove);
        toolbar.appendChild(actionStatus);
        if (!autoRead || message.deletable !== false) detail.appendChild(toolbar);

        var actionUrl = safeActionHref(message.actionUrl);
        if (actionUrl && !message.embeddedContentUrl) {
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
        if (autoRead && !message.readTime) markSelectedRead(null, true, true);
    }

    async function markSelectedRead(event, keepSelection, quiet) {
        var id = state.selectedId;
        if (!id || markingReadIds.has(id) || !state.selectedMessage || state.selectedMessage.readTime) return;
        markingReadIds.add(id);
        var button = event && event.currentTarget;
        if (button) button.disabled = true;
        try {
            var updated = await api(withLanguage('/api/notifications/' + encodeURIComponent(id) + '/read'),
                { method: 'POST' });
            if (state.selectedId !== id) return;
            state.unreadCount = Math.max(0, state.unreadCount - 1);
            if (state.unreadOnly) {
                state.messages = state.messages.filter(function (item) { return item.id !== id; });
                if (!keepSelection) {
                    clearSelection(true);
                    return;
                }
            } else {
                state.messages = state.messages.map(function (item) { return item.id === id ? updated : item; });
            }
            state.selectedMessage = updated;
            renderList();
            renderDetail(updated);
        } catch (error) {
            if (button) button.disabled = false;
            var status = el('notificationDetailActionStatus');
            if (!quiet && status) status.textContent = t('inbox.mark-read-failed', '标记已读失败');
        } finally {
            markingReadIds.delete(id);
        }
    }

    async function deleteSelected(event) {
        var id = state.selectedId;
        var message = state.selectedMessage;
        if (!id || !message || !window.PixivFeedback) return;
        var confirmed = await window.PixivFeedback.confirm({
            title: t('inbox.delete-confirm-title', '删除消息'),
            message: message.category === 'announcement'
                ? t('inbox.delete-confirm-announcement', '删除后，即使重新拉取也不会再次显示同一公告。')
                : t('inbox.delete-confirm', '删除后将无法恢复这条消息。'),
            confirmLabel: t('inbox.delete-confirm-button', '删除'),
            cancelLabel: t('inbox.delete-cancel', '取消'),
            danger: true
        });
        if (!confirmed || state.selectedId !== id) return;
        var button = event && event.currentTarget;
        if (button) button.disabled = true;
        try {
            await api('/api/notifications/' + encodeURIComponent(id), { method: 'DELETE' });
            if (state.selectedId !== id) return;
            discardContentFrame(id);
            if (!message.readTime) state.unreadCount = Math.max(0, state.unreadCount - 1);
            state.messages = state.messages.filter(function (item) { return item.id !== id; });
            clearSelection(true);
        } catch (error) {
            if (button) button.disabled = false;
            var status = el('notificationDetailActionStatus');
            if (status) status.textContent = t('inbox.delete-failed', '删除消息失败');
        }
    }

    async function selectMessage(id, updateHistory) {
        state.selectedId = id;
        state.selectedMessage = state.messages.find(function (message) { return message.id === id; }) || null;
        renderList();
        if (state.selectedMessage) renderDetail(state.selectedMessage);
        if (updateHistory) {
            var url = new URL(location.href);
            url.searchParams.set('id', id);
            history.pushState({ id: id }, '', url.pathname + url.search + url.hash);
        }
        if (state.selectedMessage) {
            if (updateHistory && window.matchMedia('(max-width: 760px)').matches) {
                el('notificationDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
            return;
        }
        try {
            var message = await api(withLanguage('/api/notifications/' + encodeURIComponent(id)));
            if (state.selectedId !== id) return;
            if (!matchesCategory(message)) {
                clearSelection(true);
                return;
            }
            state.selectedMessage = message;
            renderDetail(message);
            if (updateHistory && window.matchMedia('(max-width: 760px)').matches) {
                el('notificationDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        } catch (error) {
            if (state.selectedId === id) {
                var detail = resetDetail();
                var failure = document.createElement('p');
                failure.textContent = t('inbox.load-failed', '消息加载失败');
                detail.insertBefore(failure, contentFrameHost);
            }
        }
    }

    async function loadMessages(quiet) {
        var requestSequence = ++loadSequence;
        var status = el('notificationStatus');
        if (!quiet) {
            status.hidden = false;
            status.textContent = t('inbox.loading', '正在加载消息…');
        }
        var query = new URLSearchParams({ limit: '100' });
        if (pageI18n && pageI18n.lang) query.set('lang', pageI18n.lang);
        if (state.category) query.set('category', state.category);
        if (state.unreadOnly) query.set('unreadOnly', 'true');
        try {
            var snapshot = await api('/api/notifications?' + query.toString());
            if (requestSequence !== loadSequence) return false;
            state.messages = Array.isArray(snapshot.messages) ? snapshot.messages : [];
            state.unreadCount = Math.max(0, Number(snapshot.categoryUnreadCount) || 0);
            preloadEmbeddedFrames(state.messages);
            renderList();
            return true;
        } catch (error) {
            if (requestSequence === loadSequence && !quiet) {
                status.hidden = false;
                status.textContent = t('inbox.load-failed', '消息加载失败');
            }
            return false;
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
                if (state.selectedMessage && !matchesCategory(state.selectedMessage)) clearSelection(true);
                loadMessages();
            });
        });
    }

    function bindListTools() {
        el('notificationUnreadOnly').addEventListener('change', function (event) {
            state.unreadOnly = event.target.checked;
            if (state.unreadOnly && state.selectedMessage && state.selectedMessage.readTime) clearSelection(true);
            loadMessages();
        });
        el('notificationMarkCategoryRead').addEventListener('click', async function () {
            var category = state.category;
            var query = category ? '?category=' + encodeURIComponent(category) : '';
            try {
                await api('/api/notifications/read-all' + query, { method: 'POST' });
                if (state.category !== category || !await loadMessages()) return;
                if (state.unreadOnly) clearSelection(true);
                else if (state.selectedId) selectMessage(state.selectedId, false);
            } catch (error) {
                var status = el('notificationStatus');
                status.hidden = false;
                status.textContent = t('inbox.mark-read-failed', '标记已读失败');
            }
        });
    }

    function applyTranslations() {
        document.title = t('inbox.page-title', '站内信 · Pixiv Downloader');
        if (pageI18n) pageI18n.apply(document.body);
        renderList();
        if (matchesCategory(state.selectedMessage)) renderDetail(state.selectedMessage);
    }

    async function initI18n() {
        pageI18n = await PixivI18n.create({ namespaces: ['notification', 'common'] });
        await PixivLangSwitcher.mount({
            mountPoint: el('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyTranslations();
                loadMessages().then(function (loaded) {
                    if (loaded && state.selectedId) selectMessage(state.selectedId, false);
                });
            }
        });
        PixivTheme.mount({ mountPoint: el('langSwitcherAnchor') });
        applyTranslations();
    }

    document.addEventListener('DOMContentLoaded', async function () {
        emptyDetail = el('notificationDetail').firstElementChild;
        contentFrameHost = el('notificationContentFrames');
        window.addEventListener('message', handleContentMessage);
        bindFilters();
        bindListTools();
        await initI18n();
        await loadMessages();
        var requestedId = new URLSearchParams(location.search).get('id');
        if (requestedId) selectMessage(requestedId, false);
        window.addEventListener('popstate', function () {
            var id = new URLSearchParams(location.search).get('id');
            if (id) selectMessage(id, false); else clearSelection(false);
        });
        window.setInterval(function () {
            if (document.visibilityState === 'visible') loadMessages(true);
        }, 45000);
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') loadMessages(true);
        });
    });
})();
