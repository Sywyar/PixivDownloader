# Plugin Management

The **Plugin Management** page (top nav → **Plugins**) lets an admin see all of the app's **plugins** in one place, check their runtime status, and run lifecycle actions on **external plugins**.

?> This page is admin-only. Non-admin users don't see the entry and are denied direct access.

> To enable/disable **optional plugins** at the config level, see `plugins.<plugin id>.enabled` in [Configuration](/en/configuration). `download-workbench` is a required external plugin and cannot be disabled. Official plugins such as `stats`, `duplicate`, `gallery`, `novel-gallery`, `notification`, `tts`, `ai`, `push`, `mail`, and `gui-theme` appear here once installed.

---

## Where plugins come from

- **Built-in**: compiled and shipped with the app (core, plugin market, core novel downloading, etc.). They are usually read-only here; whether they can be disabled through `config.yaml` depends on whether the plugin is optional.
- **Required external**: `download-workbench` provides the download page, download APIs, queue, userscript entry, Pixiv artwork proxy, and scheduled-task host. The default downloader package and default Windows installer bundle it. If it is missing, corrupted, incompatible, or fails verification, the app enters the recovery path.
- **Official optional external plugins**: `stats`, `duplicate`, `gallery`, `novel-gallery`, `notification`, `push`, `mail`, `tts`, `ai`, `gui-theme`, etc. are plugin packages under the working directory's `plugins/` folder. Missing or disabling them only withdraws their own contributions and does not trigger recovery.
- **Required but not installed**: a plugin declared required but currently missing shows up as a "Not installed" placeholder so you can add it.

---

## What you see

One card per plugin:

- **Name and source badge** (built-in / external / not installed) and **version**
- **Status**: running / stopped / disabled / failed / incompatible, …
- **Runtime phase** (managed external only): unloaded / loaded / running / quiesced / stopped
- **Core-API version requirement** and whether it is satisfied
- **Dependencies** on other plugins
- **Diagnostics** (e.g. why a plugin failed to load)

The top of the page also shows overview stats (installed / enabled / external / required), filter tabs and a search box.

---

## What you can do

For **managed external plugins**, the card footer offers buttons for the currently **available verbs**:

| Action | Meaning |
| --- | --- |
| Load | Re-attach a previously unloaded external plugin |
| Start | Start / rebuild its services |
| Quiesce | Stop taking new requests / task dispatch and drain in-flight work |
| Stop | Tear down its services |
| Unload | Stop, then remove it from the registry |
| Reload | Stop, then start again |

The switch in the card header enables / disables (maps to start / stop).

!> **Built-in plugins** don't show these buttons — they're compiled in and can't be hot-toggled. **Required plugins** cannot be disabled.

After a successful action the page refreshes plugin status and the top navigation (enabling / disabling a plugin makes its entry appear / disappear). This page is **primarily for runtime management**; it also gives admins an **Install local plugin package** entry (see below).

---

## Install a local plugin package

Besides runtime-managing already-installed plugins, this page also gives admins an **Install local plugin** entry to bring a local **external plugin package** into the app:

