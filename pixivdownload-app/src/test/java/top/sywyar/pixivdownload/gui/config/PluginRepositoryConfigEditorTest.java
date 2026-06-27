package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件仓库列表结构化读写：往返 / 顺序 / 未知字段 / 注释保留")
class PluginRepositoryConfigEditorTest {

    @TempDir
    Path tempDir;

    private Path writeFile(String... lines) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static RepositoryConfigEntry repo(String id, String url, boolean enabled, String policy) {
        return RepositoryConfigEntry.create(id, "", url, enabled, policy, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("空列表写为单行键、读回空")
    void emptyListRoundTrips() throws IOException {
        Path file = writeFile("plugin-catalog.enabled: false", "plugin-catalog.repositories:");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).isEmpty();
        editor.write(List.of());

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("plugin-catalog.repositories:");
        assertThat(content).doesNotContain("- id");
        assertThat(editor.read()).isEmpty();
    }

    @Test
    @DisplayName("单项写入后能原样读回，且是合法 YAML、可被 Spring 绑定")
    void singleEntryRoundTrips() throws IOException {
        Path file = writeFile("plugin-catalog.enabled: true", "plugin-catalog.repositories:");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        editor.write(List.of(repo("myrepo", "https://example.com/manifest.json", true, "direct-strict")));

        List<RepositoryConfigEntry> readBack = editor.read();
        assertThat(readBack).hasSize(1);
        RepositoryConfigEntry e = readBack.get(0);
        assertThat(e.id()).isEqualTo("myrepo");
        assertThat(e.manifestUrl()).isEqualTo("https://example.com/manifest.json");
        assertThat(e.enabled()).isTrue();
        assertThat(e.proxyPolicy()).isEqualTo("direct-strict");

        // Spring Boot 同款 YAML 加载 + 绑定（证明保存→重启语义等价）。
        PluginCatalogProperties bound = bind(file);
        assertThat(bound.getRepositories()).hasSize(1);
        assertThat(bound.getRepositories().get(0).getId()).isEqualTo("myrepo");
        assertThat(bound.getRepositories().get(0).getManifestUrl()).isEqualTo("https://example.com/manifest.json");
    }

    @Test
    @DisplayName("多项写入保留顺序、覆盖值与代理策略，Spring 绑定一致")
    void multipleEntriesPreserveOrderAndOverrides() throws IOException {
        Path file = writeFile("plugin-catalog.enabled: true", "plugin-catalog.repositories:", "");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        editor.write(List.of(
                RepositoryConfigEntry.create("alpha", "", "https://a.example/manifest.json", true, "proxy-trusted",
                        5000, 0, 0, 52_428_800L),
                repo("beta", "https://b.example/manifest.json", false, "direct-strict"),
                repo("gamma", "https://c.example/manifest.json", true, "direct-strict")));

        List<RepositoryConfigEntry> readBack = editor.read();
        assertThat(readBack).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta", "gamma");
        assertThat(readBack.get(0).proxyPolicy()).isEqualTo("proxy-trusted");
        assertThat(readBack.get(0).connectTimeoutMs()).isEqualTo(5000);
        assertThat(readBack.get(0).readTimeoutMs()).isZero(); // 继承
        assertThat(readBack.get(0).maxPackageBytes()).isEqualTo(52_428_800L);
        assertThat(readBack.get(1).enabled()).isFalse();

        PluginCatalogProperties bound = bind(file);
        assertThat(bound.getRepositories()).extracting(PluginCatalogProperties.RepositoryConfig::getId)
                .containsExactly("alpha", "beta", "gamma");
        assertThat(bound.getRepositories().get(0).getProxyPolicy()).isEqualTo("proxy-trusted");
        assertThat(bound.getRepositories().get(0).getConnectTimeoutMs()).isEqualTo(5000);
        assertThat(bound.getRepositories().get(0).getMaxPackageBytes()).isEqualTo(52_428_800L);
    }

    @Test
    @DisplayName("自定义代理策略四个网络开关可经 YAML 与 Spring Binder 完整往返")
    void customNetworkOptionsRoundTrip() throws IOException {
        Path file = writeFile("plugin-catalog.enabled: true", "plugin-catalog.repositories:");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        editor.write(List.of(RepositoryConfigEntry.createCustom(
                "lan", "", "http://192.168.1.10/manifest.json", true,
                true, false, true, true, 0, 0, 0, 0)));

        RepositoryConfigEntry entry = editor.read().get(0);
        assertThat(entry.proxyPolicy()).isEqualTo("custom");
        assertThat(entry.allowRedirects()).isTrue();
        assertThat(entry.strictHttps()).isFalse();
        assertThat(entry.allowNonPublicAddresses()).isTrue();
        assertThat(entry.useProxy()).isTrue();

        PluginCatalogProperties.RepositoryConfig bound = bind(file).getRepositories().get(0);
        assertThat(bound.getProxyPolicy()).isEqualTo("custom");
        assertThat(bound.isAllowRedirects()).isTrue();
        assertThat(bound.isStrictHttps()).isFalse();
        assertThat(bound.isAllowNonPublicAddresses()).isTrue();
        assertThat(bound.isUseProxy()).isTrue();
    }

    @Test
    @DisplayName("自定义网络开关缺省时采用直连严格的安全默认值")
    void customNetworkOptionsUseSafeDefaults() throws IOException {
        Path file = writeFile(
                "plugin-catalog.repositories:",
                "  - id: safe-custom",
                "    manifest-url: https://repo.example/manifest.json",
                "    proxy-policy: custom");

        RepositoryConfigEntry entry = new PluginRepositoryConfigEditor(file).read().get(0);
        assertThat(entry.allowRedirects()).isFalse();
        assertThat(entry.strictHttps()).isTrue();
        assertThat(entry.allowNonPublicAddresses()).isFalse();
        assertThat(entry.useProxy()).isFalse();
    }

    @Test
    @DisplayName("本编辑器未暴露的未知仓库字段往返保留")
    void unknownRepositoryFieldsArePreserved() throws IOException {
        Path file = writeFile("plugin-catalog.repositories:",
                "  - id: future",
                "    manifest-url: https://future.example/manifest.json",
                "    enabled: true",
                "    proxy-policy: direct-strict",
                "    signature-pin: abc123",
                "    experimental-flag: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        List<RepositoryConfigEntry> entries = editor.read();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).extraFields()).containsEntry("signature-pin", "abc123");

        // 重新写回（GUI 未触碰未知字段）→ 仍在
        editor.write(entries);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("signature-pin: abc123");
        assertThat(content).contains("experimental-flag: true");
        assertThat(editor.read().get(0).extraFields()).containsEntry("signature-pin", "abc123");
    }

    @Test
    @DisplayName("清空全部仓库后写为空、其它配置与注释逐字保留")
    void deletingAllKeepsOtherConfigAndComments() throws IOException {
        Path file = writeFile(
                "# ---- 插件市场 ----",
                "plugin-catalog.enabled: true                  # 主开关",
                "plugin-catalog.repositories:",
                "  - id: temp",
                "    manifest-url: https://temp.example/manifest.json",
                "    enabled: true",
                "",
                "# ---- 代理 ----",
                "proxy.enabled: true                           # 代理开关");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).hasSize(1);
        editor.write(List.of());

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("# ---- 插件市场 ----");
        assertThat(content).contains("plugin-catalog.enabled: true                  # 主开关");
        assertThat(content).contains("# ---- 代理 ----");
        assertThat(content).contains("proxy.enabled: true                           # 代理开关");
        assertThat(content).doesNotContain("- id: temp");
        assertThat(editor.read()).isEmpty();
        // 标量编辑器仍能读其它键（互不破坏）。
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("true");
        assertThat(new ConfigFileEditor(file).read("plugin-catalog.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("反复保存幂等：内容与读回结果稳定")
    void repeatedSaveIsIdempotent() throws IOException {
        Path file = writeFile("plugin-catalog.repositories:");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);
        List<RepositoryConfigEntry> entries = List.of(
                repo("alpha", "https://a.example/manifest.json", true, "direct-strict"),
                repo("beta", "https://b.example/manifest.json", false, "proxy-trusted"));

        editor.write(entries);
        String first = Files.readString(file, StandardCharsets.UTF_8);
        editor.write(editor.read());
        String second = Files.readString(file, StandardCharsets.UTF_8);

        assertThat(second).isEqualTo(first);
        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("仓库键不存在时插入到 plugin-catalog 段之后")
    void insertsKeyWhenMissing() throws IOException {
        Path file = writeFile("plugin-catalog.enabled: true", "proxy.enabled: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        editor.write(List.of(repo("solo", "https://solo.example/manifest.json", true, "direct-strict")));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int catalogIdx = lines.indexOf("plugin-catalog.enabled: true");
        int keyIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("plugin-catalog.repositories:")) {
                keyIdx = i;
            }
        }
        assertThat(keyIdx).isGreaterThan(catalogIdx);
        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("solo");
    }

    @Test
    @DisplayName("非法 YAML 抛 IOException 而非静默吞掉")
    void malformedYamlThrows() throws IOException {
        Path file = writeFile("plugin-catalog.repositories: [unclosed");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, editor::read);
    }

    @Test
    @DisplayName("列表项之间的空行不再提前结束块：往返不丢项 / 不重复，后续顶层键完好")
    void blankLinesBetweenItemsRoundTrip() throws IOException {
        Path file = writeFile(
                "plugin-catalog.enabled: true",
                "plugin-catalog.repositories:",
                "  - id: alpha",
                "    manifest-url: https://a.example/manifest.json",
                "    enabled: true",
                "",
                "  - id: beta",
                "    manifest-url: https://b.example/manifest.json",
                "    enabled: true",
                "proxy.enabled: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
        editor.write(editor.read()); // 往返：此前空行会让块在 alpha 后提前结束，把 beta 块悬挂 / 重复

        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 后续顶层键既未被并入仓库块、也未被覆盖
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("true");
        // beta 只出现一次（旧 bug 会在空行后遗留一份重复 / 悬挂 beta 块）
        int firstBeta = content.indexOf("id: beta");
        assertThat(firstBeta).isGreaterThanOrEqualTo(0);
        assertThat(content.indexOf("id: beta", firstBeta + 1)).isEqualTo(-1);
    }

    @Test
    @DisplayName("块后的顶层注释与空行往返中逐字保留、不被并入仓库块")
    void topLevelCommentsAndBlanksAfterBlockPreserved() throws IOException {
        Path file = writeFile(
                "plugin-catalog.repositories:",
                "  - id: only",
                "    manifest-url: https://only.example/manifest.json",
                "    enabled: true",
                "",
                "# 代理设置",
                "proxy.enabled: false");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        editor.write(editor.read());

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("# 代理设置");
        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("only");
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("含项间空行时删除全部仓库：折叠为单行键、注释与后续顶层键完好")
    void deletingAllWithBlankLinesBetweenItemsCollapses() throws IOException {
        Path file = writeFile(
                "plugin-catalog.repositories:",
                "  - id: a",
                "    manifest-url: https://a.example/manifest.json",
                "",
                "  - id: b",
                "    manifest-url: https://b.example/manifest.json",
                "# tail",
                "proxy.enabled: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).hasSize(2);
        editor.write(List.of());

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("plugin-catalog.repositories:");
        assertThat(content).doesNotContain("- id: a");
        assertThat(content).doesNotContain("- id: b");
        assertThat(content).contains("# tail");
        assertThat(editor.read()).isEmpty();
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("项间顶层注释：往返不丢项 / 不重复，注释随块替换，后续顶层键与 Binder 语义一致")
    void topLevelCommentBetweenItemsRoundTrips() throws IOException {
        Path file = writeFile(
                "plugin-catalog.enabled: true",
                "plugin-catalog.repositories:",
                "  - id: alpha",
                "    manifest-url: https://a.example/manifest.json",
                "    enabled: true",
                "# 第二个仓库（项间顶层注释，旧实现会在此提前结束块、悬挂 beta）",
                "  - id: beta",
                "    manifest-url: https://b.example/manifest.json",
                "    enabled: true",
                "proxy.enabled: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
        editor.write(editor.read()); // 往返

        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // beta 只出现一次（旧 bug 会遗留一份悬挂 / 重复 beta 块）
        int firstBeta = content.indexOf("id: beta");
        assertThat(firstBeta).isGreaterThanOrEqualTo(0);
        assertThat(content.indexOf("id: beta", firstBeta + 1)).isEqualTo(-1);
        // 后续顶层键既未被并入块、也未被覆盖
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("true");
        // Spring Binder 语义一致（保存→重启绑定等价）
        PluginCatalogProperties bound = bind(file);
        assertThat(bound.getRepositories()).extracting(PluginCatalogProperties.RepositoryConfig::getId)
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("项间缩进注释：往返不丢项 / 不重复，后续顶层键完好")
    void indentedCommentBetweenItemsRoundTrips() throws IOException {
        Path file = writeFile(
                "plugin-catalog.repositories:",
                "  - id: alpha",
                "    manifest-url: https://a.example/manifest.json",
                "    # 缩进注释（项内 / 项间，缩进判据即归属块）",
                "  - id: beta",
                "    manifest-url: https://b.example/manifest.json",
                "proxy.enabled: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
        editor.write(editor.read());

        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("alpha", "beta");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int firstBeta = content.indexOf("id: beta");
        assertThat(firstBeta).isGreaterThanOrEqualTo(0);
        assertThat(content.indexOf("id: beta", firstBeta + 1)).isEqualTo(-1);
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("true");
        assertThat(bind(file).getRepositories()).extracting(PluginCatalogProperties.RepositoryConfig::getId)
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("块后顶层注释 + 顶层键：紧贴块尾的注释与键往返中逐字保留、不被并入仓库块")
    void trailingTopLevelCommentAndKeyPreservedOnRoundTrip() throws IOException {
        Path file = writeFile(
                "plugin-catalog.repositories:",
                "  - id: only",
                "    manifest-url: https://only.example/manifest.json",
                "    enabled: true",
                "# 代理设置（紧贴块尾、无空行分隔）",
                "proxy.enabled: false");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        editor.write(editor.read());

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("# 代理设置（紧贴块尾、无空行分隔）");
        assertThat(editor.read()).extracting(RepositoryConfigEntry::id).containsExactly("only");
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("项间顶层注释 + 删除全部：折叠为单行键、不遗留注释后的旧仓库项、后续顶层键完好")
    void deletingAllWithTopLevelCommentBetweenItemsLeavesNoDanglingItem() throws IOException {
        Path file = writeFile(
                "plugin-catalog.repositories:",
                "  - id: a",
                "    manifest-url: https://a.example/manifest.json",
                "# 中间注释（旧实现会在此提前结束块、删全部后遗留 b）",
                "  - id: b",
                "    manifest-url: https://b.example/manifest.json",
                "proxy.enabled: true");
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(file);

        assertThat(editor.read()).hasSize(2);
        editor.write(List.of());

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("plugin-catalog.repositories:");
        assertThat(content).doesNotContain("- id: a");
        assertThat(content).doesNotContain("- id: b");
        assertThat(editor.read()).isEmpty();
        assertThat(new ConfigFileEditor(file).read("proxy.enabled")).isEqualTo("true");
        assertThat(bind(file).getRepositories()).isEmpty();
    }

    private static PluginCatalogProperties bind(Path file) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("config",
                new ByteArrayResource(Files.readAllBytes(file)));
        MutablePropertySources mps = new MutablePropertySources();
        for (PropertySource<?> source : sources) {
            mps.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(mps));
        return binder.bind("plugin-catalog", PluginCatalogProperties.class)
                .orElseGet(PluginCatalogProperties::new);
    }
}
