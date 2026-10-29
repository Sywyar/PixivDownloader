package top.sywyar.pixivdownload.core.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 仅从完整快照恢复与旧清理结果完全一致的说明，不推测缺失的原文。 */
@Component
@Order(150)
public class WorkDescriptionRepairTask implements MaintenanceTask {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    private final MainDatabase database;
    private final ObjectReader reader;

    public WorkDescriptionRepairTask(MainDatabase database, ObjectMapper mapper) {
        this.database = database;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    @Override public String name() { return "work-description-repair"; }

    @Override public void execute(MaintenanceContext context) throws InterruptedException {
        repair(false);
    }

    /** 只读扫描，返回可修复记录数，不保留全库候选或原文。 */
    public long preview() throws InterruptedException { return repair(true); }

    private long repair(boolean preview) throws InterruptedException {
        long repaired = 0;
        for (WorkType type : WorkType.values()) {
            checkInterrupted();
            String table = type == WorkType.ARTWORK ? "artworks" : "novels";
            String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
            Expression live = call(AND, call(EQUAL, column(table, "deleted"), value(0)),
                    call(NOT, call(IS_NULL, column(table, "metadata_json"))),
                    call(LIKE, column(table, "description"), value("%&amp;%")));
            var last = database.read(s -> s.query(new Query(table, Map.of("id", column(table, key)),
                    live, List.of(), List.of(new Query.Order(column(table, key), true, false)), 1, 0)));
            if (last.isEmpty()) continue;
            long upper = ((Number) last.get(0).get("id")).longValue();
            long cursor = 0;
            while (cursor < upper) {
                checkInterrupted();
                Expression page = call(AND, live, call(GREATER, column(table, key), value(cursor)),
                        call(LESS_EQUAL, column(table, key), value(upper)));
                var ids = database.read(s -> s.query(new Query(table, Map.of("id", column(table, key)),
                        page, List.of(), List.of(new Query.Order(column(table, key), false, false)), PAGE_SIZE, 0)));
                if (ids.isEmpty()) break;
                for (var row : ids) {
                    checkInterrupted();
                    long id = ((Number) row.get("id")).longValue();
                    cursor = id;
                    Expression identity = call(AND, live, call(EQUAL, column(table, key), value(id)));
                    var records = database.read(s -> s.query(new Query(table, Map.of(
                            "description", column(table, "description"), "metadata_json", column(table, "metadata_json")),
                            identity, List.of(), List.of(), 1, 0)));
                    if (records.isEmpty()) continue;
                    String previous = (String) records.get(0).get("description");
                    String snapshot = (String) records.get(0).get("metadata_json");
                    String replacement = replacement(type, id, previous, snapshot);
                    if (replacement == null) continue;
                    if (preview) {
                        repaired++;
                    } else {
                        checkInterrupted();
                        // 原文或快照在扫描后变化时，留给下一次维护重新判断。
                        Expression unchanged = call(AND, identity,
                                call(EQUAL, column(table, "description"), value(previous)),
                                call(EQUAL, column(table, "metadata_json"), value(snapshot)));
                        repaired += database.transaction(s -> s.update(table,
                                Map.of("description", value(replacement)), unchanged));
                    }
                }
            }
        }
        return repaired;
    }

    private String replacement(WorkType type, long id, String previous, String snapshot) throws InterruptedException {
        checkInterrupted();
        if (snapshot == null || previous == null || snapshot.length() > MAX_SNAPSHOT_BYTES
                || snapshot.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) return null;
        try {
            JsonNode document = reader.readTree(snapshot);
            if (document == null || !document.path("schemaVersion").isIntegralNumber()
                    || !document.path("schemaVersion").canConvertToInt()
                    || document.path("schemaVersion").intValue() != 1
                    || !document.path("workId").isIntegralNumber() || !document.path("workId").canConvertToLong()
                    || document.path("workId").longValue() != id
                    || !type.name().equals(document.path("workType").asText())
                    || !document.path("raw").path("description").isTextual()) return null;
            String raw = document.path("raw").path("description").textValue();
            checkInterrupted();
            String replacement = PixivDescriptionHtml.normalizeLinks(raw);
            checkInterrupted();
            if (replacement.equals(previous)) return null;
            String once = LegacyDescriptionHtml.normalizeLinks(raw);
            checkInterrupted();
            if (previous.equals(once)) return replacement;
            String twice = LegacyDescriptionHtml.normalizeLinks(once);
            checkInterrupted();
            return previous.equals(twice) ? replacement : null;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
