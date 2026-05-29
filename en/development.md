# Development Guide

## Environment Setup

### Required Software

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 17 | Compilation and runtime |
| **Maven** | 3.9+ | Or use the bundled `mvnw` / `mvnw.cmd` |
| **Git** | Any | Version control |
| **PowerShell** | 5.1+ | Windows packaging scripts (installer builds only) |
| **Inno Setup 6** | 6.x | Windows installer packaging (optional) |

### Optional Tools

| Tool | Notes |
|------|-------|
| **IntelliJ IDEA** | Recommended IDE |
| **VS Code** | Frontend resource editing |
| **Docker** | Containerized deployment |

---

## Project Structure

```
PixivDownloader/
├── src/main/java/top/sywyar/PixivDownloader/
│   ├── author/          # Author metadata persistence
│   ├── cli/             # Startup argument validation and CLI admin commands (--setup / --change-password / --reset-password / --help)
│   ├── collection/      # Collection management
│   ├── common/          # Common utilities
│   ├── config/          # App configuration generation & binding
│   ├── download/        # Core download logic
│   │   └── db/          # SQLite database access (MyBatis)
│   ├── ffmpeg/          # FFmpeg discovery & installation
│   ├── gallery/         # Gallery API
│   ├── gui/             # Swing GUI desktop manager
│   ├── i18n/            # Internationalization
│   ├── imageclassifier/ # Image classification tool
│   ├── logback/         # Custom log formatting
│   ├── maintenance/     # Maintenance task framework
│   ├── migration/       # JSON → SQLite migration
│   ├── novel/           # Novel download pipeline
│   ├── onboarding/      # GUI setup wizard
│   ├── quota/           # Quota & rate limiting
│   ├── scripts/         # Userscript distribution
│   ├── series/          # Series metadata
│   ├── setup/           # Initial setup & authentication
│   │   └── guest/       # Guest invite system
│   ├── tools/           # CLI tools
│   └── update/          # Online update
├── src/main/resources/
│   ├── static/          # Web frontend resources (110+ files)
│   ├── i18n/            # i18n resource files
│   └── application.properties
├── scripts/             # Build & packaging scripts
├── packaging/windows/inno/  # Inno Setup installer config
├── config/              # Default configuration
├── collection_icons/    # Collection icons
└── pom.xml              # Maven build configuration
```

---

## Fork & Branching

