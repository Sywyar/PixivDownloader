package top.sywyar.pixivdownload.migration.database;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration.*;

@DisplayName("中性迁移编排的失败、提交和资源生命周期")
class ProviderDatabaseMigrationTest {
    @TempDir Path directory;
    private final List<Report> reports = new ArrayList<>();
    private final MigrationControl control = (table, rows) -> {};
    private final ReceiptWriter receipts = (store, snapshot) -> {};
    private final MigrationSchema schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
            new ColumnSpec("id", "INTEGER", true, null, 1)), List.of())), List.of(), List.of());

    private record Fixture(Store store, DatabaseProvider sourceProvider, DatabaseExportSession source,
                           DatabaseImportSession target, MigrationRows rows) {}
    private Fixture fixture(String owner) throws Exception {
        var sourceProvider = mock(DatabaseProvider.class);
        var targetProvider = mock(DatabaseProvider.class);
        var sourceTarget = mock(DatabaseConnectionTarget.class);
        var targetTarget = mock(DatabaseConnectionTarget.class);
        when(sourceTarget.description()).thenReturn("native-source:" + owner);
        when(targetTarget.description()).thenReturn("native-target:" + owner);
        var request = new MigrationRequest(schema, directory.resolve(owner + ".backup"), Set.of());
        var snapshot = new MigrationSnapshot(1, schema, new MigrationVerification(Map.of("facts",
                new MigrationVerification.TableDigest(0, MigrationValues.finish(MigrationValues.newDigest()))),
                Map.of("facts", 0L), Map.of()), request.backup(), "fixture-revision:1", List.of());
        var source = mock(DatabaseExportSession.class);
        var target = mock(DatabaseImportSession.class);
        var rows = mock(MigrationRows.class);
        when(source.snapshot()).thenReturn(snapshot);
        when(source.rows("facts")).thenReturn(rows);
        when(sourceProvider.exportDatabase(eq(sourceTarget), eq(request), any())).thenReturn(source);
        when(targetProvider.importDatabase(eq(targetTarget), eq(schema), any())).thenReturn(target);
        when(target.verify(snapshot)).thenReturn(snapshot.verification());
        return new Fixture(new Store(owner, new MigrationEndpoint("source-provider", sourceProvider, sourceTarget),
                new MigrationEndpoint("target-provider", targetProvider, targetTarget), request), sourceProvider, source, target, rows);
    }
    private MigrationFailure failed(List<Store> stores, ReportWriter writer, ReceiptWriter receiptWriter,
                                   MigrationControl checkpoints) {
        return catchThrowableOfType(() -> migrate(stores, writer, receiptWriter, checkpoints), MigrationFailure.class);
    }
    @Test @DisplayName("全部主私库核验后才提交，完成前关闭双方与每张表游标")
    void verifiesAllBeforeAnyCommit() throws Exception {
        var main = fixture("main"); var owner = fixture("owner");
        var result = migrate(List.of(main.store(), owner.store()), reports::add, receipts, control);
        var order = inOrder(main.target(), owner.target());
        order.verify(main.target()).verify(any()); order.verify(owner.target()).verify(any());
        order.verify(main.target()).commit(); order.verify(owner.target()).commit();
        for (var f : List.of(main, owner)) { verify(f.source()).close(); verify(f.target()).close(); verify(f.rows()).close(); }
        assertThat(result.status()).isEqualTo("DATA_VERIFIED");
        assertThat(result.stores().values()).allMatch(state -> state.phase() == Phase.COMMITTED);
    }
    @Test @DisplayName("迁出失败中止已准备目标并释放资源，不记录成功")
    void sourceFailureAbortsDestination() throws Exception {
        var f = fixture("main");
        when(f.sourceProvider().exportDatabase(any(), any(), any())).thenThrow(new IllegalStateException("SOURCE_FAILED"));
        var error = failed(List.of(f.store()), reports::add, receipts, control);
        assertThat(error.report().stores().get("main").phase()).isEqualTo(Phase.ROLLED_BACK);
        verify(f.target()).abort(); verify(f.target()).close(); verify(f.target(), never()).commit();
        assertThat(reports.get(reports.size() - 1).status()).isEqualTo("FAILED_TARGET_NOT_VERIFIED");
    }
    @Test @DisplayName("迁入写入失败和读取取消均中止，错误行不进入报告或异常")
    void writingFailureAndCancellationReleaseBothEnds() throws Exception {
        for (Exception cause : List.of(new java.sql.SQLException("secret row and password", "22021"),
                new CancellationException("secret cancel details"))) {
            var f = fixture("main"); doThrow(cause).when(f.target()).write(anyString(), any());
            var error = failed(List.of(f.store()), reports::add, receipts, control);
            assertThat(error).hasNoCause().hasMessageNotContaining("secret");
            assertThat(error.report().failure()).isEqualTo(cause instanceof CancellationException ? "MIGRATION_CANCELLED" : "SQLSTATE_22021");
            verify(f.target()).abort(); verify(f.target()).close(); verify(f.source()).close(); verify(f.rows()).close();
        }
    }
    @Test @DisplayName("摘要不一致时所有库保持未提交")
    void mismatchedVerificationAbortsAllStores() throws Exception {
        var first = fixture("main"); var second = fixture("owner");
        when(second.target().verify(any())).thenReturn(new MigrationVerification(Map.of(), Map.of(), Map.of()));
        var error = failed(List.of(first.store(), second.store()), reports::add, receipts, control);
        assertThat(error).hasMessage("DATA_MISMATCH");
        for (var f : List.of(first, second)) { verify(f.target()).abort(); verify(f.target(), never()).commit(); }
    }
    @Test @DisplayName("部分提交不能报告整体回滚，失败提交保留未知结果")
    void partialCommitIsNeverReportedAsRollback() throws Exception {
        var first = fixture("main"); var second = fixture("owner");
        doThrow(new java.sql.SQLException("connection lost", "08006")).when(second.target()).commit();
        var error = failed(List.of(first.store(), second.store()), reports::add, receipts, control);
        assertThat(error.report().stores().get("main").phase()).isEqualTo(Phase.COMMITTED);
        assertThat(error.report().stores().get("owner").phase()).isEqualTo(Phase.UNKNOWN);
        for (var f : List.of(first, second)) { verify(f.target(), never()).abort(); verify(f.target()).close(); verify(f.source()).close(); }
    }
    @Test @DisplayName("提交后的收据失败保留已提交状态，中止其余库")
    void receiptFailurePreservesCommittedState() throws Exception {
        var first = fixture("main"); var second = fixture("owner");
        var error = failed(List.of(first.store(), second.store()), reports::add,
                (store, snapshot) -> { throw new java.io.IOException("disk full"); }, control);
        assertThat(error.report().stores().get("main").phase()).isEqualTo(Phase.COMMITTED);
        assertThat(error.report().stores().get("owner").phase()).isEqualTo(Phase.ROLLED_BACK);
        verify(first.target(), never()).abort(); verify(second.target()).abort();
    }
    @Test @DisplayName("进度报告无法落盘时不继续提交，记录报告失败")
    void persistenceFailurePreventsCommit() throws Exception {
        var f = fixture("main");
        var error = failed(List.of(f.store()), report -> {
            if (report.stores().get("main").phase() == Phase.COMMITTING || report.status().startsWith("FAILED"))
                throw new java.io.IOException("disk full");
            reports.add(report);
        }, receipts, control);
        assertThat(error.report().releaseFailures()).contains("REPORT_PERSIST_FAILED");
        verify(f.target(), never()).commit(); verify(f.target()).abort(); verify(f.source()).close();
    }
    @Test @DisplayName("关闭失败保持提交事实且拒绝总体成功")
    void releaseFailureCannotBecomeSuccess() throws Exception {
        var f = fixture("main"); doThrow(new java.io.IOException("release")).when(f.source()).close();
        var error = failed(List.of(f.store()), reports::add, receipts, control);
        assertThat(error.report().stores().get("main").phase()).isEqualTo(Phase.COMMITTED);
        assertThat(error.report().releaseFailures()).contains("main:CLOSE_FAILED");
        verify(f.target(), never()).abort();
    }

    @Test @DisplayName("重试复核已提交主库而不重复写入，再迁移剩余私库")
    void retriesOnlyAfterVerifyingCommittedStores() throws Exception {
        var main = fixture("main"); var owner = fixture("owner");
        var first = failed(List.of(main.store(), owner.store()), reports::add,
                (store, snapshot) -> { throw new java.io.IOException("receipt"); }, control);
        clearInvocations(main.target(), main.store().target().provider(), owner.target());
        var result = migrate(List.of(main.store(), owner.store()), first.report().stores(), reports::add, receipts, control);
        var snapshot = main.source().snapshot();
        verify(main.store().target().provider()).verifyImported(any(), eq(snapshot), any());
        verify(main.store().target().provider(), never()).importDatabase(any(), any(), any());
        verify(main.target(), never()).commit();
        verify(owner.target()).commit();
        assertThat(result.stores().values()).allMatch(value -> value.phase() == Phase.COMMITTED);
    }

    @Test @DisplayName("重试源已变化时拒绝并保留目标实际提交状态")
    void retryFailureRetainsCommittedReport() throws Exception {
        var f = fixture("main");
        var committed = migrate(List.of(f.store()), reports::add, receipts, control);
        clearInvocations(f.target(), f.store().target().provider());
        doThrow(new IllegalStateException("SOURCE_CHANGED")).when(f.sourceProvider()).verifySource(any(), anyString());
        var error = catchThrowableOfType(() -> migrate(List.of(f.store()), committed.stores(),
                reports::add, receipts, control), MigrationFailure.class);
        assertThat(error.report().stores().get("main")).isEqualTo(committed.stores().get("main"));
        verifyNoInteractions(f.target(), f.store().target().provider());
    }

    @Test @DisplayName("切换前核验全部库，目标变化或缺失私库均拒绝")
    void switchingRequiresEveryStoreAndItsActualContents() throws Exception {
        var main = fixture("main"); var owner = fixture("owner");
        var stores = List.of(main.store(), owner.store());
        var committed = migrate(stores, reports::add, receipts, control);
        doThrow(new IllegalStateException("DATA_MISMATCH")).when(owner.store().target().provider())
                .verifyImported(any(), any(), any());
        assertThatThrownBy(() -> verifyAll(stores, committed.stores(), control)).hasMessage("DATA_MISMATCH");
        verify(main.store().target().provider()).verifyImported(any(), any(), any());
        assertThatThrownBy(() -> verifyAll(stores, Map.of("main", committed.stores().get("main")), control))
                .hasMessage("MIGRATION_STORES_CHANGED");
    }
}
