export function mount(root, context) {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = context.t('respond');
    button.disabled = true; root.append(button);
    let current;
    if (context.mode === 'display') {
        const unsubscribe = context.interaction.onState(state => { current = state; button.disabled = false; });
        context.onCleanup(unsubscribe);
        context.on(button, 'click', async () => {
            button.disabled = true;
            try { await context.interaction.prepare(); } catch { button.disabled = false; return; }
            if (current && context.interaction.respond(current.id, 'fixture:' + current.value)) button.disabled = true;
        });
    }
    context.onCleanup(() => { root.dataset.disposed = 'true'; });
    return {destroy() { current = undefined; root.replaceChildren(); }};
}
