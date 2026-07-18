package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 一次朗读合成所需的引擎中性请求。音色可以由文字描述表达，也可以由参考音表达；
 * 调用方负责在进入引擎边界前准备好相应素材。
 *
 * @param text               要朗读的文本
 * @param controlInstruction 音色描述，可为空
 * @param delivery           本次朗读的语气微调，可为空
 * @param referenceVoice     参考音素材，可为空
 */
public record NarrationVoiceRequest(
        String text,
        String controlInstruction,
        String delivery,
        NarrationReferenceVoice referenceVoice
) {

    /** 创建不带参考音和语气微调的请求。 */
    public static NarrationVoiceRequest of(String text, String controlInstruction) {
        return new NarrationVoiceRequest(text, controlInstruction, "", null);
    }

    /** 返回仅替换 {@link #text} 的副本。 */
    public NarrationVoiceRequest withText(String newText) {
        return new NarrationVoiceRequest(newText, controlInstruction, delivery, referenceVoice);
    }

    /** 是否携带可用的参考音（用于克隆）。 */
    public boolean hasReferenceVoice() {
        return referenceVoice != null && referenceVoice.hasAudio();
    }
}
