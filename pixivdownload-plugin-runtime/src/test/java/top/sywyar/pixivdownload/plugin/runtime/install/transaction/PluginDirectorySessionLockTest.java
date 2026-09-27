package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件目录会话锁")
class PluginDirectorySessionLockTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("同一 JVM 的第二会话必须等第一会话关闭后才能取得同一目录")
    void serializesSessionsInTheSameProcess() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        PluginDirectorySessionLock first = new PluginDirectorySessionLock(plugins);
        PluginDirectorySessionLock second = new PluginDirectorySessionLock(plugins);
        try {
            first.acquireForMutation();

            assertThatThrownBy(second::acquireIfRootExists)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("already owned");

            first.close();
            assertThat(second.acquireIfRootExists()).isTrue();
            assertThat(second.held()).isTrue();
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    @DisplayName("候选锁取得后抛 JVM 致命错误必须释放文件锁与 JVM owner 并原样重抛")
    void fatalFailureAfterCandidateLocksReleasesPartialLease() throws Exception {
        Path plugins = temporaryDirectory.resolve("fatal-after-candidate-locks");
        OutOfMemoryError fatal = new OutOfMemoryError("fatal after candidate locks");
        try (PluginDirectorySessionLock failed = new PluginDirectorySessionLock(plugins, () -> {
            throw fatal;
        })) {
            assertThatThrownBy(failed::acquireForMutation).isSameAs(fatal);
            assertThat(failed.held()).isFalse();

            try (PluginDirectorySessionLock replacement = new PluginDirectorySessionLock(plugins)) {
                replacement.acquireForMutation();
                assertThat(replacement.held()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("预置 hardlink 锁文件只被加锁且不会改写外部别名内容")
    void doesNotRewriteHardlinkedLockFile() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        Path canary = temporaryDirectory.resolve("canary.txt");
        byte[] expected = "external-canary".getBytes(StandardCharsets.US_ASCII);
        Files.write(canary, expected);
        Files.createLink(plugins.resolve(PluginDirectorySessionLock.LOCK_FILE_NAME), canary);

        try (PluginDirectorySessionLock sessionLock = new PluginDirectorySessionLock(plugins)) {
            assertThat(sessionLock.acquireIfRootExists()).isTrue();
        }

        assertThat(Files.readAllBytes(canary)).isEqualTo(expected);
    }

    @Test
    @DisplayName("持锁期间路径换绑后下一次入口必须拒绝旧 inode 租约")
    void rejectsLockPathReplacementWhileHeld() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        try (PluginDirectorySessionLock sessionLock = new PluginDirectorySessionLock(plugins)) {
            sessionLock.acquireForMutation();
            Path lockPath = sessionLock.lockPath();
            Path displaced = plugins.resolve("displaced-lock");
            Files.move(lockPath, displaced);
            Files.writeString(lockPath, "replacement", StandardCharsets.US_ASCII);

            assertThatThrownBy(sessionLock::acquireIfRootExists)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no longer names the locked file");
            assertThat(Files.readString(lockPath, StandardCharsets.US_ASCII)).isEqualTo("replacement");
        }
    }

    @Test
    @DisplayName("持锁期间创建修改和删除目录子项不改变目录租约")
    void acceptsChildChangesWhileHeld() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        try (PluginDirectorySessionLock sessionLock = new PluginDirectorySessionLock(plugins)) {
            sessionLock.acquireForMutation();
            Path child = Files.writeString(plugins.resolve("child"), "first", StandardCharsets.UTF_8);
            assertThat(sessionLock.acquireIfRootExists()).isTrue();
            Files.writeString(child, "second", StandardCharsets.UTF_8);
            assertThat(sessionLock.acquireIfRootExists()).isTrue();
            Files.delete(child);
            assertThat(sessionLock.acquireIfRootExists()).isTrue();
        }
    }

    @Test
    @DisplayName("替换 plugins 根并把原锁文件 hardlink 回原路径仍必须拒绝旧目录租约")
    void rejectsPluginRootReplacementEvenWhenOriginalLockFileIsHardlinkedBack() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        Path displacedLock = temporaryDirectory.resolve("displaced-runtime.lock");
        try (PluginDirectorySessionLock sessionLock = new PluginDirectorySessionLock(plugins)) {
            sessionLock.acquireForMutation();
            var created = Files.readAttributes(plugins, BasicFileAttributes.class).creationTime();
            Files.move(sessionLock.lockPath(), displacedLock);
            Files.delete(plugins);
            Files.createDirectory(plugins);
            Files.getFileAttributeView(plugins, BasicFileAttributeView.class).setTimes(null, null, created);
            Files.createLink(sessionLock.lockPath(), displacedLock);
            assertThat(Files.isSameFile(sessionLock.lockPath(), displacedLock)).isTrue();

            assertThatThrownBy(sessionLock::acquireIfRootExists)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("plugins root identity changed");
        }
    }

    @Test
    @DisplayName("原目录仍存在且新目录创建时间相同时也必须识别根目录换绑")
    void rejectsReplacementWhileOriginalDirectoryStillExists() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        Path displaced = temporaryDirectory.resolve("original-plugins");
        Path displacedLock = temporaryDirectory.resolve("displaced-runtime.lock");
        try (PluginDirectorySessionLock sessionLock = new PluginDirectorySessionLock(plugins)) {
            sessionLock.acquireForMutation();
            var original = Files.readAttributes(plugins, BasicFileAttributes.class);
            Files.move(sessionLock.lockPath(), displacedLock);
            Files.move(plugins, displaced);
            Files.createDirectory(plugins);
            Files.getFileAttributeView(plugins, BasicFileAttributeView.class)
                    .setTimes(null, null, original.creationTime());
            Files.createLink(sessionLock.lockPath(), displacedLock);
            if (original.fileKey() == null) {
                assertThat(Files.readAttributes(plugins, BasicFileAttributes.class).creationTime())
                        .isEqualTo(original.creationTime());
            }

            assertThatThrownBy(sessionLock::acquireIfRootExists)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("plugins root identity changed");
        }
    }
}
