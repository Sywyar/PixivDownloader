# Plugin management

Administrators browse and install plugins at `/plugin-market.html`, and use `/plugin-manage.html` to check installed versions, what the current process has loaded and restart requirements, or to upload local packages. Successful installation on disk does not mean the current process has loaded the plugin. Some plugins require a backend restart or a complete application exit and restart.

## Install plugins and add custom repositories

1. Select a plugin from a trusted repository in the market, or upload a local `.jar` / `.zip` on the management page (optionally with a `.sig`).
2. Review its purpose, required SDK version, source, dependencies and execution mode.
3. Review and confirm the installation plan (target package, dependency sources and versions, reverse consumers, conflicts and restart impact). If an execution-trust confirmation appears, check the publisher's public-key fingerprint or the package SHA-256.
4. Perform the required restart shown in the receipt, then return to the management page to confirm the loaded version and feature entry points. On failure, check the original operation record before attempting another installation.

Add or edit custom repositories under desktop GUI "Settings → Plugin market settings". Choose "Use repository descriptor" (preview the publisher, network hosts and full public-key fingerprint before adding it to the draft) or "Enter manually" (provide an HTTPS manifest URL, proxy policy and Ed25519 public key). Save the settings together and restart for them to take effect.

Dependencies are installed in order through separate transactions. If the parent package fails, successfully installed dependencies remain, with their versions and `transactionId` listed in the result. Market operation records are kept in current-process memory (at most 64 records; inactive records remain for 24 hours). Closing the page does not cancel a backend installation that has started. After a process restart or record expiry, check the actual installation state on the management page first.

## Update notices and historical versions

A numeric badge in download-page navigation means installed plugins have compatible updates; an exclamation mark means a newer version requires a higher SDK. The management-page banner lists these separately. Stable plugins recommend stable updates by default. Only after manually installing a prerelease does the plugin follow that prerelease channel (it can also upgrade to a higher stable version). Manual repair packages with the same version (`-f`) do not trigger the normal update badge; reinstall them as directed by the repair instructions.

The official main manifest retains the latest version, latest stable version, and latest compatible stable versions for a fixed set of older SDKs (`1.0.0`, `1.1.0`). Other historical versions are stored in digest-named `history/` manifests (at most 900 versions or 1 MiB per file), loaded on demand when viewing history or automatically searching for a compatible older version.

