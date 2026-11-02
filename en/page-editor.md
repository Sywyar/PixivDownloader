# Page editor and plugin components

The page editor (`page-editor` plugin, requires SDK 1.4+) lets you visually arrange layouts, responsive styles, custom components and form verification components on supported pages such as the login page. Install and enable the plugin, then open "Page editor" from the navigation as an administrator.

## Edit, publish and restore pages

1. Select a target page and drag layout, content or plugin components from "Add component" on the left onto the canvas (for keyboard access, focus a component and press `Enter` / `Space` to open its details, then select "Add to page").
2. Select an instance in "Structure" on the left or on the canvas, then adjust its properties, layout and appearance on the right. You can nest containers, move items up/down or out, delete items, and set responsive overrides for narrow viewports.
3. Click "Save draft" or "Preview" (preview uses the page's actual styles but does not submit login or business write requests; "Back to editing" retains unsaved changes).
4. Click "Publish" to replace the live page (component configuration and property defaults on public pages become public, so do not enter passwords or tokens).
5. The "More" menu at the top provides "Reload draft", "Restore previous publication" (replaces both the live page and draft) and "Restore page default layout" (replaces only the unsaved canvas).

Live pages and their operation protection do not depend on keeping the editor open. "Use default layout" on a live page changes only the current browser's presentation; it does not clear server-side verification requirements. If a page contains missing or version-incompatible plugin components, you can still save a draft, but must restore the plugin or migrate its configuration before publishing.

## Form verification and resource authorization

After dragging a CAPTCHA component into a form region declared by the page owner, the only compatible target is bound automatically. If there are multiple targets, explicitly select one under "Protected operation" on the right. When multiple verification components are bound to the same form, all policies must be completed.

Before publication, the editor lists affected operations, policy changes and the external resource origins required by components. After you confirm publication, the server pauses new requests to the target operations, waits for in-flight actions to drain, and atomically commits the page, bindings and CSP resource permissions (while retaining static login protection requirements in `setup.login-verification-policies`).

Published state is stored in `data/web-publications/`, with the enrollment file at `config/web-publications.enrolled`. Do not delete these as cache. For offline recovery, first stop the application, then query candidate files from the distribution directory, check the files and SHA-256 values, and run the restore command:

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

## Headers, footers and custom CSS

The default layout includes a header, a body (which expands vertically with flex layout to fill the remaining space) and a footer. To add information such as a registration number, select "Footer" and insert "Text" or "Link". The title, username, password and button inside the login card are controlled, required regions. You can insert text or components between controls, but cannot delete, duplicate or move those regions out of their parent. Select "Show initially hidden regions" to edit the invitation-code card.

After selecting a component, use the built-in CodeMirror editor under "Style → Custom CSS" on the right to edit property declarations (with line numbers, completion, error locations and a full-screen toggle at the top right). Press `Ctrl/Cmd+Enter` or click "Apply styles" to apply them:

```css
padding: 16px;
border-radius: 8px;
```

- Each property value can contain at most 128 characters. It applies to the selected component's root element at the current canvas width (set separate overrides for narrow screens; clear and apply to remove an override).
- Selectors, comments, `@media` / `@import`, external resource URLs, `!important`, `display: none` and custom CSS variables are not accepted. Before saving, previewing or publishing, apply the edits or click "Discard changes".
- Supported properties: `display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding` (and its four sides), `margin` (and its four sides), `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## Custom HTML / CSS / JavaScript component library

Under "Add component", click "New component" or "Manage component library" (opens a separate tab without affecting the original page draft):

1. Under "Source", edit the HTML fragment, CSS and optional JavaScript function body. Under "Property definitions", add instance fields (supports text `string`, numeric `number` and toggle `boolean`):

   ```json
   {"title":{"type":"string","label":"Title","default":"Hello"}}
   ```

   For example, use `<p></p>` as HTML and `root.querySelector('p').textContent = context.properties.title;` as JavaScript (receives `root` and a `context` containing `properties` and `mode`; may return `update(context)` and `destroy()`).
2. Click "Run preview" to check the result, then "Publish" to save an immutable version and add it to the component panel.
3. Drag the component onto a page and publish the page. Publishing a newer component version does not automatically change an already published page. Select the new version in the page instance's properties, click "Use selected version and reset properties", then publish the page again.
4. "Withdraw component" stops existing instances from rendering while retaining source code and version history.

Custom components run in sandboxed iframes without `allow-same-origin`. CSP prohibits `fetch`/`XHR`, external scripts and external styles. HTML cannot contain `<script>`, `<style>`, `<form>`, embedded pages, external resources or inline event attributes. The library is stored in `data/page-editor/components/` (each source file is limited to 1 MiB; at most 128 components, 4,096 versions per component and 64 properties).

## Develop third-party plugin components and integrate pages

Plugins can contribute components and editable pages through public contracts without modifying host or editor source (for complete acceptance examples, see the main repository's [`page-components` fixtures](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)):

| Extension point | Responsibilities and constraints |
| --- | --- |
| Business contracts and `sdk-tools` | Declare operation and configuration records in a separate API package; generate JSON Schema, browser clients and Java HTTP adapters |
| `PageContribution.components()` / `componentCategories()` | Declare `PageComponent` (stable `owner:type`, positive integer configuration version, module URL, schema/defaults, `publicRenderable`, ports) and categories `owner:local-id`; implement `validate` and optional `migrate` |
| Rendering and property editor modules | Export `mount(root, context)` (and `mountProperties(root, context)` for complex properties); use `context.fetch`, `context.signal` and `context.onCleanup`, do not expose GrapesJS objects, and do not send business write requests directly in `edit`/`preview` mode |
| `PageContribution.pages()` | Declare `EditablePage` (page ID, path, `regions`, `defaultProject`, `previewModuleUrl`, at most 16 CSS files from the same owner); the frontend calls `attachPage` from `/js/pixiv-pages.mjs` (see core `login/login-page.mjs`) |

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
        update(properties) { /* Update the display without performing writes */ },
        destroy() { root.replaceChildren(); }
    };
}
```

Each page document is limited to 1 MiB, 512 nodes, 24 nesting levels, 256 port connections and a 256-character title. Standalone browser and lifecycle acceptance commands:

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

## Official CAPTCHA plugin

The official `captcha` plugin provides six verification components: Turnstile, reCAPTCHA v2, ALTCHA SHA-256, ALTCHA PBKDF2, character input and slider puzzle. See [Operation CAPTCHA](/en/captcha) for installation, form binding, keys, official test keys and troubleshooting.

Form developers use `OperationGuard.prepare(operation, subject, context)` to generate challenges (without executing business operations). The frontend registers a preparation request through `createOperationTarget(operation, onChange, prepareRequest)`, obtains proof with `target.request()` once all bound components are complete, and calls `OperationGuard.execute(...)` for the same owner at the backend business entry point to verify signatures and execute the operation.
