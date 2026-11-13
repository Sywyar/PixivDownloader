'use strict';
function renderKeys() {
    const list = keyElement('key-list');
    list.replaceChildren();
    if (!keyState.keys.length) list.append(keyNode('p', keyText('empty')));
    keyState.keys.forEach(key => {
        const row = keyNode('article', null, 'key-row');
        const description = keyNode('div');
        description.append(keyNode('strong', key.policy.name));
        description.append(keyNode('p', keyText('summary', {count: key.policy.permissions.length}) + ' · ' + keySource(key.policy)));
        const created = new Date(key.createdAt).toLocaleString(document.documentElement.lang);
        const used = key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleString(document.documentElement.lang) : keyText('unused');
        description.append(keyNode('p', keyText('times', {created, used})));
        const actions = keyNode('div', null, 'key-actions');
        const edit = keyNode('button', keyText('edit')); edit.type = 'button'; edit.addEventListener('click', () => openKeyForm(key));
        const revoke = keyNode('button', keyText('revoke')); revoke.type = 'button'; revoke.addEventListener('click', () => revokeKey(key, revoke));
        actions.append(edit, revoke); row.append(description, actions); list.append(row);
    });
}
function renderKeyPermissions(selected) {
    const container = keyElement('key-permissions'); container.replaceChildren();
    const groups = new Map();
    keyState.permissions.forEach(permission => {
        if (!groups.has(permission.owner)) groups.set(permission.owner, []);
        groups.get(permission.owner).push(permission);
    });
    groups.forEach(permissions => {
        const first = permissions[0];
        const group = keyNode('details', null, 'key-permission-group'); group.open = permissions.some(p => selected.has(p.id));
        const label = keyI18n.t(first.ownerNamespace + ':' + first.ownerLabelKey, first.owner);
        group.append(keyNode('summary', label));
        const allLabel = keyNode('label', null, 'key-choice');
        const all = document.createElement('input'); all.type = 'checkbox';
        allLabel.append(all, keyNode('span', keyText('select.current'))); group.append(allLabel);
        const inputs = [];
        const updateAll = () => { all.checked = inputs.every(input => input.checked); all.indeterminate = !all.checked && inputs.some(input => input.checked); };
        permissions.forEach(permission => {
            const row = keyNode('label', null, 'key-choice');
            const input = document.createElement('input'); input.type = 'checkbox'; input.value = permission.id;
            input.name = 'permission'; input.checked = selected.has(permission.id); input.addEventListener('change', updateAll);
            inputs.push(input); row.append(input, keyNode('span', keyLabel(permission))); group.append(row);
        });
        all.addEventListener('change', () => { inputs.forEach(input => { input.checked = all.checked; }); updateAll(); });
        updateAll(); container.append(group);
    });
    const unavailable = [...selected].filter(id => !keyState.permissions.some(permission => permission.id === id));
    if (unavailable.length) container.append(keyNode('p', keyText('permissions.unavailable'), 'key-help'));
}
function openKeyForm(key) {
    if (keyState.busy || keyState.loading || !keyState.ready || keyState.uncertain) return;
    keyState.editing = key;
    keyElement('key-form').reset();
    keyElement('key-name').value = key ? key.policy.name : '';
    keyElement('key-networks').value = key ? key.policy.networks.join('\n') : '';
    keyElement('key-form').elements['key-source'].value = key && key.policy.anyIp ? 'any' : 'restricted';
    keyElement('key-form-title').textContent = keyText(key ? 'edit' : 'create');
    keyElement('key-form-error').textContent = '';
    renderKeyPermissions(new Set(key ? key.policy.permissions : []));
    keyElement('key-list-view').hidden = true; keyElement('key-form').hidden = false;
    keySourceChanged(); keyElement('key-name').focus();
}
function closeKeyForm() {
    if (keyState.busy) return;
    keyState.editing = null; keyElement('key-form').hidden = true; keyElement('key-list-view').hidden = false;
    keyElement('key-form').reset(); keyElement(keyState.uncertain ? 'key-refresh' : 'key-create').focus();
}
