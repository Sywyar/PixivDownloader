'use strict';

window.PixivFilenameTemplatePresets = {
    bind(input, select, settings, save, text, enabled) {
        select.hidden = !enabled;
        select.disabled = !enabled;
        select.onchange = null;
        select.onfocus = null;
        if (!enabled) return;

        function templates() {
            const values = settings.fileNameTemplates;
            return Array.isArray(values)
                ? [...new Set(values.filter(value => typeof value === 'string').map(value => value.trim()).filter(Boolean))]
                : [];
        }

        function refresh() {
            const values = templates();
            const label = text('template-set.label');
            select.setAttribute('aria-label', label);
            select.setAttribute('data-i18n-aria-label', 'batch:template-set.label');
            select.replaceChildren();
            function option(value, label, key) {
                const node = document.createElement('option');
                node.value = value;
                node.textContent = label;
                if (key) node.setAttribute('data-i18n', 'batch:' + key);
                select.appendChild(node);
                return node;
            }
            const placeholder = option('', '');
            placeholder.hidden = true;
            placeholder.disabled = true;
            values.forEach((value, index) => option(String(index), value));
            const current = input.value.trim();
            option('save', text('template-set.save'), 'template-set.save').disabled = !current || values.includes(current);
            select.value = '';
        }

        select.onfocus = refresh;
        select.onchange = () => {
            const chosen = select.value;
            const values = templates();
            select.setCustomValidity('');
            if (chosen === 'save') {
                const current = input.value.trim();
                if (current && !values.includes(current)) {
                    const previous = settings.fileNameTemplates;
                    settings.fileNameTemplates = [...values, current];
                    try {
                        save();
                    } catch {
                        settings.fileNameTemplates = previous;
                        select.setCustomValidity(text('template-set.save-failed'));
                        select.reportValidity();
                    }
                }
            } else if (chosen !== '' && values[Number(chosen)] !== undefined) {
                input.value = values[Number(chosen)];
                input.dispatchEvent(new Event('change', {bubbles: true}));
            }
            refresh();
        };
        refresh();
    }
};
