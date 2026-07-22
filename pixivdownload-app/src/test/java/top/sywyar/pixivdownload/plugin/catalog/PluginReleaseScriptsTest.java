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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    @DisplayName("质量门禁以同一提交运行完整 Java 与零依赖 JavaScript 测试")
    void qualityGateRunsJavaAndJavaScriptTestsForTheSameCommit() throws Exception {
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
                "uses: actions/setup-node@v4",
                "node-version: '24'",
                "run: npm run test:js",
                "run: npm run test:web-standards");
        assertThat(workflow.split(Pattern.quote("ref: ${{ github.sha }}"), -1)).hasSize(3);
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
    @DisplayName("release/nightly 工作流只上传已验证的可执行 boot jar，避免普通 jar 混入安装器")
    void releaseWorkflowsUploadOnlyStagedExecutableBootJar() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "Stage executable JAR",
                    "build/release-jars",
                    "jar tf \"$OUTPUT_JAR\" | grep -q '^BOOT-INF/'",
                    "path: build/release-jars/*.jar",
                    "$jars = @(Get-ChildItem artifacts/jar/PixivDownload-*.jar -File)",
                    "$jars.Count -ne 1",
                    "$jar = $jars[0]");
            assertThat(workflow).as(name).doesNotContain("path: pixivdownload-app/target/PixivDownload-*.jar");
            assertThat(workflow).as(name).doesNotContain("PixivDownload-*-boot.jar -File | Select-Object -First 1");
        }
    }

    @Test
    @DisplayName("release/nightly 工作流只发布签名 full-offline 分发布局而不是裸插件 jar")
    void releaseWorkflowsPublishSignedPluginDistributions() throws Exception {
        for (String name : List.of("release.yml", "nightly.yml")) {
            String workflow = workflow(name);

            assertThat(workflow).as(name).contains(
                    "publish-plugins:",
                    "uses: ./.github/workflows/publish-plugins.yml",
                    "Stage official plugin inputs from signed catalog",
                    "stage-official-plugin-inputs-from-catalog.ps1",
                    "Assemble full-offline distribution",
                    "full-offline",
                    "-PrebuiltPluginsDir build/plugin-inputs",
                    "-SignatureToolJar $signatureTool.FullName",
                    "name: plugin-distributions",
                    "path: build/plugin-distributions/PixivDownload-*-full-offline.zip",
                    "path: artifacts/plugin-distributions",
                    "artifacts/plugin-distributions/*-full-offline.zip",
                    "name: plugin-inputs",
                    "path: build/plugin-inputs/*",
                    "path: artifacts/plugin-inputs",
                    "full-offline.zip",
                    "plugins-manifest.json",
                    "Generate update manifest",
                    "artifacts/update.json",
                    "\"win-x64-installer\"");
            assertThat(workflow).as(name).doesNotContain(
                    "Prepare plugin signing private key",
                    "PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64: ${{ secrets.PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64 }}",
                    "PLUGIN_SIGNING_PRIVATE_KEY_FILE",
                    "pixivdownload-plugin-duplicate/target/pixivdownload-plugin-duplicate-*.jar",
                    "-CoreShellOnly",
                    "-DefaultDownloader",
                    "default-downloader.zip",
                    "core-shell-only.zip",
                    "artifacts/plugin-distributions/*.zip",
                    "artifacts/plugins/*.jar",
                    "name: plugins",
                    "path: build/release-plugins/*.jar",
                    "build/release-plugins",
                    "Release 附件中的 `pixivdownload-plugin-download-workbench-*.jar`",
                    "同一 Nightly 附件中的 `pixivdownload-plugin-download-workbench-*.jar`");
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
                "package-installer-with-plugins.ps1",
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

    private static String innoScript() throws IOException {
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
