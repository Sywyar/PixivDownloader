function registerUserscriptPresence(config) {
    if (location.origin !== config.origin
        || ![config.page + '.html', config.page + '-alt.html'].includes(location.pathname)) return false;
    // 后端页面只提供检测响应，不运行 Pixiv 下载器、菜单、队列或 Cookie 流程。
    if (window.top !== window) return true;
    const info = typeof GM_info !== 'undefined' ? GM_info
        : typeof GM !== 'undefined' ? GM.info : null;
    const version = info && info.script && info.script.version;
    if (typeof version !== 'string' || !version || version.length > 128) return true;
    const respond = event => {
        const data = event.data;
        // 油猴沙箱的 window 代理与消息携带的真实页面窗口不是同一个对象。
        if (event.source !== document.defaultView || event.origin !== config.origin || !data
            || data.type !== 'pixivdownloader:userscript-probe' || data.protocol !== 1
            || typeof data.requestId !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(data.requestId)) return;
        window.postMessage({
            type: 'pixivdownloader:userscript-presence', protocol: 1,
            requestId: data.requestId, id: config.id, version
        }, config.origin);
    };
    window.addEventListener('message', respond);
    return true;
}
