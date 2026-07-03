package top.sywyar.pixivdownload.core.download.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefix;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixColumns;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.event.DatabaseReadyEvent;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 启动时把存量绝对路径迁移成 {@code {id}/relative} 形式。
 * 建表 / 补列 / 索引统一由 {@code DatabaseInitializer} 执行，本类监听其在 DDL 全部完成后发布的
 * {@link DatabaseReadyEvent} 触发迁移——事件发布于单例实例化早期（{@code @PostConstruct}），
 * 监听方必须是 {@code ApplicationListener} bean（{@code @EventListener} 注解方法此刻尚未注册）。
 *
 * <p>符号根 {@code {0}} 启用（root-folder 为相对路径）时，本类还负责：
 * <ul>
 *   <li>把 {@code path_prefixes} 中与符号根同路径的 {@code {N}} 行折叠为 {@code {0}} ——
 *       在单个事务内改写全部路径前缀列并删除该前缀行；</li>
 *   <li>维护 {@code state/download_root_marker.txt}：记录符号根上次的解析结果，
 *       启动时比对以发现「配置被直接修改但文件未迁移」的失联场景并告警。</li>
 * </ul>
 *
 * <p>幂等：行内值若已是 {@code {N}/...} 形式则跳过；折叠后前缀行不复存在；可重复启动。
 */
