let loading;
function load() {
    if (window.turnstile) return Promise.resolve(window.turnstile);
    if (!loading) loading = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
        script.async = true;
        const timer = setTimeout(() => { script.remove(); reject(new Error('CAPTCHA_SCRIPT_TIMEOUT')); }, 10000);
        script.onload = () => { clearTimeout(timer); window.turnstile ? resolve(window.turnstile) : reject(new Error('CAPTCHA_SCRIPT_INVALID')); };
        script.onerror = () => { clearTimeout(timer); script.remove(); reject(new Error('CAPTCHA_SCRIPT_UNAVAILABLE')); };
        document.head.append(script);
    }).catch(error => { loading = undefined; throw error; });
    return loading;
}
export function mount(root, context) {
    const container = document.createElement('div'), status = document.createElement('p');
    status.setAttribute('role', 'status'); root.append(container, status);
    let widget, sequence = 0;
    const reset = () => { sequence++; if (widget !== undefined) window.turnstile?.remove(widget); widget = undefined; container.replaceChildren(); };
    context.onCleanup(reset);
    if (context.mode === 'display' && context.interaction) {
        context.onCleanup(context.interaction.onState(async state => {
            reset(); status.textContent = '';
            if (!state) return;
            const current = sequence, configuration = JSON.parse(state.value);
            if (configuration.test) status.textContent = context.t('test-mode');
            try {
                const api = await load();
                if (context.signal.aborted || current !== sequence) return;
                widget = api.render(container, {sitekey: configuration.sitekey, action: configuration.action, cData: configuration.cdata,
                    'response-field': false, retry: 'never', 'refresh-expired': 'never', 'refresh-timeout': 'never',
                    callback(token) {
                        if (current === sequence && !context.signal.aborted)
                            status.textContent = context.t(context.interaction.respond(state.id, token) ? 'ready' : 'failed');
                    },
                    'error-callback'() { if (current === sequence) status.textContent = context.t('failed'); },
                    'expired-callback'() { if (current === sequence) status.textContent = context.t('expired'); }
                });
            } catch { if (!context.signal.aborted && current === sequence) status.textContent = context.t('failed'); }
        }));
    }
    return {destroy() { reset(); root.replaceChildren(); }};
}
