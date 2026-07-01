package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor;
import top.sywyar.pixivdownload.gui.config.RepositoryConfigEntry;
import top.sywyar.pixivdownload.gui.config.RepositoryConfigValidator;
import top.sywyar.pixivdownload.gui.config.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件市场配置 section 的无 Swing 逻辑：市场入口门控、代理策略文案 / 保留、市场页目标，以及读取失败后的写回守卫
 * （仓库列表读取失败不得被当作「清空」覆盖磁盘原块）。Swing 视图本身由真实渲染截图核对，不在此单测。
 */
@DisplayName("插件市场配置 section：入口门控 / 代理策略 / 市场页目标 / 读取失败写回守卫")
class PluginMarketConfigSectionTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String... lines) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private PluginMarketConfigSection section(Path configPath) {
        return new PluginMarketConfigSection(null, configPath, url -> url);
    }

    @Test
    @DisplayName("plugin-market 显式禁用时市场入口不可用，启用 / 缺省时可用")
    void marketEntryGatedOnPluginToggle() {
        assertThat(PluginMarketConfigSection.isMarketEntryEnabled("true")).isTrue();
        assertThat(PluginMarketConfigSection.isMarketEntryEnabled("")).isTrue();   // 缺省视为启用
        assertThat(PluginMarketConfigSection.isMarketEntryEnabled(null)).isTrue();
        assertThat(PluginMarketConfigSection.isMarketEntryEnabled("false")).isFalse();
        assertThat(PluginMarketConfigSection.isMarketEntryEnabled("FALSE")).isFalse();
        assertThat(PluginMarketConfigSection.isMarketEntryEnabled(" false ")).isFalse();
    }

    @Test
    @DisplayName("打开市场页目标是核心壳 admin 市场页（经共享 webUrlProvider，不新增 GUI 写端点）")
    void opensCoreMarketPage() {
        assertThat(PluginMarketConfigSection.MARKET_PAGE).isEqualTo("/plugin-market.html");
    }

    @Test
    @DisplayName("代理策略文案：固定枚举本地化、未知回退原样")
    void proxyPolicyLabels() {
        try {
            GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertThat(PluginMarketConfigSection.proxyPolicyLabel("direct-strict"))
                    .isEqualTo(GuiMessages.get("gui.config.market.repo.proxy.direct-strict"));
            assertThat(PluginMarketConfigSection.proxyPolicyLabel("proxy-trusted"))
                    .isEqualTo(GuiMessages.get("gui.config.market.repo.proxy.proxy-trusted"));
            assertThat(PluginMarketConfigSection.proxyPolicyLabel("custom"))
                    .isEqualTo(GuiMessages.get("gui.config.market.repo.proxy.custom"));
            assertThat(PluginMarketConfigSection.proxyPolicyLabel("mystery")).isEqualTo("mystery");
        } finally {
            GuiMessages.clearLocaleOverride();
        }
    }

    @Test
    @DisplayName("未知代理策略经下拉往返被保留、不静默降级为 direct-strict；OK 时以 proxy-policy-unknown 阻止")
    void unknownProxyPolicyPreservedNotSilentlyDowngraded() {
        // 未知策略：下拉项保留原值（字符串），不映射为枚举（即不降级为 direct-strict）。
        Object unknownSel = PluginMarketConfigSection.proxyComboSelection("wild-proxy");
        assertThat(unknownSel).isInstanceOf(String.class).isEqualTo("wild-proxy");
        // 持久化时原样回写：编辑其它字段、不动下拉 → 仍是 wild-proxy，不被悄悄改写。
        String persisted = PluginMarketConfigSection.persistedProxyPolicy(unknownSel);
        assertThat(persisted).isEqualTo("wild-proxy");
        // 前置校验以 proxy-policy-unknown 阻止提交（要求用户显式改选受支持策略）。
        assertThat(RepositoryConfigValidator.validateProxyPolicy(persisted))
                .isEqualTo("gui.config.market.repo.error.proxy-policy-unknown");

        // 已知策略（大小写不敏感）：映射为枚举、回写为 configId、校验通过。
        assertThat(PluginMarketConfigSection.proxyComboSelection("proxy-trusted"))
                .isEqualTo(RepositoryProxyPolicy.PROXY_TRUSTED);
        assertThat(PluginMarketConfigSection.proxyComboSelection("DIRECT-STRICT"))
                .isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
        assertThat(PluginMarketConfigSection.proxyComboSelection("custom"))
                .isEqualTo(RepositoryProxyPolicy.CUSTOM);
        assertThat(PluginMarketConfigSection.persistedProxyPolicy(RepositoryProxyPolicy.PROXY_TRUSTED))
                .isEqualTo("proxy-trusted");
        assertThat(RepositoryConfigValidator.validateProxyPolicy(
                PluginMarketConfigSection.persistedProxyPolicy(RepositoryProxyPolicy.PROXY_TRUSTED))).isNull();
    }

    @Test
    @DisplayName("勾选继承官方密钥时保存为该仓库显式 trusted-key")
    void inheritOfficialRootWritesExplicitRepositoryKey() {
        TrustedKeyConfigEntry custom = TrustedKeyConfigEntry.create("custom-key", "Ed25519",
                "MCowBQYDK2VwAyEA8no36HyWNxrjbl10qGcIumILxcgau/0egy3RODVNUIc=",
                "ACTIVE", "Custom", "Custom root");

        List<TrustedKeyConfigEntry> keys = PluginMarketConfigSection.trustedKeysForSave(List.of(custom), true);

        assertThat(keys).contains(TrustedKeyConfigEntry.officialRoot(), custom);
        assertThat(keys.get(0)).isEqualTo(TrustedKeyConfigEntry.officialRoot());
        assertThat(PluginMarketConfigSection.hasDuplicateTrustedKeyIds(keys)).isFalse();
    }

    @Test
    @DisplayName("继承官方密钥落盘为 custom 仓库自己的 trusted-keys 条目")
    void inheritedOfficialRootPersistsAsRepositoryTrustedKey() throws IOException {
        Path file = writeConfig("plugin-catalog.enabled: true", "plugin-catalog.repositories:");
        List<TrustedKeyConfigEntry> keys = PluginMarketConfigSection.trustedKeysForSave(List.of(), true);

        new PluginRepositoryConfigEditor(file).write(List.of(new RepositoryConfigEntry(
                "custom", "", "https://custom.example/manifest.json", true,
                "direct-strict", false, true, false, false,
                0, 0, 0, 0, keys, new LinkedHashMap<>())));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("plugin-catalog.repositories:");
        assertThat(content).contains("trusted-keys:");
        assertThat(content).contains("key-id: " + TrustedKeyConfigEntry.officialRoot().keyId());
        assertThat(new PluginRepositoryConfigEditor(file).read().get(0).trustedKeys())
                .containsExactly(TrustedKeyConfigEntry.officialRoot());
    }

    @Test
    @DisplayName("未勾选继承官方密钥且未填写密钥时保持无 trusted-keys")
    void noInheritAndNoCustomKeyKeepsTrustedKeysEmpty() {
        assertThat(PluginMarketConfigSection.trustedKeysForSave(List.of(), false)).isEmpty();
    }

    @Test
    @DisplayName("读取失败后保存拒绝写回：不把空表当「清空」、不覆盖磁盘上的原仓库块")
    void saveAfterReadFailureDoesNotOverwriteRepositories() throws IOException {
        // 仓库块本身合法，但文件别处的非法 YAML 使整文档解析失败 → read() 抛错。
        Path file = writeConfig(
                "plugin-catalog.repositories:",
                "  - id: keep-me",
                "    manifest-url: https://keep.example/manifest.json",
                "    enabled: true",
                "broken-key: [unclosed");
        PluginMarketConfigSection section = section(file);

        assertThat(section.reloadRepositories()).isInstanceOf(IOException.class); // 读取失败
        assertThat(section.repositoriesLoaded()).isFalse();

        String before = Files.readString(file, StandardCharsets.UTF_8);
        assertThatThrownBy(section::onSave).isInstanceOf(IOException.class); // 拒绝写回
        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before);
        assertThat(after).contains("id: keep-me"); // 原仓库块逐字保留
    }

    @Test
    @DisplayName("合法空仓库列表不是读取失败：成功加载、保存为无改动且不抛（区分空与读取失败）")
    void validEmptyRepositoriesNotTreatedAsReadFailure() throws IOException {
        Path file = writeConfig("plugin-catalog.enabled: true", "plugin-catalog.repositories:");
        PluginMarketConfigSection section = section(file);

        assertThat(section.reloadRepositories()).isNull(); // 读取成功
        assertThat(section.repositoriesLoaded()).isTrue();

        String before = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(section.onSave()).isFalse(); // 无改动、不抛
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    @DisplayName("成功加载非空仓库后保存：无改动不写、不误抛（守卫不阻塞正常保存）")
    void loadedRepositoriesSaveWithoutSpuriousWrite() throws IOException {
        Path file = writeConfig(
                "plugin-catalog.repositories:",
                "  - id: alpha",
                "    manifest-url: https://a.example/manifest.json",
                "    enabled: true");
        PluginMarketConfigSection section = section(file);

        assertThat(section.reloadRepositories()).isNull();
        assertThat(section.repositoriesLoaded()).isTrue();

        String before = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(section.onSave()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(before);
    }
}
