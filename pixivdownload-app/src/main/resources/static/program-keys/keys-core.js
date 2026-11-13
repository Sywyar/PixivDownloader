'use strict';
const keyState = { keys: [], permissions: [], editing: null, busy: false, active: true, loading: false, ready: false, uncertain: false, loadController: null, visit: 0 };
let keyI18n;
const keyElement = id => document.getElementById(id);
const keyText = (key, vars) => keyI18n ? keyI18n.t('security:' + key, '', vars) : '';
function keyNode(tag, text, className) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    return node;
}
async function keyApi(path = '', method = 'GET', body, signal) {
    const response = await fetch('/api/security/program-keys' + path, {
        method, credentials: 'same-origin', cache: 'no-store', signal,
        headers: { Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'}) },
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (response.status === 401) location.assign('/login.html?redirect=' + encodeURIComponent(location.pathname));
    if (!response.ok) {
        let message = keyText('request.failed');
        try { message = (await response.json()).error || message; } catch (_) { /* 保留受控错误提示。 */ }
        const error = new Error(message); error.definitive = response.status >= 400 && response.status < 500; throw error;
    }
    return response.status === 204 ? null : response.json();
}
function keyLabel(permission) {
    return keyI18n.t(permission.namespace + ':' + permission.labelKey, permission.id);
}
function keySource(policy) { return policy.anyIp ? keyText('source.any') : policy.networks.join(', '); }
function keyBusy(busy) {
    keyState.busy = busy;
    keyElement('key-form').querySelectorAll('button, input, textarea').forEach(node => { node.disabled = busy; });
    keyElement('key-create').disabled = busy || keyState.loading || !keyState.ready || keyState.uncertain;
    keyElement('key-refresh').disabled = busy || keyState.loading;
    keyElement('key-list').querySelectorAll('button').forEach(node => { node.disabled = busy || keyState.loading || keyState.uncertain; });
    keyElement('key-save').disabled = busy || keyState.uncertain;
    if (!busy) keySourceChanged();
}
function keySourceChanged() {
    keyElement('key-networks').disabled = keyState.busy || keyElement('key-form').elements['key-source'].value === 'any';
}
