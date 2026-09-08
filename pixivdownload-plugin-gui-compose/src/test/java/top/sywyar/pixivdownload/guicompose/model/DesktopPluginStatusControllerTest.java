package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 插件状态请求合并")
class DesktopPluginStatusControllerTest {
    @Test
    @DisplayName("磁盘校验未返回时合并并发刷新并在完成后允许读取新状态")
    void coalescesConcurrentRefreshesUntilResponseArrives() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    assertEquals("guiGet", method.getName());
                    assertEquals("plugins/status", arguments[0]);
                    assertTrue((int) arguments[1] >= 60_000, "完整插件包复验需要至少 60 秒读取预算");
                    int request = requests.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return new DesktopUiHost.GuiResponse(
                            true,
                            200,
                            DesktopUiHost.GuiValue.of(Map.of("plugins", List.of(Map.of(
                                    "id", "example",
                                    "status", request == 1 ? "STARTED" : "CRASHED"
                            )))),
                            "",
                            false
                    );
                }
        );
        DesktopPluginStatusController controller = new DesktopPluginStatusController(null, host);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var first = worker.submit(controller::load);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.load();
            assertEquals(1, requests.get());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, controller.count());
            assertEquals(1, controller.startedCount());
            controller.load();
            assertEquals(2, requests.get());
            assertEquals(0, controller.startedCount());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("请求异常后释放刷新占用以便下一次重试")
    void permitsRefreshAfterRequestFailure() {
        AtomicInteger requests = new AtomicInteger();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("message")) return arguments[0];
                    assertEquals("guiGet", method.getName());
                    if (requests.incrementAndGet() == 1) throw new IllegalStateException("request failed");
                    return DesktopUiHost.GuiResponse.unreachable();
                }
        );
        DesktopPluginStatusController controller = new DesktopPluginStatusController(null, host);
        assertThrows(IllegalStateException.class, controller::load);
        assertDoesNotThrow(controller::load);
        assertEquals(2, requests.get());
    }
}
