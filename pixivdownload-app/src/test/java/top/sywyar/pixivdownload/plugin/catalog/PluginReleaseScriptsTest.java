package top.sywyar.pixivdownload.plugin.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        assertThat(script).doesNotContain(
                "[switch]$Force",
                "Remove-ExistingReleaseAssets",
                "gh release delete-asset",
                "(forced)");
        assertThat(script).doesNotContain("$assetName = \"$($plugin.Module)-$version.jar\"");
        assertThat(script).doesNotContain("Assert-ThinPluginJar $builtJar");
        assertThat(script).doesNotContain("Build-StagedPluginJar");
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
                "Id = \"douyin\"",
                "Module = \"pixivdownload-plugin-douyin\"",
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
                "function Get-PluginArtifactSignatureForDistribution",
                "Assert-JarWithPrivatePluginLibs",
                "Assert-ThinPluginJar",
                "^flatlaf-[0-9].*\\.jar$",
                "^jna-[0-9].*\\.jar$",
                "Plugin jar is not thin - found private lib/*.jar entries");
        assertThat(common).doesNotContain("Format = \"zip\"");
        assertThat(common).doesNotContain("Assert-ExplodedPluginZip");
    }

    @Test
    @DisplayName("市场清单身份字段从官方 descriptor/i18n 派生，curation 只保留市场专属字段")
    void marketIdentityMetadataIsCanonicalDescriptorDerived() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String generator = script("generate-market-manifest.ps1");
        JsonNode curation = new ObjectMapper().readTree(repoRoot().resolve("scripts").resolve("market-curation.json").toFile());
        List<OfficialPlugin> officialPlugins = officialDistributionPlugins(common);
        Set<String> officialPluginIds = officialDistributionPluginIds(common);

        assertThat(officialPluginIds).contains("download-workbench");
        assertThat(officialPluginIds).contains("notification");
        assertThat(officialPluginIds).contains("douyin");
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
    @DisplayName("市场策展将通知基础插件归为依赖、Douyin 归为下载类型扩展")
    void marketCurationClassifiesDependencyAndDownloadTypeExtension() throws Exception {
        JsonNode curation = new ObjectMapper().readTree(
                repoRoot().resolve("scripts").resolve("market-curation.json").toFile());

        assertThat(curation.path("notification").path("category").asText()).isEqualTo("dependency");
        assertThat(curation.path("douyin").path("category").asText()).isEqualTo("download-type");
    }

    @Test
    @DisplayName("默认安装集合包含除 Douyin 外的全部用户插件，optional 集合仅保留 Douyin")
    void distributionSeparatesDefaultInstalledAndOnDemandPlugins() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        Matcher defaultInstalled = Pattern.compile(
                "function Get-OfficialDefaultInstalledPlugins(?<body>.*?)function Get-OfficialOptionalPlugins",
                Pattern.DOTALL).matcher(common);
        assertThat(defaultInstalled.find()).isTrue();
        assertThat(defaultInstalled.group("body")).contains(
                "Get-OfficialRequiredPlugins",
                "Id = \"gui-theme\"", "Id = \"stats\"", "Id = \"duplicate\"",
                "Id = \"gallery\"", "Id = \"novel\"", "Id = \"notification\"",
                "Id = \"push\"", "Id = \"mail\"", "Id = \"tts\"", "Id = \"ai\"")
                .doesNotContain("Id = \"douyin\"", "Id = \"recovery-sentinel\"");

        Matcher optional = Pattern.compile(
                "function Get-OfficialOptionalPlugins(?<body>.*?)function Get-OfficialDistributionPlugins",
                Pattern.DOTALL).matcher(common);
        assertThat(optional.find()).isTrue();
        assertThat(optional.group("body")).contains("Id = \"douyin\"")
                .doesNotContain("Id = \"stats\"", "Id = \"gallery\"");
        assertThat(common).contains("$plugins = @(Get-OfficialDefaultInstalledPlugins)");
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
    @DisplayName("离线分发与 Windows 打包脚本同时携带 artifact 签名和 provenance sidecar")
    void offlinePackagingScriptsCarrySignatureAndProvenanceSidecar() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String distribution = script("assemble-plugin-distribution.ps1");
        String windows = script("package-local.ps1");
        String catalogStage = script("stage-official-plugin-inputs-from-catalog.ps1");
        String inno = innoScript();
        String installerInstall = innoSupportScript("installer-plugin-install.ps1");

        assertThat(common).contains(
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
        for (String script : List.of(distribution, windows)) {
            assertThat(script).contains(
                    "Find-PluginArtifactSignatureSidecar",
                    "Get-PluginArtifactSignatureForDistribution",
                    "Write-PluginProvenanceSidecar",
                    "Assert-NoPrivateKeyMaterial",
                    "signature = $signature"
            );
        }
        assertThat(distribution).contains(
                "[string]$PrebuiltPluginsDir",
                "Find-PrebuiltPluginArtifact",
                "[switch]$DefaultDownloader",
                "Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader)",
                "CoreShellOnly and DefaultDownloader cannot be combined.");
        assertThat(windows).contains(
                "$OfficialPluginCatalogUrl = \"https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json\"",
                "$InstallerPluginApiVersion = \"1.0.0\"",
                "$EnableInstallerPluginSelection = $false",
                "Get-OfficialDefaultInstalledPlugins",
                "Stage-InstallerPluginCatalogSnapshot",
                "Write-InstallerPluginCatalogProjection",
                "Write-InstallerPluginCatalogInclude",
                "Escape-InstallerCatalogIssString",
                "$InstallerCatalogIncludePath = Join-Path $BuildRoot \"installer-plugin-catalog-items.iss.inc\"",
                "[AllowEmptyString()][string]$Fallback",
                "\"verify-manifest\"",
                "installer-catalog",
                "catalog.en.txt",
                "catalog.zh-CN.txt",
                "installer-plugin-catalog-items.iss.inc",
                "Get-InstallerCatalogProp $market \"defaultInstalled\"",
                "if ($EnableInstallerPluginSelection)",
                "[switch]$AllowUnsignedLocalPlugins",
                "Write-UnsignedLocalPluginProvenanceSidecar",
                "LOCAL-UNSIGNED-BUILD.txt",
                "AllowUnsignedLocalPlugins only accepts plugin artifacts built from the current source tree",
                "AllowUnsignedLocalPlugins requires SkipPortable and SkipOfflinePortable",
                "AllowUnsignedLocalPlugins is only for building a local test installer",
                "out-local-unsigned",
                "$AppName-$Version-LOCAL-UNSIGNED-win-x64-setup.exe",
                "Move-Item -LiteralPath $SetupPath -Destination $LocalUnsignedSetupPath -Force",
                "$installerPluginCatalogEnabled = if ((-not $SkipPlugins) -and $EnableInstallerPluginSelection) { \"1\" } else { \"0\" }",
                "/DInstallerPluginCatalogEnabled=$installerPluginCatalogEnabled",
                "/DSignatureToolJar=$SignatureToolJar");
        assertThat(catalogStage).contains(
                "[string]$CoreApiVersion = \"1.0.0\"",
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
                "#define PluginApiVersion \"1.0.0\"",
                "#define InstallerPluginCatalogEnabled \"0\"",
                "#error SignatureToolJar must be defined when InstallerPluginCatalogEnabled is 1.",
                "#if InstallerPluginCatalogEnabled == \"1\"",
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
        Matcher curPageChanged = Pattern.compile("procedure CurPageChanged\\(CurPageID: Integer\\);(?<body>.*?)function OnFfmpegDownloadProgress",
                Pattern.DOTALL).matcher(inno);
        assertThat(curPageChanged.find()).isTrue();
        assertThat(curPageChanged.group("body")).contains("LoadPackagedInstallerPluginCatalog");
        assertThat(curPageChanged.group("body")).doesNotContain(
                "CurPageID = OptionalFeaturesPage.ID",
                "StartPluginCatalogTimer",
                "FinishInstallerPluginCatalogLoad",
                "ExtractPluginInstallerSupportFiles");
        assertThat(installerInstall).contains(
                "[string]$CoreApiVersion = \"1.0.0\"",
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
        assertAsciiWithoutBom(repoRoot().resolve("packaging").resolve("windows").resolve("inno")
                .resolve("installer-plugin-install.ps1"));
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
                "[string]$CoreApiVersion = \"1.0.0\"",
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
    @DisplayName("GitHub Actions 发布流从 Secrets 注入私钥并提交 manifest detached 签名")
    void publishWorkflowInjectsSigningSecretAndPublishesManifestSignature() throws Exception {
        String workflow = workflow("publish-plugins.yml");

        assertThat(workflow).contains(
                "workflow_call:",
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
                ".\\scripts\\publish-plugin-releases.ps1 @publishArgs",
                "-OfficialKeyId $env:PLUGIN_SIGNING_KEY_ID",
                "-PrivateKeyFile $env:PLUGIN_SIGNING_PRIVATE_KEY_FILE",
                "Copy-Item build/manifest.json.sig plugins-repo/manifest.json.sig -Force",
                "git add manifest.json manifest.json.sig",
                "Cleanup plugin signing private key");
        assertThat(workflow).doesNotContain("tags:");
        assertThat(workflow).doesNotContain("schedule:");
        assertThat(workflow).doesNotContain("publish_args", "PUBLISH_PLUGIN_ARGS", "[\"Force\"]");
        assertThat(workflow).doesNotContain("-----BEGIN PRIVATE KEY-----");
        assertThat(workflow).doesNotContain("\"-Repo\", $env:PLUGINS_REPO");
        assertThat(workflow).doesNotContain("\"-PrivateKeyFile\", $env:PLUGIN_SIGNING_PRIVATE_KEY_FILE");
    }

    @Test
    @DisplayName("质量门禁以同一提交运行 Java、签名泄露守卫与 JavaScript 测试")
    void qualityGateRunsJavaSignatureGuardAndJavaScriptTestsForTheSameCommit() throws Exception {
        String workflow = workflow("quality-gate.yml");
        JsonNode packageJson = new ObjectMapper().readTree(repoRoot().resolve("package.json").toFile());

        assertThat(workflow).contains(
                "push:",
                "branches: [master]",
                "pull_request:",
                "merge_group:",
                "workflow_call:",
                "ref: ${{ github.sha }}",
                "mvn -B -ntp -pl pixivdownload-official-plugins -am compile -Dexec.skip=true",
                "mvn -B -ntp test -Dexec.skip=true",
                "signature-guard:",
                "run: bash scripts/hooks/pre-push-guard.sh",
                "uses: actions/setup-node@v4",
                "node-version: '24'",
                "run: npm run test:js",
                "run: npm run test:web-standards");
        assertThat(workflow.split(Pattern.quote("ref: ${{ github.sha }}"), -1)).hasSize(4);
        assertThat(workflow).doesNotContain("-DskipTests", "-Dmaven.test.skip");
        assertThat(workflow.indexOf("run: npm run test:web-standards"))
                .isGreaterThan(workflow.indexOf("run: npm run test:js"));
        assertThat(packageJson.path("private").asBoolean()).isTrue();
        assertThat(packageJson.path("scripts").path("test:js").asText())
                .isEqualTo("node --test \"pixivdownload-*/src/test/js/*.test.js\" "
                        + "\"plugin-templates/*/src/test/js/*.test.js\"");
        assertThat(packageJson.path("scripts").path("test:web-standards").asText())
                .isEqualTo("node scripts/check-web-standards.mjs");
        assertThat(packageJson.has("dependencies")).isFalse();
        assertThat(packageJson.has("devDependencies")).isFalse();
    }

    @Test
    @DisplayName("发布与 nightly 在外部写入前依赖同一提交的质量门禁")
    void releaseWorkflowsRequireQualityGateBeforePublishing() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "publish-plugins:",
                    "uses: ./.github/workflows/publish-plugins.yml",
                    "needs:",
                    "publish-plugins",
                    "ref: ${{ github.sha }}",
                    "Verify packaged distribution boundaries",
                    "-Dtest=DistributionPackagingBoundaryTest",
                    "-Dsurefire.failIfNoSpecifiedTests=false",
                    "-Ddistribution.packaging.require-artifacts=true",
                    "*DistributionPackagingBoundaryTest.txt",
                    "Failures: 0, Errors: 0, Skipped: 0");
        }

        String release = workflow("release.yml");
        assertThat(release).contains(
                "draft-quality-gate:",
                "uses: ./.github/workflows/quality-gate.yml",
                "create-draft-release:",
                "needs: draft-quality-gate",
                "Verify draft tag targets the tested commit",
                "test \"$TAG_COMMIT\" = \"$GITHUB_SHA\"",
                "target_commitish: ${{ github.sha }}");
        String publish = workflow("publish-plugins.yml");
        assertThat(publish).contains(
                "quality-gate:",
                "uses: ./.github/workflows/quality-gate.yml",
                "publish:",
                "needs: quality-gate",
                "needs.quality-gate.result == 'success'");
        assertThat(release).doesNotContain("quality_gate_passed");
        assertThat(workflow("nightly.yml")).doesNotContain("quality_gate_passed");
    }

    @Test
    @DisplayName("release/nightly 仅在 build-jar 注入生产凭证密钥并于使用后清理临时过滤文件")
    void releaseWorkflowsIsolateProductionCredentialKeyToBuildJar() throws Exception {
        String secretName = "PIXIVDOWNLOAD_PLUGIN_CREDENTIAL_MASTER_KEY_BASE64";
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);
            String buildJarJob = workflowJob(workflow, "build-jar");
            String outsideBuildJarJob = workflow.replace(buildJarJob, "");

            assertThat(buildJarJob).as(name).contains(
                    "Prepare production plugin credential key filter",
                    secretName + ": ${{ secrets." + secretName + " }}",
                    "$env:RUNNER_TEMP",
                    "./scripts/write-plugin-credential-key-filter.ps1 -OutputPath $filterPath",
                    "PLUGIN_CREDENTIAL_FILTER=$filterPath",
                    "\"-Dplugin.credential.filter=$PLUGIN_CREDENTIAL_FILTER\"",
                    "-Ddistribution.packaging.require-production-credential-key=true",
                    "Remove production plugin credential key filter",
                    "if: always()",
                    "Remove-Item -LiteralPath $env:PLUGIN_CREDENTIAL_FILTER");
            assertThat(buildJarJob).as(name).doesNotContain(
                    "-Dplugin.credential.key.current-base64",
                    "-D" + secretName,
                    "echo ${{ secrets." + secretName);
            assertThat(outsideBuildJarJob).as(name + " non-build-jar jobs").doesNotContain(secretName);
            assertThat(workflow).as(name).doesNotContain("secrets: inherit");

            String publishJob = workflowJob(workflow, "publish-plugins");
            assertThat(publishJob).contains(
                    "PLUGINS_REPO_TOKEN: ${{ secrets.PLUGINS_REPO_TOKEN }}",
                    "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64: ${{ secrets.PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64 }}",
                    "PLUGIN_SIGNING_PRIVATE_KEY_PEM: ${{ secrets.PLUGIN_SIGNING_PRIVATE_KEY_PEM }}");
        }

        assertThat(workflow("quality-gate.yml")).doesNotContain(
                secretName,
                "plugin.credential.filter",
                "require-production-credential-key");
        String publishWorkflow = workflow("publish-plugins.yml");
        assertThat(publishWorkflow)
                .doesNotContain(secretName, "plugin.credential.filter")
                .contains(
                        "workflow_call:",
                        "PLUGINS_REPO_TOKEN:",
                        "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64:",
                        "PLUGIN_SIGNING_PRIVATE_KEY_PEM:");
    }

    @Test
    @DisplayName("手动插件发布在签名与跨仓库写入前自行运行质量门禁")
    void manualPluginPublishingRequiresQualityGate() throws Exception {
        String workflow = workflow("publish-plugins.yml");

        assertThat(workflow).contains(
                "quality-gate:",
                "github.ref == 'refs/heads/master'",
                "uses: ./.github/workflows/quality-gate.yml",
                "publish:",
                "needs: quality-gate",
                "!cancelled()",
                "needs.quality-gate.result == 'success'",
                "ref: ${{ github.sha }}");
        assertThat(workflow).doesNotContain("quality_gate_passed", "if: ${{ always()");
    }

    @Test
    @DisplayName("所有未发布官方插件统一使用初始版本 1.0.0 和首个核心 API 1.0")
    void officialPluginVersionsStartAtInitialVersion() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        for (OfficialPlugin plugin : officialDistributionPlugins(common)) {
            assertThat(pluginDescriptor(plugin.module())).as(plugin.id())
                    .contains("plugin.version=1.0.0", "plugin.requires=1.0");
        }
        assertThat(pluginDescriptor("pixivdownload-plugin-recovery-sentinel"))
                .contains("plugin.version=1.0.0", "plugin.requires=1.0");
        assertThat(common)
                .contains("Id = \"douyin\"", "Module = \"pixivdownload-plugin-douyin\"");
    }

    @Test
    @DisplayName("市场清单从 descriptor 投影初始核心 API 要求")
    void marketManifestProjectsInitialCoreApi() throws Exception {
        String descriptor = pluginDescriptor("pixivdownload-plugin-douyin");

        assertThat(descriptor)
                .contains("plugin.requires=1.0")
                .doesNotContain(
                        "plugin.requires=1.1",
                        "plugin.requires=1.2",
                        "plugin.requires=1.3");
        assertThat(script("generate-market-manifest.ps1")).contains(
                "$requires = $d[\"plugin.requires\"]",
                "requiredCoreApi   = (Get-RequiredCoreApi $requires)");
    }

    @Test
    @DisplayName("release/nightly 只上传恰好一个内部 app-shell JAR，安装器消费同一 artifact")
    void releaseWorkflowsUploadOnlyStagedAppShellJar() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "Stage executable JAR",
                    "build/release-jars",
                    "jar tf \"$OUTPUT_JAR\" | grep -q '^BOOT-INF/'",
                    "test -s \"$OUTPUT_JAR\"",
                    "STAGED_COUNT",
                    "path: build/release-jars/*.jar",
                    "name: app-shell-jar",
                    "if-no-files-found: error",
                    "Upload internal app-shell JAR",
                    "Download internal app-shell JAR",
                    "path: artifacts/app-shell-jar",
                    "$jars = @(Get-ChildItem artifacts/app-shell-jar/PixivDownload-*.jar -File)",
                    "$jars.Count -ne 1",
                    "$jar = $jars[0]");
            // 候选必须唯一：优先 boot jar，回退普通可执行 jar 时排除 sources/javadoc/original，
            // 不得静默取第一个。
            assertThat(workflow).as(name).contains(
                    "Expected exactly one executable app jar",
                    "PixivDownload-*-boot.jar 2>/dev/null",
                    "-original\\.jar");
            assertThat(workflow).as(name).doesNotContain(
                    "name: jar",
                    "artifacts/jar",
                    "path: artifacts/jar",
                    "path: pixivdownload-app/target/PixivDownload-*.jar");
        }
    }

    @Test
    @DisplayName("release/nightly 经共享脚本发布 java-standard 与 full-offline 签名分发布局")
    void releaseWorkflowsPublishJavaDistributions() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "publish-plugins:",
                    "uses: ./.github/workflows/publish-plugins.yml",
                    "Stage official plugin inputs from signed catalog",
                    "stage-official-plugin-inputs-from-catalog.ps1",
                    "-IncludeOptional",
                    "Assemble Java distributions",
                    "package-java-distributions.ps1",
                    "-PrebuiltJar $jars[0].FullName",
                    "-PrebuiltPluginsDir build/plugin-inputs",
                    "-SignatureToolJar $signatureTool.FullName",
                    "name: java-distributions",
                    "build/plugin-distributions/PixivDownload-*-java.zip",
                    "build/plugin-distributions/PixivDownload-*-full-offline.zip",
                    "if-no-files-found: error",
                    "path: artifacts/java-distributions",
                    "name: plugin-inputs",
                    "path: build/plugin-inputs/*",
                    "path: artifacts/plugin-inputs",
                    "Generate update manifest",
                    "artifacts/update.json",
                    "\"win-x64-installer\"");
            // 最终 Release files 只含安装包 + 两个 ZIP + update.json，绝不含裸 JAR / app-shell JAR。
            String filesBlock = workflow.substring(workflow.lastIndexOf("files: |"));
            assertThat(filesBlock).as(name + " release files").contains(
                    "artifacts/*-setup.exe",
                    "artifacts/java-distributions/*-java.zip",
                    "artifacts/java-distributions/*-full-offline.zip",
                    "artifacts/update.json")
                    .doesNotContain(".jar");
            assertThat(workflow).as(name).doesNotContain(
                    "Prepare plugin signing private key",
                    "PLUGIN_SIGNING_PRIVATE_KEY_FILE",
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
                "package-local.ps1")) {
            assertThat(script(name)).as(name).doesNotContain(
                    "Id = \"download-workbench\"",
                    "Id = \"gui-theme\"",
                    "Id = \"douyin\"",
                    "Id = \"stats\"");
        }
    }

    @Test
    @DisplayName("Java 标准包与 full-offline 的 Douyin 语义由共享集合与开关派生")
    void javaDistributionsDouyinSemantics() throws Exception {
        String common = script("plugin-distribution-common.ps1");
        String orchestrator = script("package-java-distributions.ps1");
        String distribution = script("assemble-plugin-distribution.ps1");

        Matcher defaultInstalled = Pattern.compile(
                "function Get-OfficialDefaultInstalledPlugins(?<body>.*?)function Get-OfficialOptionalPlugins",
                Pattern.DOTALL).matcher(common);
        assertThat(defaultInstalled.find()).isTrue();
        assertThat(defaultInstalled.group("body")).doesNotContain("Id = \"douyin\"");
        assertThat(common).contains("Id = \"douyin\"");
        assertThat(orchestrator).contains(
                "-DefaultDownloader",
                "Get-OfficialDistributionPlugins -IncludeOptional",
                "ExpectDouyin $false",
                "ExpectDouyin $true",
                "douyin artifact must not be staged",
                "must include the douyin plugin");
        assertThat(distribution).contains(
                "[switch]$DefaultDownloader",
                "Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader)");
    }

    @Test
    @DisplayName("Release 与 Nightly 使用说明描述 Java 标准包 / 离线全量包新发行矩阵")
    void releaseNotesDescribeJavaDistributionMatrix() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);
            assertThat(workflow).as(name).contains(
                    "### Java 标准包（跨平台）",
                    "Java 17",
                    "完整解压",
                    "run.bat",
                    "sh run.sh",
                    "除 Douyin 外的全部面向用户的签名官方插件",
                    "包内不包含 JRE，也不包含 FFmpeg",
                    "### 离线全量包（跨平台）",
                    "含 Douyin");
            assertThat(workflow).as(name).doesNotContain(
                    "仅提供 Windows 安装包和离线全量包",
                    "不再提供独立 JAR、core-shell-only 包或 default-downloader 包",
                    "java -Dfile.encoding=UTF-8 -jar PixivDownload-");
        }
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
    @DisplayName("Dockerfile 只从签名 default 分发布局复制 required 插件")
    void dockerfileCopiesSignedDefaultDistribution() throws Exception {
        String dockerfile = dockerfile();

        assertThat(dockerfile).contains(
                "ARG PIXIVDOWNLOADER_DISTRIBUTION=build/dist/default-downloader",
                "COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/PixivDownload-*.jar app.jar",
                "COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/plugins/ plugins/",
                "COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/plugins-manifest.json plugins-manifest.json",
                "COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/SHA256SUMS SHA256SUMS",
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
                "package-local.ps1")) {
            String script = script(name);
            assertThat(script).as(name).doesNotContain("-----BEGIN PRIVATE KEY-----");
            assertThat(script).as(name).doesNotContain("-----END PRIVATE KEY-----");
            assertThat(script).as(name).doesNotContain("Set-Content -Path $PrivateKeyFile");
            assertThat(script).as(name).doesNotContain("WriteAllText($PrivateKeyFile");
        }
    }

    @Test
    @DisplayName("布局偏好调查四个固定 Repository Variable 经 vars 上下文只传给 download-workbench")
    void layoutSurveyVarsAreFixedPublicVarsPassedOnlyToDownloadWorkbench() throws Exception {
        String release = workflow("release.yml");
        String nightly = workflow("nightly.yml");
        String publish = workflow("publish-plugins.yml");

        // 1. 固定变量名存在且使用 vars（Repository Variables）而非 secrets
        for (String name : List.of("release.yml", "nightly.yml", "publish-plugins.yml")) {
            String workflow = workflow(name);
            for (String variable : List.of(
                    "PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN",
                    "PIXIV_LAYOUT_SURVEY_ID",
                    "PIXIV_LAYOUT_SURVEY_API_HOST",
                    "PIXIV_LAYOUT_SURVEY_UI_HOST")) {
                assertThat(workflow).as(name).contains(variable);
                assertThat(workflow).as(name).doesNotContain("secrets." + variable);
                assertThat(workflow).as(name).contains("vars." + variable);
            }
            // 3. 不存在 enabled / SDK 版本 Repository Variable
            assertThat(workflow).as(name).doesNotContain("PIXIV_LAYOUT_SURVEY_ENABLED");
            assertThat(workflow).as(name).doesNotContain("PIXIV_LAYOUT_SURVEY_POSTHOG_JS_VERSION");
        }

        // 2. 上游正式发布缺配置时失败：require-config 按 github.repository 推导
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);
            assertThat(workflow).as(name).contains(
                    "-Dpixiv.layout-survey.project-token=${{ vars.PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN }}",
                    "-Dpixiv.layout-survey.survey-id=${{ vars.PIXIV_LAYOUT_SURVEY_ID }}",
                    "-Dpixiv.layout-survey.api-host=${{ vars.PIXIV_LAYOUT_SURVEY_API_HOST }}",
                    "-Dpixiv.layout-survey.ui-host=${{ vars.PIXIV_LAYOUT_SURVEY_UI_HOST }}",
                    "-Dpixiv.layout-survey.require-config=${{ github.repository == 'Sywyar/PixivDownloader' }}");
        }
        // 4. 发布脚本只在 download-workbench 插件上应用调查配置（不传给其它插件）
        assertThat(script("publish-plugin-releases.ps1"))
                .contains("if ($Plugin.Id -eq \"download-workbench\")")
                .doesNotContain("$Plugin.Id -eq \"stats\"");
        // 5. 参数带引号安全传递（避免空格拆分）
        assertThat(release).contains("\"-Dpixiv.layout-survey.api-host=${{ vars.PIXIV_LAYOUT_SURVEY_API_HOST }}\"");
        // 8/9/10/11. release / nightly / workflow_dispatch / workflow_call 流程保留
        assertThat(release).contains("push:", "tags:", "workflow_dispatch:");
        assertThat(nightly).contains("schedule:", "workflow_dispatch:");
        assertThat(publish).contains("workflow_call:", "workflow_dispatch:");
    }

    @Test
    @DisplayName("插件发布脚本把调查配置写入 download-workbench jar 后再计算 sha256 / 签名")
    void publishScriptBakesSurveyConfigIntoWorkbenchJarBeforeSigning() throws Exception {
        String publishScript = script("publish-plugin-releases.ps1");
        String common = script("plugin-distribution-common.ps1");

        assertThat(publishScript).contains(
                "New-LayoutSurveyPublicConfig",
                "if ($Plugin.Id -eq \"download-workbench\")",
                "Update-JarFileEntry",
                "\"static/pixiv-layout-feedback/public-config.js\"",
                "generator",
                "PIXIV_LAYOUT_SURVEY_OUTPUT_PATH",
                "enabled: true");
        // 签名 / sha256 覆盖最终字节：生成器替换发生在 Build-StagedPluginArtifact 内部，
        // Write-StagedCompanionFiles 在构建返回之后才计算校验与签名。
        assertThat(publishScript.indexOf("Update-JarFileEntry"))
                .as("jar 内容替换必须先于 sha256 / 签名")
                .isGreaterThan(publishScript.indexOf("Copy-Item $builtArtifact $stagedArtifact -Force"));
        assertThat(publishScript.indexOf("Write-StagedCompanionFiles -StagedArtifact $stagedArtifact"))
                .as("sha256 / 签名在 jar 内容替换之后")
                .isGreaterThan(publishScript.indexOf("Update-JarFileEntry"));

        assertThat(common).contains(
                "function Update-JarFileEntry",
                "[System.IO.Compression.ZipArchiveMode]::Update",
                "so the signature always",
                "covers the final artifact bytes");
        // 上游缺配置时发布脚本明确失败
        assertThat(publishScript).contains(
                "refusing to publish download-workbench without a complete configuration");
    }

    @Test
    @DisplayName("full-offline / 市场 manifest 仍覆盖最终公开配置字节（插件 jar 是不可变发布身份）")
    void distributionStillCoversFinalPublicConfig() throws Exception {
        String distribution = script("assemble-plugin-distribution.ps1");
        String common = script("plugin-distribution-common.ps1");
        assertThat(distribution).contains(
                "Get-OfficialDistributionPlugins",
                "plugins-manifest.json",
                "SHA256SUMS");
        // 发布脚本以 plugin.version 为不可变键，已有版本绝不重传
        assertThat(script("publish-plugin-releases.ps1")).contains(
                "Bump plugin.version instead of publishing new bytes under an existing tag",
                "already published with expected assets; skip");
        // 生成器只输出公开客户端配置，不含任何管理密钥
        String generator = script("generate-layout-survey-public-config.ps1");
        assertThat(generator)
                .contains("PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN")
                .contains("PIXIV_LAYOUT_SURVEY_ID")
                .contains("PIXIV_LAYOUT_SURVEY_API_HOST")
                .contains("PIXIV_LAYOUT_SURVEY_UI_HOST")
                .contains("Object.freeze({")
                .doesNotContain("personalApiKey")
                .doesNotContain("serviceAccountToken")
                .doesNotContain("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    @DisplayName("本地安装包脚本显式启用布局调查：EnableLayoutSurvey 只允许 Local，默认 properties 路径正确")
    void localInstallerLayoutSurveyExplicitEnableContract() throws Exception {
        String installer = script("package-installer-with-plugins.ps1");
        String local = script("package-local.ps1");
        String generator = script("generate-layout-survey-public-config.ps1");

        // 1/2. 参数与默认路径
        assertThat(installer).contains(
                "[switch]$EnableLayoutSurvey",
                "[string]$LayoutSurveyPropertiesFile",
                "Join-Path $PSScriptRoot \"properties/posthog.properties\"");
        // 3/4. Enable 只允许 Local；Catalog + Enable 立即失败
        assertThat(installer).contains(
                "if ($EnableLayoutSurvey -and $PluginSource -ne \"Local\")",
                "EnableLayoutSurvey requires -PluginSource Local");
        // 5. 文件存在不会自动启用：禁用分支生成时不携带 PropertiesFile
        assertThat(installer).contains("$generatorParams = @{ OutputPath = $layoutSurveyConfigFile }");
        // 6. 显式传文件但未 Enable 立即失败
        assertThat(installer).contains(
                "if (-not $EnableLayoutSurvey -and $PSBoundParameters.ContainsKey(\"LayoutSurveyPropertiesFile\"))");
        // 7/8. Local 未 Enable 生成 disabled；Local Enable 调用统一生成器
        assertThat(installer).contains(
                "$generatorParams.PropertiesFile = $LayoutSurveyPropertiesFile",
                "Layout survey packaging: enabled",
                "Layout survey packaging: disabled",
                "$layoutSurveyConfigFile = Join-Path $layoutSurveyDir \"public-config.js\"");
        // 9. 生成路径传给 package-local.ps1
        assertThat(installer).contains("$packageArgs.LayoutSurveyPublicConfigFile = $layoutSurveyConfigFile");
        // 10. package-local.ps1 定义内部参数
        assertThat(local).contains("[string]$LayoutSurveyPublicConfigFile");
        // 11/12. 只修改 download-workbench；JAR entry 路径准确
        assertThat(local).contains(
                "if ($plugin.Id -eq \"download-workbench\"",
                "\"static/pixiv-layout-feedback/public-config.js\"");
        // 13. 修改后执行字节校验
        assertThat(local).contains(
                "Assert-JarFileEntryEqualsFile",
                "Update-JarFileEntry");
        // 14. Patch 发生在 SHA 与签名之前
        assertThat(local.indexOf("Update-JarFileEntry -JarPath $targetArtifact"))
                .as("JAR entry 替换必须先于 SHA-256")
                .isGreaterThan(local.indexOf("Copy-Item $sourceArtifact $targetArtifact -Force"));
        assertThat(local.indexOf("$sha = Get-Sha256Hex $targetArtifact"))
                .as("SHA-256 在 JAR entry 替换之后")
                .isGreaterThan(local.indexOf("Update-JarFileEntry -JarPath $targetArtifact"));
        assertThat(local.indexOf("Get-PluginArtifactSignatureForDistribution"))
                .as("签名在 SHA-256 之后")
                .isGreaterThan(local.indexOf("$sha = Get-Sha256Hex $targetArtifact"));
        // 15. 修改后不复用旧签名（artifactMutated 时清空 source sidecar）
        assertThat(local).contains(
                "$artifactMutated = $true",
                "if ($artifactMutated) {",
                "$sourceSignaturePath = \"\"");
        // 16. 其他插件不受影响：注入被 plugin.id 门控，且无配置时完全不动作
        assertThat(local).contains(
                "-not [string]::IsNullOrWhiteSpace($LayoutSurveyPublicConfigFile)");
        // Catalog 模式不允许本地覆盖
        assertThat(local).contains(
                "cannot be combined with PrebuiltPluginsDir (catalog artifacts are never modified locally)");
        // 生成器 PropertiesFile 解析契约
        assertThat(generator).contains(
                "[string]$PropertiesFile",
                "pixiv.layout-survey.project-token",
                "pixiv.layout-survey.survey-id",
                "pixiv.layout-survey.api-host",
                "pixiv.layout-survey.ui-host",
                "PropertiesFile cannot be combined with the explicit ProjectToken",
                "unknown key",
                "duplicate key",
                "missing required key",
                "multi-line continuation",
                "placeholder value");
        assertThat(generator).doesNotContain("pixiv.feedback.layout-survey");
        assertThat(generator).doesNotContain("Write-Host $ProjectToken");
    }

    @Test
    @DisplayName("本地打包改造不改变固定版本、GitHub Variables 与官方配置来源")
    void localSurveyPackagingKeepsFixedVersionsAndOfficialSources() throws Exception {
        assertThat(pluginDescriptor("pixivdownload-plugin-download-workbench"))
                .contains("plugin.version=1.0.0");
        String js = Files.readString(repoRoot().resolve("pixivdownload-plugin-download-workbench")
                .resolve("src/main/resources/static/pixiv-layout-feedback/pixiv-layout-feedback.js"),
                StandardCharsets.UTF_8);
        assertThat(js).contains("POSTHOG_JS_VERSION = '1.409.5'");
        for (String name : List.of("release.yml", "nightly.yml", "publish-plugins.yml")) {
            assertThat(workflow(name)).as(name)
                    .doesNotContain("posthog.properties")
                    .doesNotContain("EnableLayoutSurvey");
        }
        // 四个 Repository Variable 名称与 vars 来源未改变
        String release = workflow("release.yml");
        for (String variable : List.of(
                "PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN",
                "PIXIV_LAYOUT_SURVEY_ID",
                "PIXIV_LAYOUT_SURVEY_API_HOST",
                "PIXIV_LAYOUT_SURVEY_UI_HOST")) {
            assertThat(release).contains("vars." + variable);
            assertThat(release).doesNotContain("secrets." + variable);
        }
    }

    @Test
    @DisplayName("本地 posthog.properties 被精确忽略，example 文件四项为空且无旧键")
    void localPropertiesGitignoreAndExampleContract() throws Exception {
        String ignore = Files.readString(repoRoot().resolve(".gitignore"), StandardCharsets.UTF_8);
        assertThat(ignore).contains("/scripts/properties/posthog.properties");
        assertThat(ignore.lines()).as("不得忽略整个 scripts/properties/ 目录")
                .doesNotContain("/scripts/properties/");

        Path example = repoRoot().resolve("scripts/properties/posthog.properties.example");
        assertThat(Files.isRegularFile(example)).isTrue();
        Map<String, String> props = readProperties(example);
        assertThat(props.keySet()).as("example 四个精确键且无旧前缀")
                .containsExactly(
                        "pixiv.layout-survey.project-token",
                        "pixiv.layout-survey.survey-id",
                        "pixiv.layout-survey.api-host",
                        "pixiv.layout-survey.ui-host");
        for (String value : props.values()) {
            assertThat(value).as("example 值必须为空").isEmpty();
        }
        assertThat(Files.readString(example, StandardCharsets.UTF_8))
                .doesNotContain("pixiv.feedback.layout-survey");
    }

    @Test
    @DisplayName("Nightly no-diff 门禁保留：has_changes 由真实 Git diff 判定并门控全部昂贵任务")
    void nightlyNoDiffGateUsesRealGitDiff() throws Exception {
        String nightly = workflow("nightly.yml");
        String script = script("nightly-changelog-gate.sh");

        // resolve-version 仍输出 has_changes，四个昂贵 job 仍由同一门控。
        assertThat(nightly).contains(
                "has_changes: ${{ steps.changelog.outputs.has_changes }}",
                "publish-plugins:",
                "build-jar:",
                "build-windows-installer:",
                "release-nightly:");
        Matcher gated = Pattern.compile("needs\\.resolve-version\\.outputs\\.has_changes == 'true'")
                .matcher(nightly);
        int gatedCount = 0;
        while (gated.find()) {
            gatedCount++;
        }
        assertThat(gatedCount).as("has_changes 门控数量").isEqualTo(4);

        // workflow 调用共享门禁脚本，脚本按 Git 真实 diff 三态判定。
        assertThat(nightly).contains("./scripts/nightly-changelog-gate.sh CHANGELOG.md nightly");
        assertThat(script).contains(
                "git diff --quiet",
                "case \"$diff_status\" in",
                "0)",
                "1)",
                "*)",
                "git diff failed with exit code",
                "set -euo pipefail",
                "/^## \\[Unreleased\\]/");
        // 已有标签场景不再用 CHANGELOG_DIFF.md 非空与否决定 has_changes。
        String checkStep = nightly.substring(nightly.indexOf("Check changelog diff"),
                nightly.indexOf("Resolve next version"));
        assertThat(checkStep).doesNotContain("CHANGELOG_DIFF.md", "[ -s CHANGELOG_DIFF.md ]");
        // 首次无标签仍检查 [Unreleased] 区段。
        assertThat(script).contains("[Unreleased]");
        assertAsciiWithoutBom(repoRoot().resolve("scripts").resolve("nightly-changelog-gate.sh"));
    }

    @Test
    @DisplayName("Nightly 门禁脚本行为矩阵：无 diff 跳过，新增/修改/纯删除都触发，Git 错误失败")
    void nightlyChangelogGateScriptBehaviorMatrix() throws Exception {
        Path script = repoRoot().resolve("scripts").resolve("nightly-changelog-gate.sh");
        assumeTrue(canRun("bash", "--version"), "bash 不可用，跳过行为测试");
        assumeTrue(canRun("git", "--version"), "git 不可用，跳过行为测试");

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
    }    private static String innoScript() throws IOException {
        return Files.readString(repoRoot().resolve("packaging").resolve("windows").resolve("inno")
                .resolve("PixivDownload.iss"), StandardCharsets.UTF_8);
    }

    private static String innoSupportScript(String name) throws IOException {
        return Files.readString(repoRoot().resolve("packaging").resolve("windows").resolve("inno")
                .resolve(name), StandardCharsets.UTF_8);
    }

    private static String workflow(String name) throws IOException {
        return Files.readString(repoRoot().resolve(".github").resolve("workflows").resolve(name),
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

    private static List<OfficialPlugin> officialDistributionPlugins(String common) {
        Matcher matcher = Pattern.compile("\\[pscustomobject\\]@\\{(?<body>.*?)\\}", Pattern.DOTALL)
                .matcher(common);
        List<OfficialPlugin> plugins = new ArrayList<>();
        while (matcher.find()) {
            String body = matcher.group("body");
            String id = pscustomObjectStringField(body, "Id");
            String module = pscustomObjectStringField(body, "Module");
            if (id != null && module != null && !"recovery-sentinel".equals(id)) {
                plugins.add(new OfficialPlugin(id, module));
            }
        }
        assertThat(plugins).as("official plugins").isNotEmpty();
        return plugins;
    }

    private static Set<String> officialDistributionPluginIds(String common) {
        Set<String> ids = new LinkedHashSet<>();
        for (OfficialPlugin plugin : officialDistributionPlugins(common)) {
            ids.add(plugin.id());
        }
        assertThat(ids).as("official plugin ids").isNotEmpty();
        return ids;
    }

    private static String pscustomObjectStringField(String body, String field) {
        Matcher matcher = Pattern.compile("\\b" + field + "\\s*=\\s*\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
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
}
