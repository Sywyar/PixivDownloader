package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 一名说话人的<b>参考音</b>（reference voice）：供 VoxCPM 等引擎做<b>音色克隆</b>用。承载原始音频字节、MIME 与
 * 可选的转录文本（{@code ref_text}）。这是一个<b>引擎无关的纯值对象</b>——参考音如何按 {@code (castId, characterId)}
 * 从磁盘 + 数据库解析出来，由引擎之外的组件完成，引擎只消费本对象，从而保持「引擎与持久化解耦、只认值对象」。
 *
 * <p>带参考音时引擎走<b>可控克隆</b>：音色（timbre）取自参考音，逐句情绪（delivery）仍由内联 {@code (style)} 控制；
 * {@code text} 作为 in-context 提示进一步提升克隆保真度。无参考音时引擎退回内联 voice-design。
 *
 * @param audio 参考音频字节（非空、非空数组由解析方保证）
 * @param mime  音频 MIME（如 {@code audio/wav} / {@code audio/mpeg}）
 * @param text  参考音对应的转录文本（{@code ref_text}，可为空）
 */
public record NarrationReferenceVoice(byte[] audio, String mime, String text) {

    /** 是否携带有效音频字节。 */
    public boolean hasAudio() {
        return audio != null && audio.length > 0;
    }
}
