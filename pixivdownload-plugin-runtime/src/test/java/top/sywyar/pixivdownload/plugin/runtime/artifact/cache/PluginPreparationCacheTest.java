package top.sywyar.pixivdownload.plugin.runtime.artifact.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.artifact.*;
import top.sywyar.pixivdownload.plugin.runtime.cache.PluginCacheAccess;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.*;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件准备缓存独占复用与失效")
class PluginPreparationCacheTest {
    @TempDir Path temporary;
    private Path artifact, cacheRoot;
    private PluginRuntimeLayout layout;

    @BeforeEach
    void prepare() throws IOException {
        temporary = temporary.toRealPath();
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        artifact = plugins.resolve("probe.jar");
        writeArtifact("first");
        layout = new PluginRuntimeLayout(plugins);
        cacheRoot = temporary.resolve("prepared");
    }

    @AfterEach
    void cleanPrivateCache() { assertThat(PluginArtifactSnapshot.deleteWorkspaceQuietly(temporary)).isTrue(); }

    @Test
    @DisplayName("新缓存实例复用相同文件且活动代际保持独占")
    void reusesFilesWithoutSharingLiveGenerations() throws Exception {
        var cold = new PluginPreparationCache(cacheRoot);
        Path originalLink;
        Path oldWorkspace;
        try (var snapshot = prepare(cold)) {
            oldWorkspace = snapshot.snapshotArtifact().getParent();
            Path file = oldWorkspace.resolve("load/classes/content.txt");
            var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
            var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            var permissions = posix == null ? null : posix.readAttributes().permissions();
            var entries = acl == null ? null : acl.getAcl();
            try {
                // Windows 创建硬链接需要写权限；只在夹具中临时放开，随后恢复原封存权限。
                makeWritable(file);
                originalLink = Files.createLink(temporary.resolve("original-file-link"), file);
            } finally {
                if (posix != null) posix.setPermissions(permissions);
                if (acl != null) acl.setAcl(entries);
            }
        }
        assertThat(oldWorkspace).doesNotExist();
        var warm = new PluginPreparationCache(cacheRoot);
        try (var first = prepare(warm); var second = prepare(warm)) {
            assertThat(warm.statistics().hits()).isEqualTo(1);
            Path firstFile = first.snapshotArtifact().getParent().resolve("load/classes/content.txt");
            Path secondFile = second.snapshotArtifact().getParent().resolve("load/classes/content.txt");
            assertThat(Files.isSameFile(firstFile, originalLink)).isTrue();
            assertThat(Files.isSameFile(secondFile, originalLink)).isFalse();
            assertThat(firstFile).hasContent("first");
            assertThat(firstFile.getParent()).isNotEqualTo(oldWorkspace.resolve("load/classes"));
        }
        assertThat(warm.statistics().returns()).isEqualTo(2);
    }

