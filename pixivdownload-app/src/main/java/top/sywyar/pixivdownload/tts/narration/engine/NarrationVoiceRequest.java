package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 交给 {@link NarrationVoiceEngine} 合成<b>一句话</b>的纯值对象。它刻意只承载朗读所需的语言无关数据，
 * <b>不</b>依赖分析层（{@code ai.narration} / {@code tts.narration} 的脚本与花名册实体）或任何数据库类型——
 * 引擎因此与分析层、与彼此完全解耦，新增引擎不必触碰上游。
 *
 * <p>音色按<b>两条互斥路径</b>表达，引擎据 {@link #referenceVoice} 是否存在二选一：
 * <ul>
 *   <li><b>无参考音</b> → 内联 voice-design：用 {@link #controlInstruction}（角色基底画像 + 本句 delivery 合并串）
 *       按 {@code (描述)正文} 语法拼进合成文本（timbre 与情绪都来自该描述）；</li>
 *   <li><b>有参考音</b>（{@link #referenceVoice}）→ 可控克隆：timbre 来自参考音，括号里<b>只</b>放 {@link #delivery}
 *       （切勿再塞基底画像，否则与参考音的音色相互打架），并把参考音字节 + 转录作为克隆输入。</li>
 * </ul>
 * 其余字段（gender/age/localeHint）为将来的引擎（如把 gender/age + delivery 映射成 voice + rate/pitch 的 Edge
 * 引擎）预留。
 *
 * @param text               要朗读的原句文本（保持原文语言）
 * @param controlInstruction 已合成的音色描述（角色基底画像 + 本句 delivery，统一英文，可为空）；无参考音时用作内联 voice-design
 * @param delivery           本句情绪 / 语气微调（短英文，可为空）；有参考音时<b>只</b>用它作内联 {@code (style)}
 * @param gender             说话人性别（{@code male}/{@code female}/{@code unknown}，可为空）
 * @param age                说话人年龄段（{@code child}…{@code elderly}/{@code unknown}，可为空）
 * @param castId             所属花名册 id（信息性；参考音已在上游解析进 {@link #referenceVoice}，引擎无需再查库，可为 0）
 * @param characterId        花名册内说话人 id（0 为旁白）
 * @param localeHint         界面 / 文本语言提示（如 {@code zh-CN}，可为空；VoxCPM 内联不使用）
 * @param referenceVoice     参考音克隆素材（可为空）；非空且有音频时引擎走可控克隆
 */
public record NarrationVoiceRequest(
        String text,
        String controlInstruction,
        String delivery,
        String gender,
        String age,
        long castId,
        int characterId,
        String localeHint,
        NarrationReferenceVoice referenceVoice
) {

    /** 便捷构造：仅文本 + 音色描述（+ 可选 locale 提示），无参考音。用于联调 / 预览。 */
    public static NarrationVoiceRequest of(String text, String controlInstruction, String localeHint) {
        return new NarrationVoiceRequest(text, controlInstruction, "", "", "", 0L, 0, localeHint, null);
    }

    /** 返回仅替换 {@link #text} 的副本（record 不可变）：供派发器用归一后的文本重建请求。 */
    public NarrationVoiceRequest withText(String newText) {
        return new NarrationVoiceRequest(newText, controlInstruction, delivery,
                gender, age, castId, characterId, localeHint, referenceVoice);
    }

    /** 是否携带可用的参考音（用于克隆）。 */
    public boolean hasReferenceVoice() {
        return referenceVoice != null && referenceVoice.hasAudio();
    }
}
