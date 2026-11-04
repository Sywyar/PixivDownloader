export function mount(root, context) {
    context.onCleanup(() => { root.dataset.released = 'true'; });
    context.onCleanup(() => { throw new Error('private-provider-cleanup-detail'); });
    return {destroy() { throw new Error('private-provider-destroy-detail'); }};
}
