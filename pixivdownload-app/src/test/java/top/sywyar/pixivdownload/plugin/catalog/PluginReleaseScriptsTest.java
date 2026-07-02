package top.sywyar.pixivdownload.plugin.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("官方插件发布脚本签名协议守卫")
class PluginReleaseScriptsTest {

    @Test
    @DisplayName("市场清单生成脚本输出结构化包签名，并在写出原始 manifest 后生成 detached 签名")
    void marketManifestScriptWritesStructuredSignaturesAndDetachedManifestSignature() throws Exception {
        String script = script("generate-market-manifest.ps1");

        assertThat(script).contains(
                "Invoke-PluginSignatureTool $SignatureToolJar @(",
                "$assetName = Get-OfficialPluginArtifactName $plugin $version",
                "\"artifact\"",
                "signature         = $signature",
                "signatureUrl      = \"$packageUrl.sig\"",
                "schemaVersion = \"1\"",
                "$manifestSignatureFile = \"$OutputFile.sig\"",
                "ConvertTo-Json -Depth 12) -replace \"`r`n\", \"`n\" -replace \"`r\", \"`n\"",
                "\"manifest\"",
                "\"--manifest\", $OutputFile",
                "\"--repository-id\", \"official\""
        );
        assertThat(script).doesNotContain("schemaVersion = \"2\"");
        assertThat(script).doesNotContain("signature         = \"");
        assertThat(script).doesNotContain("signature = \"");
        assertThat(script).doesNotContain("$jarName = \"$($plugin.Module)-$version.jar\"");

        assertThat(script.indexOf("[System.IO.File]::WriteAllText($OutputFile"))
                .as("manifest 原始 JSON 必须先写入文件")
                .isGreaterThanOrEqualTo(0);
        assertThat(script.indexOf("$manifestSignatureFile = \"$OutputFile.sig\""))
                .as("manifest detached 签名必须在原始文件写出之后生成")
                .isGreaterThan(script.indexOf("[System.IO.File]::WriteAllText($OutputFile"));
    }

    @Test
    @DisplayName("市场清单生成脚本把插件 descriptor 依赖投影到 package 元数据")
    void marketManifestScriptProjectsDescriptorDependencies() throws Exception {
        String script = script("generate-market-manifest.ps1");

        assertThat(script).contains(
                "function Get-PluginDependencies([string]$value)",
                "$dependencies = @(Get-PluginDependencies $d[\"plugin.dependencies\"])",
                "dependencies      = @($dependencies)"
        );
        assertThat(script).doesNotContain("dependencies      = @()");

        assertThat(pluginDescriptor("pixivdownload-plugin-mail")).contains("plugin.dependencies=notification@1.0");
        assertThat(pluginDescriptor("pixivdownload-plugin-push")).contains("plugin.dependencies=notification@1.0");
    }

    @Test
    @DisplayName("插件 release 发布脚本按官方产物形态上传 artifact、sha256 与 detached artifact 签名")
    void publishScriptUploadsArtifactSignature() throws Exception {
        String script = script("publish-plugin-releases.ps1");

        assertThat(script).contains(
                "$assetName = Get-OfficialPluginArtifactName $plugin $version",
                "$expectedAssets = @($assetName, $shaAssetName, $sigAssetName)",
                "$missingAssets = @($expectedAssets | Where-Object { $assetNames -notcontains $_ })",
                "already published with expected assets; skip",
                "Download-ReleaseAsset -Tag $tag -AssetName $assetName",
                "Build-StagedPluginArtifact -Plugin $plugin -Version $version -AssetName $assetName",
                "if ($missingAssets -contains $shaAssetName)",
                "if ($missingAssets -contains $sigAssetName)",
                "Upload-ReleaseAssetFiles -Tag $tag -Paths $uploadPaths",
                "$sigFile = \"$StagedArtifact.sig\"",
                "\"artifact\"",
                "\"--artifact\", $StagedArtifact",
                "\"--plugin-id\", $Plugin.Id",
                "\"--version\", $Version",
                "\"--key-id\", $OfficialKeyId",
                "\"--private-key\", $PrivateKeyFile",
                "gh release upload $Tag $Paths --repo $Repo"
        );
        assertThat(script).doesNotContain("already published; skip (immutable");
        assertThat(script).doesNotContain("$assetName = \"$($plugin.Module)-$version.jar\"");
        assertThat(script).doesNotContain("Assert-ThinPluginJar $builtJar");
        assertThat(script).doesNotContain("Build-StagedPluginJar");
    }

