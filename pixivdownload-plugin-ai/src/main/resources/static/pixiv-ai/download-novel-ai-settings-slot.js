(function (global) {
    'use strict';

    var queueTypes = global.PixivBatch && global.PixivBatch.queueTypes;
    if (!queueTypes || typeof queueTypes.registerUiModule !== 'function') return;

    queueTypes.registerUiModule(function (context) {
        var unbindSettings = function () {};

        function targetGrid() {
            var card = document.getElementById('novel-settings-card');
            return card ? card.querySelector('.settings-grid') : null;
        }

        function render() {
            if (!context.isActive()) return;
            var grid = targetGrid();
            if (!grid || document.getElementById('s-novel-auto-translate-row')) return;
            var wrapper = document.createElement('div');
            wrapper.innerHTML =
                '<div class="setting-item" id="s-novel-auto-translate-row" style="display:none;">' +
                '<input type="checkbox" id="s-novel-auto-translate">' +
                '<label for="s-novel-auto-translate" style="cursor:pointer;" data-i18n="ai:batch.auto-translate-label">Auto-translate newly downloaded novels</label>' +
                '</div>' +
                '<div class="setting-item" id="s-novel-translate-lang-row" style="display:none;">' +
                '<label for="s-novel-translate-lang" data-i18n="ai:batch.translate-lang-label">Target language:</label>' +
                '<input type="text" id="s-novel-translate-lang" class="novel-translate-input" spellcheck="false">' +
                '</div>' +
                '<div class="setting-item" id="s-novel-translate-seg-row" style="display:none;">' +
                '<label for="s-novel-translate-seg" data-i18n="ai:batch.translate-seg-label">Segment size:</label>' +
                '<input type="number" id="s-novel-translate-seg" class="novel-translate-input novel-translate-input--number" min="0" step="500" value="0">' +
                '</div>' +
                '<div class="setting-item" id="s-novel-translate-hint" style="display:none;grid-column:1/-1;font-size:12px;color:var(--muted);line-height:1.5;"' +
                ' data-i18n="ai:batch.translate-glossary-hint">Auto-translation uses the default term glossary. Segment size 0 translates the whole chapter at once.</div>';
            while (wrapper.firstChild) {
                grid.appendChild(wrapper.firstChild);
            }
            if (typeof pageI18n !== 'undefined' && pageI18n) {
                try { pageI18n.apply(grid); } catch (_) {}
            }
            var settings = global.PixivBatch && global.PixivBatch.settings;
            if (settings && typeof settings.bindContributedSettings === 'function') {
                unbindSettings();
                var defaultLanguage = function () { return bt('ai:batch.translate-lang-default', 'english'); };
                unbindSettings = settings.bindContributedSettings(grid, {
                    's-novel-auto-translate': {key: 'novelAutoTranslate',
                        read: function (input) { return input.checked; },
                        write: function (input, value) { input.checked = !!value; }},
                    's-novel-translate-lang': {key: 'novelTranslateLang',
                        read: function (input) {
                            var value = input.value.trim();
                            return value && value !== defaultLanguage() ? value : '';
                        },
                        write: function (input, value) { input.value = value || defaultLanguage(); }},
                    's-novel-translate-seg': {key: 'novelTranslateSeg',
                        read: function (input) { return Math.max(0, parseInt(input.value, 10) || 0); },
                        write: function (input, value) { input.value = value || 0; }}
                }, function () { return context.isActive() && isAdmin; }, function () {
                    var enabled = isAdmin && grid.querySelector('#s-novel-auto-translate').checked;
                    grid.querySelector('#s-novel-auto-translate-row').style.display = isAdmin ? '' : 'none';
                    ['s-novel-translate-lang-row', 's-novel-translate-seg-row', 's-novel-translate-hint']
                        .forEach(function (id) { grid.querySelector('#' + id).style.display = enabled ? '' : 'none'; });
                });
            } else if (settings && typeof settings.updateNovelTranslateVisibility === 'function') {
                settings.updateNovelTranslateVisibility();
            }
        }

        function removeRendered() {
            unbindSettings();
            [
                's-novel-auto-translate-row',
                's-novel-translate-lang-row',
                's-novel-translate-seg-row',
                's-novel-translate-hint'
            ].forEach(function (id) {
                var node = document.getElementById(id);
                if (node && typeof node.remove === 'function') node.remove();
            });
        }

        function onSlotsRendered() {
            if (context.isActive()) render();
        }

        if (typeof global.addEventListener === 'function') {
            global.addEventListener('pixivbatch:slotsrendered', onSlotsRendered);
            context.onCleanup(function () {
                if (typeof global.removeEventListener === 'function') {
                    global.removeEventListener('pixivbatch:slotsrendered', onSlotsRendered);
                }
            });
        }
        context.onCleanup(removeRendered);
        render();
    });
})(window);
