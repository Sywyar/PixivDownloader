'use strict';

const PixivUserscriptDetection = (() => {
    const bundledIds = new Set(['experience-toolbox', 'artwork-java', 'user-batch', 'page-batch', 'import-batch']);
    let current = new Map();
    let cancel = null;

    function probe(ids) {
        if (cancel) cancel();
        current = new Map();
        const allowed = new Set(ids);
        const requestId = Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16)).join('-');
        return new Promise(resolve => {
            const found = new Map();
            const timers = [];
            const receive = event => {
                const data = event.data;
                if (event.source !== window || event.origin !== location.origin || !data
                    || data.type !== 'pixivdownloader:userscript-presence' || data.protocol !== 1
                    || data.requestId !== requestId || !allowed.has(data.id)
                    || typeof data.version !== 'string' || !data.version.trim() || data.version.length > 128) return;
                const versions = found.get(data.id) || new Set();
                if (versions.size < 8) versions.add(data.version);
                found.set(data.id, versions);
            };
            const finish = () => {
                timers.forEach(clearTimeout);
                window.removeEventListener('message', receive);
                window.removeEventListener('pagehide', abort);
                cancel = null;
                current = found;
                resolve(found);
            };
            const abort = () => { found.clear(); finish(); };
            cancel = abort;
            window.addEventListener('message', receive);
            window.addEventListener('pagehide', abort, {once: true});
            const send = () => window.postMessage({
                type: 'pixivdownloader:userscript-probe', protocol: 1, requestId
            }, location.origin);
            send();
            timers.push(setTimeout(send, 250), setTimeout(send, 1000), setTimeout(finish, 1800));
        });
    }

    function has(id) {
        return current.has(id) || (bundledIds.has(id) && current.has('all-in-one'));
    }

    function describe(id, found, translate) {
        const lines = [];
        if (found.has(id)) {
            lines.push(translate('batch:userscripts.detected', undefined, {version: [...found.get(id)].join(', ')}));
        }
        if (bundledIds.has(id) && found.has('all-in-one')) {
            lines.push(translate('batch:userscripts.covered', undefined, {version: [...found.get('all-in-one')].join(', ')}));
        }
        return lines.length ? lines.join(' · ') : translate('batch:userscripts.not-detected');
    }

    return {probe, has, describe};
})();
