/** 只复用登录页的静态控件外观；不加载登录脚本或提交凭据。 */
export async function mount(root, context) {
    const url = new URL('/login.html', location.origin);
    url.searchParams.set('pagePublication', new URL(import.meta.url).searchParams.get('pagePublication'));
    const response = await fetch(url, {signal: context.signal, cache: 'force-cache', redirect: 'error'});
    if (!response.ok) throw new Error('PAGE_PREVIEW_UNAVAILABLE');
    const template = new DOMParser().parseFromString(await response.text(), 'text/html');
    if (context.signal.aborted) throw new DOMException('Disposed', 'AbortError');
    const source = [...template.querySelectorAll('[data-page-region]')]
        .find(node => node.dataset.pageRegion === context.properties.region);
    if (!source) throw new Error('PAGE_REGION_UNAVAILABLE');
    const element = root.ownerDocument.importNode(source, true);
    element.inert = true;
    for (const node of [element, ...element.querySelectorAll('*')]) {
        node.removeAttribute('id'); node.removeAttribute('for'); node.removeAttribute('data-pixiv-click');
        node.removeAttribute('data-page-region');
        if (node.matches('a')) node.removeAttribute('href');
        if (node.dataset.i18n) node.textContent = context.t(node.dataset.i18n);
        if (node.dataset.i18nPlaceholder) node.placeholder = context.t(node.dataset.i18nPlaceholder);
    }
    if (context.properties.region === 'preferences') {
        element.replaceChildren();
        const client = await window.PixivI18n.create({namespaces: ['login'], signal: context.signal});
        if (context.signal.aborted) { client.dispose(); throw new DOMException('Disposed', 'AbortError'); }
        const language = await window.PixivLangSwitcher.mount({mountPoint: element, i18n: client, variant: 'login'});
        context.onCleanup(() => language.destroy());
        const theme = window.PixivTheme.mount({mountPoint: element, variant: 'login'});
        context.onCleanup(() => theme.destroy());
    }
    root.replaceChildren(element);
    return {destroy() { root.replaceChildren(); }};
}
