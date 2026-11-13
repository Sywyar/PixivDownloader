'use strict';
keyElement('key-create').addEventListener('click', () => openKeyForm(null));
keyElement('key-refresh').addEventListener('click', loadKeys);
keyElement('key-cancel').addEventListener('click', closeKeyForm);
keyElement('key-form').addEventListener('submit', saveKey);
keyElement('key-form').elements['key-source'].forEach(input => input.addEventListener('change', keySourceChanged));
keyElement('key-done').addEventListener('click', () => {
    clearKeySecret(); keyElement('key-list-view').hidden = false; keyElement('key-create').focus();
});
keyElement('key-copy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(keyElement('key-secret').value); keyElement('key-copy-status').textContent = keyText('copied'); }
    catch (_) { keyElement('key-secret').focus(); keyElement('key-secret').select(); keyElement('key-copy-status').textContent = keyText('copy.manual'); }
});
window.addEventListener('pagehide', () => {
    keyState.visit++; keyState.active = false; clearKeySecret();
    if (keyState.busy) keyState.uncertain = true;
    if (keyState.loadController) keyState.loadController.abort();
    keyState.loadController = null; keyState.loading = false;
});
window.addEventListener('pageshow', event => {
    keyState.active = true;
    if (event.persisted && keyI18n) { keyElement('key-list-view').hidden = !keyElement('key-form').hidden; loadKeys(); }
});
(async () => {
    keyI18n = await PixivI18n.create({namespaces: ['security', 'common']}); keyI18n.apply();
    await loadKeys();
    keySwitcher = await PixivLangSwitcher.mount({ mountPoint: keyElement('langSwitcherAnchor'), i18n: keyI18n,
        onChange: client => {
            keyI18n = client; renderKeys();
            if (!keyElement('key-form').hidden) {
                const selected = new Set(Array.from(keyElement('key-permissions').querySelectorAll('input[name="permission"]:checked'), input => input.value));
                renderKeyPermissions(selected); keyElement('key-form-title').textContent = keyText(keyState.editing ? 'edit' : 'create');
            }
        }
    });
    if (window.PixivTheme) PixivTheme.mount({mountPoint: keyElement('langSwitcherAnchor')});
})();