@Slf4j
@Component
public class PathPrefixStartupMigration
        implements ApplicationListener<PayloadApplicationEvent<DatabaseReadyEvent>> {

    private final DataSource dataSource;
    private final PathPrefixCodec codec;
    private final PathPrefixColumns pathPrefixColumns;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final TransactionOperations transactionOperations;

    @Autowired
    public PathPrefixStartupMigration(DataSource dataSource, PathPrefixCodec codec,
                                      PathPrefixColumns pathPrefixColumns,
                                      DownloadConfig downloadConfig, AppMessages messages,
                                      PlatformTransactionManager transactionManager) {
        this(dataSource, codec, pathPrefixColumns, downloadConfig, messages,
                new TransactionTemplate(transactionManager));
    }

    public PathPrefixStartupMigration(DataSource dataSource, PathPrefixCodec codec,
                               PathPrefixColumns pathPrefixColumns,
                               DownloadConfig downloadConfig, AppMessages messages,
                               TransactionOperations transactionOperations) {
        this.dataSource = dataSource;
        this.codec = codec;
        this.pathPrefixColumns = pathPrefixColumns;
        this.downloadConfig = downloadConfig;
        this.messages = messages;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<DatabaseReadyEvent> event) {
        migrate();
    }

    public void migrate() {
        try {
            seedPrefixes();
        } catch (Exception e) {
            log.warn(logMessage("download.db.log.prefix-init-failed", e.getMessage()), e);
            return;
        }
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        try {
            reconcileSymbolicRoot(jdbc);
        } catch (Exception e) {
            log.warn(logMessage("download.db.log.symbolic-root-fold-failed", e.getMessage()), e);
        }
        try {
            int total = 0;
            for (PathPrefixColumns.TableColumns tc : pathPrefixColumns.all()) {
                total += migrateTable(jdbc, tc.table(), tc.idColumn(), tc.columns());
            }
            if (total > 0) {
                log.info(logMessage("download.db.log.migration-complete", total));
            }
        } catch (Exception e) {
            log.warn(logMessage("download.db.log.migration-exception", e.getMessage()), e);
        }
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    /**
     * 预置前缀池：rootFolder + image_classifier.properties 中的 target.folder.N / default.folder
     * + 现有 collections.download_root 中可见的绝对路径。
     * 符号根启用时不再把 rootFolder 注册为 {@code {N}} 行 —— 它由 {@code {0}} 运行期解析覆盖。
     */
    private void seedPrefixes() {
        String rootFolder = downloadConfig.getRootFolder();
        if (!codec.isSymbolicRootActive()) {
            Path rootAbs = Path.of(rootFolder).toAbsolutePath().normalize();
            registerPrefix(rootAbs.toString());
        }

        Path classifierProperties = RuntimeFiles.resolveImageClassifierPath(rootFolder);
        if (Files.isRegularFile(classifierProperties)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(classifierProperties)) {
                props.load(in);
            } catch (IOException e) {
                log.warn(logMessage("download.db.log.read-failed", classifierProperties, e.getMessage()));
                return;
            }
            registerPrefix(props.getProperty("default.folder"));
            for (String key : new TreeMap<>(props).keySet().toArray(new String[0])) {
                if (key.startsWith("target.folder.")) {
                    registerPrefix(props.getProperty(key));
                }
            }
        }
    }

    private void registerPrefix(String value) {
        if (value == null) return;
        String stripped = PathPrefixCodec.stripTrailingSeparators(value.trim());
        if (stripped == null || stripped.isEmpty()) return;
        if (codec.looksEncoded(stripped)) return;
        try {
            codec.getOrCreatePrefixId(stripped);
        } catch (Exception e) {
            log.warn(logMessage("download.db.log.register-prefix-failed", stripped, e.getMessage()));
        }
    }

    // ── 符号根 {0}：marker 比对 + {N} 折叠 ─────────────────────────────────────

    private void reconcileSymbolicRoot(NamedParameterJdbcTemplate jdbc) {
        String current = codec.getSymbolicRootPath();
        boolean hasSymbolicRows = pathPrefixColumns.hasSymbolicRootRows(jdbc);
        if (codec.isSymbolicRootActive()) {
            // 解析位置疑似「改了配置 / 工作目录但没搬文件」时保留 marker：旧路径是后续修复的唯一线索，
            // 且保留后每次启动都会继续告警，直到使用者处理（搬文件或用迁移工具固定记录）。
            boolean keepMarker = warnIfRootMoved(current, hasSymbolicRows);
            foldRootPrefixes(jdbc, current);
            if (!keepMarker) {
                writeMarker(current);
            }
        } else if (hasSymbolicRows) {
            // 孤儿 {0}：root-folder 已是绝对路径（不满足符号根条件），但数据库仍有 {0} 引用。
            // 不覆盖 marker —— 它保存着这些记录真正所在的旧路径，GUI 启动检查会据此引导修复。
            log.warn(logMessage("download.db.log.symbolic-root-orphan",
                    downloadConfig.getRootFolder(), readMarkerQuietly()));
        } else {
            // 符号根未启用且无 {0} 行：marker 已无意义，删除以免日后误报
            deleteMarkerQuietly();
        }
    }

    private String readMarkerQuietly() {
        try {
            Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
            if (Files.isRegularFile(marker)) {
                String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
                return value.isEmpty() ? "?" : value;
            }
        } catch (IOException ignored) {
            // 仅用于日志展示
        }
        return "?";
    }

    private void deleteMarkerQuietly() {
        try {
            Files.deleteIfExists(RuntimeFiles.resolveDownloadRootMarkerPath());
        } catch (IOException e) {
            log.warn(logMessage("download.db.log.symbolic-root-marker-failed", e.getMessage()));
        }
    }

    /**
     * 把 {@code path_prefixes} 中与符号根同路径的 {@code {N}} 行折叠为 {@code {0}}：
     * 单个事务内改写全部路径前缀列并删除该前缀行，绝不留下悬空的 {@code {N}} 引用。
     */
    private int foldRootPrefixes(NamedParameterJdbcTemplate jdbc, String rootPath) {
        String rootNorm = normalize(rootPath);
        List<PathPrefix> matches = codec.snapshot().stream()
                .filter(p -> rootNorm.equals(normalize(p.path())))
                .toList();
        int total = 0;
        for (PathPrefix match : matches) {
            String token = "{" + match.id() + "}";
            Integer rows = transactionOperations.execute(status -> {
                int n = 0;
                for (PathPrefixColumns.TableColumns tc : pathPrefixColumns.all()) {
                    for (String column : tc.columns()) {
                        n += PathPrefixColumns.retargetColumn(jdbc, tc.table(), column,
                                token, PathPrefixCodec.SYMBOLIC_ROOT_TOKEN);
                    }
                }
                jdbc.update("DELETE FROM path_prefixes WHERE id = :id",
                        new MapSqlParameterSource("id", match.id()));
                return n;
            });
            total += rows == null ? 0 : rows;
            log.info(logMessage("download.db.log.symbolic-root-folded",
                    match.id(), match.path(), rows == null ? 0 : rows));
        }
        if (!matches.isEmpty()) {
            codec.reload();
        }
        return total;
    }

    /**
     * marker 比对：符号根的解析结果与上次运行不同且存在 {@code {0}} 行时，说明下载根目录被重定向。
     * 旧位置仍有文件而新位置缺失 / 为空 → 大概率是「改了配置 / 换了工作目录但没搬文件」，告警提示走迁移工具，
     * 并返回 {@code true} 要求保留 marker（旧路径是修复线索，且保证下次启动继续告警）；
     * 否则视为整目录搬迁的正常跟随，仅记 info、返回 {@code false}（marker 随后更新为当前解析结果）。
     */
    private boolean warnIfRootMoved(String current, boolean hasSymbolicRows) {
        Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
        String previous;
        try {
            if (!Files.isRegularFile(marker)) return false;
            previous = Files.readString(marker, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn(logMessage("download.db.log.read-failed", marker, e.getMessage()));
            return false;
        }
        if (previous.isEmpty() || normalize(previous).equals(normalize(current))) return false;
        if (!hasSymbolicRows) return false;
        if (directoryHasEntries(previous) && !directoryHasEntries(current)) {
            log.warn(logMessage("download.db.log.symbolic-root-moved-warn", previous, current));
            return true;
        }
        log.info(logMessage("download.db.log.symbolic-root-repointed", previous, current));
        return false;
    }

    private void writeMarker(String current) {
        Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, current, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn(logMessage("download.db.log.symbolic-root-marker-failed", e.getMessage()));
        }
    }

    private static boolean directoryHasEntries(String path) {
        try {
            Path dir = Path.of(path);
            if (!Files.isDirectory(dir)) return false;
            try (Stream<Path> entries = Files.list(dir)) {
                return entries.findFirst().isPresent();
            }
        } catch (InvalidPathException | IOException e) {
            return false;
        }
    }

    private static String normalize(String value) {
        return PathPrefixCodec.stripTrailingSeparators(value).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private int migrateTable(NamedParameterJdbcTemplate jdbc,
                             String table,
                             String idColumn,
                             List<String> pathColumns) {
        // 注意：表名/列名来自受管 schema 合并出的白名单，未来添加新列时仍需在所属领域
        // contribution 声明 PathColumnSpec，
        // 因此直接字符串拼接是安全的（无外部输入）。
        StringBuilder sql = new StringBuilder("SELECT ").append(idColumn);
        for (String c : pathColumns) sql.append(", ").append(c);
        sql.append(" FROM ").append(table);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql.toString(), new MapSqlParameterSource());
        } catch (Exception e) {
            log.warn(logMessage("download.db.log.scan-failed", table, e.getMessage()));
            return 0;
        }

        int updated = 0;
        for (Map<String, Object> row : rows) {
            Object idObj = row.get(idColumn);
            if (idObj == null) continue;
            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("id", idObj);

            StringBuilder set = new StringBuilder();
            boolean changed = false;
            for (String column : pathColumns) {
                Object current = row.get(column);
                String currentStr = current == null ? null : current.toString();
                if (currentStr == null || currentStr.isEmpty()) continue;
                if (codec.looksEncoded(currentStr)) continue;
                String encoded = codec.encode(currentStr);
                if (encoded.equals(currentStr)) continue; // 无前缀匹配
                if (changed) set.append(", ");
                set.append(column).append(" = :v_").append(column);
                params.addValue("v_" + column, encoded);
                changed = true;
            }
            if (!changed) continue;

            String update = "UPDATE " + table + " SET " + set + " WHERE " + idColumn + " = :id";
            try {
                jdbc.update(update, params);
                updated++;
            } catch (Exception e) {
                log.warn(logMessage("download.db.log.migrate-row-failed", table, idColumn, idObj, e.getMessage()));
            }
        }
        return updated;
    }
}
