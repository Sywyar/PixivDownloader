package top.sywyar.pixivdownload.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliSetupCommandTest {

    @Test
    @DisplayName("首次配置使用短密码时空回车确认并允许重新输入")
    void confirmsOrReplacesPasswordBelowRecommendation() {
        assertEquals("12345678", CliSetupCommand.confirmSetupPassword("12345678", () -> ""));
        assertEquals("123456789012",
                CliSetupCommand.confirmSetupPassword("12345678", () -> "123456789012"));

        AtomicInteger prompts = new AtomicInteger();
        assertEquals("abcdefgh",
                CliSetupCommand.confirmSetupPassword("12345678",
                        () -> prompts.getAndIncrement() == 0 ? "abcdefgh" : ""));
        assertEquals(2, prompts.get());
        assertNull(CliSetupCommand.confirmSetupPassword("12345678", () -> null));

        AtomicInteger strongPrompts = new AtomicInteger();
        assertEquals("123456789012",
                CliSetupCommand.confirmSetupPassword("123456789012", () -> {
                    strongPrompts.incrementAndGet();
                    return "";
                }));
        assertEquals(0, strongPrompts.get());
    }
}