- **Local files only**: select a local `.jar` or compatible `.zip` package together with its detached `.sig` file. This entry accepts no arbitrary install-from-URL; online browsing and repository installation belong to the separate [Web Plugin Marketplace](#web-plugin-marketplace).
- **Official signature required**: outside explicit plugin development mode, the signature must verify against the built-in official trust root and bind the exact artifact. Missing, malformed, mismatched, or non-official signatures fail closed. Third-party distributions that trust their own key must use a configured custom repository instead of treating local upload as a custom trust-root entry.
- **Persistent provenance**: a verified local package keeps `LOCAL_UPLOAD` as its source while recording its detached signature and `VERIFIED` provenance for offline verification. Plugin development mode is the only mode that may omit `.sig`; such packages remain `LOCAL_UPLOAD / UNSIGNED_ALLOWED`.
- **Transactional activation**: installation uses the same validation, atomic replacement, rollback, and lifecycle policy as marketplace installation. `hot-reload` and `backend-restart` packages activate in the current process; `process-restart` packages take effect after a full process restart. Use the `remove` lifecycle action to remove an installed artifact; there is no data-purging action.

!> The install entry is admin-only and never goes online. A valid signature establishes publisher and byte integrity only; external plugin code still runs in the host JVM with the host process's OS privileges.

---

## Web plugin marketplace

The **Plugin Marketplace** is a separate admin-only page for browsing and installing plugins from **trusted repositories**. It does not replace the runtime status and lifecycle operations on this Plugin Management page.

The page supports:

- Switching official / custom repositories and viewing repository enabled state and proxy policy
- Filtering by category, keyword, official source, and current-version compatibility, then sorting by recommendation, update time, downloads, rating, or name
- Viewing plugin details, version history, dependencies, required core API, package size, SHA-256, signature status, change notes, and homepage
- Showing local install state as not installed / installed / update available / incompatible / no installable version
- Installing a version from the repository and reporting whether its lifecycle policy activates it immediately or requires a process restart

The marketplace itself is provided by the built-in `plugin-market` plugin. With `plugins.plugin-market.enabled=false`, the market page, APIs, static resources, i18n, navigation entry, and the "Market" entry on the Plugin Management page are withdrawn and direct access returns 404.

The network master switch `plugin-catalog.enabled` and the built-in official repository default to enabled. Startup itself does not access repositories; network access starts only when an administrator opens or refreshes the Plugin Market or installs a plugin. The master switch or official repository can be disabled separately. Custom repositories, proxy policies, timeouts, and size limits can be maintained in `config.yaml` or in the desktop GUI "Config -> Plugins" page. See [Configuration](/en/configuration).

### Install security boundary

- Requests submit only **repository id + plugin id + version**. Repositories must come from server config or the built-in official repository; download URLs can only come from that repository's manifest and never from request input.
- `direct-strict`: HTTPS only, private addresses rejected, no redirects, no app proxy.
- `proxy-trusted`: uses the core outbound proxy and follows at most five redirects only within the built-in GitHub Release asset host allowlist. Every hop is revalidated. The official repository uses this policy.
- Manifest and package downloads are bound to the same repository and use that repository's proxy policy, timeouts, and manifest / package size limits.
- Downloaded packages are verified by declared size, SHA-256, and signature before staging. If a signature is declared but no verifier is available, installation fails closed.
- Installation validates and transactionally replaces the package in `plugins/`, then activates it according to its lifecycle policy. Failures clean up temporary files and restore the old plugin when replacement has begun.

The marketplace does not provide arbitrary URL install, auto update, delete, or purge. The old `/api/plugins/catalog/**` route has been removed; the market uses the separate admin route `/api/plugin-market/**`.

---

## Recovery path

A required plugin such as `download-workbench` being missing, corrupted, incompatible, or failing verification puts the app into recovery mode. A crash while any plugin is starting does the same. The Plugin Marketplace banner names the missing required plugin or the plugin that failed to start, includes the available diagnostic, and shows default-installed plugins so they can be repaired. Normal features remain unavailable while the repair entries stay accessible.

Missing or disabling official optional plugins such as `duplicate`, `gallery`, `novel-gallery`, `stats`, `notification`, `tts`, `ai`, `push`, `mail`, and `gui-theme` does not trigger recovery:

- Missing `duplicate` does not affect image Hash writes or historical Hash data.
- Missing `gallery` does not affect the download page, download APIs, userscripts, Pixiv artwork proxy, scheduled-task host, work metadata, download facts, Hash data, or local resource index.
- Missing `novel-gallery` does not affect novel downloading, body storage, translation state, series compilation, scheduled novel runner, TTS / AI degradation behavior, or reading historical novel data.
- Missing TTS / AI / push / mail makes the corresponding capability unavailable or skipped; there is no fallback implementation inside the core.

---

## Desktop GUI entry

If you use the desktop app (GUI), the main window has a **Plugins** tab (alongside Status / Config / Tools) that **read-only** shows the same plugin status as this page (name / source / status / runtime phase / required / version), with **Refresh** and **Open web plugin manager** buttons.

- Enabling / disabling / installing / uninstalling plugins is **not done in the GUI** — click **Open web plugin manager** to come here and do it.
- Once the external statistics (`stats`) plugin is installed you can see its install and runtime status in the GUI; when the core enters recovery mode for a missing required download plugin, the GUI shows a clear notice too.
- The GUI reads status from the backend (same as this page); it never scans the plugin folder itself and does not relax any permission checks.

---

## See also

- [Configuration](/en/configuration): `plugins.<plugin id>.enabled` plugin enablement switches
