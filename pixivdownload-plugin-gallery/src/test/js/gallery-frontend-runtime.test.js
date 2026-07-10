'use strict';
/*
 * 旧画廊壳中性前端运行时的真实 Node/vm 行为测试。
 *
 * 运行：node src/test/js/gallery-frontend-runtime.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-gallery');
const RUNTIME_SOURCE = fs.readFileSync(path.join(STATIC_ROOT, 'gallery-frontend-runtime.js'), 'utf8');
const GENERIC_SOURCE = fs.readFileSync(path.join(STATIC_ROOT, 'gallery-generic-view.js'), 'utf8');

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

class DomNode {
    constructor(tag, ownerDocument, nodeType) {
        this.tagName = String(tag || '').toUpperCase();
        this.ownerDocument = ownerDocument;
        this.nodeType = nodeType || 1;
        this.children = [];
        this.parentNode = null;
        this.attributes = {};
        this.dataset = {};
        this.style = {};
        this.hidden = false;
        this._text = '';
        this._listeners = {};
        this.className = '';
        this.classList = {
            add: (...names) => {
                const values = new Set(this.className.split(/\s+/).filter(Boolean));
                names.forEach(name => values.add(name));
                this.className = Array.from(values).join(' ');
            },
            remove: (...names) => {
                const removed = new Set(names);
                this.className = this.className.split(/\s+/).filter(name => name && !removed.has(name)).join(' ');
            },
            toggle: (name, force) => {
                const has = this.className.split(/\s+/).includes(name);
                const enabled = force == null ? !has : !!force;
                if (enabled) this.classList.add(name); else this.classList.remove(name);
                return enabled;
            },
            contains: name => this.className.split(/\s+/).includes(name)
        };
    }
    appendChild(child) {
        if (!child) return child;
        if (child.nodeType === 11) {
            child.children.slice().forEach(item => this.appendChild(item));
            child.children = [];
            return child;
        }
        if (child.parentNode) child.parentNode.removeChild(child);
        child.parentNode = this;
        this.children.push(child);
        return child;
    }
    removeChild(child) {
        const index = this.children.indexOf(child);
        if (index >= 0) this.children.splice(index, 1);
        child.parentNode = null;
        return child;
    }
    replaceChildren(...children) {
        this.children.slice().forEach(child => this.removeChild(child));
        this._text = '';
        children.forEach(child => this.appendChild(child));
    }
    remove() { if (this.parentNode) this.parentNode.removeChild(this); }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }
    addEventListener(type, listener) { this._listeners[type] = listener; }
    click() { if (this._listeners.click) this._listeners.click({preventDefault() {}}); }
    set textContent(value) {
        this.ownerDocument.textWrites++;
        this._text = value == null ? '' : String(value);
        this.children.slice().forEach(child => this.removeChild(child));
    }
    get textContent() {
        return this._text + this.children.map(child => child.textContent).join('');
    }
    set innerHTML(value) {
        this.ownerDocument.innerHtmlWrites++;
        this._text = String(value);
        this.children.slice().forEach(child => this.removeChild(child));
    }
    get innerHTML() { return this._text; }
}

function makeDocument(moduleScripts, sandboxRef) {
    const document = {
        textWrites: 0,
        innerHtmlWrites: 0,
        createElement(tag) { return new DomNode(tag, document); },
        createDocumentFragment() { return new DomNode('#fragment', document, 11); }
    };
    document.head = document.createElement('head');
    document.body = document.createElement('body');
    const append = document.head.appendChild.bind(document.head);
    document.head.appendChild = function (script) {
        append(script);
        if (script.tagName !== 'SCRIPT') return script;
        queueMicrotask(() => {
            const moduleUrl = script.dataset.galleryFrontendModule;
            const loader = moduleScripts[moduleUrl];
            if (!loader) {
                if (typeof script.onerror === 'function') script.onerror(new Error('missing module'));
                return;
            }
            try {
                loader(sandboxRef.current);
                if (typeof script.onload === 'function') script.onload();
            } catch (failure) {
                if (typeof script.onerror === 'function') script.onerror(failure);
            }
        });
        return script;
    };
    return document;
}

function response(value, status) {
    const code = status || 200;
    return Promise.resolve({
        ok: code >= 200 && code < 300,
        status: code,
        headers: {get: () => 'application/json'},
        json: () => Promise.resolve(value)
    });
}

function scope(sourceIds, namespaces, kinds, mediaKinds) {
    return {
        sourceIds: sourceIds || [],
        sourceWorkNamespaces: namespaces || [],
        galleryKinds: kinds || [],
        mediaKinds: mediaKinds || []
    };
}

function frontend(id, moduleUrl, hooks, frontendScope, extra) {
    return Object.assign({
        contributionId: id,
        moduleUrl,
        scope: frontendScope,
        hooks,
        viewHref: null,
        displayNamespace: null,
        displayI18nKey: null,
        iconToken: null,
        order: 10
    }, extra || {});
}

function projectionDescriptor(sourceId, kind, order) {
    return {
        sourceId,
        kind,
        displayNamespace: 'alpha',
        displayI18nKey: 'source.label',
        order: order == null ? 10 : order,
        dataAccess: 'SHARED',
        filterCapabilities: {}
    };
}

function projection(sourceId, kind, title, mediaKinds) {
    return {
        key: {
            workKey: {sourceId, sourceWorkNamespace: 'work', sourceWorkId: '42'},
            kind
        },
        title,
        summary: 'summary',
        thumbnailUrl: '/thumb/42',
        author: {sourceId, actorId: 'a1', name: 'Author'},
        tags: [],
        containedMediaKinds: mediaKinds || ['IMAGE'],
        contentRating: 'SFW',
        aiStatus: 'NON_AI',
        attributes: {}
    };
}

function snapshot(generation, projections, frontends) {
    return {generation, projections: projections || [], works: [], frontends: frontends || [], diagnostics: []};
}

function makeEnvironment(options) {
    const opts = options || {};
    const calls = [];
    const state = {
        descriptor: opts.descriptor || snapshot(1, [], []),
        projectionPage: opts.projectionPage || {projections: [], nextCursor: null, hasMore: false, diagnostics: []},
        work: opts.work || null
    };
    const moduleScripts = Object.assign({}, opts.moduleScripts || {});
    const sandboxRef = {current: null};
    const document = makeDocument(moduleScripts, sandboxRef);
    const sandbox = {
        document,
        console: {warn() {}, error() {}, log() {}},
        location: {
            origin: 'http://localhost:8080',
            pathname: '/gallery.html',
            search: opts.search || '?view=all',
            href: 'http://localhost:8080/gallery.html' + (opts.search || '?view=all')
        },
        URL,
        URLSearchParams,
        Promise,
        setTimeout,
        clearTimeout,
        queueMicrotask,
        fetch(url) {
            const value = String(url);
            calls.push(value);
            if (value.startsWith('/api/gallery/unified/descriptors')) return response(state.descriptor);
            if (value.startsWith('/api/gallery/unified/projections')) return response(state.projectionPage);
            if (value.startsWith('/api/gallery/unified/works/')) {
                if (typeof opts.workRequest === 'function') return opts.workRequest(value);
                return state.work ? response({work: state.work, diagnostics: []}) : response({}, 404);
            }
            return response({}, 404);
        },
        PixivI18n: {
            create(config) {
                return Promise.resolve({
                    lang: config && config.lang || 'en-US',
                    namespaces: config && config.namespaces || [],
                    t: (key, fallback) => key ? 'T:' + key : fallback
                });
            }
        }
    };
    sandbox.window = sandbox;
    sandboxRef.current = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(RUNTIME_SOURCE, sandbox);
    vm.runInContext(GENERIC_SOURCE, sandbox);
    return {sandbox, api: sandbox.PixivGalleryFrontend, document, calls, state, moduleScripts};
}

function cardContext(env, item) {
    return {
        work: item,
        card: item,
        host: env.document.createElement('div'),
        openDetail() {}
    };
}

async function main() {
    // 1. 默认 URL 只装配 descriptors/VIEW_ENTRY，仍调用旧数据流，不触碰 neutral broker。
    {
        const descriptor = snapshot(1, [projectionDescriptor('alpha', 'IMAGE')], []);
        descriptor.diagnostics = [{
            providerId: 'third-party', sourceId: 'alpha', kind: 'IMAGE',
            code: 'provider-failed', message: 'descriptor-secret-token', payload: {private: true}
        }];
        const env = makeEnvironment({
            search: '?view=all',
            descriptor
        });
        const nav = env.document.createElement('nav');
        await env.api.bootstrap({navigationHost: nav, existingHrefs: []});
        let legacyCalls = 0;
        await env.api.startDataFlow({
            search: '?view=all',
            loadLegacy: () => { legacyCalls++; }
        });
        ok('默认 URL 调用既有 loadGallery 路径', legacyCalls === 1);
        ok('默认 URL 仅请求 descriptors，不请求 projections/works',
            env.calls.length === 1 && env.calls[0].includes('/descriptors'));
        ok('无专属 module 的 projection 仍生成 fallback 入口',
            env.api.viewEntries().some(entry => entry.href === '/gallery.html?galleryKind=IMAGE&sourceId=alpha'));
        ok('服务端诊断只保留受控字段，不暴露 message 或 DTO',
            !JSON.stringify(env.api.diagnostics()).includes('descriptor-secret-token')
            && !Object.prototype.hasOwnProperty.call(env.api.diagnostics()[0], 'payload'));
    }

    // 2. neutral URL 走 projection broker，不调用旧路径，详情未打开前不请求 works。
    {
        const item = projection('alpha', 'IMAGE', 'Neutral title');
        const env = makeEnvironment({
            search: '?galleryKind=IMAGE&sourceId=alpha',
            descriptor: snapshot(2, [projectionDescriptor('alpha', 'IMAGE')], []),
            projectionPage: {projections: [item], nextCursor: null, hasMore: false, diagnostics: []}
        });
        const hosts = {
            grid: env.document.createElement('section'),
            status: env.document.createElement('div'),
            pagination: env.document.createElement('nav'),
            detail: env.document.createElement('section'),
            filters: env.document.createElement('section')
        };
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        let legacyCalls = 0;
        await env.api.startDataFlow({
            search: '?galleryKind=IMAGE&sourceId=alpha',
            loadLegacy: () => { legacyCalls++; },
            generic: hosts
        });
        ok('neutral URL 不调用旧 loadGallery', legacyCalls === 0);
        ok('neutral URL 请求 projection broker', env.calls.some(url => url.includes('/projections?')));
        ok('未打开详情前不请求 works', !env.calls.some(url => url.includes('/works/')));
        ok('中性卡片安全渲染到旧网格', hosts.grid.children.length === 1);
    }

    // 3. filter extension 得到只读 filters 与受控 setFilter，并驱动 broker 查询参数。
    {
        const moduleUrl = '/extensions/filter.js';
        const contribution = frontend('alpha.filter', moduleUrl, ['FILTER_EXTENSION'],
            scope(['alpha'], [], ['IMAGE'], []));
        let filterContext = null;
        let initializerApiKeys = null;
        const env = makeEnvironment({
            search: '?galleryKind=IMAGE&sourceId=alpha',
            descriptor: snapshot(3, [projectionDescriptor('alpha', 'IMAGE')], [contribution]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule(moduleUrl, api => {
                        initializerApiKeys = Object.keys(api).sort().join(',');
                        api.registerFilterExtension({
                            id: 'alpha.filter',
                            render(context) { filterContext = context; return false; }
                        });
                    });
                }
            }
        });
        const hosts = {
            grid: env.document.createElement('section'),
            status: env.document.createElement('div'),
            pagination: env.document.createElement('nav'),
            detail: env.document.createElement('section'),
            filters: env.document.createElement('section')
        };
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        await env.api.startDataFlow({
            search: '?galleryKind=IMAGE&sourceId=alpha',
            loadLegacy() {},
            generic: hosts
        });
        ok('filter context 同时提供 filters 与 setFilter',
            filterContext && Object.isFrozen(filterContext.filters)
            && typeof filterContext.setFilter === 'function');
        ok('initializer API 仅暴露四个约定注册方法', initializerApiKeys === [
            'registerCardExtension', 'registerDetailAction',
            'registerFilterExtension', 'registerMediaRenderer'
        ].join(','));
        await filterContext.setFilter({sort: 'TITLE', direction: 'ASC', tag: ['tag-1']});
        const filteredCall = env.calls.filter(url => url.includes('/projections?')).pop();
        ok('setFilter 以受控参数重新查询 broker',
            filteredCall.includes('sort=TITLE') && filteredCall.includes('direction=ASC')
            && filteredCall.includes('tag=tag-1'));
    }

    // 4. projection 的 preferredMediaId 与 galleryKind 成为详情媒体提示，不复制详情模型。
    {
        const item = projection('alpha', 'IMAGE', 'Preferred media');
        item.preferredMediaId = 'media-2';
        const workKey = item.key.workKey;
        const work = {
            key: workKey,
            title: 'Preferred media',
            author: {sourceId: 'alpha', actorId: 'a1', name: 'Author'},
            media: [
                {key: {workKey, mediaId: 'media-1'}, kind: 'IMAGE', url: '/media/one.jpg'},
                {key: {workKey, mediaId: 'media-2'}, kind: 'IMAGE', url: '/media/two.jpg'}
            ],
            attributes: {}
        };
        const env = makeEnvironment({
            search: '?galleryKind=IMAGE&sourceId=alpha',
            descriptor: snapshot(4, [projectionDescriptor('alpha', 'IMAGE')], []),
            projectionPage: {projections: [item], nextCursor: null, hasMore: false, diagnostics: []},
            work
        });
        const hosts = {
            grid: env.document.createElement('section'),
            status: env.document.createElement('div'),
            pagination: env.document.createElement('nav'),
            detail: env.document.createElement('section'),
            filters: env.document.createElement('section')
        };
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        await env.api.startDataFlow({
            search: '?galleryKind=IMAGE&sourceId=alpha',
            loadLegacy() {},
            generic: hosts
        });
        const resolved = await env.api.openDetail(item);
        const mediaRoot = hosts.detail.children[hosts.detail.children.length - 1];
        const preferredHost = mediaRoot.children[0];
        ok('详情直接使用 broker 返回的同一 GalleryWork', resolved === work);
        ok('preferredMediaId 对应媒体优先并标记 focus',
            preferredHost.dataset.mediaId === 'media-2'
            && preferredHost.classList.contains('gallery-generic-media-preferred')
            && preferredHost.getAttribute('tabindex') === '-1');
        ok('详情保留 projection 当前 galleryKind 提示', mediaRoot.dataset.galleryKind === 'IMAGE');
    }

    // 5. 模块加载失败 fail-soft，入口/标准卡片仍可用。
    {
        const contribution = frontend('alpha.card', '/missing.js', ['CARD_EXTENSION'],
            scope(['alpha'], ['work'], ['IMAGE'], []));
        const env = makeEnvironment({
            descriptor: snapshot(3, [projectionDescriptor('alpha', 'IMAGE')], [contribution])
        });
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        ok('模块加载失败形成安全诊断',
            env.api.diagnostics().some(item => item.code === 'module-load-failed'));
        ok('模块加载失败诊断不泄漏第三方异常原文',
            !JSON.stringify(env.api.diagnostics()).includes('missing module'));
        ok('模块加载失败仍保留 projection fallback 入口', env.api.viewEntries().length === 1);
    }

    // 6. 异步执行的 module 必须自报当前正在加载的 URL，不能冒充另一个本地模块。
    {
        const moduleUrl = '/extensions/expected.js';
        const contribution = frontend('alpha.card', moduleUrl, ['CARD_EXTENSION'],
            scope(['alpha'], ['work'], ['IMAGE'], []));
        const env = makeEnvironment({
            descriptor: snapshot(4, [projectionDescriptor('alpha', 'IMAGE')], [contribution]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule('/extensions/spoofed.js', () => {});
                }
            }
        });
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        ok('异步 module 自报不同 URL 被拒绝',
            env.api.diagnostics().some(item => item.code === 'module-url-mismatch'));
        ok('冒充 module 不会成为当前模块定义',
            env.api.diagnostics().some(item => item.code === 'module-registration-missing'));
    }

    // 7. initializer 重复 id：首个 handler 生效，重复项被诊断且不会覆盖。
    {
        const moduleUrl = '/extensions/duplicate.js';
        const contribution = frontend('alpha.card', moduleUrl, ['CARD_EXTENSION'],
            scope(['alpha'], ['work'], ['IMAGE'], []));
        const env = makeEnvironment({
            descriptor: snapshot(4, [projectionDescriptor('alpha', 'IMAGE')], [contribution]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule(moduleUrl, api => {
                        api.registerCardExtension({id: 'alpha.card', render: context => {
                            const node = context.host.ownerDocument.createElement('span');
                            node.textContent = 'first';
                            return node;
                        }});
                        api.registerCardExtension({id: 'alpha.card', render: context => {
                            const node = context.host.ownerDocument.createElement('span');
                            node.textContent = 'second';
                            return node;
                        }});
                    });
                }
            }
        });
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        const context = cardContext(env, projection('alpha', 'IMAGE', 'x'));
        env.api.renderCardExtensions(context);
        ok('重复 id 仅保留首个 handler', context.host.textContent === 'first');
        ok('重复 id 形成诊断', env.api.diagnostics().some(item => item.code === 'duplicate-handler-id'));
    }

    // 8. initializer 部分注册后抛错时回滚该模块新增的全部 handler。
    {
        const moduleUrl = '/extensions/partial.js';
        const contribution = frontend('alpha.card', moduleUrl, ['CARD_EXTENSION'],
            scope(['alpha'], ['work'], ['IMAGE'], []));
        const env = makeEnvironment({
            descriptor: snapshot(5, [projectionDescriptor('alpha', 'IMAGE')], [contribution]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule(moduleUrl, api => {
                        api.registerCardExtension({id: 'alpha.card', render: context => {
                            const node = context.host.ownerDocument.createElement('span');
                            node.textContent = 'partial-handler';
                            return node;
                        }});
                        throw new Error('initializer-secret-token');
                    });
                }
            }
        });
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        const context = cardContext(env, projection('alpha', 'IMAGE', 'x'));
        env.api.renderCardExtensions(context);
        ok('initializer 抛错后不残留部分注册的 handler', context.host.children.length === 0);
        ok('initializer 抛错形成诊断',
            env.api.diagnostics().some(item => item.code === 'module-initializer-failed'));
        ok('initializer 诊断不泄漏第三方异常原文',
            !JSON.stringify(env.api.diagnostics()).includes('initializer-secret-token'));
    }

    // 9. renderer 抛错或返回 false 均回退标准 renderer；media 对象原样透传。
    {
        const moduleUrl = '/extensions/media.js';
        const contribution = frontend('alpha.media', moduleUrl, ['MEDIA_RENDERER'],
            scope(['alpha'], ['work'], ['VIDEO'], ['LIVE_PHOTO_VIDEO']));
        let seenMedia = null;
        const env = makeEnvironment({
            descriptor: snapshot(5, [projectionDescriptor('alpha', 'VIDEO')], [contribution]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule(moduleUrl, api => {
                        api.registerMediaRenderer({
                            id: 'alpha.media',
                            mediaKinds: ['LIVE_PHOTO_VIDEO'],
                            render: context => {
                                seenMedia = context.media;
                                throw new Error('renderer-secret-token');
                            }
                        });
                    });
                }
            }
        });
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        const media = {kind: 'LIVE_PHOTO_VIDEO', url: '/media/live.mp4', thumbnailUrl: '/thumb/live'};
        const host = env.document.createElement('div');
        const node = env.api.renderMedia({
            work: {key: {sourceId: 'alpha', sourceWorkNamespace: 'work', sourceWorkId: '42'}},
            media,
            host,
            galleryKind: 'VIDEO',
            openDetail() {}
        });
        ok('context.media 原对象透传', seenMedia === media);
        ok('renderer 抛错后使用 LIVE_PHOTO_VIDEO 标准 video fallback', node && node.tagName === 'VIDEO');
        ok('renderer throw 形成诊断', env.api.diagnostics().some(item => item.code === 'handler-render-failed'));
        ok('renderer 诊断不泄漏第三方异常原文',
            !JSON.stringify(env.api.diagnostics()).includes('renderer-secret-token'));
    }

    // 10. 稳定公开标准媒体 helper 覆盖 LIVE_PHOTO_VIDEO 与 UNKNOWN，返回 Node 且不自行 append。
    {
        const env = makeEnvironment({});
        const host = env.document.createElement('div');
        const image = env.api.renderStandardMedia({
            work: {}, media: {kind: 'IMAGE', url: '/image.png'}, host
        });
        const video = env.api.renderStandardMedia({
            work: {}, media: {kind: 'VIDEO', url: '/video.mp4'}, host
        });
        const live = env.api.renderStandardMedia({
            work: {}, media: {kind: 'LIVE_PHOTO_VIDEO', url: '/live.mp4'}, host
        });
        const text = env.api.renderStandardMedia({
            work: {}, media: {kind: 'TEXT', content: 'plain text'}, host
        });
        const cover = env.api.renderStandardMedia({
            work: {}, media: {kind: 'COVER', url: '/cover.png'}, host
        });
        const ugoira = env.api.renderStandardMedia({
            work: {}, media: {kind: 'UGOIRA', url: '/ugoira.webp'}, host
        });
        const unknown = env.api.renderStandardMedia({
            work: {}, media: {kind: 'UNKNOWN', content: '<script>unsafe</script>'}, host
        });
        ok('IMAGE、COVER 与 UGOIRA 使用标准图片 fallback',
            image && image.tagName === 'IMG'
            && cover && cover.tagName === 'IMG'
            && ugoira && ugoira.tagName === 'IMG');
        ok('VIDEO 使用标准 video fallback', video && video.tagName === 'VIDEO');
        ok('renderStandardMedia LIVE_PHOTO_VIDEO 返回 video', live && live.tagName === 'VIDEO');
        ok('TEXT 使用安全正文 fallback', text && text.tagName === 'ARTICLE'
            && text.textContent === 'plain text');
        ok('renderStandardMedia UNKNOWN 返回可见 fallback Node', unknown && unknown.nodeType === 1);
        ok('UNKNOWN fallback 显示稳定机器类型', unknown.textContent.includes('UNKNOWN'));
        ok('renderStandardMedia 不负责 append', host.children.length === 0);
        ok('UNKNOWN 文案经 textContent 安全写入', env.document.innerHtmlWrites === 0 && unknown.textContent.length > 0);
    }

    // 11. generation 刷新先注销旧 handler；同一作品不再被旧扩展装饰。
    {
        const moduleUrl = '/extensions/generation.js';
        const contribution = frontend('alpha.card', moduleUrl, ['CARD_EXTENSION'],
            scope(['alpha'], ['work'], ['IMAGE'], []));
        const env = makeEnvironment({
            descriptor: snapshot(7, [projectionDescriptor('alpha', 'IMAGE')], [contribution]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule(moduleUrl, api => {
                        api.registerCardExtension({id: 'alpha.card', render: context => {
                            const node = context.host.ownerDocument.createElement('span');
                            node.textContent = 'generation-7';
                            return node;
                        }});
                    });
                }
            }
        });
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        const before = cardContext(env, projection('alpha', 'IMAGE', 'x'));
        env.api.renderCardExtensions(before);
        env.state.descriptor = snapshot(8, [projectionDescriptor('alpha', 'IMAGE')], []);
        await env.api.refresh({force: true});
        const after = cardContext(env, projection('alpha', 'IMAGE', 'x'));
        env.api.renderCardExtensions(after);
        ok('旧 generation handler 原先生效', before.host.textContent === 'generation-7');
        ok('generation 刷新后旧 handler 已注销', after.host.children.length === 0);
        ok('运行时 generation 更新', env.api.generation() === 8);
    }

    // 12. generation 移除当前 projection 时清空旧内容，恢复后重新查询 broker。
    {
        const item = projection('alpha', 'IMAGE', 'generation-card');
        const env = makeEnvironment({
            descriptor: snapshot(9, [projectionDescriptor('alpha', 'IMAGE')], []),
            projectionPage: {projections: [item], nextCursor: null, hasMore: false, diagnostics: []}
        });
        const hosts = {
            grid: env.document.createElement('section'),
            status: env.document.createElement('div'),
            pagination: env.document.createElement('nav'),
            detail: env.document.createElement('section'),
            filters: env.document.createElement('section')
        };
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        await env.api.startDataFlow({
            search: '?galleryKind=IMAGE&sourceId=alpha', loadLegacy() {}, generic: hosts
        });
        ok('projection 活动时中性卡片可见', hosts.grid.children.length === 1);

        env.state.descriptor = snapshot(10, [], []);
        await env.api.refresh({force: true});
        await env.api.refreshGeneric();
        ok('projection 卸载后旧卡片立即清空', hosts.grid.children.length === 0);
        ok('projection 卸载后显示不可用状态',
            hosts.status.textContent.includes('frontend.status.unavailable'));

        env.state.descriptor = snapshot(11, [projectionDescriptor('alpha', 'IMAGE')], []);
        await env.api.refresh({force: true});
        await env.api.refreshGeneric();
        ok('projection 恢复后重新查询并显示卡片', hosts.grid.children.length === 1
            && env.calls.filter(url => url.startsWith('/api/gallery/unified/projections')).length === 2);
    }

    // 13. generation 更新后 pending 详情响应不能把已卸载来源写回页面。
    {
        let resolveWork;
        const pendingWork = new Promise(resolve => { resolveWork = resolve; });
        const item = projection('alpha', 'IMAGE', 'pending-detail');
        const work = {
            key: item.key.workKey,
            title: item.title,
            author: item.author,
            media: [{key: {workKey: item.key.workKey, mediaId: 'media-1'}, kind: 'IMAGE', url: '/image'}]
        };
        const env = makeEnvironment({
            descriptor: snapshot(12, [projectionDescriptor('alpha', 'IMAGE')], []),
            projectionPage: {projections: [item], nextCursor: null, hasMore: false, diagnostics: []},
            workRequest() { return pendingWork; }
        });
        const hosts = {
            grid: env.document.createElement('section'),
            status: env.document.createElement('div'),
            pagination: env.document.createElement('nav'),
            detail: env.document.createElement('section'),
            filters: env.document.createElement('section')
        };
        await env.api.bootstrap({navigationHost: env.document.createElement('nav'), existingHrefs: []});
        await env.api.startDataFlow({
            search: '?galleryKind=IMAGE&sourceId=alpha', loadLegacy() {}, generic: hosts
        });
        const detailPromise = env.api.openDetail(item);
        env.state.descriptor = snapshot(13, [], []);
        await env.api.refresh({force: true});
        await env.api.refreshGeneric();
        resolveWork(await response({work, diagnostics: []}));
        const staleResult = await detailPromise;

        ok('generation 更新后丢弃 pending 详情响应', staleResult == null);
        ok('pending 详情响应不会重绘已卸载来源',
            hosts.detail.hidden && hosts.detail.children.length === 0
            && hosts.status.textContent.includes('frontend.status.unavailable'));
    }

    // 14. VIEW_ENTRY 直接由 descriptor 生成且与 projection fallback 去重；context.t 支持 namespace:key。
    {
        const moduleUrl = '/extensions/i18n.js';
        const view = frontend('alpha.view', moduleUrl, ['VIEW_ENTRY'],
            scope(['alpha'], ['work'], ['IMAGE'], []), {
                viewHref: '/gallery.html?galleryKind=IMAGE&sourceId=alpha',
                displayNamespace: 'alpha',
                displayI18nKey: 'view.image',
                iconToken: 'image'
            });
        const card = frontend('alpha.card', moduleUrl, ['CARD_EXTENSION'],
            scope(['alpha'], ['work'], ['IMAGE'], []));
        const videoView = frontend('beta.video-view', moduleUrl, ['VIEW_ENTRY'],
            scope(['beta'], ['work'], ['VIDEO'], []), {
                viewHref: '/gallery.html?galleryKind=VIDEO&sourceId=beta',
                displayNamespace: 'alpha',
                displayI18nKey: 'view.video',
                iconToken: 'video'
            });
        const env = makeEnvironment({
            descriptor: snapshot(9, [
                projectionDescriptor('alpha', 'IMAGE'), projectionDescriptor('beta', 'VIDEO')
            ], [view, card, videoView]),
            moduleScripts: {
                [moduleUrl](window) {
                    window.PixivGalleryFrontend.registerModule(moduleUrl, api => {
                        api.registerCardExtension({id: 'alpha.card', render: context => {
                            const node = context.host.ownerDocument.createElement('span');
                            node.textContent = context.t('alpha:card.label');
                            return node;
                        }});
                    });
                }
            }
        });
        const navigation = env.document.createElement('nav');
        await env.api.bootstrap({navigationHost: navigation, existingHrefs: []});
        const context = cardContext(env, projection('alpha', 'IMAGE', 'x'));
        env.api.renderCardExtensions(context);
        ok('VIEW_ENTRY 与同 source/kind projection fallback 去重',
            env.api.viewEntries().filter(entry => entry.sourceId === 'alpha').length === 1);
        ok('VIEW_ENTRY 不要求 JS handler', env.api.viewEntries()[0].contributionId === 'alpha.view');
        ok('context.t 支持 namespace:key', context.host.textContent === 'T:alpha:card.label');
        const imageGlyph = navigation.children[0].children[0].children[0];
        const videoGlyph = navigation.children[1].children[0].children[0];
        ok('合法 iconToken 映射为不同的受控图标',
            imageGlyph.dataset.iconToken === 'image'
            && videoGlyph.dataset.iconToken === 'video'
            && imageGlyph.textContent !== videoGlyph.textContent);
    }

    // 15. 标准卡片只用 textContent，恶意标题不会成为 HTML，缩略图拒绝编码路径穿越。
    {
        const env = makeEnvironment({});
        const host = env.document.createElement('div');
        const item = projection('alpha', 'IMAGE', '<img src=x onerror=alert(1)>');
        item.thumbnailUrl = '/%2e%2e/private.png';
        const card = env.api.renderStandardCard({work: item, card: item, host, openDetail() {}});
        ok('标准卡片返回 Node', card && card.nodeType === 1);
        ok('恶意标题保持纯文本', card.textContent.includes('<img src=x onerror=alert(1)>'));
        ok('标准卡片零 innerHTML 写入', env.document.innerHtmlWrites === 0);
        const descendants = node => node.children.reduce(
            (items, child) => items.concat(child, descendants(child)), []);
        ok('标准卡片拒绝编码路径穿越缩略图',
            !descendants(card).some(node => node.tagName === 'IMG'));
    }

    console.log(`\ngallery-frontend-runtime.test.js: ${passed} assertions passed ✓`);
}

main().catch(failure => {
    console.error('TEST FAILED:', failure && failure.stack ? failure.stack : failure);
    process.exit(1);
});
