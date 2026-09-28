'use strict';
    /**
     * 流式抓取 JSON：边读边通过 onProgress(已接收, 总字节) 上报进度，
     * 用于小说正文（meta）下载的流式进度条。返回一个与 Response 兼容的轻量对象
     * （含 ok / status / json()）。非 2xx 或浏览器不支持流时回退为原始 Response。
     */
    async function fetchJsonWithProgress(url, opts, onProgress) {
        const res = await fetch(url, opts);
        if (!res.ok || !res.body || typeof res.body.getReader !== 'function') {
            return res;
        }
        const totalHeader = res.headers.get('Content-Length');
        const total = totalHeader ? Number(totalHeader) : 0;
        const reader = res.body.getReader();
        const chunks = [];
        let received = 0;
        for (; ;) {
            const {done, value} = await reader.read();
            if (done) break;
            chunks.push(value);
            received += value.length;
            if (onProgress) onProgress(received, total);
        }
        let len = 0;
        chunks.forEach(c => len += c.length);
        const buf = new Uint8Array(len);
        let off = 0;
        chunks.forEach(c => {
            buf.set(c, off);
            off += c.length;
        });
        const text = new TextDecoder('utf-8').decode(buf);
        return {
            ok: res.ok,
            status: res.status,
            json: () => Promise.resolve(JSON.parse(text))
        };
    }
