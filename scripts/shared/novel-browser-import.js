/* Exchange a browser-authenticated Pixiv novel response only with an exact loopback backend. */
const NovelBrowserImport = (() => {
    function isLoopback(serverBase) {
        try {
            const url = new URL(serverBase);
            return (url.protocol === 'http:' || url.protocol === 'https:')
                && !url.username && !url.password
                && (url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]');
        } catch (_) {
            return false;
        }
    }

    function request(options) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest(Object.assign({}, options, {
                anonymous: true,
                timeout: 5000,
                onload: resolve,
                onerror: reject,
                onabort: reject,
                ontimeout: reject
            }));
        });
    }

    function parse(response) {
        try {
            return JSON.parse(response && response.responseText || '{}');
        } catch (_) {
            return {};
        }
    }

    async function importResponse(serverBase, novelId, responseText) {
        if (!isLoopback(serverBase)) return null;
        const id = Number(novelId);
        if (!Number.isSafeInteger(id) || id <= 0 || typeof responseText !== 'string') return null;

        const tokenResponse = await request({
            method: 'GET',
            url: `${serverBase}/api/novel/browser-import/token`
        });
        if (tokenResponse.status === 403 || tokenResponse.status === 404) return null;
        const tokenBody = parse(tokenResponse);
        if (tokenResponse.status !== 200 || typeof tokenBody.token !== 'string' || !tokenBody.token) {
            throw new Error(tokenBody.error || `HTTP ${tokenResponse.status}`);
        }

        const importResponse = await request({
            method: 'POST',
            url: `${serverBase}/api/novel/browser-import/${encodeURIComponent(id)}`,
            headers: {
                'Content-Type': 'application/json',
                'X-Novel-Import-Token': tokenBody.token
            },
            data: responseText
        });
        if (importResponse.status === 403 || importResponse.status === 404) return null;
        const importBody = parse(importResponse);
        if (importResponse.status !== 200
            || typeof importBody.fetchToken !== 'string' || !importBody.fetchToken) {
            throw new Error(importBody.error || `HTTP ${importResponse.status}`);
        }
        return importBody.fetchToken;
    }

    return Object.freeze({importResponse});
})();