    @Test
    @DisplayName("准备缓存移入后被改写的原包仍在物化时拒绝")
    void rejectsSnapshotMutationAfterCacheRestore() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        try (var ignored = prepare(cache)) { }
        try (var restored = PluginArtifactSnapshot.create(layout, artifact,
                PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES, cache)) {
            assertThat(cache.statistics().hits()).isEqualTo(1);
            var inspection = PluginPackageReader.inspect(restored.snapshotArtifact());
            String verified = PluginPackageIntegrity.sha256Hex(restored.snapshotArtifact());
            makeWritable(restored.snapshotArtifact());
            byte[] changed = Files.readAllBytes(restored.snapshotArtifact());
            changed[changed.length - 1] ^= 1;
            Files.write(restored.snapshotArtifact(), changed);
            assertThatThrownBy(() -> new PluginArtifactMaterializer(layout).materialize(restored, inspection, verified))
                    .isInstanceOf(PluginRuntimeOperationException.class)
                    .rootCause().isInstanceOf(IOException.class).hasMessageContaining("snapshot changed");
        }
    }

    @Test
    @DisplayName("独立 JVM 复用上个进程释放的准备结果")
    void reusesAcrossJvm() throws Exception {
        try (var ignored = prepare(new PluginPreparationCache(cacheRoot))) { }
        Path output = temporary.resolve("child.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Child.class.getName(), artifact.toString(), cacheRoot.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).contains("PREPARATION_HITS=1");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    public static class Child {
        public static void main(String[] args) throws IOException {
            var cache = new PluginPreparationCache(Path.of(args[1]));
            Path artifact = Path.of(args[0]);
            try (var snapshot = PluginArtifactSnapshot.create(new PluginRuntimeLayout(artifact.getParent()), artifact,
                    PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES, cache)) {
                materialize(snapshot, new PluginRuntimeLayout(artifact.getParent()));
                System.out.println("PREPARATION_HITS=" + cache.statistics().hits());
            }
        }
    }

    @Test
    @DisplayName("同大小同修改时间的包替换不命中旧准备结果")
    void invalidatesChangedSource() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        try (var ignored = prepare(cache)) { }
        var time = Files.getLastModifiedTime(artifact);
        writeArtifact("other"); Files.setLastModifiedTime(artifact, time);
        try (var next = prepare(cache)) {
            assertThat(cache.statistics().hits()).isZero();
            assertThat(next.snapshotArtifact().getParent().resolve("load/classes/content.txt")).hasContent("other");
        }
    }

    @Test
    @DisplayName("损坏的加载树和认证清单回退重建，缓存不信任旧标记")
    void repairsDamagedImages() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        try (var ignored = prepare(cache)) { }
        Path ready = cacheRoot.resolve(cache.identity(artifact, false));
        Path contents = ready.resolve("load/classes/content.txt");
        makeWritable(contents);
        Files.writeString(contents, "evil!");
        try (var snapshot = prepare(cache)) {
            assertThat(snapshot.snapshotArtifact().getParent().resolve("load/classes/content.txt")).hasContent("first");
        }
        Files.write(ready.resolve(".preparation-cache"), new byte[]{1});
        try (var ignored = prepare(cache)) { }
        try (var ignored = prepare(cache)) { }
        assertThat(cache.statistics().hits()).isEqualTo(1);
        assertThat(cache.statistics().failures()).isEqualTo(2);
    }

    @Test
    @DisplayName("运行期间改变准备树不能把修改后的代码发布回缓存")
    void refusesMutatedGenerationOnReturn() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        try (var snapshot = prepare(cache)) {
            Path file = snapshot.snapshotArtifact().getParent().resolve("load/classes/content.txt");
            makeWritable(file);
            Files.writeString(file, "evil!");
        }
        assertThat(cache.statistics().returns()).isZero();
        try (var snapshot = prepare(cache)) {
            assertThat(cache.statistics().hits()).isZero();
            assertThat(snapshot.snapshotArtifact().getParent().resolve("load/classes/content.txt")).hasContent("first");
        }
    }

    @Test
    @DisplayName("锁竞争和缓存路径不可用时继续正常准备插件")
    void unavailableCacheFallsBack() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        try (var ignored = prepare(cache)) { }
        try (var channel = FileChannel.open(cacheRoot.resolve("cache.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock(); var snapshot = prepare(cache)) {
            assertThat(snapshot.snapshotArtifact()).isRegularFile();
            assertThat(cache.statistics().hits()).isZero();
        }
        Path unavailable = Files.writeString(temporary.resolve("not-a-directory"), "keep");
        try (var snapshot = prepare(new PluginPreparationCache(unavailable))) {
            assertThat(snapshot.snapshotArtifact()).isRegularFile();
        }
        assertThat(unavailable).hasContent("keep");
    }

    @Test
    @DisplayName("开发快照复用真实文件且源内容及私有库变化使缓存失效")
    void developmentSnapshotReuseAndInvalidation() throws Exception {
        Path classes = Files.createDirectories(temporary.resolve("module/target/classes"));
        try (var zip = new ZipFile(artifact.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement(); Path output = classes.resolve(entry.getName());
                Files.createDirectories(output.getParent());
                try (var input = zip.getInputStream(entry)) { Files.copy(input, output); }
            }
        }
        var source = new PluginDevelopmentArtifacts.DevelopmentPluginArtifact(temporary.resolve("module"), classes,
                classes.resolve("plugin.properties"));
        var cache = new PluginPreparationCache(cacheRoot);
        Path sessions = temporary.resolve("sessions");
        Path originalLink;
        try (var session = PluginDevelopmentArtifacts.openSession(sessions, cache)) {
            Path snapshot = PluginDevelopmentArtifacts.materialize(source, session).pf4jLoadPath();
            originalLink = Files.createLink(temporary.resolve("development-file-link"), snapshot.resolve("classes/content.txt"));
        }
        try (var session = PluginDevelopmentArtifacts.openSession(sessions, cache)) {
            Path snapshot = PluginDevelopmentArtifacts.materialize(source, session).pf4jLoadPath();
            assertThat(Files.isSameFile(snapshot.resolve("classes/content.txt"), originalLink)).isTrue();
        }
        assertThat(cache.statistics().hits()).isEqualTo(1);
        assertThat(cache.statistics().returns()).isEqualTo(2);
        assertThat(cache.statistics().failures()).isZero();
        var time = Files.getLastModifiedTime(classes.resolve("content.txt"));
        Files.writeString(classes.resolve("content.txt"), "other"); Files.setLastModifiedTime(classes.resolve("content.txt"), time);
        try (var session = PluginDevelopmentArtifacts.openSession(sessions, cache)) {
            assertThat(PluginDevelopmentArtifacts.materialize(source, session).pf4jLoadPath().resolve("classes/content.txt")).hasContent("other");
        }
        Files.write(classes.resolve("lib/private.jar"), PluginPackageFixtures.zipBytes(Map.of("marker", new byte[]{2})));
        try (var session = PluginDevelopmentArtifacts.openSession(sessions, cache)) {
            PluginDevelopmentArtifacts.materialize(source, session);
        }
        assertThat(cache.statistics().hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("数量预算淘汰空闲旧条目且不删除工作区")
    void evictsIdleEntries() throws Exception {
        fillIdleEntries(PluginPreparationCache.MAX_ENTRIES, 0);
        returnSmallImage();
        assertThat(cacheRoot.resolve("%064x".formatted(1))).doesNotExist();
        try (var entries = Files.list(cacheRoot)) {
            assertThat(entries.filter(Files::isDirectory).count()).isEqualTo(PluginPreparationCache.MAX_ENTRIES);
        }
    }

    @Test
    @DisplayName("裸 JAR 和目录 ZIP 都可以复用准备结果")
    void supportsBothOtherArchiveLayouts() throws Exception {
        for (boolean exploded : new boolean[]{false, true}) {
            artifact = layout.pluginsRoot().resolve(exploded ? "directory.zip" : "thin.jar");
            byte[] jar = PluginPackageFixtures.pluginJarBytes("probe", "4.2.0", "1.0", "com.example.Probe",
                    Map.of("content.txt", new byte[]{1}));
            if (exploded) {
                Path descriptorSource = Files.write(temporary.resolve("descriptor-source.jar"), jar);
                try (var zip = new ZipFile(descriptorSource.toFile()); var input = zip.getInputStream(zip.getEntry("plugin.properties"))) {
                    Files.write(artifact, PluginPackageFixtures.zipBytes(Map.of(
                            "plugin.properties", input.readAllBytes(), "classes/content.txt", new byte[]{1})));
                }
            } else Files.write(artifact, jar);
            var cache = new PluginPreparationCache(cacheRoot);
            try (var ignored = prepare(cache)) { }
            try (var ignored = prepare(cache)) { }
            assertThat(cache.statistics().hits()).isEqualTo(1);
            assertThat(cache.statistics().failures()).isZero();
        }
    }

    @Test
    @DisplayName("仍有活动引用时不得把工作区归还缓存")
    void physicalOwnerControlsReturn() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        var snapshot = prepare(cache);
        Path workspace = snapshot.snapshotArtifact().getParent();
        var owner = new PluginArtifactWorkspaceOwner(layout);
        owner.release(snapshot, java.util.List.of(snapshot));
        assertThat(workspace).isDirectory();
        assertThat(cache.statistics().returns()).isZero();
        owner.release(snapshot, java.util.List.of());
        assertThat(workspace).doesNotExist();
        assertThat(cache.statistics().returns()).isEqualTo(1);
    }

    @Test
    @DisplayName("验证限制变化后必须重新准备")
    void invalidatesChangedLimits() throws Exception {
        var first = new PluginPreparationCache(cacheRoot);
        try (var ignored = prepare(first)) { }
        String property = "pixivdownload.plugin.package.max-entries";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "40000");
            var changed = new PluginPreparationCache(cacheRoot);
            try (var ignored = prepare(changed)) { }
            assertThat(changed.statistics().hits()).isZero();
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
        try (var ignored = prepare(first)) { }
        assertThat(first.statistics().hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("空闲缓存根变成只读后仍能创建当前运行归属标记")
    void restoresWorkspaceRootPermissions() throws Exception {
        var cache = new PluginPreparationCache(cacheRoot);
        try (var ignored = prepare(cache)) { }
        Path ready = cacheRoot.resolve(cache.identity(artifact, false));
        var posix = Files.getFileAttributeView(ready, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        } else {
            var acl = Files.getFileAttributeView(ready, java.nio.file.attribute.AclFileAttributeView.class);
            var sealed = Files.getFileAttributeView(ready.resolve("load"), java.nio.file.attribute.AclFileAttributeView.class);
            acl.setAcl(sealed.getAcl());
        }
        try (var ignored = prepare(cache)) { }
        assertThat(cache.statistics().hits()).isEqualTo(1);
        assertThat(cache.statistics().failures()).isZero();
    }

    @Test
    @DisplayName("累计字节预算按已认证清单计量并淘汰旧条目")
    void evictsByTotalBytes() throws Exception {
        // 用认证清单模拟大树的已计量容量，避免为算术边界反复创建数 GiB 的测试文件。
        fillIdleEntries(2, PluginPreparationCache.MAX_IMAGE_BYTES);
        returnSmallImage();
        assertThat(cacheRoot.resolve("%064x".formatted(1))).doesNotExist();
        assertThat(cacheRoot.resolve("%064x".formatted(2))).isDirectory();
    }

    private void returnSmallImage() throws IOException {
        var cache = new PluginPreparationCache(cacheRoot);
        Path input = Files.writeString(temporary.resolve("source"), "input");
        Path directory = Files.createDirectory(temporary.resolve("incoming"));
        Files.writeString(directory.resolve("contents"), "value");
        var ticket = cache.remember(cache.identity(input, false), directory);
        assertThat(cache.release(ticket, directory)).isTrue();
        assertThat(input).hasContent("input");
    }

    private void fillIdleEntries(int count, long measuredBytes) throws IOException {
        var access = new PluginCacheAccess(cacheRoot);
        access.locked(key -> {
            for (int i = 1; i <= count; i++) {
                String identity = "%064x".formatted(i);
                Path directory = Files.createDirectory(cacheRoot.resolve(identity));
                access.makePrivate(directory, true);
                var bytes = new java.io.ByteArrayOutputStream();
                try (var data = new java.io.DataOutputStream(bytes)) {
                    data.writeUTF(identity);
                    data.writeUTF(PreparationFingerprint.capture(directory, false, true, access, 1, 1).sha256());
                    data.writeLong(measuredBytes);
                }
                Path manifest = directory.resolve(".preparation-cache");
                try (var output = Files.newOutputStream(manifest)) {
                    output.write(bytes.toByteArray()); output.write(PluginCacheAccess.authenticate(key, bytes.toByteArray()));
                }
                access.makePrivate(manifest, false);
                Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(i * 1000L));
            }
            return true;
        });
    }

    private void makeWritable(Path file) throws IOException {
        var access = new PluginCacheAccess(cacheRoot);
        access.locked(key -> { access.makePrivate(file, false); return true; });
    }

    private void writeArtifact(String content) throws IOException {
        Files.write(artifact, PluginPackageFixtures.pluginJarBytes("probe", "4.2.0", "1.0", "com.example.Probe",
                Map.of("content.txt", content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "lib/private.jar", PluginPackageFixtures.zipBytes(Map.of("marker", new byte[]{1})))));
    }

    private PluginArtifactSnapshot prepare(PluginPreparationCache cache) throws IOException {
        var snapshot = PluginArtifactSnapshot.create(layout, artifact, PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES, cache);
        try { materialize(snapshot, layout); return snapshot; }
        catch (RuntimeException | IOException failure) { snapshot.close(); throw failure; }
    }

    private static void materialize(PluginArtifactSnapshot snapshot, PluginRuntimeLayout layout) throws IOException {
        var materialized = new PluginArtifactMaterializer(layout).materialize(snapshot,
                PluginPackageReader.inspect(snapshot.snapshotArtifact()), PluginPackageIntegrity.sha256Hex(snapshot.snapshotArtifact()));
        snapshot.verifyLoadPath(materialized.pf4jLoadPath());
    }

}
