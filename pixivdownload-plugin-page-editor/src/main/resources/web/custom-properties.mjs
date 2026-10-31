export async function mountProperties(root, context) {
    let configuration = structuredClone(context.properties);
    const get = async path => {
        const response = await fetch('/api/page-editor/components/' + path, {signal: context.signal, cache: 'no-store'});
        if (!response.ok) throw new Error('PAGE_COMPONENT_UNAVAILABLE');
        return response.json();
    };
    const state = await get(configuration.componentId);
    if (context.signal.aborted) return;
    const versions = document.createElement('select');
    const caption = document.createElement('label'); caption.className = 'editor-field'; caption.textContent = context.t('custom.version');
    for (const version of [...state.versions].reverse()) {
        const option = document.createElement('option'); option.value = version;
        option.textContent = version.slice(0, 12) + (version === state.latest ? ' · ' + context.t('custom.latest') : '');
        versions.append(option);
    }
    versions.value = configuration.version; caption.append(versions);
    const apply = document.createElement('button'); apply.textContent = context.t('custom.use-version');
    const fields = document.createElement('div'); root.append(caption, apply, fields);
    const render = async () => {
        const source = await get(configuration.componentId + '/versions/' + configuration.version);
        if (context.signal.aborted) return;
        fields.replaceChildren();
        for (const [name, property] of Object.entries(source.properties)) {
            const label = document.createElement('label'); label.className = 'editor-field'; label.textContent = property.label || name;
            const input = document.createElement('input');
            input.type = property.type === 'boolean' ? 'checkbox' : property.type === 'number' ? 'number' : 'text';
            input.step = 'any'; input.maxLength = 16384;
            const value = configuration.properties[name] ?? String(property.default);
            if (property.type === 'boolean') input.checked = value === 'true'; else input.value = value;
            input.addEventListener('change', () => {
                if (!input.checkValidity() || property.type === 'number' && !input.value.trim()) { input.reportValidity(); return; }
                configuration.properties[name] = property.type === 'boolean' ? String(input.checked) : input.value;
                context.change(configuration);
            }, {signal: context.signal});
            label.append(input); fields.append(label);
        }
    };
    apply.addEventListener('click', async () => {
        apply.disabled = true;
        try {
            const source = await get(configuration.componentId + '/versions/' + versions.value);
            const next = {};
            for (const [name, property] of Object.entries(source.properties)) next[name] = String(property.default);
            configuration = {...configuration, version: versions.value, properties: next};
            context.change(configuration); await render();
        } catch (_) { fields.textContent = context.t('failed'); }
        finally { apply.disabled = false; }
    }, {signal: context.signal});
    await render();
}
