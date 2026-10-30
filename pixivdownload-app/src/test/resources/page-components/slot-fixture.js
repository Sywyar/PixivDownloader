PixivUiSlots.registerModule('/page-checklist/render/slot-fixture.js', (root, scope) => {
    const facts = window.slotFixture;
    facts.mounts++; facts.scopes.push(scope);
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = scope.properties.label;
    root.append(button);
    const input = document.createElement('input');
    input.setAttribute('aria-label', 'Fixture input'); input.value = 'Selection stays here';
    root.append(input);
    scope.on(button, 'click', async () => {
        scope.emit('pressed', {label: scope.properties.label});
        try {
            await scope.fetch('/api/web/ui-slots?fixture-delay=1');
            facts.late++; button.textContent = 'Late response';
        } catch (error) { if (error.name === 'AbortError') facts.aborted++; else throw error; }
    });
    scope.on(window, 'fixture-signal', () => { facts.events++; });
    scope.interval(() => { facts.ticks++; }, 100);
    scope.onCleanup(() => { facts.cleanups++; });
    return {update(properties) { button.textContent = properties.label; }, destroy() { facts.destroys++; }};
});
