'use strict';

window.PixivMediaSettings = (() => {
    const fields = [
        {key: 'imageFormats', name: 'image-formats', initial: 'original', options: ['original', 'png', 'jpg', 'webp']},
        {key: 'ugoiraFormats', name: 'ugoira-formats', initial: 'webp', options: ['webp', 'gif', 'apng', 'mp4', 'zip']}
    ];

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
        const refreshers = [];
        const visibleFields = fields;
        for (const field of visibleFields) {
            const row = document.createElement('div');
            row.className = 'media-format-row';
            const label = document.createElement('span');
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
            const help = document.createElement('small');
            help.textContent = translate('media.' + field.name + '.help');
            row.append(label, dropdown, help);
            container.append(row);
            refreshers.push(refresh);
            refresh();
        }
        fetch('/api/download/media/settings', {credentials: 'same-origin'})
            .then(response => response.ok ? response.json() : null)
            .then(defaults => {
                if (!defaults || !container.isConnected) return;
                for (const field of visibleFields) {
                    if (settings[field.key] == null && valid(defaults[field.key], field.options)) {
                        settings[field.key] = defaults[field.key];
                    }
                }
                refreshers.forEach(refresh => refresh());
            }).catch(() => {});
    }
    return {mount, valid};
})();
