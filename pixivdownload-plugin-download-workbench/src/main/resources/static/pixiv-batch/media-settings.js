'use strict';

window.PixivMediaSettings = (() => {
    const fields = [
        {key: 'imageFormats', name: 'image-formats', initial: 'original', options: ['original', 'png', 'jpg', 'webp']},
        {key: 'ugoiraFormats', name: 'ugoira-formats', initial: 'webp', options: ['webp', 'gif', 'apng', 'mp4', 'zip']}
    ];
    const encodingFields = [
        {key: 'mediaQuality', name: 'quality', initial: 90, min: 1, max: 100},
        {key: 'mediaWebpLossless', name: 'webp-lossless', initial: false},
        {key: 'mediaMaximumEdge', name: 'maximum-edge', initial: 0, min: 0, max: 16383}
    ];

    function snapshot(settings) {
        const result = {};
        for (const field of fields) result[field.key] = valid(settings[field.key], field.options) ? settings[field.key] : field.initial;
        for (const field of encodingFields) {
            const value = settings[field.key];
            result[field.key] = field.min == null ? value === true
                : Number.isInteger(value) && value >= field.min && value <= field.max ? value : field.initial;
        }
        return result;
    }

    function valid(value, options) {
        if (typeof value !== 'string' || !value) return false;
        const selected = value.split(',');
        return selected.length === new Set(selected).size && selected.every(format => options.includes(format));
    }

    function mount(container, settings, changed, translate, admin) {
        if (!container) return;
        container.replaceChildren();
        container.hidden = !admin;
        if (!admin) return;
        const classic = !!container.closest('#download-settings-card');
        const rowClass = classic ? 'setting-item' : 'media-format-row';
        if (!classic) {
            const scope = document.createElement('p');
            scope.className = 'media-settings-scope';
            scope.textContent = translate('media.settings.scope');
            container.append(scope);
        }
        for (const field of fields) {
            const row = document.createElement('div');
            row.className = rowClass;
            const label = document.createElement(classic ? 'label' : 'span');
            label.textContent = translate('media.' + field.name + '.label');
            const dropdown = document.createElement('details');
            dropdown.className = 'media-format-select';
            const summary = document.createElement('summary');
            summary.setAttribute('aria-label', label.textContent);
            const options = document.createElement('div');
            options.className = 'media-format-options';
            const controls = new Map();
            function selected() {
                const value = valid(settings[field.key], field.options) ? settings[field.key] : field.initial;
                return value.split(',');
            }
            function refresh() {
                const values = selected();
                summary.textContent = values.map(format => translate('media.format.' + format)).join(', ');
                controls.forEach((checkbox, format) => { checkbox.checked = values.includes(format); });
            }
            for (const format of field.options) {
                const option = document.createElement('label');
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.value = format;
                const text = document.createElement('span');
                text.textContent = translate('media.format.' + format);
                checkbox.addEventListener('change', () => {
                    const values = field.options.filter(value => controls.get(value).checked);
                    if (values.length) {
                        settings[field.key] = values.join(',');
                        changed();
                    }
                    refresh();
                });
                controls.set(format, checkbox);
                option.append(checkbox, text);
                options.append(option);
            }
            dropdown.addEventListener('keydown', event => {
                if (event.key === 'Escape' && dropdown.open) {
                    event.preventDefault();
                    event.stopPropagation();
                    dropdown.open = false;
                    summary.focus();
                }
            });
            dropdown.addEventListener('focusout', event => {
                // label 激活控件前，焦点可能暂时为空或落在祖先模态窗口上，不能在此时隐藏控件。
                const next = event.relatedTarget;
                if (next && !dropdown.contains(next) && !next.contains(dropdown)) dropdown.open = false;
            });
            dropdown.append(summary, options);
            row.append(label, dropdown);
            if (!classic) {
                const help = document.createElement('small');
                help.textContent = translate('media.' + field.name + '.help');
                row.append(help);
            }
            container.append(row);
            refresh();
        }
        for (const field of encodingFields) {
            const row = document.createElement('div');
            row.className = rowClass + ' media-encoding-row';
            const label = document.createElement('label');
            label.htmlFor = 'media-setting-' + field.key;
            label.textContent = translate('media.' + field.name + '.label');
            const input = document.createElement('input');
            input.id = label.htmlFor;
            const checkbox = field.min == null;
            if (checkbox) row.classList.add('media-checkbox-row');
            input.type = checkbox ? 'checkbox' : 'number';
            if (checkbox) input.checked = snapshot(settings)[field.key];
            else {
                if (!classic) input.className = 'ab-input';
                input.min = String(field.min);
                input.max = String(field.max);
                input.step = '1';
                input.required = true;
                input.value = String(snapshot(settings)[field.key]);
            }
            input.addEventListener('change', () => {
                if (!input.reportValidity()) return;
                settings[field.key] = checkbox ? input.checked : input.valueAsNumber;
                changed();
            });
            let control = input;
            if (checkbox && container.closest('.ab-settings')) {
                control = document.createElement('label');
                control.className = 'toggle ab-switch';
                input.setAttribute('role', 'switch');
                const icon = document.createElement('span');
                icon.className = 'toggle-icon';
                control.append(input, icon);
            }
            if (classic && checkbox) row.append(control, label);
            else row.append(label, control);
            if (!classic) {
                const help = document.createElement('small');
                help.id = input.id + '-help';
                help.textContent = translate('media.' + field.name + '.help');
                input.setAttribute('aria-describedby', help.id);
                row.append(help);
            }
            container.append(row);
        }
    }
    return {mount, valid, snapshot};
})();
