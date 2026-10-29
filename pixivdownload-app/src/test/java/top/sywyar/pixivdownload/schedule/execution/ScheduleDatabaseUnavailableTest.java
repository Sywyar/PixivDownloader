package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("计划执行的数据库缺席边界")
class ScheduleDatabaseUnavailableTest {
    @Test
    @DisplayName("直接执行和凭据绑定均在规划、网络探活及 pending 写入前拒绝")
    void unavailableDatabasePreventsPlanningAndCredentials() {
        var store = mock(ScheduledTaskStore.class);
        var registry = mock(ScheduleCapabilityAccess.class);
        var mapper = new ObjectMapper();
        var engine = new ScheduleExecutionEngine(store, registry, new ScheduleRunState(), new ScheduleRunQueue(),
                new ScheduleConfig(), new ScheduleWorkPersistenceCodec(mapper),
                mock(ScheduleNetworkRouteResolver.class), new SyncTaskExecutor(), mapper, () -> false);
        assertThat(engine.canResolve(null)).isFalse();
        assertThatThrownBy(() -> engine.execute(null)).isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> engine.prepareCredentialBinding(null, "unused"))
                .isInstanceOf(DatabaseUnavailableException.class);
        verifyNoInteractions(store, registry);
    }
}
