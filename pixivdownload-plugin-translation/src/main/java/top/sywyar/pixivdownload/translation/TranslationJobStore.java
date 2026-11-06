package top.sywyar.pixivdownload.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.sql.SQLException;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 任务与检查点归翻译插件私库；读取状态不加载正文或分段响应。 */
public final class TranslationJobStore {
    static final int MAX_JOBS = 256;
    static final long MAX_CHARS = 256L * 1024 * 1024;
    private static final String JOBS = "translation_jobs", STEPS = "translation_steps";
    private final PluginDatabase database;
    final ObjectMapper json;
    public TranslationJobStore(PluginDatabase database, ObjectMapper json) { this.database = database; this.json = json; }
    public static SchemaContribution schema() {
        return new SchemaContribution(List.of(
                new TableSpec(JOBS, List.of(columnSpec("id", 1), columnSpec("payload", 0)), List.of(), null),
                new TableSpec(STEPS, List.of(columnSpec("job_id", 1), columnSpec("slot", 2),
                        columnSpec("signature", 0), columnSpec("payload", 0),
                        new ColumnSpec("chars", "INTEGER", true, null, 0)), List.of(), null)), List.of(), List.of());
    }
    private static ColumnSpec columnSpec(String name, int key) { return new ColumnSpec(name, "TEXT", true, null, key); }
    private static Expression eq(String table, String key, Object value) { return call(EQUAL, column(table, key), value(value)); }
    private static Query query(String table, Expression where, int limit, String... fields) {
        Map<String, Expression> selected = new LinkedHashMap<>();
        for (String field : fields) selected.put(field, column(table, field));
        return new Query(table, selected, where, List.of(), List.of(), limit, 0);
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalStateException("translation.job-storage", e); }
    }
    <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (java.io.IOException e) { throw new IllegalStateException("translation.job-storage", e); }
    }
    List<TranslationJobs.Job> all() {
        try {
            return database.read(s -> s.query(query(JOBS, null, MAX_JOBS, "payload"))).stream()
                    .map(row -> decode((String) row.get("payload"), TranslationJobs.Job.class)).toList();
        } catch (SQLException e) { throw failure(e); }
    }
    TranslationJobs.Job get(UUID id) {
        try {
            var rows = database.read(s -> s.query(query(JOBS, eq(JOBS, "id", id.toString()), 1, "payload")));
            return rows.isEmpty() ? null : decode((String) rows.get(0).get("payload"), TranslationJobs.Job.class);
        } catch (SQLException e) { throw failure(e); }
    }
    synchronized void create(TranslationJobs.Job job) {
        var completed = all().stream().filter(j -> "COMPLETED".equals(j.state))
                .sorted(Comparator.comparingLong(j -> j.updatedAt)).toList();
        long cutoff = System.currentTimeMillis() - java.time.Duration.ofDays(7).toMillis();
        for (var old : completed) if (old.updatedAt < cutoff) discard(old.id);
        if (all().size() >= MAX_JOBS && !completed.isEmpty()) discard(completed.get(0).id);
        try {
            database.transaction(s -> {
                long count = (Long) s.query(Query.count(JOBS, null)).get(0).get("count");
                if (count >= MAX_JOBS) throw new IllegalStateException("translation.job-capacity");
                s.insert(JOBS, Map.of("id", job.id.toString(), "payload", encode(job)), DatabaseSession.Conflict.error());
                return null;
            });
        } catch (SQLException e) { throw failure(e); }
    }
    synchronized void save(TranslationJobs.Job job) {
        job.updatedAt = System.currentTimeMillis();
        try {
            database.transaction(s -> s.update(JOBS, Map.of("payload", value(encode(job))), eq(JOBS, "id", job.id.toString())));
        } catch (SQLException e) { throw failure(e); }
    }
    synchronized void advance(TranslationJobs.Job job, String prefix) {
        job.updatedAt = System.currentTimeMillis();
        try {
            database.transaction(s -> {
                s.update(JOBS, Map.of("payload", value(encode(job))), eq(JOBS, "id", job.id.toString()));
                Expression where = eq(STEPS, "job_id", job.id.toString());
                if (prefix != null) where = call(AND, where, call(LIKE, column(STEPS, "slot"), value(prefix + ":%")));
                s.delete(STEPS, where);
                return null;
            });
        } catch (SQLException e) { throw failure(e); }
    }
    record Step(String signature, String payload) {}
    Step step(UUID id, String slot) {
        try {
            var rows = database.read(s -> s.query(query(STEPS, stepKey(id, slot), 1, "signature", "payload")));
            return rows.isEmpty() ? null : new Step((String) rows.get(0).get("signature"), (String) rows.get(0).get("payload"));
        } catch (SQLException e) { throw failure(e); }
    }
    synchronized void checkpoint(TranslationJobs.Job job, String slot, String signature, String payload) {
        job.updatedAt = System.currentTimeMillis();
        try {
            database.transaction(s -> {
                var totals = s.query(new Query(STEPS, Map.of("total", call(SUM, column(STEPS, "chars"))),
                        null, List.of(), List.of(), 1, 0));
                Number total = (Number) totals.get(0).get("total");
                if (payload.length() > MAX_CHARS - (total == null ? 0 : total.longValue()))
                    throw new IllegalStateException("translation.job-capacity");
                s.insert(STEPS, Map.of("job_id", job.id.toString(), "slot", slot, "signature", signature,
                        "payload", payload, "chars", (long) payload.length()), DatabaseSession.Conflict.error());
                s.update(JOBS, Map.of("payload", value(encode(job))), eq(JOBS, "id", job.id.toString()));
                return null;
            });
        } catch (SQLException e) { throw failure(e); }
    }
    synchronized void discard(UUID id) {
        try {
            database.transaction(s -> {
                s.delete(STEPS, eq(STEPS, "job_id", id.toString()));
                s.delete(JOBS, eq(JOBS, "id", id.toString()));
                return null;
            });
        } catch (SQLException e) { throw failure(e); }
    }
    private static Expression stepKey(UUID id, String slot) {
        return call(AND, eq(STEPS, "job_id", id.toString()), eq(STEPS, "slot", slot));
    }
    private static IllegalStateException failure(SQLException error) { return new IllegalStateException("translation.job-storage", error); }
}
