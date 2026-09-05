package top.sywyar.pixivdownload.plugin.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assertThat(pluginDescriptor("pixivdownload-plugin-download-workbench"))
                .contains("plugin.dependencies=posthog?@1.0");
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
        assertThat(script).contains("Bump plugin.version instead of publishing new bytes under an existing tag");
        assertThat(script).contains(
                "[switch]$Force",
                "Remove-ExistingReleaseAssets",
                "gh release delete-asset",
                "(forced)");
        assertThat(script).doesNotContain("$assetName = \"$($plugin.Module)-$version.jar\"");
        assertThat(script).doesNotContain("Assert-ThinPluginJar $builtJar");
        assertThat(script).doesNotContain("Build-StagedPluginJar");
    }

    @Test
    @DisplayName("Nightly 插件按各自版本刷新固定 prerelease、改写包版本并生成独立清单")
    void nightlyPublicationUsesMatchingPluginVersionsAndManifest() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String publisher = script("publish-plugin-releases.ps1");
        String generator = script("generate-market-manifest.ps1");

        assertThat(common).contains(
                "function Get-NightlyPluginVersion",
                "return \"$SourceVersion-$NightlySuffix\"");
        assertThat(publisher).contains(
                "[string]$NightlyBuildVersion",
                "$version = Get-NightlyPluginVersion $sourceVersion $nightlySuffix",
                "$tag = \"$($plugin.Id)-nightly\"",
                "$title = \"Nightly Build $($plugin.Id)-v$version\"",
                "Set-StagedPluginVersion -StagedArtifact $stagedArtifact -Plugin $Plugin -Version $Version",
                "\"--update\" \"--file\" $StagedArtifact \"plugin.properties\"",
                "Remove-ExistingReleaseAssets -Tag $tag -AssetNames $assetNames",
                "gh release edit $tag --repo $Repo --title $title",
                "gh release create $tag $uploadPaths --repo $Repo --title $title",
                "--prerelease");
        assertThat(publisher).doesNotContain("Nightly release $tag already exists");
        assertThat(generator).contains(
                "$manifestName = if ($isNightly) { \"nightly-manifest.json\" } else { \"manifest.json\" }",
                "$version = if ($isNightly) {",
                "Get-NightlyPluginVersion $sourceVersion $nightlySuffix",
                "$tag = if ($isNightly) { \"$id-nightly\" } else { \"$id-v$version\" }",
                "$releasedTime = if ($isNightly) { $nowUtc } else { $rel.publishedAt }",
                "$channel = if ($isNightly) { \"nightly\" } else { \"stable\" }",
                "channel           = $channel");
    }

    @Test
    @DisplayName("共享分发脚本接受 SDK 支持的稳定版和预发布版本")
    void commonDistributionScriptParsesSdkPrereleaseVersions(@TempDir Path tempDir) throws Exception {
        Path metadata = tempDir.resolve(
                "pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties");
        Files.createDirectories(metadata.getParent());
        for (String version : List.of("1.0.0", "1.0.0-alpha1", "1.0.0-beta2", "1.0.0-rc12")) {
            Files.writeString(metadata, "version=" + version + "\n", StandardCharsets.UTF_8);
            assertThat(runPowerShell(
                    "$ErrorActionPreference='Stop'; "
                            + ". './scripts/plugin-distribution-common.ps1'; "
                            + "Get-PixivDownloadSdkVersion -ProjectRoot '" + psQuote(tempDir) + "'"))
                    .isEqualTo(version);
        }

        Files.writeString(metadata, "version=1.0.0-rc0\n", StandardCharsets.UTF_8);
        CommandResult invalid = runPowerShellResult(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/plugin-distribution-common.ps1'; "
                        + "Get-PixivDownloadSdkVersion -ProjectRoot '" + psQuote(tempDir) + "'");
        assertThat(invalid.exitCode()).as(invalid.output()).isNotZero();
        assertThat(invalid.output()).contains("SDK metadata contains an invalid semantic version");
    }

    @Test
    @DisplayName("共享分发脚本提供官方插件 jar 产物名解析和私有 lib 形态断言")
    void commonDistributionScriptResolvesOfficialArtifactNames() throws Exception {
        String common = script("plugin-distribution-common.ps1");

        assertThat(common).contains(
                "function Get-OfficialRequiredPlugins",
                "function Get-OfficialDefaultInstalledPlugins",
                "function Get-OfficialOptionalPlugins",
                "Id = \"download-workbench\"",
                "Module = \"pixivdownload-plugin-download-workbench\"",
                "Id = \"gui-compose\"",
                "Module = \"pixivdownload-plugin-gui-compose\"",
                "function Get-OfficialDistributionPlugins",
                "Format = \"jar\"",
                "PrivateLibs = $true",
                "PrivateLibs = $false",
                "function Get-OfficialPluginArtifactExtension",
                "function Get-OfficialPluginArtifactName",
                "return \"$($Plugin.Module)-$Version.$extension\"",
                "function Find-ModulePluginArtifact",
                "function Find-PluginArtifactSignatureSidecar",
                "function Assert-PluginArtifactSignature",
                "function Assert-ProguardProcessedArtifact",
                "function Get-PluginArtifactSignatureForDistribution",
                "Assert-JarWithPrivatePluginLibs",
                "Assert-ThinPluginJar",
                "^flatlaf-[0-9].*\\.jar$",
                "^jna-[0-9].*\\.jar$",
                "^ui-desktop-[0-9].*\\.jar$",
                "^material-icons-extended-desktop-[0-9].*\\.jar$",
                "^skiko-awt-runtime-linux-arm64-[0-9].*\\.jar$",
                "Plugin jar is not thin - found private lib/*.jar entries");
        assertThat(common).doesNotContain("Format = \"zip\"");
        assertThat(common).doesNotContain("Assert-ExplodedPluginZip");
    }

    @Test
    @DisplayName("发布产物 ProGuard 标记必须声明 shrink/optimize 且关闭混淆")
    void proguardArtifactMetadataIsValidated(@TempDir Path tempDir) throws Exception {
        Path processed = tempDir.resolve("processed.jar");
        writeThinPluginJar(processed, "sample", "1.0.1", "1.0");
        assertThat(runPowerShell(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/plugin-distribution-common.ps1'; "
                        + "Assert-ProguardProcessedArtifact '" + psQuote(processed) + "'; 'OK'"))
                .isEqualTo("OK");

        Path raw = tempDir.resolve("raw.jar");
        writeThinPluginJar(raw, "sample", "1.0.1", "1.0", false);
        CommandResult failure = runPowerShellResult(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/plugin-distribution-common.ps1'; "
                        + "Assert-ProguardProcessedArtifact '" + psQuote(raw) + "'");
        assertThat(failure.exitCode()).as(failure.output()).isNotZero();
        assertThat(failure.output()).contains("Release artifact is missing ProGuard metadata");
    }

    @Test
    @DisplayName("市场清单身份字段从官方 descriptor/i18n 派生，curation 只保留市场专属字段")
    void marketIdentityMetadataIsCanonicalDescriptorDerived() throws Exception {
        String generator = script("generate-market-manifest.ps1");
        JsonNode curation = new ObjectMapper().readTree(repoRoot().resolve("scripts").resolve("market-curation.json").toFile());
        List<OfficialPlugin> officialPlugins = officialDistributionPlugins();
        Set<String> officialPluginIds = officialPluginIds();

        assertThat(officialPluginIds).contains("download-workbench");
        assertThat(officialPluginIds).contains("posthog");
        assertThat(officialPluginIds).contains("multi-mode-decision-survey");
        assertThat(officialPluginIds).contains("notification");
        assertThat(officialPluginIds).contains("gui-compose");
        assertThat(generator).contains(
                "pixiv.display-namespace",
                "pixiv.display-name-key",
                "pixiv.description-key",
                "pixiv.icon-key",
                "pixiv.color-token",
                "Resolve-LocalizedTextMap",
                "displayNamespace = $displayNamespace",
                "displayName      = $displayName",
                "summary          = $summary",
                "iconToken        = $iconToken",
                "colorToken       = $colorToken");
        assertThat(generator).contains(
                "$defaultInstalledPluginIds = @(Get-OfficialDefaultInstalledPlugins | ForEach-Object { $_.Id })",
                "defaultInstalled = ($defaultInstalledPluginIds -contains $id)");
        assertThat(generator).doesNotContain(
                "$c.displayName",
                "$c.summary",
                "$c.iconToken",
                "$c.colorToken",
                "$c.displayNameKey",
                "$c.descriptionKey");

        for (OfficialPlugin plugin : officialPlugins) {
            String pluginId = plugin.id();
            JsonNode entry = curation.get(pluginId);
            assertThat(entry).as("missing market curation for official plugin %s", pluginId).isNotNull();
            assertThat(entry.has("displayNameKey")).as("%s displayNameKey belongs to descriptor", pluginId).isFalse();
            assertThat(entry.has("descriptionKey")).as("%s descriptionKey belongs to descriptor", pluginId).isFalse();
            assertThat(entry.has("displayName")).as("%s displayName is derived from i18n", pluginId).isFalse();
            assertThat(entry.has("summary")).as("%s summary is derived from i18n", pluginId).isFalse();
            assertThat(entry.has("iconToken")).as("%s iconToken belongs to descriptor", pluginId).isFalse();
            assertThat(entry.has("colorToken")).as("%s colorToken belongs to descriptor", pluginId).isFalse();
            assertLocalizedText(entry, pluginId, "description");
            assertTextField(entry, pluginId, "author");
            assertTextField(entry, pluginId, "sourceType");
            assertTextField(entry, pluginId, "category");
            assertTextField(entry, pluginId, "homepageUrl");
            assertTextField(entry, pluginId, "license");
            assertThat(entry.path("tags").isArray()).as("%s tags must be an array", pluginId).isTrue();
            assertThat(entry.path("tags").size()).as("%s tags must not be empty", pluginId).isPositive();
            assertThat(entry.path("recommended").isBoolean()).as("%s recommended must be boolean", pluginId).isTrue();
            assertThat(entry.path("officialRequired").isBoolean()).as("%s officialRequired must be boolean", pluginId).isTrue();
            assertThat(entry.path("officialRequired").asBoolean())
                    .as("%s officialRequired should mirror required plugin policy, not replace it", pluginId)
                    .isEqualTo("download-workbench".equals(pluginId));
            assertThat(entry.has("defaultInstalled"))
                    .as("%s defaultInstalled is derived from the distribution source", pluginId)
                    .isFalse();

            Map<String, String> descriptor = readProperties(repoRoot().resolve(plugin.module())
                    .resolve("src/main/resources/plugin.properties"));
            assertThat(descriptor.get("plugin.id")).isEqualTo(pluginId);
            assertDescriptorField(descriptor, pluginId, "pixiv.display-namespace");
            assertThat(descriptor.get("pixiv.display-name-key")).as("%s identity name key", pluginId)
                    .isEqualTo("plugin.name");
            assertThat(descriptor.get("pixiv.description-key")).as("%s identity summary key", pluginId)
                    .isEqualTo("plugin.summary");
            assertDescriptorField(descriptor, pluginId, "pixiv.icon-key");
            assertDescriptorField(descriptor, pluginId, "pixiv.color-token");
            assertI18nKey(plugin.module(), descriptor.get("pixiv.display-namespace"),
                    descriptor.get("pixiv.display-name-key"));
            assertI18nKey(plugin.module(), descriptor.get("pixiv.display-namespace"),
                    descriptor.get("pixiv.description-key"));
        }
        assertI18nValue("pixivdownload-plugin-ai", "ai", "plugin.name", "AI 翻译", "AI Translation");
        assertI18nValue("pixivdownload-plugin-tts", "tts", "plugin.name", "TTS 朗读", "TTS Narration");
    }

    @Test
    @DisplayName("市场策展将通知与 PostHog 插件归为依赖、Compose 归为界面")
    void marketCurationClassifiesDependenciesAndUi() throws Exception {
        JsonNode curation = new ObjectMapper().readTree(
                repoRoot().resolve("scripts").resolve("market-curation.json").toFile());

        assertThat(curation.path("notification").path("category").asText()).isEqualTo("dependency");
        assertThat(curation.path("posthog").path("category").asText()).isEqualTo("dependency");
        assertThat(curation.path("gui-compose").path("category").asText()).isEqualTo("ui");
    }

    @Test
    @DisplayName("默认安装集合同时携带 Compose 与 Swing，官方可选集合只保留显式夹具")
    void distributionSeparatesDefaultInstalledAndValidationFixtures() throws Exception {
        assertThat(officialPluginIds("Get-OfficialDefaultInstalledPlugins"))
                .containsExactly(
                        "download-workbench", "gui-compose", "gui-swing", "gallery-tools", "posthog", "gallery",
                        "novel", "notification", "multi-mode-decision-survey", "push", "mail", "tts", "ai");
        assertThat(runPowerShell(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/plugin-distribution-common.ps1'; "
                        + "@(Get-OfficialOptionalPlugins).Count"))
                .isEqualTo("0");
        assertThat(officialPluginIds())
                .doesNotContain("recovery-sentinel")
                .containsAll(officialPluginIds("Get-OfficialDefaultInstalledPlugins"));
    }

    @Test
    @DisplayName("Douyin 保留同一源码模块和插件身份但退出官方聚合与发布清单")
    void douyinRetainsIdentityOutsideOfficialReleaseAggregation() throws Exception {
        String rootPom = Files.readString(repoRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
        String officialPom = Files.readString(
                repoRoot().resolve("pixivdownload-official-plugins/pom.xml"), StandardCharsets.UTF_8);
        String common = script("plugin-distribution-common.ps1");
        JsonNode curation = new ObjectMapper().readTree(
                repoRoot().resolve("scripts/market-curation.json").toFile());

        assertThat(rootPom).contains("<module>pixivdownload-plugin-douyin</module>");
        assertThat(officialPom).doesNotContain("<artifactId>pixivdownload-plugin-douyin</artifactId>");
        assertThat(common).doesNotContain(
                "Id = \"douyin\"",
                "Module = \"pixivdownload-plugin-douyin\"");
        assertThat(curation.has("douyin")).isFalse();
        assertThat(pluginDescriptor("pixivdownload-plugin-douyin")).contains(
                "plugin.id=douyin",
                "plugin.requires=1.0");
    }

    @Test
    @DisplayName("Windows 升级按随包 manifest 清理同 id 旧包，保留清单外插件")
    void installerUpgradeReconcilesOnlyBundledDefaultPluginIds() throws Exception {
        String inno = innoScript();
        String installerInstall = innoSupportScript("installer-plugin-install.ps1");

        assertThat(installerInstall).contains(
                "ParameterSetName = \"Reconcile\"",
                "[switch]$ReconcileBundledDefaults",
                "function Reconcile-BundledDefaultPlugins",
                "Get-Prop $entry \"id\"",
                "Get-Prop $entry \"version\"",
                "Get-Prop $entry \"file\"",
                "Read-PluginDescriptorFromPackage $stableArtifact",
                "Remove-SupersededInstalledPlugins $pluginsDir $pluginId $stableArtifact",
                "\"$($artifact.FullName).sig.json\"");
        assertThat(inno).contains(
                "procedure ReconcileBundledDefaultPlugins;",
                "{app}\\plugins\\plugins-manifest.json",
                "-ReconcileBundledDefaults",
                "ewWaitUntilTerminated",
                "(CurStep = ssPostInstall) and ShouldInstallApplicationFiles");
        assertThat(inno).doesNotContain(
                "{app}\\plugins\\douyin-*",
                "Type: filesandordirs; Name: \"{app}\\plugins\"");
    }

    @Test
    @DisplayName("Inno 主脚本通过原生 include 装配职责源码")
    void innoSourceGraphAssemblesResponsibilityIncludes() throws Exception {
        Path innoDir = repoRoot().resolve("packaging/windows/inno");
        String main = Files.readString(innoDir.resolve("PixivDownload.iss"), StandardCharsets.UTF_8);
        List<String> includes = List.of(
                "PixivDownload-maintenance.iss.inc",
                "PixivDownload-plugins.iss.inc",
                "PixivDownload-ffmpeg.iss.inc",
                "PixivDownload-lifecycle.iss.inc");

        for (String include : includes) {
            assertThat(main).contains("#include \"" + include + "\"");
            assertThat(innoDir.resolve(include)).isRegularFile();
            assertThat(Files.readString(innoDir.resolve(include), StandardCharsets.UTF_8))
                    .doesNotContain("[Code]");
        }
        assertThat(main.substring(main.indexOf("[Code]"))).doesNotContain("\nbegin\n");

        String compilationUnit = innoScript();
        assertThat(compilationUnit).contains(
                "procedure ResolveExistingInstallation;",
                "procedure LoadPackagedInstallerPluginCatalog;",
                "procedure DownloadAndInstallFfmpeg;",
                "procedure InitializeWizard;",
                "procedure CurStepChanged(CurStep: TSetupStep);");
        assertThat(compilationUnit).doesNotContain("function BuildFfmpegDownloadScript: String;");
    }

    @Test
    @DisplayName("Windows 主程序与安装器 loader 均请求管理员权限")
    void windowsLauncherAndInstallerLoaderRequestAdministratorRights() throws Exception {
        String windows = script("package-local.ps1");
        String executionLevel = script("set-windows-exe-requested-execution-level.ps1");
        String adminLoader = script("prepare-inno-admin-loader.ps1");
        String inno = innoScript();

        assertThat(windows).contains(
                "Patching launcher to request administrator rights",
                "-Level \"requireAdministrator\"");
        assertThat(executionLevel).contains("[string]$Level = \"requireAdministrator\"");
        assertThat(adminLoader).contains("-Level \"requireAdministrator\"");
        assertThat(inno).contains(
                "PrivilegesRequired=admin",
                "runascurrentuser");
    }

    @Test
    @DisplayName("离线分发与 Windows 打包脚本同时携带 artifact 签名和 provenance sidecar")
    void offlinePackagingScriptsCarrySignatureAndProvenanceSidecar() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String distribution = script("assemble-plugin-distribution.ps1");
        String windows = script("package-local.ps1");
        String windowsPluginStaging = script("package-local-plugin-staging.ps1");
        String windowsCatalog = script("package-local-installer-catalog.ps1");
        String catalogStage = script("stage-official-plugin-inputs-from-catalog.ps1");
        String ffmpegIntegrity = script("ffmpeg-release-integrity.ps1");
        String inno = innoScript();
        String installerInstall = innoSupportScript("installer-plugin-install.ps1");
        String ffmpegInstallerDownload = innoSupportScript("installer-ffmpeg-download.ps1");

        assertThat(common).contains(
                "function Get-PixivDownloadSdkVersion",
                "pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties",
                "function New-PluginArtifactSignature",
                "function Find-PluginArtifactSignatureSidecar",
                "function Assert-PluginArtifactSignature",
                "function Get-PluginArtifactSignatureForDistribution",
                "\"verify-artifact\"",
                "function Write-PluginProvenanceSidecar",
                "function Write-UnsignedLocalPluginProvenanceSidecar",
                "Join-Path $artifact.Directory.FullName \"provenance\"",
                ".pixiv-plugin-provenance",
                "artifactSizeBytes=$artifactSizeBytes",
                "artifactSha256=$artifactSha256",
                "signature.formatVersion=$($Signature.formatVersion)",
                "status=VERIFIED",
                "source=LOCAL_UPLOAD",
                "status=UNSIGNED_ALLOWED"
        );
        Matcher signedProvenance = Pattern.compile(
                "function Write-PluginProvenanceSidecar(?<body>.*?)function Write-UnsignedLocalPluginProvenanceSidecar",
                Pattern.DOTALL).matcher(common);
        assertThat(signedProvenance.find()).isTrue();
        assertThat(signedProvenance.group("body")).contains(
                "$artifactSizeBytes = [int64]$artifact.Length",
                "$artifactSha256 = Get-Sha256Hex $ArtifactPath",
                "if ($artifactSizeBytes -ne $ExpectedSizeBytes)",
                "[string]::Equals($artifactSha256, $Sha256, [System.StringComparison]::OrdinalIgnoreCase)",
                "artifactSizeBytes=$artifactSizeBytes",
                "artifactSha256=$artifactSha256");
        assertThat(signedProvenance.group("body").indexOf("if ($artifactSizeBytes -ne $ExpectedSizeBytes)"))
                .isLessThan(signedProvenance.group("body").indexOf("\"status=VERIFIED\""));
        assertThat(signedProvenance.group("body").indexOf("[string]::Equals($artifactSha256, $Sha256"))
                .isLessThan(signedProvenance.group("body").indexOf("\"status=VERIFIED\""));
        for (String script : List.of(distribution, windowsPluginStaging)) {
            assertThat(script).contains(
                    "Find-PluginArtifactSignatureSidecar",
                    "Get-PluginArtifactSignatureForDistribution",
                    "Write-PluginProvenanceSidecar",
                    "signature = $signature"
            );
        }
        assertThat(windows).contains("Assert-NoPrivateKeyMaterial");
        assertThat(distribution).contains(
                "[string]$PrebuiltPluginsDir",
                "Find-PrebuiltOfficialPluginArtifact",
                "[switch]$DefaultDownloader",
                "Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader)",
                "CoreShellOnly and DefaultDownloader cannot be combined.");
        assertThat(windows).contains(
                "$OfficialPluginCatalogUrl = \"https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json\"",
                "$InstallerSdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot $ProjectRoot",
                "$EnableInstallerPluginSelection = $false",
                "Get-OfficialDefaultInstalledPlugins",
                "Stage-InstallerPluginCatalogSnapshot",
                "$InstallerCatalogIncludePath = Join-Path $BuildRoot \"installer-plugin-catalog-items.iss.inc\"",
                "installer-plugin-catalog-items.iss.inc",
                "if ($EnableInstallerPluginSelection)",
                "[switch]$AllowUnsignedLocalPlugins",
                "AllowUnsignedLocalPlugins only accepts plugin artifacts built from the current source tree",
                "AllowUnsignedLocalPlugins requires SkipPortable and SkipOfflinePortable",
                "AllowUnsignedLocalPlugins is only for building a local test installer",
                "out-local-unsigned",
                "$AppName-$Version-LOCAL-UNSIGNED-win-x64-setup.exe",
                "Move-Item -LiteralPath $SetupPath -Destination $LocalUnsignedSetupPath -Force",
                "ffmpeg-release-integrity.ps1",
                "Get-VerifiedFfmpegReleaseAsset",
                "Assert-FfmpegReleaseAsset",
                "$installerPluginCatalogEnabled = if ((-not $SkipPlugins) -and $EnableInstallerPluginSelection) { \"1\" } else { \"0\" }",
                "/DSdkVersion=$InstallerSdkVersion",
                "/DInstallerPluginCatalogEnabled=$installerPluginCatalogEnabled",
                "/DSignatureToolJar=$SignatureToolJar");
        assertThat(windowsPluginStaging).contains(
                "Write-UnsignedLocalPluginProvenanceSidecar",
                "LOCAL-UNSIGNED-BUILD.txt");
        assertThat(windowsCatalog).contains(
                "Write-InstallerPluginCatalogProjection",
                "Write-InstallerPluginCatalogInclude",
                "Escape-InstallerCatalogIssString",
                "[AllowEmptyString()][string]$Fallback",
                "\"verify-manifest\"",
                "installer-catalog",
                "catalog.en.txt",
                "catalog.zh-CN.txt",
                "Get-InstallerCatalogProp $market \"defaultInstalled\"");
        assertThat(catalogStage).contains(
                "$SdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot $ProjectRoot",
                "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json",
                "\"verify-manifest\"",
                "Assert-PluginArtifactSignature",
                "Get-OfficialDistributionPlugins -IncludeOptional:$IncludeOptional",
                "$missingPluginIds = @()",
                "$incompatiblePluginIds = @()",
                "$stagingPlans = @()",
                "Official catalog is not synchronized with this source tree",
                "Catalog generatedTime:",
                "package-installer-with-plugins.ps1 -PluginSource Local with the official signing key",
                "[System.IO.File]::WriteAllText($artifactSignaturePath",
                "$artifactPath.sha256");
        assertThat(catalogStage.indexOf("Remove-Item -LiteralPath $OutputDir -Recurse -Force"))
                .as("catalog 完整性预检失败时不得删除既有 plugin inputs")
                .isGreaterThan(catalogStage.indexOf("if ($problems.Count -gt 0)"));
        assertThat(inno).contains(
                "#ifndef SdkVersion",
                "#error SdkVersion must be supplied from pixivdownload-sdk-info metadata.",
                "#define InstallerPluginCatalogEnabled \"0\"",
                "#error SignatureToolJar must be defined for FFmpeg release verification.",
                "#if InstallerPluginCatalogEnabled == \"1\"",
                "installer-ffmpeg-download.ps1",
                "ffmpeg-release-integrity.ps1",
                "-SignatureToolJar ' + DoubleQuote(SignatureToolTempPath)",
                "installer-plugin-install.ps1",
                "IsInstallerPluginCatalogEnabled",
                "ShouldShowOptionalPluginsPage",
                "OptionalPluginsPage := CreateCustomPage",
                "PluginCheckList.Parent := OptionalPluginsPage.Surface",
                "PageID = OptionalPluginsPage.ID",
                "#include \"..\\..\\..\\build\\installer-plugin-catalog-items.iss.inc\"",
                "Type: files; Name: \"{app}\\plugins\\LOCAL-UNSIGNED-BUILD.txt\"; Check: ShouldInstallApplicationFiles",
                "LoadCompiledInstallerPluginCatalogItems",
                "PackagedPluginCatalogManifestPath",
                "LoadPackagedInstallerPluginCatalog",
                "WizardForm.NextButton.Enabled := True",
                "CurPageID = OptionalPluginsPage.ID",
                "ewNoWait",
                "ReadProgressLineUtf8",
                "LoadStringsFromFile(ProgressPath, Lines)",
                "RaiseException(DecodeCatalogField(Parts[1]))",
                "pixivdownload-plugin-signature-tool.jar");
        assertThat(ffmpegIntegrity).contains(
                "verify-manifest",
                "--repository-id", "ffmpeg-stable",
                "--official-purpose", "ffmpeg",
                "expectedSizeBytes",
                "Get-FileHash -Algorithm SHA256",
                "does not contain the exact asset name");
        assertThat(ffmpegInstallerDownload).contains(
                "Get-VerifiedFfmpegReleaseAsset",
                "Assert-FfmpegReleaseAsset",
                "Move-Item -LiteralPath $downloadPath -Destination $OutFile -Force");
        assertThat(ffmpegInstallerDownload.indexOf("Assert-FfmpegReleaseAsset"))
                .isLessThan(ffmpegInstallerDownload.indexOf(
                        "Move-Item -LiteralPath $downloadPath -Destination $OutFile -Force"));
        assertThat(windows.indexOf("Assert-FfmpegReleaseAsset -ArchivePath $downloadZip"))
                .isLessThan(windows.indexOf("Expand-Archive -Path $zipPath"));
        assertThat(inno.indexOf("DownloadFfmpegArchive(ArchivePath)"))
                .isLessThan(inno.indexOf("ExtractArchive(ArchivePath, ExtractDir"));
        assertThat(inno).doesNotContain("#if Len(SignatureToolJar) > 0");
        assertThat(inno).doesNotContain("LoadStringFromFile(OutputPath");
        assertThat(inno).doesNotContain("RunPowerShellAndWait");
        assertThat(inno).doesNotContain("PluginCatalogManifestUrl");
        assertThat(inno).doesNotContain("PluginCatalogTimeoutMs");
        assertThat(inno).doesNotContain("PluginCatalogOnline");
        assertThat(inno).doesNotContain("installer-plugin-catalog.ps1");
        assertThat(inno).doesNotContain("StartInstallerPluginCatalogLoad");
        assertThat(inno).doesNotContain("FinishInstallerPluginCatalogLoad");
        assertThat(inno).doesNotContain("PollInstallerPluginCatalog");
        assertThat(inno).doesNotContain("installer-plugin-catalog.en.txt");
        assertThat(inno).doesNotContain("installer-plugin-catalog.zh-CN.txt");
        assertThat(inno).doesNotContain("PluginCatalogProjection");
        assertThat(inno).doesNotContain("ExtractPluginCatalogProjectionFile");
        assertThat(inno).doesNotContain("ParsePluginCatalogOutput");
        assertThat(inno).doesNotContain("LoadStringsFromFile(OutputPath");
        assertThat(inno).doesNotContain("solidbreak");
        assertThat(inno).doesNotContain("SetTimer@user32.dll");
        assertThat(inno).doesNotContain("KillTimer@user32.dll");
        assertThat(inno).doesNotContain("StartPluginCatalogTimer");
        assertThat(inno).doesNotContain("PluginCatalogTimerProc");
        assertThat(inno).doesNotContain("CreateCallback(@PluginCatalogTimerProc)");
        assertThat(inno).doesNotContain("StartPluginCatalogProjectionTimer");
        assertThat(inno).doesNotContain("StopPluginCatalogProjectionTimer");
        assertThat(inno).doesNotContain("CreateCallback(@PluginCatalogProjectionTimerProc)");
        assertThat(inno).doesNotContain("SchedulePackagedInstallerPluginCatalogLoad");
        assertThat(inno).doesNotContain("while (not IsPluginCatalogOutputReady(OutputPath))");
        assertThat(inno).doesNotContain("WizardForm.NextButton.Enabled := False");
        assertThat(inno).doesNotContainPattern("if\\s+ShouldShowOptionalPluginsPage\\s+then\\s+StartPluginCatalogTimer");
        Matcher initializeWizard = Pattern.compile("procedure InitializeWizard;(?<body>.*?)function ShouldSkipPage",
                Pattern.DOTALL).matcher(inno);
        assertThat(initializeWizard.find()).isTrue();
        assertThat(initializeWizard.group("body")).doesNotContain("StartPluginCatalogTimer");
        Matcher curPageChanged = Pattern.compile(
                "procedure CurPageChanged\\(CurPageID: Integer\\);(?<body>.*?)function GetProcessExeName",
                Pattern.DOTALL).matcher(innoSupportScript("PixivDownload-lifecycle.iss.inc"));
        assertThat(curPageChanged.find()).isTrue();
        assertThat(curPageChanged.group("body")).contains("LoadPackagedInstallerPluginCatalog");
        assertThat(curPageChanged.group("body")).doesNotContain(
                "CurPageID = OptionalFeaturesPage.ID",
                "StartPluginCatalogTimer",
                "FinishInstallerPluginCatalogLoad",
                "ExtractPluginInstallerSupportFiles");
        assertThat(installerInstall).contains(
                "[Parameter(Mandatory = $true, ParameterSetName = \"Install\")][string]$SdkVersion",
                "$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)",
                "$Utf8NoBom.GetBytes($Value + \"`n\")",
                "function Escape-StateField",
                "function Format-Error([System.Management.Automation.ErrorRecord]$ErrorRecord)",
                "return $result.ToArray()",
                "[System.Net.WebRequest]::GetSystemWebProxy()",
                "Write-State (\"ERROR|\" + (Escape-StateField (Format-Error $_)))",
                "function Remove-SupersededInstalledPlugins",
                "Read-PluginIdFromPackage",
                "plugin.id",
                "$item.PluginId + \"-\" + $item.Version + $ext",
                "Remove-SupersededInstalledPlugins $pluginsDir $item.PluginId $target",
                "$($artifact.FullName).sha256",
                "$($artifact.FullName).sig",
                ".pixiv-plugin-provenance");
        Matcher installerProvenance = Pattern.compile(
                "function Write-Provenance(?<body>.*?)function Enable-Plugin",
                Pattern.DOTALL).matcher(installerInstall);
        assertThat(installerProvenance.find()).isTrue();
        assertThat(installerProvenance.group("body")).contains(
                "$expectedSizeBytes = [int64](Get-Prop $Item.Package \"expectedSizeBytes\")",
                "$expectedSha256 = [string](Get-Prop $Item.Package \"sha256\")",
                "$artifactSizeBytes = [int64]$artifact.Length",
                "$artifactSha256 = Get-Sha256Hex $ArtifactPath",
                "if ($artifactSizeBytes -ne $expectedSizeBytes)",
                "[string]::Equals($artifactSha256, $expectedSha256, [System.StringComparison]::OrdinalIgnoreCase)",
                "artifactSizeBytes=$artifactSizeBytes",
                "artifactSha256=$artifactSha256");
        assertThat(installerProvenance.group("body").indexOf("if ($artifactSizeBytes -ne $expectedSizeBytes)"))
                .isLessThan(installerProvenance.group("body").indexOf("\"status=VERIFIED\""));
        assertThat(installerProvenance.group("body").indexOf("[string]::Equals($artifactSha256, $expectedSha256"))
                .isLessThan(installerProvenance.group("body").indexOf("\"status=VERIFIED\""));
        assertThat(installerInstall.indexOf("Write-Provenance $item $target \"$target.sig\""))
                .isGreaterThan(installerInstall.indexOf(
                        "Copy-Item -LiteralPath $download -Destination $target -Force"));
    }

    @Test
    @DisplayName("PowerShell provenance 脚本保持无 BOM 的纯 ASCII 字节")
    void provenancePowerShellScriptsAreAsciiWithoutBom() throws Exception {
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("plugin-distribution-common.ps1"));
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("ffmpeg-release-integrity.ps1"));
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("package-local-plugin-staging.ps1"));
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("package-local-installer-catalog.ps1"));
        assertAsciiWithoutBom(repoRoot().resolve("packaging").resolve("windows").resolve("inno")
                .resolve("installer-plugin-install.ps1"));
        assertAsciiWithoutBom(repoRoot().resolve("packaging").resolve("windows").resolve("inno")
                .resolve("installer-ffmpeg-download.ps1"));
    }

    @Test
    @DisplayName("插件凭证密钥资源默认使用公开回退值，生产过滤脚本严格校验独立的 32 字节主密钥")
    void pluginCredentialKeyResourceUsesValidatedBuildFilter() throws Exception {
        Path appRoot = repoRoot().resolve("pixivdownload-app");
        String pom = Files.readString(appRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
        String resource = Files.readString(
                appRoot.resolve("src/main/resources/plugin-credential-key.properties"),
                StandardCharsets.UTF_8);
        Map<String, String> openSourceFilter = readProperties(
                appRoot.resolve("src/main/filters/plugin-credential-key-open-source.properties"));
        String script = script("write-plugin-credential-key-filter.ps1");

        assertThat(pom).contains(
                "<plugin.credential.filter>",
                "src/main/filters/plugin-credential-key-open-source.properties",
                "<filter>${plugin.credential.filter}</filter>",
                "<include>plugin-credential-key.properties</include>",
                "<exclude>plugin-credential-key.properties</exclude>");
        assertThat(resource).contains(
                "profile=@plugin.credential.key.profile@",
                "current-key-base64=@plugin.credential.key.current-base64@",
                "open-source-fallback-key-base64=@plugin.credential.key.open-source-fallback-base64@");

        assertThat(openSourceFilter.get("plugin.credential.key.profile")).isEqualTo("open-source");
        String currentKey = openSourceFilter.get("plugin.credential.key.current-base64");
        String fallbackKey = openSourceFilter.get("plugin.credential.key.open-source-fallback-base64");
        assertThat(currentKey).isEqualTo(fallbackKey);
        assertCanonicalBase64Key(currentKey);
        assertThat(script).contains("$openSourceFallback = \"" + fallbackKey + "\"");

        assertThat(script).contains(
                "$secretName = \"PIXIVDOWNLOAD_PLUGIN_CREDENTIAL_MASTER_KEY_BASE64\"",
                "[Environment]::GetEnvironmentVariable($secretName)",
                "[Convert]::FromBase64String($masterKeyBase64)",
                "$masterKey.Length -ne 32",
                "[Convert]::ToBase64String($masterKey)",
                "[StringComparison]::Ordinal",
                "$canonical, $openSourceFallback",
                "\"plugin.credential.key.profile=production\"",
                "\"plugin.credential.key.current-base64=$canonical\"",
                "\"plugin.credential.key.open-source-fallback-base64=$openSourceFallback\"",
                "[System.IO.File]::WriteAllText",
                "System.Text.UTF8Encoding($false)",
                "[Array]::Clear");
        assertThat(script).doesNotContain(
                "Write-Host",
                "Write-Output",
                "Write-Verbose",
                "Write-Debug",
                "Write-Information");
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("write-plugin-credential-key-filter.ps1"));
    }

    @Test
    @DisplayName("本地一键安装器脚本支持签名 catalog、当前源码官方签名及显式本地 unsigned 测试输入")
    void oneShotInstallerScriptSupportsCatalogSignedLocalAndExplicitUnsignedLocalInputs() throws Exception {
        String script = script("package-installer-with-plugins.ps1");

        assertThat(script).contains(
                "$SdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot $ProjectRoot",
                "[ValidateSet(\"Catalog\", \"Local\")]",
                "[string]$PluginSource = \"Catalog\"",
                "[string]$OfficialKeyId",
                "[string]$PrivateKeyFile",
                "[switch]$AllowUnsignedLocalPlugins",
                "Get-OfficialDefaultInstalledPlugins",
                "$mavenProjects = @($mavenProjects | Select-Object -Unique)",
                "if ($PluginSource -eq \"Catalog\")",
                "stage-official-plugin-inputs-from-catalog.ps1",
                "package-local.ps1",
                "Resolve-SignatureToolJar",
                "SignatureToolJar must not be empty.",
                "$packageArgs.PrebuiltPluginsDir = $PluginInputsDir",
                "$packageArgs.OfficialKeyId = $OfficialKeyId",
                "$packageArgs.PrivateKeyFile = $PrivateKeyFile",
                "AllowUnsignedLocalPlugins can only be used with -PluginSource Local.",
                "AllowUnsignedLocalPlugins cannot be combined with OfficialKeyId, PrivateKeyFile, or SignatureToolJar.",
                "$packageArgs.AllowUnsignedLocalPlugins = $true",
                "LOCAL TEST ONLY",
                "SkipPortable = $true",
                "SkipOfflinePortable = $true",
                "& $PackageLocalScript @packageArgs",
                "build/out-local-unsigned/PixivDownload-$Version-LOCAL-UNSIGNED-win-x64-setup.exe",
                "PixivDownload-$Version-win-x64-setup.exe"
        );
        assertThat(script).doesNotContain(
                "-IncludeOptional",
                "-SkipPlugins"
        );
    }

    @Test
    @DisplayName("本地 unsigned 安装器开关不得进入正式分发或发布 workflow")
    void unsignedLocalInstallerModeIsExcludedFromDistributionAndReleaseWorkflows() throws Exception {
        assertThat(script("assemble-plugin-distribution.ps1")).doesNotContain("AllowUnsignedLocalPlugins");
        assertThat(script("package-java-distributions.ps1")).doesNotContain("AllowUnsignedLocalPlugins");
        assertThat(script("stage-official-plugin-inputs-from-catalog.ps1"))
                .doesNotContain("AllowUnsignedLocalPlugins");
        for (String name : List.of("release.yml", "nightly.yml", "publish-plugins.yml")) {
            assertThat(workflow(name)).as(name).doesNotContain("AllowUnsignedLocalPlugins");
        }
        for (String name : List.of("package-release-java", "package-windows-installer", "sign-update-manifest")) {
            assertThat(action(name)).as(name).doesNotContain("AllowUnsignedLocalPlugins");
        }
    }

    @Test
    @DisplayName("离线分发 boot jar 边界黑名单覆盖可选外置插件")
    void offlineDistributionBootJarBlacklistCoversOptionalPlugins() throws Exception {
        String distribution = script("assemble-plugin-distribution.ps1");

        assertThat(distribution).contains(
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/ai/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/duplicate/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/novel/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/novelgallery/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/notification/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/push/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/tts/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/download/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/schedule/\"",
                "\"BOOT-INF/classes/top/sywyar/pixivdownload/notificationbase/\"",
                "\"BOOT-INF/classes/static/pixiv-novel-download\"",
                "\"BOOT-INF/classes/i18n/web/duplicates\"",
                "\"BOOT-INF/classes/i18n/web/novel\"",
                "\"BOOT-INF/classes/i18n/web/novel-gallery\"",
                "\"BOOT-INF/classes/i18n/web/narration\"",
                "\"BOOT-INF/classes/i18n/web/notification\""
        );
    }

    @Test
    @DisplayName("JavaScript 测试脚本覆盖所有模块测试且 YAML 解析器仅为开发依赖")
    void javascriptTestScriptsCoverAllModules() throws Exception {
        JsonNode packageJson = new ObjectMapper().readTree(repoRoot().resolve("package.json").toFile());

        assertThat(packageJson.path("private").asBoolean()).isTrue();
        assertThat(packageJson.path("scripts").path("test:js").asText())
                .isEqualTo("node --test \"pixivdownload-*/src/test/js/*.test.js\" "
                        + "\"plugin-templates/*/src/test/js/*.test.js\" "
                        + "\"scripts/ci/test/*.test.mjs\"");
        assertThat(packageJson.path("scripts").path("test:web-standards").asText())
                .isEqualTo("node scripts/check-web-standards.mjs");
        assertThat(packageJson.has("dependencies")).isFalse();
        assertThat(packageJson.path("devDependencies").path("yaml").asText())
                .isNotBlank();
    }

    @Test
    @DisplayName("市场清单从 descriptor 投影 SDK 要求并保留旧 wire alias")
    void marketManifestProjectsInitialSdkAndLegacyAlias() throws Exception {
        String descriptor = pluginDescriptor("pixivdownload-plugin-download-workbench");

        assertThat(descriptor)
                .contains("plugin.requires=1.0")
                .doesNotContain(
                        "plugin.requires=1.1",
                        "plugin.requires=1.2",
                        "plugin.requires=1.3");
        assertThat(script("generate-market-manifest.ps1")).contains(
                "$requires = $d[\"plugin.requires\"]",
                "requiredSdk       = (Get-RequiredSdk $requires)",
                "requiredCoreApi   = (Get-RequiredSdk $requires)");
        assertThat(script("stage-official-plugin-inputs-from-catalog.ps1")).contains(
                "Get-Prop $Package \"requiredSdk\"",
                "Get-Prop $Package \"requiredCoreApi\"");
        assertThat(script("package-local-installer-catalog.ps1")).contains(
                "Get-InstallerCatalogProp $Package \"requiredSdk\"",
                "Get-InstallerCatalogProp $Package \"requiredCoreApi\"");
        assertThat(innoSupportScript("installer-plugin-install.ps1")).contains(
                "Get-Prop $Package \"requiredSdk\"",
                "Get-Prop $Package \"requiredCoreApi\"");
    }

    @Test
    @DisplayName("安装器目录投影执行公开入口并按 SDK 与安装集合过滤")
    void installerCatalogProjectionExecutesPublicEntryPoint(@TempDir Path tempDir) throws Exception {
        assumeTrue(canRun("powershell", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major")
                        || canRun("pwsh", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major"),
                "PowerShell 不可用，跳过行为测试");
        Path manifest = tempDir.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "entries": [
                    {
                      "pluginId": "optional-sample",
                      "market": {
                        "officialRequired": false,
                        "defaultInstalled": false,
                        "latestVersion": "1.2.3",
                        "displayName": {"en": "Optional Sample", "zh": "可选示例"},
                        "summary": {"en": "Install on demand", "zh": "按需安装"},
                        "category": "tools"
                      },
                      "packages": [{
                        "version": "1.2.3",
                        "packageUrl": "https://example.invalid/sample.jar",
                        "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "expectedSizeBytes": 123,
                        "signature": {"formatVersion": 1},
                        "requiredSdk": "1.0"
                      }]
                    },
                    {
                      "pluginId": "default-sample",
                      "market": {
                        "officialRequired": false,
                        "defaultInstalled": true,
                        "latestVersion": "1.0.0",
                        "displayName": {"en": "Default Sample"}
                      },
                      "packages": [{
                        "version": "1.0.0",
                        "packageUrl": "https://example.invalid/default.jar",
                        "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "expectedSizeBytes": 456,
                        "signature": {"formatVersion": 1},
                        "requiredSdk": "1.0"
                      }]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        String rowsJson = runPowerShell(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/package-local-installer-catalog.ps1'; "
                        + "$rows=@(New-InstallerCatalogProjectionRows -ManifestPath '" + psQuote(manifest)
                        + "' -Language 'en' -SdkVersion '1.0.0'); "
                        + "ConvertTo-Json -InputObject $rows -Depth 4 -Compress");
        JsonNode rows = new ObjectMapper().readTree(rowsJson);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("PluginId").asText()).isEqualTo("optional-sample");
        assertThat(rows.get(0).path("Version").asText()).isEqualTo("1.2.3");
        assertThat(rows.get(0).path("DisplayName").asText()).isEqualTo("Optional Sample");
        assertThat(rows.get(0).path("Summary").asText()).isEqualTo("Install on demand");
        assertThat(rows.get(0).path("Size").asText()).isEqualTo("123");

        CommandResult failure = runPowerShellResult(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/package-local-installer-catalog.ps1'; "
                        + "$null=New-InstallerCatalogProjectionRows -ManifestPath '"
                        + psQuote(tempDir.resolve("missing.json")) + "' -Language 'en' -SdkVersion '1.0.0'");
        assertThat(failure.exitCode()).as(failure.output()).isNotZero();
    }

    @Test
    @DisplayName("本地插件暂存执行公开入口并写出可复验布局")
    void packageLocalPluginStagingExecutesPublicEntryPoint(@TempDir Path tempDir) throws Exception {
        assumeTrue(canRun("powershell", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major")
                        || canRun("pwsh", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major"),
                "PowerShell 不可用，跳过行为测试");
        Path prebuilt = Files.createDirectories(tempDir.resolve("prebuilt"));
        Path appDir = Files.createDirectories(tempDir.resolve("app"));
        Path expectedArtifact = prebuilt.resolve("sample-module-1.0.0.jar");
        Path collidingArtifact = prebuilt.resolve("sample-module-tools-9.0.0.jar");
        writeThinPluginJar(expectedArtifact, "sample", "1.0.0", "1.0");
        writeThinPluginJar(collidingArtifact, "sample-tools", "9.0.0", "1.0");
        Files.setLastModifiedTime(expectedArtifact, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(collidingArtifact, FileTime.fromMillis(2_000));

        String command = "$ErrorActionPreference='Stop'; "
                + ". './scripts/package-local-plugin-staging.ps1'; "
                + "$plugin=[pscustomobject]@{Id='sample';Module='sample-module';Format='jar';PrivateLibs=$false}; "
                + "$count=Stage-OfficialPlugins -AppDir '" + psQuote(appDir) + "' -Plugins @($plugin) "
                + "-PrebuiltPluginsDir '" + psQuote(prebuilt) + "' -ProjectRoot '" + psQuote(repoRoot())
                + "' -AllowUnsignedLocalPlugins; Write-Output ('RESULT:'+$count)";
        CommandResult result = runPowerShellResult(command);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("RESULT:1");

        Path plugins = appDir.resolve("plugins");
        Path artifact = plugins.resolve("sample-module.jar");
        assertThat(artifact).isRegularFile();
        assertThat(plugins.resolve("sample-module.jar.sha256")).isRegularFile();
        assertThat(plugins.resolve("SHA256SUMS")).isRegularFile();
        assertThat(plugins.resolve("LOCAL-UNSIGNED-BUILD.txt")).isRegularFile();
        assertThat(plugins.resolve("sample-module.jar.sig")).doesNotExist();
        Path provenance = plugins.resolve("provenance/sample-module.jar.pixiv-plugin-provenance");
        assertThat(provenance).isRegularFile();
        assertThat(Files.readString(provenance, StandardCharsets.UTF_8))
                .contains("source=LOCAL_UPLOAD", "status=UNSIGNED_ALLOWED");

        JsonNode manifest = new ObjectMapper().readTree(
                Files.readString(plugins.resolve("plugins-manifest.json"), StandardCharsets.UTF_8));
        JsonNode entry = manifest.isArray() ? manifest.get(0) : manifest;
        assertThat(entry.path("id").asText()).isEqualTo("sample");
        assertThat(entry.path("version").asText()).isEqualTo("1.0.0");
        assertThat(entry.path("requires").asText()).isEqualTo("1.0");
        assertThat(entry.path("file").asText()).isEqualTo("sample-module.jar");
        assertThat(entry.path("source").asText()).isEqualTo("LOCAL_UPLOAD");
        assertThat(entry.path("verification").asText()).isEqualTo("UNSIGNED_ALLOWED");

        CommandResult failure = runPowerShellResult(
                "$ErrorActionPreference='Stop'; . './scripts/package-local-plugin-staging.ps1'; "
                        + "$null=Resolve-PrebuiltPluginsDir '" + psQuote(tempDir.resolve("missing")) + "'");
        assertThat(failure.exitCode()).as(failure.output()).isNotZero();
    }

    @Test
    @DisplayName("release/nightly 通过共享动作只上传一个 app-shell JAR，安装器消费同一 artifact")
    void releaseWorkflowsUploadOnlyStagedAppShellJar() throws Exception {
        String javaAction = action("package-release-java");
        String windowsAction = action("package-windows-installer");

        assertThat(javaAction).contains(
                "Stage executable JAR",
                "build/release-jars",
                "jar tf \"$OUTPUT_JAR\" | grep -q '^BOOT-INF/'",
                "test -s \"$OUTPUT_JAR\"",
                "STAGED_COUNT",
                "path: build/release-jars/*.jar",
                "name: app-shell-jar",
                "if-no-files-found: error",
                "Upload internal app-shell JAR",
                "Expected exactly one executable app jar",
                "PixivDownload-*-boot.jar 2>/dev/null",
                "-original\\.jar");
        assertThat(windowsAction).contains(
                "Download internal app-shell JAR",
                "path: artifacts/app-shell-jar",
                "$jars = @(Get-ChildItem artifacts/app-shell-jar/PixivDownload-*.jar -File)",
                "$jars.Count -ne 1");
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "uses: ./.github/actions/package-release-java",
                    "uses: ./.github/actions/package-windows-installer");
            assertThat(workflow).as(name).doesNotContain(
                    "Stage executable JAR",
                    "Get-ChildItem artifacts/app-shell-jar/PixivDownload-*.jar",
                    "name: jar",
                    "artifacts/jar",
                    "path: artifacts/jar",
                    "path: pixivdownload-app/target/PixivDownload-*.jar");
        }
    }

    @Test
    @DisplayName("release/nightly 经共享动作发布 java-standard 与 full-offline 签名分发布局")
    void releaseWorkflowsPublishJavaDistributions() throws Exception {
        String javaAction = action("package-release-java");
        assertThat(javaAction).contains(
                "Stage official plugin inputs from signed catalog",
                "stage-official-plugin-inputs-from-catalog.ps1",
                "IncludeOptional = $true",
                "Assemble Java distributions",
                "package-java-distributions.ps1",
                "-PrebuiltJar $jars[0].FullName",
                "-PrebuiltPluginsDir build/plugin-inputs",
                "-SignatureToolJar $signatureTools[0].FullName",
                "name: java-distributions",
                "build/plugin-distributions/PixivDownload-*-java.zip",
                "build/plugin-distributions/PixivDownload-*-full-offline.zip",
                "if-no-files-found: error",
                "name: plugin-inputs",
                "path: build/plugin-inputs/*");
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "publish-plugins:",
                    "uses: ./.github/workflows/publish-plugins.yml",
                    "uses: ./.github/actions/package-release-java",
                    "uses: ./.github/actions/package-windows-installer",
                    "path: artifacts/java-distributions",
                    "Generate update manifest",
                    "artifacts/update.json",
                    "artifacts/update.json.sig",
                    "\"win-x64-installer\"");
            // 最终 Release files 只含安装包 + 两个 ZIP + update.json 及其签名，绝不含裸 JAR / app-shell JAR。
            String filesBlock = workflow.substring(workflow.lastIndexOf("files: |"));
            assertThat(filesBlock).as(name + " release files").contains(
                    "artifacts/*-setup.exe",
                    "artifacts/java-distributions/*-java.zip",
                    "artifacts/java-distributions/*-full-offline.zip",
                    "artifacts/update.json",
                    "artifacts/update.json.sig")
                    .doesNotContain(".jar");
            assertThat(workflow).as(name).doesNotContain(
                    "-CoreShellOnly",
                    "-DefaultDownloader",
                    "default-downloader.zip",
                    "core-shell-only.zip",
                    "name: jar",
                    "artifacts/jar",
                    "name: plugins",
                    "path: build/release-plugins/*.jar",
                    "artifacts/plugins/*.jar");
        }
    }

    @Test
    @DisplayName("release/nightly 发布前安装并启动最终 Windows 与 Java 分发产物")
    void releaseWorkflowsRunFinalArtifactAcceptanceBeforePublication() throws Exception {
        String script = script("test-release-artifacts.ps1");
        String acceptanceAction = action("test-release-artifacts");

        assertThat(script).contains(
                "Refusing installer E2E because PixivDownload is already registered",
                "Expand-Archive -LiteralPath $Archive",
                "PixivDownload-$Version.jar",
                "runtime\\bin\\server\\jvm.dll",
                "--setup",
                "--no-gui",
                "/actuator/health",
                "/actuator/info",
                "/api/auth/login",
                "/api/plugins/status",
                "status -ne \"STARTED\"",
                "runtimePhase -ne \"STARTED\"",
                "recoveryMode",
                "Wait-ArtifactProcessExit",
                "-TimeoutSeconds 600",
                "-TimeoutSeconds 300",
                "unins000.exe");
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("test-release-artifacts.ps1"));
        assertThat(acceptanceAction).contains(
                "name: windows-installer",
                "name: java-distributions",
                "Install and start final release artifacts",
                "./scripts/test-release-artifacts.ps1",
                "-InstallerPath $installers[0].FullName",
                "-JavaZipPath $javaZips[0].FullName",
                "-FullOfflineZipPath $offlineZips[0].FullName");

        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);
            String acceptanceJob = workflowJob(workflow, "release-artifact-e2e");
            String publicationJob = workflowJob(workflow,
                    name.equals("release.yml") ? "release" : "release-nightly");

            assertThat(acceptanceJob).as(name).contains(
                    "runs-on: windows-latest",
                    "timeout-minutes: 45",
                    "uses: ./.github/actions/test-release-artifacts",
                    "release_version:");
            assertThat(publicationJob).as(name).contains("release-artifact-e2e");
        }
    }

    @Test
    @DisplayName("分发组装脚本支持精确 PrebuiltJar 输入：互斥、严格验证、精确路径、本地 fallback 保留")
    void distributionAssemblerSupportsExactPrebuiltJarInput() throws Exception {
        String distribution = script("assemble-plugin-distribution.ps1");

        assertThat(distribution).contains(
                "[string]$PrebuiltJar",
                "Build and PrebuiltJar cannot be combined.",
                "Test-Path -LiteralPath $PrebuiltJar -PathType Leaf",
                "$prebuiltItem.Extension.Equals(\".jar\", [System.StringComparison]::OrdinalIgnoreCase)",
                "PrebuiltJar is empty",
                "PrebuiltJar cannot be read as a zip/jar",
                "missing BOOT-INF/",
                "$SelectedAppJar = $prebuiltItem.FullName",
                "if (-not $SelectedAppJar) {",
                "Get-AppBootJar",
                "Assert-BootJarBoundary $SelectedAppJar",
                "Copy-Item $SelectedAppJar (Join-Path $OutputDir $coreJarName) -Force");
        // 路径解析必须发生在 Push-Location 切目录之前。
        assertThat(distribution.indexOf("$SelectedAppJar = $prebuiltItem.FullName"))
                .as("PrebuiltJar 解析必须先于 Push-Location")
                .isLessThan(distribution.indexOf("Push-Location $ProjectRoot"));
        // 指定 PrebuiltJar 后不再走 Get-AppBootJar：fallback 分支整体位于 Push-Location 之后。
        assertThat(distribution.indexOf("if (-not $SelectedAppJar) {"))
                .as("PrebuiltJar 指定后不再调用 Get-AppBootJar")
                .isGreaterThan(distribution.indexOf("Push-Location $ProjectRoot"));
    }

    @Test
    @DisplayName("非 CoreShellOnly 分发生成引用精确 JAR 的 run.bat / run.sh 启动脚本")
    void distributionAssemblerGeneratesLaunchScripts() throws Exception {
        String distribution = script("assemble-plugin-distribution.ps1");

        assertThat(distribution).contains(
                "Writing launch scripts",
                "$coreJarName = \"PixivDownload-$Version.jar\"",
                "$runBat = $runBat.Replace(\"PixivDownload-VERSION.jar\", $coreJarName)",
                "$runSh = $runSh.Replace(\"PixivDownload-VERSION.jar\", $coreJarName)",
                "[System.IO.File]::WriteAllText((Join-Path $OutputDir \"run.bat\")",
                "[System.IO.File]::WriteAllText((Join-Path $OutputDir \"run.sh\")",
                "$runBat = ($runBat -replace \"`r?`n\", \"`r`n\")",
                "$runSh = ($runSh -replace \"`r?`n\", \"`n\")",
                "$Utf8NoBom");
        // run.bat 契约：%~dp0 / 切目录 / plugins-dir / 精确 JAR 名 / %* 透传 / ERRORLEVEL。
        Matcher runBat = Pattern.compile("@echo off(?<body>.*?)exit /b %ERRORLEVEL%", Pattern.DOTALL)
                .matcher(distribution);
        assertThat(runBat.find()).isTrue();
        assertThat(runBat.group("body")).contains(
                "setlocal",
                "set \"APP_HOME=%~dp0\"",
                "cd /d \"%APP_HOME%\" || exit /b 1",
                "\"-Dpixivdownload.plugins-dir=%APP_HOME%plugins\"",
                "\"%APP_HOME%PixivDownload-VERSION.jar\" %*")
                .doesNotContain("*.jar", "Get-ChildItem", "head");
        // run.sh 契约：#!/usr/bin/env sh / 自解析目录 / plugins-dir / 精确 JAR 名 / "$@" / exec。
        Matcher runSh = Pattern.compile("#!/usr/bin/env sh(?<body>.*?\"\\$@\")", Pattern.DOTALL)
                .matcher(distribution);
        assertThat(runSh.find()).isTrue();
        assertThat(runSh.group("body")).contains(
                "set -eu",
                "APP_HOME=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)",
                "cd \"$APP_HOME\"",
                "\"-Dpixivdownload.plugins-dir=$APP_HOME/plugins\"",
                "\"$APP_HOME/PixivDownload-VERSION.jar\" \\",
                "\"$@\"")
                .doesNotContain("*.jar", "Get-ChildItem", "head");
    }

    @Test
    @DisplayName("共享 Java 分发编排脚本两次调用组装器并做真实布局验收")
    void javaDistributionOrchestratorContract() throws Exception {
        String orchestrator = script("package-java-distributions.ps1");

        assertThat(orchestrator).contains(
                "[Parameter(Mandatory = $true)][string]$Version",
                "[Parameter(Mandatory = $true)][string]$PrebuiltJar",
                "[Parameter(Mandatory = $true)][string]$PrebuiltPluginsDir",
                "[Parameter(Mandatory = $true)][string]$SignatureToolJar",
                "[string]$OutputDir",
                "assemble-plugin-distribution.ps1",
                "-PrebuiltJar $ResolvedPrebuiltJar",
                "-PrebuiltPluginsDir $ResolvedPrebuiltPluginsDir",
                "-SignatureToolJar $ResolvedSignatureToolJar",
                "-DefaultDownloader",
                "Get-OfficialDefaultInstalledPlugins",
                "Get-OfficialDistributionPlugins -IncludeOptional",
                "PixivDownload-$Version-java.zip",
                "PixivDownload-$Version-full-offline.zip",
                "Assert-DistributionLayout",
                "run.bat",
                "run.sh",
                "plugins-manifest.json",
                "SHA256SUMS",
                "LOCAL-UNSIGNED-BUILD.txt",
                "$prov[\"status\"] -ne \"VERIFIED\"",
                "$prov[\"source\"] -ne \"MARKET_CATALOG\"",
                "Compress-Archive",
                "$InputJarSha256 = Get-Sha256Hex $ResolvedPrebuiltJar",
                "-InputJarSha256 $InputJarSha256");
        // 不接收私钥 / 本地 unsigned 输入。
        assertThat(orchestrator).doesNotContain(
                "AllowUnsignedLocalPlugins",
                "PrivateKeyFile",
                "OfficialKeyId",
                "-----BEGIN PRIVATE KEY-----",
                "-----END PRIVATE KEY-----");
        // java-standard（DefaultDownloader）先于 full-offline；两次调用使用同一组输入；
        // 布局验收必须先于压缩，避免半成品被打包。
        assertThat(orchestrator.indexOf("-DefaultDownloader"))
                .as("java-standard 调用必须先于 full-offline")
                .isLessThan(orchestrator.indexOf("Assembling full-offline"));
        assertThat(orchestrator.indexOf("& $AssemblerScript", orchestrator.indexOf("Assembling java-standard")))
                .as("组装器必须被调用两次").isGreaterThan(0);
        assertThat(orchestrator.indexOf("& $AssemblerScript", orchestrator.indexOf("Assembling full-offline")))
                .as("组装器必须被调用两次").isGreaterThan(0);
        assertThat(orchestrator.indexOf("Compress-Archive"))
                .as("布局验收必须先于压缩")
                .isGreaterThan(orchestrator.indexOf("-InputJarSha256 $InputJarSha256"));
    }

    @Test
    @DisplayName("Java 分发编排脚本保持 ASCII 无 BOM（Windows PowerShell 5.1 兼容）")
    void javaDistributionOrchestratorIsAsciiWithoutBom() throws Exception {
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("package-java-distributions.ps1"));
    }

    @Test
    @DisplayName("Windows 打包与 Java 标准包共享同一默认插件集合来源，无第二份发行插件列表")
    void setupAndJavaStandardShareDefaultInstalledSet() throws Exception {
        String windows = script("package-local.ps1");
        String orchestrator = script("package-java-distributions.ps1");
        String distribution = script("assemble-plugin-distribution.ps1");

        assertThat(windows).contains("$defaultInstalledPlugins = @(Get-OfficialDefaultInstalledPlugins)");
        assertThat(orchestrator).contains(
                "$DefaultInstalledIds = @(Get-OfficialDefaultInstalledPlugins | ForEach-Object { $_.Id })");
        assertThat(distribution).contains(
                "Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader)");
        // 打包脚本不得自带第二份硬编码插件清单（common 才是唯一事实源）。
        for (String name : List.of(
                "package-java-distributions.ps1",
                "assemble-plugin-distribution.ps1",
                "package-local.ps1",
                "package-local-plugin-staging.ps1",
                "package-local-installer-catalog.ps1")) {
            assertThat(script(name)).as(name).doesNotContain(
                    "Id = \"download-workbench\"",
                    "Id = \"gui-swing\"",
                    "Id = \"douyin\"",
                    "Id = \"gallery-tools\"");
        }
    }

    @Test
    @DisplayName("Java 标准包与 full-offline 只消费共享官方集合并核对实际 artifact")
    void javaDistributionsUseCanonicalOfficialSets() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String orchestrator = script("package-java-distributions.ps1");
        String distribution = script("assemble-plugin-distribution.ps1");

        assertThat(common).contains("function Get-OfficialOptionalPlugins");
        assertThat(orchestrator).contains(
                "-DefaultDownloader",
                "Get-OfficialDistributionPlugins -IncludeOptional",
                "staged plugin artifacts do not match plugins-manifest.json");
        assertThat(distribution).contains(
                "[switch]$DefaultDownloader",
                "Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader)");
        assertThat(orchestrator).doesNotContain("douyin", "ExpectDouyin");
    }

    @Test
    @DisplayName("update 清单只包含 win-x64-installer 资产，不扩展 Java / 离线资产类型")
    void updateManifestStaysInstallerOnly() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);
            assertThat(workflow).as(name).contains("Generate update manifest");
            Matcher assets = Pattern.compile("assets: \\{(?<body>.*?)\\}' > artifacts/update\\.json",
                    Pattern.DOTALL).matcher(workflow);
            assertThat(assets.find()).as(name + " update manifest assets block").isTrue();
            String block = assets.group("body");
            assertThat(block).as(name).doesNotContain(
                    "java-standard", "java-zip", "java.zip", "full-offline");
            List<String> keys = new ArrayList<>();
            Matcher keyMatcher = Pattern.compile("\"([a-z0-9-]+)\":").matcher(block);
            while (keyMatcher.find()) {
                keys.add(keyMatcher.group(1));
            }
            assertThat(keys).as(name + " update asset keys").containsExactly("win-x64-installer");
        }
    }

    @Test
    @DisplayName("Release 与 Nightly 为更新清单写入时效元数据并生成 detached 签名")
    void updateManifestsAreVersionedAndSigned() throws Exception {
        String signingAction = action("sign-update-manifest");
        assertThat(signingAction).contains(
                "Checkout trusted update signature tool source",
                "working-directory: trusted-update-signature-tool-source",
                "test \"$(git rev-parse HEAD)\" = \"$TRUSTED_BASE_SHA\"",
                "mvn -B -ntp -pl pixivdownload-plugin-signature -am package -DskipTests -Dexec.skip=true",
                "TRUSTED_UPDATE_SIGNATURE_TOOL=$destination",
                "TRUSTED_UPDATE_SIGNATURE_TOOL_SHA256=$sha256",
                "Trusted update signature tool checksum mismatch.",
                "& java -cp $env:TRUSTED_UPDATE_SIGNATURE_TOOL",
                "UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64: ${{ inputs.update_signing_private_key_pem_base64 }}",
                "--repository-id pixivdownloader-update",
                "--key-id pixivdownloader-update-root-2026-08",
                "--out artifacts/update.json.sig",
                "chmod 600 -- $privateKeyFile",
                "} finally {",
                "Remove-Item -LiteralPath $privateKeyFile",
                "pixivdownloader-update-signing-key.pem")
                .doesNotContain(
                        "UPDATE_SIGNING_PRIVATE_KEY_PEM:",
                        "Download update signature tool",
                        "tools/update-signature",
                        "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64",
                        "PLUGIN_SIGNING_PRIVATE_KEY_PEM",
                        "--key-id pixivdownloader-official-root-2026-07");
        assertThat(signingAction.indexOf("name: Build trusted update signature tool"))
                .isGreaterThan(signingAction.indexOf("name: Checkout trusted update signature tool source"))
                .isLessThan(signingAction.indexOf("- name: Sign update manifest"));
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);
            String signingJob = workflowJob(workflow,
                    name.equals("release.yml") ? "release" : "release-nightly");
            assertThat(workflow).as(name).contains(
                    "channel: $channel",
                    "sequence: $sequence",
                    "expiresAt: $expiresAt",
                    "Sign update manifest",
                    "update_signing_private_key_pem_base64: ${{ secrets.UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64 }}",
                    "uses: ./.github/actions/sign-update-manifest",
                    "artifacts/update.json.sig");
            assertThat(workflowJob(workflow, "build-jar")).as(name + " candidate build job")
                    .doesNotContain("Upload update signature tool");
            assertThat(signingJob).as(name + " update signing job")
                    .contains(
                            "needs.publish-plugins.outputs.trusted_base_sha",
                            "update_signing_private_key_pem_base64: ${{ secrets.UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64 }}",
                            "uses: ./.github/actions/sign-update-manifest")
                    .doesNotContain(
                            "UPDATE_SIGNING_PRIVATE_KEY_PEM: ${{ secrets.UPDATE_SIGNING_PRIVATE_KEY_PEM }}",
                            "Download update signature tool",
                            "tools/update-signature",
                            "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64",
                            "PLUGIN_SIGNING_PRIVATE_KEY_PEM",
                            "--key-id pixivdownloader-official-root-2026-07");
            assertThat(signingJob.indexOf("name: Sign update manifest"))
                    .as(name + " signing action follows manifest generation")
                    .isGreaterThan(signingJob.indexOf("name: Generate update manifest"));
        }
        assertThat(workflow("publish-plugins.yml")).contains(
                "trusted_base_sha:",
                "value: ${{ jobs.trusted-base.outputs.sha }}");
        assertThat(workflow("release.yml")).contains(
                "--arg channel \"stable\"", "--argjson sequence \"$GITHUB_RUN_ID\"", "'+370 days'");
        assertThat(workflow("nightly.yml")).contains(
                "--arg channel \"nightly\"", "--argjson sequence \"$SEQUENCE\"", "'+14 days'");
    }

    @Test
    @DisplayName("Dockerfile 只从签名 default 分发布局复制 required 插件")
    void dockerfileCopiesSignedDefaultDistribution() throws Exception {
        String dockerfile = dockerfile();

        assertThat(dockerfile).contains(
                "ARG PIXIVDOWNLOADER_DISTRIBUTION=build/dist/default-downloader",
                "COPY --chown=10001:10001 ${PIXIVDOWNLOADER_DISTRIBUTION}/PixivDownload-*.jar app.jar",
                "COPY --chown=10001:10001 ${PIXIVDOWNLOADER_DISTRIBUTION}/plugins/ plugins/",
                "COPY --chown=10001:10001 ${PIXIVDOWNLOADER_DISTRIBUTION}/plugins-manifest.json plugins-manifest.json",
                "COPY --chown=10001:10001 ${PIXIVDOWNLOADER_DISTRIBUTION}/SHA256SUMS SHA256SUMS",
                "pixivdownload-plugin-download-workbench-*.jar",
                "test -f \"$required_plugin.sha256\"",
                "test -f \"$required_plugin.sig\"",
                "plugins/provenance/$(basename \"$required_plugin\").pixiv-plugin-provenance");
        assertThat(dockerfile).doesNotContain(
                "/build/pixivdownload-plugin-download-workbench/target",
                "COPY --from=builder /build/pixivdownload-plugin-download-workbench",
                "mvn -B -DskipTests package",
                "COPY . .");
    }

    @Test
    @DisplayName("Docker 默认使用非特权只读运行边界")
    void dockerDefaultsUseConstrainedRuntime() throws Exception {
        String dockerfile = dockerfile();
        String compose = dockerCompose();

        assertThat(dockerfile).contains(
                "groupadd --gid 10001 pixivdownloader",
                "useradd --uid 10001 --gid 10001",
                "USER 10001:10001");
        assertThat(compose).contains(
                "127.0.0.1:6999:6999",
                "cap_drop:",
                "- ALL",
                "no-new-privileges:true",
                "read_only: true",
                "pids_limit: 256",
                "mem_limit: 2g",
                "cpus: 2.0",
                "/tmp:size=256m,noexec,nosuid,nodev",
                "plugins:/app/plugins:rw",
                "./config:/app/config:rw",
                "./state:/app/state:rw",
                "./data:/app/data:rw",
                "./pixiv-download:/app/pixiv-download:rw",
                "./log:/app/log:rw");
        assertThat(compose).doesNotContain("- \"6999:6999\"", "privileged: true");
    }

    @Test
    @DisplayName("发布脚本不携带私钥材料，也不把私钥写入产物")
    void scriptsDoNotEmbedOrWritePrivateKeyMaterial() throws Exception {
        for (String name : List.of(
                "plugin-distribution-common.ps1",
                "publish-plugin-releases.ps1",
                "generate-market-manifest.ps1",
                "stage-official-plugin-inputs-from-catalog.ps1",
                "assemble-plugin-distribution.ps1",
                "package-java-distributions.ps1",
                "package-installer-with-plugins.ps1",
                "package-local.ps1",
                "package-local-plugin-staging.ps1",
                "package-local-installer-catalog.ps1")) {
            String script = script(name);
            assertThat(script).as(name).doesNotContain("-----BEGIN PRIVATE KEY-----");
            assertThat(script).as(name).doesNotContain("-----END PRIVATE KEY-----");
            assertThat(script).as(name).doesNotContain("Set-Content -Path $PrivateKeyFile");
            assertThat(script).as(name).doesNotContain("WriteAllText($PrivateKeyFile");
        }
    }

    @Test
    @DisplayName("调查资源按依赖顺序装配且 official-surveys profile 启用发布位")
    void surveyPublisherOwnsPostHogConfigurationAndOfficialProfileActivatesIt() throws Exception {
        assertThat(pluginDescriptor("pixivdownload-plugin-download-workbench"))
                .contains("plugin.dependencies=posthog?@1.0");
        assertThat(pluginDescriptor("pixivdownload-plugin-multi-mode-decision-survey"))
                .contains("plugin.dependencies=posthog@1.0,notification@1.0");
        assertThat(Files.readString(repoRoot().resolve("pixivdownload-plugin-download-workbench")
                .resolve("src/main/resources/static/pixiv-batch-alt.html"), StandardCharsets.UTF_8))
                .contains("/pixiv-posthog/pixiv-posthog.js")
                .contains("/pixiv-layout-feedback/release-activation.js")
                .contains("/pixiv-layout-feedback/posthog-config.js")
                .doesNotContain("/pixiv-layout-feedback/public-config.js");

        assertThat(workflow("publish-plugins.yml"))
                .doesNotContain("PIXIV_LAYOUT_SURVEY", "pixiv.layout-survey", "Repository Variables");
        assertThat(workflow("publish-plugins.yml"))
                .contains("github.repository == 'Sywyar/PixivDownloader'");
        String rootPom = Files.readString(repoRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(rootPom)
                .contains("<layout-survey.official-release-enabled>false</layout-survey.official-release-enabled>")
                .contains("<multi-mode-decision-survey.official-release-enabled>false</multi-mode-decision-survey.official-release-enabled>")
                .contains("<id>official-surveys</id>")
                .contains("<layout-survey.official-release-enabled>true</layout-survey.official-release-enabled>")
                .contains("<multi-mode-decision-survey.official-release-enabled>true</multi-mode-decision-survey.official-release-enabled>")
                .doesNotContain("<name>pixiv.layout-survey.require-config</name>");
        for (String name : List.of(
                "package-local.ps1",
                "package-installer-with-plugins.ps1",
                "plugin-distribution-common.ps1",
                "publish-plugin-releases.ps1")) {
            assertThat(script(name)).as(name)
                    .doesNotContain(
                            "LayoutSurveyPublicConfigFile",
                            "EnableLayoutSurvey",
                            "posthog.properties",
                            "generate-layout-survey-public-config");
        }
        assertThat(repoRoot().resolve("scripts/generate-layout-survey-public-config.ps1")).doesNotExist();
        assertThat(repoRoot().resolve("scripts/properties/posthog.properties")).doesNotExist();
        assertThat(repoRoot().resolve("scripts/properties/posthog.properties.example")).doesNotExist();
    }


    @Test
    @DisplayName("Nightly 更新清单序号按工作流运行号和重试次数严格递增")
    void nightlyManifestSequenceIncludesRunAttempt() throws Exception {
        Path sequenceScript = repoRoot().resolve("scripts").resolve("ci").resolve("nightly-manifest-sequence.sh");
        String nightly = workflow("nightly.yml");
        assumeTrue(canRun("bash", "--version"), "bash 不可用，跳过行为测试");

        assertThat(nightly)
                .contains(
                        "SEQUENCE=$(./scripts/ci/nightly-manifest-sequence.sh \"$GITHUB_RUN_NUMBER\" \"$GITHUB_RUN_ATTEMPT\")",
                        "--argjson sequence \"$SEQUENCE\"")
                .doesNotContain(
                        "nightly-manifest-sequence.sh \"$GITHUB_RUN_ID\"",
                        "--argjson sequence \"$GITHUB_RUN_ID\"");

        long firstAttempt = Long.parseLong(runBash(repoRoot(), sequenceScript, "9000000000", "1"));
        long secondAttempt = Long.parseLong(runBash(repoRoot(), sequenceScript, "9000000000", "2"));
        long lastSupportedAttempt = Long.parseLong(runBash(repoRoot(), sequenceScript, "9000000000", "999"));
        long nextRun = Long.parseLong(runBash(repoRoot(), sequenceScript, "9000000001", "1"));
        assertThat(secondAttempt).isGreaterThan(firstAttempt);
        assertThat(nextRun).isGreaterThan(lastSupportedAttempt);
        assertThat(firstAttempt).isGreaterThan(9_000_000_000L);

        Process invalidAttempt = new ProcessBuilder(
                "bash", toBashPath(sequenceScript), "9000000000", "1000")
                .directory(repoRoot().toFile()).redirectErrorStream(true).start();
        String invalidOutput = new String(invalidAttempt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(invalidAttempt.waitFor()).as(invalidOutput).isNotEqualTo(0);
        assertAsciiWithoutBom(sequenceScript);
    }

    @Test
    @DisplayName("Nightly 部分发布失败时不会暴露可验签的新清单")
    void nightlyPublishesSignedManifestAfterAllRequiredAssets() throws Exception {
        String nightly = workflow("nightly.yml");
        String releaseJob = workflowJob(nightly, "release-nightly");
        String releaseStep = releaseJob.substring(
                releaseJob.indexOf("name: Create/Update Nightly Release"),
                releaseJob.indexOf("name: Advance nightly tag after successful release"));

        assertThat(nightly).contains("workflow_dispatch:");
        assertThat(releaseJob)
                .contains(
                        "needs: [resolve-version, publish-plugins, publish-plugin-artifacts, build-jar, "
                                + "build-windows-installer, release-artifact-e2e]",
                        "if: needs.resolve-version.outputs.has_changes == 'true'")
                .doesNotContain("always()");
        assertThat(releaseStep).contains(
                "fail_on_unmatched_files: true",
                "preserve_order: true",
                "artifacts/*-setup.exe",
                "artifacts/java-distributions/*-java.zip",
                "artifacts/java-distributions/*-full-offline.zip",
                "artifacts/update.json",
                "artifacts/update.json.sig");
        assertThat(releaseStep.indexOf("artifacts/*-setup.exe"))
                .isLessThan(releaseStep.indexOf("artifacts/update.json"));
        assertThat(releaseStep.indexOf("artifacts/java-distributions/*-java.zip"))
                .isLessThan(releaseStep.indexOf("artifacts/update.json"));
        assertThat(releaseStep.indexOf("artifacts/java-distributions/*-full-offline.zip"))
                .isLessThan(releaseStep.indexOf("artifacts/update.json"));
        assertThat(releaseStep.indexOf("artifacts/update.json"))
                .isLessThan(releaseStep.indexOf("artifacts/update.json.sig"));
    }

    @Test
    @DisplayName("Nightly 门禁脚本行为矩阵：无 diff 跳过，新增/修改/纯删除都触发，Git 错误失败")
    void nightlyChangelogGateScriptBehaviorMatrix() throws Exception {
        Path script = repoRoot().resolve("scripts").resolve("nightly-changelog-gate.sh");
        assumeTrue(canRun("bash", "--version"), "bash 不可用，跳过行为测试");
        assumeTrue(canRun("git", "--version"), "git 不可用，跳过行为测试");
        assertAsciiWithoutBom(script);

        Path repo = Files.createTempDirectory("nightly-gate-");
        try {
            initGitRepo(repo);
            Path changelog = repo.resolve("CHANGELOG.md");
            String baseline = "# Changelog\n\n## [Unreleased]\n\n### Features\n"
                    + "- entry one\n- entry two\n- entry three\n";
            Files.writeString(changelog, baseline, StandardCharsets.UTF_8);
            commitAll(repo, "baseline");
            runGit(repo, "tag", "nightly");

            // 1. 无 diff：false
            assertThat(runGate(repo, script, "nightly")).isEqualTo("false");

            // 2. 新增一行：true
            Files.writeString(changelog, baseline + "- entry four\n", StandardCharsets.UTF_8);
            assertThat(runGate(repo, script, "nightly")).isEqualTo("true");

            // 3. 修改一行：true
            Files.writeString(changelog, baseline.replace("- entry two\n", "- entry two (changed)\n"),
                    StandardCharsets.UTF_8);
            assertThat(runGate(repo, script, "nightly")).isEqualTo("true");

            // 4. 只删除一行：true（旧实现按新增行判定会误报 false）
            Files.writeString(changelog, baseline.replace("- entry two\n", ""), StandardCharsets.UTF_8);
            assertThat(runGate(repo, script, "nightly")).isEqualTo("true");

            // 5. git diff 真实错误（损坏的 index 使退出码为 128）：
            //    步骤失败，绝不输出 has_changes 判定。GIT_INDEX_FILE 在 bash
            //    （WSL）一侧设置，避免 Windows 环境变量透传差异。
            Path garbageIndex = repo.resolve("garbage-index");
            Files.write(garbageIndex, new byte[] { 1, 2, 3, 4, 5 });
            String command = "cd " + toBashPath(repo) + " && GIT_INDEX_FILE=" + toBashPath(garbageIndex)
                    + " " + toBashPath(script) + " CHANGELOG.md nightly";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(repo.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(code).as("git 错误必须使步骤失败: %s", err).isNotEqualTo(0);
            assertThat(out.trim()).as("git 错误不得输出 has_changes 判定").isEmpty();
        } finally {
            deleteRecursively(repo);
        }
    }

    @Test
    @DisplayName("首次 Nightly（无 nightly 标签）由 [Unreleased] 非空内容判定，纯空白不算变更")
    void nightlyGateFirstRunUsesUnreleasedSection() throws Exception {
        Path script = repoRoot().resolve("scripts").resolve("nightly-changelog-gate.sh");
        assumeTrue(canRun("bash", "--version"), "bash 不可用，跳过行为测试");
        assumeTrue(canRun("git", "--version"), "git 不可用，跳过行为测试");

        Path repo = Files.createTempDirectory("nightly-gate-first-");
        try {
            initGitRepo(repo);
            Path changelog = repo.resolve("CHANGELOG.md");

            // 空 [Unreleased]（只含空白行）→ false，不会永久跳过逻辑不影响首次构建判定
            Files.writeString(changelog,
                    "# Changelog\n\n## [Unreleased]\n\n\n## [v1.0.0] - 2026-01-01\n- released\n",
                    StandardCharsets.UTF_8);
            assertThat(runGate(repo, script, "nightly")).isEqualTo("false");

            // 非空 → true（nightly 参数传了但标签不存在，走 rev-parse 回退）
            Files.writeString(changelog,
                    "# Changelog\n\n## [Unreleased]\n\n### Features\n- upcoming\n",
                    StandardCharsets.UTF_8);
            assertThat(runGate(repo, script, "nightly")).isEqualTo("true");
        } finally {
            deleteRecursively(repo);
        }
    }

    @Test
    @DisplayName("Nightly 版本解析考虑正式版与 Beta 标签并忽略无效标签")
    void nightlyVersionResolutionHandlesStableAndBetaTags() throws Exception {
        String nightly = workflow("nightly.yml");
        assumeTrue(canRun("bash", "--version"), "bash 不可用，跳过行为测试");
        assumeTrue(canRun("git", "--version"), "git 不可用，跳过行为测试");

        assertThat(nightly).contains(
                "set -euo pipefail",
                "while IFS= read -r tag",
                "LATEST_TAG=\"$tag\"",
                "done < <(git tag --sort=-v:refname --list 'v*')",
                "PATCH=\"${PATCH%%-*}\"",
                "git rev-parse --verify --quiet \"refs/tags/v${MAJOR}.${MINOR}.${PATCH}\"",
                "NEXT_PATCH=$((PATCH + 1))",
                "NEXT_VERSION=\"${MAJOR}.${MINOR}.${NEXT_PATCH}\"");

        Path repo = Files.createTempDirectory("nightly-version-");
        try {
            initGitRepo(repo);
            Files.writeString(repo.resolve("marker"), "nightly\n", StandardCharsets.UTF_8);
            commitAll(repo, "baseline");
            String resolveScript = nightly.substring(
                    nightly.indexOf("          set -euo pipefail", nightly.indexOf("Resolve next version")),
                    nightly.indexOf("          NIGHTLY_VERSION=", nightly.indexOf("Resolve next version")))
                    .replaceAll("(?m)^ {10}", "");
            Path script = repo.resolve("resolve-nightly-version.sh");
            Files.writeString(script, resolveScript + "printf '%s\\n' \"$NEXT_VERSION\"\n", StandardCharsets.UTF_8);

            assertThat(runBash(repo, script)).isEqualTo("0.0.1");
            runGit(repo, "tag", "v1.13.1");
            assertThat(runBash(repo, script)).isEqualTo("1.13.2");
            runGit(repo, "tag", "v1.14.0-beta.1");
            assertThat(runBash(repo, script)).isEqualTo("1.14.0");
            runGit(repo, "tag", "v1.14.0");
            assertThat(runBash(repo, script)).isEqualTo("1.14.1");
            runGit(repo, "tag", "v1.15.0-beta.2");
            assertThat(runBash(repo, script)).isEqualTo("1.15.0");
            runGit(repo, "tag", "v999999999.0.0-beta.0");
            runGit(repo, "tag", "v9999999999.0.0");
            assertThat(runBash(repo, script)).isEqualTo("1.15.0");
        } finally {
            deleteRecursively(repo);
        }
    }

    @Test
    @DisplayName("Nightly 标签只在完整发布成功后推进：清理 < 发布 < 标签，且标签步骤不吞错")
    void nightlyAdvancesTagOnlyAfterSuccessfulRelease() throws Exception {
        String nightly = workflow("nightly.yml");
        String releaseJob = workflowJob(nightly, "release-nightly");

        int deleteIndex = releaseJob.indexOf("name: Delete old nightly assets");
        int releaseIndex = releaseJob.indexOf("name: Create/Update Nightly Release");
        int advanceIndex = releaseJob.indexOf("name: Advance nightly tag after successful release");

        assertThat(deleteIndex).as("清理步骤必须存在").isGreaterThanOrEqualTo(0);
        assertThat(releaseIndex).as("发布步骤必须在清理之后").isGreaterThan(deleteIndex);
        assertThat(advanceIndex).as("标签步骤必须在发布之后").isGreaterThan(releaseIndex);

        // Release action 之前不存在任何更新 nightly 标签的步骤，旧步骤名已删除。
        assertThat(releaseJob.substring(0, releaseIndex)).doesNotContain("git tag -f nightly");
        assertThat(releaseJob).doesNotContain("name: Update nightly tag");
        // 标签指向当前提交 SHA，失败不被吞掉。
        String advanceBlock = releaseJob.substring(advanceIndex);
        assertThat(advanceBlock).contains(
                "if: ${{ success() }}",
                "git tag -f nightly \"$GITHUB_SHA\"",
                "git push -f origin refs/tags/nightly")
                .doesNotContain("|| true", "||true");
        // 标签推进必须是 release-nightly job 的最后一步。
        assertThat(releaseJob.substring(advanceIndex + 1)).doesNotContain("- name:");
    }

    @Test
    @DisplayName("共享路径安全函数覆盖仓库根/祖先/源码目录/build 根与 protected paths 重叠")
    void sharedDistributionPathSafetyContract() throws Exception {
        String common = script("plugin-distribution-common.ps1");

        assertThat(common).contains(
                "function Get-NormalizedDistributionPath",
                "function Test-PathSameOrDescendant",
                "function Assert-SafeDistributionOutputDirectory",
                "[System.IO.Path]::GetFullPath",
                "[System.StringComparison]::OrdinalIgnoreCase",
                "Refusing to use a drive/filesystem root as the output dir",
                "Refusing to use the repository root as the output dir",
                "Refusing to use an ancestor of the repository root as the output dir",
                "Refusing to use the build root as the output dir",
                "Refusing to use a repository source directory as the output dir",
                "only <repository>/build/<subdirectory> is allowed",
                "Output dir overlaps a protected path",
                "Output dir contains protected input",
                "Output dir is inside protected input");
        // 父子判断必须带显式分隔符边界，不得用裸前缀 Contains。
        assertThat(common).contains(
                "StartsWith($normalizedParent + $sep",
                "StartsWith($normalizedParent + $alt");
        assertThat(common).doesNotContain(".Contains($normalizedParent");
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("plugin-distribution-common.ps1"));
    }

    @Test
    @DisplayName("两个分发脚本先经共享安全断言再删除 OutputDir，且不再保留弱化版本地守卫")
    void distributionScriptsAssertSafetyBeforeRemovingOutputDir() throws Exception {
        for (String name : List.of("assemble-plugin-distribution.ps1", "package-java-distributions.ps1")) {
            String s = script(name);
            int lastAssert = s.lastIndexOf("Assert-SafeDistributionOutputDirectory");
            assertThat(lastAssert).as(name + " 必须调用共享安全断言").isGreaterThanOrEqualTo(0);
            assertThat(s).as(name).doesNotContain("Assert-SafeRemovableDir");
            // 任何 OutputDir 递归删除都必须位于最后一次安全断言之后。
            Matcher remove = Pattern.compile("Remove-Item[^\r\n]*OutputDir[^\r\n]*").matcher(s);
            boolean foundDelete = false;
            while (remove.find()) {
                foundDelete = true;
                assertThat(remove.start()).as(name + " 存在未受共享断言保护的 OutputDir 删除").isGreaterThan(lastAssert);
            }
            assertThat(foundDelete).as(name + " 必须存在 OutputDir 递归删除").isTrue();
        }

        String assemble = script("assemble-plugin-distribution.ps1");
        String orchestrator = script("package-java-distributions.ps1");
        // assemble：两阶段保护，stage 2 必须包含最终 SelectedAppJar，私有钥（存在时）一并保护。
        assertThat(assemble).contains(
                "$ProtectedInputPaths += $SelectedAppJar",
                "$ProtectedInputPaths += $ResolvedPrebuiltPluginsDir",
                "$ProtectedInputPaths += $SignatureToolJar",
                "$privateKeyItem = Get-Item -LiteralPath $PrivateKeyFile",
                "-ProtectedPaths $ProtectedInputPaths");
        assertThat(assemble.lastIndexOf("$ProtectedInputPaths += $SelectedAppJar"))
                .as("stage 2 必须位于 Push-Location 之后的最终 SelectedAppJar 确定处")
                .isGreaterThan(assemble.indexOf("Push-Location $ProjectRoot"));
        assertThat(assemble.indexOf("$SelectedAppJar = $appJarCandidate.FullName"))
                .as("本地 fallback 必须在 stage 1 保护之后确定 SelectedAppJar")
                .isGreaterThan(assemble.indexOf("Assert-SafeDistributionOutputDirectory"));
        // package-java：protected paths 覆盖全部输入。
        assertThat(orchestrator).contains(
                "$ResolvedPrebuiltJar,",
                "$ResolvedPrebuiltPluginsDir,",
                "$ResolvedSignatureToolJar,",
                "$AssemblerScript,",
                "$PSScriptRoot,",
                "$ProjectRoot");
    }

    private static void initGitRepo(Path repo) throws Exception {
        runGit(repo, "init", "-q", "-b", "main");
        runGit(repo, "config", "user.name", "gate-test");
        runGit(repo, "config", "user.email", "gate@test");
        runGit(repo, "config", "core.autocrlf", "false");
    }

    private static void commitAll(Path repo, String message) throws Exception {
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-q", "-m", message);
    }

    private static void runGit(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertThat(code).as("git %s failed: %s", String.join(" ", args), output).isEqualTo(0);
    }

    private static String runGate(Path repo, Path script, String nightlyRef) throws Exception {
        Process p = new ProcessBuilder("bash", toBashPath(script), "CHANGELOG.md", nightlyRef)
                .directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertThat(code).as("gate script failed: %s", output).isEqualTo(0);
        return output.trim();
    }

    private static String runBash(Path repo, Path script, String... args) throws Exception {
        String[] command = new String[args.length + 2];
        command[0] = "bash";
        command[1] = toBashPath(script);
        System.arraycopy(args, 0, command, 2, args.length);
        Process p = new ProcessBuilder(command)
                .directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertThat(code).as("bash script failed: %s", output).isEqualTo(0);
        return output.trim();
    }

    private static String toBashPath(Path path) {
        // On Windows, `bash` resolves to the WSL bash where Windows paths must
        // be written as /mnt/<drive>/...; on Linux the path is used as-is.
        String p = path.toString();
        if (p.length() >= 2 && p.charAt(1) == ':') {
            return "/mnt/" + Character.toLowerCase(p.charAt(0)) + "/" + p.substring(2).replace('\\', '/');
        }
        return p.replace('\\', '/');
    }

    private static boolean canRun(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // Best-effort cleanup of the temp repo.
                }
            });
        }
    }

    private static String script(String name) throws IOException {
        return Files.readString(repoRoot().resolve("scripts").resolve(name), StandardCharsets.UTF_8);
    }

    private static String innoScript() throws IOException {
        Path source = repoRoot().resolve("packaging/windows/inno/PixivDownload.iss");
        return assembleInnoSource(source, new LinkedHashSet<>());
    }

    private static String assembleInnoSource(Path source, Set<Path> activeIncludes) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (!activeIncludes.add(normalized)) {
            throw new IllegalStateException("Inno include cycle: " + normalized);
        }
        try {
            String text = Files.readString(normalized, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("(?m)^#include\\s+\"([^\"]+)\"\\s*\\r?$").matcher(text);
            StringBuilder assembled = new StringBuilder(text.length());
            int cursor = 0;
            while (matcher.find()) {
                assembled.append(text, cursor, matcher.start());
                Path include = normalized.getParent()
                        .resolve(matcher.group(1).replace('\\', '/'))
                        .normalize();
                if (Files.isRegularFile(include)) {
                    assembled.append(assembleInnoSource(include, activeIncludes));
                } else {
                    assembled.append(matcher.group());
                }
                cursor = matcher.end();
            }
            return assembled.append(text, cursor, text.length()).toString();
        } finally {
            activeIncludes.remove(normalized);
        }
    }

    private static String innoSupportScript(String name) throws IOException {
        return Files.readString(repoRoot().resolve("packaging").resolve("windows").resolve("inno")
                .resolve(name), StandardCharsets.UTF_8);
    }

    private static String workflow(String name) throws IOException {
        return Files.readString(repoRoot().resolve(".github").resolve("workflows").resolve(name),
                StandardCharsets.UTF_8);
    }

    private static String action(String name) throws IOException {
        return Files.readString(repoRoot().resolve(".github").resolve("actions").resolve(name).resolve("action.yml"),
                StandardCharsets.UTF_8);
    }

    private static String workflowJob(String workflow, String jobName) {
        Matcher matcher = Pattern.compile("(?m)^  ([A-Za-z0-9_-]+):[ \\t]*\\r?$").matcher(workflow);
        int start = -1;
        while (matcher.find()) {
            if (start >= 0) {
                return workflow.substring(start, matcher.start());
            }
            if (jobName.equals(matcher.group(1))) {
                start = matcher.start();
            }
        }
        if (start >= 0) {
            return workflow.substring(start);
        }
        throw new IllegalArgumentException("Missing workflow job: " + jobName);
    }

    private static String dockerfile() throws IOException {
        return Files.readString(repoRoot().resolve("Dockerfile"), StandardCharsets.UTF_8);
    }

    private static String dockerCompose() throws IOException {
        return Files.readString(repoRoot().resolve("docker-compose.yml"), StandardCharsets.UTF_8);
    }

    private static String pluginDescriptor(String module) throws IOException {
        return Files.readString(repoRoot().resolve(module).resolve("src/main/resources/plugin.properties"),
                StandardCharsets.UTF_8);
    }

    private static void assertAsciiWithoutBom(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        boolean hasUtf8Bom = bytes.length >= 3
                && Byte.toUnsignedInt(bytes[0]) == 0xEF
                && Byte.toUnsignedInt(bytes[1]) == 0xBB
                && Byte.toUnsignedInt(bytes[2]) == 0xBF;
        assertThat(hasUtf8Bom).as("%s must not start with a UTF-8 BOM", path).isFalse();
        for (int index = 0; index < bytes.length; index++) {
            assertThat(Byte.toUnsignedInt(bytes[index]))
                    .as("%s byte %s must be ASCII", path, index)
                    .isLessThanOrEqualTo(0x7F);
        }
    }

    private static void assertCanonicalBase64Key(String value) {
        assertThat(value).isNotBlank();
        byte[] decoded = Base64.getDecoder().decode(value);
        assertThat(decoded).hasSize(32);
        assertThat(Base64.getEncoder().encodeToString(decoded)).isEqualTo(value);
    }

    private static List<OfficialPlugin> officialDistributionPlugins() throws Exception {
        return officialDistributionPlugins("Get-OfficialDistributionPlugins -IncludeOptional");
    }

    private static List<OfficialPlugin> officialDistributionPlugins(String functionName) throws Exception {
        JsonNode values = new ObjectMapper().readTree(runPowerShell(
                "$ErrorActionPreference='Stop'; "
                        + ". './scripts/plugin-distribution-common.ps1'; "
                        + "$items=@(" + functionName + "); "
                        + "ConvertTo-Json -InputObject $items -Depth 4 -Compress"));
        List<OfficialPlugin> plugins = new ArrayList<>();
        for (JsonNode value : values) {
            plugins.add(new OfficialPlugin(value.path("Id").asText(), value.path("Module").asText()));
        }
        assertThat(plugins).as("official plugins").isNotEmpty();
        assertThat(plugins).allSatisfy(plugin -> {
            assertThat(plugin.id()).isNotBlank();
            assertThat(plugin.module()).isNotBlank();
        });
        return plugins;
    }

    private static Set<String> officialPluginIds(String functionName) throws Exception {
        Set<String> ids = officialDistributionPlugins(functionName).stream()
                .map(OfficialPlugin::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(ids).as("official plugin ids").isNotEmpty();
        return ids;
    }

    private static Set<String> officialPluginIds() throws Exception {
        return officialPluginIds("Get-OfficialDistributionPlugins -IncludeOptional");
    }

    private static String runPowerShell(String command) throws Exception {
        CommandResult result = runPowerShellResult(command);
        assertThat(result.exitCode()).as("PowerShell failed: %s", result.output()).isEqualTo(0);
        return result.output();
    }

    private static CommandResult runPowerShellResult(String command) throws Exception {
        String executable = canRun("pwsh", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major")
                ? "pwsh" : "powershell";
        Process process = new ProcessBuilder(executable, "-NoProfile", "-Command", command)
                .directory(repoRoot().toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new CommandResult(process.waitFor(), output);
    }

    private static String psQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private static void writeThinPluginJar(Path path, String id, String version, String requires)
            throws IOException {
        writeThinPluginJar(path, id, version, requires, true);
    }

    private static void writeThinPluginJar(
            Path path, String id, String version, String requires, boolean includeProguardMetadata)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write(("plugin.id=" + id + "\n"
                    + "plugin.version=" + version + "\n"
                    + "plugin.requires=" + requires + "\n"
                    + "pixiv.execution-mode=host-process-full-trust\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (includeProguardMetadata) {
                zip.putNextEntry(new ZipEntry("META-INF/pixivdownload-proguard.properties"));
                zip.write(("formatVersion=1\n"
                        + "proguard.version=7.10.0\n"
                        + "shrink=true\n"
                        + "optimize=true\n"
                        + "obfuscate=false\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static Map<String, String> readProperties(Path path) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') {
                trimmed = trimmed.substring(1).trim();
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx < 1) {
                continue;
            }
            props.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
        }
        return props;
    }

    private static void assertDescriptorField(Map<String, String> descriptor, String pluginId, String field) {
        assertThat(descriptor.get(field)).as("%s %s must not be blank", pluginId, field).isNotBlank();
    }

    private static void assertI18nKey(String module, String namespace, String key) throws IOException {
        Path bundle = repoRoot().resolve(module).resolve("src/main/resources/i18n/web")
                .resolve(namespace + ".properties");
        Path enBundle = repoRoot().resolve(module).resolve("src/main/resources/i18n/web")
                .resolve(namespace + "_en.properties");
        assertThat(readProperties(bundle).get(key)).as("%s must contain %s", bundle, key).isNotBlank();
        assertThat(readProperties(enBundle).get(key)).as("%s must contain %s", enBundle, key).isNotBlank();
    }

    private static void assertI18nValue(String module, String namespace, String key, String zh, String en)
            throws IOException {
        Path bundle = repoRoot().resolve(module).resolve("src/main/resources/i18n/web")
                .resolve(namespace + ".properties");
        Path enBundle = repoRoot().resolve(module).resolve("src/main/resources/i18n/web")
                .resolve(namespace + "_en.properties");
        assertThat(readProperties(bundle).get(key)).as("%s %s", module, key).isEqualTo(zh);
        assertThat(readProperties(enBundle).get(key)).as("%s %s", module, key).isEqualTo(en);
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

    private record OfficialPlugin(String id, String module) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
