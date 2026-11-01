export function createWidget(root, context) {
    const document = root.ownerDocument;
    const stylesheet = document.createElement('link');
    stylesheet.rel = 'stylesheet';
    const url = new URL('./captcha.css', import.meta.url);
    url.search = new URL(import.meta.url).search;
    stylesheet.href = url.href;
    const control = document.createElement('div');
    control.className = 'captcha-control';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'captcha-button';
    const icon = document.createElement('span');
    icon.className = 'captcha-icon';
    icon.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    button.append(icon, label);
    const container = document.createElement('div');
    container.className = 'captcha-provider';
    const status = document.createElement('p');
    status.className = 'captcha-status';
    status.setAttribute('role', 'status');
    control.append(button, container, status);
    root.append(stylesheet, control);
    function show(state, message = '', action = '') {
        control.dataset.state = state;
        button.disabled = state === 'working' || state === 'ready' || context.mode !== 'display' || !context.interaction;
        button.setAttribute('aria-busy', String(state === 'working'));
        label.textContent = context.t(action || (state === 'working' || state === 'ready' ? state : 'verify'));
        status.textContent = message ? context.t(message) : state === 'working' || state === 'ready' ? label.textContent : '';
        status.classList.toggle('captcha-status--quiet', !message);
    }
    show('idle');
    return {button, container, show};
}
