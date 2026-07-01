(function (global) {
    'use strict';

    function slotHost() {
        if (global.PixivVue && typeof global.PixivVue.anchorFor === 'function') {
            return global.PixivVue.anchorFor('settings-card-ai');
        }
        return document.querySelector('[data-vue-slot="settings-card-ai"]')
                || document.getElementById('settings-card-ai');
    }

    function render() {
        var host = slotHost();
        if (!host || document.getElementById('novel-ai-settings-card')) return;
        host.innerHTML =
            '<div class="card" id="novel-ai-settings-card" style="display:none;">' +
            '<div class="card-title" data-i18n="ai:batch.auto-translate-label">Auto-translate newly downloaded novels</div>' +
            '<div class="settings-grid">' +
            '<div class="setting-item" id="s-novel-auto-translate-row" style="display:none;">' +
            '<input type="checkbox" id="s-novel-auto-translate">' +
            '<label for="s-novel-auto-translate" style="cursor:pointer;" data-i18n="ai:batch.auto-translate-label">Auto-translate newly downloaded novels</label>' +
            '</div>' +
            '<div class="setting-item" id="s-novel-translate-lang-row" style="display:none;">' +
            '<label for="s-novel-translate-lang" data-i18n="ai:batch.translate-lang-label">Target language:</label>' +
            '<input type="text" id="s-novel-translate-lang" spellcheck="false"' +
            ' style="padding:4px 6px;border:1px solid #ddd;border-radius:4px;font-size:13px;max-width:160px;">' +
            '</div>' +
            '<div class="setting-item" id="s-novel-translate-seg-row" style="display:none;">' +
            '<label for="s-novel-translate-seg" data-i18n="ai:batch.translate-seg-label">Segment size:</label>' +
            '<input type="number" id="s-novel-translate-seg" min="0" step="500" value="0"' +
            ' style="padding:4px 6px;border:1px solid #ddd;border-radius:4px;font-size:13px;max-width:120px;">' +
            '</div>' +
            '<div class="setting-item" id="s-novel-translate-hint" style="display:none;grid-column:1/-1;font-size:12px;color:var(--muted);line-height:1.5;"' +
            ' data-i18n="ai:batch.translate-glossary-hint">Auto-translation uses the default term glossary. Segment size 0 translates the whole chapter at once.</div>' +
            '</div></div>';
        if (typeof pageI18n !== 'undefined' && pageI18n) {
            try { pageI18n.apply(host); } catch (_) {}
        }
    }

    render();
})(window);
