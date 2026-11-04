// 编辑器读取同一个目标解析器；配置只成为草稿，正式执行仍由发布事务决定。
export async function mountHooks(root, page, bindings, update, t, signal, relations = {actions: []}, names) {
    root.replaceChildren();
    const list = document.createElement('div');
    root.append(list);
    const render = () => {
        list.replaceChildren();
        for (const binding of bindings) {
            const row = document.createElement('p');
            row.append(document.createTextNode(`${names.policy(binding.hook)} · ${t('hook.' + binding.phase)} · ${names.operation(binding.operation || binding.endpoint)} `));
            const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = t('delete');
            remove.addEventListener('click', () => { bindings = bindings.filter(value => value.id !== binding.id); update(bindings); render(); }, {signal});
            row.append(remove); list.append(row);
        }
    };
    render();
    const request = async path => {
        const response = await fetch('/api/web/operation-hooks/' + path, {credentials: 'same-origin', signal});
        if (!response.ok) throw new Error('PAGE_HOOK_UNAVAILABLE');
        return response.json();
    };
    const definitions = await request('definitions');
    const catalog = await request('catalog');
    if (signal.aborted) return;
    mountActions(root, page, catalog, relations, t, signal);
    const form = document.createElement('form');
    const field = (key, node) => {
        const label = document.createElement('label'); label.className = 'editor-field';
        const caption = document.createElement('span'); caption.textContent = t('hook.' + key);
        label.append(caption, node); form.append(label); return node;
    };
    const option = (select, value, text) => { const node = document.createElement('option'); node.value = value; node.textContent = text; select.append(node); };
    const target = field('action', document.createElement('select'));
    option(target, '', t('select-first'));
    const targets = (page.targets || []).map(value => ({id: value.id, label: names.operation(value.operation)}));
    for (const value of targets) {
        const duplicate = targets.filter(item => item.label === value.label).length > 1;
        option(target, value.id, value.label + (duplicate ? ' (' + value.id + ')' : ''));
    }
    const endpoint = field('scope', document.createElement('select'));
    const hook = field('definition', document.createElement('select'));
    option(hook, '', t('select-first'));
    for (const value of definitions) option(hook, value.id, names.policy(value.id, value.owner));
    const phase = field('phase', document.createElement('select'));
    const completion = field('completion', document.createElement('select'));
    for (const value of ['ALL', 'SUCCEEDED', 'FAILED', 'BLOCKED']) option(completion, value, t('hook.' + value));
    const order = field('order', document.createElement('input')); order.type = 'number'; order.step = '1'; order.value = '0';
    order.min = '-2147483648'; order.max = '2147483647'; order.required = true;
    const configuration = field('configuration', document.createElement('textarea')); configuration.value = '{}'; configuration.maxLength = 16384;
    const fields = field('fields', document.createElement('textarea')); fields.value = '[]'; fields.maxLength = 16384;
    const message = document.createElement('p'); message.setAttribute('role', 'status'); form.append(message);
    const add = document.createElement('button'); add.type = 'submit'; add.textContent = t('hook.add'); add.disabled = true; form.append(add);
    let resolution, generation = 0;
    const phases = () => {
        phase.replaceChildren();
        const definition = definitions.find(value => value.id === hook.value)?.definition;
        const candidates = resolution?.endpoints?.filter(value => !endpoint.value || value.id === endpoint.value) || [];
        for (const value of definition?.phases || []) if (candidates.length && candidates.every(item => item.phases.includes(value)))
            option(phase, value, t('hook.' + value));
        completion.disabled = phase.value !== 'AFTER_COMPLETION';
        if (completion.disabled) completion.value = 'ALL';
        add.disabled = !phase.value || bindings.length >= 16;
    };
    target.addEventListener('change', async () => {
        const current = ++generation; resolution = null; endpoint.replaceChildren(); phases();
        if (!target.value) return;
        try {
            const action = relations.actions.find(action => action.id === target.value);
            const value = await request('targets?' + new URLSearchParams(action ? {
                kind: action.endpoint ? 'ENDPOINT' : 'OPERATION', id: action.endpoint || action.operation
            } : {kind: 'FORM_ACTION', id: page.id, targetId: target.value}));
            if (current !== generation || signal.aborted) return;
            if (value.status !== 'AVAILABLE') throw new Error(value.status);
            resolution = value;
            if (!action?.endpoint && value.operations.length === 1) option(endpoint, '', t('hook.operation') + ': ' + value.operations.map(names.operation).join(', '));
            for (const item of value.endpoints) option(endpoint, item.id, item.id + ': ' + item.routes.map(route => route.method + ' ' + route.template).join(', '));
            message.textContent = ''; phases();
        } catch (error) { if (!signal.aborted && current === generation) message.textContent = t('hook.unavailable'); }
    }, {signal});
    hook.addEventListener('change', phases, {signal}); endpoint.addEventListener('change', phases, {signal});
    phase.addEventListener('change', () => { completion.disabled = phase.value !== 'AFTER_COMPLETION'; if (completion.disabled) completion.value = 'ALL'; }, {signal});
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (add.disabled || !resolution || !form.reportValidity()) return;
        try {
            const value = JSON.stringify(JSON.parse(configuration.value));
            const access = JSON.parse(fields.value);
            if (!Array.isArray(access) || access.length > 16) throw new Error('PAGE_HOOK_INVALID');
            bindings = [...bindings, {id: crypto.randomUUID(), hook: hook.value, operation: endpoint.value ? '' : resolution.operations[0],
                endpoint: endpoint.value, phase: phase.value, order: Number(order.value), completion: completion.value, configuration: value, fields: access}];
            update(bindings); render(); phases(); message.textContent = t('hook.draft');
        } catch { message.textContent = t('hook.invalid'); }
    }, {signal});
    root.append(form);
}

