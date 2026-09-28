package top.sywyar.pixivdownload.core.asset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

/** 外部作品的只读文件引用。删除画廊记录后保留引用作为文件所有权标记。 */
@Repository
public class ExternalWorkFiles {
    private final JdbcTemplate jdbc;
    private final PathPrefixCodec paths;
    public ExternalWorkFiles(DataSource source, PathPrefixCodec paths, DatabaseInitializer initializer) {
        jdbc = new JdbcTemplate(source);
        this.paths = paths;
    }
    public boolean contains(WorkType type, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM external_work_files WHERE work_type = ? AND work_id = ? AND record_time = CASE work_type WHEN 'ARTWORK' THEN (SELECT time FROM artworks WHERE artwork_id = work_id) ELSE (SELECT time FROM novels WHERE novel_id = work_id) END)",
                Boolean.class, type.name(), id));
    }
    public List<Path> files(WorkType type, long id) {
        return jdbc.query("SELECT root_path, file_path FROM external_work_files WHERE work_type = ? AND work_id = ? AND record_time = CASE work_type WHEN 'ARTWORK' THEN (SELECT time FROM artworks WHERE artwork_id = work_id) ELSE (SELECT time FROM novels WHERE novel_id = work_id) END ORDER BY page",
                (rs, index) -> {
                    Path root = Path.of(paths.resolve(rs.getString(1))).toAbsolutePath().normalize();
                    Path file = Path.of(paths.resolve(rs.getString(2))).toAbsolutePath().normalize();
                    return file.startsWith(root) && PlainFilePathGuard.isPlainRegularFile(file) ? file : null;
                }, type.name(), id);
    }
    public Path file(WorkType type, long id, int page) {
        if (page < 0) return null;
        List<Path> matches = jdbc.query("SELECT root_path, file_path FROM external_work_files WHERE work_type = ? AND work_id = ? AND page = ? AND record_time = CASE work_type WHEN 'ARTWORK' THEN (SELECT time FROM artworks WHERE artwork_id = work_id) ELSE (SELECT time FROM novels WHERE novel_id = work_id) END",
                (rs, index) -> {
                    Path root = Path.of(paths.resolve(rs.getString(1))).toAbsolutePath().normalize();
                    Path file = Path.of(paths.resolve(rs.getString(2))).toAbsolutePath().normalize();
                    return file.startsWith(root) && PlainFilePathGuard.isPlainRegularFile(file) ? file : null;
                }, type.name(), id, page);
        return matches.isEmpty() ? null : matches.get(0);
    }
    public void save(WorkFileImportRequest request) {
        jdbc.update("DELETE FROM external_work_files WHERE work_type = ? AND work_id = ?", request.workType().name(), request.workId());
        Long time = jdbc.queryForObject(request.workType() == WorkType.ARTWORK
                ? "SELECT time FROM artworks WHERE artwork_id = ?" : "SELECT time FROM novels WHERE novel_id = ?", Long.class, request.workId());
        for (int page = 0; page < request.pageFiles().size(); page++) {
            Path file = request.sourceRoot().resolve(request.pageFiles().get(page)).toAbsolutePath().normalize();
            jdbc.update("INSERT INTO external_work_files(reference_id, work_type, work_id, page, root_path, file_path, record_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    request.workType().name() + ":" + request.workId() + ":" + page,
                    request.workType().name(), request.workId(), page,
                    paths.encode(request.sourceRoot().toString()), paths.encode(file.toString()), time);
        }
    }
}
