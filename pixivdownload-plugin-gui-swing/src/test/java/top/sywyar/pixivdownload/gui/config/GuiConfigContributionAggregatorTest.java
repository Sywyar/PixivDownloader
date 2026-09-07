package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 插件配置分组聚合")
class GuiConfigContributionAggregatorTest {
    @BeforeEach
    void installHost() {
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "message" -> args[0];
                    default -> throw new AssertionError("Unexpected host call: " + method.getName());
                }
        );
        SwingHost.install(new DesktopUiContext(
                false,
                6999,
                ".",
                Path.of("config.yaml"),
                "gui-swing",
                host,
                List.of(),
                List::of,
                text -> "plugin.name".equals(text.key()) ? "Name " + text.namespace() : text.fallback(),
                () -> "system"
        ));
    }

    @Test
    @DisplayName("省略分组声明时保留字段和区段并按活动插件名称生成分组")
    void infersGroupsFromActivePluginMetadata() {
        DesktopUiPluginSnapshot alpha = snapshot("alpha", "shared", List.of());
        DesktopUiPluginSnapshot beta = snapshot("beta", "shared", List.of());
        for (List<DesktopUiPluginSnapshot> sources : List.of(List.of(alpha, beta), List.of(beta, alpha))) {
            GuiConfigContributionSnapshot result = GuiConfigContributionAggregator.fromRegisteredPlugins(sources);
            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.fields()).extracting(ConfigFieldSpec::key)
                    .containsExactlyInAnyOrder("alpha.enabled", "beta.enabled");
            assertThat(result.sections()).extracting(GuiConfigSectionSpec::sectionId)
                    .containsExactlyInAnyOrder("alpha.settings", "beta.settings");
            assertThat(ConfigFieldRegistry.snapshot(result).groups()).containsExactly("Name alpha");
        }

        GuiConfigContributionSnapshot remaining = GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(beta));
        assertThat(remaining.diagnostics()).isEmpty();
        assertThat(remaining.fields()).extracting(ConfigFieldSpec::key).containsExactly("beta.enabled");
        assertThat(ConfigFieldRegistry.snapshot(remaining).groups()).containsExactly("Name beta");
        assertThat(ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.fromRegisteredPlugins(List.of())).groups())
                .isEmpty();
    }

    @Test
    @DisplayName("显式分组声明优先于插件名称回退且无字段区段仍可显示")
    void honorsDeclaredGroupsAndSectionOnlyContributions() {
        GuiConfigGroupContribution group = new GuiConfigGroupContribution("shared", "Declared", 42);
        DesktopUiPluginSnapshot declared = snapshot("beta", "shared", List.of(group));
        DesktopUiPluginSnapshot inferred = snapshot("alpha", "shared", List.of());
        GuiConfigContributionSnapshot result = GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(inferred, declared));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.groups()).containsExactly(new ConfigGroupSpec("shared", "Declared", 42, true));

        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                "only.settings",
                "only",
                GuiConfigSectionLayout.COMPACT_GRID,
                1,
                List.of()
        );
        DesktopUiPluginSnapshot sectionOnly = plugin("only", new GuiConfigContribution(List.of(), List.of(), List.of(section)));
        result = GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(sectionOnly));
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.sections()).hasSize(1);
        assertThat(ConfigFieldRegistry.snapshot(result).groups()).containsExactly("Name only");
    }

    @Test
    @DisplayName("非法显式分组不会通过默认名称回退重新接纳")
    void rejectedExplicitGroupIsNotReplacedByFallback() {
        GuiConfigGroupContribution group = new GuiConfigGroupContribution("invalid", "", 42);
        GuiConfigContributionSnapshot result = GuiConfigContributionAggregator.fromRegisteredPlugins(
                List.of(snapshot("alpha", "invalid", List.of(group)))
        );
        assertThat(result.fields()).isEmpty();
        assertThat(result.sections()).isEmpty();
        assertThat(result.diagnostics()).isNotEmpty();
    }

    private static DesktopUiPluginSnapshot snapshot(
            String owner,
            String group,
            List<GuiConfigGroupContribution> groups
    ) {
        GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                owner + ".enabled",
                group,
                "Enabled",
                GuiConfigFieldType.BOOL,
                "false",
                1
        );
        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                owner + ".settings",
                group,
                GuiConfigSectionLayout.COMPACT_GRID,
                1,
                List.of(new GuiConfigFieldLayoutContribution(field.key(), 1))
        );
        return plugin(owner, new GuiConfigContribution(groups, List.of(field), List.of(section)));
    }

    private static DesktopUiPluginSnapshot plugin(String owner, GuiConfigContribution contribution) {
        return new DesktopUiPluginSnapshot(
                owner,
                false,
                owner,
                1,
                false,
                owner,
                "plugin.name",
                List.of(),
                List.of(contribution),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
