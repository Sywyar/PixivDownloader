package top.sywyar.pixivdownload.core.db.pathprefix;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import static top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec.comparisonKey;
import java.util.Map;
import java.util.UUID;

/**
 * 「迁移下载目录」后端逻辑：列出并改写 {@code path_prefixes} 中登记的绝对路径前缀。
 *
 * <p><b>只改数据库记录，不移动磁盘文件。</b>本服务只重写 {@code path_prefixes} 行里的路径字符串，
 * 让既有作品行（编码为 {@code {N}/relative}）解析到新的绝对路径；磁盘上的文件需要使用者自行迁移。
 *
 * <p>下载根目录所对应的那一行由 {@link DownloadConfig#getRootFolder()} 解析出的绝对路径标识
 * （与启动时预置路径前缀的算法一致）。改写该行后，调用方应同时同步
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
    private final PathPrefixColumns pathPrefixColumns;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final TransactionOperations transactionOperations;
    private final MainDatabase database;
    private final DownloadDirectories directories;

    @Autowired
    public PathPrefixMigrationService(PathPrefixCodec codec, PathPrefixMapper mapper,
                                      PathPrefixColumns pathPrefixColumns,
                                      DownloadConfig downloadConfig, AppMessages messages,
                                      PlatformTransactionManager transactionManager,
                                      MainDatabase database, DownloadDirectories directories) {
        this(codec, mapper, pathPrefixColumns, downloadConfig, messages,
                writeTransactions(transactionManager), database, directories);
    }

    private static TransactionOperations writeTransactions(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        // The table has a literal-path UNIQUE constraint. Equivalent spellings share a logical key.
        transaction.setIsolationLevel(TransactionTemplate.ISOLATION_SERIALIZABLE);
        return transaction;
    }

    PathPrefixMigrationService(PathPrefixCodec codec, PathPrefixMapper mapper,
                               PathPrefixColumns pathPrefixColumns,
                               DownloadConfig downloadConfig, AppMessages messages,
                               TransactionOperations transactionOperations,
                               MainDatabase database, DownloadDirectories directories) {
        this.directories = directories;
        this.codec = codec;
        this.mapper = mapper;
        this.pathPrefixColumns = pathPrefixColumns;
        this.downloadConfig = downloadConfig;
        this.messages = messages;
        this.transactionOperations = transactionOperations;
        this.database = database;
    }

    /**
     * 列出全部前缀，并标记哪一行是当前下载根目录；当前下载根目录置顶，其余按 id 升序。
     * 符号根启用时额外置顶一条虚拟行（id 0、{@code symbolic=true}，路径为符号根当前解析结果）。
     */
    public List<PathPrefixView> list() {
        String rootNorm = comparisonKey(currentDownloadRootAbsolute());
        List<PathPrefixView> views = new ArrayList<>();
        if (codec.isSymbolicRootActive()) {
            views.add(new PathPrefixView(PathPrefixCodec.SYMBOLIC_ROOT_ID,
                    codec.getSymbolicRootPath(), true, true));
        }
        codec.snapshot().stream()
                .map(p -> new PathPrefixView(
                        p.id(),
                        PathPrefixCodec.stripTrailingSeparators(p.path()),
                        rootNorm != null && rootNorm.equals(comparisonKey(p.path())),
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
        boolean referenced = pathPrefixColumns.hasSymbolicRootRows(database);
        boolean active = codec.isSymbolicRootActive();
        String suggested = readMarker();
        // marker 与当前解析结果相同说明它已被覆盖 / 不含旧路径信息（如修复早于本版本的覆盖 bug），
        // 这种建议值只会误导使用者，宁可留空让其手动填写
        if (suggested != null && comparisonKey(suggested).equals(comparisonKey(codec.getSymbolicRootPath()))) {
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
        String stripped = PathPrefixCodec.stripTrailingSeparators(rawPath);
        if (stripped == null || stripped.isBlank()) {
            return new PathPrefixMigrationResult(false, 0,
                    List.of(new PrefixError(PathPrefixCodec.SYMBOLIC_ROOT_ID, "invalid")));
        }
        String reason = validatePath(stripped);
        if (reason != null) {
            return new PathPrefixMigrationResult(false, 0,
                    List.of(new PrefixError(PathPrefixCodec.SYMBOLIC_ROOT_ID, reason)));
        }
        var result = migrate(() -> {
            long id = PathPrefixCodec.SYMBOLIC_ROOT_ID;
            String oldRoot = codec.getSymbolicRootPath();
            if (!java.util.Objects.equals(oldRoot, stripped)) {
                if (reserved(oldRoot, stripped)) return new PathPrefixMigrationResult(false, 0, List.of(new PrefixError(id, "conflict")));
                invalidateHashSources(currentPrefixes(), Map.of(), oldRoot, stripped);
            }
            rewriteSymbolicRoot(codec.forceCreatePrefixId(stripped));
            return new PathPrefixMigrationResult(true, 1, List.of());
        });
        if (!result.success()) return result;
        boolean wasActive = codec.isSymbolicRootActive();
        // pin 已把全部 {0} 引用固定为真实 {N} 行：从运行期编码候选中摘除符号根，避免「改了
        // download.root-folder 但尚未重启」的窗口里新下载仍被编码成 {0}（重启后会解析到新 root 的错误目录）。
        codec.deactivateSymbolicRootEncoding();
        if (!wasActive) {
            // 孤儿修复（符号根本就未启用）：marker 已完成使命，删除以免日后误报
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
            String value = Files.readString(marker, StandardCharsets.UTF_8);
            return value.isBlank() ? null : value;
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

    public record SymbolicRootStatus(boolean active, boolean referenced, boolean orphan, @HttpApiNullable String suggestedOldPath) {
    }

    /**
     * 校验并应用一批前缀路径改写。全有或全无：任一条非法即不写入任何记录，返回逐条错误原因。
     * 仅处理 {@code path} 非空白且确实改变的条目；其余忽略（视为保持原值）。
     *
     * <p>{@code registerPaths} 在同一事务内确保前缀存在：
     * 典型用于用户改写了下载根目录、但选择不同步 {@code config.yaml} 时，把旧下载根目录重新登记成
     * 一条新前缀，使后续仍下到旧目录的新作品能继续被编码压缩。
     */
    public PathPrefixMigrationResult apply(List<PathPrefixUpdate> updates, List<String> registerPaths) {
        return migrate(() -> applyInTransaction(updates, registerPaths));
    }

    private PathPrefixMigrationResult migrate(java.util.function.Supplier<PathPrefixMigrationResult> operation) {
        try {
            synchronized (directories) {
                return codec.changeMappings(() -> transactionOperations.execute(status -> operation.get()),
                        result -> result.success() && result.applied() > result.registered());
            }
        } catch (DataAccessException | TransactionException failure) {
            return writeFailure(failure);
        }
    }

    private PathPrefixMigrationResult applyInTransaction(List<PathPrefixUpdate> updates, List<String> registerPaths) {
        List<PathPrefixUpdate> safeUpdates = updates == null ? List.of() : updates;

        // 现有前缀：id -> 去尾分隔符后的原始路径
        Map<Long, String> existing = currentPrefixes();

        List<PrefixError> errors = new ArrayList<>();
        // id -> 待写入的新路径（已去尾分隔符）；仅含实际发生变化的行
        LinkedHashMap<Long, String> pending = new LinkedHashMap<>();
        // 符号根 {0} 的改写目标（已去尾分隔符）；null = 本批不动符号根
        String symbolicTarget = null;

        for (PathPrefixUpdate update : safeUpdates) {
            long id = update.id();
            String stripped = PathPrefixCodec.stripTrailingSeparators(update.path());
            if (stripped == null || stripped.isBlank()) {
                continue; // 留空 = 保持原值
            }
            if (id == PathPrefixCodec.SYMBOLIC_ROOT_ID) {
                if (!codec.isSymbolicRootActive()) {
                    errors.add(new PrefixError(id, "unknown-id"));
                    continue;
                }
                if (comparisonKey(stripped).equals(comparisonKey(codec.getSymbolicRootPath()))) {
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
            if (comparisonKey(stripped).equals(comparisonKey(existing.get(id)))) {
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

        List<String> registrations = new ArrayList<>();
        if (registerPaths != null) {
            for (int i = 0; i < registerPaths.size(); i++) {
                String raw = registerPaths.get(i);
                String path = PathPrefixCodec.stripTrailingSeparators(raw);
                String reason = path == null || path.isBlank() ? "invalid" : validatePath(path);
                if (reason != null) errors.add(new PrefixError(0L, reason, "/registerPaths/" + i));
                else registrations.add(path);
            }
        }
        if (!errors.isEmpty()) return new PathPrefixMigrationResult(false, 0, errors);

        Map<Long, String> replacements = new HashMap<>();
        for (var edit : pending.entrySet()) {
            String old = existing.get(edit.getKey());
            if (reserved(old, edit.getValue())) errors.add(new PrefixError(edit.getKey(), "conflict"));
            replacements.put(edit.getKey(), edit.getValue());
        }
        String oldRoot = codec.getSymbolicRootPath();
        String newRoot = symbolicTarget == null ? oldRoot : symbolicTarget;
        if (!java.util.Objects.equals(oldRoot, newRoot) && reserved(oldRoot, newRoot))
            errors.add(new PrefixError(PathPrefixCodec.SYMBOLIC_ROOT_ID, "conflict"));
        if (!errors.isEmpty()) return new PathPrefixMigrationResult(false, 0, errors);
        if (!replacements.isEmpty() || !java.util.Objects.equals(oldRoot, newRoot))
            invalidateHashSources(existing, replacements, oldRoot, newRoot);

        applyPending(pending);
        if (symbolicTarget != null) rewriteSymbolicRoot(codec.getOrCreatePrefixId(symbolicTarget));
        int registered = 0;
        if (!registrations.isEmpty()) {
            var ids = codec.registrationIds();
            for (String path : registrations) {
                if (codec.registerPrefix(path, false, ids).inserted()) registered++;
            }
        }
        codec.reload();
        int applied = pending.size() + (symbolicTarget != null ? 1 : 0) + registered;
        return new PathPrefixMigrationResult(true, applied, registered, registrations.size() - registered, List.of());
    }

    private boolean reserved(String old, String destination) {
        try {
            try { directories.requireDirectoryAvailable(Path.of(old)); }
            catch (InvalidPathException unusableOnThisPlatform) { /* No local reservation can use this spelling. */ }
            directories.requireDirectoryAvailable(Path.of(destination));
            return false;
        } catch (DirectoryTemplateException conflict) {
            return true;
        }
    }

    private Map<Long, String> currentPrefixes() {
        Map<Long, String> existing = new HashMap<>();
        for (PathPrefix p : mapper.findAll()) {
            existing.put(p.id(), PathPrefixCodec.stripTrailingSeparators(p.path()));
        }
        return existing;
    }

    private void invalidateHashSources(Map<Long, String> previous, Map<Long, String> replacements,
                                       String oldRoot, String newRoot) {
        ArtworkHashSourcePaths.invalidate(database, path -> PathPrefixCodec.resolve(path, oldRoot, previous::get),
                path -> PathPrefixCodec.resolve(path, newRoot, id -> replacements.getOrDefault(id, previous.get(id))));
    }

    private PathPrefixMigrationResult writeFailure(RuntimeException failure) {
        log.warn(messages.getForLog("download.db.log.prefix-migrate-failed", failure.getMessage()), failure);
        String reason = failure instanceof org.springframework.dao.DuplicateKeyException ? "conflict" : "write-failed";
        return new PathPrefixMigrationResult(false, 0, List.of(new PrefixError(0L, reason)));
    }

    /**
     * 把全部路径前缀列中的符号根 {@code {0}} 引用改写为指定前缀。必须在事务内调用。
     */
    private void rewriteSymbolicRoot(long newId) {
        for (PathPrefixColumns.TableColumns tc : pathPrefixColumns.all()) {
            for (String column : tc.columns()) {
                PathPrefixColumns.retargetColumn(database, tc.table(), column,
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
     * 故写入最终目标路径时不会再冲突。
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
            currentByNorm.put(comparisonKey(e.getValue()), e.getKey());
        }
        // 符号根启用时其当前解析路径始终被占用：config 是否同步由 GUI 决定，本批改写后
        // 该路径仍可能继续作为下载根接收新作品，其它行的新路径不得与之重合
        if (codec.isSymbolicRootActive()) {
            currentByNorm.putIfAbsent(comparisonKey(codec.getSymbolicRootPath()), PathPrefixCodec.SYMBOLIC_ROOT_ID);
        }
        Map<String, Long> newByNorm = new HashMap<>();
        for (Map.Entry<Long, String> e : pending.entrySet()) {
            String norm = comparisonKey(e.getValue());
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

    /**
     * @param symbolic 是否为符号根 {@code {0}} 的虚拟行（不对应 {@code path_prefixes} 中的真实行，
     *                 {@code path} 为符号根当前的运行期解析结果）
     */
    public record PathPrefixView(long id, String path, boolean downloadRoot, boolean symbolic) {
    }

    public record PathPrefixUpdate(long id, String path) {
    }

    public record PathPrefixMigrationResult(boolean success, int applied, int registered, int alreadyRegistered, List<PrefixError> errors) {
        public PathPrefixMigrationResult(boolean success, int applied, List<PrefixError> errors) {
            this(success, applied, 0, 0, errors);
        }
    }

    public record PrefixError(long id, @Schema(allowableValues = {"invalid", "not-absolute", "not-exist", "not-directory", "conflict", "unknown-id", "duplicate", "write-failed"}) String reason,
                              @HttpApiNullable String field) {
        public PrefixError(long id, String reason) { this(id, reason, null); }
    }
}
