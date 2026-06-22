package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件版本 semver 比较（升级 / 降级判定）")
class PluginPackageVersionTest {

    @Test
    @DisplayName("major / minor / patch 按数值比较")
    void comparesCoreNumbers() {
        assertThat(cmp("1.0.0", "1.0.0")).isZero();
        assertThat(cmp("1.0.1", "1.0.0")).isPositive();
        assertThat(cmp("1.1.0", "1.0.9")).isPositive();
        assertThat(cmp("2.0.0", "1.9.9")).isPositive();
        assertThat(cmp("1.0.0", "2.0.0")).isNegative();
        // 数值比较而非字典序：10 > 9
        assertThat(cmp("1.10.0", "1.9.0")).isPositive();
    }

    @Test
    @DisplayName("预发布版优先级低于同号正式版")
    void prereleaseLowerThanRelease() {
        assertThat(cmp("1.0.0-rc.1", "1.0.0")).isNegative();
        assertThat(cmp("1.0.0", "1.0.0-rc.1")).isPositive();
    }

    @Test
    @DisplayName("预发布段逐段比较：数字段按值、字母段字典序、数字段 < 字母段、段更多者更高")
    void comparesPrereleaseIdentifiers() {
        assertThat(cmp("1.0.0-alpha", "1.0.0-alpha.1")).isNegative();
        assertThat(cmp("1.0.0-alpha.1", "1.0.0-alpha.beta")).isNegative();
        assertThat(cmp("1.0.0-alpha.beta", "1.0.0-beta")).isNegative();
        assertThat(cmp("1.0.0-1", "1.0.0-2")).isNegative();
        assertThat(cmp("1.0.0-rc.2", "1.0.0-rc.1")).isPositive();
    }

    @Test
    @DisplayName("build 元数据（+...）不参与比较")
    void buildMetadataIgnored() {
        assertThat(cmp("1.0.0+build.1", "1.0.0+build.9")).isZero();
        assertThat(cmp("1.2.3-rc.1+a", "1.2.3-rc.1+b")).isZero();
    }

    private static int cmp(String a, String b) {
        return PluginPackageVersion.parse(a).compareTo(PluginPackageVersion.parse(b));
    }
}
