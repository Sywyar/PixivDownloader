(function (global) {
    'use strict';

    var queueTypes = global.PixivBatch && global.PixivBatch.queueTypes;
    if (!queueTypes || typeof queueTypes.registerUiModule !== 'function') return;

    queueTypes.registerUiModule(function (context) {
        var snapshot = {messages: [], unreadCount: 0};
        var root = null;
        var popover = null;
        var style = null;
        var refreshTimer = null;

        function t(key, fallback) {
            return typeof pageI18n !== 'undefined' && pageI18n
                ? pageI18n.t('notification:' + key, fallback)
                : fallback;
        }

        function host() {
            return document.querySelector('[data-vue-slot="topbar-actions"]');
        }

        function categoryCopy(category) {
            var labels = {
                download: ['inbox.category.download', '下载通知'],
                announcement: ['inbox.category.announcement', '公告'],
                survey: ['inbox.category.survey', '调查'],
                system: ['inbox.category.system', '系统']
            };
            return labels[category] || labels.system;
        }

        function closePopover() {
            if (!popover) return;
            if (typeof popover.hidePopover === 'function') {
                try { popover.hidePopover(); } catch (e) { /* already closed */ }
            } else {
                popover.hidden = true;
            }
        }

        function openMessage(message) {
            location.href = '/pixiv-notifications.html?id=' + encodeURIComponent(message.id);
        }

        function severityClass(message) {
            var severity = message && String(message.severity || '').toLowerCase();
            return severity === 'warning' || severity === 'error' ? ' severity-' + severity : '';
        }

        function renderMessages() {
            if (!popover || !snapshot) return;
            var list = popover.querySelector('.notification-popover-list');
            list.textContent = '';
            var messages = Array.isArray(snapshot.messages) ? snapshot.messages : [];
            if (!messages.length) {
                var empty = document.createElement('div');
                empty.className = 'notification-popover-empty';
                empty.setAttribute('data-i18n', 'notification:inbox.empty');
                empty.textContent = t('inbox.empty', '暂无消息');
                list.appendChild(empty);
                return;
            }
            messages.forEach(function (message) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'notification-popover-item' + (message.readTime ? '' : ' unread')
                    + severityClass(message);
                var meta = document.createElement('span');
                meta.className = 'notification-item-meta';
                var category = document.createElement('span');
                category.className = 'notification-category';
                var categoryText = categoryCopy(message.category);
                category.setAttribute('data-i18n', 'notification:' + categoryText[0]);
                category.textContent = t(categoryText[0], categoryText[1]);
                meta.appendChild(category);
                var title = document.createElement('span');
                title.className = 'notification-item-title';
                title.textContent = message.title;
                var preview = document.createElement('span');
                preview.className = 'notification-item-preview';
                preview.textContent = message.body;
                button.append(meta, title, preview);
                button.addEventListener('click', function () { openMessage(message); });
                list.appendChild(button);
            });
        }

        async function refresh() {
            try {
                var response = await fetch('/api/notifications?limit=8', {
                    credentials: 'same-origin', signal: context.signal,
                    headers: { 'Accept': 'application/json' }
                });
                if (response.status === 401 || response.status === 403) {
                    removeRendered();
                    return;
                }
                if (!response.ok) throw new Error('HTTP ' + response.status);
                snapshot = await response.json();
                render();
                renderMessages();
            } catch (error) {
                if (!context.signal.aborted && root) renderMessages();
            }
        }

        function render() {
            if (!context.isActive() || !snapshot) return;
            var slotHost = host();
            if (!slotHost) return;
            if (root && root.parentNode !== slotHost) root.remove();
            if (!root || !root.isConnected) {
                root = document.createElement('span');
                root.className = 'notification-inbox-slot';
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'notification-inbox-button';
                button.setAttribute('aria-haspopup', 'dialog');
                button.setAttribute('aria-controls', 'notificationInboxPopover');
                button.setAttribute('aria-label', t('inbox.open', '打开站内信'));
                button.setAttribute('data-i18n-aria-label', 'notification:inbox.open');
                button.title = t('inbox.open', '打开站内信');
                button.setAttribute('data-i18n-title', 'notification:inbox.open');
                button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">'
                    + '<path d="M4 5h16v14H4z"></path><path d="m4 7 8 6 8-6"></path></svg>';
                var badge = document.createElement('span');
                badge.className = 'notification-inbox-badge';
                badge.setAttribute('aria-hidden', 'true');
                button.appendChild(badge);
                button.addEventListener('click', function () {
                    var nativeOpen = false;
                    try { nativeOpen = popover.matches(':popover-open'); } catch (e) { nativeOpen = false; }
                    if (typeof popover.showPopover === 'function') {
                        if (nativeOpen) closePopover(); else popover.showPopover();
                    } else {
                        popover.hidden = !popover.hidden;
                    }
                    refresh();
                });
                root.appendChild(button);
                slotHost.appendChild(root);
            }
            var unread = Math.max(0, Number(snapshot.unreadCount) || 0);
            var badgeNode = root.querySelector('.notification-inbox-badge');
            badgeNode.hidden = unread === 0;
            badgeNode.textContent = unread > 99 ? '99+' : String(unread);
        }

        function createPopover() {
            popover = document.createElement('div');
            popover.id = 'notificationInboxPopover';
            popover.className = 'notification-inbox-popover';
            popover.setAttribute('role', 'dialog');
            popover.setAttribute('aria-label', t('inbox.latest', '最新消息'));
            popover.setAttribute('data-i18n-aria-label', 'notification:inbox.latest');
            if ('popover' in HTMLElement.prototype) popover.setAttribute('popover', 'auto');
            else popover.hidden = true;

            var header = document.createElement('div');
            header.className = 'notification-popover-header';
            var title = document.createElement('strong');
            title.setAttribute('data-i18n', 'notification:inbox.latest');
            title.textContent = t('inbox.latest', '最新消息');
            var all = document.createElement('a');
            all.className = 'notification-popover-all';
            all.href = '/pixiv-notifications.html';
            all.setAttribute('data-i18n', 'notification:inbox.view-all');
            all.textContent = t('inbox.view-all', '查看全部');
            header.append(title, all);
            var list = document.createElement('div');
            list.className = 'notification-popover-list';
            popover.append(header, list);
            document.body.appendChild(popover);
        }

        function removeRendered() {
            closePopover();
            if (refreshTimer) global.clearInterval(refreshTimer);
            if (root) root.remove();
            if (popover) popover.remove();
            if (style) style.remove();
            root = null;
            popover = null;
            style = null;
            refreshTimer = null;
        }

        style = document.createElement('link');
        style.rel = 'stylesheet';
        style.href = '/pixiv-notifications/pixiv-notifications.css';
        document.head.appendChild(style);
        createPopover();
        if (global.PixivVue && typeof global.PixivVue.prepareSlotHosts === 'function') {
            global.PixivVue.prepareSlotHosts(document);
        }
        render();

        function onSlotsRendered() { render(); }
        function onVisibilityChange() {
            if (root && document.visibilityState === 'visible') refresh();
        }
        global.addEventListener('pixivbatch:slotsrendered', onSlotsRendered);
        document.addEventListener('visibilitychange', onVisibilityChange);
        refreshTimer = global.setInterval(function () {
            if (document.visibilityState === 'visible') refresh();
        }, 45000);
        context.onCleanup(function () {
            global.removeEventListener('pixivbatch:slotsrendered', onSlotsRendered);
            document.removeEventListener('visibilitychange', onVisibilityChange);
            removeRendered();
        });
        refresh();
    });
})(window);
