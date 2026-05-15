package top.sywyar.pixivdownload.update;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 在线更新核心服务。
 * <ul>
 *   <li>{@link #checkForUpdate(boolean)} 拉取并解析 manifest，按当前平台挑选 asset。</li>
 *   <li>{@link #downloadInstaller} 走系统代理把 installer 下载到运行目录的临时位置，并校验 SHA-256。</li>
 *   <li>{@link #launchInstallerAndExit} 拉起安装包后退出当前 JVM，把覆盖安装交给 Inno Setup。</li>
 * </ul>
 *
 * <p>清单 JSON 形如：
 * <pre>{@code
 * {
 *   "latestVersion": "1.2.3",
 *   "releaseDate": "2026-05-15",
 *   "releaseNotes": "...",
 *   "releaseNotesUrl": "https://...",
 *   "assets": {
 *     "win-x64-installer": {
 *       "url": "https://example.com/PixivDownload-1.2.3-win-x64-setup.exe",
 *       "sha256": "abc...",
 *       "sizeBytes": 12345678
 *     }
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Service
public class UpdateService {

    /** 当前平台支持的 asset key。Windows installer 是目前唯一受支持的类型。 */
    public static final String ASSET_WIN_X64_INSTALLER = "win-x64-installer";

    /** installer 临时存放目录（运行目录下）。 */
    private static final Path INSTALLER_CACHE_DIR = Path.of("update-cache");
    /** 安装包硬上限，避免清单声明异常导致磁盘塞满。 */
    private static final long MAX_INSTALLER_BYTES = 500L * 1024L * 1024L;
    /** 静默检查的最小间隔：24 小时。 */
    private static final long AUTO_CHECK_MIN_INTERVAL_MS = 24L * 3600L * 1000L;

    private final UpdateConfig updateConfig;
    private final RestTemplate downloadRestTemplate;
    private final AppMessages messages;

    private volatile UpdateCheckResult lastResult;
    private volatile Instant lastSuccessfulCheckAt;

    public UpdateService(UpdateConfig updateConfig,
                         @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                         AppMessages messages) {
        this.updateConfig = updateConfig;
        this.downloadRestTemplate = downloadRestTemplate;
        this.messages = messages;
    }

    /**
     * 拉取清单并判断是否有更新。
     *
     * @param force {@code true} 跳过 24h 缓存窗口；GUI 上的手动按钮使用 true，
     *              启动静默检查使用 false 让缓存生效。
     */
    public synchronized UpdateCheckResult checkForUpdate(boolean force) {
        String currentVersion = AppVersion.getDisplayVersionOrDefault("");

        if (!updateConfig.isEnabled()) {
            log.debug(forLog("update.log.check.disabled"));
            UpdateCheckResult disabled = UpdateCheckResult.builder()
                    .enabled(false)
                    .checkSucceeded(false)
                    .updateAvailable(false)
                    .currentVersion(currentVersion)
                    .checkedAt(Instant.now())
                    .build();
            lastResult = disabled;
            return disabled;
        }

        String manifestUrl = updateConfig.getManifestUrl();
        if (manifestUrl == null || manifestUrl.isBlank()) {
            log.info(forLog("update.log.check.no-url"));
            UpdateCheckResult noUrl = UpdateCheckResult.builder()
                    .enabled(true)
                    .checkSucceeded(false)
                    .updateAvailable(false)
                    .currentVersion(currentVersion)
                    .checkedAt(Instant.now())
                    .error(forLog("update.log.check.no-url"))
                    .build();
            lastResult = noUrl;
            return noUrl;
        }

        if (!force && !shouldHitNetwork()) {
            UpdateCheckResult cached = lastResult;
            if (cached != null) {
                return cached;
            }
        }

        log.info(forLog("update.log.check.starting", manifestUrl));
        try {
            UpdateManifest manifest = downloadRestTemplate.getForObject(URI.create(manifestUrl), UpdateManifest.class);
            if (manifest == null || manifest.getLatestVersion() == null) {
                throw new IllegalStateException(forLog("update.error.manifest.invalid", "empty"));
            }
            return handleManifest(currentVersion, manifest);
        } catch (RestClientException | IllegalStateException e) {
            log.warn(forLog("update.log.check.failed", e.getMessage()));
            UpdateCheckResult failed = UpdateCheckResult.builder()
                    .enabled(true)
                    .checkSucceeded(false)
                    .updateAvailable(false)
                    .currentVersion(currentVersion)
                    .checkedAt(Instant.now())
                    .error(e.getMessage())
                    .build();
            lastResult = failed;
            return failed;
        }
    }

    public UpdateCheckResult lastResult() {
        return lastResult;
    }

    /**
     * 启动时静默自动检查。在最小间隔内不会重复联网；不会抛出异常。
     * Spring 就绪后异步触发，避免阻塞启动流程。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runStartupAutoCheck() {
        if (!updateConfig.isEnabled() || !updateConfig.isAutoCheck()) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                checkForUpdate(false);
            } catch (Exception e) {
                log.warn(forLog("update.log.check.failed", e.getMessage()));
            }
        }, "update-startup-check");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean shouldHitNetwork() {
        Instant last = lastSuccessfulCheckAt;
        if (last == null) {
            return true;
        }
        return last.plus(AUTO_CHECK_MIN_INTERVAL_MS, ChronoUnit.MILLIS).isBefore(Instant.now());
    }

    private UpdateCheckResult handleManifest(String currentVersion, UpdateManifest manifest) {
        String latest = manifest.getLatestVersion().trim();
        String platformKey = currentPlatformAssetKey();
        UpdateManifest.Asset asset = platformKey == null
                ? null
                : pickAsset(manifest.getAssets(), platformKey);

        boolean newer = compareVersions(latest, currentVersion) > 0;
        boolean updateAvailable = newer && asset != null && asset.getUrl() != null && !asset.getUrl().isBlank();

        if (asset == null && newer) {
            log.info(forLog("update.log.check.no-asset", platformKey));
        }
        if (newer) {
            log.info(forLog("update.log.check.available", currentVersion, latest));
        } else {
            log.info(forLog("update.log.check.up-to-date", currentVersion));
        }

        UpdateCheckResult result = UpdateCheckResult.builder()
                .enabled(true)
                .checkSucceeded(true)
                .updateAvailable(updateAvailable)
                .currentVersion(currentVersion)
                .latestVersion(latest)
                .releaseDate(manifest.getReleaseDate())
                .releaseNotes(manifest.getReleaseNotes())
                .releaseNotesUrl(manifest.getReleaseNotesUrl())
                .assetPlatform(platformKey)
                .assetUrl(asset == null ? null : asset.getUrl())
                .assetSha256(asset == null ? null : asset.getSha256())
                .assetSizeBytes(asset == null ? 0L : asset.getSizeBytes())
                .checkedAt(Instant.now())
                .build();
        lastResult = result;
        lastSuccessfulCheckAt = result.getCheckedAt();
        return result;
    }

    private static UpdateManifest.Asset pickAsset(Map<String, UpdateManifest.Asset> assets, String key) {
        if (assets == null || key == null) {
            return null;
        }
        return assets.get(key);
    }

    /**
     * 当前操作系统对应的 asset key。null = 暂不支持。
     */
    public static String currentPlatformAssetKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && (arch.contains("64") || arch.contains("amd64") || arch.contains("x86_64"))) {
            return ASSET_WIN_X64_INSTALLER;
        }
        return null;
    }

    /**
     * 下载已检查到的 installer。调用前应先调用 {@link #checkForUpdate(boolean)} 并确认
     * {@code updateAvailable} 为 true。
     */
    public UpdateDownloadResult downloadInstaller() throws IOException {
        UpdateCheckResult check = lastResult;
        if (check == null || !check.isUpdateAvailable() || check.getAssetUrl() == null) {
            throw new IllegalStateException(forLog("update.error.asset.missing-url"));
        }

        String url = check.getAssetUrl();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException(forLog("update.error.asset.url-not-allowed", url));
        }
        if (check.getAssetSizeBytes() > MAX_INSTALLER_BYTES) {
            throw new IllegalStateException(forLog("update.error.asset.size-too-large",
                    check.getAssetSizeBytes(), MAX_INSTALLER_BYTES));
        }

        Files.createDirectories(INSTALLER_CACHE_DIR);
        Path target = INSTALLER_CACHE_DIR.resolve(buildInstallerFileName(url, check.getLatestVersion()));
        Path tmp = INSTALLER_CACHE_DIR.resolve(target.getFileName() + ".part");

        log.info(forLog("update.log.download.starting", url));

        ResponseEntity<byte[]> response;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "PixivDownload-Updater");
            response = downloadRestTemplate.exchange(
                    URI.create(url),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    byte[].class);
        } catch (RestClientException e) {
            throw new IOException(forLog("update.log.download.failed", e.getMessage()), e);
        }

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new IOException(forLog("update.log.download.failed",
                    "HTTP " + response.getStatusCode().value()));
        }

        byte[] body = response.getBody();
        if (body.length > MAX_INSTALLER_BYTES) {
            throw new IOException(forLog("update.error.asset.size-too-large", body.length, MAX_INSTALLER_BYTES));
        }

        Files.write(tmp, body);

        String expected = check.getAssetSha256();
        String actual = sha256(tmp);
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tmp);
            throw new IOException(forLog("update.log.download.checksum-mismatch", expected, actual));
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        log.info(forLog("update.log.download.completed", target.toAbsolutePath(), body.length));

        return UpdateDownloadResult.builder()
                .installerPath(target.toAbsolutePath().toString())
                .sizeBytes(body.length)
                .sha256(actual)
                .version(check.getLatestVersion())
                .build();
    }

    /**
     * 启动 installer 并请求异步退出当前 JVM。
     * Inno Setup 安装包带有 UAC manifest，会请求管理员权限，
     * 当前进程退出后 installer 可以覆盖已安装文件。
     */
    public void launchInstallerAndExit(String installerPath) throws IOException {
        Path installer = Path.of(installerPath).toAbsolutePath();
        if (!Files.isRegularFile(installer)) {
            throw new IOException(forLog("update.error.installer.not-found", installer));
        }

        String platformKey = currentPlatformAssetKey();
        if (!ASSET_WIN_X64_INSTALLER.equals(platformKey)) {
            throw new IOException(forLog("update.error.installer.platform-unsupported"));
        }

        log.info(forLog("update.log.install.launching", installer));
        try {
            new ProcessBuilder(installer.toString())
                    .directory(installer.getParent().toFile())
                    .inheritIO()
                    .start();
        } catch (IOException e) {
            log.warn(forLog("update.log.install.launch-failed", e.getMessage()));
            throw e;
        }

        Thread exitThread = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "update-exit");
        exitThread.setDaemon(true);
        exitThread.start();
    }

    private static String buildInstallerFileName(String url, String version) {
        String guess = url;
        int slash = guess.lastIndexOf('/');
        if (slash >= 0 && slash < guess.length() - 1) {
            guess = guess.substring(slash + 1);
        }
        int query = guess.indexOf('?');
        if (query >= 0) {
            guess = guess.substring(0, query);
        }
        // 防御性兜底：清单 URL 路径异常时仍能落地一个可用文件名
        if (guess.isBlank()) {
            return "PixivDownload-" + (version == null ? "update" : version) + "-setup.exe";
        }
        return guess;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * 简易版本比较：按 . 拆分、按段数字比较，非数字段当作 -1。
     * 返回 &gt;0 表示 left 较新，&lt;0 表示 left 较旧，0 表示一致或两侧为空。
     */
    static int compareVersions(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null || right.isBlank() ? 0 : -1;
        }
        if (right == null || right.isBlank()) {
            return 1;
        }
        String[] l = stripBuildSuffix(left).split("\\.");
        String[] r = stripBuildSuffix(right).split("\\.");
        int len = Math.max(l.length, r.length);
        for (int i = 0; i < len; i++) {
            int li = parseSegment(i < l.length ? l[i] : "0");
            int ri = parseSegment(i < r.length ? r[i] : "0");
            if (li != ri) {
                return Integer.compare(li, ri);
            }
        }
        return 0;
    }

    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            // 类似 "1.0-SNAPSHOT" 中的 "0-SNAPSHOT"：尽量取出前导数字部分
            StringBuilder digits = new StringBuilder();
            for (char c : segment.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            if (digits.length() == 0) {
                return -1;
            }
            return Integer.parseInt(digits.toString());
        }
    }

    private static String stripBuildSuffix(String version) {
        int dash = version.indexOf('-');
        if (dash > 0) {
            return version.substring(0, dash);
        }
        int plus = version.indexOf('+');
        if (plus > 0) {
            return version.substring(0, plus);
        }
        return version;
    }

    private String forLog(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