1. **Fork the repo**: Visit [GitHub](https://github.com/Sywyar/PixivDownloader), click Fork
2. **Clone your fork**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
   cd PixivDownloader
   ```
3. **Add upstream**:
   ```bash
   git remote add upstream https://github.com/Sywyar/PixivDownloader.git
   git fetch upstream
   ```
4. **Create a feature branch**:
   ```bash
   git checkout -b feat/your-change upstream/master
   ```

---

## Local Development

### Build

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd package -DskipTests
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw package -DskipTests
```

### Run

```bash
java -Dfile.encoding=UTF-8 -jar target/PixivDownload-*.jar
```

### Run Tests

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd test
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw test

# Run a single test class
./mvnw test -Dtest=PixivDownloadApplicationTests
```

> [!IMPORTANT]
> Always use `-Dfile.encoding=UTF-8`, otherwise tests may produce garbled output on Chinese Windows.

### Userscript Development Notes

The userscript install cards on `pixiv-batch.html` read the built-in script list via `/api/scripts`. Scripts are assembled from two sources:
- Standalone `*.user.js` files in the repo root
- `scripts/build-userscript-bundle.ps1` generates `build/generated-userscripts/Pixiv All-in-One.user.js`

`pom.xml` copies both to `target/classes/static/userscripts` during the `generate-resources` phase. Therefore, after modifying userscripts, you must run at least one Maven lifecycle (recommend `package`) — do not rely solely on the IDE to run directly.

---

## Local Windows Build

> [!NOTE]
> The release process no longer publishes portable zip packages. These commands are for local debugging or personal use only.

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# Generate portable version only (with PixivDownload.exe), skip installer
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipInstaller

# Generate full Windows artifacts
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local

# Run tests before packaging
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -RunTests
```

### Common Parameters

| Parameter | Description |
|-----------|-------------|
| `-Version` | Version number (e.g., `0.0.1-local`) |
| `-SkipPortable` | Skip online portable version |
| `-SkipOfflinePortable` | Skip offline portable with bundled FFmpeg |
| `-SkipInstaller` | Skip Inno Setup installer, keep portable artifacts only |
| `-RedownloadFfmpeg` | Re-download FFmpeg payload |
| `-RunTests` | Run tests before packaging |
| `-PrebuiltJar` | Use pre-built JAR (skip Maven build) |

### Build Pipeline

1. Generate `Pixiv All-in-One.user.js` bundle
2. Maven `package` (or use pre-built JAR)
3. `jlink` generate trimmed JRE (21 modules)
4. `jpackage` generate app-image (with `PixivDownload.exe`)
5. Online portable zip
6. Offline portable zip (with bundled FFmpeg)
7. Inno Setup installer

Output goes to `build/out/`.

### Inno Setup Installation

Building installers requires [Inno Setup 6](https://jrsoftware.org/isdl.php). The script checks the default `Inno Setup 6\ISCC.exe` install directory first, then `PATH`.

---

## Commit & PR Workflow

### Pre-Commit Checklist

1. **Sync upstream**:
   ```bash
   git fetch upstream
   git rebase upstream/master
   ```

2. **Run tests**:
   ```bash
   ./mvnw test
   ```

3. **If userscripts or static resources are involved**, run `package` and verify script list and install links at `http://localhost:6999/pixiv-batch.html`.

4. **Review changes**:
   ```bash
   git diff --staged
   ```

### Commit Conventions

- Use clear commit messages
- **Do not** include `Co-Authored-By` lines in commit messages
- Do not commit build artifacts (`target/`, `build/`, etc.)

### Submitting a PR

1. Push the branch to your fork
2. Open a Pull Request against upstream `main` branch on GitHub
3. PR description should include:
   - Motivation for the change
   - Key modifications
   - Verification steps
   - Screenshots or key output if UI/packaging is involved

---

## CI/CD

### Release Workflow

Pushing a `v*` tag (e.g., `v1.8.3`) triggers automatic build and release:

1. **build-jar** (Ubuntu): Maven package JAR + userscripts
2. **build-windows-installer** (Windows): Build installer
3. **release** (Ubuntu): Aggregate artifacts, generate update manifest, create GitHub Release

### Manual Draft Release

Trigger `create-draft-release` via `workflow_dispatch` in GitHub Actions, specifying the tag name to create a draft Release.

### GitHub Pages

Pushing a `v*` tag automatically deploys `src/main/resources/static/` to GitHub Pages for static preview.

---

## Coding Standards

### General

- Follow existing Spring Boot patterns in the project
- Prefer constructor injection
- Use explicit DTO classes for public HTTP APIs
- Centralize exception handling via `@RestControllerAdvice` / `@ExceptionHandler`
- Use Lombok judiciously; plain Java preferred when it's clearer

### Key Invariants

When modifying backend behavior, the following must be preserved:

- **Dynamic URL construction**: scheme derived from `server.ssl.enabled`, hostname from `ssl.domain`
- `DownloadService.validatePixivUrl()` must reject non-Pixiv URLs
- Solo vs. Multi mode behavior must be differentiated
- Rate limiting applies only to Multi mode guests, not Solo users or logged-in admins
- Login brute-force protection is the exception — applies to `/api/auth/login` in all modes
- Post-download bookmarking is best-effort and must not cause completed downloads to fail
- Ugoira conversion depends on ffmpeg in `PATH`

### Web Page Standards

- All user-facing strings go through the i18n pipeline
- All new pages must support dark mode (use CSS variable approach)
- HTML, CSS, JavaScript in separate files
- Reuse existing CSS variables (`--bg`, `--surface`, `--line`, `--text`, `--muted`, `--brand`, etc.)

### Userscript Standards

See `CLAUDE.md` for detailed specifications (UserScript header format, shared i18n runtime, PromptGuard deduplication, panel/FAB/menu conventions, etc.).

### Database Schema

Every time a Mapper's `CREATE TABLE` statement is modified, `ManagedDatabaseSchema.createSpec()` must be updated synchronously, otherwise the startup drift check will produce false alerts.

---

## Technology Stack

| Technology | Purpose |
|------------|---------|
| Spring Boot 3.5.7 | Backend framework |
| MyBatis 3.0.4 | ORM |
| SQLite 3.47.1 | Local database (WAL mode) |
| Apache HttpClient5 | HTTP client |
| Lombok | Reduce boilerplate |
| FlatLaf 3.5.4 | Swing L&F |
| Spring Security Crypto | Password security |
| Maven Wrapper | Build tool |
| jlink / jpackage | JRE trimming & native packaging |
| Inno Setup 6 | Windows installer |
| Bootstrap + Chart.js | Web frontend |
