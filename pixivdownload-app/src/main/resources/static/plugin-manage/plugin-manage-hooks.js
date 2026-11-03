'use strict';
(function (global) {
    var PM = global.PixivPluginManage;
    var host = null, provider = null, lifetime = null, request = null;
    var listing = null, plan = null, selected = null, busy = false, error = false;
    function text(tag, value) {
        var element = document.createElement(tag);
        element.textContent = value;
        return element;
    }
    function button(label, action, id) {
        var element = text('button', PM.t(label));
        element.type = 'button';
        element.className = 'pm-btn pm-btn--gray';
        element.dataset.hookAction = action;
        if (id) element.dataset.hookId = id;
        element.disabled = busy;
        return element;
    }
    function render() {
        if (!host) return;
        host.replaceChildren(text('h3', PM.t('hooks.title')), text('p', PM.t('hooks.description')));
        host.appendChild(button('hooks.refresh', 'refresh'));
        var status = text('p', busy ? PM.t('status.loading') : error ? PM.t('hooks.error') : '');
        status.setAttribute('role', 'status');
        host.appendChild(status);
        if (plan) {
            host.appendChild(text('p', selected.hook + ' · ' + selected.callback));
            host.appendChild(text('p', selected.target ? selected.target.owner + ' · ' + selected.target.method + ' ' + selected.target.path : selected.endpoint));
            host.appendChild(text('p', PM.t('hooks.phase.' + selected.phase)));
            host.appendChild(text('p', selected.fields.length ? selected.fields.map(function (field) {
                return field.source + ' ' + field.path + ' → ' + field.name;
            }).join(', ') : PM.t('hooks.metadata')));
            host.appendChild(text('p', PM.t('hooks.confirm')));
            var permissions = document.createElement('ul');
            plan.permissions.forEach(function (permission) {
                permissions.appendChild(text('li', permission.owner + ' · ' + permission.policy + ' · '
                    + permission.profile.id + ' · ' + permission.profile.sources.map(function (resource) {
                        return resource.kind + ': ' + resource.origin;
                    }).join(', ')));
            });
            host.appendChild(permissions);
            if (plan.pages.length) host.appendChild(text('p', PM.t('hooks.pages') + ': ' + plan.pages.join(', ')));
            host.appendChild(button('hooks.publish', 'publish'));
            host.appendChild(button('hooks.cancel', 'cancel'));
            return;
        }
        if (!listing) return;
        if (!listing.declarations.length) host.appendChild(text('p', PM.t('hooks.empty')));
        listing.declarations.forEach(function (row) {
            var section = document.createElement('section');
            section.className = 'pm-detail-section';
            section.appendChild(text('h4', row.hook + ' · ' + PM.t('hooks.state.' + row.status)));
            section.appendChild(text('p', row.callback || row.provider));
            if (row.target) {
                section.appendChild(text('p', row.target.owner + ' · ' + row.target.method + ' ' + row.target.path));
                ['params', 'headers', 'consumes', 'produces'].forEach(function (key) {
                    if (row.target[key].length) section.appendChild(text('p', key + ': ' + row.target[key].join(', ')));
                });
            }
            section.appendChild(text('p', row.endpoint + ' · ' + PM.t('hooks.phase.' + row.phase)));
            section.appendChild(text('p', row.fields.length ? row.fields.map(function (field) {
                return field.source + ' ' + field.path + ' → ' + field.name;
            }).join(', ') : PM.t('hooks.metadata')));
            if (row.status === 'PENDING_APPROVAL' || row.status === 'CHANGED')
                section.appendChild(button('hooks.prepare', 'prepare', row.id));
            if (row.approved) section.appendChild(button('hooks.revoke', 'revoke', row.id));
            host.appendChild(section);
        });
    }
    async function api(path, body, signal) {
        var response = await fetch('/api/web/operation-hooks/' + path, {
            method: body ? 'POST' : 'GET', credentials: 'same-origin', signal: signal,
            headers: {'Accept': 'application/json', 'Content-Type': 'application/json'},
            body: body ? JSON.stringify(body) : undefined
        });
        if (!response.ok) throw new Error('HOOK_DECLARATION_REQUEST_FAILED');
        return response.json();
    }
    async function perform(action, id) {
        if (!host || busy) return;
        if (action === 'cancel') { plan = null; render(); return; }
        var current = new AbortController();
        request = current; busy = true; error = false; render();
        try {
            if (action === 'publish') {
                await api('publish', {plan: plan, approvals: plan.permissions}, current.signal);
                if (request !== current) return;
                plan = null;
            } else if (action === 'prepare' || action === 'revoke') {
                var row = listing.declarations.find(function (value) { return value.id === id; });
                var prepared = await api('declarations/prepare', {revision: listing.revision, provider: provider,
                    id: id, fingerprint: row.fingerprint, remove: action === 'revoke'}, current.signal);
                if (request !== current) return;
                selected = row;
                plan = prepared;
                return;
            }
            var fresh = await api('declarations?provider=' + encodeURIComponent(provider), null, current.signal);
            if (request === current) listing = fresh;
        } catch (failure) {
            if (request === current && !current.signal.aborted) { error = true; plan = null; }
        } finally {
            if (request === current) { request = null; busy = false; render(); }
        }
    }
    function close() {
        if (request) request.abort();
        if (lifetime) lifetime.abort();
        if (host) host.replaceChildren();
        host = provider = lifetime = request = listing = plan = selected = null;
        busy = error = false;
    }
    function open(element, id) {
        close(); host = element; provider = id; lifetime = new AbortController();
        host.addEventListener('click', function (event) {
            var target = event.target.closest('[data-hook-action]');
            if (target && host.contains(target) && !target.disabled) perform(target.dataset.hookAction, target.dataset.hookId);
        }, {signal: lifetime.signal});
        perform('refresh');
    }
    PM.hooks = {open: open, close: close, render: render};
})(window);
