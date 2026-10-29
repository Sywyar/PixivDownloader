# Page editor and plugin components

The page editor requires SDK 1.4 or later. Install and start the optional `page-editor` plugin, sign in as an administrator, and open Page editor from navigation. Core currently contributes the login page. Other page owners declare their own integration.

## Edit, publish and recover

1. Select a page and drag components from Add component into the canvas. Focus a component button and press Enter or Space to add it with the keyboard.
2. Select an instance in Layers to edit its properties and styles. Containers support nesting. Move, nest, outdent and delete buttons provide keyboard alternatives. The narrow viewport sets responsive styles.
3. Save and reload drafts as needed. Preview disables component business operations. Publishing saves the current edit and replaces the live version. Component configuration on public pages becomes public too.
4. Restore previous publication replaces both the live page and draft with the previous usable publication. Concurrent edits from another window produce a revision conflict; reload before applying your changes again.

Reload draft and Restore previous publication are in the More menu at the top. Restore is unavailable until a previous publication exists.

Core retains the login form, account login, invitation-code flow and redirect behavior. Use default layout restores its original appearance. Missing components, invalid publications or an inactive editor also fall back to the default login page. Editing does not change authentication policy; signed-in users still redirect as before.

Missing or incompatible components retain their original type, configuration version, instance identity and configuration. Other layout changes can be saved, but an incomplete page cannot be published. Reactivate the matching plugin or explicitly apply its configuration migration. A failed publication or restore leaves the last committed version intact.

## Header, footer and login card

A new draft loads the original layout supplied by the page owner. Select its header, main content or footer to edit styles, or add these regions from Add component. A container’s Region type switches between a regular container, header, main content and footer.

For registration information, select Footer in Layers, add Text or Link, then enter the text and URL. The default layout uses a vertical flex container with Main content set to Fill remaining space. The footer reaches the bottom on short pages and follows the content on long pages without covering it.

The login card exposes its title, username, password, buttons and other required controls as selectable regions. Drag text or a plugin between controls, or select a control and press Enter on a component button to insert after it. Required controls cannot be deleted, copied or moved outside their declared parent region. Regular containers can group content and styles. Show initially hidden regions reveals the invitation card for editing without changing how the live page switches cards.

More → Restore page default layout replaces only the unsaved canvas; save and publish to change the live page. Use default layout on the live login page restores that browser view without deleting server drafts or publication history. If required regions are missing, the editor shows an explanation and a preview of the default login page, with saving and publishing unavailable. Start from this layout replaces only the unsaved canvas. The original draft remains stored until you explicitly save.

Switch between Structure and Add component in the left sidebar. Select content on the canvas or in the structure list, then edit its properties and styles on the right. Movement and deletion controls are also on the right. Browser tab title in Page settings changes only the tab title. Preview expands the actual layout in the center; Back to editing keeps unsaved changes. The preview uses the login page’s own controls and styles without submitting sign-in or business requests.

## Component CSS

Select a component and click Style · CSS to expand the inline CodeMirror editor. It provides line numbers, highlighting, supported-property completion and error locations. Use the question-mark button for input syntax and keyboard shortcuts. The diagonal-arrow button fills the screen; click it again or press Esc to return to the sidebar with your input and undo history intact. Enter declarations and choose Apply styles, for example:

```css
padding: 16px;
border-radius: 8px;
```

Styles affect the selected component’s root element at the current viewport width. Inheritable properties still follow CSS inheritance. Switch to Narrow to set separate overrides; applying empty input removes overrides at that width. Applied styles support undo, are saved with the draft and are used in previews and publications; no separate `.css` file is stored.

Ctrl/Cmd+Enter applies styles, Tab moves to the next control, and Esc closes completion suggestions or help before leaving full screen or collapsing the editor. Collapsing preserves pending input; Discard changes reloads the current component’s styles. Apply or discard pending CSS before saving, previewing or publishing. Switching components or viewport widths preserves the input but prevents applying it to a different target.

Panel expansion, component movement and action feedback use interruptible transitions. The system’s reduced-motion setting disables these animations while keeping status text and results visible.

The following properties and valid values are accepted. Each value is limited to 128 characters. Selectors, comments, `@media` / `@import`, external resources, `!important`, `display: none` and custom property definitions are not accepted. Invalid input stays in the editor with an error message.

`display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`, `padding-top`, `padding-right`, `padding-bottom`, `padding-left`, `margin`, `margin-top`, `margin-right`, `margin-bottom`, `margin-left`, `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## Contribute a component

A new component changes only its own plugin and independent business contract. No host, central SDK or editor component-type list needs updating. The repository's [independent checklist example](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components) demonstrates nested `items[]`, a custom property editor, two instances and a generated business client. It is an acceptance fixture, not an installed product feature.

| File or entry point | Component author's responsibility |
| --- | --- |
| Business contract and records | Declare operations and configuration types in an independent public contract; generate JSON Schema, browser client and Java HTTP adapter using SDK tools |
| `PageContribution` bean | Return `PageComponent` declarations; implement business validation and any configuration migrations |
| Render module | Export `mount(root, context)`; return `update(properties)` and `destroy()` |
| Property editor module | Export `mountProperties(root, context)` for complex data; submit complete value snapshots through `context.change(value)` |
| Configuration class and descriptor | Register beans, the generated adapter, service imports/exports and contract dependencies; declare the minimum SDK |
| Static resources, routes and i18n | Contribute same-origin modules and dictionaries; public render modules require actual PUBLIC routes, while management assets and business operations keep their declared access policies |

`PageComponent` declares a stable `owner:type`, positive configuration version, label key/namespace, render and optional property-editor URLs, schema/defaults JSON, `publicRenderable`, and input/output port names. The type's owner prefix must match the actual plugin owner. Duplicate types reject publication. The catalog aggregates multiple owners; it does not use a single-provider `PluginServices` key as a component collection.

Schemas use the semantics supported by the current SDK generator and the same server validator. Arbitrary JSON Schema drafts are not supported. The basic inspector handles strings, booleans and numbers; plugins provide property editors for nested objects and arrays. Check business invariants in `validate(type, json)`. `migrate(type, fromVersion, json)` returns updated configuration, which the server validates before accepting. Migration is unsupported by default.

Browser declarations are included in the Plugin API JAR at `META-INF/pixivdownload/web/page-components.d.mts`. The context provides instance identity, `edit/preview/display` mode, value properties, cancellation, translations, controlled fetch, cleanup and declared port connections. It does not expose GrapesJS objects. Supply `context.fetch` to your generated client:

```js
import {createClient} from './generated/my.contract.mjs';

