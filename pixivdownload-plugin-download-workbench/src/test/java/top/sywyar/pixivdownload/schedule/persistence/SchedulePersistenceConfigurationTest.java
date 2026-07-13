package top.sywyar.pixivdownload.schedule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.schedule.ScheduleHostPluginConfiguration;
import top.sywyar.pixivdownload.schedule.persistence.migration.PixivLegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.schedule.persistence.migration.PixivLegacySchedulePersistenceDescriptorProvider;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 计划任务持久化装配")
class SchedulePersistenceConfigurationTest {

    @Test
    @DisplayName("下载工作台子上下文显式提供 codec、旧数据适配器和独立持久化规范")
    void providesCodecAndMigrationAdapter() {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ObjectMapper objectMapper = new ObjectMapper();

        PixivSchedulePersistenceCodec codec =
                configuration.pixivSchedulePersistenceCodec(objectMapper);
        PixivLegacyScheduledTaskMigrationAdapter adapter =
                configuration.pixivLegacyScheduledTaskMigrationAdapter(objectMapper, codec);
        PixivLegacySchedulePersistenceDescriptorProvider descriptorProvider =
                configuration.pixivLegacySchedulePersistenceDescriptorProvider();

        assertThat(codec).isNotNull();
        assertThat(adapter).isNotNull();
        assertThat(descriptorProvider.legacySchedulePersistenceDescriptors()).hasSize(7);
    }
}
