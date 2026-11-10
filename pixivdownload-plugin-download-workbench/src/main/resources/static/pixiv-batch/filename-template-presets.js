'use strict';

window.PixivFilenameTemplatePresets = (() => {
    const bindings = new WeakMap();
    function bind(input, select, settings, save, text, enabled) {
        bindings.get(select)?.();
        bindings.delete(select);
        select.hidden = !enabled;
        select.disabled = !enabled;
        select.onchange = null;
        select.onfocus = null;
        if (!enabled) return;

        const toolbar = document.createElement('div');
        toolbar.className = 'filename-template-presets';
        input.parentNode.insertBefore(toolbar, input);
        toolbar.appendChild(select);
        const actions = document.createElement('div');
        actions.className = 'filename-template-preset-actions';
        function button(key) {
            const node = document.createElement('button');
            node.type = 'button';
            node.textContent = text(key);
            node.setAttribute('data-i18n', 'batch:' + key);
            actions.appendChild(node);
            return node;
        }
        const saveButton = button('template-set.save');
        const removeButton = button('template-set.remove');
        toolbar.appendChild(actions);
        const status = document.createElement('p');
        status.className = 'filename-template-status';
        status.setAttribute('role', 'status');
        toolbar.appendChild(status);

        function templates() {
            const values = settings.fileNameTemplates;
            return Array.isArray(values)
                ? [...new Set(values.filter(value => typeof value === 'string').map(value => value.trim()).filter(Boolean))]
                : [];
        }

        function refresh() {
            const values = templates();
            const label = text('template-set.label');
            saveButton.textContent = text('template-set.save');
            removeButton.textContent = text('template-set.remove');
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
            const key = values.length ? 'template-set.choose' : 'template-set.empty';
            const placeholder = option('', text(key), key);
            placeholder.disabled = true;
            values.forEach((value, index) => option(String(index), value));
            const current = input.value.trim();
            const index = values.indexOf(current);
            select.value = index < 0 ? '' : String(index);
            select.disabled = !values.length;
            saveButton.disabled = !current || index >= 0;
            removeButton.disabled = index < 0;
        }

        function clearStatus() {
            status.textContent = '';
            status.removeAttribute('data-i18n');
        }
        function updateTemplates(remove) {
            clearStatus();
            const focused = document.activeElement;
            const values = templates();
            const current = input.value.trim();
            if (current && (remove ? values.includes(current) : !values.includes(current))) {
                const previous = settings.fileNameTemplates;
                settings.fileNameTemplates = remove
                    ? values.filter(value => value !== current) : [...values, current];
                try {
                    save();
                } catch {
                    settings.fileNameTemplates = previous;
                    status.textContent = text('template-set.save-failed');
                    status.setAttribute('data-i18n', 'batch:template-set.save-failed');
                }
            }
            refresh();
            if (focused === saveButton && saveButton.disabled) select.focus();
            if (focused === removeButton && removeButton.disabled) input.focus();
        }
        select.onfocus = refresh;
        select.onchange = () => {
            clearStatus();
            const chosen = select.value;
            const values = templates();
            if (chosen !== '' && values[Number(chosen)] !== undefined) {
                input.value = values[Number(chosen)];
                input.dispatchEvent(new Event('change', {bubbles: true}));
            }
            refresh();
        };
        saveButton.onclick = () => updateTemplates(false);
        removeButton.onclick = () => updateTemplates(true);
        function edited() { clearStatus(); refresh(); }
        input.addEventListener('input', edited);
        input.addEventListener('change', edited);
        bindings.set(select, () => {
            input.removeEventListener('input', edited);
            input.removeEventListener('change', edited);
            saveButton.onclick = removeButton.onclick = null;
            toolbar.parentNode.insertBefore(select, toolbar);
            toolbar.parentNode.removeChild(toolbar);
        });
        refresh();
    }
    return {bind};
})();
