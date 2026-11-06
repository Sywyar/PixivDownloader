package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("历史任务排队撤回")
class HistoryTaskTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("句柄绑定前后撤回都交付业务取消与清理，空包装器不再执行正文")
    void cancelsQueuedAndLateBoundTasks(boolean beforeBinding) {
        var registry = new PluginRuntimeTaskRegistry();
        var owner = registry.registrarForPlugin("history");
        var queue = new ArrayList<Runnable>();
        var ran = new AtomicInteger();
        var cancelled = new AtomicInteger();
        var cleaned = new AtomicInteger();
        HistoryTask.submit(owner, task -> {
            queue.add(task);
            if (beforeBinding) {
                var drain = registry.prepareQuiesce("history");
                registry.cancelQuiescedTasks("history", drain);
                assertFalse(drain.isDrained());
            }
        }, ran::incrementAndGet, cancelled::incrementAndGet, cleaned::incrementAndGet);
        var drain = registry.prepareQuiesce("history");
        registry.cancelQuiescedTasks("history", drain);
        assertTrue(drain.isDrained());
        queue.getFirst().run();
        assertEquals(0, ran.get());
        assertEquals(1, cancelled.get());
        assertEquals(1, cleaned.get());
    }
}
