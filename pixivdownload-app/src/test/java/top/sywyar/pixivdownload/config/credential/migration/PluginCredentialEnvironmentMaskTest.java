package top.sywyar.pixivdownload.config.credential.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件凭证父环境遮罩")
class PluginCredentialEnvironmentMaskTest {

    private static final String AI_KEY = "ai.api-key";
    private static final String DECLARED_ONLY_KEY = "custom.private-value";
    private static final String HOST_SSL_KEY = "server.ssl.key-store-password";

    @Test
    @DisplayName("遮罩从非空缩减至空时旧敏感键仍不回显且宿主密码保持可见")
    void retainsTombstonesAcrossShrinkAndEmptyReplacement() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "legacy-config",
                Map.of(
                        AI_KEY, "legacy-ai-secret",
                        DECLARED_ONLY_KEY, "legacy-declared-secret",
                        HOST_SSL_KEY, "host-ssl-secret")));
        PluginCredentialEnvironmentMask mask =
                new PluginCredentialEnvironmentMask(environment);

        mask.replace(Set.of(AI_KEY, DECLARED_ONLY_KEY), Set.of(HOST_SSL_KEY));
        assertMaskedWithoutHidingHost(environment);

        mask.replace(Set.of(AI_KEY), Set.of(HOST_SSL_KEY));
        assertMaskedWithoutHidingHost(environment);

        mask.replace(Set.of(), Set.of(HOST_SSL_KEY));
        assertMaskedWithoutHidingHost(environment);
        assertThat(mask.maskKeys())
                .contains(AI_KEY, DECLARED_ONLY_KEY, HOST_SSL_KEY);
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(PluginCredentialEnvironmentMask.PROPERTY_SOURCE_NAME);
    }

    private static void assertMaskedWithoutHidingHost(StandardEnvironment environment) {
        assertThat(environment.getProperty(AI_KEY)).isEmpty();
        assertThat(environment.getProperty(DECLARED_ONLY_KEY)).isEmpty();
        assertThat(environment.getProperty(HOST_SSL_KEY)).isEqualTo("host-ssl-secret");
    }
}
