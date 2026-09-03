# Plugin Management

The **Plugin Management** page (top nav → **Plugins**) lets an admin see all of the app's **plugins** in one place, check their runtime status, and run lifecycle actions on **external plugins**.

?> This page is admin-only. Non-admin users don't see the entry and are denied direct access.

> To enable or disable **optional plugins**, see `plugins.<plugin id>.enabled` in [Configuration](/en/configuration). `download-workbench` is required and cannot be disabled. The official distribution set is `download-workbench`, `gui-compose`, `gui-swing`, `gallery-tools`, `posthog`, `gallery`, `novel`, `notification`, `multi-mode-decision-survey`, `push`, `mail`, `tts`, and `ai`. Douyin is a regular third-party plugin installed from a custom repository or local package.

---

## Execution security

A signature proves publisher identity and artifact integrity. It does not certify harmless behavior or grant runtime capabilities.

Every external plugin must explicitly set `pixiv.execution-mode` in `plugin.properties`:

| Value | Runs in | Boundary |
| --- | --- | --- |
| `host-process-full-trust` | Host JVM | Inherits the host process's file, network, and OS privileges |
| `declarative-process` | Separate worker JVM | Publishes declarative routes and capabilities through a bounded protocol |

Missing, blank, or unknown values are rejected before any plugin code runs. The worker still uses the host's OS account, so it provides limited process, protocol, and resource isolation, not a complete OS sandbox. This release has no OS sandbox provider or JVM switch that requires one. Production rejects directory-form `declarative-process` plugins; explicit development mode downgrades them to `host-process-full-trust` and reports the effective mode in status and logs.

Trust cannot silently expand across execution boundaries. An upgrade from `declarative-process` to `host-process-full-trust` requires administrator confirmation again even if the publisher is unchanged. The same applies after an SDK major-version change or trust revocation. If the host is actually elevated, full-trust plugins inherit those privileges and the management page keeps a warning visible.

Workers default to 128 MiB heap, 128 MiB metaspace, and 64 MiB direct memory, with exit on OOM. Initialization, command, and shutdown timeouts are 10,000 / 5,000 / 2,000 ms. An unexpected exit gets at most 3 restarts, with backoff from 500 ms to 10,000 ms. The host reads at most 1 MiB of stderr and retains the last 16 KiB. Each worker allows 1 in-flight and 1 queued request. When a worker exits, the host withdraws its routes and capabilities before attempting recovery.

Override the configurable values before JVM startup under `pixivdownload.plugin-worker.*`: `initialize-timeout-ms`, `command-timeout-ms`, `shutdown-timeout-ms`, `restart-attempts`, `restart-initial-delay-ms`, `restart-max-delay-ms`, and `stderr-max-bytes`.

### Package admission limits

Defaults are a 192 MiB archive, 48,000 entries, 672 MiB of actual total decompressed data, a 64 MiB single entry, a 1 MiB descriptor, compression ratio 200 for entries of at least 64 KiB, a 1,024-character entry name, and 64 path segments. Override them with positive integers using:

- `pixivdownload.plugin.package.max-archive-bytes`
- `pixivdownload.plugin.package.max-entries`
- `pixivdownload.plugin.package.max-total-uncompressed-bytes`
- `pixivdownload.plugin.package.max-entry-uncompressed-bytes`
- `pixivdownload.plugin.package.max-descriptor-bytes`
- `pixivdownload.plugin.package.max-compression-ratio`
- `pixivdownload.plugin.package.max-entry-name-length`
- `pixivdownload.plugin.package.max-entry-depth`

Invalid values fail plugin-runtime initialization instead of silently falling back.

---

## Where plugins come from

- **Built-in**: compiled and shipped with the app (core, plugin market, etc.). They are usually read-only here; whether they can be disabled through `config.yaml` depends on whether the plugin is optional.
- **Required external**: `download-workbench` provides the download page, download APIs, queue, userscript entry, Pixiv artwork proxy, and scheduled-task host. The default downloader package and default Windows installer bundle it. If it is missing, corrupted, incompatible, or fails verification, the app enters the recovery path.
- **Official optional external plugins**: `gui-compose`, `gui-swing`, `gallery-tools`, `posthog`, `gallery`, `novel`, `notification`, `multi-mode-decision-survey`, `push`, `mail`, `tts`, and `ai` remain separate packages under the working directory's `plugins/` folder. Missing or disabling one withdraws only its own contributions and does not by itself trigger recovery.
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

