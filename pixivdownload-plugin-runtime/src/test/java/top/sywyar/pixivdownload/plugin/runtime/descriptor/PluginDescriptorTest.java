package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("统一插件描述符：映射、校验与兼容性")
class PluginDescriptorTest {

    @Test
    @DisplayName("内置插件描述符：版本=核心契约版本、requires=当前核心（恒兼容）、pluginClass=实现类名、无依赖")
    void forBuiltInMapsMetadata() {
        PluginDescriptor descriptor = PluginDescriptor.forBuiltIn(new TestFeaturePlugin("stats"));

        assertThat(descriptor.id()).isEqualTo("stats");
        assertThat(descriptor.sourcePluginId()).isEqualTo("stats");
        assertThat(descriptor.version()).isEqualTo(PluginApiVersion.VERSION);
        assertThat(descriptor.requires().isSatisfiedByCurrentApi()).isTrue();
        assertThat(descriptor.dependencies()).isEmpty();
        assertThat(descriptor.pluginClass()).isEqualTo(TestFeaturePlugin.class.getName());
        assertThat(descriptor.displayName()).isEqualTo("stats.label");
        assertThat(descriptor.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(descriptor.lifecyclePolicy()).isEqualTo(PluginLifecyclePolicy.HOT_RELOAD);
        assertThat(descriptor.isApiCompatible()).isTrue();
        assertThat(descriptor.validationErrors()).isEmpty();
        assertThat(descriptor.externalValidationErrors()).isEmpty();
    }

    @Test
    @DisplayName("显式稳定 id 构造内置描述符时不再读取插件身份 getter")
    void forBuiltInUsesCapturedStableId() {
        PluginDescriptor descriptor = PluginDescriptor.forBuiltIn(new IdRejectingPlugin(), "stable-plugin");

        assertThat(descriptor.id()).isEqualTo("stable-plugin");
        assertThat(descriptor.sourcePluginId()).isEqualTo("stable-plugin");
        assertThat(descriptor.validationErrors()).isEmpty();
    }

    @Test
    @DisplayName("完整外置描述符通过通用与外置完整性校验")
    void completeExternalDescriptorIsValid() {
        PluginDescriptor descriptor = external("ext-stats", "1.2.0", "1.0",
                "com.example.ExtStatsPlugin", "ext.label", PluginKind.FEATURE, List.of());

        assertThat(descriptor.validationErrors()).isEmpty();
        assertThat(descriptor.externalValidationErrors()).isEmpty();
        assertThat(descriptor.isApiCompatible()).isTrue();
    }

    @Test
    @DisplayName("非法 id / 空 displayName / null kind 均被通用校验拒绝")
    void rejectsInvalidIdentityFields() {
        assertThat(external("Bad_Id", "1.0", "1.0", "com.example.P", "x.label", PluginKind.FEATURE, List.of())
                .validationErrors()).anyMatch(e -> e.contains("invalid plugin id"));
        assertThat(external("ext", "1.0", "1.0", "com.example.P", "  ", PluginKind.FEATURE, List.of())
                .validationErrors()).anyMatch(e -> e.contains("displayName"));
        assertThat(external("ext", "1.0", "1.0", "com.example.P", "x.label", null, List.of())
                .validationErrors()).anyMatch(e -> e.contains("kind"));
    }

    @Test
    @DisplayName("plugin-class 非法（不是合法 FQN）被通用校验拒绝")
    void rejectsMalformedPluginClass() {
        assertThat(external("ext", "1.0", "1.0", "123.bad-class", "x.label", PluginKind.FEATURE, List.of())
                .validationErrors()).anyMatch(e -> e.contains("invalid plugin-class"));
    }

    @Test
    @DisplayName("外置描述符缺 version / plugin-class：通用校验放行，外置完整性校验拒绝")
    void externalCompletenessRequiresVersionAndPluginClass() {
        PluginDescriptor missing = external("ext", "  ", "1.0", "  ", "x.label", PluginKind.FEATURE, List.of());

        // 通用校验不强制 version / plugin-class（内置插件可无 PF4J Plugin-Class）
        assertThat(missing.validationErrors()).isEmpty();
        // 外置完整性校验补上这两项
        assertThat(missing.externalValidationErrors())
                .anyMatch(e -> e.contains("version"))
                .anyMatch(e -> e.contains("plugin-class"));
    }

    @Test
    @DisplayName("不可解析的 requires：通用校验拒绝、判为不兼容")
    void rejectsUnparseableRequires() {
        PluginDescriptor descriptor = new PluginDescriptor("ext", "ext-pack", "1.0",
                PluginApiRequirement.parse("latest"), List.of(), "com.example.P", "ns", "x.label", null, null, null, PluginKind.FEATURE);

        assertThat(descriptor.validationErrors()).anyMatch(e -> e.contains("unparseable requires"));
        assertThat(descriptor.isApiCompatible()).isFalse();
    }

    @Test
    @DisplayName("requires 版本过高（MINOR 超核心）：描述符判为 API 不兼容")
    void higherRequiresIsIncompatible() {
        PluginDescriptor descriptor = external("ext", "1.0", PluginApiVersion.MAJOR + "." + (PluginApiVersion.MINOR + 1),
                "com.example.P", "x.label", PluginKind.FEATURE, List.of());
        assertThat(descriptor.isApiCompatible()).isFalse();
        // 校验本身不因 API 不兼容失败（不兼容是独立状态，非描述符缺陷）
        assertThat(descriptor.validationErrors()).isEmpty();
    }

    @Test
    @DisplayName("外置 version 非合法 semver（残缺 1.0 / 1、非版本串 latest）：通用校验放行、外置完整性校验拒绝")
    void externalVersionMustBeSemver() {
        for (String badVersion : new String[]{"1.0", "1", "latest", "1.0.x", "v1.0.0", "1.0.0.0"}) {
            PluginDescriptor descriptor = external("ext", badVersion, "1.0",
                    "com.example.P", "x.label", PluginKind.FEATURE, List.of());
            // 通用校验不约束 version 形态（内置插件版本恒为核心契约版本，无需 PF4J 风格 semver）
            assertThat(descriptor.validationErrors()).isEmpty();
            // 外置完整性校验要求合法 semver，否则报错（错误信息含 "version"）
            assertThat(descriptor.externalValidationErrors())
                    .as("external version '%s' must be rejected", badVersion)
                    .anyMatch(e -> e.contains("version"));
        }
    }

    @Test
    @DisplayName("外置 version 合法 semver（major.minor.patch，含可选 -prerelease / +build）通过外置完整性校验")
    void externalVersionAcceptsSemverForms() {
        for (String okVersion : new String[]{"1.0.0", "2.3.1", "1.0.0-SNAPSHOT", "2.3.1-rc.1+build.9", "0.0.1"}) {
            PluginDescriptor descriptor = external("ext", okVersion, "1.0",
                    "com.example.P", "x.label", PluginKind.FEATURE, List.of());
            assertThat(descriptor.externalValidationErrors())
                    .as("external version '%s' must be accepted", okVersion)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("非法依赖 id 被通用校验拒绝")
    void rejectsInvalidDependencyId() {
        PluginDescriptor descriptor = external("ext", "1.0", "1.0", "com.example.P", "x.label",
                PluginKind.FEATURE, List.of(new PluginDependencyRef("Bad Dep", "1.0", false)));
        assertThat(descriptor.validationErrors()).anyMatch(e -> e.contains("dependency plugin id"));
    }

    @Test
    @DisplayName("替代声明拒绝非法 id、自替代和重复身份")
    void rejectsInvalidReplacementIds() {
        PluginDescriptor descriptor = new PluginDescriptor("novel", "novel", "1.0.0",
                PluginApiRequirement.parse("1.0"), List.of(), "com.example.NovelPlugin", null,
                "novel.label", null, null, null, PluginKind.FEATURE,
                List.of("Bad_Id", "novel", "novel-gallery", "novel-gallery"));

        assertThat(descriptor.validationErrors())
                .anyMatch(error -> error.contains("invalid replaced plugin id"))
                .anyMatch(error -> error.contains("must not replace itself"))
                .anyMatch(error -> error.contains("must be unique"));
    }

    @Test
    @DisplayName("运行期功能描述符合并清单专属替代关系和生命周期策略")
    void attachesPackageOnlyMetadata() {
        PluginDescriptor runtimeDescriptor = external("gui-theme", "1.0.0", "1.0",
                "com.example.ThemePlugin", "theme.label", PluginKind.FEATURE, List.of());
        PluginDescriptor packageDescriptor = new PluginDescriptor("gui-theme-pack", "gui-theme-pack", "1.0.0",
                PluginApiRequirement.parse("1.0"), List.of(), "com.example.ThemePlugin", null,
                "package.label", null, null, null, PluginKind.FEATURE, List.of("legacy-theme"),
                PluginLifecyclePolicy.PROCESS_RESTART);

        PluginDescriptor attached = runtimeDescriptor.withPackageMetadataFrom(packageDescriptor);

        assertThat(attached.displayName()).isEqualTo("theme.label");
        assertThat(attached.replaces()).containsExactly("legacy-theme");
        assertThat(attached.lifecyclePolicy()).isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART);
    }

    private static PluginDescriptor external(String id, String version, String requires, String pluginClass,
                                             String displayName, PluginKind kind, List<PluginDependencyRef> deps) {
        return new PluginDescriptor(id, id + "-pack", version, PluginApiRequirement.parse(requires),
                deps, pluginClass, null, displayName, null, null, null, kind);
    }

    private static final class TestFeaturePlugin implements PixivFeaturePlugin {
        private final String id;

        TestFeaturePlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id + ".label";
        }

        @Override
        public String description() {
            return id + ".summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }

    private static final class IdRejectingPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            throw new AssertionError("id getter must not be called");
        }

        @Override
        public String displayName() {
            return "stable.label";
        }

        @Override
        public String description() {
            return "stable.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }
}
