package top.sywyar.pixivdownload.migration.database;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import java.util.*;

/** 仅编排中性迁移会话；报告写入是事务流程的一部分，不承担任何引擎操作。 */
public final class ProviderDatabaseMigration {
    private ProviderDatabaseMigration() {}
    public enum Phase { NOT_STARTED, PREPARING, COPYING, VERIFIED, COMMITTING, COMMITTED, ROLLED_BACK, UNKNOWN }
    public record Store(String owner, MigrationEndpoint source, MigrationEndpoint target, MigrationRequest request,
                        MigrationReplacement replacement) {
        public Store(String owner, MigrationEndpoint source, MigrationEndpoint target, MigrationRequest request) {
            this(owner, source, target, request, null);
        }
    }
    public record StoreReport(String sourceProvider, String source, String targetProvider, String target,
                              Phase phase, String table, long rows, MigrationSnapshot snapshot) {}
    public record Report(int formatVersion, String status, Map<String, StoreReport> stores,
                         String failure, List<String> releaseFailures) {
        public Report { stores = Map.copyOf(stores); releaseFailures = List.copyOf(releaseFailures); }
    }
    @FunctionalInterface public interface ReportWriter { void write(Report report) throws Exception; }
    @FunctionalInterface public interface ReceiptWriter { void verified(Store store, MigrationSnapshot snapshot) throws Exception; }

    public static Report migrate(List<Store> stores, ReportWriter reports, ReceiptWriter receipts,
                                 MigrationControl control) throws Exception {
        return migrate(stores, Map.of(), reports, receipts, control);
    }

