# Page editor and plugin components

The page editor requires SDK 1.4 or later. Install and start the optional `page-editor` plugin, sign in as an administrator, and open Page editor from navigation. Core currently contributes the login page. Other page owners declare their own integration.

## Edit, publish and recover

1. Select a page and drag components from Add component into the canvas. To use the keyboard, focus a component and press Enter or Space to view its details, then choose Add to page.
2. Select an instance in Layers to edit its properties and styles. Containers support nesting. Move, nest, outdent and delete buttons provide keyboard alternatives. The narrow viewport sets responsive styles.
3. Save and reload drafts as needed. Preview disables component business operations. Publishing saves the current edit and replaces the live version. Component configuration on public pages becomes public too.
4. Restore previous publication replaces both the live page and draft with the previous usable publication. Concurrent edits from another window produce a revision conflict; reload before applying your changes again.

If the published page is restored but saving its draft fails, the editor keeps the restored content and marks it unsaved. Resolve the storage problem and choose Save draft; do not restore or publish again, as that would replace the current rollback point.

Reload draft and Restore previous publication are in the More menu at the top. Restore is unavailable until a previous publication exists.

Core retains the login form, account login, invitation-code flow and redirects. Published pages and operation protection remain active when the editor stops. Use default layout changes only the current browser view; it does not remove server verification requirements. Unavailable formal records, required components or policies keep protected operations closed. Signed-in users still redirect as before.

Missing or incompatible components retain their original type, configuration version, instance identity and configuration. Other layout changes can be saved, but an incomplete page cannot be published. Reactivate the matching plugin or explicitly apply its configuration migration. Failure before commit preserves the previous formal version. If activation fails after the commit record is written, operations remain closed while the committed revision is restored.

## Form verification and resource approval

Drag a verification component into a form region declared by the page owner. A unique compatible target is selected automatically. If several targets match, choose one under Protected operation. With no match, you can save a draft but cannot publish protection. All participating policies on a target are required; duplicate policies are rejected. Copy, move and undo preserve separate instance relationships without copying active proofs.

Before publication, the editor lists affected operations, changes to required policies and requested resource origins. After confirmation, the server rechecks the current catalog and revision, closes admission for affected operations, waits for active actions to finish, and commits pages, bindings and permissions together. Drafts and previews do not change live protection or create real challenges. Pages referring to the same operation must agree on configuration. Static `setup.login-verification-policies` remain required for login.

HTML, title, projection, ETag and CSP use one formal revision. Old documents navigate again when they detect a revision change; the backend rejects old proofs, and components dispose workers and other instance resources. Custom HTML/JS components keep their existing sandbox and receive no CAPTCHA plugin script permissions.

Formal state lives in `data/web-publications/`, with enrollment recorded in `config/web-publications.enrolled`. Do not delete them as cache. Stopping the editor or losing the main database does not clear protection. For offline repair, stop the instance and run these local commands from its runtime directory. Query lists candidate files and SHA-256 digests. Restore only a verified complete candidate; file timestamps do not establish which revision was committed.

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

Replace `<boot-jar>` with the actual boot JAR and use verified query values for `<candidate.json>` and `<sha256>`.

## Header, footer and login card

A new draft loads the original layout supplied by the page owner. Select its header, main content or footer to edit styles, or add these regions from Add component. A container’s Region type switches between a regular container, header, main content and footer.

For registration information, select Footer in Layers, add Text or Link, then enter the text and URL. The default layout uses a vertical flex container with Main content set to Fill remaining space. The footer reaches the bottom on short pages and follows the content on long pages without covering it.

The login card exposes its title, username, password, buttons and other required controls as selectable regions. Drag text or a plugin between controls, or select a control, open a component’s details, and choose Add to page to insert after it. Required controls cannot be deleted, copied or moved outside their declared parent region. Regular containers can group content and styles. Show initially hidden regions reveals the invitation card for editing without changing how the live page switches cards.

More → Restore page default layout replaces only the unsaved canvas; save and publish to change the live page. Use default layout on the live login page restores that browser view without deleting server drafts or publication history. If required regions are missing, the editor shows an explanation and a preview of the default login page, with saving and publishing unavailable. Start from this layout replaces only the unsaved canvas. The original draft remains stored until you explicitly save.

Switch between Structure and Add component in the left sidebar. Select content on the canvas or in the structure list, then edit its properties and styles on the right. Movement and deletion controls are also on the right. Browser tab title in Page settings changes only the tab title. Preview expands the actual layout in the center; Back to editing keeps unsaved changes. The preview uses the login page’s own controls and styles without submitting sign-in or business requests.

## Component CSS

Select a component and expand Custom CSS within Style to use the inline CodeMirror editor. Layout, Appearance and Custom CSS expand independently; collapsing Style hides the whole group and preserves unapplied CSS. The editor provides line numbers, highlighting, supported-property completion and error locations. Use the question-mark button for input syntax and keyboard shortcuts. The diagonal-arrow button fills the screen; click it again or press Esc to return to the sidebar with your input and undo history intact. Enter declarations and choose Apply styles, for example:

```css
padding: 16px;
border-radius: 8px;
```

Styles affect the selected component’s root element at the current viewport width. Inheritable properties still follow CSS inheritance. Switch to Narrow to set separate overrides; applying empty input removes overrides at that width. Applied styles support undo, are saved with the draft and are used in previews and publications; no separate `.css` file is stored.

