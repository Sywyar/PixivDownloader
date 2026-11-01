export function mount(root, context) {
    const button = document.createElement('button'), status = document.createElement('p');
    button.type = 'button'; button.textContent = context.t('verify'); button.disabled = true;
    status.setAttribute('role', 'status'); root.append(button, status);
    let current, worker, timer;
    const stop = () => { worker?.terminate(); worker = undefined; clearTimeout(timer); };
    context.onCleanup(stop);
    if (context.mode === 'display' && context.interaction) {
        context.onCleanup(context.interaction.onState(state => {
            stop(); current = state; button.disabled = !state; status.textContent = '';
        }));
        context.on(button, 'click', () => {
            if (!current || worker) return;
            const challenge = JSON.parse(current.value), id = current.id;
            button.disabled = true; status.textContent = context.t('working');
            const workerUrl = new URL(challenge.parameters.algorithm === 'PBKDF2/SHA-256' ? './vendor/pbkdf2.js' : './vendor/sha.js', import.meta.url);
            workerUrl.search = new URL(import.meta.url).search;
            worker = new Worker(workerUrl);
            const fail = () => { stop(); status.textContent = context.t('failed'); };
            worker.onerror = fail;
            worker.onmessage = event => {
                const solution = event.data;
                stop();
                if (!solution || solution.error || current?.id !== id || context.signal.aborted) {
                    status.textContent = context.t('failed'); return;
                }
                const bytes = new TextEncoder().encode(JSON.stringify({challenge, solution}));
                const proof = btoa(String.fromCharCode(...bytes));
                status.textContent = context.t(context.interaction.respond(id, proof) ? 'ready' : 'failed');
            };
            timer = setTimeout(fail, 15000);
            worker.postMessage({type: 'work', challenge, counterStart: 0, counterStep: 1, timeout: 14000});
        });
    }
    return {destroy() { stop(); current = undefined; root.replaceChildren(); }};
}
