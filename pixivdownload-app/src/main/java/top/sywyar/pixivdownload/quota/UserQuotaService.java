package top.sywyar.pixivdownload.quota;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;
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
    /**
     * 作品文件级删除入口：与核心删除链路（{@code LocalWorkAssetService} / {@code CoreWorkDeletionService}）
     * 复用同一条「原子删除 + 失败回滚」能力，避免配额链路自成一套安全语义。
     */
    private final ArtworkFileLocator artworkFileLocator;
    /** 按下载目录删除（小说目录 / 共享目录等无作品记录的场景）时的文件级原子删除能力。 */
    private final StagedFileDeletion stagedFileDeletion;

    public UserQuotaService(MultiModeConfig config,
                            DownloadConfig downloadConfig,
                            PixivDatabase pixivDatabase,
                            AppMessages messages,
                            @Qualifier("archiveTaskExecutor") TaskExecutor archiveTaskExecutor,
                            ArtworkFileLocator artworkFileLocator,
                            StagedFileDeletion stagedFileDeletion) {
        this.config = config;
        this.downloadConfig = downloadConfig;
        this.pixivDatabase = pixivDatabase;
        this.messages = messages;
        this.archiveTaskExecutor = archiveTaskExecutor;
        this.artworkFileLocator = artworkFileLocator;
        this.stagedFileDeletion = stagedFileDeletion;
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
                        // meta sidecar 是作品元数据、非下载内容，配额打包排除 *.meta.json。
                        stream.filter(Files::isRegularFile)
                                .filter(file -> !WorkSidecarFiles.isSidecarFile(file))
                                .forEach(file -> {
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
            List<Path> removableFolders = new ArrayList<>(folders.size());
            if (!"never-delete".equals(pdMode) && !"timed-delete".equals(pdMode)) {
                // 删除失败的目录保留在配额中，下次打包时重试；无论成败都不会出现「文件没了记录还在 / 记录删了文件还在」
                for (Path folder : folders) {
                    if (deleteArchivedFolder(folder)) {
                        removableFolders.add(folder);
                    }
                }
            } else {
                removableFolders.addAll(folders);
            }
            quota.getDownloadedFolders().removeAll(removableFolders);

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
                        // meta sidecar 是作品元数据、非下载内容，配额打包排除 *.meta.json。
                        stream.filter(Files::isRegularFile)
                                .filter(file -> !WorkSidecarFiles.isSidecarFile(file))
                                .forEach(file -> {
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
            // 直接按记录删除：记录已在手上，不需要（也不允许）用目录名反推作品 ID。
            // 删除同时按该作品自己的文件名主干做非递归精确匹配，共享目录里不会删到其它作品的文件。
            // 删除失败时记录保留，下一次扫描会自然重试。
            deleteArtworkFilesAndRecord(artwork);
        }
    }

    /**
     * 按记录删除一个作品在磁盘上的留存文件及其下载历史记录（统计数据不受影响）。
     *
     * <p>失败一致性：文件删除复用核心链路同一条 {@link ArtworkFileLocator#deleteArtworkFiles(ArtworkRecord)}
     * （内部走 {@link StagedFileDeletion#deleteAtomically}，任一文件失败即回滚到删除前状态），
     * <b>只有文件确实全部删除成功时才删除数据库记录</b>；文件删除失败时记录原样保留并记日志，
     * 因此任何时刻文件与记录要么都在、要么都不在，不会出现单边残留。
     *
     * @return {@code true} 表示文件与记录都已清理（或本来就没有可删的文件）；{@code false} 表示删除失败、记录已保留
     */
    private boolean deleteArtworkFilesAndRecord(ArtworkRecord artwork) {
        if (artwork == null) {
            return false;
        }
        long artworkId = artwork.artworkId();
        boolean filesDeleted;
        try {
            filesDeleted = artworkFileLocator.deleteArtworkFiles(artwork);
        } catch (Exception e) {
            // 目录不安全 / 不可读等：文件未被删除，记录必须保留
            log.warn(message("work.delete.file-failed", message("work.type.artwork"), artworkId), e);
            return false;
        }
        if (!filesDeleted) {
            // 复用核心删除链路的同一条失败语义文案：已中止数据库清理
            log.warn(message("work.delete.file-failed", message("work.type.artwork"), artworkId));
            return false;
        }
        try {
            pixivDatabase.deleteArtwork(artworkId);
            log.info(message("quota.log.history.deleted", artworkId));
            return true;
        } catch (Exception e) {
            log.warn(message("quota.log.history.delete.failed", artworkFileLocator.resolveArtworkDirectory(artwork)), e);
            return false;
        }
    }

    /**
     * 归档后按下载目录删除（{@code pack-and-delete} 专用）。
     *
     * <p>该入口手上只有「下载目录」而没有作品记录（{@code recordFolder} 记录的就是目录），因此：
     * <ul>
     *   <li>目录确实属于某个作品记录（标准 {@code {root}/{artworkId}} 布局或分类后 {@code move_folder}）
     *       时，走 {@link #deleteArtworkFilesAndRecord(ArtworkRecord)}：只删该作品自己的文件，文件全删成功才删记录；</li>
     *   <li>否则（小说目录、共享 / 作者目录、归属未确认）沿用「清空目录内文件」的既有语义，
     *       改为文件级原子删除，且<b>不</b>删除任何数据库记录——避免用目录名反推出的 ID 误删无关记录。</li>
     * </ul>
     *
     * @return {@code true} 表示目录内文件已全部删除（或目录本就不存在）；{@code false} 表示删除失败、调用方应保留该目录
     */
    private boolean deleteArchivedFolder(Path folder) {
        if (folder == null) {
            return true;
        }
        try {
            ArtworkRecord owner = findOwningArtwork(folder);
            if (owner != null) {
                return deleteArtworkFilesAndRecord(owner);
            }
            return deleteFolderFiles(folder);
        } catch (Exception e) {
            // 任何意外（含记录查询失败）都视为删除失败：目录保留在配额中待下次打包重试，且不删除任何记录
            log.warn(message("quota.log.folder.delete.failed", folder), e);
            return false;
        }
    }

    /** 仅当目录名反推出的作品 ID 对应的记录<b>确实落在该目录</b>时才认账，避免纯数字的共享 / 作者目录误删无关记录。 */
    private ArtworkRecord findOwningArtwork(Path folder) {
        Long artworkId = tryParseArtworkId(folder);
        if (artworkId == null) {
            return null;
        }
        ArtworkRecord record = pixivDatabase.getArtwork(artworkId);
        // 用与文件删除同一条目录解析（优先 move_folder）判断归属，保证「被认账的目录」==「会被删文件的目录」
        String owned = record == null ? null : artworkFileLocator.resolveArtworkDirectory(record);
        if (owned == null || owned.isBlank()) {
            return null;
        }
        try {
            return Paths.get(owned).toAbsolutePath().normalize()
                    .equals(folder.toAbsolutePath().normalize()) ? record : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 原子删除目录内的全部常规文件（不递归跟随符号链接，也不删除符号链接本身）。
     * 任一文件删除失败时 {@link StagedFileDeletion#deleteAtomically} 会把已删文件复原，故不会留下半删状态。
     *
     * @return {@code true} 表示文件已全部删除；{@code false} 表示删除失败、文件已回滚复原
     */
    private boolean deleteFolderFiles(Path folder) {
        if (Files.notExists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        boolean filesDeleted;
        try {
            Set<Path> files = new LinkedHashSet<>();
            try (var stream = Files.walk(folder)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .forEach(files::add);
            }
            filesDeleted = stagedFileDeletion.deleteAtomically(files);
        } catch (Exception e) {
            log.warn(message("quota.log.folder.delete.failed", folder), e);
            return false;
        }
        if (!filesDeleted) {
            log.warn(message("quota.log.folder.delete.failed", folder));
            return false;
        }
        removeEmptyDirectories(folder);
        log.info(message("quota.log.folder.deleted", folder));
        return true;
    }

    /**
     * 回收删除后留下的空目录壳：自底向上只删空目录（{@code Files.delete} 对非空目录抛
     * {@link DirectoryNotEmptyException}），因此仍含其它作品文件的共享目录不会被移除。
     */
    private void removeEmptyDirectories(Path folder) {
        try (var stream = Files.walk(folder)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                            // 目录非空（仍有其它作品的文件）或被占用：保留
                        }
                    });
        } catch (IOException e) {
            log.warn(message("download.file.log.remove-empty-dir-failed", folder), e);
        }
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
