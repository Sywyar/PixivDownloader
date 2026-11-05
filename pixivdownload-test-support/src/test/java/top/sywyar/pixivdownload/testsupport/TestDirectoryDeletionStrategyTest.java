package top.sywyar.pixivdownload.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@DisplayName("JUnit 临时目录策略接入与失败传播")
class TestDirectoryDeletionStrategyTest {
    @Test
    @DisplayName("全局策略在资源关闭后清理静态字段、实例字段和方法参数目录")
    void honorsNativeLifecycle() throws Exception {
        LifecycleFixture.afterEach = false;
        LifecycleFixture.afterAll = false;
        try (var cleanup = mockStatic(TestDirectoryCleanup.class, CALLS_REAL_METHODS)) {
            cleanup.when(() -> TestDirectoryCleanup.delete(any(Path.class))).thenAnswer(call -> {
                Path path = call.getArgument(0);
                assertThat(LifecycleFixture.afterEach).isTrue();
                if (path.equals(LifecycleFixture.shared)) assertThat(LifecycleFixture.afterAll).isTrue();
                return call.callRealMethod();
            });
            TestExecutionSummary result = run(LifecycleFixture.class);
            assertThat(result.getTestsSucceededCount()).isEqualTo(1);
            assertThat(result.getFailures()).isEmpty();
            for (Path path : new Path[]{LifecycleFixture.shared, LifecycleFixture.instance,
                    LifecycleFixture.parameter}) {
                assertThat(path).doesNotExist();
                cleanup.verify(() -> TestDirectoryCleanup.delete(path));
            }
        }
    }

    @Test
    @DisplayName("清理权限错误使原生测试执行失败且保留原始异常")
    void propagatesCleanupFailure() throws Exception {
        var denied = new AccessDeniedException("fixture");
        try {
            try (var cleanup = mockStatic(TestDirectoryCleanup.class)) {
                cleanup.when(() -> TestDirectoryCleanup.delete(any(Path.class))).thenThrow(denied);
                TestExecutionSummary result = run(FailureFixture.class);
                assertThat(result.getFailures()).anySatisfy(failure -> {
                    Throwable cause = failure.getException();
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertThat(cause).isSameAs(denied);
                });
                assertThat(FailureFixture.directory.resolve("retained")).hasContent("fixture");
            }
        } finally {
            if (FailureFixture.directory != null) TestDirectoryCleanup.delete(FailureFixture.directory);
        }
    }

    private static TestExecutionSummary run(Class<?> fixture) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(fixture))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build();
        LauncherFactory.create().execute(request, listener);
        return listener.getSummary();
    }

    static class LifecycleFixture {
        @TempDir static Path shared;
        @TempDir Path directory;
        static Path instance;
        static Path parameter;
        static boolean afterEach;
        static boolean afterAll;
        FileChannel channel;

        @Test
        @DisplayName("创建各作用域的临时资源")
        void create(@TempDir Path argument) throws Exception {
            instance = directory;
            parameter = argument;
            Files.writeString(shared.resolve("shared"), "fixture");
            Files.writeString(argument.resolve("parameter"), "fixture");
            channel = FileChannel.open(directory.resolve("open"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
        }

        @AfterEach
        void close() throws Exception {
            channel.close();
            assertThat(directory.resolve("open")).exists();
            afterEach = true;
        }

        @AfterAll
        static void finish() {
            assertThat(shared.resolve("shared")).exists();
            afterAll = true;
        }
    }

    static class FailureFixture {
        static Path directory;

        @Test
        @DisplayName("保留无法删除的资源")
        void create(@TempDir Path argument) throws Exception {
            directory = argument;
            Files.writeString(argument.resolve("retained"), "fixture");
        }
    }
}