Ctrl/Cmd+Enter applies styles, Tab moves to the next control, and Esc closes completion suggestions or help before leaving full screen or collapsing the editor. Collapsing preserves pending input; Discard changes reloads the current component’s styles. Apply or discard pending CSS before saving, previewing or publishing. Switching components or viewport widths preserves the input but prevents applying it to a different target.

Panel expansion, component movement and action feedback use interruptible transitions. The system’s reduced-motion setting disables these animations while keeping status text and results visible.

The following properties and valid values are accepted. Each value is limited to 128 characters. Selectors, comments, `@media` / `@import`, external resources, `!important`, `display: none` and custom property definitions are not accepted. Invalid input stays in the editor with an error message.

`display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`, `padding-top`, `padding-right`, `padding-bottom`, `padding-left`, `margin`, `margin-top`, `margin-right`, `margin-bottom`, `margin-left`, `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## Save your own HTML, CSS and JavaScript components

Add component shows layout, content, plugin-defined categories and custom components. Search matches component and category names. Plugins can supply several categories and descriptions; unassigned components use the plugin display name. The CAPTCHA plugin uses the CAPTCHA category. Drag a component onto the canvas to add it directly. Clicking a component or pressing Enter or Space opens its details without changing the page. Choose Add to page to insert it at the selected position. Details open beside the component while the list stays visible. Click outside, use Close details, or press Escape to dismiss them; the search is preserved. Manage library and New component open a new tab, keeping your page draft in the original tab. The library supports name search and status filters with counts; select a component row to open its editor page.

Enter a component name at the top of the editor. Source contains HTML, CSS and JavaScript; Property definitions contains instance fields; Versions and publishing contains history, version loading and withdrawal. Source and preview appear side by side in wide windows and stack in narrow windows. HTML is an internal fragment. CSS selectors apply inside that instance's sandbox. Leave JavaScript empty for static content. Switching source tabs preserves each tab's undo history.

Components have a transparent background and inherit the page's text color by default. Component CSS can set its own colors. The container grows and shrinks with its content; fully hidden content collapses in previews and published pages. The editing canvas keeps a small selectable placeholder.

1. Run preview shows the current source with default properties. Run it again after editing. Save draft stores the source without changing published pages.
2. Publish previews and saves the source as a pinned version, then adds it to the component palette. Published source and property defaults are public. Do not include passwords or tokens.
3. Add the component to a page. Each copy has its own state, properties and outer layout. Save and publish the page to make it live.
4. Publishing a component update leaves existing pages on their pinned versions. Select a version in the instance properties, use the button to apply it and reset properties, then save and publish the page. The library can also load a historical version into the draft for publication.
5. Withdraw stops existing instances while retaining source and versions. Re-enable and reload the page to restore access. Conflicts and failed saves preserve your input and the last committed version.

Under Property definitions, choose Add property and fill in its name in code, display label, type and default value. Types are Text, Number and Boolean. For example, use `title`, `Title`, Text and `Hello`. The corresponding definition is:

```json
{"title":{"type":"string","label":"Title","default":"Hello"}}
```

Use `<p></p>` as HTML and `root.querySelector('p').textContent = context.properties.title;` as the JavaScript function body. Scripts receive `root` and `context` (`properties` and `mode`), and may return `update(context)` and `destroy()` methods. Stored property values are validated text, converted to their declared types inside the sandbox. Host sessions, business clients and editor objects are not passed to the component.

Each instance runs in an iframe without `allow-same-origin`. CSP blocks fetch/XHR, external scripts and external style resources. HTML scripts, style elements, forms, embedded pages, resource addresses and event attributes report an error. Inline CSS styles and data image backgrounds work inside the component. This sandbox cannot guarantee termination of synchronous infinite loops or enforce CPU and memory quotas. Use trusted code; if a tab freezes, close it and withdraw the component from the editor.

The server stores drafts, availability and immutable versions under `data/page-editor/components/`, outside the plugin JAR. Source JSON and management requests are limited to 1 MiB; the library supports 128 components, 4096 published versions per component and 64 properties. History is retained. Up to eight temporary previews expire after ten minutes; closing the preview or stopping the plugin revokes access.

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

Return `PageComponentCategory(id, labelKey, namespace, descriptionKey, components)` from `PageContribution.componentCategories()`. Use an `owner:local-id`, i18n keys for the title and optional description, and this plugin’s component types in display order. Categories must be nonempty; each component may belong to only one category. Duplicate IDs or members and unknown or foreign types are rejected. Unassigned components use the plugin display name. Categories disappear with their plugin and do not change page bindings or operation permissions. Category count cannot exceed component count; categories share the 1 MiB UTF-8 declaration budget with pages and components.

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

## Official CAPTCHA plugin

The official `captcha` plugin provides Turnstile, ALTCHA SHA-256 and ALTCHA PBKDF2 components. See [Operation CAPTCHA plugin](/en/captcha) for installation, form binding, key configuration, local testing and troubleshooting.

Form owners obtain challenges with `OperationGuard.prepare(operation, subject, context)`, which does not execute business actions. In the browser, register the form-owned preparation request with `createOperationTarget(operation, onChange, prepareRequest)`. Verification components call `context.interaction.prepare()`, receive challenges and return proofs. Only when `target.ready()` is true can the form obtain submission data through `target.request()`. The submission endpoint must still call the same owner's `OperationGuard.execute(...)`, with business actions inside its verified callback. Preparation and submission must use the same subject and context. Call `reset()` on input changes; components call `invalidate(id)` when verification becomes invalid. Both clear proofs and cancel pending preparation. Preparation sends only the non-sensitive context needed for verification; verification components do not read passwords or complete business requests.
