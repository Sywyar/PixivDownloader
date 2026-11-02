# Installation and startup

Choose a distribution for your environment from [Releases](https://github.com/Sywyar/PixivDownloader/releases). In the filenames below, `*` or `x.x.x` stands for the version. Replace `APP.jar` in commands with the actual application JAR filename, and run commands from the fully extracted directory.

| Use case | Distribution | Additional requirements |
| --- | --- | --- |
| Windows x64 desktop | `*-win-x64-setup.exe` | Bundled JRE; install FFmpeg if needed |
| Run Java on Windows / Linux / macOS | `*-java.zip` | Java 17+; install FFmpeg if needed |
| Preinstall optional official plugins for databases, import, CAPTCHA and more | `*-full-offline.zip` | Java 17+; install FFmpeg if needed |
| Persistent Docker service | Build with the repository's `Dockerfile` and a complete Java distribution directory | Docker Engine, Compose and writable mounted directories |

The standard Java package and Windows installer include the default official plugin set. The full-offline package also includes on-demand official plugins such as `database-postgresql` and `pixiv-batch-downloader-import`. Neither Java package includes a JRE, FFmpeg or the third-party `douyin` plugin. Full-offline means that optional official plugins are preinstalled; Pixiv, online AI and online TTS still require network access.

## Method 1: standard Java package / full-offline package

1. Install Java 17 or later. On Windows, download it from [Adoptium](https://adoptium.net/); on macOS, use `brew install openjdk@17`; on Debian/Ubuntu, use `sudo apt install openjdk-17-jdk`; on Fedora, use `sudo dnf install java-17-openjdk`. Check the version:

   ```bash
   java -version
   ```

2. Download `PixivDownload-*-java.zip` or `PixivDownload-*-full-offline.zip` and **extract the entire archive**. Retain the startup scripts and `plugins/` beside the application. At startup, the app loads external plugins from `plugins/` in its working directory.

   ?> A standalone `PixivDownload-*.jar` is a core-shell build input without external plugins, not a distribution for ordinary users. Running it directly does not preinstall the download workbench or database plugins.

3. Start the app: run `run.bat` on Windows, run `sh run.sh` from the extracted directory on Linux / macOS, or start it manually:

   ```text
   java -Dfile.encoding=UTF-8 -jar APP.jar
   ```

   Startup scripts already include `-Dfile.encoding=UTF-8`. Keep this argument when starting manually to avoid garbled output in Chinese Windows consoles.

4. Follow [first-time setup](/en/first-setup) to create an administrator account and configure the proxy.

### Headless server (`--no-gui`)

A server has no desktop onboarding, and `setup.html` allows local access only. Starting with `--no-gui` before initialization stops with exit code `78`. Initialize through the CLI in the same directory before the first startup:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

For noninteractive initialization in automation scripts, supply parameters explicitly. Replace the password and proxy with actual values; a command-line password remains in shell history or process listings, so prefer interactive mode for routine use:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

Use `--proxy-enabled=false` if no proxy is needed. Example of running in the background on Linux / macOS:

```bash
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

The backend proxy applies to requests using host or task routing, including Pixiv downloads, online updates, FFmpeg downloads and some online TTS requests. Direct browser requests, SMTP and database connections use their own routes; see [network access and third-party services](/en/network-access).

## Method 2: Windows installer

1. Run `PixivDownload-x.x.x-win-x64-setup.exe`, then choose an installation language (Simplified Chinese, Traditional Chinese, English, Japanese or Korean) and directory.
2. Select Download and install FFmpeg if needed. Ordinary static images do not need FFmpeg; you can also download it from the desktop Status page after installation.
3. Start the app after installation, or use its Start menu entry or desktop shortcut.

If the installer detects a running `PixivDownload.exe`, it asks you to close it and retry. Running the installer again offers repair, component changes or uninstall. The installer, installed app and portable launcher request administrator privileges by default. When the host actually runs elevated, `host-process-full-trust` plugins inherit the same privileges, and plugin management continuously displays that state.

## Method 3: Docker deployment

The repository's `Dockerfile` builds an image from a signed, complete Java distribution directory. The image includes Java 17, FFmpeg and curl.

1. Extract the complete `*-java.zip` or `*-full-offline.zip` into the repository's `build/dist/default-downloader/`. Confirm that this directory directly contains the application JAR, `plugins/`, `plugins-manifest.json` and `SHA256SUMS`, then build the image:

   ```bash
   docker compose build
   ```

2. The container runs as UID / GID `10001`. For a new Linux deployment, create writable mounted directories in the directory containing `docker-compose.yml`:

   ```bash
   sudo install -d -o 10001 -g 10001 config state data pixiv-download log
   ```

3. Complete first-time initialization, then start the persistent container:

   ```bash
   docker compose run --rm app --setup --mode=solo
   docker compose up -d
   docker compose logs -f app
   ```

!> Running `docker compose up -d` before completing `--setup` causes repeated restarts with exit code `78`.

By default, the port binds only to `127.0.0.1:6999` on the host. Open `http://127.0.0.1:6999/` in a local browser; use an SSH tunnel or reverse proxy for remote access. Run `docker compose down` to stop the service. Do not add `-v`, which deletes volumes, if you want to retain data.

### Container proxy and persistent directories

Inside the container, `127.0.0.1` refers to the container itself. `docker-compose.yml` maps `host.docker.internal` to the host gateway. To reuse the host's proxy, set the proxy host to `host.docker.internal` in `--setup` or `config/config.yaml`, and make sure the host proxy accepts connections from the container subnet:

```bash
docker compose run --rm app --setup --mode=solo --proxy-host=host.docker.internal --proxy-port=7890
```

After editing the mounted `config/config.yaml`, run `docker compose restart app` to apply it. If you change `server.port`, update the port mapping and health-check address in `docker-compose.yml` too.

| Host mount or volume | Persistent content |
| --- | --- |
| `./config/` | `config.yaml`, plugin business configuration and encrypted credentials |
| `./state/` | Login state, `setup_config.json`, queue checkpoints, instance locks and the SQLite native-library extraction directory |
| `./data/` | SQLite databases, collection icons, thumbnail and TTS caches, backfill state and reference voices |
| `./pixiv-download/` | Downloaded illustrations, manga, animations, novels and temporary `_archives` |
| `./log/` | Runtime logs |
| Named volume `plugins` | Installed plugins and provenance |

`state/` and `state/instance/` must be writable and allow loading the SQLite native library. The container root filesystem remains read-only, and `/tmp` retains `noexec,nosuid,nodev`. Updating the image does not automatically overwrite an existing `plugins` volume. After recreating the container, check plugin versions and compatibility in plugin management.

## FFmpeg and userscripts (optional)

- **FFmpeg**: converting Ugoira to WebP / GIF / APNG / MP4 and processing media requires FFmpeg; MP4 requires an H.264 encoder. Select it during Windows installation, or click Download FFmpeg on the desktop GUI's Status page to fetch signature-verified Windows x64, Linux x64/arm64 or macOS x64/arm64 assets from the project's `ffmpeg-stable` Release. You can also install it from the [FFmpeg website](https://ffmpeg.org/download.html), add it to the system `PATH`, and check it with `ffmpeg -version`.
- **Userscripts**: you can batch-download through `pixiv-batch.html` without scripts. To operate directly on Pixiv pages, start the backend and install through Userscripts on the download page, or install standalone `.user.js` files from the source repository. Remote deployments require setting both the backend address and `@connect` in the script; see [userscripts](/en/userscripts).

## Verify the installation and understand plugins

After [first-time setup](/en/first-setup), sign in at `http://localhost:6999/` and follow [your first download](/en/first-download) to download one work. Confirm that files are created on disk and that the gallery can open the work. `GET /actuator/health` returning `{"status":"UP"}` means only that the HTTP service has started; it does not prove that plugins, the database, proxy and Pixiv cookie are all ready.

Common entries:

- `http://localhost:6999/`: redirects to the active download page by default
- `http://localhost:6999/setup.html`: local first-time setup page; redirects automatically after initialization
- `http://localhost:6999/pixiv-batch.html`: download workbench, provided by the default-installed `download-workbench`
- `http://localhost:6999/pixiv-gallery.html`: artwork gallery, provided by the default-installed `gallery`
- `http://localhost:6999/monitor.html`: download monitoring page

Default-installed official plugins include `download-workbench`, `database-sqlite`, `gui-compose` (default desktop interface), `gui-swing` (fallback desktop interface), `gallery`, `gallery-tools`, `novel`, `notification`, `push`, `mail`, `tts`, `ai`, `posthog` and `multi-mode-decision-survey`. Plugins such as `database-postgresql`, `pixiv-batch-downloader-import`, `page-editor`, `captcha` and `translation` are available on demand from the plugin market or full-offline package. If the selected main database plugin is unavailable, the app enters recovery mode and exposes only login, plugin management and repair entries. Disabling or removing an optional plugin withdraws only its pages and capabilities; see [plugin management](/en/plugin-management) and [storage and backup](/en/storage).
