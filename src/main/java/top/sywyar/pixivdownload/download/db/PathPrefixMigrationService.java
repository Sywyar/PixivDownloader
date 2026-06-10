package top.sywyar.pixivdownload.download.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 *
 * <p><b>符号根 {@code {0}}</b>（root-folder 为相对路径时启用）以一条虚拟行参与本服务：
 * {@link #list()} 把它置顶返回（{@code symbolic=true}）；{@link #apply} 对 id 0 的改写不走
 * {@code path_prefixes}（表中无此行），而是把目标路径注册为新前缀 {@code {N}} 后，
 * 在同一事务内把全部路径前缀列中的 {@code {0}} 引用改写为 {@code {N}}。
 * 是否同步 {@code config.yaml} 仍由调用方（GUI）决定。
 */
@Slf4j
@Service
public class PathPrefixMigrationService {

    private final PathPrefixCodec codec;
    private final PathPrefixMapper mapper;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final TransactionOperations transactionOperations;
    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public PathPrefixMigrationService(PathPrefixCodec codec, PathPrefixMapper mapper,
                                      DownloadConfig downloadConfig, AppMessages messages,
                                      PlatformTransactionManager transactionManager,
                                      DataSource dataSource) {
        this(codec, mapper, downloadConfig, messages, new TransactionTemplate(transactionManager), dataSource);
    }

    PathPrefixMigrationService(PathPrefixCodec codec, PathPrefixMapper mapper,
                               DownloadConfig downloadConfig, AppMessages messages,
                               TransactionOperations transactionOperations,
                               DataSource dataSource) {
        this.codec = codec;
        this.mapper = mapper;
        this.downloadConfig = downloadConfig;
        this.messages = messages;
        this.transactionOperations = transactionOperations;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * 列出全部前缀，并标记哪一行是当前下载根目录；当前下载根目录置顶，其余按 id 升序。
     * 符号根启用时额外置顶一条虚拟行（id 0、{@code symbolic=true}，路径为符号根当前解析结果）。
     */
    public List<PathPrefixView> list() {
        String rootNorm = normalize(currentDownloadRootAbsolute());
        List<PathPrefixView> views = new ArrayList<>();
        if (codec.isSymbolicRootActive()) {
            views.add(new PathPrefixView(PathPrefixCodec.SYMBOLIC_ROOT_ID,
                    codec.getSymbolicRootPath(), true, true));
        }
        codec.snapshot().stream()
                .map(p -> new PathPrefixView(
                        p.id(),
                        PathPrefixCodec.stripTrailingSeparators(p.path()),
                        rootNorm != null && rootNorm.equals(normalize(p.path())),
                        false))
                .sorted(Comparator.comparing(PathPrefixView::downloadRoot).reversed()
                        .thenComparingLong(PathPrefixView::id))
                .forEach(views::add);
        return views;
    }

    /** 软件根目录（后端工作目录）的绝对路径，供 GUI 判断新下载根是否仍在软件目录内。 */
    public String appRootAbsolute() {
        return PathPrefixCodec.stripTrailingSeparators(
                Path.of("").toAbsolutePath().normalize().toString());
    }

    /**
     * 符号根的当前状态，供 GUI 决策：
     * {@code referenced} —— 数据库是否存在 {@code {0}} 引用行；
     * {@code orphan} —— root-folder 已不满足符号根启用条件（绝对路径）但仍有 {@code {0}} 行，
     * 这些记录会错误地解析到新配置的位置，需要引导使用者固定到真实的旧路径；
     * {@code suggestedOldPath} —— {@code download_root_marker} 记录的上次解析结果（修复建议，可能为 null）。
     */
    public SymbolicRootStatus symbolicRootStatus() {
        boolean referenced = PathPrefixColumns.hasSymbolicRootRows(jdbc);
        boolean active = codec.isSymbolicRootActive();
        String suggested = readMarker();
        // marker 与当前解析结果相同说明它已被覆盖 / 不含旧路径信息（如修复早于本版本的覆盖 bug），
        // 这种建议值只会误导使用者，宁可留空让其手动填写
        if (suggested != null && normalize(suggested).equals(normalize(codec.getSymbolicRootPath()))) {
            suggested = null;
        }
        return new SymbolicRootStatus(active, referenced, !active && referenced, suggested);
    }

    /**
     * 把全部 {@code {0}} 引用固定为指向 {@code rawPath} 的 {@code {N}}（pin）。
     * 与 {@link #apply} 中的符号根改写不同：目标路径允许等于符号根当前解析结果
     * （GUI 配置页改下载根目录前的「冻结旧记录」正是这个场景），故走
     * {@link PathPrefixCodec#forceCreatePrefixId} 强制建行。孤儿修复（符号根未启用但仍有
     * {@code {0}} 行）同样经此入口，成功后删除已无意义的 marker 文件。
     */
    public PathPrefixMigrationResult pinSymbolicRoot(String rawPath) {
        String stripped = PathPrefixCodec.stripTrailingSeparators(rawPath == null ? "" : rawPath.trim());
        if (stripped == null || stripped.isEmpty()) {
            return new PathPrefixMigrationResult(false, 0,
                    List.of(new PrefixError(PathPrefixCodec.SYMBOLIC_ROOT_ID, "invalid")));
        }
        String reason = validatePath(stripped);
        if (reason != null) {
            return new PathPrefixMigrationResult(false, 0,
                    List.of(new PrefixError(PathPrefixCodec.SYMBOLIC_ROOT_ID, reason)));
        }
        try {
            transactionOperations.executeWithoutResult(status -> {
                long newId = codec.forceCreatePrefixId(stripped);
                for (PathPrefixColumns.TableColumns tc : PathPrefixColumns.ALL) {
                    for (String column : tc.columns()) {
                        PathPrefixStartupMigration.retargetColumn(jdbc, tc.table(), column,
                                PathPrefixCodec.SYMBOLIC_ROOT_TOKEN, "{" + newId + "}");
                    }
                }
            });
        } catch (DataAccessException e) {
            log.warn(messages.getForLog("download.db.log.prefix-migrate-failed", e.getMessage()), e);
            codec.reload();
            return new PathPrefixMigrationResult(false, 0,
                    List.of(new PrefixError(PathPrefixCodec.SYMBOLIC_ROOT_ID, "conflict")));
        }
        codec.reload();
        if (!codec.isSymbolicRootActive()) {
            deleteMarkerQuietly();
        }
        return new PathPrefixMigrationResult(true, 1, List.of());
    }

    private String readMarker() {
        try {
            Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
            if (!Files.isRegularFile(marker)) {
                return null;
            }
            String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteMarkerQuietly() {
        try {
            Files.deleteIfExists(RuntimeFiles.resolveDownloadRootMarkerPath());
        } catch (Exception e) {
            log.warn(messages.getForLog("download.db.log.symbolic-root-marker-failed", e.getMessage()));
        }
    }

    public record SymbolicRootStatus(boolean active, boolean referenced, boolean orphan, String suggestedOldPath) {
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
        // 符号根 {0} 的改写目标（已去尾分隔符）；null = 本批不动符号根
        String symbolicTarget = null;

        for (PathPrefixUpdate update : safeUpdates) {
            long id = update.id();
            String raw = update.path() == null ? "" : update.path().trim();
            if (raw.isEmpty()) {
                continue; // 留空 = 保持原值
            }
            if (id == PathPrefixCodec.SYMBOLIC_ROOT_ID) {
                if (!codec.isSymbolicRootActive()) {
                    errors.add(new PrefixError(id, "unknown-id"));
                    continue;
                }
                String stripped = PathPrefixCodec.stripTrailingSeparators(raw);
                if (stripped == null || stripped.isEmpty()) {
                    errors.add(new PrefixError(id, "invalid"));
                    continue;
                }
                if (normalize(stripped).equals(normalize(codec.getSymbolicRootPath()))) {
                    continue; // 与当前解析结果相同 = 未改动
                }
                String reason = validatePath(stripped);
                if (reason != null) {
                    errors.add(new PrefixError(id, reason));
                    continue;
                }
                symbolicTarget = stripped;
                continue;
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
        // 符号根改写目标允许与既有前缀行同路径（合并复用该行 id），故不参与 pending 查重；
        // 但其它行的新路径不得占用符号根的当前解析路径（否则编码歧义）。
        detectCollisions(pending, existing, errors);

        if (!errors.isEmpty()) {
            return new PathPrefixMigrationResult(false, 0, errors);
        }

        String finalSymbolicTarget = symbolicTarget;
        if (!pending.isEmpty() || finalSymbolicTarget != null) {
            try {
                transactionOperations.executeWithoutResult(status -> {
                    applyPending(pending);
                    if (finalSymbolicTarget != null) {
                        rewriteSymbolicRoot(finalSymbolicTarget);
                    }
                });
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
        int applied = pending.size() + (finalSymbolicTarget != null ? 1 : 0);
        return new PathPrefixMigrationResult(true, applied, List.of());
    }

    /**
     * 把全部路径前缀列中的符号根 {@code {0}} 引用改写为指向 {@code targetPath} 的 {@code {N}}。
     * 目标路径已存在同路径前缀行时直接复用其 id（合并）。必须在事务内调用。
     */
    private void rewriteSymbolicRoot(String targetPath) {
        long newId = codec.getOrCreatePrefixId(targetPath);
        for (PathPrefixColumns.TableColumns tc : PathPrefixColumns.ALL) {
            for (String column : tc.columns()) {
                PathPrefixStartupMigration.retargetColumn(jdbc, tc.table(), column,
                        PathPrefixCodec.SYMBOLIC_ROOT_TOKEN, "{" + newId + "}");
            }
        }
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
        // 符号根启用时其当前解析路径始终被占用：config 是否同步由 GUI 决定，本批改写后
        // 该路径仍可能继续作为下载根接收新作品，其它行的新路径不得与之重合
        if (codec.isSymbolicRootActive()) {
            currentByNorm.putIfAbsent(normalize(codec.getSymbolicRootPath()), PathPrefixCodec.SYMBOLIC_ROOT_ID);
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

    /**
     * @param symbolic 是否为符号根 {@code {0}} 的虚拟行（不对应 {@code path_prefixes} 中的真实行，
     *                 {@code path} 为符号根当前的运行期解析结果）
     */
    public record PathPrefixView(long id, String path, boolean downloadRoot, boolean symbolic) {
    }

    public record PathPrefixUpdate(long id, String path) {
    }

    public record PathPrefixMigrationResult(boolean success, int applied, List<PrefixError> errors) {
    }

    public record PrefixError(long id, String reason) {
    }
}
