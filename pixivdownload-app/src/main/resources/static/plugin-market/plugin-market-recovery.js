'use strict';
/* 恢复操作独立于市场仓库加载和两种渲染器，仓库不可用时仍能正常重启或退出。 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var snapshot = null;
    var root = null;
    var busy = false;
    var pending = false;
    var generation = 0;
    var error = '';
    function allowed() {
        return !!snapshot && snapshot.active === true && snapshot.actionsAllowed === true;
    }
    function element(tag, text, parent) {
        var node = document.createElement(tag);
        if (text) node.textContent = text;
        parent.appendChild(node);
        return node;
    }
    function render() {
        if (!root) return;
        root.replaceChildren();
        var active = snapshot && snapshot.active;
        root.classList.toggle('pmk-recovery--active', !!active);
        if (active) {
            element('h2', PMK.t(snapshot.recoveryMode ? 'recovery.banner.title' : 'recovery.no-gui'), root);
            element('p', PMK.t('recovery.explanation'), root);
            element('p', snapshot.advice, root).className = 'pmk-recovery-advice';
            var details = element('details', '', root);
            details.open = true;
            element('summary', PMK.t('recovery.error-summary'), details);
            var list = element('ul', '', details);
            (snapshot.errors || []).forEach(function (message) { element('li', message, list); });
            if (!snapshot.errors || !snapshot.errors.length)
                element('p', PMK.t('recovery.no-error-details'), details);
        }
        var actions = element('div', '', root);
        actions.className = 'pmk-recovery-actions';
        ['restart', 'exit'].forEach(function (action) {
            var button = element('button', PMK.t('recovery.' + action), actions);
            button.type = 'button';
            button.className = 'pmk-btn pmk-btn--gray';
            button.disabled = !allowed() || busy || pending;
            button.addEventListener('click', function () { perform(action); });
        });
        var hint = pending ? 'recovery.action-pending'
            : !snapshot ? 'recovery.status-unavailable'
            : !active ? 'recovery.normal-disabled'
            : !snapshot.actionsAllowed ? 'recovery.local-only' : '';
        if (hint) element('p', PMK.t(hint), root);
        if (error) element('p', error, root).setAttribute('role', 'alert');
    }
    async function refresh() {
        var token = ++generation;
        snapshot = null;
        render();
        try {
            var value = await PMK.api.fetchRecovery();
            if (token === generation) { snapshot = value; error = ''; render(); refreshCards(); }
            return token === generation ? snapshot : null;
        } catch (failure) {
            if (token === generation) { snapshot = null; error = PMK.t('recovery.status-unavailable'); render(); refreshCards(); }
            return null;
        }
    }
    function refreshCards() {
        if (PMK.state.activeView) PMK.state.activeView.rerender();
    }
    async function perform(action) {
        if (['restart', 'exit'].indexOf(action) === -1 || !allowed() || busy || pending) return false;
        if (!global.PixivFeedback || typeof global.PixivFeedback.confirm !== 'function') return false;
        busy = true;
        render();
        try {
            var confirmed = await global.PixivFeedback.confirm({
                title: PMK.t('recovery.' + action),
                message: PMK.t('recovery.confirm-' + action),
                confirmLabel: PMK.t('recovery.' + action),
                cancelLabel: PMK.t('install.trust.cancel')
            });
            if (!confirmed) return false;
            await refresh();
            if (!allowed()) return false;
            var result = await PMK.api.recoveryAction(action);
            if (!result || result.accepted !== true) throw new Error(PMK.t('recovery.action-failed'));
            pending = true;
            return true;
        } catch (failure) {
            error = failure.message || PMK.t('recovery.action-failed');
            return false;
        } finally {
            busy = false;
            render();
        }
    }
    PMK.recovery = {
        refresh: refresh,
        perform: perform,
        focus: function () { return snapshot && snapshot.active ? snapshot.focus : null; },
        installationBlocked: function () { return !snapshot || snapshot.installationBlocked === true; },
        mount: function () {
            root = document.getElementById('pmk-recovery');
            render();
            global.addEventListener('pageshow', function (event) { if (event.persisted) refresh(); });
            document.addEventListener('visibilitychange', function () { if (!document.hidden && !pending) refresh(); });
        }
    };
})(window);
