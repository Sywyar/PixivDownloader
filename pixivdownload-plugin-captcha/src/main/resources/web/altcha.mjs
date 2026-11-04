export async function mount(root, context) {
    const {createWidget} = await context.importModule('./widget.mjs');
    const {button, show} = createWidget(root, context);
    let current, worker, timer;
    const stop = () => { worker?.terminate(); worker = undefined; clearTimeout(timer); };
    context.onCleanup(stop);
    if (context.mode === 'display' && context.interaction) {
        context.onCleanup(context.interaction.onState(state => {
            stop(); current = state; show('idle');
        }));
        context.on(button, 'click', async () => {
            if (worker || button.disabled) return;
            show('working');
            try { await context.interaction.prepare(); }
            catch (error) {
                if (!context.signal.aborted && error.name !== 'AbortError') {
                    show('failed', 'failed');
                }
                return;
            }
            if (!current || context.signal.aborted) return;
            const challenge = JSON.parse(current.value), id = current.id;
            show('working');
            const workerUrl = new URL(challenge.parameters.algorithm === 'PBKDF2/SHA-256' ? './vendor/pbkdf2.js' : './vendor/sha.js', import.meta.url);
            workerUrl.search = new URL(import.meta.url).search;
            const fail = () => {
                stop(); context.interaction.invalidate(id);
                show('failed', 'failed');
            };
            try { worker = new Worker(workerUrl); }
            catch { fail(); return; }
            worker.onerror = fail;
            worker.onmessage = event => {
                const solution = event.data;
                stop();
                if (current?.id !== id || context.signal.aborted) return;
                if (!solution || solution.error) {
                    fail(); return;
                }
                const bytes = new TextEncoder().encode(JSON.stringify({challenge, solution}));
                const proof = btoa(String.fromCharCode(...bytes));
                if (context.interaction.respond(id, proof)) show('ready');
                else fail();
            };
            timer = setTimeout(fail, 15000);
            worker.postMessage({type: 'work', challenge, counterStart: 0, counterStep: 1, timeout: 14000});
        });
    }
    return {destroy() { stop(); current = undefined; root.replaceChildren(); }};
}
