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
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionService;
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
    private final ArtworkFileLocator artworkFileLocator;
    private final NovelMetadataRepository novelMetadataRepository;
    private final WorkDeletionService workDeletionService;
    private final top.sywyar.pixivdownload.core.work.service.WorkAssetService workAssetService;
    private final TaskExecutor archiveTaskExecutor;

    public UserQuotaService(MultiModeConfig config,
                            DownloadConfig downloadConfig,
                            PixivDatabase pixivDatabase,
                            AppMessages messages,
                            @Qualifier("archiveTaskExecutor") TaskExecutor archiveTaskExecutor,
                            ArtworkFileLocator artworkFileLocator,
                            NovelMetadataRepository novelMetadataRepository,
                            WorkDeletionService workDeletionService,
                            top.sywyar.pixivdownload.core.work.service.WorkAssetService workAssetService) {
        this.config = config;
        this.downloadConfig = downloadConfig;
        this.pixivDatabase = pixivDatabase;
        this.messages = messages;
        this.archiveTaskExecutor = archiveTaskExecutor;
        this.artworkFileLocator = artworkFileLocator;
        this.novelMetadataRepository = novelMetadataRepository;
        this.workDeletionService = workDeletionService;
        this.workAssetService = workAssetService;
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

        submitArchive(entry, () -> buildArchive(token, uuid));
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
        List<Path> snapshot = folders == null ? List.of() : new ArrayList<>(folders);
        submitArchive(entry, () -> buildAdminArchive(token, snapshot));
        return token;
    }

    /**
     * 管理员按文件清单打包。exportType 标注任务来源（如 artworks / novels），
     * 供任务列表展示；afterReady 仅在打包成功后执行（如导出后删除源文件）。
     */
    public String triggerAdminFileArchive(List<ArchiveItem> items, String exportType, int workCount,
                                          Runnable afterReady) {
        return triggerAdminFileArchive(items, exportType, workCount, afterReady, () -> () -> {});
    }

    public String triggerAdminFileArchive(List<ArchiveItem> items, String exportType, int workCount,
                                          Runnable afterReady, java.util.function.Supplier<AutoCloseable> sourceLease) {
        String token = UUID.randomUUID().toString();
        long expireTime = System.currentTimeMillis()
                + (long) config.getQuota().getArchiveExpireMinutes() * 60_000;
        ArchiveEntry entry = new ArchiveEntry(token, null, expireTime);
        entry.setExportType(exportType);
        entry.setWorkCount(workCount);
        archiveMap.put(token, entry);
        List<ArchiveItem> snapshot = items == null ? List.of() : new ArrayList<>(items);
        submitArchive(entry, () -> {
            try (var ignored = sourceLease.get()) {
                buildAdminFileArchive(token, snapshot, afterReady);
            } catch (Exception failure) {
                entry.setStatus("error");
                log.error(message("archive.log.admin.create.failed", token), failure);
            }
        });
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

    private void submitArchive(ArchiveEntry entry, Runnable task) {
        try {
            archiveTaskExecutor.execute(task);
        } catch (RuntimeException failure) {
            archiveMap.remove(entry.getToken(), entry);
            UserQuota quota = entry.getUserUuid() == null ? null : quotaMap.get(entry.getUserUuid());
            if (quota != null) {
                synchronized (quota) {
                    if (entry.getToken().equals(quota.getArchiveToken())) quota.setArchiveToken(null);
                }
            }
            throw failure;
        }
    }

    private void buildArchive(String token, String uuid) {
        UserQuota quota = quotaMap.get(uuid);
        List<Path> folders = quota == null ? List.of() : new ArrayList<>(quota.getDownloadedFolders());
        try (var artworks = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquireAll(WorkType.ARTWORK, folderWorkIds(folders, false));
             var novels = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquireAll(WorkType.NOVEL, folderWorkIds(folders, true))) {
            Map<Path, java.util.function.BooleanSupplier> deletions = new LinkedHashMap<>();
            for (Path folder : folders) deletions.put(folder, prepareFolderDeletion(folder));
            buildFolderArchive(token, folders, () -> {
                String mode = config.getPostDownloadMode();
                for (Path folder : folders) {
                    if ("never-delete".equals(mode) || "timed-delete".equals(mode) || deletions.get(folder).getAsBoolean()) {
                        quota.getDownloadedFolders().remove(folder);
                    }
                }
            });
        } catch (Exception failure) {
            ArchiveEntry entry = archiveMap.get(token);
            if (entry != null) entry.setStatus("error");
            log.error(message("archive.log.admin.create.failed", token), failure);
        }
    }

    private static List<Long> folderWorkIds(List<Path> folders, boolean novels) {
        List<Long> ids = new ArrayList<>();
        for (Path folder : folders) {
            if (folder.getFileName() == null) continue;
            String name = folder.getFileName().toString();
            if (novels != name.startsWith("novel-")) continue;
            try { ids.add(Long.parseLong(novels ? name.substring(6) : name)); }
            catch (NumberFormatException ignored) { /* 非作品目录不参与作品删除。 */ }
        }
        return ids;
    }

    private void buildAdminArchive(String token, List<Path> folders) {
        buildFolderArchive(token, folders, null);
    }

    private void buildFolderArchive(String token, List<Path> folders, Runnable afterReady) {
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;
        if (folders.isEmpty()) {
            buildAdminFileArchive(token, List.of(), afterReady);
            return;
        }
        try {
            List<ArchiveItem> items = new ArrayList<>();
            Path archiveDir = Paths.get(downloadConfig.getRootFolder(), "_archives").toAbsolutePath().normalize();
            for (Path folder : folders) {
                Path source = folder.toAbsolutePath().normalize();
                if (!PlainFilePathGuard.isPlainDirectory(source)) throw new IOException("Unsafe archive directory: " + source);
                // 输入不能包含输出目录，也不能位于输出目录内。
                if (archiveDir.startsWith(source) || source.startsWith(archiveDir)) {
                    throw new IOException("Archive source overlaps its output directory");
                }
                try (var stream = Files.walk(source)) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        Path file = iterator.next();
                        if (PlainFilePathGuard.isPlainDirectory(file)) continue;
                        PlainFilePathGuard.requirePlainRegularFile(file);
                        if (WorkSidecarFiles.isSidecarFile(file)) continue;
                        String name = source.getFileName() + "/" + source.relativize(file).toString().replace('\\', '/');
                        items.add(ArchiveItem.file(file, name));
                    }
                }
            }
            buildAdminFileArchive(token, items, afterReady);
        } catch (Exception failure) {
            entry.setStatus("error");
            log.error(message("archive.log.admin.create.failed", token), failure);
        }
    }

    private void buildAdminFileArchive(String token, List<ArchiveItem> items, Runnable afterReady) {
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;

        synchronized (entry) {
            if (archiveMap.get(token) != entry || "cancelled".equals(entry.getStatus()) || entry.getExpireTime() <= System.currentTimeMillis()) return;
            entry.setStatus("creating");
        }

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
            Map<Path, java.nio.file.attribute.BasicFileAttributes> sources = new LinkedHashMap<>();
            entry.setArchivePath(archivePath);

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(archivePath.toFile()),
                            64 * 1024))) {
                zos.setLevel(Deflater.BEST_COMPRESSION);

                for (ArchiveItem item : items) {
                    if (archiveMap.get(token) != entry || "cancelled".equals(entry.getStatus()) || entry.getExpireTime() <= System.currentTimeMillis()) {
                        throw new IOException("Archive task is no longer active");
                    }
                    if (item == null) throw new IOException("Missing archive item");
                    if (item.workId() != null && startedWorks.add(item.workId())) {
                        entry.setProcessedWorks(startedWorks.size());
                    }
                    String entryName = uniqueEntryName(safeZipEntryName(item.entryName()), entryNames);
                    if (entryName == null) throw new IOException("Invalid archive entry name");
                    try {
                        if (item.bytes() != null) {
                            zos.putNextEntry(new ZipEntry(entryName));
                            zos.write(item.bytes());
                            zos.closeEntry();
                            written++;
                        } else {
                            PlainFilePathGuard.requirePlainRegularFile(item.path());
                            var before = Files.readAttributes(item.path(), java.nio.file.attribute.BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(item.path(), zos);
                            zos.closeEntry();
                            requireUnchangedSource(item.path(), before);
                            sources.put(item.path(), before);
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

            synchronized (entry) {
                if (archiveMap.get(token) != entry || "cancelled".equals(entry.getStatus()) || entry.getExpireTime() <= System.currentTimeMillis()) {
                    deletePartialArchive(entry);
                    archiveMap.remove(token, entry);
                    return;
                }
                if (afterReady != null) {
                    try {
                        for (var source : sources.entrySet()) requireUnchangedSource(source.getKey(), source.getValue());
                        afterReady.run();
                    } catch (Exception e) {
                        // ZIP 已完整关闭；删源失败必须保留这份可下载副本。
                        log.warn(message("archive.log.admin.post-action.failed", token), e);
                    }
                }
                entry.setFileCount(written);
                entry.setStatus("ready");
            }
            log.info(message("archive.log.admin.file-archive.created", token, archivePath, written));
        } catch (Exception e) {
            boolean cancelled = "cancelled".equals(entry.getStatus()) || archiveMap.get(token) != entry;
            entry.setStatus(cancelled ? "cancelled" : "error");
            deletePartialArchive(entry);
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
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;
        synchronized (entry) {
            entry.setStatus("cancelled");
            if (entry.getArchivePath() != null) {
                try {
                    Files.deleteIfExists(entry.getArchivePath());
                } catch (IOException failure) {
                    log.warn(message("archive.log.file.delete.failed", entry.getArchivePath()), failure);
                    return;
                }
            }
            archiveMap.remove(token, entry);
        }
    }

    private static void requireUnchangedSource(Path path, java.nio.file.attribute.BasicFileAttributes before)
            throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(path);
        var after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("Archive source changed: " + path);
        }
    }

    /**
     * 归档失败或取消后清理实际输出路径；失败时保留条目，供过期清理重试。
     */
    private void deletePartialArchive(ArchiveEntry entry) {
        try {
            if (entry.getArchivePath() != null) Files.deleteIfExists(entry.getArchivePath());
        } catch (Exception e) {
            log.warn(message("archive.log.partial.delete.failed", entry.getToken()), e);
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
        for (ArchiveEntry entry : archiveMap.values()) {
            if (now > entry.getExpireTime() || "cancelled".equals(entry.getStatus())) {
                deleteArchive(entry.getToken());
            }
        }
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

    /** 固定归档开始时的作品记录；目录名只作查库线索，不构成删除授权。 */
    private java.util.function.BooleanSupplier prepareFolderDeletion(Path folder) {
        if (folder == null || folder.getFileName() == null) return () -> false;
        try {
            String name = folder.getFileName().toString();
            if (name.startsWith("novel-")) {
                long id = Long.parseLong(name.substring(6));
                var novel = novelMetadataRepository.getNovel(id);
                if (novel == null || novel.deleted() || !samePath(folder, novel.folder())) return () -> false;
                return () -> {
                    try (var ignored = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(WorkType.NOVEL, id)) {
                        return novel.equals(novelMetadataRepository.getNovel(id))
                                && workAssetService.hasCompleteFiles(WorkType.NOVEL, id)
                                && workDeletionService.delete(WorkType.NOVEL, id);
                    }
                };
            }
            long id = Long.parseLong(name);
            ArtworkRecord artwork = pixivDatabase.getArtwork(id);
            if (artwork == null || !samePath(folder, artworkFileLocator.resolveArtworkDirectory(artwork))) return () -> false;
            return () -> workAssetService.hasCompleteFiles(WorkType.ARTWORK, id) && deleteArtworkFolder(artwork);
        } catch (Exception failure) {
            log.warn(message("quota.log.folder.delete.failed", folder), failure);
            return () -> false;
        }
    }

    private boolean deleteArtworkFolder(ArtworkRecord artwork) {
        if (artwork == null || artwork.deleted() || artworkFileLocator.isReadOnly(artwork)) return false;
        try (var ignored = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(WorkType.ARTWORK, artwork.artworkId())) {
            if (!artwork.equals(pixivDatabase.getArtwork(artwork.artworkId()))) return false;
            return artworkFileLocator.deleteArtworkFiles(artwork,
                    () -> pixivDatabase.deleteArtwork(artwork.artworkId()));
        } catch (Exception failure) {
            log.warn(message("quota.log.folder.delete.failed", artwork.folder()), failure);
            return false;
        }
    }

    private static boolean samePath(Path path, String stored) {
        return stored != null && !stored.isBlank()
                && path.toAbsolutePath().normalize().equals(Paths.get(stored).toAbsolutePath().normalize());
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
