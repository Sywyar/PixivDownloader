package top.sywyar.pixivdownload.schedule.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.work.*;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("手动批次完整账本与重试边界")
class BatchWorkLedgerTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner("fixture", "fixture", 1);

    private ScheduledWork work(String id, String payload) {
        return new ScheduledWork(new ScheduledWorkKey("fixture", id), "fixture.payload", 1, payload, null, List.of());
    }

    @Test
    @DisplayName("重新开始保留成功与失败，重试只携失败作品的完整载荷")
    void retainsResultsAndRetriesFailuresOnly() {
        var ledger = new BatchWorkLedger(json);
        var run = ledger.begin(1);
        var good = work("good", "{\"value\":1}");
        var bad = work("bad", "{\"value\":2}");
        run.discovered(good, owner, 1);
        run.discovered(bad, owner, 1);
        run.markResult(good.key(), ScheduledWorkResult.completed(), ScheduleRunQueue.STATUS_DOWNLOADED, null);
        run.mark(bad.key(), ScheduleRunQueue.STATUS_FAILED, "fixture.retry");
        assertThat(ledger.begin(1)).isSameAs(run);
        assertThat(run.alreadyResolved(good.key())).isTrue();
        assertThat(run.alreadyResolved(bad.key())).isFalse();
        var retries = ledger.retryRows(1, new ScheduleWorkPersistenceCodec(json));
        assertThat(retries).singleElement().satisfies(row -> {
            assertThat(row.workId()).isEqualTo("bad");
            assertThat(row.payloadJson()).isEqualTo(bad.payloadJson());
            assertThat(row.attempts()).isZero();
        });
        run.markResult(bad.key(), ScheduledWorkResult.completed(), ScheduleRunQueue.STATUS_DOWNLOADED, null);
        assertThat(ledger.retryRows(1, new ScheduleWorkPersistenceCodec(json))).isEmpty();
        assertThat(run.snapshot()).hasSize(2);
    }

    @Test
    @DisplayName("累计超限拒绝新作品且不截断已接纳条目，已接纳作品仍可结算")
    void capacityRejectsBeforeAdmissionAndPreservesResults() {
        var ledger = new BatchWorkLedger(json);
        var run = ledger.begin(1);
        String payload = "{\"text\":\"" + "x".repeat(900_000) + "\"}";
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            try { run.discovered(work("item-" + i, payload), owner, 1); accepted++; }
            catch (BatchWorkLedger.CapacityExceeded expected) { break; }
        }
        assertThat(accepted).isBetween(1, 99);
        assertThat(ledger.capacityExceeded()).isTrue();
        assertThat(run.truncated()).isFalse();
        assertThat(run.snapshot()).hasSize(accepted);
        var first = run.snapshot().getFirst().key();
        run.markResult(first, ScheduledWorkResult.completed(), ScheduleRunQueue.STATUS_DOWNLOADED, null);
        assertThat(run.alreadyResolved(first)).isTrue();
        assertThat(run.snapshot()).hasSize(accepted);
    }
}
