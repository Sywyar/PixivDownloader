package top.sywyar.pixivdownload.download.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 「迁移下载目录」后端逻辑：列出并改写 {@code path_prefixes} 中登记的绝对路径前缀。
 *
 * <p><b>只改数据库记录，不移动磁盘文件。</b>本服务只重写 {@code path_prefixes} 行里的路径字符串，
 * 让既有作品行（编码为 {@code {N}/relative}）解析到新的绝对路径；磁盘上的文件需要使用者自行迁移。
 *
 * <p>下载根目录所对应的那一行由 {@link DownloadConfig#getRootFolder()} 解析出的绝对路径标识
 * （与 {@link PathPrefixStartupMigration} 预置前缀时的算法一致）。改写该行后，调用方应同时同步
 * {@code config.yaml} 的 {@code download.root-folder}，否则新下载仍会落到旧目录。
 */
@Slf4j
@Service
public class PathPrefixMigrationService {

    private final PathPrefixCodec codec;
    private final PathPrefixMapper mapper;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final TransactionOperations transactionOperations;

    @Autowired
    public PathPrefixMigrationService(PathPrefixCodec codec, PathPrefixMapper mapper,
                                      DownloadConfig downloadConfig, AppMessages messages,
                                      PlatformTransactionManager transactionManager) {
        this(codec, mapper, downloadConfig, messages, new TransactionTemplate(transactionManager));
    }

    PathPrefixMigrationService(PathPrefixCodec codec, PathPrefixMapper mapper,
                               DownloadConfig downloadConfig, AppMessages messages,
                               TransactionOperations transactionOperations) {
        this.codec = codec;
        this.mapper = mapper;
        this.downloadConfig = downloadConfig;
        this.messages = messages;
        this.transactionOperations = transactionOperations;
    }

    /** 列出全部前缀，并标记哪一行是当前下载根目录；当前下载根目录置顶，其余按 id 升序。 */
    public List<PathPrefixView> list() {
        String rootNorm = normalize(currentDownloadRootAbsolute());
        return codec.snapshot().stream()
                .map(p -> new PathPrefixView(
                        p.id(),
                        PathPrefixCodec.stripTrailingSeparators(p.path()),
                        rootNorm != null && rootNorm.equals(normalize(p.path()))))
                .sorted(Comparator.comparing(PathPrefixView::downloadRoot).reversed()
                        .thenComparingLong(PathPrefixView::id))
                .toList();
    }

    /**
     * 校验并应用一批前缀路径改写。全有或全无：任一条非法即不写入任何记录，返回逐条错误原因。
     * 仅处理 {@code path} 非空白且确实改变的条目；其余忽略（视为保持原值）。
     *
     * <p>{@code registerPaths} 为改写完成后需要「确保存在」的前缀（best-effort 追加新行）：
     * 典型用于用户改写了下载根目录、但选择不同步 {@code config.yaml} 时，把旧下载根目录重新登记成
     * 一条新前缀，使后续仍下到旧目录的新作品能继续被编码压缩。
     */
    public PathPrefixMigrationResult apply(List<PathPrefixUpdate> updates, List<String> registerPaths) {
        List<PathPrefixUpdate> safeUpdates = updates == null ? List.of() : updates;

        // 现有前缀：id -> 去尾分隔符后的原始路径
        Map<Long, String> existing = new HashMap<>();
        for (PathPrefix p : codec.snapshot()) {
            existing.put(p.id(), PathPrefixCodec.stripTrailingSeparators(p.path()));
        }

        List<PrefixError> errors = new ArrayList<>();
        // id -> 待写入的新路径（已去尾分隔符）；仅含实际发生变化的行
        LinkedHashMap<Long, String> pending = new LinkedHashMap<>();

        for (PathPrefixUpdate update : safeUpdates) {
            long id = update.id();
            String raw = update.path() == null ? "" : update.path().trim();
            if (raw.isEmpty()) {
                continue; // 留空 = 保持原值
            }
            if (!existing.containsKey(id)) {
                errors.add(new PrefixError(id, "unknown-id"));
                continue;
            }
            String stripped = PathPrefixCodec.stripTrailingSeparators(raw);
            if (stripped == null || stripped.isEmpty()) {
                errors.add(new PrefixError(id, "invalid"));
                continue;
            }
            // 与原值相同（大小写 / 分隔符不敏感）→ 视为未改动，忽略
            if (normalize(stripped).equals(normalize(existing.get(id)))) {
                continue;
            }
            String reason = validatePath(stripped);
            if (reason != null) {
                errors.add(new PrefixError(id, reason));
                continue;
            }
            pending.put(id, stripped);
        }

        // 唯一性校验：保证「最终状态」合法 —— 新路径不得与「其它任何一行的当前路径」或「本批其它新路径」
        // 冲突（归一化比较）。这只排除终态重复；写入过程中的瞬时冲突由下面的两阶段事务写入处理。
        detectCollisions(pending, existing, errors);

        if (!errors.isEmpty()) {
            return new PathPrefixMigrationResult(false, 0, errors);
        }

        if (!pending.isEmpty()) {
            try {
                transactionOperations.executeWithoutResult(status -> applyPending(pending));
            } catch (DataAccessException e) {
                // 整批回滚：要么全部生效，要么一行都不动，绝不留下「混合新旧目录」的部分迁移结果。
                log.warn(messages.getForLog("download.db.log.prefix-migrate-failed", e.getMessage()), e);
                codec.reload();
                return new PathPrefixMigrationResult(false, 0,
                        List.of(new PrefixError(0L, "conflict")));
            }
        }
        reRegister(registerPaths);
        codec.reload();
        return new PathPrefixMigrationResult(true, pending.size(), List.of());
    }

    /**
     * 在单个事务内两阶段写入待改写前缀，避免链式重命名 / 互换在顺序写入时触发 {@code path_prefixes} 的
     * UNIQUE 约束。
     *
     * <p>SQLite 的 UNIQUE 约束按语句即时校验，即便处于同一事务，顺序写入仍可能与「本批尚未改写的行」
     * 瞬时撞车（例如把 id2 改成 C，而占用 C 的 id3 还没被改）。因此先把每一行改成与表中任何真实路径都
     * 不可能相同的唯一临时占位值，再逐行写入最终目标路径；终态合法性已由 {@link #detectCollisions} 保证，
     * 故第二阶段不会再冲突。
     */
    private void applyPending(LinkedHashMap<Long, String> pending) {
        String token = UUID.randomUUID().toString();
        for (Map.Entry<Long, String> entry : pending.entrySet()) {
            mapper.updatePath(entry.getKey(), temporaryPlaceholder(entry.getKey(), token));
        }
        for (Map.Entry<Long, String> entry : pending.entrySet()) {
            mapper.updatePath(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 生成两阶段写入的临时占位路径：以非法绝对路径前缀 + 行 id + 本批唯一 token 组合，
     * 既保证本批内各行互不相同，又不可能与表中任何真实目录路径相等。
     */
    private static String temporaryPlaceholder(long id, String token) {
        return ":pixiv-path-prefix-migrating:" + id + ":" + token;
    }

    /** best-effort 追加新前缀行（如保留旧下载根目录），失败仅记日志、不影响主迁移结果。 */
    private void reRegister(List<String> registerPaths) {
        if (registerPaths == null) {
            return;
        }
        for (String raw : registerPaths) {
            if (raw == null) {
                continue;
            }
            String stripped = PathPrefixCodec.stripTrailingSeparators(raw.trim());
            if (stripped == null || stripped.isEmpty()) {
                continue;
            }
            try {
                codec.getOrCreatePrefixId(stripped);
            } catch (Exception e) {
                log.warn(messages.getForLog("download.db.log.prefix-migrate-failed", e.getMessage()), e);
            }
        }
    }

    private void detectCollisions(LinkedHashMap<Long, String> pending,
                                  Map<Long, String> existing,
                                  List<PrefixError> errors) {
        if (pending.isEmpty()) {
            return;
        }
        // 其它行的当前归一化路径（排除正在被改写的行本身）
        Map<String, Long> currentByNorm = new HashMap<>();
        for (Map.Entry<Long, String> e : existing.entrySet()) {
            if (pending.containsKey(e.getKey())) {
                continue;
            }
            currentByNorm.put(normalize(e.getValue()), e.getKey());
        }
        Map<String, Long> newByNorm = new HashMap<>();
        for (Map.Entry<Long, String> e : pending.entrySet()) {
            String norm = normalize(e.getValue());
            Long clashOther = currentByNorm.get(norm);
            Long clashNew = newByNorm.put(norm, e.getKey());
            if (clashOther != null || clashNew != null) {
                errors.add(new PrefixError(e.getKey(), "duplicate"));
            }
        }
    }

    /** 返回非空的错误码代表非法；null 代表合法。 */
    private String validatePath(String stripped) {
        Path path;
        try {
            path = Path.of(stripped);
        } catch (InvalidPathException e) {
            return "invalid";
        }
        if (!path.isAbsolute()) {
            return "not-absolute";
        }
        java.io.File file = path.toFile();
        if (!file.exists()) {
            return "not-exist";
        }
        if (!file.isDirectory()) {
            return "not-directory";
        }
        return null;
    }

    private String currentDownloadRootAbsolute() {
        try {
            return PathPrefixCodec.stripTrailingSeparators(
                    Path.of(downloadConfig.getRootFolder()).toAbsolutePath().normalize().toString());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 与 {@link PathPrefixCodec} 内部一致的归一化：{@code \\} 与 {@code /} 等价、大小写不敏感。 */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return PathPrefixCodec.stripTrailingSeparators(value).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public record PathPrefixView(long id, String path, boolean downloadRoot) {
    }

    public record PathPrefixUpdate(long id, String path) {
    }

    public record PathPrefixMigrationResult(boolean success, int applied, List<PrefixError> errors) {
    }

    public record PrefixError(long id, String reason) {
    }
}
