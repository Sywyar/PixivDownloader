package top.sywyar.pixivdownload.core.asset;

import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;

import java.nio.file.Path;
import java.util.List;

/** 文件备份的耐久意图；业务记录与提交标记使用同一主库事务。 */
@Repository
public class FileOperationJournal {
    private static final String TABLE = "file_operation_entries";
    private final MainDatabase database;
    private final TransactionTemplate transaction;
    private final PathPrefixCodec paths;

    public FileOperationJournal(MainDatabase database, PlatformTransactionManager manager,
                                PathPrefixCodec paths, DatabaseInitializer initializer) {
        this.database = database;
        this.paths = paths;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Entry(int index, Path target, String oldHash, String newHash) {}

    /** 文件恢复意图依赖原安装的暂存副本，不能随数据库转移到另一安装。 */
    public static boolean hasPendingFileOperations(top.sywyar.pixivdownload.plugin.api.database.migration.MigrationVerification snapshot) {
        var table = snapshot.tables().get(TABLE);
        return table != null && table.rows() != 0;
    }

    public static boolean hasPendingFileOperations(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget target,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws java.sql.SQLException {
        try (var connection = target.openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            if (!target.readSchema(connection).containsKey(TABLE)) return false;
            try (var session = target.storageSession(connection, schema)) {
                return !session.query(new Query(TABLE, Map.of("entry_id", column(TABLE, "entry_id")), null,
                        List.of(), List.of(), 1, 0)).isEmpty();
            }
        }
    }

    public boolean isUnavailable() {
        return !database.available();
    }

    public void prepare(String operation, List<Entry> entries) {
        // 恢复意图引用已提交的固定前缀，不随后续业务事务回滚撤回。
        var targets = new java.util.ArrayList<String>(entries.size());
        for (Entry entry : entries) {
            long prefix = paths.forceCreatePrefixId(entry.target().getParent().toString());
            targets.add("{" + prefix + "}/" + entry.target().getFileName());
        }
        transaction.executeWithoutResult(status -> database.transaction(session -> {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                // 恢复必须指向操作发生时的物理路径，不能跟随后来变更的下载符号根。
                var row = new LinkedHashMap<String, Object>();
                row.put("entry_id", operation + ":" + entry.index());
                row.put("operation_id", operation);
                row.put("ordinal", entry.index());
                row.put("target_path", targets.get(index));
                row.put("old_hash", entry.oldHash());
                row.put("new_hash", entry.newHash());
                row.put("committed", 0L);
                session.insert(TABLE, row, DatabaseSession.Conflict.error());
            }
            return null;
        }));
    }

    public void commit(String operation, Runnable records) {
        transaction.executeWithoutResult(status -> {
            records.run();
            database.transaction(s -> s.update(TABLE, Map.of("committed", value(1)), operation(operation)));
        });
    }

    private static Expression operation(String operation) {
        return call(EQUAL, column(TABLE, "operation_id"), value(operation));
    }

    public boolean committed(String operation) {
        return database.read(s -> s.query(new Query(TABLE,
                Map.of("committed", call(COALESCE, call(MIN, column(TABLE, "committed")), value(0))),
                operation(operation), List.of(), List.of(), 1, 0))).get(0).get("committed").equals(1L);
    }

    public List<String> operations() {
        return database.read(s -> s.query(new Query(new Query.Table(TABLE), List.of(),
                Map.of("operation_id", column(TABLE, "operation_id")), null, List.of(), null, List.of(),
                true, Integer.MAX_VALUE, 0))).stream().map(row -> (String) row.get("operation_id")).toList();
    }

    public List<Entry> entries(String operation) {
        return database.read(s -> s.query(new Query(TABLE, Map.of("ordinal", column(TABLE, "ordinal"),
                "target_path", column(TABLE, "target_path"), "old_hash", column(TABLE, "old_hash"),
                "new_hash", column(TABLE, "new_hash")), operation(operation), List.of(),
                List.of(new Query.Order(column(TABLE, "ordinal"), false, false)), Integer.MAX_VALUE, 0))).stream()
                .map(row -> new Entry(Math.toIntExact((Long) row.get("ordinal")),
                        Path.of(paths.resolve((String) row.get("target_path"))).toAbsolutePath().normalize(),
                        (String) row.get("old_hash"), (String) row.get("new_hash"))).toList();
    }

    public void forget(String operation) {
        database.transaction(s -> s.delete(TABLE, operation(operation)));
    }
}
