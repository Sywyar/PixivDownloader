package top.sywyar.pixivdownload.core.asset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

/** 文件备份的耐久意图；业务记录与提交标记使用同一主库事务。 */
@Repository
public class FileOperationJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PathPrefixCodec paths;

    public FileOperationJournal(DataSource source, PlatformTransactionManager manager,
                                PathPrefixCodec paths, DatabaseInitializer initializer) {
        this.jdbc = new JdbcTemplate(source);
        this.paths = paths;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Entry(int index, Path target, String oldHash, String newHash) {}

    public void prepare(String operation, List<Entry> entries) {
        // 前缀注册会刷新运行期缓存，先独立提交，避免后续意图事务回滚留下不存在的前缀。
        var targets = new java.util.ArrayList<String>(entries.size());
        for (Entry entry : entries) {
            long prefix = paths.forceCreatePrefixId(entry.target().getParent().toString());
            targets.add("{" + prefix + "}/" + entry.target().getFileName());
        }
        transaction.executeWithoutResult(status -> {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                // 恢复必须指向操作发生时的物理路径，不能跟随后来变更的下载符号根。
                jdbc.update("INSERT INTO file_operation_entries(entry_id, operation_id, ordinal, target_path, old_hash, new_hash, committed) VALUES (?, ?, ?, ?, ?, ?, 0)",
                        operation + ":" + entry.index(), operation, entry.index(), targets.get(index), entry.oldHash(), entry.newHash());
            }
        });
    }

    public void commit(String operation, Runnable records) {
        transaction.executeWithoutResult(status -> {
            records.run();
            jdbc.update("UPDATE file_operation_entries SET committed = 1 WHERE operation_id = ?", operation);
        });
    }

    public boolean committed(String operation) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COALESCE(MIN(committed), 0) FROM file_operation_entries WHERE operation_id = ?",
                Boolean.class, operation));
    }

    public List<String> operations() {
        return jdbc.queryForList("SELECT DISTINCT operation_id FROM file_operation_entries", String.class);
    }

    public List<Entry> entries(String operation) {
        return jdbc.query("SELECT ordinal, target_path, old_hash, new_hash FROM file_operation_entries WHERE operation_id = ? ORDER BY ordinal",
                (rs, row) -> new Entry(rs.getInt(1), Path.of(paths.resolve(rs.getString(2))).toAbsolutePath().normalize(),
                        rs.getString(3), rs.getString(4)), operation);
    }

    public void forget(String operation) {
        jdbc.update("DELETE FROM file_operation_entries WHERE operation_id = ?", operation);
    }
}
