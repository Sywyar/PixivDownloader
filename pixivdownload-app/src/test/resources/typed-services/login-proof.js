(() => {
const proofModule = new URL(document.currentScript.src).pathname;
PixivUiSlots.registerModule(proofModule, (root, context) => {
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = 'Confirm neutral proof'; button.id = 'neutral-proof';
    root.append(button);
    function update(properties) {
        window.neutralProofProperties = properties;
        button.hidden = !properties.challenges['neutral-policy'];
    }
    context.on(button, 'click', () => context.emit('proof', {
        verificationToken: context.properties.verificationToken, policyId: 'neutral-policy', proof: 'neutral-proof'
    }));
    update(context.properties);
    return {update, destroy() { root.replaceChildren(); }};
});
})();