    @Test
    @DisplayName("共享分发脚本提供官方插件 jar 产物名解析和私有 lib 形态断言")
    void commonDistributionScriptResolvesOfficialArtifactNames() throws Exception {
        String common = script("plugin-distribution-common.ps1");

        assertThat(common).contains(
                "Format = \"jar\"",
                "PrivateLibs = $true",
                "PrivateLibs = $false",
                "function Get-OfficialPluginArtifactExtension",
                "function Get-OfficialPluginArtifactName",
                "return \"$($Plugin.Module)-$Version.$extension\"",
                "function Find-ModulePluginArtifact",
                "Assert-JarWithPrivatePluginLibs",
                "Assert-ThinPluginJar",
                "^flatlaf-[0-9].*\\.jar$",
                "^jna-[0-9].*\\.jar$",
                "Plugin jar is not thin - found private lib/*.jar entries");
        assertThat(common).doesNotContain("Format = \"zip\"");
        assertThat(common).doesNotContain("Assert-ExplodedPluginZip");
    }

    @Test
    @DisplayName("市场策展文件覆盖所有官方可选插件的展示字段")
    void marketCurationCoversOfficialOptionalPlugins() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        JsonNode curation = new ObjectMapper().readTree(repoRoot().resolve("scripts").resolve("market-curation.json").toFile());
        Set<String> officialPluginIds = officialOptionalPluginIds(common);

