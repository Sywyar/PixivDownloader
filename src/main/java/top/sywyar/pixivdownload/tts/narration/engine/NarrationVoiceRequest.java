package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 交给 {@link NarrationVoiceEngine} 合成<b>一句话</b>的纯值对象。它刻意只承载朗读所需的语言无关数据，
 * <b>不</b>依赖分析层（{@code ai.narration} / {@code tts.narration} 的脚本与花名册实体）或任何数据库类型——
 * 引擎因此与分析层、与彼此完全解耦，新增引擎不必触碰上游。
 *
 * <p>不同引擎按需取用：VoxCPM 的内联 voice-design 只用到 {@link #text} 与 {@link #controlInstruction}
 * （把后者按 {@code (描述)正文} 语法拼进合成文本）；其余字段为将来的引擎（如把 gender/age + delivery 映射成
 * voice + rate/pitch 的 Edge 引擎）预留。
 *
 * @param text               要朗读的原句文本（保持原文语言）
 * @param controlInstruction 已合成的音色描述（角色基底画像 + 本句 delivery，统一英文，可为空）
 * @param delivery           本句情绪 / 语气微调（短英文，可为空）
 * @param gender             说话人性别（{@code male}/{@code female}/{@code unknown}，可为空）
 * @param age                说话人年龄段（{@code child}…{@code elderly}/{@code unknown}，可为空）
 * @param castId             所属花名册 id（将来用于参考音克隆定位；本次未用，可为 0）
 * @param characterId        花名册内说话人 id（0 为旁白；将来用于参考音克隆定位）
 * @param localeHint         界面 / 文本语言提示（如 {@code zh-CN}，可为空；VoxCPM 内联不使用）
 */
public record NarrationVoiceRequest(
        String text,
        String controlInstruction,
        String delivery,
        String gender,
        String age,
        long castId,
        int characterId,
        String localeHint
) {

    /** 便捷构造：仅文本 + 音色描述（+ 可选 locale 提示），其余字段留空。用于联调 / 预览。 */
    public static NarrationVoiceRequest of(String text, String controlInstruction, String localeHint) {
        return new NarrationVoiceRequest(text, controlInstruction, "", "", "", 0L, 0, localeHint);
    }
}
