package top.sywyar.pixivdownload.plugin.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PluginRegistry 精确身份提交临界区")
class PluginRegistryCommitIdentityTest {

    @Test
    @DisplayName("最终身份复核到下游提交之间 remove 不能插入")
    void removalCannotInterleaveIdentityCheckAndCommit() throws Exception {
        PluginRegistry registry = new PluginRegistry(List.of(new TestPlugin()));
        PluginRegistry.RegisteredPlugin registered = registry.registeredPlugins().get(0);
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch removerStarted = new CountDownLatch(1);
        CountDownLatch removalFinished = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger commitOrder = new AtomicInteger();
        AtomicInteger removalOrder = new AtomicInteger();

        Thread commit = new Thread(() -> registry.commitIfActiveIdentity(registered, () -> {
            commitEntered.countDown();
            await(releaseCommit);
            commitOrder.set(sequence.incrementAndGet());
            return null;
        }), "identity-bound-commit");
        commit.start();
        assertThat(commitEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Thread remover = new Thread(() -> {
            removerStarted.countDown();
            registry.unregister(registered.id());
            removalOrder.set(sequence.incrementAndGet());
            removalFinished.countDown();
        }, "concurrent-plugin-removal");
        remover.start();
        assertThat(removerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(removalFinished.await(150, TimeUnit.MILLISECONDS))
                .as("remove 必须等待精确身份提交临界区")
                .isFalse();

        releaseCommit.countDown();
        commit.join(5000);
        remover.join(5000);

        assertThat(commit.isAlive()).isFalse();
        assertThat(remover.isAlive()).isFalse();
        assertThat(commitOrder.get()).isEqualTo(1);
        assertThat(removalOrder.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("身份提交令牌离开回调栈即失效")
    void activeIdentityCommitAuthorityExpiresAfterCallback() {
        PluginRegistry registry = new PluginRegistry(List.of(new TestPlugin()));
        PluginRegistry.RegisteredPlugin registered = registry.registeredPlugins().get(0);

        PluginRegistry.ActiveIdentityCommit expired =
                registry.commitIfActiveIdentity(registered, commit -> commit);

        assertThatThrownBy(() -> registry.requireActiveIdentityCommit(expired, registered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid plugin active identity commit authority");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "identity-owner"; }
        @Override public String displayName() { return "identity-owner.name"; }
        @Override public String description() { return "identity-owner.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
    }
}
