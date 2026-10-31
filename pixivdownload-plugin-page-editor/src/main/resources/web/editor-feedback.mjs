export function createEditorFeedback(signal, translate) {
    let current;
    return (kind, message, description = '') => {
        if (!message) { current?.close(); return; }
        const options = {
            kind: kind === 'busy' ? 'progress' : kind === 'idle' ? 'info' : kind,
            message, description, dismissLabel: translate('close'),
            durationMs: kind === 'busy' ? 0 : kind === 'error' ? 8000 : 5000,
            countdown: kind !== 'busy',
            signal
        };
        if (!current?.update(options)) current = window.PixivFeedback.toast(options);
    };
}
