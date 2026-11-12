function installedServer() {
    const info = typeof GM_info !== 'undefined' ? GM_info
        : typeof GM !== 'undefined' ? GM.info : null;
    for (const address of [info?.script?.downloadURL, info?.script?.updateURL, info?.scriptUpdateURL]) {
        try {
            const url = new URL(address);
            const route = url.pathname.match(/^(.*)\/api\/scripts\/[a-z0-9]+(?:-[a-z0-9]+)*(?:\.user\.js|\/install)$/);
            if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
                && !url.search && !url.hash && route) {
                return url.origin + route[1];
            }
        } catch (_) { /* 直接从源码安装时可能没有后端安装地址。 */ }
    }
    return 'http://localhost:6999';
}
