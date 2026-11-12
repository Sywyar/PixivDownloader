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

    function compareVersions(left, right) {
        // 油猴允许自定义版本；只比较明确的数字版本和常见预发布编号，其他格式不推断升级。
        const parse = value => {
            const match = typeof value === 'string'
                && /^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-(alpha|beta|rc)(?:\.(\d+))?)?$/.exec(value);
            if (!match) return null;
            return [
                BigInt(match[1]), BigInt(match[2] || 0), BigInt(match[3] || 0),
                ['alpha', 'beta', 'rc', undefined].indexOf(match[4]), BigInt(match[5] || 0)
            ];
        };
        const a = parse(left), b = parse(right);
        if (!a || !b) return null;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
        }
        return 0;
    }

    function describe(id, availableVersion, found, translate) {
        const lines = [];
        const add = (state, version) => lines.push({
            state,
            text: translate('batch:userscripts.' + state, undefined, {version})
        });
        const versions = found?.get(id);
        let availableState = 'available-version';
        if (versions?.size) {
            const compared = [...versions].map(version => compareVersions(availableVersion, version));
            if (compared.every(order => order === 1)) availableState = 'update-available';
            else if (compared.every(order => order === -1)) availableState = 'older-version';
        }
        if (availableState === 'update-available') add(availableState, availableVersion);
        if (versions?.size) add('detected', [...versions].join(', '));
        if (bundledIds.has(id) && found?.has('all-in-one')) {
            add('covered', [...found.get('all-in-one')].join(', '));
        }
        if (!lines.length) add(found ? 'not-detected' : 'detecting');
        if (availableState !== 'update-available' && availableVersion
            && !(versions?.size === 1 && versions.has(availableVersion))) {
            add(availableState, availableVersion);
        }
        return lines;
    }

    function renderVersion(node, script, found, translate) {
        const lines = describe(script.id, script.version, found, translate);
        node.setAttribute('role', 'status');
        node.setAttribute('aria-atomic', 'true');
        lines.forEach((line, index) => {
            const part = node.children[index] || node.appendChild(document.createElement('span'));
            part.dataset.state = line.state;
            part.textContent = line.text;
        });
        while (node.children.length > lines.length) node.lastElementChild.remove();
    }

    return {probe, has, describe, renderVersion};
})();
