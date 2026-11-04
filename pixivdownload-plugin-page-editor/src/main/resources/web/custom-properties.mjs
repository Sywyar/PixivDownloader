import {errorMessage} from './editor-feedback.mjs';

export async function mountProperties(root, context) {
    let configuration = structuredClone(context.properties);
    let renderAbort, applyAbort, epoch = 0;
    const inputs = new Map();
    const get = async (path, signal) => {
        const response = await fetch('/api/page-editor/components/' + path,
            {signal: AbortSignal.any([context.signal, signal]), cache: 'no-store'});
        if (!response.ok) throw new Error('PAGE_COMPONENT_UNAVAILABLE');
        return response.json();
    };
    const versions = document.createElement('select');
    const caption = document.createElement('label'); caption.className = 'editor-field'; caption.textContent = context.t('custom.version');
    caption.append(versions);
    const apply = document.createElement('button'); apply.textContent = context.t('custom.use-version');
    apply.type = 'button';
    const fields = document.createElement('div'); root.append(caption, apply, fields);
    const sync = () => {
        for (const [name, {input, property}] of inputs) {
            const value = configuration.properties[name] ?? String(property.default);
            if (property.type === 'boolean') input.checked = value === 'true';
            else if (input.value !== value) input.value = value;
        }
    };
    const render = async () => {
        renderAbort?.abort();
        const pending = renderAbort = new AbortController();
        const {componentId, version} = configuration;
        inputs.clear(); fields.replaceChildren();
        const state = await get(componentId, pending.signal);
        const source = await get(componentId + '/versions/' + version, pending.signal);
        if (pending.signal.aborted || context.signal.aborted) return;
        versions.replaceChildren();
        for (const version of [...state.versions].reverse()) {
            const option = document.createElement('option'); option.value = version;
            option.textContent = version.slice(0, 12) + (version === state.latest ? ' · ' + context.t('custom.latest') : '');
            versions.append(option);
        }
        versions.value = configuration.version;
        for (const [name, property] of Object.entries(source.properties)) {
            const label = document.createElement('label'); label.className = 'editor-field'; label.textContent = property.label || name;
            const input = document.createElement('input');
            input.type = property.type === 'boolean' ? 'checkbox' : property.type === 'number' ? 'number' : 'text';
            input.step = 'any'; input.maxLength = 16384;
            input.addEventListener('change', () => {
                if (pending.signal.aborted || context.signal.aborted) return;
                if (!input.checkValidity() || property.type === 'number' && !Number.isFinite(input.valueAsNumber)) {
                    input.reportValidity(); return;
                }
                const value = property.type === 'boolean' ? String(input.checked)
                    : property.type === 'number' ? JSON.stringify(input.valueAsNumber) : input.value;
                configuration = {...configuration, properties: {...configuration.properties, [name]: value}};
                context.change(configuration);
            }, {signal: pending.signal});
            inputs.set(name, {input, property});
            label.append(input); fields.append(label);
        }
        sync();
    };
    apply.addEventListener('click', async () => {
        if (apply.disabled || context.signal.aborted) return;
        const pending = applyAbort = new AbortController();
        const current = epoch, componentId = configuration.componentId, version = versions.value;
        apply.disabled = true;
        try {
            const source = await get(componentId + '/versions/' + version, pending.signal);
            if (pending.signal.aborted || context.signal.aborted || current !== epoch) return;
            const next = {};
            for (const [name, property] of Object.entries(source.properties)) next[name] = String(property.default);
            context.change({...configuration, version, properties: next});
        } catch (error) {
            if (!pending.signal.aborted && !context.signal.aborted && current === epoch) fields.textContent = errorMessage(error, context.t);
        } finally { if (applyAbort === pending) { applyAbort = undefined; apply.disabled = false; } }
    }, {signal: context.signal});
    context.onCleanup(() => { renderAbort?.abort(); applyAbort?.abort(); inputs.clear(); });
    await render();
    return {
        update(next) {
            epoch++;
            applyAbort?.abort(); applyAbort = undefined; apply.disabled = false;
            const changed = next.componentId !== configuration.componentId || next.version !== configuration.version;
            configuration = structuredClone(next);
            if (changed) return render();
            sync();
        }
    };
}
