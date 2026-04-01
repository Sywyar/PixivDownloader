package top.sywyar.pixivdownload.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserQuotaService 单元测试")
class UserQuotaServiceTest {

    @Mock
    private DownloadConfig downloadConfig;
    @Mock
    private PixivDatabase pixivDatabase;

    private MultiModeConfig multiModeConfig;
    private UserQuotaService userQuotaService;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        multiModeConfig.getQuota().setEnabled(true);
        multiModeConfig.getQuota().setMaxArtworks(3);
        multiModeConfig.getQuota().setResetPeriodHours(24);
        multiModeConfig.getQuota().setArchiveExpireMinutes(60);

        userQuotaService = new UserQuotaService(multiModeConfig, downloadConfig, pixivDatabase);
    }

    // ========== checkAndReserve ==========

    @Nested
    @DisplayName("checkAndReserve - 配额检查与预留")
    class CheckAndReserveTests {

        @Test
        @DisplayName("首次请求应允许")
        void shouldAllowFirstRequest() {
            UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve("user1", 1);

            assertThat(result.allowed()).isTrue();
            assertThat(result.artworksUsed()).isEqualTo(1);
            assertThat(result.maxArtworks()).isEqualTo(3);
        }

        @Test
        @DisplayName("达到配额上限后应拒绝")
        void shouldRejectWhenQuotaExceeded() {
            userQuotaService.checkAndReserve("user1", 1);
            userQuotaService.checkAndReserve("user1", 1);
            userQuotaService.checkAndReserve("user1", 1);

            UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve("user1", 1);
            assertThat(result.allowed()).isFalse();
            assertThat(result.artworksUsed()).isEqualTo(3);
        }

        @Test
        @DisplayName("不同用户应有独立配额")
        void shouldHaveIndependentQuotaPerUser() {
            userQuotaService.checkAndReserve("user1", 1);
            userQuotaService.checkAndReserve("user1", 1);
            userQuotaService.checkAndReserve("user1", 1);

            // user2 应有独立的配额
            UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve("user2", 1);
            assertThat(result.allowed()).isTrue();
            assertThat(result.artworksUsed()).isEqualTo(1);
        }
    }

    // ========== checkAndReserve - limit-image 权重 ==========

    @Nested
    @DisplayName("checkAndReserve - limit-image 多作品权重")
    class LimitImageTests {

        @BeforeEach
        void setLimitImage() {
            multiModeConfig.getQuota().setLimitImage(3);
            multiModeConfig.getQuota().setMaxArtworks(5);
            userQuotaService = new UserQuotaService(multiModeConfig, downloadConfig, pixivDatabase);
        }

        @Test
        @DisplayName("图片数未超限时权重为 1")
        void shouldWeightOneWhenUnderLimit() {
            UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve("u1", 3);
            assertThat(result.allowed()).isTrue();
            assertThat(result.artworksUsed()).isEqualTo(1);
        }

        @Test
        @DisplayName("图片数超限时权重为 ceil(count/limit)")
        void shouldWeightMultipleWhenOverLimit() {
            // 7张图 / 3 = ceil(2.33) = 3 个作品
            UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve("u1", 7);
            assertThat(result.allowed()).isTrue();
            assertThat(result.artworksUsed()).isEqualTo(3);
        }

        @Test
        @DisplayName("权重超出剩余配额时应拒绝且不消耗剩余配额")
        void shouldRejectWhenWeightExceedsRemainingAndNotConsumeQuota() {
            // ceil(4/3)=2，used=2
            userQuotaService.checkAndReserve("u1", 4);
            // weight=1，used=3
            userQuotaService.checkAndReserve("u1", 3);
            // ceil(9/3)=3，used+3=6 > 5，应拒绝
            UserQuotaService.QuotaCheckResult rejected = userQuotaService.checkAndReserve("u1", 9);
            assertThat(rejected.allowed()).isFalse();
            // 拒绝后剩余 2 个配额应未被消耗，单张图片仍可下载
            UserQuotaService.QuotaCheckResult next = userQuotaService.checkAndReserve("u1", 1);
            assertThat(next.allowed()).isTrue();
            assertThat(next.artworksUsed()).isEqualTo(4); // 3 + 1，未加上被拒绝的 3
        }

        @Test
        @DisplayName("剩余1个配额时权重>1的作品应拒绝且不消耗该配额")
        void shouldNotConsumeLastSlotWhenWeightExceedsIt() {
            // max=5，先消耗4个：weight=ceil(4/3)=2 → used=2，再 weight=1 两次 → used=4
            userQuotaService.checkAndReserve("u1", 4); // used=2
            userQuotaService.checkAndReserve("u1", 1); // used=3
            userQuotaService.checkAndReserve("u1", 1); // used=4，剩余1个
            // 权重=2的作品：4+2=6 > 5，应拒绝
            UserQuotaService.QuotaCheckResult rejected = userQuotaService.checkAndReserve("u1", 4);
            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.artworksUsed()).isEqualTo(4); // 未变化
            // 剩余的1个配额仍可被单张图片使用
            UserQuotaService.QuotaCheckResult last = userQuotaService.checkAndReserve("u1", 1);
            assertThat(last.allowed()).isTrue();
            assertThat(last.artworksUsed()).isEqualTo(5);
        }

        @Test
        @DisplayName("limitImage=0 时始终权重为 1")
        void shouldAlwaysWeightOneWhenLimitDisabled() {
            multiModeConfig.getQuota().setLimitImage(0);
            UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve("u1", 100);
            assertThat(result.allowed()).isTrue();
            assertThat(result.artworksUsed()).isEqualTo(1);
        }
    }

    // ========== recordFolder ==========

    @Nested
    @DisplayName("recordFolder - 记录下载文件夹")
    class RecordFolderTests {

        @Test
        @DisplayName("记录文件夹后应可在配额中查到")
        void shouldRecordFolder() {
            userQuotaService.checkAndReserve("user1", 1); // 创建用户配额
            userQuotaService.recordFolder("user1", Path.of("/path/to/folder"));

            UserQuotaService.UserQuota quota = userQuotaService.getQuotaForUser("user1");
            assertThat(quota).isNotNull();
            assertThat(quota.getDownloadedFolders()).contains(Path.of("/path/to/folder"));
        }

        @Test
        @DisplayName("null uuid/folder 应不抛异常")
        void shouldHandleNullInputs() {
            assertThatCode(() -> userQuotaService.recordFolder(null, Path.of("/path")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> userQuotaService.recordFolder("user1", null))
                    .doesNotThrowAnyException();
        }
    }

    // ========== getQuotaStatus ==========

    @Nested
    @DisplayName("getQuotaStatus - 配额状态查询")
    class GetQuotaStatusTests {

        @Test
        @DisplayName("无配额记录的用户应返回默认值")
        void shouldReturnDefaultForNewUser() {
            UserQuotaService.QuotaStatusResult status = userQuotaService.getQuotaStatus("newuser");

            assertThat(status.artworksUsed()).isZero();
            assertThat(status.maxArtworks()).isEqualTo(3);
            assertThat(status.archive()).isNull();
        }

        @Test
        @DisplayName("已使用配额的用户应返回正确数值")
        void shouldReturnCorrectUsageForExistingUser() {
            userQuotaService.checkAndReserve("user1", 1);
            userQuotaService.checkAndReserve("user1", 1);

            UserQuotaService.QuotaStatusResult status = userQuotaService.getQuotaStatus("user1");
            assertThat(status.artworksUsed()).isEqualTo(2);
        }
    }

    // ========== checkAndReserveProxy ==========

    @Nested
    @DisplayName("checkAndReserveProxy - 代理请求限流")
    class CheckAndReserveProxyTests {

        @Test
        @DisplayName("首次代理请求应允许")
        void shouldAllowFirstProxyRequest() {
            assertThat(userQuotaService.checkAndReserveProxy("user1")).isTrue();
        }

        @Test
        @DisplayName("达到上限后应拒绝")
        void shouldRejectWhenProxyLimitReached() {
            for (int i = 0; i < 3; i++) {
                userQuotaService.checkAndReserveProxy("user1");
            }
            assertThat(userQuotaService.checkAndReserveProxy("user1")).isFalse();
        }
    }

    // ========== checkAndReservePack ==========

    @Nested
    @DisplayName("checkAndReservePack - 打包频率限流")
    class CheckAndReservePackTests {

        @Test
        @DisplayName("首次打包请求应允许")
        void shouldAllowFirstPackRequest() {
            assertThat(userQuotaService.checkAndReservePack("user1")).isTrue();
        }

        @Test
        @DisplayName("达到上限后应拒绝")
        void shouldRejectWhenPackLimitReached() {
            for (int i = 0; i < 3; i++) {
                userQuotaService.checkAndReservePack("user1");
            }
            assertThat(userQuotaService.checkAndReservePack("user1")).isFalse();
        }
    }

    // ========== triggerArchive ==========

    @Nested
    @DisplayName("triggerArchive - 触发打包")
    class TriggerArchiveTests {

        @Test
        @DisplayName("应返回有效的 token")
        void shouldReturnValidToken() {
            userQuotaService.checkAndReserve("user1", 1);
            String token = userQuotaService.triggerArchive("user1");

            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("应能通过 token 查到压缩包信息")
        void shouldFindArchiveByToken() {
            userQuotaService.checkAndReserve("user1", 1);
            String token = userQuotaService.triggerArchive("user1");

            UserQuotaService.ArchiveEntry entry = userQuotaService.getArchive(token);
            assertThat(entry).isNotNull();
            assertThat(entry.getToken()).isEqualTo(token);
            assertThat(entry.getUserUuid()).isEqualTo("user1");
        }
    }

    // ========== deleteArchive ==========

    @Test
    @DisplayName("删除不存在的 token 应无异常")
    void shouldHandleDeleteNonExistentArchive() {
        assertThatCode(() -> userQuotaService.deleteArchive("nonexistent"))
                .doesNotThrowAnyException();
    }

    // ========== generateUuidFromFingerprint ==========

    @Nested
    @DisplayName("generateUuidFromFingerprint - UUID 生成")
    class GenerateUuidTests {

        @Test
        @DisplayName("相同输入应生成相同 UUID")
        void shouldGenerateConsistentUuid() {
            String uuid1 = UserQuotaService.generateUuidFromFingerprint("127.0.0.1", "Chrome/100");
            String uuid2 = UserQuotaService.generateUuidFromFingerprint("127.0.0.1", "Chrome/100");

            assertThat(uuid1).isEqualTo(uuid2);
        }

        @Test
        @DisplayName("不同输入应生成不同 UUID")
        void shouldGenerateDifferentUuidForDifferentInput() {
            String uuid1 = UserQuotaService.generateUuidFromFingerprint("127.0.0.1", "Chrome/100");
            String uuid2 = UserQuotaService.generateUuidFromFingerprint("192.168.1.1", "Firefox/100");

            assertThat(uuid1).isNotEqualTo(uuid2);
        }

        @Test
        @DisplayName("null 输入应不抛异常")
        void shouldHandleNullInputs() {
            assertThatCode(() -> UserQuotaService.generateUuidFromFingerprint(null, null))
                    .doesNotThrowAnyException();

            String uuid = UserQuotaService.generateUuidFromFingerprint(null, null);
            assertThat(uuid).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("生成的 UUID 应符合标准格式")
        void shouldGenerateValidUuidFormat() {
            String uuid = UserQuotaService.generateUuidFromFingerprint("127.0.0.1", "Chrome");

            assertThat(uuid).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            );
        }
    }

    // ========== UserQuota inner class ==========

    @Nested
    @DisplayName("UserQuota - 用户配额对象")
    class UserQuotaTests {

        @Test
        @DisplayName("reset 应重置所有计数器")
        void shouldResetAllCounters() {
            UserQuotaService.UserQuota quota = new UserQuotaService.UserQuota("testuser");
            quota.getArtworksUsed().set(5);
            quota.getProxyCount().set(10);
            quota.getDownloadedFolders().add(Path.of("/test"));
            quota.setArchiveToken("some-token");

            quota.reset();

            assertThat(quota.getArtworksUsed().get()).isZero();
            assertThat(quota.getProxyCount().get()).isZero();
            assertThat(quota.getDownloadedFolders()).isEmpty();
            assertThat(quota.getArchiveToken()).isNull();
        }

        @Test
        @DisplayName("resetPackWindow 应仅重置打包计数")
        void shouldResetPackCountOnly() {
            UserQuotaService.UserQuota quota = new UserQuotaService.UserQuota("testuser");
            quota.getPackCount().set(5);
            quota.getArtworksUsed().set(3);

            quota.resetPackWindow();

            assertThat(quota.getPackCount().get()).isZero();
            assertThat(quota.getArtworksUsed().get()).isEqualTo(3); // 不受影响
        }
    }
}
