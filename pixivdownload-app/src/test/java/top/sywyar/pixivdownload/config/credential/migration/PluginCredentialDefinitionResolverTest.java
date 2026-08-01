package top.sywyar.pixivdownload.config.credential.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件凭证定义解析")
class PluginCredentialDefinitionResolverTest {

    @Test
    @DisplayName("Spring 在存在测试构造器时仍选择生产构造器")
    void springSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    PluginRegistry.class,
                    () -> new PluginRegistry(List.of()));
            context.register(PluginCredentialDefinitionResolver.class);

            context.refresh();

            assertThat(context.getBean(PluginCredentialDefinitionResolver.class))
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("使用注册时捕获的 owner 并同时识别 sensitive 与 PASSWORD")
    void usesCapturedOwnerAndSensitiveContract() {
        MutablePlugin plugin = new MutablePlugin("owner-a", List.of(
                field("owner-a.secret-flag", GuiConfigFieldType.STRING, true),
                field("owner-a.password", GuiConfigFieldType.PASSWORD, false),
                field("owner-a.ordinary", GuiConfigFieldType.STRING, false)));
        PluginRegistry registry = new PluginRegistry(List.of(plugin));
        plugin.id = "owner-b";

        PluginCredentialDefinitionResolver resolver = resolver(registry);

        assertThat(resolver.resolveAll())
                .containsOnlyKeys("owner-a")
                .containsEntry("owner-a", Set.of("owner-a.secret-flag", "owner-a.password"));
        assertThat(resolver.resolveForOwner("owner-b")).isEmpty();
    }

    @Test
    @DisplayName("禁用但仍安装的插件继续参与凭证定义")
    void includesDisabledInstalledPlugin() {
        MutablePlugin plugin = new MutablePlugin("disabled-owner",
                List.of(field("disabled-owner.token", GuiConfigFieldType.PASSWORD, false)));
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.setEnabled("disabled-owner", false);
        PluginRegistry registry = new PluginRegistry(List.of(plugin), toggles);

        PluginCredentialDefinitionResolver resolver = resolver(registry);

        assertThat(registry.registeredPlugins()).isEmpty();
        assertThat(registry.allRegisteredPlugins()).hasSize(1);
        assertThat(resolver.resolveForOwner("disabled-owner"))
                .containsExactly("disabled-owner.token");
    }

    @Test
    @DisplayName("宿主字段冲突会拒绝 owner 且不会遮蔽宿主字段")
    void rejectsHostOwnedCredentialKey() {
        MutablePlugin plugin = new MutablePlugin("host-conflict", List.of(
                field("server.ssl.key-store-password", GuiConfigFieldType.PASSWORD, false),
                field("host-conflict.token", GuiConfigFieldType.PASSWORD, false)));
        PluginRegistry registry = new PluginRegistry(List.of(plugin));
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(
                        registry, () -> Set.of("server.ssl.key-store-password"));

        PluginCredentialDefinitionResolver.Resolution resolution = resolver.resolveSnapshot();

        assertThat(resolver.resolveAll()).doesNotContainKey("host-conflict");
        assertThat(resolution.maskKeys())
                .containsExactly("host-conflict.token")
                .doesNotContain("server.ssl.key-store-password");
        assertThatThrownBy(() -> resolver.resolveForOwner("host-conflict"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owned by the host");
    }

    @Test
    @DisplayName("跨 owner 重复凭证键会同时拒绝冲突 owner")
    void rejectsCredentialKeyDeclaredByMultipleOwners() {
        MutablePlugin first = new MutablePlugin("tts",
                List.of(field("narration-tts.token", GuiConfigFieldType.PASSWORD, false)));
        MutablePlugin second = new MutablePlugin("narration-tts",
                List.of(field("narration-tts.token", GuiConfigFieldType.PASSWORD, false)));
        PluginCredentialDefinitionResolver resolver =
                resolver(new PluginRegistry(List.of(first, second)));

        PluginCredentialDefinitionResolver.Resolution resolution = resolver.resolveSnapshot();

        assertThat(resolution.validDefinitions()).isEmpty();
        assertThat(resolution.maskKeys()).containsExactly("narration-tts.token");
        assertThat(resolution.failures()).containsOnlyKeys("tts", "narration-tts");
    }

    @Test
    @DisplayName("插件声明其它 owner 命名空间时拒绝定义但仍遮蔽敏感键")
    void rejectsForeignNamespaceAndStillMasksCredentialKey() {
        MutablePlugin plugin = new MutablePlugin("other",
                List.of(field("ai.api-key", GuiConfigFieldType.PASSWORD, false)));
        PluginCredentialDefinitionResolver resolver =
                resolver(new PluginRegistry(List.of(plugin)));

        PluginCredentialDefinitionResolver.Resolution resolution = resolver.resolveSnapshot();

        assertThat(resolution.validDefinitions()).doesNotContainKey("other");
        assertThat(resolution.maskKeys()).containsExactly("ai.api-key");
        assertThat(resolution.failures())
                .containsEntry(
                        "other",
                        "credential key namespace does not belong to owner: ai.api-key");
        assertThatThrownBy(() -> resolver.resolveForOwner("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("namespace does not belong to owner");
    }

    @Test
    @DisplayName("短 owner 可以拥有以短横线结尾的复合命名空间")
    void acceptsCompoundNamespaceEndingWithOwner() {
        MutablePlugin plugin = new MutablePlugin("tts",
                List.of(field(
                        "narration-tts.voxcpm.api-key",
                        GuiConfigFieldType.PASSWORD,
                        false)));
        PluginCredentialDefinitionResolver resolver =
                resolver(new PluginRegistry(List.of(plugin)));

        PluginCredentialDefinitionResolver.Resolution resolution = resolver.resolveSnapshot();

        assertThat(resolution.validDefinitions())
                .containsOnlyKeys("tts")
                .containsEntry(
                        "tts",
                        Set.of("narration-tts.voxcpm.api-key"));
        assertThat(resolution.maskKeys())
                .containsExactly("narration-tts.voxcpm.api-key");
        assertThat(resolution.failures()).isEmpty();
    }

    private static PluginCredentialDefinitionResolver resolver(PluginRegistry registry) {
        return new PluginCredentialDefinitionResolver(registry, Set::of);
    }

    private static GuiConfigFieldContribution field(String key,
                                                    GuiConfigFieldType type,
                                                    boolean sensitive) {
        return new GuiConfigFieldContribution(
                key, "fixture", key, "", type, "", 10, sensitive, true);
    }

    private static final class MutablePlugin implements PixivFeaturePlugin {

        private String id;
        private final List<GuiConfigFieldContribution> fields;

        private MutablePlugin(String id, List<GuiConfigFieldContribution> fields) {
            this.id = id;
            this.fields = fields;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "plugin.name";
        }

        @Override
        public String description() {
            return "plugin.description";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<GuiConfigContribution> guiConfigContributions() {
            return List.of(new GuiConfigContribution(fields));
        }
    }
}
