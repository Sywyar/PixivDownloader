package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.quota.MultiModeConfig;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StaticResourceRateLimitService 单元测试")
class StaticResourceRateLimitServiceTest {

    private MultiModeConfig multiModeConfig;
    private StaticResourceRateLimitService service;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        service = new StaticResourceRateLimitService(multiModeConfig);
    }

    @Nested
    @DisplayName("限流启用时")
    class WhenLimitEnabled {

        @BeforeEach
        void setLimit() {
            multiModeConfig.setStaticResourceRequestLimitMinute(3);
        }

        @Test
        @DisplayName("同一 IP 超出每分钟限制后应拒绝")
        void shouldDenyRequestsAfterLimitExceeded() {
            String ip = "203.0.113.10";

            assertThat(service.isAllowed(ip)).isTrue();
            assertThat(service.isAllowed(ip)).isTrue();
            assertThat(service.isAllowed(ip)).isTrue();
            assertThat(service.isAllowed(ip)).isFalse();
        }

        @Test
        @DisplayName("不同 IP 的计数应互相独立")
        void shouldTrackCountersPerIp() {
            String ip1 = "203.0.113.10";
            String ip2 = "203.0.113.11";

            service.isAllowed(ip1);
            service.isAllowed(ip1);
            service.isAllowed(ip1);

            assertThat(service.isAllowed(ip1)).isFalse();
            assertThat(service.isAllowed(ip2)).isTrue();
        }
    }

    @Nested
    @DisplayName("限流禁用时")
    class WhenLimitDisabled {

        @Test
        @DisplayName("配置为 0 时应全部放行")
        void shouldAllowAllRequestsWhenLimitIsZero() {
            multiModeConfig.setStaticResourceRequestLimitMinute(0);

            for (int i = 0; i < 1000; i++) {
                assertThat(service.isAllowed("203.0.113.10")).isTrue();
            }
        }

        @Test
        @DisplayName("配置为负数时应全部放行")
        void shouldAllowAllRequestsWhenLimitIsNegative() {
            multiModeConfig.setStaticResourceRequestLimitMinute(-1);

            for (int i = 0; i < 10; i++) {
                assertThat(service.isAllowed("203.0.113.10")).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("跟踪 IP 数量上限")
    class WhenTrackedIpsAtCapacity {

        @Test
        @DisplayName("达到上限后新 IP 应被拒绝，已记录 IP 仍正常计数")
        void shouldDenyNewIpsAtCapacityButKeepExistingTracked() {
            multiModeConfig.setStaticResourceRequestLimitMinute(1000);

            for (int i = 0; i < StaticResourceRateLimitService.MAX_TRACKED_IPS; i++) {
                assertThat(service.isAllowed("10.0." + (i >>> 8) + "." + (i & 0xFF))).isTrue();
            }

            assertThat(service.isAllowed("203.0.113.99")).isFalse();
            assertThat(service.isAllowed("10.0.0.0")).isTrue();
        }
    }
}
