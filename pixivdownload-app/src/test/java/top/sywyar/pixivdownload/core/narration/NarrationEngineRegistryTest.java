package top.sywyar.pixivdownload.core.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceMode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("朗读引擎注册工厂（唯一 id / byId / selected）")
class NarrationEngineRegistryTest {

    @Test
    @DisplayName("状态发布失败时引擎 owner 与可见快照保持原子一致")
    void statePublicationFailureKeepsOwnerAndSnapshotAtomic() {
        AtomicReference<Throwable> nextFailure = new AtomicReference<>();
        NarrationEngineRegistry registry = new NarrationEngineRegistry(
                List.of(), () -> throwPending(nextFailure));
        NarrationVoiceEngine first = engine("first");
        NarrationVoiceEngine second = engine("second");
        registry.registerPrepared("owner-a", 1L, List.of(
                new NarrationEngineRegistry.PreparedEngine("first", first, "first.Type")));
        List<NarrationVoiceEngine> beforePublish = registry.all();

        for (Throwable expected : failures("publish")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.registerPrepared("owner-b", 2L, List.of(
                    new NarrationEngineRegistry.PreparedEngine("second", second, "second.Type")))))
                    .isSameAs(expected);
            assertThat(registry.all()).isSameAs(beforePublish);
            assertThat(registry.byId("second")).isEmpty();
        }

        registry.registerPrepared("owner-b", 2L, List.of(
                new NarrationEngineRegistry.PreparedEngine("second", second, "second.Type")));
        List<NarrationVoiceEngine> beforeWithdraw = registry.all();
        for (Throwable expected : failures("withdraw")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.unregisterPrepared("owner-b", 2L))).isSameAs(expected);
            assertThat(registry.all()).isSameAs(beforeWithdraw);
            assertThat(registry.byId("second")).containsSame(second);
        }
        registry.unregisterPrepared("owner-b", 2L);
        assertThat(registry.all()).containsExactly(first);
    }

    @Test
    @DisplayName("byId / selected：按 id 大小写不敏感取引擎，未知 / 空返回空")
    void byIdResolvesCaseInsensitively() {
        NarrationVoiceEngine voxcpm = engine("voxcpm");
        NarrationEngineRegistry registry = new NarrationEngineRegistry(List.of(voxcpm));

        assertThat(registry.byId("voxcpm")).containsSame(voxcpm);
        assertThat(registry.byId("VoxCPM")).containsSame(voxcpm);
        assertThat(registry.selected("  voxcpm ")).containsSame(voxcpm);
        assertThat(registry.byId("unknown")).isEmpty();
        assertThat(registry.byId("")).isEmpty();
        assertThat(registry.byId(null)).isEmpty();
        assertThat(registry.all()).containsExactly(voxcpm);
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复 id（大小写无关）→ 启动即抛 IllegalStateException")
    void duplicateIdThrows() {
        assertThatThrownBy(() -> new NarrationEngineRegistry(List.of(engine("voxcpm"), engine("VoxCPM"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voxcpm");
    }

    @Test
    @DisplayName("空白 id → 启动即抛 IllegalStateException")
    void blankIdThrows() {
        assertThatThrownBy(() -> new NarrationEngineRegistry(List.of(engine("  "))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static NarrationVoiceEngine engine(String id) {
        NarrationVoiceEngine engine = mock(NarrationVoiceEngine.class);
        when(engine.id()).thenReturn(id);
        when(engine.supportedModes()).thenReturn(Set.of(NarrationVoiceMode.VOICE_DESIGN));
        return engine;
    }

    private static List<Throwable> failures(String action) {
        return List.of(
                new IllegalStateException("ordinary-" + action),
                new OutOfMemoryError("fatal-" + action),
                new ThreadDeath());
    }

    private static void throwPending(AtomicReference<Throwable> pending) {
        Throwable failure = pending.getAndSet(null);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
