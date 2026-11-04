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
export async function mount(root, context) {
    const {createWidget} = await context.importModule('./widget.mjs');
    const {button, container, show} = createWidget(root, context);
    let widget, sequence = 0;
    const reset = () => { sequence++; if (widget !== undefined) window.turnstile?.remove(widget); widget = undefined; container.replaceChildren(); };
    context.onCleanup(reset);
    if (context.mode === 'display' && context.interaction) {
        context.on(button, 'click', async () => {
            show('working');
            try { await context.interaction.prepare(); }
            catch (error) {
                if (!context.signal.aborted && error.name !== 'AbortError') {
                    show('failed', 'failed');
                }
            }
        });
        context.onCleanup(context.interaction.onState(async state => {
            reset(); show(state ? 'working' : 'idle'); button.hidden = false;
            if (!state) return;
            const current = sequence, configuration = JSON.parse(state.value);
            try {
                const api = await load();
                if (context.signal.aborted || current !== sequence) return;
                button.hidden = true;
                if (configuration.test) show('working', 'test-mode');
                widget = api.render(container, {sitekey: configuration.sitekey, action: configuration.action, cData: configuration.cdata,
                    size: root.clientWidth < 300 ? 'compact' : 'flexible',
                    theme: root.ownerDocument.documentElement.dataset.theme || 'auto',
                    'response-field': false, retry: 'never', 'refresh-expired': 'never', 'refresh-timeout': 'never',
                    callback(token) {
                        if (current === sequence && !context.signal.aborted) {
                            if (context.interaction.respond(state.id, token)) show('ready', configuration.test ? 'test-mode' : '');
                            else {
                                context.interaction.invalidate(state.id);
                                show('failed', 'failed'); button.hidden = false;
                            }
                        }
                    },
                    'error-callback'() {
                        if (current === sequence) {
                            context.interaction.invalidate(state.id);
                            show('failed', 'failed'); button.hidden = false;
                        }
                    },
                    'expired-callback'() {
                        if (current === sequence) {
                            context.interaction.invalidate(state.id);
                            show('failed', 'expired'); button.hidden = false;
                        }
                    }
                });
            } catch {
                if (!context.signal.aborted && current === sequence) {
                    context.interaction.invalidate(state.id);
                    show('failed', 'failed'); button.hidden = false;
                }
            }
        }));
    }
    return {destroy() { reset(); root.replaceChildren(); }};
}
