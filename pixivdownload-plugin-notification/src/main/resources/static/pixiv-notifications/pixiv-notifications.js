(function () {
    'use strict';

    var pageI18n = null;
    var emptyDetail = null;
    var contentFrameHost = null;
    var surveyFrameHost = null;
    var contentFrames = new Map();
    var readRequests = {list: null, detail: null};
    var refreshTimer = null;
    var pageSuspended = false;
    var markingReadIds = new Set();
    var state = {
        category: '', unreadOnly: false, messages: [], unreadCount: 0,
        selectedId: '', selectedMessage: null
    };

    function el(id) { return document.getElementById(id); }
    function t(key, fallback, vars) { return pageI18n ? pageI18n.t(key, fallback, vars) : (fallback || key); }

    function cancelReadRequest(kind) {
        var request = readRequests[kind];
        readRequests[kind] = null;
        if (request) request.abort();
    }

    async function api(url, options) {
        var response = await fetch(url, Object.assign({
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }, options || {}));
        if (options && options.signal && options.signal.aborted) {
            throw new DOMException('Aborted', 'AbortError');
        }
        if (response.status === 401) {
            cancelReadRequest('list');
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
        cancelReadRequest('detail');
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
        return withLanguage('/api/notifications/' + encodeURIComponent(message.id) + '/content');
    }

    function discardContentFrame(id) {
        var frame = contentFrames.get(id);
        if (frame) {
            if (frame._notificationHeightTimer) window.clearTimeout(frame._notificationHeightTimer);
            if (surveyFrameHost) surveyFrameHost.detach(frame);
            frame.remove();
        }
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
        frame.setAttribute('sandbox', 'allow-scripts');
        frame.setAttribute('loading', 'lazy');
        if (message.embeddedContentUrl) {
            frame.setAttribute('data-embedded-survey', 'true');
        }
        frame.title = t('inbox.html-content', '消息 HTML 正文') + '：' + message.title;
        frame.setAttribute('referrerpolicy', 'no-referrer');
        frame.setAttribute('scrolling', 'no');
        frame.setAttribute('allow', "camera 'none'; clipboard-read 'none'; clipboard-write 'none'; fullscreen 'none'; geolocation 'none'; microphone 'none'; payment 'none'; usb 'none'");
        contentFrames.set(message.id, frame);
        if (message.embeddedContentUrl && surveyFrameHost) surveyFrameHost.attach(frame, source);
        contentFrameHost.appendChild(frame);
        return frame;
    }

    function activeContentFrame(frame) {
        return !!frame && contentFrames.get(frame.getAttribute('data-notification-id')) === frame
            && state.selectedId === frame.getAttribute('data-notification-id') && !frame.hidden;
    }

    function applyContentHeight(frame, height) {
        if (!activeContentFrame(frame) || typeof height !== 'number' || !Number.isFinite(height)) return;
        frame._notificationPendingHeight = Math.min(2000, Math.max(160, Math.ceil(height + 2)));
        if (frame._notificationHeightTimer) return;
        var applyHeight = function () {
            frame._notificationHeightTimer = null;
            if (!activeContentFrame(frame)) return;
            var frameHeight = frame._notificationPendingHeight + 'px';
            if (frame.style.height !== frameHeight) frame.style.height = frameHeight;
        };
        applyHeight();
        frame._notificationHeightTimer = window.setTimeout(applyHeight, 50);
    }

    async function handleSurveyFrameMessage(frame, data) {
        if (!activeContentFrame(frame) || !data) return;
        if (data.type === 'pixiv-content-height') {
            applyContentHeight(frame, data.height);
            return;
        }
        if (data.type === 'pixiv-survey-unavailable') {
            var notificationId = frame.getAttribute('data-notification-id');
            if (frame.getAttribute('data-embedded-survey') !== 'true'
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
        }
    }

    function handleContentMessage(event) {
        var data = event.data;
        if (!data || event.origin !== 'null') return;
        var frames = document.querySelectorAll('.notification-detail-content-frame');
        var frame = Array.prototype.find.call(frames, function (candidate) {
            return candidate.contentWindow === event.source;
        });
        if (!activeContentFrame(frame) || frame.getAttribute('data-embedded-survey') === 'true') return;
        if (data.type === 'pixiv-content-height') {
            applyContentHeight(frame, data.height);
            return;
        }
        if (data.type !== 'pixiv-external-link' || typeof data.href !== 'string'
                || !data.href || data.href.length > 8192 || typeof data.newTab !== 'boolean'
                || !window.PixivFeedback) return;
        window.PixivFeedback.followLink(data.href, {newTab: data.newTab});
    }

    function renderDetail(message) {
        var detail = resetDetail();
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
        if (message.deletable !== false) toolbar.appendChild(remove);
        toolbar.appendChild(actionStatus);
        detail.appendChild(toolbar);

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
        if (!message.readTime) markSelectedRead();
    }

    async function markSelectedRead() {
        var id = state.selectedId;
        if (!id || markingReadIds.has(id) || !state.selectedMessage || state.selectedMessage.readTime) return;
        var status = el('notificationDetailActionStatus');
        markingReadIds.add(id);
        try {
            var updated = await api(withLanguage('/api/notifications/' + encodeURIComponent(id) + '/read'),
                { method: 'POST' });
            if (state.selectedId === id && state.selectedMessage) {
                state.selectedMessage.readTime = updated.readTime;
            }
            await loadMessages();
        } catch (error) {
            if (status && el('notificationDetailActionStatus') === status) {
                status.textContent = t('inbox.mark-read-failed', '标记已读失败');
            }
        } finally {
            markingReadIds.delete(id);
        }
    }

    async function deleteSelected(event) {
        var id = state.selectedId;
        var message = state.selectedMessage;
        var button = event && event.currentTarget;
        if (!id || !message || !window.PixivFeedback) return;
        var status = el('notificationDetailActionStatus');
        var confirmed = await window.PixivFeedback.confirm({
            title: t('inbox.delete-confirm-title', '删除消息'),
            message: message.category === 'announcement'
                ? t('inbox.delete-confirm-announcement', '删除后，即使重新拉取也不会再次显示同一公告。')
                : t('inbox.delete-confirm', '删除后将无法恢复这条消息。'),
            confirmLabel: t('inbox.delete-confirm-button', '删除'),
            cancelLabel: t('inbox.delete-cancel', '取消'),
            danger: true
        });
        if (!confirmed || el('notificationDetailActionStatus') !== status) return;
        if (button) button.disabled = true;
        try {
            await api('/api/notifications/' + encodeURIComponent(id), { method: 'DELETE' });
            discardContentFrame(id);
            if (state.selectedId === id) clearSelection(true);
            await loadMessages();
        } catch (error) {
            if (button) button.disabled = false;
            if (status && el('notificationDetailActionStatus') === status) {
                status.textContent = t('inbox.delete-failed', '删除消息失败');
            }
        }
    }

    async function selectMessage(id, updateHistory) {
        cancelReadRequest('detail');
        if (pageSuspended) return;
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
        var request = new AbortController();
        readRequests.detail = request;
        try {
            var message = await api(withLanguage('/api/notifications/' + encodeURIComponent(id)),
                {signal: request.signal});
            if (readRequests.detail !== request) return;
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
            if (readRequests.detail === request) {
                var detail = resetDetail();
                var failure = document.createElement('p');
                failure.textContent = t('inbox.load-failed', '消息加载失败');
                detail.insertBefore(failure, contentFrameHost);
            }
        } finally {
            if (readRequests.detail === request) readRequests.detail = null;
        }
    }

    async function loadMessages(quiet) {
        cancelReadRequest('list');
        if (pageSuspended) return false;
        var request = new AbortController();
        readRequests.list = request;
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
            var snapshot = await api('/api/notifications?' + query.toString(), {signal: request.signal});
            if (readRequests.list !== request) return false;
            state.messages = Array.isArray(snapshot.messages) ? snapshot.messages : [];
            state.unreadCount = Math.max(0, Number(snapshot.categoryUnreadCount) || 0);
            renderList();
            return true;
        } catch (error) {
            if (readRequests.list === request && !quiet) {
                status.hidden = false;
                status.textContent = t('inbox.load-failed', '消息加载失败');
            }
            return false;
        } finally {
            if (readRequests.list === request) readRequests.list = null;
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
                if (!await loadMessages() || !state.selectedMessage
                    || (category && state.selectedMessage.category !== category)) return;
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
                cancelReadRequest('detail');
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
        if (window.PixivSurveyFrameBridge) {
            surveyFrameHost = window.PixivSurveyFrameBridge.createHost({
                isActive: activeContentFrame,
                onMessage: handleSurveyFrameMessage
            });
            window.addEventListener('storage', surveyFrameHost.handleStorageEvent);
            window.addEventListener('pixiv-theme-change', function (event) {
                var theme = event && event.detail ? event.detail.theme : null;
                if (typeof theme === 'string') surveyFrameHost.publishStorage('pixiv_theme', theme);
            });
        }
        window.addEventListener('message', handleContentMessage);
        function startPolling() {
            if (pageSuspended || refreshTimer !== null) return;
            refreshTimer = window.setInterval(function () {
                if (document.visibilityState === 'visible') loadMessages(true);
            }, 45000);
        }
        window.addEventListener('pagehide', function () {
            pageSuspended = true;
            window.clearInterval(refreshTimer);
            refreshTimer = null;
            cancelReadRequest('list');
            cancelReadRequest('detail');
        });
        window.addEventListener('pageshow', function (event) {
            if (!event.persisted) return;
            pageSuspended = false;
            startPolling();
            loadMessages(true).then(function (loaded) {
                if (loaded && state.selectedId) selectMessage(state.selectedId, false);
            });
        });
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
        startPolling();
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') loadMessages(true);
        });
    });
})();
