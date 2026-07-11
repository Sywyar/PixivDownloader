(function (global) {
    'use strict';

    let dialogQueue = Promise.resolve();
    let dialogSequence = 0;
    let toastHost = null;

    function requiredText(value, name) {
        const text = value == null ? '' : String(value).trim();
        if (!text) throw new Error('PixivFeedback requires ' + name);
        return text;
    }

    function button(label, className, action) {
        const node = document.createElement('button');
        node.type = 'button';
        node.className = 'pixiv-feedback-button ' + className;
        node.textContent = requiredText(label, className + 'Label');
        node.dataset.feedbackAction = action;
        return node;
    }

    function focusableElements(root) {
        return Array.from(root.querySelectorAll('button:not([disabled]), input:not([disabled])'));
    }

    function enqueue(factory) {
        const pending = dialogQueue.then(factory, factory);
        dialogQueue = pending.then(function () {}, function () {});
        return pending;
    }

    function openDialog(kind, options) {
        return enqueue(function () {
            return new Promise(function (resolve) {
                const dialogId = ++dialogSequence;
                const previousFocus = document.activeElement;
                const backdrop = document.createElement('div');
                backdrop.className = 'pixiv-feedback-backdrop';

                const panel = document.createElement('section');
                panel.className = 'pixiv-feedback-dialog pixiv-feedback-dialog--' + kind;
                panel.setAttribute('role', kind === 'alert' ? 'alertdialog' : 'dialog');
                panel.setAttribute('aria-modal', 'true');
                panel.tabIndex = -1;

                const title = options.title == null ? '' : String(options.title).trim();
                if (title) {
                    const heading = document.createElement('h2');
                    heading.className = 'pixiv-feedback-title';
                    heading.textContent = title;
                    const titleId = 'pixiv-feedback-title-' + dialogId;
                    heading.id = titleId;
                    panel.setAttribute('aria-labelledby', titleId);
                    panel.appendChild(heading);
                }

                const message = document.createElement('div');
                message.className = 'pixiv-feedback-message';
                message.textContent = requiredText(options.message, 'message');
                const messageId = 'pixiv-feedback-message-' + dialogId;
                message.id = messageId;
                if (title) panel.setAttribute('aria-describedby', messageId);
                else panel.setAttribute('aria-labelledby', messageId);
                panel.appendChild(message);

                let input = null;
                if (kind === 'prompt') {
                    input = document.createElement('input');
                    input.className = 'pixiv-feedback-input';
                    input.type = options.inputType || 'text';
                    input.value = options.value == null ? '' : String(options.value);
                    if (options.min != null) input.min = String(options.min);
                    if (options.max != null) input.max = String(options.max);
                    if (options.step != null) input.step = String(options.step);
                    input.setAttribute('aria-labelledby', messageId);
                    panel.appendChild(input);
                }

                const actions = document.createElement('div');
                actions.className = 'pixiv-feedback-actions';
                let cancelButton = null;
                if (kind !== 'alert') {
                    cancelButton = button(options.cancelLabel, 'pixiv-feedback-button--secondary', 'cancel');
                    actions.appendChild(cancelButton);
                }
                const acceptButton = button(
                    options.confirmLabel,
                    options.danger ? 'pixiv-feedback-button--danger' : 'pixiv-feedback-button--primary',
                    'accept'
                );
                actions.appendChild(acceptButton);
                panel.appendChild(actions);
                backdrop.appendChild(panel);

                let settled = false;
                function finish(value) {
                    if (settled) return;
                    settled = true;
                    document.removeEventListener('keydown', onKeyDown, true);
                    backdrop.remove();
                    document.body.classList.remove('pixiv-feedback-open');
                    if (previousFocus && typeof previousFocus.focus === 'function' && document.contains(previousFocus)) {
                        previousFocus.focus();
                    }
                    resolve(value);
                }

                function cancel() {
                    finish(kind === 'confirm' ? false : null);
                }

                function accept() {
                    if (kind === 'confirm') finish(true);
                    else if (kind === 'prompt') finish(input.value);
                    else finish(undefined);
                }

                function onKeyDown(event) {
                    if (event.key === 'Escape') {
                        event.preventDefault();
                        if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
                        else if (typeof event.stopPropagation === 'function') event.stopPropagation();
                        if (kind === 'alert') accept(); else cancel();
                        return;
                    }
                    if (event.key !== 'Tab') return;
                    const focusable = focusableElements(panel);
                    if (!focusable.length) {
                        event.preventDefault();
                        panel.focus();
                        return;
                    }
                    const first = focusable[0];
                    const last = focusable[focusable.length - 1];
                    if (event.shiftKey && document.activeElement === first) {
                        event.preventDefault();
                        last.focus();
                    } else if (!event.shiftKey && document.activeElement === last) {
                        event.preventDefault();
                        first.focus();
                    }
                }

                actions.addEventListener('click', function (event) {
                    const action = event.target && event.target.dataset
                        ? event.target.dataset.feedbackAction
                        : null;
                    if (action === 'accept') accept();
                    else if (action === 'cancel') cancel();
                });
                backdrop.addEventListener('mousedown', function (event) {
                    if (event.target === backdrop && kind !== 'alert') cancel();
                });
                if (input) {
                    input.addEventListener('keydown', function (event) {
                        if (event.key === 'Enter') {
                            event.preventDefault();
                            accept();
                        }
                    });
                }

                document.body.appendChild(backdrop);
                document.body.classList.add('pixiv-feedback-open');
                document.addEventListener('keydown', onKeyDown, true);
                (input || cancelButton || acceptButton || panel).focus();
            });
        });
    }

    function ensureToastHost() {
        if (toastHost && document.contains(toastHost)) return toastHost;
        toastHost = document.createElement('div');
        toastHost.className = 'pixiv-feedback-toast-host';
        toastHost.setAttribute('aria-live', 'polite');
        document.body.appendChild(toastHost);
        return toastHost;
    }

    function toast(options) {
        const node = document.createElement('div');
        const kind = options.kind || 'info';
        node.className = 'pixiv-feedback-toast pixiv-feedback-toast--' + kind;
        node.setAttribute('role', kind === 'error' ? 'alert' : 'status');
        node.textContent = requiredText(options.message, 'message');
        ensureToastHost().appendChild(node);
        global.setTimeout(function () {
            node.classList.add('pixiv-feedback-toast--leaving');
            global.setTimeout(function () { node.remove(); }, 180);
        }, Math.max(1000, Number(options.durationMs) || 3500));
    }

    global.PixivFeedback = Object.freeze({
        alert: function (options) { return openDialog('alert', options || {}); },
        confirm: function (options) { return openDialog('confirm', options || {}); },
        prompt: function (options) { return openDialog('prompt', options || {}); },
        toast: toast
    });
})(window);
