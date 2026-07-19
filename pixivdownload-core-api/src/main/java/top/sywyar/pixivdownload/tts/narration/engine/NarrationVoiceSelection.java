package top.sywyar.pixivdownload.tts.narration.engine;

import java.util.Objects;

/**
 * 宿主已经按当前配置选中的朗读引擎快照。
 *
 * <p>{@code id} 由宿主在能力发布时捕获，消费者不得为了日志或路由重新调用可能已撤回的引擎代理。
 */
public record NarrationVoiceSelection(String id, NarrationVoiceEngine engine) {

    public NarrationVoiceSelection {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("narration voice selection id must not be blank");
        }
        id = id.trim();
        engine = Objects.requireNonNull(engine, "narration voice selection engine");
    }
}
