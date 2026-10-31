/* 此脚本先于用户代码安装握手；所有 DOM 和用户生命周期都留在不透明源内。 */
(() => {
    'use strict';
    const hostOrigin = new URL(document.currentScript.src).origin;
    let port, mounted, definition, context;
    const report = error => port?.postMessage({type: 'error',
        code: ['PAGE_COMPONENT_HTML_INVALID', 'PAGE_COMPONENT_PROPERTIES_INVALID'].includes(error?.message) ? error.message : 'PAGE_COMPONENT_SCRIPT_FAILED'});
    addEventListener('error', report);
    addEventListener('unhandledrejection', report);
    function values(encoded) {
        if (!encoded || typeof encoded !== 'object' || Array.isArray(encoded)) throw new Error('PAGE_COMPONENT_PROPERTIES_INVALID');
        const result = Object.create(null);
        for (const [name, schema] of Object.entries(definition.properties)) {
            const value = Object.hasOwn(encoded, name) ? encoded[name] : undefined;
            result[name] = value === undefined ? schema.default : schema.type === 'string' ? value : JSON.parse(value);
            if (typeof result[name] !== schema.type || schema.type === 'number' && !Number.isFinite(result[name]))
                throw new Error('PAGE_COMPONENT_PROPERTIES_INVALID');
        }
        return Object.freeze(result);
    }
    addEventListener('message', async event => {
        // 编辑画布内的可信适配器由顶层模块执行；正式页由直接父窗口执行。
        if (port || event.source !== parent && event.source !== top || event.origin !== hostOrigin
                || event.data?.type !== 'component-connect' || !event.ports[0]) return;
        port = event.ports[0];
        try {
            definition = window.componentDefinition;
            if (!definition || typeof definition.mount !== 'function') throw new Error('PAGE_COMPONENT_SCRIPT_FAILED');
            const root = document.getElementById('root');
            const appearance = value => {
                document.documentElement.style.colorScheme = value.colorScheme;
                document.documentElement.style.color = value.color;
            };
            appearance(event.data.appearance);
            const template = document.createElement('template'); template.innerHTML = definition.html;
            // HTML 字段只承载结构，脚本与外部资源必须通过受限执行入口。
            for (const element of template.content.querySelectorAll('*')) {
                if (['SCRIPT', 'IFRAME', 'OBJECT', 'EMBED', 'BASE', 'META', 'LINK', 'STYLE', 'FORM', 'TEMPLATE'].includes(element.tagName.toUpperCase()))
                    throw new Error('PAGE_COMPONENT_HTML_INVALID');
                for (const attribute of element.attributes) {
                    if (/^on/i.test(attribute.name) || ['src', 'srcset', 'href', 'xlink:href', 'action', 'formaction', 'srcdoc'].includes(attribute.name.toLowerCase()))
                        throw new Error('PAGE_COMPONENT_HTML_INVALID');
                }
            }
            root.replaceChildren(template.content.cloneNode(true));
            context = Object.freeze({properties: values(event.data.properties), mode: event.data.mode});
            mounted = await definition.mount(root, context);
            let lastHeight;
            const measure = () => {
                // 文档 scrollHeight 不小于 iframe 视口，不能用于内容收缩。
                const height = Math.ceil(Math.max(root.getBoundingClientRect().height, root.scrollHeight));
                if (height !== lastHeight) { lastHeight = height; port.postMessage({type: 'height', value: height}); }
            };
            port.onmessage = async ({data}) => {
                if (data?.type === 'appearance') { appearance(data.appearance); return; }
                if (data?.type !== 'update') return;
                try {
                    context = Object.freeze({properties: values(data.properties), mode: context.mode});
                    if (mounted?.update) await mounted.update(context);
                    else { await mounted?.destroy?.(); root.replaceChildren(template.content.cloneNode(true)); mounted = await definition.mount(root, context); }
                    measure();
                } catch (error) { report(error); }
            };
            const resize = new ResizeObserver(measure);
            resize.observe(root);
            addEventListener('pagehide', () => { resize.disconnect(); mounted?.destroy?.(); port.close(); }, {once: true});
            measure(); port.postMessage({type: 'mounted'});
        } catch (error) { report(error); }
    });
    addEventListener('DOMContentLoaded', () => parent.postMessage('component-ready', hostOrigin), {once: true});
})();