function mountActions(root, page, catalog, relations, t, signal) {
    const section = document.createElement('details'), title = document.createElement('summary');
    title.textContent = t('hook.relations'); section.append(title); root.prepend(section);
    for (const action of relations.actions) {
        const row = document.createElement('p'), remove = document.createElement('button');
        row.append(document.createTextNode(`${action.formId} / ${action.actionId || '—'} → ${action.operation || action.endpoint} `));
        remove.type = 'button'; remove.textContent = t('delete');
        remove.addEventListener('click', () => relations.update(relations.actions.filter(value => value.id !== action.id)), {signal});
        row.append(remove); section.append(row);
    }
    const form = document.createElement('form');
    const field = (key, node) => {
        const label = document.createElement('label'); label.className = 'editor-field';
        const text = document.createElement('span'); text.textContent = t('hook.' + key);
        label.append(text, node); form.append(label); return node;
    };
    const option = (node, value, text) => { const item = document.createElement('option'); item.value = value; item.textContent = text; node.append(item); };
    const detected = field('detected', document.createElement('select'));
    option(detected, '', t('select-first'));
    const candidates = [];
    for (const native of relations.canvas?.querySelectorAll('form[id]') || []) {
        const buttons = [...native.elements].filter(node => node.type === 'submit');
        for (const button of buttons.length ? buttons : [undefined]) {
            if (buttons.length > 1 && !button.id) continue;
            const method = (button?.getAttribute('formmethod') || native.method || 'GET').toUpperCase();
            const url = new URL(button?.getAttribute('formaction') || native.getAttribute('action') || page.path, location.origin);
            if (url.origin !== location.origin) continue;
            candidates.push({formId: native.id, actionId: button?.id || '', method, path: url.pathname});
            option(detected, String(candidates.length - 1), `${native.id} / ${button?.id || '—'} · ${method} ${url.pathname}`);
        }
    }
    const formId = field('formId', document.createElement('input'));
    const actionId = field('actionId', document.createElement('input'));
    for (const input of [formId, actionId]) { input.maxLength = 128; input.pattern = '[A-Za-z0-9._-]+'; }
    formId.required = true;
    const region = field('region', document.createElement('select'));
    for (const value of page.regions.filter(region => region.container)) option(region, value.id, value.id);
    const endpoint = field('endpoint', document.createElement('select'));
    const choices = selected => {
        endpoint.replaceChildren(); option(endpoint, '', t('select-first'));
        for (const value of catalog) {
            if (selected && !value.routes.some(route => route.method === selected.method && route.template === selected.path)) continue;
            option(endpoint, value.id, value.owner + ' · ' + value.routes.map(route => route.method + ' ' + route.template).join(', '));
        }
    };
    choices();
    detected.addEventListener('change', () => {
        const selected = detected.value === '' ? null : candidates[Number(detected.value)];
        if (selected) { formId.value = selected.formId; actionId.value = selected.actionId; }
        choices(selected);
    }, {signal});
    const add = document.createElement('button'); add.type = 'submit'; add.textContent = t('hook.addRelation');
    const message = document.createElement('p'); message.setAttribute('role', 'status'); form.append(add, message);
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (!form.reportValidity() || !endpoint.value) return;
        if (relations.actions.length >= 128 || relations.actions.some(action => action.formId === formId.value && action.actionId === actionId.value)) {
            message.textContent = t('hook.relationInvalid'); return;
        }
        relations.update([...relations.actions, {id: crypto.randomUUID(), formId: formId.value, actionId: actionId.value,
            region: region.value, endpoint: endpoint.value, operation: ''}]);
    }, {signal});
    section.append(form);
}
