package top.sywyar.pixivdownload.ffmpeg;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.signature.OfficialArtifactTrustRoots;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FFmpeg 稳定版资产选择")
class FfmpegInstallerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedProbePublicationPreservesTheWholeInstallationAndCanRetry(boolean existing, @TempDir Path root) throws Exception {
        Path tools = Files.createDirectories(root.resolve("tools/ffmpeg"));
        Files.createDirectories(tools.resolve("licenses"));
        Path unrelated = Files.writeString(tools.resolve("unrelated"), "keep");
        var names = List.of(FfmpegLocator.executableName(), FfmpegLocator.probeExecutableName(),
                "licenses/ffmpeg-LGPLv2.1.txt", "licenses/libwebp-COPYING.txt", "licenses/libwebp-PATENTS.txt");
        var inputs = new java.util.ArrayList<Path>();
        for (String name : names) {
            inputs.add(Files.writeString(root.resolve(Path.of(name).getFileName()), "new " + name));
            if (existing) Files.writeString(tools.resolve(name), "old " + name);
        }
        var extracted = new FfmpegInstaller.ExtractedFiles(inputs.get(0), inputs.get(1), inputs.get(2), inputs.get(3), inputs.get(4));
        Path probe = tools.resolve(FfmpegLocator.probeExecutableName());
        if (existing && FfmpegLocator.isWindows()) {
            try (var busy = java.nio.channels.FileChannel.open(probe, java.nio.file.StandardOpenOption.READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                assertThatThrownBy(() -> FfmpegInstaller.publish(extracted, tools)).isInstanceOf(IOException.class);
            }
            for (String name : names) assertThat(Files.readString(tools.resolve(name))).isEqualTo("old " + name);
        }
        var denyNextProbeMove = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var files = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("move") && probe.equals(invocation.getArgument(1))
                    && denyNextProbeMove.getAndSet(false))
                throw new java.nio.file.AccessDeniedException(probe.toString());
            return invocation.callRealMethod();
        })) {
            assertThatThrownBy(() -> FfmpegInstaller.publish(extracted, tools)).isInstanceOf(java.nio.file.AccessDeniedException.class);
        }
        for (String name : names) {
            if (existing) assertThat(Files.readString(tools.resolve(name))).isEqualTo("old " + name);
            else assertThat(tools.resolve(name)).doesNotExist();
        }
        assertThat(FfmpegLocator.installationAt(tools, FfmpegInstallation.Source.MANAGED).isPresent()).isEqualTo(existing);
        FfmpegInstaller.publish(extracted, tools);
        for (String name : names) assertThat(Files.readString(tools.resolve(name))).isEqualTo("new " + name);
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"backup", "publish"})
    void freshProcessRecoversInterruptedUpdate(String phase, @TempDir Path root) throws Exception {
        for (String mode : List.of(phase, "recover")) {
            runFixture(mode, root, mode.equals("recover") ? 0 : 71);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"status-copy", "status-publish"})
    void statusReturnsBeforePublicationContinues(String phase, @TempDir Path root) throws Exception {
        runFixture(phase, root, 0);
    }

    private static void runFixture(String mode, Path root, int expectedExit) throws Exception {
        Path log = root.resolve(mode + ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dpixivdownload.config-dir=" + root.resolve("config"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FfmpegInstallerTest.class.getName(), mode).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(log)).isEqualTo(expectedExit);
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
    }

    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        Path root = Path.of("").toAbsolutePath();
        Path tools = root.resolve("tools/ffmpeg");
        assertThat(FfmpegLocator.managedToolsDir()).isEqualTo(tools);
        var names = List.of(FfmpegLocator.executableName(), FfmpegLocator.probeExecutableName(),
                "licenses/ffmpeg-LGPLv2.1.txt", "licenses/libwebp-COPYING.txt", "licenses/libwebp-PATENTS.txt");
        var inputs = names.stream().map(name -> root.resolve(Path.of(name).getFileName())).toList();
        var extracted = new FfmpegInstaller.ExtractedFiles(inputs.get(0), inputs.get(1), inputs.get(2), inputs.get(3), inputs.get(4));
        if (args[0].equals("recover")) {
            assertThat(FfmpegLocator.locate(root.resolve("config.yaml")).orElseThrow().source())
                    .isEqualTo(FfmpegInstallation.Source.MANAGED);
            for (String name : names) assertThat(Files.readString(tools.resolve(name))).isEqualTo("old " + name);
            FfmpegInstaller.publish(extracted, tools);
            for (String name : names) assertThat(Files.readString(tools.resolve(name))).isEqualTo("new " + name);
            try (var entries = Files.newDirectoryStream(tools, ".install-*")) {
                var workspaces = new java.util.ArrayList<Path>();
                entries.forEach(workspaces::add);
                assertThat(workspaces).hasSize(1);
                assertThat(workspaces.getFirst().resolve("idle")).isRegularFile();
            }
            return;
        }
        for (int i = 0; i < names.size(); i++) Files.writeString(inputs.get(i), "old " + names.get(i));
        FfmpegInstaller.publish(extracted, tools);
        for (int i = 0; i < names.size(); i++) Files.writeString(inputs.get(i), "new " + names.get(i));
        if (args[0].startsWith("status-")) {
            checkStatusWhilePublishing(extracted, tools, args[0].equals("status-copy"));
            return;
        }
        try (var files = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
            Object result = invocation.callRealMethod();
            if (invocation.getMethod().getName().equals("move")) {
                Path destination = invocation.getArgument(1);
                if (args[0].equals("backup") ? destination.getFileName().toString().equals("old-0")
                        : destination.equals(tools.resolve(names.get(3)))) Runtime.getRuntime().halt(71);
            }
            return result;
        })) { FfmpegInstaller.publish(extracted, tools); }
    }

    private static void checkStatusWhilePublishing(FfmpegInstaller.ExtractedFiles extracted, Path tools, boolean beforeCopy) throws Exception {
        var paused = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var service = new FfmpegInstallService((proxy, progress) -> {
            progress.onProgress(FfmpegInstaller.ProgressStage.INSTALLING, -1, -1);
            try (var files = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
                String method = invocation.getMethod().getName();
                boolean checkpoint = beforeCopy
                        ? method.equals("copy") && extracted.ffmpeg().equals(invocation.getArgument(0))
                        : method.equals("move") && ((Path) invocation.getArgument(0)).getFileName().toString().equals("new-4");
                if (checkpoint) {
                    paused.countDown();
                    if (!resume.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Publication timed out");
                }
                return invocation.callRealMethod();
            })) {
                FfmpegInstaller.publish(extracted, tools);
            }
            return FfmpegLocator.managedInstallation().orElseThrow();
        });
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new FfmpegToolsController(service, null, null, null)).build();
        try (var client = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var accepted = service.start(FfmpegInstaller.ProxySettings.disabled());
            var resolving = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand> command;
            try {
                assertThat(paused.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(Files.exists(tools.resolve(FfmpegLocator.executableName()))).isEqualTo(beforeCopy);
                var response = client.submit(() -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tools/ffmpeg")).andReturn().getResponse()).get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(200);
                var status = MAPPER.readTree(response.getContentAsByteArray());
                assertThat(status.at("/task/executionId").asString()).isEqualTo(accepted.executionId().toString());
                assertThat(status.at("/task/stage").asString()).isEqualTo("INSTALLING");
                assertThat(status.at("/installation/source").asString()).isEqualTo("MANAGED");
                command = client.submit(() -> {
                    resolving.countDown();
                    return new FfmpegCommandResolverAdapter().resolve();
                });
                assertThat(resolving.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                top.sywyar.pixivdownload.gui.AppDesktopUiHostFfmpegTest.checkSwingStatus(tools);
                assertThat(command.isDone()).isFalse();
                assertThat(Path.of(java.net.URI.create(status.at("/installation/ffmpegPath").asString())))
                        .isEqualTo(tools.resolve(FfmpegLocator.executableName()));
            } finally {
                resume.countDown();
                service.close();
            }
            assertThat(service.snapshot().state()).isEqualTo("COMPLETED");
            Path executable = Path.of(command.get(5, java.util.concurrent.TimeUnit.SECONDS).command());
            assertThat(executable).isEqualTo(tools.resolve(FfmpegLocator.executableName()));
            assertThat(Files.readString(executable)).isEqualTo("new " + FfmpegLocator.executableName());
        }
    }

    @Test
    @DisplayName("启用的代理配置无效时拒绝连接，不退回直连")
    void rejectsInvalidEnabledProxy() {
        assertThatThrownBy(() -> new FfmpegInstaller.ProxySettings(true, " ", 17891).toProxySelector())
                .isInstanceOf(IllegalArgumentException.class);
        for (int port : new int[]{-1, 0, 65536}) {
            assertThatThrownBy(() -> new FfmpegInstaller.ProxySettings(true, "127.0.0.1", port).toProxySelector())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("实际下载只使用显式代理，关闭后不继承进程默认代理")
    void routesDownloadUsingExplicitProxy() throws Exception {
        var origin = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var proxyCalls = new java.util.concurrent.atomic.AtomicInteger();
        var previous = java.net.ProxySelector.getDefault();
        origin.createContext("/", exchange -> {
            byte[] bytes = "direct fixture".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        proxy.createContext("/", exchange -> {
            proxyCalls.incrementAndGet();
            byte[] bytes = "proxy fixture".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        try {
            origin.start();
            proxy.start();
            java.net.ProxySelector.setDefault(java.net.ProxySelector.of(proxy.getAddress()));
            var download = FfmpegInstaller.class.getDeclaredMethod("openDownload", URI.class, FfmpegInstaller.ProxySettings.class);
            download.setAccessible(true);
            var direct = (java.net.http.HttpResponse<?>) download.invoke(null,
                    URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/fixture"),
                    FfmpegInstaller.ProxySettings.disabled());
            try (var body = (java.io.InputStream) direct.body()) {
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("direct fixture");
            }
            assertThat(proxyCalls.get()).isZero();
            var proxied = (java.net.http.HttpResponse<?>) download.invoke(null, URI.create("http://unreachable.invalid/fixture"),
                    new FfmpegInstaller.ProxySettings(true, "127.0.0.1", proxy.getAddress().getPort()));
            try (var body = (java.io.InputStream) proxied.body()) {
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("proxy fixture");
            }
            assertThat(proxyCalls.get()).isEqualTo(1);
        } finally {
            java.net.ProxySelector.setDefault(previous);
            origin.stop(0);
            proxy.stop(0);
        }
    }

    @Test
    @DisplayName("按操作系统与架构选择对应的稳定版资产")
    void selectsStableAssetForSupportedPlatform() {
        assertAsset("Windows 11", "amd64", "ffmpeg-windows-x64.zip");
        assertAsset("Linux", "x86_64", "ffmpeg-linux-x64.zip");
        assertAsset("Linux", "aarch64", "ffmpeg-linux-arm64.zip");
        assertAsset("Mac OS X", "x86_64", "ffmpeg-macos-x64.zip");
        assertAsset("Mac OS X", "arm64", "ffmpeg-macos-arm64.zip");
        assertAsset("Darwin", "arm64", "ffmpeg-macos-arm64.zip");
    }

    @Test
    @DisplayName("没有对应构建的系统保持手动安装降级")
    void rejectsUnsupportedPlatform() {
        assertThat(FfmpegInstaller.assetName("Windows 11", "aarch64")).isEmpty();
        assertThat(FfmpegInstaller.assetName("Linux", "x86")).isEmpty();
        assertThat(FfmpegInstaller.assetName("FreeBSD", "amd64")).isEmpty();
    }

    @Test
    @DisplayName("只提取运行文件与随包许可证")
    void extractsRequiredFiles(@TempDir Path tempDir) throws IOException {
        Path archive = tempDir.resolve("ffmpeg.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "ffmpeg-test/bin/" + FfmpegLocator.executableName());
            addEntry(zip, "ffmpeg-test/bin/" + FfmpegLocator.probeExecutableName());
            addEntry(zip, "ffmpeg-test/licenses/ffmpeg-LGPLv2.1.txt");
            addEntry(zip, "ffmpeg-test/licenses/libwebp-COPYING.txt");
            addEntry(zip, "ffmpeg-test/licenses/libwebp-PATENTS.txt");
            addEntry(zip, "ffmpeg-test/ignored.txt");
        }

        FfmpegInstaller.ExtractedFiles files = FfmpegInstaller.extractRequiredFiles(
                archive, tempDir.resolve("extracted"));
        assertThat(List.of(files.ffmpeg(), files.ffprobe(), files.ffmpegLicense(),
                files.libwebpLicense(), files.libwebpPatents())).allMatch(Files::isRegularFile);
        assertThat(tempDir.resolve("extracted/ignored.txt")).doesNotExist();
    }

    @Test
    @DisplayName("签名清单绑定精确资产名、长度与 SHA-256")
    void verifiesSignedManifestAndArchive(@TempDir Path tempDir) throws Exception {
        SigningSupport signing = new SigningSupport();
        byte[] archiveBytes = "verified archive".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = manifest("ffmpeg-windows-x64.zip", archiveBytes);
        FfmpegInstaller.AssetMetadata asset = FfmpegInstaller.verifyRelease(
                manifest, signing.signature(manifest), "ffmpeg-windows-x64.zip", signing.verifier());
        Path archive = tempDir.resolve(asset.assetName());
        Files.write(archive, archiveBytes);

        FfmpegInstaller.verifyArchive(archive, asset);

        assertThat(asset.expectedSizeBytes()).isEqualTo(archiveBytes.length);
        assertThat(asset.sha256()).isEqualTo(sha256(archiveBytes));
    }

    @Test
    @DisplayName("清单缺失、签名无效或资产名不匹配时拒绝安装")
    void rejectsUntrustedReleaseMetadata() throws Exception {
        SigningSupport signing = new SigningSupport();
        byte[] archiveBytes = "archive".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = manifest("ffmpeg-windows-x64.zip", archiveBytes);
        byte[] signature = signing.signature(manifest);

        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                new byte[0], signature, "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("MANIFEST_MISSING");
        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                manifest, new byte[0], "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("SIGNATURE_MISSING");

        byte[] changedManifest = manifest.clone();
        changedManifest[changedManifest.length - 2] ^= 1;
        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                changedManifest, signature, "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("INVALID_SIGNATURE");

        byte[] otherAssetManifest = manifest("ffmpeg-linux-x64.zip", archiveBytes);
        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                otherAssetManifest, signing.signature(otherAssetManifest),
                "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("ASSET_NAME_MISMATCH");
    }

    @Test
    @DisplayName("生产 FFmpeg 验签只信任 FFmpeg 用途公钥")
    void productionVerifierOnlyTrustsFfmpegRoot() throws Exception {
        byte[] manifest = "{}".getBytes(StandardCharsets.UTF_8);
        String invalidSignature = Base64.getEncoder().encodeToString(new byte[64]);

        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                manifest,
                signature(OfficialArtifactTrustRoots.FFMPEG_KEY_ID, invalidSignature),
                "ffmpeg-windows-x64.zip",
                FfmpegInstaller.ffmpegManifestVerifier()))
                .hasMessageContaining("INVALID_SIGNATURE");
        for (String otherPurposeKey : List.of(
                OfficialArtifactTrustRoots.PLUGIN_KEY_ID,
                OfficialArtifactTrustRoots.UPDATE_KEY_ID)) {
            assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                    manifest,
                    signature(otherPurposeKey, invalidSignature),
                    "ffmpeg-windows-x64.zip",
                    FfmpegInstaller.ffmpegManifestVerifier()))
                    .hasMessageContaining("UNKNOWN_KEY");
        }
    }

    @Test
    @DisplayName("长度或摘要不匹配时在解压和替换既有工具前失败")
    void rejectsTamperedArchiveWithoutReplacingExistingFiles(@TempDir Path tempDir) throws Exception {
        Path existing = tempDir.resolve("installed-ffmpeg");
        Files.writeString(existing, "existing", StandardCharsets.UTF_8);
        Path archive = tempDir.resolve("ffmpeg.zip");
        Files.writeString(archive, "tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> FfmpegInstaller.verifyArchive(archive,
                new FfmpegInstaller.AssetMetadata("ffmpeg.zip", Files.size(archive) + 1L, sha256("tampered"))))
                .hasMessageContaining("ASSET_SIZE_MISMATCH");
        assertThatThrownBy(() -> FfmpegInstaller.verifyArchive(archive,
                new FfmpegInstaller.AssetMetadata("ffmpeg.zip", Files.size(archive), sha256("expected"))))
                .hasMessageContaining("ASSET_SHA256_MISMATCH");
        assertThat(existing).hasContent("existing");
        assertThat(tempDir.resolve("extracted")).doesNotExist();
    }

    @Test
    @DisplayName("首次自动安装的三种初始化状态都能安全建立目录")
    void preparesManagedDirectoryForEveryFirstInstallState(@TempDir Path tempDir) throws IOException {
        // 状态一：没有 tools —— 真正的首次安装，必须允许逐级创建。
        Path rootWithoutTools = Files.createDirectories(tempDir.resolve("no-tools"));
        Path toolsMissingAll = rootWithoutTools.resolve("tools").resolve("ffmpeg");
        FfmpegInstaller.requirePlainManagedDirectory(toolsMissingAll);
        assertThat(toolsMissingAll).isDirectory();
        FfmpegInstaller.requirePlainManagedDirectory(toolsMissingAll.resolve("licenses"));
        assertThat(toolsMissingAll.resolve("licenses")).isDirectory();

        // 状态二：只有 tools —— 上一轮在中途失败后重试。
        Path rootWithToolsOnly = Files.createDirectories(tempDir.resolve("tools-only"));
        Files.createDirectory(rootWithToolsOnly.resolve("tools"));
        Path toolsMissingLeaf = rootWithToolsOnly.resolve("tools").resolve("ffmpeg");
        FfmpegInstaller.requirePlainManagedDirectory(toolsMissingLeaf);
        assertThat(toolsMissingLeaf).isDirectory();
        FfmpegInstaller.requirePlainManagedDirectory(toolsMissingLeaf.resolve("licenses"));
        assertThat(toolsMissingLeaf.resolve("licenses")).isDirectory();

        // 状态三：只有 tools/ffmpeg —— 既有安装目录，保持可用且补齐许可证目录。
        Path rootWithTools = Files.createDirectories(tempDir.resolve("ffmpeg-only"));
        Path toolsPresent = Files.createDirectories(rootWithTools.resolve("tools").resolve("ffmpeg"));
        FfmpegInstaller.requirePlainManagedDirectory(toolsPresent);
        FfmpegInstaller.requirePlainManagedDirectory(toolsPresent.resolve("licenses"));
        assertThat(toolsPresent.resolve("licenses")).isDirectory();
    }

    @Test
    @DisplayName("已有同名普通文件时拒绝安装，且不覆盖该文件")
    void rejectsManagedDirectoryOccupiedByRegularFile(@TempDir Path tempDir) throws IOException {
        Path tools = Files.createDirectories(tempDir.resolve("tools"));
        Path occupied = Files.writeString(tools.resolve("ffmpeg"), "third-party");

        assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(occupied))
                .isInstanceOf(IOException.class)
                .hasMessage(MessageBundles.get("gui.ffmpeg.install.managed-not-directory", occupied));
        assertThat(occupied).hasContent("third-party");

        // 祖先节点是普通文件时同样拒绝，不把它当成可创建的缺失目录。
        Path ancestorFile = Files.writeString(tempDir.resolve("file-tools"), "third-party");
        assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(
                ancestorFile.resolve("ffmpeg")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(ancestorFile.toString());
        assertThat(ancestorFile).hasContent("third-party");
    }

    @Test
    @DisplayName("软件目录是链接或 Junction 时拒绝安装而不写入被指向的目录")
    void rejectsManagedDirectoryBehindReparsePoint(@TempDir Path tempDir) throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain/tools/ffmpeg"));
        FfmpegInstaller.requirePlainManagedDirectory(plain);
        FfmpegInstaller.requirePlainManagedDirectory(tempDir.resolve("plain/tools/ffmpeg/licenses"));

        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outsideDir.resolve("ffmpeg"), "third-party");
        Path junction = tempDir.resolve("linked-ffmpeg");
        Assumptions.assumeTrue(createJunction(junction, outsideDir), "当前 Windows 环境无法创建 Junction");
        try {
            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(junction))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(junction.toString());
            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(junction.resolve("licenses")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(junction.toString());
            assertThat(outsideFile).hasContent("third-party");
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    @DisplayName("已有祖先目录是 Junction 时拒绝安装，不顺着链接创建目录")
    void rejectsReparsePointInAncestorChain(@TempDir Path tempDir) throws Exception {
        Path realTools = Files.createDirectories(tempDir.resolve("real-tools"));
        Path linkTools = tempDir.resolve("tools");
        Assumptions.assumeTrue(createJunction(linkTools, realTools), "当前 Windows 环境无法创建 Junction");
        try {
            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(
                    linkTools.resolve("ffmpeg")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(linkTools.toString());
            assertThat(realTools.resolve("ffmpeg")).doesNotExist();

            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(linkTools))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(linkTools.toString());
        } finally {
            Files.deleteIfExists(linkTools);
        }
    }

    @Test
    @DisplayName("软件目录是目录符号链接时拒绝安装，且文案区分于 Junction")
    void rejectsManagedDirectoryBehindDirectorySymlink(@TempDir Path tempDir) throws Exception {
        Path realTools = Files.createDirectories(tempDir.resolve("real/tools/ffmpeg"));
        Path outsideFile = Files.writeString(realTools.resolve("ffmpeg"), "third-party");

        Path symlink = tempDir.resolve("linked-ffmpeg");
        Assumptions.assumeTrue(createDirectorySymlink(symlink, realTools),
                "当前环境无法创建目录符号链接");

        try {
            // 目标本身就是目录符号链接：必须拒绝，且文案只说符号链接、不混入 Junction。
            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(symlink))
                    .isInstanceOf(IOException.class)
                    .hasMessage(MessageBundles.get("gui.ffmpeg.install.managed-symbolic-link", symlink));

            // 祖先链上的目录符号链接：同样拒绝，并把出问题的层级如实报出来。
            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(
                    symlink.resolve("tools").resolve("ffmpeg")))
                    .isInstanceOf(IOException.class)
                    .hasMessage(MessageBundles.get("gui.ffmpeg.install.managed-symbolic-link", symlink));

            assertThat(realTools.resolve("tools")).doesNotExist();
            assertThat(outsideFile).hasContent("third-party");
        } finally {
            Files.deleteIfExists(symlink);
        }
    }

    @Test
    @DisplayName("含空格、括号与中文的目录符号链接同样被拒绝")
    void rejectsDirectorySymlinkWithAwkwardPathSegments(@TempDir Path tempDir) throws Exception {
        Path realTools = Files.createDirectories(tempDir.resolve("真实 tools (v1)/ffmpeg"));
        Path outsideFile = Files.writeString(realTools.resolve("ffmpeg"), "third-party");
        Path symlink = tempDir.resolve("链接 tools (v2)");
        Assumptions.assumeTrue(createDirectorySymlink(symlink, realTools),
                "当前环境无法创建目录符号链接");

        try {
            assertThatThrownBy(() -> FfmpegInstaller.requirePlainManagedDirectory(symlink))
                    .isInstanceOf(IOException.class)
                    .hasMessage(MessageBundles.get("gui.ffmpeg.install.managed-symbolic-link", symlink));
            assertThat(outsideFile).hasContent("third-party");
        } finally {
            Files.deleteIfExists(symlink);
        }
    }

    /**
     * 创建目录符号链接（{@code mklink /D} 的等价物）。
     *
     * <p>Windows 上创建符号链接可能「报告成功但磁盘上没有条目」，因此不能只看返回值，
     * 必须回读确认链接真的存在；确认不了就返回 {@code false}，由调用方跳过该用例。
     */
    private static boolean createDirectorySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            return false;
        }
        return Files.isSymbolicLink(link);
    }

    private static boolean createJunction(Path link, Path target) throws IOException, InterruptedException {
        if (!FfmpegLocator.isWindows()) {
            return false;
        }
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
        return process.waitFor() == 0;
    }

    private static void assertAsset(String osName, String osArch, String asset) {
        assertThat(FfmpegInstaller.assetName(osName, osArch)).contains(asset);
    }

    private static void addEntry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(name.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] manifest(String assetName, byte[] archiveBytes) throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "assets": {
                    "%s": {
                      "expectedSizeBytes": %d,
                      "sha256": "%s"
                    }
                  }
                }
                """.formatted(assetName, archiveBytes.length, sha256(archiveBytes));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(String value) throws Exception {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] signature(String keyId, String value) throws Exception {
        return MAPPER.writeValueAsBytes(new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519,
                keyId,
                value));
    }

    private static final class SigningSupport {
        private final String keyId = "test-ffmpeg-root";
        private final KeyPair keyPair;
        private final PluginSupplyChainVerifier verifier;

        private SigningSupport() throws Exception {
            keyPair = KeyPairGenerator.getInstance(SignatureMetadata.ED25519).generateKeyPair();
            TrustedPluginKey key = new TrustedPluginKey(
                    keyId,
                    SignatureMetadata.ED25519,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE,
                    "test",
                    "test FFmpeg root",
                    true);
            verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        }

        private PluginSupplyChainVerifier verifier() {
            return verifier;
        }

        private byte[] signature(byte[] manifest) throws Exception {
            byte[] envelope = EnvelopeV1Codec.manifestMessage(
                    FfmpegInstaller.RELEASE_REPOSITORY_ID,
                    manifest.length,
                    MessageDigest.getInstance("SHA-256").digest(manifest));
            Signature signer = Signature.getInstance(SignatureMetadata.ED25519);
            signer.initSign(keyPair.getPrivate());
            signer.update(envelope);
            return MAPPER.writeValueAsBytes(new SignatureMetadata(
                    SignatureMetadata.FORMAT_VERSION,
                    SignatureMetadata.ED25519,
                    keyId,
                    Base64.getEncoder().encodeToString(signer.sign())));
        }
    }
}
