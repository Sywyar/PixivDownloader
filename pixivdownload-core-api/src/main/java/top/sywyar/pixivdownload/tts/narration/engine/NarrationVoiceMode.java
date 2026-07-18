package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 多角色朗读的<b>合成模式</b>：显式表达「音色从哪来、情绪如何控制」，由调用方在合成请求处给出。
 * 引擎以 {@link NarrationVoiceEngine#supportedModes()} 声明自己能处理哪些模式；不支持的模式由派发器降级为
 * {@link #VOICE_DESIGN}。模式与「有没有参考音」不再隐式绑定，便于按各引擎能力精确路由。
 */
public enum NarrationVoiceMode {

    /**
     * 无参考音的<b>内联 voice-design</b>：音色（timbre）与风格都来自 {@code controlInstruction}；具体 wire
     * 表达由引擎实现决定。所有引擎都至少支持本模式。
     */
    VOICE_DESIGN,

    /**
     * <b>可控克隆</b>：音色来自参考音，并允许 {@code controlInstruction} 参与表达控制；具体请求格式和控制能力
     * 由引擎实现决定。
     */
    CLONE,

    /**
     * <b>高保真克隆</b>：参考音及其转录文本共同提供音色上下文；具体请求格式和控制优先级由引擎实现决定。
     * 缺少参考音转录文本时应回退到 {@link #CLONE}。
     */
    HIFI_CLONE
}