If the latest plugin version is incompatible with the current SDK, the market automatically finds the highest compatible, installable older version in the same repository and shows the default selection on the card and detail page. In the details, you can also expand a statically isolated README, release notes for the update or CHANGELOG, or manually select a historical version. For plugin licenses and community inclusion rules, see [Third-party plugin SDK](/en/plugin-development#plugin-licenses-and-distribution-channels).

## Security model and package admission limits

An Ed25519 signature proves publisher origin and byte integrity, not a code security review or OS sandbox isolation. Install only publishers and repositories you trust. Before loading a plugin, the host freezes its package into a private workspace within resource limits, then checks package structure, `plugin.properties`, SHA-256, signatures, provenance, SDK compatibility and dependencies against the same frozen bytes.

### Execution modes

Every external plugin must explicitly declare `pixiv.execution-mode` in `plugin.properties` (missing or unknown values are rejected before any code executes):

| Value | Runs in | Isolation and permission boundaries |
| --- | --- | --- |
| `host-process-full-trust` | Host JVM | Inherits the host process's filesystem, network and OS permissions (the management page shows a persistent warning when the host runs elevated) |
| `declarative-process` | Separate worker JVM | Shares the host OS account and publishes declarative routes and capabilities only through a bounded protocol (no full OS sandbox; directory-form plugins are rejected in production, while development mode temporarily falls back to full-trust and displays this accurately) |

An administrator must confirm trust again when upgrading from `declarative-process` to `host-process-full-trust`, changing the SDK major version, or after trust is revoked. Workers default to 128 MiB heap, 128 MiB metaspace and 64 MiB direct memory (exit on OOM, at most 3 automatic restarts, with `500–10000` ms backoff). Initialization / command / shutdown timeouts are `10000` / `5000` / `2000` ms, with only 1 in-flight and 1 queued request at a time (adjustable through `pixivdownload.plugin-worker.*` JVM properties).

### Default plugin package resource limits

| JVM property (prefix `pixivdownload.plugin.package.`) | Default limit |
| --- | --- |
| `max-archive-bytes` | 192 MiB |
| `max-entries` | 48,000 entries |
| `max-total-uncompressed-bytes` | 672 MiB |
| `max-entry-uncompressed-bytes` | 64 MiB |
| `max-descriptor-bytes` | 1 MiB |
| `max-compression-ratio` | 200 (checked only for entries ≥ 64 KiB) |
| `max-entry-name-length` / `max-entry-depth` | 1,024 characters / 64 directory levels |

These properties accept only positive integers. Invalid values prevent plugin runtime initialization.

## Installation sources and market states

- **Bundled distributions**: Windows installers and standard Java packages include default official plugins in `plugins/` (`download-workbench`, `database-sqlite`, `gui-compose`, `gui-swing`, `gallery`, `novel`, etc.). Full offline packages also include official optional plugins such as `database-postgresql` and [PixivBatchDownloader import support](/en/pixiv-batch-downloader-import). `douyin` is an independent third-party plugin installed only from a custom repository or local package.
- **Local upload**: does not establish a custom trust root. Signed packages must validate against an existing trust root (approved by publisher fingerprint); unsigned packages are marked `LOCAL_UPLOAD / UNSIGNED_ALLOWED` (approval applies only to the current exact SHA-256). Remote repository packages require manifest signatures and never fall back to local unsigned treatment. In development mode, if an installed package conflicts with a same-named source module, the package is saved but not enabled. Move the conflicting package out or exclude the source module, then restart.
- **Market status codes**: `NOT_INSTALLED` (a compatible installable version exists), `INSTALLED` (installed with no higher compatible version), `UPDATE_AVAILABLE` (a higher compatible version exists), `INCOMPATIBLE` (no version supports the current SDK), `UNAVAILABLE` (no installable artifact, or compatibility lookup is incomplete).

## Transactional replacement and lifecycle policies

Installation and updates use atomic transactions: freeze candidate bytes → validate descriptor/signature/dependencies → stop the old generation accepting new requests and wait for it to drain → atomically replace the artifact and provenance → activate according to policy (fully roll back the old generation on failure).

| `pixiv.lifecycle-policy` | Install / update | Enable / disable | Suitable plugins |
| --- | --- | --- | --- |
| `hot-reload` (default) | Replace and activate immediately in the current process | Direct start / stop | Plugins that can fully withdraw contributions and release background tasks and clients |
| `backend-restart` | Replace and activate immediately in the current process | Save state, then request a backend restart | Plugins that need to rebuild the Spring backend context |
| `process-restart` | Save to disk; activate after a full application exit and restart | Save state, then request a full application restart | Process-level plugins such as `gui-compose`, `gui-swing`, database providers, contract carriers and themes |

Manageable `hot-reload` plugins support 8 actions: `load`, `start`, `quiesce` (stop new admissions and wait for drain), `stop`, `unload`, `remove` (delete the installed package and provenance, retaining business data in `config/`, `state/` and `data/`), `restart` (restart the same generation) and `reload` (create a new generation and classloader). There is no `purge` action.

## Recovery mode, revocations and troubleshooting

- **Enabled state and recovery mode**: switches are stored as `plugins.{pluginId}.enabled` in `config/config.yaml`. If a required plugin or the selected main database provider is missing, damaged, incompatible or fails to start, the application enters recovery mode (retaining login, plugin management and "Repair with another package"). Failure of an ordinary optional plugin isolates only its own capabilities.
- **Revocations (`YANKED` / `REVOKED`)**: `YANKED` blocks new installations and updates; `REVOKED` also blocks subsequent loading and startup. New installations are prohibited without a valid revocation snapshot or beyond the 24-hour grace period. Refreshing revocations does not forcibly terminate full-trust code already running in the current process. Fully exit the application, repair it and then restart.
- **Common problems**:
  - **Page missing after installation**: check `effectiveAfterRestart` in the receipt. A `process-restart` plugin requires a full application exit and restart; refreshing the web page or restarting only the backend is insufficient.
  - **Incompatibility or signature verification failure**: check `plugin.requires` (the required SDK version, not the application version) and dependency versions. Obtain the complete package again from its original repository; do not manually alter `plugin.properties`.
  - **`stop` / `reload` waits a long time**: check `log/latest.log` for queue tasks, HTTP/WebSocket clients or scheduler threads that have not exited.

See [Storage and backup](/en/storage) for plugin file layout, and [Third-party plugin SDK](/en/plugin-development) for plugin development.
