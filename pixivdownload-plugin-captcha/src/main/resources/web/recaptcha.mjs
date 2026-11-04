let loading;
function load() {
    if (typeof window.grecaptcha?.render === 'function') return Promise.resolve(window.grecaptcha);
    if (!loading) loading = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = 'https://www.google.com/recaptcha/api.js?render=explicit';
        script.async = true;
        const fail = () => { clearTimeout(timer); script.remove(); reject(new Error('CAPTCHA_SCRIPT_UNAVAILABLE')); };
        const timer = setTimeout(fail, 10000);
        script.onerror = fail;
        script.onload = () => {
            const ready = () => {
                if (typeof window.grecaptcha?.render !== 'function') { fail(); return; }
                clearTimeout(timer); resolve(window.grecaptcha);
            };
            if (window.grecaptcha?.ready) window.grecaptcha.ready(ready); else ready();
        };
        document.head.append(script);
    }).catch(error => { loading = undefined; throw error; });
    return loading;
}

export async function mount(root, context) {
    const {createWidget} = await context.importModule('./widget.mjs');
    const {button, container, show} = createWidget(root, context);
    let widget, sitekey, sequence = 0, callbacks;
    const reset = () => {
        sequence++; callbacks = undefined;
        if (widget !== undefined) window.grecaptcha?.reset(widget);
        container.hidden = true;
    };
    context.onCleanup(reset);
    if (context.mode === 'display' && context.interaction) {
        context.on(button, 'click', async () => {
            show('working');
            try { await context.interaction.prepare(); }
            catch (error) { if (!context.signal.aborted && error.name !== 'AbortError') show('failed', 'failed'); }
        });
        context.onCleanup(context.interaction.onState(async state => {
            reset(); show(state ? 'working' : 'idle'); button.hidden = false;
            if (!state) return;
            const current = sequence;
            const active = () => current === sequence && !context.signal.aborted;
            const fail = message => {
                if (!active()) return;
                context.interaction.invalidate(state.id); show('failed', message); button.hidden = false;
            };
            try {
                const configuration = JSON.parse(state.value), api = await load();
                if (!active()) return;
                button.hidden = true;
                container.hidden = false;
                if (configuration.test) show('working', 'test-mode');
                callbacks = {
                    ready(token) {
                        if (!active()) return;
                        if (context.interaction.respond(state.id, token)) show('ready', configuration.test ? 'test-mode' : '');
                        else fail('failed');
                    },
                    error() { fail('failed'); }, expired() { fail('expired'); }
                };
                if (widget !== undefined && sitekey === configuration.sitekey) return;
                container.replaceChildren();
                sitekey = configuration.sitekey;
                widget = api.render(container, {
                    sitekey: configuration.sitekey,
                    theme: root.ownerDocument.documentElement.dataset.theme === 'dark' ? 'dark' : 'light',
                    size: root.clientWidth < 304 ? 'compact' : 'normal',
                    callback: token => callbacks?.ready(token),
                    'error-callback': () => callbacks?.error(),
                    'expired-callback': () => callbacks?.expired()
                });
            } catch { fail('failed'); }
        }));
    }
    return {destroy() { reset(); root.replaceChildren(); }};
}
