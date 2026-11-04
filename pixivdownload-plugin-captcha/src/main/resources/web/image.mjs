export async function mount(root, context) {
    const {createWidget} = await context.importModule('./widget.mjs');
    const {button, container, show} = createWidget(root, context);
    container.after(button);
    const document = root.ownerDocument;
    let current, configuration, input, canvas, images = [], events, timer, loaded = false;
    const reset = () => {
        clearTimeout(timer); events?.abort();
        for (const image of images) image.removeAttribute('src');
        images = []; if (canvas) canvas.width = canvas.height = 0;
        current = configuration = input = canvas = undefined; loaded = false;
        container.replaceChildren(); show('idle');
    };
    context.onCleanup(reset);
    if (context.mode === 'display' && context.interaction) {
        const prepare = async () => {
            show('working');
            try { await context.interaction.prepare(); }
            catch (error) { if (!context.signal.aborted && error.name !== 'AbortError') show('failed', 'failed'); }
        };
        context.on(button, 'click', () => {
            if (!current) { void prepare(); return; }
            if (button.disabled || !loaded) return;
            const proof = configuration.piece
                ? {id: configuration.id, position: Number(input.value)}
                : {id: configuration.id, answer: input.value.trim()};
            if (context.interaction.respond(current.id, JSON.stringify(proof))) {
                input.disabled = true;
                show('ready', '', 'answer-ready');
            } else {
                context.interaction.invalidate(current.id);
                show('failed', 'failed');
            }
        });
        context.onCleanup(context.interaction.onState(state => {
            reset();
            if (!state || context.signal.aborted) return;
            current = state;
            events = new AbortController();
            const on = (node, event, handler) => node.addEventListener(event, handler, {signal: events.signal});
            const fail = (message = 'failed') => {
                if (current !== state || context.signal.aborted) return;
                context.interaction.invalidate(state.id); show('failed', message);
            };
            try {
                configuration = JSON.parse(state.value);
                const config = configuration;
                if (!/^[a-f0-9-]{36}$/.test(config.id) || !Number.isFinite(config.expiresAt)) throw new Error('CAPTCHA_IMAGE_INVALID');
                const url = index => `/api/captcha/images/${config.id}/${index}`;
                if (config.image !== url(0) || (config.piece && config.piece !== url(1))) throw new Error('CAPTCHA_IMAGE_INVALID');
                const slider = !!config.piece;
                show('idle', '', slider ? 'confirm-position' : 'confirm-answer'); button.disabled = true;
                const field = document.createElement('label');
                field.className = 'captcha-answer';
                const label = document.createElement('span');
                label.textContent = context.t(slider ? 'slider-instruction' : 'text-instruction');
                input = document.createElement('input');
                input.className = slider ? 'captcha-range' : 'captcha-input';
                input.disabled = true;
                field.append(label, input);
                const refresh = document.createElement('button');
                refresh.type = 'button'; refresh.className = 'captcha-button captcha-refresh';
                refresh.textContent = context.t('refresh');
                on(refresh, 'click', () => { context.interaction.invalidate(state.id); void prepare(); });
                const picture = document.createElement('div');
                picture.className = slider ? 'captcha-picture captcha-picture--slider' : 'captcha-picture';
                const background = document.createElement('img');
                background.alt = context.t(slider ? 'slider-image' : 'text-image');
                images.push(background);
                if (slider) {
                    if (config.width !== 600 || config.height !== 360 || !Number.isInteger(config.pieceWidth)
                            || config.pieceWidth < 1 || config.pieceWidth >= config.width) throw new Error('CAPTCHA_IMAGE_INVALID');
                    canvas = document.createElement('canvas');
                    canvas.width = config.width; canvas.height = config.height;
                    canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', context.t('slider-image'));
                    picture.append(canvas);
                    input.type = 'range'; input.min = '0'; input.max = String(config.width - config.pieceWidth);
                    input.step = '1'; input.value = '0';
                    const piece = document.createElement('img');
                    images.push(piece);
                    const draw = () => {
                        const drawing = canvas.getContext('2d');
                        drawing.clearRect(0, 0, canvas.width, canvas.height);
                        drawing.drawImage(background, 0, 0); drawing.drawImage(piece, Number(input.value), 0);
                    };
                    const ready = () => {
                        if (current !== state || !images.every(image => image.complete && image.naturalWidth)) return;
                        loaded = true; input.disabled = false; draw();
                    };
                    on(background, 'load', ready); on(piece, 'load', ready);
                    on(input, 'input', () => { if (loaded) { draw(); button.disabled = false; } });
                    on(piece, 'error', () => fail()); piece.src = config.piece;
                } else {
                    if (config.length !== 5) throw new Error('CAPTCHA_IMAGE_INVALID');
                    picture.append(background);
                    input.type = 'text'; input.maxLength = config.length;
                    input.autocomplete = 'off'; input.spellcheck = false;
                    input.setAttribute('autocapitalize', 'characters');
                    on(background, 'load', () => { if (current === state) { loaded = true; input.disabled = false; } });
                    on(input, 'input', () => { button.disabled = !loaded || !/^[a-z0-9]{5}$/i.test(input.value.trim()); });
                }
                on(input, 'keydown', event => {
                    if (event.key === 'Enter') { event.preventDefault(); button.click(); }
                });
                on(background, 'error', () => fail()); background.src = config.image;
                container.append(picture, field, refresh);
                timer = setTimeout(() => fail('expired'), Math.max(0, Math.min(120000, config.expiresAt - Date.now())));
            } catch { fail(); }
        }));
    }
    return {destroy() { reset(); root.replaceChildren(); }};
}