        assertThat(officialPluginIds).contains("notification");
        for (String pluginId : officialPluginIds) {
            JsonNode entry = curation.get(pluginId);
            assertThat(entry).as("missing market curation for official plugin %s", pluginId).isNotNull();
            assertTextField(entry, pluginId, "displayNameKey");
            assertTextField(entry, pluginId, "descriptionKey");
            assertLocalizedText(entry, pluginId, "displayName");
            assertLocalizedText(entry, pluginId, "summary");
            assertLocalizedText(entry, pluginId, "description");
            assertTextField(entry, pluginId, "author");
            assertTextField(entry, pluginId, "sourceType");
            assertTextField(entry, pluginId, "category");
            assertTextField(entry, pluginId, "homepageUrl");
            assertTextField(entry, pluginId, "license");
            assertTextField(entry, pluginId, "iconToken");
            assertTextField(entry, pluginId, "colorToken");
            assertThat(entry.path("tags").isArray()).as("%s tags must be an array", pluginId).isTrue();
            assertThat(entry.path("tags").size()).as("%s tags must not be empty", pluginId).isPositive();
            assertThat(entry.path("recommended").isBoolean()).as("%s recommended must be boolean", pluginId).isTrue();
            assertThat(entry.path("officialRequired").isBoolean()).as("%s officialRequired must be boolean", pluginId).isTrue();
        }
    }

    @Test
    @DisplayName("离线分发与 Windows 打包脚本同时携带 artifact 签名和 provenance sidecar")
    void offlinePackagingScriptsCarrySignatureAndProvenanceSidecar() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String distribution = script("assemble-plugin-distribution.ps1");
        String windows = script("package-local.ps1");

        assertThat(common).contains(
                "function New-PluginArtifactSignature",
                "function Write-PluginProvenanceSidecar",
                "Join-Path $artifact.Directory.FullName \"provenance\"",
                ".pixiv-plugin-provenance",
                "signature.formatVersion=$($Signature.formatVersion)",
                "status=VERIFIED"
        );
        for (String script : List.of(distribution, windows)) {
            assertThat(script).contains(
                    "New-PluginArtifactSignature",
                    "Write-PluginProvenanceSidecar",
                    "Assert-NoPrivateKeyMaterial",
                    "signature = $signature"
            );
        }
    }

    @Test
    @DisplayName("离线分发 boot jar 边界黑名单覆盖 notification 基础插件")
    void offlineDistributionBootJarBlacklistCoversNotificationPlugin() throws Exception {
        String distribution = script("assemble-plugin-distribution.ps1");

        assertThat(distribution).contains(
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/notificationbase/\"",
                "\"BOOT-INF/classes/i18n/web/notification\""
        );
    }

    @Test
    @DisplayName("GitHub Actions 发布流从 Secrets 注入私钥并提交 manifest detached 签名")
    void publishWorkflowInjectsSigningSecretAndPublishesManifestSignature() throws Exception {
        String workflow = workflow("publish-plugins.yml");

        assertThat(workflow).contains(
                "PLUGIN_SIGNING_KEY_ID: pixivdownloader-official-root-2026-07",
                "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64: ${{ secrets.PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64 }}",
                "PLUGIN_SIGNING_PRIVATE_KEY_PEM: ${{ secrets.PLUGIN_SIGNING_PRIVATE_KEY_PEM }}",
                "FromBase64String",
                "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64 is not valid Base64",
                "gh secret set PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64 --repo Sywyar/PixivDownloader --body",
                "Prepared plugin signing private key contains '?' characters",
                "PLUGIN_SIGNING_PRIVATE_KEY_FILE=$privateKeyFile",
                "$publishArgs = @{",
                "Repo = $env:PLUGINS_REPO",
                "OfficialKeyId = $env:PLUGIN_SIGNING_KEY_ID",
                "PrivateKeyFile = $env:PLUGIN_SIGNING_PRIVATE_KEY_FILE",
                "$publishArgs[\"Force\"] = $true",
                ".\\scripts\\publish-plugin-releases.ps1 @publishArgs",
                "-OfficialKeyId $env:PLUGIN_SIGNING_KEY_ID",
                "-PrivateKeyFile $env:PLUGIN_SIGNING_PRIVATE_KEY_FILE",
                "Copy-Item build/manifest.json.sig plugins-repo/manifest.json.sig -Force",
                "git add manifest.json manifest.json.sig",
                "Cleanup plugin signing private key");
        assertThat(workflow).doesNotContain("-----BEGIN PRIVATE KEY-----");
        assertThat(workflow).doesNotContain("\"-Repo\", $env:PLUGINS_REPO");
        assertThat(workflow).doesNotContain("\"-PrivateKeyFile\", $env:PLUGIN_SIGNING_PRIVATE_KEY_FILE");
    }

    @Test
    @DisplayName("发布脚本不携带私钥材料，也不把私钥写入产物")
    void scriptsDoNotEmbedOrWritePrivateKeyMaterial() throws Exception {
        for (String name : List.of(
                "plugin-distribution-common.ps1",
                "publish-plugin-releases.ps1",
                "generate-market-manifest.ps1",
                "assemble-plugin-distribution.ps1",
                "package-local.ps1")) {
            String script = script(name);
            assertThat(script).as(name).doesNotContain("-----BEGIN PRIVATE KEY-----");
            assertThat(script).as(name).doesNotContain("-----END PRIVATE KEY-----");
            assertThat(script).as(name).doesNotContain("Set-Content -Path $PrivateKeyFile");
            assertThat(script).as(name).doesNotContain("WriteAllText($PrivateKeyFile");
        }
    }

    private static String script(String name) throws IOException {
        return Files.readString(repoRoot().resolve("scripts").resolve(name), StandardCharsets.UTF_8);
    }

    private static String workflow(String name) throws IOException {
        return Files.readString(repoRoot().resolve(".github").resolve("workflows").resolve(name),
                StandardCharsets.UTF_8);
    }

    private static String pluginDescriptor(String module) throws IOException {
        return Files.readString(repoRoot().resolve(module).resolve("src/main/resources/plugin.properties"),
                StandardCharsets.UTF_8);
    }

    private static Set<String> officialOptionalPluginIds(String common) {
        int start = common.indexOf("$plugins = @(");
        int end = common.indexOf("if ($IncludeSentinel)", start);
        assertThat(start).as("official plugin list start").isGreaterThanOrEqualTo(0);
        assertThat(end).as("official plugin list end").isGreaterThan(start);

        Matcher matcher = Pattern.compile("Id\\s*=\\s*\"([^\"]+)\"").matcher(common.substring(start, end));
        Set<String> ids = new LinkedHashSet<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        assertThat(ids).as("official plugin ids").isNotEmpty();
        return ids;
    }

    private static void assertTextField(JsonNode entry, String pluginId, String field) {
        assertThat(entry.path(field).asText()).as("%s %s must not be blank", pluginId, field).isNotBlank();
    }

    private static void assertLocalizedText(JsonNode entry, String pluginId, String field) {
        JsonNode localized = entry.path(field);
        assertThat(localized.isObject()).as("%s %s must be localized object", pluginId, field).isTrue();
        assertThat(localized.path("zh").asText()).as("%s %s.zh must not be blank", pluginId, field).isNotBlank();
        assertThat(localized.path("en").asText()).as("%s %s.en must not be blank", pluginId, field).isNotBlank();
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("scripts"))
                    && Files.isDirectory(current.resolve("pixivdownload-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }
}
