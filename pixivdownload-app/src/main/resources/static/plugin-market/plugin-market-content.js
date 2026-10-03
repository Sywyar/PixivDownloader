'use strict';
/* 两条市场视图共用附件展示；内容只在展开时读取，切换身份与离页会撤销在途请求。 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var C = PMK.content = {};

    C.model = function (repositoryId, entry, pkg) {
        var market = entry.market || {};
        var content = pkg && pkg.content || {};
        var links = market.links;
        if (links == null) links = market.homepageUrl ? [{ kind: 'repository', url: market.homepageUrl }] : [];
        return {
            repositoryId: repositoryId, pluginId: entry.pluginId, version: pkg && pkg.version,
            language: PMK.currentLang(),
            documents: ['readme', 'releaseNotes', 'changelog'].map(function (kind) {
                var locale = PMK.localeKey(content[kind], market.defaultLocale);
                var document = locale && content[kind][locale];
                return document && document.asset ? { kind: kind, locale: locale, asset: document.asset, resources: document.resources } : null;
            }).filter(Boolean),
            links: links.filter(function (link) {
                try { var url = new URL(link.url); return /^https?:$/.test(url.protocol) && !url.username && !url.password; }
                catch (error) { return false; }
            }).map(function (link) {
                return { url: link.url, label: link.kind === 'custom'
                    ? PMK.localeText(link.label, link.url, market.defaultLocale) : PMK.t('content.link.' + link.kind) };
            }),
            screenshots: (market.screenshots || []).map(function (image, index) {
                return { url: PMK.api.contentImageUrl(repositoryId, entry.pluginId, image, 'screenshot', index),
                    alt: PMK.localeText(image.alt, PMK.t('content.screenshot', '', { n: index + 1 }), market.defaultLocale) };
            }).filter(function (image) { return !!image.url; })
        };
    };

    function element(tag, className, text) {
        var result = document.createElement(tag);
        if (className) result.className = className;
        if (text != null) result.textContent = text;
        return result;
    }

    function frameSource(html, theme) {
        // srcdoc 的默认基址来自父页；显式指向自身才能保留文档内锚点导航。
        html = html.replace(/(<a\b[^>]*\bhref=")#/g, '$1about:srcdoc#');
        return '<!doctype html><html><head><meta charset="UTF-8">'
            + '<meta name="viewport" content="width=device-width,initial-scale=1">'
            + '<meta http-equiv="Content-Security-Policy" content="default-src &#39;none&#39;; img-src data:; '
            + 'style-src &#39;unsafe-inline&#39;; base-uri &#39;none&#39;; form-action &#39;none&#39;">'
            + '<style>:root{color-scheme:' + theme + '}body{font:1rem/1.65 system-ui,sans-serif;margin:1rem;'
            + 'overflow-wrap:anywhere}img{max-width:100%;height:auto}pre{white-space:pre-wrap}table{border-collapse:collapse;'
            + 'max-width:100%}th,td{border:1px solid GrayText;padding:.4rem}a{color:LinkText}</style></head><body>'
            + html + '</body></html>';
    }

    C.mount = function (host) {
        var identity = null;
        var current = null;
        var sections = [];
        var pictures = [];
        var disposed = false;
        var pageActive = true;
        function currentTheme() { return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light'; }
        var theme = currentTheme();
        var observer = global.MutationObserver ? new MutationObserver(function () {
            var next = currentTheme();
            if (next === theme) return;
            theme = next;
            sections.forEach(function (section) {
                var frame = section.body.querySelector('iframe');
                if (frame) frame.srcdoc = frame.srcdoc.replace(/:root\{color-scheme:(light|dark)}/, ':root{color-scheme:' + theme + '}');
            });
        }) : null;
        if (observer) observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
        function reset() {
            pictures.forEach(function (picture) { picture.suspend(); });
            pictures = [];
            sections.forEach(function (section) { if (section.controller) section.controller.abort(); });
            sections = [];
            host.replaceChildren();
        }
        function active(section) { return !disposed && pageActive && sections.indexOf(section) !== -1; }
        function load(section) {
            if (!active(section) || !section.root.open || section.loaded || section.controller) return;
            var controller = section.controller = new AbortController();
            section.body.replaceChildren(element('p', 'pmk-content-status', PMK.t('content.loading')));
            PMK.api.fetchContent(current, section.document, controller.signal).then(function (result) {
                if (!active(section) || section.controller !== controller || controller.signal.aborted) return;
                if (result.sha256 !== section.document.asset.sha256 || typeof result.html !== 'string') throw new Error('CONTENT_CHANGED');
                var frame = element('iframe', 'pmk-content-frame');
                frame.title = PMK.t('content.' + section.document.kind);
                frame.setAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox');
                frame.setAttribute('referrerpolicy', 'no-referrer');
                frame.srcdoc = frameSource(result.html, theme);
                section.body.replaceChildren(frame);
                if (result.missingResources) {
                    section.body.appendChild(element('p', 'pmk-content-status', PMK.t('content.missing-images')));
                    section.body.appendChild(retry(section));
                }
                section.loaded = true;
            }).catch(function (error) {
                if (!active(section) || section.controller !== controller || controller.signal.aborted) return;
                section.body.replaceChildren(element('p', 'pmk-content-status', PMK.t('content.failed')), retry(section));
            }).finally(function () { if (section.controller === controller) section.controller = null; });
        }
        function retry(section) {
            var button = element('button', 'pmk-btn pmk-btn--gray pmk-btn--sm', PMK.t('content.retry'));
            button.type = 'button';
            button.addEventListener('click', function () { section.loaded = false; load(section); });
            return button;
        }
        function update(model) {
            if (disposed) return;
            var next = JSON.stringify(model);
            if (next === identity) return;
            reset(); identity = next; current = model;
            if (model.links.length) {
                var links = element('nav', 'pmk-content-links');
                links.setAttribute('aria-label', PMK.t('content.links'));
                model.links.forEach(function (link) {
                    var a = element('a', '', link.label); a.href = link.url; a.target = '_blank'; a.rel = 'noopener noreferrer';
                    links.appendChild(a);
                });
                host.appendChild(links);
            }
            if (model.screenshots.length) {
                var gallery = element('div', 'pmk-content-screenshots');
                model.screenshots.forEach(function (image) {
                    var figure = element('figure');
                    var img = element('img'); img.alt = image.alt; img.loading = 'lazy';
                    function valid() { return !disposed && pageActive && pictures.indexOf(picture) !== -1; }
                    function failed() {
                        if (!valid()) return;
                        var button = element('button', 'pmk-btn pmk-btn--gray pmk-btn--sm', PMK.t('content.retry'));
                        button.type = 'button';
                        button.addEventListener('click', function () { if (valid()) picture.resume(); });
                        figure.replaceChildren(element('p', 'pmk-content-status', PMK.t('content.failed')), button);
                    }
                    var picture = {
                        suspend: function () { img.removeEventListener('error', failed); img.removeAttribute('src'); },
                        resume: function () {
                            if (!valid()) return;
                            img.addEventListener('error', failed); figure.replaceChildren(img); img.src = image.url;
                        }
                    };
                    pictures.push(picture); picture.resume();
                    figure.appendChild(img); gallery.appendChild(figure);
                });
                host.appendChild(gallery);
            }
            model.documents.forEach(function (doc) {
                var details = element('details', 'pmk-content-document');
                details.setAttribute('data-content-kind', doc.kind);
                var summary = element('summary', '', PMK.t('content.' + doc.kind));
                var body = element('div', 'pmk-content-body');
                body.setAttribute('aria-live', 'polite');
                var section = { root: details, body: body, document: doc, loaded: false, controller: null };
                sections.push(section);
                details.addEventListener('toggle', function () {
                    if (details.open) load(section);
                    else {
                        if (section.controller) { section.controller.abort(); section.controller = null; }
                        section.body.replaceChildren(); section.loaded = false;
                    }
                });
                details.append(summary, body); host.appendChild(details);
            });
        }
        function hide() {
            pageActive = false;
            pictures.forEach(function (picture) { picture.suspend(); });
            sections.forEach(function (section) {
                if (section.controller) { section.controller.abort(); section.controller = null; }
                section.body.replaceChildren(); section.loaded = false;
            });
        }
        function show() { pageActive = true; pictures.forEach(function (picture) { picture.resume(); }); sections.forEach(load); }
        global.addEventListener('pagehide', hide);
        global.addEventListener('pageshow', show);
        return {
            update: update,
            dispose: function () {
                disposed = true; reset(); current = null; identity = null;
                if (observer) observer.disconnect();
                global.removeEventListener('pagehide', hide); global.removeEventListener('pageshow', show);
            }
        };
    };

    C.component = function (Vue) {
        return {
            props: ['model'],
            mounted: function () { this.contentOwner = C.mount(this.$el); this.contentOwner.update(this.model); },
            watch: { model: function (value) { this.contentOwner.update(value); } },
            beforeUnmount: function () { this.contentOwner.dispose(); },
            render: function () { return Vue.h('div', { class: 'pmk-content' }); }
        };
    };
})(window);