- **Local files only**: select a local `.jar` or compatible `.zip`; a detached `.sig` is optional. This entry accepts no arbitrary install URL. Online repository installation belongs to the separate [Web Plugin Marketplace](#web-plugin-marketplace).
- **No custom trust root**: when a signature is supplied, it must bind the exact artifact and verify under the applicable trust root. An unsigned package is recorded as `LOCAL_UPLOAD / UNSIGNED_ALLOWED`. Remote repository packages always require the signature declared by their manifest and cannot fall back to local unsigned handling.
- **Execution confirmation**: non-official local packages can be installed in production, but they require risk confirmation before code runs. Signed packages are approved by publisher fingerprint; unsigned packages are approved only for the exact SHA-256. Updates, key changes, revocation, or an execution-privilege increase can require confirmation again. Use a custom repository when a third party needs durable trust in its own key.
- **Transactional activation**: installation uses the same validation, atomic replacement, rollback, and lifecycle policy as marketplace installation. `hot-reload` and `backend-restart` packages activate in the current process; `process-restart` packages take effect after a full process restart. Use the `remove` lifecycle action to remove an installed artifact; there is no data-purging action.

!> The install entry is admin-only and never goes online. Check the declared execution mode and the confirmation summary before approving a package.

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

Missing or disabling an official optional plugin such as `gallery-tools`, `gallery`, `novel`, `notification`, `tts`, `ai`, `push`, `mail`, or a desktop GUI provider does not trigger recovery by itself:

- Missing `gallery-tools` does not affect image Hash writes or historical Hash data.
- Missing `gallery` does not affect the download page, download APIs, userscripts, Pixiv artwork proxy, scheduled-task host, work metadata, download facts, Hash data, or local resource index.
- Missing `novel` withdraws novel downloading, its gallery, body storage, translation state, series compilation, and scheduled novel runner together; the core does not retain a parallel novel implementation.
- Missing TTS / AI / push / mail makes the corresponding capability unavailable or skipped; there is no fallback implementation inside the core.

---

## Desktop GUI entry

If you use the desktop app, the selected GUI provider owns its **Plugins** page and reads the same backend status as this page (name / source / status / runtime phase / required / version), with **Refresh** and **Open web plugin manager** actions. Compose and Swing own their interfaces separately while sharing application semantics.

- Enabling / disabling / installing / uninstalling plugins is **not done in the GUI** — click **Open web plugin manager** to come here and do it.
- Once the external `gallery-tools` plugin is installed you can see its install and runtime status in the GUI; when the core enters recovery mode for a missing required download plugin, the GUI shows a clear notice too.
- The GUI reads status from the backend (same as this page); it never scans the plugin folder itself and does not relax any permission checks.
- `gui-compose` is the default desktop provider and `gui-swing` is the automatic fallback. Both are included in the official distribution and use `process-restart`; install, update, enable/disable, remove, or provider selection changes require a full application restart.

## Filesystem boundary

The original artifact under `plugins/` and its `plugins/provenance/` sidecar define installation identity. `plugins/runtime/` is only a private frozen workspace for a generation. A portable installation may make the `plugins/` root itself a symbolic link or Windows junction; the runtime resolves and pins the real root first, while linked artifact candidates inside it are still rejected individually.

The host tightens POSIX permissions or Windows ACLs on managed `plugins/runtime/` and `plugins/provenance/` paths when supported. FAT32, exFAT, SMB, and similar filesystems that expose neither capability produce a diagnostic and continue with regular-file, `NOFOLLOW`, frozen-snapshot, and hash checks.

---

## See also

- [Configuration](/en/configuration): `plugins.<plugin id>.enabled` plugin enablement switches
