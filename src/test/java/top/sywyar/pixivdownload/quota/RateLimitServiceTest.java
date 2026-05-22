package top.sywyar.pixivdownload.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RateLimitService 单元测试")
class RateLimitServiceTest {

    private MultiModeConfig multiModeConfig;
    private GuestInviteConfig guestInviteConfig;
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        guestInviteConfig = new GuestInviteConfig();
        rateLimitService = new RateLimitService(multiModeConfig, guestInviteConfig, TestI18nBeans.appMessages());
    }

    @Nested
    @DisplayName("速率限制启用时")
    class WhenLimitEnabled {

        @BeforeEach
        void setLimit() {
            multiModeConfig.setRequestLimitMinute(3);
        }

        @Test
        @DisplayName("在同一分钟内请求次数超过限制后应返回 429")
        void shouldDenyRequestsAfterLimitExceeded() {
            String uuid = "test-uuid-001";

            // 前 3 次请求应放行
            assertThat(rateLimitService.isAllowed(uuid)).isTrue();
            assertThat(rateLimitService.isAllowed(uuid)).isTrue();
            assertThat(rateLimitService.isAllowed(uuid)).isTrue();

            // 第 4 次及之后超出限制，应被拒绝（对应 429）
            assertThat(rateLimitService.isAllowed(uuid)).isFalse();
            assertThat(rateLimitService.isAllowed(uuid)).isFalse();
        }

        @Test
        @DisplayName("不同 UUID 的计数互相独立")
        void shouldTrackCountersPerUuid() {
            String uuid1 = "test-uuid-001";
            String uuid2 = "test-uuid-002";

            // uuid1 用完 3 次额度
            rateLimitService.isAllowed(uuid1);
            rateLimitService.isAllowed(uuid1);
            rateLimitService.isAllowed(uuid1);
            assertThat(rateLimitService.isAllowed(uuid1)).isFalse();

            // uuid2 不受 uuid1 影响，仍可正常请求
            assertThat(rateLimitService.isAllowed(uuid2)).isTrue();
        }

        @Test
        @DisplayName("恰好到达限制时最后一次请求应被允许")
        void shouldAllowRequestExactlyAtLimit() {
            String uuid = "test-uuid-003";

            for (int i = 0; i < 2; i++) {
                rateLimitService.isAllowed(uuid);
            }
            // 第 3 次恰好到达上限，应放行
            assertThat(rateLimitService.isAllowed(uuid)).isTrue();
            // 第 4 次超出上限，应拒绝
            assertThat(rateLimitService.isAllowed(uuid)).isFalse();
        }
    }

    @Nested
    @DisplayName("邀请访客限流（按邀请码、guest-invite 上限）")
    class WhenInviteGuest {

        @Test
        @DisplayName("邀请访客使用 guest-invite 上限，与多人模式上限互不影响")
        void shouldUseGuestInviteLimitIndependentlyFromMultiMode() {
            guestInviteConfig.setRequestLimitMinute(2);
            multiModeConfig.setRequestLimitMinute(0); // 多人模式不限流

            String inviteKey = "invite:ABC123";
            assertThat(rateLimitService.isAllowedForInvite(inviteKey)).isTrue();
            assertThat(rateLimitService.isAllowedForInvite(inviteKey)).isTrue();
            // 第 3 次超出 guest-invite 上限
            assertThat(rateLimitService.isAllowedForInvite(inviteKey)).isFalse();

            // 多人模式 UUID 路径不受 guest-invite 上限影响
            assertThat(rateLimitService.isAllowed("uuid-x")).isTrue();
        }

        @Test
        @DisplayName("guest-invite 上限为 0 时邀请访客不限流")
        void shouldAllowAllWhenInviteLimitIsZero() {
            guestInviteConfig.setRequestLimitMinute(0);
            for (int i = 0; i < 1000; i++) {
                assertThat(rateLimitService.isAllowedForInvite("invite:Z")).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("速率限制禁用时")
    class WhenLimitDisabled {

        @Test
        @DisplayName("requestLimitMinute 为 0 时所有请求均放行")
        void shouldAllowAllRequestsWhenLimitIsZero() {
            multiModeConfig.setRequestLimitMinute(0);
            String uuid = "test-uuid-004";

            for (int i = 0; i < 1000; i++) {
                assertThat(rateLimitService.isAllowed(uuid)).isTrue();
            }
        }

        @Test
        @DisplayName("requestLimitMinute 为负数时所有请求均放行")
        void shouldAllowAllRequestsWhenLimitIsNegative() {
            multiModeConfig.setRequestLimitMinute(-1);
            String uuid = "test-uuid-005";

            for (int i = 0; i < 10; i++) {
                assertThat(rateLimitService.isAllowed(uuid)).isTrue();
            }
        }
    }
}