    /** 重试仅复用身份、源变化凭据和全部内容均匹配的已提交库；非空目标替换必须另有确认凭据。 */
    public static Report migrate(List<Store> stores, Map<String, StoreReport> previous,
                                 ReportWriter reports, ReceiptWriter receipts, MigrationControl control) throws Exception {
        if (stores.isEmpty() || stores.stream().map(Store::owner).distinct().count() != stores.size())
            throw new IllegalArgumentException("MIGRATION_STORES_INVALID");
        if (!previous.isEmpty() && !previous.keySet().equals(stores.stream().map(Store::owner)
                .collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("MIGRATION_STORES_CHANGED");
        Map<String, StoreReport> state = new LinkedHashMap<>();
        for (var store : stores) {
            if (store.source().target().description().equals(store.target().target().description()))
                throw new IllegalArgumentException("MIGRATION_SAME_TARGET");
            state.put(store.owner(), new StoreReport(store.source().owner(), store.source().target().description(),
                    store.target().owner(), store.target().target().description(), Phase.NOT_STARTED, "", 0, null));
            var retained = previous.get(store.owner());
            if (reusable(retained)) {
                requireIdentity(store, retained);
                state.put(store.owner(), retained);
            }
        }
        List<OpenStore> opened = new ArrayList<>();
        List<String> releaseFailures = new ArrayList<>();
        reports.write(report("IN_PROGRESS", state, null, releaseFailures));
        Exception problem = null;
        try {
            for (var store : stores) {
                control.checkpoint("", 0);
                var retained = previous.get(store.owner());
                boolean reuse = reusable(retained);
                if (!reuse) phase(state, store.owner(), Phase.PREPARING, null);
                reports.write(report("IN_PROGRESS", state, null, releaseFailures));
                var open = new OpenStore(store);
                opened.add(open);
                var progress = new Progress(store.owner(), state, reports, control, releaseFailures);
                if (reuse) {
                    open.committed = true;
                    store.source().provider().verifySource(store.source().target(), retained.snapshot().sourceRevision());
                    store.target().provider().verifyImported(store.target().target(), retained.snapshot(), progress);
                } else {
                    open.destination = Objects.requireNonNull(store.replacement() == null
                            ? store.target().provider().importDatabase(store.target().target(), store.request().schema(), progress)
                            : store.target().provider().replaceDatabase(store.target().target(), store.request().schema(),
                                    store.replacement(), progress));
                }
                open.source = Objects.requireNonNull(store.source().provider().exportDatabase(
                        store.source().target(), store.request(), progress));
                var snapshot = Objects.requireNonNull(open.source.snapshot());
                if (!store.request().schema().equals(snapshot.schema())) throw new IllegalStateException("MIGRATION_SCHEMA_MISMATCH");
                if (reuse) {
                    if (!snapshot.verification().equals(retained.snapshot().verification()))
                        throw new IllegalStateException("MIGRATION_SOURCE_CHANGED");
                    phase(state, store.owner(), Phase.COMMITTED, snapshot);
                    reports.write(report("IN_PROGRESS", state, null, releaseFailures));
                    receipts.verified(store, snapshot);
                    continue;
                }
                phase(state, store.owner(), Phase.COPYING, snapshot);
                reports.write(report("IN_PROGRESS", state, null, releaseFailures));
                for (var table : snapshot.schema().tables()) {
                    progress.checkpoint(table.name(), 0);
                    try (var rows = open.source.rows(table.name())) { open.destination.write(table.name(), rows); }
                }
                if (!snapshot.verification().equals(open.destination.verify(snapshot)))
                    throw new IllegalStateException("DATA_MISMATCH");
                phase(state, store.owner(), Phase.VERIFIED, snapshot);
                reports.write(report("IN_PROGRESS", state, null, releaseFailures));
            }
            for (var open : opened) {
                control.checkpoint("", 0);
                if (open.committed) continue;
                var store = open.store;
                phase(state, store.owner(), Phase.COMMITTING, open.source.snapshot());
                reports.write(report("IN_PROGRESS", state, null, releaseFailures));
                open.commitAttempted = true;
                open.destination.commit();
                open.committed = true;
                phase(state, store.owner(), Phase.COMMITTED, open.source.snapshot());
                reports.write(report("IN_PROGRESS", state, null, releaseFailures));
                receipts.verified(store, open.source.snapshot());
            }
        } catch (Exception failure) {
            problem = failure;
            for (var open : opened) {
                if (open.committed) continue;
                if (open.commitAttempted) phase(state, open.store.owner(), Phase.UNKNOWN, snapshot(open));
                else if (open.destination != null) {
                    try {
                        open.destination.abort();
                        phase(state, open.store.owner(), Phase.ROLLED_BACK, snapshot(open));
                    } catch (Exception rollback) {
                        phase(state, open.store.owner(), Phase.UNKNOWN, snapshot(open));
                        releaseFailures.add(open.store.owner() + ":ABORT_FAILED");
                    }
                }
            }
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) {
                var open = opened.get(i);
                for (AutoCloseable resource : new AutoCloseable[]{open.destination, open.source}) {
                    if (resource != null) try { resource.close(); }
                    catch (Exception close) { releaseFailures.add(open.store.owner() + ":CLOSE_FAILED"); }
                }
            }
        }
        if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
        if (problem != null || !releaseFailures.isEmpty()) {
            String code = problem == null ? "MIGRATION_RELEASE_FAILED" : safeFailure(problem);
            var failed = report("FAILED_TARGET_NOT_VERIFIED", state, code, releaseFailures);
            try { reports.write(failed); }
            catch (Exception reportFailure) {
                releaseFailures.add("REPORT_PERSIST_FAILED");
                failed = report("FAILED_TARGET_NOT_VERIFIED", state, code, releaseFailures);
            }
            throw new MigrationFailure(code, failed);
        }
        var result = report("DATA_VERIFIED", state, null, releaseFailures);
        // 此时全部库已提交；落盘失败必须传播，旧报告仍显示实际逐库进度，不声称回滚。
        reports.write(result);
        return result;
    }
    private static boolean reusable(StoreReport report) {
        return report != null && Set.of(Phase.COMMITTED, Phase.COMMITTING, Phase.UNKNOWN).contains(report.phase());
    }
    /** 切换配置前重新核验全部库，不把私库收据或连接成功当作主库核验。 */
    public static void verifyAll(List<Store> stores, Map<String, StoreReport> reports, MigrationControl control) throws Exception {
        if (!reports.keySet().equals(stores.stream().map(Store::owner).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("MIGRATION_STORES_CHANGED");
        for (var store : stores) {
            var report = reports.get(store.owner());
            requireIdentity(store, report);
            if (report.phase() != Phase.COMMITTED) throw new IllegalStateException("MIGRATION_NOT_COMMITTED");
            store.source().provider().verifySource(store.source().target(), report.snapshot().sourceRevision());
            store.target().provider().verifyImported(store.target().target(), report.snapshot(), control);
        }
    }
    private static void requireIdentity(Store store, StoreReport report) {
        if (report == null || report.snapshot() == null || !store.request().schema().equals(report.snapshot().schema())
                || !store.source().owner().equals(report.sourceProvider()) || !store.target().owner().equals(report.targetProvider())
                || !store.source().target().description().equals(report.source())
                || !store.target().target().description().equals(report.target()))
            throw new IllegalStateException("MIGRATION_IDENTITY_CHANGED");
    }
    private static MigrationSnapshot snapshot(OpenStore open) { return open.source == null ? null : open.source.snapshot(); }
    private static Report report(String status, Map<String, StoreReport> state, String failure, List<String> release) {
        return new Report(1, status, state, failure, release);
    }
    private static void phase(Map<String, StoreReport> state, String owner, Phase phase, MigrationSnapshot snapshot) {
        var previous = state.get(owner);
        state.put(owner, new StoreReport(previous.sourceProvider(), previous.source(), previous.targetProvider(),
                previous.target(), phase, previous.table(), previous.rows(), snapshot));
    }
    private static final class Progress implements MigrationControl {
        private final String owner;
        private final Map<String, StoreReport> state;
        private final ReportWriter reports;
        private final MigrationControl control;
        private final List<String> release;
        private long lastWrite;
        private Progress(String owner, Map<String, StoreReport> state, ReportWriter reports,
                         MigrationControl control, List<String> release) {
            this.owner = owner; this.state = state; this.reports = reports; this.control = control; this.release = release;
        }
        @Override public void checkpoint(String table, long rows) throws Exception {
            control.checkpoint(table, rows);
            var previous = state.get(owner);
            long now = System.nanoTime();
            if (!table.equals(previous.table()) || now - lastWrite >= 1_000_000_000L) {
                state.put(owner, new StoreReport(previous.sourceProvider(), previous.source(), previous.targetProvider(),
                        previous.target(), previous.phase(), table, rows, previous.snapshot()));
                reports.write(report("IN_PROGRESS", state, null, release));
                lastWrite = now;
            }
        }
    }
    public static String safeFailure(Exception failure) {
        if (failure instanceof java.util.concurrent.CancellationException || failure instanceof InterruptedException)
            return "MIGRATION_CANCELLED";
        String message = failure.getMessage();
        if (message != null && switch (message) {
            case "DATABASE_CONFIGURATION_INVALID", "DATABASE_USERNAME_REQUIRED", "DATABASE_PROVIDER_UNAVAILABLE",
                    "DATABASE_PRODUCT_UNSUPPORTED", "DATABASE_ENCODING_UNSUPPORTED", "DATABASE_SCHEMA_MISSING",
                    "DATABASE_TARGET_MISMATCH", "DATABASE_FILE_NOT_FOUND", "DATABASE_FILE_UNAVAILABLE",
                    "DATABASE_PRIVATE_STORAGE_UNAVAILABLE", "DATABASE_STORAGE_PREPARATION_FAILED",
                    "DATABASE_LOCAL_FILE_UNSUPPORTED", "STORAGE_SCHEMA_MISMATCH", "STORAGE_UNSAFE_COLUMN_CHANGE",
                    "SCHEMA_CHECK_UNAVAILABLE", "SCHEMA_MISMATCH", "MIGRATION_SCHEMA_MISMATCH",
                    "UNDECLARED_OBJECT", "UNDECLARED_TABLE", "REBUILD_OBJECT_MISSING", "NOT_REGENERABLE",
                    "PRIVATE_SCHEMA_MISSING", "COLUMN_TYPE", "SEQUENCE_MISSING", "SEQUENCE_UNSUPPORTED",
                    "SEQUENCE_MISMATCH", "TARGET_NOT_WRITABLE", "SOURCE_MISSING", "SOURCE_CORRUPT", "BACKUP_EXISTS",
                    "CONFIG_CHANGED", "MIGRATION_PLAN_CHANGED", "MIGRATION_BUSY",
                    "RESTART_FAILED" -> true;
            default -> false;
        }) return message;
        if (failure instanceof java.sql.SQLException sql) {
            String state = sql.getSQLState();
            return "SQLSTATE_" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN");
        }
        if (failure instanceof java.io.IOException && message != null && message.matches("SQLSTATE_[A-Z0-9]{5}"))
            return message;
        return (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException)
                && message != null && message.matches("[A-Z][A-Z0-9_]*") ? message : "MIGRATION_FAILED";
    }
    public static final class MigrationFailure extends IllegalStateException {
        private final Report report;
        private MigrationFailure(String code, Report report) { super(code); this.report = report; }
        public Report report() { return report; }
    }
    private static final class OpenStore {
        private final Store store;
        private DatabaseExportSession source;
        private DatabaseImportSession destination;
        private boolean commitAttempted, committed;
        private OpenStore(Store store) { this.store = store; }
    }
}
