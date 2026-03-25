package top.sywyar.pixivdownload.quota;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class UserQuotaService {

    @Autowired
    private MultiModeConfig config;

    @Autowired
    private DownloadConfig downloadConfig;

    @Autowired
    private PixivDatabase pixivDatabase;

    /** UUID → 用户配额信息 */
    private final ConcurrentHashMap<String, UserQuota> quotaMap = new ConcurrentHashMap<>();
    /** token → 压缩包信息 */
    private final ConcurrentHashMap<String, ArchiveEntry> archiveMap = new ConcurrentHashMap<>();

    // ---- 配额管理 ----------------------------------------------------------------

    /**
     * 检查并预留配额。若允许，原子地扣减 1（每次下载请求对应一个作品）；否则返回拒绝结果。
     */
    public QuotaCheckResult checkAndReserve(String uuid) {
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

            if (used + 1 > max) {
                return new QuotaCheckResult(false, used, max, resetSeconds);
            }

            quota.getArtworksUsed().incrementAndGet();
            return new QuotaCheckResult(true, used + 1, max, resetSeconds);
        }
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

        buildArchiveAsync(token, uuid);
        return token;
    }

    @Async
    public void buildArchiveAsync(String token, String uuid) {
        ArchiveEntry entry = archiveMap.get(token);
        if (entry == null) return;

        entry.setStatus("creating");

        UserQuota quota = quotaMap.get(uuid);
        if (quota == null || quota.getDownloadedFolders().isEmpty()) {
            entry.setStatus("empty");
            log.info("压缩包 {}: 用户 {} 暂无已下载文件夹", token, uuid);
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
                                log.warn("打包文件失败: {}", file, e);
                            }
                        });
                    }
                }
            }

            entry.setArchivePath(archivePath);
            entry.setStatus("ready");
            log.info("压缩包 {} 创建完成: {}", token, archivePath);

            // 打包后删除源文件及对应的下载历史记录
            for (Path folder : folders) {
                // 删除磁盘文件
                try {
                    Files.walk(folder)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                    log.info("已删除源文件夹: {}", folder);
                } catch (Exception e) {
                    log.warn("删除源文件夹失败: {}", folder, e);
                }
                // 删除 artworks 表中对应记录（folder 名即 artworkId）
                try {
                    long artworkId = Long.parseLong(folder.getFileName().toString());
                    pixivDatabase.deleteArtwork(artworkId);
                    log.info("已删除下载历史记录: artworkId={}", artworkId);
                } catch (NumberFormatException ignored) {
                    // 文件夹名不是纯数字（如用户名子目录），跳过
                } catch (Exception e) {
                    log.warn("删除下载历史记录失败: folder={}", folder, e);
                }
            }
            quota.getDownloadedFolders().removeAll(folders);

        } catch (Exception e) {
            entry.setStatus("error");
            log.error("压缩包 {} 创建失败 (用户 {})", token, uuid, e);
        }
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
                log.warn("删除压缩包文件失败: {}", entry.getArchivePath(), e);
            }
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
                    try { Files.deleteIfExists(ae.getArchivePath()); } catch (Exception ignored) {}
                }
                log.info("压缩包 {} 已过期，已删除", e.getKey());
                return true;
            }
            return false;
        });
    }

    // ---- UUID 工具 ---------------------------------------------------------------

    /**
     * 基于 IP + User-Agent 生成稳定 UUID（相同输入始终得到相同 UUID）。
     */
    public static String generateUuidFromFingerprint(String ip, String userAgent) {
        try {
            String input = (ip != null ? ip : "") + "|" + (userAgent != null ? userAgent : "");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    // ---- 内部数据类 --------------------------------------------------------------

    @Getter
    public static class UserQuota {
        private final String uuid;
        private final AtomicInteger artworksUsed = new AtomicInteger(0);
        private volatile long periodStart = System.currentTimeMillis();
        private final List<Path> downloadedFolders =
                Collections.synchronizedList(new ArrayList<>());
        private volatile String archiveToken = null;

        public UserQuota(String uuid) { this.uuid = uuid; }

        public void setArchiveToken(String token) { this.archiveToken = token; }

        public synchronized void reset() {
            artworksUsed.set(0);
            periodStart = System.currentTimeMillis();
            downloadedFolders.clear();
            archiveToken = null;
        }
    }

    @Getter
    public static class ArchiveEntry {
        private final String token;
        private final String userUuid;
        private volatile Path archivePath;
        private volatile String status = "pending";
        private final long expireTime;

        public ArchiveEntry(String token, String userUuid, long expireTime) {
            this.token = token;
            this.userUuid = userUuid;
            this.expireTime = expireTime;
        }

        public void setArchivePath(Path p) { this.archivePath = p; }
        public void setStatus(String s) { this.status = s; }
    }

    public record QuotaCheckResult(boolean allowed, int artworksUsed, int maxArtworks, long resetSeconds) {}
    public record QuotaStatusResult(int artworksUsed, int maxArtworks, long resetSeconds, ArchiveInfo archive) {}
    public record ArchiveInfo(String token, String status, long expireSeconds) {}
}
