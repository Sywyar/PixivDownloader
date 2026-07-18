package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主数据库连接池设置")
class DatabasePoolPropertiesTest {

    @Test
    @DisplayName("默认容量保持 28 且手写过小值按安全下限收敛")
    void preservesDefaultCapacityAndClampsUnsafeValues() {
        DatabasePoolProperties properties = new DatabasePoolProperties();

        assertThat(properties.getMaximumPoolSize()).isEqualTo(28);
        properties.setMaximumPoolSize(3);
        assertThat(properties.getMaximumPoolSize()).isEqualTo(8);
    }

    @Test
    @DisplayName("从宿主 database 前缀绑定显式池容量")
    void bindsExplicitHostPoolCapacity() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("database.maximum-pool-size", "36");

        DatabasePoolProperties properties = Binder.get(environment)
                .bind("database", Bindable.of(DatabasePoolProperties.class))
                .orElseThrow(() -> new AssertionError("database properties should bind"));

        assertThat(properties.getMaximumPoolSize()).isEqualTo(36);
    }
}
