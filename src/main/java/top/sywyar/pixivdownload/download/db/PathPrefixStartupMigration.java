package top.sywyar.pixivdownload.download.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 启动时把存量绝对路径迁移成 {@code {id}/relative} 形式。
 * 依赖所有相关 @Repository / @Service 的 {@code @PostConstruct} 已建表完成，
 * 因此本类显式注入 {@link PixivDatabase} / {@link NovelDatabase} / {@link MangaSeriesService} /
 * {@link CollectionService} 以触发 Spring 的初始化顺序。
 *
 * <p>幂等：行内值若已是 {@code {N}/...} 形式则跳过；可重复启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathPrefixStartupMigration {

    private final DataSource dataSource;
    private final PathPrefixCodec codec;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;

    // 仅为触发 @PostConstruct 顺序，确保被迁移表已建好
    @SuppressWarnings("unused")
    private final PixivDatabase pixivDatabase;
    @SuppressWarnings("unused")
    private final NovelDatabase novelDatabase;
    @SuppressWarnings("unused")
    private final MangaSeriesService mangaSeriesService;
    @SuppressWarnings("unused")
    private final CollectionService collectionService;

    @PostConstruct
    public void migrate() {
        try {
            seedPrefixes();
        } catch (Exception e) {
            log.warn(logMessage("download.db.log.prefix-init-failed", e.getMessage()), e);
            return;
        }
        try {
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
            int total = 0;
            total += migrateTable(jdbc, "artworks", "artwork_id", List.of("folder", "move_folder"));
            total += migrateTable(jdbc, "novels", "novel_id", List.of("folder"));
            total += migrateTable(jdbc, "manga_series", "series_id", List.of("cover_folder"));
            total += migrateTable(jdbc, "novel_series", "series_id", List.of("cover_folder"));
            total += migrateTable(jdbc, "collections", "id", List.of("download_root"));
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
     */
    private void seedPrefixes() {
        String rootFolder = downloadConfig.getRootFolder();
        Path rootAbs = Path.of(rootFolder).toAbsolutePath().normalize();
        registerPrefix(rootAbs.toString());

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

    private int migrateTable(NamedParameterJdbcTemplate jdbc,
                             String table,
                             String idColumn,
                             List<String> pathColumns) {
        // 注意：表名/列名为白名单常量，未来添加新列时仍需在这里登记，
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
