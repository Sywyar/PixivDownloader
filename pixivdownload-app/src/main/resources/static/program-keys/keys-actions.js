'use strict';
let keySwitcher;
async function loadKeys() {
    if (keyState.loading || keyState.busy) return;
    const controller = new AbortController(); keyState.loadController = controller;
    keyState.loading = true; keyBusy(false);
    try {
        const [keys, permissions] = await Promise.all([keyApi('', 'GET', undefined, controller.signal), keyApi('/permissions', 'GET', undefined, controller.signal)]);
        if (!keyState.active || controller.signal.aborted) return;
        const namespaces = [...new Set(['security', 'common', ...permissions.flatMap(permission => [permission.namespace, permission.ownerNamespace])].filter(Boolean))];
        if (namespaces.some(namespace => !keyI18n.namespaces.includes(namespace))) {
            const next = await PixivI18n.create({lang: keyI18n.lang, namespaces});
            if (!keyState.active || controller.signal.aborted) { next.dispose(); return; }
            if (keySwitcher) keySwitcher.refresh(next); else keyI18n.dispose();
            keyI18n = next; keyI18n.apply();
        }
        keyState.keys = keys; keyState.permissions = permissions; keyState.ready = true;
        keyState.uncertain = false;
        keyElement('key-error').textContent = '';
        renderKeys();
    } catch (error) {
        if (keyState.active && !controller.signal.aborted) keyElement('key-error').textContent = error.message || keyText('request.failed');
    } finally {
        controller.abort();
        if (keyState.loadController === controller) {
            keyState.loadController = null; keyState.loading = false; keyBusy(keyState.busy);
        }
    }
}
async function saveKey(event) {
    event.preventDefault();
    if (keyState.busy || keyState.loading || keyState.uncertain) return;
    const anyIp = keyElement('key-form').elements['key-source'].value === 'any';
    const policy = {
        name: keyElement('key-name').value.trim(),
        permissions: Array.from(keyElement('key-permissions').querySelectorAll('input[name="permission"]:checked'), input => input.value),
        anyIp, networks: anyIp ? [] : keyElement('key-networks').value.split(/[\n,]/).map(value => value.trim()).filter(Boolean)
    };
    if (!anyIp && !policy.networks.length) { keyElement('key-form-error').textContent = keyText('source.required'); keyElement('key-networks').focus(); return; }
    const editing = keyState.editing;
    const visit = keyState.visit;
    let submitted = false;
    const names = keyState.permissions.filter(permission => policy.permissions.includes(permission.id))
        .map(permission => keyI18n.t(permission.ownerNamespace + ':' + permission.ownerLabelKey, permission.owner)
            + ' · ' + keyLabel(permission));
    const message = policy.name + '\n\n' + (names.length ? names.join('\n') : keyText('permissions.none')) + '\n\n' + keyText('source') + ': ' + keySource(policy);
    keyBusy(true);
    try {
        const confirmed = await PixivFeedback.confirm({ title: keyText('review.title'), message,
            confirmLabel: keyText(editing ? 'save' : 'create'), cancelLabel: keyText('cancel') });
        if (!confirmed || !keyState.active || visit !== keyState.visit) return;
        submitted = true;
        const result = await keyApi(editing ? '/' + editing.id : '', editing ? 'PUT' : 'POST', editing ? {revision: editing.revision, policy} : policy);
        if (!keyState.active || visit !== keyState.visit) return;
        keyBusy(false); closeKeyForm();
        if (!editing) {
            keyElement('key-list-view').hidden = true; keyElement('key-created').hidden = false;
            keyElement('key-secret').value = result.secret;
            keyElement('key-copy-status').textContent = '';
            keyElement('key-created-title').focus();
        }
        await loadKeys();
    } catch (error) {
        if (visit === keyState.visit) {
            keyState.uncertain = submitted && !error.definitive;
            if (keyState.active) keyElement('key-form-error').textContent = (error.message || keyText('request.failed')) + ' ' + keyText('request.check-before-retry');
        }
    } finally {
        keyBusy(false);
        if (visit !== keyState.visit && keyState.active) await loadKeys();
    }
}
async function revokeKey(key, button) {
    if (keyState.busy || keyState.loading || keyState.uncertain) return;
    const visit = keyState.visit;
    keyBusy(true); button.disabled = true;
    try {
        const confirmed = await PixivFeedback.confirm({title: keyText('revoke'), message: keyText('revoke.help', {name: key.policy.name}),
            confirmLabel: keyText('revoke'), cancelLabel: keyText('cancel'), danger: true});
        if (!confirmed || !keyState.active || visit !== keyState.visit) return;
        await keyApi('/' + key.id, 'DELETE', {revision: key.revision});
        if (!keyState.active || visit !== keyState.visit) return;
        keyBusy(false); await loadKeys();
    } catch (error) { if (keyState.active && visit === keyState.visit) keyElement('key-error').textContent = error.message || keyText('request.failed'); }
    finally { keyBusy(false); if (visit !== keyState.visit && keyState.active) await loadKeys(); }
}
function clearKeySecret() { keyElement('key-secret').value = ''; keyElement('key-created').hidden = true; }
