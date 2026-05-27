package top.sywyar.pixivdownload.mail.preset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.mail.MailSecurity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MailPresetRegistry 单元测试")
class MailPresetRegistryTest {

    private final MailPresetRegistry registry = new MailPresetRegistry();

    @Test
    @DisplayName("预设 id 全部唯一")
    void shouldHaveUniqueIds() {
        Set<String> ids = new HashSet<>();
        for (MailPreset preset : registry.all()) {
            assertThat(ids.add(preset.id()))
                    .as("duplicate preset id: " + preset.id())
                    .isTrue();
        }
        assertThat(ids).contains("custom", "qq", "gmail", "ms365");
    }

    @Test
    @DisplayName("custom 哨兵必须位于列表末尾")
    void customSentinelShouldBeLast() {
        List<MailPreset> all = registry.all();
        assertThat(all.get(all.size() - 1).isCustom()).isTrue();
    }

    @Test
    @DisplayName("按 host 反查命中已知服务商")
    void shouldFindPresetByHost() {
        assertThat(registry.findByHost("smtp.163.com"))
                .isPresent()
                .get()
                .extracting(MailPreset::id)
                .isEqualTo("netease-163");

        // 大小写不敏感
        assertThat(registry.findByHost("SMTP.QQ.COM"))
                .isPresent()
                .get()
                .extracting(MailPreset::id)
                .isEqualTo("qq");
    }

    @Test
    @DisplayName("按 host 反查未命中返回 empty，不会返回 custom 哨兵")
    void shouldNotMatchCustomOnUnknownHost() {
        assertThat(registry.findByHost("smtp.example.com")).isEmpty();
        assertThat(registry.findByHost("")).isEmpty();
        assertThat(registry.findByHost(null)).isEmpty();
    }

    @Test
    @DisplayName("findById 大小写不敏感且可定位 custom")
    void findByIdShouldBeCaseInsensitive() {
        assertThat(registry.findById("GMAIL"))
                .isPresent()
                .get()
                .extracting(MailPreset::id)
                .isEqualTo("gmail");

        assertThat(registry.findById("custom"))
                .isPresent()
                .get()
                .extracting(MailPreset::isCustom)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("企业邮箱预设带 OAuth 警告标记符合预期")
    void oauthWarningMatchesSpec() {
        MailPreset ms365 = registry.findById("ms365").orElseThrow();
        MailPreset googleWorkspace = registry.findById("google-workspace").orElseThrow();
        MailPreset gmail = registry.findById("gmail").orElseThrow();

        assertThat(ms365.oauthWarning()).isTrue();
        assertThat(googleWorkspace.oauthWarning()).isTrue();
        assertThat(gmail.oauthWarning()).isFalse();
    }

    @Test
    @DisplayName("预设的 host / port / security 与设计文档一致")
    void presetFieldsMatchDesign() {
        MailPreset qq = registry.findById("qq").orElseThrow();
        assertThat(qq.host()).isEqualTo("smtp.qq.com");
        assertThat(qq.port()).isEqualTo(465);
        assertThat(qq.security()).isEqualTo(MailSecurity.SSL);

        MailPreset gmail = registry.findById("gmail").orElseThrow();
        assertThat(gmail.host()).isEqualTo("smtp.gmail.com");
        assertThat(gmail.port()).isEqualTo(587);
        assertThat(gmail.security()).isEqualTo(MailSecurity.STARTTLS);
    }
}
