export function mountProperties(root, context) {
    let value = context.properties;
    let listeners = new AbortController();
    context.onCleanup(() => listeners.abort());
    const commit = () => context.change(value);
    const render = () => {
        listeners.abort(); listeners = new AbortController();
        root.replaceChildren();
        const titleLabel = document.createElement('label'); titleLabel.textContent = context.t('title');
        const title = document.createElement('input'); title.value = value.title; title.maxLength = 80;
        title.addEventListener('change', () => { value.title = title.value; commit(); }, {signal: listeners.signal});
        titleLabel.append(title); root.append(titleLabel);
        value.items.forEach((item, index) => {
            const row = document.createElement('div');
            const textLabel = document.createElement('label'); textLabel.textContent = context.t('item');
            const text = document.createElement('input'); text.value = item.label; text.maxLength = 80;
            text.addEventListener('change', () => { item.label = text.value; commit(); }, {signal: listeners.signal}); textLabel.append(text);
            const checkLabel = document.createElement('label'); checkLabel.textContent = context.t('checked');
            const checked = document.createElement('input'); checked.type = 'checkbox'; checked.checked = item.checked;
            checked.addEventListener('change', () => { item.checked = checked.checked; commit(); }, {signal: listeners.signal}); checkLabel.append(checked);
            const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = context.t('remove');
            remove.addEventListener('click', () => { value.items.splice(index, 1); commit(); render(); }, {signal: listeners.signal});
            row.append(textLabel, checkLabel, remove); root.append(row);
        });
        const add = document.createElement('button'); add.type = 'button'; add.textContent = context.t('add'); add.disabled = value.items.length >= 20;
        add.addEventListener('click', () => { value.items.push({label: context.t('item') + ' ' + (value.items.length + 1), checked: false}); commit(); render(); }, {signal: listeners.signal});
        root.append(add);
    };
    render();
    return {update(properties) { value = properties; render(); }, destroy() { root.replaceChildren(); }};
}
