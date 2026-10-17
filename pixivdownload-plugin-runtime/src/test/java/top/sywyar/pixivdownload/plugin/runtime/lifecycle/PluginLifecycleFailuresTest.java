package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleFailures.addSuppressedSafely;
import static top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleFailures.mergeFailure;

@DisplayName("插件生命周期失败聚合")
class PluginLifecycleFailuresTest {

    @Test
    @DisplayName("无失败和重复失败不产生额外诊断")
    void absentAndIdenticalFailuresKeepIdentity() {
        IOException failure = new IOException("read");
        assertThat(mergeFailure(null, null)).isNull();
        assertThat(mergeFailure(null, failure)).isSameAs(failure);
        assertThat(mergeFailure(failure, null)).isSameAs(failure);
        assertThat(mergeFailure(failure, failure)).isSameAs(failure);
        assertThat(failure.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("普通 Error 不覆盖首个失败且后续诊断保持顺序")
    void ordinaryFailuresKeepFirstAndSuppressedOrder() {
        IOException first = new IOException("read");
        AssertionError second = new AssertionError("assertion");
        IllegalStateException third = new IllegalStateException("close");
        assertThat(mergeFailure(first, second)).isSameAs(first);
        assertThat(mergeFailure(first, third)).isSameAs(first);
        assertThat(first.getSuppressed()).containsExactly(second, third);
    }

    @Test
    @DisplayName("首个 JVM 致命错误优先且保留先前诊断和后续致命错误")
    void firstFatalPreservesEarlierAndLaterFailures() {
        for (Error fatal : List.of(new OutOfMemoryError("fatal"), new ThreadDeath())) {
            IOException first = new IOException("read");
            IllegalStateException second = new IllegalStateException("close");
            Error laterFatal = new OutOfMemoryError("later");
            Throwable primary = mergeFailure(first, second);
            primary = mergeFailure(primary, fatal);
            primary = mergeFailure(primary, laterFatal);

            assertThat(primary).isSameAs(fatal);
            assertThat(fatal.getSuppressed()).containsExactly(first, laterFatal);
            assertThat(first.getSuppressed()).containsExactly(second);
        }
    }

    @Test
    @DisplayName("禁用 suppressed 的失败仍保持主失败身份")
    void disabledSuppressionDoesNotReplaceFailure() {
        Throwable primary = new Throwable("no suppression", null, false, false) { };
        assertThat(mergeFailure(primary, new IOException("close"))).isSameAs(primary);
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("附加诊断允许缺席值和同一异常")
    void optionalDiagnosticsIgnoreAbsentAndIdenticalFailures() {
        Throwable failure = new IOException("read");
        addSuppressedSafely(null, failure);
        addSuppressedSafely(failure, null);
        addSuppressedSafely(failure, failure);
        assertThat(failure.getSuppressed()).isEmpty();
    }
}