export function mount(root, context) {
    const client = createClient(context.fetch);
    const button = document.createElement('button');
    button.textContent = context.t('run');
    button.disabled = context.mode !== 'display';
    context.on(button, 'click', async () => {
        try {
            const result = await client.run(context.properties, {signal: context.signal});
            if (!context.signal.aborted) context.emit('result', result);
        } catch (error) {
            if (!context.signal.aborted) root.dataset.failed = 'true';
        }
    });
    root.append(button);
    return {
        update(properties) { /* Update this instance without business writes. */ },
        destroy() { root.replaceChildren(); }
    };
}
```

Replace the example contract, operation and port with your declarations, and show localized errors in real components. Register additional subscriptions, listeners and timers with `context.onCleanup`. Stop asynchronous work on cancellation and ignore late results. Property editors follow the same rules. Context fetch binds the exact publication; old requests never switch to a replacement plugin. Existing backend authentication still applies. A browser disconnect does not prove server work has drained.

Plugin code is trusted installed code; the iframe is not a security sandbox. Do not bypass the context with native fetch to perform business writes while editing or previewing. Do not store functions, scripts, runtime handles or authorization tokens in configuration. Component contracts define port payloads and validation. Pages connect declared ports without arbitrary URLs, reflection method names or script execution.

## Integrate a page

`EditablePage` also declares `regions`, `defaultProject`, `previewModuleUrl` and `stylesheets`. Each `PageRegion(id, labelKey, parent, container, previewHidden)` must appear exactly once. `parent` identifies its nearest controlled ancestor; an empty string prohibits nesting inside another controlled region. Only regions with `container=true` accept children. The server checks the owner’s declaration rather than permissions claimed by page JSON.

`defaultProject` uses the editor archive shape `{"format":1,"project":{...}}` and contains pure values. An empty string creates a minimal layout from the declared region hierarchy. Nodes include `page-box` with role `section/header/main/footer`, `page-text`, `page-link`, `page-region` and `page-component`. Core `login/login-layout.json` is a complete example. A link’s `href` accepts a root-relative path or HTTP(S) URL, up to 2048 characters. Links are navigation, not business-operation dispatch.

The preview module implements `mount(root, context)` with `context.properties.region`. It renders only the leaf region’s readonly appearance; the editor assembles container children. Resources must belong to the current owner. Up to 16 CSS files can be declared, with publication validation and withdrawal. The preview module URL is omitted from anonymous projections. Use `attachPage.onMounted` to hide the original shell and `onFallback` to restore it. The runtime restores moved region DOM, original children and styles.

The page owner contributes `EditablePage` through `PageContribution.pages()`: identity, display metadata, actual page path, public visibility and required region names. Call `attachPage` from core `/js/pixiv-pages.mjs` with the render root, existing region DOM, default-layout button and title callback. Core `login/login-page.mjs` shows the integration. Preserve the owner's default page and business listeners: region DOM is moved, not recreated. Public visibility must match the owner's actual PUBLIC route.

Catalogs and drafts require ADMIN. Anonymous `GET /api/web/page-publications/{pageId}` returns only the published render projection of a declared public page, without drafts, schemas or property-editor addresses. Live pages load the small runtime and required components, not GrapesJS. Documents belong to the editor owner under its runtime data directory's `pages`; they do not modify JAR resources or host configuration.

Archive format 1 stores constrained GrapesJS project JSON, title, instance configuration and connections. HTML/CSS are derived. Each document permits up to 1 MiB, 512 nodes, 24 component nesting levels and 256 connections; titles allow 256 characters. A state file retains draft, live and previous versions, uses revisions for concurrency, and commits through a same-directory atomic replacement. Controlled layout and text styles are supported; scripts, arbitrary HTML, external resource URLs and CSS imports are rejected.

## Run independent-plugin acceptance

Build the host, SDK tools, editor and required plugins using Java 17, Maven, Python and Node.js. Use an installed Playwright/Chromium: set `PAGE_EDITOR_PLAYWRIGHT` to the absolute Playwright `index.mjs` path and, if needed, `PAGE_EDITOR_CHROMIUM` to the browser executable.

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

The first command builds focused artifacts only; run unit tests separately. The second requires a new destination directory. It freezes the input JARs, separately generates and compiles the example, installs it through the real host entry point, runs browser and lifecycle checks with isolated configuration/database, and checks frozen hashes afterward. It does not touch real user data or constitute distribution-package or CAPTCHA acceptance.

Pinned GrapesJS Core files, license and hashes are recorded in the editor's `web/vendor/grapesjs/vendor.json`. Runtime use disables default CDN icons and GrapesJS telemetry. Components retain responsibility for declaring their own business network access.
