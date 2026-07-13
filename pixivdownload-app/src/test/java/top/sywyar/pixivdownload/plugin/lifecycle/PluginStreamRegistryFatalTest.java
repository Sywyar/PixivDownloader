package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("插件推流关闭 claim 致命错误回滚")
class PluginStreamRegistryFatalTest {

    @Test
    @DisplayName("close claim 后的 OOME 与 ThreadDeath 不会遗留 closeInProgress")
    void fatalAfterCloseClaimRemainsRetryable() {
        for (Error expected : new Error[]{new OutOfMemoryError("stream-fatal"), new ThreadDeath()}) {
            AtomicReference<Error> nextFailure = new AtomicReference<>(expected);
            PluginStreamRegistry registry = new PluginStreamRegistry(() -> {
                Error failure = nextFailure.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
            });
            AtomicInteger closes = new AtomicInteger();
            registry.register("ext-demo", "stream", closes::incrementAndGet);

            assertThat(catchThrowable(() -> registry.closeForPlugin("ext-demo"))).isSameAs(expected);
            assertThat(closes).hasValue(0);
            assertThat(registry.activeStreamCount("ext-demo")).isOne();

            assertThat(registry.closeForPlugin("ext-demo")).isOne();
            assertThat(closes).hasValue(1);
            assertThat(registry.activeStreamCount("ext-demo")).isZero();
        }
    }
}
