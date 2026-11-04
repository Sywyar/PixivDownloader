(() => {
const proofModule = new URL(document.currentScript.src).pathname;
PixivUiSlots.registerModule(proofModule, (root, context) => {
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = 'Confirm neutral proof'; button.id = 'neutral-proof';
    root.append(button);
    let current;
    context.onCleanup(context.interaction.onState(state => {
        current = state;
        window.neutralProofState = state;
        button.hidden = !state;
    }));
    context.on(button, 'click', () => { if (current) context.interaction.respond(current.id, 'neutral-proof'); });
    return {destroy() { root.replaceChildren(); }};
});
})();
