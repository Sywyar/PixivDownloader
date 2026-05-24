package top.sywyar.pixivdownload.update;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.common.SemanticVersion;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /**
     * 用于解析 manifest 文本。GitHub release 资产以 {@code application/octet-stream} 返回，
     * Spring 默认的 Jackson HttpMessageConverter 不接受该 content-type，所以手动把响应体
     * 拿成 {@link String} 后再反序列化。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final UpdateConfig updateConfig;
    private final RestTemplate downloadRestTemplate;
    private final AppMessages messages;

    /**
     * 下载安装包时的实时进度；null 表示当前没有进行中的下载。
     * done=true 时 installerPath 携带落盘后的绝对路径，供 GUI 直接传给安装步骤。
     */
    public record DownloadProgress(long received, long total, boolean done, boolean failed, String error, String installerPath) {}

    private volatile UpdateCheckResult lastResult;
    private volatile Instant lastSuccessfulCheckAt;
    private volatile DownloadProgress currentDownloadProgress;
    private volatile boolean downloadInProgress;

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
            UpdateManifest manifest = fetchManifest(manifestUrl);
            if (manifest == null || manifest.getLatestVersion() == null) {
                throw new IllegalStateException(forLog("update.error.manifest.invalid", "empty"));
            }
            UpdateCheckResult officialResult = handleManifest(currentVersion, manifest, false);
            UpdateCheckResult nightlyAlternative = null;

            if (updateConfig.resolveCheckNightly()) {
                String nightlyUrl = updateConfig.getNightlyManifestUrl();
                if (nightlyUrl != null && !nightlyUrl.isBlank()) {
                    try {
                        UpdateManifest nightlyManifest = fetchManifest(nightlyUrl);
                        if (nightlyManifest != null && nightlyManifest.getLatestVersion() != null) {
                            UpdateCheckResult nightlyResult = handleManifest(currentVersion, nightlyManifest, true);
                            boolean hasUpdate = nightlyResult.isUpdateAvailable();
                            boolean hasNotes = nightlyResult.getReleaseNotes() != null
                                    && !nightlyResult.getReleaseNotes().isBlank();
                            // 每夜版严格新于最新正式版时才作为替代选项
                            boolean newerThanOfficial = compareVersions(
                                    nightlyResult.getLatestVersion(),
                                    officialResult.getLatestVersion()) > 0;
                            if (hasUpdate && hasNotes && newerThanOfficial) {
                                nightlyAlternative = nightlyResult;
                                log.info(forLog("update.log.check.nightly-available",
                                        currentVersion, nightlyResult.getLatestVersion()));
                            } else if (hasUpdate && !hasNotes) {
                                log.info(forLog("update.log.check.nightly-empty-notes",
                                        nightlyResult.getLatestVersion()));
                            }
                        }
                    } catch (RestClientException | IOException | IllegalStateException e) {
                        log.warn(forLog("update.log.check.nightly-failed", e.getMessage()));
                    }
                }
            }

            UpdateCheckResult combined = officialResult;
            if (nightlyAlternative != null) {
                combined = officialResult.toBuilder().nightlyAlternative(nightlyAlternative).build();
            }

            lastResult = combined;
            lastSuccessfulCheckAt = combined.getCheckedAt();
            return combined;
        } catch (RestClientException | IOException | IllegalStateException e) {
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

    /**
     * 拉取 manifest 字节并手动用 Jackson 解析。
     * <p>必须请求 {@code byte[]} 而不是 {@code String}：GitHub release 资产以
     * {@code application/octet-stream}（无 charset）返回，Spring 默认的
     * {@code StringHttpMessageConverter} 会按 ISO-8859-1 解码，把 UTF-8 的中文
     * （如发布说明里的"修复"、"新增"）变成乱码。Jackson 直接对字节流按 UTF-8
     * 解析即可绕过这一层错误的字符集协商。
     */
    private UpdateManifest fetchManifest(String manifestUrl) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.set(HttpHeaders.USER_AGENT, AppInfo.dashedUserAgent("Updater"));

        ResponseEntity<byte[]> response = downloadRestTemplate.exchange(
                URI.create(manifestUrl),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new IOException(forLog("update.error.manifest.invalid",
                    "HTTP " + response.getStatusCode().value()));
        }

        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IOException(forLog("update.error.manifest.invalid", "empty body"));
        }
        return MAPPER.readValue(body, UpdateManifest.class);
    }

    public UpdateCheckResult lastResult() {
        return lastResult;
    }

    public DownloadProgress getDownloadProgress() {
        return currentDownloadProgress;
    }

    /**
     * 在后台线程异步启动安装包下载，立即返回。
     * 进度通过 {@link #currentDownloadProgress} 暴露；GUI 轮询 {@code /api/gui/update/download/progress}。
     *
     * @param nightlyChannel {@code true} 表示下载每夜版替代选项；{@code false} 表示下载正式版
     * @throws IllegalStateException 若已有下载正在进行，或无可用的更新资产
     */
    public synchronized void startDownloadAsync(boolean nightlyChannel) {
        if (downloadInProgress) {
            throw new IllegalStateException("Download already in progress");
        }
        UpdateCheckResult selected = selectChannel(nightlyChannel);
        downloadInProgress = true;
        long declaredSize = selected.getAssetSizeBytes();
        currentDownloadProgress = new DownloadProgress(0, declaredSize, false, false, null, null);

        Thread worker = new Thread(() -> {
            try {
                downloadInstaller(nightlyChannel);
            } catch (Exception e) {
                DownloadProgress cur = currentDownloadProgress;
                if (cur == null || (!cur.done() && !cur.failed())) {
                    currentDownloadProgress = new DownloadProgress(
                            cur != null ? cur.received() : 0, declaredSize, false, true, e.getMessage(), null);
                }
            } finally {
                downloadInProgress = false;
            }
        }, "update-download");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 根据 channel 从 {@link #lastResult} 中挑选可下载的 {@link UpdateCheckResult}。
     * 每夜版 channel 取 {@code nightlyAlternative}；正式版 channel 取 {@code lastResult} 本体。
     */
    private UpdateCheckResult selectChannel(boolean nightlyChannel) {
        UpdateCheckResult check = lastResult;
        if (check == null) {
            throw new IllegalStateException(forLog("update.error.asset.missing-url"));
        }
        UpdateCheckResult selected = nightlyChannel ? check.getNightlyAlternative() : check;
        if (selected == null
                || !selected.isUpdateAvailable()
                || selected.getAssetUrl() == null
                || selected.getAssetUrl().isBlank()) {
            throw new IllegalStateException(forLog("update.error.asset.missing-url"));
        }
        return selected;
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

    private UpdateCheckResult handleManifest(String currentVersion, UpdateManifest manifest, boolean nightly) {
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

        return UpdateCheckResult.builder()
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
                .nightly(nightly)
                .build();
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
     * <p>下载过程中实时更新 {@link #currentDownloadProgress}，供 GUI 轮询展示进度。
     *
     * @param nightlyChannel {@code true} 表示下载每夜版替代选项；{@code false} 表示下载正式版
     */
    public UpdateDownloadResult downloadInstaller(boolean nightlyChannel) throws IOException {
        UpdateCheckResult check = selectChannel(nightlyChannel);

        String url = check.getAssetUrl();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException(forLog("update.error.asset.url-not-allowed", url));
        }
        long declaredSize = check.getAssetSizeBytes();
        if (declaredSize > MAX_INSTALLER_BYTES) {
            throw new IllegalStateException(forLog("update.error.asset.size-too-large",
                    declaredSize, MAX_INSTALLER_BYTES));
        }

        Files.createDirectories(INSTALLER_CACHE_DIR);
        Path target = INSTALLER_CACHE_DIR.resolve(buildInstallerFileName(url, check.getLatestVersion()));
        Path tmp = INSTALLER_CACHE_DIR.resolve(target.getFileName() + ".part");

        log.info(forLog("update.log.download.starting", url));
        currentDownloadProgress = new DownloadProgress(0, declaredSize, false, false, null, null);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.set(HttpHeaders.USER_AGENT, AppInfo.dashedUserAgent("Updater"));

        try {
            downloadRestTemplate.execute(URI.create(url), HttpMethod.GET,
                    req -> req.getHeaders().putAll(requestHeaders),
                    response -> {
                        long contentLength = response.getHeaders().getContentLength();
                        long total = contentLength > 0 ? contentLength : declaredSize;
                        try (InputStream in = response.getBody();
                             OutputStream out = Files.newOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            long received = 0;
                            int read;
                            while ((read = in.read(buf)) != -1) {
                                if (received + read > MAX_INSTALLER_BYTES) {
                                    throw new IOException(forLog("update.error.asset.size-too-large",
                                            received + read, MAX_INSTALLER_BYTES));
                                }
                                out.write(buf, 0, read);
                                received += read;
                                currentDownloadProgress = new DownloadProgress(received, total, false, false, null, null);
                            }
                        }
                        return null;
                    });
        } catch (RestClientException e) {
            Files.deleteIfExists(tmp);
            Throwable cause = e.getCause();
            String errMsg = cause != null ? cause.getMessage() : e.getMessage();
            currentDownloadProgress = new DownloadProgress(
                    currentDownloadProgress != null ? currentDownloadProgress.received() : 0,
                    declaredSize, false, true, errMsg, null);
            throw new IOException(forLog("update.log.download.failed", errMsg), e);
        }

        String expected = check.getAssetSha256();
        long finalSize = Files.size(tmp);
        String actual = sha256(tmp);
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tmp);
            String msg = forLog("update.log.download.checksum-mismatch", expected, actual);
            currentDownloadProgress = new DownloadProgress(finalSize, declaredSize, false, true, msg, null);
            throw new IOException(msg);
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        currentDownloadProgress = new DownloadProgress(finalSize, finalSize, true, false, null, target.toAbsolutePath().toString());
        log.info(forLog("update.log.download.completed", target.toAbsolutePath(), finalSize));

        return UpdateDownloadResult.builder()
                .installerPath(target.toAbsolutePath().toString())
                .sizeBytes(finalSize)
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
            return AppInfo.LEGACY_ARTIFACT_NAME + "-" + (version == null ? "update" : version) + "-setup.exe";
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
     * 版本比较，支持本项目标准的 {@code n.n.n-xx.n} 预发布后缀
     * （见 {@link SemanticVersion}）。返回 &gt;0 表示 left 较新，
     * &lt;0 表示 left 较旧，0 表示一致或两侧为空。
     */
    static int compareVersions(String left, String right) {
        return SemanticVersion.compare(left, right);
    }

    private String forLog(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
