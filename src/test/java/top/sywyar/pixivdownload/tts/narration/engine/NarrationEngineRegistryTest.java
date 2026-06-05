package top.sywyar.pixivdownload.tts.narration.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("朗读引擎注册工厂（唯一 id / byId / selected）")
class NarrationEngineRegistryTest {

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
}
