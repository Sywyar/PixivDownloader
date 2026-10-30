export async function mount(root, context) {
    const {createClient} = await context.importModule('./generated/example.pages.contract.Checklist.mjs');
    let properties = context.properties;
    const title = document.createElement('h3');
    const list = document.createElement('ul');
    const button = document.createElement('button');
    const result = document.createElement('output');
    button.type = 'button'; button.textContent = context.t('verify');
    button.disabled = context.mode !== 'display';
    root.append(title, list, button, result);
    context.interval(() => { root.dataset.tick = String(Number(root.dataset.tick || 0) + 1); }, 40);
    context.onCleanup(() => { root.dataset.disposed = 'true'; });
    const client = createClient(context.fetch);
    const render = () => {
        title.textContent = properties.title; list.replaceChildren();
        for (const item of properties.items) {
            const row = document.createElement('li'); row.textContent = (item.checked ? '✓ ' : '○ ') + item.label; list.append(row);
        }
    };
    context.on(button, 'click', async () => {
        button.disabled = true;
        try {
            const value = await client.verify(properties, {signal: context.signal});
            result.textContent = value.checked + ' / ' + value.total;
            context.emit('result', value);
        } catch (error) {
            if (!context.signal.aborted) result.textContent = context.t('failed');
        } finally { if (!context.signal.aborted) button.disabled = false; }
    });
    context.onInput('selection', value => { result.textContent = JSON.stringify(value); context.emit('result', value); });
    render();
    return {update(value) { properties = value; render(); }, destroy() { root.replaceChildren(); }};
}
