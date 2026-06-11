package top.sywyar.pixivdownload.quota;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class UserQuotaService {

    private final MultiModeConfig config;
    private final DownloadConfig downloadConfig;
    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;
    private final TaskExecutor archiveTaskExecutor;

    public UserQuotaService(MultiModeConfig config,
                            DownloadConfig downloadConfig,
                            PixivDatabase pixivDatabase,
                            AppMessages messages,
                            @Qualifier("archiveTaskExecutor") TaskExecutor archiveTaskExecutor) {
        this.config = config;
        this.downloadConfig = downloadConfig;
        this.pixivDatabase = pixivDatabase;
        this.messages = messages;
        this.archiveTaskExecutor = archiveTaskExecutor;
    }

    /** UUID → 用户配额信息 */
    private final ConcurrentHashMap<String, UserQuota> quotaMap = new ConcurrentHashMap<>();
    /** token → 压缩包信息 */
    private final ConcurrentHashMap<String, ArchiveEntry> archiveMap = new ConcurrentHashMap<>();

    // ---- 配额管理 ----------------------------------------------------------------

    /**
     * 检查并预留配额。若允许，按作品权重扣减配额；否则返回拒绝结果。
     * 权重计算：若 limitImage > 0 且 imageCount > limitImage，则权重 = ceil(imageCount / limitImage)，否则为 1。
     */
    public QuotaCheckResult checkAndReserve(String uuid, int imageCount) {
        UserQuota quota = quotaMap.computeIfAbsent(uuid, UserQuota::new);
        MultiModeConfig.Quota cfg = config.getQuota();

        synchronized (quota) {
            long now = System.currentTimeMillis();
            long periodMs = (long) cfg.getResetPeriodHours() * 3_600_000L;

            // 周期过期则重置
            if (now - quota.getPeriodStart() >= periodMs) {
                quota.reset();
            }

            int used = quota.getArtworksUsed().get();
            int max = cfg.getMaxArtworks();
            long resetSeconds = Math.max(0, (quota.getPeriodStart() + periodMs - now) / 1000);
            int weight = calculateArtworkWeight(imageCount);

            if (used + weight > max) {
                return new QuotaCheckResult(false, used, max, resetSeconds);
            }

            quota.getArtworksUsed().addAndGet(weight);
            return new QuotaCheckResult(true, used + weight, max, resetSeconds);
        }
    }

    /**
     * 计算作品配额权重。
     * 当 limitImage <= 0 或 imageCount <= limitImage 时，权重为 1；
     * 否则为 ceil(imageCount / limitImage)。
     */
    private int calculateArtworkWeight(int imageCount) {
        int limitImage = config.getQuota().getLimitImage();
        if (limitImage <= 0 || imageCount <= limitImage) {
            return 1;
        }
        return (int) Math.ceil((double) imageCount / limitImage);
    }

    /**
     * 记录已下载完成的作品文件夹（用于之后打包）。
     */
    public void recordFolder(String uuid, Path folder) {
        if (uuid == null || folder == null) return;
        UserQuota quota = quotaMap.get(uuid);
        if (quota != null) {
            quota.getDownloadedFolders().add(folder);
        }
    }

    /** 获取用户配额对象（供 ArchiveController 判断是否有文件可打包）。 */
    public UserQuota getQuotaForUser(String uuid) {
        return quotaMap.get(uuid);
    }

    /**
     * 获取指定用户的当前配额状态。
     */
    public QuotaStatusResult getQuotaStatus(String uuid) {
        MultiModeConfig.Quota cfg = config.getQuota();
        UserQuota quota = quotaMap.get(uuid);
        if (quota == null) {
            return new QuotaStatusResult(0, cfg.getMaxArtworks(),
                    (long) cfg.getResetPeriodHours() * 3600L, null);
        }

        long now = System.currentTimeMillis();
        long periodMs = (long) cfg.getResetPeriodHours() * 3_600_000L;
        long resetSeconds = Math.max(0, (quota.getPeriodStart() + periodMs - now) / 1000);

        ArchiveInfo archiveInfo = null;
        String token = quota.getArchiveToken();
        if (token != null) {
            ArchiveEntry entry = archiveMap.get(token);
            if (entry != null && entry.getExpireTime() > now) {
                archiveInfo = new ArchiveInfo(token, entry.getStatus(),
                        (entry.getExpireTime() - now) / 1000);
            }
        }

        return new QuotaStatusResult(quota.getArtworksUsed().get(), cfg.getMaxArtworks(),
                resetSeconds, archiveInfo);
    }

    // ---- 代理请求频率限制 --------------------------------------------------------

    /**
     * 检查并预留代理请求次数。
     * 在 resetPeriodHours 窗口内，同一用户最多发起 maxProxyRequests 次搜索/代理请求。
     * maxProxyRequests <= 0 时不限制，直接返回 true。
     * 返回 true 表示允许；返回 false 表示已达上限。
     */
    public boolean checkAndReserveProxy(String uuid) {
        MultiModeConfig.Quota cfg = config.getQuota();
        if (cfg.getMaxProxyRequests() <= 0) {
            return true;
        }
        UserQuota quota = quotaMap.computeIfAbsent(uuid, UserQuota::new);

        synchronized (quota) {
            long now = System.currentTimeMillis();
            long periodMs = (long) cfg.getResetPeriodHours() * 3_600_000L;

            // 与下载配额共用同一周期：若周期过期则整体重置
            if (now - quota.getPeriodStart() >= periodMs) {
                quota.reset();
            }

            if (quota.getProxyCount().get() >= cfg.getMaxProxyRequests()) {
                return false;
            }
            quota.getProxyCount().incrementAndGet();
            return true;
        }
    }

    // ---- 打包频率限制 ------------------------------------------------------------

    /**
     * 检查并预留打包次数。
     * 在 archiveExpireMinutes 窗口内，同一用户最多触发 maxArtworks 次打包。
     * 返回 true 表示允许；返回 false 表示已达上限。
     */
    public boolean checkAndReservePack(String uuid) {
        UserQuota quota = quotaMap.computeIfAbsent(uuid, UserQuota::new);
        MultiModeConfig.Quota cfg = config.getQuota();

        synchronized (quota) {
            long now = System.currentTimeMillis();
            long windowMs = (long) cfg.getArchiveExpireMinutes() * 60_000L;

            if (now - quota.getPackWindowStart() >= windowMs) {
                quota.resetPackWindow();
            }

            if (quota.getPackCount().get() >= cfg.getMaxArtworks()) {
                return false;
            }
            quota.getPackCount().incrementAndGet();
            return true;
        }
    }

    // ---- 压缩包管理 --------------------------------------------------------------

    /**
     * 为指定用户创建压缩包 token，并在后台异步打包已下载文件。
     */
    public String triggerArchive(String uuid) {
        String token = UUID.randomUUID().toString();
        long expireTime = System.currentTimeMillis()
                + (long) config.getQuota().getArchiveExpireMinutes() * 60_000;
        ArchiveEntry entry = new ArchiveEntry(token, uuid, expireTime);
        archiveMap.put(token, entry);

        UserQuota quota = quotaMap.get(uuid);
        if (quota != null) {
            quota.setArchiveToken(token);
        }

        archiveTaskExecutor.execute(() -> buildArchive(token, uuid));
        return token;
    }

    public String triggerAdminArchive(List<Path> folders) {
        String token = UUID.randomUUID().toString();
        long expireTime = System.currentTimeMillis()
                + (long) config.getQuota().getArchiveExpireMinutes() * 60_000;
        ArchiveEntry entry = new ArchiveEntry(token, null, expireTime);
        entry.setExportType("pack");
        entry.setWorkCount(folders == null ? 0 : folders.size());
        archiveMap.put(token, entry);
        archiveTaskExecutor.execute(() -> buildAdminArchive(token, folders));
        return token;
    }

    /**
     * 管理员按文件清单打包。exportType 标注任务来源（如 artworks / novels），
     * 供任务列表展示；afterReady 仅在打包成功后执行（如导出后删除源文件）。
     */
    public String triggerAdminFileArchive(List<ArchiveItem> items, String exportType, int workCount,
                                          Runnable afterReady) {
        String token = UUID.randomUUID().toString();
        long expireTime = System.currentTimeMillis()
                + (long) config.getQuota().getArchiveExpireMinutes() * 60_000;
        ArchiveEntry entry = new ArchiveEntry(token, null, expireTime);
        entry.setExportType(exportType);
        entry.setWorkCount(workCount);
        archiveMap.put(token, entry);
        archiveTaskExecutor.execute(() -> buildAdminFileArchive(token, items, afterReady));
        return token;
    }

    /** 管理员侧所有未过期的压缩任务（含导出与打包），按创建时间倒序。 */
    public List<ArchiveEntry> listAdminArchives() {
        long now = System.currentTimeMillis();
        return archiveMap.values().stream()
                .filter(e -> e.getUserUuid() == null && e.getExpireTime() > now)
                .sorted(Comparator.comparingLong(ArchiveEntry::getCreatedTime).reversed())
                .toList();
    }

    private void buildArchive(String token, String uuid) {
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;

        entry.setStatus("creating");

        UserQuota quota = quotaMap.get(uuid);
        if (quota == null || quota.getDownloadedFolders().isEmpty()) {
            entry.setStatus("empty");
            log.info(message("archive.log.user.empty", token, uuid));
            return;
        }

        List<Path> folders = new ArrayList<>(quota.getDownloadedFolders());

        try {
            Path archiveDir = Paths.get(downloadConfig.getRootFolder(), "_archives");
            Files.createDirectories(archiveDir);
            Path archivePath = archiveDir.resolve(token + ".zip");

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(archivePath.toFile()),
                            64 * 1024))) {
                zos.setLevel(Deflater.BEST_COMPRESSION);

                for (Path folder : folders) {
                    if (!Files.exists(folder)) continue;
                    String folderName = folder.getFileName().toString();
                    try (var stream = Files.walk(folder)) {
                        stream.filter(Files::isRegularFile).forEach(file -> {
                            try {
                                String entryName = folderName + "/" + file.getFileName();
                                zos.putNextEntry(new ZipEntry(entryName));
                                Files.copy(file, zos);
                                zos.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    }
                }
            }

            entry.setArchivePath(archivePath);
            entry.setStatus("ready");
            log.info(message("archive.log.created", token, archivePath));

            // pack-and-delete 模式：打包后立即删除源文件及下载历史记录
            // never-delete / timed-delete 模式：保留源文件，不删除历史记录
            String pdMode = config.getPostDownloadMode();
            if (!"never-delete".equals(pdMode) && !"timed-delete".equals(pdMode)) {
                for (Path folder : folders) {
                    deleteArtworkFolder(folder);
                }
            }
            quota.getDownloadedFolders().removeAll(folders);

        } catch (Exception e) {
            entry.setStatus("error");
            deletePartialArchive(token);
            log.error(message("archive.log.create.failed", token, uuid), e);
        }
    }

    private void buildAdminArchive(String token, List<Path> folders) {
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;

        entry.setStatus("creating");

        if (folders == null || folders.isEmpty()) {
            entry.setStatus("empty");
            return;
        }

        try {
            Path archiveDir = Paths.get(downloadConfig.getRootFolder(), "_archives");
            Files.createDirectories(archiveDir);
            Path archivePath = archiveDir.resolve(token + ".zip");

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(archivePath.toFile()),
                            64 * 1024))) {
                zos.setLevel(Deflater.BEST_COMPRESSION);

                int processed = 0;
                for (Path folder : folders) {
                    processed++;
                    entry.setProcessedWorks(processed);
                    if (folder == null || !Files.exists(folder)) continue;
                    String folderName = folder.getFileName().toString();
                    try (var stream = Files.walk(folder)) {
                        stream.filter(Files::isRegularFile).forEach(file -> {
                            try {
                                String entryName = folderName + "/" + file.getFileName();
                                zos.putNextEntry(new ZipEntry(entryName));
                                Files.copy(file, zos);
                                zos.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    }
                }
            }

            entry.setArchivePath(archivePath);
            entry.setStatus("ready");
            log.info(message("archive.log.admin.created", token, archivePath, folders.size()));
        } catch (Exception e) {
            entry.setStatus("error");
            deletePartialArchive(token);
            log.error(message("archive.log.admin.create.failed", token), e);
        }
    }

    private void buildAdminFileArchive(String token, List<ArchiveItem> items, Runnable afterReady) {
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;

        entry.setStatus("creating");

        if (items == null || items.isEmpty()) {
            entry.setStatus("empty");
            return;
        }

        try {
            Path archiveDir = Paths.get(downloadConfig.getRootFolder(), "_archives");
            Files.createDirectories(archiveDir);
            Path archivePath = archiveDir.resolve(token + ".zip");
            Set<String> entryNames = new HashSet<>();
            Set<Long> startedWorks = new HashSet<>();
            int written = 0;

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(archivePath.toFile()),
                            64 * 1024))) {
                zos.setLevel(Deflater.BEST_COMPRESSION);

                for (ArchiveItem item : items) {
                    if (item == null) continue;
                    if (item.workId() != null && startedWorks.add(item.workId())) {
                        entry.setProcessedWorks(startedWorks.size());
                    }
                    String entryName = uniqueEntryName(safeZipEntryName(item.entryName()), entryNames);
                    if (entryName == null) continue;
                    try {
                        if (item.bytes() != null) {
                            zos.putNextEntry(new ZipEntry(entryName));
                            zos.write(item.bytes());
                            zos.closeEntry();
                            written++;
                        } else if (item.path() != null && Files.isRegularFile(item.path())) {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(item.path(), zos);
                            zos.closeEntry();
                            written++;
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }

            if (written == 0) {
                Files.deleteIfExists(archivePath);
                entry.setStatus("empty");
                return;
            }

            if (afterReady != null) {
                try {
                    afterReady.run();
                } catch (Exception e) {
                    log.warn(message("archive.log.admin.post-action.failed", token), e);
                }
            }

            entry.setArchivePath(archivePath);
            entry.setFileCount(written);
            entry.setStatus("ready");
            log.info(message("archive.log.admin.file-archive.created", token, archivePath, written));
        } catch (Exception e) {
            entry.setStatus("error");
            deletePartialArchive(token);
            log.error(message("archive.log.admin.create.failed", token), e);
        }
    }

    private String safeZipEntryName(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return null;
        }
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return null;
        }
        String[] parts = normalized.split("/");
        List<String> safeParts = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part == null || part.isBlank() || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            safeParts.add(part);
        }
        return safeParts.isEmpty() ? null : String.join("/", safeParts);
    }

    private String uniqueEntryName(String entryName, Set<String> used) {
        if (entryName == null || used == null) {
            return entryName;
        }
        if (used.add(entryName)) {
            return entryName;
        }
        int slash = entryName.lastIndexOf('/');
        String dir = slash >= 0 ? entryName.substring(0, slash + 1) : "";
        String name = slash >= 0 ? entryName.substring(slash + 1) : entryName;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10_000; i++) {
            String candidate = dir + base + " (" + i + ")" + ext;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public ArchiveEntry getArchive(String token) {
        return archiveMap.get(token);
    }

    public void deleteArchive(String token) {
        ArchiveEntry entry = archiveMap.remove(token);
        if (entry != null && entry.getArchivePath() != null) {
            try {
                Files.deleteIfExists(entry.getArchivePath());
            } catch (Exception e) {
                log.warn(message("archive.log.file.delete.failed", entry.getArchivePath()), e);
            }
        }
    }

    /**
     * 归档构建失败时删除可能已部分写入的 zip。失败的条目永远不会 {@code setArchivePath}，
     * 因此残留文件不会被运行期或过期清理触达，必须在此就地删除，避免留到下次启动清理。
     */
    private void deletePartialArchive(String token) {
        try {
            Path archivePath = Paths.get(downloadConfig.getRootFolder(), "_archives", token + ".zip");
            Files.deleteIfExists(archivePath);
        } catch (Exception e) {
            log.warn(message("archive.log.partial.delete.failed", token), e);
        }
    }

    /** 启动时扫描并清理上次运行遗留的孤儿压缩包 */
    @PostConstruct
    public void cleanupOrphanArchivesOnStartup() {
        Path archiveDir = Paths.get(downloadConfig.getRootFolder(), "_archives");
        if (!Files.isDirectory(archiveDir)) {
            return;
        }
        log.info(message("archive.log.startup.cleanup.scanning", archiveDir));
        int deleted = 0;
        try (var stream = Files.list(archiveDir)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".zip") || name.endsWith(".zip.part")) {
                    try {
                        Files.deleteIfExists(file);
                        deleted++;
                        log.info(message("archive.log.startup.cleanup.deleted", file.getFileName()));
                    } catch (Exception e) {
                        log.warn(message("archive.log.startup.cleanup.delete.failed", file.getFileName()), e);
                    }
                }
            }
        } catch (Exception e) {
            log.warn(message("archive.log.startup.cleanup.scan.failed", archiveDir), e);
            return;
        }
        if (deleted == 0) {
            log.info(message("archive.log.startup.cleanup.none"));
        }
    }

    /** 每分钟清理过期压缩包 */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredArchives() {
        long now = System.currentTimeMillis();
        archiveMap.entrySet().removeIf(e -> {
            ArchiveEntry ae = e.getValue();
            if (now > ae.getExpireTime()) {
                if (ae.getArchivePath() != null) {
                    try {
                        Files.deleteIfExists(ae.getArchivePath());
                    } catch (Exception e1) {
                        log.warn(message("archive.log.expired-file.delete.failed", ae.getArchivePath()), e1);
                    }
                }
                log.info(message("archive.log.expired.deleted", e.getKey()));
                return true;
            }
            return false;
        });
    }

    /** timed-delete 模式：每小时扫描并删除超过 deleteAfterHours 的作品文件 */
    @Scheduled(fixedRate = 3_600_000)
    public void cleanupTimedDeleteArtworks() {
        if (!"timed-delete".equals(config.getPostDownloadMode())) return;
        long cutoffMillis = System.currentTimeMillis() - (long) config.getDeleteAfterHours() * 3_600_000L;
        List<ArtworkRecord> oldArtworks = pixivDatabase.getArtworksOlderThan(cutoffMillis);
        if (oldArtworks.isEmpty()) return;
        log.info(message("quota.log.timed-delete.started", oldArtworks.size()));
        for (ArtworkRecord artwork : oldArtworks) {
            deleteArtworkFolder(artwork);
        }
    }

    /** 删除作品文件夹及其下载历史记录（统计数据不受影响）。*/
    private void deleteArtworkFolder(Path folder) {
        deleteArtworkFolder(folder, tryParseArtworkId(folder));
    }

    /** 删除作品文件夹及其下载历史记录（统计数据不受影响）。*/
    private void deleteArtworkFolder(ArtworkRecord artwork) {
        if (artwork == null) {
            return;
        }
        deleteArtworkFolder(resolveArtworkFolder(artwork), artwork.artworkId());
    }

    /** 删除作品文件夹及其下载历史记录（统计数据不受影响）。*/
    private void deleteArtworkFolder(Path folder, Long artworkId) {
        try {
            if (folder != null && Files.exists(folder)) {
                try (var stream = Files.walk(folder)) {
                    stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
                log.info(message("quota.log.folder.deleted", folder));
            }
        } catch (Exception e) {
            log.warn(message("quota.log.folder.delete.failed", folder), e);
        }
        try {
            if (artworkId == null) {
                return;
            }
            pixivDatabase.deleteArtwork(artworkId);
            log.info(message("quota.log.history.deleted", artworkId));
        } catch (Exception e) {
            log.warn(message("quota.log.history.delete.failed", folder), e);
        }
    }

    private Path resolveArtworkFolder(ArtworkRecord artwork) {
        if (artwork == null) {
            return null;
        }
        String folder = artwork.moved() && artwork.moveFolder() != null && !artwork.moveFolder().isBlank()
                ? artwork.moveFolder()
                : artwork.folder();
        if (folder == null || folder.isBlank()) {
            return null;
        }
        return Paths.get(folder);
    }

    private Long tryParseArtworkId(Path folder) {
        if (folder == null || folder.getFileName() == null) {
            return null;
        }
        try {
            return Long.parseLong(folder.getFileName().toString());
        } catch (NumberFormatException ignored) {
            // 文件夹名不是纯数字（如用户名子目录），跳过
            return null;
        }
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    // ---- 内部数据类 --------------------------------------------------------------

    @Getter
    public static class UserQuota {
        private final String uuid;
        private final AtomicInteger artworksUsed = new AtomicInteger(0);
        private volatile long periodStart = System.currentTimeMillis();
        private final Set<Path> downloadedFolders = ConcurrentHashMap.newKeySet();
        @Setter private volatile String archiveToken = null;

        /** 打包频率限制：在 archiveExpireMinutes 窗口内的打包次数 */
        private final AtomicInteger packCount = new AtomicInteger(0);
        private volatile long packWindowStart = System.currentTimeMillis();

        /** 代理请求频率限制：在 resetPeriodHours 窗口内的代理请求次数 */
        private final AtomicInteger proxyCount = new AtomicInteger(0);

        public UserQuota(String uuid) { this.uuid = uuid; }

        public synchronized void reset() {
            artworksUsed.set(0);
            proxyCount.set(0);
            periodStart = System.currentTimeMillis();
            downloadedFolders.clear();
            archiveToken = null;
        }

        /** 重置打包次数窗口 */
        public void resetPackWindow() {
            packCount.set(0);
            packWindowStart = System.currentTimeMillis();
        }
    }

    @Getter
    public static class ArchiveEntry {
        private final String token;
        private final String userUuid;
        @Setter private volatile Path archivePath;
        @Setter private volatile String status = "pending";
        private final long expireTime;
        private final long createdTime = System.currentTimeMillis();
        /** 任务来源标注（artworks / novels / pack），用于管理员任务列表展示。 */
        @Setter private volatile String exportType;
        @Setter private volatile int workCount;
        /** 打包过程中已开始处理的作品数（≤ workCount），用于任务列表进度条。 */
        @Setter private volatile int processedWorks;
        @Setter private volatile int fileCount;

        public ArchiveEntry(String token, String userUuid, long expireTime) {
            this.token = token;
            this.userUuid = userUuid;
            this.expireTime = expireTime;
        }
    }

    public record QuotaCheckResult(boolean allowed, int artworksUsed, int maxArtworks, long resetSeconds) {}
    public record QuotaStatusResult(int artworksUsed, int maxArtworks, long resetSeconds, ArchiveInfo archive) {}
    public record ArchiveInfo(String token, String status, long expireSeconds) {}

    /** workId 标注条目所属作品（manifest 等附加条目为 null），用于打包进度统计。 */
    public record ArchiveItem(Path path, String entryName, byte[] bytes, Long workId) {
        public static ArchiveItem file(Path path, String entryName) {
            return new ArchiveItem(path, entryName, null, null);
        }

        public static ArchiveItem file(Path path, String entryName, Long workId) {
            return new ArchiveItem(path, entryName, null, workId);
        }

        public static ArchiveItem bytes(String entryName, byte[] bytes) {
            return new ArchiveItem(null, entryName, bytes, null);
        }
    }
}
