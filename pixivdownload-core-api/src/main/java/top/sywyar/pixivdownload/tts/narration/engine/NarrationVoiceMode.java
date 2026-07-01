package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 多角色朗读的<b>合成模式</b>：显式表达「音色从哪来、情绪如何控制」，由 {@code NarrationAudioService} 在调用处给出，
 * 引擎以 {@link NarrationVoiceEngine#supportedModes()} 声明自己能处理哪些模式（对齐 {@code PushChannel.supportedFormats()}
 * 惯例）；引擎不支持的模式由派发器降级为 {@link #VOICE_DESIGN}。模式与「有没有参考音」不再隐式绑定，便于后续接入
 * 更多富感情 TTS（MiMo / CosyVoice / Fish 等）时按各引擎能力精确路由。
 */
public enum NarrationVoiceMode {

    /**
     * 无参考音的<b>内联 voice-design</b>：音色（timbre）与风格都来自 {@code controlInstruction}（角色基底画像 + 本句
     * delivery 合并串），按引擎的 {@code (描述)正文} 语法拼进合成文本。所有引擎都至少支持本模式。
     */
    VOICE_DESIGN,

    /**
     * <b>可控克隆</b>（Controllable Cloning）：音色来自参考音，参考音<b>不带转录</b>，括号里只放 {@code delivery}
     * 控制逐句情绪。当前默认的克隆路径——既克隆音色又保住逐句情绪。
     */
    CLONE,

    /**
     * <b>Hi-Fi 续写</b>：音色来自参考音且<b>带转录</b>（{@code ref_text}），保真度最高，但<b>忽略</b> delivery 等控制指令
     * （由参考音 + 转录主导）。需要参考音确有转录文本时才可用，否则降回 {@link #CLONE}。
     */
    HIFI_CLONE
}
